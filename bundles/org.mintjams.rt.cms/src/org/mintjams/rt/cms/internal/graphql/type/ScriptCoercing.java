/*
 * Copyright (c) 2022 MintJams Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.rt.cms.internal.graphql.type;

import java.util.Locale;
import java.util.Map;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import groovy.lang.Closure;

/**
 * A custom scalar whose coercing is defined by a script (declared in
 * {@code wiring.yml} under {@code scalars}). The script is evaluated once when
 * the schema is compiled and must return a map of one-argument closures:
 *
 * <ul>
 *   <li>{@code serialize}  — internal value &rarr; output value (server &rarr; client)</li>
 *   <li>{@code parseValue} — variable input &rarr; internal value (client &rarr; server)</li>
 *   <li>{@code parseLiteral} — inline-literal value &rarr; internal value (optional;
 *       defaults to {@code parseValue}). The closure receives the literal already
 *       converted to a plain Java value, so authors never touch the GraphQL AST.</li>
 * </ul>
 *
 * <p>Any missing closure defaults to identity. Because the closures are produced
 * at schema-compile time and reused for the schema's lifetime, scalar scripts
 * must be <em>pure value converters</em>: they should rely only on their argument
 * and the Groovy standard library, not on the per-request platform APIs
 * (no {@code repositorySession}, no caller identity).
 */
public final class ScriptCoercing implements Coercing<Object, Object> {

	private final String fName;
	private final Closure<?> fSerialize;
	private final Closure<?> fParseValue;
	private final Closure<?> fParseLiteral;

	private ScriptCoercing(String name, Closure<?> serialize, Closure<?> parseValue, Closure<?> parseLiteral) {
		fName = name;
		fSerialize = serialize;
		fParseValue = parseValue;
		fParseLiteral = parseLiteral;
	}

	/**
	 * Builds the scalar from a scalar script's result.
	 *
	 * @throws IllegalArgumentException if the result is not a map, or a mapped
	 *                                  value is not a closure.
	 */
	public static GraphQLScalarType newScalar(String name, Object scriptResult) {
		if (!(scriptResult instanceof Map)) {
			throw new IllegalArgumentException("Scalar script for \"" + name
					+ "\" must return a map of closures (serialize/parseValue/parseLiteral).");
		}
		Map<?, ?> map = (Map<?, ?>) scriptResult;
		return GraphQLScalarType.newScalar().name(name)
				.coercing(new ScriptCoercing(name, asClosure(name, map.get("serialize")),
						asClosure(name, map.get("parseValue")), asClosure(name, map.get("parseLiteral"))))
				.build();
	}

	private static Closure<?> asClosure(String name, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Closure) {
			return (Closure<?>) value;
		}
		throw new IllegalArgumentException("Scalar script for \"" + name
				+ "\" must map serialize/parseValue/parseLiteral to closures; got: " + value.getClass().getName());
	}

	@Override
	public Object serialize(Object dataFetcherResult, GraphQLContext context, Locale locale)
			throws CoercingSerializeException {
		if (dataFetcherResult == null || fSerialize == null) {
			return dataFetcherResult;
		}
		try {
			return fSerialize.call(dataFetcherResult);
		} catch (Exception ex) {
			throw new CoercingSerializeException("Scalar \"" + fName + "\" failed to serialize a value.", ex);
		}
	}

	@Override
	public Object parseValue(Object input, GraphQLContext context, Locale locale)
			throws CoercingParseValueException {
		if (input == null || fParseValue == null) {
			return input;
		}
		try {
			return fParseValue.call(input);
		} catch (Exception ex) {
			throw new CoercingParseValueException("Scalar \"" + fName + "\" failed to parse a value.", ex);
		}
	}

	@Override
	public Object parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale)
			throws CoercingParseLiteralException {
		Object value = ScalarTypes.literalToJava(input);
		Closure<?> closure = (fParseLiteral != null) ? fParseLiteral : fParseValue;
		if (value == null || closure == null) {
			return value;
		}
		try {
			return closure.call(value);
		} catch (Exception ex) {
			throw new CoercingParseLiteralException("Scalar \"" + fName + "\" failed to parse a literal.", ex);
		}
	}

}

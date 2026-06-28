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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;

/**
 * Custom GraphQL scalar implementations made available to application-defined
 * schemas. A schema only needs to declare the matching {@code scalar Foo} in
 * its SDL; {@link #forName(String)} supplies the coercing.
 *
 * <p>The names mirror the scalars used by the platform's own GraphQL contract
 * ({@code documents/GRAPHQL_SCHEMA.graphql}): {@code DateTime}, {@code Long},
 * {@code JSON}, {@code Base64}. Any other custom scalar a schema declares is
 * wired to a permissive pass-through ({@link #passThrough(String)}) so that a
 * schema author can introduce a scalar without having to register Java code —
 * values flow through unchanged in both directions.
 */
public final class ScalarTypes {

	private ScalarTypes() {}

	/**
	 * Returns the built-in coercing for one of the well-known scalar names, or
	 * {@code null} if the name is not one this class specialises (the caller
	 * then falls back to {@link #passThrough(String)}).
	 */
	public static GraphQLScalarType forName(String name) {
		if ("Long".equals(name)) {
			return longType();
		}
		if ("DateTime".equals(name) || "Base64".equals(name)) {
			// Carried as opaque strings: the platform formats DateTime as
			// ISO-8601 (UTC) and Base64 as standard base64 text already.
			return stringType(name);
		}
		if ("JSON".equals(name)) {
			return passThrough(name);
		}
		return null;
	}

	private static GraphQLScalarType longType() {
		return GraphQLScalarType.newScalar().name("Long").description("64-bit integer")
				.coercing(new Coercing<Long, Long>() {
					@Override
					public Long serialize(Object input, GraphQLContext context, Locale locale) {
						if (input instanceof Number) {
							return ((Number) input).longValue();
						}
						if (input instanceof String) {
							try {
								return Long.parseLong(((String) input).trim());
							} catch (NumberFormatException ignore) {}
						}
						return null;
					}

					@Override
					public Long parseValue(Object input, GraphQLContext context, Locale locale) {
						return serialize(input, context, locale);
					}

					@Override
					public Long parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
						if (input instanceof IntValue) {
							return ((IntValue) input).getValue().longValue();
						}
						if (input instanceof StringValue) {
							try {
								return Long.parseLong(((StringValue) input).getValue().trim());
							} catch (NumberFormatException ignore) {}
						}
						return null;
					}
				}).build();
	}

	private static GraphQLScalarType stringType(String name) {
		return GraphQLScalarType.newScalar().name(name)
				.coercing(new Coercing<String, String>() {
					@Override
					public String serialize(Object input, GraphQLContext context, Locale locale) {
						return (input == null) ? null : input.toString();
					}

					@Override
					public String parseValue(Object input, GraphQLContext context, Locale locale) {
						return (input == null) ? null : input.toString();
					}

					@Override
					public String parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
						if (input instanceof StringValue) {
							return ((StringValue) input).getValue();
						}
						return null;
					}
				}).build();
	}

	/**
	 * A scalar that lets arbitrary JSON-shaped values pass through unchanged.
	 * Output values are returned as-is for the JSON serialiser; input values
	 * (variables and inline literals) are converted to plain Java
	 * {@code Map}/{@code List}/scalar structures.
	 */
	public static GraphQLScalarType passThrough(String name) {
		return GraphQLScalarType.newScalar().name(name).description("Arbitrary JSON value")
				.coercing(new Coercing<Object, Object>() {
					@Override
					public Object serialize(Object input, GraphQLContext context, Locale locale) {
						return input;
					}

					@Override
					public Object parseValue(Object input, GraphQLContext context, Locale locale) {
						return input;
					}

					@Override
					public Object parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
						return literalToJava(input);
					}
				}).build();
	}

	static Object literalToJava(Value<?> value) {
		if (value == null || value instanceof NullValue) {
			return null;
		}
		if (value instanceof StringValue) {
			return ((StringValue) value).getValue();
		}
		if (value instanceof BooleanValue) {
			return ((BooleanValue) value).isValue();
		}
		if (value instanceof IntValue) {
			BigInteger v = ((IntValue) value).getValue();
			long l = v.longValue();
			if (BigInteger.valueOf(l).equals(v)) {
				return l;
			}
			return v;
		}
		if (value instanceof FloatValue) {
			BigDecimal v = ((FloatValue) value).getValue();
			return v.doubleValue();
		}
		if (value instanceof EnumValue) {
			return ((EnumValue) value).getName();
		}
		if (value instanceof ArrayValue) {
			List<Object> list = new ArrayList<>();
			for (Value<?> item : ((ArrayValue) value).getValues()) {
				list.add(literalToJava(item));
			}
			return list;
		}
		if (value instanceof ObjectValue) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (ObjectField field : ((ObjectValue) value).getObjectFields()) {
				map.put(field.getName(), literalToJava(field.getValue()));
			}
			return map;
		}
		return null;
	}

}

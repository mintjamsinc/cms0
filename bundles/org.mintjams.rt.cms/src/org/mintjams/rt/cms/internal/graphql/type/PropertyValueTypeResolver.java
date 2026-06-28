/*
 * Copyright (c) 2026 MintJams Inc.
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

import java.util.Map;

import graphql.TypeResolutionEnvironment;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import org.mintjams.rt.cms.internal.graphql.resolver.GroovyTypeResolver;

/**
 * Resolves the concrete object type of a {@code PropertyValue} union value by
 * reading the {@code __typename} discriminator that {@code NodeMapper} /
 * {@code PropertyValue} already embed in each property-value map (e.g.
 * {@code "StringPropertyValue"}, {@code "LongPropertyValueArray"},
 * {@code "BinaryPropertyValue"}). graphql-java requires a type resolver for every
 * abstract output type; this is the Java equivalent of {@link GroovyTypeResolver}
 * for the platform's built-in union.
 */
public final class PropertyValueTypeResolver implements TypeResolver {

	@Override
	public GraphQLObjectType getType(TypeResolutionEnvironment environment) {
		Object object = environment.getObject();
		if (object instanceof Map) {
			Object typename = ((Map<?, ?>) object).get("__typename");
			if (typename != null) {
				return environment.getSchema().getObjectType(typename.toString());
			}
		}
		return null;
	}

}

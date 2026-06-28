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

package org.mintjams.rt.cms.internal.graphql.wiring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import graphql.schema.DataFetcher;
import graphql.schema.GraphQLScalarType;
import graphql.schema.TypeResolver;
import org.mintjams.rt.cms.internal.graphql.engine.GraphQLSchemaCompiler;

/**
 * A bundle of programmatic GraphQL wiring — SDL text plus Java
 * {@link DataFetcher}s, {@link TypeResolver}s and custom scalars — produced by a
 * {@link WiringContributor} and merged into a workspace's schema by
 * {@link GraphQLSchemaCompiler}.
 *
 * <p>This is how the platform's built-in API is wired with Java resolvers
 * (reusing the existing executors), as opposed to the per-workspace, folder-based
 * Groovy resolvers. Built with a fluent API; not thread-safe — create one per
 * compile.
 *
 * <p>On conflict with folder/script wiring for the same {@code Type.field}, type
 * resolver, or scalar name, the folder/script wiring wins (mirroring the
 * "explicit wiring overrides convention" rule); contributor SDL is merged before
 * folder SDL so workspace files may {@code extend} contributor base types.
 */
public final class SchemaContribution {

	private final List<String> sdl = new ArrayList<>();
	// typeName -> (fieldName -> data fetcher)
	private final Map<String, Map<String, DataFetcher<?>>> dataFetchers = new LinkedHashMap<>();
	// typeName -> type resolver (interface/union)
	private final Map<String, TypeResolver> typeResolvers = new LinkedHashMap<>();
	// scalarName -> custom scalar
	private final Map<String, GraphQLScalarType> scalars = new LinkedHashMap<>();

	/** Adds one SDL fragment (schema definition language text). Blank input is ignored. */
	public SchemaContribution sdl(String sdl) {
		if (sdl != null && !sdl.isBlank()) {
			this.sdl.add(sdl);
		}
		return this;
	}

	/** Registers a Java data fetcher for {@code typeName.fieldName}. */
	public SchemaContribution dataFetcher(String typeName, String fieldName, DataFetcher<?> dataFetcher) {
		dataFetchers.computeIfAbsent(typeName, k -> new LinkedHashMap<>()).put(fieldName, dataFetcher);
		return this;
	}

	/** Registers a type resolver for an {@code interface}/{@code union} type. */
	public SchemaContribution typeResolver(String typeName, TypeResolver typeResolver) {
		typeResolvers.put(typeName, typeResolver);
		return this;
	}

	/** Registers a custom scalar. Its name (as declared in the SDL) is taken from the scalar. */
	public SchemaContribution scalar(GraphQLScalarType scalar) {
		scalars.put(scalar.getName(), scalar);
		return this;
	}

	public List<String> getSdl() {
		return Collections.unmodifiableList(sdl);
	}

	public Map<String, Map<String, DataFetcher<?>>> getDataFetchers() {
		return Collections.unmodifiableMap(dataFetchers);
	}

	public Map<String, TypeResolver> getTypeResolvers() {
		return Collections.unmodifiableMap(typeResolvers);
	}

	public Map<String, GraphQLScalarType> getScalars() {
		return Collections.unmodifiableMap(scalars);
	}

}

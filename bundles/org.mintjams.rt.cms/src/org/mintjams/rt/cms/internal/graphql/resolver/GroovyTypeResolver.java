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

package org.mintjams.rt.cms.internal.graphql.resolver;

import java.util.HashMap;
import java.util.Map;

import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;

import graphql.TypeResolutionEnvironment;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;

/**
 * A {@link TypeResolver} for an {@code interface}/{@code union} type, backed by
 * a server-side script declared in {@code wiring.yml} ({@code typeResolver}).
 * graphql-java requires a type resolver for every abstract output type to pick
 * the concrete object type of a resolved value — something the per-field
 * {@code <Type>.<field>.<ext>} filename convention cannot express, which is the
 * main reason {@code wiring.yml} exists.
 *
 * <p>The script receives the value being resolved and returns the concrete type.
 * Bindings:
 * <ul>
 *   <li>{@code object} / {@code source} — the value whose type must be decided</li>
 *   <li>{@code args} — the field's arguments</li>
 *   <li>{@code env} — the {@link TypeResolutionEnvironment}</li>
 * </ul>
 *
 * <p>The script returns either the concrete type's name ({@code String}) — the
 * common case, e.g. {@code object.kind == 'BOOK' ? 'Book' : 'Movie'} — or a
 * {@link GraphQLObjectType} directly.
 */
public class GroovyTypeResolver implements TypeResolver {

	private final String fTypeName;
	private final String fScriptPath;
	private final ResolverScript fScript;

	public GroovyTypeResolver(String workspaceName, String typeName, String scriptPath, String extension,
			String source, String runAs) {
		fTypeName = typeName;
		fScriptPath = scriptPath;
		fScript = new ResolverScript(workspaceName, scriptPath, extension, source, runAs);
	}

	@Override
	public GraphQLObjectType getType(TypeResolutionEnvironment environment) {
		WorkspaceScriptContext sharedContext = environment.getGraphQLContext().get(GroovyDataFetcher.CTX_SCRIPT_CONTEXT);

		Object object = environment.getObject();
		Map<String, Object> bindings = new HashMap<>();
		bindings.put("env", environment);
		bindings.put("object", object);
		bindings.put("source", object);
		bindings.put("args", environment.getArguments());

		Object result;
		try {
			result = fScript.eval(sharedContext, bindings);
		} catch (Exception ex) {
			throw new IllegalStateException("Type resolver failed for \"" + fTypeName + "\": " + fScriptPath, ex);
		}

		if (result == null) {
			return null;
		}
		if (result instanceof GraphQLObjectType) {
			return (GraphQLObjectType) result;
		}
		return environment.getSchema().getObjectType(result.toString());
	}

	public String getTypeName() {
		return fTypeName;
	}

	public String getScriptPath() {
		return fScriptPath;
	}

}

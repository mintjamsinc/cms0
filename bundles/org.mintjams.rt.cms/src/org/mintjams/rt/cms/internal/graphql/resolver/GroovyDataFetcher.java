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

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

/**
 * A {@link DataFetcher} that resolves a single schema field by evaluating a
 * server-side script (typically Groovy) authored into the workspace's GraphQL
 * folders.
 *
 * <p>The script source is captured when the schema is compiled (read with the
 * engine's service session, so the script files themselves can be ACL-protected
 * from end users). At request time the script is evaluated through
 * {@link ResolverScript}: by default in the shared {@link WorkspaceScriptContext}
 * created for the request (the caller's JCR session, so repository ACLs govern
 * data access), or — when the wiring declares {@code runAs} — under that
 * principal's service identity.
 *
 * <p>Bindings visible to the script:
 * <ul>
 *   <li>{@code env} — the {@link DataFetchingEnvironment}</li>
 *   <li>{@code args} — the field's arguments ({@code Map<String,Object>})</li>
 *   <li>{@code source} / {@code parent} — the parent object being resolved</li>
 *   <li>the standard scripting APIs prepared by {@code Scripts.prepareAPIs(...)}
 *       (e.g. {@code SessionAPI}, {@code repositorySession}, {@code JSON},
 *       {@code log})</li>
 * </ul>
 *
 * <p>The script's value is its last evaluated expression. Return a
 * {@code Map}/{@code List}/scalar; nested object fields without their own
 * resolver are then resolved by graphql-java's default property fetcher (map
 * key or bean property lookup).
 */
public class GroovyDataFetcher implements DataFetcher<Object> {

	/** GraphQLContext key under which the per-request script context is shared. */
	public static final String CTX_SCRIPT_CONTEXT = "org.mintjams.appql.scriptContext";

	private final String fTypeName;
	private final String fFieldName;
	private final String fScriptPath;
	private final ResolverScript fScript;

	public GroovyDataFetcher(String workspaceName, String typeName, String fieldName, String scriptPath,
			String extension, String source, String runAs) {
		fTypeName = typeName;
		fFieldName = fieldName;
		fScriptPath = scriptPath;
		fScript = new ResolverScript(workspaceName, scriptPath, extension, source, runAs);
	}

	@Override
	public Object get(DataFetchingEnvironment environment) throws Exception {
		WorkspaceScriptContext sharedContext = environment.getGraphQlContext().get(CTX_SCRIPT_CONTEXT);

		Object parent = environment.getSource();
		Map<String, Object> bindings = new HashMap<>();
		bindings.put("env", environment);
		bindings.put("args", environment.getArguments());
		bindings.put("source", parent);
		bindings.put("parent", parent);

		try {
			return fScript.eval(sharedContext, bindings);
		} catch (Throwable ex) {
			// Tag the failure as application-resolver-originated so the unified
			// engine's exception handler surfaces the script's own message to the
			// client (trusted author code) instead of sanitizing it as a platform
			// internal error. See AppResolverException.
			throw new AppResolverException(ex);
		}
	}

	public String getTypeName() {
		return fTypeName;
	}

	public String getFieldName() {
		return fFieldName;
	}

	public String getScriptPath() {
		return fScriptPath;
	}

}

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

package org.mintjams.rt.cms.internal.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;
import javax.script.ScriptException;

import org.mintjams.rt.cms.internal.WorkspaceUserHomes;
import org.mintjams.rt.cms.internal.graphql.resolver.ResolverScript;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.util.ISO8601;

import com.google.gson.JsonObject;

/**
 * A workspace-defined tool that is a script, run the way a GraphQL resolver
 * is: as the caller, with the platform APIs of a script context, compiled
 * once per class-loader generation.
 *
 * <p>Bindings: {@code args} (the arguments, as a map), {@code graphql} (the
 * workspace schema, see {@link McpScriptGraphQL}), {@code mcp} (the
 * {@link McpCallContext}: who is calling and whether the connection may
 * write), and everything {@code Scripts.prepareAPIs} provides ({@code log},
 * {@code ScriptAPI}, {@code ProcessAPI}, …). What the script returns is the
 * result: a string as text, anything else as JSON.
 */
final class ScriptToolHandler implements McpTool.Handler {

	private final String fWorkspaceName;
	private final String fScriptPath;
	private final ResolverScript fScript;

	ScriptToolHandler(String workspaceName, String scriptPath, String extension, String source) {
		fWorkspaceName = workspaceName;
		fScriptPath = scriptPath;
		fScript = new ResolverScript(workspaceName, scriptPath, extension, source, null);
	}

	String getScriptPath() {
		return fScriptPath;
	}

	@Override
	public McpToolResult call(JsonObject arguments, McpCallContext context) throws Exception {
		WorkspaceScriptContext scriptContext = new WorkspaceScriptContext(fWorkspaceName);
		try {
			scriptContext.setCredentials(context.getCredentials());
			Scripts.prepareAPIs(scriptContext);
			WorkspaceUserHomes.ensureUserHome(scriptContext.adaptTo(Session.class));
			Map<String, Object> bindings = new HashMap<>();
			bindings.put("args", (arguments == null) ? new LinkedHashMap<>() : ContentTools.toJava(arguments));
			bindings.put("graphql", new McpScriptGraphQL(context));
			bindings.put("mcp", context);
			Object result = fScript.eval(scriptContext, bindings);
			return toResult(result);
		} catch (Throwable ex) {
			Throwable cause = unwrap(ex);
			if (cause instanceof McpToolException) {
				throw (McpToolException) cause;
			}
			throw new McpToolException(McpServer.describe(cause), cause);
		} finally {
			try {
				scriptContext.close();
			} catch (Throwable ignore) {}
		}
	}

	private static McpToolResult toResult(Object value) {
		if (value instanceof McpToolResult) {
			return (McpToolResult) value;
		}
		if (value instanceof CharSequence) {
			return McpToolResult.text(value.toString());
		}
		return McpToolResult.json(normalize(value));
	}

	/** Script values as plain JSON-ready Java: maps, lists, primitives and ISO 8601 times. */
	static Object normalize(Object value) {
		if (value == null || value instanceof Boolean || value instanceof Number) {
			return value;
		}
		if (value instanceof CharSequence) {
			return value.toString();
		}
		if (value instanceof Map) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
				map.put(String.valueOf(e.getKey()), normalize(e.getValue()));
			}
			return map;
		}
		if (value instanceof Collection) {
			List<Object> list = new ArrayList<>();
			for (Object item : (Collection<?>) value) {
				list.add(normalize(item));
			}
			return list;
		}
		if (value instanceof Object[]) {
			List<Object> list = new ArrayList<>();
			for (Object item : (Object[]) value) {
				list.add(normalize(item));
			}
			return list;
		}
		if (value instanceof Calendar) {
			return ISO8601.format(((Calendar) value).toInstant());
		}
		if (value instanceof Date) {
			return ISO8601.format(((Date) value).toInstant());
		}
		if (value instanceof Instant) {
			return ISO8601.format((Instant) value);
		}
		return value.toString();
	}

	/** The failure a script reported, without the script engine's wrapping. */
	private static Throwable unwrap(Throwable ex) {
		Throwable current = ex;
		while (current instanceof ScriptException && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}
}

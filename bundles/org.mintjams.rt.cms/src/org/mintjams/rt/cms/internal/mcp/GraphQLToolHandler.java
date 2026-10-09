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

import java.util.Collections;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * A workspace-defined tool that is one GraphQL operation: the tool's
 * arguments are the operation's variables, by name, and the result is the
 * operation's {@code data}.
 *
 * <p>Whether the operation may run on a read-only connection was settled when
 * the tool was deployed: a mutation is a write tool, which {@link McpServer}
 * refuses for such a connection before this handler is reached.
 */
final class GraphQLToolHandler implements McpTool.Handler {

	private final String fQuery;
	private final String fOperationName;

	GraphQLToolHandler(String query, String operationName) {
		fQuery = query;
		fOperationName = operationName;
	}

	@Override
	public McpToolResult call(JsonObject arguments, McpCallContext context) throws Exception {
		@SuppressWarnings("unchecked")
		Map<String, Object> variables = (arguments == null) ? Collections.emptyMap()
				: (Map<String, Object>) ContentTools.toJava(arguments);
		Map<String, Object> response = context.executeGraphQL(fQuery, fOperationName, variables);
		String errors = McpGraphQL.errorMessages(response);
		if (errors != null && response.get("data") == null) {
			return McpToolResult.error(errors);
		}
		// Partial data comes back with its errors, for the model to weigh.
		return McpToolResult.json((errors == null) ? response.get("data") : response);
	}
}

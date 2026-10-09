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

/**
 * The {@code graphql} binding of a script tool: runs an operation of the
 * workspace schema as the caller, with the same limits as the {@code graphql}
 * tool. A read-only connection cannot run a mutation through a script
 * either, whatever the tool's own access says.
 *
 * <pre>
 * def room = graphql.data('query ($id: ID!) { reversiRoom(id: $id) { size moves } }', [id: args.id]).reversiRoom;
 * </pre>
 */
public final class McpScriptGraphQL {

	private final McpCallContext fContext;

	McpScriptGraphQL(McpCallContext context) {
		fContext = context;
	}

	/** Runs the operation and returns the whole response ({@code data}, {@code errors}). */
	public Map<String, Object> execute(String query) throws McpToolException {
		return execute(query, null, null);
	}

	public Map<String, Object> execute(String query, Map<String, Object> variables) throws McpToolException {
		return execute(query, null, variables);
	}

	public Map<String, Object> execute(String query, String operationName, Map<String, Object> variables)
			throws McpToolException {
		if (query == null || query.trim().isEmpty()) {
			throw new McpToolException("The GraphQL document is empty.");
		}
		McpGraphQL.OperationKind kind = McpGraphQL.operationKind(query, operationName);
		if (kind == McpGraphQL.OperationKind.SUBSCRIPTION) {
			throw new McpToolException("Subscriptions are not supported in a tool.");
		}
		if (!fContext.canWrite() && kind != McpGraphQL.OperationKind.QUERY) {
			throw new McpToolException("This connection is read-only, so the tool cannot run an operation that is"
					+ " not a query: " + ((operationName != null) ? operationName : "(anonymous)"));
		}
		return fContext.executeGraphQL(query, operationName,
				(variables == null) ? Collections.emptyMap() : variables);
	}

	/** Runs the operation and returns its {@code data}; an operation that returns no data fails. */
	public Object data(String query) throws McpToolException {
		return data(query, null, null);
	}

	public Object data(String query, Map<String, Object> variables) throws McpToolException {
		return data(query, null, variables);
	}

	public Object data(String query, String operationName, Map<String, Object> variables) throws McpToolException {
		Map<String, Object> response = execute(query, operationName, variables);
		String errors = McpGraphQL.errorMessages(response);
		if (errors != null && response.get("data") == null) {
			throw new McpToolException(errors);
		}
		return response.get("data");
	}
}

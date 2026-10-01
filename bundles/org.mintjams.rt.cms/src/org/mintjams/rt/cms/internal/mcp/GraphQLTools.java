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
import java.util.List;
import java.util.Map;

import org.mintjams.rt.cms.internal.mcp.McpTool.Args;
import org.mintjams.rt.cms.internal.mcp.McpTool.Schema;

import com.google.gson.JsonElement;

/**
 * {@code graphql}: the workspace's whole GraphQL schema as one tool.
 *
 * <p>The dedicated tools cover content. Everything else the platform exposes —
 * access control, versioning operations, processes and tasks (BPM),
 * integration routes (EIP), the identity store, and the schema a workspace
 * defines for its own applications — is already reachable through GraphQL, with
 * authorization enforced by the resolvers. Rather than mirror each field as a
 * tool, this one hands the model the schema itself: it can introspect, then
 * query.
 *
 * <p>The scope still holds. A read-scoped caller may only run an operation
 * that is <em>provably</em> a query: a mutation, a subscription, or a document
 * whose operation cannot be determined is refused before execution. This is as
 * strong as the schema's own convention that queries do not change state;
 * application-defined resolvers are trusted to follow it.
 *
 * <p>Subscriptions are refused for every caller: a tool call returns once, and
 * the server holds no stream open for an MCP client.
 */
final class GraphQLTools {

	private GraphQLTools() {}

	static List<McpTool> all() {
		return Collections.singletonList(graphql());
	}

	private static McpTool graphql() {
		// Listed for read-scoped callers too, so that they can query; the
		// handler enforces the scope per operation.
		return McpTool.named("graphql").title("GraphQL").writesWhenAllowed()
				.description("Run a GraphQL operation against the workspace schema, for anything the other tools do",
						"not cover: access control, locks, check-in and check-out, processes and tasks (BPM),",
						"integration routes (EIP), users and groups, and application-defined types. Discover what",
						"exists with introspection first, e.g. { __schema { queryType { fields { name description",
						"} } } } or { __type(name: \"Mutation\") { fields { name description args { name type {",
						"name kind ofType { name } } } } } }. Mutations need a connection with the write scope and",
						"change live data: confirm with the user first. Subscriptions are not supported. Prefer the",
						"dedicated tools for files and folders.")
				.required("query", Schema.string("The GraphQL document"))
				.optional("variables", Schema.object("Variables for the operation"))
				.optional("operationName",
						Schema.string("Which operation to run, when the document defines several"))
				.handler((arguments, context) -> {
					String query = Args.string(arguments, "query");
					String operationName = Args.optString(arguments, "operationName", null);
					JsonElement variablesElement = arguments.get("variables");
					if (variablesElement != null && !variablesElement.isJsonNull()
							&& !variablesElement.isJsonObject()) {
						throw new McpToolException("variables must be an object.");
					}

					McpGraphQL.OperationKind kind = McpGraphQL.operationKind(query, operationName);
					if (kind == McpGraphQL.OperationKind.SUBSCRIPTION) {
						throw new McpToolException("Subscriptions are not supported over MCP. Query the current"
								+ " state instead.");
					}
					if (!context.canWrite() && kind != McpGraphQL.OperationKind.QUERY) {
						if (kind == McpGraphQL.OperationKind.MUTATION) {
							throw new McpToolException("This connection is read-only, so mutations are refused."
									+ " Use an access token with the write scope.");
						}
						throw new McpToolException("This connection is read-only and the operation could not be"
								+ " identified as a query. Send a document that parses and contains a single"
								+ " query, or name the query with operationName.");
					}

					@SuppressWarnings("unchecked")
					Map<String, Object> variables = (variablesElement != null && variablesElement.isJsonObject())
							? (Map<String, Object>) ContentTools.toJava(variablesElement)
							: Collections.emptyMap();
					Map<String, Object> response = context.executeGraphQL(query, operationName, variables);

					if (kind == McpGraphQL.OperationKind.MUTATION) {
						org.mintjams.rt.cms.internal.CmsService.getLogger(GraphQLTools.class)
								.info("MCP graphql mutation: user=" + context.getUserId() + " workspace="
										+ context.getWorkspaceName() + " operation="
										+ (operationName != null ? operationName : "(anonymous)"));
					}

					// A response with errors and no data is a failed call; partial
					// data is returned as it is, errors included, for the model to weigh.
					if (McpGraphQL.errorMessages(response) != null && response.get("data") == null) {
						return McpToolResult.error(McpGraphQL.errorMessages(response));
					}
					return McpToolResult.json(response);
				}).build();
	}

}

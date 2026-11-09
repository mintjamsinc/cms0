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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.engine.GraphQLSchemaDomain;
import org.mintjams.rt.cms.internal.mcp.McpTool.Args;
import org.mintjams.rt.cms.internal.mcp.McpTool.Schema;

import com.google.gson.JsonElement;

/**
 * {@code graphql_schema} and {@code graphql}: the workspace's whole GraphQL
 * schema as two tools.
 *
 * <p>The dedicated tools cover content. Everything else the platform exposes —
 * access control, versioning operations, processes and tasks (BPM),
 * integration routes (EIP), the identity store, and the schema a workspace
 * defines for its own applications — is already reachable through GraphQL, with
 * authorization enforced by the resolvers. Rather than mirror each field as a
 * tool, these hand the model the schema itself.
 *
 * <p>Not by introspection first, though. The schema is compiled as one unit
 * but written in parts ({@link GraphQLSchemaDomain}), each about one thing and
 * small enough to read whole, with the comments its authors wrote;
 * {@code graphql_schema} lists the parts with their root fields and returns
 * one part as written. A whole-schema introspection is several times larger
 * and says nothing a comment would.
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
		return List.of(graphqlSchema(), graphql());
	}

	private static McpTool graphqlSchema() {
		return McpTool.named("graphql_schema").title("GraphQL schema")
				.description("The workspace GraphQL schema, part by part, as its authors wrote it, comments",
						"included. Without arguments: the parts, each with one line on what it covers and the",
						"names of its query and mutation root fields (the functions a graphql call names). With",
						"domain: that part's definitions in schema definition language. Read the part you need",
						"before composing a graphql call: a part is a few thousand characters, where introspecting",
						"the whole schema is hundreds of thousands and carries no comments.")
				.optional("domain", Schema.string("The name of a part, as the listing gives it (e.g. bpm, idp,"
						+ " site). Omit for the listing."))
				.handler((arguments, context) -> {
					String domain = Args.optString(arguments, "domain", null);
					List<GraphQLSchemaDomain> domains = context.getSchemaDomains();
					if (domains.isEmpty()) {
						throw new McpToolException("The GraphQL schema is not available for the workspace: "
								+ context.getWorkspaceName());
					}
					if (domain == null || domain.isEmpty()) {
						List<Map<String, Object>> parts = new ArrayList<>();
						for (GraphQLSchemaDomain part : domains) {
							Map<String, Object> entry = new LinkedHashMap<>();
							entry.put("domain", part.getName());
							entry.put("description", part.getDescription());
							entry.put("platform", part.isPlatform());
							entry.put("queries", part.getQueryFields());
							entry.put("mutations", part.getMutationFields());
							entry.put("characters", part.getSdl().length());
							parts.add(entry);
						}
						Map<String, Object> result = new LinkedHashMap<>();
						result.put("domains", parts);
						result.put("note", "Call again with domain to read one part. Every part extends the same"
								+ " Query and Mutation types and shares the platform part's scalars.");
						return McpToolResult.json(result);
					}
					for (GraphQLSchemaDomain part : domains) {
						if (part.getName().equals(domain)) {
							return McpToolResult.text(part.getSdl());
						}
					}
					List<String> names = new ArrayList<>();
					for (GraphQLSchemaDomain part : domains) {
						names.add(part.getName());
					}
					throw new McpToolException("There is no part named " + domain + ". The parts are: "
							+ String.join(", ", names) + ".");
				}).build();
	}

	private static McpTool graphql() {
		// Listed for read-scoped callers too, so that they can query; the
		// handler enforces the scope per operation.
		return McpTool.named("graphql").title("GraphQL").writesWhenAllowed()
				.description("Run a GraphQL operation against the workspace schema, for anything the other tools do",
						"not cover: access control, locks, check-in and check-out, processes and tasks (BPM),",
						"integration routes (EIP), users and groups, and application-defined types. Find the fields",
						"with graphql_schema first: the parts of the schema and their root fields, then one part's",
						"definitions. Introspection works too but is many times larger. Mutations need a connection",
						"with the write scope and change live data: confirm with the user first. Subscriptions are",
						"not supported. Prefer the dedicated tools for files and folders.")
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
						// Every change made through MCP is logged with who made
						// it; for a mutation, the root fields say what was done.
						CmsService.getLogger(GraphQLTools.class)
								.info("MCP graphql mutation: user=" + context.getUserId() + " workspace="
										+ context.getWorkspaceName() + " operation="
										+ (operationName != null ? operationName : "(anonymous)") + " fields="
										+ String.join(",", McpGraphQL.rootFields(query, operationName)));
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

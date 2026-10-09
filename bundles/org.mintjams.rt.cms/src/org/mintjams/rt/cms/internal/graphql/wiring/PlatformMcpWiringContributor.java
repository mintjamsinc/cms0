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


package org.mintjams.rt.cms.internal.graphql.wiring;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;
import org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider;
import org.mintjams.rt.cms.internal.mcp.McpConfiguration;
import org.mintjams.rt.cms.internal.mcp.McpConnections;
import org.mintjams.rt.cms.internal.mcp.McpToolRegistry;
import org.mintjams.rt.cms.internal.util.ISO8601;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

/**
 * Wires the MCP part of the platform schema ({@code mcp-schema.graphqls}): the
 * caller's MCP connection to the workspace, which Preferences turns on and
 * off.
 *
 * <p>A user manages only their own connection — there is no argument naming
 * another user — and only a signed-in user has one: a guest, or a service
 * session, is told the connection is unavailable.
 */
public final class PlatformMcpWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/mcp-schema.graphqls";

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "mcpConnection", (DataFetcher<Object>) PlatformMcpWiringContributor::mcpConnection)
				.dataFetcher("Query", "mcpToolDeployments",
						(DataFetcher<Object>) PlatformMcpWiringContributor::mcpToolDeployments)
				.dataFetcher("Mutation", "setMcpConnection",
						(DataFetcher<Object>) PlatformMcpWiringContributor::setMcpConnection);
	}

	private static Object mcpConnection(DataFetchingEnvironment environment) throws Exception {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		McpConfiguration config = McpConfiguration.get();
		if (!isUser(context.getCallerSession())) {
			return map(context.getWorkspaceName(), config, false, null);
		}
		return map(context.getWorkspaceName(), config, config.isEnabled(),
				McpConnections.get(context.getWorkspaceName(), context.getCallerSession().getUserID()));
	}

	private static Object setMcpConnection(DataFetchingEnvironment environment) throws Exception {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		Map<String, Object> input = environment.getArgument("input");
		boolean enabled = Boolean.TRUE.equals(input.get("enabled"));
		boolean write = Boolean.TRUE.equals(input.get("write"));

		Session session = context.getCallerSession();
		if (!isUser(session)) {
			throw new IllegalStateException("Only a signed-in user has an MCP connection.");
		}
		McpConfiguration config = McpConfiguration.get();
		if (enabled && !config.isEnabled()) {
			throw new IllegalStateException("The MCP server is disabled on this server.");
		}

		McpConnections.Connection connection = McpConnections.set(context.getWorkspaceName(), session.getUserID(),
				enabled, write, System.currentTimeMillis());
		CmsService.getLogger(PlatformMcpWiringContributor.class).info("MCP connection turned "
				+ (enabled ? "on" : "off") + ": user=" + session.getUserID() + " workspace="
				+ context.getWorkspaceName() + (enabled ? " write=" + connection.isWrite() : ""));
		return map(context.getWorkspaceName(), config, config.isEnabled(), connection);
	}

	private static Object mcpToolDeployments(DataFetchingEnvironment environment) throws Exception {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		WorkspaceGraphQLEngineProvider engine = CmsService
				.getWorkspaceGraphQLEngineProvider(context.getWorkspaceName());
		List<Map<String, Object>> deployments = new ArrayList<>();
		if (engine == null) {
			return deployments;
		}
		for (McpToolRegistry.Deployment deployment : engine.getMcpTools().getDeployments().values()) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("path", deployment.getPath());
			d.put("deployedAt", ISO8601.format(Instant.ofEpochMilli(deployment.getDeployedAtMillis())));
			d.put("stale", deployment.isStale());
			List<Map<String, Object>> tools = new ArrayList<>();
			for (McpToolRegistry.Entry entry : deployment.getEntries()) {
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("name", entry.getTool().getName());
				t.put("title", entry.getTool().getTitle());
				t.put("kind", entry.getKind());
				t.put("access", entry.getAccess());
				t.put("enabled", entry.isEnabled());
				tools.add(t);
			}
			d.put("tools", tools);
			d.put("problems", deployment.getProblems());
			deployments.add(d);
		}
		return deployments;
	}

	private static boolean isUser(Session session) {
		org.mintjams.jcr.Session jcrSession = org.mintjams.jcr.Session.class.cast(session);
		return !(jcrSession.isGuest() || jcrSession.isAnonymous() || jcrSession.isSystem() || jcrSession.isService());
	}

	private static Map<String, Object> map(String workspaceName, McpConfiguration config, boolean available,
			McpConnections.Connection connection) {
		boolean enabled = available && connection != null && connection.isEnabled();
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("available", available);
		map.put("enabled", enabled);
		map.put("write", enabled && connection.isWrite());
		map.put("writeAllowed", config.isWriteAllowed());
		map.put("enabledAt", enabled ? ISO8601.format(Instant.ofEpochMilli(connection.getEnabledAtMillis())) : null);
		map.put("serverName", config.getServerName());
		map.put("endpointPath", CmsConfiguration.MCP_CGI_PATH + "/" + workspaceName);
		List<Map<String, Object>> clients = new ArrayList<>();
		if (enabled) {
			for (McpConnections.Client client : connection.getClients()) {
				Map<String, Object> c = new LinkedHashMap<>();
				c.put("name", client.getName());
				c.put("redirectTarget", client.getRedirectTarget());
				c.put("authorizedAt", ISO8601.format(Instant.ofEpochMilli(client.getAuthorizedAtMillis())));
				clients.add(c);
			}
		}
		map.put("clients", clients);
		return map;
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformMcpWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("MCP GraphQL schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}

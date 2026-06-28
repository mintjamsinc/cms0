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

package org.mintjams.rt.cms.internal.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import javax.jcr.Credentials;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider;
import org.mintjams.rt.cms.internal.graphql.GraphQLRequest;
import org.mintjams.rt.cms.internal.graphql.GraphQLRequestParser;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Servlet for the unified GraphQL API on graphql-java. Sole endpoint:
 * {@code /bin/graphql.cgi/{workspace}}.
 *
 * <p>It serves the per-workspace schema compiled by
 * {@link WorkspaceGraphQLEngineProvider}, which merges the platform's built-in
 * schema (Java resolvers) with the workspace's application-defined SDL/Groovy
 * resolvers — so a single endpoint exposes both. This consolidates the former
 * {@code /bin/pgraphql.cgi} (platform) and {@code /bin/appql.cgi} (application)
 * endpoints, both now removed; the handmade engine remains retired to
 * {@code /bin/graphql-legacy.cgi} for rollback.
 *
 * <p>Requests carry the standard GraphQL-over-HTTP JSON envelope and the response
 * is the GraphQL-spec {@code data}/{@code errors} map; authorization is delegated
 * to the resolvers and JCR ACLs. Subscriptions use the SSE transport at
 * {@code /bin/graphql.cgi/{workspace}/stream} (see {@link GraphQLStreamHandler}).
 */
@Component(service = Servlet.class, property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + CmsConfiguration.GRAPHQL_CGI_PATH + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
				+ HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=org.osgi.service.http)",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ASYNC_SUPPORTED + "=true" })
public class GraphQLServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	// serializeNulls(): preserve explicit nulls so clients can observe nullable
	// fields transitioning to null (matches the built-in GraphQLServlet).
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handleRequest(request, response);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handleRequest(request, response);
	}

	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handleRequest(request, response);
	}

	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handleRequest(request, response);
	}

	private void handleRequest(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			String workspaceName = getWorkspaceName(request);
			if (workspaceName == null || workspaceName.isEmpty()) {
				sendError(response, "Workspace name must be specified");
				return;
			}

			// Subscriptions: /bin/graphql.cgi/{workspace}/stream speaks graphql-sse in
			// single connection mode — one SSE multiplexes all of a client's operations.
			// PUT reserves a token, GET (EventSource) opens the one stream, POST adds an
			// operation, DELETE stops one. The subscriber's session is not held; payload
			// mappers read JCR on demand.
			if (isStreamRequest(request)) {
				new GraphQLStreamHandler(workspaceName).handle(request, response, getCredentials(request));
				return;
			}

			// Non-stream requests are GraphQL query/mutation over HTTP (GET/POST only).
			String method = request.getMethod();
			if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
				sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
						"Method not allowed");
				return;
			}

			WorkspaceGraphQLEngineProvider engine = CmsService.getWorkspaceGraphQLEngineProvider(workspaceName);
			if (engine == null) {
				sendError(response, "Unknown or stopped workspace: " + workspaceName);
				return;
			}
			if (!engine.isAvailable()) {
				sendError(response, "The GraphQL schema is not available for the workspace: " + workspaceName);
				return;
			}

			GraphQLRequest graphQLRequest;
			if ("POST".equalsIgnoreCase(request.getMethod())) {
				graphQLRequest = GraphQLRequestParser.parse(request.getInputStream());
			} else {
				String query = request.getParameter("query");
				String operationName = request.getParameter("operationName");
				String variables = request.getParameter("variables");
				graphQLRequest = GraphQLRequestParser.parseFromQueryParams(query, operationName, variables);

				// GraphQL-over-HTTP: GET is for queries only — mutations must use POST,
				// so a mutation cannot be triggered by a plain link / cross-site GET.
				if (engine.isMutationOperation(graphQLRequest)) {
					sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
							"Mutations must be sent via HTTP POST");
					return;
				}
			}

			Credentials credentials = getCredentials(request);
			Map<String, Object> result = engine.execute(graphQLRequest, credentials);
			sendResponse(response, result);
		} catch (Throwable e) {
			CmsService.getLogger(getClass()).error("Platform GraphQL execution failed", e);
			sendError(response, "Internal server error: " + e.getMessage());
		}
	}

	/**
	 * Extract workspace name from URL. Example: {@code /bin/graphql.cgi/web} →
	 * "web".
	 */
	/** Whether this request targets the SSE subscription stream ({@code …/stream}). */
	private boolean isStreamRequest(HttpServletRequest request) {
		String pathInfo = Webs.getEffectivePathInfo(request);
		return pathInfo != null && pathInfo.endsWith("/stream");
	}

	private String getWorkspaceName(HttpServletRequest request) {
		String pathInfo = Webs.getEffectivePathInfo(request);
		if (pathInfo == null || pathInfo.isEmpty()) {
			return null;
		}
		if (pathInfo.startsWith("/")) {
			pathInfo = pathInfo.substring(1);
		}
		String[] segments = pathInfo.split("/");
		return segments.length > 0 ? segments[0] : null;
	}

	private Credentials getCredentials(HttpServletRequest request) {
		Object credentials = request.getAttribute(Credentials.class.getName());
		if (credentials instanceof Credentials) {
			return (Credentials) credentials;
		}
		credentials = Webs.getCredentials(request);
		if (credentials instanceof Credentials) {
			return (Credentials) credentials;
		}
		return new javax.jcr.GuestCredentials();
	}

	private void sendResponse(HttpServletResponse response, Map<String, Object> result) throws IOException {
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setStatus(HttpServletResponse.SC_OK);
		PrintWriter writer = response.getWriter();
		writer.write(GSON.toJson(result));
		writer.flush();
	}

	private void sendError(HttpServletResponse response, String message) throws IOException {
		sendError(response, HttpServletResponse.SC_BAD_REQUEST, message);
	}

	private void sendError(HttpServletResponse response, int status, String message) throws IOException {
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setStatus(status);
		Map<String, Object> error = Map.<String, Object>of("errors", List.of(Map.of("message", message)));
		PrintWriter writer = response.getWriter();
		writer.write(GSON.toJson(error));
		writer.flush();
	}
}

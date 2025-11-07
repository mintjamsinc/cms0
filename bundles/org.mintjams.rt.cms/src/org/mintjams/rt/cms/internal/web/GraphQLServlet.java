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

package org.mintjams.rt.cms.internal.web;

import java.io.IOException;
import java.io.PrintWriter;

import javax.jcr.Credentials;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutor;
import org.mintjams.rt.cms.internal.graphql.GraphQLRequest;
import org.mintjams.rt.cms.internal.graphql.GraphQLRequestParser;
import org.mintjams.rt.cms.internal.graphql.GraphQLResponse;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Servlet for GraphQL API Endpoint: /bin/graphql.cgi/{workspace}
 */
@Component(service = Servlet.class, property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/bin/graphql.cgi/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
				+ HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=org.osgi.service.http)",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ASYNC_SUPPORTED + "=true" })
public class GraphQLServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handleRequest(request, response);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		// Also support GET requests (for development/debugging)
		handleRequest(request, response);
	}

	private void handleRequest(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		Session jcrSession = null;

		try {
			// Get workspace name
			String workspaceName = getWorkspaceName(request);
			if (workspaceName == null || workspaceName.isEmpty()) {
				sendError(response, "Workspace name must be specified");
				return;
			}

			// Get credentials
			Credentials credentials = getCredentials(request);

			// Get JCR session
			try {
				jcrSession = CmsService.getRepository().login(credentials, workspaceName);
			} catch (Throwable e) {
				CmsService.getLogger(getClass()).error("Failed to create JCR session", e);
				sendError(response, "Authentication failed: " + e.getMessage());
				return;
			}

			// Parse GraphQL request
			GraphQLRequest graphQLRequest;
			if ("POST".equalsIgnoreCase(request.getMethod())) {
				graphQLRequest = GraphQLRequestParser.parse(request.getInputStream());
			} else {
				// For GET requests
				String query = request.getParameter("query");
				String operationName = request.getParameter("operationName");
				String variables = request.getParameter("variables");
				graphQLRequest = GraphQLRequestParser.parseFromQueryParams(query, operationName, variables);
			}

			// Execute GraphQL
			GraphQLExecutor executor = new GraphQLExecutor(jcrSession);
			GraphQLResponse graphQLResponse = executor.execute(graphQLRequest);

			// Send response
			sendResponse(response, graphQLResponse);
		} catch (Throwable e) {
			CmsService.getLogger(getClass()).error("GraphQL execution failed", e);
			sendError(response, "Internal server error: " + e.getMessage());
		} finally {
			if (jcrSession != null && jcrSession.isLive()) {
				try {
					jcrSession.refresh(false);
				} catch (Throwable ignore) {}
				jcrSession.logout();
			}
		}
	}

	/**
	 * Extract workspace name from URL Example: /bin/graphql.cgi/system → "system"
	 */
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

	/**
	 * Get authentication credentials
	 */
	private Credentials getCredentials(HttpServletRequest request) {
		// Get credentials from HttpServletRequest
		// Use cms0's existing authentication mechanism
		Object credentials = request.getAttribute("javax.jcr.Credentials");
		if (credentials instanceof Credentials) {
			return (Credentials) credentials;
		}

		// Get credentials from session
		if (request.getSession(false) != null) {
			credentials = request.getSession().getAttribute("javax.jcr.Credentials");
			if (credentials instanceof Credentials) {
				return (Credentials) credentials;
			}
		}

		// Default is guest authentication
		return new javax.jcr.GuestCredentials();
	}

	/**
	 * Send GraphQL response
	 */
	private void sendResponse(HttpServletResponse response, GraphQLResponse graphQLResponse) throws IOException {
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setStatus(HttpServletResponse.SC_OK);

		String json = GSON.toJson(graphQLResponse.toMap());
		PrintWriter writer = response.getWriter();
		writer.write(json);
		writer.flush();
	}

	/**
	 * Send error response
	 */
	private void sendError(HttpServletResponse response, String message) throws IOException {
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);

		GraphQLResponse errorResponse = new GraphQLResponse();
		errorResponse.addError(message);

		String json = GSON.toJson(errorResponse.toMap());
		PrintWriter writer = response.getWriter();
		writer.write(json);
		writer.flush();
	}
}

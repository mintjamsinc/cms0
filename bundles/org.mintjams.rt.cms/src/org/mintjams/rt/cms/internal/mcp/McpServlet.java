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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.jcr.Credentials;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2Credentials;
import org.mintjams.rt.cms.internal.web.Webs;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;

/**
 * The MCP (Model Context Protocol) endpoint: {@code /bin/mcp.cgi/{workspace}}.
 *
 * <p>It speaks MCP's Streamable HTTP transport in its simplest conforming form.
 * A client POSTs one JSON-RPC message (or a batch) and receives the response as
 * {@code application/json}; a body holding only notifications is acknowledged
 * with 202. The server never initiates a message, so it offers no SSE stream
 * ({@code GET} answers 405) and no session to end ({@code DELETE} answers 405).
 * See {@link McpServer} for the protocol itself.
 *
 * <p>Each request is authenticated on its own, one of two ways:
 *
 * <ul>
 * <li>{@code Authorization: Bearer <token>} — an {@link McpAccessToken} the
 * client obtained through the authorization flow served below the endpoint
 * (see {@link McpOAuth}). This is how an external MCP client connects. The
 * token is honoured while the user's connection to the workspace is on (see
 * {@link McpConnections}).</li>
 * <li>the CMS login of the browser (session or authentication cookie) — for
 * pages served by the CMS itself. Such a request acts with the user's full
 * rights.</li>
 * </ul>
 *
 * <p>There is no anonymous access: an unauthenticated request is answered 401,
 * never run as the guest user, and the answer tells the client where the
 * authorization flow starts. The browser login is honoured only for requests
 * from the CMS's own origin, and the body must be {@code application/json}
 * — together these keep another website from driving the endpoint through a
 * signed-in user's browser.
 *
 * <p>The servlet also answers under {@code /.well-known/}, where a client looks
 * up the authorization metadata of an endpoint by inserting the well-known
 * name in front of the endpoint's path.
 */
@Component(service = Servlet.class, property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + CmsConfiguration.MCP_CGI_PATH + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/" + McpOAuth.WELL_KNOWN + "/"
				+ McpOAuth.PROTECTED_RESOURCE + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/" + McpOAuth.WELL_KNOWN + "/"
				+ McpOAuth.AUTHORIZATION_SERVER + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/" + McpOAuth.WELL_KNOWN + "/"
				+ McpOAuth.OPENID_CONFIGURATION + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
				+ HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=org.osgi.service.http)" })
public class McpServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	/** Largest request body accepted; bounds what a Base64 {@code write_file} can carry. */
	private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

	/** JSON-RPC error code used for transport-level refusals (authentication, origin). */
	private static final int UNAUTHORIZED = -32001;

	private volatile McpServer fServer;

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("X-Content-Type-Options", "nosniff");

		try {
			McpConfiguration config = McpConfiguration.get();
			if (!config.isEnabled()) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, McpServer.METHOD_NOT_FOUND,
						"The MCP endpoint is disabled on this server.");
				return;
			}

			String pathInfo = Webs.getEffectivePathInfo(request);
			String wellKnownPrefix = "/" + McpOAuth.WELL_KNOWN + "/";
			if (request.getServletPath().startsWith(wellKnownPrefix)) {
				// /.well-known/{name}/bin/mcp.cgi/{workspace}
				String workspaceName = null;
				if (pathInfo != null && pathInfo.startsWith(CmsConfiguration.MCP_CGI_PATH + "/")) {
					workspaceName = pathInfo.substring(CmsConfiguration.MCP_CGI_PATH.length() + 1)
							.replaceAll("/+$", "");
				}
				if (workspaceName == null || workspaceName.isEmpty() || workspaceName.indexOf('/') != -1
						|| CmsService.getWorkspaceGraphQLEngineProvider(workspaceName) == null) {
					sendError(response, HttpServletResponse.SC_NOT_FOUND, McpServer.INVALID_REQUEST, "Not found.");
					return;
				}
				handleWellKnown(request, response, request.getServletPath().substring(wellKnownPrefix.length()),
						new McpOAuth(workspaceName, config));
				return;
			}

			String[] segments = (pathInfo == null) ? new String[0]
					: pathInfo.replaceAll("^/+|/+$", "").split("/");
			if (segments.length == 0 || segments[0].isEmpty() || segments.length > 3) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, McpServer.INVALID_REQUEST,
						"The endpoint is " + CmsConfiguration.MCP_CGI_PATH + "/{workspace}.");
				return;
			}
			String workspaceName = segments[0];
			if (CmsService.getWorkspaceGraphQLEngineProvider(workspaceName) == null) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, McpServer.INVALID_REQUEST,
						"Unknown or stopped workspace.");
				return;
			}

			if (segments.length == 1) {
				handleMcp(request, response, workspaceName, config);
				return;
			}

			McpOAuth oauth = new McpOAuth(workspaceName, config);
			if (segments.length == 3) {
				if (McpOAuth.WELL_KNOWN.equals(segments[1])) {
					handleWellKnown(request, response, segments[2], oauth);
					return;
				}
			} else if (McpOAuth.AUTHORIZE_PATH.equals(segments[1])) {
				oauth.authorize(request, response);
				return;
			} else if (McpOAuth.TOKEN_PATH.equals(segments[1])) {
				oauth.token(request, response);
				return;
			} else if (McpOAuth.REGISTER_PATH.equals(segments[1])) {
				oauth.register(request, response);
				return;
			}
			sendError(response, HttpServletResponse.SC_NOT_FOUND, McpServer.INVALID_REQUEST, "Not found.");
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error("MCP request failed", ex);
			if (!response.isCommitted()) {
				sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, -32603, "Internal error.");
			}
		}
	}

	private void handleWellKnown(HttpServletRequest request, HttpServletResponse response, String name,
			McpOAuth oauth) throws IOException {
		if (McpOAuth.PROTECTED_RESOURCE.equals(name)) {
			oauth.protectedResourceMetadata(request, response);
		} else if (McpOAuth.AUTHORIZATION_SERVER.equals(name) || McpOAuth.OPENID_CONFIGURATION.equals(name)) {
			oauth.authorizationServerMetadata(request, response);
		} else {
			sendError(response, HttpServletResponse.SC_NOT_FOUND, McpServer.INVALID_REQUEST, "Not found.");
		}
	}

	private void handleMcp(HttpServletRequest request, HttpServletResponse response, String workspaceName,
			McpConfiguration config) throws IOException {
		// A request that names another origin is served, but never on the browser
		// login: a client such as Claude sends its own origin with every call, and
		// what must not happen is another website acting through the cookie of a
		// signed-in user. A bearer token has to be presented on purpose.
		String origin = request.getHeader("Origin");
		boolean crossOrigin = origin != null && !isOwnOrigin(request, origin);
		if (crossOrigin) {
			// Credentials (cookies) are deliberately not allowed cross-origin.
			response.setHeader("Access-Control-Allow-Origin", origin);
			response.setHeader("Vary", "Origin");
			response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
			response.setHeader("Access-Control-Allow-Headers",
					"Authorization, Content-Type, Accept, MCP-Protocol-Version, Mcp-Session-Id");
			response.setHeader("Access-Control-Expose-Headers", "WWW-Authenticate");
			response.setHeader("Access-Control-Max-Age", "600");
		}

		String method = request.getMethod();
		if ("OPTIONS".equalsIgnoreCase(method)) {
			response.setHeader("Allow", "POST, OPTIONS");
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return;
		}

		// Before anything else that could refuse the request: a client finds out
		// how to authorize from the answer to its first, unauthenticated call.
		String authorization = request.getHeader("Authorization");
		boolean bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
		McpCallContext context;
		try {
			context = authenticate(request, bearer ? authorization.substring(7).trim() : null, workspaceName, config,
					crossOrigin);
		} catch (McpAuthException ex) {
			response.setHeader("WWW-Authenticate", McpOAuth.challenge(request, workspaceName, bearer));
			sendError(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED, ex.getMessage());
			return;
		}

		if (!"POST".equalsIgnoreCase(method)) {
			// No server-initiated stream (GET) and no session to terminate (DELETE).
			response.setHeader("Allow", "POST, OPTIONS");
			sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, McpServer.INVALID_REQUEST,
					"Use POST. This server offers no SSE stream and keeps no session.");
			return;
		}

		if (!"application/json".equals(Webs.extractMimeType(request.getContentType()))) {
			sendError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, McpServer.INVALID_REQUEST,
					"Content-Type must be application/json.");
			return;
		}

		String protocolVersion = request.getHeader("MCP-Protocol-Version");
		if (protocolVersion != null && !McpServer.isSupportedProtocolVersion(protocolVersion)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, McpServer.INVALID_REQUEST,
					"Unsupported MCP-Protocol-Version. Supported: " + McpServer.SUPPORTED_PROTOCOL_VERSIONS);
			return;
		}

		byte[] body = readBody(request.getInputStream());
		if (body == null) {
			sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, McpServer.INVALID_REQUEST,
					"The request body is larger than " + MAX_BODY_BYTES + " bytes.");
			return;
		}

		JsonElement message;
		try {
			message = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
		} catch (Throwable ex) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, McpServer.PARSE_ERROR, "Parse error");
			return;
		}

		JsonElement result = getServer().handle(message, context);
		if (result == null) {
			response.setStatus(HttpServletResponse.SC_ACCEPTED);
			return;
		}
		send(response, HttpServletResponse.SC_OK, result);
	}

	/**
	 * Resolves the caller. A bearer token wins over the browser login, and a
	 * request from another origin must use one: its cookies are not trusted.
	 */
	private McpCallContext authenticate(HttpServletRequest request, String token, String workspaceName,
			McpConfiguration config, boolean bearerOnly) throws McpAuthException {
		String serverLabel = McpOAuth.serverLabel(request, config);

		if (token != null) {
			McpAccessToken.Verified verified = McpAccessToken.verify(CmsService.getEncryptor(), token, workspaceName,
					config, System.currentTimeMillis());
			String userId = verified.getCredentials().getName();

			// The token only says who the client works as. Whether it still may,
			// and what it may do, is the user's connection as it is right now.
			McpConnections.Connection connection;
			try {
				connection = McpConnections.get(workspaceName, userId);
			} catch (Throwable ex) {
				CmsService.getLogger(getClass()).error("Could not read the MCP connection of " + userId, ex);
				throw new McpAuthException("The MCP connection could not be verified.");
			}
			if (!connection.isEnabled() || connection.getGeneration() != verified.getGeneration()) {
				throw new McpAuthException("The MCP connection has been turned off. Its user can turn it on again in "
						+ "Preferences; the client then has to be authorized again.");
			}
			return new McpCallContext(workspaceName, verified.getCredentials(), userId,
					connection.isWrite() && config.isWriteAllowed(), serverLabel);
		}

		if (!bearerOnly) {
			Credentials credentials = getBrowserCredentials(request);
			if (credentials != null) {
				return new McpCallContext(workspaceName, credentials, userIdOf(credentials), true, serverLabel);
			}
		}

		throw new McpAuthException("Authorization required. The WWW-Authenticate header names the metadata that "
				+ "describes how to authorize.");
	}

	/** The CMS login carried by the browser (session or authentication cookie), or {@code null}. */
	static Credentials getBrowserCredentials(HttpServletRequest request) {
		Object credentials = request.getAttribute(Credentials.class.getName());
		if (credentials instanceof Credentials) {
			return (Credentials) credentials;
		}
		return Webs.getCredentials(request);
	}

	static String userIdOf(Credentials credentials) {
		if (credentials instanceof Saml2Credentials) {
			return ((Saml2Credentials) credentials).getName();
		}
		return credentials.getClass().getSimpleName();
	}

	/**
	 * Whether {@code origin} is the origin this request was addressed to. Only
	 * host and port are compared: behind a TLS-terminating proxy the scheme the
	 * servlet sees need not be the one the browser used.
	 */
	static boolean isOwnOrigin(HttpServletRequest request, String origin) {
		String host = request.getHeader("Host");
		if (host == null || host.isEmpty()) {
			return false;
		}
		int p = origin.indexOf("://");
		if (p == -1) {
			return false;
		}
		String scheme = origin.substring(0, p);
		String originHost = origin.substring(p + 3);
		return stripDefaultPort(originHost, scheme).equalsIgnoreCase(stripDefaultPort(host, scheme));
	}

	/** {@code host[:port]} without the port when it is the default one for {@code scheme}. */
	static String stripDefaultPort(String host, String scheme) {
		if ("https".equalsIgnoreCase(scheme) && host.endsWith(":443")) {
			return host.substring(0, host.length() - 4);
		}
		if ("http".equalsIgnoreCase(scheme) && host.endsWith(":80")) {
			return host.substring(0, host.length() - 3);
		}
		return host;
	}

	/** Reads the body, or returns {@code null} when it exceeds {@link #MAX_BODY_BYTES}. */
	private static byte[] readBody(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int n;
		while ((n = in.read(buffer)) != -1) {
			if (out.size() + n > MAX_BODY_BYTES) {
				return null;
			}
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}

	private McpServer getServer() {
		McpServer server = fServer;
		if (server == null) {
			String version = "0";
			try {
				Bundle bundle = FrameworkUtil.getBundle(McpServlet.class);
				if (bundle != null) {
					version = bundle.getVersion().toString();
				}
			} catch (Throwable ignore) {}
			server = new McpServer(version, McpTools.all());
			fServer = server;
		}
		return server;
	}

	private static void sendError(HttpServletResponse response, int status, int code, String message)
			throws IOException {
		send(response, status, McpServer.error(JsonNull.INSTANCE, code, message));
	}

	private static void send(HttpServletResponse response, int status, JsonElement body) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		// JsonElement.toString() keeps explicit nulls ("id": null), which JSON-RPC requires.
		response.getWriter().write(body.toString());
		response.getWriter().flush();
	}

}

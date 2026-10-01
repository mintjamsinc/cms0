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
 * <li>{@code Authorization: Bearer <token>} — an {@link McpAccessToken} the user
 * issued at {@code /bin/mcp.cgi/{workspace}/token} (see {@link McpTokenPage}).
 * This is how an external MCP client connects.</li>
 * <li>the CMS login of the browser (session or authentication cookie) — for
 * pages served by the CMS itself. Such a request acts with the user's full
 * rights.</li>
 * </ul>
 *
 * <p>There is no anonymous access: an unauthenticated request is answered 401,
 * never run as the guest user. Requests carrying an {@code Origin} header are
 * accepted only from the CMS's own origin or one listed in
 * {@code mcp.yml#allowedOrigins}, and the body must be {@code application/json}
 * — together these keep another website from driving the endpoint through a
 * signed-in user's browser.
 */
@Component(service = Servlet.class, property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + CmsConfiguration.MCP_CGI_PATH + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
				+ HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=org.osgi.service.http)" })
public class McpServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	/** Largest request body accepted; bounds what a Base64 {@code write_file} can carry. */
	private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

	/** JSON-RPC error code used for transport-level refusals (authentication, origin). */
	private static final int UNAUTHORIZED = -32001;

	static final String TOKEN_PATH = "token";

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
			String[] segments = (pathInfo == null) ? new String[0]
					: pathInfo.replaceAll("^/+|/+$", "").split("/");
			if (segments.length == 0 || segments[0].isEmpty() || segments.length > 2) {
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

			if (segments.length == 2) {
				if (!TOKEN_PATH.equals(segments[1])) {
					sendError(response, HttpServletResponse.SC_NOT_FOUND, McpServer.INVALID_REQUEST, "Not found.");
					return;
				}
				new McpTokenPage(workspaceName, config).handle(request, response);
				return;
			}

			handleMcp(request, response, workspaceName, config);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error("MCP request failed", ex);
			if (!response.isCommitted()) {
				sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, -32603, "Internal error.");
			}
		}
	}

	private void handleMcp(HttpServletRequest request, HttpServletResponse response, String workspaceName,
			McpConfiguration config) throws IOException {
		String origin = request.getHeader("Origin");
		boolean crossOriginAllowed = false;
		if (origin != null) {
			if (isOwnOrigin(request, origin)) {
				// Same origin: no CORS headers needed.
			} else if (config.getAllowedOrigins().contains(origin)) {
				crossOriginAllowed = true;
			} else {
				sendError(response, HttpServletResponse.SC_FORBIDDEN, UNAUTHORIZED,
						"Requests from this origin are not allowed.");
				return;
			}
		}
		if (crossOriginAllowed) {
			// Bearer tokens only: credentials (cookies) are deliberately not allowed cross-origin.
			response.setHeader("Access-Control-Allow-Origin", origin);
			response.setHeader("Vary", "Origin");
			response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
			response.setHeader("Access-Control-Allow-Headers",
					"Authorization, Content-Type, Accept, MCP-Protocol-Version, Mcp-Session-Id");
			response.setHeader("Access-Control-Max-Age", "600");
		}

		String method = request.getMethod();
		if ("OPTIONS".equalsIgnoreCase(method)) {
			response.setHeader("Allow", "POST, OPTIONS");
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
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

		McpCallContext context;
		try {
			context = authenticate(request, workspaceName, config, crossOriginAllowed);
		} catch (McpAuthException ex) {
			response.setHeader("WWW-Authenticate", "Bearer realm=\"MintJams CMS MCP\", error=\"invalid_token\"");
			sendError(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED, ex.getMessage());
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
	 * request allowed cross-origin must use one: its cookies are not trusted.
	 */
	private McpCallContext authenticate(HttpServletRequest request, String workspaceName, McpConfiguration config,
			boolean bearerOnly) throws McpAuthException {
		String authorization = request.getHeader("Authorization");
		if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			McpAccessToken.Verified verified = McpAccessToken.verify(CmsService.getEncryptor(),
					authorization.substring(7).trim(), workspaceName, config, System.currentTimeMillis());
			return new McpCallContext(workspaceName, verified.getCredentials(), verified.getCredentials().getName(),
					verified.canWrite());
		}

		if (!bearerOnly) {
			Credentials credentials = getBrowserCredentials(request);
			if (credentials != null) {
				return new McpCallContext(workspaceName, credentials, userIdOf(credentials), true);
			}
		}

		throw new McpAuthException("Authentication required. Send an access token issued at "
				+ CmsConfiguration.MCP_CGI_PATH + "/" + workspaceName + "/" + TOKEN_PATH
				+ " as \"Authorization: Bearer <token>\".");
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

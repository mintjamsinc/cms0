/*
 * Copyright (c) 2024 MintJams Inc.
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

package org.mintjams.idp.internal.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.idp.internal.Activator;
import org.mintjams.tools.lang.Strings;

/**
 * The login page at {@code /idp/login}.
 *
 * <p>The page is a small single-page application. Its logic and markup live in
 * {@code /idp/login/app.js}, which this servlet serves from the bundle
 * together with the framework it needs, so that a page can be replaced for
 * branding without copying the sign-in flow:</p>
 * <ul>
 *   <li>{@code GET /idp/login} serves the bundled default page, or redirects
 *       to {@code customLoginPageUrl} when one is configured. A custom page is
 *       a plain HTML document that provides an element with the id
 *       {@code idp-login} and loads {@code /idp/login/app.js} as a module;
 *       everything else on the page is free.</li>
 *   <li>{@code GET /idp/login/app.js} and {@code GET /idp/login/ichigo.esm.min.js}
 *       serve the application and its framework.</li>
 * </ul>
 *
 * <p>Authentication itself goes through {@link LoginApiServlet} and
 * {@link WebAuthnApiServlet}; this servlet accepts no credentials.</p>
 *
 * <p>Session attributes (shared with {@link SsoServlet} and the API servlets):</p>
 * <ul>
 *   <li>{@code idp.user} - the authenticated {@link org.mintjams.idp.internal.model.IdpUser}</li>
 *   <li>{@code idp.samlRequest} - the original SAMLRequest parameter</li>
 *   <li>{@code idp.relayState} - the original RelayState parameter</li>
 *   <li>{@code idp.binding} - the original binding ("REDIRECT" or "POST")</li>
 * </ul>
 */
public class LoginServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	static final String SESSION_USER = "idp.user";
	static final String SESSION_SAML_REQUEST = "idp.samlRequest";
	static final String SESSION_RELAY_STATE = "idp.relayState";
	static final String SESSION_BINDING = "idp.binding";

	private static final String RESOURCE_BASE = "/org/mintjams/idp/internal/web/";

	private static final Map<String, String> ASSETS = Map.of(
			"/app.js", "text/javascript; charset=UTF-8",
			"/ichigo.esm.min.js", "text/javascript; charset=UTF-8");

	private final Map<String, Resource> fCache = new ConcurrentHashMap<>();

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String pathInfo = request.getPathInfo();
		if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
			String customLoginPageURL = Activator.getDefault().getConfiguration().getCustomLoginPageURL();
			if (Strings.isNotBlank(customLoginPageURL)) {
				response.sendRedirect(customLoginPageURL);
				return;
			}
			serve(request, response, "login.html", "text/html; charset=UTF-8");
			return;
		}
		String contentType = ASSETS.get(pathInfo);
		if (contentType == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		serve(request, response, pathInfo.substring(1), contentType);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setHeader("Allow", "GET");
		response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Sign in through the login page.");
	}

	private void serve(HttpServletRequest request, HttpServletResponse response, String name, String contentType) throws IOException {
		Resource resource = fCache.get(name);
		if (resource == null) {
			resource = load(name);
			if (resource == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			fCache.put(name, resource);
		}
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("ETag", resource.etag);
		if (resource.etag.equals(request.getHeader("If-None-Match"))) {
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return;
		}
		response.setContentType(contentType);
		response.setContentLength(resource.bytes.length);
		response.getOutputStream().write(resource.bytes);
	}

	private static Resource load(String name) throws IOException {
		try (InputStream in = LoginServlet.class.getResourceAsStream(RESOURCE_BASE + name)) {
			if (in == null) {
				return null;
			}
			byte[] bytes = in.readAllBytes();
			String etag;
			try {
				byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
				StringBuilder sb = new StringBuilder("\"");
				for (int i = 0; i < 16; i++) {
					sb.append(String.format("%02x", digest[i]));
				}
				etag = sb.append('"').toString();
			} catch (Exception ex) {
				etag = "\"" + bytes.length + "\"";
			}
			return new Resource(bytes, etag);
		}
	}

	private static final class Resource {
		final byte[] bytes;
		final String etag;

		Resource(byte[] bytes, String etag) {
			this.bytes = bytes;
			this.etag = etag;
		}
	}

}

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.io.IOUtils;
import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.IdpConfiguration;
import org.mintjams.idp.internal.model.AuthnRequest;
import org.mintjams.idp.internal.model.IdpUser;
import org.mintjams.idp.internal.saml.AuthnRequestParser;
import org.mintjams.idp.internal.saml.SamlResponseBuilder;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API servlet for authentication at {@code /idp/api/login}.
 *
 * <p>Accepts POST with JSON body containing username and password.
 * On successful authentication, retrieves the pending SAML request from the
 * HTTP session, builds a signed SAML Response, and returns the data needed
 * for the custom login page SPA to perform the POST redirect to the SP.</p>
 *
 * <p>Request body:</p>
 * <pre>{"username": "...", "password": "..."}</pre>
 *
 * <p>Success response:</p>
 * <pre>{"status": "success", "data": {"acsUrl": "...", "samlResponse": "...", "relayState": "..."}}</pre>
 *
 * <p>Error response:</p>
 * <pre>{"status": "error", "message": "..."}</pre>
 */
public class LoginApiServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(LoginApiServlet.class);

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setContentType("application/json; charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");

		// Read JSON body
		String body;
		try (BufferedReader in = request.getReader()) {
			body = IOUtils.toString(in);
		}

		Map<String, Object> requestBody = Activator.getDefault().parseJSON(body);
		AdaptableMap<String, Object> jsonBody = AdaptableMap.<String, Object>newBuilder().putAll(requestBody).build();
		String username = jsonBody.getString("username");
		String password = jsonBody.getString("password");

		if (Strings.isBlank(username) || Strings.isBlank(password)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Username and password are required.");
			return;
		}

		// Authenticate
		IdpUser user = Activator.getDefault().getUserStore().authenticate(username, password);
		if (user == null) {
			LOG.warn("Authentication failed for user: {}", username);
			sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid username or password.");
			return;
		}

		LOG.info("User authenticated via API: {}", username);

		// Store user in session
		HttpSession session = request.getSession(true);
		session.setAttribute(LoginServlet.SESSION_USER, user);

		// Retrieve pending SAML request from session
		String samlRequest = (String) session.getAttribute(LoginServlet.SESSION_SAML_REQUEST);
		if (samlRequest == null) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "No pending authentication request.");
			return;
		}

		String relayState = (String) session.getAttribute(LoginServlet.SESSION_RELAY_STATE);
		String binding = (String) session.getAttribute(LoginServlet.SESSION_BINDING);

		// Clean up session
		session.removeAttribute(LoginServlet.SESSION_SAML_REQUEST);
		session.removeAttribute(LoginServlet.SESSION_RELAY_STATE);
		session.removeAttribute(LoginServlet.SESSION_BINDING);

		try {
			IdpConfiguration config = Activator.getDefault().getConfiguration();

			// Parse the AuthnRequest
			AuthnRequestParser parser = new AuthnRequestParser();
			AuthnRequest authnRequest;
			if ("POST".equals(binding)) {
				authnRequest = parser.parsePostBinding(samlRequest, relayState);
			} else {
				authnRequest = parser.parseRedirectBinding(samlRequest, relayState);
			}

			// Validate trusted SP
			if (!config.isTrustedSP(authnRequest.getIssuer())) {
				LOG.warn("Untrusted SP: {}", authnRequest.getIssuer());
				sendError(response, HttpServletResponse.SC_FORBIDDEN, "Untrusted Service Provider.");
				return;
			}

			// Build signed SAML Response
			SamlResponseBuilder builder = new SamlResponseBuilder(config);
			String base64Response = builder.buildBase64Response(authnRequest, user);

			// Send success response
			sendSuccess(response, authnRequest.getAssertionConsumerServiceUrl(), base64Response, authnRequest.getRelayState());

		} catch (Exception e) {
			LOG.error("Failed to build SAML response", e);
			sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process authentication request.");
		}
	}

	private void sendSuccess(HttpServletResponse response, String acsUrl, String samlResponse, String relayState) throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		Map<String, Object> successResponse = Map.of(
			"status", "success",
			"data", Map.of(
				"acsUrl", acsUrl,
				"samlResponse", samlResponse,
				"relayState", relayState
			)
		);
		PrintWriter out = response.getWriter();
		out.write(Activator.getDefault().toJSON(successResponse));
	}

	private void sendError(HttpServletResponse response, int statusCode, String message) throws IOException {
		response.setStatus(statusCode);
		Map<String, Object> errorResponse = Map.of(
			"status", "error",
			"message", message
		);
		PrintWriter out = response.getWriter();
		out.write(Activator.getDefault().toJSON(errorResponse));
	}

}

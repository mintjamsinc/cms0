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

package org.mintjams.idp.servlet;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.mintjams.idp.model.AuthnRequest;
import org.mintjams.idp.model.IdpSettings;
import org.mintjams.idp.model.IdpUser;
import org.mintjams.idp.saml.AuthnRequestParser;
import org.mintjams.idp.saml.SamlResponseBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SAML 2.0 SSO endpoint at {@code /idp/sso}.
 *
 * <p>Handles the SAML authentication flow:</p>
 * <ol>
 *   <li>Receives AuthnRequest from SP (via HTTP-Redirect or HTTP-POST binding)</li>
 *   <li>If user is not logged in, redirects to login form</li>
 *   <li>If user is logged in, builds signed SAMLResponse and posts it back to SP</li>
 * </ol>
 */
public class SsoServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(SsoServlet.class);

	private final IdpSettings settings;
	private final String roleAttributeName;

	public SsoServlet(IdpSettings settings, String roleAttributeName) {
		this.settings = settings;
		this.roleAttributeName = roleAttributeName;
	}

	/**
	 * Handles HTTP-Redirect binding (SP sends AuthnRequest as query parameter).
	 * Also handles re-entry after login (LoginServlet redirects back here via GET).
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String samlRequest = request.getParameter("SAMLRequest");
		String relayState = request.getParameter("RelayState");

		if (samlRequest == null || samlRequest.isEmpty()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing SAMLRequest parameter");
			return;
		}

		// Check if this is a re-entry after login with a different original binding
		String binding = request.getParameter("binding");
		if ("POST".equals(binding)) {
			processSsoRequest(request, response, samlRequest, relayState, "POST");
		} else {
			processSsoRequest(request, response, samlRequest, relayState, "REDIRECT");
		}
	}

	/**
	 * Handles HTTP-POST binding (SP sends AuthnRequest as form field).
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String samlRequest = request.getParameter("SAMLRequest");
		String relayState = request.getParameter("RelayState");

		if (samlRequest == null || samlRequest.isEmpty()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing SAMLRequest parameter");
			return;
		}

		processSsoRequest(request, response, samlRequest, relayState, "POST");
	}

	private void processSsoRequest(HttpServletRequest request, HttpServletResponse response,
			String samlRequest, String relayState, String binding) throws IOException {

		try {
			// Parse the AuthnRequest
			AuthnRequestParser parser = new AuthnRequestParser();
			AuthnRequest authnRequest;

			if ("REDIRECT".equals(binding)) {
				authnRequest = parser.parseRedirectBinding(samlRequest, relayState);
			} else {
				authnRequest = parser.parsePostBinding(samlRequest, relayState);
			}

			LOG.info("Received AuthnRequest from SP: {} (ID: {})", authnRequest.getIssuer(), authnRequest.getId());

			// Validate trusted SP
			if (!settings.isTrustedSP(authnRequest.getIssuer())) {
				LOG.warn("Untrusted SP: {}", authnRequest.getIssuer());
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "Untrusted Service Provider");
				return;
			}

			// Check if user is already authenticated
			HttpSession session = request.getSession(true);
			IdpUser user = (IdpUser) session.getAttribute(LoginServlet.SESSION_USER);

			// Check if the user just completed fresh authentication
			Boolean freshLogin = (Boolean) session.getAttribute("idp.freshLogin");
			if (freshLogin != null && freshLogin) {
				session.removeAttribute("idp.freshLogin");
			}

			if (user == null || (authnRequest.isForceAuthn() && (freshLogin == null || !freshLogin))) {
				// Store the SAML request in session and redirect to login
				session.setAttribute(LoginServlet.SESSION_SAML_REQUEST, samlRequest);
				session.setAttribute(LoginServlet.SESSION_RELAY_STATE, relayState);
				session.setAttribute(LoginServlet.SESSION_BINDING, binding);

				response.sendRedirect(settings.getLoginUrl());
				return;
			}

			// User is authenticated - build and send SAML Response
			sendSamlResponse(response, authnRequest, user);

		} catch (Exception e) {
			LOG.error("SSO processing failed", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "SSO processing failed");
		}
	}

	private void sendSamlResponse(HttpServletResponse response, AuthnRequest authnRequest, IdpUser user)
			throws Exception {

		LOG.info("Sending SAMLResponse for user: {} to SP: {}",
				user.getUsername(), authnRequest.getIssuer());

		SamlResponseBuilder builder = new SamlResponseBuilder(settings);
		String base64Response = builder.buildBase64Response(authnRequest, user, roleAttributeName);

		// Build and send auto-submit POST form
		String html = builder.buildPostForm(
				authnRequest.getAssertionConsumerServiceUrl(),
				base64Response,
				authnRequest.getRelayState());

		response.setContentType("text/html; charset=UTF-8");
		response.getWriter().write(html);
	}

}

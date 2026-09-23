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
import java.util.LinkedHashMap;
import java.util.Map;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The state the sign-in API servlets share: the session attributes of a
 * sign-in in progress, the JSON envelope of every API response, and the last
 * step that turns an authenticated {@link IdpUser} into the SAML response
 * the login page posts to the service provider.
 *
 * <p>Session attributes:</p>
 * <ul>
 *   <li>{@code idp.user} - the signed-in user (set only once every factor passed)</li>
 *   <li>{@code idp.pendingUser} - a user whose password verified and who still owes a second factor</li>
 *   <li>{@code idp.pendingSince} - when that happened (the step expires)</li>
 *   <li>{@code idp.mfaAttempts} - failed second-factor attempts in this step</li>
 *   <li>{@code idp.webauthnChallenge} - the challenge issued for a passkey sign-in</li>
 *   <li>{@code idp.webauthnChallengeSince} - when it was issued</li>
 * </ul>
 */
final class AuthnFlow {

	private static final Logger LOG = LoggerFactory.getLogger(AuthnFlow.class);

	static final String SESSION_USER = LoginServlet.SESSION_USER;
	static final String SESSION_PENDING_USER = "idp.pendingUser";
	static final String SESSION_PENDING_SINCE = "idp.pendingSince";
	static final String SESSION_MFA_ATTEMPTS = "idp.mfaAttempts";
	static final String SESSION_WEBAUTHN_CHALLENGE = "idp.webauthnChallenge";
	static final String SESSION_WEBAUTHN_CHALLENGE_SINCE = "idp.webauthnChallengeSince";

	/** How long a second-factor step or a passkey challenge stays valid. */
	static final long STEP_TTL_MILLIS = 5L * 60 * 1000;

	/** Failed second-factor attempts before the user has to start over. */
	static final int MAX_MFA_ATTEMPTS = 5;

	private AuthnFlow() {}

	/**
	 * Reads the request body as a JSON object; an empty body is an empty map.
	 */
	static Map<String, Object> readJson(HttpServletRequest request) throws IOException {
		String body;
		try (BufferedReader in = request.getReader()) {
			body = IOUtils.toString(in);
		}
		if (body == null || body.isBlank()) {
			return new LinkedHashMap<>();
		}
		Map<String, Object> parsed = Activator.getDefault().parseJSON(body);
		return parsed == null ? new LinkedHashMap<>() : parsed;
	}

	static void prepareJson(HttpServletResponse response) {
		response.setContentType("application/json; charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");
	}

	static void sendJson(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
		prepareJson(response);
		response.setStatus(status);
		response.getWriter().write(Activator.getDefault().toJSON(body));
	}

	static void sendSuccess(HttpServletResponse response, Map<String, Object> data) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", "success");
		body.put("data", data);
		sendJson(response, HttpServletResponse.SC_OK, body);
	}

	static void sendStatus(HttpServletResponse response, String status, Map<String, Object> data) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status);
		body.put("data", data);
		sendJson(response, HttpServletResponse.SC_OK, body);
	}

	static void sendError(HttpServletResponse response, int status, String message) throws IOException {
		sendError(response, status, message, null);
	}

	static void sendError(HttpServletResponse response, int status, String message, String code) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", "error");
		body.put("message", message);
		if (code != null) {
			body.put("code", code);
		}
		sendJson(response, status, body);
	}

	/**
	 * Marks the user as owing a second factor.
	 */
	static void startSecondFactor(HttpSession session, IdpUser user) {
		session.setAttribute(SESSION_PENDING_USER, user);
		session.setAttribute(SESSION_PENDING_SINCE, System.currentTimeMillis());
		session.setAttribute(SESSION_MFA_ATTEMPTS, 0);
	}

	/**
	 * The user owing a second factor, or null when there is none or the step
	 * has expired (in which case the step is cleared).
	 */
	static IdpUser pendingUser(HttpSession session) {
		Object user = session.getAttribute(SESSION_PENDING_USER);
		Object since = session.getAttribute(SESSION_PENDING_SINCE);
		if (!(user instanceof IdpUser) || !(since instanceof Long)) {
			return null;
		}
		if (System.currentTimeMillis() - (Long) since > STEP_TTL_MILLIS) {
			clearSecondFactor(session);
			return null;
		}
		return (IdpUser) user;
	}

	/**
	 * Counts a failed second-factor attempt.
	 *
	 * @return whether another attempt is allowed
	 */
	static boolean recordFailedAttempt(HttpSession session) {
		Object value = session.getAttribute(SESSION_MFA_ATTEMPTS);
		int attempts = (value instanceof Integer ? (Integer) value : 0) + 1;
		session.setAttribute(SESSION_MFA_ATTEMPTS, attempts);
		if (attempts >= MAX_MFA_ATTEMPTS) {
			clearSecondFactor(session);
			return false;
		}
		return true;
	}

	static void clearSecondFactor(HttpSession session) {
		session.removeAttribute(SESSION_PENDING_USER);
		session.removeAttribute(SESSION_PENDING_SINCE);
		session.removeAttribute(SESSION_MFA_ATTEMPTS);
	}

	/**
	 * Finishes the sign-in: records the user in the session, builds the signed
	 * SAML response for the pending authentication request and returns what
	 * the login page needs to post it to the service provider.
	 */
	static void complete(HttpServletRequest request, HttpServletResponse response, IdpUser user, String authnContextClassRef)
			throws IOException {
		HttpSession session = request.getSession(true);
		clearSecondFactor(session);
		session.removeAttribute(SESSION_WEBAUTHN_CHALLENGE);
		session.removeAttribute(SESSION_WEBAUTHN_CHALLENGE_SINCE);
		user.setAuthnContextClassRef(authnContextClassRef);
		session.setAttribute(SESSION_USER, user);

		String samlRequest = (String) session.getAttribute(LoginServlet.SESSION_SAML_REQUEST);
		if (samlRequest == null) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "No pending authentication request.", "NO_REQUEST");
			return;
		}
		String relayState = (String) session.getAttribute(LoginServlet.SESSION_RELAY_STATE);
		String binding = (String) session.getAttribute(LoginServlet.SESSION_BINDING);
		session.removeAttribute(LoginServlet.SESSION_SAML_REQUEST);
		session.removeAttribute(LoginServlet.SESSION_RELAY_STATE);
		session.removeAttribute(LoginServlet.SESSION_BINDING);

		try {
			IdpConfiguration config = Activator.getDefault().getConfiguration();
			AuthnRequestParser parser = new AuthnRequestParser();
			AuthnRequest authnRequest;
			if ("POST".equals(binding)) {
				authnRequest = parser.parsePostBinding(samlRequest, relayState);
			} else {
				authnRequest = parser.parseRedirectBinding(samlRequest, relayState);
			}

			if (!config.isTrustedSP(authnRequest.getIssuer())) {
				LOG.warn("Untrusted SP: {}", authnRequest.getIssuer());
				sendError(response, HttpServletResponse.SC_FORBIDDEN, "Untrusted Service Provider.");
				return;
			}

			SamlResponseBuilder builder = new SamlResponseBuilder(config);
			String base64Response = builder.buildBase64Response(authnRequest, user);

			Map<String, Object> data = new LinkedHashMap<>();
			data.put("acsUrl", authnRequest.getAssertionConsumerServiceUrl());
			data.put("samlResponse", base64Response);
			data.put("relayState", authnRequest.getRelayState());
			sendSuccess(response, data);
		} catch (Exception ex) {
			LOG.error("Failed to build SAML response", ex);
			sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process authentication request.");
		}
	}

}

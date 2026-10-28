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
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
 *   <li>{@code idp.pendingUser} - a user whose first factor verified and who still owes a second factor</li>
 *   <li>{@code idp.pendingSince} - when that happened (the step expires)</li>
 *   <li>{@code idp.firstFactor} - which first factor that was ({@code password} or {@code emailCode})</li>
 *   <li>{@code idp.mfaAttempts} - failed second-factor attempts in this step</li>
 *   <li>{@code idp.emailCode} - the sign-in code sent by email that is still waiting to be entered</li>
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
	static final String SESSION_FIRST_FACTOR = "idp.firstFactor";
	static final String SESSION_EMAIL_CODE = "idp.emailCode";

	/** The first factor a pending second-factor step followed. */
	static final String FIRST_FACTOR_PASSWORD = "password";
	static final String FIRST_FACTOR_EMAIL_CODE = "emailCode";

	/** How long a second-factor step or a passkey challenge stays valid. */
	static final long STEP_TTL_MILLIS = 5L * 60 * 1000;

	/** Failed second-factor attempts before the user has to start over. */
	static final int MAX_MFA_ATTEMPTS = 5;

	/** How long a sign-in code sent by email stays valid. */
	static final long EMAIL_CODE_TTL_MILLIS = 5L * 60 * 1000;

	/** How long after a code was sent another one may be asked for. */
	static final long EMAIL_CODE_RESEND_MILLIS = 60L * 1000;

	/** Wrong codes before the user has to ask for a new one. */
	static final int MAX_EMAIL_CODE_ATTEMPTS = 5;

	private static final SecureRandom RANDOM = new SecureRandom();

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

	/**
	 * Sends {@code {"status": "error", "message": ..., "code": ...}}. The
	 * message is English and meant for logs and unknown clients; the code is
	 * what the login page shows in the visitor's language, so every error a
	 * visitor can see carries one:
	 * <ul>
	 * <li>{@code MISSING_CREDENTIALS}, {@code INVALID_CREDENTIALS} — password step</li>
	 * <li>{@code MISSING_CODE}, {@code INVALID_CODE}, {@code TOO_MANY_ATTEMPTS} — second factor, or a sign-in code</li>
	 * <li>{@code PASSWORD_DISABLED}, {@code EMAIL_CODE_UNAVAILABLE} — the method is not offered</li>
	 * <li>{@code MISSING_USERNAME}, {@code MAIL_FAILED} — asking for a sign-in code</li>
	 * <li>{@code SESSION_EXPIRED} — the step the client is on is no longer pending; start over</li>
	 * <li>{@code NOT_AVAILABLE}, {@code INVALID_CREDENTIAL}, {@code UNKNOWN_CREDENTIAL} — passkey</li>
	 * <li>{@code ACCOUNT_DISABLED}, {@code NO_REQUEST}, {@code UNTRUSTED_SP}</li>
	 * <li>{@code SERVER_ERROR}, {@code UNKNOWN_ENDPOINT}</li>
	 * </ul>
	 */
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
		startSecondFactor(session, user, FIRST_FACTOR_PASSWORD);
	}

	/**
	 * Marks the user as owing a second factor, remembering which first
	 * factor ({@link #FIRST_FACTOR_PASSWORD} or {@link #FIRST_FACTOR_EMAIL_CODE})
	 * they passed, so that the completed sign-in reports the right context.
	 */
	static void startSecondFactor(HttpSession session, IdpUser user, String firstFactor) {
		session.setAttribute(SESSION_PENDING_USER, user);
		session.setAttribute(SESSION_PENDING_SINCE, System.currentTimeMillis());
		session.setAttribute(SESSION_MFA_ATTEMPTS, 0);
		session.setAttribute(SESSION_FIRST_FACTOR, firstFactor);
	}

	/** The first factor of the pending second-factor step; the password when unknown. */
	static String firstFactor(HttpSession session) {
		Object value = session.getAttribute(SESSION_FIRST_FACTOR);
		return (value instanceof String) ? (String) value : FIRST_FACTOR_PASSWORD;
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
		session.removeAttribute(SESSION_FIRST_FACTOR);
	}

	// ---- sign-in code sent by email ----------------------------------------

	/** A sign-in code waiting to be entered: never the code itself, only its salted hash. */
	static final class EmailCodeState implements Serializable {
		private static final long serialVersionUID = 1L;
		/** The username the page asked for (what the cool-down is keyed on). */
		String requestedUsername;
		/** The account the code was sent to, or null when there was none (a decoy that never verifies). */
		String username;
		String salt;
		String hash;
		long sentAt;
		int attempts;
	}

	/** The outcome of entering a sign-in code. */
	enum EmailCodeOutcome {
		VERIFIED, INVALID, TOO_MANY_ATTEMPTS, NO_CODE
	}

	static final class EmailCodeResult {
		private final EmailCodeOutcome fOutcome;
		private final String fUsername;

		EmailCodeResult(EmailCodeOutcome outcome, String username) {
			fOutcome = outcome;
			fUsername = username;
		}

		EmailCodeOutcome getOutcome() {
			return fOutcome;
		}

		/** The verified account, set only for {@link EmailCodeOutcome#VERIFIED}. */
		String getUsername() {
			return fUsername;
		}
	}

	/**
	 * Whether a code may be sent now: not when one went to the same username
	 * less than {@link #EMAIL_CODE_RESEND_MILLIS} ago and is still valid.
	 */
	static boolean canSendEmailCode(HttpSession session, String requestedUsername) {
		EmailCodeState state = emailCodeState(session);
		if (state == null || !requestedUsername.equals(state.requestedUsername)) {
			return true;
		}
		return System.currentTimeMillis() - state.sentAt >= EMAIL_CODE_RESEND_MILLIS;
	}

	/**
	 * Records a code that was just sent (or, when {@code username} is null,
	 * a decoy for an account that got nothing). Replaces any earlier code.
	 */
	static void startEmailCode(HttpSession session, String requestedUsername, String username, String code) {
		EmailCodeState state = new EmailCodeState();
		state.requestedUsername = requestedUsername;
		state.username = username;
		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		state.salt = hex(salt);
		state.hash = hashCode(state.salt, code);
		state.sentAt = System.currentTimeMillis();
		state.attempts = 0;
		session.setAttribute(SESSION_EMAIL_CODE, state);
	}

	/**
	 * Checks an entered code against the one that was sent. A verified or
	 * exhausted code is removed; a wrong one counts an attempt.
	 */
	static EmailCodeResult verifyEmailCode(HttpSession session, String code) {
		EmailCodeState state = emailCodeState(session);
		if (state == null) {
			return new EmailCodeResult(EmailCodeOutcome.NO_CODE, null);
		}
		String entered = code.replaceAll("[^0-9]", "");
		boolean matches = state.username != null && MessageDigest.isEqual(
				hashCode(state.salt, entered).getBytes(StandardCharsets.US_ASCII),
				state.hash.getBytes(StandardCharsets.US_ASCII));
		if (matches) {
			session.removeAttribute(SESSION_EMAIL_CODE);
			return new EmailCodeResult(EmailCodeOutcome.VERIFIED, state.username);
		}
		state.attempts++;
		if (state.attempts >= MAX_EMAIL_CODE_ATTEMPTS) {
			session.removeAttribute(SESSION_EMAIL_CODE);
			return new EmailCodeResult(EmailCodeOutcome.TOO_MANY_ATTEMPTS, null);
		}
		// Write the attribute back so a shared session store sees the count.
		session.setAttribute(SESSION_EMAIL_CODE, state);
		return new EmailCodeResult(EmailCodeOutcome.INVALID, null);
	}

	/** The pending code, or null when there is none or it has expired (in which case it is cleared). */
	private static EmailCodeState emailCodeState(HttpSession session) {
		Object value = session.getAttribute(SESSION_EMAIL_CODE);
		if (!(value instanceof EmailCodeState)) {
			return null;
		}
		EmailCodeState state = (EmailCodeState) value;
		if (System.currentTimeMillis() - state.sentAt > EMAIL_CODE_TTL_MILLIS) {
			session.removeAttribute(SESSION_EMAIL_CODE);
			return null;
		}
		return state;
	}

	private static String hashCode(String salt, String code) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return hex(digest.digest((salt + ":" + code).getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
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
		session.removeAttribute(SESSION_EMAIL_CODE);
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
				sendError(response, HttpServletResponse.SC_FORBIDDEN, "Untrusted Service Provider.", "UNTRUSTED_SP");
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
			sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process authentication request.", "SERVER_ERROR");
		}
	}

}

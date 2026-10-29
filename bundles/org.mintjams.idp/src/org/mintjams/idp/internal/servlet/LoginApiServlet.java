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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.mintjams.cms.mail.MailException;
import org.mintjams.cms.mail.MailService;
import org.mintjams.cms.security.SignInPolicy;
import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.auth.SignInCodeMail;
import org.mintjams.idp.internal.auth.SignInPolicyImpl;
import org.mintjams.idp.internal.mfa.BackupCodes;
import org.mintjams.idp.internal.mfa.CredentialStore;
import org.mintjams.idp.internal.mfa.Totp;
import org.mintjams.idp.internal.model.IdpUser;
import org.mintjams.idp.internal.saml.SamlResponseBuilder;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first-factor sign-in API at {@code /idp/api/login}.
 *
 * <p>{@code GET} describes the sign-in methods the login page may offer.
 * The booleans say what can be used now; {@code states} says why a method
 * is missing ({@code available}, {@code disabled} by the administrator,
 * {@code unavailable} in this installation):</p>
 * <pre>{"status": "success", "data": {"password": true, "webauthn": true, "emailCode": false,
 *   "states": {"password": "available", "passkey": "available", "emailCode": "unavailable"}}}</pre>
 *
 * <p>{@code POST} with {@code {"username": "...", "password": "..."}} verifies
 * the password. When the user has no second factor the sign-in completes and
 * the response carries what the page posts to the service provider:</p>
 * <pre>{"status": "success", "data": {"acsUrl": "...", "samlResponse": "...", "relayState": "..."}}</pre>
 * <p>When the user has TOTP enabled the response is instead</p>
 * <pre>{"status": "mfa_required", "data": {"methods": ["totp", "backupCode"]}}</pre>
 * <p>and the page continues with {@code POST /idp/api/login/mfa} carrying
 * {@code {"method": "totp" | "backupCode", "code": "..."}}, which completes
 * the sign-in with the same success payload.</p>
 *
 * <p>A sign-in code sent by email is the other first factor.
 * {@code POST /idp/api/login/code/request} with {@code {"username": "...", "lang": "ja"}}
 * sends a code to the user's registered address and answers
 * {@code {"status": "success", "data": {"expiresInSeconds": 300, "resendAfterSeconds": 60}}}
 * whether or not the username exists or has an address (so the page cannot
 * be used to enumerate accounts). An address that was never confirmed by
 * its owner gets a notice instead of a code. {@code POST /idp/api/login/code} with
 * {@code {"code": "..."}} verifies it and continues exactly like the
 * password: success, or {@code mfa_required} when the user has TOTP.</p>
 *
 * <p>Errors are {@code {"status": "error", "message": "...", "code": "..."}}.
 * The message is English; the code is what a page translates (see
 * {@link AuthnFlow#sendError(HttpServletResponse, int, String, String)} for
 * the list). After {@code SESSION_EXPIRED} or {@code TOO_MANY_ATTEMPTS} the
 * page has to start over from the first factor.</p>
 */
public class LoginApiServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(LoginApiServlet.class);

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		SignInPolicy policy = Activator.getDefault().getSignInPolicy();
		SignInPolicy.State password = policy.getPassword();
		SignInPolicy.State emailCode = policy.getEmailCode();
		SignInPolicy.State passkey = policy.getPasskey();

		Map<String, Object> states = new LinkedHashMap<>();
		states.put("password", password.key());
		states.put("passkey", passkey.key());
		states.put("emailCode", emailCode.key());

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("password", password == SignInPolicy.State.AVAILABLE);
		data.put("webauthn", passkey == SignInPolicy.State.AVAILABLE);
		data.put("emailCode", emailCode == SignInPolicy.State.AVAILABLE);
		data.put("states", states);
		AuthnFlow.sendSuccess(response, data);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String pathInfo = request.getPathInfo();
		if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
			password(request, response);
			return;
		}
		if ("/mfa".equals(pathInfo)) {
			secondFactor(request, response);
			return;
		}
		if ("/code/request".equals(pathInfo)) {
			requestEmailCode(request, response);
			return;
		}
		if ("/code".equals(pathInfo)) {
			emailCode(request, response);
			return;
		}
		AuthnFlow.sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint.", "UNKNOWN_ENDPOINT");
	}

	// ---- password ----------------------------------------------------------

	private void password(HttpServletRequest request, HttpServletResponse response) throws IOException {
		SignInPolicyImpl policy = Activator.getDefault().getSignInPolicy();
		if (policy.getPassword() != SignInPolicy.State.AVAILABLE) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_FORBIDDEN, "Password sign-in is disabled by the administrator.", "PASSWORD_DISABLED");
			return;
		}

		AdaptableMap<String, Object> body = AdaptableMap.<String, Object>newBuilder().putAll(AuthnFlow.readJson(request)).build();
		String username = body.getString("username");
		String password = body.getString("password");
		if (Strings.isBlank(username) || Strings.isBlank(password)) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Username and password are required.", "MISSING_CREDENTIALS");
			return;
		}

		IdpUser user = Activator.getDefault().getUserStore().authenticate(username, password);
		if (user == null) {
			LOG.warn("Authentication failed for user: {}", username);
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid username or password.", "INVALID_CREDENTIALS");
			return;
		}
		if (policy.isPasswordAllowedByOverride()) {
			LOG.warn("Password accepted for user {} although signIn.password is false: {} is set.", username, SignInPolicyImpl.ENV_ALLOW_PASSWORD);
		}

		continueAfterFirstFactor(request, response, user, AuthnFlow.FIRST_FACTOR_PASSWORD, SamlResponseBuilder.AUTHN_CONTEXT_PASSWORD);
	}

	// ---- email code --------------------------------------------------------

	private void requestEmailCode(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (Activator.getDefault().getSignInPolicy().getEmailCode() != SignInPolicy.State.AVAILABLE) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_FORBIDDEN, "Sign-in codes are not available.", "EMAIL_CODE_UNAVAILABLE");
			return;
		}

		AdaptableMap<String, Object> body = AdaptableMap.<String, Object>newBuilder().putAll(AuthnFlow.readJson(request)).build();
		String username = body.getString("username");
		if (Strings.isBlank(username)) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Username is required.", "MISSING_USERNAME");
			return;
		}
		username = username.trim();

		HttpSession session = request.getSession(true);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("expiresInSeconds", AuthnFlow.EMAIL_CODE_TTL_MILLIS / 1000);
		data.put("resendAfterSeconds", AuthnFlow.EMAIL_CODE_RESEND_MILLIS / 1000);

		if (!AuthnFlow.canSendEmailCode(session, username)) {
			// Too soon after the last one: the page shows the same message and
			// the previous code stays valid.
			LOG.info("Sign-in code for {} not resent (asked again within {} s)", username, AuthnFlow.EMAIL_CODE_RESEND_MILLIS / 1000);
			AuthnFlow.sendSuccess(response, data);
			return;
		}

		IdpUser user = Activator.getDefault().getUserStore().findSignInUser(username);
		String code = SignInCodeMail.generateCode();
		if (user == null || Strings.isBlank(user.getEmail())) {
			// Same answer as for a real user, and a code nobody knows, so the
			// response cannot tell an unknown account from one without an
			// address.
			LOG.info("Sign-in code requested for {}: no such user or no email address; nothing sent", username);
			AuthnFlow.startEmailCode(session, username, null, code);
			AuthnFlow.sendSuccess(response, data);
			return;
		}

		String lang = SignInCodeMail.languageTag(body.getString("lang"), request.getHeader("Accept-Language"));
		MailService mail = Activator.getDefault().getMailService();
		if (!user.isMailVerified()) {
			// Nobody has proven this address yet, so no code goes to it: a
			// wrong address would let a stranger in. The owner is told how to
			// confirm it; the page's answer stays the same as for a sent code.
			LOG.info("Sign-in code requested for {}: email address not confirmed; a notice sent instead", username);
			if (mail != null) {
				try {
					mail.send(SignInCodeMail.unconfirmedNotice(mail, user.getEmail(), user.getUsername(), user.getDisplayName(),
							Activator.getDefault().getConfiguration().getTotpIssuer(), lang));
				} catch (MailException ex) {
					LOG.warn("Could not send the unconfirmed-address notice to the address of {}", username, ex);
				}
			}
			AuthnFlow.startEmailCode(session, username, null, code);
			AuthnFlow.sendSuccess(response, data);
			return;
		}

		int minutes = (int) (AuthnFlow.EMAIL_CODE_TTL_MILLIS / 60000);
		try {
			if (mail == null) {
				throw new MailException(MailException.NOT_CONFIGURED, "No mail transport is available.");
			}
			mail.send(SignInCodeMail.signInCode(mail, user.getEmail(), user.getUsername(), user.getDisplayName(), code, minutes,
					Activator.getDefault().getConfiguration().getTotpIssuer(), lang));
		} catch (MailException ex) {
			LOG.error("Failed to send a sign-in code to the address of {}", username, ex);
			AuthnFlow.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "The code could not be sent.", "MAIL_FAILED");
			return;
		}
		AuthnFlow.startEmailCode(session, username, user.getUsername(), code);
		LOG.info("Sign-in code sent to the address of {}", username);
		AuthnFlow.sendSuccess(response, data);
	}

	private void emailCode(HttpServletRequest request, HttpServletResponse response) throws IOException {
		HttpSession session = request.getSession(false);
		if (session == null) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Please request a code first.", "SESSION_EXPIRED");
			return;
		}

		AdaptableMap<String, Object> body = AdaptableMap.<String, Object>newBuilder().putAll(AuthnFlow.readJson(request)).build();
		String code = body.getString("code");
		if (Strings.isBlank(code)) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "A code is required.", "MISSING_CODE");
			return;
		}

		AuthnFlow.EmailCodeResult result = AuthnFlow.verifyEmailCode(session, code);
		switch (result.getOutcome()) {
		case NO_CODE:
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Please request a code first.", "SESSION_EXPIRED");
			return;
		case TOO_MANY_ATTEMPTS:
			LOG.warn("Sign-in code rejected too often; starting over");
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Too many attempts. Please sign in again.", "TOO_MANY_ATTEMPTS");
			return;
		case INVALID:
			LOG.warn("Sign-in code rejected");
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "The code is not valid.", "INVALID_CODE");
			return;
		case VERIFIED:
			break;
		}

		IdpUser user = Activator.getDefault().getUserStore().findSignInUser(result.getUsername());
		if (user == null) {
			// Disabled since the code was sent, or the code was the decoy of an
			// unknown account (which never verifies; this is belt and braces).
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "This account cannot sign in.", "ACCOUNT_DISABLED");
			return;
		}

		continueAfterFirstFactor(request, response, user, AuthnFlow.FIRST_FACTOR_EMAIL_CODE, SamlResponseBuilder.AUTHN_CONTEXT_EMAIL_CODE);
	}

	// ---- after the first factor -------------------------------------------

	/**
	 * Asks for the second factor when the user has TOTP, else completes the
	 * sign-in with the context class of the first factor alone.
	 */
	private void continueAfterFirstFactor(HttpServletRequest request, HttpServletResponse response, IdpUser user,
			String firstFactor, String authnContextClassRef) throws IOException {
		String username = user.getUsername();
		boolean totpEnabled;
		try {
			Session session = CredentialStore.openSession();
			try {
				totpEnabled = CredentialStore.readTotp(session, username).isEnabled();
			} finally {
				session.logout();
			}
		} catch (Exception ex) {
			LOG.error("Failed to read the second-factor state of {}", username, ex);
			AuthnFlow.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process authentication request.", "SERVER_ERROR");
			return;
		}

		HttpSession session = request.getSession(true);
		if (totpEnabled) {
			AuthnFlow.startSecondFactor(session, user, firstFactor);
			LOG.info("First factor ({}) verified for user: {}; second factor required", firstFactor, username);
			AuthnFlow.sendStatus(response, "mfa_required", Map.of("methods", List.of("totp", "backupCode")));
			return;
		}

		LOG.info("User authenticated via API ({}): {}", firstFactor, username);
		AuthnFlow.complete(request, response, user, authnContextClassRef);
	}

	// ---- second factor -----------------------------------------------------

	private void secondFactor(HttpServletRequest request, HttpServletResponse response) throws IOException {
		HttpSession session = request.getSession(false);
		IdpUser user = session == null ? null : AuthnFlow.pendingUser(session);
		if (user == null) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Please sign in with your password first.", "SESSION_EXPIRED");
			return;
		}
		boolean afterEmailCode = AuthnFlow.FIRST_FACTOR_EMAIL_CODE.equals(AuthnFlow.firstFactor(session));
		String firstFactorOnly = afterEmailCode ? SamlResponseBuilder.AUTHN_CONTEXT_EMAIL_CODE : SamlResponseBuilder.AUTHN_CONTEXT_PASSWORD;
		String withTotp = afterEmailCode ? SamlResponseBuilder.AUTHN_CONTEXT_EMAIL_CODE_TIME_SYNC_TOKEN : SamlResponseBuilder.AUTHN_CONTEXT_TIME_SYNC_TOKEN;

		AdaptableMap<String, Object> body = AdaptableMap.<String, Object>newBuilder().putAll(AuthnFlow.readJson(request)).build();
		String method = body.getString("method");
		String code = body.getString("code");
		if (Strings.isBlank(code)) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "A code is required.", "MISSING_CODE");
			return;
		}

		boolean verified;
		try {
			Session jcrSession = CredentialStore.openSession();
			try {
				CredentialStore.TotpState state = CredentialStore.readTotp(jcrSession, user.getUsername());
				if (!state.isEnabled()) {
					// TOTP was switched off between the two steps; the first factor alone suffices.
					AuthnFlow.complete(request, response, user, firstFactorOnly);
					return;
				}
				if ("backupCode".equals(method)) {
					int index = BackupCodes.match(state.getBackupCodeHashes(), code);
					verified = index >= 0;
					if (verified) {
						List<String> remaining = new java.util.ArrayList<>(state.getBackupCodeHashes());
						remaining.remove(index);
						CredentialStore.setBackupCodeHashes(jcrSession, user.getUsername(), remaining);
						LOG.info("Backup code used by user: {} ({} left)", user.getUsername(), remaining.size());
					}
				} else {
					long counter = Totp.verify(state.getSecret(), code, state.getLastCounter());
					verified = counter >= 0;
					if (verified) {
						CredentialStore.setLastCounter(jcrSession, user.getUsername(), counter);
					}
				}
			} finally {
				jcrSession.logout();
			}
		} catch (Exception ex) {
			LOG.error("Failed to verify the second factor of {}", user.getUsername(), ex);
			AuthnFlow.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process authentication request.", "SERVER_ERROR");
			return;
		}

		if (!verified) {
			LOG.warn("Second factor rejected for user: {}", user.getUsername());
			if (AuthnFlow.recordFailedAttempt(session)) {
				AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "The code is not valid.", "INVALID_CODE");
			} else {
				AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Too many attempts. Please sign in again.", "TOO_MANY_ATTEMPTS");
			}
			return;
		}

		LOG.info("User authenticated via API with a second factor: {}", user.getUsername());
		AuthnFlow.complete(request, response, user, withTotp);
	}

}

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

import org.mintjams.idp.internal.Activator;
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
 * The password sign-in API at {@code /idp/api/login}.
 *
 * <p>{@code GET} describes the sign-in methods the login page may offer:</p>
 * <pre>{"status": "success", "data": {"password": true, "webauthn": true}}</pre>
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
 * <p>Errors are {@code {"status": "error", "message": "...", "code": "..."}}.
 * After too many failed second-factor attempts the code is {@code RESTART}
 * and the page has to start over from the password.</p>
 */
public class LoginApiServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(LoginApiServlet.class);

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("password", true);
		data.put("webauthn", Strings.isNotEmpty(Activator.getDefault().getConfiguration().getWebAuthnRpId()));
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
		AuthnFlow.sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint.");
	}

	private void password(HttpServletRequest request, HttpServletResponse response) throws IOException {
		AdaptableMap<String, Object> body = AdaptableMap.<String, Object>newBuilder().putAll(AuthnFlow.readJson(request)).build();
		String username = body.getString("username");
		String password = body.getString("password");
		if (Strings.isBlank(username) || Strings.isBlank(password)) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Username and password are required.");
			return;
		}

		IdpUser user = Activator.getDefault().getUserStore().authenticate(username, password);
		if (user == null) {
			LOG.warn("Authentication failed for user: {}", username);
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid username or password.");
			return;
		}

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
			AuthnFlow.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process authentication request.");
			return;
		}

		HttpSession session = request.getSession(true);
		if (totpEnabled) {
			AuthnFlow.startSecondFactor(session, user);
			LOG.info("Password verified for user: {}; second factor required", username);
			AuthnFlow.sendStatus(response, "mfa_required", Map.of("methods", List.of("totp", "backupCode")));
			return;
		}

		LOG.info("User authenticated via API: {}", username);
		AuthnFlow.complete(request, response, user, SamlResponseBuilder.AUTHN_CONTEXT_PASSWORD);
	}

	private void secondFactor(HttpServletRequest request, HttpServletResponse response) throws IOException {
		HttpSession session = request.getSession(false);
		IdpUser user = session == null ? null : AuthnFlow.pendingUser(session);
		if (user == null) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Please sign in with your password first.", "RESTART");
			return;
		}

		AdaptableMap<String, Object> body = AdaptableMap.<String, Object>newBuilder().putAll(AuthnFlow.readJson(request)).build();
		String method = body.getString("method");
		String code = body.getString("code");
		if (Strings.isBlank(code)) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "A code is required.");
			return;
		}

		boolean verified;
		try {
			Session jcrSession = CredentialStore.openSession();
			try {
				CredentialStore.TotpState state = CredentialStore.readTotp(jcrSession, user.getUsername());
				if (!state.isEnabled()) {
					// TOTP was switched off between the two steps; the password alone suffices.
					AuthnFlow.complete(request, response, user, SamlResponseBuilder.AUTHN_CONTEXT_PASSWORD);
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
			AuthnFlow.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process authentication request.");
			return;
		}

		if (!verified) {
			LOG.warn("Second factor rejected for user: {}", user.getUsername());
			if (AuthnFlow.recordFailedAttempt(session)) {
				AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "The code is not valid.", "INVALID_CODE");
			} else {
				AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Too many attempts. Please sign in again.", "RESTART");
			}
			return;
		}

		LOG.info("User authenticated via API with a second factor: {}", user.getUsername());
		AuthnFlow.complete(request, response, user, SamlResponseBuilder.AUTHN_CONTEXT_TIME_SYNC_TOKEN);
	}

}

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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.Session;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.IdpConfiguration;
import org.mintjams.idp.internal.mfa.CredentialStore;
import org.mintjams.idp.internal.model.IdpUser;
import org.mintjams.idp.internal.saml.SamlResponseBuilder;
import org.mintjams.idp.internal.webauthn.WebAuthnException;
import org.mintjams.idp.internal.webauthn.WebAuthnVerifier;
import org.mintjams.tools.lang.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The passkey sign-in API at {@code /idp/api/webauthn}.
 *
 * <p>{@code POST /options} issues a challenge (kept in the session) and returns
 * the {@code PublicKeyCredentialRequestOptions} for
 * {@code navigator.credentials.get()}; no user name is needed because
 * passkeys are registered as discoverable credentials.</p>
 *
 * <p>{@code POST /verify} takes the resulting {@code PublicKeyCredential} as
 * JSON (binary members base64url-encoded), verifies the assertion against
 * the stored passkey and completes the sign-in with the same payload as the
 * password API.</p>
 */
public class WebAuthnApiServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(WebAuthnApiServlet.class);

	/** How long the browser gets to complete the ceremony. */
	private static final long TIMEOUT_MILLIS = 120_000;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		IdpConfiguration config = Activator.getDefault().getConfiguration();
		String rpId = config.getWebAuthnRpId();
		if (Strings.isEmpty(rpId)) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_NOT_FOUND, "Passkeys are not available.", "NOT_AVAILABLE");
			return;
		}
		String pathInfo = request.getPathInfo();
		if ("/options".equals(pathInfo)) {
			options(request, response, rpId);
			return;
		}
		if ("/verify".equals(pathInfo)) {
			verify(request, response, config, rpId);
			return;
		}
		AuthnFlow.sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint.");
	}

	private void options(HttpServletRequest request, HttpServletResponse response, String rpId) throws IOException {
		byte[] challenge = new byte[32];
		RANDOM.nextBytes(challenge);
		HttpSession session = request.getSession(true);
		session.setAttribute(AuthnFlow.SESSION_WEBAUTHN_CHALLENGE, challenge);
		session.setAttribute(AuthnFlow.SESSION_WEBAUTHN_CHALLENGE_SINCE, System.currentTimeMillis());

		Map<String, Object> options = new LinkedHashMap<>();
		options.put("challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(challenge));
		options.put("rpId", rpId);
		options.put("timeout", TIMEOUT_MILLIS);
		options.put("userVerification", "required");
		options.put("allowCredentials", Collections.emptyList());
		AuthnFlow.sendSuccess(response, options);
	}

	private void verify(HttpServletRequest request, HttpServletResponse response, IdpConfiguration config, String rpId) throws IOException {
		HttpSession session = request.getSession(false);
		byte[] challenge = session == null ? null : (byte[]) session.getAttribute(AuthnFlow.SESSION_WEBAUTHN_CHALLENGE);
		Object since = session == null ? null : session.getAttribute(AuthnFlow.SESSION_WEBAUTHN_CHALLENGE_SINCE);
		if (session != null) {
			session.removeAttribute(AuthnFlow.SESSION_WEBAUTHN_CHALLENGE);
			session.removeAttribute(AuthnFlow.SESSION_WEBAUTHN_CHALLENGE_SINCE);
		}
		if (challenge == null || !(since instanceof Long) || System.currentTimeMillis() - (Long) since > AuthnFlow.STEP_TTL_MILLIS) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "The sign-in request has expired. Please try again.", "RESTART");
			return;
		}

		Map<String, Object> credential = AuthnFlow.readJson(request);
		Object rawId = credential.containsKey("rawId") ? credential.get("rawId") : credential.get("id");
		if (!(rawId instanceof String) || ((String) rawId).isEmpty()) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "The credential is incomplete.");
			return;
		}
		byte[] credentialId;
		try {
			credentialId = Base64.getUrlDecoder().decode((String) rawId);
		} catch (IllegalArgumentException ex) {
			AuthnFlow.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "The credential is malformed.");
			return;
		}

		IdpUser user;
		try {
			Session jcrSession = CredentialStore.openSession();
			try {
				CredentialStore.Passkey passkey = CredentialStore.findPasskey(jcrSession, credentialId);
				if (passkey == null) {
					LOG.warn("Passkey sign-in with an unknown credential ({})", CredentialStore.passkeyId(credentialId));
					AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "This passkey is not registered.", "UNKNOWN_CREDENTIAL");
					return;
				}
				WebAuthnVerifier.Assertion assertion;
				try {
					assertion = WebAuthnVerifier.verifyAssertion(credential, challenge, rpId, config.getWebAuthnOrigins(),
							passkey.getPublicKey(), passkey.getAlgorithm(), passkey.getSignCount());
				} catch (WebAuthnException ex) {
					LOG.warn("Passkey sign-in rejected for {}: {}", passkey.getUsername(), ex.getMessage());
					AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "The passkey could not be verified.", "INVALID_CREDENTIAL");
					return;
				}
				byte[] userHandle = CredentialStore.getUserHandle(jcrSession, passkey.getUsername());
				if (assertion.getUserHandle() != null && userHandle != null &&
						!MessageDigest.isEqual(assertion.getUserHandle(), userHandle)) {
					LOG.warn("Passkey sign-in rejected for {}: the user handle does not match", passkey.getUsername());
					AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "The passkey could not be verified.", "INVALID_CREDENTIAL");
					return;
				}
				user = Activator.getDefault().getUserStore().findSignInUser(passkey.getUsername());
				if (user == null) {
					LOG.warn("Passkey sign-in rejected: user {} cannot sign in", passkey.getUsername());
					AuthnFlow.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "This account cannot sign in.", "ACCOUNT_DISABLED");
					return;
				}
				CredentialStore.updatePasskeyUsage(jcrSession, passkey, assertion.getSignCount(), assertion.isBackupState());
			} finally {
				jcrSession.logout();
			}
		} catch (Exception ex) {
			LOG.error("Passkey sign-in failed", ex);
			AuthnFlow.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process authentication request.");
			return;
		}

		LOG.info("User authenticated via passkey: {}", user.getUsername());
		AuthnFlow.complete(request, response, user, SamlResponseBuilder.AUTHN_CONTEXT_WEBAUTHN);
	}

}

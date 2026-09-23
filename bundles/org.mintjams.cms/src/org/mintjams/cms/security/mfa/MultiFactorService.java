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

package org.mintjams.cms.security.mfa;

import java.util.List;
import java.util.Map;

/**
 * Second-factor enrollment for the identity provider's users: time-based
 * one-time passwords (TOTP, RFC 6238) with backup codes, and passkeys
 * (WebAuthn).
 *
 * <p>The identity provider bundle publishes this as an OSGi service. Callers
 * (the platform GraphQL layer) authenticate and authorize the caller before
 * invoking it; the service itself trusts the {@code username} it is given and
 * writes with its own service session.</p>
 *
 * <p>Enrollment state that spans two calls (a TOTP secret waiting for its
 * first code, a WebAuthn challenge waiting for the created credential) is
 * stored with the user's credentials, so the second call may reach any node
 * of a cluster.</p>
 */
public interface MultiFactorService {

	/**
	 * Returns the user's enrollment state.
	 */
	MultiFactorStatus getStatus(String username) throws MultiFactorException;

	/**
	 * Generates a new TOTP secret and stores it as pending. The secret only
	 * becomes active once {@link #confirmTotpEnrollment} verifies a code from
	 * it. Calling this again replaces the pending secret; an active secret is
	 * left untouched until the new one is confirmed.
	 */
	TotpEnrollment beginTotpEnrollment(String username) throws MultiFactorException;

	/**
	 * Verifies the first code from the pending secret, activates TOTP and
	 * issues a fresh set of backup codes.
	 *
	 * @return the backup codes in plain text; they are not retrievable later
	 */
	List<String> confirmTotpEnrollment(String username, String code) throws MultiFactorException;

	/**
	 * Disables TOTP and discards the secret and the backup codes.
	 */
	void disableTotp(String username) throws MultiFactorException;

	/**
	 * Replaces the backup codes. TOTP must be enabled.
	 *
	 * @return the new backup codes in plain text
	 */
	List<String> regenerateBackupCodes(String username) throws MultiFactorException;

	/**
	 * Starts a passkey registration and returns the
	 * {@code PublicKeyCredentialCreationOptions} to pass to
	 * {@code navigator.credentials.create()}. Binary members (challenge,
	 * user.id, excludeCredentials[].id) are base64url strings.
	 */
	Map<String, Object> beginPasskeyRegistration(String username) throws MultiFactorException;

	/**
	 * Completes a passkey registration with the credential the browser
	 * created. {@code credential} is the {@code PublicKeyCredential} as JSON
	 * (binary members base64url-encoded).
	 */
	PasskeyInfo finishPasskeyRegistration(String username, Map<String, Object> credential, String displayName) throws MultiFactorException;

	void renamePasskey(String username, String passkeyId, String displayName) throws MultiFactorException;

	void deletePasskey(String username, String passkeyId) throws MultiFactorException;

	/**
	 * Removes every second factor of the user (used when the user is deleted).
	 */
	void removeAll(String username) throws MultiFactorException;

}

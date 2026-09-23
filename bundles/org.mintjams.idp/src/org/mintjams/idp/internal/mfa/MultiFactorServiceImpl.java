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

package org.mintjams.idp.internal.mfa;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.mintjams.cms.security.mfa.MultiFactorException;
import org.mintjams.cms.security.mfa.MultiFactorService;
import org.mintjams.cms.security.mfa.MultiFactorStatus;
import org.mintjams.cms.security.mfa.PasskeyInfo;
import org.mintjams.cms.security.mfa.TotpEnrollment;
import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.IdpConfiguration;
import org.mintjams.idp.internal.model.IdpUser;
import org.mintjams.idp.internal.webauthn.CoseKeys;
import org.mintjams.idp.internal.webauthn.WebAuthnException;
import org.mintjams.idp.internal.webauthn.WebAuthnVerifier;
import org.mintjams.tools.lang.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MultiFactorService} the IdP publishes for the platform: TOTP and
 * passkey enrollment on top of {@link CredentialStore}.
 */
public class MultiFactorServiceImpl implements MultiFactorService {

	private static final Logger LOG = LoggerFactory.getLogger(MultiFactorServiceImpl.class);

	/** How long the browser gets to complete a registration ceremony. */
	private static final long REGISTRATION_TIMEOUT_MILLIS = 120_000;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Override
	public MultiFactorStatus getStatus(String username) throws MultiFactorException {
		Session session = openSession();
		try {
			CredentialStore.TotpState totp = CredentialStore.readTotp(session, username);
			List<PasskeyInfo> passkeys = new ArrayList<>();
			for (CredentialStore.Passkey passkey : CredentialStore.listPasskeys(session, username)) {
				passkeys.add(toInfo(passkey));
			}
			return new MultiFactorStatus(totp.isEnabled(), totp.isEnabled() ? totp.getBackupCodeHashes().size() : 0,
					passkeys, Strings.isNotEmpty(config().getWebAuthnRpId()));
		} catch (Exception ex) {
			throw wrap("Failed to read the second-factor state of " + username, ex);
		} finally {
			session.logout();
		}
	}

	@Override
	public TotpEnrollment beginTotpEnrollment(String username) throws MultiFactorException {
		IdpUser user = requireUser(username);
		Session session = openSession();
		try {
			String secret = Totp.generateSecret();
			CredentialStore.setPendingTotpSecret(session, username, secret);
			String issuer = config().getTotpIssuer();
			String account = Strings.isNotEmpty(user.getEmail()) ? user.getEmail() : username;
			return new TotpEnrollment(secret, issuer, account, Totp.buildOtpauthUri(issuer, account, secret));
		} catch (Exception ex) {
			throw wrap("Failed to start TOTP enrollment for " + username, ex);
		} finally {
			session.logout();
		}
	}

	@Override
	public List<String> confirmTotpEnrollment(String username, String code) throws MultiFactorException {
		requireUser(username);
		Session session = openSession();
		try {
			CredentialStore.TotpState state = CredentialStore.readTotp(session, username);
			String pending = state.getPendingSecret();
			if (pending == null) {
				throw new MultiFactorException(MultiFactorException.NOT_ENROLLED, "There is no enrollment to confirm, or it has expired. Start again.");
			}
			long counter = Totp.verify(pending, code, -1);
			if (counter < 0) {
				throw new MultiFactorException(MultiFactorException.INVALID_CODE, "The code is not valid.");
			}
			List<String> codes = BackupCodes.generate();
			CredentialStore.activateTotp(session, username, pending, BackupCodes.hash(codes));
			CredentialStore.setLastCounter(session, username, counter);
			LOG.info("TOTP enabled for user: {}", username);
			return codes;
		} catch (MultiFactorException ex) {
			throw ex;
		} catch (Exception ex) {
			throw wrap("Failed to confirm TOTP enrollment for " + username, ex);
		} finally {
			session.logout();
		}
	}

	@Override
	public void disableTotp(String username) throws MultiFactorException {
		Session session = openSession();
		try {
			CredentialStore.disableTotp(session, username);
			LOG.info("TOTP disabled for user: {}", username);
		} catch (Exception ex) {
			throw wrap("Failed to disable TOTP for " + username, ex);
		} finally {
			session.logout();
		}
	}

	@Override
	public List<String> regenerateBackupCodes(String username) throws MultiFactorException {
		Session session = openSession();
		try {
			if (!CredentialStore.readTotp(session, username).isEnabled()) {
				throw new MultiFactorException(MultiFactorException.INVALID_STATE, "Backup codes need TOTP to be enabled.");
			}
			List<String> codes = BackupCodes.generate();
			CredentialStore.setBackupCodeHashes(session, username, BackupCodes.hash(codes));
			LOG.info("Backup codes regenerated for user: {}", username);
			return codes;
		} catch (MultiFactorException ex) {
			throw ex;
		} catch (Exception ex) {
			throw wrap("Failed to regenerate backup codes for " + username, ex);
		} finally {
			session.logout();
		}
	}

	@Override
	public Map<String, Object> beginPasskeyRegistration(String username) throws MultiFactorException {
		IdpConfiguration config = config();
		String rpId = config.getWebAuthnRpId();
		if (Strings.isEmpty(rpId)) {
			throw new MultiFactorException(MultiFactorException.NOT_AVAILABLE, "Passkeys are not configured on this server.");
		}
		IdpUser user = requireUser(username);
		Session session = openSession();
		try {
			byte[] challenge = new byte[32];
			RANDOM.nextBytes(challenge);
			byte[] userHandle = CredentialStore.getOrCreateUserHandle(session, username);
			CredentialStore.setPendingRegistration(session, username, challenge);

			Map<String, Object> options = new LinkedHashMap<>();
			Map<String, Object> rp = new LinkedHashMap<>();
			rp.put("id", rpId);
			rp.put("name", config.getWebAuthnRpName());
			options.put("rp", rp);
			Map<String, Object> userEntity = new LinkedHashMap<>();
			userEntity.put("id", base64url(userHandle));
			userEntity.put("name", username);
			userEntity.put("displayName", Strings.isNotEmpty(user.getDisplayName()) ? user.getDisplayName() : username);
			options.put("user", userEntity);
			options.put("challenge", base64url(challenge));
			List<Map<String, Object>> params = new ArrayList<>();
			for (Long alg : CoseKeys.SUPPORTED_ALGORITHMS) {
				params.add(Map.of("type", "public-key", "alg", alg));
			}
			options.put("pubKeyCredParams", params);
			options.put("timeout", REGISTRATION_TIMEOUT_MILLIS);
			options.put("attestation", "none");
			Map<String, Object> selection = new LinkedHashMap<>();
			// Discoverable credentials let the user sign in without typing a name,
			// and user verification makes the passkey a second factor by itself.
			selection.put("residentKey", "required");
			selection.put("requireResidentKey", true);
			selection.put("userVerification", "required");
			options.put("authenticatorSelection", selection);
			List<Map<String, Object>> exclude = new ArrayList<>();
			for (CredentialStore.Passkey passkey : CredentialStore.listPasskeys(session, username)) {
				Map<String, Object> descriptor = new LinkedHashMap<>();
				descriptor.put("type", "public-key");
				descriptor.put("id", base64url(passkey.getCredentialId()));
				if (!passkey.getTransports().isEmpty()) {
					descriptor.put("transports", passkey.getTransports());
				}
				exclude.add(descriptor);
			}
			options.put("excludeCredentials", exclude);
			return options;
		} catch (Exception ex) {
			throw wrap("Failed to start passkey registration for " + username, ex);
		} finally {
			session.logout();
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public PasskeyInfo finishPasskeyRegistration(String username, Map<String, Object> credential, String displayName) throws MultiFactorException {
		IdpConfiguration config = config();
		String rpId = config.getWebAuthnRpId();
		if (Strings.isEmpty(rpId)) {
			throw new MultiFactorException(MultiFactorException.NOT_AVAILABLE, "Passkeys are not configured on this server.");
		}
		requireUser(username);
		Session session = openSession();
		try {
			byte[] challenge = CredentialStore.takePendingRegistration(session, username);
			if (challenge == null) {
				throw new MultiFactorException(MultiFactorException.NOT_ENROLLED, "There is no registration in progress, or it has expired. Start again.");
			}
			WebAuthnVerifier.Registration registration;
			try {
				registration = WebAuthnVerifier.verifyRegistration(credential, challenge, rpId, config.getWebAuthnOrigins());
			} catch (WebAuthnException ex) {
				LOG.warn("Passkey registration rejected for {}: {}", username, ex.getMessage());
				throw new MultiFactorException(MultiFactorException.INVALID_CREDENTIAL, "The passkey could not be verified.", ex);
			}
			List<String> transports = new ArrayList<>();
			Object response = credential.get("response");
			if (response instanceof Map && ((Map<String, Object>) response).get("transports") instanceof List) {
				for (Object t : (List<Object>) ((Map<String, Object>) response).get("transports")) {
					if (t != null) {
						transports.add(t.toString());
					}
				}
			}
			if (CredentialStore.findPasskey(session, registration.getCredentialId()) != null) {
				throw new MultiFactorException(MultiFactorException.INVALID_STATE, "This passkey is already registered.");
			}
			CredentialStore.Passkey passkey = new CredentialStore.Passkey(registration.getCredentialId(), registration.getPublicKey(),
					registration.getAlgorithm(), registration.getSignCount(), registration.getAaguid(), transports,
					Strings.isNotEmpty(displayName) ? displayName.trim() : defaultPasskeyName(credential),
					registration.isBackupEligible(), registration.isBackupState());
			PasskeyInfo info = toInfo(CredentialStore.addPasskey(session, username, passkey));
			LOG.info("Passkey registered for user: {} ({})", username, info.getId());
			return info;
		} catch (MultiFactorException ex) {
			throw ex;
		} catch (Exception ex) {
			throw wrap("Failed to register a passkey for " + username, ex);
		} finally {
			session.logout();
		}
	}

	@Override
	public void renamePasskey(String username, String passkeyId, String displayName) throws MultiFactorException {
		Session session = openSession();
		try {
			if (!CredentialStore.renamePasskey(session, username, passkeyId, displayName)) {
				throw new MultiFactorException(MultiFactorException.NOT_FOUND, "The passkey was not found.");
			}
		} catch (MultiFactorException ex) {
			throw ex;
		} catch (Exception ex) {
			throw wrap("Failed to rename a passkey of " + username, ex);
		} finally {
			session.logout();
		}
	}

	@Override
	public void deletePasskey(String username, String passkeyId) throws MultiFactorException {
		Session session = openSession();
		try {
			if (!CredentialStore.deletePasskey(session, username, passkeyId)) {
				throw new MultiFactorException(MultiFactorException.NOT_FOUND, "The passkey was not found.");
			}
			LOG.info("Passkey deleted for user: {} ({})", username, passkeyId);
		} catch (MultiFactorException ex) {
			throw ex;
		} catch (Exception ex) {
			throw wrap("Failed to delete a passkey of " + username, ex);
		} finally {
			session.logout();
		}
	}

	@Override
	public void removeAll(String username) throws MultiFactorException {
		Session session = openSession();
		try {
			CredentialStore.removeAll(session, username);
		} catch (Exception ex) {
			throw wrap("Failed to remove the second factors of " + username, ex);
		} finally {
			session.logout();
		}
	}

	// ---- helpers ------------------------------------------------------------

	static PasskeyInfo toInfo(CredentialStore.Passkey passkey) {
		return new PasskeyInfo(passkey.getId(), passkey.getDisplayName(), passkey.getCreated(), passkey.getLastUsed(),
				passkey.getTransports(), passkey.isBackupState());
	}

	private static String defaultPasskeyName(Map<String, Object> credential) {
		Object attachment = credential.get("authenticatorAttachment");
		if ("cross-platform".equals(attachment)) {
			return "Security key";
		}
		return "Passkey";
	}

	private static IdpUser requireUser(String username) throws MultiFactorException {
		if (Strings.isEmpty(username)) {
			throw new MultiFactorException(MultiFactorException.NOT_FOUND, "A user name is required.");
		}
		IdpUser user = Activator.getDefault().getUserStore().findSignInUser(username);
		if (user == null) {
			throw new MultiFactorException(MultiFactorException.NOT_FOUND, "The user does not exist or cannot sign in.");
		}
		return user;
	}

	private static Session openSession() throws MultiFactorException {
		try {
			return CredentialStore.openSession();
		} catch (Exception ex) {
			throw wrap("The credential store is not available.", ex);
		}
	}

	private static MultiFactorException wrap(String message, Exception ex) {
		LOG.error(message, ex);
		return new MultiFactorException("INTERNAL_ERROR", message, ex);
	}

	private static IdpConfiguration config() {
		return Activator.getDefault().getConfiguration();
	}

	private static String base64url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}

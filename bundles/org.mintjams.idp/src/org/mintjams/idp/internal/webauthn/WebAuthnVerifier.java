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

package org.mintjams.idp.internal.webauthn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

/**
 * Server-side verification of the two WebAuthn ceremonies (Level 2, sections
 * 7.1 and 7.2) as a relying party that asks for {@code attestation: "none"}
 * and user verification. Attestation statements are therefore not validated;
 * what is checked is the client data (type, challenge, origin), the
 * authenticator data (relying-party id hash, presence and verification
 * flags, signature counter) and, at sign-in, the assertion signature.
 *
 * <p>Credentials arrive as the JSON the browser produces from a
 * {@code PublicKeyCredential}, with every binary member base64url-encoded.</p>
 */
public final class WebAuthnVerifier {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

	private static final int FLAG_UP = 0x01;
	private static final int FLAG_UV = 0x04;
	private static final int FLAG_BE = 0x08;
	private static final int FLAG_BS = 0x10;
	private static final int FLAG_AT = 0x40;

	private WebAuthnVerifier() {}

	/** The outcome of a verified registration. */
	public static final class Registration {
		private byte[] fCredentialId;
		private byte[] fPublicKey;
		private long fAlgorithm;
		private long fSignCount;
		private String fAaguid;
		private boolean fBackupEligible;
		private boolean fBackupState;

		public byte[] getCredentialId() {
			return fCredentialId;
		}

		/** The COSE-encoded credential public key. */
		public byte[] getPublicKey() {
			return fPublicKey;
		}

		public long getAlgorithm() {
			return fAlgorithm;
		}

		public long getSignCount() {
			return fSignCount;
		}

		public String getAaguid() {
			return fAaguid;
		}

		public boolean isBackupEligible() {
			return fBackupEligible;
		}

		public boolean isBackupState() {
			return fBackupState;
		}
	}

	/** The outcome of a verified assertion. */
	public static final class Assertion {
		private long fSignCount;
		private byte[] fUserHandle;
		private boolean fBackupState;

		public long getSignCount() {
			return fSignCount;
		}

		/** The user handle the authenticator returned, or null. */
		public byte[] getUserHandle() {
			return fUserHandle;
		}

		public boolean isBackupState() {
			return fBackupState;
		}
	}

	/**
	 * Verifies a registration ({@code navigator.credentials.create()} result).
	 */
	@SuppressWarnings("unchecked")
	public static Registration verifyRegistration(Map<String, Object> credential, byte[] expectedChallenge,
			String rpId, List<String> allowedOrigins) throws WebAuthnException {
		Map<String, Object> response = map(credential, "response");
		byte[] clientDataJSON = base64url(response, "clientDataJSON");
		verifyClientData(clientDataJSON, "webauthn.create", expectedChallenge, allowedOrigins);

		byte[] attestationObject = base64url(response, "attestationObject");
		Map<String, Object> attestation;
		try {
			attestation = CBOR.readValue(attestationObject, Map.class);
		} catch (Exception ex) {
			throw new WebAuthnException("The attestation object is not valid CBOR.", ex);
		}
		Object authDataValue = attestation.get("authData");
		if (!(authDataValue instanceof byte[])) {
			throw new WebAuthnException("The attestation object has no authenticator data.");
		}
		byte[] authData = (byte[]) authDataValue;
		AuthenticatorData parsed = parseAuthenticatorData(authData, rpId, true);

		byte[] rawId = base64url(credential, credential.containsKey("rawId") ? "rawId" : "id");
		if (!Arrays.equals(rawId, parsed.credentialId)) {
			throw new WebAuthnException("The credential id does not match the attested credential.");
		}

		Map<String, Object> cose = CoseKeys.decode(parsed.credentialPublicKey);
		long algorithm = CoseKeys.algorithm(cose);
		if (!CoseKeys.SUPPORTED_ALGORITHMS.contains(algorithm)) {
			throw new WebAuthnException("Unsupported credential algorithm: " + algorithm);
		}
		// Make sure the key is usable before it is stored.
		CoseKeys.toPublicKey(cose);

		Registration registration = new Registration();
		registration.fCredentialId = parsed.credentialId;
		registration.fPublicKey = parsed.credentialPublicKey;
		registration.fAlgorithm = algorithm;
		registration.fSignCount = parsed.signCount;
		registration.fAaguid = parsed.aaguid;
		registration.fBackupEligible = parsed.backupEligible;
		registration.fBackupState = parsed.backupState;
		return registration;
	}

	/**
	 * Verifies an assertion ({@code navigator.credentials.get()} result)
	 * against the stored credential.
	 */
	public static Assertion verifyAssertion(Map<String, Object> credential, byte[] expectedChallenge, String rpId,
			List<String> allowedOrigins, byte[] cosePublicKey, long algorithm, long storedSignCount) throws WebAuthnException {
		Map<String, Object> response = map(credential, "response");
		byte[] clientDataJSON = base64url(response, "clientDataJSON");
		verifyClientData(clientDataJSON, "webauthn.get", expectedChallenge, allowedOrigins);

		byte[] authData = base64url(response, "authenticatorData");
		AuthenticatorData parsed = parseAuthenticatorData(authData, rpId, false);

		byte[] signature = base64url(response, "signature");
		byte[] signed;
		try {
			byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataJSON);
			signed = ByteBuffer.allocate(authData.length + clientDataHash.length).put(authData).put(clientDataHash).array();
		} catch (Exception ex) {
			throw new WebAuthnException("SHA-256 is not available.", ex);
		}

		try {
			Map<String, Object> cose = CoseKeys.decode(cosePublicKey);
			if (CoseKeys.algorithm(cose) != algorithm) {
				throw new WebAuthnException("The stored key does not match its algorithm.");
			}
			PublicKey publicKey = CoseKeys.toPublicKey(cose);
			Signature verifier = Signature.getInstance(CoseKeys.signatureAlgorithm(algorithm));
			verifier.initVerify(publicKey);
			verifier.update(signed);
			if (!verifier.verify(signature)) {
				throw new WebAuthnException("The assertion signature is invalid.");
			}
		} catch (WebAuthnException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new WebAuthnException("The assertion signature could not be verified.", ex);
		}

		// A counter that does not advance points at a cloned authenticator.
		// Synced passkeys legitimately report 0 forever, which is why a pair of
		// zeros is accepted.
		if ((storedSignCount != 0 || parsed.signCount != 0) && parsed.signCount <= storedSignCount) {
			throw new WebAuthnException("The signature counter did not advance (" + parsed.signCount + " <= " + storedSignCount + ").");
		}

		Assertion assertion = new Assertion();
		assertion.fSignCount = parsed.signCount;
		assertion.fBackupState = parsed.backupState;
		Object userHandle = response.get("userHandle");
		if (userHandle instanceof String && !((String) userHandle).isEmpty()) {
			assertion.fUserHandle = Base64.getUrlDecoder().decode((String) userHandle);
		}
		return assertion;
	}

	// ---- pieces ---------------------------------------------------------------

	private static final class AuthenticatorData {
		long signCount;
		boolean backupEligible;
		boolean backupState;
		String aaguid;
		byte[] credentialId;
		byte[] credentialPublicKey;
	}

	@SuppressWarnings("unchecked")
	private static void verifyClientData(byte[] clientDataJSON, String expectedType, byte[] expectedChallenge,
			List<String> allowedOrigins) throws WebAuthnException {
		Map<String, Object> clientData;
		try {
			clientData = JSON.readValue(clientDataJSON, Map.class);
		} catch (Exception ex) {
			throw new WebAuthnException("The client data is not valid JSON.", ex);
		}
		if (!expectedType.equals(clientData.get("type"))) {
			throw new WebAuthnException("Unexpected client data type: " + clientData.get("type"));
		}
		Object challenge = clientData.get("challenge");
		if (!(challenge instanceof String)) {
			throw new WebAuthnException("The client data has no challenge.");
		}
		byte[] presented;
		try {
			presented = Base64.getUrlDecoder().decode((String) challenge);
		} catch (IllegalArgumentException ex) {
			throw new WebAuthnException("The client data challenge is not base64url.", ex);
		}
		if (!MessageDigest.isEqual(presented, expectedChallenge)) {
			throw new WebAuthnException("The challenge does not match.");
		}
		Object origin = clientData.get("origin");
		if (!(origin instanceof String) || !allowedOrigins.contains(origin)) {
			throw new WebAuthnException("Unexpected origin: " + origin);
		}
	}

	private static AuthenticatorData parseAuthenticatorData(byte[] authData, String rpId, boolean expectCredential)
			throws WebAuthnException {
		if (authData.length < 37) {
			throw new WebAuthnException("The authenticator data is too short.");
		}
		byte[] rpIdHash;
		try {
			rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			throw new WebAuthnException("SHA-256 is not available.", ex);
		}
		if (!MessageDigest.isEqual(rpIdHash, Arrays.copyOfRange(authData, 0, 32))) {
			throw new WebAuthnException("The relying-party id hash does not match " + rpId + ".");
		}
		int flags = authData[32] & 0xff;
		if ((flags & FLAG_UP) == 0) {
			throw new WebAuthnException("User presence was not verified.");
		}
		if ((flags & FLAG_UV) == 0) {
			throw new WebAuthnException("User verification was not performed.");
		}
		AuthenticatorData parsed = new AuthenticatorData();
		parsed.signCount = ByteBuffer.wrap(authData, 33, 4).getInt() & 0xffffffffL;
		parsed.backupEligible = (flags & FLAG_BE) != 0;
		parsed.backupState = (flags & FLAG_BS) != 0;

		if ((flags & FLAG_AT) != 0) {
			if (authData.length < 55) {
				throw new WebAuthnException("The attested credential data is truncated.");
			}
			parsed.aaguid = formatAaguid(Arrays.copyOfRange(authData, 37, 53));
			int credentialIdLength = ((authData[53] & 0xff) << 8) | (authData[54] & 0xff);
			int keyOffset = 55 + credentialIdLength;
			if (credentialIdLength == 0 || credentialIdLength > 1023 || authData.length <= keyOffset) {
				throw new WebAuthnException("The attested credential id is invalid.");
			}
			parsed.credentialId = Arrays.copyOfRange(authData, 55, keyOffset);
			parsed.credentialPublicKey = Arrays.copyOfRange(authData, keyOffset, authData.length);
		} else if (expectCredential) {
			throw new WebAuthnException("The authenticator data carries no attested credential.");
		}
		return parsed;
	}

	private static String formatAaguid(byte[] aaguid) {
		StringBuilder sb = new StringBuilder(36);
		for (int i = 0; i < aaguid.length; i++) {
			if (i == 4 || i == 6 || i == 8 || i == 10) {
				sb.append('-');
			}
			sb.append(String.format("%02x", aaguid[i]));
		}
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Map<String, Object> parent, String name) throws WebAuthnException {
		Object value = parent.get(name);
		if (!(value instanceof Map)) {
			throw new WebAuthnException("The credential has no " + name + ".");
		}
		return (Map<String, Object>) value;
	}

	private static byte[] base64url(Map<String, Object> parent, String name) throws WebAuthnException {
		Object value = parent.get(name);
		if (!(value instanceof String) || ((String) value).isEmpty()) {
			throw new WebAuthnException("The credential has no " + name + ".");
		}
		try {
			return Base64.getUrlDecoder().decode((String) value);
		} catch (IllegalArgumentException ex) {
			throw new WebAuthnException("The credential member " + name + " is not base64url.", ex);
		}
	}

}

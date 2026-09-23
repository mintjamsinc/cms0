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

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.mintjams.cms.security.Encryptor;
import org.mintjams.cms.security.UserCredentials;
import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.security.IdpServiceCredentials;
import org.mintjams.jcr.util.JCRs;

/**
 * The identity provider's part of the credential store (see
 * {@link UserCredentials} for the layout and the access model): the TOTP
 * secret with its backup codes, the WebAuthn user handle and pending
 * registration challenge, and the registered passkeys.
 *
 * <p>Every method takes a service session opened with {@link #openSession()}
 * and saves its own changes. Secrets are encrypted with the CMS encryptor
 * before they are written.</p>
 */
public final class CredentialStore {

	static final String TOTP_NODE = "totp";
	static final String WEBAUTHN_NODE = "webauthn";
	static final String PASSKEYS_FOLDER = "passkeys";

	/** How long a pending TOTP secret or registration challenge stays usable. */
	static final long PENDING_TTL_MILLIS = 10L * 60 * 1000;

	private static final SecureRandom RANDOM = new SecureRandom();

	private CredentialStore() {}

	/**
	 * Opens the service session the store works with. The caller logs it out.
	 */
	public static Session openSession() throws RepositoryException {
		return Activator.getDefault().getRepository().login(new IdpServiceCredentials(), "system");
	}

	// ---- TOTP ---------------------------------------------------------------

	/** The stored TOTP state of a user. */
	public static final class TotpState {
		private boolean fEnabled;
		private String fSecret;
		private String fPendingSecret;
		private Date fPendingSince;
		private long fLastCounter = -1;
		private List<String> fBackupCodeHashes = new ArrayList<>();

		public boolean isEnabled() {
			return fEnabled;
		}

		/** The active secret (Base32), or null. */
		public String getSecret() {
			return fSecret;
		}

		/** The secret waiting for its first code (Base32), or null when none or expired. */
		public String getPendingSecret() {
			if (fPendingSecret == null || fPendingSince == null) {
				return null;
			}
			if (System.currentTimeMillis() - fPendingSince.getTime() > PENDING_TTL_MILLIS) {
				return null;
			}
			return fPendingSecret;
		}

		public long getLastCounter() {
			return fLastCounter;
		}

		public List<String> getBackupCodeHashes() {
			return fBackupCodeHashes;
		}
	}

	public static TotpState readTotp(Session session, String username) throws RepositoryException {
		TotpState state = new TotpState();
		Node folder = UserCredentials.getUserFolder(session, username);
		if (folder == null || !folder.hasNode(TOTP_NODE)) {
			return state;
		}
		Node content = JCRs.getContentNode(folder.getNode(TOTP_NODE));
		state.fEnabled = content.hasProperty("enabled") && content.getProperty("enabled").getBoolean();
		if (content.hasProperty("secret")) {
			state.fSecret = decrypt(content.getProperty("secret").getString());
		}
		if (content.hasProperty("pendingSecret")) {
			state.fPendingSecret = decrypt(content.getProperty("pendingSecret").getString());
		}
		if (content.hasProperty("pendingSince")) {
			state.fPendingSince = content.getProperty("pendingSince").getDate().getTime();
		}
		if (content.hasProperty("lastCounter")) {
			state.fLastCounter = content.getProperty("lastCounter").getLong();
		}
		if (content.hasProperty("backupCodes")) {
			for (Value v : content.getProperty("backupCodes").getValues()) {
				state.fBackupCodeHashes.add(v.getString());
			}
		}
		return state;
	}

	public static void setPendingTotpSecret(Session session, String username, String secret) throws RepositoryException {
		Node content = contentNode(UserCredentials.getOrCreateUserFolder(session, username), TOTP_NODE);
		content.setProperty("pendingSecret", encrypt(secret));
		content.setProperty("pendingSince", Calendar.getInstance());
		session.save();
	}

	public static void activateTotp(Session session, String username, String secret, List<String> backupCodeHashes) throws RepositoryException {
		Node content = contentNode(UserCredentials.getOrCreateUserFolder(session, username), TOTP_NODE);
		content.setProperty("secret", encrypt(secret));
		content.setProperty("enabled", true);
		content.setProperty("enabledAt", Calendar.getInstance());
		content.setProperty("lastCounter", -1L);
		content.setProperty("backupCodes", backupCodeHashes.toArray(new String[0]));
		content.setProperty("pendingSecret", (String) null);
		content.setProperty("pendingSince", (Value) null);
		session.save();
	}

	public static void disableTotp(Session session, String username) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(session, username);
		if (folder == null || !folder.hasNode(TOTP_NODE)) {
			return;
		}
		folder.getNode(TOTP_NODE).remove();
		session.save();
	}

	public static void setBackupCodeHashes(Session session, String username, List<String> hashes) throws RepositoryException {
		Node content = contentNode(UserCredentials.getOrCreateUserFolder(session, username), TOTP_NODE);
		content.setProperty("backupCodes", hashes.toArray(new String[0]));
		session.save();
	}

	public static void setLastCounter(Session session, String username, long counter) throws RepositoryException {
		Node content = contentNode(UserCredentials.getOrCreateUserFolder(session, username), TOTP_NODE);
		content.setProperty("lastCounter", counter);
		session.save();
	}

	// ---- WebAuthn -----------------------------------------------------------

	/** A registered passkey. */
	public static final class Passkey {
		private String fId;
		private String fUsername;
		private byte[] fCredentialId;
		private byte[] fPublicKey;
		private long fAlgorithm;
		private long fSignCount;
		private String fAaguid;
		private List<String> fTransports = new ArrayList<>();
		private String fDisplayName;
		private Date fCreated;
		private Date fLastUsed;
		private boolean fBackupEligible;
		private boolean fBackupState;

		public Passkey() {}

		public Passkey(byte[] credentialId, byte[] publicKey, long algorithm, long signCount, String aaguid,
				List<String> transports, String displayName, boolean backupEligible, boolean backupState) {
			fCredentialId = credentialId;
			fPublicKey = publicKey;
			fAlgorithm = algorithm;
			fSignCount = signCount;
			fAaguid = aaguid;
			if (transports != null) {
				fTransports.addAll(transports);
			}
			fDisplayName = displayName;
			fBackupEligible = backupEligible;
			fBackupState = backupState;
		}

		/** The opaque id (a digest of the credential id) used in the API. */
		public String getId() {
			return fId;
		}

		public String getUsername() {
			return fUsername;
		}

		public byte[] getCredentialId() {
			return fCredentialId;
		}

		/** The COSE-encoded public key. */
		public byte[] getPublicKey() {
			return fPublicKey;
		}

		/** The COSE algorithm identifier (-7 ES256, -257 RS256, -8 EdDSA). */
		public long getAlgorithm() {
			return fAlgorithm;
		}

		public long getSignCount() {
			return fSignCount;
		}

		public String getAaguid() {
			return fAaguid;
		}

		public List<String> getTransports() {
			return fTransports;
		}

		public String getDisplayName() {
			return fDisplayName;
		}

		public Date getCreated() {
			return fCreated;
		}

		public Date getLastUsed() {
			return fLastUsed;
		}

		public boolean isBackupEligible() {
			return fBackupEligible;
		}

		public boolean isBackupState() {
			return fBackupState;
		}
	}

	/**
	 * The WebAuthn user handle: 32 random bytes fixed per user, so that every
	 * passkey of the user carries the same {@code user.id} and the handle
	 * reveals nothing about the account.
	 */
	public static byte[] getOrCreateUserHandle(Session session, String username) throws RepositoryException {
		Node content = contentNode(UserCredentials.getOrCreateUserFolder(session, username), WEBAUTHN_NODE);
		if (content.hasProperty("userHandle")) {
			return Base64.getUrlDecoder().decode(content.getProperty("userHandle").getString());
		}
		byte[] handle = new byte[32];
		RANDOM.nextBytes(handle);
		content.setProperty("userHandle", Base64.getUrlEncoder().withoutPadding().encodeToString(handle));
		session.save();
		return handle;
	}

	/**
	 * The user handle, or null when the user never started a registration.
	 */
	public static byte[] getUserHandle(Session session, String username) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(session, username);
		if (folder == null || !folder.hasNode(WEBAUTHN_NODE)) {
			return null;
		}
		Node content = JCRs.getContentNode(folder.getNode(WEBAUTHN_NODE));
		if (!content.hasProperty("userHandle")) {
			return null;
		}
		return Base64.getUrlDecoder().decode(content.getProperty("userHandle").getString());
	}

	public static void setPendingRegistration(Session session, String username, byte[] challenge) throws RepositoryException {
		Node content = contentNode(UserCredentials.getOrCreateUserFolder(session, username), WEBAUTHN_NODE);
		content.setProperty("challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(challenge));
		content.setProperty("challengeSince", Calendar.getInstance());
		session.save();
	}

	/**
	 * Returns and clears the pending registration challenge, or null when
	 * there is none or it has expired.
	 */
	public static byte[] takePendingRegistration(Session session, String username) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(session, username);
		if (folder == null || !folder.hasNode(WEBAUTHN_NODE)) {
			return null;
		}
		Node content = JCRs.getContentNode(folder.getNode(WEBAUTHN_NODE));
		if (!content.hasProperty("challenge") || !content.hasProperty("challengeSince")) {
			return null;
		}
		String challenge = content.getProperty("challenge").getString();
		long since = content.getProperty("challengeSince").getDate().getTimeInMillis();
		content.setProperty("challenge", (String) null);
		content.setProperty("challengeSince", (Value) null);
		session.save();
		if (System.currentTimeMillis() - since > PENDING_TTL_MILLIS) {
			return null;
		}
		return Base64.getUrlDecoder().decode(challenge);
	}

	public static List<Passkey> listPasskeys(Session session, String username) throws RepositoryException {
		List<Passkey> passkeys = new ArrayList<>();
		Node folder = UserCredentials.getUserFolder(session, username);
		if (folder == null || !folder.hasNode(PASSKEYS_FOLDER)) {
			return passkeys;
		}
		for (NodeIterator i = folder.getNode(PASSKEYS_FOLDER).getNodes(); i.hasNext();) {
			Node file = i.nextNode();
			if (!file.hasNode(Node.JCR_CONTENT)) {
				continue;
			}
			passkeys.add(readPasskey(username, file));
		}
		passkeys.sort((a, b) -> {
			long x = a.getCreated() == null ? 0 : a.getCreated().getTime();
			long y = b.getCreated() == null ? 0 : b.getCreated().getTime();
			return Long.compare(x, y);
		});
		return passkeys;
	}

	public static Passkey getPasskey(Session session, String username, String id) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(session, username);
		if (folder == null || !folder.hasNode(PASSKEYS_FOLDER) || !isValidId(id)) {
			return null;
		}
		Node passkeys = folder.getNode(PASSKEYS_FOLDER);
		if (!passkeys.hasNode(id)) {
			return null;
		}
		return readPasskey(username, passkeys.getNode(id));
	}

	/**
	 * Finds the passkey (and its owner) for a credential id presented at
	 * sign-in, through the index folder.
	 */
	public static Passkey findPasskey(Session session, byte[] credentialId) throws RepositoryException {
		String id = passkeyId(credentialId);
		String indexPath = UserCredentials.ROOT + "/" + UserCredentials.PASSKEY_INDEX_FOLDER + "/" + id;
		if (!session.nodeExists(indexPath)) {
			return null;
		}
		Node index = JCRs.getContentNode(session.getNode(indexPath));
		if (!index.hasProperty("username")) {
			return null;
		}
		return getPasskey(session, index.getProperty("username").getString(), id);
	}

	public static Passkey addPasskey(Session session, String username, Passkey passkey) throws RepositoryException {
		String id = passkeyId(passkey.getCredentialId());
		Node folder = UserCredentials.getOrCreateUserFolder(session, username);
		Node passkeys = folder.hasNode(PASSKEYS_FOLDER) ? folder.getNode(PASSKEYS_FOLDER) : JCRs.createFolder(folder, PASSKEYS_FOLDER);
		if (passkeys.hasNode(id)) {
			throw new RepositoryException("The passkey is already registered.");
		}
		Node file = JCRs.createFile(passkeys, id);
		Node content = JCRs.getContentNode(file);
		content.setProperty("credentialId", Base64.getUrlEncoder().withoutPadding().encodeToString(passkey.getCredentialId()));
		content.setProperty("publicKey", Base64.getEncoder().encodeToString(passkey.getPublicKey()));
		content.setProperty("algorithm", passkey.getAlgorithm());
		content.setProperty("signCount", passkey.getSignCount());
		if (passkey.getAaguid() != null) {
			content.setProperty("aaguid", passkey.getAaguid());
		}
		content.setProperty("transports", passkey.getTransports().toArray(new String[0]));
		content.setProperty("displayName", passkey.getDisplayName() == null ? "" : passkey.getDisplayName());
		content.setProperty("createdAt", Calendar.getInstance());
		content.setProperty("backupEligible", passkey.isBackupEligible());
		content.setProperty("backupState", passkey.isBackupState());

		Node root = UserCredentials.ensureRoot(session);
		Node index = root.hasNode(UserCredentials.PASSKEY_INDEX_FOLDER) ?
				root.getNode(UserCredentials.PASSKEY_INDEX_FOLDER) :
				JCRs.createFolder(root, UserCredentials.PASSKEY_INDEX_FOLDER);
		Node indexFile = index.hasNode(id) ? index.getNode(id) : JCRs.createFile(index, id);
		JCRs.getContentNode(indexFile).setProperty("username", username);
		session.save();
		return readPasskey(username, file);
	}

	public static void updatePasskeyUsage(Session session, Passkey passkey, long signCount, boolean backupState) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(session, passkey.getUsername());
		if (folder == null || !folder.hasNode(PASSKEYS_FOLDER + "/" + passkey.getId())) {
			return;
		}
		Node content = JCRs.getContentNode(folder.getNode(PASSKEYS_FOLDER + "/" + passkey.getId()));
		content.setProperty("signCount", signCount);
		content.setProperty("backupState", backupState);
		content.setProperty("lastUsedAt", Calendar.getInstance());
		session.save();
	}

	public static boolean renamePasskey(Session session, String username, String id, String displayName) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(session, username);
		if (folder == null || !isValidId(id) || !folder.hasNode(PASSKEYS_FOLDER + "/" + id)) {
			return false;
		}
		JCRs.getContentNode(folder.getNode(PASSKEYS_FOLDER + "/" + id)).setProperty("displayName", displayName == null ? "" : displayName);
		session.save();
		return true;
	}

	public static boolean deletePasskey(Session session, String username, String id) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(session, username);
		if (folder == null || !isValidId(id) || !folder.hasNode(PASSKEYS_FOLDER + "/" + id)) {
			return false;
		}
		folder.getNode(PASSKEYS_FOLDER + "/" + id).remove();
		removeIndexEntry(session, id);
		session.save();
		return true;
	}

	/**
	 * Removes every second factor of the user; the password is left alone.
	 */
	public static void removeAll(Session session, String username) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(session, username);
		if (folder == null) {
			return;
		}
		if (folder.hasNode(PASSKEYS_FOLDER)) {
			for (NodeIterator i = folder.getNode(PASSKEYS_FOLDER).getNodes(); i.hasNext();) {
				removeIndexEntry(session, i.nextNode().getName());
			}
			folder.getNode(PASSKEYS_FOLDER).remove();
		}
		if (folder.hasNode(TOTP_NODE)) {
			folder.getNode(TOTP_NODE).remove();
		}
		if (folder.hasNode(WEBAUTHN_NODE)) {
			folder.getNode(WEBAUTHN_NODE).remove();
		}
		session.save();
	}

	/**
	 * The opaque passkey id: the hex SHA-256 of the credential id, which is
	 * safe as a node name whatever the authenticator put in the credential id.
	 */
	public static String passkeyId(byte[] credentialId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(credentialId);
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private static boolean isValidId(String id) {
		return id != null && id.matches("[0-9a-f]{64}");
	}

	private static void removeIndexEntry(Session session, String id) throws RepositoryException {
		String indexPath = UserCredentials.ROOT + "/" + UserCredentials.PASSKEY_INDEX_FOLDER + "/" + id;
		if (session.nodeExists(indexPath)) {
			session.getNode(indexPath).remove();
		}
	}

	private static Passkey readPasskey(String username, Node file) throws RepositoryException {
		Node content = JCRs.getContentNode(file);
		Passkey passkey = new Passkey();
		passkey.fId = file.getName();
		passkey.fUsername = username;
		passkey.fCredentialId = Base64.getUrlDecoder().decode(content.getProperty("credentialId").getString());
		passkey.fPublicKey = Base64.getDecoder().decode(content.getProperty("publicKey").getString());
		passkey.fAlgorithm = content.getProperty("algorithm").getLong();
		passkey.fSignCount = content.hasProperty("signCount") ? content.getProperty("signCount").getLong() : 0;
		passkey.fAaguid = content.hasProperty("aaguid") ? content.getProperty("aaguid").getString() : null;
		if (content.hasProperty("transports")) {
			for (Value v : content.getProperty("transports").getValues()) {
				passkey.fTransports.add(v.getString());
			}
		}
		passkey.fDisplayName = content.hasProperty("displayName") ? content.getProperty("displayName").getString() : "";
		passkey.fCreated = content.hasProperty("createdAt") ? content.getProperty("createdAt").getDate().getTime() : null;
		passkey.fLastUsed = content.hasProperty("lastUsedAt") ? content.getProperty("lastUsedAt").getDate().getTime() : null;
		passkey.fBackupEligible = content.hasProperty("backupEligible") && content.getProperty("backupEligible").getBoolean();
		passkey.fBackupState = content.hasProperty("backupState") && content.getProperty("backupState").getBoolean();
		return passkey;
	}

	private static Node contentNode(Node folder, String name) throws RepositoryException {
		Node file = folder.hasNode(name) ? folder.getNode(name) : JCRs.createFile(folder, name);
		return JCRs.getContentNode(file);
	}

	private static String encrypt(String value) {
		return encryptor().encrypt(value);
	}

	private static String decrypt(String value) {
		Encryptor encryptor = encryptor();
		return encryptor.isEncrypted(value) ? encryptor.decrypt(value) : value;
	}

	private static Encryptor encryptor() {
		return Activator.getDefault().getEncryptor();
	}

}

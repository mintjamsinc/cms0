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

package org.mintjams.cms.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.jcr.util.JCRs;

/**
 * The per-user credential store under {@value #ROOT} in the system workspace.
 *
 * <p>User profiles ({@code /home/users/<username>/profile}) are readable by
 * every signed-in user (the workspace root grants {@code jcr:read} to
 * everyone) and writable by the user themself (the user holds
 * {@code jcr:all} on their own home). Neither is acceptable for secrets, so
 * everything that authenticates a user lives here instead: the password hash,
 * the TOTP secret and backup codes, and the registered passkeys. The root
 * folder denies {@code jcr:all} to everyone; only system, service and
 * administrator sessions reach it, so every caller is expected to pass a
 * service session and to enforce its own authorization beforehand.</p>
 *
 * <p>Layout:</p>
 * <pre>
 * /home/credentials                              nt:folder (everyone: deny jcr:all)
 * /home/credentials/users/&lt;username&gt;          nt:folder
 * /home/credentials/users/&lt;username&gt;/password nt:file, jcr:content/@hash
 * /home/credentials/users/&lt;username&gt;/totp     nt:file (owned by the IdP)
 * /home/credentials/users/&lt;username&gt;/webauthn nt:file (owned by the IdP)
 * /home/credentials/users/&lt;username&gt;/passkeys nt:folder, one nt:file per passkey (owned by the IdP)
 * /home/credentials/passkeys/&lt;passkey-id&gt;     nt:file, jcr:content/@username (owned by the IdP)
 * </pre>
 *
 * <p>The password hash keeps the existing storage format: a {@code {bcrypt}}
 * prefix followed by the hash. {@code {sha256}} (and unprefixed SHA-256 hex)
 * are still verified for hashes written by older tooling.</p>
 */
public final class UserCredentials {

	/** The root folder of the credential store. */
	public static final String ROOT = "/home/credentials";

	/** The folder below {@link #ROOT} that holds one folder per user. */
	public static final String USERS_FOLDER = "users";

	/** The folder below {@link #ROOT} that maps passkey ids to user names (owned by the IdP). */
	public static final String PASSKEY_INDEX_FOLDER = "passkeys";

	/** The file node that holds the password hash. */
	public static final String PASSWORD_NODE = "password";

	/** The property on the password file's content node that holds the hash. */
	public static final String HASH_PROPERTY = "hash";

	private static final String BCRYPT_PREFIX = "{bcrypt}";
	private static final String SHA256_PREFIX = "{sha256}";

	private UserCredentials() {}

	/**
	 * Returns the absolute path of a user's credential folder.
	 */
	public static String getPath(String username) {
		return ROOT + "/" + USERS_FOLDER + "/" + username;
	}

	/**
	 * Returns the root folder, creating it (with its deny-everyone entry) when
	 * it does not exist yet. Pending changes are saved.
	 */
	public static Node ensureRoot(Session session) throws RepositoryException {
		if (session.nodeExists(ROOT)) {
			Node root = session.getNode(ROOT);
			if (!hasEveryoneDeny(root)) {
				JCRs.setAccessControlEntry(root, new EveryonePrincipal(), false, Privilege.JCR_ALL);
				session.save();
			}
			return root;
		}

		Node home = session.nodeExists("/home") ? session.getNode("/home") : JCRs.createFolder(session.getRootNode(), "home");
		Node root = JCRs.createFolder(home, "credentials");
		session.save();
		JCRs.setAccessControlEntry(root, new EveryonePrincipal(), false, Privilege.JCR_ALL);
		session.save();
		return root;
	}

	/**
	 * Returns the user's credential folder, or {@code null} when the user has
	 * no credentials.
	 */
	public static Node getUserFolder(Session session, String username) throws RepositoryException {
		String path = getPath(username);
		if (!session.nodeExists(path)) {
			return null;
		}
		return session.getNode(path);
	}

	/**
	 * Returns the user's credential folder, creating it when needed. Pending
	 * changes are saved when a folder had to be created.
	 */
	public static Node getOrCreateUserFolder(Session session, String username) throws RepositoryException {
		Node root = ensureRoot(session);
		Node users = root.hasNode(USERS_FOLDER) ? root.getNode(USERS_FOLDER) : JCRs.createFolder(root, USERS_FOLDER);
		if (users.hasNode(username)) {
			return users.getNode(username);
		}
		Node folder = JCRs.createFolder(users, username);
		session.save();
		return folder;
	}

	/**
	 * Removes every credential of the user. Pending changes are saved.
	 */
	public static void remove(Session session, String username) throws RepositoryException {
		Node folder = getUserFolder(session, username);
		if (folder == null) {
			return;
		}
		folder.remove();
		session.save();
	}

	/**
	 * Stores the password (hashed with bcrypt), replacing any previous one.
	 * Pending changes are saved.
	 */
	public static void setPassword(Session session, String username, String password) throws RepositoryException {
		setPasswordHash(session, username, BCRYPT_PREFIX + BCrypt.hash(password));
	}

	/**
	 * Stores an already-hashed password (with its {@code {bcrypt}} or
	 * {@code {sha256}} prefix). Pending changes are saved.
	 */
	public static void setPasswordHash(Session session, String username, String hash) throws RepositoryException {
		Node folder = getOrCreateUserFolder(session, username);
		Node file = folder.hasNode(PASSWORD_NODE) ? folder.getNode(PASSWORD_NODE) : JCRs.createFile(folder, PASSWORD_NODE);
		JCRs.getContentNode(file).setProperty(HASH_PROPERTY, hash);
		session.save();
	}

	/**
	 * Returns the stored password hash, or {@code null} when the user has no
	 * password (service accounts, or a user that was never given one).
	 */
	public static String getPasswordHash(Session session, String username) throws RepositoryException {
		Node folder = getUserFolder(session, username);
		if (folder == null || !folder.hasNode(PASSWORD_NODE)) {
			return null;
		}
		Node contentNode = JCRs.getContentNode(folder.getNode(PASSWORD_NODE));
		if (!contentNode.hasProperty(HASH_PROPERTY)) {
			return null;
		}
		return contentNode.getProperty(HASH_PROPERTY).getString();
	}

	/**
	 * Returns whether the user has a stored password.
	 */
	public static boolean hasPassword(Session session, String username) throws RepositoryException {
		return getPasswordHash(session, username) != null;
	}

	/**
	 * Verifies a plain-text password against the user's stored hash. A user
	 * without a stored password never verifies.
	 */
	public static boolean verifyPassword(Session session, String username, String password) throws RepositoryException {
		if (password == null || password.isEmpty()) {
			return false;
		}
		String stored = getPasswordHash(session, username);
		if (stored == null) {
			return false;
		}
		return verifyPasswordHash(password, stored);
	}

	/**
	 * Verifies a plain-text password against a stored hash in any of the
	 * supported formats.
	 */
	public static boolean verifyPasswordHash(String input, String stored) {
		if (input == null || stored == null) {
			return false;
		}
		if (stored.startsWith(BCRYPT_PREFIX)) {
			return BCrypt.verify(input, stored.substring(BCRYPT_PREFIX.length()));
		}
		String hex = sha256Hex(input);
		if (hex == null) {
			return false;
		}
		if (stored.startsWith(SHA256_PREFIX)) {
			return hex.equalsIgnoreCase(stored.substring(SHA256_PREFIX.length()));
		}
		return hex.equalsIgnoreCase(stored);
	}

	private static boolean hasEveryoneDeny(Node node) throws RepositoryException {
		AccessControlList acl = (AccessControlList) JCRs.getAccessControlList(node);
		for (AccessControlEntry entry : acl) {
			if (!EveryonePrincipal.NAME.equals(entry.getPrincipal().getName())) {
				continue;
			}
			if (entry instanceof org.mintjams.jcr.security.AccessControlEntry &&
					!((org.mintjams.jcr.security.AccessControlEntry) entry).isAllow()) {
				return true;
			}
		}
		return false;
	}

	private static String sha256Hex(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception ex) {
			return null;
		}
	}

}

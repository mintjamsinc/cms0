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

package org.mintjams.idp.internal.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.model.IdpUser;
import org.mintjams.idp.internal.security.IdpServiceCredentials;
import org.mintjams.jcr.util.JCRs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JCR-based user store that reads user profiles from the repository.
 *
 * <p>User profiles are stored at {@code /home/idp/users/{username}/profile}
 * in the system workspace with the following properties:</p>
 * <ul>
 *   <li>{@code password} - hashed password with prefix ({bcrypt} or {sha256})</li>
 *   <li>{@code displayName} - display name</li>
 *   <li>{@code mail} - email address</li>
 *   <li>{@code memberOf} - multi-value string array of role names</li>
 * </ul>
 */
public class JcrUserStore implements UserStore {

	private static final Logger LOG = LoggerFactory.getLogger(JcrUserStore.class);

	private static final String USERS_ROOT = "/home/idp/users";

	@Override
	public IdpUser authenticate(String username, String password) {
		Session jcrSession = null;
		try {
			jcrSession = Activator.getDefault().getRepository().login(new IdpServiceCredentials(), "system");

			String profilePath = USERS_ROOT + "/" + username + "/profile";
			if (!jcrSession.nodeExists(profilePath)) {
				return null;
			}

			Node contentNode = JCRs.getContentNode(jcrSession.getNode(profilePath));
			if (!contentNode.hasProperty("password")) {
				return null;
			}

			String stored = contentNode.getProperty("password").getString();
			if (!verifyPassword(password, stored)) {
				return null;
			}

			return buildUser(username, contentNode);
		} catch (Throwable ex) {
			LOG.error("Failed to authenticate user: {}", username, ex);
			throw new RuntimeException(ex);
		} finally {
			try {
				 jcrSession.logout();
			} catch (Throwable ignore) {}
		}
	}

	@Override
	public IdpUser findUser(String username) {
		Session jcrSession = null;
		try {
			jcrSession = Activator.getDefault().getRepository().login(new IdpServiceCredentials(), "system");

			String profilePath = USERS_ROOT + "/" + username + "/profile";
			if (!jcrSession.nodeExists(profilePath)) {
				return null;
			}

			Node contentNode = JCRs.getContentNode(jcrSession.getNode(profilePath));
			return buildUser(username, contentNode);
		} catch (PathNotFoundException e) {
			return null;
		} catch (RepositoryException e) {
			LOG.error("Failed to find user: {}", username, e);
			throw new RuntimeException(e);
		}
	}

	private IdpUser buildUser(String username, Node contentNode) throws RepositoryException {
		IdpUser user = new IdpUser();
		user.setUsername(username);

		if (contentNode.hasProperty("displayName")) {
			user.setDisplayName(contentNode.getProperty("displayName").getString());
		}
		if (contentNode.hasProperty("mail")) {
			user.setEmail(contentNode.getProperty("mail").getString());
		}
		if (contentNode.hasProperty("memberOf")) {
			for (Value v : contentNode.getProperty("memberOf").getValues()) {
				user.addMemberOf(v.getString());
			}
		}
		if (contentNode.hasProperty("role")) {
			for (Value v : contentNode.getProperty("role").getValues()) {
				user.addRole(v.getString());
			}
		}

		return user;
	}

	private boolean verifyPassword(String input, String stored) {
		if (stored.startsWith("{bcrypt}")) {
			String hash = stored.substring("{bcrypt}".length());
			return OpenBSDBCrypt.checkPassword(hash, input.toCharArray());
		}
		if (stored.startsWith("{sha256}")) {
			String hash = stored.substring("{sha256}".length());
			return hash.equalsIgnoreCase(sha256Hex(input));
		}
		// Fallback: no prefix is treated as SHA-256
		return stored.equalsIgnoreCase(sha256Hex(input));
	}

	private String sha256Hex(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

}

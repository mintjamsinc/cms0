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

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.mintjams.cms.security.UserCredentials;
import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.model.IdpUser;
import org.mintjams.idp.internal.security.IdpServiceCredentials;
import org.mintjams.jcr.util.JCRs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JCR-based user store that reads user profiles from the repository.
 *
 * <p>User profiles are stored at {@code /home/users/{username}/profile}
 * in the system workspace with the following properties:</p>
 * <ul>
 *   <li>{@code displayName} - display name</li>
 *   <li>{@code mail} - email address</li>
 *   <li>{@code memberOf} - multi-value string array of role names</li>
 * </ul>
 * <p>The password hash lives in the credential store
 * ({@link UserCredentials}), not on the profile.</p>
 */
public class JcrUserStore implements UserStore {

	private static final Logger LOG = LoggerFactory.getLogger(JcrUserStore.class);

	private static final String USERS_ROOT = "/home/users";
	private static final String ROLES_ROOT = "/home/roles";
	private static final String GROUPS_ROOT = "/home/groups";

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
			if (!isSignInAllowed(contentNode)) {
				return null;
			}
			if (!UserCredentials.verifyPassword(jcrSession, username, password)) {
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
	public IdpUser findSignInUser(String username) {
		Session jcrSession = null;
		try {
			jcrSession = Activator.getDefault().getRepository().login(new IdpServiceCredentials(), "system");

			String profilePath = USERS_ROOT + "/" + username + "/profile";
			if (!jcrSession.nodeExists(profilePath)) {
				return null;
			}

			Node contentNode = JCRs.getContentNode(jcrSession.getNode(profilePath));
			if (!isSignInAllowed(contentNode)) {
				return null;
			}
			return buildUser(username, contentNode);
		} catch (RepositoryException e) {
			LOG.error("Failed to find user: {}", username, e);
			throw new RuntimeException(e);
		} finally {
			try {
				jcrSession.logout();
			} catch (Throwable ignore) {}
		}
	}

	/**
	 * Service accounts are non-interactive identities (assumed only via runAs)
	 * and must never sign in, regardless of any stored credential; a disabled
	 * user must not sign in either.
	 */
	private static boolean isSignInAllowed(Node contentNode) throws RepositoryException {
		if (contentNode.hasProperty("isService") && contentNode.getProperty("isService").getBoolean()) {
			return false;
		}
		if (contentNode.hasProperty("enabled") && !contentNode.getProperty("enabled").getBoolean()) {
			return false;
		}
		return true;
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
				String samlValue = resolveWeakReferenceToSamlPath(v, contentNode.getSession(), GROUPS_ROOT);
				if (samlValue != null) {
					user.addMemberOf(samlValue);
				}
			}
		}
		if (contentNode.hasProperty("roles")) {
			int propType = contentNode.getProperty("roles").getType();
			for (Value v : contentNode.getProperty("roles").getValues()) {
				if (propType == PropertyType.WEAKREFERENCE || propType == PropertyType.REFERENCE) {
					String samlValue = resolveWeakReferenceToSamlPath(v, contentNode.getSession(), ROLES_ROOT);
					if (samlValue != null) {
						user.addRole(samlValue);
					}
				} else {
					// Legacy: String property — treat value as-is (prefix with "/" for SAML)
					user.addRole("/" + v.getString());
				}
			}
		}

		return user;
	}

	/**
	 * Resolves a WEAKREFERENCE value to a SAML path string.
	 * e.g. UUID of /home/roles/administrator/profile → "/administrator"
	 *      UUID of /home/groups/mintjams/sales/profile → "/mintjams/sales"
	 */
	private String resolveWeakReferenceToSamlPath(Value v, Session jcrSession, String rootPath) {
		try {
			Node profileNode = jcrSession.getNodeByIdentifier(v.getString());
			String fullPath = profileNode.getPath(); // e.g. /home/roles/administrator/profile
			// Strip rootPath prefix and /profile suffix
			String relative = fullPath.substring(rootPath.length()); // /administrator/profile
			return relative.substring(0, relative.length() - "/profile".length()); // /administrator
		} catch (ItemNotFoundException e) {
			LOG.warn("Dangling weak reference in user profile (rootPath={})", rootPath);
			return null;
		} catch (Exception e) {
			LOG.error("Failed to resolve weak reference", e);
			return null;
		}
	}

}

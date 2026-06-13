/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.rt.cms.internal;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

/**
 * Lazily materialises a user's home directory in a content workspace.
 *
 * Identity — the user profile, preferences, avatar — always lives in the
 * system workspace, where identity provisioning creates the full home.
 * Content workspaces hold only the user's working area
 * ({@code /home/users/<id>/Desktop}); since workspaces can be created at
 * any time and users may never enter most of them, the working area is
 * created on the user's first request to the workspace rather than being
 * provisioned eagerly for every user in every workspace.
 */
public class WorkspaceUserHomes {

	private static final String SYSTEM_WORKSPACE_NAME = "system";
	private static final String USERS_ROOT_NAME = "users";
	private static final String HOME_FOLDER_NAME = "home";
	private static final String DESKTOP_FOLDER_NAME = "Desktop";

	/**
	 * Users whose home is known to exist, per workspace. Only an
	 * optimisation: entries are invalidated when a workspace or user is
	 * deleted, and a stale miss merely re-runs the idempotent creation.
	 */
	private static final Map<String, Set<String>> fPrepared = new ConcurrentHashMap<>();

	private WorkspaceUserHomes() {
	}

	/**
	 * Ensures the home directory of the session's user exists in the
	 * session's workspace. Never fails the caller: home creation is a
	 * convenience on the request path, so errors are logged and the
	 * request proceeds.
	 */
	public static void ensureUserHome(Session session) {
		try {
			org.mintjams.jcr.Session jcrSession = org.mintjams.jcr.Session.class.cast(session);
			String workspaceName = session.getWorkspace().getName();
			if (SYSTEM_WORKSPACE_NAME.equals(workspaceName)) {
				// Identity provisioning owns /home in the system workspace.
				return;
			}
			if (jcrSession.isGuest() || jcrSession.isAnonymous() || jcrSession.isSystem() || jcrSession.isService()) {
				return;
			}

			String userId = session.getUserID();
			Set<String> prepared = fPrepared.computeIfAbsent(workspaceName, k -> ConcurrentHashMap.newKeySet());
			if (prepared.contains(userId)) {
				return;
			}

			createUserHome(workspaceName, userId);
			prepared.add(userId);
		} catch (Throwable ex) {
			CmsService.getLogger(WorkspaceUserHomes.class)
					.warn("An error occurred while preparing the user home directory.", ex);
		}
	}

	private static void createUserHome(String workspaceName, String userId) throws Exception {
		// Regular users cannot write under /home; the home is created with
		// service privileges and then handed to the user via an ACE.
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), workspaceName);
		try {
			Node homeFolder = JCRs.getOrCreateFolder(session.getRootNode(), HOME_FOLDER_NAME);
			Node usersFolder = JCRs.getOrCreateFolder(homeFolder, USERS_ROOT_NAME);
			boolean created = !JCRs.exists(usersFolder, userId);
			Node userFolder = JCRs.getOrCreateFolder(usersFolder, userId);
			JCRs.getOrCreateFolder(userFolder, DESKTOP_FOLDER_NAME);
			session.save();

			if (created) {
				JCRs.setAccessControlEntry(userFolder, new Principal() {
					@Override
					public String getName() {
						return userId;
					}
				}, true, Privilege.JCR_ALL);
				session.save();

				CmsService.getLogger(WorkspaceUserHomes.class)
						.info("User home directory has been created: " + userFolder.getPath() + " (" + workspaceName + ")");
			}
		} catch (Throwable ex) {
			try {
				session.refresh(false);
			} catch (Throwable ignore) {}
			throw ex;
		} finally {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}
	}

	/**
	 * Drops the cache for a workspace. Called when a workspace is deleted —
	 * a workspace recreated under the same name must rebuild every home.
	 */
	public static void invalidateWorkspace(String workspaceName) {
		fPrepared.remove(workspaceName);
	}

	/**
	 * Drops the cache for a user in every workspace. Called when a user is
	 * deleted — a user recreated under the same name must get a fresh home.
	 */
	public static void invalidateUser(String userId) {
		for (Set<String> prepared : fPrepared.values()) {
			prepared.remove(userId);
		}
	}

}

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

package org.mintjams.rt.cms.internal.security;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.jcr.security.GuestPrincipal;
import org.mintjams.jcr.util.JCRs;

/**
 * Who may read a user's home ({@code /home/users/<id>}).
 *
 * <p>The root of a workspace grants {@code jcr:read} to everyone, so a home
 * that only grants its owner {@code jcr:all} is still readable by every
 * signed-in user: the desktop, the preferences, the mail. A home is therefore
 * closed by default, whatever an application stores in it later:</p>
 *
 * <pre>
 * /home/users/&lt;id&gt;            deny everyone jcr:read, allow &lt;id&gt; jcr:all
 * /home/users/&lt;id&gt;/profile    allow everyone jcr:read, deny anonymous jcr:read
 * </pre>
 *
 * <p>The profile stays readable because the names of the other users are
 * shown throughout (an assignee, an author). It exists in the identity store
 * only, the {@code system} workspace; a content workspace holds no profile.</p>
 *
 * <p>Entries apply in definition order, so the deny comes first and the
 * owner's grant after it. A home that predates this class carries the grant
 * only; {@link #protect} rebuilds its list with the deny in front and keeps
 * whatever else was granted on it.</p>
 */
public final class UserHomeAccess {

	public static final String USERS_ROOT = "/home/users";
	public static final String PROFILE_NAME = "profile";

	private UserHomeAccess() {
	}

	/**
	 * Closes the home to everyone but its owner. Does nothing to a home that is
	 * already closed. The caller saves the session.
	 *
	 * @return whether the access control list was changed
	 */
	public static boolean protect(Node userFolder, String userId) throws RepositoryException {
		AccessControlManager acm = userFolder.getSession().getAccessControlManager();
		AccessControlList acl = (AccessControlList) JCRs.getAccessControlList(userFolder);

		List<AccessControlEntry> entries = new ArrayList<>();
		boolean ownerGranted = false;
		for (AccessControlEntry entry : acl.getAccessControlEntries()) {
			String name = entry.getPrincipal().getName();
			if (EveryonePrincipal.NAME.equals(name) && !isAllow(entry) && covers(entry, Privilege.JCR_READ)) {
				return false;
			}
			if (userId.equals(name) && isAllow(entry) && covers(entry, Privilege.JCR_ALL)) {
				ownerGranted = true;
			}
			entries.add(entry);
		}

		acl.clear();
		acl.addAccessControlEntry(new EveryonePrincipal(), false, acm.privilegeFromName(Privilege.JCR_READ));
		if (!ownerGranted) {
			acl.addAccessControlEntry(named(userId), true, acm.privilegeFromName(Privilege.JCR_ALL));
		}
		for (AccessControlEntry entry : entries) {
			acl.addAccessControlEntry(entry.getPrincipal(), isAllow(entry), entry.getPrivileges());
		}
		acm.setPolicy(userFolder.getPath(), acl);
		return true;
	}

	/**
	 * Lets every signed-in user read the profile of a closed home. Does nothing
	 * to a profile that already is readable. The caller saves the session.
	 *
	 * @return whether the access control list was changed
	 */
	public static boolean publishProfile(Node profile) throws RepositoryException {
		AccessControlManager acm = profile.getSession().getAccessControlManager();
		AccessControlList acl = (AccessControlList) JCRs.getAccessControlList(profile);
		for (AccessControlEntry entry : acl.getAccessControlEntries()) {
			if (EveryonePrincipal.NAME.equals(entry.getPrincipal().getName()) && isAllow(entry)
					&& covers(entry, Privilege.JCR_READ)) {
				return false;
			}
		}

		acl.addAccessControlEntry(new EveryonePrincipal(), true, acm.privilegeFromName(Privilege.JCR_READ));
		// Allowing everyone would give anonymous back what the root denies it.
		acl.addAccessControlEntry(new GuestPrincipal(), false, acm.privilegeFromName(Privilege.JCR_READ));
		acm.setPolicy(profile.getPath(), acl);
		return true;
	}

	/**
	 * Closes every home of the session's workspace and opens the profiles in
	 * them. The session must be allowed to change access control; the caller
	 * saves it.
	 *
	 * @return how many homes or profiles were changed
	 */
	public static int protectAll(Session session) throws RepositoryException {
		if (!session.nodeExists(USERS_ROOT)) {
			return 0;
		}

		int changed = 0;
		for (NodeIterator i = session.getNode(USERS_ROOT).getNodes(); i.hasNext();) {
			Node userFolder = i.nextNode();
			if (!JCRs.isFolder(userFolder)) {
				continue;
			}
			if (protect(userFolder, userFolder.getName())) {
				changed++;
			}
			if (userFolder.hasNode(PROFILE_NAME) && publishProfile(userFolder.getNode(PROFILE_NAME))) {
				changed++;
			}
		}
		return changed;
	}

	private static boolean isAllow(AccessControlEntry entry) {
		return !(entry instanceof org.mintjams.jcr.security.AccessControlEntry)
				|| ((org.mintjams.jcr.security.AccessControlEntry) entry).isAllow();
	}

	private static boolean covers(AccessControlEntry entry, String privilegeName) {
		for (Privilege privilege : entry.getPrivileges()) {
			String name = localName(privilege.getName());
			if (name.equals(localName(privilegeName)) || name.equals(localName(Privilege.JCR_ALL))) {
				return true;
			}
		}
		return false;
	}

	/** "read" for both "jcr:read" and "{http://www.jcp.org/jcr/1.0}read": a name comes in either form. */
	private static String localName(String privilegeName) {
		int i = Math.max(privilegeName.lastIndexOf('}'), privilegeName.lastIndexOf(':'));
		return privilegeName.substring(i + 1);
	}

	private static Principal named(String name) {
		return new Principal() {
			@Override
			public String getName() {
				return name;
			}
		};
	}

}

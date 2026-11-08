/*
 * Copyright (c) 2022 MintJams Inc.
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

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.query.Query;

/**
 * Finds the profiles of the identity store in the {@code system} workspace.
 *
 * <p>A group lives at {@code /home/groups/<group id>/profile}, where the group
 * id is a path: groups nest, and the last segment of the id is the identifier
 * the security layer knows the group by (the {@code identifier} property of
 * the profile's content). So a top-level group is found at a path built from
 * its identifier, while a nested one has to be searched for.
 *
 * <p>The search index is the quick way to search, but it is brought up to
 * date after a commit, on another thread, and it can be lost: a group that
 * was committed a moment ago is not in it yet. Everything that decides
 * access or identity by a group's existence must not depend on it, so a miss
 * in the index is confirmed by reading the folders under {@code /home/groups}
 * from the repository itself. The index then only saves time; it never gives
 * the answer. Package provisioning creates a group and, in the next step,
 * an access control entry that names it; the entry is validated against the
 * identity store, and without that confirmation the installation failed with
 * an unknown principal.
 */
final class IdentityProfiles {

	private static final String GROUPS_ROOT = "/home/groups";
	private static final String PROFILE = "profile";
	private static final String CONTENT = "jcr:content";

	private IdentityProfiles() {}

	/**
	 * Returns the {@code profile} file of the group with the given identifier,
	 * or {@code null} when there is no such group.
	 *
	 * @param session  a session on the {@code system} workspace that reads the
	 *                 identity store
	 * @param identifier the group identifier (the last segment of the group id)
	 */
	static Node findGroupProfile(Session session, String identifier) throws RepositoryException {
		if (identifier == null || identifier.isEmpty() || identifier.indexOf('/') >= 0) {
			return null;
		}

		// A top-level group: its folder is named by the identifier.
		try {
			Node profile = groupProfileAt(session, GROUPS_ROOT + "/" + identifier, identifier);
			if (profile != null) {
				return profile;
			}
		} catch (RepositoryException | RuntimeException ignore) {
			// The identifier does not make a valid path; it may still be a
			// property value somewhere below.
		}

		// The search index, when it has the group.
		Node indexed = queryGroupProfile(session, identifier);
		if (indexed != null) {
			return indexed;
		}

		// The repository itself: the index may not have caught up with the
		// commit that created the group, or may have lost it.
		if (!session.nodeExists(GROUPS_ROOT)) {
			return null;
		}
		return walk(session.getNode(GROUPS_ROOT), identifier);
	}

	@SuppressWarnings("deprecation")
	private static Node queryGroupProfile(Session session, String identifier) throws RepositoryException {
		String escaped = identifier.replace("'", "\\'");
		String stmt = "/jcr:root/home/groups//*[@identifier = '" + escaped + "' and @isGroup = true]";
		Query q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH);
		q.setOffset(0);
		q.setLimit(1);
		for (NodeIterator i = q.execute().getNodes(); i.hasNext();) {
			Node hit = i.nextNode();
			Node profile = hit.getName().equals(CONTENT) ? hit.getParent() : hit;
			if (isGroupProfile(profile, identifier)) {
				return profile;
			}
		}
		return null;
	}

	private static Node walk(Node folder, String identifier) throws RepositoryException {
		for (NodeIterator i = folder.getNodes(); i.hasNext();) {
			Node child = i.nextNode();
			if (!child.isNodeType(NodeType.NT_FOLDER)) {
				continue;
			}
			Node profile = groupProfileAt(child.getSession(), child.getPath(), identifier);
			if (profile != null) {
				return profile;
			}
			Node found = walk(child, identifier);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static Node groupProfileAt(Session session, String folderPath, String identifier) throws RepositoryException {
		try {
			Node profile = session.getNode(folderPath + "/" + PROFILE);
			return isGroupProfile(profile, identifier) ? profile : null;
		} catch (PathNotFoundException ignore) {
			return null;
		}
	}

	private static boolean isGroupProfile(Node profile, String identifier) throws RepositoryException {
		if (!profile.hasNode(CONTENT)) {
			return false;
		}
		Node content = profile.getNode(CONTENT);
		return content.hasProperty("isGroup") && content.getProperty("isGroup").getBoolean()
				&& content.hasProperty("identifier") && content.getProperty("identifier").getString().equals(identifier);
	}

}

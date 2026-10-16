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

package org.mintjams.rt.cms.internal.graphql.type;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.util.JCRs;

/**
 * What the session may do with a node: its effective privileges, put as the
 * questions a client asks before it acts — may I change this file, add to
 * this folder, delete it, lock it.
 *
 * <p>The repository's access control is the only limit on a user, and an MCP
 * client works as its user; knowing the answer first lets it say "you cannot
 * change this" instead of trying and failing. The answers are the
 * repository's own ({@link AccessControlManager#hasPrivileges}), resolved for
 * the session's principals with every ACL that applies.
 */
final class NodePermissions {

	private NodePermissions() {}

	static Map<String, Object> of(Node node) throws RepositoryException {
		AccessControlManager acm = node.getSession().getAccessControlManager();
		String path = node.getPath();
		// A file's content and properties live on its jcr:content: that is
		// where a change to the file is checked.
		String contentPath = path;
		if (JCRs.isFile(node) && node.hasNode("jcr:content")) {
			contentPath = node.getNode("jcr:content").getPath();
		}
		String parentPath = path.equals("/") ? null : node.getParent().getPath();

		Map<String, Object> permissions = new LinkedHashMap<>();
		permissions.put("read", has(acm, path, Privilege.JCR_READ));
		permissions.put("write", has(acm, contentPath, Privilege.JCR_MODIFY_PROPERTIES));
		permissions.put("addChildren", JCRs.isFolder(node) && has(acm, path, Privilege.JCR_ADD_CHILD_NODES));
		permissions.put("remove", parentPath != null && has(acm, path, Privilege.JCR_REMOVE_NODE)
				&& has(acm, parentPath, Privilege.JCR_REMOVE_CHILD_NODES));
		permissions.put("lock", has(acm, path, Privilege.JCR_LOCK_MANAGEMENT));
		permissions.put("manageVersions", has(acm, path, Privilege.JCR_VERSION_MANAGEMENT));
		permissions.put("readAccessControl", has(acm, path, Privilege.JCR_READ_ACCESS_CONTROL));
		permissions.put("modifyAccessControl", has(acm, path, Privilege.JCR_MODIFY_ACCESS_CONTROL));
		return permissions;
	}

	private static boolean has(AccessControlManager acm, String path, String privilege) {
		try {
			return acm.hasPrivileges(path, new Privilege[] { acm.privilegeFromName(privilege) });
		} catch (Exception ex) {
			return false;
		}
	}

}

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

package org.mintjams.rt.cms.internal.job.archive;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.PrincipalProvider;
import org.mintjams.jcr.util.JCRs;

/**
 * Reads and writes the {@code .cms-archive/acl.ndjson} section of a CMS Archive
 * — one node's access control list per line, captured on export and reapplied
 * on import (when the operator opts in). See
 * {@code documents/cms-archive-export-import.md}.
 *
 * <p>Each entry records a principal, the allow/deny flag and the privilege
 * names, mapping directly onto the repository's
 * {@code AccessControlList}/{@code AccessControlEntry} model.
 */
public final class AclCodec {

	private AclCodec() {}

	/**
	 * Serialise a node's explicit access control list, or {@code null} when the
	 * node has no entries (or the caller cannot read its policy). The returned
	 * map is {@code {"path": ..., "entries": [{principal, allow, privileges}]}}.
	 */
	public static Map<String, Object> toMap(Node node) throws RepositoryException {
		org.mintjams.jcr.security.AccessControlList acl;
		try {
			// Covers both "no ACL exists" (AccessControlException) and "no
			// read-ACL privilege" (AccessDeniedException, a subclass): a node
			// whose ACL cannot be read is skipped rather than failing the archive.
			acl = (org.mintjams.jcr.security.AccessControlList) JCRs.getAccessControlList(node);
		} catch (AccessControlException none) {
			return null;
		}

		List<Map<String, Object>> entries = new ArrayList<>();
		for (AccessControlEntry ace : acl) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("principal", ace.getPrincipal().getName());
			e.put("allow", ((org.mintjams.jcr.security.AccessControlEntry) ace).isAllow());
			List<String> privileges = new ArrayList<>();
			for (Privilege p : ace.getPrivileges()) {
				privileges.add(p.getName());
			}
			e.put("privileges", privileges);
			entries.add(e);
		}
		if (entries.isEmpty()) {
			return null;
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("path", node.getPath());
		out.put("entries", entries);
		return out;
	}

	/**
	 * Reapply the access control entries from an {@code acl.ndjson} record onto a
	 * node.
	 *
	 * @param strict when {@code true}, a principal that cannot be resolved in the
	 *               target installation fails (throws {@link PrincipalNotFoundException});
	 *               when {@code false}, it is skipped with a warning. Import uses
	 *               strict mode so a missing principal is reported rather than
	 *               silently dropping permissions.
	 */
	@SuppressWarnings("unchecked")
	public static void apply(Node node, List<Map<String, Object>> entries, PrincipalProvider principals,
			List<String> warnings, boolean strict) throws RepositoryException {
		if (entries == null || entries.isEmpty()) {
			return;
		}
		for (Map<String, Object> e : entries) {
			String principalName = (String) e.get("principal");
			boolean allow = Boolean.TRUE.equals(e.get("allow"));
			List<String> privileges = (List<String>) e.get("privileges");
			Principal principal;
			try {
				principal = principals.getPrincipal(principalName);
			} catch (PrincipalNotFoundException notFound) {
				if (strict) {
					throw notFound;
				}
				warnings.add("ACL principal not found: " + principalName + " on " + safePath(node));
				continue;
			}
			JCRs.addAccessControlEntry(node, principal, allow,
					privileges.toArray(new String[0]));
		}
	}

	private static String safePath(Node node) {
		try {
			return node.getPath();
		} catch (RepositoryException ex) {
			return "<unknown>";
		}
	}
}

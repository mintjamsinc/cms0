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

package org.mintjams.rt.jcr.internal.security;

import java.io.IOException;
import java.security.Principal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.lock.LockException;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;
import javax.jcr.version.VersionException;

import org.mintjams.jcr.JcrName;
import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.NamespaceProvider;
import org.mintjams.jcr.security.AccessControlManager;
import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.UnknownPrincipal;
import org.mintjams.rt.jcr.internal.AccessControlStore;
import org.mintjams.rt.jcr.internal.Activator;
import org.mintjams.rt.jcr.internal.JcrNode;
import org.mintjams.rt.jcr.internal.JcrSession;
import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.rt.jcr.internal.JcrWorkspaceProvider;
import org.mintjams.rt.jcr.internal.WorkspaceQuery;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.sql.Query;

public class JcrAccessControlManager implements AccessControlManager, Adaptable {

	private final Map<String, JcrPrivilege> fStandardPrivileges = new HashMap<>();
	private final List<JcrPrivilege> fStandardPrivilegeList = new ArrayList<>();
	private final JcrWorkspace fWorkspace;

	private JcrAccessControlManager(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static JcrAccessControlManager create(JcrWorkspace workspace) {
		return new JcrAccessControlManager(workspace);
	}

	public JcrAccessControlManager load() {
		JcrPrivilege read = JcrPrivilege.create(Privilege.JCR_READ, fWorkspace).setBits(1L << 0);
		JcrPrivilege modifyProperties = JcrPrivilege.create(Privilege.JCR_MODIFY_PROPERTIES, fWorkspace).setBits(1L << 1);
		JcrPrivilege addChildNodes = JcrPrivilege.create(Privilege.JCR_ADD_CHILD_NODES, fWorkspace).setBits(1L << 2);
		JcrPrivilege removeNode = JcrPrivilege.create(Privilege.JCR_REMOVE_NODE, fWorkspace).setBits(1L << 3);
		JcrPrivilege removeChildNodes = JcrPrivilege.create(Privilege.JCR_REMOVE_CHILD_NODES, fWorkspace).setBits(1L << 4);
		JcrPrivilege write = JcrPrivilege.create(Privilege.JCR_WRITE, fWorkspace)
				.addAggregatePrivilege(modifyProperties)
				.addAggregatePrivilege(addChildNodes)
				.addAggregatePrivilege(removeNode)
				.addAggregatePrivilege(removeChildNodes);
		JcrPrivilege readAccessControl = JcrPrivilege.create(Privilege.JCR_READ_ACCESS_CONTROL, fWorkspace).setBits(1L << 5);
		JcrPrivilege modifyAccessControl = JcrPrivilege.create(Privilege.JCR_MODIFY_ACCESS_CONTROL, fWorkspace).setBits(1L << 6);
		JcrPrivilege lockManagement = JcrPrivilege.create(Privilege.JCR_LOCK_MANAGEMENT, fWorkspace).setBits(1L << 7);
		JcrPrivilege versionManagement = JcrPrivilege.create(Privilege.JCR_VERSION_MANAGEMENT, fWorkspace).setBits(1L << 8);
		JcrPrivilege nodeTypeManagement = JcrPrivilege.create(Privilege.JCR_NODE_TYPE_MANAGEMENT, fWorkspace).setBits(1L << 9);
		JcrPrivilege retentionManagement = JcrPrivilege.create(Privilege.JCR_RETENTION_MANAGEMENT, fWorkspace).setBits(1L << 10);
		JcrPrivilege lifeCycleManagement = JcrPrivilege.create(Privilege.JCR_LIFECYCLE_MANAGEMENT, fWorkspace).setBits(1L << 11);
		JcrPrivilege all = JcrPrivilege.create(Privilege.JCR_ALL, fWorkspace)
				.addAggregatePrivilege(read)
				.addAggregatePrivilege(write)
				.addAggregatePrivilege(readAccessControl)
				.addAggregatePrivilege(modifyAccessControl)
				.addAggregatePrivilege(lockManagement)
				.addAggregatePrivilege(versionManagement)
				.addAggregatePrivilege(nodeTypeManagement)
				.addAggregatePrivilege(retentionManagement)
				.addAggregatePrivilege(lifeCycleManagement);

		for (JcrPrivilege privilege : new JcrPrivilege[] { read, modifyProperties, addChildNodes, removeNode,
				removeChildNodes, write, readAccessControl, modifyAccessControl, lockManagement, versionManagement,
				nodeTypeManagement, retentionManagement, lifeCycleManagement, all }) {
			fStandardPrivileges.put(privilege.getName(), privilege);
			fStandardPrivilegeList.add(privilege);
		}
		return this;
	}

	@Override
	public AccessControlPolicyIterator getApplicablePolicies(String absPath)
			throws PathNotFoundException, AccessDeniedException, RepositoryException {
		adaptTo(JcrSession.class).checkPrivileges(absPath, Privilege.JCR_READ_ACCESS_CONTROL);
		return JcrAccessControlPolicyIterator.create();
	}

	@Override
	public AccessControlPolicy[] getEffectivePolicies(String absPath)
			throws PathNotFoundException, AccessDeniedException, RepositoryException {
		adaptTo(JcrSession.class).checkPrivileges(absPath, Privilege.JCR_READ_ACCESS_CONTROL);
		return _effectivePolicies(JcrPath.valueOf(absPath).with(adaptTo(NamespaceProvider.class)));
	}

	private AccessControlPolicy[] _effectivePolicies(JcrPath absPath)
			throws PathNotFoundException, AccessDeniedException, RepositoryException {
		// Each distinct principal is resolved against the identity store once per
		// call — an ancestor chain repeats the same few principals across many
		// entries, and per-entry lookups dominated the cost of this method.
		Map<String, Principal> principalCache = new HashMap<>();

		if (!getWorkspaceQuery().isAccessControlAffected()) {
			// Serve from the workspace-wide access control store: same committed
			// data the SQL below would return, without the query.
			AccessControlStore.Snapshot snapshot;
			try {
				snapshot = adaptTo(AccessControlStore.class).getSnapshot();
			} catch (IOException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}

			// Deepest path first, entries in definition (row) order — the same
			// ordering the SQL fallback produces (item_path DESC, row_no).
			Map<String, JcrAccessControlList> policies = new LinkedHashMap<>();
			for (JcrPath path = absPath; path != null; path = path.getParent()) {
				List<AccessControlStore.Entry> entries = snapshot.getEntries(path.toString());
				if (entries.isEmpty()) {
					continue;
				}
				JcrAccessControlList acl = JcrAccessControlList.create(path.toString(), this);
				for (AccessControlStore.Entry entry : entries) {
					acl.addAccessControlEntry(resolvePrincipal(entry.getPrincipalName(), principalCache),
							entry.isAllow(), entry.getPrivilegeNames());
				}
				policies.put(path.toString(), acl);
			}
			return policies.values().toArray(AccessControlPolicy[]::new);
		}

		// The transaction carries uncommitted access control changes; evaluate
		// against it.
		try (Query.Result result = getWorkspaceQuery().items().collectAccessControlEntries(absPath.toString())) {
			Map<String, JcrAccessControlList> policies = new LinkedHashMap<>();
			for (AdaptableMap<String, Object> r : result) {
				JcrAccessControlList acl = policies.get(r.getString("item_path"));
				if (acl == null) {
					acl = JcrAccessControlList.create(r.getString("item_path"), this);
					policies.put(r.getString("item_path"), acl);
				}

				acl.addAccessControlEntry(resolvePrincipal(r.getString("principal_name"), principalCache),
						r.getBoolean("is_allow"), Arrays.stream(r.getObjectArray("privilege_names")).toArray(String[]::new));
			}
			return policies.values().toArray(AccessControlPolicy[]::new);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	private Principal resolvePrincipal(String principalName, Map<String, Principal> cache) {
		Principal principal = cache.get(principalName);
		if (principal == null) {
			try {
				principal = Activator.getDefault().getPrincipal(principalName);
			} catch (PrincipalNotFoundException ignore) {
				principal = new UnknownPrincipal(principalName);
			}
			cache.put(principalName, principal);
		}
		return principal;
	}

	@Override
	public AccessControlPolicy[] getPolicies(String absPath)
			throws PathNotFoundException, AccessDeniedException, RepositoryException {
		adaptTo(JcrSession.class).checkPrivileges(absPath, Privilege.JCR_READ_ACCESS_CONTROL);
		return _policies(absPath);
	}

	private AccessControlPolicy[] _policies(String absPath)
			throws PathNotFoundException, AccessDeniedException, RepositoryException {
		try (Query.Result result = getWorkspaceQuery().items().listAccessControlEntries(absPath)) {
			JcrAccessControlList acl = JcrAccessControlList.create(absPath, this);
			for (AdaptableMap<String, Object> r : result) {
				Principal principal;
				try {
					principal = Activator.getDefault().getPrincipal(r.getString("principal_name"));
				} catch (PrincipalNotFoundException ignore) {
					principal = new UnknownPrincipal(r.getString("principal_name"));
				}

				acl.addAccessControlEntry(principal, r.getBoolean("is_allow"), Arrays.stream(r.getObjectArray("privilege_names")).toArray(String[]::new));
			}

			return new AccessControlPolicy[] { acl };
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public Privilege[] getPrivileges(String absPath) throws PathNotFoundException, RepositoryException {
		return toPrivileges(getPrivilegesMask(absPath));
	}

	/**
	 * Evaluates the effective privileges at the given path as a bit set of leaf
	 * privileges. Access control entries are applied from the root down to the path,
	 * in definition order: an allow entry adds the privilege's bits, a deny entry
	 * removes exactly those bits. This follows the JCR 2.0 principle that an aggregate
	 * privilege is equivalent to the set of its constituents — denying a constituent
	 * leaves the remaining constituents granted, and denying an aggregate revokes all
	 * of its constituents however they were granted.
	 */
	private long getPrivilegesMask(String absPath) throws PathNotFoundException, RepositoryException {
		JcrPath path = JcrPath.valueOf(absPath).with(adaptTo(NamespaceProvider.class));
		JcrSession session = adaptTo(JcrSession.class);
		if (session.isSystem()) {
			return privilegeBits(Privilege.JCR_ALL);
		}
		if (absPath.equals("/" + JcrNode.JCR_SYSTEM_NAME)) {
			return 0;
		}
		if (absPath.startsWith("/" + JcrNode.JCR_SYSTEM_NAME + "/")) {
			return privilegeBits(Privilege.JCR_READ);
		}
		if (session.isService() || session.isAdmin()) {
			return privilegeBits(Privilege.JCR_ALL);
		}
		if (adaptTo(JcrWorkspaceProvider.class).getConfiguration().isPublicAccess(session, path)) {
			return privilegeBits(Privilege.JCR_READ);
		}

		List<String> principals = sessionPrincipalNames();

		long allowed = 0;

		if (!getWorkspaceQuery().isAccessControlAffected()) {
			// Evaluate against the workspace-wide access control store; no SQL involved.
			AccessControlStore.Snapshot snapshot;
			try {
				snapshot = adaptTo(AccessControlStore.class).getSnapshot();
			} catch (IOException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}

			return storedPrivilegesMask(snapshot, path, principals);
		}

		// The transaction carries uncommitted access control changes; evaluate against it.
		AccessControlPolicy[] policies = _effectivePolicies(path);
		List<AccessControlPolicy> policyList = Arrays.asList(policies);
		Collections.reverse(policyList);
		for (AccessControlPolicy acp : policyList) {
			for (AccessControlEntry ace : ((JcrAccessControlList) acp).getAccessControlEntries()) {
				if (!principals.contains(ace.getPrincipal().getName())) {
					continue;
				}

				long bits = 0;
				for (Privilege privilege : ace.getPrivileges()) {
					bits |= ((JcrPrivilege) privilege).getBits();
				}
				if (((JcrAccessControlEntry) ace).isAllow()) {
					allowed |= bits;
				} else {
					allowed &= ~bits;
				}
			}
		}
		return allowed;
	}

	private long privilegeBits(String privilegeName) throws AccessControlException, RepositoryException {
		return ((JcrPrivilege) privilegeFromName(privilegeName)).getBits();
	}

	/** The principal names privilege evaluation matches entries against: everyone, groups, user. */
	private List<String> sessionPrincipalNames() throws RepositoryException {
		JcrSession session = adaptTo(JcrSession.class);
		List<String> principals = new ArrayList<>();
		principals.add(new EveryonePrincipal().getName());
		principals.addAll(session.getGroups().stream().map(Principal::getName).collect(Collectors.toList()));
		principals.add(session.getUserPrincipal().getName());
		return principals;
	}

	/**
	 * Applies the store's entries along the root-to-path chain for the given
	 * principals and returns the resulting privilege mask (allow adds bits, deny
	 * removes them, root first).
	 */
	private long storedPrivilegesMask(AccessControlStore.Snapshot snapshot, JcrPath path, List<String> principals)
			throws RepositoryException {
		List<String> paths = new ArrayList<>();
		for (JcrPath p = path; p != null; p = p.getParent()) {
			paths.add(p.toString());
		}
		Collections.reverse(paths);

		long allowed = 0;
		for (String p : paths) {
			for (AccessControlStore.Entry entry : snapshot.getEntries(p)) {
				if (!principals.contains(entry.getPrincipalName())) {
					continue;
				}

				long bits = 0;
				for (String privilegeName : entry.getPrivilegeNames()) {
					bits |= privilegeBits(privilegeName);
				}
				if (entry.isAllow()) {
					allowed |= bits;
				} else {
					allowed &= ~bits;
				}
			}
		}
		return allowed;
	}

	/**
	 * Returns whether every child of the given node is readable by this session,
	 * decided without visiting the children. A {@code true} answer guarantees the
	 * per-child read filtering in child-node iteration is a no-op, which lets a
	 * pagination skip be pushed down to the database as a row offset. Answers
	 * {@code false} whenever that guarantee cannot be established cheaply.
	 */
	public boolean canReadAllChildren(String absParentPath) {
		try {
			if (absParentPath == null || "/".equals(absParentPath)) {
				// The root's children include /jcr:system, which is not readable
				// by every session (nor by admin sessions).
				return false;
			}
			JcrSession session = adaptTo(JcrSession.class);
			if (session.isSystem() || session.isService() || session.isAdmin()) {
				return true;
			}
			String systemPath = "/" + JcrNode.JCR_SYSTEM_NAME;
			if (absParentPath.equals(systemPath) || absParentPath.startsWith(systemPath + "/")) {
				// Every session holds read below /jcr:system.
				return true;
			}
			if (getWorkspaceQuery().isAccessControlAffected()) {
				// Uncommitted access control changes require per-child evaluation.
				return false;
			}
			AccessControlStore store = adaptTo(AccessControlStore.class);
			if (store.hasEntriesBelow(absParentPath)) {
				// An entry on an individual child could deny it read.
				return false;
			}
			// No entries below the parent, so every child inherits the parent's
			// entry-derived mask. Public-access filters are deliberately ignored:
			// they are not guaranteed to cover subtrees, so read must be
			// established from the entries alone.
			JcrPath path = JcrPath.valueOf(absParentPath).with(adaptTo(NamespaceProvider.class));
			long readBits = privilegeBits(Privilege.JCR_READ);
			long mask = storedPrivilegesMask(store.getSnapshot(), path, sessionPrincipalNames());
			return (mask & readBits) == readBits;
		} catch (Throwable ignore) {
			return false;
		}
	}

	/**
	 * Returns the most compact privilege set for the given bits: a privilege is
	 * reported when all of its bits are granted, and is omitted when another reported
	 * privilege already covers it (e.g. all four constituents of jcr:write are
	 * reported as jcr:write alone).
	 */
	private Privilege[] toPrivileges(long mask) {
		List<JcrPrivilege> granted = new ArrayList<>();
		for (JcrPrivilege privilege : fStandardPrivilegeList) {
			long bits = privilege.getBits();
			if (bits != 0 && (mask & bits) == bits) {
				granted.add(privilege);
			}
		}

		List<Privilege> l = new ArrayList<>();
		for (JcrPrivilege candidate : granted) {
			boolean covered = false;
			for (JcrPrivilege other : granted) {
				if (other == candidate) {
					continue;
				}
				long candidateBits = candidate.getBits();
				long otherBits = other.getBits();
				if ((candidateBits & otherBits) == candidateBits && candidateBits != otherBits) {
					covered = true;
					break;
				}
			}
			if (!covered) {
				l.add(candidate);
			}
		}
		return l.toArray(Privilege[]::new);
	}

	@Override
	public Privilege[] getSupportedPrivileges(String absPath) throws PathNotFoundException, RepositoryException {
		return fStandardPrivilegeList.toArray(Privilege[]::new);
	}

	@Override
	public boolean hasPrivileges(String absPath, Privilege[] privileges) throws PathNotFoundException, RepositoryException {
		long required = 0;
		for (Privilege e : privileges) {
			try {
				required |= privilegeBits(e.getName());
			} catch (AccessControlException ignore) {
				// An unknown privilege can never be held.
				return false;
			}
		}
		return (getPrivilegesMask(absPath) & required) == required;
	}

	@Override
	public Privilege privilegeFromName(String privilegeName) throws AccessControlException, RepositoryException {
		JcrPrivilege privilege = fStandardPrivileges.get(JcrName.valueOf(privilegeName).with(adaptTo(NamespaceProvider.class)).toString());
		if (privilege == null) {
			throw new AccessControlException("Invalid privilege name: " + privilegeName);
		}
		return privilege;
	}

	@Override
	public void removePolicy(String absPath, AccessControlPolicy policy) throws PathNotFoundException,
			AccessControlException, AccessDeniedException, LockException, VersionException, RepositoryException {
		removePolicy(absPath);
	}

	@Override
	public void setPolicy(String absPath, AccessControlPolicy policy) throws PathNotFoundException, AccessControlException,
			AccessDeniedException, LockException, VersionException, RepositoryException {
		adaptTo(JcrSession.class).checkPrivileges(absPath, Privilege.JCR_MODIFY_ACCESS_CONTROL);
		Node node = fWorkspace.getNode(absPath);

		if (policy instanceof JcrAccessControlList) {
			JcrAccessControlList acl = (JcrAccessControlList) policy;
			if (!node.getPath().equals(acl.getPath())) {
				throw new AccessControlException("Access control policy is not applicable.");
			}

			List<AccessControlEntry> entries = Arrays.asList(acl.getAccessControlEntries());
			for (AccessControlEntry ace : entries) {
				try {
					Activator.getDefault().getPrincipal(ace.getPrincipal().getName());
				} catch (PrincipalNotFoundException ex) {
					throw new AccessControlException("Unknown principal: " + ace.getPrincipal().getName(), ex);
				}
			}

			removePolicy(absPath);
			try {
				getWorkspaceQuery().items().setAccessControlPolicy(
						node.getIdentifier(),
						entries.toArray(JcrAccessControlEntry[]::new));
			} catch (IOException | SQLException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}
			return;
		}

		throw new AccessControlException("Unsupported access control policy type: " + policy.getClass().getName());
	}

	@Override
	public boolean hasPrivileges(String absPath, String... privileges)
			throws PathNotFoundException, RepositoryException {
		long required = 0;
		for (String privilege : privileges) {
			try {
				required |= privilegeBits(privilege);
			} catch (AccessControlException ignore) {
				// An unknown privilege can never be held.
				return false;
			}
		}
		return (getPrivilegesMask(absPath) & required) == required;
	}

	@Override
	public void removePolicy(String absPath) throws PathNotFoundException, AccessControlException,
			AccessDeniedException, LockException, VersionException, RepositoryException {
		adaptTo(JcrSession.class).checkPrivileges(absPath, Privilege.JCR_MODIFY_ACCESS_CONTROL);
		Node node = fWorkspace.getNode(absPath);
		try {
			getWorkspaceQuery().items().removeAccessControlPolicy(node.getIdentifier());
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	private WorkspaceQuery getWorkspaceQuery() {
		return adaptTo(WorkspaceQuery.class);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}

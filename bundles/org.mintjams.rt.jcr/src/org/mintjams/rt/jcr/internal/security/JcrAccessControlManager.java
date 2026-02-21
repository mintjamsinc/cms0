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
import org.mintjams.jcr.security.UnknownGroupPrincipal;
import org.mintjams.jcr.security.UnknownUserPrincipal;
import org.mintjams.rt.jcr.internal.Activator;
import org.mintjams.rt.jcr.internal.JcrCache;
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
	private final JcrWorkspace fWorkspace;

	private JcrAccessControlManager(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static JcrAccessControlManager create(JcrWorkspace workspace) {
		return new JcrAccessControlManager(workspace);
	}

	public JcrAccessControlManager load() {
		JcrPrivilege read = JcrPrivilege.create(Privilege.JCR_READ, fWorkspace);
		JcrPrivilege modifyProperties = JcrPrivilege.create(Privilege.JCR_MODIFY_PROPERTIES, fWorkspace);
		JcrPrivilege addChildNodes = JcrPrivilege.create(Privilege.JCR_ADD_CHILD_NODES, fWorkspace);
		JcrPrivilege removeNode = JcrPrivilege.create(Privilege.JCR_REMOVE_NODE, fWorkspace);
		JcrPrivilege removeChildNodes = JcrPrivilege.create(Privilege.JCR_REMOVE_CHILD_NODES, fWorkspace);
		JcrPrivilege write = JcrPrivilege.create(Privilege.JCR_WRITE, fWorkspace)
				.addAggregatePrivilege(modifyProperties)
				.addAggregatePrivilege(addChildNodes)
				.addAggregatePrivilege(removeNode)
				.addAggregatePrivilege(removeChildNodes);
		JcrPrivilege readAccessControl = JcrPrivilege.create(Privilege.JCR_READ_ACCESS_CONTROL, fWorkspace);
		JcrPrivilege modifyAccessControl = JcrPrivilege.create(Privilege.JCR_MODIFY_ACCESS_CONTROL, fWorkspace);
		JcrPrivilege lockManagement = JcrPrivilege.create(Privilege.JCR_LOCK_MANAGEMENT, fWorkspace);
		JcrPrivilege versionManagement = JcrPrivilege.create(Privilege.JCR_VERSION_MANAGEMENT, fWorkspace);
		JcrPrivilege nodeTypeManagement = JcrPrivilege.create(Privilege.JCR_NODE_TYPE_MANAGEMENT, fWorkspace);
		JcrPrivilege retentionManagement = JcrPrivilege.create(Privilege.JCR_RETENTION_MANAGEMENT, fWorkspace);
		JcrPrivilege lifeCycleManagement = JcrPrivilege.create(Privilege.JCR_LIFECYCLE_MANAGEMENT, fWorkspace);
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

		fStandardPrivileges.put(read.getName(), read);
		fStandardPrivileges.put(modifyProperties.getName(), modifyProperties);
		fStandardPrivileges.put(addChildNodes.getName(), addChildNodes);
		fStandardPrivileges.put(removeNode.getName(), removeNode);
		fStandardPrivileges.put(removeChildNodes.getName(), removeChildNodes);
		fStandardPrivileges.put(write.getName(), write);
		fStandardPrivileges.put(readAccessControl.getName(), readAccessControl);
		fStandardPrivileges.put(modifyAccessControl.getName(), modifyAccessControl);
		fStandardPrivileges.put(lockManagement.getName(), lockManagement);
		fStandardPrivileges.put(versionManagement.getName(), versionManagement);
		fStandardPrivileges.put(nodeTypeManagement.getName(), nodeTypeManagement);
		fStandardPrivileges.put(retentionManagement.getName(), retentionManagement);
		fStandardPrivileges.put(lifeCycleManagement.getName(), lifeCycleManagement);
		fStandardPrivileges.put(all.getName(), all);
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
		try (Query.Result result = getWorkspaceQuery().items().collectAccessControlEntries(absPath.toString())) {
			Map<String, JcrAccessControlList> policies = new LinkedHashMap<>();
			for (AdaptableMap<String, Object> r : result) {
				JcrAccessControlList acl = policies.get(r.getString("item_path"));
				if (acl == null) {
					acl = JcrAccessControlList.create(r.getString("item_path"), this);
					policies.put(r.getString("item_path"), acl);
				}

				Principal principal;
				if (r.getBoolean("is_group")) {
					try {
						principal = Activator.getDefault().getGroupPrincipal(r.getString("principal_name"));
					} catch (PrincipalNotFoundException ignore) {
						principal = new UnknownGroupPrincipal(r.getString("principal_name"));
					}
				} else {
					try {
						principal = Activator.getDefault().getUserPrincipal(r.getString("principal_name"));
					} catch (PrincipalNotFoundException ignore) {
						principal = new UnknownUserPrincipal(r.getString("principal_name"));
					}
				}
				acl.addAccessControlEntry(principal, r.getBoolean("is_allow"), Arrays.stream(r.getObjectArray("privilege_names")).toArray(String[]::new));
			}
			return policies.values().toArray(AccessControlPolicy[]::new);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
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
				if (r.getBoolean("is_group")) {
					try {
						principal = Activator.getDefault().getGroupPrincipal(r.getString("principal_name"));
					} catch (PrincipalNotFoundException ignore) {
						principal = new UnknownGroupPrincipal(r.getString("principal_name"));
					}
				} else {
					try {
						principal = Activator.getDefault().getUserPrincipal(r.getString("principal_name"));
					} catch (PrincipalNotFoundException ignore) {
						principal = new UnknownUserPrincipal(r.getString("principal_name"));
					}
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
		JcrPath path = JcrPath.valueOf(absPath).with(adaptTo(NamespaceProvider.class));
		JcrSession session = adaptTo(JcrSession.class);
		if (session.isSystem()) {
			return new Privilege[] { privilegeFromName(Privilege.JCR_ALL) };
		}
		if (absPath.equals("/" + JcrNode.JCR_SYSTEM_NAME)) {
			return new Privilege[0];
		}
		if (absPath.startsWith("/" + JcrNode.JCR_SYSTEM_NAME + "/")) {
			return new Privilege[] { privilegeFromName(Privilege.JCR_READ) };
		}
		if (session.isService() || session.isAdmin()) {
			return new Privilege[] { privilegeFromName(Privilege.JCR_ALL) };
		}
		if (adaptTo(JcrWorkspaceProvider.class).getConfiguration().isPublicAccess(session, path)) {
			return new Privilege[] { privilegeFromName(Privilege.JCR_READ) };
		}

		Privilege[] privileges = adaptTo(JcrCache.class).getPrivileges(absPath);
		if (privileges == null) {
			List<Privilege> privilegeList = new ArrayList<>();
			List<String> principals = new ArrayList<>();
			principals.add(new EveryonePrincipal().getName());
			principals.addAll(session.getGroups().stream().map(Principal::getName).collect(Collectors.toList()));
			principals.add(session.getUserPrincipal().getName());
			AccessControlPolicy[] policies = _effectivePolicies(path);
			List<AccessControlPolicy> policyList = Arrays.asList(policies);
			Collections.reverse(policyList);
			for (AccessControlPolicy acp : policyList) {
				for (AccessControlEntry ace : ((JcrAccessControlList) acp).getAccessControlEntries()) {
					if (!principals.contains(ace.getPrincipal().getName())) {
						continue;
					}

					if (((JcrAccessControlEntry) ace).isAllow()) {
						for (Privilege privilege : ace.getPrivileges()) {
							if (!privilegeList.contains(privilege)) {
								privilegeList.add(privilege);
							}
						}
					} else {
						for (Privilege privilege : ace.getPrivileges()) {
							for (Privilege allowed : privilegeList.toArray(Privilege[]::new)) {
								if (allowed.equals(privilege) || ((JcrPrivilege) allowed).contains(privilege)) {
									privilegeList.remove(allowed);
								}
							}
						}
					}
				}
			}
			privileges = privilegeList.toArray(Privilege[]::new);
			adaptTo(JcrCache.class).setPrivileges(absPath, privileges);
		}
		return privileges;
	}

	@Override
	public Privilege[] getSupportedPrivileges(String absPath) throws PathNotFoundException, RepositoryException {
		return fStandardPrivileges.values().toArray(Privilege[]::new);
	}

	@Override
	public boolean hasPrivileges(String absPath, Privilege[] privileges) throws PathNotFoundException, RepositoryException {
		List<Privilege> checkList = new ArrayList<>(Arrays.asList(privileges));
		List<Privilege> allowList = Arrays.asList(getPrivileges(absPath));
		for (Privilege e : checkList.toArray(Privilege[]::new)) {
			for (Privilege p : allowList) {
				if (p.equals(e) || ((JcrPrivilege) p).contains(e)) {
					checkList.remove(e);
					break;
				}
			}
		}
		return checkList.isEmpty();
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

			removePolicy(absPath);
			try {
				getWorkspaceQuery().items().setAccessControlPolicy(
						node.getIdentifier(),
						Arrays.asList(acl.getAccessControlEntries()).toArray(JcrAccessControlEntry[]::new));
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
		List<Privilege> l = new ArrayList<>();
		for (String privilege : privileges) {
			l.add(JcrPrivilege.create(privilege, fWorkspace));
		}
		return hasPrivileges(absPath, l.toArray(Privilege[]::new));
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

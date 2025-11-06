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

package org.mintjams.rt.jcr.internal.version;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.ItemExistsException;
import javax.jcr.MergeException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.Session;
import org.mintjams.jcr.UncheckedRepositoryException;
import org.mintjams.jcr.security.Privilege;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.jcr.version.VersionManager;
import org.mintjams.rt.jcr.internal.JcrCache;
import org.mintjams.rt.jcr.internal.JcrNode;
import org.mintjams.rt.jcr.internal.JcrProperty;
import org.mintjams.rt.jcr.internal.JcrSession;
import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.rt.jcr.internal.JcrWorkspaceProvider;
import org.mintjams.rt.jcr.internal.WorkspaceQuery;
import org.mintjams.rt.jcr.internal.lock.JcrLock;
import org.mintjams.rt.jcr.internal.lock.JcrLockManager;
import org.mintjams.rt.jcr.internal.nodetype.JcrNodeType;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;

public class JcrVersionManager implements VersionManager, Adaptable {

	private final JcrWorkspace fWorkspace;

	private JcrVersionManager(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static JcrVersionManager create(JcrWorkspace workspace) {
		return new JcrVersionManager(workspace);
	}

	@Override
	public void cancelMerge(String absPath, Version version) throws VersionException, InvalidItemStateException,
			UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Version checkin(String absPath) throws VersionException, UnsupportedRepositoryOperationException,
			InvalidItemStateException, LockException, RepositoryException {
		Node item = fWorkspace.getSession().getNode(absPath);
		if (!item.isNodeType(NodeType.MIX_VERSIONABLE)) {
			throw new UnsupportedRepositoryOperationException("Node '" + absPath + "' is not " + NodeType.MIX_VERSIONABLE);
		}
		adaptTo(Session.class).checkPrivileges(item.getPath(), Privilege.JCR_VERSION_MANAGEMENT);
		checkItemState(item);
		JcrLock lock = null;
		try {
			lock = (JcrLock) adaptTo(JcrLockManager.class).getLock(absPath);
		} catch (LockException ignore) {}
		if (lock != null && !lock.isLockOwner()) {
			throw new LockException("Node '" + absPath + "' is locked.");
		}
		if (item.hasProperty(Property.JCR_MERGE_FAILED)) {
			throw new VersionException("Node '" + absPath + "' cannot be checked in due to existing merge conflicts.");
		}
		if (!item.getProperty(Property.JCR_IS_CHECKED_OUT).getBoolean()) {
			return getBaseVersion(absPath);
		}

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new SystemPrincipal(fWorkspace.getSession().getUserID()))) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);

			for (String lockToken : fWorkspace.getLockManager().getLockTokens()) {
				workspace.getLockManager().addLockToken(lockToken);
			}

			JcrPath versionHistoryPath = workspaceQuery.items().getVersionHistoryPath(item.getIdentifier());
			String baseVersionId = item.getProperty(JcrProperty.JCR_BASE_VERSION).getString();
			AdaptableMap<String,Object> baseVersionData = workspaceQuery.items().getNodeByIdentifier(baseVersionId);
			String baseVersionName = workspaceQuery.getResolved(baseVersionData.getString("item_name"));
			String versionName = null;
			if (baseVersionName.equals(workspaceQuery.getResolved(Node.JCR_ROOT_VERSION))) {
				for (int i = 1; versionName == null; i++) {
					String name = "" + i + ".0";
					try {
						workspaceQuery.items().getNode(versionHistoryPath.resolve(name).toString());
					} catch (PathNotFoundException ignore) {
						versionName = name;
					}
				}
			} else {
				int p = baseVersionName.lastIndexOf(".");
				String prefix = baseVersionName.substring(0, p + 1);
				int n = Integer.parseInt(baseVersionName.substring(p + 1));
				String name = prefix + (n + 1);
				try {
					workspaceQuery.items().getNode(versionHistoryPath.resolve(name).toString());
				} catch (PathNotFoundException ignore) {
					versionName = name;
				}
				for (name = baseVersionName + ".0"; versionName == null; name = name + ".0") {
					try {
						workspaceQuery.items().getNode(versionHistoryPath.resolve(name).toString());
					} catch (PathNotFoundException ignore) {
						versionName = name;
					}
				}
			}

			JcrPath versionPath = versionHistoryPath.resolve(versionName);
			AdaptableMap<String, Object> versionData = workspaceQuery.items().createNode(versionPath.toString(), NodeType.NT_VERSION);
			String versionId = versionData.getString("item_id");
			workspaceQuery.items().setProperty(versionId, JcrProperty.JCR_PREDECESSORS, PropertyType.REFERENCE, item.getProperty(JcrProperty.JCR_PREDECESSORS).getValues());
			workspaceQuery.items().setProperty(versionId, JcrProperty.JCR_CREATED, PropertyType.DATE, workspaceQuery.createValue(PropertyType.DATE, Calendar.getInstance()));
			workspaceQuery.items().setProperty(versionId, JcrProperty.JCR_CREATED_BY, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, fWorkspace.getSession().getUserID()));

			for (Value v : item.getProperty(JcrProperty.JCR_PREDECESSORS).getValues()) {
				Node predecessor = workspace.getSession().getNodeByIdentifier(v.getString());
				if (predecessor.hasProperty(JcrProperty.JCR_SUCCESSORS) && predecessor.getProperty(JcrProperty.JCR_SUCCESSORS).getValues().length > 0) {
					List<Value> l = new ArrayList<>();
					l.addAll(Arrays.asList(predecessor.getProperty(JcrProperty.JCR_SUCCESSORS).getValues()));
					l.add(workspaceQuery.createValue(PropertyType.REFERENCE, versionId));
					workspaceQuery.items().setProperty(predecessor.getIdentifier(), JcrProperty.JCR_SUCCESSORS,
							PropertyType.REFERENCE,
							l.toArray(Value[]::new));
				} else {
					workspaceQuery.items().setProperty(predecessor.getIdentifier(), JcrProperty.JCR_SUCCESSORS,
							PropertyType.REFERENCE,
							workspaceQuery.createValues(PropertyType.REFERENCE, versionId));
				}
			}

			AdaptableMap<String, Object> frozenNodeData = workspaceQuery.items().createNode(versionPath.resolve(JcrNode.JCR_FROZEN_NODE).toString(), NodeType.NT_FROZEN_NODE);
			freeze(item, frozenNodeData, workspaceQuery);

			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_IS_CHECKED_OUT, PropertyType.BOOLEAN, workspaceQuery.createValue(PropertyType.BOOLEAN, false));
			workspaceQuery.items().removeProperty(item.getIdentifier(), JcrProperty.MI_CHECKED_OUT_BY);
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_BASE_VERSION, PropertyType.REFERENCE, workspaceQuery.createValue(PropertyType.REFERENCE, versionId));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_PREDECESSORS, PropertyType.REFERENCE, new Value[0]);

			workspace.getSession().save();
			adaptTo(JcrCache.class).remove(item.getIdentifier());

			return JcrNode.create(versionData, adaptTo(JcrSession.class)).adaptTo(Version.class);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void checkout(String absPath)
			throws UnsupportedRepositoryOperationException, LockException, RepositoryException {
		Node item = fWorkspace.getSession().getNode(absPath);
		if (!item.isNodeType(NodeType.MIX_VERSIONABLE)) {
			throw new UnsupportedRepositoryOperationException("Node '" + absPath + "' is not " + NodeType.MIX_VERSIONABLE);
		}
		adaptTo(Session.class).checkPrivileges(item.getPath(), Privilege.JCR_VERSION_MANAGEMENT);
		JcrLock lock = null;
		try {
			lock = (JcrLock) adaptTo(JcrLockManager.class).getLock(absPath);
		} catch (LockException ignore) {}
		if (lock != null && !lock.isLockOwner()) {
			throw new LockException("Node '" + absPath + "' is locked.");
		}
		if (item.getProperty(Property.JCR_IS_CHECKED_OUT).getBoolean()) {
			return;
		}

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new SystemPrincipal(fWorkspace.getSession().getUserID()))) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);

			for (String lockToken : fWorkspace.getLockManager().getLockTokens()) {
				workspace.getLockManager().addLockToken(lockToken);
			}

			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_IS_CHECKED_OUT, PropertyType.BOOLEAN, workspaceQuery.createValue(PropertyType.BOOLEAN, true));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.MI_CHECKED_OUT_BY, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, workspace.getSession().getUserID()));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_PREDECESSORS, PropertyType.REFERENCE, workspaceQuery.createValues(PropertyType.REFERENCE, item.getProperty(JcrProperty.JCR_BASE_VERSION).getValue()));

			workspace.getSession().save();
			adaptTo(JcrCache.class).remove(item.getIdentifier());
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public Version checkpoint(String absPath) throws VersionException, UnsupportedRepositoryOperationException,
			InvalidItemStateException, LockException, RepositoryException {
		Version version = checkin(absPath);
		checkout(absPath);
		return version;
	}

	@Override
	public Node createActivity(String title) throws UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Node createConfiguration(String absPath) throws UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void doneMerge(String absPath, Version version) throws VersionException, InvalidItemStateException,
			UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Node getActivity() throws UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Version getBaseVersion(String absPath) throws UnsupportedRepositoryOperationException, RepositoryException {
		Node item = fWorkspace.getSession().getNode(absPath);
		if (!item.isNodeType(NodeType.MIX_VERSIONABLE)) {
			throw new UnsupportedRepositoryOperationException("Node '" + absPath + "' is not " + NodeType.MIX_VERSIONABLE);
		}

		return Adaptables.getAdapter(item.getProperty(Property.JCR_BASE_VERSION).getNode(), Version.class);
	}

	@Override
	public VersionHistory getVersionHistory(String absPath)
			throws UnsupportedRepositoryOperationException, RepositoryException {
		Node item = fWorkspace.getSession().getNode(absPath);
		if (!item.isNodeType(NodeType.MIX_VERSIONABLE)) {
			throw new UnsupportedRepositoryOperationException("Node '" + absPath + "' is not " + NodeType.MIX_VERSIONABLE);
		}

		return Adaptables.getAdapter(item.getProperty(Property.JCR_VERSION_HISTORY).getNode(), VersionHistory.class);
	}

	@Override
	public boolean isCheckedOut(String absPath) throws RepositoryException {
		for (Node item = fWorkspace.getSession().getNode(absPath); item != null; item = item.getParent()) {
			if (!item.hasProperty(Property.JCR_IS_CHECKED_OUT)) {
				continue;
			}
			return item.getProperty(Property.JCR_IS_CHECKED_OUT).getBoolean();
		}
		return true;
	}

	@Override
	public NodeIterator merge(Node activityNode) throws VersionException, AccessDeniedException, MergeException, LockException,
			InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NodeIterator merge(String absPath, String srcWorkspace, boolean bestEffort, boolean isShallow) throws NoSuchWorkspaceException,
			AccessDeniedException, MergeException, LockException, InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NodeIterator merge(String absPath, String srcWorkspace, boolean bestEffort) throws NoSuchWorkspaceException,
			AccessDeniedException, MergeException, LockException, InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void removeActivity(Node activityNode)
			throws UnsupportedRepositoryOperationException, VersionException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void restore(String absPath, String versionName, boolean removeExisting) throws VersionException, ItemExistsException,
			UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
		restore(absPath, getVersionHistory(absPath).getVersion(versionName), removeExisting);
	}

	@Override
	public void restore(String absPath, Version version, boolean removeExisting)
			throws PathNotFoundException, ItemExistsException, VersionException, ConstraintViolationException,
			UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
		fWorkspace.getSession().getNode(JcrPath.valueOf(absPath).getParent().toString());
		Node item = null;
		try {
			item = fWorkspace.getSession().getNode(absPath);
			if (!item.isNodeType(NodeType.MIX_VERSIONABLE)) {
				throw new UnsupportedRepositoryOperationException("Node '" + absPath + "' is not " + NodeType.MIX_VERSIONABLE);
			}
			adaptTo(Session.class).checkPrivileges(item.getPath(), Privilege.JCR_VERSION_MANAGEMENT);
			checkItemState(item);
			JcrLock lock = null;
			try {
				lock = (JcrLock) adaptTo(JcrLockManager.class).getLock(absPath);
			} catch (LockException ignore) {}
			if (lock != null && !lock.isLockOwner()) {
				throw new LockException("Node '" + absPath + "' is locked.");
			}
			if (!removeExisting) {
				throw new ItemExistsException("Node '" + absPath + "' exists.");
			}
			if (!version.getParent().getProperty(JcrProperty.JCR_VERSIONABLE_UUID).getString().equals(item.getIdentifier())) {
				throw new VersionException("Invalid version: " + version.getName());
			}
		} catch (PathNotFoundException ignore) {}
		if (version.getName().equals(JcrNode.JCR_ROOT_VERSION_NAME)) {
			throw new VersionException("Node cannot be restored to its root version.");
		}

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new SystemPrincipal(fWorkspace.getSession().getUserID()))) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);
			String itemPath = workspaceQuery.getResolved(JcrPath.valueOf(absPath).toString());

			for (String lockToken : fWorkspace.getLockManager().getLockTokens()) {
				workspace.getLockManager().addLockToken(lockToken);
			}

			if (item != null) {
				JcrNode.class.cast(workspace.getSession().getNode(absPath)).remove(options -> {
					if (options.get("path").equals(itemPath)) {
						options.put("leaveLock", true);
						options.put("leaveAccessControlPolicy", true);
						options.put("leaveVersionHistory", true);
					}
					return options;
				});
			}

			try {
				Map<String, Object> definition = new HashMap<>();
				definition.put("identifier", version.getFrozenNode().getProperty(JcrProperty.JCR_FROZEN_UUID).getString());
				definition.put("path", absPath);
				definition.put("primaryType", version.getFrozenNode().getProperty(JcrProperty.JCR_FROZEN_PRIMARY_TYPE).getString());
				definition.put("mixinTypes", Arrays.stream(version.getFrozenNode().getProperty(JcrProperty.JCR_FROZEN_MIXIN_TYPES).getValues())
						.map(e -> {
							try {
								return e.getString();
							} catch (RepositoryException ex) {
								throw new UncheckedRepositoryException(ex);
							}
						}).toArray(String[]::new));
				definition.put("disablePostProcess", true);
				AdaptableMap<String, Object> itemData = workspaceQuery.items().createNode(definition);
				restore(itemData, version.getFrozenNode(), workspaceQuery);
				item = JcrNode.create(itemData, (JcrSession) workspace.getSession());
			} catch (UncheckedRepositoryException ex) {
				throw ex.getCause();
			}

			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_IS_CHECKED_OUT, PropertyType.BOOLEAN, workspaceQuery.createValue(PropertyType.BOOLEAN, true));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.MI_CHECKED_OUT_BY, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, workspace.getSession().getUserID()));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_VERSION_HISTORY, PropertyType.REFERENCE, workspaceQuery.createValue(PropertyType.REFERENCE, version.getParent().getIdentifier()));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_BASE_VERSION, PropertyType.REFERENCE, workspaceQuery.createValue(PropertyType.REFERENCE, version.getIdentifier()));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_PREDECESSORS, PropertyType.REFERENCE, workspaceQuery.createValues(PropertyType.REFERENCE, version.getIdentifier()));

			workspace.getSession().save();
			adaptTo(JcrCache.class).remove(item.getIdentifier());
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void restore(Version version, boolean removeExisting) throws VersionException, ItemExistsException,
			InvalidItemStateException, UnsupportedRepositoryOperationException, LockException, RepositoryException {
		Node item = fWorkspace.getSession().getNodeByIdentifier(version.getContainingHistory().getVersionableIdentifier());
		restore(item.getPath(), version, removeExisting);
	}

	@Override
	public void restore(Version[] versions, boolean removeExisting)
			throws ItemExistsException, UnsupportedRepositoryOperationException, VersionException, LockException,
			InvalidItemStateException, RepositoryException {
		for (Version version : versions) {
			restore(version, removeExisting);
		}
	}

	@Override
	public void restoreByLabel(String absPath, String versionLabel, boolean removeExisting) throws VersionException, ItemExistsException,
			UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Node setActivity(Node activity) throws UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void uncheckout(String absPath) throws VersionException, UnsupportedRepositoryOperationException,
			InvalidItemStateException, LockException, RepositoryException {
		Node item = fWorkspace.getSession().getNode(absPath);
		if (!item.isNodeType(NodeType.MIX_VERSIONABLE)) {
			throw new UnsupportedRepositoryOperationException("Node '" + absPath + "' is not " + NodeType.MIX_VERSIONABLE);
		}
		adaptTo(Session.class).checkPrivileges(item.getPath(), Privilege.JCR_VERSION_MANAGEMENT);
		checkItemState(item);
		JcrLock lock = null;
		try {
			lock = (JcrLock) adaptTo(JcrLockManager.class).getLock(absPath);
		} catch (LockException ignore) {}
		if (lock != null && !lock.isLockOwner()) {
			throw new LockException("Node '" + absPath + "' is locked.");
		}
		if (!item.getProperty(Property.JCR_IS_CHECKED_OUT).getBoolean()) {
			throw new VersionException("Node '" + absPath + "' is not checked-out.");
		}
		if (getBaseVersion(item.getPath()).getName().equals(JcrNode.JCR_ROOT_VERSION_NAME)) {
			throw new VersionException("Node cannot be unchecked-out to its root version.");
		}

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new SystemPrincipal(fWorkspace.getSession().getUserID()))) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);
			String itemPath = item.getPath();
			Version version = getBaseVersion(item.getPath());

			for (String lockToken : fWorkspace.getLockManager().getLockTokens()) {
				workspace.getLockManager().addLockToken(lockToken);
			}

			JcrNode.class.cast(workspace.getSession().getNode(absPath)).remove(options -> {
				if (options.get("path").equals(itemPath)) {
					options.put("leaveLock", true);
					options.put("leaveAccessControlPolicy", true);
					options.put("leaveVersionHistory", true);
				}
				return options;
			});

			try {
				Map<String, Object> definition = new HashMap<>();
				definition.put("identifier", version.getFrozenNode().getProperty(JcrProperty.JCR_FROZEN_UUID).getString());
				definition.put("path", absPath);
				definition.put("primaryType", version.getFrozenNode().getProperty(JcrProperty.JCR_FROZEN_PRIMARY_TYPE).getString());
				definition.put("mixinTypes", Arrays.stream(version.getFrozenNode().getProperty(JcrProperty.JCR_FROZEN_MIXIN_TYPES).getValues())
						.map(e -> {
							try {
								return e.getString();
							} catch (RepositoryException ex) {
								throw new UncheckedRepositoryException(ex);
							}
						}).toArray(String[]::new));
				definition.put("disablePostProcess", true);
				AdaptableMap<String, Object> itemData = workspaceQuery.items().createNode(definition);
				restore(itemData, version.getFrozenNode(), workspaceQuery);
				item = JcrNode.create(itemData, (JcrSession) workspace.getSession());
			} catch (UncheckedRepositoryException ex) {
				throw ex.getCause();
			}

			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_IS_CHECKED_OUT, PropertyType.BOOLEAN, workspaceQuery.createValue(PropertyType.BOOLEAN, false));
			workspaceQuery.items().removeProperty(item.getIdentifier(), JcrProperty.MI_CHECKED_OUT_BY);
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_PREDECESSORS, PropertyType.REFERENCE, new Value[0]);

			workspace.getSession().save();
			adaptTo(JcrCache.class).remove(item.getIdentifier());
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	public void addVersionControl(String id) throws RepositoryException {
		Node item = fWorkspace.getSession().getNodeByIdentifier(id);
		adaptTo(Session.class).checkPrivileges(item.getPath(), Privilege.JCR_VERSION_MANAGEMENT);
		checkItemState(item);

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new SystemPrincipal(fWorkspace.getSession().getUserID()))) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);

			for (String lockToken : fWorkspace.getLockManager().getLockTokens()) {
				workspace.getLockManager().addLockToken(lockToken);
			}

			if (!item.hasProperty(JcrProperty.JCR_UUID_NAME)) {
				workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_UUID, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, item.getIdentifier()));
			}

			JcrPath versionHistoryPath = workspaceQuery.items().getVersionHistoryPath(item.getIdentifier());
			JCRs.mkdirs(versionHistoryPath.getParent(), workspace.getSession());
			AdaptableMap<String, Object> versionHistoryData = workspaceQuery.items().createNode(versionHistoryPath.toString(), NodeType.NT_VERSION_HISTORY);
			workspaceQuery.items().setProperty(versionHistoryData.getString("item_id"), JcrProperty.JCR_VERSIONABLE_UUID, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, item.getIdentifier()));

			JcrPath versionLabelsPath = versionHistoryPath.resolve(JcrNode.JCR_VERSION_LABELS);
			workspaceQuery.items().createNode(versionLabelsPath.toString(), JcrNodeType.NT_VERSION_LABELS_NAME);

			JcrPath versionPath = versionHistoryPath.resolve(JcrNode.JCR_ROOT_VERSION);
			AdaptableMap<String, Object> versionData = workspaceQuery.items().createNode(versionPath.toString(), NodeType.NT_VERSION);
			String versionId = versionData.getString("item_id");
			workspaceQuery.items().setProperty(versionId, JcrProperty.JCR_PREDECESSORS, PropertyType.REFERENCE, new Value[0]);
			workspaceQuery.items().setProperty(versionId, JcrProperty.JCR_CREATED, PropertyType.DATE, workspaceQuery.createValue(PropertyType.DATE, Calendar.getInstance()));
			workspaceQuery.items().setProperty(versionId, JcrProperty.JCR_CREATED_BY, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, fWorkspace.getSession().getUserID()));

			AdaptableMap<String, Object> frozenNodeData = workspaceQuery.items().createNode(versionPath.resolve(JcrNode.JCR_FROZEN_NODE).toString(), NodeType.NT_FROZEN_NODE);
			String frozenNodeId = frozenNodeData.getString("item_id");
			workspaceQuery.items().setProperty(frozenNodeId, JcrProperty.JCR_FROZEN_PRIMARY_TYPE, PropertyType.STRING, item.getProperty(JcrProperty.JCR_PRIMARY_TYPE).getValue());
			if (item.hasProperty(JcrProperty.JCR_MIXIN_TYPES)) {
				workspaceQuery.items().setProperty(frozenNodeId, JcrProperty.JCR_FROZEN_MIXIN_TYPES, PropertyType.STRING, item.getProperty(JcrProperty.JCR_MIXIN_TYPES).getValues());
			}
			workspaceQuery.items().setProperty(frozenNodeId, JcrProperty.JCR_FROZEN_UUID, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, item.getIdentifier()));

			List<String> mixinTypeNames = workspaceQuery.items().getMixinTypes(item.getIdentifier());
			mixinTypeNames.add(workspaceQuery.getResolved(NodeType.MIX_VERSIONABLE));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_MIXIN_TYPES, PropertyType.NAME, workspaceQuery.createValues(PropertyType.NAME, mixinTypeNames.toArray()));

			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_IS_CHECKED_OUT, PropertyType.BOOLEAN, workspaceQuery.createValue(PropertyType.BOOLEAN, true));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.MI_CHECKED_OUT_BY, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, workspace.getSession().getUserID()));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_VERSION_HISTORY, PropertyType.REFERENCE, workspaceQuery.createValue(PropertyType.REFERENCE, versionHistoryData.getString("item_id")));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_BASE_VERSION, PropertyType.REFERENCE, workspaceQuery.createValue(PropertyType.REFERENCE, versionId));
			workspaceQuery.items().setProperty(item.getIdentifier(), JcrProperty.JCR_PREDECESSORS, PropertyType.REFERENCE, workspaceQuery.createValues(PropertyType.REFERENCE, versionId));

			workspace.getSession().save();
			adaptTo(JcrCache.class).remove(item.getIdentifier());
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	private void checkItemState(Node item) throws RepositoryException {
		if (item.isNew() || item.isModified()) {
			throw new InvalidItemStateException("Node '" + item.getPath() + "' has unsaved changes.");
		}

		for (NodeIterator i = item.getNodes(); i.hasNext();) {
			checkItemState(i.nextNode());
		}
	}

	private void freeze(Node item, AdaptableMap<String, Object> frozenNodeData, WorkspaceQuery workspaceQuery) throws IOException, SQLException, RepositoryException {
		String frozenNodeId = frozenNodeData.getString("item_id");
		workspaceQuery.items().setProperty(frozenNodeId, JcrProperty.JCR_FROZEN_UUID, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, item.getIdentifier()));
		for (PropertyIterator i = item.getProperties(); i.hasNext();) {
			Property p = i.nextProperty();

			if (p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_LOCK_IS_DEEP))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_LOCK_OWNER))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_IS_CHECKED_OUT))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.MI_CHECKED_OUT_BY))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_VERSION_HISTORY))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_BASE_VERSION))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_PREDECESSORS))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_UUID))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_MERGE_FAILED))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_ACTIVITY))
					|| p.getName().equals(workspaceQuery.getResolved(JcrProperty.JCR_CONFIGURATION))) {
				continue;
			}

			if (p.getName().equals(JcrProperty.JCR_PRIMARY_TYPE_NAME)) {
				workspaceQuery.items().setProperty(frozenNodeId, JcrProperty.JCR_FROZEN_PRIMARY_TYPE, PropertyType.STRING, p.getValue());
				continue;
			}
			if (p.getName().equals(JcrProperty.JCR_MIXIN_TYPES_NAME)) {
				workspaceQuery.items().setProperty(frozenNodeId, JcrProperty.JCR_FROZEN_MIXIN_TYPES, PropertyType.STRING, p.getValues());
				continue;
			}

			if (p.isMultiple()) {
				workspaceQuery.items().setProperty(frozenNodeId, p.getName(), p.getType(), p.getValues());
			} else {
				workspaceQuery.items().setProperty(frozenNodeId, p.getName(), p.getType(), p.getValue());
			}
		}

		for (NodeIterator i = item.getNodes(); i.hasNext();) {
			Node childItem = i.nextNode();
			JcrPath childFrozenNodePath = JcrPath.valueOf(frozenNodeData.getString("item_path")).resolve(childItem.getName());
			AdaptableMap<String, Object> childFrozenNodeData = workspaceQuery.items().createNode(childFrozenNodePath.toString(), NodeType.NT_FROZEN_NODE);
			freeze(childItem, childFrozenNodeData, workspaceQuery);
		}
	}

	private void restore(AdaptableMap<String, Object> itemData, Node frozenNode, WorkspaceQuery workspaceQuery) throws IOException, SQLException, RepositoryException {
		String itemId = itemData.getString("item_id");
		for (PropertyIterator i = frozenNode.getProperties(); i.hasNext();) {
			Property p = i.nextProperty();

			if (p.getName().equals(JcrProperty.JCR_PRIMARY_TYPE_NAME)
					|| p.getName().equals(JcrProperty.JCR_FROZEN_PRIMARY_TYPE_NAME)
					|| p.getName().equals(JcrProperty.JCR_MIXIN_TYPES_NAME)
					|| p.getName().equals(JcrProperty.JCR_FROZEN_MIXIN_TYPES_NAME)) {
				continue;
			}

			if (p.isMultiple()) {
				workspaceQuery.items().setProperty(itemId, p.getName(), p.getType(), p.getValues());
			} else {
				workspaceQuery.items().setProperty(itemId, p.getName(), p.getType(), p.getValue());
			}
		}

		for (NodeIterator i = frozenNode.getNodes(); i.hasNext();) {
			Node childFrozenNode = i.nextNode();
			JcrPath childItemPath = JcrPath.valueOf(itemData.getString("item_path")).resolve(childFrozenNode.getName());
			AdaptableMap<String, Object> childItemData;
			try {
				Map<String, Object> definition = new HashMap<>();
				definition.put("identifier", childFrozenNode.getProperty(JcrProperty.JCR_FROZEN_UUID).getString());
				definition.put("path", childItemPath.toString());
				definition.put("primaryType", childFrozenNode.getProperty(JcrProperty.JCR_FROZEN_PRIMARY_TYPE).getString());
				definition.put("mixinTypes", Arrays.stream(childFrozenNode.getProperty(JcrProperty.JCR_FROZEN_MIXIN_TYPES).getValues())
						.map(e -> {
							try {
								return e.getString();
							} catch (RepositoryException ex) {
								throw new UncheckedRepositoryException(ex);
							}
						}).toArray(String[]::new));
				definition.put("disablePostProcess", true);
				childItemData = workspaceQuery.items().createNode(definition);
			} catch (UncheckedRepositoryException ex) {
				throw ex.getCause();
			}
			restore(childItemData, childFrozenNode, workspaceQuery);
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}

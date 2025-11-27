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

package org.mintjams.rt.jcr.internal.lock;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.jcr.AccessDeniedException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;
import javax.jcr.lock.LockManager;
import javax.jcr.nodetype.NodeType;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.Session;
import org.mintjams.jcr.observation.Event;
import org.mintjams.jcr.security.Privilege;
import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.rt.jcr.internal.JcrWorkspaceProvider;
import org.mintjams.rt.jcr.internal.WorkspaceQuery;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.sql.Query;

public class JcrLockManager implements LockManager, Adaptable {

	private final JcrWorkspace fWorkspace;
	private final List<String> fLockTokens = new ArrayList<>();

	private JcrLockManager(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static JcrLockManager create(JcrWorkspace workspace) {
		return new JcrLockManager(workspace);
	}

	@Override
	public void addLockToken(String lockToken) throws LockException, RepositoryException {
		if (Strings.isEmpty(lockToken)) {
			throw new IllegalArgumentException("Invalid lock token: " + lockToken);
		}

		if (fLockTokens.contains(lockToken)) {
			return;
		}

		fLockTokens.add(lockToken);
	}

	@Override
	public Lock getLock(String absPath)
			throws PathNotFoundException, LockException, AccessDeniedException, RepositoryException {
		try {
			return JcrLock.create(getWorkspaceQuery().items().getLock(absPath), fWorkspace.getSession());
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public String[] getLockTokens() throws RepositoryException {
		return fLockTokens.toArray(String[]::new);
	}

	@Override
	public boolean holdsLock(String absPath) throws PathNotFoundException, RepositoryException {
		try {
			return getLock(absPath).getNode().getPath().equals(JcrPath.valueOf(absPath).toString());
		} catch (LockException ignore) {
			return false;
		}
	}

	@Override
	public boolean isLocked(String absPath) throws PathNotFoundException, RepositoryException {
		try {
			getLock(absPath);
			return true;
		} catch (LockException ignore) {
			return false;
		}
	}

	@Override
	public Lock lock(String absPath, boolean isDeep, boolean isSessionScoped, long timeoutHint, String ownerInfo)
			throws LockException, PathNotFoundException, AccessDeniedException, InvalidItemStateException,
			RepositoryException {
		Node item = fWorkspace.getSession().getNode(absPath);
		if (!item.isNodeType(NodeType.MIX_LOCKABLE)) {
			throw new UnsupportedRepositoryOperationException("Node '" + absPath + "' is not " + NodeType.MIX_LOCKABLE);
		}
		adaptTo(Session.class).checkPrivileges(absPath, Privilege.JCR_LOCK_MANAGEMENT);
		checkItemState(item);
		// Check for existing lock on the item
		JcrLock lock = null;
		try {
			lock = (JcrLock) adaptTo(JcrLockManager.class).getLock(absPath);
		} catch (LockException ignore) {}
		if (lock != null) {
			throw new LockException("Node '" + absPath + "' is locked.");
		}
		// Check for locks on descendants if isDeep is true
		if (isDeep) {
			long count;
			try {
				count = getWorkspaceQuery().items().countNotOwnedLocksInDescendants(absPath);
			} catch (IOException | SQLException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}
			if (count > 0) {
				throw new LockException("A descendant node of node '" + absPath + "' is locked.");
			}
		}

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new SystemPrincipal(fWorkspace.getSession().getUserID()))) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);

			AdaptableMap<String,Object> lockData = AdaptableMap.<String, Object>newBuilder()
					.put("item_id", item.getIdentifier())
					.put("is_deep", isDeep)
					.put("session_id", isSessionScoped ? workspaceQuery.getSessionIdentifier().toString() : null)
					.put("timeout_hint", timeoutHint)
					.put("owner_info", ownerInfo)
					.put("principal_name", fWorkspace.getSession().getUserID())
					.put("lock_created", System.currentTimeMillis())
					.put("lock_token", UUID.randomUUID().toString())
					.build();
			try {
				workspaceQuery.items().locksEntity().create(lockData).execute();
			} catch (SQLException ignore) {
				throw new LockException("Node '" + absPath + "' is already locked.");
			}
			addLockToken(lockData.getString("lock_token"));

			workspaceQuery.items().setProperty(item.getIdentifier(), Property.JCR_LOCK_OWNER, PropertyType.STRING, workspaceQuery.createValue(PropertyType.STRING, fWorkspace.getSession().getUserID()));
			workspaceQuery.items().setProperty(item.getIdentifier(), Property.JCR_LOCK_IS_DEEP, PropertyType.BOOLEAN, workspaceQuery.createValue(PropertyType.BOOLEAN, isDeep));

			workspaceQuery.journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.LOCKED)
					.put("item_id", item.getIdentifier())
					.put("item_path", item.getPath())
					.put("primary_type", item.getPrimaryNodeType().getName())
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", null)
					.build());

			workspace.getSession().save();

			lock = JcrLock.create(lockData, fWorkspace.getSession());
			addLockToken(lock.getLockToken());

			return lock;
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void removeLockToken(String lockToken) throws LockException, RepositoryException {
		if (Strings.isEmpty(lockToken)) {
			throw new IllegalArgumentException("Invalid lock token: " + lockToken);
		}

		if (!fLockTokens.contains(lockToken)) {
			throw new LockException("Invalid lock token: " + lockToken);
		}

		fLockTokens.remove(lockToken);
	}

	@Override
	public void unlock(String absPath) throws PathNotFoundException, LockException, AccessDeniedException,
			InvalidItemStateException, RepositoryException {
		Node item = fWorkspace.getSession().getNode(absPath);
		if (!item.isNodeType(NodeType.MIX_LOCKABLE)) {
			throw new UnsupportedRepositoryOperationException("Node '" + absPath + "' is not " + NodeType.MIX_LOCKABLE);
		}
		adaptTo(Session.class).checkPrivileges(absPath, Privilege.JCR_LOCK_MANAGEMENT);
		checkItemState(item);
		JcrLock lock = null;
		try {
			lock = (JcrLock) adaptTo(JcrLockManager.class).getLock(absPath);
		} catch (LockException ignore) {}
		if (lock == null) {
			throw new LockException("Node '" + absPath + "' is not locked.");
		}

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new SystemPrincipal(fWorkspace.getSession().getUserID()))) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);

			if (!lock.getNode().getIdentifier().equals(item.getIdentifier())) {
				throw new LockException("Node '" + absPath + "' is locked on node '" + lock.getNode().getPath() + "'.");
			}

			if (!Arrays.asList(fWorkspace.getLockManager().getLockTokens()).contains(lock.getLockToken())) {
				throw new LockException("Could not unlock node '" + absPath + "'.");
			}

			AdaptableMap<String,Object> lockPk = AdaptableMap.<String, Object>newBuilder()
					.put("item_id", item.getIdentifier())
					.build();

			int count = workspaceQuery.items().locksEntity().deleteByPrimaryKey(lockPk).execute();
			if (count == 0) {
				throw new LockException("Could not unlock node '" + absPath + "'.");
			}

			workspaceQuery.journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.UNLOCKED)
					.put("item_id", item.getIdentifier())
					.put("item_path", item.getPath())
					.put("primary_type", item.getPrimaryNodeType().getName())
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", null)
					.build());

			workspaceQuery.items().removeProperty(item.getIdentifier(), Property.JCR_LOCK_OWNER);
			workspaceQuery.items().removeProperty(item.getIdentifier(), Property.JCR_LOCK_IS_DEEP);

			workspace.getSession().save();
			removeLockToken(lock.getLockToken());
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	public JcrLockManager load() throws IOException {
		try (Query.Result result = getWorkspaceQuery().items().listLockTokens()) {
			for (AdaptableMap<String, Object> r : result) {
				String lockToken = r.getString("lock_token");
				if (Strings.isEmpty(lockToken) || fLockTokens.contains(lockToken)) {
					continue;
				}
				fLockTokens.add(lockToken);
			}
			return this;
		} catch (SQLException ex) {
			throw Cause.create(ex).wrap(IOException.class);
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

	private WorkspaceQuery getWorkspaceQuery() {
		return adaptTo(WorkspaceQuery.class);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}

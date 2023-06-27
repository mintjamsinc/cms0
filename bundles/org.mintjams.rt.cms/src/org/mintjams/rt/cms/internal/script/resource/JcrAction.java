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

package org.mintjams.rt.cms.internal.script.resource;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;
import javax.jcr.lock.LockManager;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;

import org.mintjams.tools.adapter.Adaptable;

public class JcrAction implements Adaptable {

	private final Node fNode;

	private JcrAction(Node node) {
		fNode = node;
	}

	public static JcrAction create(Node node) {
		return new JcrAction(node);
	}

	public Node getNode() {
		return fNode;
	}

	public String getPath() throws RepositoryException {
		return getNode().getPath();
	}

	public Session getSession() throws RepositoryException {
		return (Session) getNode().getSession();
	}

	public LockManager getLockManager() throws RepositoryException {
		return (LockManager) getSession().getWorkspace().getLockManager();
	}

	public VersionManager getVersionManager() throws RepositoryException {
		return (VersionManager) getSession().getWorkspace().getVersionManager();
	}

	public JcrAction addLockable() throws RepositoryException {
		return addMixin(NodeType.MIX_LOCKABLE);
	}

	public JcrAction lock(boolean isDeep, boolean isSessionScoped) throws RepositoryException {
		getLockManager().lock(getPath(), isDeep, isSessionScoped, Long.MAX_VALUE, getSession().getUserID());
		return this;
	}

	public JcrAction unlock() throws RepositoryException {
		getLockManager().unlock(getPath());
		return this;
	}

	public boolean isLocked() throws RepositoryException {
		return getNode().isLocked();
	}

	public boolean holdsLock() throws RepositoryException {
		return getLockManager().holdsLock(getPath());
	}

	public Lock getLock() throws RepositoryException {
		return (Lock) getLockManager().getLock(getPath());
	}

	public JcrAction addLockToken() throws RepositoryException {
		if (!getLockManager().isLocked(getNode().getPath())) {
			return this;
		}

		Lock lock = getLockManager().getLock(getNode().getPath());
		if (!lock.isSessionScoped()) {
			if (lock.getLockOwner().equals(getSession().getUserID())) {
				try {
					getLockManager().addLockToken(lock.getLockToken());
				} catch (LockException ignore) {}
			}
		}

		return this;
	}

	public JcrAction checkLock() throws RepositoryException {
		if (!getLockManager().isLocked(getNode().getPath())) {
			return this;
		}

		Lock lock = getLockManager().getLock(getNode().getPath());
		if (lock.isSessionScoped()) {
			return this;
		}

		if (getLockManager().getLockTokens().length > 0) {
			return this;
		}

		throw new LockException(getNode().getPath());
	}

	public JcrAction checkDeepLock() throws RepositoryException {
		if (!getLockManager().isLocked(getNode().getPath())) {
			return this;
		}

		Lock lock = getLockManager().getLock(getNode().getPath());
		if (lock.isSessionScoped()) {
			return this;
		}

		if (!lock.isDeep()) {
			return this;
		}

		if (getLockManager().getLockTokens().length > 0) {
			return this;
		}

		throw new LockException(getNode().getPath());
	}

	public JcrAction addVersionControl() throws RepositoryException {
		return addMixin(NodeType.MIX_VERSIONABLE);
	}

	public Version checkpoint() throws RepositoryException {
		return getVersionManager().checkpoint(getPath());
	}

	public JcrAction checkout() throws RepositoryException {
		getVersionManager().checkout(getPath());
		return this;
	}

	public Version checkin() throws RepositoryException {
		return getVersionManager().checkin(getPath());
	}

	public void restore(String versionName) throws RepositoryException {
		getVersionManager().restore(getPath(), versionName, true);
	}

	public boolean isLockable() throws RepositoryException {
		return getNode().isNodeType(NodeType.MIX_LOCKABLE);
	}

	public boolean isVersionControlled() throws RepositoryException {
		return getNode().isNodeType(NodeType.MIX_VERSIONABLE);
	}

	public boolean isCheckedOut() throws RepositoryException {
		return getNode().isCheckedOut();
	}

	public JcrAction addReferenceable() throws RepositoryException {
		return addMixin(NodeType.MIX_REFERENCEABLE);
	}

	public JcrAction removeReferenceable() throws RepositoryException {
		return removeMixin(NodeType.MIX_REFERENCEABLE);
	}

	public boolean isReferenceable() throws RepositoryException {
		return getNode().isNodeType(NodeType.MIX_REFERENCEABLE);
	}

	public JcrAction addMixin(String mixinName) throws RepositoryException {
		return addMixin(getNode(), mixinName);
	}

	public JcrAction addMixin(Node node, String mixinName) throws RepositoryException {
		if (!node.isNodeType(mixinName)) {
			node.addMixin(mixinName);
		}
		return this;
	}

	public JcrAction removeMixin(String mixinName) throws RepositoryException {
		return removeMixin(getNode(), mixinName);
	}

	public JcrAction removeMixin(Node node, String mixinName) throws RepositoryException {
		if (node.isNodeType(mixinName)) {
			node.removeMixin(mixinName);
		}
		return this;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(Node.class)) {
			return (AdapterType) getNode();
		}

		return null;
	}

}

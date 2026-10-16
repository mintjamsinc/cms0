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

import java.util.Arrays;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;
import javax.jcr.lock.LockManager;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;

import org.mintjams.jcr.util.JCRs;
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
		return lock(isDeep, isSessionScoped, 0);
	}

	/**
	 * Locks the node, passing the timeout hint through to the lock manager.
	 * A non-positive timeout means the lock never times out. A file is always
	 * locked deep (see {@link #isDeepLock}).
	 */
	public JcrAction lock(boolean isDeep, boolean isSessionScoped, long timeoutSeconds) throws RepositoryException {
		getLockManager().lock(getPath(), isDeepLock(getNode(), isDeep), isSessionScoped,
				(timeoutSeconds > 0) ? timeoutSeconds : Long.MAX_VALUE, getSession().getUserID());
		return this;
	}

	/**
	 * Whether a lock on this node is to be deep. A file is always locked deep,
	 * whatever was asked: its content lives in its {@code jcr:content} child,
	 * and a shallow lock covers the locked node alone (JSR-283), so a shallow
	 * lock on a file would leave its content writable by anyone. A folder is
	 * locked as asked. Every way of locking — GraphQL, the Resource API, the
	 * EIP {@code cms:lock} — applies this rule.
	 */
	public static boolean isDeepLock(Node node, boolean isDeep) throws RepositoryException {
		return isDeep || JCRs.isFile(node);
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

	/**
	 * Refuses a change to the node while it is locked (on itself, or deep on
	 * an ancestor) by a lock this session does not hold the token of — the
	 * rule the JCR layer applies to the node's own properties.
	 *
	 * <p>Checked on the file node before its content is written, it also
	 * refuses a write under a shallow lock on the file, which the JCR layer
	 * lets through to {@code jcr:content}: files are locked deep now, but a
	 * lock taken shallow before that is still honoured.
	 */
	public JcrAction checkLock() throws RepositoryException {
		Lock lock = findLock();
		if (lock == null || holdsToken(lock)) {
			return this;
		}

		throw new LockException(lockedMessage(lock));
	}

	/** {@link #checkLock()} for a deep lock only: whether nodes may be added or removed beneath this one. */
	public JcrAction checkDeepLock() throws RepositoryException {
		Lock lock = findLock();
		if (lock == null || !lock.isDeep() || holdsToken(lock)) {
			return this;
		}

		throw new LockException(lockedMessage(lock));
	}

	private Lock findLock() throws RepositoryException {
		try {
			return getLockManager().getLock(getPath());
		} catch (LockException ignore) {
			return null;
		}
	}

	/**
	 * Whether this session holds the lock's token. An open-scoped lock's token
	 * is held by every session of its owner; a session-scoped lock's by the
	 * session that took it alone (see {@code documents/resource-locks.md}).
	 */
	private boolean holdsToken(Lock lock) throws RepositoryException {
		String token = lock.getLockToken();
		return token != null && Arrays.asList(getLockManager().getLockTokens()).contains(token);
	}

	private String lockedMessage(Lock lock) throws RepositoryException {
		String owner = lock.getLockOwner();
		return getPath() + " is locked" + ((owner != null && !owner.isEmpty()) ? " by " + owner : "") + ".";
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

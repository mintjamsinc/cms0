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

package org.mintjams.rt.jcr.internal;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.InvalidSerializedDataException;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.lock.LockException;
import javax.jcr.lock.LockManager;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.observation.ObservationManager;
import javax.jcr.query.QueryManager;
import javax.jcr.security.AccessControlManager;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionManager;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.NamespaceProvider;
import org.mintjams.jcr.UncheckedRepositoryException;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.rt.jcr.internal.lock.JcrLock;
import org.mintjams.rt.jcr.internal.lock.JcrLockManager;
import org.mintjams.rt.jcr.internal.nodetype.JcrNodeTypeManager;
import org.mintjams.rt.jcr.internal.observation.JcrObservationManager;
import org.mintjams.rt.jcr.internal.query.JcrQueryManager;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlManager;
import org.mintjams.rt.jcr.internal.security.ServicePrincipal;
import org.mintjams.rt.jcr.internal.version.JcrVersionManager;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.sql.Query;
import org.xml.sax.ContentHandler;

public class JcrWorkspace implements org.mintjams.jcr.Workspace, Closeable, Adaptable {

	private final UserPrincipal fUserPrincipal;
	private final JcrWorkspaceProvider fWorkspaceProvider;
	private final SessionIdentifier fSessionIdentifier;
	private final Closer fCloser = Closer.create();
	private final JcrCache fCache;
	private JcrSession fSession;
	private JcrNamespaceRegistry fNamespaceRegistry;
	private JcrNamespaceProvider fNamespaceProvider;
	private JcrNodeTypeManager fNodeTypeManager;
	private JcrAccessControlManager fAccessControlManager;
	private JcrLockManager fLockManager;
	private JcrVersionManager fVersionManager;
	private JcrQueryManager fQueryManager;
	private JcrObservationManager fObservationManager;
	private Connection fConnection;
	private WorkspaceQuery fWorkspaceQuery;

	private JcrWorkspace(UserPrincipal principal, JcrWorkspaceProvider workspaceProvider) {
		fUserPrincipal = principal;
		fWorkspaceProvider = workspaceProvider;
		fSessionIdentifier = SessionIdentifier.create(principal);
		fCache = fCloser.register(JcrCache.create(this));
	}

	public static JcrWorkspace create(UserPrincipal principal, JcrWorkspaceProvider workspaceProvider) {
		return new JcrWorkspace(principal, workspaceProvider);
	}

	public JcrWorkspace open() throws IOException, SQLException {
		fCloser.add(new Closeable() {
			@Override
			public void close() throws IOException {
				try {
					fWorkspaceProvider.closeSession(JcrWorkspace.this);
				} catch (Throwable ignore) {}
			}
		});

		fConnection = fWorkspaceProvider.getConnection();
		fCloser.add(new Closeable() {
			@Override
			public void close() throws IOException {
				try {
					fConnection.close();
				} catch (Throwable ignore) {}
			}
		});

		fWorkspaceQuery = WorkspaceQuery.create(this);
		fCloser.add(new Closeable() {
			@Override
			public void close() throws IOException {
				try {
					fWorkspaceQuery.items().unlockSessionScopedLocks();
					fWorkspaceQuery.commit();
				} catch (Throwable ignore) {
				} finally {
					try {
						fWorkspaceQuery.rollback();
					} catch (Throwable ignore) {}
				}
			}
		});
		fCloser.add(new Closeable() {
			@Override
			public void close() throws IOException {
				try {
					fWorkspaceQuery.rollback();
				} catch (Throwable ignore) {}
			}
		});

		fSession = JcrSession.create(fUserPrincipal, this);
		fNamespaceRegistry = JcrNamespaceRegistry.create(this);
		fNamespaceProvider = JcrNamespaceProvider.create(this);
		fNodeTypeManager = JcrNodeTypeManager.create(this);
		fAccessControlManager = JcrAccessControlManager.create(this).load();
		fVersionManager = JcrVersionManager.create(this);
		fLockManager = JcrLockManager.create(this).load();
		fQueryManager = JcrQueryManager.create(this);
		fObservationManager = JcrObservationManager.create(this);

		return this;
	}

	@Override
	public void clone(String srcWorkspace, String srcAbsPath, String destAbsPath, boolean removeExisting)
			throws NoSuchWorkspaceException, ConstraintViolationException, VersionException, AccessDeniedException,
			PathNotFoundException, ItemExistsException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void copy(String srcAbsPath, String destAbsPath) throws ConstraintViolationException, VersionException,
			AccessDeniedException, PathNotFoundException, ItemExistsException, LockException, RepositoryException {
		Node item = getSession().getNode(srcAbsPath);
		checkItemState(item);
		try {
			getSession().getNode(destAbsPath);
			throw new ItemExistsException("An item '" + destAbsPath + "' already exists.");
		} catch (PathNotFoundException ignore) {}
		Node destParentItem = getSession().getNode(JcrPath.valueOf(destAbsPath).getParent().toString());
		JcrLock lock = null;
		try {
			lock = (JcrLock) adaptTo(JcrLockManager.class).getLock(destParentItem.getPath());
		} catch (LockException ignore) {}
		if (lock != null && !lock.isLockOwner()) {
			throw new LockException("Node '" + destParentItem.getPath() + "' is locked.");
		}

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new ServicePrincipal(fUserPrincipal.getName()))) {
			for (String lockToken : getLockManager().getLockTokens()) {
				workspace.getLockManager().addLockToken(lockToken);
			}

			copy(workspace.getSession().getNode(srcAbsPath), destAbsPath);

			workspace.getSession().save();
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	private void copy(Node srcItem, String destAbsPath) throws RepositoryException, IOException, SQLException {
		WorkspaceQuery workspaceQuery = Adaptables.getAdapter(srcItem, WorkspaceQuery.class);

		String primaryType = srcItem.getPrimaryNodeType().getName();
		String[] mixinTypes = Arrays.stream(srcItem.getMixinNodeTypes())
				.map(e -> {
					return e.getName();
				})
				.filter(e -> {
					if (e.equals(workspaceQuery.getResolved(NodeType.MIX_SIMPLE_VERSIONABLE))
							|| e.equals(workspaceQuery.getResolved(NodeType.MIX_VERSIONABLE))) {
						return false;
					}
					return true;
				})
				.toArray(String[]::new);
		List<String> propertyNames = new ArrayList<>();
		{
			List<String> nodeNames = new ArrayList<>();
			nodeNames.add(primaryType);
			nodeNames.addAll(Arrays.asList(mixinTypes));
			NodeTypeManager ntm = srcItem.getSession().getWorkspace().getNodeTypeManager();
			for (String type : nodeNames) {
				for (PropertyDefinition e : ntm.getNodeType(type).getPropertyDefinitions()) {
					if (e.getName().equals("*")) {
						continue;
					}
					if (propertyNames.contains(e.getName())) {
						continue;
					}
					propertyNames.add(e.getName());
				}
			}
		}

		AdaptableMap<String, Object> destItemData;
		try {
			Map<String, Object> definition = new HashMap<>();
			definition.put("path", destAbsPath);
			definition.put("primaryType", primaryType);
			definition.put("mixinTypes", mixinTypes);
			destItemData = workspaceQuery.items().createNode(definition);
		} catch (UncheckedRepositoryException ex) {
			throw ex.getCause();
		}

		String destItemId = destItemData.getString("item_id");
		for (PropertyIterator i = srcItem.getProperties(); i.hasNext();) {
			Property p = i.nextProperty();

			if (p.getName().startsWith(JcrNamespaceRegistry.PREFIX_JCR + ":")) {
				if (!propertyNames.contains(p.getName())) {
					continue;
				}
			}

			if (p.isMultiple()) {
				workspaceQuery.items().setProperty(destItemId, p.getName(), p.getType(), p.getValues());
			} else {
				workspaceQuery.items().setProperty(destItemId, p.getName(), p.getType(), p.getValue());
			}
		}

		for (NodeIterator i = srcItem.getNodes(); i.hasNext();) {
			Node childItem = i.nextNode();
			copy(childItem, workspaceQuery.getResolved(JcrPath.valueOf(destAbsPath)).resolve(childItem.getName()).toString());
		}
	}

	@Override
	public void copy(String srcWorkspace, String srcAbsPath, String destAbsPath)
			throws NoSuchWorkspaceException, ConstraintViolationException, VersionException, AccessDeniedException,
			PathNotFoundException, ItemExistsException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void createWorkspace(String name)
			throws AccessDeniedException, UnsupportedRepositoryOperationException, RepositoryException {
		createWorkspace(name, null);
	}

	@Override
	public void createWorkspace(String name, String srcWorkspace) throws AccessDeniedException,
			UnsupportedRepositoryOperationException, NoSuchWorkspaceException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteWorkspace(String name) throws AccessDeniedException, UnsupportedRepositoryOperationException,
			NoSuchWorkspaceException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public String[] getAccessibleWorkspaceNames() throws RepositoryException {
		return adaptTo(JcrRepository.class).getAvailableWorkspaceNames();
	}

	@Override
	public ContentHandler getImportContentHandler(String parentAbsPath, int uuidBehavior) throws PathNotFoundException,
			ConstraintViolationException, VersionException, LockException, AccessDeniedException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public LockManager getLockManager() throws UnsupportedRepositoryOperationException, RepositoryException {
		return fLockManager;
	}

	@Override
	public String getName() {
		return fWorkspaceProvider.getWorkspaceName();
	}

	@Override
	public NamespaceRegistry getNamespaceRegistry() throws RepositoryException {
		return fNamespaceRegistry;
	}

	@Override
	public NodeTypeManager getNodeTypeManager() throws RepositoryException {
		return adaptTo(JcrNodeTypeManager.class);
	}

	@Override
	public ObservationManager getObservationManager()
			throws UnsupportedRepositoryOperationException, RepositoryException {
		return fObservationManager;
	}

	@Override
	public QueryManager getQueryManager() throws RepositoryException {
		return fQueryManager;
	}

	@Override
	public Session getSession() {
		return fSession;
	}

	@Override
	public VersionManager getVersionManager() throws UnsupportedRepositoryOperationException, RepositoryException {
		return fVersionManager;
	}

	@Override
	public void importXML(String parentAbsPath, InputStream in, int uuidBehavior) throws IOException, VersionException,
			PathNotFoundException, ItemExistsException, ConstraintViolationException, InvalidSerializedDataException,
			LockException, AccessDeniedException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void move(String srcAbsPath, String destAbsPath) throws ConstraintViolationException, VersionException,
			AccessDeniedException, PathNotFoundException, ItemExistsException, LockException, RepositoryException {
		Node item = getSession().getNode(srcAbsPath);
		checkItemState(item);
		try {
			getSession().getNode(destAbsPath);
			throw new ItemExistsException("An item '" + destAbsPath + "' already exists.");
		} catch (PathNotFoundException ignore) {}
		Node destParentItem = getSession().getNode(JcrPath.valueOf(destAbsPath).getParent().toString());
		JcrLock lock = null;
		try {
			lock = (JcrLock) adaptTo(JcrLockManager.class).getLock(destParentItem.getPath());
		} catch (LockException ignore) {}
		if (lock != null && !lock.isLockOwner()) {
			throw new LockException("Node '" + destParentItem.getPath() + "' is locked.");
		}

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new ServicePrincipal(fUserPrincipal.getName()))) {
			for (String lockToken : getLockManager().getLockTokens()) {
				workspace.getLockManager().addLockToken(lockToken);
			}

			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);
			workspaceQuery.items().moveNode(srcAbsPath, destAbsPath);

			workspace.getSession().save();
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void restore(Version[] versions, boolean removeExisting)
			throws ItemExistsException, UnsupportedRepositoryOperationException, VersionException, LockException,
			InvalidItemStateException, RepositoryException {
		fVersionManager.restore(versions, removeExisting);
	}

	public boolean isLive() {
		try {
			return (fConnection != null && !fConnection.isClosed());
		} catch (SQLException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	public <CloseableType extends Closeable> CloseableType add(CloseableType closeable) {
		return fCloser.register(closeable);
	}

	public <CloseableType extends Closeable> CloseableType remove(CloseableType closeable) {
		return fCloser.unregister(closeable);
	}

	public Node getNode(String absPath) throws PathNotFoundException, RepositoryException {
		absPath = JcrPath.valueOf(absPath).with(adaptTo(NamespaceProvider.class)).toString();
		AdaptableMap<String, Object> itemData = fCache.getNode(absPath);
		if (itemData == null) {
			try (Query.Result result = fWorkspaceQuery.items().collectNodes(absPath)) {
				for (AdaptableMap<String, Object> data : result) {
					fCache.setNode(data);
				}
			} catch (IOException | SQLException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}

			itemData = fCache.getNode(absPath);
			if (itemData == null) {
				throw new PathNotFoundException(absPath);
			}
		}

		return JcrNode.create(itemData, fSession);
	}

	public Node getNodeByIdentifier(String id) throws ItemNotFoundException, RepositoryException {
		AdaptableMap<String, Object> itemData = fCache.getNodeByIdentifier(id);
		if (itemData == null) {
			try (Query.Result result = fWorkspaceQuery.items().collectNodesByIdentifier(id)) {
				for (AdaptableMap<String, Object> data : result) {
					fCache.setNode(data);
				}
			} catch (IOException | SQLException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}

			itemData = fCache.getNodeByIdentifier(id);
			if (itemData == null) {
				throw new ItemNotFoundException(id);
			}
		}

		return JcrNode.create(itemData, fSession);
	}

	@Override
	public void close() throws IOException {
		fCloser.close();
	}

	@Override
	public int hashCode() {
		return fSessionIdentifier.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof JcrWorkspace)) {
			return false;
		}
		return (hashCode() == obj.hashCode());
	}

	private void checkItemState(Node item) throws RepositoryException {
		if (item.isNew() || item.isModified()) {
			throw new InvalidItemStateException("Node '" + item.getPath() + "' has unsaved changes.");
		}

		for (NodeIterator i = item.getNodes(); i.hasNext();) {
			checkItemState(i.nextNode());
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(SessionIdentifier.class)) {
			return (AdapterType) fSessionIdentifier;
		}

		if (adapterType.equals(JcrCache.class)) {
			return (AdapterType) fCache;
		}

		if (adapterType.equals(Connection.class)) {
			return (AdapterType) fConnection;
		}

		if (adapterType.equals(WorkspaceQuery.class)) {
			return (AdapterType) fWorkspaceQuery;
		}

		if (adapterType.equals(Session.class) || adapterType.equals(org.mintjams.jcr.Session.class) || adapterType.equals(JcrSession.class)) {
			return (AdapterType) fSession;
		}

		if (adapterType.equals(NamespaceRegistry.class) || adapterType.equals(JcrNamespaceRegistry.class)) {
			return (AdapterType) fNamespaceRegistry;
		}

		if (adapterType.equals(NamespaceProvider.class) || adapterType.equals(JcrNamespaceProvider.class)) {
			return (AdapterType) fNamespaceProvider;
		}

		if (adapterType.equals(NodeTypeManager.class) || adapterType.equals(JcrNodeTypeManager.class)) {
			return (AdapterType) fNodeTypeManager;
		}

		if (adapterType.equals(AccessControlManager.class) || adapterType.equals(JcrAccessControlManager.class)) {
			return (AdapterType) fAccessControlManager;
		}

		if (adapterType.equals(LockManager.class) || adapterType.equals(JcrLockManager.class)) {
			return (AdapterType) fLockManager;
		}

		if (adapterType.equals(VersionManager.class) || adapterType.equals(JcrVersionManager.class)) {
			return (AdapterType) fVersionManager;
		}

		if (adapterType.equals(ObservationManager.class) || adapterType.equals(JcrObservationManager.class)) {
			return (AdapterType) fObservationManager;
		}

		return Adaptables.getAdapter(fWorkspaceProvider, adapterType);
	}

}

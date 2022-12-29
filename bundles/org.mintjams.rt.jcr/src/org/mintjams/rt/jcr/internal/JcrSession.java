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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.AccessControlException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import javax.jcr.AccessDeniedException;
import javax.jcr.Credentials;
import javax.jcr.InvalidItemStateException;
import javax.jcr.InvalidSerializedDataException;
import javax.jcr.Item;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.LoginException;
import javax.jcr.NamespaceException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.ValueFactory;
import javax.jcr.Workspace;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.retention.RetentionManager;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;
import javax.jcr.version.VersionException;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.Session;
import org.mintjams.jcr.security.AdminPrincipal;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.GuestPrincipal;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.rt.jcr.internal.lock.JcrLockManager;
import org.mintjams.rt.jcr.internal.observation.JournalObserver;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlManager;
import org.mintjams.rt.jcr.internal.security.ServicePrincipal;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

public class JcrSession implements Session, Adaptable {

	private final UserPrincipal fPrincipal;
	private final Collection<GroupPrincipal> fGroups;
	private final JcrWorkspace fWorkspace;
	private final Map<String, String> fNamespaces = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	private JcrSession(UserPrincipal principal, JcrWorkspace workspace) {
		fPrincipal = principal;
		fGroups = Activator.getDefault().getMemberOf(principal);
		fWorkspace = workspace;
	}

	public static JcrSession create(UserPrincipal principal, JcrWorkspace workspace) {
		return new JcrSession(principal, workspace);
	}

	@Override
	public void addLockToken(String lockToken) {
		try {
			adaptTo(JcrLockManager.class).addLockToken(lockToken);
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public void checkPermission(String path, String actions) throws AccessControlException, RepositoryException {
		if (!hasPermission(path, actions)) {
			throw new AccessDeniedException("Access denied: " + path + " (" + String.join(", ", actions.split("\\s*,\\s*")) + ")");
		}
	}

	@Override
	public void exportDocumentView(String absPath, ContentHandler contentHandler, boolean skipBinary, boolean noRecurse)
			throws PathNotFoundException, SAXException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void exportDocumentView(String absPath, OutputStream out, boolean skipBinary, boolean noRecurse)
			throws IOException, PathNotFoundException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void exportSystemView(String absPath, ContentHandler contentHandler, boolean skipBinary, boolean noRecurse)
			throws PathNotFoundException, SAXException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void exportSystemView(String absPath, OutputStream out, boolean skipBinary, boolean noRecurse)
			throws IOException, PathNotFoundException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public AccessControlManager getAccessControlManager()
			throws UnsupportedRepositoryOperationException, RepositoryException {
		return adaptTo(AccessControlManager.class);
	}

	@Override
	public Object getAttribute(String name) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String[] getAttributeNames() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ContentHandler getImportContentHandler(String parentAbsPath, int uuidBehavior) throws PathNotFoundException,
			ConstraintViolationException, VersionException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Item getItem(String absPath) throws PathNotFoundException, RepositoryException {
		try {
			return getNode(absPath);
		} catch (PathNotFoundException ignore) {}

		try {
			return getProperty(absPath);
		} catch (PathNotFoundException ignore) {}

		throw new PathNotFoundException(absPath);
	}

	@Override
	public String[] getLockTokens() {
		try {
			return adaptTo(JcrLockManager.class).getLockTokens();
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public String getNamespacePrefix(String uri) throws NamespaceException, RepositoryException {
		if (Strings.isEmpty(uri)) {
			throw new NamespaceException("URI must not be null or empty.");
		}

		for (Map.Entry<String, String> e : fNamespaces.entrySet()) {
			if (e.getValue().equalsIgnoreCase(uri)) {
				return e.getKey();
			}
		}

		throw new NamespaceException("Unknown URI: " + uri);
	}

	@Override
	public String[] getNamespacePrefixes() throws RepositoryException {
		return fNamespaces.keySet().toArray(String[]::new);
	}

	@Override
	public String getNamespaceURI(String prefix) throws NamespaceException, RepositoryException {
		if (Strings.isEmpty(prefix)) {
			throw new NamespaceException("Prefix must not be null or empty.");
		}
		if (!fNamespaces.containsKey(prefix)) {
			throw new NamespaceException("Unknown prefix: " + prefix);
		}

		return fNamespaces.get(prefix);
	}

	@Override
	public Node getNode(String absPath) throws PathNotFoundException, RepositoryException {
		Node item = fWorkspace.getNode(absPath);
		checkPrivileges(item.getPath(), Privilege.JCR_READ);
		return item;
	}

	@Override
	public Node getNodeByIdentifier(String id) throws ItemNotFoundException, RepositoryException {
		Node item = fWorkspace.getNodeByIdentifier(id);
		checkPrivileges(item.getPath(), Privilege.JCR_READ);
		return item;
	}

	@Override
	public Node getNodeByUUID(String uuid) throws ItemNotFoundException, RepositoryException {
		return getNodeByIdentifier(uuid);
	}

	@Override
	public Property getProperty(String absPath) throws PathNotFoundException, RepositoryException {
		JcrPath path = JcrPath.valueOf(absPath);
		JcrPath nodePath = path.getParent();
		if (nodePath == null) {
			throw new PathNotFoundException(absPath);
		}
		return getNode(nodePath.toString()).getProperty(path.getName().toString());
	}

	@Override
	public Repository getRepository() {
		return adaptTo(JcrRepository.class);
	}

	@Override
	public RetentionManager getRetentionManager() throws UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Node getRootNode() throws RepositoryException {
		return (Node) getItem("/");
	}

	@Override
	public String getUserID() {
		return fPrincipal.getName();
	}

	@Override
	public ValueFactory getValueFactory() throws UnsupportedRepositoryOperationException, RepositoryException {
		return adaptTo(ValueFactory.class);
	}

	@Override
	public Workspace getWorkspace() {
		return fWorkspace;
	}

	@Override
	public boolean hasCapability(String methodName, Object target, Object[] arguments) throws RepositoryException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean hasPendingChanges() throws RepositoryException {
		try {
			return getWorkspaceQuery().items().hasPendingChanges();
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public boolean hasPermission(String absPath, String actions) throws RepositoryException {
		JcrAccessControlManager acm = adaptTo(JcrAccessControlManager.class);
		JcrPath path = JcrPath.valueOf(absPath);
		boolean allow = true;
		for (String action : actions.split("\\s*,\\s*")) {
			switch (action) {
			case ACTION_ADD_NODE:
				if (!acm.hasPrivileges(path.getParent().toString(), Privilege.JCR_ADD_CHILD_NODES)) {
					allow = false;
				}
				break;
			case ACTION_SET_PROPERTY:
				if (!acm.hasPrivileges(path.toString(), Privilege.JCR_MODIFY_PROPERTIES)) {
					allow = false;
				}
				break;
			case ACTION_REMOVE:
				if (!acm.hasPrivileges(path.getParent().toString(), Privilege.JCR_REMOVE_CHILD_NODES)) {
					allow = false;
				}
				if (!acm.hasPrivileges(path.toString(), Privilege.JCR_REMOVE_NODE)) {
					allow = false;
				}
				break;
			case ACTION_READ:
				if (!acm.hasPrivileges(path.toString(), Privilege.JCR_READ)) {
					allow = false;
				}
				break;
			}

			if (!allow) {
				return false;
			}
		}
		return true;
	}

	@Override
	public Session impersonate(Credentials credentials) throws LoginException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void importXML(String parentAbsPath, InputStream in, int uuidBehavior)
			throws IOException, PathNotFoundException, ItemExistsException, ConstraintViolationException,
			VersionException, InvalidSerializedDataException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean isLive() {
		return fWorkspace.isLive();
	}

	@Override
	public boolean itemExists(String absPath) throws RepositoryException {
		return (getNode(absPath) != null);
	}

	@Override
	public void logout() {
		try {
			fWorkspace.close();
		} catch (IOException ex) {
			Activator.getDefault().getLogger(getClass()).warn("An error occurred while logging out of the session.", ex);
		}
	}

	@Override
	public void move(String srcAbsPath, String destAbsPath) throws ItemExistsException, PathNotFoundException,
			VersionException, ConstraintViolationException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean nodeExists(String absPath) throws RepositoryException {
		try {
			getNode(absPath);
			return true;
		} catch (PathNotFoundException ignore) {}
		return false;
	}

	@Override
	public boolean propertyExists(String absPath) throws RepositoryException {
		try {
			getProperty(absPath);
			return true;
		} catch (PathNotFoundException ignore) {}
		return false;
	}

	@Override
	public void refresh(boolean keepChanges) throws RepositoryException {
		checkLive();

		adaptTo(JcrCache.class).clear();

		if (keepChanges) {
			return;
		}

		try {
			getWorkspaceQuery().rollback();
		} catch (SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void removeItem(String absPath) throws VersionException, LockException, ConstraintViolationException,
			AccessDeniedException, RepositoryException {
		getNode(absPath).remove();
	}

	@Override
	public void removeLockToken(String lockToken) {
		try {
			adaptTo(JcrLockManager.class).removeLockToken(lockToken);
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public void save() throws AccessDeniedException, ItemExistsException, ReferentialIntegrityException,
			ConstraintViolationException, InvalidItemStateException, VersionException, LockException,
			NoSuchNodeTypeException, RepositoryException {
		checkLive();

		if (!hasPendingChanges()) {
			return;
		}

		try {
			getWorkspaceQuery().commit();
		} catch (SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}

		SessionIdentifier sessionIdentifier = adaptTo(SessionIdentifier.class);
		adaptTo(JournalObserver.class).comitted(sessionIdentifier.getTransactionIdentifier());
		adaptTo(WorkspaceCleaner.class).comitted();
		sessionIdentifier.nextTransaction();
		adaptTo(JcrCache.class).clear();
	}

	@Override
	public void setNamespacePrefix(String prefix, String uri)
			throws NamespaceException, RepositoryException {
		if (Strings.isEmpty(prefix)) {
			throw new NamespaceException("Prefix must not be null or empty.");
		}
		if (Strings.isEmpty(uri)) {
			throw new NamespaceException("URI must not be null or empty.");
		}
		if (JcrNamespaceRegistry.PREDEFINEDS.containsKey(prefix)) {
			throw new NamespaceException("Pre-defined prefix: " + prefix);
		}
		if (JcrNamespaceRegistry.PREDEFINEDS.containsKey(prefix)) {
			throw new NamespaceException("Pre-defined URI: " + uri);
		}

		fNamespaces.put(prefix, uri);
	}

	@Override
	public void checkPrivileges(String absPath, String... privileges) throws RepositoryException {
		if (!hasPrivileges(absPath, privileges)) {
			StringBuilder buf = new StringBuilder("Access denied: ").append(absPath)
					.append(" (").append(String.join(", ", privileges)).append(")");
			throw new AccessDeniedException(buf.toString());
		}
	}

	@Override
	public boolean hasPrivileges(String absPath, String... privileges) throws RepositoryException {
		return adaptTo(JcrAccessControlManager.class).hasPrivileges(absPath, privileges);
	}

	@Override
	public UserPrincipal getUserPrincipal() {
		return fPrincipal;
	}

	@Override
	public Collection<GroupPrincipal> getGroups() {
		return fGroups;
	}

	@Override
	public boolean isAdmin() {
		return (fPrincipal instanceof AdminPrincipal);
	}

	@Override
	public boolean isGuest() {
		return (fPrincipal instanceof GuestPrincipal);
	}

	@Override
	public boolean isAnonymous() {
		return isGuest();
	}

	@Override
	public boolean isSystem() {
		return (fPrincipal instanceof SystemPrincipal);
	}

	@Override
	public boolean isService() {
		return (fPrincipal instanceof ServicePrincipal);
	}

	private void checkLive() throws RepositoryException {
		if (!isLive()) {
			throw new RepositoryException("This session is already closed: " + adaptTo(SessionIdentifier.class).toString());
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

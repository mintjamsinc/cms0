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
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

import javax.jcr.AccessDeniedException;
import javax.jcr.Binary;
import javax.jcr.InvalidItemStateException;
import javax.jcr.InvalidLifecycleTransitionException;
import javax.jcr.Item;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.ItemVisitor;
import javax.jcr.MergeException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.ActivityViolationException;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;

import org.mintjams.jcr.UncheckedRepositoryException;
import org.mintjams.jcr.security.Privilege;
import org.mintjams.rt.jcr.internal.JcrNode;
import org.mintjams.rt.jcr.internal.JcrProperty;
import org.mintjams.rt.jcr.internal.JcrSession;
import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.rt.jcr.internal.JcrWorkspaceProvider;
import org.mintjams.rt.jcr.internal.WorkspaceQuery;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

public class JcrVersion implements Version, Adaptable {

	private final Node fItem;

	private JcrVersion(Node item) {
		fItem = item;
	}

	public static JcrVersion create(Node item) {
		if (item instanceof JcrVersion) {
			return (JcrVersion) item;
		}
		try {
			if (item.isNodeType(NodeType.NT_VERSION)) {
				return new JcrVersion(item);
			}
		} catch (Throwable ignore) {}
		return null;
	}

	@Override
	public void addMixin(String arg0) throws NoSuchNodeTypeException, VersionException, ConstraintViolationException,
			LockException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Node addNode(String arg0) throws ItemExistsException, PathNotFoundException, VersionException,
			ConstraintViolationException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Node addNode(String arg0, String arg1)
			throws ItemExistsException, PathNotFoundException, NoSuchNodeTypeException, LockException, VersionException,
			ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean canAddMixin(String arg0) throws NoSuchNodeTypeException, RepositoryException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void cancelMerge(Version arg0) throws VersionException, InvalidItemStateException,
			UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Version checkin() throws VersionException, UnsupportedRepositoryOperationException,
			InvalidItemStateException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void checkout() throws UnsupportedRepositoryOperationException, LockException, ActivityViolationException,
			RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void doneMerge(Version arg0) throws VersionException, InvalidItemStateException,
			UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void followLifecycleTransition(String arg0)
			throws UnsupportedRepositoryOperationException, InvalidLifecycleTransitionException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public String[] getAllowedLifecycleTransistions()
			throws UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Version getBaseVersion() throws UnsupportedRepositoryOperationException, RepositoryException {
		return fItem.getBaseVersion();
	}

	@Override
	public String getCorrespondingNodePath(String workspaceName)
			throws ItemNotFoundException, NoSuchWorkspaceException, AccessDeniedException, RepositoryException {
		return fItem.getCorrespondingNodePath(workspaceName);
	}

	@Override
	public NodeDefinition getDefinition() throws RepositoryException {
		return fItem.getDefinition();
	}

	@Override
	public String getIdentifier() throws RepositoryException {
		return fItem.getIdentifier();
	}

	@Override
	public int getIndex() throws RepositoryException {
		return fItem.getIndex();
	}

	@Override
	public Lock getLock()
			throws UnsupportedRepositoryOperationException, LockException, AccessDeniedException, RepositoryException {
		return fItem.getLock();
	}

	@Override
	public NodeType[] getMixinNodeTypes() throws RepositoryException {
		return fItem.getMixinNodeTypes();
	}

	@Override
	public Node getNode(String relPath) throws PathNotFoundException, RepositoryException {
		return fItem.getNode(relPath);
	}

	@Override
	public NodeIterator getNodes() throws RepositoryException {
		return fItem.getNodes();
	}

	@Override
	public NodeIterator getNodes(String namePattern) throws RepositoryException {
		return fItem.getNodes(namePattern);
	}

	@Override
	public NodeIterator getNodes(String[] nameGlobs) throws RepositoryException {
		return fItem.getNodes(nameGlobs);
	}

	@Override
	public Item getPrimaryItem() throws ItemNotFoundException, RepositoryException {
		return fItem.getPrimaryItem();
	}

	@Override
	public NodeType getPrimaryNodeType() throws RepositoryException {
		return fItem.getPrimaryNodeType();
	}

	@Override
	public PropertyIterator getProperties() throws RepositoryException {
		return fItem.getProperties();
	}

	@Override
	public PropertyIterator getProperties(String nameGlobs) throws RepositoryException {
		return fItem.getProperties(nameGlobs);
	}

	@Override
	public PropertyIterator getProperties(String[] nameGlobs) throws RepositoryException {
		return fItem.getProperties(nameGlobs);
	}

	@Override
	public Property getProperty(String relPath) throws PathNotFoundException, RepositoryException {
		return fItem.getProperty(relPath);
	}

	@Override
	public PropertyIterator getReferences() throws RepositoryException {
		return fItem.getReferences();
	}

	@Override
	public PropertyIterator getReferences(String name) throws RepositoryException {
		return fItem.getReferences(name);
	}

	@Override
	public NodeIterator getSharedSet() throws RepositoryException {
		return fItem.getSharedSet();
	}

	@Override
	public String getUUID() throws UnsupportedRepositoryOperationException, RepositoryException {
		return fItem.getUUID();
	}

	@Override
	public VersionHistory getVersionHistory() throws UnsupportedRepositoryOperationException, RepositoryException {
		return getContainingHistory();
	}

	@Override
	public PropertyIterator getWeakReferences() throws RepositoryException {
		return fItem.getWeakReferences();
	}

	@Override
	public PropertyIterator getWeakReferences(String name) throws RepositoryException {
		return fItem.getWeakReferences(name);
	}

	@Override
	public boolean hasNode(String relPath) throws RepositoryException {
		return fItem.hasNode(relPath);
	}

	@Override
	public boolean hasNodes() throws RepositoryException {
		return fItem.hasNodes();
	}

	@Override
	public boolean hasProperties() throws RepositoryException {
		return fItem.hasProperties();
	}

	@Override
	public boolean hasProperty(String relPath) throws RepositoryException {
		return fItem.hasProperty(relPath);
	}

	@Override
	public boolean holdsLock() throws RepositoryException {
		return fItem.holdsLock();
	}

	@Override
	public boolean isCheckedOut() throws RepositoryException {
		return fItem.isCheckedOut();
	}

	@Override
	public boolean isLocked() throws RepositoryException {
		return fItem.isLocked();
	}

	@Override
	public boolean isNodeType(String nodeTypeName) throws RepositoryException {
		return fItem.isNodeType(nodeTypeName);
	}

	@Override
	public Lock lock(boolean arg0, boolean arg1) throws UnsupportedRepositoryOperationException, LockException,
			AccessDeniedException, InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NodeIterator merge(String arg0, boolean arg1) throws NoSuchWorkspaceException, AccessDeniedException,
			MergeException, LockException, InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void orderBefore(String arg0, String arg1) throws UnsupportedRepositoryOperationException, VersionException,
			ConstraintViolationException, ItemNotFoundException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void removeMixin(String arg0) throws NoSuchNodeTypeException, VersionException, ConstraintViolationException,
			LockException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void removeShare()
			throws VersionException, LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void removeSharedSet()
			throws VersionException, LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void restore(String arg0, boolean arg1) throws VersionException, ItemExistsException,
			UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void restore(Version arg0, boolean arg1) throws VersionException, ItemExistsException,
			InvalidItemStateException, UnsupportedRepositoryOperationException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void restore(Version arg0, String arg1, boolean arg2)
			throws PathNotFoundException, ItemExistsException, VersionException, ConstraintViolationException,
			UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void restoreByLabel(String arg0, boolean arg1) throws VersionException, ItemExistsException,
			UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setPrimaryType(String arg0) throws NoSuchNodeTypeException, VersionException,
			ConstraintViolationException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Property setProperty(String arg0, Value arg1) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, Value[] arg1) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, String[] arg1) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, String arg1) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, InputStream arg1) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, Binary arg1) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, boolean arg1) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, double arg1) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, BigDecimal arg1) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, long arg1) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, Calendar arg1) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, Node arg1) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, Value arg1, int arg2) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, Value[] arg1, int arg2) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, String[] arg1, int arg2) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Property setProperty(String arg0, String arg1, int arg2) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void unlock() throws UnsupportedRepositoryOperationException, LockException, AccessDeniedException,
			InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void update(String arg0) throws NoSuchWorkspaceException, AccessDeniedException, LockException,
			InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void accept(ItemVisitor arg0) throws RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Item getAncestor(int visitor) throws ItemNotFoundException, AccessDeniedException, RepositoryException {
		return fItem.getAncestor(visitor);
	}

	@Override
	public int getDepth() throws RepositoryException {
		return fItem.getDepth();
	}

	@Override
	public String getName() throws RepositoryException {
		return fItem.getName();
	}

	@Override
	public Node getParent() throws ItemNotFoundException, AccessDeniedException, RepositoryException {
		return fItem.getParent();
	}

	@Override
	public String getPath() throws RepositoryException {
		return fItem.getPath();
	}

	@Override
	public Session getSession() throws RepositoryException {
		return fItem.getSession();
	}

	@Override
	public boolean isModified() {
		return false;
	}

	@Override
	public boolean isNew() {
		return false;
	}

	@Override
	public boolean isNode() {
		return true;
	}

	@Override
	public boolean isSame(Item otherItem) throws RepositoryException {
		return fItem.isSame(otherItem);
	}

	@Override
	public void refresh(boolean arg0) throws InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void remove() throws VersionException, LockException, ConstraintViolationException, AccessDeniedException,
			RepositoryException {
		Node item = fItem.getSession().getNodeByIdentifier(getContainingHistory().getVersionableIdentifier());
		((JcrSession) fItem.getSession()).checkPrivileges(item.getPath(), Privilege.JCR_VERSION_MANAGEMENT);

		try (JcrWorkspace workspace = adaptTo(JcrWorkspaceProvider.class).createSession(new SystemPrincipal(fItem.getSession().getUserID()))) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);

			Node versionLabels = workspace.getSession().getNodeByIdentifier(getContainingHistory().getIdentifier()).getNode(JcrNode.JCR_VERSION_LABELS);
			for (PropertyIterator i = versionLabels.getProperties(); i.hasNext();) {
				Property p = i.nextProperty();
				if (p.getString().equals(getIdentifier())) {
					workspaceQuery.items().removeProperty(versionLabels.getIdentifier(), p.getName());
				}
			}

			Version[] predecessors = getPredecessors();
			Version[] successors = getSuccessors();

			for (Version predecessor : predecessors) {
				List<String> l = new ArrayList<>();
				for (Value v : predecessor.getProperty(JcrProperty.JCR_SUCCESSORS).getValues()) {
					String id = v.getString();
					if (getIdentifier().equals(id)) {
						continue;
					}
					l.add(id);
				}
				for (Version v : successors) {
					l.add(v.getIdentifier());
				}
				workspaceQuery.items().setProperty(predecessor.getIdentifier(), JcrProperty.JCR_SUCCESSORS,
						PropertyType.REFERENCE,
						workspaceQuery.createValues(PropertyType.REFERENCE, l.toArray()));
			}

			for (Version successor : successors) {
				List<String> l = new ArrayList<>();
				for (Value v : successor.getProperty(JcrProperty.JCR_PREDECESSORS).getValues()) {
					String id = v.getString();
					if (getIdentifier().equals(id)) {
						continue;
					}
					l.add(id);
				}
				for (Version v : predecessors) {
					l.add(v.getIdentifier());
				}
				workspaceQuery.items().setProperty(successor.getIdentifier(), JcrProperty.JCR_PREDECESSORS,
						PropertyType.REFERENCE,
						workspaceQuery.createValues(PropertyType.REFERENCE, l.toArray()));
			}

			workspace.getSession().getNodeByIdentifier(getIdentifier()).remove();

			workspace.getSession().save();
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void save() throws AccessDeniedException, ItemExistsException, ConstraintViolationException,
			InvalidItemStateException, ReferentialIntegrityException, VersionException, LockException,
			NoSuchNodeTypeException, RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public VersionHistory getContainingHistory() throws RepositoryException {
		return Adaptables.getAdapter(getParent(), JcrVersionHistory.class);
	}

	@Override
	public Calendar getCreated() throws RepositoryException {
		return getProperty(Property.JCR_CREATED).getDate();
	}

	@Override
	public Node getFrozenNode() throws RepositoryException {
		return getNode(Node.JCR_FROZEN_NODE);
	}

	@Override
	public Version getLinearPredecessor() throws RepositoryException {
		String prefix = getName();
		prefix = prefix.substring(0, prefix.lastIndexOf(".") + 1);
		for (Version version : getPredecessors()) {
			String versionName = version.getName();
			if (versionName.startsWith(prefix) && versionName.substring(prefix.length()).indexOf(".") == -1) {
				return version;
			}
		}
		return null;
	}

	@Override
	public Version getLinearSuccessor() throws RepositoryException {
		String prefix = getName();
		prefix = prefix.substring(0, prefix.lastIndexOf(".") + 1);
		for (Version version : getSuccessors()) {
			String versionName = version.getName();
			if (versionName.startsWith(prefix) && versionName.substring(prefix.length()).indexOf(".") == -1) {
				return version;
			}
		}
		return null;
	}

	@Override
	public Version[] getPredecessors() throws RepositoryException {
		if (!hasProperty(JcrProperty.JCR_PREDECESSORS)) {
			return new Version[0];
		}

		List<Version> l = new ArrayList<>();
		for (Value v : getProperty(JcrProperty.JCR_PREDECESSORS).getValues()) {
			l.add(Adaptables.getAdapter(fItem.getSession().getNodeByIdentifier(v.getString()), Version.class));
		}
		try {
			l.sort(new Comparator<Version>() {
				@Override
				public int compare(Version o1, Version o2) {
					try {
						long t1 = o1.getCreated().getTimeInMillis();
						long t2 = o2.getCreated().getTimeInMillis();
						if (t1 < t2) {
							return 1;
						}
						if (t1 > t2) {
							return -1;
						}
						return 0;
					} catch (RepositoryException ex) {
						throw new UncheckedRepositoryException(ex);
					}
				}
			});
		} catch (UncheckedRepositoryException ex) {
			throw ex.getCause();
		}
		return l.toArray(Version[]::new);
	}

	@Override
	public Version[] getSuccessors() throws RepositoryException {
		if (!hasProperty(JcrProperty.JCR_SUCCESSORS)) {
			return new Version[0];
		}

		List<Version> l = new ArrayList<>();
		for (Value v : getProperty(JcrProperty.JCR_SUCCESSORS).getValues()) {
			l.add(Adaptables.getAdapter(fItem.getSession().getNodeByIdentifier(v.getString()), Version.class));
		}
		try {
			l.sort(new Comparator<Version>() {
				@Override
				public int compare(Version o1, Version o2) {
					try {
						long t1 = o1.getCreated().getTimeInMillis();
						long t2 = o2.getCreated().getTimeInMillis();
						if (t1 < t2) {
							return -1;
						}
						if (t1 > t2) {
							return 1;
						}
						return 0;
					} catch (RepositoryException ex) {
						throw new UncheckedRepositoryException(ex);
					}
				}
			});
		} catch (UncheckedRepositoryException ex) {
			throw ex.getCause();
		}
		return l.toArray(Version[]::new);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fItem, adapterType);
	}

}

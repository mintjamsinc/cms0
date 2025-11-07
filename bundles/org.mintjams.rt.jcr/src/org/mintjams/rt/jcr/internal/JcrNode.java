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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

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
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.security.Privilege;
import javax.jcr.version.ActivityViolationException;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;

import org.mintjams.jcr.JcrName;
import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.NamespaceProvider;
import org.mintjams.jcr.NamespaceRegistry;
import org.mintjams.jcr.UncheckedRepositoryException;
import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.jcr.internal.lock.JcrLockManager;
import org.mintjams.rt.jcr.internal.nodetype.JcrNodeTypeManager;
import org.mintjams.rt.jcr.internal.version.JcrVersion;
import org.mintjams.rt.jcr.internal.version.JcrVersionHistory;
import org.mintjams.rt.jcr.internal.version.JcrVersionManager;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.sql.Query;

public class JcrNode implements org.mintjams.jcr.Node, Adaptable {

	private final AdaptableMap<String, Object> fItemData;
	private final JcrSession fSession;

	private JcrNode(AdaptableMap<String, Object> itemData, JcrSession session) {
		fItemData = itemData;
		fSession = session;
	}

	public static JcrNode create(AdaptableMap<String, Object> itemData, JcrSession session) {
		return new JcrNode(itemData, session);
	}

	@Override
	public void accept(ItemVisitor mixinName) throws RepositoryException {
		// TODO Auto-generated method stub

	}

	@Override
	public Item getAncestor(int visitor) throws ItemNotFoundException, AccessDeniedException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getDepth() throws RepositoryException {
		String path = getPath();
		if (path.equals("/")) {
			return 0;
		}

		return path.substring(1).split("/").length;
	}

	@Override
	public String getName() throws RepositoryException {
		if (isRootNode()) {
			return "";
		}

		return JcrPath.valueOf(getPath()).getName().toString();
	}

	@Override
	public Node getParent() throws ItemNotFoundException, AccessDeniedException, RepositoryException {
		if (isRootNode()) {
			throw new ItemNotFoundException("This item is the root node.");
		}

		return fSession.getNodeByIdentifier(fItemData.getString("parent_item_id"));
	}

	@Override
	public String getPath() throws RepositoryException {
		return fItemData.getString("item_path");
	}

	@Override
	public Session getSession() throws RepositoryException {
		return fSession;
	}

	@Override
	public boolean isModified() {
		try {
			return getWorkspaceQuery().items().nodeIsModified(getIdentifier());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public boolean isNew() {
		try {
			return getWorkspaceQuery().items().nodeIsNew(getIdentifier());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public boolean isNode() {
		return true;
	}

	@Override
	public boolean isSame(Item otherItem) throws RepositoryException {
		return equals(otherItem);
	}

	@Override
	public void refresh(boolean keepChanges) throws InvalidItemStateException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public void remove() throws VersionException, LockException, ConstraintViolationException, AccessDeniedException,
			RepositoryException {
		remove(options -> {
			return options;
		});
	}

	public void remove(UnaryOperator<Map<String, Object>> op) throws VersionException, LockException,
			ConstraintViolationException, AccessDeniedException, RepositoryException {
		Map<String, Object> options = new HashMap<>();
		options.put("identifier", getIdentifier());
		options.put("path", getPath());
		options = op.apply(options);
		ExpressionContext el = ExpressionContext.create().setVariable("options", options);

		if (!el.getBoolean("options.force")) {
			if (isNodeType(NodeType.NT_VERSION_HISTORY) || isNodeType(NodeType.NT_VERSION)
					|| isNodeType(NodeType.NT_FROZEN_NODE)) {
				JcrPath path = JcrPath.valueOf(JCRs.getVersionHistoryNode(this)
						.getProperty(JcrProperty.JCR_VERSIONABLE_UUID).getNode().getPath());
				fSession.checkPrivileges(path.getParent().toString(), Privilege.JCR_REMOVE_CHILD_NODES);
				fSession.checkPrivileges(path.toString(), Privilege.JCR_REMOVE_NODE);
			} else {
				JcrPath path = JcrPath.valueOf(getPath());
				fSession.checkPrivileges(path.getParent().toString(), Privilege.JCR_REMOVE_CHILD_NODES);
				fSession.checkPrivileges(path.toString(), Privilege.JCR_REMOVE_NODE);
			}
			checkRemovable();
			if (adaptTo(JcrNodeTypeManager.class).isProtectedNode(getName())) {
				throw new ConstraintViolationException("The node '" + getName() + "' is protected.");
			}
		}

		for (NodeIterator i = getNodes(); i.hasNext();) {
			JcrNode childItem = (JcrNode) i.nextNode();
			childItem.remove(op);
		}

		if (hasProperty(JcrProperty.JCR_VERSION_HISTORY)) {
			if (!el.getBoolean("options.leaveVersionHistory")) {
				((JcrNode) getProperty(JcrProperty.JCR_VERSION_HISTORY).getNode()).remove(opt -> {
					opt.put("force", true);
					return opt;
				});
			}
		}

		try {
			getWorkspaceQuery().items().removeNode(getIdentifier(), options);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void save() throws AccessDeniedException, ItemExistsException, ConstraintViolationException,
			InvalidItemStateException, ReferentialIntegrityException, VersionException, LockException,
			NoSuchNodeTypeException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public void addMixin(String mixinName) throws NoSuchNodeTypeException, VersionException,
			ConstraintViolationException, LockException, RepositoryException {
		fSession.checkPrivileges(getPath(), Privilege.JCR_NODE_TYPE_MANAGEMENT);
		checkWritable();
		NodeType type = adaptTo(JcrNodeTypeManager.class).getNodeType(mixinName);
		if (!type.isMixin()) {
			throw new NoSuchNodeTypeException("Node type '" + mixinName + "' is not mixin type.");
		}
		try {
			getWorkspaceQuery().items().addMixin(getIdentifier(), mixinName);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public Node addNode(String relPath) throws ItemExistsException, PathNotFoundException, VersionException,
			ConstraintViolationException, LockException, RepositoryException {
		return addNode(relPath, null);
	}

	@Override
	public Node addNode(String relPath, String primaryNodeTypeName)
			throws ItemExistsException, PathNotFoundException, NoSuchNodeTypeException, LockException, VersionException,
			ConstraintViolationException, RepositoryException {
		fSession.checkPrivileges(getPath(), Privilege.JCR_ADD_CHILD_NODES);
		checkCanAddNode();
		JcrPath path = JcrPath.valueOf(getPath()).resolve(relPath);
		if (adaptTo(JcrNodeTypeManager.class).isProtectedNode(path.getName().toString())) {
			throw new ConstraintViolationException("The node '" + path.getName().toString() + "' is protected.");
		}
		try {
			Node node = JcrNode.create(getWorkspaceQuery().items().createNode(path.toString(), primaryNodeTypeName),
					fSession);
			if (primaryNodeTypeName != null) {
				fSession.checkPrivileges(node.getPath(), Privilege.JCR_NODE_TYPE_MANAGEMENT);
			}
			return node;
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public boolean canAddMixin(String mixinName) throws NoSuchNodeTypeException, RepositoryException {
		try {
			checkWritable();
			NodeType type = adaptTo(JcrNodeTypeManager.class).getNodeType(mixinName);
			if (!type.isMixin()) {
				return false;
			}
		} catch (Throwable ignore) {
			return false;
		}
		if (!fSession.hasPrivileges(getPath(), Privilege.JCR_NODE_TYPE_MANAGEMENT)) {
			return false;
		}
		for (NodeType type : getMixinNodeTypes()) {
			if (type.isNodeType(mixinName)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public void cancelMerge(Version version) throws VersionException, InvalidItemStateException,
			UnsupportedRepositoryOperationException, RepositoryException {
		adaptTo(JcrVersionManager.class).cancelMerge(getPath(), version);
	}

	@Override
	public Version checkin() throws VersionException, UnsupportedRepositoryOperationException,
			InvalidItemStateException, LockException, RepositoryException {
		return adaptTo(JcrVersionManager.class).checkin(getPath());
	}

	@Override
	public void checkout() throws UnsupportedRepositoryOperationException, LockException, ActivityViolationException,
			RepositoryException {
		adaptTo(JcrVersionManager.class).checkout(getPath());
	}

	@Override
	public void doneMerge(Version version) throws VersionException, InvalidItemStateException,
			UnsupportedRepositoryOperationException, RepositoryException {
		adaptTo(JcrVersionManager.class).doneMerge(getPath(), version);
	}

	@Override
	public void followLifecycleTransition(String transition)
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
		return adaptTo(JcrVersionManager.class).getBaseVersion(getPath());
	}

	@Override
	public String getCorrespondingNodePath(String workspaceName)
			throws ItemNotFoundException, NoSuchWorkspaceException, AccessDeniedException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NodeDefinition getDefinition() throws RepositoryException {
		if (isRootNode()) {
			return adaptTo(JcrNodeTypeManager.class).getRootNodeDefinition();
		}

		return JCRs.findChildNodeDefinition(getParent(), getName());
	}

	@Override
	public String getIdentifier() throws RepositoryException {
		return fItemData.getString("item_id");
	}

	@Override
	public int getIndex() throws RepositoryException {
		return 1;
	}

	@Override
	public Lock getLock()
			throws UnsupportedRepositoryOperationException, LockException, AccessDeniedException, RepositoryException {
		return adaptTo(JcrLockManager.class).getLock(getPath());
	}

	@Override
	public NodeType[] getMixinNodeTypes() throws RepositoryException {
		if (!hasProperty(Property.JCR_MIXIN_TYPES)) {
			return new NodeType[0];
		}

		try {
			return Arrays.stream(getProperty(Property.JCR_MIXIN_TYPES).getValues()).map(e -> {
				try {
					return adaptTo(NodeTypeManager.class).getNodeType(e.getString());
				} catch (RepositoryException ex) {
					throw new UncheckedRepositoryException(ex);
				}
			}).toArray(NodeType[]::new);
		} catch (UncheckedRepositoryException ex) {
			throw ex.getCause();
		}
	}

	@Override
	public Node getNode(String relPath) throws PathNotFoundException, RepositoryException {
		return fSession.getNode(JcrPath.valueOf(getPath()).resolve(relPath).toString());
	}

	@Override
	public NodeIterator getNodes() throws RepositoryException {
		return JcrNodeIterator.create(this);
	}

	@Override
	public NodeIterator getNodes(String namePattern) throws RepositoryException {
		List<String> globs = new ArrayList<>();
		for (String glob : namePattern.split("\\|")) {
			glob = Strings.trimToEmpty(glob);
			if (Strings.isEmpty(glob)) {
				throw new IllegalArgumentException("Invalid name pattern: " + namePattern);
			}
			globs.add(glob);
		}
		return getNodes(globs.toArray(String[]::new));
	}

	@Override
	public NodeIterator getNodes(String[] nameGlobs) throws RepositoryException {
		return JcrNodeIterator.create(this, nameGlobs);
	}

	@Override
	public Item getPrimaryItem() throws ItemNotFoundException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NodeType getPrimaryNodeType() throws RepositoryException {
		return adaptTo(NodeTypeManager.class).getNodeType(getProperty(Property.JCR_PRIMARY_TYPE).getString());
	}

	@Override
	public PropertyIterator getProperties() throws RepositoryException {
		return getProperties((String[]) null);
	}

	@Override
	public PropertyIterator getProperties(String namePattern) throws RepositoryException {
		List<String> globs = new ArrayList<>();
		for (String glob : namePattern.split("\\|")) {
			glob = Strings.trimToEmpty(glob);
			if (Strings.isEmpty(glob)) {
				throw new IllegalArgumentException("Invalid name pattern: " + namePattern);
			}
			globs.add(glob);
		}
		return getProperties(globs.toArray(String[]::new));
	}

	@Override
	public PropertyIterator getProperties(String[] nameGlobs) throws RepositoryException {
		return new CachedPropertyIterator(nameGlobs);
	}

	@Override
	public Property getProperty(String relPath) throws PathNotFoundException, RepositoryException {
		relPath = getWorkspaceQuery().getResolved(relPath);
		Map<String, AdaptableMap<String, Object>> properties = getCachedProperties();
		if (!properties.containsKey(relPath)) {
			throw new PathNotFoundException(getPath() + "/" + relPath);
		}
		return JcrProperty.create(properties.get(relPath), this);
	}

	@Override
	public PropertyIterator getReferences() throws RepositoryException {
		return getReferences((String) null);
	}

	@Override
	public PropertyIterator getReferences(String name) throws RepositoryException {
//		return createReferenceIterator(name, false);
		return JcrReferencePropertyIterator.create(this, name, false);
	}

	@Override
	public NodeIterator getSharedSet() throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getUUID() throws UnsupportedRepositoryOperationException, RepositoryException {
		return getIdentifier();
	}

	@Override
	public VersionHistory getVersionHistory() throws UnsupportedRepositoryOperationException, RepositoryException {
		return adaptTo(JcrVersionManager.class).getVersionHistory(getPath());
	}

	@Override
	public PropertyIterator getWeakReferences() throws RepositoryException {
		return getWeakReferences((String) null);
	}

	@Override
	public PropertyIterator getWeakReferences(String name) throws RepositoryException {
//		return createReferenceIterator(name, true);
		return JcrReferencePropertyIterator.create(this, name, true);
	}

	@Override
	public boolean hasNode(String relPath) throws RepositoryException {
		try {
			return (getWorkspaceQuery().items().countNodes(getIdentifier(),
					new String[] { getWorkspaceQuery().getResolved(relPath) }) > 0);
		} catch (PathNotFoundException ignore) {
			return false;
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public boolean hasNodes() throws RepositoryException {
		try {
			return (getWorkspaceQuery().items().countNodes(getIdentifier(), null) > 0);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public boolean hasProperties() throws RepositoryException {
		return !getCachedProperties().isEmpty();
	}

	@Override
	public boolean hasProperty(String relPath) throws RepositoryException {
		return getCachedProperties().containsKey(getWorkspaceQuery().getResolved(relPath));
	}

	@Override
	public boolean holdsLock() throws RepositoryException {
		return fSession.getWorkspace().getLockManager().holdsLock(getPath());
	}

	@Override
	public boolean isCheckedOut() throws RepositoryException {
		return adaptTo(JcrVersionManager.class).isCheckedOut(getPath());
	}

	@Override
	public boolean isLocked() throws RepositoryException {
		return fSession.getWorkspace().getLockManager().isLocked(getPath());
	}

	@Override
	public boolean isNodeType(String nodeTypeName) throws RepositoryException {
		nodeTypeName = JcrName.valueOf(nodeTypeName).with(adaptTo(NamespaceProvider.class)).toString();

		NodeType primaryType = getPrimaryNodeType();
		if (primaryType.isNodeType(nodeTypeName)) {
			return true;
		}

		for (NodeType mixinType : getMixinNodeTypes()) {
			if (mixinType.isNodeType(nodeTypeName)) {
				return true;
			}
		}

		return false;
	}

	@Override
	public Lock lock(boolean isDeep, boolean isSessionScoped) throws UnsupportedRepositoryOperationException,
			LockException, AccessDeniedException, InvalidItemStateException, RepositoryException {
		return fSession.getWorkspace().getLockManager().lock(getPath(), isDeep, isSessionScoped, -1, null);
	}

	@Override
	public NodeIterator merge(String srcWorkspace, boolean bestEffort) throws NoSuchWorkspaceException,
			AccessDeniedException, MergeException, LockException, InvalidItemStateException, RepositoryException {
		return adaptTo(JcrVersionManager.class).merge(getPath(), srcWorkspace, bestEffort);
	}

	@Override
	public void orderBefore(String srcChildRelPath, String destChildRelPath)
			throws UnsupportedRepositoryOperationException, VersionException, ConstraintViolationException,
			ItemNotFoundException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		JcrPath path = JcrPath.valueOf(getPath());
		fSession.checkPrivileges(path.getParent().toString(), Privilege.JCR_REMOVE_CHILD_NODES);
		fSession.checkPrivileges(path.getParent().toString(), Privilege.JCR_ADD_CHILD_NODES);
	}

	@Override
	public void removeMixin(String mixinName) throws NoSuchNodeTypeException, VersionException,
			ConstraintViolationException, LockException, RepositoryException {
		fSession.checkPrivileges(getPath(), Privilege.JCR_NODE_TYPE_MANAGEMENT);
		checkWritable();
		if (isNodeType(NodeType.MIX_REFERENCEABLE)) {
			try {
				if (getWorkspaceQuery().items().countReferenced(getIdentifier()) > 0) {
					throw new ConstraintViolationException("Node '" + getPath() + "' is referenced.");
				}
			} catch (IOException | SQLException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}
		}
		try {
			getWorkspaceQuery().items().removeMixin(getIdentifier(), mixinName);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
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
	public void restore(String versionName, boolean removeExisting) throws VersionException, ItemExistsException,
			UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
		adaptTo(JcrVersionManager.class).restore(getPath(), versionName, removeExisting);
	}

	@Override
	public void restore(Version version, boolean removeExisting) throws VersionException, ItemExistsException,
			InvalidItemStateException, UnsupportedRepositoryOperationException, LockException, RepositoryException {
		adaptTo(JcrVersionManager.class).restore(getPath(), version, removeExisting);
	}

	@Override
	public void restore(Version version, String relPath, boolean removeExisting)
			throws PathNotFoundException, ItemExistsException, VersionException, ConstraintViolationException,
			UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
		adaptTo(JcrVersionManager.class).restore(JcrPath.valueOf(getPath()).resolve(relPath).toString(), version,
				removeExisting);
	}

	@Override
	public void restoreByLabel(String versionLabel, boolean removeExisting)
			throws VersionException, ItemExistsException, UnsupportedRepositoryOperationException, LockException,
			InvalidItemStateException, RepositoryException {
		adaptTo(JcrVersionManager.class).restoreByLabel(getPath(), versionLabel, removeExisting);
	}

	@Override
	public void setPrimaryType(String nodeTypeName) throws NoSuchNodeTypeException, VersionException,
			ConstraintViolationException, LockException, RepositoryException {
		// TODO Auto-generated method stub
		fSession.checkPrivileges(getPath(), Privilege.JCR_NODE_TYPE_MANAGEMENT);
		checkWritable();
		NodeType type = adaptTo(JcrNodeTypeManager.class).getNodeType(nodeTypeName);
		if (!type.isMixin()) {
			throw new NoSuchNodeTypeException("Node type '" + nodeTypeName + "' is mixin type.");
		}
	}

	@Override
	public Property setProperty(String name, Value value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		int type = PropertyType.STRING;
		if (value != null) {
			type = value.getType();
		}
		return setProperty(name, value, type);
	}

	@Override
	public Property setProperty(String name, Value[] value) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		int type = PropertyType.STRING;
		if (value != null && value[0] != null) {
			type = value[0].getType();
		}
		return setProperty(name, value, type);
	}

	@Override
	public Property setProperty(String name, String[] value) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.STRING);
	}

	@Override
	public Property setProperty(String name, String value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.STRING);
	}

	@Override
	public Property setProperty(String name, InputStream value) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.BINARY);
	}

	@Override
	public Property setProperty(String name, Binary value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.BINARY);
	}

	@Override
	public Property setProperty(String name, boolean value) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.BOOLEAN);
	}

	@Override
	public Property setProperty(String name, double value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.DOUBLE);
	}

	@Override
	public Property setProperty(String name, BigDecimal value) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.DECIMAL);
	}

	@Override
	public Property setProperty(String name, long value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.LONG);
	}

	@Override
	public Property setProperty(String name, Calendar value) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.DATE);
	}

	@Override
	public Property setProperty(String name, Node value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value), PropertyType.REFERENCE);
	}

	@Override
	public Property setProperty(String name, Value value, int type) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		fSession.checkPrivileges(getPath(), Privilege.JCR_MODIFY_PROPERTIES);
		checkWritable();
		if (adaptTo(JcrNodeTypeManager.class).isProtectedProperty(name)) {
			throw new ConstraintViolationException("Unable to set a value for a protected property: " + name);
		}
		try {
			AdaptableMap<String, Object> updated = getWorkspaceQuery().items().setProperty(getIdentifier(), name, type,
					value);
			if (updated == null) {
				return null;
			}
			return JcrProperty.create(updated, this);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public Property setProperty(String name, Value[] values, int type) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		fSession.checkPrivileges(getPath(), Privilege.JCR_MODIFY_PROPERTIES);
		checkWritable();
		if (adaptTo(JcrNodeTypeManager.class).isProtectedProperty(name)) {
			throw new ConstraintViolationException("Unable to set a value for a protected property: " + name);
		}
		try {
			AdaptableMap<String, Object> updated = getWorkspaceQuery().items().setProperty(getIdentifier(), name, type,
					Arrays.stream(values).toArray(JcrValue[]::new));
			if (updated == null) {
				return null;
			}
			return JcrProperty.create(updated, this);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public Property setProperty(String name, String[] values, int type) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(values, type), type);
	}

	@Override
	public Property setProperty(String name, String value, int type) throws ValueFormatException, VersionException,
			LockException, ConstraintViolationException, RepositoryException {
		return setProperty(name, getValueFactory().createValue(value, type), type);
	}

	@Override
	public void unlock() throws UnsupportedRepositoryOperationException, LockException, AccessDeniedException,
			InvalidItemStateException, RepositoryException {
		fSession.getWorkspace().getLockManager().unlock(getPath());
	}

	@Override
	public void update(String srcWorkspace) throws NoSuchWorkspaceException, AccessDeniedException, LockException,
			InvalidItemStateException, RepositoryException {
		// TODO Auto-generated method stub

	}

	@Override
	public String[] getPropertyKeys() throws RepositoryException {
		return new TreeSet<>(getCachedProperties().keySet()).toArray(String[]::new);
	}

	public AdaptableMap<String, Object> getRawData() {
		return fItemData;
	}

	private void checkCanAddNode() throws LockException, RepositoryException {
		if (!fSession.isLive()) {
			throw new RepositoryException("The session has been closed.");
		}

		Lock lock = null;
		try {
			lock = adaptTo(JcrLockManager.class).getLock(getPath());
		} catch (LockException ex) {
		}
		if (lock != null && lock.isDeep()) {
			if (!Arrays.asList(adaptTo(JcrLockManager.class).getLockTokens()).contains(lock.getLockToken())) {
				throw new LockException("Node '" + getPath() + "' is locked.");
			}
		}
	}

	protected void checkWritable() throws LockException, RepositoryException {
		if (!fSession.isLive()) {
			throw new RepositoryException("The session has been closed.");
		}

		Lock lock = null;
		try {
			lock = adaptTo(JcrLockManager.class).getLock(getPath());
		} catch (LockException ex) {
		}
		if (lock != null) {
			if (!Arrays.asList(adaptTo(JcrLockManager.class).getLockTokens()).contains(lock.getLockToken())) {
				throw new LockException("Node '" + getPath() + "' is locked.");
			}
		}

		Node versionControlledNode = JCRs.getVersionControlledNode(this);
		if (versionControlledNode != null) {
			if (!versionControlledNode.isCheckedOut()) {
				throw new VersionException("Node '" + getPath() + "' is checked-in.");
			}

			if (versionControlledNode.hasProperty(JcrProperty.MI_CHECKED_OUT_BY)) {
				String checkedOutBy = versionControlledNode.getProperty(JcrProperty.MI_CHECKED_OUT_BY).getString();
				if (!getSession().getUserID().equals(checkedOutBy)) {
					throw new VersionException("Node '" + getPath() + "' is checked-out.");
				}
			}
		}
	}

	private void checkRemovable() throws LockException, RepositoryException {
		if (!fSession.isLive()) {
			throw new RepositoryException("The session has been closed.");
		}

		Lock lock = null;
		try {
			lock = adaptTo(JcrLockManager.class).getLock(getPath());
		} catch (LockException ex) {
		}
		if (lock != null) {
			if (!Arrays.asList(adaptTo(JcrLockManager.class).getLockTokens()).contains(lock.getLockToken())) {
				throw new LockException("Node '" + getPath() + "' is locked.");
			}
		}

		if (isNodeType(NodeType.MIX_REFERENCEABLE)) {
			try {
				if (getWorkspaceQuery().items().countReferenced(getIdentifier()) > 0) {
					throw new ConstraintViolationException("Node '" + getPath() + "' is referenced.");
				}
			} catch (IOException | SQLException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}
		}
	}

	private boolean isRootNode() {
		return (fItemData.getString("parent_item_id") == null);
	}

	private WorkspaceQuery getWorkspaceQuery() {
		return adaptTo(WorkspaceQuery.class);
	}

	private JcrValueFactory getValueFactory() {
		return adaptTo(JcrValueFactory.class);
	}

	private Map<String, AdaptableMap<String, Object>> getCachedProperties() throws RepositoryException {
		JcrCache jcrCache = adaptTo(JcrCache.class);
		Map<String, AdaptableMap<String, Object>> properties = jcrCache.getProperties(getIdentifier());
		if (properties == null) {
			properties = new HashMap<>();
			try (Query.Result result = getWorkspaceQuery().items().listProperties(getIdentifier(), null, 0)) {
				for (AdaptableMap<String, Object> itemData : result) {
					if (itemData.getBoolean("is_deleted")) {
						continue;
					}

					properties.put(itemData.getString("item_name"), itemData);
				}
			} catch (IOException | SQLException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}
			jcrCache.setProperties(getIdentifier(), properties);

			try {
				Node versionControlledNode = JCRs.getVersionControlledNode(this);
				if (versionControlledNode != null && versionControlledNode.isCheckedOut()
						&& versionControlledNode.hasProperty(org.mintjams.jcr.Property.MI_CHECKED_OUT_BY)) {
					String checkedOutBy = versionControlledNode.getProperty(org.mintjams.jcr.Property.MI_CHECKED_OUT_BY)
							.getString();
					if (!getSession().getUserID().equals(checkedOutBy)) {
						Map<String, AdaptableMap<String, Object>> replaced = new HashMap<>();
						for (Map.Entry<String, AdaptableMap<String, Object>> e : properties.entrySet()) {
							if (e.getKey().startsWith(NamespaceRegistry.PREFIX_JCR + ":")
									|| e.getKey().equals(org.mintjams.jcr.Property.MI_CHECKED_OUT_BY_NAME)) {
								replaced.put(e.getKey(), e.getValue());
							}
						}

						Node frozenNode;
						if (getIdentifier().equals(versionControlledNode.getIdentifier())) {
							frozenNode = getBaseVersion().getFrozenNode();
						} else {
							String relPath = getPath().substring(versionControlledNode.getPath().length());
							frozenNode = versionControlledNode.getBaseVersion().getFrozenNode()
									.getNode(relPath.substring(1));
						}
						try (Query.Result result = getWorkspaceQuery().items()
								.listProperties(frozenNode.getIdentifier(), null, 0)) {
							for (AdaptableMap<String, Object> itemData : result) {
								String k = itemData.getString("item_name");
								if (k.startsWith(NamespaceRegistry.PREFIX_JCR + ":")) {
									if (k.equals(org.mintjams.jcr.Property.JCR_FROZEN_PRIMARY_TYPE_NAME)) {
										k = org.mintjams.jcr.Property.JCR_PRIMARY_TYPE_NAME;
										itemData.put("item_name", k);
									} else if (k.equals(org.mintjams.jcr.Property.JCR_FROZEN_MIXIN_TYPES_NAME)) {
										k = org.mintjams.jcr.Property.JCR_MIXIN_TYPES_NAME;
										itemData.put("item_name", k);
									} else if (k.equals(org.mintjams.jcr.Property.JCR_MIMETYPE_NAME)
											|| k.equals(org.mintjams.jcr.Property.JCR_ENCODING_NAME)
											|| k.equals(org.mintjams.jcr.Property.JCR_LAST_MODIFIED_NAME)
											|| k.equals(org.mintjams.jcr.Property.JCR_LAST_MODIFIED_BY_NAME)
											|| k.equals(org.mintjams.jcr.Property.JCR_DATA_NAME)) {
										// do nothing
									} else {
										continue;
									}
								}
								replaced.put(k, itemData);
							}
						}

						properties = replaced;
						jcrCache.setProperties(getIdentifier(), properties);
					}
				}
			} catch (Throwable ex) {
				jcrCache.setProperties(getIdentifier(), null);
				throw Cause.create(ex).wrap(RepositoryException.class);
			}
		}
		return properties;
	}

//	private void collectReferencesForId(String id, String name, boolean weak, List<JcrProperty> items,
//			java.util.Set<String> seen) throws IOException, SQLException, RepositoryException {
//		if (id == null) {
//			return;
//		}
//
//		try (Query.Result result = getWorkspaceQuery().items().listReferences(id, name, weak)) {
//			for (AdaptableMap<String, Object> itemData : result) {
//				if (itemData.getBoolean("is_deleted")) {
//					continue;
//				}
//				String propId = itemData.getString("item_id");
//				if (seen.add(propId)) {
//					items.add(JcrProperty.create(itemData, (JcrNode) fSession.getNodeByIdentifier(itemData.getString("parent_item_id"))));
//				}
//			}
//		}
//	}

//	private PropertyIterator createReferenceIterator(String name, boolean weak) throws RepositoryException {
//		List<JcrProperty> items = new ArrayList<>();
//		try {
//			java.util.Set<String> seen = new java.util.LinkedHashSet<>();
//
//			// collect for live node
//			collectReferencesForId(getIdentifier(), name, weak, items, seen);
//
//			// versioning adjustment: consider frozen node when appropriate
//			Node versionControlledNode = JCRs.getVersionControlledNode(this);
//			if (versionControlledNode != null && versionControlledNode.isCheckedOut()
//					&& versionControlledNode.hasProperty(org.mintjams.jcr.Property.MI_CHECKED_OUT_BY)) {
//				String checkedOutBy = versionControlledNode.getProperty(org.mintjams.jcr.Property.MI_CHECKED_OUT_BY)
//						.getString();
//				if (!getSession().getUserID().equals(checkedOutBy)) {
//					Node frozenNode;
//					if (getIdentifier().equals(versionControlledNode.getIdentifier())) {
//						frozenNode = getBaseVersion().getFrozenNode();
//					} else {
//						String relPath = getPath().substring(versionControlledNode.getPath().length());
//						frozenNode = versionControlledNode.getBaseVersion().getFrozenNode()
//								.getNode(relPath.substring(1));
//					}
//					collectReferencesForId(frozenNode.getIdentifier(), name, weak, items, seen);
//				}
//			}
//		} catch (IOException | SQLException ex) {
//			throw Cause.create(ex).wrap(RepositoryException.class);
//		}
//
//		return new PropertyIterator() {
//			private final List<JcrProperty> fItems = items;
//			private int pos = 0;
//
//			@Override
//			public long getPosition() {
//				return pos;
//			}
//
//			@Override
//			public long getSize() {
//				return fItems.size();
//			}
//
//			@Override
//			public void skip(long skipNum) {
//				if (skipNum < 0 || Integer.MAX_VALUE < skipNum)
//					throw new NoSuchElementException("Invalid skip number: " + skipNum);
//				for (long i = 0; i < skipNum && hasNext(); i++)
//					nextProperty();
//			}
//
//			@Override
//			public boolean hasNext() {
//				return pos < fItems.size();
//			}
//
//			@Override
//			public Object next() {
//				return nextProperty();
//			}
//
//			@Override
//			public Property nextProperty() {
//				if (!hasNext())
//					throw new IllegalStateException("No more items.");
//				return fItems.get(pos++);
//			}
//		};
//	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(Version.class) || adapterType.equals(JcrVersion.class)) {
			try {
				if (isNodeType(NodeType.NT_VERSION)) {
					return (AdapterType) JcrVersion.create(this);
				}
			} catch (RepositoryException ex) {
				throw new IllegalStateException(ex);
			}
		}

		if (adapterType.equals(VersionHistory.class) || adapterType.equals(JcrVersionHistory.class)) {
			try {
				if (isNodeType(NodeType.NT_VERSION_HISTORY)) {
					return (AdapterType) JcrVersionHistory.create(this);
				}
			} catch (RepositoryException ex) {
				throw new IllegalStateException(ex);
			}
		}

		return Adaptables.getAdapter(fSession, adapterType);
	}

	@Override
	public int hashCode() {
		try {
			return (JcrNode.class.getSimpleName() + "|" + getIdentifier()).hashCode();
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof JcrNode)) {
			return false;
		}
		return (hashCode() == obj.hashCode());
	}

	@Override
	public String toString() {
		try {
			return getPath();
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	private class CachedPropertyIterator implements PropertyIterator {
		private final List<JcrProperty> fItems = new ArrayList<>();
		private int fPosition = 0;

		private CachedPropertyIterator(String[] nameGlobs) throws RepositoryException {
			for (Map.Entry<String, AdaptableMap<String, Object>> e : new TreeMap<String, AdaptableMap<String, Object>>(
					getCachedProperties()).entrySet()) {
				boolean matches = false;
				if (nameGlobs != null) {
					for (String glob : nameGlobs) {
						if (glob.startsWith("*") && glob.endsWith("*")) {
							if (e.getKey().contains(glob.substring(1, glob.length() - 1))) {
								matches = true;
								break;
							}
						} else if (glob.startsWith("*")) {
							if (e.getKey().endsWith(glob.substring(1))) {
								matches = true;
								break;
							}
						} else if (glob.endsWith("*")) {
							if (e.getKey().startsWith(glob.substring(0, glob.length() - 1))) {
								matches = true;
								break;
							}
						} else {
							if (e.getKey().equals(glob)) {
								matches = true;
								break;
							}
						}
					}
				} else {
					matches = true;
				}
				if (!matches) {
					continue;
				}

				fItems.add(JcrProperty.create(e.getValue(), JcrNode.this));
			}
		}

		@Override
		public long getPosition() {
			return fPosition;
		}

		@Override
		public long getSize() {
			return fItems.size();
		}

		@Override
		public void skip(long skipNum) {
			if (skipNum < 0 || Integer.MAX_VALUE < skipNum) {
				throw new NoSuchElementException("Invalid skip number: " + skipNum);
			}

			for (long i = 0; i < skipNum; i++) {
				if (!hasNext()) {
					break;
				}
				nextProperty();
			}
		}

		@Override
		public boolean hasNext() {
			return (fPosition < fItems.size());
		}

		@Override
		public Object next() {
			return nextProperty();
		}

		@Override
		public Property nextProperty() {
			if (!hasNext()) {
				throw new IllegalStateException("No more items.");
			}

			JcrProperty property = fItems.get(fPosition);
			fPosition++;
			return property;
		}
	}

}

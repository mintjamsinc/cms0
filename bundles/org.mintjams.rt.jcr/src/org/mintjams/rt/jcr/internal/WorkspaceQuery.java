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

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.Principal;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.jcr.Binary;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.security.AccessControlException;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import org.mintjams.jcr.JcrName;
import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.NamespaceProvider;
import org.mintjams.jcr.UncheckedRepositoryException;
import org.mintjams.jcr.observation.Event;
import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.UnknownGroupPrincipal;
import org.mintjams.jcr.security.UnknownUserPrincipal;
import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlEntry;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlList;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlManager;
import org.mintjams.rt.jcr.internal.version.JcrVersionManager;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.adapter.UnadaptableValueException;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.sql.Entity;
import org.mintjams.tools.sql.Query;
import org.mintjams.tools.sql.Update;

public class WorkspaceQuery implements Adaptable {

	private final JcrWorkspace fWorkspace;

	private WorkspaceQuery(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static WorkspaceQuery create(JcrWorkspace workspace) {
		return new WorkspaceQuery(workspace);
	}

	public void commit() throws SQLException {
		newUpdateBuilder("DELETE FROM jcr_items WHERE is_deleted = TRUE").build().execute();
		newUpdateBuilder("DELETE FROM jcr_properties WHERE is_deleted = TRUE").build().execute();

		getConnection().commit();
	}

	public void rollback() throws SQLException {
		getConnection().rollback();
	}

	private final JournalQuery _journalQuery = new JournalQuery();
	public JournalQuery journal() {
		return _journalQuery;
	}

	private final FilesQuery _filesQuery = new FilesQuery();
	public FilesQuery files() {
		return _filesQuery;
	}

	private final ItemsQuery _itemsQuery = new ItemsQuery();
	public ItemsQuery items() {
		return _itemsQuery;
	}

	private final NamespacesQuery _namespacesQuery = new NamespacesQuery();
	public NamespacesQuery namespaces() {
		return _namespacesQuery;
	}

	public SessionIdentifier getSessionIdentifier() {
		return adaptTo(SessionIdentifier.class);
	}

	private Connection getConnection() {
		return adaptTo(Connection.class);
	}

	public JcrWorkspace getWorkspace() {
		return fWorkspace;
	}

	public NamespaceProvider getNamespaceProvider() {
		return adaptTo(NamespaceProvider.class);
	}

	public JcrAccessControlManager getAccessControlManager() {
		return adaptTo(JcrAccessControlManager.class);
	}

	public ValueFactory getValueFactory() {
		return adaptTo(ValueFactory.class);
	}

	public JcrPath getResolved(JcrPath path) {
		return path.with(getNamespaceProvider());
	}

	public JcrName getResolved(JcrName name) {
		return name.with(getNamespaceProvider());
	}

	public String getResolved(String name) {
		return getResolved(JcrName.valueOf(name)).toString();
	}

	public JcrValue createValue(int type, Object value) {
		return JcrValue.create(value, type).with(fWorkspace);
	}

	public JcrValue[] createValues(int type, Object... values) throws RepositoryException {
		try {
			return Arrays.stream(values).map(e -> {
				if (e instanceof JcrValue) {
					try {
						return ((JcrValue) e).as(type);
					} catch (ValueFormatException ex) {
						throw new UncheckedRepositoryException(ex);
					}
				}
				return createValue(type, e).with(fWorkspace);
			}).toArray(JcrValue[]::new);
		} catch (UncheckedRepositoryException ex) {
			throw ex.getCause();
		}
	}

	public MimeTypeDetector getMimeTypeDetector() {
		return adaptTo(MimeTypeDetector.class);
	}

	public NodeTypeManager getNodeTypeManager() {
		return adaptTo(NodeTypeManager.class);
	}

	private Query.Builder newQueryBuilder(String statement) {
		return Query.newBuilder(getConnection()).setStatement(statement);
	}

	private Update.Builder newUpdateBuilder(String statement) {
		return Update.newBuilder(getConnection()).setStatement(statement);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

	interface QueryMonitor {
		boolean isCancelled();
	}

	public class JournalQuery {
		private JournalQuery() {}

		private Entity _journalEntity;
		private Entity journalEntity() throws SQLException {
			if (_journalEntity == null) {
				_journalEntity = Entity.newBuilder(getConnection()).setName("jcr_journal").build();
			}
			return _journalEntity;
		}

		public void writeJournal(Map<String, Object> data) throws SQLException {
			SessionIdentifier sessionIdentifier = getSessionIdentifier();
			String journalId = MessageFormat.format("{0,number,00000000000000000000}-{1,number,00000000000000000000}", sessionIdentifier.getCreated(), System.nanoTime());
			for (int i = 0;; i++) {
				try {
					journalEntity().create(AdaptableMap.<String, Object>newBuilder()
							.putAll(data)
							.put("session_id", sessionIdentifier.toString())
							.put("transaction_id", sessionIdentifier.getTransactionIdentifier())
							.put("journal_id", journalId)
							.build()).execute();
					return;
				} catch (SQLException ex) {
					if (i == 0) {
						continue;
					}

					throw ex;
				}
			}
		}

		public Query.Result listJournal(String id) throws SQLException {
			return newQueryBuilder("SELECT * FROM jcr_journal WHERE transaction_id = {{id}} ORDER BY event_occurred")
					.setVariable("id", id)
					.build().setOffset(0).execute();
		}
	}

	public class FilesQuery {
		private FilesQuery() {}

		private Entity _filesEntity;
		private Entity filesEntity() throws SQLException {
			if (_filesEntity == null) {
				_filesEntity = Entity.newBuilder(getConnection()).setName("jcr_files").build();
			}
			return _filesEntity;
		}

		public void createFile(String id, JcrBinary data) throws IOException, SQLException, RepositoryException {
			Path path = getPath(id);
			Files.createDirectories(path.getParent());
			try (InputStream in = data.getStream()) {
				try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
					IOs.copy(in, out);
				}
			}

			filesEntity().create(AdaptableMap.<String, Object>newBuilder()
					.put("file_id", id)
					.put("file_size", Files.size(path))
					.build()).execute();
		}

		public void deleteFile(String id) throws SQLException {
			filesEntity().updateByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
					.put("file_id", id)
					.put("is_deleted", Boolean.TRUE)
					.build()).execute();
		}

		public boolean exists(String id) throws IOException, SQLException {
			try (Query.Result result = filesEntity().findByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
					.put("file_id", id)
					.build()).setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public long getSize(String id) throws IOException, SQLException {
			try (Query.Result result = filesEntity().findByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
					.put("file_id", id)
					.build()).setOffset(0).setLimit(1).execute()) {
				return result.iterator().next().getLong("file_size");
			}
		}

		public Path getPath(String id) {
			return adaptTo(JcrWorkspaceProvider.class).getJcrBinPath()
					.resolve(id.substring(0, 2))
					.resolve(id.substring(2, 4))
					.resolve(id.substring(4, 6))
					.resolve(id.substring(6, 8))
					.resolve(id)
					.toAbsolutePath();
		}

		public InputStream getInputStream(String id) throws IOException {
			return Files.newInputStream(getPath(id));
		}

		public void clean(QueryMonitor monitor) throws IOException, SQLException {
			if (monitor.isCancelled()) {
				return;
			}

			try (Query.Result result = newQueryBuilder("SELECT file_id FROM jcr_files WHERE is_deleted = TRUE")
					.build().setOffset(0).execute()) {
				for (AdaptableMap<String, Object> r : result) {
					if (monitor.isCancelled()) {
						break;
					}

					Files.deleteIfExists(getPath(r.getString("file_id")));
					filesEntity().deleteByPrimaryKey(r).execute();
				}
			}
		}
	}

	public class ItemsQuery {
		private ItemsQuery() {}

		private Entity _itemsEntity;
		private Entity itemsEntity() throws SQLException {
			if (_itemsEntity == null) {
				_itemsEntity = Entity.newBuilder(getConnection()).setName("jcr_items").build();
			}
			return _itemsEntity;
		}

		private Entity _propertiesEntity;
		private Entity propertiesEntity() throws SQLException {
			if (_propertiesEntity == null) {
				_propertiesEntity = Entity.newBuilder(getConnection()).setName("jcr_properties").build();
			}
			return _propertiesEntity;
		}

		private Entity _acesEntity;
		private Entity acesEntity() throws SQLException {
			if (_acesEntity == null) {
				_acesEntity = Entity.newBuilder(getConnection()).setName("jcr_aces").build();
			}
			return _acesEntity;
		}

		private Entity _locksEntity;
		public Entity locksEntity() throws SQLException {
			if (_locksEntity == null) {
				_locksEntity = Entity.newBuilder(getConnection()).setName("jcr_locks").build();
			}
			return _locksEntity;
		}

		public JcrPath getVersionHistoryPath(String id) {
			return getResolved(JcrPath.valueOf("/")
					.resolve(JcrNode.JCR_SYSTEM_NAME)
					.resolve(JcrNode.JCR_VERSION_STORAGE_NAME)
					.resolve(id.substring(0, 2))
					.resolve(id.substring(2, 4))
					.resolve(id.substring(4, 6))
					.resolve(id.substring(6, 8))
					.resolve(id));
		}

		public AdaptableMap<String, Object> createRootNode() throws IOException, SQLException, RepositoryException {
			Map<String, Object> definition = new HashMap<>();
			definition.put("path", "/");
			definition.put("primaryType", "mi:root");
			definition.put("mixinTypes", NodeType.MIX_CREATED);
			definition.put("acl", AdaptableMap.<String, Object>newBuilder()
					.put("principal", EveryonePrincipal.NAME)
					.put("privileges", Arrays.asList("jcr:read"))
					.put("effect", "allow")
					.build());
			return createNode(definition);
		}

		public AdaptableMap<String, Object> createNode(String absPath, String primaryTypeName) throws IOException, SQLException, RepositoryException {
			Map<String, Object> definition = new HashMap<>();
			definition.put("path", absPath);
			definition.put("primaryType", primaryTypeName);
			definition.put("mixinTypes", null);
			return createNode(definition);
		}

		public AdaptableMap<String, Object> createNode(String absPath, String primaryTypeName, String[] mixinTypeNames) throws IOException, SQLException, RepositoryException {
			Map<String, Object> definition = new HashMap<>();
			definition.put("path", absPath);
			definition.put("primaryType", primaryTypeName);
			definition.put("mixinTypes", mixinTypeNames);
			return createNode(definition);
		}

		public AdaptableMap<String, Object> createNode(Map<String, Object> definition) throws IOException, SQLException, RepositoryException {
			ExpressionContext el = ExpressionContext.create().setVariable("definition", definition);
			JcrPath itemPath = getResolved(JcrPath.valueOf(el.getString("definition.path")));

			String itemName = null;
			if (!itemPath.isRoot()) {
				itemName = itemPath.getName().toString();
			}

			AdaptableMap<String,Object> parentItemData = null;
			if (!itemPath.isRoot()) {
				parentItemData = getNode(itemPath.getParent().toString());
			}

			try {
				getNode(itemPath.toString());
				throw new ItemExistsException("Node already exists: " + itemPath.toString());
			} catch (PathNotFoundException ignore) {}

			String primaryTypeName = el.getString("definition.primaryType");
			if (Strings.isEmpty(primaryTypeName)) {
				primaryTypeName = NodeType.NT_UNSTRUCTURED;
			}
			String primaryType = getResolved(primaryTypeName);
			if (!getNodeTypeManager().hasNodeType(primaryType)) {
				throw new NoSuchNodeTypeException(primaryTypeName);
			}
			if (getNodeTypeManager().getNodeType(primaryType).isAbstract()) {
				throw new ConstraintViolationException("The primary type of a node cannot be abstract: " + primaryTypeName);
			}

			List<String> mixinTypes = new ArrayList<>();
			for (String mixinTypeName : el.getStringArray("definition.mixinTypes")) {
				String mixinType = getResolved(mixinTypeName);
				if (!getNodeTypeManager().hasNodeType(mixinType)) {
					throw new NoSuchNodeTypeException(mixinTypeName);
				}
				if (getNodeTypeManager().getNodeType(mixinType).isAbstract()) {
					throw new ConstraintViolationException("The mixin type of a node cannot be abstract: " + mixinTypeName);
				}
				mixinTypes.add(mixinType);
			}

			String id = el.getString("definition.identifier");
			if (Strings.isEmpty(id)) {
				id = UUID.randomUUID().toString();
			}

			newUpdateBuilder("DELETE FROM jcr_items WHERE item_id = {{id}}").setVariable("id", id).build().execute();
			newUpdateBuilder("DELETE FROM jcr_properties WHERE parent_item_id = {{id}}").setVariable("id", id).build().execute();

			String path = "/" + getResolved(Strings.defaultString(itemName));
			if (parentItemData != null) {
				if (!JcrPath.valueOf(parentItemData.getString("item_path")).isRoot()) {
					path = parentItemData.getString("item_path") + path;
				}
			}

			itemsEntity().create(AdaptableMap.<String, Object>newBuilder()
					.put("item_id", id)
					.put("item_name", JcrPath.valueOf(path).getName().toString())
					.put("item_path", path)
					.put("parent_item_id", (parentItemData == null) ? null : parentItemData.getString("item_id"))
					.build()).execute();
			setProperty(id, JcrProperty.JCR_PRIMARY_TYPE, PropertyType.NAME, createValue(PropertyType.NAME, primaryType));
			if (!mixinTypes.isEmpty()) {
				setProperty(id, JcrProperty.JCR_MIXIN_TYPES, PropertyType.NAME, createValues(PropertyType.NAME, mixinTypes.toArray()));
			}

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.NODE_ADDED)
					.put("item_id", id)
					.put("item_path", path)
					.put("primary_type", primaryType)
					.put("user_id", fWorkspace.getSession().getUserID())
					.build());

			AdaptableMap<String, Object> itemData = getNodeByIdentifier(id);
			if (!el.getBoolean("definition.disablePostProcess")) {
				postCreateNode(itemData, definition);
			}
			return itemData;
		}

		private void postCreateNode(AdaptableMap<String, Object> itemData, Map<String, Object> definition) throws IOException, SQLException, RepositoryException {
			JcrNode node = JcrNode.create(itemData, adaptTo(JcrSession.class));

			Map<String, NodeType> nodeTypes = new HashMap<>();
			NodeType primaryType = node.getPrimaryNodeType();
			nodeTypes.put(primaryType.getName(), primaryType);
			for (NodeType e : primaryType.getSupertypes()) {
				if (!nodeTypes.containsKey(e.getName())) {
					nodeTypes.put(e.getName(), e);
				}
			}
			for (NodeType mixinType : node.getMixinNodeTypes()) {
				if (nodeTypes.containsKey(mixinType.getName())) {
					continue;
				}

				nodeTypes.put(mixinType.getName(), mixinType);
				for (NodeType e : mixinType.getSupertypes()) {
					if (!nodeTypes.containsKey(e.getName())) {
						nodeTypes.put(e.getName(), e);
					}
				}
			}

			Map<String, PropertyDefinition> propertyDefinitions = new HashMap<>();
			for (NodeType type : nodeTypes.values()) {
				for (PropertyDefinition e : type.getPropertyDefinitions()) {
					if (e.isAutoCreated()) {
						if (e.getName().equals(JcrProperty.JCR_PRIMARY_TYPE_NAME)
								|| e.getName().equals(JcrProperty.JCR_MIXIN_TYPES_NAME)
								|| e.getName().equals(JcrProperty.JCR_FROZEN_PRIMARY_TYPE_NAME)) {
							continue;
						}
						if (!propertyDefinitions.containsKey(e.getName())) {
							propertyDefinitions.put(e.getName(), e);
						}
					}
				}
			}
			for (PropertyDefinition e : propertyDefinitions.values()) {
				JcrValue[] defaultValues = createDefaultValues(e, node);
				if (defaultValues != null) {
					if (e.isMultiple()) {
						setProperty(node.getIdentifier(), e.getName(), e.getRequiredType(), defaultValues);
					} else {
						setProperty(node.getIdentifier(), e.getName(), e.getRequiredType(), defaultValues[0]);
					}
				}
			}

			Map<String, NodeDefinition> nodeDefinitions = new HashMap<>();
			for (NodeType type : nodeTypes.values()) {
				for (NodeDefinition e : type.getChildNodeDefinitions()) {
					if (e.getName().equals(getResolved(JcrNode.JCR_ROOT_VERSION))) {
						continue;
					}
					if (e.isAutoCreated() && !nodeDefinitions.containsKey(e.getName())) {
						nodeDefinitions.put(e.getName(), e);
					}
				}
			}
			for (NodeDefinition e : nodeDefinitions.values()) {
				createNode(JcrPath.valueOf(node.getPath()).resolve(e.getName()).toString(), e.getDefaultPrimaryTypeName());
			}

			Collection<Map<String, Object>> l = (Collection<Map<String, Object>>) definition.get("acl");
			if (l != null) {
				for (Map<String, Object> ace : l) {
					ExpressionContext el = ExpressionContext.create().setVariable("ace", ace);
					JcrAccessControlList acl = (JcrAccessControlList) getAccessControlManager().getPolicies(node.getPath())[0];
					String group = el.getString("ace.group");
					String user = el.getString("ace.user");
					Principal principal;
					if (Strings.isNotEmpty(group) && Strings.isNotEmpty(user)) {
						throw new RepositoryException("You cannot specify both user and group.");
					} else if (Strings.isNotEmpty(group)) {
						try {
							principal = Activator.getDefault().getGroupPrincipal(group);
						} catch (PrincipalNotFoundException ignore) {
							principal = new UnknownGroupPrincipal(group);
						}
					} else if (Strings.isNotEmpty(user)) {
						try {
							principal = Activator.getDefault().getUserPrincipal(user);
						} catch (PrincipalNotFoundException ignore) {
							principal = new UnknownUserPrincipal(user);
						}
					} else {
						throw new RepositoryException("The grantee must be specified as either a user or a group.");
					}
					String[] privileges = el.getStringArray("ace.privileges");
					boolean effect;
					if (el.getString("ace.effect").equalsIgnoreCase("allow")) {
						effect = true;
					} else if (el.getString("ace.effect").equalsIgnoreCase("deny")) {
						effect = false;
					} else {
						throw new RepositoryException("The effect must be specified as either allow or deny.");
					}
					acl.addAccessControlEntry(principal, effect, privileges);
					getAccessControlManager().setPolicy(node.getPath(), acl);
				}
			}
		}

		private JcrValue[] createDefaultValues(PropertyDefinition propertyDefinition, Node node) throws IOException, RepositoryException {
			Value[] defaults = propertyDefinition.getDefaultValues();
			if (defaults != null) {
				return Stream.of(defaults).toArray(JcrValue[]::new);
			}

			if (propertyDefinition.getRequiredType() == PropertyType.DATE) {
				return createValues(PropertyType.DATE, Calendar.getInstance());
			}

			if (propertyDefinition.getName().equals(JcrProperty.JCR_CREATED_BY_NAME)
					|| propertyDefinition.getName().equals(JcrProperty.JCR_LAST_MODIFIED_BY_NAME)) {
				return createValues(PropertyType.STRING, fWorkspace.getSession().getUserID());
			}

			if (propertyDefinition.getName().equals(JcrProperty.JCR_MIMETYPE_NAME)) {
				Node fileNode = node;
				if (fileNode.getName().equals(JcrNode.JCR_CONTENT_NAME)) {
					fileNode = fileNode.getParent();
				}
				return createValues(PropertyType.STRING, getMimeTypeDetector().probeContentType(Paths.get(fileNode.getName())));
			}

			return null;
		}

		public AdaptableMap<String, Object> addMixin(String id, String mixinName) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}
			if (Strings.isEmpty(mixinName)) {
				throw new NoSuchNodeTypeException("Mixin type must not be null or empty.");
			}

			String mixinType = getResolved(mixinName);
			if (!getNodeTypeManager().hasNodeType(mixinType)) {
				throw new NoSuchNodeTypeException("Invalid mixin name: " + mixinName);
			}
			if (getNodeTypeManager().getNodeType(mixinType).isAbstract()) {
				throw new ConstraintViolationException("The mixin type of a node cannot be abstract: " + mixinName);
			}

			AdaptableMap<String,Object> itemData = getNodeByIdentifier(id);

			List<String> mixinTypeNames = getMixinTypes(id);
			if (mixinTypeNames.contains(mixinType)) {
				throw new ConstraintViolationException("The specified node type '" + mixinName + "' is in node '" + itemData.getString("item_path") + "'.");
			}
			mixinTypeNames.add(mixinType);

			if (!(mixinType.equals(getResolved(NodeType.MIX_SIMPLE_VERSIONABLE))
					|| mixinType.equals(getResolved(NodeType.MIX_VERSIONABLE)))) {
				setProperty(id, JcrProperty.JCR_MIXIN_TYPES, PropertyType.NAME, createValues(PropertyType.NAME, mixinTypeNames.toArray()));
			}

			postAddMixin(itemData, mixinType);
			return itemData;
		}

		private void postAddMixin(AdaptableMap<String, Object> itemData, String mixinName) throws IOException, SQLException, RepositoryException {
			if (mixinName.equals(getResolved(NodeType.MIX_VERSIONABLE))) {
				adaptTo(JcrVersionManager.class).addVersionControl(itemData.getString("item_id"));
			}
		}

		public AdaptableMap<String, Object> removeMixin(String id, String mixinName) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}
			if (Strings.isEmpty(mixinName)) {
				throw new NoSuchNodeTypeException("Mixin type must not be null or empty.");
			}

			String mixinType = getResolved(mixinName);
			if (!getNodeTypeManager().hasNodeType(mixinType)) {
				throw new NoSuchNodeTypeException("Invalid mixin name: " + mixinName);
			}

			AdaptableMap<String,Object> itemData = getNodeByIdentifier(id);

			List<String> mixinTypeNames = getMixinTypes(id);
			if (!mixinTypeNames.contains(mixinType)) {
				throw new ConstraintViolationException("The specified node type '" + mixinName + "' is not in node '" + itemData.getString("item_path") + "'.");
			}
			if (mixinType.equals(getResolved(NodeType.MIX_LOCKABLE))
					|| mixinType.equals(getResolved(NodeType.MIX_SIMPLE_VERSIONABLE))
					|| mixinType.equals(getResolved(NodeType.MIX_VERSIONABLE))) {
				throw new ConstraintViolationException("The specified node type '" + mixinName + "' cannot be removed.");
			}
			mixinTypeNames.remove(mixinType);

			setProperty(id, JcrProperty.JCR_MIXIN_TYPES, PropertyType.NAME, createValues(PropertyType.NAME, mixinTypeNames.toArray()));

			return itemData;
		}

		public AdaptableMap<String, Object> getNode(String absPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(absPath) || !absPath.startsWith("/")) {
				throw new PathNotFoundException("Invalid path: " + absPath);
			}

			String path = getResolved(JcrPath.valueOf(absPath)).toString();
			try (Query.Result result = itemsEntity().find(AdaptableMap.<String, Object>newBuilder()
					.put("item_path", path)
					.put("is_deleted", Boolean.FALSE)
					.build()).setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					throw new PathNotFoundException(absPath);
				}

				return i.next();
			}
		}

		public AdaptableMap<String, Object> getNodeByIdentifier(String id) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			try (Query.Result result = itemsEntity().find(AdaptableMap.<String, Object>newBuilder()
					.put("item_id", id)
					.put("is_deleted", Boolean.FALSE)
					.build()).setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					throw new ItemNotFoundException(id);
				}

				return i.next();
			}
		}

		public Query.Result collectNodes(String... absPaths) throws SQLException {
			if (absPaths == null || absPaths.length == 0) {
				throw new IllegalArgumentException("Path must not be null or empty.");
			}

			List<String> paths = new ArrayList<>();
			for (String absPath : absPaths) {
				JcrPath path = JcrPath.valueOf(absPath);
				paths.add(path.toString());
				if (!path.getName().toString().equals(JcrNode.JCR_CONTENT_NAME)) {
					paths.add(path.resolve(JcrNode.JCR_CONTENT_NAME).toString());
				}
			}

			StringBuilder statement = new StringBuilder().append("SELECT * FROM jcr_items");
			statement.append(" WHERE item_path IN ({{paths;list}})");
			statement.append(" ORDER BY item_path");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("paths", paths)
					.build();

			return newQueryBuilder(statement.toString())
					.setVariables(variables)
					.build().setOffset(0).execute();
		}

		public Query.Result collectNodesByIdentifier(String... ids) throws SQLException {
			if (ids == null || ids.length == 0) {
				throw new IllegalArgumentException("Identifier must not be null or empty.");
			}

			StringBuilder statement = new StringBuilder().append("SELECT * FROM jcr_items");
			statement.append(" WHERE item_id IN ({{ids;list}})");
			statement.append(" OR (parent_item_id IN ({{ids;list}}) AND item_name = {{name}})");
			statement.append(" ORDER BY item_path");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("ids", ids)
					.put("name", JcrNode.JCR_CONTENT_NAME)
					.build();

			return newQueryBuilder(statement.toString())
					.setVariables(variables)
					.build().setOffset(0).execute();
		}

		public String getPath(String id) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			return getNodeByIdentifier(id).getString("item_path");
		}

		public Query.Result listNodes(String id, String[] nameGlobs, int offset) throws SQLException {
			if (Strings.isEmpty(id)) {
				throw new IllegalArgumentException("Identifier must not be null or empty.");
			}

			List<String> globs = new ArrayList<>();
			if (nameGlobs != null) {
				for (String glob : nameGlobs) {
					if (Strings.isBlank(glob)) {
						throw new IllegalArgumentException("Invalid name globs: " + String.join(", ", nameGlobs));
					}

					if (glob.equals("*")) {
						globs.clear();
						continue;
					}

					globs.add(glob);
				}
			}

			StringBuilder statement = new StringBuilder().append("SELECT * FROM jcr_items WHERE parent_item_id = {{id}}");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("id", id)
					.build();
			for (int i = 0; i < globs.size(); i++) {
				String glob = globs.get(i);
				String varName = "glob" + i;

				if (glob.startsWith("*") || glob.endsWith("*")) {
					if (glob.startsWith("*")) {
						glob = "%" + glob.substring(1);
					}
					if (glob.endsWith("*")) {
						glob = glob.substring(0, glob.length() - 1) + "%";
					}
					statement.append(" AND item_name LIKE {{" + varName + "}}");
					variables.put(varName, glob);
					continue;
				}

				statement.append(" AND item_name = {{" + varName + "}}");
				variables.put(varName, glob);
			}
			statement.append(" ORDER BY item_name");

			return newQueryBuilder(statement.toString())
					.setVariables(variables)
					.build().setOffset(offset).execute();
		}

		public Query.Result listContentNodes(String... ids) throws SQLException {
			if (ids == null || ids.length == 0) {
				throw new IllegalArgumentException("Identifier must not be null or empty.");
			}

			StringBuilder statement = new StringBuilder().append("SELECT * FROM jcr_items");
			statement.append(" WHERE parent_item_id IN ({{ids;list}})");
			statement.append(" AND item_name = {{name}}");
			statement.append(" ORDER BY item_path");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("ids", ids)
					.put("name", JcrNode.JCR_CONTENT_NAME)
					.build();

			return newQueryBuilder(statement.toString())
					.setVariables(variables)
					.build().setOffset(0).execute();
		}

		public Query.Result listProperties(String id, String[] nameGlobs, int offset) throws SQLException {
			if (Strings.isEmpty(id)) {
				throw new IllegalArgumentException("Identifier must not be null or empty.");
			}

			List<String> globs = new ArrayList<>();
			if (nameGlobs != null) {
				for (String glob : nameGlobs) {
					if (Strings.isBlank(glob)) {
						throw new IllegalArgumentException("Invalid name globs: " + String.join(", ", nameGlobs));
					}

					if (glob.equals("*")) {
						globs.clear();
						continue;
					}

					globs.add(glob);
				}
			}

			StringBuilder statement = new StringBuilder().append("SELECT * FROM jcr_properties WHERE parent_item_id = {{id}}");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("id", id)
					.build();
			for (int i = 0; i < globs.size(); i++) {
				String glob = globs.get(i);
				String varName = "glob" + i;

				if (glob.startsWith("*") || glob.endsWith("*")) {
					if (glob.startsWith("*")) {
						glob = "%" + glob.substring(1);
					}
					if (glob.endsWith("*")) {
						glob = glob.substring(0, glob.length() - 1) + "%";
					}
					statement.append(" AND item_name LIKE {{" + varName + "}}");
					variables.put(varName, glob);
					continue;
				}

				statement.append(" AND item_name = {{" + varName + "}}");
				variables.put(varName, glob);
			}
			statement.append(" ORDER BY item_name");

			return newQueryBuilder(statement.toString())
					.setVariables(variables)
					.build().setOffset(offset).execute();
		}

		public Query.Result listAccessControlEntries(String absPath) throws SQLException {
			if (Strings.isEmpty(absPath)) {
				throw new IllegalArgumentException("Path must not be null or empty.");
			}

			StringBuilder statement = new StringBuilder().append("SELECT a.* FROM jcr_aces a");
			statement.append(" JOIN jcr_items i ON (a.item_id = i.item_id)");
			statement.append(" WHERE i.item_path = {{path}}");
			statement.append(" ORDER BY a.row_no");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("path", getResolved(JcrPath.valueOf(absPath)).toString())
					.build();

			return newQueryBuilder(statement.toString())
					.setVariables(variables)
					.build().setOffset(0).execute();
		}

		public Query.Result collectAccessControlEntries(String absPath) throws SQLException {
			if (Strings.isEmpty(absPath)) {
				throw new IllegalArgumentException("Path must not be null or empty.");
			}

			List<String> paths = new ArrayList<>();
			for (JcrPath path = getResolved(JcrPath.valueOf(absPath)); path != null; path = path.getParent()) {
				paths.add(path.toString());
			}

			StringBuilder statement = new StringBuilder().append("SELECT i.item_path, a.* FROM jcr_aces a");
			statement.append(" JOIN jcr_items i ON (a.item_id = i.item_id)");
			statement.append(" WHERE i.item_path IN ({{paths;list}})");
			statement.append(" ORDER BY i.item_path DESC, a.row_no");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("paths", paths)
					.build();

			return newQueryBuilder(statement.toString())
					.setVariables(variables)
					.build().setOffset(0).execute();
		}

		public Query.Result listLockTokens() throws SQLException {
			StringBuilder statement = new StringBuilder().append("SELECT * FROM jcr_locks WHERE principal_name = {{principal_name}}");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("principal_name", fWorkspace.getSession().getUserID())
					.build();

			return newQueryBuilder(statement.toString())
					.setVariables(variables)
					.build().setOffset(0).execute();
		}

		public long countNodes(String id, String[] nameGlobs) throws IOException, SQLException {
			if (Strings.isEmpty(id)) {
				throw new IllegalArgumentException("Identifier must not be null or empty.");
			}

			List<String> globs = new ArrayList<>();
			if (nameGlobs != null) {
				for (String glob : nameGlobs) {
					if (Strings.isBlank(glob)) {
						throw new IllegalArgumentException("Invalid name globs: " + String.join(", ", nameGlobs));
					}

					if (glob.equals("*")) {
						globs.clear();
						continue;
					}

					globs.add(glob);
				}
			}

			StringBuilder statement = new StringBuilder().append("SELECT COUNT(*) AS item_count FROM jcr_items WHERE parent_item_id = {{id}}");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("id", id)
					.build();
			for (int i = 0; i < globs.size(); i++) {
				String glob = globs.get(i);
				String varName = "glob" + i;

				if (glob.endsWith("*")) {
					statement.append(" AND item_name LIKE {{" + varName + "}}");
					variables.put(varName, glob.substring(0, glob.length() - 1) + "%");
					continue;
				}

				statement.append(" AND item_name = {{" + varName + "}}");
				variables.put(varName, glob);
			}
			statement.append(" AND is_deleted = FALSE");

			try (Query.Result result = newQueryBuilder(statement.toString())
					.setVariables(variables)
					.build().setOffset(0).execute()) {
				return result.iterator().next().getLong("item_count");
			}
		}

		public long countReferenced(String id) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT COUNT(*) AS reference_count FROM jcr_properties WHERE property_type = {{type}} AND ARRAY_CONTAINS(property_value, {{value}}) AND is_deleted = FALSE")
					.setVariable("type", PropertyType.REFERENCE)
					.setVariable("value", new QName(JcrValue.STRING_NS_URI, id, XMLConstants.DEFAULT_NS_PREFIX))
					.build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().next().getLong("reference_count");
			}
		}

		public boolean hasPendingChanges() throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier())
					.build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public boolean nodeIsNew(String id) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}} AND item_id = {{itemId}} AND event_type = {{eventType}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier())
					.setVariable("itemId", id)
					.setVariable("eventType", Event.NODE_ADDED)
					.build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public boolean nodeIsModified(String id) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}} AND item_id = {{itemId}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier())
					.setVariable("itemId", id)
					.build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public boolean propertyIsNew(String id, String relName) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}} AND item_id = {{itemId}} AND property_name = {{propertyName}} AND event_type = {{eventType}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier())
					.setVariable("itemId", id)
					.setVariable("propertyName", relName)
					.setVariable("eventType", Event.PROPERTY_ADDED)
					.build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public boolean propertyIsModified(String id, String relName) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}} AND item_id = {{itemId}} AND property_name = {{propertyName}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier())
					.setVariable("itemId", id)
					.setVariable("propertyName", relName)
					.build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public void removeNode(String id, Map<String, Object> options) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			ExpressionContext el = ExpressionContext.create().setVariable("options", options);

			AdaptableMap<String, Object> pk = AdaptableMap.<String, Object>newBuilder()
					.put("item_id", id)
					.build();

			AdaptableMap<String,Object> existing = null;
			try (Query.Result result = itemsEntity().findByPrimaryKey(pk).setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (i.hasNext()) {
					existing = i.next();
				}
			}

			if (existing == null) {
				return;
			}

			String path = existing.getString("item_path");
			String primaryType = getPrimaryType(id);

			if (!el.getBoolean("options.leaveLock")) {
				removeLock(id);
			}

			if (!el.getBoolean("options.leaveAccessControlPolicy")) {
				removeAccessControlPolicy(id);
			}

			try (Query.Result result = propertiesEntity().find(AdaptableMap.<String, Object>newBuilder()
					.put("parent_item_id", id)
					.build()).execute()) {
				for (Iterator<AdaptableMap<String, Object>> i = result.iterator(); i.hasNext();) {
					AdaptableMap<String, Object> r = i.next();

					for (QName propertyValue : Arrays.stream(r.getObjectArray("property_value"))
							.map(e -> QName.valueOf((String) e)).toArray(QName[]::new)) {
						if (JcrValue.BINARY_NS_URI.equals(propertyValue.getNamespaceURI())) {
							files().deleteFile(propertyValue.getLocalPart());
						}

						propertiesEntity().deleteByPrimaryKey(r).execute();
					}
				}
			}

			itemsEntity().deleteByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
					.putAll(pk)
					.put("is_deleted", Boolean.TRUE)
					.build()).execute();

			adaptTo(JcrCache.class).remove(id);

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.NODE_REMOVED)
					.put("item_id", id)
					.put("item_path", path)
					.put("primary_type", primaryType)
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", null)
					.build());
		}

		public void moveNode(String srcAbsPath, String destAbsPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(srcAbsPath)) {
				throw new PathNotFoundException("Source path must not be null or empty.");
			}
			if (Strings.isEmpty(destAbsPath)) {
				throw new PathNotFoundException("Destination path must not be null or empty.");
			}

			try {
				getNode(destAbsPath);
				throw new ItemExistsException("An item '" + destAbsPath + "' already exists.");
			} catch (PathNotFoundException ignore) {}

			JcrPath srcPath = getResolved(JcrPath.valueOf(srcAbsPath));
			JcrPath destPath = getResolved(JcrPath.valueOf(destAbsPath));
			AdaptableMap<String, Object> srcItem = getNode(srcPath.toString());
			AdaptableMap<String, Object> destParentItem = getNode(destPath.getParent().toString());

			if (!srcPath.getParent().toString().equals(destPath.getParent().toString())) {
				itemsEntity().updateByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
						.put("item_id", srcItem.getString("item_id"))
						.put("item_name", destPath.getName().toString())
						.put("item_path", destPath.toString())
						.put("parent_item_id", destParentItem.getString("item_id"))
						.build()).execute();
			}

			if (!srcPath.getName().toString().equals(destPath.getName().toString())) {
				itemsEntity().updateByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
						.put("item_id", srcItem.getString("item_id"))
						.put("item_name", destPath.getName().toString())
						.put("item_path", destPath.toString())
						.build()).execute();
			}

			adaptTo(JcrCache.class).remove(srcItem.getString("item_id"));
			moveChildNodes(srcItem.getString("item_id"));

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.NODE_MOVED)
					.put("item_id", srcItem.getString("item_id"))
					.put("item_path", destPath.toString())
					.put("primary_type", getPrimaryType(srcItem.getString("item_id")))
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", null)
					.put("source_path", srcPath.toString())
					.put("destination_path", destPath.toString())
					.build());
		}

		private void moveChildNodes(String parentId) throws IOException, SQLException, RepositoryException {
			AdaptableMap<String, Object> parentItemData = getNodeByIdentifier(parentId);
			try (Query.Result result = listNodes(parentId, null, 0)) {
				for (AdaptableMap<String, Object> itemData : result) {
					String name = JcrPath.valueOf(itemData.getString("item_path")).getName().toString();
					String path = JcrPath.valueOf(parentItemData.getString("item_path")).resolve(name).toString();
					itemsEntity().updateByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
							.put("item_id", itemData.getString("item_id"))
							.put("item_name", name)
							.put("item_path", path)
							.build()).execute();

					adaptTo(JcrCache.class).remove(itemData.getString("item_id"));
					moveChildNodes(itemData.getString("item_id"));
				}
			}
		}

		public AdaptableMap<String, Object> getProperty(String id, String relPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}
			if (Strings.isEmpty(relPath)) {
				throw new PathNotFoundException("Invalid relative path: " + relPath);
			}
			relPath = getResolved(relPath);
			if (relPath.indexOf("/") != -1) {
				throw new PathNotFoundException("Invalid relative path: " + relPath);
			}

			JcrName itemName = getResolved(JcrName.valueOf(relPath));

			try (Query.Result result = propertiesEntity().find(AdaptableMap.<String, Object>newBuilder()
					.put("parent_item_id", id)
					.put("item_name", itemName.toString())
					.put("is_deleted", Boolean.FALSE)
					.build()).setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					throw new PathNotFoundException(id + "/" + relPath);
				}

				return i.next();
			}
		}

		public String getPropertyIdentifier(String id, String relPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}
			if (Strings.isEmpty(relPath) || relPath.indexOf("/") != -1) {
				throw new PathNotFoundException("Invalid relative path: " + relPath);
			}

			JcrName itemName = getResolved(JcrName.valueOf(relPath));

			try (Query.Result result = propertiesEntity().find(AdaptableMap.<String, Object>newBuilder()
					.put("parent_item_id", id)
					.put("item_name", itemName.toString())
					.put("is_deleted", Boolean.FALSE)
					.build()).setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					return null;
				}

				return i.next().getString("item_id");
			}
		}

		public AdaptableMap<String, Object> setProperty(String id, String name, int type, Value value) throws IOException, SQLException, RepositoryException {
			return setProperty(id, name, type, false, value);
		}

		public AdaptableMap<String, Object> setProperty(String id, String name, int type, Value[] values) throws IOException, SQLException, RepositoryException {
			return setProperty(id, name, type, true, values);
		}

		public AdaptableMap<String, Object> setProperty(String id, String relPath, int type, boolean multiple, Value... values) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}
			if (Strings.isEmpty(relPath)) {
				throw new PathNotFoundException("Property name must not be null or empty.");
			}

			if (values == null) {
				return removeProperty(id, relPath);
			}

			try (PropertyParameters params = new PropertyParameters(id, relPath, type, multiple, values)) {
				AdaptableMap<String, Object> r = AdaptableMap.<String, Object>newBuilder()
						.put("item_id", params.getItemId())
						.put("item_name", params.getItemName())
						.put("parent_item_id", params.getParentItemId())
						.put("property_type", params.getPropertyType())
						.put("property_value", params.getPropertyValues())
						.put("is_multiple", params.isMultiple())
						.build();

				AdaptableMap<String,Object> existing = null;
				try (Query.Result result = propertiesEntity().find(AdaptableMap.<String, Object>newBuilder()
						.put("item_id", params.getItemId())
						.put("is_deleted", false)
						.build()).setOffset(0).setLimit(1).execute()) {
					Iterator<AdaptableMap<String, Object>> i = result.iterator();
					if (i.hasNext()) {
						existing = i.next();
					}
				}

				if (existing == null) {
					propertiesEntity().deleteByPrimaryKey(r).execute();
					propertiesEntity().create(r).execute();

					for (Map.Entry<String, JcrBinary> e : params.getBinaries()) {
						files().createFile(e.getKey(), e.getValue());
					}

					adaptTo(JcrCache.class).remove(id);

					journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
							.put("event_occurred", System.nanoTime())
							.put("event_type", Event.PROPERTY_ADDED)
							.put("item_id", id)
							.put("item_path", getPath(id))
							.put("primary_type", getPrimaryType(id))
							.put("property_name", params.getItemName())
							.put("user_id", fWorkspace.getSession().getUserID())
							.put("user_data", null)
							.put("event_info", null)
							.build());
				} else {
					for (QName propertyValue : Arrays.stream(existing.getObjectArray("property_value"))
							.map(e -> QName.valueOf((String) e)).toArray(QName[]::new)) {
						if (JcrValue.BINARY_NS_URI.equals(propertyValue.getNamespaceURI())) {
							files().deleteFile(propertyValue.getLocalPart());
						}
					}

					propertiesEntity().updateByPrimaryKey(r).execute();

					for (Map.Entry<String, JcrBinary> e : params.getBinaries()) {
						files().createFile(e.getKey(), e.getValue());
					}

					adaptTo(JcrCache.class).remove(id);

					journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
							.put("event_occurred", System.nanoTime())
							.put("event_type", Event.PROPERTY_CHANGED)
							.put("item_id", id)
							.put("item_path", getPath(id))
							.put("primary_type", getPrimaryType(id))
							.put("property_name", params.getItemName())
							.put("user_id", fWorkspace.getSession().getUserID())
							.put("user_data", null)
							.put("event_info", null)
							.build());
				}
			}

			return getProperty(id, relPath);
		}

		public JcrValue[] getPropertyValues(String id, String relPath) throws IOException, SQLException, RepositoryException {
			AdaptableMap<String, Object> itemData = getProperty(id, relPath);
			int type = itemData.getInteger("property_type");
			return Arrays.stream(itemData.getObjectArray("property_value"))
					.map(e -> createValue(type, QName.valueOf((String) e))).toArray(JcrValue[]::new);
		}

		public String getPrimaryType(String id) throws IOException, SQLException, RepositoryException {
			return getPropertyValues(id, JcrProperty.JCR_PRIMARY_TYPE)[0].getString();
		}

		public List<String> getMixinTypes(String id) throws IOException, SQLException, RepositoryException {
			List<String> l = new ArrayList<>();
			try {
				Arrays.stream(getPropertyValues(id, JcrProperty.JCR_MIXIN_TYPES)).map(e -> {
					try {
						return e.getString();
					} catch (RepositoryException ex) {
						throw new UncheckedRepositoryException(ex);
					}
				}).forEachOrdered(l::add);
			} catch (PathNotFoundException ignore) {
			} catch (UncheckedRepositoryException ex) {
				throw ex.getCause();
			}
			return l;
		}

		public AdaptableMap<String, Object> removeProperty(String id, String relPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}
			if (Strings.isEmpty(relPath)) {
				throw new PathNotFoundException("Property name must not be null or empty.");
			}

			AdaptableMap<String, Object> pk = AdaptableMap.<String, Object>newBuilder()
					.put("item_id", new PropertyIdentifier(id, relPath).toString())
					.build();

			AdaptableMap<String,Object> existing = null;
			try (Query.Result result = propertiesEntity().findByPrimaryKey(pk).setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (i.hasNext()) {
					existing = i.next();
				}
			}

			if (existing == null) {
				return null;
			}

			for (QName propertyValue : Arrays.stream(existing.getObjectArray("property_value"))
					.map(e -> QName.valueOf((String) e)).toArray(QName[]::new)) {
				if (JcrValue.BINARY_NS_URI.equals(propertyValue.getNamespaceURI())) {
					files().deleteFile(propertyValue.getLocalPart());
				}
			}

			propertiesEntity().deleteByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
					.putAll(pk)
					.put("is_deleted", Boolean.TRUE)
					.build()).execute();

			adaptTo(JcrCache.class).remove(id);

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.PROPERTY_REMOVED)
					.put("item_id", id)
					.put("item_path", getPath(id))
					.put("primary_type", getPrimaryType(id))
					.put("property_name", getResolved(relPath))
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", null)
					.build());

			return null;
		}

		public AdaptableMap<String, Object> getLock(String absPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(absPath) || !absPath.startsWith("/")) {
				throw new PathNotFoundException("Invalid path: " + absPath);
			}

			int d = -1;
			for (JcrPath path = getResolved(JcrPath.valueOf(absPath)); path != null; path = path.getParent()) {
				d++;
				try (Query.Result result = locksEntity().findByPrimaryKey(getNode(path.toString()))
						.setOffset(0).setLimit(1).execute()) {
					Iterator<AdaptableMap<String, Object>> i = result.iterator();
					if (!i.hasNext()) {
						continue;
					}

					AdaptableMap<String, Object> lockData = i.next();
					if (d > 0 && !lockData.getBoolean("is_deep")) {
						continue;
					}

					return lockData;
				}
			}

			throw new LockException("Node '" + absPath + "' is not locked.");
		}

		public String unlock(String absPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(absPath) || !absPath.startsWith("/")) {
				throw new PathNotFoundException("Invalid path: " + absPath);
			}

			AdaptableMap<String,Object> lockData;
			try {
				lockData = getLock(absPath);
			} catch (LockException ignore) {
				throw new LockException("Node '" + absPath + "' is not locked.");
			}

			AdaptableMap<String,Object> itemData = getNode(absPath);
			String id = itemData.getString("item_id");

			if (!lockData.getString("item_id").equals(id)) {
				throw new LockException("Node '" + absPath + "' is locked on node '" + itemData.getString("item_path") + "'.");
			}

			if (!Arrays.asList(fWorkspace.getLockManager().getLockTokens()).contains(lockData.getString("lock_token"))) {
				throw new LockException("Could not unlock node '" + absPath + "'.");
			}

			int count = locksEntity().deleteByPrimaryKey(lockData).execute();
			if (count == 0) {
				throw new LockException("Could not unlock node '" + absPath + "'.");
			}

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.UNLOCKED)
					.put("item_id", id)
					.put("item_path", getPath(id))
					.put("primary_type", getPrimaryType(id))
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", null)
					.build());

			removeProperty(id, Property.JCR_LOCK_OWNER);
			removeProperty(id, Property.JCR_LOCK_IS_DEEP);

			return lockData.getString("lock_token");
		}

		public void unlockSessionScopedLocks() throws IOException, SQLException, RepositoryException {
			try (Query.Result result = newQueryBuilder("SELECT item_id FROM jcr_locks WHERE session_id = {{session_id}}")
					.setVariable("session_id", getSessionIdentifier().toString())
					.build().execute()) {
				for (AdaptableMap<String, Object> r : result) {
					String absPath = getPath(r.getString("item_id"));
					unlock(absPath);
				}
			}
		}

		public AdaptableMap<String, Object> refreshLock(String absPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(absPath) || !absPath.startsWith("/")) {
				throw new PathNotFoundException("Invalid path: " + absPath);
			}

			AdaptableMap<String,Object> lockData;
			try {
				lockData = getLock(absPath);
			} catch (LockException ignore) {
				throw new LockException("Node '" + absPath + "' is not locked.");
			}

			AdaptableMap<String,Object> itemData = getNode(absPath);
			String id = itemData.getString("item_id");

			if (!lockData.getString("item_id").equals(id)) {
				throw new LockException("Node '" + absPath + "' is locked on node '" + itemData.getString("item_path") + "'.");
			}

			lockData.put("lock_created", System.currentTimeMillis());
			try {
				int count = locksEntity().update(
						AdaptableMap.<String, Object>newBuilder()
								.put("lock_created", lockData.getLong("lock_created"))
								.build(),
						AdaptableMap.<String, Object>newBuilder()
								.put("item_id", lockData.getString("item_id"))
								.build()).execute();
				if (count == 0) {
					throw new LockException("Could not refresh lock on node '" + absPath + "'.");
				}
			} catch (SQLException ignore) {
				throw new LockException("Node '" + absPath + "' is already locked.");
			}

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.LOCK_REFRESHED)
					.put("item_id", id)
					.put("item_path", itemData.getString("item_path"))
					.put("primary_type", getPrimaryType(id))
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", null)
					.build());

			return lockData;
		}

		public void removeLock(String id) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			newUpdateBuilder("DELETE FROM jcr_locks WHERE item_id = {{id}}")
					.setVariable("id", id)
					.build().execute();
		}

		public void setAccessControlPolicy(String id, JcrAccessControlEntry... aces) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}
			if (aces == null) {
				throw new AccessControlException("Access control entries must not be null.");
			}

			newUpdateBuilder("DELETE FROM jcr_aces WHERE item_id = {{id}}")
					.setVariable("id", id)
					.build().execute();

			int rowNo = 0;
			for (JcrAccessControlEntry ace : aces) {
				acesEntity().create(AdaptableMap.<String, Object>newBuilder()
						.put("item_id", id)
						.put("row_no", ++rowNo)
						.put("principal_name", ace.getPrincipal().getName())
						.put("is_group", (ace.getPrincipal() instanceof GroupPrincipal))
						.put("privilege_names", Arrays.stream(ace.getPrivileges()).map(e -> e.getName()).toArray())
						.put("is_allow", ace.isAllow())
						.build()).execute();
			}

			adaptTo(JcrCache.class).remove(id);

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.ACCESS_CONTROL_POLICY_CHANGED)
					.put("item_id", id)
					.put("item_path", getPath(id))
					.put("primary_type", getPrimaryType(id))
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", null)
					.build());
		}

		public void removeAccessControlPolicy(String id) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			newUpdateBuilder("DELETE FROM jcr_aces WHERE item_id = {{id}}")
					.setVariable("id", id)
					.build().execute();

			adaptTo(JcrCache.class).remove(id);

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
					.put("event_occurred", System.nanoTime())
					.put("event_type", Event.ACCESS_CONTROL_POLICY_REMOVED)
					.put("item_id", id)
					.put("item_path", getPath(id))
					.put("primary_type", getPrimaryType(id))
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", null)
					.build());
		}
	}

	private class PropertyIdentifier {
		private final String fItemId;
		private final String fPropertyName;

		private PropertyIdentifier(String itemId, String propertyName) {
			fItemId = itemId;
			fPropertyName = getResolved(propertyName);
		}

		@Override
		public String toString() {
			return fItemId + "/" + fPropertyName;
		}
	}

	private class PropertyParameters implements Closeable {
		private final String fItemId;
		private final String fItemName;
		private final String fParentItemId;
		private final int fPropertyType;
		private final List<String> fPropertyValues = new ArrayList<>();
		private final boolean fMultiple;
		private final Map<String, JcrBinary> fBinaries = new HashMap<>();
		private final Closer fCloser = Closer.create();

		private PropertyParameters(String itemId, String relPath, int type, boolean multiple, Value... values) throws IOException, RepositoryException {
			fItemId = new PropertyIdentifier(itemId, relPath).toString();
			fItemName = getResolved(relPath);
			fParentItemId = itemId;
			fMultiple = multiple;
			fPropertyType = type;

			for (Value value : values) {
				if (value == null) {
					continue;
				}

				QName propertyValue = null;
				JcrBinary binary = null;
				try {
					if (type == PropertyType.BINARY || relPath.equals(JcrProperty.JCR_DATA_NAME)) {
						propertyValue = new QName(JcrValue.BINARY_NS_URI, UUID.randomUUID().toString(), XMLConstants.DEFAULT_NS_PREFIX);
						binary = fCloser.register((JcrBinary) ((JcrValue) value).adapt(Binary.class));
					} else if (type == PropertyType.BOOLEAN || type == PropertyType.DATE || type == PropertyType.DECIMAL
							|| type == PropertyType.DOUBLE || type == PropertyType.LONG || type == PropertyType.STRING
							|| type == PropertyType.NAME || type == PropertyType.URI) {
						String v = ((JcrValue) value).adapt(String.class);
						if (v == null) {
							propertyValue = null;
						} else {
							if (v.length() > 3072) {
								propertyValue = new QName(JcrValue.BINARY_NS_URI, UUID.randomUUID().toString(), XMLConstants.DEFAULT_NS_PREFIX);
								binary = fCloser.register(JcrBinary.create(v.getBytes(StandardCharsets.UTF_8.toString())));
							} else {
								propertyValue = new QName(JcrValue.STRING_NS_URI, v, XMLConstants.DEFAULT_NS_PREFIX);
							}
						}
					} else if (type == PropertyType.REFERENCE) {
						try {
							String v = ((JcrValue) value).adapt(Node.class).getIdentifier();
							propertyValue = new QName(JcrValue.STRING_NS_URI, v, XMLConstants.DEFAULT_NS_PREFIX);
						} catch (ValueFormatException ignore) {
							String idOrPath = ((JcrValue) value).adapt(String.class);
							AdaptableMap<String,Object> itemData = null;
							try {
								itemData = items().getNodeByIdentifier(UUID.fromString(idOrPath).toString());
							} catch (ItemNotFoundException ex) {
								throw ex;
							} catch (Throwable ex) {}
							try {
								itemData = items().getNode(idOrPath);
							} catch (Throwable ex) {}
							if (itemData == null) {
								propertyValue = null;
							} else {
								propertyValue = new QName(JcrValue.STRING_NS_URI, itemData.getString("item_id"), XMLConstants.DEFAULT_NS_PREFIX);
							}
						}
					} else if (type == PropertyType.PATH || type == PropertyType.WEAKREFERENCE) {
						try {
							String v = ((JcrValue) value).adapt(Node.class).getPath();
							propertyValue = new QName(JcrValue.STRING_NS_URI, v, XMLConstants.DEFAULT_NS_PREFIX);
						} catch (ValueFormatException ignore) {
							String idOrPath = ((JcrValue) value).adapt(String.class);
							AdaptableMap<String,Object> itemData = null;
							try {
								itemData = items().getNodeByIdentifier(UUID.fromString(idOrPath).toString());
							} catch (ItemNotFoundException ex) {
								throw ex;
							} catch (Throwable ex) {}
							try {
								itemData = items().getNode(idOrPath);
							} catch (Throwable ex) {}
							if (itemData == null) {
								propertyValue = null;
							} else {
								propertyValue = new QName(JcrValue.STRING_NS_URI, itemData.getString("item_path"), XMLConstants.DEFAULT_NS_PREFIX);
							}
						}
					} else {
						throw new IllegalArgumentException("Invalid property type: " + type);
					}
				} catch (UnadaptableValueException ex) {
					throw Cause.create(ex).wrap(ValueFormatException.class);
				}

				if (propertyValue == null) {
					throw new ValueFormatException("The value of property '" + relPath + "' cannot be converted to a " + PropertyType.nameFromValue(type) + ".");
				}

				fPropertyValues.add(propertyValue.toString());
				if (binary != null) {
					fBinaries.put(propertyValue.getLocalPart(), binary);
				}
			}
		}

		public String getItemId() {
			return fItemId;
		}

		public String getItemName() {
			return fItemName;
		}

		public String getParentItemId() {
			return fParentItemId;
		}

		public int getPropertyType() {
			return fPropertyType;
		}

		public String[] getPropertyValues() {
			return fPropertyValues.toArray(String[]::new);
		}

		public Set<Map.Entry<String, JcrBinary>> getBinaries() {
			return fBinaries.entrySet();
		}

		public boolean isMultiple() {
			return fMultiple;
		}

		@Override
		public void close() throws IOException {
			fCloser.close();
		}
	}

	public class NamespacesQuery {
		private NamespacesQuery() {}

		private Entity _namespacesEntity;
		private Entity namespacesEntity() throws SQLException {
			if (_namespacesEntity == null) {
				_namespacesEntity = Entity.newBuilder(getConnection()).setName("jcr_namespaces").build();
			}
			return _namespacesEntity;
		}

		public String getPrefix(String uri) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT namespace_prefix FROM jcr_namespaces WHERE namespace_uri = {{uri}}")
					.setVariable("uri", uri)
					.build().setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					return null;
				}

				return i.next().getString("namespace_prefix");
			}
		}

		public String[] getPrefixes() throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT namespace_prefix FROM jcr_namespaces ORDER BY namespace_prefix")
					.build().setOffset(0).execute()) {
				List<String> l = new ArrayList<>();
				for (AdaptableMap<String, Object> r : result) {
					l.add(r.getString("namespace_prefix"));
				}
				return l.toArray(String[]::new);
			}
		}

		public String getURI(String prefix) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT namespace_uri FROM jcr_namespaces WHERE namespace_prefix = {{prefix}}")
					.setVariable("prefix", prefix)
					.build().setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					return null;
				}

				return i.next().getString("namespace_uri");
			}
		}

		public String[] getURIs() throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder("SELECT namespace_uri FROM jcr_namespaces ORDER BY namespace_uri")
					.build().setOffset(0).execute()) {
				List<String> l = new ArrayList<>();
				for (AdaptableMap<String, Object> r : result) {
					l.add(r.getString("namespace_uri"));
				}
				return l.toArray(String[]::new);
			}
		}

		public void registerNamespace(String prefix, String uri) throws IOException, SQLException {
			namespacesEntity().create(AdaptableMap.<String, Object>newBuilder()
					.put("namespace_prefix", prefix)
					.put("namespace_uri", uri)
					.build()).execute();
		}

		public void unregisterNamespace(String prefix) throws IOException, SQLException {
			namespacesEntity().deleteByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
					.put("namespace_prefix", prefix)
					.build()).execute();
		}
	}

}

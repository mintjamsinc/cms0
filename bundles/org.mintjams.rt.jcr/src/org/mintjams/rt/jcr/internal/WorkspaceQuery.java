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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
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
import org.mintjams.jcr.security.UnknownPrincipal;
import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.jcr.internal.blob.BlobStore;
import org.mintjams.rt.jcr.internal.cluster.ClusterController;
import org.mintjams.rt.jcr.internal.cluster.ClusterJournal;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlEntry;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlList;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlManager;
import org.mintjams.rt.jcr.internal.nodetype.JcrNodeTypeManager;
import org.mintjams.rt.jcr.internal.sql.DatabaseDialect;
import org.mintjams.rt.jcr.internal.version.JcrVersionManager;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.adapter.UnadaptableValueException;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.sql.Entity;
import org.mintjams.tools.sql.Query;
import org.mintjams.tools.sql.Update;

public class WorkspaceQuery implements Adaptable {

	private final JcrWorkspace fWorkspace;
	private boolean fAccessControlAffected;
	private boolean fJournalAffected;
	private final Set<String> fDirtyItems = new HashSet<>();
	private final LinkedHashMap<String, OverlayEntry> fOverlay = new LinkedHashMap<>(16, 0.75f, true);
	private final Map<String, String> fOverlayPaths = new HashMap<>();

	private WorkspaceQuery(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static WorkspaceQuery create(JcrWorkspace workspace) {
		return new WorkspaceQuery(workspace);
	}

	public void commit() throws SQLException {
		newUpdateBuilder("DELETE FROM jcr_items WHERE is_deleted = TRUE").build().execute();
		newUpdateBuilder("DELETE FROM jcr_properties WHERE is_deleted = TRUE").build().execute();

		if (fJournalAffected) {
			fJournalAffected = false;
			// In a cluster, mark the transaction in the shared commit log so
			// that the other nodes can replay its journal entries. Written
			// within this transaction: the marker becomes visible if and only
			// if the changes do.
			ClusterController clusterController = adaptTo(ClusterController.class);
			if (clusterController.isClusterEnabled()) {
				ClusterJournal.writeCommitMarker(getConnection(),
						getSessionIdentifier().getTransactionIdentifier(), clusterController.getNodeId());
			}
		}

		getConnection().commit();

		if (!fDirtyItems.isEmpty()) {
			adaptTo(NodeCache.class).invalidate(fDirtyItems);
			fDirtyItems.clear();
			fOverlay.clear();
			fOverlayPaths.clear();
		}

		if (fAccessControlAffected) {
			fAccessControlAffected = false;
			AccessControlStore store = adaptTo(AccessControlStore.class);
			try {
				store.reload();
			} catch (Throwable ex) {
				store.markStale();
				Activator.getDefault().getLogger(getClass())
						.error("An error occurred while reloading the access control store.", ex);
			}
		}
	}

	public void rollback() throws SQLException {
		getConnection().rollback();
		fAccessControlAffected = false;
		fJournalAffected = false;
		fDirtyItems.clear();
		fOverlay.clear();
		fOverlayPaths.clear();
	}

	/**
	 * Returns whether this transaction carries uncommitted changes that affect access
	 * control evaluation (access control lists, or paths of items that carry them).
	 * While this returns {@code true}, privileges must be evaluated against the
	 * transaction instead of the workspace-wide access control store.
	 */
	public boolean isAccessControlAffected() {
		return fAccessControlAffected;
	}

	public void setAccessControlAffected() {
		fAccessControlAffected = true;
	}

	/**
	 * Marks an item as changed by this transaction. Dirty items bypass the
	 * workspace-wide node cache — they are read through this transaction's own
	 * connection and cached in the transaction-local overlay — and are invalidated
	 * in the workspace-wide cache when the transaction commits.
	 */
	public void markDirty(String id) {
		fDirtyItems.add(id);
		removeOverlayEntry(id);
	}

	/**
	 * Returns the node cache revision to capture before reading item data from the
	 * database, for use with {@link #cacheNode(AdaptableMap, long)} and
	 * {@link #cacheProperties(String, Map, long)}.
	 */
	public long getNodeCacheRevision() {
		return adaptTo(NodeCache.class).getRevision();
	}

	public AdaptableMap<String, Object> getCachedNode(String absPath) {
		String id = fOverlayPaths.get(absPath);
		if (id != null) {
			OverlayEntry entry = fOverlay.get(id);
			if (entry != null && entry.fItemData != null) {
				return entry.fItemData;
			}
		}
		AdaptableMap<String, Object> itemData = adaptTo(NodeCache.class).getNode(absPath);
		if (itemData != null && fDirtyItems.contains(itemData.getString("item_id"))) {
			return null;
		}
		return itemData;
	}

	public AdaptableMap<String, Object> getCachedNodeByIdentifier(String id) {
		if (fDirtyItems.contains(id)) {
			OverlayEntry entry = fOverlay.get(id);
			return (entry != null) ? entry.fItemData : null;
		}
		return adaptTo(NodeCache.class).getNodeByIdentifier(id);
	}

	public Map<String, AdaptableMap<String, Object>> getCachedProperties(String id) {
		if (fDirtyItems.contains(id)) {
			OverlayEntry entry = fOverlay.get(id);
			return (entry != null) ? entry.fProperties : null;
		}
		return adaptTo(NodeCache.class).getProperties(id);
	}

	public void cacheNode(AdaptableMap<String, Object> itemData, long readRevision) {
		String id = itemData.getString("item_id");
		if (fDirtyItems.contains(id)) {
			String path = itemData.getString("item_path");
			OverlayEntry entry = fOverlay.get(id);
			if (entry == null) {
				entry = new OverlayEntry();
				fOverlay.put(id, entry);
			}
			if (entry.fPath != null && !entry.fPath.equals(path) && id.equals(fOverlayPaths.get(entry.fPath))) {
				fOverlayPaths.remove(entry.fPath);
			}
			entry.fItemData = itemData;
			entry.fPath = path;
			fOverlayPaths.put(path, id);
			trimOverlay();
			return;
		}
		adaptTo(NodeCache.class).setNode(itemData, readRevision);
	}

	public void cacheProperties(String id, Map<String, AdaptableMap<String, Object>> properties, long readRevision) {
		if (fDirtyItems.contains(id)) {
			OverlayEntry entry = fOverlay.get(id);
			if (entry == null) {
				entry = new OverlayEntry();
				fOverlay.put(id, entry);
			}
			entry.fProperties = properties;
			trimOverlay();
			return;
		}
		adaptTo(NodeCache.class).setProperties(id, properties, readRevision);
	}

	private void removeOverlayEntry(String id) {
		OverlayEntry entry = fOverlay.remove(id);
		if (entry != null && entry.fPath != null && id.equals(fOverlayPaths.get(entry.fPath))) {
			fOverlayPaths.remove(entry.fPath);
		}
	}

	private void trimOverlay() {
		int cacheSize = adaptTo(JcrRepository.class).getConfiguration().getNodeCacheSize();
		while (fOverlay.size() > cacheSize) {
			Iterator<Map.Entry<String, OverlayEntry>> i = fOverlay.entrySet().iterator();
			Map.Entry<String, OverlayEntry> eldest = i.next();
			i.remove();
			OverlayEntry entry = eldest.getValue();
			if (entry.fPath != null && eldest.getKey().equals(fOverlayPaths.get(entry.fPath))) {
				fOverlayPaths.remove(entry.fPath);
			}
		}
	}

	private static class OverlayEntry {
		private AdaptableMap<String, Object> fItemData;
		private Map<String, AdaptableMap<String, Object>> fProperties;
		private String fPath;
	}

	private final JournalQuery fJournalQuery = new JournalQuery();

	public JournalQuery journal() {
		return fJournalQuery;
	}

	private final FilesQuery fFilesQuery = new FilesQuery();

	public FilesQuery files() {
		return fFilesQuery;
	}

	private final ItemsQuery fItemsQuery = new ItemsQuery();

	public ItemsQuery items() {
		return fItemsQuery;
	}

	private final NamespacesQuery fNamespacesQuery = new NamespacesQuery();

	public NamespacesQuery namespaces() {
		return fNamespacesQuery;
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

	/**
	 * Recomputes {@code active_path} for the given item from its current
	 * {@code item_path} and {@code is_deleted} state: the live path while the node
	 * exists, {@code NULL} once it is removed. Call this after any change to either
	 * column (a move that rewrites the path, or a soft delete) so the unique index
	 * that guards path uniqueness stays in step. Inserts set {@code active_path}
	 * directly and do not need this.
	 */
	private void syncActivePath(String id) throws SQLException {
		newUpdateBuilder(
				"UPDATE jcr_items SET active_path = CASE WHEN is_deleted THEN NULL ELSE item_path END"
				+ " WHERE item_id = {{id}}")
				.setVariable("id", id).build().execute();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

	interface QueryMonitor {
		boolean isCancelled();
	}

	interface OrphanMonitor extends QueryMonitor {
		Function<Query, Query> getQueryCustomizer();

		Consumer<AdaptableMap<String, Object>> getNodeConsumer();
	}

	public class JournalQuery {
		private JournalQuery() {
		}

		private Entity fJournalEntity;

		private Entity journalEntity() throws SQLException {
			if (fJournalEntity == null) {
				fJournalEntity = Entity.newBuilder(getConnection()).setName("jcr_journal").build();
			}
			return fJournalEntity;
		}

		public void writeJournal(Map<String, Object> data) throws SQLException {
			fJournalAffected = true;
			SessionIdentifier sessionIdentifier = getSessionIdentifier();
			boolean useSavepoint = adaptTo(DatabaseDialect.class).isTransactionAbortedOnError();
			for (int i = 0;; i++) {
				String journalId = MessageFormat.format("{0,number,00000000000000000000}-{1,number,00000000000000000000}",
						sessionIdentifier.getCreated(), System.nanoTime());
				// On databases that abort the transaction on a statement
				// failure, a savepoint keeps a retryable insert failure from
				// poisoning the caller's uncommitted changes.
				Savepoint savepoint = useSavepoint ? getConnection().setSavepoint() : null;
				try {
					journalEntity().create(AdaptableMap.<String, Object>newBuilder().putAll(data)
							.put("session_id", sessionIdentifier.toString())
							.put("transaction_id", sessionIdentifier.getTransactionIdentifier())
							.put("journal_id", journalId).build()).execute();
					return;
				} catch (SQLException ex) {
					if (savepoint != null) {
						getConnection().rollback(savepoint);
					}
					if (i == 0) {
						continue;
					}

					throw ex;
				}
			}
		}

		public Query.Result listJournal(String id) throws SQLException {
			return newQueryBuilder("SELECT * FROM jcr_journal WHERE transaction_id = {{id}} ORDER BY event_occurred")
					.setVariable("id", id).build().setOffset(0).execute();
		}

		public Query.Result listNewNodes(String transactionID) throws SQLException {
			return newQueryBuilder(
					"SELECT DISTINCT item_id, item_path, primary_type FROM jcr_journal"
					+ " WHERE transaction_id = {{id}} AND event_type = {{eventType}}")
					.setVariable("id", transactionID)
					.setVariable("eventType", Event.NODE_ADDED)
					.build().setOffset(0).execute();
		}

		public Query.Result listRemovedNodes(String transactionID) throws SQLException {
			return newQueryBuilder(
					"SELECT DISTINCT item_id, item_path FROM jcr_journal"
					+ " WHERE transaction_id = {{id}} AND event_type = {{eventType}}")
					.setVariable("id", transactionID)
					.setVariable("eventType", Event.NODE_REMOVED)
					.build().setOffset(0).execute();
		}
	}

	public class FilesQuery {
		private FilesQuery() {
		}

		private Entity fFilesEntity;

		private Entity filesEntity() throws SQLException {
			if (fFilesEntity == null) {
				fFilesEntity = Entity.newBuilder(getConnection()).setName("jcr_files").build();
			}
			return fFilesEntity;
		}

		public void createFile(String id, JcrBinary data) throws IOException, SQLException, RepositoryException {
			long size;
			try (InputStream in = data.getStream()) {
				size = adaptTo(BlobStore.class).write(id, in);
			}

			filesEntity().create(AdaptableMap.<String, Object>newBuilder().put("file_id", id)
					.put("file_size", size).build()).execute();
		}

		/**
		 * Marks a file row deleted. Conditional on {@code is_deleted = FALSE}: a
		 * transaction whose snapshot still references an already-deleted blob (a
		 * concurrent rewrite or removal of the same node got there first) skips
		 * the row instead of queueing behind the lock a concurrent {@link #clean}
		 * chunk holds on it until that chunk commits.
		 */
		public void deleteFile(String id) throws SQLException {
			newUpdateBuilder("UPDATE jcr_files SET is_deleted = TRUE"
					+ " WHERE file_id = {{id}} AND is_deleted = FALSE")
					.setVariable("id", id).build().execute();
		}

		public boolean exists(String id) throws IOException, SQLException {
			try (Query.Result result = filesEntity()
					.findByPrimaryKey(AdaptableMap.<String, Object>newBuilder().put("file_id", id).build()).setOffset(0)
					.setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public long getSize(String id) throws IOException, SQLException {
			try (Query.Result result = filesEntity()
					.findByPrimaryKey(AdaptableMap.<String, Object>newBuilder().put("file_id", id).build()).setOffset(0)
					.setLimit(1).execute()) {
				return result.iterator().next().getLong("file_size");
			}
		}

		public Path getPath(String id) {
			try {
				return adaptTo(BlobStore.class).getPath(id);
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		public InputStream getInputStream(String id) throws IOException {
			return adaptTo(BlobStore.class).read(id);
		}

		/**
		 * Number of blobs removed per transaction of {@link #clean}. Each chunk
		 * commit releases the deleted rows' locks — so a user transaction
		 * re-marking one of them never waits behind the whole run — and the
		 * work already committed survives a failure further down the list.
		 */
		private static final int CLEAN_BATCH_SIZE = 100;

		/**
		 * Removes the blobs of the {@code jcr_files} rows marked deleted, and
		 * the rows themselves, in chunks of {@value #CLEAN_BATCH_SIZE} with a
		 * commit after each chunk. A row locked by a concurrent transaction
		 * (concurrent removals of overlapping subtrees mark the same row
		 * deleted again) is skipped, not failed: it stays marked and is picked
		 * up by the run that transaction's commit triggers.
		 */
		public void clean(QueryMonitor monitor) throws IOException, SQLException {
			DatabaseDialect dialect = adaptTo(DatabaseDialect.class);
			boolean useSavepoint = dialect.isTransactionAbortedOnError();
			// Rows skipped because a concurrent transaction holds their lock.
			// Excluded from the chunk queries below, so a backlog of only
			// locked rows terminates the loop instead of spinning on them.
			Set<String> skipped = new HashSet<>();
			while (!monitor.isCancelled()) {
				// Fetch a chunk at a time: a bulk removal can mark far more
				// rows than should be materialized (or locked) at once.
				List<String> ids = new ArrayList<>();
				try (Query.Result result = newQueryBuilder("SELECT file_id FROM jcr_files WHERE is_deleted = TRUE")
						.build().setOffset(0).setLimit(CLEAN_BATCH_SIZE + skipped.size()).execute()) {
					for (AdaptableMap<String, Object> r : result) {
						String id = r.getString("file_id");
						if (!skipped.contains(id)) {
							ids.add(id);
						}
					}
				}
				if (ids.isEmpty()) {
					break;
				}

				for (String id : ids) {
					if (monitor.isCancelled()) {
						break;
					}

					// A blob whose row delete was skipped or rolled back on an
					// earlier run is already gone; delete(id) is a no-op then.
					adaptTo(BlobStore.class).delete(id);

					Savepoint savepoint = useSavepoint ? getConnection().setSavepoint() : null;
					try {
						filesEntity().deleteByPrimaryKey(
								AdaptableMap.<String, Object>newBuilder().put("file_id", id).build()).execute();
					} catch (SQLException ex) {
						if (!dialect.isLockContention(ex)) {
							throw ex;
						}
						if (savepoint != null) {
							getConnection().rollback(savepoint);
						}
						skipped.add(id);
					}
				}

				getConnection().commit();
			}

			if (!skipped.isEmpty()) {
				Activator.getDefault().getLogger(getClass()).info(
						"Skipped {} deleted file(s) locked by concurrent transactions; they will be cleaned on a later run.",
						skipped.size());
			}
		}
	}

	public class ItemsQuery {
		private ItemsQuery() {
		}

		private Entity fItemsEntity;

		private Entity itemsEntity() throws SQLException {
			if (fItemsEntity == null) {
				fItemsEntity = Entity.newBuilder(getConnection()).setName("jcr_items").build();
			}
			return fItemsEntity;
		}

		private Entity fPropertiesEntity;

		private Entity propertiesEntity() throws SQLException {
			if (fPropertiesEntity == null) {
				fPropertiesEntity = Entity.newBuilder(getConnection()).setName("jcr_properties").build();
			}
			return fPropertiesEntity;
		}

		private Entity fAcesEntity;

		private Entity acesEntity() throws SQLException {
			if (fAcesEntity == null) {
				fAcesEntity = Entity.newBuilder(getConnection()).setName("jcr_aces").build();
			}
			return fAcesEntity;
		}

		private Entity fLocksEntity;

		public Entity locksEntity() throws SQLException {
			if (fLocksEntity == null) {
				fLocksEntity = Entity.newBuilder(getConnection()).setName("jcr_locks").build();
			}
			return fLocksEntity;
		}

		public JcrPath getVersionHistoryPath(String id) {
			return getResolved(JcrPath.valueOf("/").resolve(JcrNode.JCR_SYSTEM_NAME)
					.resolve(JcrNode.JCR_VERSION_STORAGE_NAME).resolve(id.substring(0, 2)).resolve(id.substring(2, 4))
					.resolve(id.substring(4, 6)).resolve(id.substring(6, 8)).resolve(id));
		}

		public AdaptableMap<String, Object> createRootNode() throws IOException, SQLException, RepositoryException {
			Map<String, Object> definition = new HashMap<>();
			definition.put("path", "/");
			definition.put("primaryType", "mi:root");
			definition.put("mixinTypes", NodeType.MIX_CREATED);
			definition.put("acl", AdaptableMap.<String, Object>newBuilder().put("principal", EveryonePrincipal.NAME)
					.put("privileges", Arrays.asList("jcr:read")).put("effect", "allow").build());
			return createNode(definition);
		}

		public AdaptableMap<String, Object> createNode(String absPath, String primaryTypeName)
				throws IOException, SQLException, RepositoryException {
			Map<String, Object> definition = new HashMap<>();
			definition.put("path", absPath);
			definition.put("primaryType", primaryTypeName);
			definition.put("mixinTypes", null);
			return createNode(definition);
		}

		public AdaptableMap<String, Object> createNode(String absPath, String primaryTypeName, String[] mixinTypeNames)
				throws IOException, SQLException, RepositoryException {
			Map<String, Object> definition = new HashMap<>();
			definition.put("path", absPath);
			definition.put("primaryType", primaryTypeName);
			definition.put("mixinTypes", mixinTypeNames);
			return createNode(definition);
		}

		public AdaptableMap<String, Object> createNode(Map<String, Object> definition)
				throws IOException, SQLException, RepositoryException {
			ExpressionContext el = ExpressionContext.create().setVariable("definition", definition);
			JcrPath itemPath = getResolved(JcrPath.valueOf(el.getString("definition.path")));

			String itemName = null;
			if (!itemPath.isRoot()) {
				itemName = itemPath.getName().toString();
			}

			AdaptableMap<String, Object> parentItemData = null;
			if (!itemPath.isRoot()) {
				parentItemData = getNode(itemPath.getParent().toString());
			}

			try {
				getNode(itemPath.toString());
				throw new ItemExistsException("Node already exists: " + itemPath.toString());
			} catch (PathNotFoundException ignore) {
			}

			String primaryTypeName = el.getString("definition.primaryType");
			if (Strings.isEmpty(primaryTypeName)) {
				primaryTypeName = NodeType.NT_UNSTRUCTURED;
			}
			String primaryType = getResolved(primaryTypeName);
			if (!getNodeTypeManager().hasNodeType(primaryType)) {
				throw new NoSuchNodeTypeException(primaryTypeName);
			}
			if (getNodeTypeManager().getNodeType(primaryType).isAbstract()) {
				throw new ConstraintViolationException(
						"The primary type of a node cannot be abstract: " + primaryTypeName);
			}

			List<String> mixinTypes = new ArrayList<>();
			for (String mixinTypeName : el.getStringArray("definition.mixinTypes")) {
				String mixinType = getResolved(mixinTypeName);
				if (!getNodeTypeManager().hasNodeType(mixinType)) {
					throw new NoSuchNodeTypeException(mixinTypeName);
				}
				if (getNodeTypeManager().getNodeType(mixinType).isAbstract()) {
					throw new ConstraintViolationException(
							"The mixin type of a node cannot be abstract: " + mixinTypeName);
				}
				mixinTypes.add(mixinType);
			}

			// Check child node definition constraints against parent node type
			if (parentItemData != null) {
				JcrNode parentNode = JcrNode.create(parentItemData, adaptTo(JcrSession.class));
				NodeType childType = getNodeTypeManager().getNodeType(primaryType);
				validateChildNodeDefinition(parentNode, itemName, childType);
			}

			String id = el.getString("definition.identifier");
			if (Strings.isEmpty(id)) {
				id = UUID.randomUUID().toString();
			}

			String path = "/" + getResolved(Strings.defaultString(itemName));
			if (parentItemData != null) {
				if (!JcrPath.valueOf(parentItemData.getString("item_path")).isRoot()) {
					path = parentItemData.getString("item_path") + path;
				}
			}

			// active_path mirrors item_path while the node is live; a UNIQUE index on
			// it makes path uniqueness a hard guarantee. The getNode() check above is
			// still useful (it gives a clean error in the common, uncontended case
			// without a database round-trip), but it cannot see a sibling transaction's
			// uncommitted insert. If two sessions race, the loser's insert violates the
			// unique index; we translate that into the same ItemExistsException the
			// check would have thrown. The savepoint lets the caller's transaction
			// survive that expected failure on databases that abort on error.
			boolean useSavepoint = adaptTo(DatabaseDialect.class).isTransactionAbortedOnError();
			Savepoint savepoint = useSavepoint ? getConnection().setSavepoint() : null;
			try {
				newUpdateBuilder("DELETE FROM jcr_items WHERE item_id = {{id}}").setVariable("id", id).build().execute();
				newUpdateBuilder("DELETE FROM jcr_properties WHERE parent_item_id = {{id}}").setVariable("id", id).build()
						.execute();
				newUpdateBuilder("DELETE FROM jcr_references WHERE parent_item_id = {{id}}").setVariable("id", id).build()
						.execute();

				itemsEntity()
						.create(AdaptableMap.<String, Object>newBuilder()
								.put("item_id", id)
								.put("item_name", JcrPath.valueOf(path).getName().toString())
								.put("item_path", path)
								.put("active_path", path)
								.put("parent_item_id", (parentItemData == null) ? null : parentItemData.getString("item_id"))
								.put("is_system", JCRs.isSystemPath(path))
								.build())
						.execute();
			} catch (SQLException ex) {
				if (savepoint != null) {
					// Undo the failed attempt so the caller's transaction stays usable.
					getConnection().rollback(savepoint);
				}
				if (adaptTo(DatabaseDialect.class).isUniqueConstraintViolation(ex)) {
					throw new ItemExistsException("Node already exists: " + itemPath.toString());
				}
				throw ex;
			}

			// The new item must never reach the workspace-wide cache before it is committed.
			markDirty(id);

			setProperty(id, JcrProperty.JCR_PRIMARY_TYPE, PropertyType.NAME,
					createValue(PropertyType.NAME, primaryType));
			if (!mixinTypes.isEmpty()) {
				setProperty(id, JcrProperty.JCR_MIXIN_TYPES, PropertyType.NAME,
						createValues(PropertyType.NAME, mixinTypes.toArray()));
			}

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder().put("event_occurred", System.currentTimeMillis())
					.put("event_type", Event.NODE_ADDED).put("item_id", id).put("item_path", path)
					.put("primary_type", primaryType).put("user_id", fWorkspace.getSession().getUserID()).build());

			AdaptableMap<String, Object> itemData = getNodeByIdentifier(id);
			if (!el.getBoolean("definition.disablePostProcess")) {
				postCreateNode(itemData, definition);
			}
			return itemData;
		}

		private void validateChildNodeDefinition(JcrNode parentNode, String childName, NodeType childType)
				throws ConstraintViolationException, RepositoryException {
			// Collect all child node definitions from primary type and mixins
			List<NodeDefinition> allChildDefs = new ArrayList<>();
			NodeType primaryType = parentNode.getPrimaryNodeType();
			for (NodeDefinition def : primaryType.getChildNodeDefinitions()) {
				allChildDefs.add(def);
			}
			for (NodeType mixin : parentNode.getMixinNodeTypes()) {
				for (NodeDefinition def : mixin.getChildNodeDefinitions()) {
					allChildDefs.add(def);
				}
			}

			if (allChildDefs.isEmpty()) {
				throw new ConstraintViolationException(
						"Node type '" + primaryType.getName()
						+ "' does not allow child nodes.");
			}

			// Search for a matching named definition first, then fall back to residual
			NodeDefinition matchedDef = null;
			NodeDefinition residualDef = null;
			for (NodeDefinition def : allChildDefs) {
				if ("*".equals(def.getName())) {
					if (residualDef == null) {
						residualDef = def;
					}
				} else if (def.getName().equals(childName)) {
					matchedDef = def;
					break;
				}
			}

			NodeDefinition effectiveDef = (matchedDef != null) ? matchedDef : residualDef;
			if (effectiveDef == null) {
				throw new ConstraintViolationException(
						"Node type '" + primaryType.getName()
						+ "' does not allow child node '" + childName + "'.");
			}

			// Check requiredPrimaryTypes
			String[] requiredTypeNames = effectiveDef.getRequiredPrimaryTypeNames();
			if (requiredTypeNames != null && requiredTypeNames.length > 0) {
				boolean typeAllowed = false;
				for (String requiredTypeName : requiredTypeNames) {
					if (childType.isNodeType(requiredTypeName)) {
						typeAllowed = true;
						break;
					}
				}
				if (!typeAllowed) {
					throw new ConstraintViolationException(
							"Child node '" + childName + "' of type '" + childType.getName()
							+ "' does not satisfy required type constraint "
							+ Arrays.toString(requiredTypeNames)
							+ " of parent node type '" + primaryType.getName() + "'.");
				}
			}

			// Check sameNameSiblings
			if (!effectiveDef.allowsSameNameSiblings() && parentNode.hasNode(childName)) {
				throw new ConstraintViolationException(
						"Child node '" + childName + "' already exists and same-name siblings"
						+ " are not allowed by node type '" + primaryType.getName() + "'.");
			}
		}

		private void postCreateNode(AdaptableMap<String, Object> itemData, Map<String, Object> definition)
				throws IOException, SQLException, RepositoryException {
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
				JcrValue[] defaultValues = createDefaultValues(e, node, definition);
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
				createNode(JcrPath.valueOf(node.getPath()).resolve(e.getName()).toString(),
						e.getDefaultPrimaryTypeName());
			}

			@SuppressWarnings("unchecked")
			Collection<Map<String, Object>> l = (Collection<Map<String, Object>>) definition.get("acl");
			if (l != null) {
				for (Map<String, Object> ace : l) {
					ExpressionContext el = ExpressionContext.create().setVariable("ace", ace);
					JcrAccessControlList acl = (JcrAccessControlList) getAccessControlManager()
							.getPolicies(node.getPath())[0];
					String principalName = el.getString("ace.principal");
					if (Strings.isEmpty(principalName)) {
						principalName = el.getString("ace.group");
					}
					if (Strings.isEmpty(principalName)) {
						principalName = el.getString("ace.user");
					}
					if (Strings.isEmpty(principalName)) {
						throw new RepositoryException("The principal must be specified as either a user or a group.");
					}

					Principal principal;
					try {
						principal = Activator.getDefault().getPrincipal(principalName);
					} catch (PrincipalNotFoundException ignore) {
						principal = new UnknownPrincipal(principalName);
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

		private JcrValue[] createDefaultValues(PropertyDefinition propertyDefinition, Node node,
				Map<String, Object> definition) throws IOException, RepositoryException {
			Value[] defaults = propertyDefinition.getDefaultValues();
			if (defaults != null) {
				return Stream.of(defaults).toArray(JcrValue[]::new);
			}

			// A caller-supplied creation timestamp (trusted restore/import tooling)
			// seeds the protected jcr:created instead of "now"; every other auto-
			// created DATE still defaults to the current time. The definition is
			// absent when seeding a mixin's defaults on an existing node.
			if (propertyDefinition.getName().equals(JcrProperty.JCR_CREATED_NAME)
					&& definition != null && definition.get("created") instanceof Calendar) {
				return createValues(PropertyType.DATE, (Calendar) definition.get("created"));
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
				return createValues(PropertyType.STRING,
						getMimeTypeDetector().probeContentType(Paths.get(fileNode.getName())));
			}

			if (propertyDefinition.getName().equals(JcrProperty.JCR_UUID_NAME)) {
				return createValues(PropertyType.STRING, node.getIdentifier());
			}

			return null;
		}

		public AdaptableMap<String, Object> addMixin(String id, String mixinName)
				throws IOException, SQLException, RepositoryException {
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

			AdaptableMap<String, Object> itemData = getNodeByIdentifier(id);

			List<String> mixinTypeNames = getMixinTypes(id);
			if (mixinTypeNames.contains(mixinType)) {
				throw new ConstraintViolationException("The specified node type '" + mixinName + "' is in node '"
						+ itemData.getString("item_path") + "'.");
			}
			mixinTypeNames.add(mixinType);

			if (!(mixinType.equals(getResolved(NodeType.MIX_SIMPLE_VERSIONABLE))
					|| mixinType.equals(getResolved(NodeType.MIX_VERSIONABLE)))) {
				setProperty(id, JcrProperty.JCR_MIXIN_TYPES, PropertyType.NAME,
						createValues(PropertyType.NAME, mixinTypeNames.toArray()));
			}

			postAddMixin(itemData, mixinType);
			return itemData;
		}

		private void postAddMixin(AdaptableMap<String, Object> itemData, String mixinName)
				throws IOException, SQLException, RepositoryException {
			if (mixinName.equals(getResolved(NodeType.MIX_VERSIONABLE))) {
				adaptTo(JcrVersionManager.class).addVersionControl(itemData.getString("item_id"));
			} else {
				Node node = fWorkspace.getSession().getNodeByIdentifier(itemData.getString("item_id"));
				NodeType mixinType = getNodeTypeManager().getNodeType(mixinName);
				Map<String, PropertyDefinition> propertyDefinitions = new HashMap<>();
				for (PropertyDefinition e : mixinType.getPropertyDefinitions()) {
					if (e.isAutoCreated()) {
						if (!propertyDefinitions.containsKey(e.getName())) {
							propertyDefinitions.put(e.getName(), e);
						}
					}
				}
				for (PropertyDefinition e : propertyDefinitions.values()) {
					if (node.hasProperty(e.getName())) {
						continue;
					}

					JcrValue[] defaultValues = createDefaultValues(e, node, null);
					if (defaultValues != null) {
						if (e.isMultiple()) {
							setProperty(node.getIdentifier(), e.getName(), e.getRequiredType(), defaultValues);
						} else {
							setProperty(node.getIdentifier(), e.getName(), e.getRequiredType(), defaultValues[0]);
						}
					}
				}
			}
		}

		public AdaptableMap<String, Object> removeMixin(String id, String mixinName)
				throws IOException, SQLException, RepositoryException {
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

			AdaptableMap<String, Object> itemData = getNodeByIdentifier(id);

			List<String> mixinTypeNames = getMixinTypes(id);
			if (!mixinTypeNames.contains(mixinType)) {
				throw new ConstraintViolationException("The specified node type '" + mixinName + "' is not in node '"
						+ itemData.getString("item_path") + "'.");
			}
			if (mixinType.equals(getResolved(NodeType.MIX_LOCKABLE))
					|| mixinType.equals(getResolved(NodeType.MIX_SIMPLE_VERSIONABLE))
					|| mixinType.equals(getResolved(NodeType.MIX_VERSIONABLE))) {
				throw new ConstraintViolationException(
						"The specified node type '" + mixinName + "' cannot be removed.");
			}
			mixinTypeNames.remove(mixinType);

			setProperty(id, JcrProperty.JCR_MIXIN_TYPES, PropertyType.NAME,
					createValues(PropertyType.NAME, mixinTypeNames.toArray()));

			return itemData;
		}

		public AdaptableMap<String, Object> getNode(String absPath)
				throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(absPath) || !absPath.startsWith("/")) {
				throw new PathNotFoundException("Invalid path: " + absPath);
			}

			String path = getResolved(JcrPath.valueOf(absPath)).toString();
			try (Query.Result result = itemsEntity().find(AdaptableMap.<String, Object>newBuilder()
					.put("item_path", path).put("is_deleted", Boolean.FALSE).build()).setOffset(0).setLimit(1)
					.execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					throw new PathNotFoundException(absPath);
				}

				return i.next();
			}
		}

		public AdaptableMap<String, Object> getNodeByIdentifier(String id)
				throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			try (Query.Result result = itemsEntity().find(AdaptableMap.<String, Object>newBuilder().put("item_id", id)
					.put("is_deleted", Boolean.FALSE).build()).setOffset(0).setLimit(1).execute()) {
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
			statement.append(" AND is_deleted = FALSE");
			statement.append(" ORDER BY item_path");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder().put("paths", paths)
					.build();

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(0).execute();
		}

		public Query.Result collectNodesByIdentifier(String... ids) throws SQLException {
			if (ids == null || ids.length == 0) {
				throw new IllegalArgumentException("Identifier must not be null or empty.");
			}

			StringBuilder statement = new StringBuilder().append("SELECT * FROM jcr_items");
			statement.append(" WHERE item_id IN ({{ids;list}})");
			statement.append(" OR (parent_item_id IN ({{ids;list}}) AND item_name = {{name}})");
			statement.append(" ORDER BY item_path");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder().put("ids", ids)
					.put("name", JcrNode.JCR_CONTENT_NAME).build();

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(0).execute();
		}

		public String getPath(String id) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			return getNodeByIdentifier(id).getString("item_path");
		}

		public Query.Result listNodes(String id, String[] nameGlobs, int offset) throws SQLException {
			return listNodes(id, nameGlobs, offset, false);
		}

		/**
		 * Lists child rows ordered by name. With {@code excludeDeleted} the
		 * soft-deleted rows are filtered in SQL, so {@code offset} addresses the
		 * same row set {@link #countNodes} counts — required when a pagination
		 * offset is pushed down to the database. Without it the result also
		 * carries rows soft-deleted earlier in this transaction, which some
		 * callers (e.g. subtree moves) rely on.
		 */
		public Query.Result listNodes(String id, String[] nameGlobs, int offset, boolean excludeDeleted) throws SQLException {
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

			StringBuilder statement = new StringBuilder()
					.append("SELECT * FROM jcr_items WHERE parent_item_id = {{id}}");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder().put("id", id).build();
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
			if (excludeDeleted) {
				statement.append(" AND is_deleted = FALSE");
			}
			statement.append(" ORDER BY item_name");

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(offset).execute();
		}

		/**
		 * Returns whether the node has at least one child, with a single-row probe
		 * instead of a full child count (a count scans the whole index range of a
		 * large folder just to compare it with zero).
		 */
		public boolean hasChildNodes(String id) throws SQLException {
			if (Strings.isEmpty(id)) {
				throw new IllegalArgumentException("Identifier must not be null or empty.");
			}

			StringBuilder statement = new StringBuilder()
					.append("SELECT item_id FROM jcr_items WHERE parent_item_id = {{id}} AND is_deleted = FALSE");
			try (Query.Result result = newQueryBuilder(statement.toString())
					.setVariables(AdaptableMap.<String, Object>newBuilder().put("id", id).build())
					.build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			} catch (IOException ex) {
				throw new SQLException("Failed to check for child nodes of " + id, ex);
			}
		}

		/**
		 * Lists the property rows of all the given nodes in one statement, for
		 * batch-seeding the property cache when a page of children is fetched.
		 */
		public Query.Result listPropertiesByParents(List<String> ids) throws SQLException {
			if (ids == null || ids.isEmpty()) {
				throw new IllegalArgumentException("Identifiers must not be null or empty.");
			}

			StringBuilder statement = new StringBuilder()
					.append("SELECT * FROM jcr_properties WHERE parent_item_id IN ({{ids;list}})")
					.append(" ORDER BY parent_item_id, item_name");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder().put("ids", ids).build();

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(0).execute();
		}

		public Query.Result listContentNodes(String... ids) throws SQLException {
			if (ids == null || ids.length == 0) {
				throw new IllegalArgumentException("Identifier must not be null or empty.");
			}

			StringBuilder statement = new StringBuilder().append("SELECT * FROM jcr_items");
			statement.append(" WHERE parent_item_id IN ({{ids;list}})");
			statement.append(" AND item_name = {{name}}");
			statement.append(" ORDER BY item_path");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder().put("ids", ids)
					.put("name", JcrNode.JCR_CONTENT_NAME).build();

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(0).execute();
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

			StringBuilder statement = new StringBuilder()
					.append("SELECT * FROM jcr_properties WHERE parent_item_id = {{id}}");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder().put("id", id).build();
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

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(offset).execute();
		}

		public Query.Result listReferences(String id, String name, boolean weak) throws SQLException {
			if (Strings.isEmpty(id)) {
				throw new IllegalArgumentException("Identifier must not be null or empty.");
			}

			StringBuilder statement = new StringBuilder()
					.append("SELECT p.* FROM jcr_references r")
					.append(" INNER JOIN jcr_properties p ON (r.item_id = p.item_id)")
					.append(" INNER JOIN jcr_items i ON (p.parent_item_id = i.item_id")
					.append(" AND i.is_system IS FALSE)")
					.append(" WHERE r.property_type = {{type}} AND r.target_item_id = {{id}}");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("type", weak ? PropertyType.WEAKREFERENCE : PropertyType.REFERENCE)
					.put("id", id).build();
			if (Strings.isNotEmpty(name)) {
				statement.append(" AND p.item_name = {{name}}");
				variables.put("name", name);
			}
			statement.append(" ORDER BY p.item_name");

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(0).execute();
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
					.put("path", getResolved(JcrPath.valueOf(absPath)).toString()).build();

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(0).execute();
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
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder().put("paths", paths)
					.build();

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(0).execute();
		}

		public Query.Result listLockTokens() throws SQLException {
			StringBuilder statement = new StringBuilder()
					.append("SELECT * FROM jcr_locks WHERE principal_name = {{principal_name}}");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("principal_name", fWorkspace.getSession().getUserID()).build();

			return newQueryBuilder(statement.toString()).setVariables(variables).build().setOffset(0).execute();
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

			StringBuilder statement = new StringBuilder()
					.append("SELECT COUNT(*) AS item_count FROM jcr_items WHERE parent_item_id = {{id}}");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder().put("id", id).build();
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

			try (Query.Result result = newQueryBuilder(statement.toString()).setVariables(variables).build().execute()) {
				return result.iterator().next().getLong("item_count");
			}
		}

		/**
		 * Counts, for each of the given item identifiers, the REFERENCE properties on
		 * live, non-system nodes that point at it. Identifiers that are not referenced
		 * are absent from the returned map. Resolved over the {@code jcr_references}
		 * index (a handful of indexed rows per identifier), so the cost is
		 * O(removed nodes), not O(workspace property rows).
		 */
		public Map<String, Long> countReferenced(Collection<String> ids) throws IOException, SQLException {
			Map<String, Long> counts = new HashMap<>();
			if (ids.isEmpty()) {
				return counts;
			}

			for (List<String> chunk : chunked(new ArrayList<>(new HashSet<>(ids)))) {
				try (Query.Result result = newQueryBuilder(
						"SELECT r.target_item_id AS target_item_id, COUNT(*) AS reference_count FROM jcr_references r"
						+ " INNER JOIN jcr_items i ON (r.parent_item_id = i.item_id AND i.is_system IS FALSE)"
						+ " WHERE r.property_type = {{type}} AND r.target_item_id IN ({{ids;list}})"
						+ " GROUP BY r.target_item_id")
						.setVariable("type", PropertyType.REFERENCE).setVariable("ids", chunk)
						.build().setOffset(0).execute()) {
					for (AdaptableMap<String, Object> r : result) {
						counts.put(r.getString("target_item_id"), r.getLong("reference_count"));
					}
				}
			}
			return counts;
		}

		public long countReferenced(String id, String name, boolean weak) throws IOException, SQLException {
			StringBuilder statement = new StringBuilder()
					.append("SELECT COUNT(*) AS reference_count FROM jcr_references r")
					.append(" INNER JOIN jcr_items i ON (r.parent_item_id = i.item_id AND i.is_system IS FALSE)");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("type", weak ? PropertyType.WEAKREFERENCE : PropertyType.REFERENCE)
					.put("id", id).build();
			if (Strings.isNotEmpty(name)) {
				statement.append(" INNER JOIN jcr_properties p ON (r.item_id = p.item_id AND p.item_name = {{name}})");
				variables.put("name", name);
			}
			statement.append(" WHERE r.property_type = {{type}} AND r.target_item_id = {{id}}");

			try (Query.Result result = newQueryBuilder(statement.toString()).setVariables(variables).build().execute()) {
				return result.iterator().next().getLong("reference_count");
			}
		}

		public long countDescendants(String absPath) throws IOException, SQLException {
			if (Strings.isEmpty(absPath)) {
				throw new IllegalArgumentException("Path must not be null or empty.");
			}

			String path = getResolved(JcrPath.valueOf(absPath)).toString();

			StringBuilder statement = new StringBuilder()
					.append("SELECT COUNT(*) AS item_count FROM jcr_items")
					.append(" WHERE item_path LIKE {{likePath}}")
					.append(" AND is_deleted IS FALSE");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
					.put("likePath", path + "/%").build();

			try (Query.Result result = newQueryBuilder(statement.toString()).setVariables(variables).build()
					.execute()) {
				return result.iterator().next().getLong("item_count");
			}
		}

		public long countNotOwnedLocksInDescendants(String absPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(absPath)) {
				throw new IllegalArgumentException("Path must not be null or empty.");
			}

			String prefix = getResolved(JcrPath.valueOf(absPath)).toString() + "/";
			Set<String> ownedTokens = new HashSet<>(Arrays.asList(fWorkspace.getLockManager().getLockTokens()));

			// jcr_locks only holds rows while locks are held, so the whole table is
			// fetched and filtered here. This keeps the cost O(held locks) instead
			// of letting the optimizer drive the join from the subtree's item_path
			// range (which scans the subtree once per call), and it also copes with
			// a session that holds no tokens (an empty NOT IN list is not valid SQL).
			long count = 0;
			try (Query.Result result = newQueryBuilder(
					"SELECT i.item_path, l.lock_token FROM jcr_locks l"
					+ " JOIN jcr_items i ON (l.item_id = i.item_id)"
					+ " WHERE i.is_deleted IS FALSE").build().setOffset(0).execute()) {
				for (AdaptableMap<String, Object> r : result) {
					if (!r.getString("item_path").startsWith(prefix)) {
						continue;
					}
					if (ownedTokens.contains(r.getString("lock_token"))) {
						continue;
					}
					count++;
				}
			}
			return count;
		}

		public boolean hasPendingChanges() throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder(
					"SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier()).build().setOffset(0)
					.setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public boolean nodeIsNew(String id) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder(
					"SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}} AND item_id = {{itemId}} AND event_type = {{eventType}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier()).setVariable("itemId", id)
					.setVariable("eventType", Event.NODE_ADDED).build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public boolean nodeIsModified(String id) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder(
					"SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}} AND item_id = {{itemId}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier()).setVariable("itemId", id)
					.build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public boolean propertyIsNew(String id, String relName) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder(
					"SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}} AND item_id = {{itemId}} AND property_name = {{propertyName}} AND event_type = {{eventType}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier()).setVariable("itemId", id)
					.setVariable("propertyName", relName).setVariable("eventType", Event.PROPERTY_ADDED).build()
					.setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public boolean propertyIsModified(String id, String relName) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder(
					"SELECT transaction_id FROM jcr_journal WHERE transaction_id = {{id}} AND item_id = {{itemId}} AND property_name = {{propertyName}}")
					.setVariable("id", getSessionIdentifier().getTransactionIdentifier()).setVariable("itemId", id)
					.setVariable("propertyName", relName).build().setOffset(0).setLimit(1).execute()) {
				return result.iterator().hasNext();
			}
		}

		public void removeNode(String id, Map<String, Object> options)
				throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			ExpressionContext el = ExpressionContext.create().setVariable("options", options);

			AdaptableMap<String, Object> pk = AdaptableMap.<String, Object>newBuilder().put("item_id", id).build();

			AdaptableMap<String, Object> existing = null;
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
			} else if (adaptTo(AccessControlStore.class).hasEntriesAt(path)) {
				// The item disappears (or is recreated) while its access control entries
				// are kept; the path-keyed store view changes on commit.
				setAccessControlAffected();
			}

			try (Query.Result result = propertiesEntity()
					.find(AdaptableMap.<String, Object>newBuilder().put("parent_item_id", id).build()).execute()) {
				for (Iterator<AdaptableMap<String, Object>> i = result.iterator(); i.hasNext();) {
					AdaptableMap<String, Object> r = i.next();

					for (QName propertyValue : Arrays.stream(r.getObjectArray("property_value"))
							.map(e -> QName.valueOf((String) e)).toArray(QName[]::new)) {
						if (JcrValue.BINARY_NS_URI.equals(propertyValue.getNamespaceURI())) {
							files().deleteFile(propertyValue.getLocalPart());
						}
					}

					propertiesEntity().updateByPrimaryKey(
							AdaptableMap.<String, Object>newBuilder().put("item_id", r.getString("item_id"))
									.put("is_deleted", Boolean.TRUE).build())
							.execute();
				}
			}
			newUpdateBuilder("DELETE FROM jcr_references WHERE parent_item_id = {{id}}")
					.setVariable("id", id).build().execute();

			itemsEntity().updateByPrimaryKey(
					AdaptableMap.<String, Object>newBuilder().putAll(pk).put("is_deleted", Boolean.TRUE).build())
					.execute();
			// Release the path so a same-path replacement may be created in this same
			// transaction (delete-then-recreate).
			syncActivePath(id);

			markDirty(id);

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder().put("event_occurred", System.currentTimeMillis())
					.put("event_type", Event.NODE_REMOVED).put("item_id", id).put("item_path", path)
					.put("primary_type", primaryType).put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null).put("event_info", null).build());
		}

		/**
		 * Maximum number of identifiers per {@code IN}-list statement issued by
		 * {@link #removeTree(String)}. Keeps every statement well below the
		 * parameter limits of both supported databases.
		 */
		private static final int REMOVE_TREE_CHUNK = 400;

		/** Rows per multi-row {@code INSERT} when journaling a removed subtree. */
		private static final int JOURNAL_INSERT_CHUNK = 50;

		/**
		 * Removes the node with the given identifier and its entire subtree with
		 * set-based statements: the subtree is collected level by level over
		 * {@code parent_item_id} (a few queries per tree level), the version
		 * histories, binaries and primary types it owns are collected from its
		 * property rows in one pass, and everything is then soft-deleted in bounded
		 * {@code IN}-list updates. This replaces the per-node statement sequence of
		 * {@link #removeNode(String, Map)} — which costs 15+ statements per node —
		 * with a per-{@link #REMOVE_TREE_CHUNK}-nodes statement sequence, which is
		 * what makes deleting large trees fast.
		 *
		 * <p>Every removed node still gets its own {@code NODE_REMOVED} journal row
		 * (written in multi-row inserts), so all journal consumers keep working
		 * unchanged: referential-integrity validation, the observation events that
		 * feed {@code nodeChanged} subscriptions and OSGi handlers, search index and
		 * suggestion cleanup, and cluster cache invalidation.
		 *
		 * <p>The caller is responsible for the subtree-root access checks; descendant
		 * names are held to the same protected-node rule the per-node path applies
		 * (see {@code JcrNode#removeTree()} for the eligibility gate).
		 *
		 * @return the number of removed files and folders (matching the per-node
		 *         path's progress counting), or {@code 0} when the node does not
		 *         exist (or was already removed in this transaction).
		 */
		public long removeTree(String id) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			return removeTree(List.of(id));
		}

		/**
		 * Removes the subtrees of all the given identifiers as one set-based unit.
		 * Collecting several roots at once is what lets a caller bound a large
		 * delete: a folder's children can be handed over a batch at a time, so the
		 * statements stay set-based while the transaction, the memory held for the
		 * collected subtree and the progress interval all stay proportional to the
		 * batch instead of the whole tree.
		 *
		 * <p>Identifiers that are not live (already removed in this transaction)
		 * drop out silently, matching the single-identifier form's {@code 0}.
		 */
		public long removeTree(Collection<String> ids) throws IOException, SQLException, RepositoryException {
			if (ids == null || ids.isEmpty()) {
				return 0;
			}

			// The live subtree roots, id -> path.
			Map<String, String> removedPaths = new LinkedHashMap<>();
			List<String> roots = new ArrayList<>();
			for (List<String> chunk : chunked(new ArrayList<>(new LinkedHashSet<>(ids)))) {
				try (Query.Result result = newQueryBuilder(
						"SELECT item_id, item_path FROM jcr_items"
						+ " WHERE item_id IN ({{ids;list}}) AND is_deleted = FALSE")
						.setVariable("ids", chunk).build().setOffset(0).execute()) {
					for (AdaptableMap<String, Object> r : result) {
						roots.add(r.getString("item_id"));
						removedPaths.put(r.getString("item_id"), r.getString("item_path"));
					}
				}
			}
			if (roots.isEmpty()) {
				return 0;
			}

			// Descendant names are held to the same protected-node rule remove()
			// applies while recursing; the roots' own names were already checked
			// by the caller.
			JcrNodeTypeManager nodeTypeManager = adaptTo(JcrNodeTypeManager.class);
			List<String> subtreeIds = new ArrayList<>(roots);
			for (AdaptableMap<String, Object> r : collectDescendants(roots)) {
				String name = r.getString("item_name");
				if (nodeTypeManager.isProtectedNode(name)) {
					throw new ConstraintViolationException("The node '" + name + "' is protected.");
				}
				subtreeIds.add(r.getString("item_id"));
				removedPaths.put(r.getString("item_id"), r.getString("item_path"));
			}

			// One pass over the subtree's property rows collects the binaries to
			// release, the version histories the subtree owns, and each node's
			// primary type (for progress counting and the journal rows). Version
			// storage internals never carry jcr:versionHistory themselves (freeze()
			// skips version control properties), so histories need no further chasing.
			Set<String> binaryIds = new HashSet<>();
			List<String> historyIds = new ArrayList<>();
			Map<String, String> primaryTypes = new HashMap<>();
			collectOwnedValues(subtreeIds, binaryIds, historyIds, primaryTypes);

			List<String> historyRoots = new ArrayList<>();
			for (List<String> chunk : chunked(historyIds)) {
				try (Query.Result result = newQueryBuilder(
						"SELECT item_id, item_path FROM jcr_items"
						+ " WHERE item_id IN ({{ids;list}}) AND is_deleted = FALSE")
						.setVariable("ids", chunk).build().setOffset(0).execute()) {
					for (AdaptableMap<String, Object> r : result) {
						historyRoots.add(r.getString("item_id"));
						removedPaths.put(r.getString("item_id"), r.getString("item_path"));
					}
				}
			}
			List<String> historyItemIds = new ArrayList<>(historyRoots);
			for (AdaptableMap<String, Object> r : collectDescendants(historyRoots)) {
				historyItemIds.add(r.getString("item_id"));
				removedPaths.put(r.getString("item_id"), r.getString("item_path"));
			}
			// Frozen nodes carry copies of binary properties; those blobs are
			// released together with the history.
			collectOwnedValues(historyItemIds, binaryIds, null, primaryTypes);

			// Count what the per-node path counts: files and folders.
			long countable = 0;
			Map<String, Boolean> countableByType = new HashMap<>();
			String fileTypeName = getResolved(NodeType.NT_FILE);
			String folderTypeName = getResolved(NodeType.NT_FOLDER);
			for (String itemId : subtreeIds) {
				String typeName = primaryTypes.get(itemId);
				if (typeName == null) {
					continue;
				}
				Boolean typeCountable = countableByType.get(typeName);
				if (typeCountable == null) {
					typeCountable = Boolean.FALSE;
					if (getNodeTypeManager().hasNodeType(typeName)) {
						NodeType type = getNodeTypeManager().getNodeType(typeName);
						typeCountable = type.isNodeType(fileTypeName) || type.isNodeType(folderTypeName);
					}
					countableByType.put(typeName, typeCountable);
				}
				if (typeCountable) {
					countable++;
				}
			}

			List<String> all = new ArrayList<>(removedPaths.keySet());
			int aceCount = 0;
			for (List<String> chunk : chunked(all)) {
				newUpdateBuilder("UPDATE jcr_properties SET is_deleted = TRUE"
						+ " WHERE parent_item_id IN ({{ids;list}}) AND is_deleted = FALSE")
						.setVariable("ids", chunk).build().execute();
				newUpdateBuilder("DELETE FROM jcr_references WHERE parent_item_id IN ({{ids;list}})")
						.setVariable("ids", chunk).build().execute();
				// active_path is released in the same statement so a same-path
				// replacement may be created within this transaction, exactly as
				// removeNode's syncActivePath does.
				newUpdateBuilder("UPDATE jcr_items SET is_deleted = TRUE, active_path = NULL"
						+ " WHERE item_id IN ({{ids;list}})")
						.setVariable("ids", chunk).build().execute();
				newUpdateBuilder("DELETE FROM jcr_locks WHERE item_id IN ({{ids;list}})")
						.setVariable("ids", chunk).build().execute();
				aceCount += newUpdateBuilder("DELETE FROM jcr_aces WHERE item_id IN ({{ids;list}})")
						.setVariable("ids", chunk).build().execute();
			}
			if (aceCount > 0) {
				// The path-keyed access control view changes on commit.
				setAccessControlAffected();
			}

			for (List<String> chunk : chunked(new ArrayList<>(binaryIds))) {
				newUpdateBuilder("UPDATE jcr_files SET is_deleted = TRUE WHERE file_id IN ({{ids;list}})")
						.setVariable("ids", chunk).build().execute();
			}

			for (String itemId : all) {
				markDirty(itemId);
			}

			writeRemovedJournal(removedPaths, primaryTypes);

			return countable;
		}

		/**
		 * Collects all live descendants of the given nodes, level by level over
		 * {@code parent_item_id}. The seed nodes themselves are not included.
		 */
		private List<AdaptableMap<String, Object>> collectDescendants(List<String> seedIds) throws SQLException {
			List<AdaptableMap<String, Object>> rows = new ArrayList<>();
			List<String> frontier = seedIds;
			while (!frontier.isEmpty()) {
				List<String> next = new ArrayList<>();
				for (List<String> chunk : chunked(frontier)) {
					try (Query.Result result = newQueryBuilder(
							"SELECT item_id, item_name, item_path FROM jcr_items"
							+ " WHERE parent_item_id IN ({{ids;list}}) AND is_deleted = FALSE")
							.setVariable("ids", chunk).build().setOffset(0).execute()) {
						for (AdaptableMap<String, Object> r : result) {
							rows.add(r);
							next.add(r.getString("item_id"));
						}
					} catch (IOException ex) {
						throw new SQLException("Failed to collect descendants", ex);
					}
				}
				frontier = next;
			}
			return rows;
		}

		/**
		 * Scans the live property rows of the given items, collecting the binary
		 * values they own into {@code binaryIds}, each node's primary type into
		 * {@code primaryTypes}, and — when {@code versionHistoryIds} is not
		 * {@code null} — the version histories referenced by their
		 * {@code jcr:versionHistory} properties.
		 */
		private void collectOwnedValues(List<String> itemIds, Set<String> binaryIds,
				List<String> versionHistoryIds, Map<String, String> primaryTypes) throws SQLException {
			String versionHistoryName = getResolved(JcrProperty.JCR_VERSION_HISTORY);
			String primaryTypeName = getResolved(JcrProperty.JCR_PRIMARY_TYPE);
			String binaryPrefix = "{" + JcrValue.BINARY_NS_URI + "}";
			String stringPrefix = "{" + JcrValue.STRING_NS_URI + "}";
			for (List<String> chunk : chunked(itemIds)) {
				try (Query.Result result = newQueryBuilder(
						"SELECT parent_item_id, item_name, property_value FROM jcr_properties"
						+ " WHERE parent_item_id IN ({{ids;list}}) AND is_deleted = FALSE")
						.setVariable("ids", chunk).build().setOffset(0).execute()) {
					for (AdaptableMap<String, Object> r : result) {
						String name = r.getString("item_name");
						boolean isVersionHistory = (versionHistoryIds != null) && versionHistoryName.equals(name);
						boolean isPrimaryType = primaryTypeName.equals(name);
						for (Object e : r.getObjectArray("property_value")) {
							String value = (String) e;
							if (value.startsWith(binaryPrefix)) {
								binaryIds.add(value.substring(binaryPrefix.length()));
							} else if (value.startsWith(stringPrefix)) {
								if (isVersionHistory) {
									versionHistoryIds.add(value.substring(stringPrefix.length()));
								} else if (isPrimaryType) {
									primaryTypes.put(r.getString("parent_item_id"),
											value.substring(stringPrefix.length()));
								}
							}
						}
					}
				} catch (IOException ex) {
					throw new SQLException("Failed to collect owned values", ex);
				}
			}
		}

		/**
		 * Journals a {@code NODE_REMOVED} row for every removed node, in multi-row
		 * inserts of {@link #JOURNAL_INSERT_CHUNK}. The rows are identical to what
		 * per-node removal writes, so every journal consumer — observation events,
		 * search index and suggestion cleanup, cluster replay and cache
		 * invalidation, referential-integrity validation — sees the same record a
		 * per-node removal produces.
		 */
		private void writeRemovedJournal(Map<String, String> removedPaths, Map<String, String> primaryTypes)
				throws SQLException {
			fJournalAffected = true;
			SessionIdentifier sessionIdentifier = getSessionIdentifier();
			String transactionId = sessionIdentifier.getTransactionIdentifier();
			String sessionId = sessionIdentifier.toString();
			String userId = fWorkspace.getSession().getUserID();
			long occurred = System.currentTimeMillis();
			long lastNanos = 0;

			List<Map.Entry<String, String>> rows = new ArrayList<>(removedPaths.entrySet());
			for (int offset = 0; offset < rows.size(); offset += JOURNAL_INSERT_CHUNK) {
				int end = Math.min(offset + JOURNAL_INSERT_CHUNK, rows.size());
				StringBuilder statement = new StringBuilder(
						"INSERT INTO jcr_journal (journal_id, transaction_id, session_id, event_occurred,"
						+ " event_type, item_id, item_path, primary_type, user_id) VALUES ");
				AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder()
						.put("tx", transactionId)
						.put("sid", sessionId)
						.put("occ", occurred)
						.put("evt", Event.NODE_REMOVED)
						.put("uid", userId)
						.build();
				for (int i = offset; i < end; i++) {
					Map.Entry<String, String> row = rows.get(i);
					long nanos = System.nanoTime();
					if (nanos <= lastNanos) {
						nanos = lastNanos + 1;
					}
					lastNanos = nanos;
					int n = i - offset;
					if (n > 0) {
						statement.append(", ");
					}
					statement.append("({{j").append(n).append("}}, {{tx}}, {{sid}}, {{occ}}, {{evt}},")
							.append(" {{i").append(n).append("}}, {{p").append(n).append("}}, {{y").append(n)
							.append("}}, {{uid}})");
					variables.put("j" + n, MessageFormat.format(
							"{0,number,00000000000000000000}-{1,number,00000000000000000000}",
							sessionIdentifier.getCreated(), nanos));
					variables.put("i" + n, row.getKey());
					variables.put("p" + n, row.getValue());
					String primaryType = primaryTypes.get(row.getKey());
					variables.put("y" + n, (primaryType != null) ? primaryType : "nt:base");
				}
				newUpdateBuilder(statement.toString()).setVariables(variables).build().execute();
			}
		}

		private List<List<String>> chunked(List<String> ids) {
			if (ids.isEmpty()) {
				return List.of();
			}
			if (ids.size() <= REMOVE_TREE_CHUNK) {
				return List.of(ids);
			}
			List<List<String>> chunks = new ArrayList<>();
			for (int i = 0; i < ids.size(); i += REMOVE_TREE_CHUNK) {
				chunks.add(ids.subList(i, Math.min(i + REMOVE_TREE_CHUNK, ids.size())));
			}
			return chunks;
		}

		/**
		 * Mirrors a property row's reference-typed values into
		 * {@code jcr_references} within the current transaction. The property's
		 * rows are always cleared first, so value updates and type changes never
		 * leave stale entries behind.
		 */
		private void syncReferences(String propertyItemId, String parentItemId, int propertyType,
				Object[] propertyValues) throws IOException, SQLException {
			newUpdateBuilder("DELETE FROM jcr_references WHERE item_id = {{id}}")
					.setVariable("id", propertyItemId).build().execute();
			if (propertyType != PropertyType.REFERENCE && propertyType != PropertyType.WEAKREFERENCE) {
				return;
			}

			Set<String> targets = new LinkedHashSet<>();
			for (Object e : propertyValues) {
				QName propertyValue = QName.valueOf((String) e);
				if (JcrValue.STRING_NS_URI.equals(propertyValue.getNamespaceURI())) {
					targets.add(propertyValue.getLocalPart());
				}
			}
			for (String target : targets) {
				newUpdateBuilder("INSERT INTO jcr_references (item_id, parent_item_id, property_type, target_item_id)"
						+ " VALUES ({{itemId}}, {{parentId}}, {{type}}, {{target}})")
						.setVariable("itemId", propertyItemId).setVariable("parentId", parentItemId)
						.setVariable("type", propertyType).setVariable("target", target)
						.build().execute();
			}
		}

		public void moveNode(String srcAbsPath, String destAbsPath)
				throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(srcAbsPath)) {
				throw new PathNotFoundException("Source path must not be null or empty.");
			}
			if (Strings.isEmpty(destAbsPath)) {
				throw new PathNotFoundException("Destination path must not be null or empty.");
			}

			try {
				getNode(destAbsPath);
				throw new ItemExistsException("An item '" + destAbsPath + "' already exists.");
			} catch (PathNotFoundException ignore) {
			}

			JcrPath srcPath = getResolved(JcrPath.valueOf(srcAbsPath));
			JcrPath destPath = getResolved(JcrPath.valueOf(destAbsPath));
			AdaptableMap<String, Object> srcItem = getNode(srcPath.toString());
			AdaptableMap<String, Object> destParentItem = getNode(destPath.getParent().toString());

			if (adaptTo(AccessControlStore.class).hasEntriesUnder(srcPath.toString())) {
				// Items carrying access control entries change their paths.
				setAccessControlAffected();
			}

			if (!srcPath.getParent().toString().equals(destPath.getParent().toString())) {
				itemsEntity().updateByPrimaryKey(
						AdaptableMap.<String, Object>newBuilder().put("item_id", srcItem.getString("item_id"))
								.put("item_name", destPath.getName().toString()).put("item_path", destPath.toString())
								.put("parent_item_id", destParentItem.getString("item_id")).build())
						.execute();
			}

			if (!srcPath.getName().toString().equals(destPath.getName().toString())) {
				itemsEntity().updateByPrimaryKey(AdaptableMap.<String, Object>newBuilder()
						.put("item_id", srcItem.getString("item_id")).put("item_name", destPath.getName().toString())
						.put("item_path", destPath.toString()).build()).execute();
			}
			// Keep the path-uniqueness key in step with the new path.
			syncActivePath(srcItem.getString("item_id"));

			markDirty(srcItem.getString("item_id"));
			moveChildNodes(srcItem.getString("item_id"));

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder().put("event_occurred", System.currentTimeMillis())
					.put("event_type", Event.NODE_MOVED)
					.put("item_id", srcItem.getString("item_id"))
					.put("item_path", destPath.toString())
					.put("primary_type", getPrimaryType(srcItem.getString("item_id")))
					.put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null)
					.put("event_info", Activator.getDefault().toJSON(Map.of(
							"source_path", srcPath.toString(),
							"destination_path", destPath.toString())))
					.build());
		}

		private void moveChildNodes(String parentId) throws IOException, SQLException, RepositoryException {
			AdaptableMap<String, Object> parentItemData = getNodeByIdentifier(parentId);
			try (Query.Result result = listNodes(parentId, null, 0)) {
				for (AdaptableMap<String, Object> itemData : result) {
					String name = JcrPath.valueOf(itemData.getString("item_path")).getName().toString();
					String path = JcrPath.valueOf(parentItemData.getString("item_path")).resolve(name).toString();
					itemsEntity().updateByPrimaryKey(
							AdaptableMap.<String, Object>newBuilder().put("item_id", itemData.getString("item_id"))
									.put("item_name", name).put("item_path", path).build())
							.execute();
					// Keep the path-uniqueness key in step with the new path. listNodes
					// also returns rows soft-deleted earlier in this transaction; the
					// recompute leaves their active_path NULL.
					syncActivePath(itemData.getString("item_id"));

					markDirty(itemData.getString("item_id"));
					moveChildNodes(itemData.getString("item_id"));
				}
			}
		}

		public AdaptableMap<String, Object> getProperty(String id, String relPath)
				throws IOException, SQLException, RepositoryException {
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

			try (Query.Result result = propertiesEntity()
					.find(AdaptableMap.<String, Object>newBuilder().put("parent_item_id", id)
							.put("item_name", itemName.toString()).put("is_deleted", Boolean.FALSE).build())
					.setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					throw new PathNotFoundException(id + "/" + relPath);
				}

				return i.next();
			}
		}

		public AdaptableMap<String, Object> setProperty(String id, String name, int type, Value value)
				throws IOException, SQLException, RepositoryException {
			return setProperty(id, name, type, false, value);
		}

		public AdaptableMap<String, Object> setProperty(String id, String name, int type, Value[] values)
				throws IOException, SQLException, RepositoryException {
			return setProperty(id, name, type, true, values);
		}

		public AdaptableMap<String, Object> setProperty(String id, String relPath, int type, boolean multiple,
				Value... values) throws IOException, SQLException, RepositoryException {
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
						.put("item_id", params.getItemId()).put("item_name", params.getItemName())
						.put("parent_item_id", params.getParentItemId()).put("property_type", params.getPropertyType())
						.put("property_value", params.getPropertyValues()).put("is_multiple", params.isMultiple())
						.build();

				AdaptableMap<String, Object> existing = null;
				try (Query.Result result = propertiesEntity().find(AdaptableMap.<String, Object>newBuilder()
						.put("item_id", params.getItemId()).put("is_deleted", false).build()).setOffset(0).setLimit(1)
						.execute()) {
					Iterator<AdaptableMap<String, Object>> i = result.iterator();
					if (i.hasNext()) {
						existing = i.next();
					}
				}

				if (existing == null) {
					propertiesEntity().deleteByPrimaryKey(r).execute();
					propertiesEntity().create(r).execute();
					syncReferences(params.getItemId(), id, params.getPropertyType(), params.getPropertyValues());

					for (Map.Entry<String, JcrBinary> e : params.getBinaries()) {
						files().createFile(e.getKey(), e.getValue());
					}

					markDirty(id);

					journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
							.put("event_occurred", System.currentTimeMillis()).put("event_type", Event.PROPERTY_ADDED)
							.put("item_id", id).put("item_path", getPath(id)).put("primary_type", getPrimaryType(id))
							.put("property_name", params.getItemName())
							.put("user_id", fWorkspace.getSession().getUserID()).put("user_data", null)
							.put("event_info", null).build());
				} else {
					for (QName propertyValue : Arrays.stream(existing.getObjectArray("property_value"))
							.map(e -> QName.valueOf((String) e)).toArray(QName[]::new)) {
						if (JcrValue.BINARY_NS_URI.equals(propertyValue.getNamespaceURI())) {
							files().deleteFile(propertyValue.getLocalPart());
						}
					}

					propertiesEntity().updateByPrimaryKey(r).execute();
					syncReferences(params.getItemId(), id, params.getPropertyType(), params.getPropertyValues());

					for (Map.Entry<String, JcrBinary> e : params.getBinaries()) {
						files().createFile(e.getKey(), e.getValue());
					}

					markDirty(id);

					journal().writeJournal(AdaptableMap.<String, Object>newBuilder()
							.put("event_occurred", System.currentTimeMillis()).put("event_type", Event.PROPERTY_CHANGED)
							.put("item_id", id).put("item_path", getPath(id)).put("primary_type", getPrimaryType(id))
							.put("property_name", params.getItemName())
							.put("user_id", fWorkspace.getSession().getUserID()).put("user_data", null)
							.put("event_info", null).build());
				}
			}

			return getProperty(id, relPath);
		}

		public JcrValue[] getPropertyValues(String id, String relPath)
				throws IOException, SQLException, RepositoryException {
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

		public AdaptableMap<String, Object> removeProperty(String id, String relPath)
				throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}
			if (Strings.isEmpty(relPath)) {
				throw new PathNotFoundException("Property name must not be null or empty.");
			}

			AdaptableMap<String, Object> pk = AdaptableMap.<String, Object>newBuilder()
					.put("item_id", new PropertyIdentifier(id, relPath).toString()).build();

			AdaptableMap<String, Object> existing = null;
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

			propertiesEntity().updateByPrimaryKey(
					AdaptableMap.<String, Object>newBuilder().putAll(pk).put("is_deleted", Boolean.TRUE).build())
					.execute();
			newUpdateBuilder("DELETE FROM jcr_references WHERE item_id = {{id}}")
					.setVariable("id", pk.getString("item_id")).build().execute();

			markDirty(id);

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder().put("event_occurred", System.currentTimeMillis())
					.put("event_type", Event.PROPERTY_REMOVED).put("item_id", id).put("item_path", getPath(id))
					.put("primary_type", getPrimaryType(id)).put("property_name", getResolved(relPath))
					.put("user_id", fWorkspace.getSession().getUserID()).put("user_data", null).put("event_info", null)
					.build());

			return null;
		}

		public AdaptableMap<String, Object> getLock(String absPath)
				throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(absPath) || !absPath.startsWith("/")) {
				throw new PathNotFoundException("Invalid path: " + absPath);
			}

			List<String> paths = new ArrayList<>();
			for (JcrPath path = getResolved(JcrPath.valueOf(absPath)); path != null; path = path.getParent()) {
				paths.add(path.toString());
			}

			// One statement over the whole ancestor chain instead of a node lookup
			// plus a lock lookup per level. The nearest matching lock wins — a lock
			// on the node itself always matches, an ancestor's only when deep —
			// exactly as the per-level walk did.
			StringBuilder statement = new StringBuilder()
					.append("SELECT l.*, i.item_path FROM jcr_locks l")
					.append(" JOIN jcr_items i ON (l.item_id = i.item_id)")
					.append(" WHERE i.item_path IN ({{paths;list}}) AND i.is_deleted = FALSE");
			AdaptableMap<String, Object> variables = AdaptableMap.<String, Object>newBuilder().put("paths", paths)
					.build();

			AdaptableMap<String, Object> nearest = null;
			int nearestDepth = Integer.MAX_VALUE;
			try (Query.Result result = newQueryBuilder(statement.toString()).setVariables(variables).build()
					.setOffset(0).execute()) {
				for (AdaptableMap<String, Object> lockData : result) {
					int depth = paths.indexOf(lockData.getString("item_path"));
					if (depth < 0 || depth >= nearestDepth) {
						continue;
					}
					if (depth > 0 && !lockData.getBoolean("is_deep")) {
						continue;
					}
					nearest = lockData;
					nearestDepth = depth;
				}
			}
			if (nearest == null) {
				throw new LockException("Node '" + absPath + "' is not locked.");
			}
			return nearest;
		}

		public String unlock(String absPath) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(absPath) || !absPath.startsWith("/")) {
				throw new PathNotFoundException("Invalid path: " + absPath);
			}

			AdaptableMap<String, Object> lockData;
			try {
				lockData = getLock(absPath);
			} catch (LockException ignore) {
				throw new LockException("Node '" + absPath + "' is not locked.");
			}

			AdaptableMap<String, Object> itemData = getNode(absPath);
			String id = itemData.getString("item_id");

			if (!lockData.getString("item_id").equals(id)) {
				throw new LockException(
						"Node '" + absPath + "' is locked on node '" + itemData.getString("item_path") + "'.");
			}

			if (!Arrays.asList(fWorkspace.getLockManager().getLockTokens())
					.contains(lockData.getString("lock_token"))) {
				throw new LockException("Could not unlock node '" + absPath + "'.");
			}

			int count = locksEntity().deleteByPrimaryKey(lockData).execute();
			if (count == 0) {
				throw new LockException("Could not unlock node '" + absPath + "'.");
			}

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder().put("event_occurred", System.currentTimeMillis())
					.put("event_type", Event.UNLOCKED).put("item_id", id).put("item_path", getPath(id))
					.put("primary_type", getPrimaryType(id)).put("user_id", fWorkspace.getSession().getUserID())
					.put("user_data", null).put("event_info", null).build());

			removeProperty(id, Property.JCR_LOCK_OWNER);
			removeProperty(id, Property.JCR_LOCK_IS_DEEP);

			return lockData.getString("lock_token");
		}

		public void unlockSessionScopedLocks() throws IOException, SQLException, RepositoryException {
			try (Query.Result result = newQueryBuilder(
					"SELECT item_id FROM jcr_locks WHERE session_id = {{session_id}}")
					.setVariable("session_id", getSessionIdentifier().toString()).build().execute()) {
				for (AdaptableMap<String, Object> r : result) {
					String absPath = getPath(r.getString("item_id"));
					unlock(absPath);
				}
			}
		}

		public AdaptableMap<String, Object> refreshLock(String absPath)
				throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(absPath) || !absPath.startsWith("/")) {
				throw new PathNotFoundException("Invalid path: " + absPath);
			}

			AdaptableMap<String, Object> lockData;
			try {
				lockData = getLock(absPath);
			} catch (LockException ignore) {
				throw new LockException("Node '" + absPath + "' is not locked.");
			}

			AdaptableMap<String, Object> itemData = getNode(absPath);
			String id = itemData.getString("item_id");

			if (!lockData.getString("item_id").equals(id)) {
				throw new LockException(
						"Node '" + absPath + "' is locked on node '" + itemData.getString("item_path") + "'.");
			}

			lockData.put("lock_created", System.currentTimeMillis());
			try {
				int count = locksEntity().update(
						AdaptableMap.<String, Object>newBuilder().put("lock_created", lockData.getLong("lock_created"))
								.build(),
						AdaptableMap.<String, Object>newBuilder().put("item_id", lockData.getString("item_id")).build())
						.execute();
				if (count == 0) {
					throw new LockException("Could not refresh lock on node '" + absPath + "'.");
				}
			} catch (SQLException ignore) {
				throw new LockException("Node '" + absPath + "' is already locked.");
			}

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder().put("event_occurred", System.currentTimeMillis())
					.put("event_type", Event.LOCK_REFRESHED).put("item_id", id)
					.put("item_path", itemData.getString("item_path")).put("primary_type", getPrimaryType(id))
					.put("user_id", fWorkspace.getSession().getUserID()).put("user_data", null).put("event_info", null)
					.build());

			return lockData;
		}

		public void removeLock(String id) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			newUpdateBuilder("DELETE FROM jcr_locks WHERE item_id = {{id}}").setVariable("id", id).build().execute();
		}

		public void setAccessControlPolicy(String id, JcrAccessControlEntry... aces)
				throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}
			if (aces == null) {
				throw new AccessControlException("Access control entries must not be null.");
			}

			setAccessControlAffected();

			newUpdateBuilder("DELETE FROM jcr_aces WHERE item_id = {{id}}").setVariable("id", id).build().execute();

			int rowNo = 0;
			for (JcrAccessControlEntry ace : aces) {
				acesEntity().create(AdaptableMap.<String, Object>newBuilder().put("item_id", id).put("row_no", ++rowNo)
						.put("principal_name", ace.getPrincipal().getName())
						.put("is_group", (ace.getPrincipal() instanceof GroupPrincipal))
						.put("privilege_names", Arrays.stream(ace.getPrivileges()).map(e -> e.getName()).toArray())
						.put("is_allow", ace.isAllow()).build()).execute();
			}

			markDirty(id);

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder().put("event_occurred", System.currentTimeMillis())
					.put("event_type", Event.ACCESS_CONTROL_POLICY_CHANGED).put("item_id", id)
					.put("item_path", getPath(id)).put("primary_type", getPrimaryType(id))
					.put("user_id", fWorkspace.getSession().getUserID()).put("user_data", null).put("event_info", null)
					.build());
		}

		public void removeAccessControlPolicy(String id) throws IOException, SQLException, RepositoryException {
			if (Strings.isEmpty(id)) {
				throw new ItemNotFoundException("Identifier must not be null or empty.");
			}

			if (adaptTo(AccessControlStore.class).hasEntriesAt(getPath(id))) {
				setAccessControlAffected();
			}

			newUpdateBuilder("DELETE FROM jcr_aces WHERE item_id = {{id}}").setVariable("id", id).build().execute();

			markDirty(id);

			journal().writeJournal(AdaptableMap.<String, Object>newBuilder().put("event_occurred", System.currentTimeMillis())
					.put("event_type", Event.ACCESS_CONTROL_POLICY_REMOVED).put("item_id", id)
					.put("item_path", getPath(id)).put("primary_type", getPrimaryType(id))
					.put("user_id", fWorkspace.getSession().getUserID()).put("user_data", null).put("event_info", null)
					.build());
		}

		public void checkOrphanNodes(OrphanMonitor monitor) throws IOException, SQLException {
			if (monitor.isCancelled()) {
				return;
			}

			String sql = """
					SELECT i.*
					FROM jcr_items i
					WHERE i.parent_item_id IS NOT NULL
					  AND i.parent_item_id <> ''
					  AND NOT EXISTS (
					      SELECT 1 FROM jcr_items p WHERE p.item_id = i.parent_item_id
					  )
					ORDER BY i.item_path
					""";
			try (Query.Result result = Optional.ofNullable(monitor.getQueryCustomizer()).orElse(q -> q)
					.apply(newQueryBuilder(sql).build()).execute()) {
				for (AdaptableMap<String, Object> r : result) {
					if (monitor.isCancelled()) {
						break;
					}

					Optional.ofNullable(monitor.getNodeConsumer()).orElse(record -> {}).accept(r);
				}
			}
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

		private PropertyParameters(String itemId, String relPath, int type, boolean multiple, Value... values)
				throws IOException, RepositoryException {
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
						propertyValue = new QName(JcrValue.BINARY_NS_URI, UUID.randomUUID().toString(),
								XMLConstants.DEFAULT_NS_PREFIX);
						binary = fCloser.register((JcrBinary) ((JcrValue) value).adapt(Binary.class));
					} else if (type == PropertyType.BOOLEAN || type == PropertyType.DATE || type == PropertyType.DECIMAL
							|| type == PropertyType.DOUBLE || type == PropertyType.LONG || type == PropertyType.STRING
							|| type == PropertyType.NAME || type == PropertyType.PATH || type == PropertyType.URI) {
						String v = ((JcrValue) value).adapt(String.class);
						if (v == null) {
							propertyValue = null;
						} else {
							if (v.length() > 3072) {
								propertyValue = new QName(JcrValue.BINARY_NS_URI, UUID.randomUUID().toString(),
										XMLConstants.DEFAULT_NS_PREFIX);
								binary = fCloser
										.register(JcrBinary.create(v.getBytes(StandardCharsets.UTF_8.toString())));
							} else {
								propertyValue = new QName(JcrValue.STRING_NS_URI, v, XMLConstants.DEFAULT_NS_PREFIX);
							}
						}
					} else if (type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE) {
						Node node = ((JcrValue) value).adapt(Node.class);
						if (node == null) {
							propertyValue = null;
						} else {
							String v = node.getIdentifier();
							propertyValue = new QName(JcrValue.STRING_NS_URI, v, XMLConstants.DEFAULT_NS_PREFIX);
						}
					} else {
						throw new IllegalArgumentException("Invalid property type: " + type);
					}
				} catch (UnadaptableValueException ex) {
					throw Cause.create(ex).wrap(ValueFormatException.class);
				}

				if (propertyValue == null) {
					throw new ValueFormatException("The value of property '" + relPath + "' cannot be converted to a "
							+ PropertyType.nameFromValue(type) + ".");
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
		private NamespacesQuery() {
		}

		private Entity fNamespacesEntity;

		private Entity namespacesEntity() throws SQLException {
			if (fNamespacesEntity == null) {
				fNamespacesEntity = Entity.newBuilder(getConnection()).setName("jcr_namespaces").build();
			}
			return fNamespacesEntity;
		}

		public String getPrefix(String uri) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder(
					"SELECT namespace_prefix FROM jcr_namespaces WHERE namespace_uri = {{uri}}").setVariable("uri", uri)
					.build().setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					return null;
				}

				return i.next().getString("namespace_prefix");
			}
		}

		public String[] getPrefixes() throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder(
					"SELECT namespace_prefix FROM jcr_namespaces ORDER BY namespace_prefix").build().setOffset(0)
					.execute()) {
				List<String> l = new ArrayList<>();
				for (AdaptableMap<String, Object> r : result) {
					l.add(r.getString("namespace_prefix"));
				}
				return l.toArray(String[]::new);
			}
		}

		public String getURI(String prefix) throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder(
					"SELECT namespace_uri FROM jcr_namespaces WHERE namespace_prefix = {{prefix}}")
					.setVariable("prefix", prefix).build().setOffset(0).setLimit(1).execute()) {
				Iterator<AdaptableMap<String, Object>> i = result.iterator();
				if (!i.hasNext()) {
					return null;
				}

				return i.next().getString("namespace_uri");
			}
		}

		public String[] getURIs() throws IOException, SQLException {
			try (Query.Result result = newQueryBuilder(
					"SELECT namespace_uri FROM jcr_namespaces ORDER BY namespace_uri").build().setOffset(0).execute()) {
				List<String> l = new ArrayList<>();
				for (AdaptableMap<String, Object> r : result) {
					l.add(r.getString("namespace_uri"));
				}
				return l.toArray(String[]::new);
			}
		}

		public void registerNamespace(String prefix, String uri) throws IOException, SQLException {
			namespacesEntity().create(AdaptableMap.<String, Object>newBuilder().put("namespace_prefix", prefix)
					.put("namespace_uri", uri).build()).execute();
		}

		public void unregisterNamespace(String prefix) throws IOException, SQLException {
			namespacesEntity()
					.deleteByPrimaryKey(
							AdaptableMap.<String, Object>newBuilder().put("namespace_prefix", prefix).build())
					.execute();
		}
	}

}

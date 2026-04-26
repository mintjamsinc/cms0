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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.jcr.LoginException;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.security.LoginTimedOutException;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.jcr.internal.nodetype.JcrNodeType;
import org.mintjams.rt.jcr.internal.observation.JournalObserver;
import org.mintjams.rt.jcr.internal.security.ServicePrincipal;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.LockFile;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.sql.Query;
import org.mintjams.tools.sql.Update;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class JcrWorkspaceProvider implements Closeable, Adaptable {

	public static final String SYSTEM_WORKSPACE_NAME = "system";

	private final JcrWorkspaceProviderConfiguration fConfig;
	private final JcrRepository fRepository;
	private final Closer fCloser = Closer.create();
	private ConnectionPool fConnectionPool;
	private SearchIndex fSearchIndex;
	private JournalObserver fJournalObserver;
	private WorkspaceCleaner fWorkspaceCleaner;
	private WorkspaceOrphanMonitor fWorkspaceOrphanMonitor;
	private boolean fLive;
	private final List<JcrWorkspace> fActiveSessions = new ArrayList<>();

	private JcrWorkspaceProvider(String workspaceName, JcrRepository repository) {
		fConfig = new JcrWorkspaceProviderConfiguration(workspaceName);
		fRepository = repository;
	}

	public static JcrWorkspaceProvider create(String name, JcrRepository repository) {
		return new JcrWorkspaceProvider(name, repository);
	}

	public String getWorkspaceName() {
		return fConfig.getWorkspaceName();
	}

	public Path getWorkspacePath() {
		return fRepository.getConfiguration().getWorkspaceRootPath().resolve(getWorkspaceName()).normalize();
	}

	private Path getWorkspaceLockPath() {
		return getWorkspacePath().resolve(".lock").normalize();
	}

	public Path getJcrBinPath() {
		return getWorkspacePath().resolve("var/jcr/bin").normalize();
	}

	public Path getJcrDataPath() {
		return getWorkspacePath().resolve("var/jcr/data").normalize();
	}

	public Path getWorkspaceEtcPath() {
		return getWorkspacePath().resolve("etc").normalize();
	}

	public Path getWorkspaceSearchPath() {
		return getWorkspacePath().resolve("var/search").normalize();
	}

	public synchronized JcrWorkspaceProvider open() throws IOException {
		Activator.getDefault().getLogger(getClass()).info("Start the JCR workspace '" + getWorkspaceName() + "'.");
		fCloser.add(LockFile.create(getWorkspaceLockPath()));

		fConfig.load();

		fConnectionPool = fCloser.register(new ConnectionPool());
		fConnectionPool.open();

		prepareInitialData();

		boolean needsBuildSearchIndex = false;
		if (!Files.exists(getWorkspaceSearchPath())) {
			needsBuildSearchIndex = true;
		}
		fSearchIndex = fCloser.register(Activator.getDefault().getSearchIndexFactory().createSearchIndexConfiguration()
				.setDataPath(getWorkspaceSearchPath()).setConfigPath(getWorkspaceEtcPath().resolve("search"))
				.createSearchIndex());

		fJournalObserver = fCloser.register(JournalObserver.create(this));
		fJournalObserver.open();

		prepareDefaultNodes();

		fWorkspaceCleaner = fCloser.register(WorkspaceCleaner.create(this));
		fWorkspaceCleaner.open();

		WorkspaceGarbageCollection workspaceGarbageCollection = fCloser
				.register(WorkspaceGarbageCollection.create(this));
		workspaceGarbageCollection.open();

		fWorkspaceOrphanMonitor = fCloser.register(WorkspaceOrphanMonitor.create(this));
		fWorkspaceOrphanMonitor.open();

		removeOrphanNodes();

		if (needsBuildSearchIndex) {
			Activator.getDefault().getLogger(getClass()).info("Creating the JCR search index.");
			try (JcrWorkspace workspace = createSession(new SystemPrincipal())) {
				for (NodeIterator i = workspace.getSession().getRootNode().getNodes(); i.hasNext();) {
					fJournalObserver.buildSearchIndex(i.nextNode());
				}
			} catch (RepositoryException ex) {
				throw Cause.create(ex).wrap(IOException.class);
			}
			Activator.getDefault().getLogger(getClass()).info("JCR search index has been created.");
		}

		fLive = true;
		Activator.getDefault().getLogger(getClass())
				.info("JCR workspace '" + getWorkspaceName() + "' has been started.");
		return this;
	}

	protected Connection getConnection(Principal principal) throws SQLException {
		return fConnectionPool.getConnection(principal);
	}

	private void prepareInitialData() throws IOException {
		try (Connection connection = getConnection(new SystemPrincipal())) {
			try {
				for (String statement : Strings.readAll(getClass().getResourceAsStream("workspace-prepare.sql"),
						StandardCharsets.UTF_8.toString()).toString().split(";")) {
					statement = statement.trim();
					if (Strings.isEmpty(statement)) {
						continue;
					}

					Update.newBuilder(connection).setStatement(statement).build().execute();
				}
				connection.commit();
			} catch (Throwable ex) {
				try {
					connection.rollback();
				} catch (Throwable ignore) {
				}
				throw ex;
			}

			// Fix root node format
			do {
				try (Query.Result result = Query.newBuilder(connection)
						.setStatement("SELECT * FROM jcr_items WHERE item_path = '/' AND parent_item_id IS NOT NULL")
						.build().setOffset(0).setLimit(1).execute()) {
					if (result.iterator().hasNext()) {
						break;
					}

					Update.newBuilder(connection)
							.setStatement("UPDATE jcr_items SET parent_item_id = NULL WHERE item_path = '/'")
							.build().execute();
					connection.commit();
				} catch (Throwable ex) {
					try {
						connection.rollback();
					} catch (Throwable ignore) {}
					throw ex;
				}
			} while (false);

			// Migrate existing data
			do {
				// Check whether the 'is_system' column exists
				AdaptableMap<String, Object> checkRow;
				try (Query.Result result = Query.newBuilder(connection)
						.setStatement("SELECT * FROM jcr_items ORDER BY item_path")
						.build().setOffset(0).setLimit(1).execute()) {
					if (!result.iterator().hasNext()) {
						break;
					}

					checkRow = result.iterator().next();
					if (checkRow.containsKey("is_system") && checkRow.get("is_system") != null) {
						break;
					}
				}

				Activator.getDefault().getLogger(getClass())
						.info("Old JCR workspace data format detected. Starting migration process...");

				// Add the 'is_system' column and set its value
				try {
					if (!checkRow.containsKey("is_system")) {
						Update.newBuilder(connection)
								.setStatement("ALTER TABLE jcr_items ADD COLUMN is_system BOOLEAN")
								.build().execute();
					}

					try (Query.Result result = Query.newBuilder(connection)
							.setStatement("SELECT * FROM jcr_items ORDER BY item_path")
							.build().execute()) {
						for (AdaptableMap<String, Object> r : result) {
							Update.newBuilder(connection)
									.setStatement("UPDATE jcr_items SET is_system = {{isSystem}} WHERE item_id = {{itemId}}")
									.setVariable("isSystem", JCRs.isSystemPath(r.getString("item_path")))
									.setVariable("itemId", r.getString("item_id"))
									.build().execute();
						}
					}

					connection.commit();

					Update.newBuilder(connection)
							.setStatement("ALTER TABLE jcr_items ALTER COLUMN is_system SET DEFAULT FALSE")
							.build().execute();
					Update.newBuilder(connection)
							.setStatement("ALTER TABLE jcr_items ALTER COLUMN is_system SET NOT NULL")
							.build().execute();
				} catch (Throwable ex) {
					try {
						connection.rollback();
					} catch (Throwable ignore) {
					}
					throw ex;
				}

				Activator.getDefault().getLogger(getClass())
						.info("JCR workspace data migration process has been completed.");
			} while (false);

			// Migrate jcr:system and jcr:versionStorage node types from nt:folder to custom types
			do {
				String oldValue = "{http://www.mintjams.jp/jcr/1.0/value/string}nt:folder";

				// Check if jcr:system still uses nt:folder
				boolean needsMigration = false;
				try (Query.Result result = Query.newBuilder(connection)
						.setStatement("SELECT p.property_value FROM jcr_properties p"
								+ " INNER JOIN jcr_items i ON i.item_id = p.parent_item_id"
								+ " WHERE i.item_path = '/jcr:system'"
								+ " AND p.item_name = 'jcr:primaryType'"
								+ " AND p.is_deleted = FALSE")
						.build().setOffset(0).setLimit(1).execute()) {
					Iterator<AdaptableMap<String, Object>> iter = result.iterator();
					if (iter.hasNext()) {
						AdaptableMap<String, Object> row = iter.next();
						Object[] values = row.getObjectArray("property_value");
						if (values != null && values.length > 0 && oldValue.equals(values[0])) {
							needsMigration = true;
						}
					}
				}

				if (!needsMigration) {
					break;
				}

				Activator.getDefault().getLogger(getClass())
						.info("Migrating version storage node types from nt:folder to mi:system/mi:versionStorage...");

				try {
					// Update jcr:system to mi:system
					Update.newBuilder(connection)
							.setStatement("UPDATE jcr_properties SET property_value = {{newValue}}"
									+ " WHERE item_name = 'jcr:primaryType'"
									+ " AND parent_item_id IN (SELECT item_id FROM jcr_items WHERE item_path = '/jcr:system')")
							.setVariable("newValue", new String[]{"{http://www.mintjams.jp/jcr/1.0/value/string}mi:system"})
							.build().execute();

					// Update jcr:versionStorage to mi:versionStorage
					Update.newBuilder(connection)
							.setStatement("UPDATE jcr_properties SET property_value = {{newValue}}"
									+ " WHERE item_name = 'jcr:primaryType'"
									+ " AND parent_item_id IN (SELECT item_id FROM jcr_items WHERE item_path = '/jcr:system/jcr:versionStorage')")
							.setVariable("newValue", new String[]{"{http://www.mintjams.jp/jcr/1.0/value/string}mi:versionStorage"})
							.build().execute();

					connection.commit();
				} catch (Throwable ex) {
					try {
						connection.rollback();
					} catch (Throwable ignore) {
					}
					throw ex;
				}

				Activator.getDefault().getLogger(getClass())
						.info("Version storage node type migration has been completed.");
			} while (false);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private void prepareDefaultNodes() throws IOException {
		try (JcrWorkspace workspace = createSession(new SystemPrincipal())) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);

			for (Map<String, Object> definition : fConfig.getDefaultNodes()) {
				JcrPath path = JcrPath.valueOf((String) definition.get("path"));
				try {
					workspace.getSession().getNode(path.toString());
				} catch (PathNotFoundException pathNotFound) {
					try {
						if (!path.isRoot()) {
							JCRs.getOrCreateFolder(path.getParent(), workspace.getSession());
						}
						workspaceQuery.items().createNode(definition);
						workspaceQuery.commit();
					} catch (Throwable ex) {
						try {
							workspaceQuery.rollback();
						} catch (Throwable ignore) {
						}
						throw Cause.create(ex).wrap(IOException.class);
					}
				}
			}

			// /
			try {
				workspace.getSession().getRootNode();
			} catch (PathNotFoundException pathNotFound) {
				try {
					workspaceQuery.items().createRootNode();
					workspaceQuery.commit();
				} catch (Throwable ex) {
					try {
						workspaceQuery.rollback();
					} catch (Throwable ignore) {
					}
					throw Cause.create(ex).wrap(IOException.class);
				}
			}

			// /jcr:system
			JcrPath systemPath = JcrPath.valueOf("/" + JcrNode.JCR_SYSTEM_NAME);
			try {
				workspace.getSession().getNode(systemPath.toString());
			} catch (PathNotFoundException pathNotFound) {
				try {
					workspaceQuery.items().createNode(systemPath.toString(), JcrNodeType.MI_SYSTEM_NAME);
					workspaceQuery.commit();
				} catch (Throwable ex) {
					try {
						workspaceQuery.rollback();
					} catch (Throwable ignore) {
					}
					throw Cause.create(ex).wrap(IOException.class);
				}
			}

			// /jcr:system/jcr:versionStorage
			JcrPath versionStoragePath = systemPath.resolve(JcrNode.JCR_VERSION_STORAGE_NAME);
			try {
				workspace.getSession().getNode(versionStoragePath.toString());
			} catch (PathNotFoundException pathNotFound) {
				try {
					workspaceQuery.items().createNode(versionStoragePath.toString(), JcrNodeType.MI_VERSION_STORAGE_NAME);
					workspaceQuery.commit();
				} catch (Throwable ex) {
					try {
						workspaceQuery.rollback();
					} catch (Throwable ignore) {
					}
					throw Cause.create(ex).wrap(IOException.class);
				}
			}
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private void removeOrphanNodes() throws IOException {
		try (JcrWorkspace workspace = createSession(new SystemPrincipal())) {
			WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);

			Consumer<AdaptableMap<String, Object>> nodeConsumer = new Consumer<>() {
				@Override
				public void accept(AdaptableMap<String, Object> r) {
					try {
						JcrNode node = JcrNode.class.cast(workspace.getSession().getNodeByIdentifier(r.getString("item_id")));
						if (JcrPath.valueOf(node.getPath()).isRoot()) {
							return;
						}

						String log = """
								Removing orphan node:
								  * Workspace: %s
								  * Identifier: %s
								  * Path: %s
								  * IsDeleted: %s
								  * IsSystem: %s
								""".formatted(
										workspace.getName(),
										r.getString("item_id"),
										r.getString("item_path"),
										r.getBoolean("is_deleted"),
										r.getBoolean("is_system"));
						Activator.getDefault().getLogger(JcrWorkspaceProvider.class).warn(log);

						node.remove(options -> {
							options.put("force", true);
							return options;
						});

						Activator.getDefault().getLogger(JcrWorkspaceProvider.class).warn("Orphan node has been removed: " + r.getString("item_id"));
					} catch (Throwable ex) {
						throw Cause.create(ex).wrap(IllegalStateException.class);
					}
				}
			};

			try {
				workspaceQuery.items().checkOrphanNodes(new WorkspaceQuery.OrphanMonitor() {
					@Override
					public boolean isCancelled() {
						return false;
					}

					@Override
					public Function<Query, Query> getQueryCustomizer() {
						return null;
					}

					@Override
					public Consumer<AdaptableMap<String, Object>> getNodeConsumer() {
						return nodeConsumer;
					}
				});

				workspace.getSession().save();
			} catch (Throwable ex) {
				try {
					workspace.getSession().refresh(false);
				} catch (Throwable ignore) {}
				throw ex;
			}
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	public boolean isLive() {
		return fLive;
	}

	public JcrWorkspace createSession(UserPrincipal principal) throws LoginException {
		synchronized (fActiveSessions) {
			if (!getWorkspaceName().equals(SYSTEM_WORKSPACE_NAME)) {
				long timeoutMillis = 10000;
				long started = System.currentTimeMillis();
				while (fActiveSessions.size() >= fRepository.getConfiguration().getMaxSessions()) {
					long millis = System.currentTimeMillis() - started + 1;
					if (millis > 0) {
						try {
							fActiveSessions.wait(millis);
						} catch (Throwable ignore) {
						}
					}
					if (System.currentTimeMillis() >= (started + timeoutMillis)) {
						throw new LoginTimedOutException("JCR session login timed out.");
					}
				}
			}

			JcrWorkspace workspace = JcrWorkspace.create(principal, this);
			try {
				workspace.open();
			} catch (Throwable ex) {
				try {
					workspace.close();
				} catch (Throwable ignore) {
				}
				throw new LoginException("JCR session could not be created.", ex);
			}
			fActiveSessions.add(workspace);
			return workspace;
		}
	}

	public void closeSession(JcrWorkspace workspace) {
		synchronized (fActiveSessions) {
			fActiveSessions.remove(workspace);
			try {
				fActiveSessions.notifyAll();
			} catch (Throwable ignore) {
			}
		}
	}

	@Override
	public synchronized void close() throws IOException {
		fLive = false;
		fCloser.close();
	}

	public JcrWorkspaceProviderConfiguration getConfiguration() {
		return fConfig;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(SearchIndex.class)) {
			return (AdapterType) fSearchIndex;
		}

		if (adapterType.equals(JournalObserver.class)) {
			return (AdapterType) fJournalObserver;
		}

		if (adapterType.equals(WorkspaceCleaner.class)) {
			return (AdapterType) fWorkspaceCleaner;
		}

		return Adaptables.getAdapter(fRepository, adapterType);
	}

	private class ConnectionPool implements Closeable {
		private boolean fCloseRequested;
		private HikariDataSource fUserDataSource;
		private HikariDataSource fSystemDataSource;
		private HikariDataSource fServiceDataSource;

		public ConnectionPool open() {
			if (fUserDataSource != null) {
				return this;
			}

			int numProcessors = Runtime.getRuntime().availableProcessors();
			int cacheSize = fRepository.getConfiguration().getCacheSize();
			int maxPoolSize = fRepository.getConfiguration().getMaxSessions();
			int minIdle = maxPoolSize / 2;
			if (minIdle > numProcessors) {
				minIdle = numProcessors;
			}

			// User data source
			{
				HikariConfig config = new HikariConfig();
				config.setJdbcUrl("jdbc:h2:" + getJcrDataPath().resolve("data").toAbsolutePath() + ";DB_CLOSE_DELAY=-1;CACHE_SIZE=" + cacheSize);
				config.setUsername("sa");
				config.setPassword("");
				config.setMaximumPoolSize(maxPoolSize);
				config.setMinimumIdle(minIdle);
				config.setConnectionTimeout(30000);
				config.setIdleTimeout(600000);
				config.setMaxLifetime(1800000);
				config.setAutoCommit(false);
				fUserDataSource = new HikariDataSource(config);
			}

			// System data source
			{
				HikariConfig config = new HikariConfig();
				config.setJdbcUrl("jdbc:h2:" + getJcrDataPath().resolve("data").toAbsolutePath() + ";DB_CLOSE_DELAY=-1;CACHE_SIZE=" + cacheSize);
				config.setUsername("sa");
				config.setPassword("");
				config.setMaximumPoolSize(maxPoolSize);
				config.setMinimumIdle(minIdle);
				config.setConnectionTimeout(30000);
				config.setIdleTimeout(600000);
				config.setMaxLifetime(1800000);
				config.setAutoCommit(false);
				fSystemDataSource = new HikariDataSource(config);
			}

			// Service data source
			{
				HikariConfig config = new HikariConfig();
				config.setJdbcUrl("jdbc:h2:" + getJcrDataPath().resolve("data").toAbsolutePath() + ";DB_CLOSE_DELAY=-1;CACHE_SIZE=" + cacheSize);
				config.setUsername("sa");
				config.setPassword("");
				config.setMaximumPoolSize(maxPoolSize);
				config.setMinimumIdle(minIdle);
				config.setConnectionTimeout(30000);
				config.setIdleTimeout(600000);
				config.setMaxLifetime(1800000);
				config.setAutoCommit(false);
				fServiceDataSource = new HikariDataSource(config);
			}

			return this;
		}

		public Connection getConnection(Principal principal) throws SQLException {
			Connection connection;
			if (principal instanceof SystemPrincipal) {
				// System sessions use the system data source
				connection = fSystemDataSource.getConnection();
			} else if (principal instanceof ServicePrincipal) {
				// Service sessions use the service data source
				connection = fServiceDataSource.getConnection();
			} else {
				// User sessions use the user data source
				connection = fUserDataSource.getConnection();
			}
			if (connection.getAutoCommit()) {
				throw new SQLException("Auto-commit mode is not allowed for JCR workspace connections.");
			}
			return connection;
		}

		@Override
		public void close() throws IOException {
			if (fCloseRequested) {
				return;
			}

			fCloseRequested = true;
			try {
				fUserDataSource.close();
			} catch (Throwable ignore) {}
			try {
				fSystemDataSource.close();
			} catch (Throwable ignore) {}
			try {
				fServiceDataSource.close();
			} catch (Throwable ignore) {}
			fCloseRequested = false;
		}
	}

}

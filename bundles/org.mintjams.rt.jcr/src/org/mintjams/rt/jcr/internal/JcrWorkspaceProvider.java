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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.jcr.LoginException;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.security.LoginTimedOutException;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.jcr.internal.observation.JournalObserver;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.LockFile;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.sql.Update;

public class JcrWorkspaceProvider implements Closeable, Adaptable {

	public static final String SYSTEM_WORKSPACE_NAME = "system";

	private final JcrWorkspaceProviderConfiguration fConfig;
	private final JcrRepository fRepository;
	private final Closer fCloser = Closer.create();
	private ConnectionPool fConnectionPool;
	private SearchIndex fSearchIndex;
	private JournalObserver fJournalObserver;
	private WorkspaceCleaner fWorkspaceCleaner;
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
				.setDataPath(getWorkspaceSearchPath())
				.setConfigPath(getWorkspaceEtcPath().resolve("search"))
				.createSearchIndex());

		fJournalObserver = fCloser.register(JournalObserver.create(this));
		fJournalObserver.open();

		prepareDefaultNodes();

		fWorkspaceCleaner = fCloser.register(WorkspaceCleaner.create(this));
		fWorkspaceCleaner.open();

		WorkspaceGarbageCollection workspaceGarbageCollection = fCloser.register(WorkspaceGarbageCollection.create(this));
		workspaceGarbageCollection.open();

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
		Activator.getDefault().getLogger(getClass()).info("JCR workspace '" + getWorkspaceName() + "' has been started.");
		return this;
	}

	protected Connection getConnection() throws SQLException {
		return fConnectionPool.getConnection();
	}

	private void prepareInitialData() throws IOException {
		try (Connection connection = getConnection()) {
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
				} catch (Throwable ignore) {}
				throw ex;
			}
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
							JCRs.mkdirs(path.getParent(), workspace.getSession());
						}
						workspaceQuery.items().createNode(definition);
						workspaceQuery.commit();
					} catch (Throwable ex) {
						try {
							workspaceQuery.rollback();
						} catch (Throwable ignore) {}
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
					} catch (Throwable ignore) {}
					throw Cause.create(ex).wrap(IOException.class);
				}
			}

			// /jcr:system
			JcrPath systemPath = JcrPath.valueOf("/" + JcrNode.JCR_SYSTEM_NAME);
			try {
				workspace.getSession().getNode(systemPath.toString());
			} catch (PathNotFoundException pathNotFound) {
				try {
					workspaceQuery.items().createNode(systemPath.toString(), NodeType.NT_FOLDER);
					workspaceQuery.commit();
				} catch (Throwable ex) {
					try {
						workspaceQuery.rollback();
					} catch (Throwable ignore) {}
					throw Cause.create(ex).wrap(IOException.class);
				}
			}

			// /jcr:system/jcr:versionStorage
			JcrPath versionStoragePath = systemPath.resolve(JcrNode.JCR_VERSION_STORAGE_NAME);
			try {
				workspace.getSession().getNode(versionStoragePath.toString());
			} catch (PathNotFoundException pathNotFound) {
				try {
					workspaceQuery.items().createNode(versionStoragePath.toString(), NodeType.NT_FOLDER);
					workspaceQuery.commit();
				} catch (Throwable ex) {
					try {
						workspaceQuery.rollback();
					} catch (Throwable ignore) {}
					throw Cause.create(ex).wrap(IOException.class);
				}
			}
		} catch (RepositoryException ex) {
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
						} catch (Throwable ignore) {}
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
				} catch (Throwable ignore) {}
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
			} catch (Throwable ignore) {}
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
		private Thread fThread;
		private boolean fCloseRequested;
		private final Object fLock = new Object();
		private final List<Connection> fConnections = new ArrayList<>();
		private int fMinConnections;
		private int fCacheSize;

		public ConnectionPool open() {
			if (fThread != null) {
				return this;
			}

			fMinConnections = fRepository.getConfiguration().getMaxSessions() / 4;
			if (fMinConnections < 8) {
				fMinConnections = 8;
			}
			if (fMinConnections > fRepository.getConfiguration().getMaxSessions()) {
				fMinConnections = fRepository.getConfiguration().getMaxSessions();
			}

			fCacheSize = fRepository.getConfiguration().getCacheSize();

			fThread = new Thread(new Task());
			fThread.setDaemon(true);
			fThread.start();

			return this;
		}

		public Connection getConnection() throws SQLException {
			synchronized (fLock) {
				if (fConnections.isEmpty()) {
					try {
						fLock.wait(3000);
					} catch (InterruptedException ignore) {}
				}

				if (fConnections.isEmpty()) {
					throw new SQLException("Connection timed out.");
				}

				try {
					return fConnections.remove(0);
				} finally {
					fLock.notifyAll();
				}
			}
		}

		@Override
		public void close() throws IOException {
			if (fCloseRequested) {
				return;
			}

			fCloseRequested = true;
			synchronized (fLock) {
				fLock.notifyAll();
			}
			try {
				fThread.interrupt();
				fThread.join(10000);
			} catch (InterruptedException ignore) {}
			fThread = null;
			fCloseRequested = false;
		}

		private class Task implements Runnable {
			@Override
			public void run() {
                                while (!fCloseRequested) {
                                        if (Thread.interrupted()) {
                                                fCloseRequested = true;
                                                break;
                                        }
                                        while (fConnections.size() < fMinConnections) {
                                                synchronized (fLock) {
                                                        try {
								fConnections.add(createConnection());
								fLock.notifyAll();
							} catch (Throwable ex) {
								Activator.getDefault().getLogger(getClass()).error(ex.getMessage(), ex);
							}
						}
					}

					if (fCloseRequested) {
						continue;
					}

					synchronized (fLock) {
						try {
							fLock.wait();
						} catch (InterruptedException ignore) {}
					}
				}

				synchronized (fLock) {
					while (!fConnections.isEmpty()) {
						try {
							fConnections.remove(0).close();
						} catch (Throwable ex) {
							Activator.getDefault().getLogger(getClass()).error(ex.getMessage(), ex);
						}
					}
				}
			}

			private Connection createConnection() throws SQLException {
				Connection conn = DriverManager.getConnection("jdbc:h2:" + getJcrDataPath().resolve("data").toAbsolutePath() + ";CACHE_SIZE=" + fCacheSize, "sa", "");
				conn.setAutoCommit(false);
				return conn;
			}
		}
	}

}

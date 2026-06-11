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

package org.mintjams.rt.jcr.internal.cluster;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.rt.jcr.internal.Activator;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.sql.Query;
import org.mintjams.tools.sql.Update;

/**
 * Coordinates the repository nodes that share a workspace's database in a
 * clustered deployment. Coordination state lives in two workspace tables:
 * {@code jcr_cluster_nodes} (node registry, refreshed by a heartbeat) and
 * {@code jcr_cluster_locks} (lease-based locks used to serialize workspace
 * startup and to keep maintenance tasks from running on several nodes at
 * once).
 *
 * <p>Locks are node-scoped leases: a lease names its owning node and an
 * expiry, so a crashed node never blocks the cluster for longer than the
 * lease's time-to-live. In standalone mode (the default) every operation is
 * a no-op and every lock is granted immediately, so callers need not
 * distinguish between the two modes.
 */
public class ClusterController implements ClusterCoordinator, Closeable {

	private static final long HEARTBEAT_INTERVAL_MILLIS = 30000L;
	private static final long HEARTBEAT_STALE_MILLIS = HEARTBEAT_INTERVAL_MILLIS * 3;
	private static final long LOCK_RETRY_INTERVAL_MILLIS = 500L;
	private static final int PREPARE_RETRY_COUNT = 3;

	private final String fWorkspaceName;
	private final boolean fClusterEnabled;
	private final String fNodeId;
	private final ConnectionFactory fConnections;
	private Thread fThread;
	private boolean fCloseRequested;
	private final Object fLock = new Object();

	private ClusterController(String workspaceName, boolean clusterEnabled, String nodeId,
			ConnectionFactory connections) {
		fWorkspaceName = workspaceName;
		fClusterEnabled = clusterEnabled;
		fNodeId = nodeId;
		fConnections = connections;
	}

	public static ClusterController create(String workspaceName, boolean clusterEnabled, String nodeId,
			ConnectionFactory connections) {
		return new ClusterController(workspaceName, clusterEnabled, nodeId, connections);
	}

	/**
	 * Supplies a pooled connection with auto-commit disabled, as handed out
	 * by the workspace's connection pool.
	 */
	public interface ConnectionFactory {
		Connection getConnection() throws SQLException;
	}

	/**
	 * A held lock. Closing the lease releases the lock; closing it more than
	 * once has no effect.
	 */
	public interface Lease extends ClusterCoordinator.Lease {
	}

	@Override
	public boolean isClusterEnabled() {
		return fClusterEnabled;
	}

	@Override
	public String getNodeId() {
		return fNodeId;
	}

	public synchronized ClusterController open() throws IOException {
		if (!fClusterEnabled || fThread != null) {
			return this;
		}

		prepareTables();
		beat();

		fThread = new Thread(new HeartbeatTask());
		fThread.setDaemon(true);
		fThread.start();

		Activator.getDefault().getLogger(getClass()).info("Cluster node '" + fNodeId
				+ "' has joined the JCR workspace '" + fWorkspaceName + "'.");
		return this;
	}

	/**
	 * Acquires the named lock, waiting as long as it takes. Intended for
	 * startup-critical sections; the time-to-live only bounds how long a
	 * crashed owner can keep the lock.
	 */
	@Override
	public Lease lock(String name, long ttlMillis) throws IOException {
		if (!fClusterEnabled) {
			return () -> {};
		}

		long started = System.currentTimeMillis();
		long lastReported = started;
		while (!fCloseRequested) {
			if (tryAcquire(name, ttlMillis)) {
				return () -> release(name);
			}

			if (System.currentTimeMillis() - lastReported >= 30000) {
				lastReported = System.currentTimeMillis();
				Activator.getDefault().getLogger(getClass()).info("Waiting for the cluster lock '" + name
						+ "' on workspace '" + fWorkspaceName + "' ("
						+ ((System.currentTimeMillis() - started) / 1000) + " seconds).");
			}

			try {
				Thread.sleep(LOCK_RETRY_INTERVAL_MILLIS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for the cluster lock '" + name + "'.");
			}
		}
		throw new IOException("The cluster controller has been closed.");
	}

	/**
	 * Acquires the named lock if it is free (or its lease has expired) and
	 * returns the lease, or returns {@code null} without waiting. Intended
	 * for recurring maintenance tasks, where "another node is already doing
	 * it" simply means there is nothing to do.
	 */
	@Override
	public Lease tryLock(String name, long ttlMillis) {
		if (!fClusterEnabled) {
			return () -> {};
		}

		if (tryAcquire(name, ttlMillis)) {
			return () -> release(name);
		}
		return null;
	}

	@Override
	public List<ClusterCoordinator.Member> listMembers() throws IOException {
		if (!fClusterEnabled) {
			return Collections.emptyList();
		}

		try (Connection connection = fConnections.getConnection()) {
			try {
				List<ClusterCoordinator.Member> members = new ArrayList<>();
				try (Query.Result result = Query.newBuilder(connection)
						.setStatement("SELECT node_id, host_name, node_started, last_heartbeat FROM jcr_cluster_nodes"
								+ " ORDER BY node_id")
						.build().setOffset(0).execute()) {
					for (AdaptableMap<String, Object> r : result) {
						members.add(new MemberImpl(r.getString("node_id"), r.getString("host_name"),
								r.getLong("node_started"), r.getLong("last_heartbeat")));
					}
				}
				connection.rollback();
				return members;
			} catch (Throwable ex) {
				try {
					connection.rollback();
				} catch (Throwable ignore) {}
				throw ex;
			}
		} catch (IOException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private static class MemberImpl implements ClusterCoordinator.Member {
		private final String fNodeId;
		private final String fHostName;
		private final long fStarted;
		private final long fLastHeartbeat;

		private MemberImpl(String nodeId, String hostName, long started, long lastHeartbeat) {
			fNodeId = nodeId;
			fHostName = hostName;
			fStarted = started;
			fLastHeartbeat = lastHeartbeat;
		}

		@Override
		public String getNodeId() {
			return fNodeId;
		}

		@Override
		public String getHostName() {
			return fHostName;
		}

		@Override
		public long getStarted() {
			return fStarted;
		}

		@Override
		public long getLastHeartbeat() {
			return fLastHeartbeat;
		}
	}

	private void prepareTables() throws IOException {
		for (int i = 0;; i++) {
			try (Connection connection = fConnections.getConnection()) {
				try {
					for (String statement : Strings.readAll(
							getClass().getResourceAsStream("workspace-cluster-prepare.sql"),
							StandardCharsets.UTF_8.toString()).toString().split(";")) {
						statement = statement.trim();
						if (Strings.isEmpty(statement)) {
							continue;
						}

						Update.newBuilder(connection).setStatement(statement).build().execute();
					}
					connection.commit();
					return;
				} catch (Throwable ex) {
					try {
						connection.rollback();
					} catch (Throwable ignore) {}

					// Several nodes may race to create the tables; losing the
					// race is fine as long as the tables exist afterwards.
					if (i < PREPARE_RETRY_COUNT) {
						try {
							Thread.sleep(LOCK_RETRY_INTERVAL_MILLIS);
						} catch (InterruptedException ignore) {}
						continue;
					}
					throw ex;
				}
			} catch (IOException ex) {
				throw ex;
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IOException.class);
			}
		}
	}

	private boolean tryAcquire(String name, long ttlMillis) {
		try (Connection connection = fConnections.getConnection()) {
			try {
				long now = System.currentTimeMillis();

				Update.newBuilder(connection)
						.setStatement("DELETE FROM jcr_cluster_locks"
								+ " WHERE lock_name = {{name}} AND lock_expires < {{now}} AND owner_id <> {{owner}}")
						.setVariable("name", name)
						.setVariable("now", now)
						.setVariable("owner", fNodeId)
						.build().execute();

				int renewed = Update.newBuilder(connection)
						.setStatement("UPDATE jcr_cluster_locks SET lock_expires = {{expires}}"
								+ " WHERE lock_name = {{name}} AND owner_id = {{owner}}")
						.setVariable("expires", now + ttlMillis)
						.setVariable("name", name)
						.setVariable("owner", fNodeId)
						.build().execute();
				if (renewed == 0) {
					Update.newBuilder(connection)
							.setStatement("INSERT INTO jcr_cluster_locks"
									+ " (lock_name, owner_id, lock_acquired, lock_expires)"
									+ " VALUES ({{name}}, {{owner}}, {{now}}, {{expires}})")
							.setVariable("name", name)
							.setVariable("owner", fNodeId)
							.setVariable("now", now)
							.setVariable("expires", now + ttlMillis)
							.build().execute();
				}

				connection.commit();
				return true;
			} catch (Throwable ex) {
				try {
					connection.rollback();
				} catch (Throwable ignore) {}
				return false;
			}
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(getClass())
					.error("An error occurred while acquiring the cluster lock '" + name + "'.", ex);
			return false;
		}
	}

	private void release(String name) {
		try (Connection connection = fConnections.getConnection()) {
			try {
				Update.newBuilder(connection)
						.setStatement("DELETE FROM jcr_cluster_locks WHERE lock_name = {{name}} AND owner_id = {{owner}}")
						.setVariable("name", name)
						.setVariable("owner", fNodeId)
						.build().execute();
				connection.commit();
			} catch (Throwable ex) {
				try {
					connection.rollback();
				} catch (Throwable ignore) {}
				throw ex;
			}
		} catch (Throwable ex) {
			// The lease expires on its own; failing to release early is harmless.
			Activator.getDefault().getLogger(getClass())
					.warn("An error occurred while releasing the cluster lock '" + name + "'.", ex);
		}
	}

	private void beat() {
		try (Connection connection = fConnections.getConnection()) {
			try {
				long now = System.currentTimeMillis();

				int updated = Update.newBuilder(connection)
						.setStatement("UPDATE jcr_cluster_nodes SET last_heartbeat = {{now}}"
								+ " WHERE node_id = {{nodeId}}")
						.setVariable("now", now)
						.setVariable("nodeId", fNodeId)
						.build().execute();
				if (updated == 0) {
					Update.newBuilder(connection)
							.setStatement("INSERT INTO jcr_cluster_nodes"
									+ " (node_id, host_name, node_started, last_heartbeat)"
									+ " VALUES ({{nodeId}}, {{hostName}}, {{now}}, {{now}})")
							.setVariable("nodeId", fNodeId)
							.setVariable("hostName", getHostName())
							.setVariable("now", now)
							.build().execute();
				}

				connection.commit();
			} catch (Throwable ex) {
				try {
					connection.rollback();
				} catch (Throwable ignore) {}
				throw ex;
			}
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(getClass())
					.error("An error occurred while updating the cluster heartbeat.", ex);
		}
	}

	private String getHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (Throwable ignore) {
			return null;
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (fCloseRequested) {
			return;
		}

		fCloseRequested = true;
		synchronized (fLock) {
			fLock.notifyAll();
		}
		if (fThread != null) {
			try {
				fThread.interrupt();
				fThread.join(10000);
			} catch (InterruptedException ignore) {}
			fThread = null;

			try (Connection connection = fConnections.getConnection()) {
				try {
					Update.newBuilder(connection)
							.setStatement("DELETE FROM jcr_cluster_nodes WHERE node_id = {{nodeId}}")
							.setVariable("nodeId", fNodeId)
							.build().execute();
					connection.commit();
				} catch (Throwable ex) {
					try {
						connection.rollback();
					} catch (Throwable ignore) {}
					throw ex;
				}
			} catch (Throwable ex) {
				Activator.getDefault().getLogger(getClass())
						.warn("An error occurred while deregistering the cluster node.", ex);
			}
		}
		fCloseRequested = false;
	}

	private class HeartbeatTask implements Runnable {
		private final Set<String> fStaleReported = new HashSet<>();

		@Override
		public void run() {
			while (!fCloseRequested) {
				if (Thread.interrupted()) {
					fCloseRequested = true;
					break;
				}
				synchronized (fLock) {
					try {
						fLock.wait(HEARTBEAT_INTERVAL_MILLIS);
					} catch (InterruptedException ignore) {}
				}

				if (fCloseRequested) {
					continue;
				}

				beat();
				checkMembers();
			}
		}

		/**
		 * Warns when another member's heartbeat goes stale (it crashed, lost
		 * its database connection, or was partitioned away) and reports its
		 * recovery — once per transition, not per check.
		 */
		private void checkMembers() {
			try {
				long staleBefore = System.currentTimeMillis() - HEARTBEAT_STALE_MILLIS;
				Set<String> stale = new HashSet<>();
				Set<String> present = new HashSet<>();
				for (ClusterCoordinator.Member member : listMembers()) {
					if (member.getNodeId().equals(fNodeId)) {
						continue;
					}
					present.add(member.getNodeId());
					if (member.getLastHeartbeat() < staleBefore) {
						stale.add(member.getNodeId());
						if (fStaleReported.add(member.getNodeId())) {
							Activator.getDefault().getLogger(ClusterController.class)
									.warn("Cluster node '" + member.getNodeId() + "' on workspace '" + fWorkspaceName
											+ "' has not sent a heartbeat for "
											+ ((System.currentTimeMillis() - member.getLastHeartbeat()) / 1000)
											+ " seconds and is presumed dead.");
						}
					}
				}
				for (String nodeId : new HashSet<>(fStaleReported)) {
					if (stale.contains(nodeId)) {
						continue;
					}
					fStaleReported.remove(nodeId);
					// A node that deregistered (clean shutdown) just leaves;
					// only a node that is present again has recovered.
					if (present.contains(nodeId)) {
						Activator.getDefault().getLogger(ClusterController.class)
								.info("Cluster node '" + nodeId + "' on workspace '" + fWorkspaceName
										+ "' is sending heartbeats again.");
					}
				}
			} catch (Throwable ex) {
				Activator.getDefault().getLogger(ClusterController.class)
						.error("An error occurred while checking the cluster members.", ex);
			}
		}
	}

}

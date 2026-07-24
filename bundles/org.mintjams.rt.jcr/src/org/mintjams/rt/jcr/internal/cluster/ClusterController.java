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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.jcr.cluster.ClusterLeaseStore;
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
 * {@code jcr_cluster_locks} (platform-internal leases that serialize the
 * repository's own bootstrap and maintenance work — workspace startup,
 * blob cleanup and the like — across nodes).
 *
 * <p>Leases are node-scoped: a lease names its owning node and an expiry,
 * so a crashed node never blocks the cluster for longer than the lease's
 * time-to-live. Every lease caller is a per-JVM singleton (a scheduled
 * maintenance thread or a startup step), so node scope is exactly the
 * right granularity; these leases are repository infrastructure and do
 * not serialize application tasks — application code uses session-scoped
 * JCR locks for that. In standalone mode (the default) the registry,
 * heartbeat and signal machinery are a no-op and every lease is granted
 * immediately.
 */
public class ClusterController implements ClusterCoordinator, ClusterLeaseStore, Closeable {

	private static final long HEARTBEAT_INTERVAL_MILLIS = 30000L;
	private static final long HEARTBEAT_STALE_MILLIS = HEARTBEAT_INTERVAL_MILLIS * 3;
	private static final long LOCK_RETRY_INTERVAL_MILLIS = 500L;
	private static final int PREPARE_RETRY_COUNT = 3;

	private final String fWorkspaceName;
	private final boolean fClusterEnabled;
	private final String fNodeId;
	private final ConnectionFactory fConnections;
	private Thread fThread;
	private Thread fSignalThread;
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
	 * A held lease. Closing the lease releases it; closing it more than
	 * once has no effect.
	 */
	public interface Lease extends ClusterLeaseStore.Lease {
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

		fSignalThread = new Thread(new SignalPoller(), getClass().getSimpleName() + "-Signals");
		fSignalThread.setDaemon(true);
		fSignalThread.start();

		Activator.getDefault().getLogger(getClass()).info("Cluster node '" + fNodeId
				+ "' has joined the JCR workspace '" + fWorkspaceName + "'.");
		return this;
	}

	/**
	 * Acquires the named lease, waiting as long as it takes. Intended for
	 * startup-critical sections; the time-to-live only bounds how long a
	 * crashed owner can keep the lease.
	 */
	@Override
	public Lease lock(String name, long ttlMillis) throws IOException {
		if (!fClusterEnabled) {
			// Standalone: the callers are per-JVM singletons, so there is
			// nothing to serialize against and the lease is granted
			// immediately.
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
	 * Acquires the named lease if it is free (or its previous lease has
	 * expired) and returns it, or returns {@code null} without waiting.
	 * Intended for recurring maintenance tasks, where "another node is
	 * already doing it" simply means there is nothing to do.
	 */
	@Override
	public Lease tryLock(String name, long ttlMillis) {
		if (!fClusterEnabled) {
			// Standalone: the callers are per-JVM singletons, so there is
			// nothing to serialize against and the lease is granted
			// immediately.
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
				long staleBefore = System.currentTimeMillis() - HEARTBEAT_STALE_MILLIS;
				List<ClusterCoordinator.Member> members = new ArrayList<>();
				try (Query.Result result = Query.newBuilder(connection)
						.setStatement("SELECT node_id, host_name, node_started, last_heartbeat FROM jcr_cluster_nodes"
								+ " ORDER BY node_id")
						.build().setOffset(0).execute()) {
					for (AdaptableMap<String, Object> r : result) {
						long lastHeartbeat = r.getLong("last_heartbeat");
						members.add(new MemberImpl(r.getString("node_id"), r.getString("host_name"),
								r.getLong("node_started"), lastHeartbeat, lastHeartbeat >= staleBefore));
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

	@Override
	public void publish(String topic, Map<String, Object> properties) {
		if (!fClusterEnabled || Strings.isEmpty(topic)) {
			return;
		}

		try (Connection connection = fConnections.getConnection()) {
			try {
				ClusterSignals.publish(connection, fNodeId, topic, serialize(properties));
				connection.commit();
			} catch (Throwable ex) {
				try {
					connection.rollback();
				} catch (Throwable ignore) {}
				throw ex;
			}
		} catch (Throwable ex) {
			// Best-effort: a node that misses the broadcast falls back to its
			// own polling or a client reload, so a publish failure must never
			// derail the operation that triggered it.
			Activator.getDefault().getLogger(getClass()).warn("An error occurred while publishing the cluster signal '"
					+ topic + "' on workspace '" + fWorkspaceName + "'.", ex);
		}
	}

	/**
	 * Serializes event properties to the opaque JSON payload stored with a
	 * signal, or {@code null} when there are none. Only simple scalar values
	 * are expected.
	 */
	private static String serialize(Map<String, Object> properties) throws IOException {
		if (properties == null || properties.isEmpty()) {
			return null;
		}
		return Activator.getDefault().toJSON(properties);
	}

	/**
	 * Reverses {@link #serialize(Map)} for a received signal. A null, empty or
	 * malformed payload yields no properties rather than failing the delivery.
	 */
	private static Map<String, Object> deserialize(String payload) {
		if (Strings.isEmpty(payload)) {
			return Collections.emptyMap();
		}
		try {
			Map<String, Object> properties = Activator.getDefault().parseJSON(payload);
			if (properties != null) {
				return properties;
			}
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(ClusterController.class)
					.warn("Could not parse a cluster signal payload: " + payload, ex);
		}
		return Collections.emptyMap();
	}

	private static class MemberImpl implements ClusterCoordinator.Member {
		private final String fNodeId;
		private final String fHostName;
		private final long fStarted;
		private final long fLastHeartbeat;
		private final boolean fAlive;

		private MemberImpl(String nodeId, String hostName, long started, long lastHeartbeat, boolean alive) {
			fNodeId = nodeId;
			fHostName = hostName;
			fStarted = started;
			fLastHeartbeat = lastHeartbeat;
			fAlive = alive;
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

		@Override
		public boolean isAlive() {
			return fAlive;
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
		if (fSignalThread != null) {
			try {
				fSignalThread.interrupt();
				fSignalThread.join(10000);
			} catch (InterruptedException ignore) {}
			fSignalThread = null;
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
				Set<String> stale = new HashSet<>();
				Set<String> present = new HashSet<>();
				for (ClusterCoordinator.Member member : listMembers()) {
					if (member.getNodeId().equals(fNodeId)) {
						continue;
					}
					present.add(member.getNodeId());
					if (!member.isAlive()) {
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

	/**
	 * Consumes the cluster signal bus: notifications published by other nodes
	 * are re-emitted as local OSGi events, so a node behaves as if the remote
	 * post had happened here. Signals are emitted immediately for promptness;
	 * the consumed position is only advanced past a signal once it is old
	 * enough that no concurrently committing publish can still surface behind
	 * it — assuming the cluster nodes run with synchronized clocks, as the
	 * journal already requires — while a bounded set of recently emitted
	 * sequences keeps the re-scan from emitting the same signal twice.
	 *
	 * <p>No durable per-node offset is kept: signals tell live clients to
	 * refresh, so a node that was down had no client to notify and resumes at
	 * the current head. Expired rows are purged under a short lease so a
	 * single node does the cleanup.
	 */
	private class SignalPoller implements Runnable {
		private static final long POLL_INTERVAL_MILLIS = 2000L;
		private static final long STABILITY_GRACE_MILLIS = 10000L;
		private static final long RETENTION_MILLIS = 300000L;
		private static final long PURGE_INTERVAL_MILLIS = 60000L;
		private static final int BATCH_LIMIT = 200;
		private static final int PROCESSED_CACHE_SIZE = 4096;

		private final Set<Long> fProcessed = new LinkedHashSet<>();
		private long fConsumed = -1;
		private long fLastPurge;

		@Override
		public void run() {
			while (!fCloseRequested) {
				if (Thread.interrupted()) {
					fCloseRequested = true;
					break;
				}
				synchronized (fLock) {
					try {
						fLock.wait(POLL_INTERVAL_MILLIS);
					} catch (InterruptedException ignore) {}
				}

				if (fCloseRequested) {
					continue;
				}

				try {
					poll();
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(ClusterController.class).warn(
							"An error occurred while consuming cluster signals on workspace '" + fWorkspaceName + "'.",
							ex);
				}
			}
		}

		private void poll() throws SQLException, IOException {
			try (Connection connection = fConnections.getConnection()) {
				try {
					if (fConsumed < 0) {
						// A node that has never consumed the bus starts at the
						// current head: earlier signals were meant for the
						// clients that were live when they were published.
						fConsumed = ClusterSignals.getMaxSeq(connection);
					}

					List<AdaptableMap<String, Object>> signals = new ArrayList<>();
					try (Query.Result result = ClusterSignals.list(connection, fConsumed, fNodeId, BATCH_LIMIT)) {
						for (AdaptableMap<String, Object> r : result) {
							signals.add(r);
						}
					}
					// The read is the only DB work here; end its transaction
					// before the per-signal emit so the connection is not held
					// open across event delivery.
					connection.rollback();

					long stableCutoff = System.currentTimeMillis() - STABILITY_GRACE_MILLIS;
					long newConsumed = fConsumed;
					boolean stable = true;
					for (AdaptableMap<String, Object> signal : signals) {
						if (fCloseRequested) {
							break;
						}

						long seq = signal.getLong("signal_seq");
						if (!fProcessed.contains(seq)) {
							emit(signal.getString("topic"), signal.getString("payload"));
							fProcessed.add(seq);
							while (fProcessed.size() > PROCESSED_CACHE_SIZE) {
								Iterator<Long> i = fProcessed.iterator();
								i.next();
								i.remove();
							}
						}

						// A row becomes visible when its INSERT commits, which can
						// be after a higher sequence is already visible. Only
						// consume past a signal once nothing can still surface
						// behind it; until then the processed set keeps re-reads
						// from emitting it twice.
						if (stable && signal.getLong("created") <= stableCutoff) {
							newConsumed = seq;
						} else {
							stable = false;
						}
					}
					fConsumed = newConsumed;
				} catch (Throwable ex) {
					try {
						connection.rollback();
					} catch (Throwable ignore) {}
					throw ex;
				}
			}

			purgeExpired();
		}

		private void purgeExpired() {
			long now = System.currentTimeMillis();
			if (now - fLastPurge < PURGE_INTERVAL_MILLIS) {
				return;
			}
			fLastPurge = now;

			// One node is enough; a brief lease keeps every node from deleting
			// the same rows at once. Losing the race simply means another node
			// is already doing the cleanup. The lease is acquired before the
			// purge connection is opened, so the two never overlap in the pool.
			Lease lease = tryLock("cluster-signals-purge", PURGE_INTERVAL_MILLIS);
			if (lease == null) {
				return;
			}
			try (Connection connection = fConnections.getConnection()) {
				try {
					ClusterSignals.purge(connection, RETENTION_MILLIS);
					connection.commit();
				} catch (Throwable ex) {
					try {
						connection.rollback();
					} catch (Throwable ignore) {}
					throw ex;
				}
			} catch (Throwable ex) {
				Activator.getDefault().getLogger(ClusterController.class).warn(
						"An error occurred while purging cluster signals on workspace '" + fWorkspaceName + "'.", ex);
			} finally {
				lease.close();
			}
		}

		private void emit(String topic, String payload) {
			if (Strings.isEmpty(topic)) {
				return;
			}
			Activator.getDefault().postEvent(topic, deserialize(payload));
		}
	}

}

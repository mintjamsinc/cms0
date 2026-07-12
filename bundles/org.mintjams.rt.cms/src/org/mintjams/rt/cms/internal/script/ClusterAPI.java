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

package org.mintjams.rt.cms.internal.script;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.script.ScriptingContext;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

/**
 * Cluster coordination for application code. Scheduled work (EIP timer
 * routes, recurring scripts) runs on every cluster node; guarding it with
 * a lock from this API makes it run on exactly one:
 *
 * <pre>
 * def lease = cluster.tryLock("nightly-report", 600000)
 * if (lease != null) {
 *     try { ... } finally { lease.close() }
 * }
 * </pre>
 *
 * <p>Locks are lease-based and workspace-wide; a crashed node never holds
 * a lock for longer than the lease's time-to-live. In standalone
 * deployments locks are held in an in-JVM lease registry, so overlapping
 * executions on the single node (a timer tick racing an async kick of the
 * same task) exclude each other exactly as cluster nodes do, and the same
 * code runs unchanged. Application locks live in their own namespace and
 * cannot collide with the platform's internal locks.
 */
public class ClusterAPI {

	private static final String APPLICATION_LOCK_PREFIX = "app:";

	/**
	 * Standalone lock table: lock name to the held lease (expiry in epoch
	 * ms). Mutual exclusion for concurrent script executions inside one JVM
	 * when no cluster coordinator exists; an expired entry is treated as
	 * free, so a script that died without closing its lease can never hold
	 * a lock past the time-to-live (the same guarantee the cluster
	 * coordinator gives). The entry value is a per-claim instance, compared
	 * by identity, so equal expiry times of racing claims cannot be
	 * confused and closing a stale lease can never release a successor's.
	 */
	private static final ConcurrentHashMap<String, StandaloneLease> fStandaloneLocks = new ConcurrentHashMap<>();

	private static final class StandaloneLease {
		final long expiry;

		StandaloneLease(long expiry) {
			this.expiry = expiry;
		}
	}

	private final WorkspaceScriptContext fContext;

	public ClusterAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static ClusterAPI get(ScriptingContext context) {
		return (ClusterAPI) context.getAttribute(ClusterAPI.class.getSimpleName());
	}

	/**
	 * Returns whether this node runs as part of a cluster.
	 */
	public boolean isClusterEnabled() {
		ClusterCoordinator coordinator = getCoordinator();
		return (coordinator != null && coordinator.isClusterEnabled());
	}

	/**
	 * Returns the identifier under which this node appears in the cluster.
	 */
	public String getNodeId() {
		ClusterCoordinator coordinator = getCoordinator();
		return (coordinator == null) ? null : coordinator.getNodeId();
	}

	/**
	 * Returns the nodes currently registered for this workspace, as maps
	 * with {@code nodeId}, {@code hostName}, {@code started},
	 * {@code lastHeartbeat}, and {@code alive} entries ({@code alive} is
	 * the coordinator's heartbeat-freshness judgement; a member whose
	 * heartbeat went stale is presumed dead). Empty in standalone
	 * deployments.
	 */
	public List<Map<String, Object>> listMembers() throws IOException {
		ClusterCoordinator coordinator = getCoordinator();
		List<Map<String, Object>> members = new ArrayList<>();
		if (coordinator == null) {
			return members;
		}
		for (ClusterCoordinator.Member member : coordinator.listMembers()) {
			Map<String, Object> e = new HashMap<>();
			e.put("nodeId", member.getNodeId());
			e.put("hostName", member.getHostName());
			e.put("started", member.getStarted());
			e.put("lastHeartbeat", member.getLastHeartbeat());
			e.put("alive", member.isAlive());
			members.add(e);
		}
		return members;
	}

	/**
	 * Acquires the named application lock, waiting until it is available.
	 * The returned lease must be closed to release the lock; the
	 * time-to-live bounds how long a crashed owner can keep it.
	 */
	public ClusterCoordinator.Lease lock(String name, long ttlMillis) throws IOException {
		ClusterCoordinator coordinator = getCoordinator();
		if (coordinator == null) {
			// Standalone: wait on the in-JVM lock table, polling well under any
			// realistic lease TTL so the wait ends promptly after a release.
			for (;;) {
				ClusterCoordinator.Lease lease = tryStandaloneLock(APPLICATION_LOCK_PREFIX + name, ttlMillis);
				if (lease != null) {
					return lease;
				}
				try {
					Thread.sleep(100);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for lock: " + name, ex);
				}
			}
		}
		return coordinator.lock(APPLICATION_LOCK_PREFIX + name, ttlMillis);
	}

	/**
	 * Acquires the named application lock if it is free and returns the
	 * lease, or returns {@code null} without waiting — meaning another node
	 * (or, standalone, another execution on this node) is already doing the
	 * work.
	 */
	public ClusterCoordinator.Lease tryLock(String name, long ttlMillis) {
		ClusterCoordinator coordinator = getCoordinator();
		if (coordinator == null) {
			return tryStandaloneLock(APPLICATION_LOCK_PREFIX + name, ttlMillis);
		}
		return coordinator.tryLock(APPLICATION_LOCK_PREFIX + name, ttlMillis);
	}

	/**
	 * Standalone tryLock over the in-JVM lock table: atomically claims the
	 * name if it is free or its lease has expired, returning a lease that
	 * releases only its own claim, or {@code null} when a live lease is
	 * held by another execution.
	 */
	private static ClusterCoordinator.Lease tryStandaloneLock(String name, long ttlMillis) {
		final long now = System.currentTimeMillis();
		final StandaloneLease claimed = new StandaloneLease(ttlMillis > 0 ? now + ttlMillis : Long.MAX_VALUE);
		StandaloneLease winner = fStandaloneLocks.compute(name, (k, cur) -> {
			if (cur != null && cur.expiry > now) {
				return cur;
			}
			return claimed;
		});
		if (winner != claimed) {
			return null;
		}
		// remove(key, value) compares by identity here (StandaloneLease does not
		// override equals), so an expired-and-replaced claim is never released
		// on behalf of its successor.
		return () -> fStandaloneLocks.remove(name, claimed);
	}

	private ClusterCoordinator getCoordinator() {
		try {
			javax.jcr.Session jcrSession = fContext.getResourceResolver().getSession()
					.adaptTo(javax.jcr.Session.class);
			return Adaptables.getAdapter(jcrSession, ClusterCoordinator.class);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

}

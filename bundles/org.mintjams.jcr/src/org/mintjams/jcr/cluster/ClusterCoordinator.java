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

package org.mintjams.jcr.cluster;

import java.io.IOException;
import java.util.List;

/**
 * Coordinates work across the repository nodes that share a workspace in a
 * clustered deployment. Obtained by adapting a workspace's session (or any
 * object that adapts to its workspace provider).
 *
 * <p>Locks are lease-based and node-scoped: a lease names its owning node
 * and an expiry, so a crashed node never blocks the cluster for longer
 * than the lease's time-to-live. In standalone deployments every lock is
 * granted immediately, so callers need not distinguish between the two
 * modes.
 */
public interface ClusterCoordinator {

	/**
	 * Returns whether this repository node runs as part of a cluster.
	 */
	boolean isClusterEnabled();

	/**
	 * Returns the identifier under which this node appears in the cluster.
	 */
	String getNodeId();

	/**
	 * Acquires the named workspace-wide lock, waiting as long as it takes.
	 * The time-to-live only bounds how long a crashed owner can keep the
	 * lock.
	 */
	Lease lock(String name, long ttlMillis) throws IOException;

	/**
	 * Acquires the named workspace-wide lock if it is free (or its lease
	 * has expired) and returns the lease, or returns {@code null} without
	 * waiting.
	 */
	Lease tryLock(String name, long ttlMillis);

	/**
	 * Returns the nodes currently registered for this workspace, including
	 * this one. Nodes refresh their registration with a heartbeat, so a
	 * member whose heartbeat is stale is presumed dead. Returns an empty
	 * list in standalone deployments.
	 */
	List<Member> listMembers() throws IOException;

	/**
	 * A held lock. Closing the lease releases the lock; closing it more
	 * than once has no effect.
	 */
	interface Lease extends AutoCloseable {
		@Override
		void close();
	}

	/**
	 * A node registered for the workspace.
	 */
	interface Member {

		String getNodeId();

		String getHostName();

		/**
		 * Returns when the node joined, in milliseconds since the epoch.
		 */
		long getStarted();

		/**
		 * Returns the node's last heartbeat, in milliseconds since the
		 * epoch.
		 */
		long getLastHeartbeat();
	}

}

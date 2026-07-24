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

/**
 * Platform-internal leases that serialize the repository's own bootstrap
 * and maintenance work across the nodes sharing a workspace (workspace
 * startup, blob cleanup, content deployment, and the like). Obtained by
 * adapting a workspace's session.
 *
 * <p>Leases are node-scoped: a lease names its owning node and an expiry,
 * so a crashed node never blocks the cluster for longer than the lease's
 * time-to-live. Because every caller is a per-JVM singleton (a scheduled
 * maintenance thread or a startup step), node scope is exactly the right
 * granularity; these leases do not serialize concurrent executions inside
 * one node. In standalone deployments every lease is granted immediately.
 *
 * <p>This is repository infrastructure, not an application lock service.
 * Application code that needs "exactly one execution" of a task uses a
 * session-scoped JCR lock ({@code javax.jcr.lock.LockManager}) with a
 * timeout hint instead.
 */
public interface ClusterLeaseStore {

	/**
	 * Acquires the named lease, waiting as long as it takes. The
	 * time-to-live only bounds how long a crashed owner can keep it.
	 */
	Lease lock(String name, long ttlMillis) throws IOException;

	/**
	 * Acquires the named lease if it is free (or its previous lease has
	 * expired) and returns it, or returns {@code null} without waiting.
	 */
	Lease tryLock(String name, long ttlMillis);

	/**
	 * A held lease. Closing the lease releases it; closing it more than
	 * once has no effect.
	 */
	interface Lease extends AutoCloseable {
		@Override
		void close();
	}

}

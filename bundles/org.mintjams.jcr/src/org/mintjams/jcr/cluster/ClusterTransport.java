/*
 * Copyright (c) 2026 MintJams Inc.
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
import java.util.Map;
import java.util.Set;

/**
 * The direct connections between the nodes of a cluster, for platform code.
 * Registered as an OSGi service by the repository bundle when the repository
 * runs as part of a cluster; there is no such service in standalone
 * deployments.
 *
 * <p>The connections run on a dedicated port meant for the management
 * network, never on the public HTTP port, and every frame is signed with the
 * cluster's own key. They are a fast path only: a node that cannot be reached
 * makes a request fail, and a table update or a broadcast simply does not
 * reach it, so every user of this interface keeps working (more slowly, or
 * with a retry by the client) without it.
 *
 * <p>This is deliberately separate from {@link ClusterCoordinator}, which
 * application code uses: requests and tables are platform plumbing.
 */
public interface ClusterTransport {

	/**
	 * Returns the identifier under which this node appears in the cluster.
	 */
	String getNodeId();

	/**
	 * Sends a request of the given type to another node and waits for its
	 * answer. The payload and the answer are JSON objects (maps of strings,
	 * numbers, booleans, lists and maps) and must stay small (a few tens of
	 * kilobytes).
	 *
	 * @throws IOException when the node is not connected, does not answer in
	 *                     time, has no handler for the type, or its handler
	 *                     failed
	 */
	Map<String, Object> request(String nodeId, String type, Map<String, Object> payload, long timeoutMillis)
			throws IOException;

	/**
	 * Registers the handler for requests of the given type, replacing any
	 * previous one. Closing the returned handle removes it again.
	 */
	AutoCloseable setRequestHandler(String type, RequestHandler handler);

	/**
	 * Returns the replicated table of the given name.
	 */
	Table getTable(String name);

	/**
	 * Answers the requests of one type sent by other nodes. Runs on a pool
	 * thread, never on a connection's reader.
	 */
	interface RequestHandler {
		Map<String, Object> handle(String fromNodeId, Map<String, Object> payload) throws Exception;
	}

	/**
	 * A table that every node holds in memory and that every node can write:
	 * a write is applied locally at once and sent to the other nodes, a read
	 * never leaves the node. A key is meant to have one writer at a time (the
	 * node that owns what the key describes); concurrent writes are resolved
	 * by the later timestamp. The table is not durable — it is empty again
	 * when every node has stopped — so it holds state that may be lost, and
	 * whatever must survive goes to the repository.
	 */
	interface Table {

		/**
		 * The longest value a table accepts, in characters. A value travels
		 * to the other nodes in one frame, so a longer one is refused by
		 * {@link #put} outright rather than kept on one node only.
		 */
		int MAX_VALUE_LENGTH = 56 * 1024;

		/**
		 * Returns the value of the key, or {@code null} when there is none or
		 * it has expired.
		 */
		String get(String key);

		/**
		 * Sets the value of the key. A positive time-to-live makes every node
		 * drop the value on its own once it has passed; otherwise the value
		 * lasts until it is removed.
		 *
		 * @throws IllegalArgumentException when the value is longer than
		 *                                  {@link #MAX_VALUE_LENGTH}
		 * @throws IllegalStateException    when the table holds as many keys
		 *                                  as it may and this one is new
		 */
		void put(String key, String value, long ttlMillis);

		/**
		 * Removes the key on every node.
		 */
		void remove(String key);

		/**
		 * Returns the keys that currently have a value.
		 */
		Set<String> keySet();
	}

}

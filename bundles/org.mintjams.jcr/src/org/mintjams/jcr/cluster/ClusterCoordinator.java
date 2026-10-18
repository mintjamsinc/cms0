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
import java.util.Map;

/**
 * Exposes the cluster membership of the repository nodes that share a
 * workspace in a clustered deployment, and the signals between them.
 * Obtained by adapting a workspace's session (or any object that adapts
 * to its workspace provider).
 *
 * <p>This interface is deliberately about cluster synchronization only —
 * who the nodes are and how they notify each other. It is not a lock
 * service: application code that needs "exactly one execution" of a task
 * uses a session-scoped JCR lock ({@code javax.jcr.lock.LockManager})
 * with a timeout hint, which works identically in standalone and
 * clustered deployments.
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
	 * Returns the nodes currently registered for this workspace, including
	 * this one. Nodes refresh their registration with a heartbeat, so a
	 * member whose heartbeat is stale is presumed dead. Returns an empty
	 * list in standalone deployments.
	 */
	List<Member> listMembers() throws IOException;

	/**
	 * Broadcasts a control-plane notification to the other nodes that share
	 * this workspace in the cluster. Each receiving node re-emits it as a
	 * local OSGi {@code EventAdmin} event under the given topic with the
	 * given properties, so a node-local event handler sees a remote broadcast
	 * exactly as it sees a local post. The publishing node does not receive
	 * its own broadcast — it has already posted the event locally — so a
	 * caller pairs a local post with this call to reach the whole cluster.
	 *
	 * <p>Intended for ephemeral notifications that tell live clients to
	 * refresh (for example {@code workspaceChanged}), not for durable state:
	 * the notification is sent once, straight to the nodes that are
	 * connected, so a node that is down or not connected at that moment
	 * never sees it. The notification reaches every node of the cluster,
	 * whichever workspace's coordinator publishes it. Best-effort and a no-op
	 * in standalone deployments, where there are no other nodes; a delivery
	 * failure is logged and never propagated, so it can be called freely from
	 * the operation that triggered it.
	 *
	 * @param topic      the OSGi event topic to re-emit on the receiving nodes
	 * @param properties the event properties; values must be serializable as
	 *                   simple scalars (strings, numbers, booleans)
	 */
	void publish(String topic, Map<String, Object> properties);

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

		/**
		 * Returns whether the node's heartbeat is fresh according to the
		 * coordinator's staleness policy. A member that stopped sending
		 * heartbeats (it crashed, lost its database connection, or was
		 * partitioned away) is presumed dead; the judgement is made by the
		 * coordinator so callers never hard-code the heartbeat interval.
		 */
		boolean isAlive();

		/**
		 * Returns the address ({@code host:port}) at which the node accepts
		 * connections from the other nodes, or {@code null} when it does not
		 * accept any (its address could not be determined).
		 */
		String getAddress();

		/**
		 * Returns the connection between this node and the member, as this
		 * node sees it right now, or {@code null} for this node itself.
		 */
		Link getLink();

		/**
		 * Returns the member's connections to the other nodes as the member
		 * itself last reported them (a few seconds old at most). Empty when
		 * the member has not reported, for example while it is not
		 * connected.
		 */
		List<Link> getReportedLinks();
	}

	/**
	 * The connection between two nodes, as seen from one of them.
	 */
	interface Link {

		/**
		 * The node at the other end.
		 */
		String getNodeId();

		/**
		 * The connection this node opened to the other node:
		 * {@code connected}, {@code connecting} or {@code down}.
		 */
		String getOutbound();

		/**
		 * When the outbound connection was established, in milliseconds since
		 * the epoch, or {@code 0} when it is not connected.
		 */
		long getConnectedSince();

		/**
		 * The connection the other node opened to this one: {@code connected}
		 * or {@code none}.
		 */
		String getInbound();

		/**
		 * When this node last sent a wake-up to the other node, or {@code 0}.
		 */
		long getLastWakeSent();

		/**
		 * When this node last received a wake-up from the other node, or
		 * {@code 0}.
		 */
		long getLastWakeReceived();

		/**
		 * How many connections from the other node's address this node
		 * refused because a frame was not correctly signed.
		 */
		long getRejected();

		/**
		 * How many messages for the other node were dropped because there
		 * was no connection or the connection could not keep up.
		 */
		long getDropped();
	}

}

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

package org.mintjams.rt.cms.internal.operations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.Session;

import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.tools.adapter.Adaptables;

/**
 * The repository nodes the operations state is shared between, as seen
 * through the system workspace — the one workspace every node runs, so its
 * registry is the cluster's. A standalone deployment is a cluster of one:
 * the list holds this node only, so the same code serves both.
 */
public final class ClusterNodes {

	/** Identifier used when the repository does not expose one. */
	private static final String LOCAL_NODE_ID = "local";

	/** A repository node. */
	public static final class NodeInfo {
		public final String nodeId;
		public final String hostName;
		/** Whether the node's heartbeat is fresh; this node is always alive. */
		public final boolean alive;
		public final boolean self;

		NodeInfo(String nodeId, String hostName, boolean alive, boolean self) {
			this.nodeId = nodeId;
			this.hostName = hostName;
			this.alive = alive;
			this.self = self;
		}
	}

	private ClusterNodes() {}

	/** The registered nodes, this node first when it is not registered yet. */
	public static List<NodeInfo> list(Session systemSession) throws IOException {
		String selfId = currentNodeId(systemSession);
		ClusterCoordinator coordinator = Adaptables.getAdapter(systemSession, ClusterCoordinator.class);
		List<NodeInfo> nodes = new ArrayList<>();
		boolean selfListed = false;
		if (coordinator != null && coordinator.isClusterEnabled()) {
			for (ClusterCoordinator.Member member : coordinator.listMembers()) {
				boolean self = selfId.equals(member.getNodeId());
				selfListed |= self;
				nodes.add(new NodeInfo(member.getNodeId(), member.getHostName(), self || member.isAlive(), self));
			}
		}
		if (!selfListed) {
			nodes.add(0, new NodeInfo(selfId, localHostName(), true, true));
		}
		return nodes;
	}

	/** The identifier of the node this code runs on. */
	public static String currentNodeId(Session systemSession) {
		ClusterCoordinator coordinator = Adaptables.getAdapter(systemSession, ClusterCoordinator.class);
		String nodeId = (coordinator == null) ? null : coordinator.getNodeId();
		return (nodeId == null || nodeId.isEmpty()) ? LOCAL_NODE_ID : nodeId;
	}

	/** Whether any node other than this one has a fresh heartbeat. */
	public static boolean isAnyOtherNodeAlive(Session systemSession) throws IOException {
		for (NodeInfo node : list(systemSession)) {
			if (!node.self && node.alive) {
				return true;
			}
		}
		return false;
	}

	private static String localHostName() {
		try {
			return java.net.InetAddress.getLocalHost().getHostName();
		} catch (Throwable ex) {
			return null;
		}
	}

}

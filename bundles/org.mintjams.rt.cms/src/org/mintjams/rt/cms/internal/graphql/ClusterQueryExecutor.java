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

package org.mintjams.rt.cms.internal.graphql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.tools.adapter.Adaptables;

/**
 * Executor for cluster GraphQL queries. Reports the deployment topology of
 * the workspace this session is bound to: whether the node runs as part of
 * a cluster, the identifier of the node serving the request, and — in a
 * clustered deployment — every registered member with the coordinator's
 * heartbeat-freshness judgement.
 *
 * <p>In standalone deployments {@code enabled} is {@code false} and the
 * member list is empty, so a client can state "running as a single node"
 * instead of showing an empty cluster.
 *
 * <p>Cluster topology (node identifiers, host names) is operational
 * information; the query requires administrative privileges.
 */
public class ClusterQueryExecutor {

	private final Session session;

	public ClusterQueryExecutor(Session session) {
		this.session = session;
	}

	/**
	 * Execute cluster query.
	 *
	 * Example query:
	 * <pre>
	 * query Cluster {
	 *   cluster {
	 *     enabled
	 *     nodeId
	 *     members {
	 *       nodeId
	 *       hostName
	 *       started
	 *       lastHeartbeat
	 *       alive
	 *       self
	 *     }
	 *   }
	 * }
	 * </pre>
	 */
	public Map<String, Object> executeClusterQuery(GraphQLRequest request) throws Exception {
		org.mintjams.jcr.Session jcrSession = Adaptables.getAdapter(session, org.mintjams.jcr.Session.class);
		if (!jcrSession.isAdmin() && !jcrSession.isService()) {
			throw new Exception("Cluster information requires administrative privileges");
		}

		ClusterCoordinator coordinator = Adaptables.getAdapter(session, ClusterCoordinator.class);

		Map<String, Object> cluster = new HashMap<>();
		boolean enabled = (coordinator != null && coordinator.isClusterEnabled());
		String nodeId = (coordinator == null) ? null : coordinator.getNodeId();
		cluster.put("enabled", enabled);
		cluster.put("nodeId", nodeId);

		List<Map<String, Object>> members = new ArrayList<>();
		if (enabled) {
			for (ClusterCoordinator.Member member : coordinator.listMembers()) {
				Map<String, Object> e = new HashMap<>();
				e.put("nodeId", member.getNodeId());
				e.put("hostName", member.getHostName());
				e.put("started", member.getStarted());
				e.put("lastHeartbeat", member.getLastHeartbeat());
				e.put("alive", member.isAlive());
				e.put("self", member.getNodeId() != null && member.getNodeId().equals(nodeId));
				members.add(e);
			}
		}
		cluster.put("members", members);

		Map<String, Object> data = new HashMap<>();
		data.put("cluster", cluster);
		return data;
	}

}

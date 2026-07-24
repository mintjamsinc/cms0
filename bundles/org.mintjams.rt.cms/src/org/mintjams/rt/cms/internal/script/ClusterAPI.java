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

import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.script.ScriptingContext;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

/**
 * Cluster information for application code: whether this node runs as
 * part of a cluster, its node identifier, and the current membership.
 * Useful for operations dashboards and diagnostics.
 *
 * <p>This API is deliberately informational. Serializing work — "this
 * task must run exactly once at a time" — is not a cluster concern:
 * application code guards a task with a session-scoped JCR lock on a
 * lock resource (see {@code Resource.tryLock}), which works identically
 * in standalone and clustered deployments.
 */
public class ClusterAPI {

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

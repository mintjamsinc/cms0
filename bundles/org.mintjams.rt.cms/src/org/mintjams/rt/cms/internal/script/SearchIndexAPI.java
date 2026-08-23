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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.job.searchindex.SearchIndexRebuildJob;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.script.ScriptingContext;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

/**
 * Search index operations for application code — above all, dispatching a
 * full rebuild of the search index.
 *
 * <p>A rebuild request is durable: {@link #requestRebuild} persists one
 * QUEUED job record per target cluster node under {@code /var/jobs} (the
 * search index is node-local, so every node rebuilds its own). Each node's
 * {@code SearchIndexRebuildService} picks up the record addressed to it —
 * through the replicated node events while running, or through its startup
 * scan when it was down at dispatch time — and rebuilds without interrupting
 * queries or writes.</p>
 *
 * <p>Authorization is deliberately not enforced here: the caller (the BPMN
 * dispatch script, which runs as a service user) validates the requesting
 * user's administrator role before calling. The job records are written with
 * a privileged session, attributed to the calling user.</p>
 */
public class SearchIndexAPI {

	private final WorkspaceScriptContext fContext;

	public SearchIndexAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static SearchIndexAPI get(ScriptingContext context) {
		return (SearchIndexAPI) context.getAttribute(SearchIndexAPI.class.getSimpleName());
	}

	/**
	 * Dispatches a rebuild to every alive cluster node (or just this node in
	 * a standalone deployment). Returns the created jobs as a map of
	 * {@code nodeId} to {@code jobId}.
	 *
	 * @param rebuildId correlation key shared by the per-node jobs of this
	 *                  request (persisted as {@code jobRebuildId})
	 */
	public Map<String, String> requestRebuild(String rebuildId) throws IOException {
		return requestRebuild(rebuildId, null);
	}

	/**
	 * Dispatches a rebuild to the given cluster nodes — the re-run path,
	 * where only the failed nodes are rebuilt again. {@code nodeIds} may be a
	 * collection, an array, or a comma-separated string; empty or null means
	 * every alive node.
	 */
	public Map<String, String> requestRebuild(String rebuildId, Object nodeIds) throws IOException {
		String id = (rebuildId == null) ? "" : rebuildId.trim();
		if (id.isEmpty()) {
			throw new IllegalArgumentException("rebuildId is required");
		}

		try {
			List<String> targets = listTargets(nodeIds);
			String userId = fContext.getResourceResolver().getSession().getUserID();

			Session session = CmsService.getRepository()
					.login(new CmsServiceCredentials(userId), fContext.getWorkspaceName());
			try {
				Map<String, String> jobs = new LinkedHashMap<>();
				for (String target : targets) {
					String jobId = JobNodes.newJobId();
					Node fileNode = JobNodes.createJobNode(session, jobId, SearchIndexRebuildJob.TYPE, userId, 0);
					Node content = JobNodes.getContent(fileNode);
					content.setProperty(JobNodes.PROP_NODE_ID, target);
					content.setProperty(JobNodes.PROP_REBUILD_ID, id);
					JobNodes.setStatus(content, JobStatus.QUEUED);
					jobs.put(target, jobId);
				}
				session.save();
				return jobs;
			} finally {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}
		} catch (IOException | RuntimeException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private List<String> listTargets(Object nodeIds) throws Exception {
		javax.jcr.Session jcrSession = fContext.getResourceResolver().getSession()
				.adaptTo(javax.jcr.Session.class);
		ClusterCoordinator coordinator = Adaptables.getAdapter(jcrSession, ClusterCoordinator.class);
		String self = (coordinator == null) ? null : coordinator.getNodeId();

		List<String> targets = new ArrayList<>();
		if (coordinator != null && coordinator.isClusterEnabled()) {
			for (ClusterCoordinator.Member member : coordinator.listMembers()) {
				if (member.isAlive() && !targets.contains(member.getNodeId())) {
					targets.add(member.getNodeId());
				}
			}
			// This node is dispatching, so it is alive whatever its heartbeat
			// record says.
			if (self != null && !targets.contains(self)) {
				targets.add(self);
			}
		} else if (self != null) {
			targets.add(self);
		}
		if (targets.isEmpty()) {
			throw new IOException("No target cluster nodes are available for the search index rebuild.");
		}

		String[] filter = Scripts.asStringArray(nodeIds);
		if (filter.length > 0) {
			Set<String> requested = new LinkedHashSet<>(Arrays.asList(filter));
			requested.removeIf(Strings::isEmpty);
			if (!requested.isEmpty()) {
				targets.retainAll(requested);
				if (targets.isEmpty()) {
					throw new IllegalArgumentException(
							"None of the requested nodes are alive cluster members: " + requested);
				}
			}
		}
		return targets;
	}

}

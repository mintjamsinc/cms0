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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProvider;
import org.mintjams.rt.cms.internal.operations.OperationNodes.DesiredState;
import org.mintjams.rt.cms.internal.operations.OperationNodes.NodeReport;
import org.mintjams.rt.cms.internal.operations.OperationNodes.NodeState;
import org.mintjams.rt.cms.internal.operations.OperationNodes.Presence;
import org.mintjams.rt.cms.internal.operations.OperationNodes.RouteState;
import org.mintjams.rt.cms.internal.operations.OperationNodes.Run;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.workspace.WorkspaceSettings;

/**
 * The entry point of operators' requests. Webtop cannot choose a node, so
 * every operation on a workspace or a route is a request to the whole
 * cluster: it records the desired state here, and every node — the one that
 * received the request included — converges on it ({@link WorkspaceReconciler}).
 * This class also builds the cluster-wide view of a workspace and its routes
 * for display, and lets a job wait until the nodes have converged.
 */
public final class WorkspaceOperations {

	private static final String SYSTEM_WORKSPACE_NAME = "system";
	private static final long AWAIT_POLL_INTERVAL_MILLIS = 1000L;

	/** The state of a workspace across the cluster, aggregated over the nodes that are alive. */
	public enum ClusterState {
		/** Running on every alive node, with the latest restart applied. */
		ONLINE,
		/** Meant to run, and some nodes have not got there yet. */
		STARTING,
		/** Meant to be stopped, and some nodes have not got there yet. */
		STOPPING,
		/** Stopped on every alive node. */
		STOPPED,
		/** Meant to run, and failed on some — not all — alive nodes. */
		DEGRADED,
		/** Meant to run, and failed on every alive node. */
		FAILED,
		/** Being deleted. */
		DELETING;
	}

	/** One node's view of a workspace. */
	public static final class NodeView {
		public final String nodeId;
		public final String hostName;
		public final boolean self;
		public final boolean alive;
		/** Whether the node is in the registry; false for a node that left but still has a report on record. */
		public final boolean registered;
		/** The node's state; null when the node has not reported yet. */
		public final NodeReport report;
		/** Whether the node has not applied the latest restart request yet. */
		public final boolean restartPending;

		NodeView(String nodeId, String hostName, boolean self, boolean alive, boolean registered, NodeReport report,
				boolean restartPending) {
			this.nodeId = nodeId;
			this.hostName = hostName;
			this.self = self;
			this.alive = alive;
			this.registered = registered;
			this.report = report;
			this.restartPending = restartPending;
		}
	}

	/** A workspace as seen across the cluster. */
	public static final class WorkspaceView {
		public final String workspaceName;
		/** The desired state; null for the system workspace, which is never managed. */
		public final DesiredState desired;
		public final List<NodeView> nodes;
		public final ClusterState clusterState;

		WorkspaceView(String workspaceName, DesiredState desired, List<NodeView> nodes, ClusterState clusterState) {
			this.workspaceName = workspaceName;
			this.desired = desired;
			this.nodes = nodes;
			this.clusterState = clusterState;
		}
	}

	/** One node's route statuses. */
	public static final class RouteNodeView {
		public final String nodeId;
		public final String hostName;
		public final boolean self;
		public final boolean alive;
		/** Route id to Camel status name; null when the node has not reported. */
		public final Map<String, String> statuses;

		RouteNodeView(String nodeId, String hostName, boolean self, boolean alive, Map<String, String> statuses) {
			this.nodeId = nodeId;
			this.hostName = hostName;
			this.self = self;
			this.alive = alive;
			this.statuses = statuses;
		}
	}

	/** The routes of a workspace as seen across the cluster. */
	public static final class RouteView {
		/** Desired route states; a route not in the map follows its definition. */
		public final Map<String, RouteState> desiredStates;
		public final List<RouteNodeView> nodes;

		RouteView(Map<String, RouteState> desiredStates, List<RouteNodeView> nodes) {
			this.desiredStates = desiredStates;
			this.nodes = nodes;
		}
	}

	/** How a node stands against what an operation waits for. */
	public enum Progress {
		PENDING,
		DONE,
		FAILED;
	}

	/** Judges one alive node while an operation waits for the cluster to converge. */
	@FunctionalInterface
	public interface NodeCheck {
		Progress check(NodeView node);
	}

	@FunctionalInterface
	private interface SessionWork<T> {
		T run(Session session) throws Exception;
	}

	private WorkspaceOperations() {}

	// ---- requests ----------------------------------------------------------

	public static DesiredState readDesired(String workspaceName) throws RepositoryException {
		return read(session -> OperationNodes.readDesired(session, workspaceName));
	}

	/**
	 * Asks every node to run the workspace. Nodes on which an earlier start
	 * failed are asked to try again, so the request is answered on every node.
	 *
	 * @return the retry generation requested from each alive node
	 */
	public static Map<String, Long> requestStart(String workspaceName, String userId) throws RepositoryException {
		Map<String, Long> requested = write(userId, session -> {
			DesiredState desired = requireManaged(session, workspaceName);
			OperationNodes.writeDesired(session, workspaceName, desired.withRun(Run.RUNNING));
			return requestRetries(session, workspaceName, null);
		});
		nudge(workspaceName);
		return requested;
	}

	/** Asks every node to stop the workspace. */
	public static void requestStop(String workspaceName, String userId) throws RepositoryException {
		write(userId, session -> {
			DesiredState desired = requireManaged(session, workspaceName);
			OperationNodes.writeDesired(session, workspaceName, desired.withRun(Run.STOPPED));
			return null;
		});
		nudge(workspaceName);
	}

	/**
	 * Asks every node to restart the workspace once — how configuration read
	 * only at start time (the engine switches) is applied.
	 *
	 * @return the restart generation every node has to apply
	 */
	public static long requestRestart(String workspaceName, String userId) throws RepositoryException {
		long generation = write(userId, session -> {
			DesiredState desired = requireManaged(session, workspaceName);
			long next = desired.restartGeneration + 1;
			OperationNodes.writeDesired(session, workspaceName,
					desired.withRun(Run.RUNNING).withRestartGeneration(next));
			return next;
		});
		nudge(workspaceName);
		return generation;
	}

	/**
	 * Asks nodes on which the workspace failed to start to try again.
	 *
	 * @param nodeIds the nodes to ask; {@code null} for every alive node the workspace failed on
	 * @return the retry generation requested from each node asked
	 */
	public static Map<String, Long> requestRetry(String workspaceName, Collection<String> nodeIds, String userId)
			throws RepositoryException {
		Map<String, Long> requested = write(userId, session -> {
			requireManaged(session, workspaceName);
			Collection<String> targets = nodeIds;
			if (targets == null) {
				targets = new ArrayList<>();
				for (NodeView node : buildView(session, workspaceName, ClusterNodes.list(session)).nodes) {
					if (node.alive && node.report != null && node.report.state == NodeState.FAILED) {
						targets.add(node.nodeId);
					}
				}
			}
			return requestRetries(session, workspaceName, targets);
		});
		nudge(workspaceName);
		return requested;
	}

	/**
	 * Records a newly created workspace as present and running, replacing any
	 * record a previous workspace of the same name left behind.
	 */
	public static void declarePresent(String workspaceName, String userId) throws RepositoryException {
		write(userId, session -> {
			OperationNodes.removeWorkspace(session, workspaceName);
			OperationNodes.writeDesired(session, workspaceName, new DesiredState(Presence.PRESENT, Run.RUNNING, 0L));
			return null;
		});
		nudge(workspaceName);
	}

	/** Asks every node to stop the workspace's services and close it, ahead of removing its directory. */
	public static void declareAbsent(String workspaceName, String userId) throws RepositoryException {
		write(userId, session -> {
			DesiredState desired = OperationNodes.readDesired(session, workspaceName);
			if (desired == null) {
				desired = new DesiredState(Presence.PRESENT, Run.STOPPED, 0L);
			}
			OperationNodes.writeDesired(session, workspaceName, desired.withPresence(Presence.ABSENT));
			return null;
		});
		nudge(workspaceName);
	}

	/** Removes a workspace's whole record once its deletion is complete. */
	public static void removeRecord(String workspaceName, String userId) throws RepositoryException {
		write(userId, session -> {
			OperationNodes.removeWorkspace(session, workspaceName);
			return null;
		});
	}

	/** Records a desired route state for every node; {@code null} lets the route follow its definition again. */
	public static void setRouteState(String workspaceName, String routeId, RouteState state, String userId)
			throws RepositoryException {
		write(userId, session -> {
			OperationNodes.writeRouteState(session, workspaceName, routeId, state);
			return null;
		});
	}

	private static DesiredState requireManaged(Session session, String workspaceName) throws RepositoryException {
		if (SYSTEM_WORKSPACE_NAME.equals(workspaceName)) {
			throw new RepositoryException("The system workspace always runs.");
		}
		DesiredState desired = OperationNodes.readDesired(session, workspaceName);
		if (desired == null) {
			if (!Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(workspaceName)) {
				throw new RepositoryException("Workspace not found: " + workspaceName);
			}
			desired = new DesiredState(Presence.PRESENT,
					WorkspaceSettings.isAutoStartOf(workspaceName) ? Run.RUNNING : Run.STOPPED, 0L);
		}
		if (desired.presence == Presence.ABSENT) {
			throw new RepositoryException("The workspace '" + workspaceName + "' is being deleted.");
		}
		return desired;
	}

	private static Map<String, Long> requestRetries(Session session, String workspaceName, Collection<String> nodeIds)
			throws RepositoryException, IOException {
		Map<String, Long> requested = new LinkedHashMap<>();
		for (ClusterNodes.NodeInfo node : ClusterNodes.list(session)) {
			if (!node.alive || (nodeIds != null && !nodeIds.contains(node.nodeId))) {
				continue;
			}
			long next = OperationNodes.readRetryGeneration(session, workspaceName, node.nodeId) + 1;
			OperationNodes.writeRetryGeneration(session, workspaceName, node.nodeId, next);
			requested.put(node.nodeId, next);
		}
		return requested;
	}

	/** The receiving node converges at once instead of waiting for its own event. */
	private static void nudge(String workspaceName) {
		WorkspaceReconciler reconciler = CmsService.getWorkspaceReconciler();
		if (reconciler != null) {
			reconciler.requestReconcile(workspaceName);
		}
	}

	// ---- views -------------------------------------------------------------

	public static WorkspaceView view(String workspaceName) throws RepositoryException {
		return views(List.of(workspaceName)).get(workspaceName);
	}

	/** The cluster-wide views of several workspaces, reading the node registry once. */
	public static Map<String, WorkspaceView> views(Collection<String> workspaceNames) throws RepositoryException {
		return read(session -> {
			List<ClusterNodes.NodeInfo> cluster = ClusterNodes.list(session);
			Map<String, WorkspaceView> views = new LinkedHashMap<>();
			for (String workspaceName : workspaceNames) {
				views.put(workspaceName, buildView(session, workspaceName, cluster));
			}
			return views;
		});
	}

	private static WorkspaceView buildView(Session session, String workspaceName, List<ClusterNodes.NodeInfo> cluster)
			throws RepositoryException {
		DesiredState desired = SYSTEM_WORKSPACE_NAME.equals(workspaceName) ? null
				: OperationNodes.readDesired(session, workspaceName);
		Map<String, NodeReport> reports = OperationNodes.readReports(session, workspaceName);
		WorkspaceReconciler reconciler = CmsService.getWorkspaceReconciler();

		List<NodeView> nodes = new ArrayList<>();
		for (ClusterNodes.NodeInfo node : cluster) {
			NodeReport report = reports.remove(node.nodeId);
			if (node.self && reconciler != null && reconciler.getNodeId() != null) {
				// The answering node shows its own state live, not its last report.
				report = reconciler.describeLocal(workspaceName);
			}
			nodes.add(new NodeView(node.nodeId, node.hostName, node.self, node.alive, true, report,
					isRestartPending(desired, report)));
		}
		for (NodeReport report : reports.values()) {
			nodes.add(new NodeView(report.nodeId, null, false, false, false, report, false));
		}
		return new WorkspaceView(workspaceName, desired, nodes, clusterStateOf(desired, nodes));
	}

	private static boolean isRestartPending(DesiredState desired, NodeReport report) {
		return desired != null && report != null && report.state != NodeState.CLOSED
				&& report.appliedRestartGeneration < desired.restartGeneration;
	}

	static ClusterState clusterStateOf(DesiredState desired, List<NodeView> nodes) {
		if (desired != null && desired.presence == Presence.ABSENT) {
			return ClusterState.DELETING;
		}
		Run run = (desired == null) ? Run.RUNNING : desired.run;
		int alive = 0;
		int done = 0;
		int failed = 0;
		for (NodeView node : nodes) {
			if (!node.alive) {
				continue;
			}
			alive++;
			NodeState state = (node.report == null) ? null : node.report.state;
			if (state == NodeState.FAILED) {
				failed++;
			} else if (run == Run.RUNNING) {
				if (state == NodeState.ONLINE && !node.restartPending) {
					done++;
				}
			} else if (state == NodeState.STOPPED || state == NodeState.CLOSED) {
				done++;
			}
		}

		if (alive == 0) {
			return (run == Run.RUNNING) ? ClusterState.STARTING : ClusterState.STOPPED;
		}
		if (run == Run.RUNNING) {
			if (done == alive) {
				return ClusterState.ONLINE;
			}
			if (failed == alive) {
				return ClusterState.FAILED;
			}
			return (failed > 0) ? ClusterState.DEGRADED : ClusterState.STARTING;
		}
		return (done == alive) ? ClusterState.STOPPED : ClusterState.STOPPING;
	}

	/** The desired route states and every node's route statuses; this node's come live from its engine. */
	public static RouteView routeView(String workspaceName) throws RepositoryException {
		return read(session -> {
			Map<String, RouteState> desiredStates = OperationNodes.readRouteStates(session, workspaceName);
			Map<String, Map<String, String>> reports = OperationNodes.readRouteReports(session, workspaceName);
			List<RouteNodeView> nodes = new ArrayList<>();
			for (ClusterNodes.NodeInfo node : ClusterNodes.list(session)) {
				Map<String, String> statuses = node.self ? localRouteStatuses(workspaceName) : reports.get(node.nodeId);
				nodes.add(new RouteNodeView(node.nodeId, node.hostName, node.self, node.alive, statuses));
			}
			return new RouteView(desiredStates, nodes);
		});
	}

	private static Map<String, String> localRouteStatuses(String workspaceName) {
		Map<String, String> statuses = new TreeMap<>();
		WorkspaceIntegrationEngineProvider provider = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		if (provider == null || !provider.isAvailable()) {
			return statuses;
		}
		CamelContext context = provider.getCamelContext();
		for (List<String> routeIds : provider.getDeployments().values()) {
			for (String routeId : routeIds) {
				ServiceStatus status = context.getRouteController().getRouteStatus(routeId);
				statuses.put(routeId, (status != null) ? status.name() : "Unknown");
			}
		}
		return statuses;
	}

	// ---- waiting -----------------------------------------------------------

	/**
	 * Waits until every alive node is done or has failed. Nodes that die while
	 * waiting drop out; they converge when they come back.
	 *
	 * @param progress receives (settled nodes, alive nodes) after every poll; may be null
	 * @return the nodes that failed; empty when every alive node is done
	 * @throws RepositoryException when the timeout passes first, naming the nodes still pending
	 */
	public static List<NodeView> await(String workspaceName, NodeCheck check, long timeoutMillis,
			BiConsumer<Integer, Integer> progress) throws RepositoryException, InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (true) {
			WorkspaceView view = view(workspaceName);
			List<NodeView> pending = new ArrayList<>();
			List<NodeView> failed = new ArrayList<>();
			int alive = 0;
			for (NodeView node : view.nodes) {
				if (!node.alive) {
					continue;
				}
				alive++;
				switch (check.check(node)) {
				case DONE:
					break;
				case FAILED:
					failed.add(node);
					break;
				default:
					pending.add(node);
				}
			}
			if (progress != null) {
				progress.accept(alive - pending.size(), alive);
			}
			if (pending.isEmpty()) {
				return failed;
			}
			if (System.currentTimeMillis() >= deadline) {
				List<String> nodeIds = new ArrayList<>();
				for (NodeView node : pending) {
					nodeIds.add(node.nodeId);
				}
				throw new RepositoryException("Timed out waiting for the node(s): " + String.join(", ", nodeIds));
			}
			Thread.sleep(AWAIT_POLL_INTERVAL_MILLIS);
		}
	}

	// ---- sessions ----------------------------------------------------------

	private static <T> T read(SessionWork<T> work) throws RepositoryException {
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), SYSTEM_WORKSPACE_NAME);
		try {
			return work.run(session);
		} catch (RepositoryException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new RepositoryException(ex.getMessage(), ex);
		} finally {
			session.logout();
		}
	}

	private static <T> T write(String userId, SessionWork<T> work) throws RepositoryException {
		CmsServiceCredentials credentials = (userId != null) ? new CmsServiceCredentials(userId) : new CmsServiceCredentials();
		Session session = CmsService.getRepository().login(credentials, SYSTEM_WORKSPACE_NAME);
		try {
			T result = work.run(session);
			session.save();
			return result;
		} catch (Exception ex) {
			try {
				session.refresh(false);
			} catch (Throwable ignore) {}
			if (ex instanceof RepositoryException) {
				throw (RepositoryException) ex;
			}
			throw new RepositoryException(ex.getMessage(), ex);
		} finally {
			session.logout();
		}
	}

}

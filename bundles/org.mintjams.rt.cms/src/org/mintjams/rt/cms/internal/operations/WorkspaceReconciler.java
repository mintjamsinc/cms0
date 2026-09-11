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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import org.mintjams.jcr.WorkspaceManager;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProvider;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProvider;
import org.mintjams.rt.cms.internal.operations.OperationNodes.DesiredState;
import org.mintjams.rt.cms.internal.operations.OperationNodes.NodeReport;
import org.mintjams.rt.cms.internal.operations.OperationNodes.NodeState;
import org.mintjams.rt.cms.internal.operations.OperationNodes.Presence;
import org.mintjams.rt.cms.internal.operations.OperationNodes.RouteState;
import org.mintjams.rt.cms.internal.operations.OperationNodes.Run;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.workspace.WorkspaceSettings;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

/**
 * Brings this node's workspaces to the cluster-wide desired state recorded in
 * {@link OperationNodes}, and reports what this node actually runs.
 *
 * <p>Webtop operations never act on the node that happens to receive them;
 * they write the desired state, and every node — the receiving one included —
 * converges on it here. A node reconciles:
 * <ul>
 *   <li>at boot, before any workspace starts ({@link #bootstrap()});</li>
 *   <li>when a record under {@code /var/operations/workspaces} changes — the
 *       write reaches every node through the cluster journal as a local node
 *       event;</li>
 *   <li>on a periodic full pass, which catches anything an event did not
 *       deliver and opens workspaces created while an event was missed.</li>
 * </ul>
 *
 * <p>A node that was down simply converges when it boots, so the order in
 * which operations were requested never matters. Starting services that
 * failed is not retried automatically, which would loop on a broken
 * workspace; an operator asks for a retry, recorded per node as a retry
 * generation. A restart is an action rather than a state, recorded as a
 * restart generation every node applies once.
 */
public class WorkspaceReconciler implements Closeable {

	private static final String SYSTEM_WORKSPACE_NAME = "system";
	/** Interval of the full pass that catches anything an event did not deliver. */
	private static final long FULL_PASS_INTERVAL_MILLIS = 30000L;
	/** Route events arrive in bursts (a deployment starts every route); report once they settle. */
	private static final long ROUTE_REPORT_DELAY_MILLIS = 1000L;
	/** Reports of a node that left the registry are dropped once they are this old. */
	private static final long DEPARTED_NODE_RETENTION_MILLIS = 24L * 60L * 60L * 1000L;
	private static final String NODE_EVENT_TOPIC = javax.jcr.Node.class.getName().replace(".", "/") + "/*";
	private static final String ROUTE_EVENT_TOPIC = "org/apache/camel/Route/*";

	private final Closer fCloser = Closer.create();
	private final Object fLock = new Object();
	private final Set<String> fPendingWorkspaces = new LinkedHashSet<>();
	private final Set<String> fPendingRouteReports = new LinkedHashSet<>();
	private long fRouteReportsDueAt;
	private boolean fFullPassRequested;
	private volatile boolean fCloseRequested;
	private Thread fThread;
	private volatile String fNodeId;
	/** The restart generation this node has applied, per workspace. */
	private final Map<String, Long> fAppliedRestart = new ConcurrentHashMap<>();
	/** The retry generation this node has applied, per workspace. */
	private final Map<String, Long> fAppliedRetry = new ConcurrentHashMap<>();
	/** The last state report written, per workspace, so an unchanged report is not rewritten. */
	private final Map<String, String> fLastReports = new ConcurrentHashMap<>();
	/** The last route report written, per workspace. */
	private final Map<String, String> fLastRouteReports = new ConcurrentHashMap<>();
	private final Map<String, Object> fWorkspaceLocks = new ConcurrentHashMap<>();

	/**
	 * Settles the desired state before this node starts any workspace, so a
	 * node joining a running cluster starts what the cluster runs within its
	 * own boot sequence, rather than coming up stopped and catching up later.
	 * <ul>
	 *   <li>When no other node is alive, the cluster is starting from fully
	 *       stopped: every workspace's run state is reset from its auto-start
	 *       setting, and deletions left unfinished are completed — nothing else
	 *       can still have those workspaces open.</li>
	 *   <li>Otherwise the running cluster's desired state is followed as is.</li>
	 * </ul>
	 * A workspace without a record gets one, its run state from auto-start.
	 * Since a fresh boot starts services with the current configuration, every
	 * restart requested before it counts as applied.
	 *
	 * @return the workspaces this node must leave stopped at boot
	 */
	public Set<String> bootstrap() throws RepositoryException, IOException {
		Set<String> stopped = new HashSet<>();
		Session session = login();
		try {
			fNodeId = ClusterNodes.currentNodeId(session);
			boolean coldStart = !ClusterNodes.isAnyOtherNodeAlive(session);
			List<String> unfinishedDeletions = new ArrayList<>();

			for (String workspaceName : session.getWorkspace().getAccessibleWorkspaceNames()) {
				if (SYSTEM_WORKSPACE_NAME.equals(workspaceName)) {
					continue;
				}

				Run autoStartRun = autoStartRunOf(workspaceName);
				DesiredState desired = OperationNodes.readDesired(session, workspaceName);
				if (desired == null) {
					desired = new DesiredState(Presence.PRESENT, autoStartRun, 0L);
					OperationNodes.writeDesired(session, workspaceName, desired);
				} else if (desired.presence == Presence.ABSENT) {
					if (coldStart) {
						unfinishedDeletions.add(workspaceName);
					}
					stopped.add(workspaceName);
					continue;
				} else if (coldStart && desired.run != autoStartRun) {
					desired = desired.withRun(autoStartRun);
					OperationNodes.writeDesired(session, workspaceName, desired);
				}

				fAppliedRestart.put(workspaceName, desired.restartGeneration);
				fAppliedRetry.put(workspaceName, OperationNodes.readRetryGeneration(session, workspaceName, fNodeId));
				if (desired.run != Run.RUNNING) {
					stopped.add(workspaceName);
				}
			}
			try {
				session.save();
			} catch (RepositoryException ex) {
				// Another node booting at the same moment wrote the same records;
				// the values it wrote are the ones computed here.
				session.refresh(false);
				CmsService.getLogger(getClass()).warn("Could not record the desired workspace states at boot.", ex);
			}

			WorkspaceManager workspaceManager = workspaceManager(session);
			for (String workspaceName : unfinishedDeletions) {
				try {
					workspaceManager.closeWorkspace(workspaceName);
					workspaceManager.removeWorkspaceDirectory(workspaceName);
					OperationNodes.removeWorkspace(session, workspaceName);
					session.save();
					CmsService.getLogger(getClass()).info("Completed the unfinished deletion of the workspace: " + workspaceName);
					CmsService.postWorkspaceDeleted(workspaceName);
				} catch (Throwable ex) {
					refreshQuietly(session);
					CmsService.getLogger(getClass()).warn("Could not complete the unfinished deletion of the workspace: " + workspaceName, ex);
				}
			}
			if (coldStart) {
				CmsService.getLogger(getClass()).info("No other cluster node is alive; workspace run states follow their auto-start settings.");
			}
		} finally {
			logoutQuietly(session);
		}
		return stopped;
	}

	public synchronized WorkspaceReconciler open() {
		if (fThread != null) {
			return this;
		}

		if (fNodeId == null) {
			Session session = null;
			try {
				session = login();
				fNodeId = ClusterNodes.currentNodeId(session);
			} catch (Throwable ex) {
				CmsService.getLogger(getClass()).error("Could not determine this node's identifier.", ex);
				return this;
			} finally {
				logoutQuietly(session);
			}
		}

		fCloser.register(Registration.newBuilder(EventHandler.class)
				.setService((EventHandler) this::handleNodeEvent)
				.setProperty(EventConstants.EVENT_TOPIC, new String[] { NODE_EVENT_TOPIC })
				.setProperty(EventConstants.EVENT_FILTER, "(workspace=" + SYSTEM_WORKSPACE_NAME + ")")
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());
		fCloser.register(Registration.newBuilder(EventHandler.class)
				.setService((EventHandler) this::handleRouteEvent)
				.setProperty(EventConstants.EVENT_TOPIC, new String[] { ROUTE_EVENT_TOPIC })
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());

		fFullPassRequested = true;
		fThread = new Thread(new Task(), getClass().getSimpleName());
		fThread.setDaemon(true);
		fThread.start();
		return this;
	}

	@Override
	public void close() throws IOException {
		fCloseRequested = true;
		fCloser.close();
		synchronized (fLock) {
			fLock.notifyAll();
		}
		Thread thread = fThread;
		if (thread != null) {
			try {
				thread.interrupt();
				thread.join(10000);
			} catch (InterruptedException ignore) {}
			fThread = null;
		}
	}

	/** This node's identifier, or null before {@link #bootstrap()} or {@link #open()}. */
	public String getNodeId() {
		return fNodeId;
	}

	/** Asks for a workspace to be reconciled on the reconciler's thread. */
	public void requestReconcile(String workspaceName) {
		synchronized (fLock) {
			fPendingWorkspaces.add(workspaceName);
			fLock.notifyAll();
		}
	}

	/**
	 * Reconciles a workspace on the calling thread, serialised with the
	 * reconciler's own work on the same workspace. Failures are logged, and
	 * surface to the cluster as this node's report.
	 */
	public void reconcile(String workspaceName) {
		synchronized (lockOf(workspaceName)) {
			Session session = null;
			try {
				session = login();
				doReconcile(session, workspaceName);
			} catch (Throwable ex) {
				refreshQuietly(session);
				CmsService.getLogger(getClass()).warn("An error occurred while reconciling the workspace: " + workspaceName, ex);
			} finally {
				logoutQuietly(session);
			}
		}
	}

	/**
	 * Applies the desired states of the given routes to this node's engine —
	 * called right after routes are (re)deployed, which otherwise start or
	 * stay stopped as their definitions say.
	 */
	public void applyRouteStates(String workspaceName, Collection<String> routeIds) {
		if (routeIds == null || routeIds.isEmpty()) {
			return;
		}
		Session session = null;
		try {
			session = login();
			applyRouteStates(session, workspaceName, routeIds);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Could not apply the desired route states: " + workspaceName, ex);
		} finally {
			logoutQuietly(session);
		}
	}

	/**
	 * The state of a workspace on this node, read live rather than from the
	 * node's last report, so the node answering a request shows its own state
	 * without delay.
	 */
	public NodeReport describeLocal(String workspaceName) {
		NodeReport report = new NodeReport();
		report.nodeId = fNodeId;
		if (CmsService.getWorkspaceServletProvider(workspaceName) != null) {
			report.state = NodeState.ONLINE;
		} else if (!Arrays.asList(CmsService.getRepositoryWorkspaceNames()).contains(workspaceName)) {
			report.state = NodeState.CLOSED;
		} else if (CmsService.getWorkspaceStartError(workspaceName) != null) {
			report.state = NodeState.FAILED;
			report.message = CmsService.getWorkspaceStartError(workspaceName);
		} else if (CmsService.isWorkspaceStopped(workspaceName)) {
			report.state = NodeState.STOPPED;
		} else {
			report.state = NodeState.STARTING;
		}

		WorkspaceProcessEngineProvider processEngine = CmsService.getWorkspaceProcessEngineProvider(workspaceName);
		report.processEngineEnabled = (processEngine != null) ? processEngine.isEnabled() : null;
		report.processEngineRunning = (processEngine != null && processEngine.isAvailable());
		WorkspaceIntegrationEngineProvider integrationEngine = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		report.integrationEngineEnabled = (integrationEngine != null) ? integrationEngine.isEnabled() : null;
		report.integrationEngineRunning = (integrationEngine != null && integrationEngine.isAvailable());
		report.appliedRestartGeneration = fAppliedRestart.getOrDefault(workspaceName, 0L);
		report.appliedRetryGeneration = fAppliedRetry.getOrDefault(workspaceName, 0L);
		report.updated = Calendar.getInstance();
		return report;
	}

	// ---- events ------------------------------------------------------------

	private void handleNodeEvent(Event event) {
		Object path = event.getProperty("path");
		if (path == null) {
			return;
		}
		String workspaceName = OperationNodes.workspaceOf(path.toString());
		if (workspaceName == null) {
			return;
		}
		// Reports describe a node; they never ask anything of this one.
		if (OperationNodes.isStateReportPath(path.toString()) || OperationNodes.isRouteReportPath(path.toString())) {
			return;
		}
		requestReconcile(workspaceName);
	}

	private void handleRouteEvent(Event event) {
		Object workspaceName = event.getProperty("workspace");
		if (workspaceName == null) {
			return;
		}
		synchronized (fLock) {
			if (fPendingRouteReports.isEmpty()) {
				fRouteReportsDueAt = System.currentTimeMillis() + ROUTE_REPORT_DELAY_MILLIS;
			}
			fPendingRouteReports.add(workspaceName.toString());
			fLock.notifyAll();
		}
	}

	// ---- reconciliation ----------------------------------------------------

	private void doReconcile(Session session, String workspaceName) throws Exception {
		WorkspaceManager workspaceManager = workspaceManager(session);
		boolean open = Arrays.asList(workspaceManager.getOpenWorkspaceNames()).contains(workspaceName);

		if (SYSTEM_WORKSPACE_NAME.equals(workspaceName)) {
			// The system workspace always runs; it is reported, never acted on.
			writeReport(session, workspaceName, describeLocal(workspaceName));
			return;
		}

		DesiredState desired = OperationNodes.readDesired(session, workspaceName);
		if (desired == null) {
			if (!open) {
				forget(session, workspaceName);
				return;
			}
			// A workspace created through the JCR API directly has no record yet:
			// adopt it, so the other nodes open it too.
			desired = new DesiredState(Presence.PRESENT, autoStartRunOf(workspaceName), 0L);
			OperationNodes.writeDesired(session, workspaceName, desired);
			session.save();
		}

		if (desired.presence == Presence.ABSENT) {
			if (open) {
				CmsService.getDefault().stopWorkspaceServices(workspaceName);
				workspaceManager.closeWorkspace(workspaceName);
				CmsService.getDefault().forgetWorkspace(workspaceName);
			}
			NodeReport report = describeLocal(workspaceName);
			report.state = NodeState.CLOSED;
			report.message = null;
			writeReport(session, workspaceName, report);
			return;
		}

		long retryGeneration = OperationNodes.readRetryGeneration(session, workspaceName, fNodeId);
		if (!open) {
			if (!workspaceManager.isWorkspaceAvailable(workspaceName)) {
				writeFailure(session, workspaceName, "The workspace directory is not available on this node.");
				return;
			}
			try {
				workspaceManager.openWorkspace(workspaceName);
			} catch (Throwable ex) {
				writeFailure(session, workspaceName, messageOf(ex));
				return;
			}
			if (desired.run != Run.RUNNING) {
				// Opened without services: report it as deliberately stopped.
				CmsService.getDefault().stopWorkspaceServices(workspaceName);
			}
			// Services start from scratch on a newly opened workspace.
			fAppliedRestart.put(workspaceName, desired.restartGeneration);
		}

		boolean running = (CmsService.getWorkspaceServletProvider(workspaceName) != null);
		boolean failed = !running && (CmsService.getWorkspaceStartError(workspaceName) != null);
		boolean stopped = !running && CmsService.isWorkspaceStopped(workspaceName);
		Long knownRestart = fAppliedRestart.get(workspaceName);
		if (knownRestart == null) {
			// Nothing recorded for this workspace on this node: there is no
			// restart it could still owe.
			knownRestart = desired.restartGeneration;
			fAppliedRestart.put(workspaceName, knownRestart);
		}
		long appliedRestart = knownRestart;
		long appliedRetry = fAppliedRetry.getOrDefault(workspaceName, 0L);

		if (desired.restartGeneration > appliedRestart) {
			fAppliedRestart.put(workspaceName, desired.restartGeneration);
			fAppliedRetry.put(workspaceName, retryGeneration);
			if (desired.run == Run.RUNNING) {
				writeStarting(session, workspaceName);
				CmsService.getDefault().stopWorkspaceServices(workspaceName);
				startServices(workspaceName);
			} else if (!stopped) {
				CmsService.getDefault().stopWorkspaceServices(workspaceName);
			}
		} else if (desired.run == Run.RUNNING) {
			if (running) {
				fAppliedRetry.put(workspaceName, Math.max(appliedRetry, retryGeneration));
			} else if (!failed || retryGeneration > appliedRetry) {
				fAppliedRetry.put(workspaceName, retryGeneration);
				writeStarting(session, workspaceName);
				startServices(workspaceName);
			}
		} else {
			fAppliedRetry.put(workspaceName, Math.max(appliedRetry, retryGeneration));
			if (!stopped) {
				CmsService.getDefault().stopWorkspaceServices(workspaceName);
			}
		}

		applyRouteStates(session, workspaceName, null);
		writeReport(session, workspaceName, describeLocal(workspaceName));
	}

	private void startServices(String workspaceName) {
		try {
			CmsService.getDefault().startWorkspaceServices(workspaceName);
		} catch (Throwable ex) {
			// Recorded by CmsService as the workspace's start error and reported
			// to the cluster as this node's FAILED state.
			CmsService.getLogger(getClass()).warn("The workspace services failed to start: " + workspaceName, ex);
		}
	}

	/** Drops everything this node recorded for a workspace that no longer exists. */
	private void forget(Session session, String workspaceName) throws RepositoryException {
		fAppliedRestart.remove(workspaceName);
		fAppliedRetry.remove(workspaceName);
		fLastReports.remove(workspaceName);
		fLastRouteReports.remove(workspaceName);
		if (OperationNodes.readReport(session, workspaceName, fNodeId) != null) {
			OperationNodes.removeNode(session, workspaceName, fNodeId);
			session.save();
		}
	}

	// ---- reports -----------------------------------------------------------

	private void writeStarting(Session session, String workspaceName) throws RepositoryException {
		NodeReport report = describeLocal(workspaceName);
		report.state = NodeState.STARTING;
		report.message = null;
		writeReport(session, workspaceName, report);
	}

	private void writeFailure(Session session, String workspaceName, String message) throws RepositoryException {
		NodeReport report = describeLocal(workspaceName);
		report.state = NodeState.FAILED;
		report.message = message;
		writeReport(session, workspaceName, report);
	}

	private void writeReport(Session session, String workspaceName, NodeReport report) throws RepositoryException {
		// A workspace whose record is gone (its deletion completed) is not
		// reported, or the report would bring back a part of the record.
		if (!SYSTEM_WORKSPACE_NAME.equals(workspaceName) && OperationNodes.readDesired(session, workspaceName) == null) {
			return;
		}
		String fingerprint = report.fingerprint();
		if (fingerprint.equals(fLastReports.get(workspaceName))
				&& OperationNodes.readReport(session, workspaceName, fNodeId) != null) {
			return;
		}
		OperationNodes.writeReport(session, workspaceName, report);
		session.save();
		fLastReports.put(workspaceName, fingerprint);
	}

	private void reportRoutes(Session session, String workspaceName) throws RepositoryException {
		if (OperationNodes.readDesired(session, workspaceName) == null) {
			return;
		}
		Map<String, String> statuses = new TreeMap<>();
		WorkspaceIntegrationEngineProvider provider = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		if (provider != null && provider.isAvailable()) {
			CamelContext context = provider.getCamelContext();
			for (List<String> routeIds : provider.getDeployments().values()) {
				for (String routeId : routeIds) {
					ServiceStatus status = context.getRouteController().getRouteStatus(routeId);
					statuses.put(routeId, (status != null) ? status.name() : "Unknown");
				}
			}
		}
		String fingerprint = statuses.toString();
		if (fingerprint.equals(fLastRouteReports.get(workspaceName))) {
			return;
		}
		OperationNodes.writeRouteReport(session, workspaceName, fNodeId, statuses);
		session.save();
		fLastRouteReports.put(workspaceName, fingerprint);
	}

	/**
	 * Drops the records of nodes that left the registry long ago (replaced or
	 * decommissioned), so their last reports do not linger as "offline" rows.
	 */
	private void removeDepartedNodes(Session session) throws RepositoryException, IOException {
		Set<String> registered = new HashSet<>();
		for (ClusterNodes.NodeInfo node : ClusterNodes.list(session)) {
			registered.add(node.nodeId);
		}
		long threshold = System.currentTimeMillis() - DEPARTED_NODE_RETENTION_MILLIS;
		boolean changed = false;
		for (String workspaceName : OperationNodes.listWorkspaces(session)) {
			for (NodeReport report : OperationNodes.readReports(session, workspaceName).values()) {
				if (registered.contains(report.nodeId) || report.updated == null
						|| report.updated.getTimeInMillis() > threshold) {
					continue;
				}
				OperationNodes.removeNode(session, workspaceName, report.nodeId);
				changed = true;
			}
		}
		if (changed) {
			session.save();
		}
	}

	// ---- routes ------------------------------------------------------------

	private void applyRouteStates(Session session, String workspaceName, Collection<String> routeIds)
			throws RepositoryException {
		WorkspaceIntegrationEngineProvider provider = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		if (provider == null || !provider.isAvailable()) {
			return;
		}
		Map<String, RouteState> states = OperationNodes.readRouteStates(session, workspaceName);
		if (states.isEmpty()) {
			return;
		}
		CamelContext context = provider.getCamelContext();
		for (Map.Entry<String, RouteState> entry : states.entrySet()) {
			if (routeIds != null && !routeIds.contains(entry.getKey())) {
				continue;
			}
			applyRouteState(context, workspaceName, entry.getKey(), entry.getValue());
		}
	}

	/** Brings one route on this node's engine to a desired state; a route not deployed here is left alone. */
	static void applyRouteState(CamelContext context, String workspaceName, String routeId, RouteState state) {
		ServiceStatus status = context.getRouteController().getRouteStatus(routeId);
		if (status == null) {
			return;
		}
		try {
			switch (state) {
			case STARTED:
				if (status.isSuspended()) {
					context.getRouteController().resumeRoute(routeId);
				} else if (!status.isStarted()) {
					context.getRouteController().startRoute(routeId);
				}
				break;
			case STOPPED:
				if (!status.isStopped()) {
					context.getRouteController().stopRoute(routeId);
				}
				break;
			case SUSPENDED:
				if (status.isStopped()) {
					context.getRouteController().startRoute(routeId);
				}
				if (!context.getRouteController().getRouteStatus(routeId).isSuspended()) {
					context.getRouteController().suspendRoute(routeId);
				}
				break;
			}
		} catch (Exception ex) {
			CmsService.getLogger(WorkspaceReconciler.class).warn("Could not bring the route '" + routeId
					+ "' to the state " + state + " in the workspace: " + workspaceName, ex);
		}
	}

	// ---- helpers -----------------------------------------------------------

	private Object lockOf(String workspaceName) {
		return fWorkspaceLocks.computeIfAbsent(workspaceName, k -> new Object());
	}

	private static Run autoStartRunOf(String workspaceName) {
		return WorkspaceSettings.isAutoStartOf(workspaceName) ? Run.RUNNING : Run.STOPPED;
	}

	private static Session login() throws RepositoryException {
		return CmsService.getRepository().login(new CmsServiceCredentials(), SYSTEM_WORKSPACE_NAME);
	}

	static WorkspaceManager workspaceManager(Session session) throws RepositoryException {
		WorkspaceManager workspaceManager = Adaptables.getAdapter(session, WorkspaceManager.class);
		if (workspaceManager == null) {
			throw new RepositoryException("The repository does not support workspace management.");
		}
		return workspaceManager;
	}

	private static void refreshQuietly(Session session) {
		if (session != null) {
			try {
				session.refresh(false);
			} catch (Throwable ignore) {}
		}
	}

	private static void logoutQuietly(Session session) {
		if (session != null) {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}
	}

	private static String messageOf(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t.getMessage() != null && !t.getMessage().isBlank()) {
				return t.getMessage();
			}
		}
		return ex.getClass().getSimpleName();
	}

	private class Task implements Runnable {
		@Override
		public void run() {
			long nextFullPass = 0L;
			while (!fCloseRequested) {
				Set<String> workspaces;
				Set<String> routeReports;
				boolean fullPass;
				synchronized (fLock) {
					long now = System.currentTimeMillis();
					boolean routeReportsDue = !fPendingRouteReports.isEmpty() && now >= fRouteReportsDueAt;
					fullPass = fFullPassRequested || now >= nextFullPass;
					if (fPendingWorkspaces.isEmpty() && !routeReportsDue && !fullPass) {
						long wait = nextFullPass - now;
						if (!fPendingRouteReports.isEmpty()) {
							wait = Math.min(wait, fRouteReportsDueAt - now);
						}
						try {
							fLock.wait(Math.max(wait, 1L));
						} catch (InterruptedException ignore) {}
						continue;
					}
					workspaces = new LinkedHashSet<>(fPendingWorkspaces);
					fPendingWorkspaces.clear();
					routeReports = new LinkedHashSet<>();
					if (routeReportsDue || fullPass) {
						routeReports.addAll(fPendingRouteReports);
						fPendingRouteReports.clear();
					}
					fFullPassRequested = false;
				}
				if (fCloseRequested) {
					break;
				}

				if (fullPass) {
					nextFullPass = System.currentTimeMillis() + FULL_PASS_INTERVAL_MILLIS;
					collectFullPass(workspaces, routeReports);
				}
				for (String workspaceName : workspaces) {
					if (fCloseRequested) {
						break;
					}
					reconcile(workspaceName);
				}
				for (String workspaceName : routeReports) {
					if (fCloseRequested) {
						break;
					}
					reportRoutesQuietly(workspaceName);
				}
			}
		}

		private void collectFullPass(Set<String> workspaces, Set<String> routeReports) {
			Session session = null;
			try {
				session = login();
				workspaces.add(SYSTEM_WORKSPACE_NAME);
				for (String workspaceName : workspaceManager(session).getOpenWorkspaceNames()) {
					workspaces.add(workspaceName);
					if (!SYSTEM_WORKSPACE_NAME.equals(workspaceName)) {
						routeReports.add(workspaceName);
					}
				}
				workspaces.addAll(OperationNodes.listWorkspaces(session));
				removeDepartedNodes(session);
			} catch (Throwable ex) {
				refreshQuietly(session);
				CmsService.getLogger(WorkspaceReconciler.class).warn("An error occurred while preparing the workspace reconciliation.", ex);
			} finally {
				logoutQuietly(session);
			}
		}

		private void reportRoutesQuietly(String workspaceName) {
			synchronized (lockOf(workspaceName)) {
				Session session = null;
				try {
					session = login();
					reportRoutes(session, workspaceName);
				} catch (Throwable ex) {
					refreshQuietly(session);
					CmsService.getLogger(WorkspaceReconciler.class).warn("Could not report the route states: " + workspaceName, ex);
				} finally {
					logoutQuietly(session);
				}
			}
		}
	}

}

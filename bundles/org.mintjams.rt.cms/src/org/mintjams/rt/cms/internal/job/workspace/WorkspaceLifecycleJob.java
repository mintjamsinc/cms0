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

package org.mintjams.rt.cms.internal.job.workspace;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.jcr.WorkspaceManager;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.Job;
import org.mintjams.rt.cms.internal.job.JobContext;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.operations.OperationNodes.NodeReport;
import org.mintjams.rt.cms.internal.operations.OperationNodes.NodeState;
import org.mintjams.rt.cms.internal.operations.WorkspaceOperations;
import org.mintjams.rt.cms.internal.operations.WorkspaceOperations.NodeView;
import org.mintjams.rt.cms.internal.operations.WorkspaceOperations.Progress;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.tools.adapter.Adaptables;

/**
 * Carries out a Webtop workspace operation — create, delete, start, stop,
 * restart — for the whole cluster in the background, reporting start / phase /
 * completion / error through the standard {@code jobProgress(jobId)} channel so
 * the Workspace Manager can show a progress overlay and surface a terminal error
 * the user can dismiss, instead of a workspace stuck in {@code STARTING} forever.
 *
 * <h2>Why a job</h2>
 * Bringing a workspace online runs provisioning and content deployment and can
 * take minutes — far beyond an HTTP idle timeout — and it can fail on any node.
 * Deletion must first have every node stop the workspace's services and close
 * it before the directory is removed, or file handles still held leave debris
 * behind. Both are long-running, fail-able, multi-phase operations: exactly
 * what {@link org.mintjams.rt.cms.internal.job.JobManager} exists to run.
 *
 * <h2>How an operation reaches every node</h2>
 * Webtop cannot choose a node, so the job never acts on the node that runs it
 * alone. It records the desired state ({@link WorkspaceOperations}), which every
 * node — this one included — converges on
 * ({@link org.mintjams.rt.cms.internal.operations.WorkspaceReconciler}), and then
 * waits until every alive node has got there or failed. When a node fails, the
 * job fails with that node's reason; the desired state stays, so a retry, or a
 * node restart, completes it. The job's item counters report the nodes that have
 * settled out of the nodes that are alive.
 *
 * <h2>Phases</h2>
 * <ul>
 *   <li><b>create</b> — {@code creating} (the workspace directory, from the
 *       template, on this node) → {@code starting} (every node opens it and
 *       starts its services).</li>
 *   <li><b>delete</b> — {@code stopping} (every node stops the services and
 *       closes the workspace) → {@code deleting} (the directory, once no node has
 *       it open, and the workspace's operations record).</li>
 *   <li><b>start</b> / <b>restart</b> — {@code starting}.</li>
 *   <li><b>stop</b> — {@code stopping}.</li>
 * </ul>
 * The phase is published on the job node and republished to subscribers; the
 * generic {@link JobStatus} stays RUNNING throughout and turns COMPLETED or
 * FAILED at the end, so recovery and the manager reason about it like any other
 * job.
 *
 * <h2>Storage</h2>
 * The {@code /var/jobs} record lives in the requester's own workspace, never in
 * the workspace being acted upon — a delete job's record must outlive the
 * workspace it removes. The target workspace is carried separately
 * ({@link JobNodes#PROP_TARGET_WORKSPACE}).
 *
 * <h2>Abort</h2>
 * Neither operation is safely interruptible mid-flight (a half-created or
 * half-deleted workspace is worse than letting it finish), so the job honours
 * an abort only before it has begun; the mutation advertises
 * {@code abortable=false}.
 */
public class WorkspaceLifecycleJob implements Job {

	public static final String TYPE_CREATE = "create-workspace";
	public static final String TYPE_DELETE = "delete-workspace";
	public static final String TYPE_START = "start-workspace";
	public static final String TYPE_STOP = "stop-workspace";
	public static final String TYPE_RESTART = "restart-workspace";

	public static final String PHASE_CREATING = "creating";
	public static final String PHASE_STARTING = "starting";
	public static final String PHASE_STOPPING = "stopping";
	public static final String PHASE_DELETING = "deleting";

	/** Starting services runs provisioning and content deployment, which can take minutes per node. */
	private static final long START_TIMEOUT_MILLIS = 30L * 60L * 1000L;
	private static final long STOP_TIMEOUT_MILLIS = 10L * 60L * 1000L;

	public enum Operation {
		CREATE,
		DELETE,
		START,
		STOP,
		RESTART;
	}

	private final String fJobId;
	/** Workspace that owns the {@code /var/jobs} record (the requester's workspace). */
	private final String fJobWorkspace;
	private final String fUserId;
	private final int fPriority;
	private final Operation fOperation;
	/** Workspace this job acts upon. */
	private final String fTargetWorkspace;

	public WorkspaceLifecycleJob(String jobId, String jobWorkspace, String userId, int priority,
			Operation operation, String targetWorkspace) {
		fJobId = jobId;
		fJobWorkspace = jobWorkspace;
		fUserId = userId;
		fPriority = priority;
		fOperation = operation;
		fTargetWorkspace = targetWorkspace;
	}

	@Override
	public String getJobId() {
		return fJobId;
	}

	@Override
	public String getJobType() {
		switch (fOperation) {
		case CREATE:
			return TYPE_CREATE;
		case DELETE:
			return TYPE_DELETE;
		case START:
			return TYPE_START;
		case STOP:
			return TYPE_STOP;
		case RESTART:
			return TYPE_RESTART;
		default:
			throw new IllegalStateException("Unknown workspace operation: " + fOperation);
		}
	}

	@Override
	public String getWorkspaceName() {
		return fJobWorkspace;
	}

	@Override
	public String getUserId() {
		return fUserId;
	}

	@Override
	public int getPriority() {
		return fPriority;
	}

	@Override
	public String getJobNodePath() {
		return JobNodes.jobNodePath(fJobId);
	}

	@Override
	public void execute(JobContext context) throws Exception {
		context.getLogger().info("WorkspaceLifecycleJob " + fJobId + " (" + getJobType() + ") execute() entered for target="
				+ fTargetWorkspace + " user=" + fUserId);

		Session progressSession;
		try {
			progressSession = context.getProgressSession();
		} catch (Throwable ex) {
			context.getLogger().error("WorkspaceLifecycleJob " + fJobId + " could not open progress session", ex);
			markFailedWithSystemSession(ex);
			return;
		}

		Node progressContent;
		try {
			Node fileNode = JobNodes.getJobNode(progressSession, fJobId);
			if (fileNode == null) {
				context.getLogger().warn("Job node missing for workspace job " + fJobId + "; aborting.");
				return;
			}
			progressContent = JobNodes.getContent(fileNode);
			JobNodes.setStatus(progressContent, JobStatus.RUNNING);
			progressContent.setProperty(JobNodes.PROP_STARTED_AT, Calendar.getInstance());
			progressSession.save();
		} catch (Throwable ex) {
			context.getLogger().error("WorkspaceLifecycleJob " + fJobId + " could not initialise progress", ex);
			markFailedWithSystemSession(ex);
			return;
		}

		// A queued abort that arrives before any work has started is trivially
		// safe to honour; once the desired state is recorded we run to a
		// consistent end-state rather than leave it half-applied.
		if (context.isAborted()) {
			finalise(progressContent, progressSession, JobStatus.ABORTED, null);
			return;
		}

		try {
			switch (fOperation) {
			case CREATE:
				runCreate(progressContent, progressSession);
				break;
			case DELETE:
				runDelete(progressContent, progressSession);
				break;
			case START:
				runStart(progressContent, progressSession);
				break;
			case STOP:
				runStop(progressContent, progressSession);
				break;
			case RESTART:
				runRestart(progressContent, progressSession);
				break;
			default:
				throw new IllegalStateException("Unknown workspace operation: " + fOperation);
			}
			finalise(progressContent, progressSession, JobStatus.COMPLETED, null);
		} catch (Throwable ex) {
			context.getLogger().error("WorkspaceLifecycleJob " + fJobId + " (" + getJobType() + ") failed for target="
					+ fTargetWorkspace, ex);
			finalise(progressContent, progressSession, JobStatus.FAILED, message(ex));
		}
	}

	/**
	 * Creates the workspace directory and opens it on this node, records it as
	 * present and running, and waits until every alive node has opened it and
	 * brought its services online.
	 */
	private void runCreate(Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_CREATING);
		Session opSession = openServiceSession();
		try {
			opSession.getWorkspace().createWorkspace(fTargetWorkspace);
		} finally {
			logout(opSession);
		}
		WorkspaceOperations.declarePresent(fTargetWorkspace, fUserId);
		CmsService.postWorkspaceCreated(fTargetWorkspace);

		setPhase(progressContent, progressSession, PHASE_STARTING);
		awaitNodes(progressContent, progressSession, START_TIMEOUT_MILLIS, node -> started(node, null));
	}

	/**
	 * Has every alive node stop the workspace's services and close it, then —
	 * with no node holding it open — removes the directory and the workspace's
	 * operations record. Removing the directory while a node still has the
	 * workspace open is what leaves debris behind.
	 */
	private void runDelete(Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_STOPPING);
		WorkspaceOperations.declareAbsent(fTargetWorkspace, fUserId);
		awaitNodes(progressContent, progressSession, STOP_TIMEOUT_MILLIS, WorkspaceLifecycleJob::closed);

		setPhase(progressContent, progressSession, PHASE_DELETING);
		Session opSession = openServiceSession();
		try {
			WorkspaceManager workspaceManager = Adaptables.getAdapter(opSession, WorkspaceManager.class);
			if (workspaceManager == null) {
				throw new WorkspaceJobException("The repository does not support workspace management.");
			}
			workspaceManager.removeWorkspaceDirectory(fTargetWorkspace);
		} finally {
			logout(opSession);
		}
		WorkspaceOperations.removeRecord(fTargetWorkspace, fUserId);
		CmsService.postWorkspaceDeleted(fTargetWorkspace);
	}

	/**
	 * Asks every node to run the workspace — nodes on which an earlier start
	 * failed try again — and waits until every alive node is online or has
	 * failed.
	 */
	private void runStart(Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_STARTING);
		Map<String, Long> requested = WorkspaceOperations.requestStart(fTargetWorkspace, fUserId);
		awaitNodes(progressContent, progressSession, START_TIMEOUT_MILLIS, node -> started(node, requested));
	}

	/** Asks every node to stop the workspace's services and waits until every alive node has. */
	private void runStop(Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_STOPPING);
		WorkspaceOperations.requestStop(fTargetWorkspace, fUserId);
		awaitNodes(progressContent, progressSession, STOP_TIMEOUT_MILLIS, WorkspaceLifecycleJob::stopped);
	}

	/**
	 * Asks every node to restart the workspace once and waits until every alive
	 * node has restarted it. This is how configuration that is only read at
	 * start time — the BPM and EIP engine switches — is applied across the
	 * cluster.
	 */
	private void runRestart(Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_STARTING);
		long generation = WorkspaceOperations.requestRestart(fTargetWorkspace, fUserId);
		awaitNodes(progressContent, progressSession, START_TIMEOUT_MILLIS, node -> restarted(node, generation));
	}

	private void awaitNodes(Node progressContent, Session progressSession, long timeoutMillis,
			WorkspaceOperations.NodeCheck check) throws Exception {
		long[] lastCounts = { -1L, -1L };
		List<NodeView> failed = WorkspaceOperations.await(fTargetWorkspace, check, timeoutMillis, (settled, alive) -> {
			if (settled == lastCounts[0] && alive == lastCounts[1]) {
				return;
			}
			lastCounts[0] = settled;
			lastCounts[1] = alive;
			try {
				progressContent.setProperty(JobNodes.PROP_ITEMS_TOTAL, (long) alive);
				progressContent.setProperty(JobNodes.PROP_ITEMS_PROCESSED, (long) settled);
				progressContent.setProperty("jcr:lastModified", Calendar.getInstance());
				progressSession.save();
			} catch (Throwable ex) {
				CmsService.getLogger(WorkspaceLifecycleJob.class)
						.warn("WorkspaceLifecycleJob " + fJobId + " could not record its progress", ex);
			}
		});
		if (!failed.isEmpty()) {
			throw new WorkspaceJobException(describeFailures(failed));
		}
	}

	/** Online, once the node has acted on the request sent to it (if any). */
	private static Progress started(NodeView node, Map<String, Long> requestedRetry) {
		NodeReport report = node.report;
		if (report == null) {
			return Progress.PENDING;
		}
		Long requested = (requestedRetry == null) ? null : requestedRetry.get(node.nodeId);
		if (requested != null && report.appliedRetryGeneration < requested) {
			return Progress.PENDING;
		}
		if (report.state == NodeState.FAILED) {
			return Progress.FAILED;
		}
		return (report.state == NodeState.ONLINE && !node.restartPending) ? Progress.DONE : Progress.PENDING;
	}

	/** Online again, once the node has applied the restart. */
	private static Progress restarted(NodeView node, long generation) {
		NodeReport report = node.report;
		if (report == null || report.appliedRestartGeneration < generation) {
			return Progress.PENDING;
		}
		if (report.state == NodeState.FAILED) {
			return Progress.FAILED;
		}
		return (report.state == NodeState.ONLINE) ? Progress.DONE : Progress.PENDING;
	}

	private static Progress stopped(NodeView node) {
		NodeReport report = node.report;
		return (report != null && (report.state == NodeState.STOPPED || report.state == NodeState.CLOSED))
				? Progress.DONE : Progress.PENDING;
	}

	private static Progress closed(NodeView node) {
		NodeReport report = node.report;
		return (report != null && report.state == NodeState.CLOSED) ? Progress.DONE : Progress.PENDING;
	}

	/** One line naming every failed node and its reason, for the job's error message. */
	private static String describeFailures(List<NodeView> failed) {
		List<String> parts = new ArrayList<>();
		for (NodeView node : failed) {
			String reason = (node.report != null && node.report.message != null) ? node.report.message : "failed";
			parts.add(node.nodeId + ": " + reason);
		}
		return String.join("; ", parts);
	}

	/**
	 * A privileged, service-authorised session bound to the system workspace,
	 * used only for the repository-wide create/delete operations. It is never
	 * the target workspace, so deleting the target never trips the "cannot
	 * delete the bound workspace" guard.
	 */
	private Session openServiceSession() throws Exception {
		return CmsService.getRepository().login(new CmsServiceCredentials(fUserId), "system");
	}

	private void setPhase(Node content, Session session, String phase) throws Exception {
		content.setProperty(JobNodes.PROP_PHASE, phase);
		content.setProperty(JobNodes.PROP_TARGET_WORKSPACE, fTargetWorkspace);
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		session.save();
	}

	private void finalise(Node content, Session session, JobStatus status, String errorMessage) {
		try {
			if (errorMessage != null) {
				content.setProperty(JobNodes.PROP_ERROR_MESSAGE, errorMessage);
			}
			content.setProperty(JobNodes.PROP_TARGET_WORKSPACE, fTargetWorkspace);
			content.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
			JobNodes.setStatus(content, status);
			session.save();
		} catch (Throwable ex) {
			CmsService.getLogger(WorkspaceLifecycleJob.class)
					.error("WorkspaceLifecycleJob " + fJobId + " could not finalise status", ex);
		}
	}

	/**
	 * Last-resort terminal record when even the progress session could not be
	 * opened, mirroring the other jobs so a subscriber always sees a terminal
	 * event rather than a job stuck in {@code queued}.
	 */
	private void markFailedWithSystemSession(Throwable cause) {
		Session sysSession = null;
		try {
			sysSession = CmsService.getRepository().login(new CmsServiceCredentials(fUserId), fJobWorkspace);
			Node fileNode = JobNodes.getJobNode(sysSession, fJobId);
			if (fileNode == null) {
				return;
			}
			Node content = JobNodes.getContent(fileNode);
			content.setProperty(JobNodes.PROP_ERROR_MESSAGE, message(cause));
			content.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
			JobNodes.setStatus(content, JobStatus.FAILED);
			sysSession.save();
		} catch (Throwable ex) {
			CmsService.getLogger(WorkspaceLifecycleJob.class)
					.error("WorkspaceLifecycleJob " + fJobId + " — fallback finaliser failed", ex);
		} finally {
			logout(sysSession);
		}
	}

	private static void logout(Session session) {
		if (session != null) {
			try { session.logout(); } catch (Throwable ignore) {}
		}
	}

	private static String message(Throwable ex) {
		if (ex == null) {
			return "Unknown error";
		}
		return (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName();
	}

	/** Carries a user-facing failure message to the finaliser. */
	private static final class WorkspaceJobException extends Exception {
		private static final long serialVersionUID = 1L;

		WorkspaceJobException(String message) {
			super(message);
		}
	}
}

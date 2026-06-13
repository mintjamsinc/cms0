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

import java.util.Calendar;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.Job;
import org.mintjams.rt.cms.internal.job.JobContext;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

/**
 * Creates or deletes a repository workspace in the background, reporting
 * start / phase / completion / error through the standard
 * {@code jobProgress(jobId)} channel so the Workspace Manager can show a
 * progress overlay and — crucially — surface a terminal error the user can
 * dismiss, instead of a workspace stuck in {@code STARTING} forever.
 *
 * <h2>Why a job</h2>
 * Bringing a workspace online runs provisioning and content deployment and
 * can take minutes — far beyond an HTTP idle timeout — and either step can
 * fail. Deletion must first stop the workspace's services and wait for them
 * to come fully down before removing the directory, or file handles still
 * held by a starting/running workspace leave debris behind. Both are
 * long-running, fail-able, multi-phase operations: exactly what
 * {@link org.mintjams.rt.cms.internal.job.JobManager} exists to run.
 *
 * <h2>Phases</h2>
 * <ul>
 *   <li><b>create</b> — {@code creating} (JCR workspace) → {@code starting}
 *       (CMS services).</li>
 *   <li><b>delete</b> — {@code stopping} (CMS services, synchronously, so the
 *       workspace is fully down) → {@code deleting} (JCR workspace and its
 *       directory).</li>
 * </ul>
 * The phase is published on the job node and republished to subscribers; the
 * generic {@link JobStatus} stays RUNNING throughout and turns COMPLETED or
 * FAILED at the end, so recovery and the manager reason about it like any
 * other job.
 *
 * <h2>Storage</h2>
 * The {@code /var/jobs} record lives in the requester's own workspace, never
 * in the workspace being acted upon — a delete job's record must outlive the
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
	/** Workspace this job creates or deletes. */
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
		// safe to honour; once the JCR workspace is touched we run to a
		// consistent end-state rather than leave it half-built or half-removed.
		if (context.isAborted()) {
			finalise(progressContent, progressSession, JobStatus.ABORTED, null);
			return;
		}

		try {
			switch (fOperation) {
			case CREATE:
				runCreate(context, progressContent, progressSession);
				break;
			case DELETE:
				runDelete(context, progressContent, progressSession);
				break;
			case START:
				runStart(context, progressContent, progressSession);
				break;
			case STOP:
				runStop(context, progressContent, progressSession);
				break;
			case RESTART:
				runRestart(context, progressContent, progressSession);
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
	 * Create the JCR workspace, then bring its CMS services online. Starting
	 * is idempotent and serialised with the lifecycle-event listener that the
	 * CREATED event also triggers, so whichever runs first wins and the other
	 * is a no-op; either way this call returns only once the start attempt has
	 * settled, and the servlet provider's presence is the platform's
	 * definition of "online".
	 */
	private void runCreate(JobContext context, Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_CREATING);
		Session opSession = openServiceSession();
		try {
			opSession.getWorkspace().createWorkspace(fTargetWorkspace);
		} finally {
			logout(opSession);
		}

		setPhase(progressContent, progressSession, PHASE_STARTING);
		try {
			CmsService.getDefault().startWorkspaceServices(fTargetWorkspace);
		} catch (Throwable ex) {
			// Reconciled below against the authoritative runtime state; the
			// recorded start error becomes the job's failure message.
			context.getLogger().warn("WorkspaceLifecycleJob " + fJobId + " — start reported an error for "
					+ fTargetWorkspace, ex);
		}

		if (CmsService.getWorkspaceServletProvider(fTargetWorkspace) == null) {
			String detail = CmsService.getWorkspaceStartError(fTargetWorkspace);
			throw new WorkspaceJobException(detail != null ? detail
					: "The workspace was created but its services failed to start. See the server log.");
		}
	}

	/**
	 * Stop the workspace's services and wait for them to come fully down
	 * (stopWorkspaceServices is synchronous and serialised against any
	 * in-flight start), then delete the JCR workspace and its directory. The
	 * ordering is what keeps deletion clean: removing the directory while the
	 * workspace is still starting/running is what leaves debris behind.
	 */
	private void runDelete(JobContext context, Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_STOPPING);
		CmsService.getDefault().stopWorkspaceServices(fTargetWorkspace);

		setPhase(progressContent, progressSession, PHASE_DELETING);
		Session opSession = openServiceSession();
		try {
			opSession.getWorkspace().deleteWorkspace(fTargetWorkspace);
		} finally {
			logout(opSession);
		}
	}

	/**
	 * Start an existing, stopped workspace's CMS services. Idempotent and
	 * serialised in {@link CmsService#startWorkspaceServices(String)} like the
	 * create path, and reconciled the same way against the servlet provider —
	 * the platform's definition of "online" — so a start that throws surfaces
	 * as a job failure carrying the recorded reason instead of a workspace
	 * stuck STARTING.
	 */
	private void runStart(JobContext context, Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_STARTING);
		try {
			CmsService.getDefault().startWorkspaceServices(fTargetWorkspace);
		} catch (Throwable ex) {
			context.getLogger().warn("WorkspaceLifecycleJob " + fJobId + " — start reported an error for "
					+ fTargetWorkspace, ex);
		}

		if (CmsService.getWorkspaceServletProvider(fTargetWorkspace) == null) {
			String detail = CmsService.getWorkspaceStartError(fTargetWorkspace);
			throw new WorkspaceJobException(detail != null ? detail
					: "The workspace services failed to start. See the server log.");
		}
	}

	/**
	 * Stop a running workspace's CMS services. {@code stopWorkspaceServices} is
	 * synchronous and serialised against any in-flight start, so the workspace
	 * is fully down when this returns.
	 */
	private void runStop(JobContext context, Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_STOPPING);
		CmsService.getDefault().stopWorkspaceServices(fTargetWorkspace);
	}

	/**
	 * Restart a workspace: stop its services and wait for them to come fully
	 * down, then start them again. This is how configuration that is only read
	 * at start time — the BPM and EIP engine switches — is applied to a running
	 * workspace. Reconciled against the servlet provider after the start so a
	 * failed restart fails the job with its reason.
	 */
	private void runRestart(JobContext context, Node progressContent, Session progressSession) throws Exception {
		setPhase(progressContent, progressSession, PHASE_STOPPING);
		CmsService.getDefault().stopWorkspaceServices(fTargetWorkspace);

		setPhase(progressContent, progressSession, PHASE_STARTING);
		try {
			CmsService.getDefault().startWorkspaceServices(fTargetWorkspace);
		} catch (Throwable ex) {
			context.getLogger().warn("WorkspaceLifecycleJob " + fJobId + " — restart reported an error for "
					+ fTargetWorkspace, ex);
		}

		if (CmsService.getWorkspaceServletProvider(fTargetWorkspace) == null) {
			String detail = CmsService.getWorkspaceStartError(fTargetWorkspace);
			throw new WorkspaceJobException(detail != null ? detail
					: "The workspace services failed to restart. See the server log.");
		}
	}

	/**
	 * A privileged, service-authorised session bound to the system workspace,
	 * used only to drive the repository-wide create/delete operation. It is
	 * never the target workspace, so deleting the target never trips the
	 * "cannot delete the bound workspace" guard.
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

	/** Carries a reconciled, user-facing failure message to the finaliser. */
	private static final class WorkspaceJobException extends Exception {
		private static final long serialVersionUID = 1L;

		WorkspaceJobException(String message) {
			super(message);
		}
	}
}

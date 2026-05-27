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

package org.mintjams.rt.cms.internal.job.delete;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.Job;
import org.mintjams.rt.cms.internal.job.JobContext;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

/**
 * Deletes a list of JCR nodes (typically chosen in the Content Browser).
 * The list of paths to delete is read from the body of the job node's
 * {@code jcr:content} — one absolute path per line — written there by the
 * preceding {@code appendDeleteNodes} mutations.
 *
 * Per-leaf {@code save()} keeps each transaction small so:
 * <ul>
 *   <li>nodeChanged subscriptions fire as paths disappear (live UI feedback);</li>
 *   <li>aborts take effect within milliseconds of the next leaf;</li>
 *   <li>memory usage stays bounded for very deep trees.</li>
 * </ul>
 *
 * Top-level paths are processed strictly in the order they appear in the body.
 */
public class DeleteJob implements Job {

	public static final String TYPE = "delete";

	/** Throttle: write progress to the job node every N item deletions. */
	private static final long PROGRESS_THROTTLE_ITEMS = 100L;
	/** Throttle: also write progress when this much wall time has elapsed. */
	private static final long PROGRESS_THROTTLE_MILLIS = 500L;

	private final String fJobId;
	private final String fWorkspaceName;
	private final String fUserId;
	private final int fPriority;

	private long fItemsTotal;
	private long fItemsProcessed;
	private long fItemsDeleted;
	private long fLastWriteAt;

	public DeleteJob(String jobId, String workspaceName, String userId, int priority) {
		fJobId = jobId;
		fWorkspaceName = workspaceName;
		fUserId = userId;
		fPriority = priority;
	}

	@Override
	public String getJobId() {
		return fJobId;
	}

	@Override
	public String getJobType() {
		return TYPE;
	}

	@Override
	public String getWorkspaceName() {
		return fWorkspaceName;
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
		context.getLogger().info("DeleteJob " + fJobId + " execute() entered for workspace=" + fWorkspaceName
				+ " user=" + fUserId);
		Session jobSession;
		Session progressSession;
		try {
			jobSession = context.getJobSession();
			context.getLogger().info("DeleteJob " + fJobId + " jobSession opened");
			progressSession = context.getProgressSession();
			context.getLogger().info("DeleteJob " + fJobId + " progressSession opened");
		} catch (Throwable ex) {
			context.getLogger().error("DeleteJob " + fJobId + " could not open sessions", ex);
			markFailedWithSystemSession(fJobId, fWorkspaceName, ex);
			return;
		}

		List<String> paths = null;
		Node progressContent = null;
		String initError = null;
		try {
			Node fileNode = JobNodes.getJobNode(progressSession, fJobId);
			if (fileNode == null) {
				context.getLogger().warn("Job node missing for " + fJobId + "; aborting.");
				return;
			}
			progressContent = JobNodes.getContent(fileNode);

			paths = JobNodes.readPaths(progressContent);
			fItemsTotal = paths.size();
			fItemsProcessed = JobNodes.getLong(progressContent, JobNodes.PROP_ITEMS_PROCESSED, 0L);
			fItemsDeleted = JobNodes.getLong(progressContent, JobNodes.PROP_ITEMS_DELETED, 0L);

			JobNodes.setStatus(progressContent, JobStatus.RUNNING);
			progressContent.setProperty(JobNodes.PROP_STARTED_AT, Calendar.getInstance());
			progressContent.setProperty(JobNodes.PROP_ITEMS_DELETED, fItemsDeleted);
			progressSession.save();
		} catch (Throwable ex) {
			initError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			context.getLogger().error("DeleteJob " + fJobId + " could not be initialised", ex);
		}

		boolean aborted = false;
		String errorMessage = initError;
		fLastWriteAt = System.currentTimeMillis();

		if (initError == null && paths != null) {
			try {
				for (int i = (int) fItemsProcessed; i < paths.size(); i++) {
					if (context.isAborted()) {
						aborted = true;
						break;
					}
					String top = paths.get(i);
					try {
						deleteRecursively(jobSession, top, context, progressContent, progressSession);
					} catch (AbortedException ex) {
						aborted = true;
						break;
					}
					fItemsProcessed = i + 1L;
					writeProgress(progressContent, progressSession, top, true);
				}
			} catch (Throwable ex) {
				errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
				context.getLogger().error("DeleteJob " + fJobId + " failed at item "
						+ fItemsProcessed + " of " + fItemsTotal, ex);
			}
		}

		// Always try to finalise so the client subscription gets a terminal event.
		// Re-resolve the job content node on a fresh handle if init failed before
		// progressContent was populated.
		try {
			if (progressContent == null) {
				Node fileNode = JobNodes.getJobNode(progressSession, fJobId);
				if (fileNode != null) {
					progressContent = JobNodes.getContent(fileNode);
				}
			}
			if (progressContent != null) {
				JobStatus finalStatus;
				if (errorMessage != null) {
					finalStatus = JobStatus.FAILED;
					progressContent.setProperty(JobNodes.PROP_ERROR_MESSAGE, errorMessage);
				} else if (aborted) {
					finalStatus = JobStatus.ABORTED;
				} else {
					finalStatus = JobStatus.COMPLETED;
				}
				progressContent.setProperty(JobNodes.PROP_ITEMS_PROCESSED, fItemsProcessed);
				progressContent.setProperty(JobNodes.PROP_ITEMS_DELETED, fItemsDeleted);
				progressContent.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
				JobNodes.setStatus(progressContent, finalStatus);
				progressSession.save();
			}
		} catch (Throwable ex) {
			context.getLogger().error("DeleteJob " + fJobId + " could not finalise status", ex);
		}
	}

	private void deleteRecursively(Session session, String path, JobContext context,
			Node progressContent, Session progressSession) throws Exception {
		if (context.isAborted()) {
			throw new AbortedException();
		}
		if (!session.nodeExists(path)) {
			return;
		}
		Node node = session.getNode(path);

		List<String> childPaths = new ArrayList<>();
		for (NodeIterator it = node.getNodes(); it.hasNext();) {
			childPaths.add(it.nextNode().getPath());
		}
		for (String child : childPaths) {
			deleteRecursively(session, child, context, progressContent, progressSession);
		}

		if (!session.nodeExists(path)) {
			return;
		}
		Node toRemove = session.getNode(path);
		// Count only the items the user thinks of as deleted (files and folders);
		// internal descendants such as jcr:content must not inflate the progress.
		boolean countable = JCRs.isFile(toRemove) || JCRs.isFolder(toRemove);
		toRemove.remove();
		session.save();
		if (countable) {
			fItemsDeleted++;
		}
		writeProgress(progressContent, progressSession, path, false);
	}

	private void writeProgress(Node content, Session progressSession, String currentPath, boolean force)
			throws Exception {
		long now = System.currentTimeMillis();
		boolean shouldWrite = force
				|| (fItemsDeleted - JobNodes.getLong(content, JobNodes.PROP_ITEMS_DELETED, 0L)) >= PROGRESS_THROTTLE_ITEMS
				|| (now - fLastWriteAt) >= PROGRESS_THROTTLE_MILLIS;
		if (!shouldWrite) {
			return;
		}
		content.setProperty(JobNodes.PROP_ITEMS_PROCESSED, fItemsProcessed);
		content.setProperty(JobNodes.PROP_ITEMS_DELETED, fItemsDeleted);
		if (currentPath != null) {
			content.setProperty(JobNodes.PROP_CURRENT_PATH, currentPath);
		}
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		progressSession.save();
		fLastWriteAt = now;
	}

	/**
	 * Last-resort path to record a terminal status when even opening a
	 * user-scoped progress session has failed. Uses the privileged service
	 * credentials so the JCR record never gets stuck in {@code queued} —
	 * the client is then guaranteed a final {@code jobProgress} event.
	 */
	private void markFailedWithSystemSession(String jobId, String workspace, Throwable cause) {
		Session sysSession = null;
		try {
			sysSession = CmsService.getRepository().login(new CmsServiceCredentials(fUserId), workspace);
			Node fileNode = JobNodes.getJobNode(sysSession, jobId);
			if (fileNode == null) {
				return;
			}
			Node content = JobNodes.getContent(fileNode);
			content.setProperty(JobNodes.PROP_ERROR_MESSAGE,
					cause != null && cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause));
			content.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
			JobNodes.setStatus(content, JobStatus.FAILED);
			sysSession.save();
		} catch (Throwable ex) {
			CmsService.getLogger(DeleteJob.class).error(
					"DeleteJob " + jobId + " — fallback finaliser failed", ex);
		} finally {
			if (sysSession != null) {
				try { sysSession.logout(); } catch (Throwable ignore) {}
			}
		}
	}

	private static final class AbortedException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}

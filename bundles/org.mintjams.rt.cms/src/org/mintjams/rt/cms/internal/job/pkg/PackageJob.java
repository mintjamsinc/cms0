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

package org.mintjams.rt.cms.internal.job.pkg;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.job.Job;
import org.mintjams.rt.cms.internal.job.JobContext;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.pkg.PackageInstaller;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

import com.google.gson.Gson;

/**
 * Installs or uninstalls packages as a background job with persisted
 * progress, so a route can show the work as it runs.
 *
 * <h2>Progress</h2>
 * The job record carries the installer's phase ({@code waiting} →
 * {@code inspecting} → {@code provisioning} → {@code placing} →
 * {@code removing} → {@code committing}; an uninstallation skips provisioning
 * and placing), the number of files the work goes through as
 * {@code itemsTotal}, the files done so far as {@code itemsProcessed}, and
 * the file being worked on. On completion the result
 * ({@link org.mintjams.rt.cms.internal.pkg.InstallResult#toMap()} or
 * {@link org.mintjams.rt.cms.internal.pkg.UninstallResult#toMap()}) is stored
 * as JSON in {@link JobNodes#PROP_RESULT_DETAIL}.
 *
 * <h2>Who</h2>
 * The work runs in a service session carrying the requesting user's id, as
 * {@code PackageAPI} does, so the files and the record name that user. The
 * administrator role is checked by whoever queues the job.
 *
 * <h2>Abort and crash</h2>
 * Until the commit the work is one transaction: an abort, a failure or a
 * crash leaves the workspace as it was (provisioning, which commits as it
 * goes, is idempotent). Running the job again converges.
 */
public class PackageJob implements Job {

	public static final String TYPE_INSTALL = "packageInstall";
	public static final String TYPE_UNINSTALL = "packageUninstall";

	/**
	 * Progress writes are batched: at most one save per this many files or
	 * per {@link #SAVE_BATCH_MILLIS}, whichever comes first. Every save is
	 * replicated to the whole cluster through the journal.
	 */
	private static final int SAVE_BATCH_ITEMS = 100;
	private static final long SAVE_BATCH_MILLIS = 1000L;

	private static final Gson GSON = new Gson();

	private final String fJobId;
	private final String fJobType;
	private final String fWorkspaceName;
	private final String fUserId;
	private final String fPackagePath;
	private final String fExpectedDigest;
	private final List<String> fPackageIds;

	private PackageJob(String jobId, String jobType, String workspaceName, String userId, String packagePath,
			String expectedDigest, List<String> packageIds) {
		fJobId = jobId;
		fJobType = jobType;
		fWorkspaceName = workspaceName;
		fUserId = userId;
		fPackagePath = packagePath;
		fExpectedDigest = expectedDigest;
		fPackageIds = packageIds;
	}

	/**
	 * A job installing the package file at the path. {@code expectedDigest}
	 * is the SHA-256 of the package the installation was decided on, or
	 * {@code null}.
	 */
	public static PackageJob install(String jobId, String workspaceName, String userId, String packagePath,
			String expectedDigest) {
		return new PackageJob(jobId, TYPE_INSTALL, workspaceName, userId, packagePath, expectedDigest, null);
	}

	/** A job uninstalling the packages with the ids together. */
	public static PackageJob uninstall(String jobId, String workspaceName, String userId, List<String> packageIds) {
		return new PackageJob(jobId, TYPE_UNINSTALL, workspaceName, userId, null, null, new ArrayList<>(packageIds));
	}

	@Override
	public String getJobId() {
		return fJobId;
	}

	@Override
	public String getJobType() {
		return fJobType;
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
	public String getJobNodePath() {
		return JobNodes.jobNodePath(fJobId);
	}

	@Override
	public void execute(JobContext context) throws Exception {
		Session progressSession = context.getProgressSession();
		Node fileNode = JobNodes.getJobNode(progressSession, fJobId);
		if (fileNode == null) {
			context.getLogger().warn("Job node missing for package job " + fJobId + "; aborting.");
			return;
		}
		Node content = JobNodes.getContent(fileNode);

		JobNodes.setStatus(content, JobStatus.RUNNING);
		content.setProperty(JobNodes.PROP_STARTED_AT, Calendar.getInstance());
		progressSession.save();

		if (context.isAborted()) {
			finalise(context, progressSession, content, JobStatus.ABORTED, null, null);
			return;
		}

		Progress progress = new Progress(context, progressSession, content);
		Map<String, Object> result;
		try (WorkspaceScriptContext script = new WorkspaceScriptContext(fWorkspaceName)) {
			script.setCredentials(new CmsServiceCredentials(fUserId));
			PackageInstaller installer = new PackageInstaller(script.getSession());
			if (TYPE_UNINSTALL.equals(fJobType)) {
				result = installer.uninstall(fPackageIds, fUserId, progress).toMap();
			} else {
				result = installer.install(fPackagePath, fUserId, fExpectedDigest, progress).toMap();
			}
		} catch (Throwable ex) {
			JobStatus status = context.isAborted() ? JobStatus.ABORTED : JobStatus.FAILED;
			if (status == JobStatus.FAILED) {
				context.getLogger().error("Package job " + fJobId + " (" + fJobType + ") failed.", ex);
			}
			String message = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getName();
			finalise(context, progressSession, content, status, (status == JobStatus.FAILED) ? message : null, null);
			return;
		}
		finalise(context, progressSession, content, JobStatus.COMPLETED, null, GSON.toJson(result));
	}

	private void finalise(JobContext context, Session progressSession, Node content, JobStatus status,
			String errorMessage, String resultDetail) {
		try {
			// Drop whatever an interrupted progress write left pending so the
			// final state is exactly what is set here.
			try {
				progressSession.refresh(false);
			} catch (Throwable ignore) {}
			JobNodes.setStatus(content, status);
			if (errorMessage != null) {
				content.setProperty(JobNodes.PROP_ERROR_MESSAGE, errorMessage);
			}
			if (resultDetail != null) {
				content.setProperty(JobNodes.PROP_RESULT_DETAIL, resultDetail);
				content.setProperty(JobNodes.PROP_ITEMS_PROCESSED,
						JobNodes.getLong(content, JobNodes.PROP_ITEMS_TOTAL, 0L));
			}
			content.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
			progressSession.save();
		} catch (Throwable ex) {
			context.getLogger().error("Could not finalise package job " + fJobId + " as " + status, ex);
		}
	}

	/**
	 * Bridges the installer to the job record: phases are saved as they
	 * begin, files in throttled batches, and cancellation comes from the
	 * job's abort flag (including an abort requested on another cluster node).
	 */
	private class Progress implements PackageInstaller.Monitor {
		private final JobContext fContext;
		private final Session fProgressSession;
		private final Node fContent;
		private long fCount;
		private long fLastSavedCount;
		private long fLastSavedAt = System.currentTimeMillis();

		private Progress(JobContext context, Session progressSession, Node content) {
			fContext = context;
			fProgressSession = progressSession;
			fContent = content;
		}

		@Override
		public void phase(String phase) {
			try {
				fContent.setProperty(JobNodes.PROP_PHASE, phase);
				fContent.setProperty(JobNodes.PROP_ITEMS_PROCESSED, fCount);
				save();
			} catch (Throwable ex) {
				failedWrite(ex);
			}
		}

		@Override
		public void total(int files) {
			try {
				fContent.setProperty(JobNodes.PROP_ITEMS_TOTAL, (long) files);
				save();
			} catch (Throwable ex) {
				failedWrite(ex);
			}
		}

		@Override
		public void worked(String path) {
			fCount++;
			long now = System.currentTimeMillis();
			if (fCount - fLastSavedCount < SAVE_BATCH_ITEMS && (now - fLastSavedAt) < SAVE_BATCH_MILLIS) {
				return;
			}
			try {
				fContent.setProperty(JobNodes.PROP_ITEMS_PROCESSED, fCount);
				fContent.setProperty(JobNodes.PROP_CURRENT_PATH, path);
				save();
			} catch (Throwable ex) {
				failedWrite(ex);
			}
		}

		@Override
		public boolean isCancelled() {
			return fContext.isAborted();
		}

		private void save() throws Exception {
			fProgressSession.save();
			fLastSavedCount = fCount;
			fLastSavedAt = System.currentTimeMillis();
		}

		// A progress write must never fail the work.
		private void failedWrite(Throwable ex) {
			fContext.getLogger().warn("Could not write package job progress: " + fJobId, ex);
			try {
				fProgressSession.refresh(false);
			} catch (Throwable ignore) {}
		}
	}

}

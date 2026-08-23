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

package org.mintjams.rt.cms.internal.job.searchindex;

import java.util.Calendar;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.jcr.search.SearchIndexRebuilder;
import org.mintjams.rt.cms.internal.job.Job;
import org.mintjams.rt.cms.internal.job.JobContext;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptables;

/**
 * Rebuilds this node's search index from the repository content, as a
 * background job with persisted progress.
 *
 * <h2>Phases</h2>
 * {@code counting} (total for the progress bar) → {@code indexing} (the staged
 * traversal) → {@code catchingUp} (replaying what changed meanwhile) →
 * {@code swapping} (the staged index becomes live). The generic
 * {@link JobStatus} stays RUNNING throughout, so recovery and the manager
 * reason about it like any other job.
 *
 * <h2>Cluster</h2>
 * The search index is node-local, so one rebuild request creates one job per
 * cluster node ({@link JobNodes#PROP_NODE_ID} names the target); this job only
 * ever rebuilds the index of the node it runs on. The jobs of one request
 * share a {@link JobNodes#PROP_REBUILD_ID}.
 *
 * <h2>Abort and crash</h2>
 * The rebuild is staged: the live index stays untouched (and fully serving)
 * until the final swap, so an abort or crash needs no repair — the job is
 * simply run again. {@link SearchIndexRebuildService} re-queues this node's
 * restart casualties automatically.
 *
 * <h2>Threading</h2>
 * The rebuild fans out across worker threads that all report progress through
 * the monitor below, and the progress JCR session is not thread-safe — every
 * touch of it (including {@link JobContext#isAborted()}, which refreshes that
 * session) is serialized on this job instance's monitor.
 */
public class SearchIndexRebuildJob implements Job {

	public static final String TYPE = "searchIndexRebuild";

	public static final String PHASE_COUNTING = "counting";
	public static final String PHASE_INDEXING = "indexing";
	public static final String PHASE_CATCHING_UP = "catchingUp";
	public static final String PHASE_SWAPPING = "swapping";

	/**
	 * Progress writes are batched: at most one save per this many indexed
	 * items or per {@link #SAVE_BATCH_MILLIS}, whichever comes first. Every
	 * save is replicated to the whole cluster through the journal, so
	 * per-item saves would tax every node.
	 */
	private static final int SAVE_BATCH_ITEMS = 500;
	private static final long SAVE_BATCH_MILLIS = 3000L;

	private final String fJobId;
	private final String fWorkspaceName;
	private final String fUserId;

	public SearchIndexRebuildJob(String jobId, String workspaceName, String userId) {
		fJobId = jobId;
		fWorkspaceName = workspaceName;
		fUserId = userId;
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
	public String getJobNodePath() {
		return JobNodes.jobNodePath(fJobId);
	}

	@Override
	public void execute(JobContext context) throws Exception {
		Session progressSession = context.getProgressSession();
		Node fileNode = JobNodes.getJobNode(progressSession, fJobId);
		if (fileNode == null) {
			context.getLogger().warn("Job node missing for search index rebuild job " + fJobId + "; aborting.");
			return;
		}
		Node content = JobNodes.getContent(fileNode);

		synchronized (this) {
			JobNodes.setStatus(content, JobStatus.RUNNING);
			content.setProperty(JobNodes.PROP_STARTED_AT, Calendar.getInstance());
			content.setProperty(JobNodes.PROP_PHASE, PHASE_COUNTING);
			progressSession.save();
		}

		if (isAborted(context)) {
			finalise(progressSession, content, JobStatus.ABORTED, null);
			return;
		}

		SearchIndexRebuilder rebuilder = Adaptables.getAdapter(progressSession, SearchIndexRebuilder.class);
		if (rebuilder == null) {
			finalise(progressSession, content, JobStatus.FAILED,
					"The search index rebuilder is not available for this workspace.");
			return;
		}

		Monitor monitor = new Monitor(context, progressSession, content);
		try {
			long total = rebuilder.countIndexableItems();
			synchronized (this) {
				content.setProperty(JobNodes.PROP_ITEMS_TOTAL, total);
				content.setProperty(JobNodes.PROP_PHASE, PHASE_INDEXING);
				progressSession.save();
			}
			if (isAborted(context)) {
				finalise(progressSession, content, JobStatus.ABORTED, null);
				return;
			}

			rebuilder.rebuild(monitor);

			if (isAborted(context)) {
				finalise(progressSession, content, JobStatus.ABORTED, null);
				return;
			}
			synchronized (this) {
				content.setProperty(JobNodes.PROP_ITEMS_PROCESSED, monitor.getCount());
			}
			finalise(progressSession, content, JobStatus.COMPLETED, null);
		} catch (Throwable ex) {
			context.getLogger().error("Search index rebuild job " + fJobId + " failed.", ex);
			JobStatus status = isAborted(context) ? JobStatus.ABORTED : JobStatus.FAILED;
			finalise(progressSession, content, status, (status == JobStatus.FAILED) ? String.valueOf(ex) : null);
		}
	}

	private boolean isAborted(JobContext context) {
		// isAborted() may refresh the (thread-unsafe) progress session; keep it
		// under the same lock as every other progress-session access.
		synchronized (this) {
			return context.isAborted();
		}
	}

	private void finalise(Session progressSession, Node content, JobStatus status, String errorMessage) {
		synchronized (this) {
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
				content.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
				progressSession.save();
			} catch (Throwable ex) {
				org.mintjams.rt.cms.internal.CmsService.getLogger(getClass())
						.error("Could not finalise search index rebuild job " + fJobId + " as " + status, ex);
			}
		}
	}

	/**
	 * Bridges the rebuild to the job record: cancellation comes from the job's
	 * abort flag (including cross-node aborts via the persisted status), the
	 * indexed-path stream drives the throttled progress counters, and the
	 * post-traversal phases are published as they begin. Called from several
	 * rebuild worker threads at once.
	 */
	private class Monitor implements SearchIndex.UpdateMonitor {
		private final JobContext fContext;
		private final Session fProgressSession;
		private final Node fContent;
		private final AtomicLong fCount = new AtomicLong();
		private long fLastSavedCount;
		private long fLastSavedAt = System.currentTimeMillis();

		private Monitor(JobContext context, Session progressSession, Node content) {
			fContext = context;
			fProgressSession = progressSession;
			fContent = content;
		}

		long getCount() {
			return fCount.get();
		}

		@Override
		public boolean isCancelled() {
			return isAborted(fContext);
		}

		@Override
		public Consumer<String> getPathConsumer() {
			return path -> {
				long count = fCount.incrementAndGet();
				synchronized (SearchIndexRebuildJob.this) {
					long now = System.currentTimeMillis();
					if (count - fLastSavedCount < SAVE_BATCH_ITEMS && (now - fLastSavedAt) < SAVE_BATCH_MILLIS) {
						return;
					}
					fLastSavedCount = count;
					fLastSavedAt = now;
					try {
						fContent.setProperty(JobNodes.PROP_ITEMS_PROCESSED, count);
						fContent.setProperty(JobNodes.PROP_CURRENT_PATH, path);
						fProgressSession.save();
					} catch (Throwable ex) {
						// A progress write must never kill the rebuild.
						fContext.getLogger().warn("Could not write search index rebuild progress: " + fJobId, ex);
						try {
							fProgressSession.refresh(false);
						} catch (Throwable ignore) {}
					}
				}
			};
		}

		@Override
		public Consumer<String> getPhaseConsumer() {
			return phase -> {
				synchronized (SearchIndexRebuildJob.this) {
					try {
						fContent.setProperty(JobNodes.PROP_PHASE, phase);
						fContent.setProperty(JobNodes.PROP_ITEMS_PROCESSED, fCount.get());
						fProgressSession.save();
					} catch (Throwable ex) {
						fContext.getLogger().warn("Could not write search index rebuild phase: " + fJobId, ex);
						try {
							fProgressSession.refresh(false);
						} catch (Throwable ignore) {}
					}
				}
			};
		}
	}

}

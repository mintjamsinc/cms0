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

package org.mintjams.rt.cms.internal.job;

import java.io.Closeable;
import java.io.IOException;
import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;

/**
 * Generic background-job runner with a fixed-size worker pool, a priority
 * queue, and per-job abort flags.
 *
 * The manager itself is intentionally unaware of any specific job kind — it
 * accepts anything that implements {@link Job}. State for in-flight jobs lives
 * in JCR (the job node) so a future restart can resume; this class only owns
 * the executor and the in-memory abort flag.
 */
public class JobManager implements Closeable {

	private final ThreadPoolExecutor fExecutor;
	private final Map<String, AtomicBoolean> fAbortFlags = new ConcurrentHashMap<>();
	private volatile boolean fClosed = false;

	public JobManager(int workers) {
		if (workers < 1) {
			workers = 1;
		}
		PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>();
		AtomicLong counter = new AtomicLong();
		// Pin the worker's context classloader to this bundle's loader.
		// Without this, workers inherit the classloader of whichever thread
		// happened to call submit() first (typically a servlet thread), which
		// breaks ServiceLoader/ContextClassLoader-based lookups inside JCR
		// authentication and OSGi service resolution.
		ClassLoader bundleLoader = JobManager.class.getClassLoader();
		ThreadFactory threadFactory = r -> {
			Thread t = new Thread(r, "job-worker-" + counter.incrementAndGet());
			t.setDaemon(true);
			t.setContextClassLoader(bundleLoader);
			return t;
		};
		fExecutor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, queue, threadFactory);
		// Eagerly start one worker so the very first submit doesn't pay the
		// cost of thread creation, and so any classloader/service-resolution
		// issue surfaces at boot rather than at first use.
		fExecutor.prestartCoreThread();
		CmsService.getLogger(JobManager.class).info(
				"JobManager initialised with " + workers + " worker(s).");
	}

	/**
	 * Submit a job for asynchronous execution. Returns immediately. Higher
	 * {@link Job#getPriority()} values run sooner.
	 */
	public void submit(Job job) {
		if (fClosed) {
			throw new IllegalStateException("JobManager is closed");
		}
		AtomicBoolean abortFlag = new AtomicBoolean(false);
		fAbortFlags.put(job.getJobId(), abortFlag);
		CmsService.getLogger(JobManager.class).info(
				"Submitting job " + job.getJobId() + " (" + job.getJobType() + ") priority=" + job.getPriority());
		fExecutor.execute(new JobRunner(job, abortFlag));
	}

	/**
	 * Request the named job to stop at its next safe point. Returns true if
	 * the job was running, false if no such job is currently tracked.
	 */
	public boolean abort(String jobId) {
		AtomicBoolean f = fAbortFlags.get(jobId);
		if (f == null) {
			return false;
		}
		f.set(true);
		return true;
	}

	public boolean isRunning(String jobId) {
		return fAbortFlags.containsKey(jobId);
	}

	@Override
	public void close() throws IOException {
		fClosed = true;
		// Signal abort to any in-flight jobs so they can exit cleanly
		for (AtomicBoolean f : fAbortFlags.values()) {
			f.set(true);
		}
		fExecutor.shutdown();
		try {
			if (!fExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
				fExecutor.shutdownNow();
			}
		} catch (InterruptedException ex) {
			fExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Wraps a Job so the executor's PriorityBlockingQueue can order entries
	 * without leaking priority concerns into the Job interface.
	 */
	private final class JobRunner implements Runnable, Comparable<JobRunner> {
		private final Job fJob;
		private final AtomicBoolean fAbortFlag;

		JobRunner(Job job, AtomicBoolean abortFlag) {
			fJob = job;
			fAbortFlag = abortFlag;
		}

		@Override
		public void run() {
			CmsService.getLogger(JobManager.class).info(
					"Job " + fJob.getJobId() + " (" + fJob.getJobType() + ") starting on "
							+ Thread.currentThread().getName());
			DefaultJobContext context = new DefaultJobContext(fJob, fAbortFlag);
			try {
				if (!beginExecution(context)) {
					return;
				}
				fJob.execute(context);
				CmsService.getLogger(JobManager.class).info(
						"Job " + fJob.getJobId() + " (" + fJob.getJobType() + ") finished");
			} catch (Throwable ex) {
				CmsService.getLogger(JobManager.class).error(
						"Job " + fJob.getJobId() + " (" + fJob.getJobType() + ") failed", ex);
			} finally {
				try {
					context.close();
				} finally {
					fAbortFlags.remove(fJob.getJobId());
				}
			}
		}

		/**
		 * Consults the persisted job status before executing: a job that was
		 * aborted while still queued — possibly from another cluster node —
		 * is finalised instead of run, and a job already in a terminal state
		 * is skipped. Also records this node as the executor so a restart
		 * can recover exactly the jobs that died with it. Bookkeeping
		 * failures never block execution.
		 */
		private boolean beginExecution(DefaultJobContext context) {
			try {
				Session session = context.getProgressSession();
				session.refresh(true);
				Node jobNode = JobNodes.getJobNode(session, fJob.getJobId());
				if (jobNode == null) {
					return true;
				}
				Node content = JobNodes.getContent(jobNode);
				JobStatus status = JobNodes.getStatus(content);

				if (status == JobStatus.ABORTING) {
					JobNodes.setStatus(content, JobStatus.ABORTED);
					content.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
					session.save();
					CmsService.getLogger(JobManager.class).info(
							"Job " + fJob.getJobId() + " (" + fJob.getJobType() + ") was aborted before execution");
					return false;
				}
				if (status != null && status.isTerminal()) {
					CmsService.getLogger(JobManager.class).warn(
							"Job " + fJob.getJobId() + " (" + fJob.getJobType() + ") is already " + status
									+ " and will not be executed");
					return false;
				}

				JobNodes.setNodeId(session, content);
				session.save();
			} catch (Throwable ex) {
				CmsService.getLogger(JobManager.class).warn(
						"An error occurred while preparing job " + fJob.getJobId() + "; executing anyway", ex);
			}
			return true;
		}

		@Override
		public int compareTo(JobRunner other) {
			// Higher priority first
			return Integer.compare(other.fJob.getPriority(), this.fJob.getPriority());
		}
	}
}

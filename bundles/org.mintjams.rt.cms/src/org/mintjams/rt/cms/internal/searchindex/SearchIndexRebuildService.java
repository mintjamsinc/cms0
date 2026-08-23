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

package org.mintjams.rt.cms.internal.searchindex;

import java.io.Closeable;
import java.io.IOException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.job.searchindex.SearchIndexRebuildJob;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

/**
 * Runs this node's share of search index rebuild requests.
 *
 * <p>A rebuild request is durable: the dispatcher persists one QUEUED job
 * record per target cluster node under {@code /var/jobs} (the search index is
 * node-local, so every node rebuilds its own). This service watches for the
 * records addressed to this node and submits a {@link SearchIndexRebuildJob}
 * for each, through two paths:</p>
 *
 * <ul>
 *   <li><b>Events</b> — job-node writes replicate to every node through the
 *       cluster journal and surface as local JCR node events, so a request
 *       dispatched anywhere reaches a running node within the journal's poll
 *       latency.</li>
 *   <li><b>Startup scan</b> — a node that was down when the request was
 *       dispatched finds its QUEUED records when it comes back. The scan also
 *       re-queues this node's rebuild jobs that died with a restart (recovery
 *       marks them FAILED with {@link JobNodes#RESTART_ERROR_MESSAGE} before
 *       services start): a rebuild is idempotent, so the automatic re-run is
 *       always safe.</li>
 * </ul>
 *
 * <p>Duplicate triggers (an event racing the scan, repeated change events) are
 * absorbed by an in-memory submitted set plus the job manager's own persisted
 * status check before execution.</p>
 */
public class SearchIndexRebuildService implements EventHandler, Closeable {

	/** Bounded memory of job ids already submitted or judged not-ours. */
	private static final int SEEN_CACHE_SIZE = 1024;

	private final String fWorkspaceName;
	private final Closer fCloser = Closer.create();
	private Thread fThread;
	private volatile boolean fCloseRequested;
	private final List<String> fPendingJobIds = new ArrayList<>();
	private final Set<String> fSubmitted = new LinkedHashSet<>();
	private final Set<String> fIgnored = new LinkedHashSet<>();
	private Registration<EventHandler> fEventHandlerRegistration;

	public SearchIndexRebuildService(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	public SearchIndexRebuildService open() {
		if (fThread != null) {
			return this;
		}

		// The scan must complete before events are consumed so an event that
		// races it merely hits the submitted set.
		try {
			scanForOwnJobs();
		} catch (Throwable ex) {
			CmsService.getLogger(getClass())
					.error("An error occurred while scanning for search index rebuild jobs: " + fWorkspaceName, ex);
		}

		fThread = new Thread(new Task(), "searchindex-rebuild-service-" + fWorkspaceName);
		fThread.setDaemon(true);
		fThread.start();

		fEventHandlerRegistration = fCloser.register(Registration.newBuilder(EventHandler.class)
				.setService(this)
				.setProperty(EventConstants.EVENT_TOPIC,
						new String[] { javax.jcr.Node.class.getName().replace(".", "/") + "/*" })
				.setProperty(EventConstants.EVENT_FILTER, "(workspace=" + fWorkspaceName + ")")
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());

		return this;
	}

	@Override
	public void handleEvent(Event event) {
		Object path = event.getProperty("path");
		if (path == null) {
			return;
		}
		String jobId = jobIdOf(path.toString());
		if (jobId == null) {
			return;
		}
		synchronized (fPendingJobIds) {
			// The seen caches are maintained by the worker; membership here is a
			// cheap racy pre-filter that keeps progress-write events (which fire
			// for every running job's save) from queueing session work.
			if (fSubmitted.contains(jobId) || fIgnored.contains(jobId)) {
				return;
			}
			if (!fPendingJobIds.contains(jobId)) {
				fPendingJobIds.add(jobId);
				fPendingJobIds.notifyAll();
			}
		}
	}

	/**
	 * The job id when the given absolute path is a {@code /var/jobs} job node
	 * (or its {@code jcr:content}), otherwise null.
	 */
	private static String jobIdOf(String path) {
		if (!path.startsWith(JobNodes.JOBS_ROOT + "/")) {
			return null;
		}
		int marker = path.lastIndexOf("/job-");
		if (marker < 0) {
			return null;
		}
		String rest = path.substring(marker + "/job-".length());
		int slash = rest.indexOf('/');
		String jobId = (slash < 0) ? rest : rest.substring(0, slash);
		return jobId.isEmpty() ? null : jobId;
	}

	/**
	 * Submits this node's runnable rebuild jobs found in the current and the
	 * previous month's buckets: QUEUED records (dispatched while this node was
	 * down, or racing the event path), and this node's restart casualties,
	 * which are re-queued — the automatic recovery the design calls for.
	 */
	private void scanForOwnJobs() throws Exception {
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), fWorkspaceName);
		try {
			String nodeId = JobNodes.getCurrentNodeId(session);
			int requeued = 0;
			List<String> runnable = new ArrayList<>();
			YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
			for (YearMonth month : new YearMonth[] { thisMonth, thisMonth.minusMonths(1) }) {
				String folderPath = JobNodes.JOBS_ROOT + "/" + month.getYear()
						+ "/" + String.format("%02d", month.getMonthValue());
				if (!session.nodeExists(folderPath)) {
					continue;
				}

				for (NodeIterator i = session.getNode(folderPath).getNodes("job-*"); i.hasNext();) {
					Node content = JobNodes.getContent(i.nextNode());
					if (!SearchIndexRebuildJob.TYPE.equals(JobNodes.getString(content, JobNodes.PROP_JOB_TYPE, null))) {
						continue;
					}
					String owner = JobNodes.getString(content, JobNodes.PROP_NODE_ID, null);
					if (owner == null || !owner.equals(nodeId)) {
						continue;
					}

					JobStatus status = JobNodes.getStatus(content);
					if (status == JobStatus.QUEUED) {
						runnable.add(JobNodes.getString(content, JobNodes.PROP_JOB_ID, null));
						continue;
					}
					if (status == JobStatus.FAILED && JobNodes.RESTART_ERROR_MESSAGE
							.equals(JobNodes.getString(content, JobNodes.PROP_ERROR_MESSAGE, null))) {
						JobNodes.setStatus(content, JobStatus.QUEUED);
						content.getProperty(JobNodes.PROP_ERROR_MESSAGE).remove();
						if (content.hasProperty(JobNodes.PROP_FINISHED_AT)) {
							content.getProperty(JobNodes.PROP_FINISHED_AT).remove();
						}
						requeued++;
						runnable.add(JobNodes.getString(content, JobNodes.PROP_JOB_ID, null));
					}
				}
			}
			if (requeued > 0) {
				session.save();
				CmsService.getLogger(getClass()).info("Re-queued " + requeued
						+ " search index rebuild job(s) that died with this node's previous run: " + fWorkspaceName);
			}
			for (String jobId : runnable) {
				if (jobId != null) {
					submit(session, jobId);
				}
			}
		} finally {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}
	}

	/**
	 * Reads the job record and submits it when it is a QUEUED rebuild job
	 * addressed to this node; remembers the verdict either way so repeated
	 * events for the same job stay cheap.
	 */
	private void examine(String jobId) {
		Session session = null;
		try {
			session = CmsService.getRepository().login(new CmsServiceCredentials(), fWorkspaceName);
			Node jobNode = JobNodes.getJobNode(session, jobId);
			if (jobNode == null) {
				return;
			}
			Node content = JobNodes.getContent(jobNode);
			if (!SearchIndexRebuildJob.TYPE.equals(JobNodes.getString(content, JobNodes.PROP_JOB_TYPE, null))) {
				remember(fIgnored, jobId);
				return;
			}
			String owner = JobNodes.getString(content, JobNodes.PROP_NODE_ID, null);
			if (owner == null || !owner.equals(JobNodes.getCurrentNodeId(session))) {
				remember(fIgnored, jobId);
				return;
			}
			if (JobNodes.getStatus(content) != JobStatus.QUEUED) {
				// RUNNING/terminal records need no action; a record this node
				// submitted is already in the submitted set, and one that died
				// with a restart is handled by the startup scan.
				remember(fIgnored, jobId);
				return;
			}
			submit(session, jobId);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass())
					.error("An error occurred while examining the search index rebuild job: " + jobId, ex);
		} finally {
			if (session != null) {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}
		}
	}

	private void submit(Session session, String jobId) {
		synchronized (fPendingJobIds) {
			if (fSubmitted.contains(jobId)) {
				return;
			}
			remember(fSubmitted, jobId);
		}
		String userId;
		try {
			Node content = JobNodes.getContent(JobNodes.getJobNode(session, jobId));
			userId = JobNodes.getString(content, JobNodes.PROP_JOB_USER_ID, "system");
		} catch (Throwable ex) {
			userId = "system";
		}
		CmsService.getJobManager().submit(new SearchIndexRebuildJob(jobId, fWorkspaceName, userId));
	}

	private void remember(Set<String> cache, String jobId) {
		synchronized (fPendingJobIds) {
			cache.add(jobId);
			while (cache.size() > SEEN_CACHE_SIZE) {
				Iterator<String> i = cache.iterator();
				i.next();
				i.remove();
			}
		}
	}

	@Override
	public void close() throws IOException {
		if (fCloseRequested) {
			return;
		}
		fCloseRequested = true;
		IOs.closeQuietly(fEventHandlerRegistration);
		fEventHandlerRegistration = null;
		synchronized (fPendingJobIds) {
			fPendingJobIds.notifyAll();
		}
		if (fThread != null) {
			try {
				fThread.interrupt();
				fThread.join(10000);
			} catch (InterruptedException ignore) {}
			fThread = null;
		}
		fCloser.close();
		fCloseRequested = false;
	}

	private class Task implements Runnable {
		@Override
		public void run() {
			while (!fCloseRequested) {
				if (Thread.interrupted()) {
					break;
				}
				String jobId;
				synchronized (fPendingJobIds) {
					if (fPendingJobIds.isEmpty()) {
						try {
							fPendingJobIds.wait();
						} catch (InterruptedException ignore) {}
						continue;
					}
					jobId = fPendingJobIds.remove(0);
				}
				examine(jobId);
			}
		}
	}

}

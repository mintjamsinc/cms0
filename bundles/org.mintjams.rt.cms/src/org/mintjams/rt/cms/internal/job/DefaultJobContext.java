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

import java.util.concurrent.atomic.AtomicBoolean;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;
import org.osgi.service.log.Logger;

/**
 * Standard {@link JobContext} that opens two distinct sessions on demand:
 *
 * <ul>
 *   <li><b>jobSession</b> — {@link ServiceUserCredentials} so the actual work
 *       (e.g. node deletion) is gated by the requesting user's ACLs.</li>
 *   <li><b>progressSession</b> — {@link CmsServiceCredentials} (privileged,
 *       tagged with the requesting user's id) so progress writes against
 *       {@code /var/jobs/...} succeed regardless of how that area's ACLs
 *       are configured. The user's identity is still recorded as the
 *       modifier of the job node.</li>
 * </ul>
 */
class DefaultJobContext implements JobContext {

	/**
	 * How often {@link #isAborted()} consults the persisted job status. The
	 * in-memory flag only reaches jobs running on this node; an abort
	 * requested on another cluster node arrives as the persisted ABORTING
	 * status.
	 */
	private static final long STATUS_CHECK_INTERVAL_MILLIS = 5000L;

	private final Job fJob;
	private final AtomicBoolean fAbortFlag;
	private Session fJobSession;
	private Session fProgressSession;
	private long fStatusChecked = System.currentTimeMillis();

	DefaultJobContext(Job job, AtomicBoolean abortFlag) {
		fJob = job;
		fAbortFlag = abortFlag;
	}

	@Override
	public String getJobId() {
		return fJob.getJobId();
	}

	@Override
	public synchronized Session getJobSession() throws RepositoryException {
		if (fJobSession == null) {
			fJobSession = CmsService.getRepository()
					.login(new ServiceUserCredentials(fJob.getUserId()), fJob.getWorkspaceName());
		}
		return fJobSession;
	}

	@Override
	public synchronized Session getProgressSession() throws RepositoryException {
		if (fProgressSession == null) {
			// Privileged session so writes to /var/jobs/... succeed regardless
			// of ACL configuration; userId is still preserved as the modifier.
			fProgressSession = CmsService.getRepository()
					.login(new CmsServiceCredentials(fJob.getUserId()), fJob.getWorkspaceName());
		}
		return fProgressSession;
	}

	@Override
	public synchronized boolean isAborted() {
		if (fAbortFlag.get()) {
			return true;
		}

		// An abort requested on another cluster node is visible only as the
		// persisted ABORTING status; poll it at a low rate.
		if (System.currentTimeMillis() - fStatusChecked < STATUS_CHECK_INTERVAL_MILLIS) {
			return false;
		}
		fStatusChecked = System.currentTimeMillis();
		try {
			Session session = getProgressSession();
			session.refresh(true);
			Node jobNode = JobNodes.getJobNode(session, fJob.getJobId());
			if (jobNode != null && JobNodes.getStatus(JobNodes.getContent(jobNode)) == JobStatus.ABORTING) {
				fAbortFlag.set(true);
				return true;
			}
		} catch (Throwable ex) {
			getLogger().warn("An error occurred while checking the persisted job status: " + fJob.getJobId(), ex);
		}
		return false;
	}

	@Override
	public Logger getLogger() {
		return CmsService.getLogger(fJob.getClass());
	}

	synchronized void close() {
		if (fJobSession != null) {
			try { fJobSession.logout(); } catch (Throwable ignore) {}
			fJobSession = null;
		}
		if (fProgressSession != null) {
			try { fProgressSession.logout(); } catch (Throwable ignore) {}
			fProgressSession = null;
		}
	}
}

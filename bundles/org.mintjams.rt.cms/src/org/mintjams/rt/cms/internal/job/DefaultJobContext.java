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

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.UserServiceCredentials;
import org.osgi.service.log.Logger;

/**
 * Standard {@link JobContext} that opens user-scoped sessions on demand and
 * checks an externally-owned abort flag.
 */
class DefaultJobContext implements JobContext {

	private final Job fJob;
	private final AtomicBoolean fAbortFlag;
	private Session fJobSession;
	private Session fProgressSession;

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
					.login(new UserServiceCredentials(fJob.getUserId()), fJob.getWorkspaceName());
		}
		return fJobSession;
	}

	@Override
	public synchronized Session getProgressSession() throws RepositoryException {
		if (fProgressSession == null) {
			fProgressSession = CmsService.getRepository()
					.login(new UserServiceCredentials(fJob.getUserId()), fJob.getWorkspaceName());
		}
		return fProgressSession;
	}

	@Override
	public boolean isAborted() {
		return fAbortFlag.get();
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

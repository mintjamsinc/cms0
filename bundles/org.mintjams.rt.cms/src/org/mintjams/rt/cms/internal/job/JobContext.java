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

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.osgi.service.log.Logger;

/**
 * Runtime context handed to a {@link Job} during {@link Job#execute(JobContext)}.
 *
 * Provides two distinct sessions so progress writes never share a transaction
 * with the work itself: the work session commits frequently as it deletes,
 * while the progress session writes throttled status updates to the job node.
 *
 * The context is owned by {@link JobManager} and closed once execute returns.
 */
public interface JobContext {

	String getJobId();

	/**
	 * Lazily-opened session used by the Job for its primary work. Authorised
	 * as the user that requested the job.
	 */
	Session getJobSession() throws RepositoryException;

	/**
	 * Lazily-opened session used to write progress updates to the job node.
	 * Distinct from {@link #getJobSession()} so progress saves are independent
	 * of the work transactions.
	 */
	Session getProgressSession() throws RepositoryException;

	/**
	 * Polled by long-running work between iterations. When true, the Job
	 * should stop at the next safe point and let execute return normally
	 * (the manager records the run as ABORTED).
	 */
	boolean isAborted();

	Logger getLogger();
}

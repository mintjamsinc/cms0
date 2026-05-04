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

/**
 * A unit of work executed by {@link JobManager}. Each Job carries enough
 * metadata for the manager to spawn user-scoped sessions on its behalf.
 *
 * Implementations must be safe to invoke from a worker thread distinct from
 * the one that constructed the Job. They MUST NOT capture a JCR Session in
 * their fields — sessions are obtained from the {@link JobContext} during
 * {@link #execute(JobContext)}.
 */
public interface Job {

	String getJobId();

	/**
	 * Identifier for the kind of work this Job performs (for example
	 * {@code "delete"}). Persisted on the JCR job node so a future restart
	 * can route the job to the correct factory.
	 */
	String getJobType();

	String getWorkspaceName();

	String getUserId();

	/**
	 * Higher value runs sooner. Default 0.
	 */
	default int getPriority() {
		return 0;
	}

	/**
	 * Absolute path of the persisted job node, e.g. {@code /var/jobs/2026/05/job-...}.
	 * The job node is an {@code nt:file}; runtime state lives on its
	 * {@code jcr:content} child.
	 */
	String getJobNodePath();

	void execute(JobContext context) throws Exception;
}

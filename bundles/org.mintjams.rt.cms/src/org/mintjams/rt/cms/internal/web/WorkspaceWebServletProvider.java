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

package org.mintjams.rt.cms.internal.web;

import java.io.Closeable;
import java.io.IOException;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServlet;

import org.mintjams.tools.io.Closer;

public class WorkspaceWebServletProvider implements Closeable {

	private final WorkspaceWebServletProviderConfiguration fConfig;
	private final Closer fCloser = Closer.create();
	private HttpServlet fServlet;

	public WorkspaceWebServletProvider(String workspaceName) {
		fConfig = new WorkspaceWebServletProviderConfiguration(workspaceName);
	}

	public synchronized void open() throws IOException, RepositoryException {
		fServlet = fConfig.createServlet();
	}

	@Override
	public synchronized void close() throws IOException {
		fCloser.close();
	}

	public String getWorkspaceName() {
		return fConfig.getWorkspaceName();
	}

	public HttpServlet getServlet() {
		return fServlet;
	}

	public WorkspaceWebServletProviderConfiguration getConfiguration() {
		return fConfig;
	}

}

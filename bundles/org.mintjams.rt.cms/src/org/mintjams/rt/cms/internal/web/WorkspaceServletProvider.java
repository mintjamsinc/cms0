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
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

public class WorkspaceServletProvider implements Closeable {

	private final WorkspaceServletProviderConfiguration fConfig;
	private final Closer fCloser = Closer.create();
	private HttpServlet fServlet;

	public WorkspaceServletProvider(String workspaceName) {
		fConfig = new WorkspaceServletProviderConfiguration(workspaceName);
	}

	public synchronized void open() throws IOException, RepositoryException {
		fServlet = fConfig.createServlet();

		fCloser.register(Registration.newBuilder(Servlet.class)
				.setService(fServlet)
				.setProperty(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, fConfig.getContextPath() + "/*")
				.setProperty(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(osgi.http.whiteboard.context.name=org.osgi.service.http)")
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());
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

	public WorkspaceServletProviderConfiguration getConfiguration() {
		return fConfig;
	}

}

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
import java.util.HashMap;
import java.util.Map;

import javax.jcr.Credentials;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.util.Action;
import org.mintjams.tools.util.ActionException;
import org.mintjams.tools.util.SimpleActionChain;

public class WorkspaceServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final WorkspaceServletProviderConfiguration fConfig;

	protected WorkspaceServlet(WorkspaceServletProviderConfiguration config) {
		fConfig = config;
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		if (!Webs.isNormalRequest(request)) {
			try (DispatcherRequestScope scope = new DispatcherRequestScope(request, response)) {
				WorkspaceScriptContext context = scope.getActionScriptContext();

				new SimpleActionChain(AdaptableList.<Action>newBuilder()
						.add(new FilterAction())
						.add(new EvaluateAction())
						.build()).doAction(context);
			} catch (ActionException ex) {
				throw Cause.create(ex).wrap(ServletException.class);
			}
			return;
		}

		try (WorkspaceScriptContext context = Webs.newActionScriptContext(fConfig.getWorkspaceName(), request)) {
			context.setAttribute("request", request);
			context.setAttribute("response", response);
			context.setAttribute("session", request.getSession());
			context.setAttribute("application", request.getServletContext());
			context.setCredentials((Credentials) request.getSession().getAttribute(Credentials.class.getName()));
			request.getServletContext().setAttribute(ServletContext.TEMPDIR, CmsService.getTemporaryDirectoryPath().toFile());
			Scripts.prepareAPIs(context);

			new SimpleActionChain(AdaptableList.<Action>newBuilder()
					.add(new HandleErrorAction())
					.add(new SetDefaultResponseHeaderAction())
					.add(new CheckProtectedAction())
					.add(new FilterAction())
					.add(new EvaluateAction())
					.build()).doAction(context);
		} catch (ActionException ex) {
			throw Cause.create(ex).wrap(ServletException.class);
		}
	}

	private static class DispatcherRequestScope implements Closeable {
		private final WorkspaceScriptContext fContext;
		private final Map<String, Object> fAttributes = new HashMap<>();

		private DispatcherRequestScope(HttpServletRequest request, HttpServletResponse response) {
			fContext = Webs.getActionScriptContext(request);
			for (String name : new String[] { "request", "response", "resource", "resourcePath" }) {
				fAttributes.put(name, fContext.getAttribute(name));
			}
			fContext.setAttribute("request", request);
			fContext.setAttribute("response", response);
			fContext.removeAttribute("resource");
			fContext.removeAttribute("resourcePath");
		}

		public WorkspaceScriptContext getActionScriptContext() {
			return fContext;
		}

		@Override
		public void close() throws IOException {
			for (String name : fAttributes.keySet()) {
				fContext.setAttribute(name, fAttributes.get(name));
			}
		}
	}

}

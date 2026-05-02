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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.util.Action;
import org.mintjams.tools.util.ActionChain;
import org.mintjams.tools.util.ActionContext;
import org.mintjams.tools.util.ActionException;

public class CheckPreviewAction implements Action {

	public void doAction(ActionContext context, ActionChain chain) throws ActionException {
		WorkspaceScriptContext ctx = Scripts.getWorkspaceScriptContext(context);
		HttpServletRequest request = Webs.getRequest(context);

		if (!Webs.isPreviewRequest(request)) {
			chain.doAction(context);
			return;
		}

		if (!"POST".equals(request.getMethod())) {
			Webs.getResponse(context).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		try {
			if (ctx.getRepositorySession().isAnonymous()) {
				Webs.getResponse(context).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			}

			ctx.setAttribute("request", new GetMethodRequestWrapper(request));
			try {
				chain.doAction(context);
			} finally {
				ctx.setAttribute("request", request);
			}
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(ActionException.class);
		}
	}

	private static class GetMethodRequestWrapper extends HttpServletRequestWrapper {
		GetMethodRequestWrapper(HttpServletRequest request) {
			super(request);
		}

		@Override
		public String getMethod() {
			return "GET";
		}
	}

}

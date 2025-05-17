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

package org.mintjams.rt.cms.internal.security;

import javax.jcr.Credentials;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.webconsole.WebConsoleSecurityProvider2;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.web.Webs;

public class FelixWebConsoleSecurityProvider implements WebConsoleSecurityProvider2 {

	public static final String SERVICE_PID = "org.mintjams.rt.cms.FelixWebConsoleSecurityProvider";

	@Override
	public Object authenticate(String username, String password) {
		return null;
	}

	@Override
	public boolean authorize(Object user, String role) {
		return true;
	}

	@Override
	public boolean authenticate(HttpServletRequest request, HttpServletResponse response) {
		try (WorkspaceScriptContext context = Webs.newActionScriptContext("system", request)) {
			context.setAttribute("request", request);
			context.setAttribute("response", response);
			context.setAttribute("session", request.getSession());
			context.setAttribute("application", request.getServletContext());
			context.setCredentials((Credentials) request.getSession().getAttribute(Credentials.class.getName()));
			request.getServletContext().setAttribute(ServletContext.TEMPDIR, CmsService.getTemporaryDirectoryPath().toFile());
			Scripts.prepareAPIs(context);

			if (context.getSession().isAdmin()) {
				return true;
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error("An error occurred during user authentication.", ex);
		}

		return false;
	}

}

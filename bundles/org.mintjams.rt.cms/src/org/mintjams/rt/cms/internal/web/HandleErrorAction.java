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

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.util.Action;
import org.mintjams.tools.util.ActionChain;
import org.mintjams.tools.util.ActionContext;
import org.mintjams.tools.util.ActionException;

public class HandleErrorAction implements Action {

	public void doAction(ActionContext context, ActionChain chain) throws ActionException {
		try {
			chain.doAction(context);
			new ErrorPages(context).setStatus(Webs.getResponseStatus(context)).handle();
		} catch (Throwable exception) {
			CmsService.getLogger(getClass()).error("An error occurred while processing the request.", exception);
			try {
				if (!new ErrorPages(context).setException(exception).handle()) {
					Webs.getResponse(context).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				}
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ActionException.class);
			}
		}
	}

	private static class ErrorPages {
		private final ActionContext fContext;
		private int fStatus = -1;
		private Throwable fException = null;

		private ErrorPages(ActionContext context) {
			fContext = context;
		}

		public ErrorPages setStatus(int status) {
			fStatus = status;
			return this;
		}

		public ErrorPages setException(Throwable exception) {
			fException = exception;
			return this;
		}

		public boolean handle() throws ServletException, IOException {
			if (!Webs.isNormalRequest(fContext)) {
				return true;
			}

			if (Webs.getResponse(fContext).isCommitted()) {
				return true;
			}

			Collection<Map<String, Object>> errorPages = Webs.getErrorPageConfig(fContext);
			if (errorPages == null || errorPages.isEmpty()) {
				return false;
			}

			String resourcePath = Webs.getResourcePath(fContext);
			for (Map<String, Object> errorPage : errorPages) {
				AdaptableMap<String, Object> config = AdaptableMap.<String, Object>newBuilder().putAll(errorPage).build();
				String location = null;
				for (String urlPattern : Scripts.asStringArray(config.get("urlPattern"))) {
					if (urlPattern.startsWith("regexp:")) {
						if (resourcePath.matches(urlPattern.substring("regexp:".length()))) {
							location = Strings.defaultString(config.getString("location"));
						}
					} else {
						do {
							if (urlPattern.endsWith("/*")) {
								if (resourcePath.startsWith(urlPattern.substring(0, urlPattern.length() - 1))) {
									location = Strings.defaultString(config.getString("location"));
								}
								break;
							}

							if (urlPattern.startsWith("*.")) {
								if (resourcePath.endsWith(urlPattern.substring(1))) {
									location = Strings.defaultString(config.getString("location"));
								}
								break;
							}

							if (resourcePath.equals(urlPattern)) {
								location = Strings.defaultString(config.getString("location"));
								break;
							}
						} while (false);
					}

					if (location != null) {
						break;
					}
				}
				if (location == null) {
					continue;
				}

				if (Strings.isEmpty(location)) {
					return false;
				}

				if (fStatus != -1) {
					if (fStatus == config.getInteger("errorCode")) {
						HttpServletRequest request = Webs.getRequest(fContext);
						request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, request.getServerName());
						request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
						request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, fStatus);
						request.getServletContext().getRequestDispatcher(request.getServletPath() + location).forward(request, Webs.getResponse(fContext));
						return true;
					}
				}

				if (fException != null && !Strings.isEmpty(config.getString("exceptionType"))) {
					Class<?> exceptionClass = null;
					try {
						exceptionClass = Scripts.getClassLoader(fContext).loadClass(config.getString("exceptionType"));
					} catch (Throwable ignore) {}

					for (Throwable cause = fException;; cause = cause.getCause()) {
						if (cause == null) {
							break;
						}

						if (cause.getClass().getName().equals(config.getString("exceptionType"))
								|| (exceptionClass != null && exceptionClass.isInstance(cause))) {
							HttpServletRequest request = Webs.getRequest(fContext);
							request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, request.getServerName());
							request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
							request.setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE, cause.getClass().getName());
							request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, cause);
							request.setAttribute(RequestDispatcher.ERROR_MESSAGE, cause.getMessage());
							request.getServletContext().getRequestDispatcher(request.getServletPath() + location).forward(request, Webs.getResponse(fContext));
							return true;
						}
					}
				}
			}
			return false;
		}
	}

}

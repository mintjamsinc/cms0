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
import java.io.InputStream;
import java.nio.file.Path;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.util.Action;
import org.mintjams.tools.util.ActionChain;
import org.mintjams.tools.util.ActionContext;
import org.mintjams.tools.util.ActionException;

public class EvaluateAction implements Action {

	public void doAction(ActionContext context, ActionChain chain) throws ActionException {
		WorkspaceScriptContext ctx = Scripts.getWorkspaceScriptContext(context);
		HttpServletRequest request = Webs.getRequest(context);
		HttpServletResponse response = Webs.getResponse(context);

		try {
			WebResourceResolver.ResolveResult result = new WebResourceResolver(context).resolve(Webs.getResourcePath(context));
			if (result.isNotFound()) {
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			if (Webs.getResourcePath(context).endsWith("/") && !result.isFolder()) {
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			if (result.isAccessDenied()) {
				response.setStatus(HttpServletResponse.SC_FORBIDDEN);
				return;
			}

			ctx.setAttribute("resource", ctx.getResourceResolver().toResource(result.getNode()));

			if (result.isFolder()) {
				handleFolder(context);
				return;
			}

			if (!result.hasTemplate()) {
				if (result.isScriptable()) {
					if (Webs.isNormalRequest(context)) {
						result.setEncodingTo(response);
						response.addDateHeader("Last-Modified", result.getLastModified().getTime());
						setNoCacheHeader(response);
					}
					try (ScriptReader scriptReader = new ScriptReader(result.getContentAsReader())) {
						scriptReader
								.setScriptName("jcr://" + result.getPath())
								.setExtension(result.getScriptExtension())
								.setLastModified(result.getLastModified())
								.setScriptEngineManager(Scripts.getScriptEngineManager(context))
								.setClassLoader(Scripts.getClassLoader(context))
								.setScriptContext(ctx)
								.eval();
					}
					return;
				}

				if (request.getMethod().equalsIgnoreCase("GET")) {
					RangeHeader rangeHeader = null;

					if (Webs.isNormalRequest(context)) {
						String eTag = "" + result.getLastModified().getTime();
						response.addDateHeader("Last-Modified", result.getLastModified().getTime());
						response.setHeader("ETag", eTag);
						response.setContentType(result.getMimeType());
						setCacheHeader(response);

						if (RangeHeader.isRangeRequest(request)) {
							try {
								rangeHeader = RangeHeader.create(request);
							} catch (Throwable ignore) {
								response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
								return;
							}

							String ifRange = request.getHeader("If-Range");
							if (!Strings.isEmpty(ifRange) && !ifRange.equals(eTag)) {
								rangeHeader = null;
							}
						}
					}

					if (rangeHeader != null) {
						response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
						response.setHeader("Accept-Ranges", "bytes");
						long end;
						long length;
						if (rangeHeader.getEnd() >= 0) {
							end = rangeHeader.getEnd();
							length = rangeHeader.getLength();
						} else {
							end = result.getContentLength() - 1;
							length = result.getContentLength() - rangeHeader.getStart();
						}
						response.setContentLengthLong(length);
						response.setHeader("Content-Range", "bytes " + rangeHeader.getStart() + "-" + end + "/" + result.getContentLength());
					}

					try (InputStream in = result.getContentAsStream()) {
						if (rangeHeader == null) {
							IOs.copy(in, response.getOutputStream());
						} else {
							IOs.copy(in, response.getOutputStream(), rangeHeader.getStart(), rangeHeader.getLength());
						}
					}
					return;
				}
				return;
			}

			if (Webs.isNormalRequest(context)) {
				result.setEncodingTo(response);
				response.addDateHeader("Last-Modified", result.getLastModified().getTime());
				response.setContentType(Scripts.getFileTypeDetector(context).probeContentType(Path.of(Webs.getResourcePath(context))));
				setNoCacheHeader(response);
			}
			try (ScriptReader scriptReader = new ScriptReader(result.getTemplate().getContentAsReader())) {
				scriptReader
						.setScriptName("jcr://" + result.getTemplate().getPath())
						.setExtension(result.getTemplate().getScriptExtension())
						.setLastModified(result.getLastModified())
						.setScriptEngineManager(Scripts.getScriptEngineManager(context))
						.setClassLoader(Scripts.getClassLoader(context))
						.setScriptContext(ctx)
						.eval();
			}
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(ActionException.class);
		}

		chain.doAction(context);
	}

	private void handleFolder(ActionContext context) throws RepositoryException, IOException {
		String httpMethod = Webs.getRequest(context).getMethod();
		if (!(httpMethod.equalsIgnoreCase("GET") || httpMethod.equalsIgnoreCase("HEAD"))) {
			Webs.getResponse(context).setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		String parentPath = Webs.getResourcePath(context);
		if (!parentPath.endsWith("/")) {
			parentPath += "/";
		}

		for (String welcomeFile : Webs.getWelcomeFiles(context)) {
			if (!new WebResourceResolver(context).resolve(parentPath + welcomeFile).isNotFound()) {
				Webs.getResponse(context).sendRedirect(Webs.getRequest(context).getServletPath() + Webs.encodePath(parentPath) + "/" + welcomeFile);
				return;
			}
		}

		Webs.getResponse(context).setStatus(HttpServletResponse.SC_NOT_FOUND);
		return;
	}

	public void setNoCacheHeader(HttpServletResponse response) {
		response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
		response.setHeader("Expires", "0");
		response.setHeader("Pragma", "no-cache");
	}

	public void setCacheHeader(HttpServletResponse response) {
		response.setHeader("Cache-Control", "public, max-age=31536000");
		response.setHeader("Expires", "");
		response.setHeader("Pragma", "");
	}

}

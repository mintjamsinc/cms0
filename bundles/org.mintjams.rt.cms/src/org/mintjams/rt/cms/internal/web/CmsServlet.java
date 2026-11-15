/*
 * Copyright (c) 2026 MintJams Inc.
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

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsService;

/**
 * The base of the servlets registered as components of this bundle. They are
 * registered as soon as the bundle starts, before {@link CmsService} is up, so
 * every request first checks that the CMS serves requests and is answered 503
 * with {@code Retry-After} while it is starting.
 */
public abstract class CmsServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	/** Seconds a client is told to wait before retrying while the CMS or a workspace starts. */
	public static final int RETRY_AFTER_SECONDS = 5;

	@Override
	public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
		if (!CmsService.isAvailable()) {
			sendServiceUnavailable((HttpServletResponse) response, "The server is starting.");
			return;
		}
		super.service(request, response);
	}

	/** Answers 503 with {@code Retry-After}. */
	public static void sendServiceUnavailable(HttpServletResponse response, String message) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
		response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, message);
	}

}

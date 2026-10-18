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

import java.util.Collections;
import java.util.Enumeration;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * The {@code session} that web scripts and templates see. It accepts only
 * strings as attribute values (structured values go in as JSON): a session
 * travels between the cluster nodes, and a script's own class is redefined on
 * every redeployment, so an object of one would break on the next node or the
 * next deployment instead of where it was stored. Refusing it here makes the
 * script's author see the problem at once.
 *
 * <p>The HTTP session itself is created only when a script writes to it or
 * asks for its identity, so pages that never use the session do not make one
 * for every visitor.
 */
@SuppressWarnings("deprecation")
public final class ScriptSession implements HttpSession {

	private final HttpServletRequest fRequest;

	public ScriptSession(HttpServletRequest request) {
		fRequest = request;
	}

	private HttpSession existing() {
		return fRequest.getSession(false);
	}

	private HttpSession session() {
		return fRequest.getSession(true);
	}

	private static void check(String name, Object value) {
		if (value != null && !(value instanceof String)) {
			throw new IllegalArgumentException("The session attribute '" + name + "' must be a string, not "
					+ value.getClass().getName() + "; store structured values as JSON and keep state in the repository.");
		}
	}

	@Override
	public long getCreationTime() {
		return session().getCreationTime();
	}

	@Override
	public String getId() {
		return session().getId();
	}

	@Override
	public long getLastAccessedTime() {
		return session().getLastAccessedTime();
	}

	@Override
	public ServletContext getServletContext() {
		return fRequest.getServletContext();
	}

	@Override
	public void setMaxInactiveInterval(int interval) {
		session().setMaxInactiveInterval(interval);
	}

	@Override
	public int getMaxInactiveInterval() {
		return session().getMaxInactiveInterval();
	}

	@Override
	public javax.servlet.http.HttpSessionContext getSessionContext() {
		return session().getSessionContext();
	}

	@Override
	public Object getAttribute(String name) {
		HttpSession session = existing();
		return (session == null) ? null : session.getAttribute(name);
	}

	@Override
	public Object getValue(String name) {
		return getAttribute(name);
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		HttpSession session = existing();
		return (session == null) ? Collections.emptyEnumeration() : session.getAttributeNames();
	}

	@Override
	public String[] getValueNames() {
		return Collections.list(getAttributeNames()).toArray(String[]::new);
	}

	@Override
	public void setAttribute(String name, Object value) {
		check(name, value);
		if (value == null) {
			removeAttribute(name);
			return;
		}
		session().setAttribute(name, value);
	}

	@Override
	public void putValue(String name, Object value) {
		setAttribute(name, value);
	}

	@Override
	public void removeAttribute(String name) {
		HttpSession session = existing();
		if (session != null) {
			session.removeAttribute(name);
		}
	}

	@Override
	public void removeValue(String name) {
		removeAttribute(name);
	}

	@Override
	public void invalidate() {
		HttpSession session = existing();
		if (session != null) {
			session.invalidate();
		}
	}

	@Override
	public boolean isNew() {
		HttpSession session = existing();
		return session == null || session.isNew();
	}

}

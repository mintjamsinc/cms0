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

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.Session;

import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.searchindex.SearchIndex;

/**
 * The principals an index query is restricted to on behalf of a session: the
 * same set the JCR query layer applies to a query's nodes, so an index-only
 * query (facet counts, suggestions) covers exactly the documents the session
 * may read.
 *
 * <p>None for a system, service or admin session, which read everything;
 * everyone for a guest; everyone plus the user and its groups otherwise.
 */
public final class QueryAuthorizables {

	private QueryAuthorizables() {}

	public static Principal[] of(Session session) {
		if (!(session instanceof org.mintjams.jcr.Session)) {
			return new Principal[0];
		}
		org.mintjams.jcr.Session jcrSession = (org.mintjams.jcr.Session) session;
		if (jcrSession.isSystem() || jcrSession.isService() || jcrSession.isAdmin()) {
			return new Principal[0];
		}
		List<Principal> authorizables = new ArrayList<>();
		authorizables.add(new EveryonePrincipal());
		if (!jcrSession.isGuest()) {
			authorizables.addAll(jcrSession.getGroups());
			authorizables.add(jcrSession.getUserPrincipal());
		}
		return authorizables.toArray(Principal[]::new);
	}

	/**
	 * Restricts {@code query} to what {@code session} may read. A session that
	 * reads everything leaves the query unrestricted.
	 */
	public static SearchIndex.Query apply(SearchIndex.Query query, Session session) {
		Principal[] authorizables = of(session);
		if (authorizables.length > 0) {
			query.setAuthorizables(authorizables);
		}
		return query;
	}
}

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

package org.mintjams.rt.cms.internal.graphql;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.Session;

/**
 * Decides who is offered a Webtop app, from the {@code roles} list of its
 * {@code app.yml}. Shared by {@link QueryExecutor} and the platform wiring,
 * which both list apps.
 *
 * <pre>
 * roles: [site-editor, site-approver, site-administrator]
 * </pre>
 *
 * <ul>
 * <li>No {@code roles}: the app is offered to everyone.</li>
 * <li>{@code roles}: the app is offered to the holders of at least one of the
 * roles (the roles a user holds directly, as in access control), and to
 * administrators, whatever the list names.</li>
 * <li>An empty list, or a value that is not a list of names: administrators
 * only.</li>
 * </ul>
 *
 * This says which apps the shell lists - the start menu, the widgets, the
 * apps a file opens with, the extensions - not who may read the app's files
 * or call what it calls: those are access control and the checks of each
 * operation.
 */
public final class AppRoles {

	private AppRoles() {}

	/** Whether the app whose descriptor holds {@code rolesObj} as {@code roles} is offered to the session's user. */
	public static boolean isOffered(Session session, Object rolesObj) {
		if (rolesObj == null) {
			return true;
		}
		if (!(session instanceof org.mintjams.jcr.Session)) {
			return false;
		}
		org.mintjams.jcr.Session jcrSession = (org.mintjams.jcr.Session) session;
		if (jcrSession.isAdmin() || jcrSession.isSystem() || jcrSession.isService()) {
			return true;
		}
		List<String> roles = names(rolesObj);
		if (roles.isEmpty()) {
			return false;
		}
		for (Principal role : jcrSession.getRoles()) {
			if (roles.contains(role.getName())) {
				return true;
			}
		}
		return false;
	}

	private static List<String> names(Object rolesObj) {
		List<String> names = new ArrayList<>();
		if (rolesObj instanceof String) {
			add(names, (String) rolesObj);
		} else if (rolesObj instanceof Iterable) {
			for (Object item : (Iterable<?>) rolesObj) {
				if (item instanceof String) {
					add(names, (String) item);
				}
			}
		}
		return names;
	}

	private static void add(List<String> names, String name) {
		String trimmed = name.trim();
		if (!trimmed.isEmpty()) {
			names.add(trimmed);
		}
	}

}

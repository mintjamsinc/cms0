/*
 * Copyright (c) 2024 MintJams Inc.
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

import java.util.HashMap;
import java.util.Map;

import javax.jcr.Session;

import org.mintjams.jcr.Workspace;
import org.mintjams.jcr.security.Group;
import org.mintjams.jcr.security.IdentityProvider;
import org.mintjams.jcr.security.User;

/**
 * Resolves the display name of a user/group identifier through the workspace
 * IdentityProvider, with per-instance memoization.
 *
 * GraphQL list queries (children, ACL entries, principal search, ...) can map
 * the same identifier many times within one request. Each lookup hits the
 * system workspace, so callers should construct one resolver per request and
 * share it across all mappings in that request.
 *
 * Methods return {@code null} when the principal cannot be resolved or has no
 * displayName property; callers should fall back to the identifier itself.
 *
 * Not thread-safe — instances are intended to be confined to a single request.
 */
public class PrincipalDisplayNameResolver {

	/** Sentinel stored in caches to memoize "lookup attempted, no display name". */
	private static final String NONE = "\0\0NONE\0\0";

	private final Session session;
	private final Map<String, String> userCache = new HashMap<>();
	private final Map<String, String> groupCache = new HashMap<>();

	public PrincipalDisplayNameResolver(Session session) {
		this.session = session;
	}

	/**
	 * Returns the display name for the user with the given identifier, or
	 * {@code null} if the user does not exist or has no displayName set.
	 */
	public String getUserDisplayName(String identifier) {
		if (identifier == null || identifier.isEmpty()) {
			return null;
		}
		String cached = userCache.get(identifier);
		if (cached != null) {
			return cached == NONE ? null : cached;
		}
		String resolved = null;
		try {
			IdentityProvider idp = identityProvider();
			if (idp != null) {
				User user = idp.getUser(identifier);
				if (user != null) {
					resolved = user.getDisplayName();
				}
			}
		} catch (Throwable ignore) {
			// fall through with null
		}
		userCache.put(identifier, resolved == null ? NONE : resolved);
		return resolved;
	}

	/**
	 * Returns the display name for the group with the given identifier, or
	 * {@code null} if the group does not exist or has no displayName set.
	 */
	public String getGroupDisplayName(String identifier) {
		if (identifier == null || identifier.isEmpty()) {
			return null;
		}
		String cached = groupCache.get(identifier);
		if (cached != null) {
			return cached == NONE ? null : cached;
		}
		String resolved = null;
		try {
			IdentityProvider idp = identityProvider();
			if (idp != null) {
				Group group = idp.getGroup(identifier);
				if (group != null) {
					resolved = group.getDisplayName();
				}
			}
		} catch (Throwable ignore) {
			// fall through with null
		}
		groupCache.put(identifier, resolved == null ? NONE : resolved);
		return resolved;
	}

	/**
	 * Returns the display name when the principal kind is known.
	 * For an unknown principal (id has no display name set, or lookup failed),
	 * returns {@code null}.
	 */
	public String resolve(String identifier, boolean isGroup) {
		return isGroup ? getGroupDisplayName(identifier) : getUserDisplayName(identifier);
	}

	/**
	 * Returns the display name when the principal kind is unknown.
	 * Looks up as a user first; if not found, falls back to a group lookup.
	 */
	public String resolveUnknown(String identifier) {
		String name = getUserDisplayName(identifier);
		if (name != null) {
			return name;
		}
		return getGroupDisplayName(identifier);
	}

	private IdentityProvider identityProvider() {
		try {
			javax.jcr.Workspace ws = session.getWorkspace();
			if (ws instanceof Workspace) {
				return ((Workspace) ws).getIdentityProvider();
			}
		} catch (Throwable ignore) {
			// not a MintJams workspace, or session in an inconsistent state
		}
		return null;
	}
}

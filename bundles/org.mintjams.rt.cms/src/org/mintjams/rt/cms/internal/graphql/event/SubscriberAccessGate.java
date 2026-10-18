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

package org.mintjams.rt.cms.internal.graphql.event;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.jcr.RepositoryException;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.Repository;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.PrincipalProvider;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.cms.event.CmsEvent;
import org.mintjams.rt.cms.internal.cms.event.CmsEventHandler;
import org.mintjams.rt.cms.internal.cms.event.WorkspaceCmsEventManager;

/**
 * Decides, for the payload mappers of the live subscriptions, whether a
 * subscriber may read a path — without logging in.
 *
 * <p>Event delivery evaluates one event for every subscriber, and a stream per
 * browser tab means hundreds of subscribers per event. Impersonating each
 * subscriber in a session of their own made every decision a repository login
 * (with two more system logins behind it to resolve the user and their
 * groups), which capped delivery at a few hundred decisions per second and
 * queued events for minutes under a modest write rate. This gate instead asks
 * the repository for the decision from its committed access control entries
 * ({@link Repository#hasPrivileges}) and keeps two caches in front of it:
 *
 * <ul>
 *   <li><b>Identities</b> — the resolved user principal and group membership
 *       per user id. Dropped for a user when their profile under
 *       {@code /home/users} changes, for everyone when a group or role under
 *       {@code /home/groups} or {@code /home/roles} changes, and after
 *       {@link #IDENTITY_TTL_MS} regardless.</li>
 *   <li><b>Decisions</b> — the read decision per (user, path) of a workspace,
 *       tagged with the workspace's access control revision and the identity
 *       generation. An access control change anywhere in the workspace moves
 *       the revision as soon as it is observed, and an identity change moves
 *       the generation, so the next decision after either is evaluated afresh:
 *       a revoked read is honoured at the next event.</li>
 * </ul>
 *
 * <p>What the gate does not do: it does not check that the node exists (an
 * event for a node removed a moment later is delivered as it happened, and
 * the removal follows as its own event), and it does not see the access
 * control changes of a transaction that has not committed yet.
 */
public final class SubscriberAccessGate {

	/** How long a resolved identity is used before it is resolved again, whatever happens. */
	static final long IDENTITY_TTL_MS = 10 * 60 * 1000L;
	/** The decisions kept per workspace before the cache is simply emptied. */
	static final int MAX_DECISIONS = 50000;

	private static final String USERS_ROOT = "/home/users/";
	private static final String[] SHARED_IDENTITY_ROOTS = { "/home/groups", "/home/roles" };
	private static final String SYSTEM_WORKSPACE = "system";

	/** user id → resolved identity (or the fact that the user is unknown). */
	private static final ConcurrentMap<String, Identity> IDENTITIES = new ConcurrentHashMap<>();
	/** Moves whenever identities are dropped; decisions taken before are discarded. */
	private static final AtomicLong IDENTITY_GENERATION = new AtomicLong();
	/** workspace name → its decisions at one (revision, generation). */
	private static final ConcurrentMap<String, Decisions> DECISIONS = new ConcurrentHashMap<>();
	/** The system workspace's event manager the identity watch is registered on. */
	private static volatile WorkspaceCmsEventManager fWatched;
	private static final CmsEventHandler IDENTITY_WATCH = SubscriberAccessGate::onIdentityEvent;

	private SubscriberAccessGate() {}

	/** Whether the user may read the node at the path of the workspace. */
	public static boolean canRead(String workspaceName, String userId, String absPath) {
		if (workspaceName == null || userId == null || absPath == null || !absPath.startsWith("/")) {
			return false;
		}
		watchIdentityChanges();

		Identity identity = identity(userId);
		if (identity.principal == null) {
			return false;
		}

		Repository repository = repository();
		if (repository == null) {
			return false;
		}
		long revision;
		try {
			revision = repository.getAccessControlRevision(workspaceName);
		} catch (RepositoryException ex) {
			CmsService.getLogger(SubscriberAccessGate.class)
					.debug("The access control revision of the workspace '" + workspaceName + "' is not available.", ex);
			return false;
		}
		Decisions decisions = decisions(workspaceName, revision, IDENTITY_GENERATION.get());
		String key = userId + '\n' + absPath;
		Boolean decided = decisions.fByUserAndPath.get(key);
		if (decided != null) {
			return decided;
		}

		boolean readable;
		try {
			readable = repository.hasPrivileges(workspaceName, identity.principal, identity.groups, absPath,
					Privilege.JCR_READ);
		} catch (Throwable ex) {
			// Undecidable: never deliver on a guess.
			CmsService.getLogger(SubscriberAccessGate.class)
					.debug("Could not decide whether '" + userId + "' may read " + absPath + "; treated as not readable.", ex);
			return false;
		}
		if (decisions.fByUserAndPath.size() >= MAX_DECISIONS) {
			decisions.fByUserAndPath.clear();
		}
		decisions.fByUserAndPath.put(key, readable);
		return readable;
	}

	/** Whether the user may read the parent of the path (the root's parent is the root). */
	public static boolean canReadParent(String workspaceName, String userId, String absPath) {
		if (absPath == null) {
			return false;
		}
		int i = absPath.lastIndexOf('/');
		return canRead(workspaceName, userId, (i <= 0) ? "/" : absPath.substring(0, i));
	}

	/** Drops every cached identity and decision (tests and workspace restarts). */
	public static void reset() {
		IDENTITIES.clear();
		DECISIONS.clear();
		IDENTITY_GENERATION.incrementAndGet();
	}

	private static Identity identity(String userId) {
		long now = System.currentTimeMillis();
		Identity identity = IDENTITIES.get(userId);
		if (identity != null && now - identity.resolvedAt < IDENTITY_TTL_MS) {
			return identity;
		}
		identity = resolve(userId, now);
		IDENTITIES.put(userId, identity);
		return identity;
	}

	/** The platform repository, which implements the extended interface, or null when it is not available. */
	private static Repository repository() {
		javax.jcr.Repository repository = CmsService.getRepository();
		return (repository instanceof Repository) ? (Repository) repository : null;
	}

	private static Identity resolve(String userId, long now) {
		Repository repository = repository();
		if (repository == null) {
			return new Identity(null, Collections.emptyList(), now);
		}
		PrincipalProvider principalProvider = repository.getPrincipalProvider();
		UserPrincipal principal;
		try {
			principal = principalProvider.getUserPrincipal(userId);
		} catch (PrincipalNotFoundException ex) {
			return new Identity(null, Collections.emptyList(), now);
		} catch (Throwable ex) {
			CmsService.getLogger(SubscriberAccessGate.class).debug("Could not resolve the user '" + userId + "'.", ex);
			return new Identity(null, Collections.emptyList(), now);
		}
		Collection<GroupPrincipal> groups;
		try {
			groups = principalProvider.getMemberOf(principal);
		} catch (PrincipalNotFoundException ex) {
			groups = Collections.emptyList();
		} catch (Throwable ex) {
			CmsService.getLogger(SubscriberAccessGate.class)
					.debug("Could not resolve the groups of the user '" + userId + "'.", ex);
			groups = Collections.emptyList();
		}
		return new Identity(principal, Collections.unmodifiableCollection(groups), now);
	}

	private static Decisions decisions(String workspaceName, long revision, long generation) {
		Decisions decisions = DECISIONS.get(workspaceName);
		if (decisions == null || decisions.fRevision != revision || decisions.fGeneration != generation) {
			decisions = new Decisions(revision, generation);
			DECISIONS.put(workspaceName, decisions);
		}
		return decisions;
	}

	/**
	 * Registers the identity watch on the system workspace's event manager (the
	 * users, groups and roles live there), again when the workspace was
	 * restarted with a new manager. Anything that happened while no manager was
	 * watched is covered by dropping every identity on (re)registration.
	 */
	private static void watchIdentityChanges() {
		WorkspaceCmsEventManager manager = CmsService.getWorkspaceCmsEventManager(SYSTEM_WORKSPACE);
		if (manager == null || manager == fWatched) {
			return;
		}
		synchronized (SubscriberAccessGate.class) {
			if (manager == fWatched) {
				return;
			}
			manager.addEventHandler(IDENTITY_WATCH);
			fWatched = manager;
			IDENTITIES.clear();
			IDENTITY_GENERATION.incrementAndGet();
		}
	}

	/** A change under {@code /home}: drops the identities it may have changed. */
	private static void onIdentityEvent(CmsEvent event) {
		try {
			invalidateFor(event.getPath());
			invalidateFor(event.getSourcePath());
		} catch (Throwable ignore) {}
	}

	private static void invalidateFor(String path) {
		if (path == null) {
			return;
		}
		for (String root : SHARED_IDENTITY_ROOTS) {
			if (path.equals(root) || path.startsWith(root + "/")) {
				// Group and role definitions are shared by every identity.
				IDENTITIES.clear();
				IDENTITY_GENERATION.incrementAndGet();
				return;
			}
		}
		if (path.equals(USERS_ROOT.substring(0, USERS_ROOT.length() - 1))) {
			IDENTITIES.clear();
			IDENTITY_GENERATION.incrementAndGet();
			return;
		}
		if (!path.startsWith(USERS_ROOT)) {
			return;
		}
		String rest = path.substring(USERS_ROOT.length());
		int slash = rest.indexOf('/');
		String userId = (slash < 0) ? rest : rest.substring(0, slash);
		String below = (slash < 0) ? "" : rest.substring(slash);
		// The identity is read from the profile; the rest of the home (desktop,
		// preferences, …) changes all the time and does not affect it.
		if (!below.isEmpty() && !below.equals("/profile") && !below.startsWith("/profile/")) {
			return;
		}
		if (IDENTITIES.remove(userId) != null) {
			String prefix = userId + '\n';
			for (Decisions decisions : DECISIONS.values()) {
				for (Iterator<Map.Entry<String, Boolean>> i = decisions.fByUserAndPath.entrySet().iterator(); i.hasNext();) {
					if (i.next().getKey().startsWith(prefix)) {
						i.remove();
					}
				}
			}
		}
	}

	/** A user as the repository resolves them; {@code principal} is null for an unknown user. */
	private static final class Identity {
		final UserPrincipal principal;
		final Collection<GroupPrincipal> groups;
		final long resolvedAt;

		Identity(UserPrincipal principal, Collection<GroupPrincipal> groups, long resolvedAt) {
			this.principal = principal;
			this.groups = groups;
			this.resolvedAt = resolvedAt;
		}
	}

	/** The decisions of one workspace, valid for one access control revision and identity generation. */
	private static final class Decisions {
		final long fRevision;
		final long fGeneration;
		final ConcurrentMap<String, Boolean> fByUserAndPath = new ConcurrentHashMap<>();

		Decisions(long revision, long generation) {
			fRevision = revision;
			fGeneration = generation;
		}
	}

}

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

package org.mintjams.rt.jcr.internal;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.sql.Query;

/**
 * A workspace-wide, commit-consistent in-memory view of all access control entries.
 * <p>
 * The store holds an immutable snapshot of the {@code jcr_aces} table keyed by item path.
 * Readers obtain the current snapshot without locking and evaluate privileges entirely
 * in memory; the snapshot is replaced atomically (copy-on-write) whenever a transaction
 * that affected access control state is committed. Sessions that carry uncommitted
 * access control changes must not consult the store; they fall back to evaluating
 * against their own transaction (see {@code WorkspaceQuery#isAccessControlAffected()}).
 */
public class AccessControlStore implements Closeable, Adaptable {

	private final JcrWorkspaceProvider fWorkspaceProvider;
	private volatile Snapshot fSnapshot;
	private volatile boolean fStale = true;
	private long fRevision;

	private AccessControlStore(JcrWorkspaceProvider workspaceProvider) {
		fWorkspaceProvider = workspaceProvider;
	}

	public static AccessControlStore create(JcrWorkspaceProvider workspaceProvider) {
		return new AccessControlStore(workspaceProvider);
	}

	public AccessControlStore open() throws IOException {
		reload();
		return this;
	}

	/**
	 * Returns the current snapshot, reloading it first if it has been marked stale.
	 * Fails (rather than serving stale security data) when the reload is not possible.
	 */
	public Snapshot getSnapshot() throws IOException {
		Snapshot snapshot = fSnapshot;
		if (!fStale && snapshot != null) {
			return snapshot;
		}
		synchronized (this) {
			if (fStale || fSnapshot == null) {
				reload();
			}
			return fSnapshot;
		}
	}

	public boolean isStale() {
		return fStale;
	}

	/**
	 * Marks the snapshot as stale. The next {@link #getSnapshot()} call reloads it.
	 */
	public void markStale() {
		fStale = true;
	}

	/**
	 * Returns whether access control entries exist at the given path. Answers
	 * conservatively ({@code true}) while the snapshot is stale or not yet loaded.
	 */
	public boolean hasEntriesAt(String absPath) {
		Snapshot snapshot = fSnapshot;
		if (fStale || snapshot == null) {
			return true;
		}
		return snapshot.hasEntries(absPath);
	}

	/**
	 * Returns whether access control entries exist at or below the given path. Answers
	 * conservatively ({@code true}) while the snapshot is stale or not yet loaded.
	 */
	public boolean hasEntriesUnder(String absPath) {
		Snapshot snapshot = fSnapshot;
		if (fStale || snapshot == null) {
			return true;
		}
		return snapshot.hasEntriesUnder(absPath);
	}

	/**
	 * Returns whether access control entries exist strictly below the given path
	 * (entries at the path itself do not count — they apply uniformly to the whole
	 * subtree). Answers conservatively ({@code true}) while the snapshot is stale
	 * or not yet loaded.
	 */
	public boolean hasEntriesBelow(String absPath) {
		Snapshot snapshot = fSnapshot;
		if (fStale || snapshot == null) {
			return true;
		}
		return snapshot.hasEntriesBelow(absPath);
	}

	public synchronized void reload() throws IOException {
		long started = System.currentTimeMillis();
		TreeMap<String, List<Entry>> entries = new TreeMap<>();
		int count = 0;
		try (Connection connection = fWorkspaceProvider.getConnection(new SystemPrincipal())) {
			try {
				StringBuilder statement = new StringBuilder()
						.append("SELECT i.item_path, a.principal_name, a.is_group, a.privilege_names, a.is_allow")
						.append(" FROM jcr_aces a")
						.append(" JOIN jcr_items i ON (a.item_id = i.item_id)")
						.append(" WHERE i.is_deleted = FALSE")
						.append(" ORDER BY i.item_path, a.row_no");
				try (Query.Result result = Query.newBuilder(connection).setStatement(statement.toString()).build()
						.setOffset(0).execute()) {
					for (AdaptableMap<String, Object> r : result) {
						List<Entry> l = entries.computeIfAbsent(r.getString("item_path"), k -> new ArrayList<>());
						l.add(new Entry(r.getString("principal_name"), r.getBoolean("is_group"),
								r.getBoolean("is_allow"),
								Arrays.stream(r.getObjectArray("privilege_names")).toArray(String[]::new)));
						count++;
					}
				}
			} finally {
				try {
					connection.rollback();
				} catch (Throwable ignore) {}
			}
		} catch (SQLException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
		for (Map.Entry<String, List<Entry>> e : entries.entrySet()) {
			e.setValue(Collections.unmodifiableList(e.getValue()));
		}
		fSnapshot = new Snapshot(entries, ++fRevision);
		fStale = false;

		int threshold = adaptTo(JcrRepository.class).getConfiguration().getAccessControlStoreWarningThreshold();
		if (threshold > 0 && count > threshold) {
			Activator.getDefault().getLogger(getClass())
					.warn("The access control store of the JCR workspace '" + fWorkspaceProvider.getWorkspaceName()
							+ "' holds " + count + " entries, exceeding the configured warning threshold ("
							+ threshold + ").");
		}
		Activator.getDefault().getLogger(getClass())
				.debug("The access control store of the JCR workspace '" + fWorkspaceProvider.getWorkspaceName()
						+ "' has been loaded with " + count + " entries in "
						+ (System.currentTimeMillis() - started) + " ms.");
	}

	@Override
	public synchronized void close() throws IOException {
		fSnapshot = null;
		fStale = true;
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspaceProvider, adapterType);
	}

	/**
	 * An immutable view of all access control entries at a point in time.
	 */
	public static class Snapshot {
		private final TreeMap<String, List<Entry>> fEntries;
		private final long fRevision;

		private Snapshot(TreeMap<String, List<Entry>> entries, long revision) {
			fEntries = entries;
			fRevision = revision;
		}

		/**
		 * Returns the access control entries at the given path in definition (row) order,
		 * or an empty list if the path carries no access control list.
		 */
		public List<Entry> getEntries(String absPath) {
			List<Entry> l = fEntries.get(absPath);
			return (l != null) ? l : Collections.emptyList();
		}

		public boolean hasEntries(String absPath) {
			return fEntries.containsKey(absPath);
		}

		public boolean hasEntriesUnder(String absPath) {
			if (fEntries.containsKey(absPath)) {
				return true;
			}
			return hasEntriesBelow(absPath);
		}

		/** Returns whether entries exist strictly below the given path (excluding the path itself). */
		public boolean hasEntriesBelow(String absPath) {
			String prefix = absPath.endsWith("/") ? absPath : absPath + "/";
			String key = fEntries.ceilingKey(prefix);
			return (key != null && key.startsWith(prefix));
		}

		public long getRevision() {
			return fRevision;
		}

		public int size() {
			return fEntries.size();
		}
	}

	/**
	 * A single access control entry.
	 */
	public static class Entry {
		private final String fPrincipalName;
		private final boolean fGroup;
		private final boolean fAllow;
		private final String[] fPrivilegeNames;

		private Entry(String principalName, boolean isGroup, boolean isAllow, String[] privilegeNames) {
			fPrincipalName = principalName;
			fGroup = isGroup;
			fAllow = isAllow;
			fPrivilegeNames = privilegeNames;
		}

		public String getPrincipalName() {
			return fPrincipalName;
		}

		public boolean isGroup() {
			return fGroup;
		}

		public boolean isAllow() {
			return fAllow;
		}

		public String[] getPrivilegeNames() {
			return fPrivilegeNames.clone();
		}
	}

}

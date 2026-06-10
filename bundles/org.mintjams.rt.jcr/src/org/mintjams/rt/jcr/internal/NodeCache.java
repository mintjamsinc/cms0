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
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;

/**
 * A workspace-wide LRU cache of committed node and property data, keyed by item
 * identifier with a secondary path index.
 * <p>
 * The cache only ever holds committed state. Transactions track the items they have
 * changed on their {@code WorkspaceQuery} (see {@code WorkspaceQuery#markDirty(String)});
 * dirty items bypass this cache and are served from the transaction-local overlay, and
 * are invalidated here when the transaction commits.
 * <p>
 * Writes are guarded by a revision number: callers capture {@link #getRevision()}
 * before reading from the database and pass it to {@link #setNode(AdaptableMap, long)} /
 * {@link #setProperties(String, Map, long)}. If any transaction committed in between,
 * the put is silently dropped — the loaded data might predate that commit. Item data
 * is copied on both put and get so cached state is never aliased across sessions.
 */
public class NodeCache implements Closeable, Adaptable {

	private final JcrWorkspaceProvider fWorkspaceProvider;
	private final LinkedHashMap<String, Entry> fEntries = new LinkedHashMap<>(64, 0.75f, true);
	private final Map<String, String> fPaths = new HashMap<>();
	private long fRevision;

	private NodeCache(JcrWorkspaceProvider workspaceProvider) {
		fWorkspaceProvider = workspaceProvider;
	}

	public static NodeCache create(JcrWorkspaceProvider workspaceProvider) {
		return new NodeCache(workspaceProvider);
	}

	/**
	 * Returns the current revision. The revision advances whenever a transaction that
	 * changed items is committed.
	 */
	public synchronized long getRevision() {
		return fRevision;
	}

	public synchronized AdaptableMap<String, Object> getNode(String absPath) {
		String id = fPaths.get(absPath);
		if (id == null) {
			return null;
		}
		Entry entry = fEntries.get(id);
		if (entry == null || entry.fItemData == null) {
			return null;
		}
		return copy(entry.fItemData);
	}

	public synchronized AdaptableMap<String, Object> getNodeByIdentifier(String id) {
		Entry entry = fEntries.get(id);
		if (entry == null || entry.fItemData == null) {
			return null;
		}
		return copy(entry.fItemData);
	}

	public synchronized Map<String, AdaptableMap<String, Object>> getProperties(String id) {
		Entry entry = fEntries.get(id);
		if (entry == null || entry.fProperties == null) {
			return null;
		}
		Map<String, AdaptableMap<String, Object>> properties = new HashMap<>();
		for (Map.Entry<String, AdaptableMap<String, Object>> e : entry.fProperties.entrySet()) {
			properties.put(e.getKey(), copy(e.getValue()));
		}
		return properties;
	}

	public synchronized void setNode(AdaptableMap<String, Object> itemData, long readRevision) {
		if (readRevision != fRevision) {
			return;
		}
		String id = itemData.getString("item_id");
		String path = itemData.getString("item_path");
		Entry entry = fEntries.get(id);
		if (entry == null) {
			entry = new Entry();
			fEntries.put(id, entry);
		}
		if (entry.fPath != null && !entry.fPath.equals(path) && id.equals(fPaths.get(entry.fPath))) {
			fPaths.remove(entry.fPath);
		}
		entry.fItemData = copy(itemData);
		entry.fPath = path;
		fPaths.put(path, id);
		trim();
	}

	public synchronized void setProperties(String id, Map<String, AdaptableMap<String, Object>> properties,
			long readRevision) {
		if (readRevision != fRevision) {
			return;
		}
		Entry entry = fEntries.get(id);
		if (entry == null) {
			entry = new Entry();
			fEntries.put(id, entry);
		}
		Map<String, AdaptableMap<String, Object>> copied = new HashMap<>();
		for (Map.Entry<String, AdaptableMap<String, Object>> e : properties.entrySet()) {
			copied.put(e.getKey(), copy(e.getValue()));
		}
		entry.fProperties = copied;
		trim();
	}

	/**
	 * Drops the given items and advances the revision. Called when a transaction that
	 * changed those items is committed.
	 */
	public synchronized void invalidate(Collection<String> ids) {
		fRevision++;
		for (String id : ids) {
			Entry entry = fEntries.remove(id);
			if (entry != null && entry.fPath != null && id.equals(fPaths.get(entry.fPath))) {
				fPaths.remove(entry.fPath);
			}
		}
	}

	private void trim() {
		int cacheSize = adaptTo(JcrRepository.class).getConfiguration().getWorkspaceNodeCacheSize();
		while (fEntries.size() > cacheSize) {
			Iterator<Map.Entry<String, Entry>> i = fEntries.entrySet().iterator();
			Map.Entry<String, Entry> eldest = i.next();
			i.remove();
			Entry entry = eldest.getValue();
			if (entry.fPath != null && eldest.getKey().equals(fPaths.get(entry.fPath))) {
				fPaths.remove(entry.fPath);
			}
		}
	}

	private static AdaptableMap<String, Object> copy(AdaptableMap<String, Object> data) {
		// Result rows resolve keys case-insensitively (database column labels are
		// uppercase); the copy must preserve that or lookups like "item_path" fail.
		return AdaptableMap.<String, Object>newBuilder(String.CASE_INSENSITIVE_ORDER).putAll(data).build();
	}

	@Override
	public synchronized void close() throws IOException {
		fEntries.clear();
		fPaths.clear();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspaceProvider, adapterType);
	}

	private static class Entry {
		private AdaptableMap<String, Object> fItemData;
		private Map<String, AdaptableMap<String, Object>> fProperties;
		private String fPath;
	}

}

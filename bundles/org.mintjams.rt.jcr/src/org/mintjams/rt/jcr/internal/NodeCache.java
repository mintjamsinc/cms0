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
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
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
 * <p>
 * The cache is bounded twice: by entry count
 * ({@code org.mintjams.jcr.workspace.nodeCacheSize}) and by estimated retained bytes
 * ({@code org.mintjams.jcr.workspace.nodeCacheMemoryMB}). The count alone proved
 * insufficient — 8192 entries of nodes with large property sets retained ~117 MB per
 * workspace. Single items larger than an eighth of the byte budget are not cached at
 * all: one giant node must not flush everything else.
 */
public class NodeCache implements Closeable, Adaptable {

	/** One entry may occupy at most 1/{@value} of the byte budget. */
	private static final int OVERSIZE_DIVISOR = 8;

	private final JcrWorkspaceProvider fWorkspaceProvider;
	private final LinkedHashMap<String, Entry> fEntries = new LinkedHashMap<>(64, 0.75f, true);
	private final Map<String, String> fPaths = new HashMap<>();
	private long fRevision;
	/** Estimated retained bytes of all entries; maintained by every put/remove. */
	private long fTotalSize;

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
		resize(entry);
		trim(id, entry);
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
		resize(entry);
		trim(id, entry);
	}

	/**
	 * Drops the given items and advances the revision. Called when a transaction that
	 * changed those items is committed.
	 */
	public synchronized void invalidate(Collection<String> ids) {
		fRevision++;
		for (String id : ids) {
			Entry entry = fEntries.remove(id);
			if (entry != null) {
				fTotalSize -= entry.fSize;
				if (entry.fPath != null && id.equals(fPaths.get(entry.fPath))) {
					fPaths.remove(entry.fPath);
				}
			}
		}
	}

	/**
	 * Drops the item at the given path and everything cached below it, and advances
	 * the revision. Used when changes committed on another cluster node moved or
	 * removed a subtree: the journal carries only the subtree root, but the cached
	 * paths of all of its descendants are affected.
	 */
	public synchronized void invalidateDescendants(String absPath) {
		fRevision++;
		String prefix = absPath.endsWith("/") ? absPath : (absPath + "/");
		Iterator<Map.Entry<String, Entry>> i = fEntries.entrySet().iterator();
		while (i.hasNext()) {
			Map.Entry<String, Entry> e = i.next();
			Entry entry = e.getValue();
			if (entry.fPath == null || !(entry.fPath.equals(absPath) || entry.fPath.startsWith(prefix))) {
				continue;
			}
			i.remove();
			fTotalSize -= entry.fSize;
			if (e.getKey().equals(fPaths.get(entry.fPath))) {
				fPaths.remove(entry.fPath);
			}
		}
	}

	/**
	 * Evicts until both bounds hold; called after the given entry was put or grown.
	 * An entry too large for the cache on its own is dropped immediately instead of
	 * evicting everything else in a futile attempt to make room for it.
	 */
	private void trim(String id, Entry entry) {
		JcrRepositoryConfiguration configuration = adaptTo(JcrRepository.class).getConfiguration();
		long memorySize = configuration.getWorkspaceNodeCacheMemoryMB() * 1048576L;
		if (entry.fSize > memorySize / OVERSIZE_DIVISOR) {
			fEntries.remove(id);
			fTotalSize -= entry.fSize;
			if (entry.fPath != null && id.equals(fPaths.get(entry.fPath))) {
				fPaths.remove(entry.fPath);
			}
			return;
		}
		int cacheSize = configuration.getWorkspaceNodeCacheSize();
		while (fEntries.size() > cacheSize || (fTotalSize > memorySize && !fEntries.isEmpty())) {
			Iterator<Map.Entry<String, Entry>> i = fEntries.entrySet().iterator();
			Map.Entry<String, Entry> eldest = i.next();
			i.remove();
			Entry evicted = eldest.getValue();
			fTotalSize -= evicted.fSize;
			if (evicted.fPath != null && eldest.getKey().equals(fPaths.get(evicted.fPath))) {
				fPaths.remove(evicted.fPath);
			}
		}
	}

	/** Recomputes the entry's estimated size and folds the delta into the total. */
	private void resize(Entry entry) {
		long size = 64L + estimateSize(entry.fPath) + estimateSize(entry.fItemData);
		if (entry.fProperties != null) {
			size += 64L;
			for (Map.Entry<String, AdaptableMap<String, Object>> e : entry.fProperties.entrySet()) {
				size += 40L + estimateSize(e.getKey()) + estimateSize(e.getValue());
			}
		}
		fTotalSize += size - entry.fSize;
		entry.fSize = size;
	}

	/**
	 * Rough retained-size estimate of one cached value. Cached data are database
	 * rows: strings, scalars, timestamps and arrays of those — precision is not the
	 * goal, keeping the order of magnitude honest is.
	 */
	private static long estimateSize(Object value) {
		if (value == null) {
			return 0L;
		}
		if (value instanceof String) {
			return 48L + 2L * ((String) value).length();
		}
		if (value instanceof byte[]) {
			return 24L + ((byte[]) value).length;
		}
		if (value instanceof Object[]) {
			long size = 24L;
			for (Object element : (Object[]) value) {
				size += 8L + estimateSize(element);
			}
			return size;
		}
		if (value instanceof Map) {
			long size = 64L;
			for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
				size += 40L + estimateSize(e.getKey()) + estimateSize(e.getValue());
			}
			return size;
		}
		if (value instanceof Collection) {
			long size = 48L;
			for (Object element : (Collection<?>) value) {
				size += 8L + estimateSize(element);
			}
			return size;
		}
		if (value instanceof Calendar || value instanceof Date) {
			return 64L;
		}
		// Numbers, booleans, UUIDs and other scalar-ish values.
		return 32L;
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
		fTotalSize = 0L;
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspaceProvider, adapterType);
	}

	private static class Entry {
		private AdaptableMap<String, Object> fItemData;
		private Map<String, AdaptableMap<String, Object>> fProperties;
		private String fPath;
		/** Estimated retained bytes of this entry; see {@link #resize(Entry)}. */
		private long fSize;
	}

}

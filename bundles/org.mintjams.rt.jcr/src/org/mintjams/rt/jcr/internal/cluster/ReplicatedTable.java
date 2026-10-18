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

package org.mintjams.rt.jcr.internal.cluster;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.mintjams.jcr.cluster.ClusterTransport;
import org.mintjams.rt.jcr.internal.Activator;

/**
 * One replicated table: every node holds the whole table in memory. A write
 * is applied here and handed to the transport, which sends it to every
 * connected node; a node that connects later receives the whole table. Of two
 * values for one key the one with the later version wins, and equal versions
 * are decided by the writing node's id, so every node ends up with the same
 * value whatever order the writes arrive in. A removal is kept as a short-lived
 * tombstone so that an older value arriving afterwards cannot bring the key
 * back.
 */
final class ReplicatedTable implements ClusterTransport.Table {

	/** How long a removal is remembered. */
	static final long TOMBSTONE_TTL_MILLIS = 60000L;
	/** Tables are meant to stay small; a write past this many keys is refused. */
	static final int MAX_ENTRIES = 100000;

	private final ClusterTransportImpl fTransport;
	private final String fName;
	private final ConcurrentMap<String, Entry> fEntries = new ConcurrentHashMap<>();
	private volatile boolean fFullReported;

	ReplicatedTable(ClusterTransportImpl transport, String name) {
		fTransport = transport;
		fName = name;
	}

	String getName() {
		return fName;
	}

	@Override
	public String get(String key) {
		Entry entry = fEntries.get(key);
		if (entry == null || entry.isRemoved() || entry.isExpired(System.currentTimeMillis())) {
			return null;
		}
		return entry.fValue;
	}

	@Override
	public void put(String key, String value, long ttlMillis) {
		if (key == null) {
			throw new IllegalArgumentException("The key must not be null.");
		}
		if (value == null) {
			remove(key);
			return;
		}
		if (fEntries.size() >= MAX_ENTRIES && !fEntries.containsKey(key)) {
			if (!fFullReported) {
				fFullReported = true;
				Activator.getDefault().getLogger(ReplicatedTable.class).warn("The replicated table '" + fName
						+ "' holds " + MAX_ENTRIES + " keys; further keys are refused.");
			}
			return;
		}

		long now = System.currentTimeMillis();
		Entry entry = new Entry(value, fTransport.nextVersion(), fTransport.getNodeId(),
				(ttlMillis > 0) ? now + ttlMillis : 0L);
		fEntries.put(key, entry);
		fTransport.replicate(ClusterFrame.PUT, this, key, entry);
	}

	@Override
	public void remove(String key) {
		if (key == null) {
			return;
		}
		Entry entry = new Entry(null, fTransport.nextVersion(), fTransport.getNodeId(),
				System.currentTimeMillis() + TOMBSTONE_TTL_MILLIS);
		// Sent even when nothing was known here: another node may still have it.
		fEntries.put(key, entry);
		fTransport.replicate(ClusterFrame.REMOVE, this, key, entry);
	}

	@Override
	public Set<String> keySet() {
		long now = System.currentTimeMillis();
		Set<String> keys = new HashSet<>();
		for (Map.Entry<String, Entry> e : fEntries.entrySet()) {
			if (!e.getValue().isRemoved() && !e.getValue().isExpired(now)) {
				keys.add(e.getKey());
			}
		}
		return keys;
	}

	/**
	 * Applies a value received from another node, unless the value held here
	 * is newer.
	 */
	void merge(String key, Entry incoming) {
		fTransport.observeVersion(incoming.fVersion);
		if (incoming.isExpired(System.currentTimeMillis())) {
			return;
		}
		fEntries.compute(key, (k, current) -> (current == null || incoming.isNewerThan(current)) ? incoming : current);
	}

	/** Drops expired values and tombstones. */
	void purge(long now) {
		fEntries.entrySet().removeIf(e -> e.getValue().isExpired(now));
		if (fFullReported && fEntries.size() < MAX_ENTRIES) {
			fFullReported = false;
		}
	}

	/** A copy of every value and tombstone, for a node that has just connected. */
	Map<String, Entry> snapshot() {
		return new LinkedHashMap<>(fEntries);
	}

	/**
	 * A value (or, with a {@code null} value, a removal) with the version and
	 * the node that wrote it, and when it expires ({@code 0}: never).
	 */
	static final class Entry {
		final String fValue;
		final long fVersion;
		final String fOrigin;
		final long fExpires;

		Entry(String value, long version, String origin, long expires) {
			fValue = value;
			fVersion = version;
			fOrigin = (origin == null) ? "" : origin;
			fExpires = expires;
		}

		boolean isRemoved() {
			return fValue == null;
		}

		boolean isExpired(long now) {
			return fExpires > 0 && fExpires <= now;
		}

		boolean isNewerThan(Entry other) {
			if (fVersion != other.fVersion) {
				return fVersion > other.fVersion;
			}
			return fOrigin.compareTo(other.fOrigin) > 0;
		}

		Map<String, Object> toMap(String key) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("k", key);
			if (fValue != null) {
				m.put("v", fValue);
			}
			m.put("t", fVersion);
			m.put("o", fOrigin);
			m.put("e", fExpires);
			return m;
		}

		static Entry fromMap(Map<?, ?> m, boolean removed) {
			Object value = removed ? null : m.get("v");
			return new Entry((value == null) ? null : value.toString(), ((Number) m.get("t")).longValue(),
					(String) m.get("o"), ((Number) m.get("e")).longValue());
		}
	}

}

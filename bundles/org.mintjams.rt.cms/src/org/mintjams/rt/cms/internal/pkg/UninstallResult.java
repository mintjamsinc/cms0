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

package org.mintjams.rt.cms.internal.pkg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an uninstallation did. */
public final class UninstallResult {

	/** One uninstalled package. */
	public static final class Entry {
		private final String fId;
		private final String fTitle;
		private final String fVersion;
		private final int fRemoved;
		private final int fMissing;
		private final int fKept;

		Entry(String id, String title, String version, int removed, int missing, int kept) {
			fId = id;
			fTitle = title;
			fVersion = version;
			fRemoved = removed;
			fMissing = missing;
			fKept = kept;
		}

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", fId);
			m.put("title", fTitle);
			m.put("version", fVersion);
			m.put("removed", fRemoved);
			m.put("missing", fMissing);
			m.put("kept", fKept);
			return m;
		}

		@Override
		public String toString() {
			return fId + " " + fVersion + " (" + fRemoved + " removed, " + fMissing + " missing, " + fKept + " kept)";
		}
	}

	private final List<Entry> fEntries;
	private final long fDurationMillis;

	UninstallResult(List<Entry> entries, long durationMillis) {
		fEntries = Collections.unmodifiableList(new ArrayList<>(entries));
		fDurationMillis = durationMillis;
	}

	public List<Entry> getEntries() {
		return fEntries;
	}

	public long getDurationMillis() {
		return fDurationMillis;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		List<Map<String, Object>> packages = new ArrayList<>();
		int removed = 0;
		int missing = 0;
		int kept = 0;
		for (Entry e : fEntries) {
			packages.add(e.toMap());
			removed += e.fRemoved;
			missing += e.fMissing;
			kept += e.fKept;
		}
		m.put("packages", packages);
		m.put("removed", removed);
		m.put("missing", missing);
		m.put("kept", kept);
		m.put("durationMillis", fDurationMillis);
		return m;
	}

	@Override
	public String toString() {
		return fEntries + " (" + (fDurationMillis / 1000) + " seconds)";
	}

}

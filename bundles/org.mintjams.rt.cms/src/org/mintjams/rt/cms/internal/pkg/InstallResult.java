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

import java.util.LinkedHashMap;
import java.util.Map;

/** What an installation did. */
public final class InstallResult {

	private final String fAction;
	private final String fId;
	private final String fTitle;
	private final String fVersion;
	private final String fPreviousVersion;
	private final int fCreated;
	private final int fUpdated;
	private final int fRemoved;
	private final int fProvisioningDescriptors;
	private final long fDurationMillis;

	InstallResult(String action, String id, String title, String version, String previousVersion, int created,
			int updated, int removed, int provisioningDescriptors, long durationMillis) {
		fAction = action;
		fId = id;
		fTitle = title;
		fVersion = version;
		fPreviousVersion = previousVersion;
		fCreated = created;
		fUpdated = updated;
		fRemoved = removed;
		fProvisioningDescriptors = provisioningDescriptors;
		fDurationMillis = durationMillis;
	}

	public String getAction() {
		return fAction;
	}

	public String getId() {
		return fId;
	}

	public String getTitle() {
		return fTitle;
	}

	public String getVersion() {
		return fVersion;
	}

	public String getPreviousVersion() {
		return fPreviousVersion;
	}

	/** Files that did not exist before. */
	public int getCreated() {
		return fCreated;
	}

	/** Files that existed and were written again. */
	public int getUpdated() {
		return fUpdated;
	}

	/** Files of the previous version that the package no longer ships. */
	public int getRemoved() {
		return fRemoved;
	}

	public int getProvisioningDescriptors() {
		return fProvisioningDescriptors;
	}

	public long getDurationMillis() {
		return fDurationMillis;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("action", fAction);
		m.put("id", fId);
		m.put("title", fTitle);
		m.put("version", fVersion);
		m.put("previousVersion", fPreviousVersion);
		m.put("created", fCreated);
		m.put("updated", fUpdated);
		m.put("removed", fRemoved);
		m.put("provisioningDescriptors", fProvisioningDescriptors);
		m.put("durationMillis", fDurationMillis);
		return m;
	}

	@Override
	public String toString() {
		return fAction + " " + fId + " " + fVersion + (fPreviousVersion != null ? " (was " + fPreviousVersion + ")" : "")
				+ ": " + fCreated + " created, " + fUpdated + " updated, " + fRemoved + " removed, "
				+ fProvisioningDescriptors + " descriptor(s) (" + (fDurationMillis / 1000) + " seconds)";
	}

}

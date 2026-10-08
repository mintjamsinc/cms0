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

/**
 * What one pass over a package found: its entries with their sizes, the
 * manifest text and the provisioning descriptors. The file bodies under
 * {@code deploy/} are not kept; the installer streams them in a second pass.
 */
public final class PackageContents {

	/** One ZIP entry. */
	public static final class Entry {
		private final String fName;
		private final long fSize;
		private final boolean fDirectory;

		Entry(String name, long size, boolean directory) {
			fName = name;
			fSize = size;
			fDirectory = directory;
		}

		/** The entry name as stored in the ZIP, for example {@code deploy/usr/share/webtop/apps/blog/app.yml}. */
		public String getName() {
			return fName;
		}

		public long getSize() {
			return fSize;
		}

		public boolean isDirectory() {
			return fDirectory;
		}

		/** True for a file under {@code deploy/}. */
		public boolean isDeployFile() {
			return !fDirectory && fName.startsWith(PackageSource.DEPLOY_PREFIX)
					&& fName.length() > PackageSource.DEPLOY_PREFIX.length();
		}

		/** True for a file under {@code provisioning/}. */
		public boolean isProvisioningFile() {
			return !fDirectory && fName.startsWith(PackageSource.PROVISIONING_PREFIX)
					&& fName.length() > PackageSource.PROVISIONING_PREFIX.length();
		}

		/**
		 * The repository path a deploy entry is written to:
		 * {@code deploy/usr/share/x} becomes {@code /usr/share/x}. Null for other
		 * entries.
		 */
		public String getRepositoryPath() {
			if (!isDeployFile()) {
				return null;
			}
			return "/" + fName.substring(PackageSource.DEPLOY_PREFIX.length());
		}
	}

	private final List<Entry> fEntries = new ArrayList<>();
	private final Map<String, String> fProvisioning = new LinkedHashMap<>();
	private String fManifestText;

	void addEntry(String name, long size, boolean directory) {
		fEntries.add(new Entry(name, size, directory));
	}

	void setManifestText(String text) {
		fManifestText = text;
	}

	void addProvisioning(String name, String text) {
		fProvisioning.put(name, text);
	}

	public List<Entry> getEntries() {
		return Collections.unmodifiableList(fEntries);
	}

	/** The manifest text, or {@code null} when the package has no {@code package.yml}. */
	public String getManifestText() {
		return fManifestText;
	}

	public boolean hasManifest() {
		return fManifestText != null;
	}

	/** Provisioning descriptors by entry name, in ZIP order. */
	public Map<String, String> getProvisioning() {
		return Collections.unmodifiableMap(fProvisioning);
	}

	/** The repository paths of every deploy file, in ZIP order. */
	public List<String> getDeployPaths() {
		List<String> paths = new ArrayList<>();
		for (Entry e : fEntries) {
			if (e.isDeployFile()) {
				paths.add(e.getRepositoryPath());
			}
		}
		return paths;
	}

	public int getDeployFileCount() {
		int n = 0;
		for (Entry e : fEntries) {
			if (e.isDeployFile()) {
				n++;
			}
		}
		return n;
	}

	public long getDeployTotalSize() {
		long total = 0;
		for (Entry e : fEntries) {
			if (e.isDeployFile()) {
				total += e.getSize();
			}
		}
		return total;
	}

}

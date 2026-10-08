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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The installation record of one package in a workspace, as
 * {@link PackageRecords} keeps it under {@code /etc/packages/<id>/}.
 */
public final class InstalledPackage {

	private final String fId;
	private final String fVersion;
	private final String fTitle;
	private final String fInstalledAt;
	private final String fInstalledBy;
	private final String fPreviousVersion;
	private final String fSourceFileName;
	private final String fManifestText;
	private final Map<String, String> fFiles;

	InstalledPackage(String id, String version, String title, String installedAt, String installedBy,
			String previousVersion, String sourceFileName, String manifestText, Map<String, String> files) {
		fId = id;
		fVersion = version;
		fTitle = title;
		fInstalledAt = installedAt;
		fInstalledBy = installedBy;
		fPreviousVersion = previousVersion;
		fSourceFileName = sourceFileName;
		fManifestText = manifestText;
		fFiles = Collections.unmodifiableMap(new LinkedHashMap<>(files));
	}

	public String getId() {
		return fId;
	}

	public String getVersion() {
		return fVersion;
	}

	public String getTitle() {
		return fTitle;
	}

	/** ISO-8601 instant of the installation. */
	public String getInstalledAt() {
		return fInstalledAt;
	}

	public String getInstalledBy() {
		return fInstalledBy;
	}

	/** The version replaced by this installation, or {@code null} for a first installation. */
	public String getPreviousVersion() {
		return fPreviousVersion;
	}

	/** The file name of the package that was installed. */
	public String getSourceFileName() {
		return fSourceFileName;
	}

	/** The manifest as it was in the package, or {@code null} when the record has none. */
	public String getManifestText() {
		return fManifestText;
	}

	/** The repository paths this installation placed, with their SHA-256 digests. */
	public Map<String, String> getFiles() {
		return fFiles;
	}

	/**
	 * The packages this package requires, as id to constraint, read from the
	 * recorded manifest; empty when the record has no manifest or it declares
	 * none.
	 */
	public Map<String, String> getRequiredPackages() {
		if (fManifestText == null) {
			return Collections.emptyMap();
		}
		try {
			return PackageManifest.parse(fManifestText).getRequiredPackages();
		} catch (IOException | RuntimeException ex) {
			return Collections.emptyMap();
		}
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", fId);
		m.put("version", fVersion);
		m.put("title", fTitle);
		m.put("installedAt", fInstalledAt);
		m.put("installedBy", fInstalledBy);
		m.put("previousVersion", fPreviousVersion);
		m.put("sourceFileName", fSourceFileName);
		m.put("fileCount", fFiles.size());
		List<Map<String, Object>> requires = new ArrayList<>();
		for (Map.Entry<String, String> e : getRequiredPackages().entrySet()) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("id", e.getKey());
			r.put("constraint", e.getValue());
			requires.add(r);
		}
		m.put("requires", requires);
		return m;
	}

}

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mintjams.script.resource.Session;

/**
 * What the checks of one inspection share: the package, the workspace it
 * would be installed into, and what is already installed there.
 */
public final class InspectionContext {

	private final Session fSession;
	private final PackageSource fSource;
	private final PackageContents fContents;
	private final PackageManifest fManifest;
	private final PackageRecords fRecords;
	private final InstalledPackage fInstalled;
	private final String fPlatformVersion;

	private Set<String> fSeedPaths;
	private Map<String, String> fOwners;
	private List<Finding> fFindingsSoFar = Collections.emptyList();

	InspectionContext(Session session, PackageSource source, PackageContents contents, PackageManifest manifest,
			PackageRecords records, InstalledPackage installed, String platformVersion) {
		fSession = session;
		fSource = source;
		fContents = contents;
		fManifest = manifest;
		fRecords = records;
		fInstalled = installed;
		fPlatformVersion = platformVersion;
	}

	/** The privileged session on the target workspace. */
	public Session getSession() {
		return fSession;
	}

	public String getWorkspaceName() {
		return fSession.getWorkspace().getName();
	}

	public PackageSource getSource() {
		return fSource;
	}

	public PackageContents getContents() {
		return fContents;
	}

	/** The parsed manifest, or {@code null} when the package has none or it is not YAML. */
	public PackageManifest getManifest() {
		return fManifest;
	}

	public PackageRecords getRecords() {
		return fRecords;
	}

	/** The installed package with the same id, or {@code null}. */
	public InstalledPackage getInstalled() {
		return fInstalled;
	}

	/** The running platform's version, or {@code null} when it is not known. */
	public String getPlatformVersion() {
		return fPlatformVersion;
	}

	/**
	 * The repository paths the bundled assets of the image own in this
	 * workspace (the applied seed manifest), read on first use.
	 */
	public Set<String> getSeedPaths() throws IOException {
		if (fSeedPaths == null) {
			fSeedPaths = PackageRecords.readSeedPaths(fSession);
		}
		return fSeedPaths;
	}

	/**
	 * Every repository path an installed package owns, mapped to that
	 * package's id, read on first use. The package being inspected is left out
	 * when it is already installed, so its own files never count as a
	 * conflict.
	 */
	public Map<String, String> getPathOwners() throws IOException {
		if (fOwners == null) {
			Map<String, String> owners = new LinkedHashMap<>();
			String self = fInstalled != null ? fInstalled.getId() : null;
			for (InstalledPackage p : fRecords.list()) {
				if (p.getId().equals(self)) {
					continue;
				}
				for (String path : p.getFiles().keySet()) {
					owners.put(path, p.getId());
				}
			}
			fOwners = owners;
		}
		return fOwners;
	}

	/** The findings of the checks that ran before, for a check that builds on another's result. */
	public List<Finding> getFindingsSoFar() {
		return fFindingsSoFar;
	}

	void setFindingsSoFar(List<Finding> findings) {
		fFindingsSoFar = Collections.unmodifiableList(findings);
	}

}

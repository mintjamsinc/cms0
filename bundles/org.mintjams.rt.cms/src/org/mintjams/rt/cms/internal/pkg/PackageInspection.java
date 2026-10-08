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
 * The outcome of inspecting a package against a workspace: what the package
 * is, what installing it would do, and what the checks found. An inspection
 * with no error finding may be installed.
 */
public final class PackageInspection {

	/** A first installation of the package id. */
	public static final String ACTION_INSTALL = "install";
	/** A newer version replaces the installed one. */
	public static final String ACTION_UPGRADE = "upgrade";
	/** The installed version is installed again. */
	public static final String ACTION_REINSTALL = "reinstall";

	private final String fWorkspaceName;
	private final String fPackagePath;
	private final String fFileName;
	private final PackageManifest fManifest;
	private final PackageContents fContents;
	private final InstalledPackage fInstalled;
	private final String fAction;
	private final String fPlatformVersion;
	private final List<Finding> fFindings;
	private final List<String> fRemovedPaths;

	PackageInspection(String workspaceName, String packagePath, String fileName, PackageManifest manifest,
			PackageContents contents, InstalledPackage installed, String action, String platformVersion,
			List<Finding> findings, List<String> removedPaths) {
		fWorkspaceName = workspaceName;
		fPackagePath = packagePath;
		fFileName = fileName;
		fManifest = manifest;
		fContents = contents;
		fInstalled = installed;
		fAction = action;
		fPlatformVersion = platformVersion;
		fFindings = Collections.unmodifiableList(new ArrayList<>(findings));
		fRemovedPaths = Collections.unmodifiableList(new ArrayList<>(removedPaths));
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public String getPackagePath() {
		return fPackagePath;
	}

	public String getFileName() {
		return fFileName;
	}

	/** The manifest, or {@code null} when the package has none that parses. */
	public PackageManifest getManifest() {
		return fManifest;
	}

	/** The entries read from the package; {@code null} when the package could not be read. */
	public PackageContents getContents() {
		return fContents;
	}

	/** The installed package with the same id, or {@code null}. */
	public InstalledPackage getInstalled() {
		return fInstalled;
	}

	/**
	 * One of {@link #ACTION_INSTALL}, {@link #ACTION_UPGRADE} or
	 * {@link #ACTION_REINSTALL}; {@code null} when the package's identity or
	 * version cannot be told.
	 */
	public String getAction() {
		return fAction;
	}

	public String getPlatformVersion() {
		return fPlatformVersion;
	}

	public List<Finding> getFindings() {
		return fFindings;
	}

	/** The paths of the installed version that this package no longer ships and an upgrade removes. */
	public List<String> getRemovedPaths() {
		return fRemovedPaths;
	}

	/** True when no finding is an error. */
	public boolean isOk() {
		for (Finding f : fFindings) {
			if (f.isError()) {
				return false;
			}
		}
		return true;
	}

	public List<Finding> getErrors() {
		List<Finding> errors = new ArrayList<>();
		for (Finding f : fFindings) {
			if (f.isError()) {
				errors.add(f);
			}
		}
		return errors;
	}

	/** Plain data for scripts and forms. */
	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ok", isOk());
		m.put("action", fAction);
		m.put("workspace", fWorkspaceName);
		m.put("packagePath", fPackagePath);
		m.put("fileName", fFileName);
		m.put("platformVersion", fPlatformVersion);
		m.put("manifest", fManifest != null ? fManifest.toMap() : null);
		m.put("installedVersion", fInstalled != null ? fInstalled.getVersion() : null);
		List<Map<String, Object>> findings = new ArrayList<>();
		for (Finding f : fFindings) {
			findings.add(f.toMap());
		}
		m.put("findings", findings);
		m.put("fileCount", fContents != null ? fContents.getDeployFileCount() : 0);
		m.put("totalSize", fContents != null ? fContents.getDeployTotalSize() : 0L);
		m.put("deployPaths", fContents != null ? fContents.getDeployPaths() : Collections.emptyList());
		m.put("removedPaths", fRemovedPaths);
		m.put("provisioning", fContents != null ? new ArrayList<>(fContents.getProvisioning().keySet()) : Collections.emptyList());
		return m;
	}

}

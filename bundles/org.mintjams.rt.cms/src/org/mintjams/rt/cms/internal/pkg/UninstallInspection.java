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
 * The outcome of inspecting a selection of installed packages for
 * uninstallation: the packages, the order they are removed in, and what the
 * checks found. An inspection with no error finding may be uninstalled.
 */
public final class UninstallInspection {

	private final String fWorkspaceName;
	private final List<String> fRequestedIds;
	private final List<InstalledPackage> fPackages;
	private final List<String> fOrder;
	private final Map<String, List<String>> fRequiredBy;
	private final List<Finding> fFindings;

	UninstallInspection(String workspaceName, List<String> requestedIds, List<InstalledPackage> packages,
			List<String> order, Map<String, List<String>> requiredBy, List<Finding> findings) {
		fWorkspaceName = workspaceName;
		fRequestedIds = Collections.unmodifiableList(new ArrayList<>(requestedIds));
		fPackages = Collections.unmodifiableList(new ArrayList<>(packages));
		fOrder = Collections.unmodifiableList(new ArrayList<>(order));
		fRequiredBy = Collections.unmodifiableMap(new LinkedHashMap<>(requiredBy));
		fFindings = Collections.unmodifiableList(new ArrayList<>(findings));
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public List<String> getRequestedIds() {
		return fRequestedIds;
	}

	/** The selected packages that are installed. */
	public List<InstalledPackage> getPackages() {
		return fPackages;
	}

	/** The ids in the order they are removed: a package before the packages it requires. */
	public List<String> getOrder() {
		return fOrder;
	}

	/** For each selected package, the installed packages that require it. */
	public Map<String, List<String>> getRequiredBy() {
		return fRequiredBy;
	}

	public List<Finding> getFindings() {
		return fFindings;
	}

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
		m.put("workspace", fWorkspaceName);
		m.put("requestedIds", fRequestedIds);
		List<Map<String, Object>> packages = new ArrayList<>();
		int totalFiles = 0;
		for (InstalledPackage p : fPackages) {
			Map<String, Object> pm = p.toMap();
			pm.put("requiredBy", fRequiredBy.getOrDefault(p.getId(), Collections.emptyList()));
			pm.put("files", new ArrayList<>(p.getFiles().keySet()));
			packages.add(pm);
			totalFiles += p.getFiles().size();
		}
		m.put("packages", packages);
		m.put("order", fOrder);
		m.put("fileCount", totalFiles);
		List<Map<String, Object>> findings = new ArrayList<>();
		for (Finding f : fFindings) {
			findings.add(f.toMap());
		}
		m.put("findings", findings);
		return m;
	}

}

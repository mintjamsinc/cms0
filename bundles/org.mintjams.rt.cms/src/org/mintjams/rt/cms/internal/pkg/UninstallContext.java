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
import java.util.Set;

import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Session;

/**
 * What the checks of one uninstallation share: the ids asked for, the
 * records of those that are installed, every installed package of the
 * workspace, and who requires whom.
 */
public final class UninstallContext {

	private final Session fSession;
	private final PackageRecords fRecords;
	private final List<String> fRequestedIds;
	private final Map<String, InstalledPackage> fSelected;
	private final List<InstalledPackage> fInstalled;
	private final Map<String, List<String>> fRequiredBy;

	private Set<String> fSeedPaths;
	private List<Finding> fFindingsSoFar = Collections.emptyList();

	UninstallContext(Session session, PackageRecords records, List<String> requestedIds,
			List<InstalledPackage> installed) {
		fSession = session;
		fRecords = records;
		fRequestedIds = Collections.unmodifiableList(new ArrayList<>(requestedIds));
		fInstalled = Collections.unmodifiableList(new ArrayList<>(installed));
		Map<String, InstalledPackage> byId = new LinkedHashMap<>();
		for (InstalledPackage p : installed) {
			byId.put(p.getId(), p);
		}
		Map<String, InstalledPackage> selected = new LinkedHashMap<>();
		for (String id : requestedIds) {
			InstalledPackage p = byId.get(id);
			if (p != null) {
				selected.put(id, p);
			}
		}
		fSelected = Collections.unmodifiableMap(selected);
		fRequiredBy = PackageRecords.requiredBy(installed);
	}

	/** The privileged session on the workspace. */
	public Session getSession() {
		return fSession;
	}

	public String getWorkspaceName() {
		return fSession.getWorkspace().getName();
	}

	public PackageRecords getRecords() {
		return fRecords;
	}

	/** The ids asked for, as given. */
	public List<String> getRequestedIds() {
		return fRequestedIds;
	}

	/** The requested packages that are installed, by id, in request order. */
	public Map<String, InstalledPackage> getSelected() {
		return fSelected;
	}

	/** Every installed package of the workspace. */
	public List<InstalledPackage> getInstalled() {
		return fInstalled;
	}

	/**
	 * The installed packages outside the selection that require the given
	 * package. A dependent that is uninstalled along with it does not count.
	 */
	public List<String> getRequiredByOutsideSelection(String id) {
		List<String> result = new ArrayList<>();
		for (String dependent : fRequiredBy.getOrDefault(id, Collections.emptyList())) {
			if (!fSelected.containsKey(dependent)) {
				result.add(dependent);
			}
		}
		return result;
	}

	/** The installed packages that require the given package, selected or not. */
	public List<String> getRequiredBy(String id) {
		return Collections.unmodifiableList(fRequiredBy.getOrDefault(id, Collections.emptyList()));
	}

	/**
	 * The repository paths the bundled assets of the image own in this
	 * workspace, read on first use. A file a package placed that the image
	 * now ships is left to the image.
	 */
	public Set<String> getSeedPaths() throws IOException {
		if (fSeedPaths == null) {
			fSeedPaths = PackageRecords.readSeedPaths(fSession);
		}
		return fSeedPaths;
	}

	/** Tells whether a recorded file still exists as a file. */
	public boolean fileExists(String path) throws IOException {
		try {
			Resource resource = fSession.getResource(path);
			return resource.exists() && !resource.isCollection();
		} catch (ResourceException ex) {
			throw new IOException("Could not read " + path, ex);
		}
	}

	/** The findings of the checks that ran before. */
	public List<Finding> getFindingsSoFar() {
		return fFindingsSoFar;
	}

	void setFindingsSoFar(List<Finding> findings) {
		fFindingsSoFar = Collections.unmodifiableList(findings);
	}

}

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

package org.mintjams.rt.cms.internal.pkg.checks;

import java.util.List;
import java.util.Map;

import org.mintjams.rt.cms.internal.pkg.Finding;
import org.mintjams.rt.cms.internal.pkg.InspectionContext;
import org.mintjams.rt.cms.internal.pkg.InstalledPackage;
import org.mintjams.rt.cms.internal.pkg.PackageCheck;
import org.mintjams.rt.cms.internal.pkg.PackageManifest;
import org.mintjams.rt.cms.internal.pkg.Semver;

/**
 * Every package named in {@code requires.packages} is installed in the
 * workspace at a version that meets its constraint. Nothing is resolved or
 * fetched: a missing dependency is installed first, by hand.
 */
public final class DependencyCheck implements PackageCheck {

	@Override
	public String getId() {
		return "dependencies";
	}

	@Override
	public void check(InspectionContext context, List<Finding> findings) throws Exception {
		PackageManifest manifest = context.getManifest();
		if (manifest == null) {
			return;
		}
		for (Map.Entry<String, String> e : manifest.getRequiredPackages().entrySet()) {
			String id = e.getKey();
			String constraint = e.getValue();
			if (!PackageManifest.ID_PATTERN.matcher(id).matches()
					|| (constraint != null && !Semver.isValidConstraint(constraint))) {
				// Reported by the manifest check.
				continue;
			}
			if (id.equals(manifest.getId())) {
				findings.add(Finding.error("dependency.self", "The package requires itself: " + id));
				continue;
			}
			InstalledPackage installed = context.getRecords().get(id);
			if (installed == null) {
				findings.add(Finding.error("dependency.missing",
						"The package requires " + id + (constraint != null ? " " + constraint : "")
								+ ", which is not installed in this workspace."));
				continue;
			}
			Semver version = Semver.tryParse(installed.getVersion());
			if (constraint == null) {
				continue;
			}
			if (version == null) {
				findings.add(Finding.error("dependency.version",
						"The package requires " + id + " " + constraint + "; the installed version "
								+ installed.getVersion() + " could not be compared."));
			} else if (!Semver.satisfies(version, constraint)) {
				findings.add(Finding.error("dependency.version",
						"The package requires " + id + " " + constraint + "; version " + version + " is installed."));
			}
		}
	}

}

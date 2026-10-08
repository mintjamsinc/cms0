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

import org.mintjams.rt.cms.internal.pkg.Finding;
import org.mintjams.rt.cms.internal.pkg.InspectionContext;
import org.mintjams.rt.cms.internal.pkg.InstalledPackage;
import org.mintjams.rt.cms.internal.pkg.PackageCheck;
import org.mintjams.rt.cms.internal.pkg.PackageManifest;
import org.mintjams.rt.cms.internal.pkg.Semver;

/**
 * How the package relates to the version already installed under the same
 * id. A newer version is an upgrade and the same version a reinstallation;
 * an older version is refused, because the record of the installed version
 * would then describe files the older package knows nothing about. To go
 * back, the installed package is removed first.
 */
public final class VersionCheck implements PackageCheck {

	@Override
	public String getId() {
		return "version";
	}

	@Override
	public void check(InspectionContext context, List<Finding> findings) {
		PackageManifest manifest = context.getManifest();
		InstalledPackage installed = context.getInstalled();
		if (manifest == null || installed == null) {
			return;
		}
		Semver incoming = Semver.tryParse(manifest.getVersion());
		if (incoming == null) {
			return;
		}
		Semver current = Semver.tryParse(installed.getVersion());
		if (current == null) {
			findings.add(Finding.warning("version.installedUnknown",
					"The installed version of " + manifest.getId() + " (" + installed.getVersion()
							+ ") could not be compared; the package is treated as an upgrade."));
			return;
		}
		if (incoming.compareTo(current) < 0) {
			findings.add(Finding.error("version.downgrade",
					"Version " + current + " of " + manifest.getId() + " is installed; the package is the older version "
							+ incoming + "."));
		}
	}

}

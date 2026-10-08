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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.mintjams.rt.cms.internal.pkg.Finding;
import org.mintjams.rt.cms.internal.pkg.InspectionContext;
import org.mintjams.rt.cms.internal.pkg.PackageCheck;
import org.mintjams.rt.cms.internal.pkg.PackageContents;
import org.mintjams.rt.cms.internal.pkg.PackageSource;

/**
 * The ZIP holds only what a package may hold, at well-formed paths, and holds
 * something to install.
 */
public final class LayoutCheck implements PackageCheck {

	@Override
	public String getId() {
		return "layout";
	}

	@Override
	public void check(InspectionContext context, List<Finding> findings) {
		PackageContents contents = context.getContents();
		if (contents == null) {
			return;
		}

		Set<String> seen = new HashSet<>();
		for (PackageContents.Entry entry : contents.getEntries()) {
			String name = entry.getName();
			if (!isWellFormed(name)) {
				findings.add(Finding.error("layout.path",
						"The entry name must be a relative path without empty, '.' or '..' segments: " + name, name));
				continue;
			}
			if (entry.isDirectory()) {
				continue;
			}
			if (name.equals(PackageSource.MANIFEST_ENTRY)) {
				continue;
			}
			if (entry.isDeployFile()) {
				if (!seen.add(entry.getRepositoryPath())) {
					findings.add(Finding.error("layout.duplicate",
							"The package places this path twice: " + entry.getRepositoryPath(), name));
				}
				continue;
			}
			if (name.startsWith(PackageSource.PROVISIONING_PREFIX)) {
				String rest = name.substring(PackageSource.PROVISIONING_PREFIX.length());
				if (rest.indexOf('/') >= 0) {
					findings.add(Finding.error("layout.provisioning",
							"Provisioning descriptors must be directly under provisioning/: " + name, name));
				} else if (!entry.isProvisioningFile() || !contents.getProvisioning().containsKey(name)) {
					findings.add(Finding.error("layout.provisioning",
							"Only .yml or .yaml descriptors may be under provisioning/: " + name, name));
				}
				continue;
			}
			findings.add(Finding.error("layout.unexpected",
					"The entry is outside package.yml, deploy/ and provisioning/: " + name, name));
		}

		if (contents.getDeployFileCount() == 0 && contents.getProvisioning().isEmpty()) {
			findings.add(Finding.error("layout.empty",
					"The package has nothing to install: no files under deploy/ and no provisioning descriptors."));
		}
	}

	static boolean isWellFormed(String name) {
		if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
			return false;
		}
		String body = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
		for (String segment : body.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
				return false;
			}
		}
		return true;
	}

}

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
import org.mintjams.rt.cms.internal.pkg.PackageCheck;
import org.mintjams.rt.cms.internal.pkg.PackageManifest;
import org.mintjams.rt.cms.internal.pkg.Semver;

/**
 * The manifest is present, is this format, and declares a well-formed id,
 * version and title; what it requires is well-formed too.
 */
public final class ManifestCheck implements PackageCheck {

	@Override
	public String getId() {
		return "manifest";
	}

	@Override
	public void check(InspectionContext context, List<Finding> findings) {
		if (context.getContents() == null) {
			return;
		}
		if (!context.getContents().hasManifest()) {
			findings.add(Finding.error("manifest.missing", "The package has no package.yml."));
			return;
		}
		PackageManifest manifest = context.getManifest();
		if (manifest == null) {
			findings.add(Finding.error("manifest.invalid", "The package.yml could not be read as a YAML mapping."));
			return;
		}

		if (!PackageManifest.FORMAT.equals(manifest.getFormat())) {
			findings.add(Finding.error("manifest.format",
					"The manifest must declare format: " + PackageManifest.FORMAT + "."));
		}
		if (manifest.getFormatVersion() != PackageManifest.FORMAT_VERSION) {
			findings.add(Finding.error("manifest.formatVersion",
					"The manifest must declare formatVersion: " + PackageManifest.FORMAT_VERSION + "."));
		}

		String id = manifest.getId();
		if (id == null) {
			findings.add(Finding.error("manifest.id", "The manifest must declare an id."));
		} else if (id.length() > PackageManifest.ID_MAX_LENGTH || !PackageManifest.ID_PATTERN.matcher(id).matches()) {
			findings.add(Finding.error("manifest.id",
					"The id must be a reverse-DNS name in lower case, such as jp.example.blog: " + id));
		}

		String version = manifest.getVersion();
		if (version == null) {
			findings.add(Finding.error("manifest.version", "The manifest must declare a version."));
		} else if (Semver.tryParse(version) == null) {
			findings.add(Finding.error("manifest.version",
					"The version must be a semantic version, such as 1.2.0 or 1.2.0-beta.1: " + version));
		}

		if (manifest.getTitle() == null) {
			findings.add(Finding.error("manifest.title", "The manifest must declare a title."));
		}

		Object vendor = manifest.getVendor();
		if (vendor != null && !(vendor instanceof Map)) {
			findings.add(Finding.error("manifest.vendor", "The vendor must be a mapping with name and url."));
		}

		Object requires = manifest.getRequires();
		if (requires != null && !(requires instanceof Map)) {
			findings.add(Finding.error("manifest.requires", "The requires section must be a mapping."));
			return;
		}
		String platform = manifest.getRequiredPlatform();
		if (platform != null && !Semver.isValidConstraint(platform)) {
			findings.add(Finding.error("manifest.requires.platform",
					"The platform requirement is not a version constraint: " + platform));
		}
		if (requires instanceof Map) {
			Object packages = ((Map<?, ?>) requires).get("packages");
			if (packages != null && !(packages instanceof Map)) {
				findings.add(Finding.error("manifest.requires.packages",
						"The required packages must be a mapping of package id to version constraint."));
			}
		}
		for (Map.Entry<String, String> e : manifest.getRequiredPackages().entrySet()) {
			if (!PackageManifest.ID_PATTERN.matcher(e.getKey()).matches()) {
				findings.add(Finding.error("manifest.requires.packages",
						"The required package id is not a package id: " + e.getKey()));
			}
			if (e.getValue() != null && !Semver.isValidConstraint(e.getValue())) {
				findings.add(Finding.error("manifest.requires.packages",
						"The requirement on " + e.getKey() + " is not a version constraint: " + e.getValue()));
			}
		}
	}

}

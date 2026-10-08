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
import java.util.Set;

import org.mintjams.rt.cms.internal.pkg.Finding;
import org.mintjams.rt.cms.internal.pkg.InspectionContext;
import org.mintjams.rt.cms.internal.pkg.PackageCheck;
import org.mintjams.rt.cms.internal.pkg.PackageContents;
import org.mintjams.rt.cms.internal.pkg.PackageInstaller;
import org.mintjams.rt.cms.internal.pkg.PackageRecords;

/**
 * The package writes only where a package may write. It may not place files
 * under the areas the platform keeps for itself, over a file the bundled
 * assets of the image own, or over a file another installed package owns.
 */
public final class ReservedPathCheck implements PackageCheck {

	/** Repository areas no package may write to. */
	public static final List<String> RESERVED_ROOTS = List.of(
			PackageRecords.ROOT,
			PackageInstaller.STAGING_AREA,
			"/var/seed",
			"/var/jobs",
			"/home",
			"/jcr:system");

	@Override
	public String getId() {
		return "paths";
	}

	@Override
	public void check(InspectionContext context, List<Finding> findings) throws Exception {
		PackageContents contents = context.getContents();
		if (contents == null) {
			return;
		}
		Set<String> seedPaths = context.getSeedPaths();
		Map<String, String> owners = context.getPathOwners();
		for (PackageContents.Entry entry : contents.getEntries()) {
			if (!entry.isDeployFile() || !LayoutCheck.isWellFormed(entry.getName())) {
				continue;
			}
			String path = entry.getRepositoryPath();
			String reserved = reservedRoot(path);
			if (reserved != null) {
				findings.add(Finding.error("path.reserved",
						"The package may not write under " + reserved + ": " + path, path));
				continue;
			}
			if (seedPaths.contains(path)) {
				findings.add(Finding.error("path.bundled",
						"The path is owned by the bundled assets of the platform: " + path, path));
				continue;
			}
			String owner = owners.get(path);
			if (owner != null) {
				findings.add(Finding.error("path.owned",
						"The path is owned by the installed package " + owner + ": " + path, path));
			}
		}
	}

	/** The reserved root the path lies under, or {@code null}. */
	public static String reservedRoot(String path) {
		for (String root : RESERVED_ROOTS) {
			if (path.equals(root) || path.startsWith(root + "/")) {
				return root;
			}
		}
		return null;
	}

}

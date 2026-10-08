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
import org.mintjams.rt.cms.internal.pkg.InstalledPackage;
import org.mintjams.rt.cms.internal.pkg.UninstallCheck;
import org.mintjams.rt.cms.internal.pkg.UninstallContext;

/**
 * What the recorded files look like now. A file the image has since taken
 * over is left to the image and a file already gone is simply counted; both
 * are reported so the administrator knows what the uninstallation will and
 * will not touch.
 */
public final class RecordedFilesCheck implements UninstallCheck {

	@Override
	public String getId() {
		return "files";
	}

	@Override
	public void check(UninstallContext context, List<Finding> findings) throws Exception {
		Set<String> seedPaths = context.getSeedPaths();
		for (Map.Entry<String, InstalledPackage> e : context.getSelected().entrySet()) {
			String id = e.getKey();
			InstalledPackage p = e.getValue();
			if (p.getFiles().isEmpty()) {
				findings.add(Finding.warning("uninstall.noFiles",
						"The record of " + id + " lists no files; only the record is removed.", id));
				continue;
			}
			int missing = 0;
			for (String path : p.getFiles().keySet()) {
				if (seedPaths.contains(path)) {
					findings.add(Finding.warning("uninstall.bundledPath",
							"The file is now owned by the bundled assets of the platform and is left in place: " + path, id));
					continue;
				}
				if (!context.fileExists(path)) {
					missing++;
				}
			}
			if (missing > 0) {
				findings.add(Finding.warning("uninstall.missingFiles",
						missing + " of the " + p.getFiles().size() + " files recorded for " + id
								+ " no longer exist; they are skipped.", id));
			}
		}
	}

}

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
import java.util.List;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.pkg.checks.RecordedFilesCheck;
import org.mintjams.rt.cms.internal.pkg.checks.RequiredByCheck;
import org.mintjams.rt.cms.internal.pkg.checks.SelectionCheck;

/**
 * The checks a selection of installed packages goes through before it is
 * uninstalled, in order. A new check is added here. Like
 * {@link PackageChecks}, every check runs, and a check that fails on its
 * own becomes an error finding.
 */
public final class UninstallChecks {

	private UninstallChecks() {}

	public static List<UninstallCheck> defaults() {
		List<UninstallCheck> checks = new ArrayList<>();
		checks.add(new SelectionCheck());
		checks.add(new RequiredByCheck());
		checks.add(new RecordedFilesCheck());
		return checks;
	}

	/** Runs the checks and returns every finding, in check order. */
	public static List<Finding> run(List<UninstallCheck> checks, UninstallContext context) {
		List<Finding> findings = new ArrayList<>();
		for (UninstallCheck check : checks) {
			context.setFindingsSoFar(new ArrayList<>(findings));
			try {
				check.check(context, findings);
			} catch (Throwable ex) {
				CmsService.getLogger(UninstallChecks.class).error("Uninstall check '" + check.getId() + "' failed.", ex);
				findings.add(Finding.error("check.failed",
						"The check '" + check.getId() + "' could not be completed: "
								+ (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())));
			}
		}
		return findings;
	}

}

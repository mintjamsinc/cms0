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
import org.mintjams.rt.cms.internal.pkg.checks.DependencyCheck;
import org.mintjams.rt.cms.internal.pkg.checks.LayoutCheck;
import org.mintjams.rt.cms.internal.pkg.checks.ManifestCheck;
import org.mintjams.rt.cms.internal.pkg.checks.PlatformVersionCheck;
import org.mintjams.rt.cms.internal.pkg.checks.ProvisioningCheck;
import org.mintjams.rt.cms.internal.pkg.checks.ReservedPathCheck;
import org.mintjams.rt.cms.internal.pkg.checks.VersionCheck;

/**
 * The checks a package goes through, in order. A new check is added here.
 *
 * <p>Every check runs even after an earlier one reported an error, so one
 * inspection shows every problem; a check that cannot do its work without
 * the manifest simply adds nothing when there is none. A check that fails
 * on its own (an exception) becomes an error finding too, so a broken check
 * can never let a package through.</p>
 */
public final class PackageChecks {

	private PackageChecks() {}

	public static List<PackageCheck> defaults() {
		List<PackageCheck> checks = new ArrayList<>();
		checks.add(new ManifestCheck());
		checks.add(new LayoutCheck());
		checks.add(new PlatformVersionCheck());
		checks.add(new DependencyCheck());
		checks.add(new VersionCheck());
		checks.add(new ReservedPathCheck());
		checks.add(new ProvisioningCheck());
		return checks;
	}

	/** Runs the checks and returns every finding, in check order. */
	public static List<Finding> run(List<PackageCheck> checks, InspectionContext context) {
		List<Finding> findings = new ArrayList<>();
		for (PackageCheck check : checks) {
			context.setFindingsSoFar(new ArrayList<>(findings));
			try {
				check.check(context, findings);
			} catch (Throwable ex) {
				CmsService.getLogger(PackageChecks.class).error("Package check '" + check.getId() + "' failed.", ex);
				findings.add(Finding.error("check.failed",
						"The check '" + check.getId() + "' could not be completed: "
								+ (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())));
			}
		}
		return findings;
	}

}

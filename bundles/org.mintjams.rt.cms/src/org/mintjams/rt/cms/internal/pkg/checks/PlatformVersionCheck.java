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
import org.mintjams.rt.cms.internal.pkg.PackageCheck;
import org.mintjams.rt.cms.internal.pkg.PackageManifest;
import org.mintjams.rt.cms.internal.pkg.Semver;

/**
 * The running platform meets the package's {@code requires.platform}. When
 * the platform does not know its version (an installation outside the
 * container image) the requirement cannot be checked, which is reported as a
 * warning rather than refusing every package.
 */
public final class PlatformVersionCheck implements PackageCheck {

	@Override
	public String getId() {
		return "platform";
	}

	@Override
	public void check(InspectionContext context, List<Finding> findings) {
		PackageManifest manifest = context.getManifest();
		if (manifest == null) {
			return;
		}
		String required = manifest.getRequiredPlatform();
		if (required == null || !Semver.isValidConstraint(required)) {
			return;
		}

		Semver platform = Semver.tryParse(context.getPlatformVersion());
		if (platform == null) {
			findings.add(Finding.warning("platform.unknown",
					"The package requires platform " + required
							+ ", but the version of this platform is not known, so the requirement was not checked."));
			return;
		}
		if (!Semver.satisfies(platform, required)) {
			findings.add(Finding.error("platform.incompatible",
					"The package requires platform " + required + "; this platform is " + platform + "."));
		}
	}

}

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
import org.mintjams.rt.cms.internal.pkg.UninstallCheck;
import org.mintjams.rt.cms.internal.pkg.UninstallContext;

/**
 * No installed package that stays behind requires a package being
 * uninstalled. Dependents that are uninstalled together with it are fine:
 * the selection is removed as one.
 */
public final class RequiredByCheck implements UninstallCheck {

	@Override
	public String getId() {
		return "requiredBy";
	}

	@Override
	public void check(UninstallContext context, List<Finding> findings) {
		for (String id : context.getSelected().keySet()) {
			List<String> dependents = context.getRequiredByOutsideSelection(id);
			if (dependents.isEmpty()) {
				continue;
			}
			findings.add(Finding.error("uninstall.requiredBy",
					"The package " + id + " is required by " + String.join(", ", dependents)
							+ "; uninstall them together or leave it installed.", id));
		}
	}

}

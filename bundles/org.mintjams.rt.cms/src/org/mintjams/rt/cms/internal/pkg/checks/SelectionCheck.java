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
import org.mintjams.rt.cms.internal.pkg.UninstallCheck;
import org.mintjams.rt.cms.internal.pkg.UninstallContext;

/** Something is selected, and every selected package is installed. */
public final class SelectionCheck implements UninstallCheck {

	@Override
	public String getId() {
		return "selection";
	}

	@Override
	public void check(UninstallContext context, List<Finding> findings) {
		if (context.getRequestedIds().isEmpty()) {
			findings.add(Finding.error("uninstall.nothingSelected", "No package is selected."));
			return;
		}
		Set<String> seen = new HashSet<>();
		for (String id : context.getRequestedIds()) {
			if (!seen.add(id)) {
				continue;
			}
			if (!context.getSelected().containsKey(id)) {
				findings.add(Finding.error("uninstall.notInstalled",
						"The package is not installed in this workspace: " + id, id));
			}
		}
	}

}

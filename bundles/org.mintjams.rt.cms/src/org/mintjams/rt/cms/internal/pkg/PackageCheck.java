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

import java.util.List;

/**
 * One check a package goes through before it is installed.
 *
 * <p>Checks run in the order {@link PackageChecks#defaults()} lists them, each
 * adding its {@link Finding}s; a check never throws for a problem in the
 * package, only for a failure of the check itself. An error finding from any
 * check stops the installation. To add a check, implement this interface and
 * list it in {@link PackageChecks}.</p>
 */
public interface PackageCheck {

	/** A short stable name, for logs. */
	String getId();

	/**
	 * Examines the package and adds what it finds to {@code findings}.
	 *
	 * @param context  the package, what is known about the workspace, and the
	 *                 findings of the checks that ran before
	 * @param findings where to add this check's findings
	 */
	void check(InspectionContext context, List<Finding> findings) throws Exception;

}

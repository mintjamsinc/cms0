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
 * One check a selection of installed packages goes through before it is
 * uninstalled. The counterpart of {@link PackageCheck}: checks run in the
 * order {@link UninstallChecks#defaults()} lists them, each adding its
 * {@link Finding}s, and an error finding from any check stops the
 * uninstallation. To add a check, implement this interface and list it in
 * {@link UninstallChecks}.
 */
public interface UninstallCheck {

	/** A short stable name, for logs. */
	String getId();

	/**
	 * Examines the selection and adds what it finds to {@code findings}.
	 * A finding about one package carries that package's id as its path.
	 */
	void check(UninstallContext context, List<Finding> findings) throws Exception;

}

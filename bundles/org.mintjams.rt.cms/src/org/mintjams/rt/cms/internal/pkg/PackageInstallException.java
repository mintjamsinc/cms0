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

import java.io.IOException;

/**
 * Thrown when a package is refused: its inspection has at least one error
 * finding. The inspection is carried along so the caller can show them.
 */
public class PackageInstallException extends IOException {

	private static final long serialVersionUID = 1L;

	private final transient PackageInspection fInspection;

	public PackageInstallException(PackageInspection inspection) {
		super(describe(inspection));
		fInspection = inspection;
	}

	public PackageInspection getInspection() {
		return fInspection;
	}

	private static String describe(PackageInspection inspection) {
		StringBuilder sb = new StringBuilder("The package cannot be installed:");
		for (Finding f : inspection.getErrors()) {
			sb.append(' ').append(f.getMessage());
		}
		return sb.toString();
	}

}

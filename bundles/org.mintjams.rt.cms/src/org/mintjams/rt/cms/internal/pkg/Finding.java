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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing a {@link PackageCheck} found. An {@code ERROR} stops the
 * installation; a {@code WARNING} is shown and the installation may go on.
 *
 * <p>The code is stable and dotted ({@code manifest.missing},
 * {@code platform.incompatible}), so a form can translate it; the message is
 * the English text to show when it has no translation. The path, when
 * present, names the package entry or repository path the finding is about.
 */
public final class Finding {

	public enum Severity {
		ERROR, WARNING
	}

	private final Severity fSeverity;
	private final String fCode;
	private final String fMessage;
	private final String fPath;

	public Finding(Severity severity, String code, String message, String path) {
		fSeverity = severity;
		fCode = code;
		fMessage = message;
		fPath = path;
	}

	public static Finding error(String code, String message) {
		return new Finding(Severity.ERROR, code, message, null);
	}

	public static Finding error(String code, String message, String path) {
		return new Finding(Severity.ERROR, code, message, path);
	}

	public static Finding warning(String code, String message) {
		return new Finding(Severity.WARNING, code, message, null);
	}

	public static Finding warning(String code, String message, String path) {
		return new Finding(Severity.WARNING, code, message, path);
	}

	public Severity getSeverity() {
		return fSeverity;
	}

	public boolean isError() {
		return fSeverity == Severity.ERROR;
	}

	public String getCode() {
		return fCode;
	}

	public String getMessage() {
		return fMessage;
	}

	public String getPath() {
		return fPath;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("severity", fSeverity.name().toLowerCase());
		m.put("code", fCode);
		m.put("message", fMessage);
		m.put("path", fPath);
		return m;
	}

	@Override
	public String toString() {
		return fSeverity + " " + fCode + ": " + fMessage + (fPath != null ? " (" + fPath + ")" : "");
	}

}

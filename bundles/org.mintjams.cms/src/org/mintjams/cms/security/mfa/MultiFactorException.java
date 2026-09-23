/*
 * Copyright (c) 2024 MintJams Inc.
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

package org.mintjams.cms.security.mfa;

/**
 * A multi-factor operation that could not be carried out. {@link #getCode()}
 * is a stable, machine-readable reason so callers can report it in-band.
 */
public class MultiFactorException extends Exception {

	private static final long serialVersionUID = 1L;

	/** The supplied code or credential did not verify. */
	public static final String INVALID_CODE = "INVALID_CODE";

	/** The operation needs an enrollment that does not exist (or has expired). */
	public static final String NOT_ENROLLED = "NOT_ENROLLED";

	/** The operation does not apply to the current state (e.g. already enabled). */
	public static final String INVALID_STATE = "INVALID_STATE";

	/** The credential or user was not found. */
	public static final String NOT_FOUND = "NOT_FOUND";

	/** The credential is malformed or fails a structural check. */
	public static final String INVALID_CREDENTIAL = "INVALID_CREDENTIAL";

	/** The feature is not configured on this server. */
	public static final String NOT_AVAILABLE = "NOT_AVAILABLE";

	private final String fCode;

	public MultiFactorException(String code, String message) {
		super(message);
		fCode = code;
	}

	public MultiFactorException(String code, String message, Throwable cause) {
		super(message, cause);
		fCode = code;
	}

	public String getCode() {
		return fCode;
	}

}

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

package org.mintjams.cms.mail;

/**
 * A message could not be sent.
 */
public class MailException extends Exception {

	private static final long serialVersionUID = 1L;

	/** No transport is configured ({@code etc/mail.yml} is missing or invalid). */
	public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
	/** The message lacks a recipient, a subject or a body, or an address is malformed. */
	public static final String INVALID_MESSAGE = "INVALID_MESSAGE";
	/** The transport refused the message or could not be reached. */
	public static final String SEND_FAILED = "SEND_FAILED";

	private final String fCode;

	public MailException(String code, String message) {
		this(code, message, null);
	}

	public MailException(String code, String message, Throwable cause) {
		super(message, cause);
		fCode = code;
	}

	/** One of the constants of this class. */
	public String getCode() {
		return fCode;
	}

}

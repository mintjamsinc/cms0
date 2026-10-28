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
 * The platform's outgoing mail: messages the server itself sends, such as a
 * sign-in code or an address confirmation. The transport is configured by the
 * operator in {@code etc/mail.yml}; without that file nothing can be sent and
 * {@link #isAvailable()} is {@code false}.
 *
 * <p>This is not the Mail app: that one reads and sends through the user's
 * own account. A message sent here comes from the server's address.</p>
 */
public interface MailService {

	/**
	 * Whether a transport is configured, so that {@link #send} can be
	 * expected to work. The configuration is re-read when the file changes,
	 * so the answer may change while the server runs.
	 */
	boolean isAvailable();

	/**
	 * The address messages are sent from (the {@code from} of the
	 * configuration), or {@code null} when no transport is configured.
	 */
	String getFromAddress();

	/**
	 * Sends a message and returns when the transport accepted it.
	 *
	 * @throws MailException when no transport is configured, the message is
	 *                       incomplete, or the transport refused it
	 */
	void send(MailMessage message) throws MailException;

}

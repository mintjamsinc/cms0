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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A plain-text message for {@link MailService#send}. The sender is always the
 * configured server address; a message names only its recipients, subject
 * and body.
 */
public final class MailMessage {

	private final List<String> fTo = new ArrayList<>();
	private String fSubject;
	private String fText;

	private MailMessage() {}

	/** A message to one address. */
	public static MailMessage to(String address) {
		MailMessage message = new MailMessage();
		return message.addTo(address);
	}

	public MailMessage addTo(String address) {
		if (address == null || address.isBlank()) {
			throw new IllegalArgumentException("A recipient address is required.");
		}
		fTo.add(address.trim());
		return this;
	}

	public MailMessage setSubject(String subject) {
		fSubject = subject;
		return this;
	}

	/** The body as plain text (UTF-8). */
	public MailMessage setText(String text) {
		fText = text;
		return this;
	}

	public List<String> getTo() {
		return Collections.unmodifiableList(fTo);
	}

	public String getSubject() {
		return fSubject;
	}

	public String getText() {
		return fText;
	}

}

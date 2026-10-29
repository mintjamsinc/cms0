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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A text message for {@link MailService#send}. The sender is always the
 * configured server address; a message names only its recipients, subject
 * and body. The body is plain text in UTF-8 unless another text type or
 * charset is given.
 */
public final class MailMessage {

	public static final String DEFAULT_MIME_TYPE = "text/plain";
	public static final String DEFAULT_ENCODING = "UTF-8";

	private final List<String> fTo = new ArrayList<>();
	private String fSubject;
	private String fText;
	private String fMimeType = DEFAULT_MIME_TYPE;
	private String fEncoding = DEFAULT_ENCODING;

	private MailMessage() {}

	/** A message to one address. */
	public static MailMessage to(String address) {
		MailMessage message = new MailMessage();
		return message.addTo(address);
	}

	/** A message to the same recipients as {@code message}, without subject or body. */
	public static MailMessage toRecipientsOf(MailMessage message) {
		MailMessage copy = new MailMessage();
		for (String address : message.getTo()) {
			copy.addTo(address);
		}
		return copy;
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
		return setText(text, DEFAULT_MIME_TYPE, DEFAULT_ENCODING);
	}

	/**
	 * The body in a text type ({@code text/plain}, {@code text/html}, ...),
	 * sent in {@code encoding}; the subject is encoded in it too.
	 *
	 * @throws IllegalArgumentException when the type is not {@code text/*}
	 *                                  or the charset is unknown
	 */
	public MailMessage setText(String text, String mimeType, String encoding) {
		String type = (mimeType == null) ? "" : mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
		if (!type.startsWith("text/") || type.length() == "text/".length()) {
			throw new IllegalArgumentException("Not a text type: " + mimeType);
		}
		if (encoding == null || !Charset.isSupported(encoding.trim())) {
			throw new IllegalArgumentException("Unknown charset: " + encoding);
		}
		fText = text;
		fMimeType = type;
		fEncoding = encoding.trim();
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

	/** The type of the body, such as {@code text/plain} (without parameters). */
	public String getMimeType() {
		return fMimeType;
	}

	/** The charset the subject and body are sent in. */
	public String getEncoding() {
		return fEncoding;
	}

}

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

package org.mintjams.rt.cms.internal.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.mintjams.jcr.util.JCRs;

/**
 * What a file is to a reader: text, to read, search, edit and compare line by
 * line; a document, whose text is extracted; media, which has no text; or
 * other binary content, whose text an extractor may still find.
 *
 * <p>This is the one place that decides it. Reading ({@code read_file},
 * {@code documentText}), searching ({@code grepFiles}), editing
 * ({@code editFile}) and comparing ({@code fileDiff}) all ask here, so a file
 * one of them treats as text is text to all of them: a PDF whose first bytes
 * happen to look like ASCII is a document everywhere, and is never grepped or
 * edited as text.
 *
 * <p>The MIME type decides when it says something. MIME types are assigned
 * from file names, though, and many source formats (templates, scripts,
 * Markdown) map to types that reveal nothing about being text; for those the
 * start of the content is the witness.
 */
public enum FileKind {

	/** Text: read in its encoding, searched, edited and compared by line. */
	TEXT,
	/** A document (PDF, Office, OpenDocument, RTF, EPUB, e-mail): its text is extracted, its bytes never read as text. */
	DOCUMENT,
	/** Image, audio, video or font: no text to read or extract. */
	MEDIA,
	/** Binary content of no known kind: not text, but an extractor may still find text in it. */
	BINARY;

	/** How much of the content is looked at when the MIME type does not tell. */
	private static final int SNIFF_BYTES = 4096;

	private static final Set<String> TEXT_TYPES = new HashSet<>(Arrays.asList("application/json", "application/xml",
			"application/javascript", "application/x-javascript", "application/ecmascript", "application/yaml",
			"application/x-yaml", "application/graphql", "application/sql", "application/x-sh",
			"application/x-groovy", "application/toml", "image/svg+xml"));

	/** Whether this kind has text that an extractor can find: a document, or unknown binary content. */
	public boolean isExtractable() {
		return this == DOCUMENT || this == BINARY;
	}

	/**
	 * The kind of a file, or of the frozen node of one of its versions. The
	 * content is read (its first {@value #SNIFF_BYTES} bytes) only when the
	 * MIME type does not tell.
	 */
	public static FileKind of(Node file) throws RepositoryException, IOException {
		FileKind kind = ofMimeType(JCRs.getMimeType(file));
		if (kind != null) {
			return kind;
		}
		return looksLikeText(file) ? TEXT : BINARY;
	}

	/** The kind a MIME type alone says a file is, or {@code null} when only the content can tell. */
	public static FileKind ofMimeType(String mimeType) {
		if (mimeType == null || mimeType.isEmpty()) {
			return null;
		}
		String type = mimeType.toLowerCase(Locale.ROOT);
		int parameters = type.indexOf(';');
		if (parameters >= 0) {
			type = type.substring(0, parameters).trim();
		}
		// RTF is markup a reader wants the text of, whichever of its two types it has.
		if (type.equals("application/rtf") || type.equals("text/rtf")) {
			return DOCUMENT;
		}
		// Text before media and documents: image/svg+xml and application/vnd.api+json are text.
		if (type.startsWith("text/") || type.endsWith("+json") || type.endsWith("+xml") || TEXT_TYPES.contains(type)) {
			return TEXT;
		}
		if (type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/")
				|| type.startsWith("font/")) {
			return MEDIA;
		}
		if (type.equals("application/pdf") || type.startsWith("application/vnd.") || type.equals("application/msword")
				|| type.equals("application/epub+zip") || type.equals("message/rfc822")) {
			return DOCUMENT;
		}
		return null;
	}

	/**
	 * Whether the start of the content reads as text: no NUL byte, and —
	 * unless the file records its own encoding — well-formed UTF-8.
	 */
	private static boolean looksLikeText(Node file) throws RepositoryException, IOException {
		byte[] head;
		try (InputStream in = JCRs.getContentAsStream(file)) {
			head = in.readNBytes(SNIFF_BYTES);
		}
		for (byte b : head) {
			if (b == 0) {
				return false;
			}
		}
		String encoding = JCRs.getEncoding(file);
		if (encoding != null && !encoding.isEmpty()) {
			return true;
		}
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		// endOfInput=false: a multi-byte character cut off at the end of the window is not an error.
		return !decoder.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.length + 1), false).isError();
	}

}

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
import java.io.Reader;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.tika.Tika;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ContentHandlerDecorator;
import org.apache.tika.sax.WriteOutContentHandler;
import org.mintjams.jcr.util.JCRs;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Reading the text of a file a page at a time: a text file straight from its
 * content, in its encoding; a document through Apache Tika, the extractor the
 * search index uses, so what search finds in a document is what is read.
 *
 * <p>Neither reads more than the page needs. A text file is skipped to the
 * offset and read to the end of the page; extraction stops at
 * {@code offset + limit} characters, so a page deep in a long document costs
 * a parse up to that point and no more than the page is held in memory.
 */
public final class DocumentTexts {

	private static volatile Tika fTika;

	private DocumentTexts() {}

	/** A page of text: the characters, and whether more follow. */
	public static final class Page {
		private final String fText;
		private final boolean fTruncated;

		Page(String text, boolean truncated) {
			fText = text;
			fTruncated = truncated;
		}

		public String getText() {
			return fText;
		}

		/** Whether more text follows the page. */
		public boolean isTruncated() {
			return fTruncated;
		}
	}

	/** A page of the text extracted from a document, with what the document records about itself. */
	public static final class Extracted {
		private final Page fPage;
		private final boolean fEmpty;
		private final boolean fTimedOut;
		private final String fDetectedType;
		private final String fTitle;
		private final String fAuthor;
		private final Integer fPages;

		Extracted(Page page, boolean empty, boolean timedOut, Metadata metadata) {
			fPage = page;
			fEmpty = empty;
			fTimedOut = timedOut;
			fDetectedType = metadata.get(Metadata.CONTENT_TYPE);
			fTitle = metadata.get(TikaCoreProperties.TITLE);
			fAuthor = metadata.get(TikaCoreProperties.CREATOR);
			fPages = pagesOf(metadata);
		}

		public Page getPage() {
			return fPage;
		}

		/** Whether the document has no text at all, at any offset (a scanned PDF, for example). */
		public boolean isEmpty() {
			return fEmpty;
		}

		/** Whether extraction stopped at the deadline, before the end of the page. */
		public boolean isTimedOut() {
			return fTimedOut;
		}

		/** The MIME type Tika detected from the content. */
		public String getDetectedType() {
			return fDetectedType;
		}

		public String getTitle() {
			return fTitle;
		}

		public String getAuthor() {
			return fAuthor;
		}

		/** Pages (PDF, Word) or slides (PowerPoint), when the document records them. */
		public Integer getPages() {
			return fPages;
		}

		private static Integer pagesOf(Metadata metadata) {
			for (String key : new String[] { "xmpTPg:NPages", "meta:page-count", "meta:slide-count" }) {
				String value = metadata.get(key);
				if (value != null) {
					try {
						return Integer.valueOf(value.trim());
					} catch (NumberFormatException ignore) {}
				}
			}
			return null;
		}
	}

	/** A page of a text file (or of a version of one), decoded with its recorded encoding (UTF-8 when none is). */
	public static Page readText(Node file, int offset, int limit) throws RepositoryException, IOException {
		StringBuilder text = new StringBuilder();
		boolean truncated;
		try (Reader in = JCRs.getContentAsReader(file)) {
			long toSkip = offset;
			while (toSkip > 0) {
				long skipped = in.skip(toSkip);
				if (skipped <= 0) {
					break;
				}
				toSkip -= skipped;
			}
			char[] buffer = new char[8192];
			while (text.length() < limit) {
				int n = in.read(buffer, 0, Math.min(buffer.length, limit - text.length()));
				if (n == -1) {
					break;
				}
				text.append(buffer, 0, n);
			}
			truncated = (text.length() >= limit) && (in.read() != -1);
		}
		return new Page(text.toString(), truncated);
	}

	/**
	 * A page of the text extracted from a document (or other binary content)
	 * with Apache Tika. Extraction stops at {@code offset + limit} characters,
	 * or when {@code deadline} passes; then {@link Extracted#isTimedOut()}
	 * says so, and the page holds what was extracted by then.
	 *
	 * @throws IllegalArgumentException when the document is encrypted, or its
	 *         text cannot be extracted; the message says which, for the reader
	 */
	public static Extracted extract(Node file, String path, int offset, int limit, Deadline deadline)
			throws RepositoryException, IOException {
		// One more character than the page needs tells whether the document goes on.
		int max = (offset > Integer.MAX_VALUE - limit - 1) ? Integer.MAX_VALUE : (offset + limit + 1);
		Metadata metadata = new Metadata();
		WriteOutContentHandler out = new WriteOutContentHandler(max);
		boolean timedOut = false;
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream in = JCRs.getContentAsStream(file)) {
			// Tika finds its parsers through the context class loader; on a
			// request thread that is the web container's, which sees none.
			Thread.currentThread().setContextClassLoader(Tika.class.getClassLoader());
			Parser parser = tika().getParser();
			ParseContext context = new ParseContext();
			context.set(Parser.class, parser);
			parser.parse(in, new BodyContentHandler(new DeadlineHandler(out, deadline)), metadata, context);
		} catch (RepositoryException ex) {
			throw ex;
		} catch (Exception ex) {
			if (WriteLimitReachedException.isWriteLimitReached(ex)) {
				// The page, and the one character that says more follows, are in.
			} else if (causedBy(ex, Deadline.Exceeded.class)) {
				timedOut = true;
			} else if (causedBy(ex, EncryptedDocumentException.class)) {
				throw new IllegalArgumentException(
						path + " is encrypted: its text cannot be extracted without the password.");
			} else {
				String reason = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName();
				throw new IllegalArgumentException("The text of " + path + " could not be extracted: " + reason);
			}
		} finally {
			Thread.currentThread().setContextClassLoader(loader);
		}
		String all = out.toString();
		int start = Math.min(offset, all.length());
		int end = (int) Math.min((long) offset + limit, all.length());
		Page page = new Page(all.substring(start, end), timedOut || all.length() > (long) offset + limit);
		return new Extracted(page, !timedOut && all.trim().isEmpty(), timedOut, metadata);
	}

	private static boolean causedBy(Throwable ex, Class<? extends Throwable> type) {
		for (Throwable t = ex; t != null; t = (t.getCause() == t) ? null : t.getCause()) {
			if (type.isInstance(t)) {
				return true;
			}
		}
		return false;
	}

	/** Passes the text on, and stops the parse once the deadline has passed. */
	private static final class DeadlineHandler extends ContentHandlerDecorator {
		private final Deadline fDeadline;

		DeadlineHandler(ContentHandler handler, Deadline deadline) {
			super(handler);
			fDeadline = deadline;
		}

		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			check();
			super.characters(ch, start, length);
		}

		@Override
		public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
			check();
			super.ignorableWhitespace(ch, start, length);
		}

		@Override
		public void startElement(String uri, String localName, String name, Attributes atts) throws SAXException {
			check();
			super.startElement(uri, localName, name, atts);
		}

		private void check() throws SAXException {
			if (fDeadline != null && fDeadline.isPassed()) {
				throw new SAXException(new Deadline.Exceeded());
			}
		}
	}

	private static Tika tika() {
		// One instance: it is thread-safe, and building one loads the parser registry.
		Tika tika = fTika;
		if (tika == null) {
			synchronized (DocumentTexts.class) {
				tika = fTika;
				if (tika == null) {
					fTika = tika = new Tika();
				}
			}
		}
		return tika;
	}

}

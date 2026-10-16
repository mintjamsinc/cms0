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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.mintjams.jcr.util.JCRs;

/**
 * Optimistic concurrency for a write: the writer passes the {@code modified}
 * of the content it read, and the write is refused when the file has been
 * written since.
 *
 * <p>An MCP client works as its user, next to the user and everyone else in
 * the workspace. Between reading a file and writing it back, a person may
 * have saved it in Webtop; without this check the client's write would
 * replace that change without anyone noticing. Every content write advances
 * {@code jcr:content/jcr:lastModified} ({@code JCRs.write}), so the instant
 * the client read is enough to tell whether the content is still the one it
 * read. Instants are compared to the millisecond, whatever their notation.
 */
public final class IfModified {

	private IfModified() {}

	/**
	 * Refuses the write when the file's content was last modified at another
	 * instant than {@code ifModified}; does nothing when {@code ifModified} is
	 * empty.
	 *
	 * @throws IllegalArgumentException with a message for the writer: what
	 *         changed, when and by whom, and that nothing was written
	 */
	public static void check(Node file, String path, String ifModified) throws RepositoryException, IOException {
		if (ifModified == null || ifModified.isEmpty()) {
			return;
		}
		Instant expected = parse(ifModified);
		Date actual = JCRs.getLastModified(file);
		if (actual != null && actual.getTime() == expected.toEpochMilli()) {
			return;
		}
		throw new IllegalArgumentException(path + " has changed since it was read: it was last modified "
				+ ((actual != null) ? actual.toInstant().toString() : "at an unknown time") + " by "
				+ JCRs.getLastModifiedBy(file) + ", not " + ifModified + ". Nothing was written. Read it again and"
				+ " make the change on what it holds now.");
	}

	/** Refuses a write that expected a file where there is none any more. */
	public static void checkGone(String path, String ifModified) {
		if (ifModified == null || ifModified.isEmpty()) {
			return;
		}
		throw new IllegalArgumentException("There is no file at " + path + " any more: it was deleted or moved"
				+ " since it was read. Nothing was written.");
	}

	private static Instant parse(String value) {
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException ignore) {}
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (DateTimeParseException ex) {
			throw new IllegalArgumentException("ifModified must be the modified time the file was read with, an"
					+ " ISO-8601 instant such as 2026-10-16T12:57:02.748Z: " + value);
		}
	}

}

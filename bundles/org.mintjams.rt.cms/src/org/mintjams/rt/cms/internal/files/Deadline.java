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

/**
 * The time a piece of work may take: a search over a folder, the extraction of
 * a document. A request thread cannot be stopped from outside, so work that
 * may run long checks its deadline as it goes and stops itself, reporting how
 * far it got.
 *
 * <p>A regular expression is the one piece of work that does not check by
 * itself: a pattern with nested repetition can backtrack for longer than the
 * whole request may take, on a single line. {@link #guard} hands the matcher
 * a view of the line that checks the deadline as it is read, so the match is
 * abandoned instead.
 */
public final class Deadline {

	/** How many characters a guarded matcher reads between two looks at the clock. */
	private static final int CHECK_EVERY = 4096;

	private final long fMillis;
	private final long fEndNanos;

	private Deadline(long millis) {
		fMillis = millis;
		fEndNanos = System.nanoTime() + millis * 1_000_000L;
	}

	/** A deadline {@code millis} milliseconds from now. */
	public static Deadline in(long millis) {
		return new Deadline(millis);
	}

	/** The time allowed, in milliseconds, for messages. */
	public long getMillis() {
		return fMillis;
	}

	public boolean isPassed() {
		return System.nanoTime() - fEndNanos >= 0;
	}

	/** Throws {@link Exceeded} when the deadline has passed. */
	public void check() {
		if (isPassed()) {
			throw new Exceeded();
		}
	}

	/**
	 * The text as a {@link CharSequence} that throws {@link Exceeded} once
	 * the deadline has passed while it is being read — for a regular
	 * expression matcher, which reads its input through {@code charAt}.
	 */
	public CharSequence guard(CharSequence text) {
		return new Guarded(text, this);
	}

	/** The deadline passed while the work was running. Carries no stack trace: it is an outcome, not a fault. */
	public static final class Exceeded extends RuntimeException {
		private static final long serialVersionUID = 1L;

		Exceeded() {
			super("The time allowed has passed.", null, false, false);
		}
	}

	private static final class Guarded implements CharSequence {
		private final CharSequence fText;
		private final Deadline fDeadline;
		private int fReads;

		Guarded(CharSequence text, Deadline deadline) {
			fText = text;
			fDeadline = deadline;
		}

		@Override
		public char charAt(int index) {
			if (++fReads >= CHECK_EVERY) {
				fReads = 0;
				fDeadline.check();
			}
			return fText.charAt(index);
		}

		@Override
		public int length() {
			return fText.length();
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new Guarded(fText.subSequence(start, end), fDeadline);
		}

		@Override
		public String toString() {
			return fText.toString();
		}
	}

}

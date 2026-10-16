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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * A unified diff of two texts, with three lines of context, as {@code diff -u}
 * prints it.
 *
 * <p>Lines are compared after CRLF is normalised to LF, so a change of line
 * endings alone shows no difference. The edit script is Myers' algorithm (the
 * one git uses); for two texts that differ very widely it falls back to one
 * hunk covering everything between the common head and tail, so memory stays
 * bounded whatever the texts are.
 */
public final class TextDiff {

	private static final int CONTEXT = 3;

	/** Cells of the Myers trace allowed before falling back to one wide hunk. */
	private static final long MYERS_BUDGET = 16_000_000L;

	private final boolean fIdentical;
	private final int fAdded;
	private final int fRemoved;
	private final int fHunks;
	private final int fTotalHunks;
	private final String fDiff;
	private final boolean fTruncated;

	private TextDiff(boolean identical, int added, int removed, int hunks, int totalHunks, String diff,
			boolean truncated) {
		fIdentical = identical;
		fAdded = added;
		fRemoved = removed;
		fHunks = hunks;
		fTotalHunks = totalHunks;
		fDiff = diff;
		fTruncated = truncated;
	}

	/** Whether the two are the same text, line endings aside. */
	public boolean isIdentical() {
		return fIdentical;
	}

	/** Lines added, over the whole difference. */
	public int getAdded() {
		return fAdded;
	}

	/** Lines removed, over the whole difference. */
	public int getRemoved() {
		return fRemoved;
	}

	/** Hunks in {@link #getDiff()}; fewer than {@link #getTotalHunks()} when truncated. */
	public int getHunks() {
		return fHunks;
	}

	public int getTotalHunks() {
		return fTotalHunks;
	}

	/** The unified diff, with its {@code ---} and {@code +++} header; empty when the texts are identical. */
	public String getDiff() {
		return fDiff;
	}

	/** Whether the diff was cut short at the limit, on a hunk boundary. */
	public boolean isTruncated() {
		return fTruncated;
	}

	/**
	 * Compares two texts. The labels name each side in the header
	 * ({@code --- fromLabel}, {@code +++ toLabel}); the diff text is cut on a
	 * hunk boundary before it exceeds {@code limit} characters.
	 */
	public static TextDiff compare(String fromText, String toText, String fromLabel, String toLabel, int limit) {
		String a = fromText.replace("\r\n", "\n");
		String b = toText.replace("\r\n", "\n");
		if (a.equals(b)) {
			return new TextDiff(true, 0, 0, 0, 0, "", false);
		}
		Lines la = Lines.of(a);
		Lines lb = Lines.of(b);
		List<Edit> edits = edits(la.fLines, lb.fLines);

		// Group the edits into hunks: runs of changes with CONTEXT kept lines
		// around them, as diff -u prints them.
		List<int[]> hunks = new ArrayList<>();
		int i = 0;
		while (i < edits.size()) {
			if (edits.get(i).fOp == ' ') {
				i++;
				continue;
			}
			int start = Math.max(0, i - CONTEXT);
			int end = i;
			int j = i;
			while (j < edits.size()) {
				if (edits.get(j).fOp != ' ') {
					end = j + 1;
					j++;
					continue;
				}
				// A kept run no longer than twice the context joins the hunks around it.
				int k = j;
				while (k < edits.size() && edits.get(k).fOp == ' ') {
					k++;
				}
				if (k < edits.size() && k - j <= 2 * CONTEXT) {
					j = k;
					continue;
				}
				break;
			}
			hunks.add(new int[] { start, Math.min(edits.size(), end + CONTEXT) });
			i = j;
		}

		// Line numbers of each edit in a and b, for the hunk headers.
		int[] aAt = new int[edits.size() + 1];
		int[] bAt = new int[edits.size() + 1];
		int aLine = 0;
		int bLine = 0;
		int added = 0;
		int removed = 0;
		for (int e = 0; e < edits.size(); e++) {
			aAt[e] = aLine;
			bAt[e] = bLine;
			char op = edits.get(e).fOp;
			if (op == ' ') {
				aLine++;
				bLine++;
			} else if (op == '-') {
				aLine++;
				removed++;
			} else {
				bLine++;
				added++;
			}
		}
		aAt[edits.size()] = aLine;
		bAt[edits.size()] = bLine;

		StringBuilder out = new StringBuilder();
		out.append("--- ").append(fromLabel).append('\n');
		out.append("+++ ").append(toLabel).append('\n');
		boolean truncated = false;
		int written = 0;
		for (int[] hunk : hunks) {
			int start = hunk[0];
			int end = hunk[1];
			int aCount = aAt[end] - aAt[start];
			int bCount = bAt[end] - bAt[start];
			StringBuilder text = new StringBuilder();
			text.append("@@ -").append(aCount == 0 ? aAt[start] : aAt[start] + 1).append(',').append(aCount)
					.append(" +").append(bCount == 0 ? bAt[start] : bAt[start] + 1).append(',').append(bCount)
					.append(" @@\n");
			for (int e = start; e < end; e++) {
				Edit edit = edits.get(e);
				text.append(edit.fOp).append(edit.fLine).append('\n');
				// The last line of either side without a newline is marked as diff does.
				boolean lastOfA = edit.fOp != '+' && aAt[e] == la.fLines.size() - 1 && !la.fNewline;
				boolean lastOfB = edit.fOp != '-' && bAt[e] == lb.fLines.size() - 1 && !lb.fNewline;
				if (lastOfA || lastOfB) {
					text.append("\\ No newline at end of file\n");
				}
			}
			if (out.length() + text.length() > limit) {
				truncated = true;
				break;
			}
			out.append(text);
			written++;
		}
		return new TextDiff(false, added, removed, written, hunks.size(), out.toString(), truncated);
	}

	/** The lines of a text, and whether it ends with a newline. */
	private static final class Lines {
		private final List<String> fLines;
		private final boolean fNewline;

		private Lines(List<String> lines, boolean newline) {
			fLines = lines;
			fNewline = newline;
		}

		static Lines of(String text) {
			if (text.isEmpty()) {
				return new Lines(new ArrayList<>(), true);
			}
			boolean newline = text.endsWith("\n");
			String body = newline ? text.substring(0, text.length() - 1) : text;
			return new Lines(Arrays.asList(body.split("\n", -1)), newline);
		}
	}

	/** One step of the edit script: {@code ' '} kept, {@code '-'} removed, {@code '+'} added. */
	private static final class Edit {
		private final char fOp;
		private final String fLine;

		Edit(char op, String line) {
			fOp = op;
			fLine = line;
		}
	}

	private static List<Edit> edits(List<String> a, List<String> b) {
		int n = a.size();
		int m = b.size();
		// The common head and tail are never part of an edit; trimming them
		// first keeps the search small for the usual edit in the middle of a file.
		int head = 0;
		while (head < n && head < m && a.get(head).equals(b.get(head))) {
			head++;
		}
		int tail = 0;
		while (tail < n - head && tail < m - head && a.get(n - 1 - tail).equals(b.get(m - 1 - tail))) {
			tail++;
		}
		List<String> ma = a.subList(head, n - tail);
		List<String> mb = b.subList(head, m - tail);
		List<Edit> middle = myers(ma, mb);
		if (middle == null) {
			middle = new ArrayList<>();
			for (String line : ma) {
				middle.add(new Edit('-', line));
			}
			for (String line : mb) {
				middle.add(new Edit('+', line));
			}
		}
		List<Edit> edits = new ArrayList<>(n + m);
		for (int i = 0; i < head; i++) {
			edits.add(new Edit(' ', a.get(i)));
		}
		edits.addAll(middle);
		for (int i = n - tail; i < n; i++) {
			edits.add(new Edit(' ', a.get(i)));
		}
		return edits;
	}

	/** Myers' shortest edit script, or {@code null} when it would need more memory than allowed. */
	private static List<Edit> myers(List<String> a, List<String> b) {
		int n = a.size();
		int m = b.size();
		int max = n + m;
		if (max == 0) {
			return new ArrayList<>();
		}
		int width = 2 * max + 2;
		int[] v = new int[width];
		List<int[]> trace = new ArrayList<>();
		int found = -1;
		for (int d = 0; d <= max; d++) {
			if ((long) (d + 1) * width > MYERS_BUDGET) {
				return null;
			}
			trace.add(v.clone());
			for (int k = -d; k <= d; k += 2) {
				int x;
				if (k == -d || (k != d && v[k - 1 + max] < v[k + 1 + max])) {
					x = v[k + 1 + max];
				} else {
					x = v[k - 1 + max] + 1;
				}
				int y = x - k;
				while (x < n && y < m && a.get(x).equals(b.get(y))) {
					x++;
					y++;
				}
				v[k + max] = x;
				if (x >= n && y >= m) {
					found = d;
					break;
				}
			}
			if (found >= 0) {
				break;
			}
		}
		// Backtrack from (n, m) through the recorded furthest-reaching points.
		LinkedList<Edit> edits = new LinkedList<>();
		int x = n;
		int y = m;
		for (int d = found; d > 0; d--) {
			int[] vd = trace.get(d);
			int k = x - y;
			int prevK;
			if (k == -d || (k != d && vd[k - 1 + max] < vd[k + 1 + max])) {
				prevK = k + 1;
			} else {
				prevK = k - 1;
			}
			int prevX = vd[prevK + max];
			int prevY = prevX - prevK;
			while (x > prevX && y > prevY) {
				x--;
				y--;
				edits.addFirst(new Edit(' ', a.get(x)));
			}
			if (x == prevX) {
				y--;
				edits.addFirst(new Edit('+', b.get(y)));
			} else {
				x--;
				edits.addFirst(new Edit('-', a.get(x)));
			}
		}
		while (x > 0 && y > 0) {
			x--;
			y--;
			edits.addFirst(new Edit(' ', a.get(x)));
		}
		return new ArrayList<>(edits);
	}

}

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

package org.mintjams.cms.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.mintjams.cms.security.DefaultEncryptor;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;

/**
 * The parsed text of {@code etc/secrets.yml}: a flat mapping whose values are
 * single-line scalars, each either in the clear or an {@code ENC[...]} value.
 *
 * <p>Both readers of the file share this parse — {@link Secrets}, which
 * answers {@code ${secrets.KEY}}, and the watcher in the CMS runtime, which
 * encrypts values written in the clear and puts them back. The watcher needs
 * to know <em>where</em> in the text each value stands so that it can replace
 * exactly that and nothing else (comments, order and blank lines survive),
 * which is why this works on the composed nodes rather than on a loaded map.
 *
 * <p>A value that does not fit the format (a nested mapping, a list, a block
 * scalar, a value spanning lines, an anchor, a key written twice) is kept as
 * an entry with a {@linkplain Entry#getProblem() problem} instead of a value:
 * the reader then refuses that key with the reason, and the watcher leaves
 * the file alone. A document that is not a mapping at all has no entries and
 * an {@linkplain #getError() error}.
 */
public final class SecretsFile {

	/** One {@code key: value} line of the file. */
	public static final class Entry {

		private final String fKey;
		private final String fValue;
		private final int fValueStart;
		private final int fValueEnd;
		private final String fProblem;

		private Entry(String key, String value, int valueStart, int valueEnd, String problem) {
			fKey = key;
			fValue = value;
			fValueStart = valueStart;
			fValueEnd = valueEnd;
			fProblem = problem;
		}

		public String getKey() {
			return fKey;
		}

		/**
		 * The value as written (unquoted), the empty string for an unset key
		 * ({@code KEY:} alone, or {@code null}), or {@code null} when the entry
		 * has a problem.
		 */
		public String getValue() {
			return fValue;
		}

		/** Whether the key has no value: it is written but not set. */
		public boolean isEmpty() {
			return fProblem == null && fValue.isEmpty();
		}

		/** Whether the value is an {@code ENC[...]} one. */
		public boolean isEncrypted() {
			return fProblem == null && DefaultEncryptor.isEncryptedValue(fValue);
		}

		/** Whether the value is set and written in the clear. */
		public boolean isPlain() {
			return fProblem == null && !fValue.isEmpty() && !DefaultEncryptor.isEncryptedValue(fValue);
		}

		/** Where the value scalar begins in the text, as a code point index. */
		public int getValueStart() {
			return fValueStart;
		}

		/** Where the value scalar ends in the text (exclusive), as a code point index. */
		public int getValueEnd() {
			return fValueEnd;
		}

		/** Why the entry cannot be used, or {@code null} when it can. Names the key, never a value. */
		public String getProblem() {
			return fProblem;
		}

	}

	private final List<Entry> fEntries;
	private final Map<String, Entry> fByKey;
	private final String fError;

	private SecretsFile(List<Entry> entries, String error) {
		fEntries = Collections.unmodifiableList(entries);
		Map<String, Entry> byKey = new HashMap<>();
		for (Entry entry : entries) {
			byKey.put(entry.getKey(), entry);
		}
		fByKey = byKey;
		fError = error;
	}

	/**
	 * Parses the text of the file. Never throws for the content: a document
	 * that cannot be used is returned with its {@link #getError() error}.
	 */
	public static SecretsFile parse(String text) {
		Optional<Node> root;
		try {
			root = new Compose(LoadSettings.builder().build()).composeString(text);
		} catch (Throwable ex) {
			return new SecretsFile(new ArrayList<>(), "the file is not valid YAML: " + ex.getMessage());
		}
		if (root.isEmpty()) {
			return new SecretsFile(new ArrayList<>(), null);
		}
		if (!(root.get() instanceof MappingNode)) {
			return new SecretsFile(new ArrayList<>(), "the document is not a mapping of key: value lines");
		}

		List<Entry> entries = new ArrayList<>();
		Map<String, Integer> seen = new HashMap<>();
		for (NodeTuple tuple : ((MappingNode) root.get()).getValue()) {
			if (!(tuple.getKeyNode() instanceof ScalarNode)) {
				return new SecretsFile(new ArrayList<>(), "a key is not a plain name");
			}
			String key = ((ScalarNode) tuple.getKeyNode()).getValue();
			Node valueNode = tuple.getValueNode();
			int start = index(valueNode.getStartMark());
			int end = index(valueNode.getEndMark());

			String problem = null;
			String value = null;
			if (valueNode.getAnchor().isPresent()) {
				problem = "uses an anchor or alias";
			} else if (!(valueNode instanceof ScalarNode)) {
				problem = "is not a single-line value";
			} else {
				ScalarNode scalar = (ScalarNode) valueNode;
				if (scalar.getScalarStyle() == ScalarStyle.LITERAL || scalar.getScalarStyle() == ScalarStyle.FOLDED) {
					problem = "is a block scalar (| or >)";
				} else if (line(valueNode.getStartMark()) != line(valueNode.getEndMark())) {
					problem = "spans several lines";
				} else if (Tag.NULL.equals(scalar.getTag())) {
					value = "";
				} else {
					value = scalar.getValue();
				}
			}

			Integer earlier = seen.get(key);
			if (earlier != null) {
				Entry first = entries.get(earlier);
				entries.set(earlier, new Entry(key, null, first.fValueStart, first.fValueEnd, "is written more than once"));
				problem = "is written more than once";
				value = null;
			}
			seen.put(key, entries.size());
			entries.add(new Entry(key, value, start, end, problem));
		}
		return new SecretsFile(entries, null);
	}

	/**
	 * The text with the value of each given entry replaced by what
	 * {@code newValue} makes of it, and nothing else touched: comments, blank
	 * lines, the order of the keys and the other values stay as written. The
	 * parser's positions count code points, so the text is spliced as code
	 * points; replacing from the end keeps the earlier positions valid. The
	 * replacement is written as it is, so it has to be a plain scalar that
	 * needs no quoting ({@code ENC[...]} is one).
	 */
	public static String rewrite(String text, List<Entry> entries, UnaryOperator<String> newValue) {
		int[] codePoints = text.codePoints().toArray();
		List<Entry> fromTheEnd = new ArrayList<>(entries);
		fromTheEnd.sort(Comparator.comparingInt(Entry::getValueStart).reversed());
		StringBuilder sb = new StringBuilder(text);
		for (Entry entry : fromTheEnd) {
			if (entry.getProblem() != null) {
				throw new IllegalArgumentException("The entry " + entry.getKey() + " " + entry.getProblem() + ".");
			}
			int start = charIndex(codePoints, entry.getValueStart());
			int end = charIndex(codePoints, entry.getValueEnd());
			sb.replace(start, end, newValue.apply(entry.getValue()));
		}
		return sb.toString();
	}

	private static int charIndex(int[] codePoints, int codePointIndex) {
		int chars = 0;
		for (int i = 0; i < codePointIndex && i < codePoints.length; i++) {
			chars += Character.charCount(codePoints[i]);
		}
		return chars;
	}

	/** A file that could not be read at all, with the reason as its error. */
	public static SecretsFile unreadable(String error) {
		return new SecretsFile(new ArrayList<>(), error);
	}

	/** The entries in file order. */
	public List<Entry> getEntries() {
		return fEntries;
	}

	/** The entry for a key, or {@code null}. */
	public Entry get(String key) {
		return fByKey.get(key);
	}

	/** Why the file as a whole cannot be used, or {@code null}. */
	public String getError() {
		return fError;
	}

	private static int index(Optional<Mark> mark) {
		return mark.map(Mark::getIndex).orElse(0);
	}

	private static int line(Optional<Mark> mark) {
		return mark.map(Mark::getLine).orElse(0);
	}

}

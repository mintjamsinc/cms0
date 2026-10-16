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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;

/**
 * The places in a file where a full-text search's words occur: what
 * {@code search} shows beside each result, so that a reader sees where a
 * document mentions something without reading it whole.
 *
 * <p>The search index keeps no text to highlight from, so the passages are
 * found in the file's text itself — read as it is, or extracted from a
 * document as {@link DocumentTexts} extracts it, which is the text the index
 * was built from. Only the start of a file is looked at, and the whole
 * search shares one deadline, so a page of results never costs more than the
 * limits allow; a result whose passages could not be found in time simply
 * has none.
 *
 * <p>The words are those of the search text as the full-text query reads it:
 * terms separated by spaces, a quoted phrase as one, without the excluded
 * ({@code -term}) words and the {@code OR} / {@code AND} operators. They are
 * matched regardless of case.
 */
public final class SearchExcerpts {

	/** Passages returned per file. */
	public static final int MAX_EXCERPTS = 3;

	/** Characters from the start of a file looked at for passages. */
	public static final int MAX_CHARS = 200_000;

	/** Characters of context on each side of a passage's first word. */
	private static final int CONTEXT = 80;

	private SearchExcerpts() {}

	/** The words of a full-text search text, as the passages are matched against. */
	public static List<String> terms(String searchText) {
		List<String> terms = new ArrayList<>();
		if (searchText == null) {
			return terms;
		}
		Matcher m = Pattern.compile("-?\"([^\"]*)\"|(\\S+)").matcher(searchText);
		while (m.find()) {
			String term;
			if (m.group(1) != null) {
				if (m.group().startsWith("-")) {
					continue;
				}
				term = m.group(1).trim();
			} else {
				term = m.group(2);
				if (term.startsWith("-") || term.equals("OR") || term.equals("AND") || term.equals("NOT")) {
					continue;
				}
				term = term.replace("*", "").replace("?", "");
			}
			if (!term.isEmpty() && !terms.contains(term)) {
				terms.add(term);
			}
		}
		return terms;
	}

	/**
	 * Up to {@value #MAX_EXCERPTS} passages of the file where one of the
	 * words occurs, in the order they appear: each a map with {@code offset}
	 * (the character offset of the word, for read_file and documentText),
	 * {@code line} (in a text file) and {@code text}. Empty when the words do
	 * not occur in the start of the file, the file has no text, or the
	 * deadline has passed.
	 */
	public static List<Map<String, Object>> find(Node file, List<String> terms, Deadline deadline) {
		List<Map<String, Object>> excerpts = new ArrayList<>();
		if (terms.isEmpty() || deadline.isPassed()) {
			return excerpts;
		}
		String text;
		boolean isText;
		try {
			FileKind kind = FileKind.of(file);
			if (kind == FileKind.MEDIA) {
				return excerpts;
			}
			isText = (kind == FileKind.TEXT);
			text = isText ? DocumentTexts.readText(file, 0, MAX_CHARS).getText()
					: DocumentTexts.extract(file, file.getPath(), 0, MAX_CHARS, deadline).getPage().getText();
		} catch (Exception ex) {
			// A file whose text cannot be read is listed without passages.
			return excerpts;
		}
		return find(text, isText, terms);
	}

	/** {@link #find(Node, List, Deadline)} over text already read; {@code lines} says whether to count lines. */
	static List<Map<String, Object>> find(String text, boolean lines, List<String> terms) {
		// The first occurrences of every word, in document order.
		TreeMap<Integer, Integer> hits = new TreeMap<>();
		for (String term : terms) {
			Matcher m = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
					.matcher(text);
			int found = 0;
			while (found < MAX_EXCERPTS * 4 && m.find()) {
				hits.putIfAbsent(m.start(), m.end());
				found++;
			}
		}

		List<Map<String, Object>> excerpts = new ArrayList<>();
		int coveredTo = -1;
		int line = 1;
		int counted = 0;
		for (Map.Entry<Integer, Integer> hit : hits.entrySet()) {
			int start = hit.getKey();
			if (start < coveredTo) {
				continue; // already shown inside the previous passage
			}
			int from = Math.max(0, start - CONTEXT);
			int to = Math.min(text.length(), hit.getValue() + CONTEXT);
			Map<String, Object> excerpt = new LinkedHashMap<>();
			excerpt.put("offset", start);
			if (lines) {
				for (; counted < start; counted++) {
					if (text.charAt(counted) == '\n') {
						line++;
					}
				}
				excerpt.put("line", line);
			}
			excerpt.put("text", ((from > 0) ? "…" : "") + text.substring(from, to).replaceAll("\\s+", " ").trim()
					+ ((to < text.length()) ? "…" : ""));
			excerpts.add(excerpt);
			coveredTo = to;
			if (excerpts.size() >= MAX_EXCERPTS) {
				break;
			}
		}
		return excerpts;
	}

}

/*
 * Copyright (c) 2022 MintJams Inc.
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

package org.mintjams.rt.searchindex.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.util.BytesRef;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.lang.Strings;

public class IndexableSuggestion implements SearchIndex.Suggestion {

	private String fBundleIdentifier;
	private String fSuggestion;
	private final List<String> fAuthorized = new ArrayList<>();

	private IndexableSuggestion() {}

	public static IndexableSuggestion create(UnaryOperator<SearchIndex.Suggestion> op) {
		return (IndexableSuggestion) op.apply(new IndexableSuggestion());
	}

	@Override
	public SearchIndex.Suggestion setSuggestion(String suggestion) {
		if (Strings.isEmpty(suggestion)) {
			throw new IllegalArgumentException("Suggestion must not be null or empty.");
		}
		fSuggestion = suggestion;
		return this;
	}

	@Override
	public SearchIndex.Suggestion setSuggestionBundleIdentifier(String identifier) {
		if (identifier == null) {
			throw new IllegalArgumentException("Identifier must not be null.");
		}
		fBundleIdentifier = identifier;
		return this;
	}

	@Override
	public SearchIndex.Suggestion addAuthorized(String... names) {
		if (names == null) {
			return this;
		}

		for (String name : names) {
			if (!fAuthorized.contains(name)) {
				fAuthorized.add(name);
			}
		}
		return this;
	}

	@Override
	public SearchIndex.Suggestion removeAuthorized(String name) {
		fAuthorized.remove(name);
		return this;
	}

	public org.apache.lucene.document.Document asLuceneDocument() throws IOException {
		Document doc = new Document();
		doc.add(new StringField("_bundleIdentifier", fBundleIdentifier, Field.Store.YES));

		doc.add(new StringField("_suggestion", fSuggestion, Field.Store.YES));
		doc.add(new SortedDocValuesField("_suggestion", new BytesRef(fSuggestion)));
		for (String s : fSuggestion.split("\\s")) {
			if (Strings.isEmpty(s)) {
				continue;
			}
			doc.add(new TextField("_completion", s.trim(), Field.Store.NO));
		}

		for (String e : fAuthorized) {
			doc.add(new StringField("_authorized", e, Field.Store.NO));
		}

		return doc;
	}

}

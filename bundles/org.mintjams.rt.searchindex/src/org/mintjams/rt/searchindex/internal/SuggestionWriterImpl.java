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

import java.io.Closeable;
import java.io.IOException;
import java.util.function.UnaryOperator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.charfilter.MappingCharFilterFactory;
import org.apache.lucene.analysis.cjk.CJKWidthFilterFactory;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.core.StopFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.icu.ICUTransformFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseBaseFormFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseCompletionFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseIterationMarkCharFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseKatakanaStemFilterFactory;
import org.apache.lucene.analysis.ja.JapanesePartOfSpeechStopFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseTokenizerFactory;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilterFactory;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.lang.Cause;

public class SuggestionWriterImpl implements SearchIndex.SuggestionWriter, Closeable, Adaptable {

	private final SearchIndexImpl fSearchIndex;
	private final Closer fCloser = Closer.create();
	private final IndexWriter fIndexWriter;
	private Analyzer fAnalyzer;
	private boolean fHasChanges;

	public SuggestionWriterImpl(SearchIndexImpl searchIndex) throws IOException {
		fSearchIndex = searchIndex;
		fIndexWriter = fCloser.register(new IndexWriter(fSearchIndex.getSuggestionIndexDirectory(), new IndexWriterConfig(getAnalyzer())));
	}

	@Override
	public SearchIndex.SuggestionWriter update(UnaryOperator<SearchIndex.Suggestion> op) throws IOException {
		try {
			IndexableSuggestion indexableSuggestion = IndexableSuggestion.create(op);
			Document doc = indexableSuggestion.asLuceneDocument();
			fIndexWriter.updateDocument(new Term("_suggestion", doc.get("_suggestion")), doc);
			fHasChanges = true;
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
		return this;
	}

	@Override
	public SearchIndex.SuggestionWriter delete(String... identifiers) throws IOException {
		for (String identifier : identifiers) {
			fIndexWriter.deleteDocuments(new Term("_bundleIdentifier", identifier));
			fHasChanges = true;
		}
		return this;
	}

	@Override
	public boolean hasChanges() {
		return fHasChanges;
	}

	@Override
	public void commit() throws IOException {
		if (fHasChanges) {
			fIndexWriter.commit();
			fHasChanges = false;
		}
	}

	@Override
	public void rollback() throws IOException {
		if (fHasChanges) {
			fIndexWriter.rollback();
			fHasChanges = false;
		}
	}

	private Analyzer getAnalyzer() throws IOException {
		if (fAnalyzer == null) {
			Analyzer completionAnalyzer = CustomAnalyzer.builder(adaptTo(SearchIndexConfigurationImpl.class).getConfigPath())
					.addCharFilter(MappingCharFilterFactory.class, AdaptableMap.<String, String>newBuilder()
							.put("mapping", "mapping.txt")
							.build())
					.addCharFilter(PatternReplaceCharFilterFactory.class, AdaptableMap.<String, String>newBuilder()
							.put("pattern", "\\s+")
							.put("replacement", " ")
							.build())
					.addCharFilter(JapaneseIterationMarkCharFilterFactory.class, AdaptableMap.<String, String>newBuilder()
							.put("normalizeKanji", "true")
							.put("normalizeKana", "true")
							.build())
					.withTokenizer(JapaneseTokenizerFactory.class, AdaptableMap.<String, String>newBuilder()
							.put("userDictionary", "userdict.txt")
							.build())
					.addTokenFilter(JapaneseBaseFormFilterFactory.class, AdaptableMap.<String, String>newBuilder().build())
					.addTokenFilter(JapanesePartOfSpeechStopFilterFactory.class, AdaptableMap.<String, String>newBuilder()
							.put("tags", "stoptags.txt")
							.build())
					.addTokenFilter(StopFilterFactory.class, AdaptableMap.<String, String>newBuilder()
							.put("words", "stopwords.txt")
							.build())
					.addTokenFilter(CJKWidthFilterFactory.class, AdaptableMap.<String, String>newBuilder().build())
					.addTokenFilter(ICUTransformFilterFactory.class, AdaptableMap.<String, String>newBuilder()
							.put("id", "Hiragana-Katakana")
							.build())
					.addTokenFilter(JapaneseKatakanaStemFilterFactory.class, AdaptableMap.<String, String>newBuilder()
							.put("minimumLength", "4")
							.build())
					.addTokenFilter(LowerCaseFilterFactory.class, AdaptableMap.<String, String>newBuilder().build())
					.addTokenFilter(JapaneseCompletionFilterFactory.class, AdaptableMap.<String, String>newBuilder()
							.put("mode", "INDEX")
							.build())
					.build();

			fAnalyzer = fCloser.register(new PerFieldAnalyzerWrapper(new StandardAnalyzer(),
					AdaptableMap.<String, Analyzer>newBuilder().put("_completion", completionAnalyzer).build()));
		}
		return fAnalyzer;
	}

	@Override
	public void close() throws IOException {
		fCloser.close();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(IndexWriter.class)) {
			return (AdapterType) fIndexWriter;
		}

		return Adaptables.getAdapter(fSearchIndex, adapterType);
	}

}

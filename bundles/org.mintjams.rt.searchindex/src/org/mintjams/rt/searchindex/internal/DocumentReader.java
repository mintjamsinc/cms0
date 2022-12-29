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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.charfilter.MappingCharFilterFactory;
import org.apache.lucene.analysis.cjk.CJKWidthFilterFactory;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.core.StopFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.icu.ICUTransformFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseBaseFormFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseIterationMarkCharFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseKatakanaStemFilterFactory;
import org.apache.lucene.analysis.ja.JapanesePartOfSpeechStopFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseTokenizerFactory;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilterFactory;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.Closer;

public class DocumentReader implements Closeable, Adaptable {

	private final SearchIndexImpl fSearchIndex;
	private final Closer fCloser = Closer.create();
	private DirectoryReader fIndexReader;
	private DirectoryTaxonomyReader fTaxonomyReader;
	private IndexSearcher fIndexSearcher;
	private Analyzer fAnalyzer;

	public DocumentReader(SearchIndexImpl searchIndex) throws IOException {
		fSearchIndex = searchIndex;
	}

	public IndexSearcher getIndexSearcher() throws IOException {
		if (fSearchIndex.isCloseRequested()) {
			throw new IOException("SearchIndex has been closed.");
		}

		if (fIndexSearcher == null) {
			fIndexSearcher = new IndexSearcher(getDirectoryReader());
		} else {
			DirectoryReader newDirectoryReader = getDirectoryReader();
			if (!fIndexSearcher.getIndexReader().equals(newDirectoryReader)) {
				fIndexSearcher = new IndexSearcher(newDirectoryReader);
			}
		}
		return fIndexSearcher;
	}

	private DirectoryReader getDirectoryReader() throws IOException {
		if (fIndexReader == null) {
			fIndexReader = DirectoryReader.open(Adaptables.getAdapter(fSearchIndex.getDocumentWriter(), IndexWriter.class));
		} else {
			DirectoryReader newIndexReader = DirectoryReader.openIfChanged(fIndexReader);
			if (newIndexReader != null) {
				fIndexReader.close();
				fIndexReader = newIndexReader;
			}
		}
		return fIndexReader;
	}

	public DirectoryTaxonomyReader getDirectoryTaxonomyReader() throws IOException {
		if (fTaxonomyReader == null) {
			fTaxonomyReader = new DirectoryTaxonomyReader(Adaptables.getAdapter(fSearchIndex.getDocumentWriter(), DirectoryTaxonomyWriter.class));
		} else {
			DirectoryTaxonomyReader newTaxonomyReader = DirectoryTaxonomyReader.openIfChanged(fTaxonomyReader);
			if (newTaxonomyReader != null) {
				fTaxonomyReader.close();
				fTaxonomyReader = newTaxonomyReader;
			}
		}
		return fTaxonomyReader;
	}

	public Analyzer getAnalyzer() throws IOException {
		if (fAnalyzer == null) {
			Analyzer fulltextAnalyzer = CustomAnalyzer.builder(adaptTo(SearchIndexConfigurationImpl.class).getConfigPath())
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
					.build();

			fAnalyzer = fCloser.register(new PerFieldAnalyzerWrapper(new KeywordAnalyzer(),
					AdaptableMap.<String, Analyzer>newBuilder().put("_fulltext", fulltextAnalyzer).build()));
		}
		return fAnalyzer;
	}

	@Override
	public void close() throws IOException {
		fIndexSearcher = null;

		if (fIndexReader != null) {
			try {
				fIndexReader.close();
			} catch (Throwable ignore) {}
			fIndexReader = null;
		}

		if (fTaxonomyReader != null) {
			try {
				fTaxonomyReader.close();
			} catch (Throwable ignore) {}
			fTaxonomyReader = null;
		}

		fCloser.close();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fSearchIndex, adapterType);
	}

}

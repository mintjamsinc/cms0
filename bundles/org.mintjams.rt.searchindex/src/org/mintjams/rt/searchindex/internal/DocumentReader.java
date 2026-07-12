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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
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

	/**
	 * How long a superseded reader lingers before it is closed. This instance
	 * is shared by every query thread and the callers do not acquire/release
	 * readers, so a reader replaced by a refresh may still serve another
	 * thread's in-flight search — closing it immediately raised
	 * AlreadyClosedException under those searches. In-flight use is bounded by
	 * a single query execution (result rows are materialized eagerly), so the
	 * linger only needs to outlast the slowest single execution.
	 */
	private static final long RETIRE_LINGER_MILLIS = 10L * 60 * 1000;

	private final SearchIndexImpl fSearchIndex;
	private final Closer fCloser = Closer.create();
	private DirectoryReader fIndexReader;
	private DirectoryTaxonomyReader fTaxonomyReader;
	private IndexSearcher fIndexSearcher;
	private Analyzer fAnalyzer;
	private final List<Retired> fRetired = new ArrayList<>();

	private static class Retired {
		final Closeable reader;
		final long retiredAt;

		Retired(Closeable reader, long retiredAt) {
			this.reader = reader;
			this.retiredAt = retiredAt;
		}
	}

	public DocumentReader(SearchIndexImpl searchIndex) throws IOException {
		fSearchIndex = searchIndex;
	}

	// Refresh and access are synchronized: the unsynchronized version let one
	// thread replace-and-close fIndexReader while another thread was calling
	// openIfChanged on (or searching with) the stale reference it had read,
	// which surfaced as AlreadyClosedException from both paths.
	public synchronized IndexSearcher getIndexSearcher() throws IOException {
		if (fSearchIndex.isCloseRequested()) {
			throw new IOException("SearchIndex has been closed.");
		}

		DirectoryReader directoryReader = getDirectoryReader();
		if (fIndexSearcher == null || fIndexSearcher.getIndexReader() != directoryReader) {
			fIndexSearcher = new IndexSearcher(directoryReader);
		}
		return fIndexSearcher;
	}

	private synchronized DirectoryReader getDirectoryReader() throws IOException {
		if (fIndexReader == null) {
			fIndexReader = DirectoryReader.open(Adaptables.getAdapter(fSearchIndex.getDocumentWriter(), IndexWriter.class));
		} else {
			DirectoryReader newIndexReader = DirectoryReader.openIfChanged(fIndexReader);
			if (newIndexReader != null) {
				retire(fIndexReader);
				fIndexReader = newIndexReader;
			}
		}
		closeExpiredRetired();
		return fIndexReader;
	}

	public synchronized DirectoryTaxonomyReader getDirectoryTaxonomyReader() throws IOException {
		if (fTaxonomyReader == null) {
			fTaxonomyReader = new DirectoryTaxonomyReader(Adaptables.getAdapter(fSearchIndex.getDocumentWriter(), DirectoryTaxonomyWriter.class));
		} else {
			DirectoryTaxonomyReader newTaxonomyReader = DirectoryTaxonomyReader.openIfChanged(fTaxonomyReader);
			if (newTaxonomyReader != null) {
				retire(fTaxonomyReader);
				fTaxonomyReader = newTaxonomyReader;
			}
		}
		closeExpiredRetired();
		return fTaxonomyReader;
	}

	/** Park a superseded reader instead of closing it under a possible in-flight search. */
	private void retire(Closeable reader) {
		fRetired.add(new Retired(reader, System.currentTimeMillis()));
	}

	/** Close the parked readers whose linger has elapsed (no search can still be using them). */
	private void closeExpiredRetired() {
		long cutoff = System.currentTimeMillis() - RETIRE_LINGER_MILLIS;
		for (Iterator<Retired> i = fRetired.iterator(); i.hasNext();) {
			Retired retired = i.next();
			if (retired.retiredAt > cutoff) {
				continue;
			}
			try {
				retired.reader.close();
			} catch (Throwable ignore) {}
			i.remove();
		}
	}

	public Analyzer getAnalyzer() throws IOException {
		if (fAnalyzer == null) {
			Analyzer fulltextAnalyzer = adaptTo(SearchIndexConfigurationImpl.class).getAnalyzer("fulltext@query");
			if (fulltextAnalyzer == null) {
				fulltextAnalyzer = new StandardAnalyzer();
			}
			fAnalyzer = fCloser.register(new PerFieldAnalyzerWrapper(new KeywordAnalyzer(),
					AdaptableMap.<String, Analyzer>newBuilder().put("_fulltext", fulltextAnalyzer).build()));
		}
		return fAnalyzer;
	}

	@Override
	public synchronized void close() throws IOException {
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

		for (Retired retired : fRetired) {
			try {
				retired.reader.close();
			} catch (Throwable ignore) {}
		}
		fRetired.clear();

		fCloser.close();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fSearchIndex, adapterType);
	}

}

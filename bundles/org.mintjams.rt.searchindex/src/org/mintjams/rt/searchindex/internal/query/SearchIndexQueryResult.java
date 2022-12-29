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

package org.mintjams.rt.searchindex.internal.query;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.mintjams.rt.searchindex.internal.SearchIndexImpl;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

public class SearchIndexQueryResult implements SearchIndex.QueryResult, Adaptable {

	private final SearchIndexQuery fQuery;
	private int fRow;
	private TopDocs fTopDocs;
	private final Map<String, org.apache.lucene.facet.FacetResult> fFacetResults = new HashMap<>();
	private final List<String> fSuggestions = new ArrayList<>();

	private final Iterator<Row> fIterator = new Iterator<Row>() {
		@Override
		public Row next() {
			if (fTopDocs == null) {
				throw new NoSuchElementException("No more query results available.");
			}

			try {
				ScoreDoc socreDoc = fTopDocs.scoreDocs[fRow - 1];
				Document doc = getIndexSearcher().doc(socreDoc.doc);
				RowImpl row = new RowImpl(doc.get("_identifier"), socreDoc.score, doc.getFields());
				fRow++;
				return row;
			} catch (IOException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}

		@Override
		public boolean hasNext() {
			if (fTopDocs == null) {
				return false;
			}

			return (fRow <= fTopDocs.scoreDocs.length);
		}
	};

	private final FacetResult fFacetResult = new FacetResult() {
		@Override
		public String[] getDimensions() {
			return fFacetResults.keySet().toArray(String[]::new);
		}

		@Override
		public Facet getFacet(String dimension) {
			if (!fFacetResults.containsKey(dimension)) {
				throw new IllegalArgumentException(dimension);
			}

			return new FacetImpl(fFacetResults.get(dimension));
		}
	};

	private final SuggestionResult fSuggestionResult = new SuggestionResult() {
		@Override
		public String[] getSuggestions() {
			return fSuggestions.toArray(String[]::new);
		}
	};

	private SearchIndexQueryResult(SearchIndexQuery query) throws IOException {
		fQuery = query;
		fRow = 1;
	}

	public static SearchIndexQueryResult create(SearchIndexQuery query) throws IOException {
		return new SearchIndexQueryResult(query);
	}

	public SearchIndexQueryResult with(TopDocs topDocs) {
		fTopDocs = topDocs;
		return this;
	}

	public SearchIndexQueryResult addFacetResult(org.apache.lucene.facet.FacetResult facetResult) {
		if (facetResult != null) {
			fFacetResults.put(facetResult.dim, facetResult);
		}
		return this;
	}

	public SearchIndexQueryResult addSuggestion(String suggestion) {
		if (suggestion != null) {
			fSuggestions.add(suggestion);
		}
		return this;
	}

	@Override
	public Iterator<Row> iterator() {
		return fIterator;
	}

	@Override
	public Row[] toArray() throws IOException {
		if (fTopDocs == null) {
			return new Row[0];
		}

		try {
			return Arrays.stream(fTopDocs.scoreDocs).map(scoreDoc -> {
				try {
					Document doc = getIndexSearcher().doc(scoreDoc.doc);
					return new RowImpl(doc.get("_identifier"), scoreDoc.score, doc.getFields());
				} catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}).toArray(Row[]::new);
		} catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
	}

	@Override
	public int getSize() {
		if (fTopDocs == null) {
			return 0;
		}

		return fTopDocs.scoreDocs.length;
	}

	@Override
	public int getTotalHits() {
		if (fTopDocs == null) {
			return 0;
		}

		return Math.toIntExact(fTopDocs.totalHits.value);
	}

	@Override
	public FacetResult getFacetResult() {
		return fFacetResult;
	}

	@Override
	public SuggestionResult getSuggestionResult() {
		return fSuggestionResult;
	}

	private IndexSearcher _indexSearcher;
	private IndexSearcher getIndexSearcher() throws IOException {
		if (_indexSearcher == null) {
			_indexSearcher = adaptTo(SearchIndexImpl.class).getDocumentReader().getIndexSearcher();
		}
		return _indexSearcher;
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fQuery, adapterType);
	}

	private static class RowImpl implements Row {
		private final String fIdentifier;
		private final double fScore;
		private final Map<String, List<String>> fFields = new HashMap<>();

		public RowImpl(String identifier, double score, List<IndexableField> fields) {
			fIdentifier = identifier;
			fScore = score;
			for (IndexableField field : fields) {
				List<String> values = fFields.get(field.name());
				if (values == null) {
					values = new ArrayList<>();
					fFields.put(field.name(), values);
				}
				values.add(field.stringValue());
			}
		}

		@Override
		public String getIdentifier() {
			return fIdentifier;
		}

		@Override
		public double getScore() {
			return fScore;
		}

		@Override
		public List<String> getProperty(String name) {
			return fFields.get(name);
		}
	}

	private static class FacetImpl implements FacetResult.Facet {
		private final String fDimension;
		private final Map<String, Integer> fLabelAndValues = new LinkedHashMap<>();

		public FacetImpl(org.apache.lucene.facet.FacetResult luceneFacetResult) {
			fDimension = luceneFacetResult.dim;
			for (org.apache.lucene.facet.LabelAndValue e : luceneFacetResult.labelValues) {
				fLabelAndValues.put(e.label, e.value.intValue());
			}
		}

		@Override
		public String getDimension() {
			return fDimension;
		}

		@Override
		public String[] getLabels() {
			return fLabelAndValues.keySet().toArray(String[]::new);
		}

		@Override
		public int getValue(String label) {
			return fLabelAndValues.get(label);
		}

		@Override
		public Map<String, Integer> getValues() {
			return Collections.unmodifiableMap(fLabelAndValues);
		}
	}

}

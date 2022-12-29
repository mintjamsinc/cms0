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

package org.mintjams.script.resource.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.jcr.NodeIterator;

import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.ResourceImpl;
import org.mintjams.script.resource.Session;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

public class Query implements Adaptable {

	private final javax.jcr.query.Query fJcrQuery;
	private final QueryManager fQueryManager;
	private long fLimit = Long.MAX_VALUE;

	protected Query(String statement, String language, QueryManager queryManager) throws ResourceException {
		fQueryManager = queryManager;
		try {
			fJcrQuery = fQueryManager.adaptTo(javax.jcr.Session.class).getWorkspace().getQueryManager().createQuery(statement, language);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Query offset(long offset) {
		fJcrQuery.setOffset(offset);
		return this;
	}

	public Query limit(long limit) {
		fJcrQuery.setLimit(limit + 1);
		fLimit = limit;
		return this;
	}

	public QueryResult execute() throws ResourceException {
		try {
			return new QueryResult();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fJcrQuery, adapterType);
	}

	public class QueryResult {
		private final javax.jcr.query.QueryResult fJcrQueryResult;
		private final long fElapsed;
		private List<Resource> fResources;
		private boolean fHasMore;
		private long fTotal;

		private QueryResult() throws Exception {
			long start = new java.util.Date().getTime();
			fJcrQueryResult = fJcrQuery.execute();
			long end = new java.util.Date().getTime();
			fElapsed = end - start;
		}

		public long getTotal() {
			if (fResources == null) {
				getResources();
			}
			return fTotal;
		}

		public boolean hasMore() {
			if (fResources == null) {
				getResources();
			}
			return fHasMore;
		}

		public long getElapsed() {
			return fElapsed;
		}

		public Resource[] getResources() {
			try {
				if (fResources == null) {
					NodeIterator it = fJcrQueryResult.getNodes();
					fTotal = it.getSize();
					fResources = new ArrayList<>();
					while (it.hasNext() && fResources.size() < fLimit) {
						fResources.add(new ResourceImpl(it.nextNode(), fQueryManager.adaptTo(Session.class)));
					}
					fHasMore = it.hasNext();
				}
				return fResources.toArray(Resource[]::new);
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}

		public FacetResult getFacetResult() {
			try {
				return new FacetResult();
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}

		public SuggestionResult getSuggestionResult() {
			try {
				return new SuggestionResult();
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
	}

	public class FacetResult {
		private final SearchIndex.QueryResult.FacetResult fFacetResult;

		private FacetResult() throws IOException {
			fFacetResult = adaptTo(SearchIndex.class).createQuery(fJcrQuery.getStatement(), "jcr:xpath")
					.setOffset(0).setLimit(0).execute().getFacetResult();
		}

		public String[] getDimensions() {
			return fFacetResult.getDimensions();
		}

		public Facet getFacet(String dimension) {
			return new Facet(fFacetResult.getFacet(dimension));
		}

		public class Facet {
			private final SearchIndex.QueryResult.FacetResult.Facet fFacet;

			private Facet(SearchIndex.QueryResult.FacetResult.Facet facet) {
				fFacet = facet;
			}

			public String[] getLabels() {
				return fFacet.getLabels();
			}

			public int getValue(String label) {
				return fFacet.getValue(label);
			}

			public Map<String, Integer> getValues() {
				return fFacet.getValues();
			}
		}
	}

	public class SuggestionResult {
		private final SearchIndex.QueryResult.SuggestionResult fSuggestionResult;

		private SuggestionResult() throws IOException {
			fSuggestionResult = adaptTo(SearchIndex.class).createQuery(fJcrQuery.getStatement(), "jcr:xpath")
					.setOffset(0).setLimit(0).execute().getSuggestionResult();
		}

		public String[] getSuggestions() {
			return fSuggestionResult.getSuggestions();
		}
	}

}

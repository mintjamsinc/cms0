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
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.lucene.document.Document;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.range.DoubleRange;
import org.apache.lucene.facet.range.DoubleRangeFacetCounts;
import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.facet.range.LongRangeFacetCounts;
import org.apache.lucene.facet.range.Range;
import org.apache.lucene.facet.taxonomy.FastTaxonomyFacetCounts;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.standard.StandardQueryParser;
import org.apache.lucene.queryparser.flexible.standard.config.PointsConfig;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.TopFieldCollector;
import org.mintjams.rt.searchindex.internal.Activator;
import org.mintjams.rt.searchindex.internal.SearchIndexImpl;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.searchindex.query.InvalidQuerySyntaxException;
import org.mintjams.searchindex.query.QueryStatements;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class JcrXPathQuery extends SearchIndexQuery {

	public static final String JCR_ROOT_PATH = "/jcr:root";
	private static final Map<String, String> JCR_FIELDS = AdaptableMap.<String, String>newBuilder()
			.put("jcr:identifier", "_identifier")
			.put("jcr:path", "_path")
			.put("jcr:name", "_name")
			.put("jcr:depth", "_depth")
			.put("jcr:mimeType", "_mimeType")
			.put("jcr:encoding", "_encoding")
			.put("jcr:contentLength", "_size")
			.put("jcr:created", "_created")
			.put("jcr:createdBy", "_createdBy")
			.put("jcr:lastModified", "_lastModified")
			.put("jcr:lastModifiedBy", "_lastModifiedBy")
			.build();

	private final String fStatement;
	private List<Clause> fClauses;
	private String fCompiled;
	private OrderByClause fOrderByClause;
	private FacetAccumulateClause fFacetAccumulateClause;
	private AutoCompleteClause fAutoCompleteClause;

	public JcrXPathQuery(String statement, SearchIndexImpl searchIndex) {
		super(searchIndex);

		if (Strings.isEmpty(statement)) {
			throw new InvalidQuerySyntaxException("Query statement must not be null or empty.");
		}

		fStatement = statement.trim();
	}

	@Override
	public SearchIndex.QueryResult execute() throws IOException {
		long startTime = System.currentTimeMillis();
		SearchIndexQueryResult result = SearchIndexQueryResult.create(this);
		try {
			IndexSearcher documentSearcher = fSearchIndex.getDocumentReader().getIndexSearcher();
			getCompiled(); // compile

			Query luceneQuery = getDocumentQuery();

			if (getLimit() > 0) {
				Sort luceneSort = getSort();
				FieldDoc after = null;
				if (getOffset() > 0) {
					int numForwards = getOffset();
					while (numForwards > 0) {
						int numHits = 200;
						if (numHits > numForwards) {
							numHits = numForwards;
						}

						TopFieldCollector collector;
						if (after == null) {
							collector = TopFieldCollector.create(luceneSort, numHits, 2000);
						} else {
							collector = TopFieldCollector.create(luceneSort, numHits, after, 2000);
						}

						documentSearcher.search(luceneQuery, collector);
						ScoreDoc[] scoreDocs = collector.topDocs().scoreDocs;
						numForwards -= scoreDocs.length;
						after = (FieldDoc) scoreDocs[scoreDocs.length - 1];
						if (scoreDocs.length < numHits) {
							break;
						}
					}
				}

				int numHits = 100;
				if (numHits > getLimit()) {
					numHits = getLimit();
				}
				TopFieldCollector collector;
				if (after == null) {
					collector = TopFieldCollector.create(luceneSort, numHits, 2000);
				} else {
					collector = TopFieldCollector.create(luceneSort, numHits, after, 2000);
				}
				documentSearcher.search(luceneQuery, collector);
				result.with(collector.topDocs());
			}

			List<FacetAccumulateClause.Facet> facetList = listFacets();
			if (!facetList.isEmpty()) {
				FacetsCollector collector = documentSearcher.search(luceneQuery, new FacetsCollectorManager());
				TaxonomyReader taxonomyReader = fSearchIndex.getDocumentReader().getDirectoryTaxonomyReader();
				for (FacetAccumulateClause.Facet facet : facetList) {
					if (!facet.isRange()) {
						FacetAccumulateClause.TopFacetParams params = facet.getTopFacetParams();
						Facets facets = new FastTaxonomyFacetCounts(taxonomyReader, fSearchIndex.getFacetsConfig(), collector);
						result.addFacetResult(facets.getTopChildren(params.getLimit(), params.getFieldName()));
						continue;
					}

					List<Range> ranges = new ArrayList<>();
					for (FacetAccumulateClause.RangeFacetParams params : facet.listRangeFacetParams()) {
						if (params.getFieldType().equals(BigDecimal.class)) {
							ranges.add(new DoubleRange(params.getLabel(),
									((BigDecimal) params.getMinValue()).doubleValue(), params.isMinInclusive(),
									((BigDecimal) params.getMaxValue()).doubleValue(), params.isMaxInclusive()));
							continue;
						}

						if (params.getFieldType().equals(Long.class)) {
							ranges.add(new LongRange(params.getLabel(),
									((Long) params.getMinValue()).longValue(), params.isMinInclusive(),
									((Long) params.getMaxValue()).longValue(), params.isMaxInclusive()));
							continue;
						}
					}
					if (ranges.get(0).getClass().equals(DoubleRange.class)) {
						Facets facets = new DoubleRangeFacetCounts(facet.getFieldName(), collector, ranges.toArray(DoubleRange[]::new));
						result.addFacetResult(facets.getAllChildren(facet.getFieldName()));
					} else {
						Facets facets = new LongRangeFacetCounts(facet.getFieldName(), collector, ranges.toArray(LongRange[]::new));
						result.addFacetResult(facets.getAllChildren(facet.getFieldName()));
					}
				}
			}

			if (hasAutoCompleteQuery()) {
				IndexSearcher suggestionSearcher = fSearchIndex.getSuggestionReader().getIndexSearcher();
				List<String> suggestions = new ArrayList<>();
				int numHits = 10;
				for (AutoComplete autoComplete = getAutoComplete(suggestions);
						suggestions.size() < autoComplete.getLimit();
						autoComplete = getAutoComplete(suggestions)) {
					Sort luceneSort = autoComplete.getSort();
					TopFieldCollector collector = TopFieldCollector.create(luceneSort, numHits, numHits);

					suggestionSearcher.search(autoComplete.getQuery(), collector);
					ScoreDoc[] scoreDocs = collector.topDocs().scoreDocs;
					if (scoreDocs.length == 0) {
						break;
					}

					for (ScoreDoc socreDoc : scoreDocs) {
						Document doc = suggestionSearcher.doc(socreDoc.doc);
						String suggestion = doc.get("_suggestion");
						result.addSuggestion(suggestion);
						suggestions.add(suggestion);
						if (suggestions.size() >= autoComplete.getLimit()) {
							break;
						}
					}

					if (scoreDocs.length < numHits) {
						break;
					}
				}
			}

			Activator.getLogger(getClass()).debug("Execute jcr:xpath query (" + (System.currentTimeMillis() - startTime) + "ms): " + fStatement);
		} catch (IndexNotFoundException ignore) {}
		return result;
	}

	private org.apache.lucene.search.Query getDocumentQuery() throws IOException {
		try {
			StandardQueryParser parser = new StandardQueryParser(fSearchIndex.getDocumentReader().getAnalyzer());
			parser.setPointsConfigMap(getPointsConfigMap());
			parser.setAllowLeadingWildcard(true);
			return parser.parse(getCompiled(), "_identifier");
		} catch (QueryNodeException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private String getCompiled() {
		if (fCompiled == null) {
			if (fClauses == null) {
				fClauses = new ArrayList<>();
				for (String stmt = fStatement; !Strings.isBlank(stmt);) {
					boolean matches = false;
					for (ClauseExtractor clause : ClauseExtractor.values()) {
						if (clause.match(stmt)) {
							matches = true;
							stmt = clause.extract(stmt, fClauses, this);
							break;
						}
					}
					if (matches) {
						continue;
					}

					throw new InvalidQuerySyntaxException(fStatement);
				}
			}

			StringBuilder stmt = new StringBuilder();
			stmt.append(getAuthorizablesStatement());
			for (Clause e : fClauses) {
				String q = e.compile();
				if (e instanceof OrderByClause) {
					fOrderByClause = (OrderByClause) e;
					continue;
				}
				if (e instanceof FacetAccumulateClause) {
					fFacetAccumulateClause = (FacetAccumulateClause) e;
					continue;
				}
				if (e instanceof AutoCompleteClause) {
					fAutoCompleteClause = (AutoCompleteClause) e;
					continue;
				}

				if (Strings.isEmpty(q)) {
					continue;
				}

				if (stmt.length() > 0) {
					stmt.append(" AND ");
				}
				stmt.append(q);
			}
			fCompiled = stmt.toString();
		}
		return fCompiled;
	}

        private Map<String, PointsConfig> getPointsConfigMap() {
                Map<String, PointsConfig> map = new HashMap<>();
                for (Clause e : fClauses) {
                        if (e instanceof PathClause) {
                                map.putAll(((PathClause) e).getPointsConfigMap());
                                continue;
                        }
                        if (e instanceof ConstraintClause) {
                                map.putAll(((ConstraintClause) e).getPointsConfigMap());
                        }
                }
                return map;
        }

	private Sort getSort() {
		if (fOrderByClause != null) {
			return fOrderByClause.getSort();
		}
		return new Sort(new SortField("_path", SortField.Type.STRING));
	}

	private List<FacetAccumulateClause.Facet> listFacets() {
		if (fFacetAccumulateClause != null) {
			return fFacetAccumulateClause.listFacets();
		}
		return new ArrayList<>();
	}

	private boolean hasAutoCompleteQuery() {
		return (fAutoCompleteClause != null);
	}

	private AutoComplete getAutoComplete(List<String> excludes) throws IOException {
		StringBuilder stmt = new StringBuilder();
		stmt.append(getCompiled());

		for (String s : excludes) {
			if (stmt.length() > 0) {
				stmt.append(" AND ");
			}

			stmt.append("NOT(").append(QueryStatements.escape("_suggestion")).append(":").append(QueryStatements.escape(s)).append(")");
		}

		String[] s = fAutoCompleteClause.getText().split("\\s");
		for (int i = 0; i < s.length; i++) {
			if (stmt.length() > 0) {
				stmt.append(" AND ");
			}

			stmt.append(QueryStatements.escape("_completion")).append(":").append(QueryStatements.escape(s[i]));
			if (i == s.length - 1) {
				stmt.append("*");
			}
		}

		StandardQueryParser parser = new StandardQueryParser(fSearchIndex.getSuggestionReader().getAnalyzer());
		parser.setPointsConfigMap(getPointsConfigMap());
		parser.setAllowLeadingWildcard(true);
		try {
			return new AutoComplete().setQuery(parser.parse(stmt.toString(), "_suggestion"))
					.setLimit(fAutoCompleteClause.getLimit()).setSort(fAutoCompleteClause.getSort());
		} catch (QueryNodeException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fSearchIndex, adapterType);
	}

	private enum ClauseExtractor {
		PATH("/") {
			@Override
			protected String extract(String stmt, List<Clause> clauses, Adaptable adaptable) {
				char[] chars = stmt.toCharArray();
				int endIndex = -1;
				for (int i = 0; i < chars.length; i++) {
					char c = chars[i];

					if (c == '"' || c == '\'') {
						char quart = c;
						for (i++;; i++) {
							c = chars[i];
							if (c == quart) {
								break;
							}
							if (c == '\\') {
								i++;
								continue;
							}
						}
						continue;
					}

					if (isNextClause(stmt.substring(i))) {
						endIndex = i;
						break;
					}
				}

				if (endIndex == -1) {
					clauses.add(new PathClause(stmt.substring(0).trim(), adaptable));
					return "";
				}

				clauses.add(new PathClause(stmt.substring(0, endIndex).trim(), adaptable));
				return stmt.substring(endIndex).trim();
			}
		},
		CONSTRAINT("[") {
			@Override
			protected String extract(String stmt, List<Clause> clauses, Adaptable adaptable) {
				char[] chars = stmt.toCharArray();
				int endIndex = -1;
				for (int i = 0; i < chars.length; i++) {
					char c = chars[i];

					if (c == '"' || c == '\'') {
						char quart = c;
						for (i++;; i++) {
							c = chars[i];
							if (c == quart) {
								break;
							}
							if (c == '\\') {
								i++;
								continue;
							}
						}
						continue;
					}

					if (c == ']') {
						endIndex = i + 1;
						break;
					}
				}

				if (endIndex == -1) {
					clauses.add(new ConstraintClause(stmt.substring(0).trim(), adaptable));
					return "";
				}

				clauses.add(new ConstraintClause(stmt.substring(0, endIndex).trim(), adaptable));
				return stmt.substring(endIndex).trim();
			}
		},
		ORDER_BY("order by") {
			@Override
			protected boolean match(String stmt) {
				if (Strings.isEmpty(stmt)) {
					return false;
				}
				return stmt.toLowerCase().startsWith(fClause) && (" \t\r\n".indexOf(stmt.charAt(fClause.length())) != -1);
			}

			@Override
			protected String extract(String stmt, List<Clause> clauses, Adaptable adaptable) {
				char[] chars = stmt.toCharArray();
				int endIndex = -1;
				for (int i = 0; i < chars.length; i++) {
					char c = chars[i];

					if (c == '"' || c == '\'') {
						char quart = c;
						for (i++;; i++) {
							c = chars[i];
							if (c == quart) {
								break;
							}
							if (c == '\\') {
								i++;
								continue;
							}
						}
						continue;
					}

					if (String.valueOf(c).trim().isEmpty() && isNextClause(stmt.substring(i + 1))) {
						endIndex = i + 1;
						break;
					}
				}

				if (endIndex == -1) {
					clauses.add(new OrderByClause(stmt.substring(0).trim(), adaptable));
					return "";
				}

				clauses.add(new OrderByClause(stmt.substring(0, endIndex).trim(), adaptable));
				return stmt.substring(endIndex).trim();
			}
		},
		FACET_ACCUMULATE("facet accumulate") {
			@Override
			protected boolean match(String stmt) {
				if (Strings.isEmpty(stmt)) {
					return false;
				}
				return stmt.toLowerCase().startsWith(fClause) && (" \t\r\n".indexOf(stmt.charAt(fClause.length())) != -1);
			}

			@Override
			protected String extract(String stmt, List<Clause> clauses, Adaptable adaptable) {
				char[] chars = stmt.toCharArray();
				int endIndex = -1;
				for (int i = 0; i < chars.length; i++) {
					char c = chars[i];

					if (c == '"' || c == '\'') {
						char quart = c;
						for (i++;; i++) {
							c = chars[i];
							if (c == quart) {
								break;
							}
							if (c == '\\') {
								i++;
								continue;
							}
						}
						continue;
					}

					if (String.valueOf(c).trim().isEmpty() && isNextClause(stmt.substring(i + 1))) {
						endIndex = i + 1;
						break;
					}
				}

				if (endIndex == -1) {
					clauses.add(new FacetAccumulateClause(stmt.substring(0).trim(), adaptable));
					return "";
				}

				clauses.add(new FacetAccumulateClause(stmt.substring(0, endIndex).trim(), adaptable));
				return stmt.substring(endIndex).trim();
			}
		},
		AUTO_COMPLETE("auto complete") {
			@Override
			protected boolean match(String stmt) {
				if (Strings.isEmpty(stmt)) {
					return false;
				}
				return stmt.toLowerCase().startsWith(fClause) && (" \t\r\n".indexOf(stmt.charAt(fClause.length())) != -1);
			}

			@Override
			protected String extract(String stmt, List<Clause> clauses, Adaptable adaptable) {
				char[] chars = stmt.toCharArray();
				int endIndex = -1;
				for (int i = 0; i < chars.length; i++) {
					char c = chars[i];

					if (c == '"' || c == '\'') {
						char quart = c;
						for (i++;; i++) {
							c = chars[i];
							if (c == quart) {
								break;
							}
							if (c == '\\') {
								i++;
								continue;
							}
						}
						continue;
					}

					if (String.valueOf(c).trim().isEmpty() && isNextClause(stmt.substring(i + 1))) {
						endIndex = i + 1;
						break;
					}
				}

				if (endIndex == -1) {
					clauses.add(new AutoCompleteClause(stmt.substring(0).trim(), adaptable));
					return "";
				}

				clauses.add(new AutoCompleteClause(stmt.substring(0, endIndex).trim(), adaptable));
				return stmt.substring(endIndex).trim();
			}
		};

		protected final String fClause;

		private ClauseExtractor(String clause) {
			fClause = clause;
		}

		protected String getClause() {
			return fClause;
		}

		protected boolean match(String stmt) {
			if (Strings.isEmpty(stmt)) {
				return false;
			}
			if (stmt.length() < fClause.length()) {
				return false;
			}
			return stmt.substring(0, fClause.length()).equalsIgnoreCase(fClause);
		}

		protected boolean isNextClause(String stmt) {
			for (ClauseExtractor clause : ClauseExtractor.values()) {
				if (clause.compareTo(this) <= 0) {
					continue;
				}
				if (clause.match(stmt)) {
					return true;
				}
			}
			return false;
		}

		protected abstract String extract(String stmt, List<Clause> clauses, Adaptable adaptable);
	}

	private static abstract class Clause {
		protected final String fStatement;
		protected final Adaptable fAdaptable;

		protected Clause(String statement, Adaptable adaptable) {
			fStatement = statement.trim();
			fAdaptable = adaptable;
		}

		protected abstract String compile();

		protected String escape(String value, String... excludes) {
			return QueryStatements.escape(value, excludes);
		}

		protected String getFieldName(String field) {
			field = field.trim();
			if (field.startsWith("@")) {
				field = field.substring(1);
			} else if (field.startsWith("jcr:")) {
				if (JCR_FIELDS.containsKey(field)) {
					field = JCR_FIELDS.get(field);
				} else {
					throw new InvalidQuerySyntaxException(fStatement);
				}
			}
			return field;
		}

		protected List<String> parseArguments(String argsString) {
			List<String> args = new ArrayList<>();
			StringBuilder argString = new StringBuilder();
			char[] a = argsString.toCharArray();
			for (int i = 0; i < a.length; i++) {
				char c = a[i];

				if (c == '"' || c == '\'') {
					argString.append(c);
					char quart = c;
					for (i++;; i++) {
						c = a[i];
						argString.append(c);
						if (c == quart) {
							break;
						}
						if (c == '\\') {
							argString.append('\\');
							i++;
							continue;
						}
					}
					continue;
				}

				if (c == ',') {
					args.add(argString.toString().trim());
					argString = new StringBuilder();
					continue;
				}

				argString.append(c);
			}
			if (argString.length() > 0) {
				args.add(argString.toString().trim());
			}
			return args;
		}

		protected Object toJavaValue(String value) {
			if (value.startsWith("xs:decimal(") || value.startsWith("xs:float(") || value.startsWith("xs:double(")) {
				if (!value.endsWith(")")) {
					throw new InvalidQuerySyntaxException(fStatement);
				}

				value = value.substring(value.indexOf("(") + 1, value.length() - 1).trim();

				if (value.startsWith("\"") && value.endsWith("\"")) {
					value = value.substring(1, value.length() - 1).replaceAll("\\\\\"", "\"");
				} else if (value.startsWith("'") && value.endsWith("'")) {
					value = value.substring(1, value.length() - 1).replaceAll("\\\\'", "'");
				} else {
					throw new InvalidQuerySyntaxException(fStatement);
				}

				BigDecimal decimalValue;
				try {
					decimalValue = AdaptableList.<Object>newBuilder().add(value).build().adapt(0, BigDecimal.class).getValue();
				} catch (Throwable ex) {
					throw Cause.create(ex).wrap(InvalidQuerySyntaxException.class);
				}

				return decimalValue;
			}

			if (value.startsWith("xs:dateTime(") || value.startsWith("xs:date(")) {
				if (!value.endsWith(")")) {
					throw new InvalidQuerySyntaxException(fStatement);
				}

				value = value.substring(value.indexOf("(") + 1, value.length() - 1).trim();

				if (value.startsWith("\"") && value.endsWith("\"")) {
					value = value.substring(1, value.length() - 1).replaceAll("\\\\\"", "\"");
				} else if (value.startsWith("'") && value.endsWith("'")) {
					value = value.substring(1, value.length() - 1).replaceAll("\\\\'", "'");
				} else {
					throw new InvalidQuerySyntaxException(fStatement);
				}

				java.util.Date dateValue;
				try {
					dateValue = AdaptableList.<Object>newBuilder().add(value).build().adapt(0, java.util.Date.class).getValue();
				} catch (Throwable ex) {
					throw Cause.create(ex).wrap(InvalidQuerySyntaxException.class);
				}

				return dateValue;
			}

			if (value.startsWith("xs:boolean(")) {
				if (!value.endsWith(")")) {
					throw new InvalidQuerySyntaxException(fStatement);
				}

				value = value.substring(value.indexOf("(") + 1, value.length() - 1).trim();

				if (value.startsWith("\"") && value.endsWith("\"")) {
					value = value.substring(1, value.length() - 1).replaceAll("\\\\\"", "\"");
				} else if (value.startsWith("'") && value.endsWith("'")) {
					value = value.substring(1, value.length() - 1).replaceAll("\\\\'", "'");
				} else {
					throw new InvalidQuerySyntaxException(fStatement);
				}

				value = value.trim();
				if (value.equals("true") || value.equals("1")) {
					return Boolean.TRUE;
				} else if (value.equals("false") || value.equals("0")) {
					return Boolean.FALSE;
				}

				throw new InvalidQuerySyntaxException(fStatement);
			}

			if (value.matches("^true(.*)$")) {
				return Boolean.TRUE;
			}

			if (value.matches("^false(.*)$")) {
				return Boolean.FALSE;
			}

			if (value.startsWith("\"") || value.startsWith("'")) {
				if (value.startsWith("\"") && value.endsWith("\"")) {
					return value.substring(1, value.length() - 1).replaceAll("\\\\\"", "\"");
				} else if (value.startsWith("'") && value.endsWith("'")) {
					return value.substring(1, value.length() - 1).replaceAll("\\\\'", "'");
				}
				throw new InvalidQuerySyntaxException(fStatement);
			}

			if (value.matches("^.+\\(.*\\)$")) {
				throw new InvalidQuerySyntaxException(fStatement);
			}

			try {
				return new BigDecimal(value);
			} catch (Throwable ignore) {}

			throw new InvalidQuerySyntaxException(fStatement);
		}

		@Override
		public String toString() {
			return fStatement;
		}
	}

        private static class PathClause extends Clause {
                private final Map<String, PointsConfig> fPointsConfigMap = new HashMap<>();

                protected PathClause(String statement, Adaptable adaptable) {
                        super(statement, adaptable);
                }

		@Override
		protected String compile() {
			String query = fStatement;
			String filename = null;
			String primaryType = null;
			int depth = -1;

			if (query.startsWith(JCR_ROOT_PATH + "/")) {
				query = query.substring(JCR_ROOT_PATH.length());
			}

			if (query.indexOf("///") != -1) {
				throw new InvalidQuerySyntaxException(fStatement);
			}
			for (int i = query.indexOf("//*/"); i != -1; i = query.indexOf("//*/")) {
				query = query.substring(0, i) + "//" + query.substring(i + 4);
			}
			for (int i = query.indexOf("/*//"); i != -1; i = query.indexOf("/*//")) {
				query = query.substring(0, i) + "//" + query.substring(i + 4);
			}
			for (int i = query.indexOf("///"); i != -1; i = query.indexOf("///")) {
				query = query.substring(0, i) + "//" + query.substring(i + 3);
			}
			if (query.endsWith("///*")) {
				query = query.substring(0, query.length() - 2) + "*";
			}

			Matcher regex = Pattern.compile("\\/element\\(.+\\)$").matcher(query);
			if (regex.find()) {
				String s = regex.group();
				s = s.substring(s.indexOf("(") + 1, s.indexOf(")"));
				if (s.trim().equals("*")) {
					query = regex.replaceFirst("/*");
				} else if (s.indexOf(",") == -1) {
					if (query.indexOf("//element(") != -1) {
						query = regex.replaceFirst("/*");
						filename = s.trim();
					} else {
						query = regex.replaceFirst("/" + s.trim());
					}
				} else {
					String[] args = s.split(",");
					if (query.indexOf("//element(") != -1) {
						query = regex.replaceFirst("/*");
						filename = args[0].trim();
					} else {
						query = regex.replaceFirst("/" + args[0].trim());
					}
					primaryType = args[1].trim();
				}
			}

			if (query.endsWith("/**")) {
				query = query.substring(0, query.length() - 1);
			}

			if (query.indexOf("/*/") != -1 || (query.endsWith("/*") && !query.endsWith("//*"))) {
				if (filename == null) {
					depth = query.split("\\/").length - 1;
				}
			}

			StringBuilder buf = new StringBuilder();
			if (Strings.isNotEmpty(query) && !query.equals("/*") && !query.equals("//*")) {
				if (query.startsWith("//")) {
					query = "*" + query.substring(1);
				}

				if (query.indexOf("//") != -1 && !query.endsWith("//*")) {
					String[] paths = query.split("\\/\\/");
					if (paths.length != 2) {
						throw new InvalidQuerySyntaxException(fStatement);
					}
					if (paths[1].indexOf("/") == -1) {
						paths[0] = "_path:" + toLucenePath(paths[0] + "/*");
						paths[1] = "_name:" + escape(paths[1]);
						buf.append("(").append(paths[0]).append(" AND ").append(paths[1]).append(")");
					} else {
						String path0 = paths[0] + "/" + paths[1];
						String path1 = paths[0] + "/*/" + paths[1];
						paths[0] = "_path:" + toLucenePath(path0);
						paths[1] = "_path:" + toLucenePath(path1);
						buf.append("(").append(paths[0]).append(" OR ").append(paths[1]).append(")");
					}
				} else {
					buf.append("_path:").append(toLucenePath(query));
				}
			}
			if (Strings.isNotEmpty(filename) && !filename.equals("*")) {
				if (buf.length() > 0) {
					buf.append(" AND ");
				}
				buf.append("_name:").append(escape(filename));
			}
			if (Strings.isNotEmpty(primaryType)) {
				if (buf.length() > 0) {
					buf.append(" AND ");
				}
				buf.append(escape("jcr:primaryType")).append(":").append(escape(primaryType));
			}
                        if (depth != -1) {
                                if (buf.length() > 0) {
                                        buf.append(" AND ");
                                }
                                buf.append("_depth").append(":").append("" + depth);
                                fPointsConfigMap.put("_depth", new PointsConfig(new DecimalFormat(), Long.class));
                        }
                        if (buf.length() > 0) {
                                buf.insert(0, "(");
                                buf.append(")");
                        }

			String stmt = buf.toString();
			if (Strings.isEmpty(stmt)) {
				stmt = "*:*";
			}
			return stmt;
		}

                private String toLucenePath(String path) {
                        StringBuilder buf = new StringBuilder();
                        String[] pathNames = path.split("\\/");
			for (int i = 0; i < pathNames.length; i++) {
				String e = pathNames[i];
				if (Strings.isEmpty(e)) {
					continue;
				}

				if (e.equals("*")) {
					if (i > 0) {
						buf.append("\\/");
					}
					buf.append("*");
					continue;
				}

                                buf.append("\\/").append(escape(e));
                        }
                        return buf.toString();
                }

                protected Map<String, PointsConfig> getPointsConfigMap() {
                        return fPointsConfigMap;
                }
        }

	private static class ConstraintClause extends Clause {
		private Map<String, PointsConfig> fPointsConfigMap = new HashMap<>();

		protected ConstraintClause(String statement, Adaptable adaptable) {
			super(statement, adaptable);
		}

		@Override
		protected String compile() {
			Condition cnd = new Condition(fStatement.substring(1, fStatement.length() - 1).trim(), fAdaptable);
			String stmt = cnd.compile();
			if (Strings.isEmpty(stmt)) {
				return null;
			}
			return stmt;
		}

		protected Map<String, PointsConfig> getPointsConfigMap() {
			return fPointsConfigMap;
		}

		private class Condition extends Clause {
			protected Condition(String statement, Adaptable adaptable) {
				super(statement, adaptable);
			}

			@Override
			protected String compile() {
				StringBuilder buf = new StringBuilder();
				for (String stmt = fStatement; !Strings.isBlank(stmt);) {
					if (stmt.startsWith("(")) {
						int nest = 0;
						char[] chars = stmt.toCharArray();
						int endIndex = -1;
						for (int i = 0; i < chars.length; i++) {
							char c = chars[i];

							if (c == '"' || c == '\'') {
								char quart = c;
								for (i++;; i++) {
									c = chars[i];
									if (c == quart) {
										break;
									}
									if (c == '\\') {
										i++;
										continue;
									}
								}
								continue;
							}

							if (c == '(') {
								nest++;
								continue;
							}

							if (c == ')') {
								if (nest > 0) {
									nest--;
								}
								if (nest == 0) {
									endIndex = i + 1;
									break;
								}
								continue;
							}
						}

						if (endIndex == -1) {
							throw new InvalidQuerySyntaxException(fStatement);
						}

						buf.append("(").append(new Condition(stmt.substring(1, endIndex - 1).trim(), fAdaptable).compile()).append(")");
						stmt = stmt.substring(endIndex).trim();
						continue;
					}

					if (stmt.length() > 4 && stmt.substring(0, 3).equalsIgnoreCase("and")
							&& (" \t\r\n(".indexOf(stmt.charAt(3)) != -1)) {
						buf.append(" AND ");
						stmt = stmt.substring(3).trim();
						continue;
					}

					if (stmt.length() > 3 && stmt.substring(0, 2).equalsIgnoreCase("or")
							&& (" \t\r\n(".indexOf(stmt.charAt(2)) != -1)) {
						buf.append(" OR ");
						stmt = stmt.substring(2).trim();
						continue;
					}

					if (stmt.length() > 4 && stmt.substring(0, 4).equalsIgnoreCase("not(")) {
						int nest = 0;
						char[] chars = stmt.toCharArray();
						int endIndex = -1;
						for (int i = 3; i < chars.length; i++) {
							char c = chars[i];

							if (c == '"' || c == '\'') {
								char quart = c;
								for (i++;; i++) {
									c = chars[i];
									if (c == quart) {
										break;
									}
									if (c == '\\') {
										i++;
										continue;
									}
								}
								continue;
							}

							if (c == '(') {
								nest++;
								continue;
							}

							if (c == ')') {
								if (nest > 0) {
									nest--;
								}
								if (nest == 0) {
									endIndex = i + 1;
									break;
								}
								continue;
							}
						}

						if (endIndex == -1) {
							throw new InvalidQuerySyntaxException(fStatement);
						}

						buf.append("NOT(").append(new Condition(stmt.substring(4, endIndex - 1).trim(), fAdaptable).compile()).append(")");
						stmt = stmt.substring(endIndex).trim();
						continue;
					}

					{
						char[] chars = stmt.toCharArray();
						int endIndex = -1;
						for (int i = 0; i < chars.length; i++) {
							char c = chars[i];

							if (c == '"' || c == '\'') {
								char quart = c;
								for (i++;; i++) {
									c = chars[i];
									if (c == quart) {
										break;
									}
									if (c == '\\') {
										i++;
										continue;
									}
								}
								continue;
							}

							if (i < chars.length - 5 && (" \t\r\n)".indexOf(stmt.charAt(i)) != -1)
									&& stmt.substring(i + 1, i + 4).equalsIgnoreCase("and")
									&& (" \t\r\n(".indexOf(stmt.charAt(i + 4)) != -1)) {
								endIndex = i + 1;
								break;
							}
							if (i < chars.length - 4 && (" \t\r\n)".indexOf(stmt.charAt(i)) != -1)
									&& stmt.substring(i + 1, i + 3).equalsIgnoreCase("or")
									&& (" \t\r\n(".indexOf(stmt.charAt(i + 3)) != -1)) {
								endIndex = i + 1;
								break;
							}
						}

						String cnd;
						if (endIndex == -1) {
							cnd = stmt.trim();
							stmt = "";
						} else {
							cnd = stmt.substring(0, endIndex).trim();
							stmt = stmt.substring(endIndex).trim();
						}

						int opIndex = -1;
						String operator = null;
						chars = cnd.toCharArray();
						for (int i = 0; i < chars.length; i++) {
							char c = chars[i];

							if (c == '"' || c == '\'') {
								char quart = c;
								for (i++;; i++) {
									c = chars[i];
									if (c == quart) {
										break;
									}
									if (c == '\\') {
										i++;
										continue;
									}
								}
								continue;
							}

							for (String op : new String[] { "<=", ">=", "<", ">", "!=", "=", "like ", "in " }) {
								if (i < chars.length - op.length() && cnd.substring(i, i + op.length()).equalsIgnoreCase(op)) {
									opIndex = i;
									operator = op.toLowerCase().trim();
									break;
								}
							}
							if (opIndex != -1) {
								break;
							}
						}

						if (opIndex != -1) {
							String left = cnd.substring(0, opIndex).trim();
							String right = cnd.substring(opIndex + operator.length()).trim();
							String name, value;
							if (left.startsWith("@") || left.startsWith("jcr:")) {
								name = left;
								if (name.startsWith("@")) {
									name = name.substring(1);
								} else if (name.startsWith("jcr:")) {
									if (JCR_FIELDS.containsKey(name)) {
										name = JCR_FIELDS.get(name);
									} else {
										throw new InvalidQuerySyntaxException(fStatement);
									}
								}
								value = right;
							} else if (right.startsWith("@") || right.startsWith("jcr:")) {
								if (operator.equals("like")) {
									throw new InvalidQuerySyntaxException(fStatement);
								}

								name = right;
								if (name.startsWith("@")) {
									name = name.substring(1);
								} else if (name.startsWith("jcr:")) {
									if (JCR_FIELDS.containsKey(name)) {
										name = JCR_FIELDS.get(name);
									} else {
										throw new InvalidQuerySyntaxException(fStatement);
									}
								}
								switch (operator) {
								case "<":
									operator = ">";
									break;
								case "<=":
									operator = ">=";
									break;
								case ">=":
									operator = "<=";
									break;
								case ">":
									operator = "<";
									break;
								}
								value = left;
							} else {
								throw new InvalidQuerySyntaxException(fStatement);
							}

							Object javaValue;
							List<Object> values = null;
							if (operator.equals("in")) {
								if (!value.startsWith("(") || !value.endsWith(")")) {
									throw new InvalidQuerySyntaxException(fStatement);
								}

								values = new ArrayList<>();
								StringBuilder valueString = new StringBuilder();
								char[] a = value.toCharArray();
								for (int i = 0; i < a.length; i++) {
									char c = a[i];

									if (c == '"' || c == '\'') {
										valueString.append(c);
										char quart = c;
										for (i++;; i++) {
											c = a[i];
											valueString.append(c);
											if (c == quart) {
												break;
											}
											if (c == '\\') {
												valueString.append('\\');
												i++;
												continue;
											}
										}
										continue;
									}

									if (c == ',' || c == ')') {
										values.add(toJavaValue(valueString.toString().trim()));
										valueString = new StringBuilder();
										continue;
									}
								}
								if (values.isEmpty()) {
									throw new InvalidQuerySyntaxException(fStatement);
								}

								javaValue = null;
								for (Object o : values) {
									if (o instanceof String) {
										javaValue = o;
										break;
									}
									if (o instanceof BigDecimal) {
										javaValue = o;
										continue;
									}
									if (o instanceof java.util.Date) {
										if (javaValue == null || !(javaValue instanceof BigDecimal)) {
											javaValue = o;
										}
										continue;
									}
									if (o instanceof Boolean) {
										if (javaValue == null || !(javaValue instanceof BigDecimal || javaValue instanceof java.util.Date)) {
											javaValue = o;
										}
										continue;
									}
								}
							} else {
								javaValue = toJavaValue(value);
							}

							switch (operator) {
							case "like":
								buf.append(escape(name)).append(":").append(escape(javaValue.toString(), "*"));
								break;
							case "in":
								buf.append("(");
								for (int i = 0; i < values.size(); i++) {
									Object o = values.get(i);
									if (i > 0) {
										buf.append(" OR ");
									}
									buf.append(escape(name)).append(":");
									if (o instanceof String) {
										buf.append(toLuceneValue(o));
									} else {
										buf.append(toLuceneRangedValue(o));
									}
								}
								buf.append(")");
								break;
							case "=":
								buf.append(escape(name)).append(":").append(toLuceneRangedValue(javaValue));
								break;
							case "!=":
								buf.append("NOT(").append(escape(name)).append(":").append(toLuceneRangedValue(javaValue)).append(")");
								break;
							case "<":
								buf.append(escape(name)).append(":[").append(toLuceneMinValue(javaValue)).append(" TO ").append(toLuceneValue(javaValue)).append("}");
								break;
							case "<=":
								buf.append(escape(name)).append(":[").append(toLuceneMinValue(javaValue)).append(" TO ").append(toLuceneValue(javaValue)).append("]");
								break;
							case ">=":
								buf.append(escape(name)).append(":").append("[").append(toLuceneValue(javaValue)).append(" TO ").append(toLuceneMaxValue(javaValue)).append("]");
								break;
							case ">":
								buf.append(escape(name)).append(":").append("{").append(toLuceneValue(javaValue)).append(" TO ").append(toLuceneMaxValue(javaValue)).append("]");
								break;
							}

							Class<?> fieldType = null;
							if (fAdaptable != null) {
								SearchIndex.FieldTypeProvider fieldTypeProvider = Adaptables.getAdapter(fAdaptable, SearchIndex.FieldTypeProvider.class);
								if (fieldTypeProvider != null) {
									fieldType = fieldTypeProvider.getFieldType(name);
								}
							}
							if (fieldType == null) {
								if (javaValue instanceof BigDecimal) {
									fieldType = Double.class;
								} else if (javaValue instanceof java.util.Date) {
									fieldType = java.util.Date.class;
								} else if (javaValue instanceof Boolean) {
									fieldType = Boolean.class;
								} else {
									fieldType = String.class;
								}
							}
							if (fieldType.equals(BigDecimal.class)) {
								fPointsConfigMap.put(name, new PointsConfig(new DecimalFormat(), Double.class));
							} else if (fieldType.equals(java.util.Date.class)) {
								fPointsConfigMap.put(name, new PointsConfig(new DecimalFormat(), Long.class));
							} else if (fieldType.equals(Boolean.class)) {
								fPointsConfigMap.put(name, new PointsConfig(new DecimalFormat(), Integer.class));
							}
						} else {
							do {
								if (cnd.startsWith("@")) {
									buf.append("_properties:").append(escape(cnd.substring(1)));
									break;
								}

								String fn = toFunction(cnd);
								if (fn != null) {
									buf.append(fn);
									break;
								}

								throw new InvalidQuerySyntaxException(fStatement);
							} while (false);
						}
						continue;
					}
				}

				String stmt = buf.toString();
				for (Map.Entry<String, PointsConfig> e : fPointsConfigMap.entrySet()) {
					String find = escape(e.getKey()) + ":*";
					String replacement = escape(e.getKey()) + ":" + toLuceneExistsValue(e.getValue().getType());
					for (int i = stmt.indexOf(find); i != -1; i = stmt.indexOf(find)) {
						stmt = stmt.substring(0, i) + replacement + stmt.substring(i + find.length());
					}
				}

				return stmt;
			}

			private String toLuceneValue(Object value) {
				if (value instanceof BigDecimal) {
					return escape(((BigDecimal) value).toPlainString());
				}

				if (value instanceof java.util.Date) {
					return escape("" + ((java.util.Date) value).getTime());
				}

				if (value instanceof Boolean) {
					return escape(((Boolean) value) ? "1" : "0");
				}

				return escape(value.toString());
			}

			private String toLuceneRangedValue(Object value) {
				if (value instanceof BigDecimal) {
					String v = toLuceneValue(value);
					return "[" + v + " TO " + v + "]";
				}

				if (value instanceof java.util.Date) {
					String v = toLuceneValue(value);
					return "[" + v + " TO " + v + "]";
				}

				if (value instanceof Boolean) {
					String v = toLuceneValue(value);
					return "[" + v + " TO " + v + "]";
				}

				return toLuceneValue(value);
			}

			private String toLuceneMinValue(Object value) {
				if (value instanceof BigDecimal) {
					return escape(BigDecimal.valueOf(-Double.MAX_VALUE).toPlainString());
				}

				if (value instanceof java.util.Date) {
					return escape(BigDecimal.valueOf(Long.MIN_VALUE).toPlainString());
				}

				if (value instanceof Boolean) {
					return escape("0");
				}

				throw new IllegalArgumentException("Could not get minimum value of " + value.getClass().getName() + ".");
			}

			private String toLuceneMaxValue(Object value) {
				if (value instanceof BigDecimal) {
					return escape(BigDecimal.valueOf(Double.MAX_VALUE).toPlainString());
				}

				if (value instanceof java.util.Date) {
					return escape(BigDecimal.valueOf(Long.MAX_VALUE).toPlainString());
				}

				if (value instanceof Boolean) {
					return escape("1");
				}

				throw new IllegalArgumentException("Could not get maximum value of " + value.getClass().getName() + ".");
			}

			private String toLuceneExistsValue(Class<?> type) {
				if (type.equals(Double.class)) {
					return "[" + escape(BigDecimal.valueOf(-Double.MAX_VALUE).toPlainString()) + " TO " + escape(BigDecimal.valueOf(Double.MAX_VALUE).toPlainString()) + "]";
				}

				if (type.equals(Long.class)) {
					return "[" + escape(BigDecimal.valueOf(Long.MIN_VALUE).toPlainString()) + " TO " + escape(BigDecimal.valueOf(Long.MAX_VALUE).toPlainString()) + "]";
				}

				if (type.equals(Integer.class)) {
					return "[" + escape("0") + " TO " + escape("1") + "]";
				}

				throw new IllegalArgumentException("Could not create statement of " + type.getName() + ".");
			}

			private String toFunction(String value) {
				if (value.startsWith("fn:contains(") || value.startsWith("jcr:contains(")) {
					if (!value.endsWith(")")) {
						throw new InvalidQuerySyntaxException(fStatement);
					}

					String argsString = value.substring(value.indexOf("(") + 1, value.length() - 1).trim();
					List<String> args = parseArguments(argsString);
					if (args.size() != 2) {
						throw new InvalidQuerySyntaxException(fStatement);
					}
					Object arg0 = args.get(0);
					Object arg1 = toJavaValue(args.get(1));
					if (!(arg1 instanceof String)) {
						throw new InvalidQuerySyntaxException(fStatement);
					}
					if (Strings.isEmpty(arg1.toString())) {
						throw new InvalidQuerySyntaxException(fStatement);
					}

					String propertyName = arg0.toString();
					String escapedValue = escape(arg1.toString());

					if (propertyName.equals(".")) {
						return "_fulltext:" + escapedValue;
					} else if (propertyName.startsWith("@")) {
						return escape(propertyName.substring(1)) + ":*" + escapedValue + "*";
					}

					throw new InvalidQuerySyntaxException(fStatement);
				}

				return value;
			}
		}
	}

	private static class OrderByClause extends Clause {
		private Sort fSort;

		protected OrderByClause(String statement, Adaptable adaptable) {
			super(statement, adaptable);
		}

		@Override
		protected String compile() {
			String stmt = fStatement;
			if (stmt.toLowerCase().startsWith(ClauseExtractor.ORDER_BY.getClause().toLowerCase())) {
				stmt = stmt.substring(ClauseExtractor.ORDER_BY.getClause().length()).trim();
			}

			String[] cnds = stmt.split("\\s*,\\s*");

			List<SortField> l = new ArrayList<>();
			for (String cnd : cnds) {
				String[] fieldAndDirection = cnd.split("\\s+");
				boolean ascending = true;
				if (fieldAndDirection.length > 1) {
					String direction = fieldAndDirection[1].trim().toLowerCase();
					if (direction.equals("ascending") || direction.equals("asc")) {
						ascending = true;
					} else if (direction.equals("descending") || direction.equals("desc")) {
						ascending = false;
					} else {
						throw new InvalidQuerySyntaxException(fStatement);
					}
				}
				String fieldName = getFieldName(fieldAndDirection[0]);
				String sortName = escape(fieldName);
				SortField.Type sortType = getFieldType(fieldAndDirection[0], fieldName);
				if (sortType.equals(SortField.Type.STRING)) {
					l.add(new SortField(sortName, sortType, !ascending));
				} else {
					l.add(new SortedNumericSortField(sortName, sortType, !ascending));
				}
			}
			if (l.isEmpty()) {
				l.add(new SortField("_identifier", SortField.Type.STRING));
			}
			fSort = new Sort(l.toArray(SortField[]::new));

			return null;
		}

		public Sort getSort() {
			return fSort;
		}

		protected String getFieldName(String field) {
			field = field.trim();
			if (field.startsWith("xs:decimal(") || field.startsWith("xs:float(") || field.startsWith("xs:double(")
					|| field.startsWith("xs:dateTime(") || field.startsWith("xs:date(") || field.startsWith("xs:boolean(")) {
				if (field.endsWith(")")) {
					field = field.substring(field.indexOf("(") + 1, field.length() - 1);
				}
			}
			return super.getFieldName(field);
		}

		private SortField.Type getFieldType(String field, String name) {
			if (field.startsWith("xs:decimal(") || field.startsWith("xs:float(") || field.startsWith("xs:double(")) {
				return SortField.Type.DOUBLE;
			}
			if (field.startsWith("xs:dateTime(") || field.startsWith("xs:date(")) {
				return SortField.Type.LONG;
			}
			if (field.startsWith("xs:boolean(")) {
				return SortField.Type.INT;
			}

			Class<?> fieldType = null;
			if (fAdaptable != null) {
				SearchIndex.FieldTypeProvider fieldTypeProvider = Adaptables.getAdapter(fAdaptable, SearchIndex.FieldTypeProvider.class);
				if (fieldTypeProvider != null) {
					fieldType = fieldTypeProvider.getFieldType(name);
				}
			}
			if (fieldType != null) {
				if (fieldType.equals(BigDecimal.class)) {
					return SortField.Type.DOUBLE;
				}
				if (fieldType.equals(java.util.Date.class)) {
					return SortField.Type.LONG;
				}
				if (fieldType.equals(Boolean.class)) {
					return SortField.Type.INT;
				}
			}
			return SortField.Type.STRING;
		}
	}

	private static class FacetAccumulateClause extends Clause {
		private List<Facet> fFacets;

		protected FacetAccumulateClause(String statement, Adaptable adaptable) {
			super(statement, adaptable);
		}

		@Override
		protected String compile() {
			String stmt = fStatement;
			if (stmt.toLowerCase().startsWith(ClauseExtractor.FACET_ACCUMULATE.getClause().toLowerCase())) {
				stmt = stmt.substring(ClauseExtractor.FACET_ACCUMULATE.getClause().length()).trim();
			}

			List<String> cnds = new ArrayList<>();
			while (!Strings.isBlank(stmt)) {
				char[] chars = stmt.toCharArray();
				int nest = 0;
				int endIndex = -1;

				for (int i = 0; i < chars.length; i++) {
					char c = chars[i];

					if (c == '"' || c == '\'') {
						char quart = c;
						for (i++;; i++) {
							c = chars[i];
							if (c == quart) {
								break;
							}
							if (c == '\\') {
								i++;
								continue;
							}
						}
						continue;
					}

					if (c == '(') {
						nest++;
						continue;
					}

					if (c == ')') {
						nest--;
						if (nest < 0) {
							throw new InvalidQuerySyntaxException(fStatement);
						}
						continue;
					}

					if (c == ',') {
						if (nest == 0) {
							endIndex = i;
							break;
						}
						continue;
					}
				}

				if (endIndex == -1) {
					cnds.add(stmt.trim());
					stmt = "";
				} else {
					cnds.add(stmt.substring(0, endIndex).trim());
					stmt = stmt.substring(endIndex + 1).trim();
				}
			}

			Map<String, Facet> l = new HashMap<>();
			for (String cnd : cnds) {
				if (cnd.startsWith("@")) {
					TopFacetParams f = new TopFacetParams(cnd.substring(1));
					if (l.containsKey(f.getFieldName())) {
						throw new InvalidQuerySyntaxException(fStatement);
					}
					l.put(f.getFieldName(), new Facet(f));
					continue;
				}

				if (cnd.startsWith("jcr:") && cnd.indexOf("jcr:") == -1) {
					if (JCR_FIELDS.containsKey(cnd)) {
						TopFacetParams f = new TopFacetParams(JCR_FIELDS.get(cnd));
						if (l.containsKey(f.getFieldName())) {
							throw new InvalidQuerySyntaxException(fStatement);
						}
						l.put(f.getFieldName(), new Facet(f));
						continue;
					}
					throw new InvalidQuerySyntaxException(fStatement);
				}

				if (cnd.startsWith("top(") && cnd.endsWith(")")) {
					String argsString = cnd.substring(cnd.indexOf("(") + 1, cnd.length() - 1).trim();
					List<String> args = parseArguments(argsString);
					if (args.size() != 2) {
						throw new InvalidQuerySyntaxException(fStatement);
					}
					Object arg1 = args.get(1);
					if (!(arg1 instanceof BigDecimal)) {
						throw new InvalidQuerySyntaxException(fStatement);
					}

					String fieldName = args.get(0);
					int limit = ((BigDecimal) arg1).intValue();
					if (fieldName.startsWith("@")) {
						TopFacetParams f = new TopFacetParams(fieldName.substring(1), limit);
						if (l.containsKey(f.getFieldName())) {
							throw new InvalidQuerySyntaxException(fStatement);
						}
						l.put(f.getFieldName(), new Facet(f));
						continue;
					}
					if (fieldName.startsWith("jcr:")) {
						if (JCR_FIELDS.containsKey(fieldName)) {
							TopFacetParams f = new TopFacetParams(JCR_FIELDS.get(fieldName), limit);
							if (l.containsKey(f.getFieldName())) {
								throw new InvalidQuerySyntaxException(fStatement);
							}
							l.put(f.getFieldName(), new Facet(f));
							continue;
						}
					}
					throw new InvalidQuerySyntaxException(fStatement);
				}

				if (cnd.startsWith("range(") && cnd.endsWith(")")) {
					String argsString = cnd.substring(cnd.indexOf("(") + 1, cnd.length() - 1).trim();
					List<String> args = parseArguments(argsString);
					if (args.size() != 2) {
						throw new InvalidQuerySyntaxException(fStatement);
					}
					Object arg0 = args.get(0);
					if (!(arg0 instanceof String)) {
						throw new InvalidQuerySyntaxException(fStatement);
					}

					String label = arg0.toString();
					String expression = args.get(1);
					List<String> exps = new ArrayList<>();
					for (;;) {
						int i = -1;
						for (String op : new String[] { "<", ">" }) {
							int p = expression.indexOf(op);
							if (p != -1) {
								if (i == -1 || i > p) {
									i = p;
								}
							}
						}
						if (i == -1) {
							if (!expression.isEmpty()) {
								exps.add(expression);
							}
							break;
						}

						exps.add(expression.substring(0, i).trim());
						expression = expression.substring(i).trim();
						if (expression.charAt(1) == '=') {
							exps.add(expression.substring(0, 2));
							expression = expression.substring(2).trim();
						} else {
							exps.add(expression.substring(0, 1));
							expression = expression.substring(1).trim();
						}
					}
					if (exps.size() == 5) {
						Object minValue, maxValue;
						boolean minInclusive, maxInclusive;
						String left = exps.get(0);
						String lop = exps.get(1);
						String field = exps.get(2);
						String rop = exps.get(3);
						String right = exps.get(4);
						if (lop.equals("<") || lop.equals("<=")) {
							if (!(rop.equals("<") || rop.equals("<="))) {
								throw new InvalidQuerySyntaxException(fStatement);
							}
							minValue = toJavaValue(left);
							maxValue = toJavaValue(right);
							minInclusive = lop.endsWith("=");
							maxInclusive = rop.endsWith("=");
						} else if (lop.equals(">") || lop.equals(">=")) {
							if (!(rop.equals(">") || rop.equals(">="))) {
								throw new InvalidQuerySyntaxException(fStatement);
							}
							minValue = toJavaValue(right);
							maxValue = toJavaValue(left);
							minInclusive = rop.endsWith("=");
							maxInclusive = lop.endsWith("=");
						} else {
							throw new InvalidQuerySyntaxException(fStatement);
						}
						if (!minValue.getClass().equals(maxValue.getClass())) {
							throw new InvalidQuerySyntaxException(fStatement);
						}

						if (!(minValue instanceof BigDecimal || minValue instanceof java.util.Date)) {
							throw new InvalidQuerySyntaxException(fStatement);
						}
						if (!(maxValue instanceof BigDecimal || maxValue instanceof java.util.Date)) {
							throw new InvalidQuerySyntaxException(fStatement);
						}

						RangeFacetParams f = new RangeFacetParams(label, getFieldName(field), minValue, minInclusive, maxValue, maxInclusive);
						Facet facet = l.get(f.getFieldName());
						if (facet == null) {
							facet = new Facet();
							l.put(f.getFieldName(), facet);
						}
						facet.add(f);
						continue;
					}
					if (exps.size() == 3) {
						Object minValue, maxValue;
						boolean minInclusive, maxInclusive;
						String left = exps.get(0);
						String op = exps.get(1);
						String right = exps.get(2);
						String field;
						if (left.startsWith("@") || left.startsWith("jcr:")) {
							field = left;
							if (op.equals("<") || op.equals("<=")) {
								minValue = null;
								maxValue = toJavaValue(right);
								minInclusive = false;
								maxInclusive = op.endsWith("=");
							} else if (op.equals(">") || op.equals(">=")) {
								minValue = toJavaValue(right);
								maxValue = null;
								minInclusive = op.endsWith("=");
								maxInclusive = false;
							} else {
								throw new InvalidQuerySyntaxException(fStatement);
							}
						} else if (right.startsWith("@") || right.startsWith("jcr:")) {
							field = right;
							if (op.equals("<") || op.equals("<=")) {
								minValue = toJavaValue(left);
								maxValue = null;
								minInclusive = op.endsWith("=");
								maxInclusive = false;
							} else if (op.equals(">") || op.equals(">=")) {
								minValue = null;
								maxValue = toJavaValue(left);
								minInclusive = false;
								maxInclusive = op.endsWith("=");
							} else {
								throw new InvalidQuerySyntaxException(fStatement);
							}
						} else {
							throw new InvalidQuerySyntaxException(fStatement);
						}

						if (minValue != null) {
							if (!(minValue instanceof BigDecimal || minValue instanceof java.util.Date)) {
								throw new InvalidQuerySyntaxException(fStatement);
							}
						}
						if (maxValue != null) {
							if (!(maxValue instanceof BigDecimal || maxValue instanceof java.util.Date)) {
								throw new InvalidQuerySyntaxException(fStatement);
							}
						}

						RangeFacetParams f = new RangeFacetParams(label, getFieldName(field), minValue, minInclusive, maxValue, maxInclusive);
						Facet facet = l.get(f.getFieldName());
						if (facet == null) {
							facet = new Facet();
							l.put(f.getFieldName(), facet);
						}
						facet.add(f);
						continue;
					}
					throw new InvalidQuerySyntaxException(fStatement);
				}

				throw new InvalidQuerySyntaxException(fStatement);
			}
			fFacets = new ArrayList<>(l.values());

			return null;
		}

		public List<Facet> listFacets() {
			return fFacets;
		}

		private static class Facet {
			private final List<FacetParams> fFacetParams = new ArrayList<>();

			public Facet() {}

			public Facet(FacetParams params) {
				fFacetParams.add(params);
			}

			public boolean add(FacetParams params) {
				if (fFacetParams.isEmpty()) {
					fFacetParams.add(params);
					return true;
				}

				if (fFacetParams.get(0) instanceof TopFacetParams) {
					return false;
				}
				if (params instanceof TopFacetParams) {
					return false;
				}

				RangeFacetParams f0 = (RangeFacetParams) fFacetParams.get(0);
				RangeFacetParams f1 = (RangeFacetParams) params;

				if (!f0.getFieldName().equals(f1.getFieldName())) {
					return false;
				}
				if (!f0.getFieldType().equals(f1.getFieldType())) {
					return false;
				}

				fFacetParams.add(params);
				return true;
			}

			public String getFieldName() {
				return fFacetParams.get(0).getFieldName();
			}

			public boolean isRange() {
				return (fFacetParams.get(0) instanceof RangeFacetParams);
			}

			public TopFacetParams getTopFacetParams() {
				return (TopFacetParams) fFacetParams.get(0);
			}

			public List<RangeFacetParams> listRangeFacetParams() {
				return Collections.unmodifiableList(fFacetParams.stream().map(e -> (RangeFacetParams) e).collect(Collectors.toList()));
			}
		}

		private static abstract class FacetParams {
			private final String fFieldName;

			public FacetParams(String fieldName) {
				fFieldName = fieldName;
			}

			public String getFieldName() {
				return fFieldName;
			}
		}

		private static class TopFacetParams extends FacetParams {
			private final int fLimit;

			public TopFacetParams(String fieldName) {
				this(fieldName, Integer.MAX_VALUE);
			}

			public TopFacetParams(String fieldName, int limit) {
				super(fieldName);
				fLimit = limit;
			}

			public int getLimit() {
				return fLimit;
			}
		}

		private static class RangeFacetParams extends FacetParams {
			private final String fLabel;
			private final Object fMinValue;
			private final boolean fMinInclusive;
			private final Object fMaxValue;
			private final boolean fMaxInclusive;

			public RangeFacetParams(String label, String fieldName, Object minValue, boolean minInclusive, Object maxValue, boolean maxInclusive) {
				super(fieldName);
				fLabel = label;
				fMinValue = minValue;
				fMinInclusive = minInclusive;
				fMaxValue = maxValue;
				fMaxInclusive = maxInclusive;
			}

			public String getLabel() {
				return fLabel;
			}

			public Class<?> getValueType() {
				return (fMinValue != null) ? fMinValue.getClass() : fMaxValue.getClass();
			}

			public Class<?> getFieldType() {
				Class<?> valueType = getValueType();
				if (valueType.equals(BigDecimal.class)) {
					return BigDecimal.class;
				}
				if (valueType.equals(java.util.Date.class)) {
					return Long.class;
				}
				if (valueType.equals(Boolean.class)) {
					return Integer.class;
				}
				throw new IllegalStateException("Invalid value type: " + valueType.getName());
			}

			public Object getMinValue() {
				Class<?> valueType = getValueType();
				if (fMinValue == null) {
					if (valueType.equals(BigDecimal.class)) {
						return new BigDecimal("0");
					}
					if (valueType.equals(java.util.Date.class)) {
						return Long.parseLong("0");
					}
					if (valueType.equals(Boolean.class)) {
						return Integer.parseInt("0");
					}
					throw new IllegalStateException("Invalid value type: " + valueType.getName());
				}

				if (valueType.equals(BigDecimal.class)) {
					return fMinValue;
				}
				if (valueType.equals(java.util.Date.class)) {
					return Long.valueOf(((java.util.Date) fMinValue).getTime());
				}
				if (valueType.equals(Boolean.class)) {
					return Integer.valueOf(((Boolean) fMinValue) ? 1 : 0);
				}
				throw new IllegalStateException("Invalid value type: " + valueType.getName());
			}

			public boolean isMinInclusive() {
				return fMinInclusive;
			}

			public Object getMaxValue() {
				Class<?> valueType = getValueType();
				if (fMaxValue == null) {
					if (valueType.equals(BigDecimal.class)) {
						return BigDecimal.valueOf(Double.MAX_VALUE);
					}
					if (valueType.equals(java.util.Date.class)) {
						return Long.valueOf(Long.MAX_VALUE);
					}
					if (valueType.equals(Boolean.class)) {
						return Integer.valueOf(Integer.MAX_VALUE);
					}
					throw new IllegalStateException("Invalid value type: " + valueType.getName());
				}

				if (valueType.equals(BigDecimal.class)) {
					return fMaxValue;
				}
				if (valueType.equals(java.util.Date.class)) {
					return Long.valueOf(((java.util.Date) fMaxValue).getTime());
				}
				if (valueType.equals(Boolean.class)) {
					return Integer.valueOf(((Boolean) fMaxValue) ? 1 : 0);
				}
				throw new IllegalStateException("Invalid value type: " + valueType.getName());
			}

			public boolean isMaxInclusive() {
				return fMaxInclusive;
			}
		}
	}

	private static class AutoCompleteClause extends Clause {
		private String fText;
		private int fLimit;

		protected AutoCompleteClause(String statement, Adaptable adaptable) {
			super(statement, adaptable);
		}

		@Override
		protected String compile() {
			String stmt = fStatement;
			if (stmt.toLowerCase().startsWith(ClauseExtractor.AUTO_COMPLETE.getClause().toLowerCase())) {
				stmt = stmt.substring(ClauseExtractor.AUTO_COMPLETE.getClause().length()).trim();
			}

			String cnd = stmt;
			if (cnd.startsWith("top(") && cnd.endsWith(")")) {
				String argsString = cnd.substring(cnd.indexOf("(") + 1, cnd.length() - 1).trim();

				List<String> args = parseArguments(argsString);
				if (args.size() != 2) {
					throw new InvalidQuerySyntaxException(fStatement);
				}
				Object arg0 = toJavaValue(args.get(0));
				Object arg1 = toJavaValue(args.get(1));
				if (!(arg0 instanceof String) || !(arg1 instanceof BigDecimal)) {
					throw new InvalidQuerySyntaxException(fStatement);
				}
				if (Strings.isEmpty(arg0.toString())) {
					throw new InvalidQuerySyntaxException(fStatement);
				}

				fText = arg0.toString();
				fLimit = ((BigDecimal) arg1).intValue();

				return null;
			}

			throw new InvalidQuerySyntaxException(fStatement);
		}

		public String getText() {
			return fText;
		}

		public int getLimit() {
			return fLimit;
		}

		public Sort getSort() {
			return new Sort(new SortField("_suggestion", SortField.Type.STRING));
		}
	}

	private static class AutoComplete {
		private Query fQuery;
		private int fLimit;
		private Sort fSort;

		private AutoComplete setQuery(Query query) {
			fQuery = query;
			return this;
		}

		private AutoComplete setLimit(int limit) {
			fLimit = limit;
			return this;
		}

		private AutoComplete setSort(Sort sort) {
			fSort = sort;
			return this;
		}

		public Query getQuery() {
			return fQuery;
		}

		public int getLimit() {
			return fLimit;
		}

		public Sort getSort() {
			return fSort;
		}
	}

}

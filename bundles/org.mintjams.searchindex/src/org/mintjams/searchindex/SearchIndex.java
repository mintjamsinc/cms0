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

package org.mintjams.searchindex;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.Principal;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public interface SearchIndex extends Closeable {

	Query createQuery(String statement, String language) throws IOException;

	DocumentWriter getDocumentWriter() throws IOException;

	SuggestionWriter getSuggestionWriter() throws IOException;

	SearchIndex with(FieldTypeProvider fieldTypeProvider) throws IOException;

	interface Document {
		Document setIdentifier(String identifier);

		Document setPath(String path);

		Document setMimeType(String mimeType);

		Document setEncoding(String encoding);

		Document setSize(long size);

		Document setCreated(java.util.Date created);

		Document setCreatedBy(String createdBy);

		Document setLastModified(java.util.Date lastModified);

		Document setLastModifiedBy(String lastModifiedBy);

		Document setContent(String text);

		Document setContent(Path path);

		Document setContent(InputStream stream);

		Document addProperty(String name, String value);

		Document addProperty(String name, BigDecimal value);

		Document addProperty(String name, java.util.Date value);

		Document addProperty(String name, Calendar value);

		Document addProperty(String name, boolean value);

		Document removeProperty(String name);

		Document setStored(boolean stored);

		Document addAuthorized(String... name);

		Document removeAuthorized(String name);
	}

	interface Suggestion {
		Suggestion setIdentifier(String identifier);

		Suggestion setPath(String path);

		Suggestion setMimeType(String mimeType);

		Suggestion setEncoding(String encoding);

		Suggestion setSize(long size);

		Suggestion setCreated(java.util.Date created);

		Suggestion setCreatedBy(String createdBy);

		Suggestion setLastModified(java.util.Date lastModified);

		Suggestion setLastModifiedBy(String lastModifiedBy);

		Suggestion setContent(String text);

		Suggestion setContent(Path path);

		Suggestion setContent(InputStream stream);

		Suggestion addProperty(String name, String value);

		Suggestion addProperty(String name, BigDecimal value);

		Suggestion addProperty(String name, java.util.Date value);

		Suggestion addProperty(String name, Calendar value);

		Suggestion addProperty(String name, boolean value);

		Suggestion removeProperty(String name);

		Suggestion setSuggestion(String suggestion);

		Suggestion addAuthorized(String... name);

		Suggestion removeAuthorized(String name);
	}

	interface DocumentWriter {
		DocumentWriter update(UnaryOperator<Document> op) throws IOException;

		DocumentWriter delete(String... identifiers) throws IOException;

		boolean hasChanges();

		void commit() throws IOException;

		void rollback() throws IOException;
	}

	interface SuggestionWriter {
		SuggestionWriter update(UnaryOperator<Suggestion> op) throws IOException;

		SuggestionWriter delete(String... identifiers) throws IOException;

		boolean hasChanges();

		void commit() throws IOException;

		void rollback() throws IOException;
	}

	interface Query {
		Query setOffset(int offset);

		Query setLimit(int limit);

		Query setAuthorizables(String... authorizables);

		Query setAuthorizables(Principal... authorizables);

		QueryResult execute() throws IOException;

		/**
		 * Returns the exact number of documents matching this query.
		 *
		 * <p>Unlike {@link QueryResult#getTotalHits()}, which is collected with a
		 * bounded hit-count threshold and may therefore be a lower-bound
		 * approximation on large result sets, this method counts every matching
		 * document. It fetches no documents, applies no sort and computes no
		 * facets, so it is cheap even when millions of documents match.
		 * {@code offset} and {@code limit} do not affect the result;
		 * authorizables set via {@link #setAuthorizables} are honoured.</p>
		 */
		long count() throws IOException;
	}

	interface QueryResult extends Iterable<QueryResult.Row> {
		Row[] toArray() throws IOException;

		int getSize();

		int getTotalHits();

		FacetResult getFacetResult();

		SuggestionResult getSuggestionResult();

		interface Row {
			String getIdentifier();

			double getScore();

			List<String> getProperty(String name);
		}

		interface FacetResult {
			String[] getDimensions();

			Facet getFacet(String dimension);

			interface Facet {
				String getDimension();

				String[] getLabels();

				int getValue(String label);

				Map<String, Integer> getValues();

				/**
				 * <p>Returns the value of the specified label without narrowing it
				 * to an int. Facet counts are integers, but the statistical
				 * aggregations of the {@code facet accumulate} clause (sum, avg,
				 * percentile, ...) are decimals; use this accessor for those.
				 * The value is {@code Double.NaN} when a statistic is undefined
				 * because no values were found (e.g. the average of an empty
				 * set).</p>
				 */
				Number getNumber(String label);

				Map<String, Number> getNumbers();
			}
		}

		interface SuggestionResult {
			String[] getSuggestions();
		}
	}

	interface FieldTypeProvider {
		Class<?> getFieldType(String fieldName);
	}

	interface UpdateMonitor {
		boolean isCancelled();

		Consumer<String> getPathConsumer();
	}
}

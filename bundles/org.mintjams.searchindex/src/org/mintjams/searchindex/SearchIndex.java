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
		Suggestion setSuggestion(String suggestion);

		Suggestion setSuggestionBundleIdentifier(String identifier);

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
			}
		}

		interface SuggestionResult {
			String[] getSuggestions();
		}
	}

	interface FieldTypeProvider {
		Class<?> getFieldType(String fieldName);
	}

}

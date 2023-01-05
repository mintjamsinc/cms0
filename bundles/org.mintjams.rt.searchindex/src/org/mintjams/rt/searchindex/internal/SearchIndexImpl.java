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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.mintjams.rt.searchindex.internal.query.JcrXPathQuery;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.searchindex.SearchIndexConfiguration;
import org.mintjams.searchindex.query.InvalidQuerySyntaxException;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.io.Closer;

public class SearchIndexImpl implements SearchIndex, Adaptable {

	private final SearchIndexConfigurationImpl fConfig;
	private final Closer fCloser = Closer.create();
	private final Directory fDocumentIndexDirectory;
	private final Directory fDocumentTaxonomyDirectory;
	private final Directory fSuggestionIndexDirectory;
	private final FacetsConfig fFacetsConfig = new FacetsConfig();
	private final DocumentWriterImpl fDocumentWriter;
	private final SuggestionWriterImpl fSuggestionWriter;
	private final DocumentReader fDocumentReader;
	private final SuggestionReader fSuggestionReader;
	private final List<String> fMultiValuedDimensions;
	private FieldTypeProvider fFieldTypeProvider;
	private boolean fHasMultiValuedDimensionsChanges;
	private boolean fCloseRequested;

	private SearchIndexImpl(SearchIndexConfigurationImpl config) throws IOException {
		fConfig = config;

		if (Files.exists(fConfig.getMultiValuedPath())) {
			fMultiValuedDimensions = new ArrayList<>(Files.readAllLines(fConfig.getMultiValuedPath(), StandardCharsets.UTF_8));
		} else {
			fMultiValuedDimensions = new ArrayList<>();
		}
		for (String dimension : fMultiValuedDimensions) {
			fFacetsConfig.setMultiValued(dimension, true);
		}

		fDocumentIndexDirectory = fCloser.register(MMapDirectory.open(fConfig.getDocumentIndexPath()));
		fDocumentTaxonomyDirectory = fCloser.register(MMapDirectory.open(fConfig.getDocumentTaxonomyPath()));
		fSuggestionIndexDirectory = fCloser.register(MMapDirectory.open(fConfig.getSuggestionIndexPath()));

		fDocumentWriter = fCloser.register(new DocumentWriterImpl(this));
		fSuggestionWriter = fCloser.register(new SuggestionWriterImpl(this));
		fDocumentReader = fCloser.register(new DocumentReader(this));
		fSuggestionReader = fCloser.register(new SuggestionReader(this));
	}

	public static SearchIndexImpl create(SearchIndexConfigurationImpl config) throws IOException {
		return new SearchIndexImpl(config);
	}

	@Override
	public SearchIndex.Query createQuery(String statement, String language) throws IOException {
		if ("jcr:xpath".equals(language)) {
			if (!(statement.startsWith(JcrXPathQuery.JCR_ROOT_PATH + "/") || statement.startsWith("//"))) {
				throw new InvalidQuerySyntaxException(statement);
			}
			return new JcrXPathQuery(statement, this);
		}

		if ("native".equals(language)) {
			if (!statement.startsWith("[")) {
				throw new InvalidQuerySyntaxException(statement);
			}
			return new JcrXPathQuery("//*" + statement, this);
		}

		throw new IllegalArgumentException("Unsupported query language: " + language);
	}

	@Override
	public DocumentWriter getDocumentWriter() throws IOException {
		return fDocumentWriter;
	}

	@Override
	public SuggestionWriter getSuggestionWriter() throws IOException {
		return fSuggestionWriter;
	}

	@Override
	public SearchIndex with(FieldTypeProvider fieldTypeProvider) throws IOException {
		fFieldTypeProvider = fieldTypeProvider;
		return this;
	}

	public Directory getDocumentIndexDirectory() {
		return fDocumentIndexDirectory;
	}

	public Directory getDocumentTaxonomyDirectory() {
		return fDocumentTaxonomyDirectory;
	}

	public Directory getSuggestionIndexDirectory() {
		return fSuggestionIndexDirectory;
	}

	public FacetsConfig getFacetsConfig() {
		return fFacetsConfig;
	}

	public DocumentReader getDocumentReader() throws IOException {
		return fDocumentReader;
	}

	public SuggestionReader getSuggestionReader() throws IOException {
		return fSuggestionReader;
	}

	public boolean setMultiValuedDimensions(String dimension, boolean multiValued) {
		synchronized (fMultiValuedDimensions) {
			boolean updated = false;
			if (multiValued) {
				if (!fMultiValuedDimensions.contains(dimension)) {
					fMultiValuedDimensions.add(dimension);
					fFacetsConfig.setMultiValued(dimension, multiValued);
					updated = true;
				}
			} else {
				if (fMultiValuedDimensions.contains(dimension)) {
					fMultiValuedDimensions.remove(dimension);
					fFacetsConfig.setMultiValued(dimension, multiValued);
					updated = true;
				}
			}

			if (updated) {
				fHasMultiValuedDimensionsChanges = true;
			}

			return updated;
		}
	}

	public void save() throws IOException {
		synchronized (fMultiValuedDimensions) {
			if (fHasMultiValuedDimensionsChanges) {
				Files.writeString(fConfig.getMultiValuedPath(), String.join("\n", fMultiValuedDimensions), StandardCharsets.UTF_8);
				fHasMultiValuedDimensionsChanges = false;
			}
		}
	}

	public boolean isCloseRequested() {
		return fCloseRequested;
	}

	@Override
	public void close() throws IOException {
		if (fCloseRequested) {
			return;
		}
		fCloseRequested = true;
		fCloser.close();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(FieldTypeProvider.class)) {
			return (AdapterType) fFieldTypeProvider;
		}

		if (adapterType.equals(SearchIndexConfiguration.class) || adapterType.equals(SearchIndexConfigurationImpl.class)) {
			return (AdapterType) fConfig;
		}

		return null;
	}

}

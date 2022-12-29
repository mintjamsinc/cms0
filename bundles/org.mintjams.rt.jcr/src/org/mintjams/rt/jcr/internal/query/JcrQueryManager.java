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

package org.mintjams.rt.jcr.internal.query;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.qom.QueryObjectModelFactory;

import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Strings;

public class JcrQueryManager implements QueryManager, Adaptable {

	@SuppressWarnings("deprecation")
	private static final String[] SUPPORTED_LANGUAGES = new String[] { Query.XPATH };

	private final JcrWorkspace fWorkspace;

	private JcrQueryManager(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static JcrQueryManager create(JcrWorkspace workspace) {
		return new JcrQueryManager(workspace);
	}

	@SuppressWarnings("deprecation")
	@Override
	public Query createQuery(String statement, String language) throws InvalidQueryException, RepositoryException {
		if (Strings.isBlank(statement)) {
			throw new InvalidQueryException("Query statement must not be null or empty.");
		}
		if (Strings.isBlank(language)) {
			throw new InvalidQueryException("Query language must not be null or empty.");
		}

		if (Query.XPATH.equals(language)) {
			return JcrXPathQuery.create(statement, this);
		}

		throw new InvalidQueryException("Unsupported query language: " + language);
	}

	@Override
	public QueryObjectModelFactory getQOMFactory() {
		return null;
	}

	@Override
	public Query getQuery(Node node) throws InvalidQueryException, RepositoryException {
		throw new InvalidQueryException("Operation is not supported.");
	}

	@Override
	public String[] getSupportedQueryLanguages() throws RepositoryException {
		return SUPPORTED_LANGUAGES;
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}

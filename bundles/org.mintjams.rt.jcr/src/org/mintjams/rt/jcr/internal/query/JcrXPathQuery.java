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

import java.io.IOException;

import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.jcr.version.VersionException;

import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

public class JcrXPathQuery implements Query, Adaptable {

	private final String fStatement;
	private final JcrQueryManager fQueryManager;
	private Long fOffset;
	private Long fLimit;

	private JcrXPathQuery(String statement, JcrQueryManager queryManager) {
		fStatement = statement;
		fQueryManager = queryManager;
	}

	public static JcrXPathQuery create(String statement, JcrQueryManager queryManager) {
		return new JcrXPathQuery(statement, queryManager);
	}

	@Override
	public void bindValue(String name, Value value) throws IllegalArgumentException, RepositoryException {
		throw new IllegalArgumentException("Invalid variable name: " + name);
	}

	@Override
	public QueryResult execute() throws InvalidQueryException, RepositoryException {
		try {
			return new JcrXPathQueryResult(this);
		} catch (IOException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public String[] getBindVariableNames() throws RepositoryException {
		return new String[0];
	}

	@SuppressWarnings("deprecation")
	@Override
	public String getLanguage() {
		return XPATH;
	}

	@Override
	public String getStatement() {
		return fStatement;
	}

	@Override
	public String getStoredQueryPath() throws ItemNotFoundException, RepositoryException {
		throw new ItemNotFoundException("This query is not a stored query.");
	}

	@Override
	public void setLimit(long limit) {
		fLimit = limit;
	}

	public long getLimit() {
		return (fLimit != null) ? fLimit : -1;
	}

	@Override
	public void setOffset(long offset) {
		fOffset = offset;
	}

	public long getOffset() {
		return (fOffset != null) ? fOffset : -1;
	}

	@Override
	public Node storeAsNode(String arg0) throws ItemExistsException, PathNotFoundException, VersionException,
			ConstraintViolationException, LockException, UnsupportedRepositoryOperationException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fQueryManager, adapterType);
	}

}

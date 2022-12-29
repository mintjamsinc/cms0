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

import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Workspace;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Strings;

public class QueryManager implements Adaptable {

	private final Workspace fWorkspace;

	public QueryManager(Workspace workspace) {
		fWorkspace = workspace;
	}

	@SuppressWarnings("deprecation")
	public Query createQuery(String statement, String language) throws ResourceException {
		if (Strings.isEmpty(statement)) {
			throw new IllegalArgumentException("Statement must not be null or empty.");
		}
		if (Strings.isEmpty(language)) {
			throw new IllegalArgumentException("Language must not be null or empty.");
		}

		if (language.equalsIgnoreCase(javax.jcr.query.Query.XPATH)) {
			return new Query(statement, javax.jcr.query.Query.XPATH, this);
		}

		if (language.equalsIgnoreCase(javax.jcr.query.Query.JCR_SQL2)) {
			return new Query(statement, javax.jcr.query.Query.JCR_SQL2, this);
		}

		throw new IllegalArgumentException(language);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}

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

import java.security.Principal;
import java.util.Arrays;

import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.rt.searchindex.internal.SearchIndexImpl;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.searchindex.SearchIndex.Query;
import org.mintjams.searchindex.query.QueryStatements;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public abstract class SearchIndexQuery implements SearchIndex.Query, Adaptable {

	protected final SearchIndexImpl fSearchIndex;
	private Integer fOffset;
	private Integer fLimit;
	private String[] fAuthorizables;

	protected SearchIndexQuery(SearchIndexImpl searchIndex) {
		fSearchIndex = searchIndex;
	}

	@Override
	public Query setOffset(int offset) {
		fOffset = offset;
		return this;
	}

	@Override
	public Query setLimit(int limit) {
		fLimit = limit;
		return this;
	}

	@Override
	public Query setAuthorizables(String... authorizables) {
		fAuthorizables = authorizables;
		return this;
	}

	@Override
	public Query setAuthorizables(Principal... authorizables) {
		setAuthorizables(Arrays.stream(authorizables).map(e -> {
			String name = e.getName();
			if (e instanceof GroupPrincipal) {
				name += "@group";
			} else {
				name += "@user";
			}
			return name;
		}).toArray(String[]::new));
		return this;
	}

	protected String getAuthorizablesStatement() {
		StringBuilder stmt = new StringBuilder();
		if (fAuthorizables != null && fAuthorizables.length > 0) {
			stmt.append("(");
			for (int i = 0; i < fAuthorizables.length; i++) {
				if (i > 0) {
					stmt.append(" OR ");
				}
				stmt.append("_authorized:").append(QueryStatements.escape(fAuthorizables[i]));
			}
			stmt.append(")");
		}
		return stmt.toString();
	}

	protected int getOffset() {
		return (fOffset != null) ? fOffset.intValue() : 0;
	}

	protected int getLimit() {
		return (fLimit != null) ? fLimit.intValue() : 100;
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fSearchIndex, adapterType);
	}

}

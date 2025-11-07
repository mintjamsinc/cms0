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

package org.mintjams.rt.jcr.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;

import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.sql.Query;

public class JcrReferencePropertyIterator implements PropertyIterator, Adaptable {

	private final JcrNode fNode;
	private final String fName;
	private final boolean fWeak;
	private int fOffset;
	private long fPosition;
	private long fTotalHits = -1;
	private final List<AdaptableMap<String, Object>> fFetchList = new ArrayList<>();
	private Property fNextProperty;
	private boolean fFetchMore = true;
	private int fFetchSize;

	private JcrReferencePropertyIterator(JcrNode node, String name, boolean weak) {
		fNode = node;
		fName = name;
		fWeak = weak;
		fFetchSize = 10;
		readNext();
	}

	public static JcrReferencePropertyIterator create(JcrNode node, String name, boolean weak) {
		return new JcrReferencePropertyIterator(node, name, weak);
	}

	@Override
	public long getPosition() {
		return fPosition;
	}

	@Override
	public long getSize() {
		return fTotalHits;
	}

	@Override
	public void skip(long skipNum) {
		if (skipNum < 0 || Integer.MAX_VALUE < skipNum) {
			throw new NoSuchElementException("Invalid skip number: " + skipNum);
		}

		for (long i = 0; i < skipNum; i++) {
			if (!hasNext()) {
				break;
			}
			nextProperty();
		}
	}

	@Override
	public boolean hasNext() {
		return (fNextProperty != null);
	}

	@Override
	public Object next() {
		return nextProperty();
	}

	@Override
	public Property nextProperty() {
		if (!hasNext()) {
			throw new IllegalStateException("No more items.");
		}

		Property item = fNextProperty;
		fPosition++;
		readNext();
		return item;
	}

	private void readNext() {
		fNextProperty = null;
		while (fNextProperty == null) {
			if (fFetchList.isEmpty() && fFetchMore) {
				fetch();
			}
			if (fFetchList.isEmpty()) {
				return;
			}

			try {
				AdaptableMap<String, Object> itemData = fFetchList.remove(0);
				Node node = fNode.getSession().getNodeByIdentifier(itemData.getString("parent_item_id"));
				fNextProperty = JcrProperty.create(itemData, (JcrNode) node);
			} catch (AccessDeniedException ignore) {
			} catch (RepositoryException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
	}

	private void fetch() {
		fFetchMore = false;
		if (fTotalHits == -1) {
			try {
				fTotalHits = adaptTo(WorkspaceQuery.class).items().countReferenced(fNode.getIdentifier(), fName, fWeak);
			} catch (IOException | SQLException | RepositoryException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
		try (Query.Result result = adaptTo(WorkspaceQuery.class).items().listReferences(fNode.getIdentifier(), fName, fWeak)) {
			for (AdaptableMap<String, Object> itemData : result) {
				fFetchList.add(itemData);
				fOffset++;
				if (fFetchList.size() >= fFetchSize) {
					fFetchMore = true;
					break;
				}
			}
		} catch (IOException | SQLException | RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fNode, adapterType);
	}

}

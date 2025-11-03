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
import javax.jcr.RepositoryException;

import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.sql.Query;

public class JcrNodeIterator implements NodeIterator, Adaptable {

	private final JcrNode fNode;
	private final String[] fNameGlobs;
	private int fOffset;
	private long fPosition;
	private long fTotalHits = -1;
	private final List<AdaptableMap<String, Object>> fFetchList = new ArrayList<>();
	private Map<String, AdaptableMap<String, Object>> fFetchItems;
	private Node fNextNode;
	private boolean fFetchMore = true;
	private int fFetchSize;

	private JcrNodeIterator(JcrNode node, String[] nameGlobs) {
		fNode = node;
		fNameGlobs = nameGlobs;
		int cacheSize = adaptTo(JcrRepository.class).getConfiguration().getNodeCacheSize();
		fFetchSize = BigDecimal.valueOf(cacheSize).multiply(BigDecimal.valueOf(0.5)).intValue();
		readNext();
	}

	public static JcrNodeIterator create(JcrNode node) {
		return new JcrNodeIterator(node, null);
	}

	public static JcrNodeIterator create(JcrNode node, String[] nameGlobs) {
		return new JcrNodeIterator(node, nameGlobs);
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
			nextNode();
		}
	}

	@Override
	public boolean hasNext() {
		return (fNextNode != null);
	}

	@Override
	public Object next() {
		return nextNode();
	}

	@Override
	public Node nextNode() {
		if (!hasNext()) {
			throw new IllegalStateException("No more items.");
		}

		Node item = fNextNode;
		fPosition++;
		readNext();
		return item;
	}

	private void readNext() {
		fNextNode = null;
		while (fNextNode == null) {
			if (fFetchList.isEmpty() && fFetchMore) {
				fetch();
			}
			if (fFetchList.isEmpty()) {
				return;
			}

			try {
				AdaptableMap<String, Object> itemData = fFetchList.remove(0);
				adaptTo(JcrCache.class).setNode(itemData);
				AdaptableMap<String, Object> contentItemData = fFetchItems.remove(itemData.getString("item_id") + "/" + JcrNode.JCR_CONTENT_NAME);
				if (contentItemData != null) {
					adaptTo(JcrCache.class).setNode(contentItemData);
				}
				Node node = fNode.getSession().getNodeByIdentifier(itemData.getString("item_id"));
				fNextNode = node;
			} catch (AccessDeniedException ignore) {
			} catch (RepositoryException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
	}

	private void fetch() {
		fFetchMore = false;
		List<String> identifiers = new ArrayList<>();
		if (fTotalHits == -1) {
			try {
				fTotalHits = adaptTo(WorkspaceQuery.class).items().countNodes(fNode.getIdentifier(), fNameGlobs);
			} catch (IOException | SQLException | RepositoryException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
		try (Query.Result result = adaptTo(WorkspaceQuery.class).items().listNodes(fNode.getIdentifier(), fNameGlobs, fOffset)) {
			for (AdaptableMap<String, Object> itemData : result) {
				if (!itemData.getBoolean("is_deleted")) {
					fFetchList.add(itemData);
					identifiers.add(itemData.getString("item_id"));
				}
				fOffset++;
				if (fFetchList.size() >= fFetchSize) {
					fFetchMore = true;
					break;
				}
			}
		} catch (IOException | SQLException | RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}

		fFetchItems = new HashMap<>();
		if (!identifiers.isEmpty()) {
			try (Query.Result result = adaptTo(WorkspaceQuery.class).items().listContentNodes(identifiers.toArray(String[]::new))) {
				for (AdaptableMap<String, Object> itemData : result) {
					fFetchItems.put(itemData.getString("parent_item_id") + "/" + JcrNode.JCR_CONTENT_NAME, itemData);
				}
			} catch (IOException | SQLException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fNode, adapterType);
	}

}

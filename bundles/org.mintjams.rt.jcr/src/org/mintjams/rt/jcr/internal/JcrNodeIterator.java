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

import org.mintjams.rt.jcr.internal.security.JcrAccessControlManager;
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
	private long fFetchRevision;
	private Boolean fSkipByOffset;
	private boolean fInitialized;

	private JcrNodeIterator(JcrNode node, String[] nameGlobs) {
		fNode = node;
		fNameGlobs = nameGlobs;
		int cacheSize = adaptTo(JcrRepository.class).getConfiguration().getNodeCacheSize();
		fFetchSize = BigDecimal.valueOf(cacheSize).multiply(BigDecimal.valueOf(0.5)).intValue();
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
		// Counted on demand: iterations that never ask for the size (or a page
		// whose caller already knows the total) skip the COUNT statement.
		if (fTotalHits == -1) {
			try {
				fTotalHits = adaptTo(WorkspaceQuery.class).items().countNodes(fNode.getIdentifier(), fNameGlobs);
			} catch (IOException | SQLException | RepositoryException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
		return fTotalHits;
	}

	@Override
	public void skip(long skipNum) {
		if (skipNum < 0 || Integer.MAX_VALUE < skipNum) {
			throw new NoSuchElementException("Invalid skip number: " + skipNum);
		}
		if (skipNum == 0) {
			return;
		}

		// When this session is entitled to read every child, the skip is pushed
		// down to the database as a row offset. The walking fallback below loads
		// and access-checks every skipped node, which makes deep pagination cost
		// O(offset) per page — quadratic over a full listing of a large folder.
		if (isSkipByOffsetSafe()) {
			// fNextNode (once initialized) is the row at index fPosition of the
			// non-deleted, name-ordered child set — the same row set the database
			// offset addresses (fetch() filters deleted rows in SQL).
			long target = fPosition + skipNum;
			fInitialized = true;
			fFetchList.clear();
			if (fFetchItems != null) {
				fFetchItems.clear();
			}
			fOffset = (int) Math.min(target, Integer.MAX_VALUE);
			fFetchMore = true;
			fNextNode = null;
			fPosition = target;
			readNext();
			return;
		}

		for (long i = 0; i < skipNum; i++) {
			if (!hasNext()) {
				break;
			}
			nextNode();
		}
	}

	/**
	 * Whether skipping may reposition the database cursor instead of walking
	 * nodes: only when no child can be filtered out by the per-node read check,
	 * so row offsets and node positions are guaranteed to coincide.
	 */
	private boolean isSkipByOffsetSafe() {
		if (fSkipByOffset == null) {
			boolean safe = false;
			try {
				JcrAccessControlManager accessControlManager = adaptTo(JcrAccessControlManager.class);
				safe = (accessControlManager != null) && accessControlManager.canReadAllChildren(fNode.getPath());
			} catch (Throwable ignore) {
			}
			fSkipByOffset = safe;
		}
		return fSkipByOffset;
	}

	@Override
	public boolean hasNext() {
		ensureInitialized();
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

	/**
	 * The first fetch is deferred until the iteration is actually consumed, so
	 * a caller that immediately repositions the cursor (skip-by-offset paging)
	 * never pays for a page it does not read.
	 */
	private void ensureInitialized() {
		if (!fInitialized) {
			fInitialized = true;
			readNext();
		}
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
				adaptTo(WorkspaceQuery.class).cacheNode(itemData, fFetchRevision);
				AdaptableMap<String, Object> contentItemData = fFetchItems.remove(itemData.getString("item_id") + "/" + JcrNode.JCR_CONTENT_NAME);
				if (contentItemData != null) {
					adaptTo(WorkspaceQuery.class).cacheNode(contentItemData, fFetchRevision);
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
		fFetchRevision = adaptTo(WorkspaceQuery.class).getNodeCacheRevision();
		List<String> identifiers = new ArrayList<>();
		// Deleted rows are filtered in SQL so that fOffset addresses the same
		// row set countNodes() counts — the invariant skip-by-offset relies on.
		try (Query.Result result = adaptTo(WorkspaceQuery.class).items().listNodes(fNode.getIdentifier(), fNameGlobs, fOffset, true)) {
			for (AdaptableMap<String, Object> itemData : result) {
				fFetchList.add(itemData);
				identifiers.add(itemData.getString("item_id"));
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
			prefetchProperties(identifiers);
		}
	}

	/**
	 * Seeds the property cache for every fetched child and its prefetched
	 * {@code jcr:content} node with one statement, so mapping a page of children
	 * (node types, file metadata, property listings) does not issue one
	 * properties query per node. Best-effort: on failure the per-node lazy load
	 * takes over.
	 */
	private void prefetchProperties(List<String> identifiers) {
		WorkspaceQuery workspaceQuery = adaptTo(WorkspaceQuery.class);
		Map<String, Map<String, AdaptableMap<String, Object>>> byParent = new HashMap<>();
		for (String id : identifiers) {
			byParent.put(id, new HashMap<>());
		}
		for (AdaptableMap<String, Object> itemData : fFetchItems.values()) {
			byParent.put(itemData.getString("item_id"), new HashMap<>());
		}

		try (Query.Result result = workspaceQuery.items().listPropertiesByParents(new ArrayList<>(byParent.keySet()))) {
			for (AdaptableMap<String, Object> propertyData : result) {
				if (propertyData.getBoolean("is_deleted")) {
					continue;
				}
				Map<String, AdaptableMap<String, Object>> properties = byParent.get(propertyData.getString("parent_item_id"));
				if (properties != null) {
					properties.put(propertyData.getString("item_name"), propertyData);
				}
			}
		} catch (IOException | SQLException ex) {
			return;
		}

		// Nodes without rows are cached as empty so they do not re-query either.
		for (Map.Entry<String, Map<String, AdaptableMap<String, Object>>> e : byParent.entrySet()) {
			workspaceQuery.cacheProperties(e.getKey(), e.getValue(), fFetchRevision);
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fNode, adapterType);
	}

}

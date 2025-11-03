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
import java.math.BigDecimal;
import java.security.Principal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;

import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.rt.jcr.internal.JcrCache;
import org.mintjams.rt.jcr.internal.JcrNode;
import org.mintjams.rt.jcr.internal.JcrRepository;
import org.mintjams.rt.jcr.internal.JcrSession;
import org.mintjams.rt.jcr.internal.WorkspaceQuery;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.sql.Query;

public class JcrXPathQueryResult implements QueryResult, Adaptable {

	private final JcrXPathQuery fQuery;

	public JcrXPathQueryResult(JcrXPathQuery query) throws IOException {
		fQuery = query;
	}

	@Override
	public String[] getColumnNames() throws RepositoryException {
		return new String[0];
	}

	@Override
	public NodeIterator getNodes() throws RepositoryException {
		return new NodeIteratorImpl();
	}

	@Override
	public RowIterator getRows() throws RepositoryException {
		return new NodeIteratorImpl();
	}

	@Override
	public String[] getSelectorNames() throws RepositoryException {
		return new String[0];
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fQuery, adapterType);
	}

	private class NodeIteratorImpl implements NodeIterator, RowIterator {
		private int fOffset;
		private int fLimit;
		private final List<Principal> fAuthorizables = new ArrayList<>();
		private long fPosition;
		private long fTotalHits = -1;
		private final List<SearchIndex.QueryResult.Row> fFetchList = new ArrayList<>();
		private Map<String, AdaptableMap<String, Object>> fFetchItems;
		private Node fNextNode;
		private SearchIndex.QueryResult.Row fNextRow;
		private Node fCurrentNode;
		private SearchIndex.QueryResult.Row fCurrentRow;
		private boolean fFetchMore = true;
		private int fFetchSize;

		private NodeIteratorImpl() {
			if (fQuery.getOffset() != -1) {
				fOffset = Math.toIntExact(fQuery.getOffset());
			}
			fLimit = (fQuery.getLimit() != -1) ? Math.toIntExact(fQuery.getLimit()) : 100;
			JcrSession session = adaptTo(JcrSession.class);
			if (!session.isSystem() && !session.isService() && !session.isAdmin()) {
				fAuthorizables.add(new EveryonePrincipal());
				if (!session.isGuest()) {
					fAuthorizables.addAll(session.getGroups());
					fAuthorizables.add(session.getUserPrincipal());
				}
			}
			int cacheSize = adaptTo(JcrRepository.class).getConfiguration().getNodeCacheSize();
			fFetchSize = BigDecimal.valueOf(cacheSize).multiply(BigDecimal.valueOf(0.5)).intValue();
			readNext();
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
				throw new IllegalArgumentException("Invalid skip number: " + skipNum);
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
			fCurrentNode = null;
			fCurrentRow = null;
			if (!hasNext()) {
				throw new IllegalStateException("No more items.");
			}

			fCurrentNode = fNextNode;
			fCurrentRow = fNextRow;
			fPosition++;
			readNext();
			return fCurrentNode;
		}

		@Override
		public Row nextRow() {
			nextNode();
			return new RowImpl(fCurrentNode, fCurrentRow);
		}

		private void readNext() {
			fNextNode = null;
			fNextRow = null;
			while (fNextNode == null) {
				if (fFetchList.isEmpty() && fFetchMore) {
					fetch();
				}
				if (fFetchList.isEmpty()) {
					return;
				}

				try {
					SearchIndex.QueryResult.Row row = fFetchList.remove(0);
					AdaptableMap<String, Object> itemData = fFetchItems.remove(row.getIdentifier());
					adaptTo(JcrCache.class).setNode(itemData);
					AdaptableMap<String, Object> contentItemData = fFetchItems.remove(itemData.getString("item_id") + "/" + JcrNode.JCR_CONTENT_NAME);
					if (contentItemData != null) {
						adaptTo(JcrCache.class).setNode(contentItemData);
					}
					Node node = adaptTo(JcrSession.class).getNodeByIdentifier(itemData.getString("item_id"));
					fNextNode = node;
					fNextRow = row;
				} catch (AccessDeniedException ignore) {
				} catch (RepositoryException ex) {
					throw Cause.create(ex).wrap(IllegalStateException.class);
				}
			}
		}

		private void fetch() {
			fFetchMore = false;
			List<String> identifiers = new ArrayList<>();
			try {
				SearchIndex.Query indexQuery = adaptTo(SearchIndex.class).createQuery(fQuery.getStatement(), "jcr:xpath")
						.setOffset(fOffset).setLimit(fLimit);
				if (!fAuthorizables.isEmpty()) {
					indexQuery.setAuthorizables(fAuthorizables.toArray(Principal[]::new));
				}
				SearchIndex.QueryResult result = indexQuery.execute();
				fTotalHits = result.getTotalHits();
				for (SearchIndex.QueryResult.Row row : result) {
					fFetchList.add(row);
					identifiers.add(row.getIdentifier());
					fOffset++;
					fLimit--;
					if (fLimit <= 0) {
						break;
					}
					if (fFetchList.size() >= fFetchSize) {
						fFetchMore = true;
						break;
					}
				}
			} catch (IOException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}

			fFetchItems = new HashMap<>();
			if (!identifiers.isEmpty()) {
				try (Query.Result result = adaptTo(WorkspaceQuery.class).items().collectNodesByIdentifier(identifiers.toArray(String[]::new))) {
					for (AdaptableMap<String, Object> itemData : result) {
						if (itemData.getString("item_name").equals(JcrNode.JCR_CONTENT_NAME)) {
							fFetchItems.put(itemData.getString("parent_item_id") + "/" + JcrNode.JCR_CONTENT_NAME, itemData);
						} else {
							fFetchItems.put(itemData.getString("item_id"), itemData);
						}
					}
				} catch (IOException | SQLException ex) {
					throw Cause.create(ex).wrap(IllegalStateException.class);
				}
			}
		}
	}

	private class RowImpl implements Row {
		private final Node fNode;
		private final SearchIndex.QueryResult.Row fRow;

		private RowImpl(Node node, SearchIndex.QueryResult.Row row) {
			fNode = node;
			fRow = row;
		}

		@Override
		public Node getNode() throws RepositoryException {
			return fNode;
		}

		@Override
		public Node getNode(String selectorName) throws RepositoryException {
			return null;
		}

		@Override
		public String getPath() throws RepositoryException {
			return getNode().getPath();
		}

		@Override
		public String getPath(String selectorName) throws RepositoryException {
			return null;
		}

		@Override
		public double getScore() throws RepositoryException {
			return fRow.getScore();
		}

		@Override
		public double getScore(String selectorName) throws RepositoryException {
			return 0;
		}

		@Override
		public Value getValue(String columnName) throws ItemNotFoundException, RepositoryException {
			return null;
		}

		@Override
		public Value[] getValues() throws RepositoryException {
			return new Value[0];
		}
	}

}

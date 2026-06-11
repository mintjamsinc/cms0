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

package org.mintjams.rt.jcr.internal.cluster;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Iterator;

import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.sql.Query;
import org.mintjams.tools.sql.Update;

/**
 * Data access for the cluster journal: the {@code jcr_journal_commits}
 * table records, in commit order, every transaction that wrote journal
 * entries, and {@code jcr_journal_offsets} records how far each node has
 * consumed it. Together they let every node replay the changes the other
 * nodes committed — the backbone of cross-node search-index updates,
 * cache invalidation, and event propagation.
 *
 * <p>All methods operate on the caller's connection and leave transaction
 * control to the caller.
 */
public final class ClusterJournal {

	/**
	 * The reserved offsets row that records the highest commit marker
	 * removed by retention. A node whose consumed position falls behind
	 * this mark has missed changes it can no longer replay and must rebuild
	 * its node-local state (search index) from the repository content.
	 */
	public static final String PURGED_NODE_ID = "#purged";

	private ClusterJournal() {}

	/**
	 * Records that the given transaction has been committed by the given
	 * node. Must be executed within the committing transaction so that the
	 * marker becomes visible if and only if the changes do.
	 */
	public static void writeCommitMarker(Connection connection, String transactionId, String nodeId)
			throws SQLException {
		Update.newBuilder(connection)
				.setStatement("INSERT INTO jcr_journal_commits (transaction_id, node_id, committed)"
						+ " VALUES ({{transactionId}}, {{nodeId}}, {{committed}})")
				.setVariable("transactionId", transactionId)
				.setVariable("nodeId", nodeId)
				.setVariable("committed", System.currentTimeMillis())
				.build().execute();
	}

	/**
	 * Lists commit markers written by other nodes after the given position,
	 * oldest first.
	 */
	public static Query.Result listCommittedTransactions(Connection connection, long afterSeq, String excludeNodeId,
			int limit) throws SQLException {
		return Query.newBuilder(connection)
				.setStatement("SELECT commit_seq, transaction_id, committed FROM jcr_journal_commits"
						+ " WHERE commit_seq > {{seq}} AND node_id <> {{nodeId}}"
						+ " ORDER BY commit_seq")
				.setVariable("seq", afterSeq)
				.setVariable("nodeId", excludeNodeId)
				.build().setOffset(0).setLimit(limit).execute();
	}

	/**
	 * Returns the node's consumed position, or {@code -1} when the node has
	 * never consumed the journal.
	 */
	public static long getConsumedSeq(Connection connection, String nodeId) throws SQLException {
		try (Query.Result result = Query.newBuilder(connection)
				.setStatement("SELECT consumed_seq FROM jcr_journal_offsets WHERE node_id = {{nodeId}}")
				.setVariable("nodeId", nodeId)
				.build().setOffset(0).setLimit(1).execute()) {
			Iterator<AdaptableMap<String, Object>> i = result.iterator();
			if (!i.hasNext()) {
				return -1;
			}
			return i.next().getLong("consumed_seq");
		} catch (SQLException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SQLException(ex);
		}
	}

	public static void setConsumedSeq(Connection connection, String nodeId, long seq) throws SQLException {
		int updated = Update.newBuilder(connection)
				.setStatement("UPDATE jcr_journal_offsets SET consumed_seq = {{seq}}, updated = {{updated}}"
						+ " WHERE node_id = {{nodeId}}")
				.setVariable("seq", seq)
				.setVariable("updated", System.currentTimeMillis())
				.setVariable("nodeId", nodeId)
				.build().execute();
		if (updated == 0) {
			Update.newBuilder(connection)
					.setStatement("INSERT INTO jcr_journal_offsets (node_id, consumed_seq, updated)"
							+ " VALUES ({{nodeId}}, {{seq}}, {{updated}})")
					.setVariable("nodeId", nodeId)
					.setVariable("seq", seq)
					.setVariable("updated", System.currentTimeMillis())
					.build().execute();
		}
	}

	public static void deleteConsumedSeq(Connection connection, String nodeId) throws SQLException {
		Update.newBuilder(connection)
				.setStatement("DELETE FROM jcr_journal_offsets WHERE node_id = {{nodeId}}")
				.setVariable("nodeId", nodeId)
				.build().execute();
	}

	/**
	 * Returns the highest commit marker position, or {@code 0} when the
	 * journal is empty. A node that starts consuming begins here: history
	 * before this point is covered by the node's initial index build.
	 */
	public static long getMaxCommitSeq(Connection connection) throws SQLException {
		try (Query.Result result = Query.newBuilder(connection)
				.setStatement("SELECT MAX(commit_seq) AS max_seq FROM jcr_journal_commits")
				.build().setOffset(0).setLimit(1).execute()) {
			Iterator<AdaptableMap<String, Object>> i = result.iterator();
			if (!i.hasNext()) {
				return 0;
			}
			AdaptableMap<String, Object> row = i.next();
			if (row.get("max_seq") == null) {
				return 0;
			}
			return row.getLong("max_seq");
		} catch (SQLException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SQLException(ex);
		}
	}

	/**
	 * Removes commit markers older than the given retention period and
	 * advances the {@link #PURGED_NODE_ID} mark accordingly. Returns the
	 * number of markers removed.
	 */
	public static int purgeCommitMarkers(Connection connection, long retentionMillis) throws SQLException {
		long cutoff = System.currentTimeMillis() - retentionMillis;

		long maxPurged;
		try (Query.Result result = Query.newBuilder(connection)
				.setStatement("SELECT MAX(commit_seq) AS max_seq FROM jcr_journal_commits"
						+ " WHERE committed < {{cutoff}}")
				.setVariable("cutoff", cutoff)
				.build().setOffset(0).setLimit(1).execute()) {
			Iterator<AdaptableMap<String, Object>> i = result.iterator();
			if (!i.hasNext()) {
				return 0;
			}
			AdaptableMap<String, Object> row = i.next();
			if (row.get("max_seq") == null) {
				return 0;
			}
			maxPurged = row.getLong("max_seq");
		} catch (SQLException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SQLException(ex);
		}
		if (maxPurged <= 0) {
			return 0;
		}

		if (getConsumedSeq(connection, PURGED_NODE_ID) < maxPurged) {
			setConsumedSeq(connection, PURGED_NODE_ID, maxPurged);
		}

		return Update.newBuilder(connection)
				.setStatement("DELETE FROM jcr_journal_commits WHERE commit_seq <= {{seq}}")
				.setVariable("seq", maxPurged)
				.build().execute();
	}

}

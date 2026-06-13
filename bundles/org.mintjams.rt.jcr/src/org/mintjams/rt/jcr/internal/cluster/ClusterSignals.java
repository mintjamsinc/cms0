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
 * Data access for the cluster signal bus: the {@code jcr_cluster_signals}
 * table carries short-lived, control-plane notifications between the nodes
 * that share a workspace's database — "something changed, refresh" messages
 * that are not themselves repository writes and so never enter the
 * {@link ClusterJournal}.
 *
 * <p>A signal records the publishing node, an OSGi event topic and an opaque
 * serialized payload. Each node re-emits the signals published by the other
 * nodes as local OSGi events, so a node-local event handler sees a remote
 * broadcast exactly as it sees a local post. This is the backbone of
 * cross-node control notifications such as {@code workspaceChanged}.
 *
 * <p>Unlike the journal, signals are deliberately ephemeral: they tell live
 * clients to refresh, so a node that was down when a signal was published has
 * no client to notify and loses nothing. Rows are retained only long enough
 * for every live node to observe them and are then purged by age. No durable
 * per-node offset is kept; a fresh node simply begins at the current head.
 *
 * <p>All methods operate on the caller's connection and leave transaction
 * control to the caller.
 */
public final class ClusterSignals {

	private ClusterSignals() {}

	/**
	 * Publishes a signal under the given topic with the given serialized
	 * payload, attributed to the publishing node so it can be filtered out of
	 * that node's own reads.
	 */
	public static void publish(Connection connection, String nodeId, String topic, String payload)
			throws SQLException {
		Update.newBuilder(connection)
				.setStatement("INSERT INTO jcr_cluster_signals (node_id, topic, payload, created)"
						+ " VALUES ({{nodeId}}, {{topic}}, {{payload}}, {{created}})")
				.setVariable("nodeId", nodeId)
				.setVariable("topic", topic)
				.setVariable("payload", payload)
				.setVariable("created", System.currentTimeMillis())
				.build().execute();
	}

	/**
	 * Lists signals published by other nodes after the given position, oldest
	 * first.
	 */
	public static Query.Result list(Connection connection, long afterSeq, String excludeNodeId, int limit)
			throws SQLException {
		return Query.newBuilder(connection)
				.setStatement("SELECT signal_seq, topic, payload, created FROM jcr_cluster_signals"
						+ " WHERE signal_seq > {{seq}} AND node_id <> {{nodeId}}"
						+ " ORDER BY signal_seq")
				.setVariable("seq", afterSeq)
				.setVariable("nodeId", excludeNodeId)
				.build().setOffset(0).setLimit(limit).execute();
	}

	/**
	 * Returns the highest signal position, or {@code 0} when the bus is empty.
	 * A node that starts consuming begins here: signals published before it
	 * joined were meant for the clients that were live at the time.
	 */
	public static long getMaxSeq(Connection connection) throws SQLException {
		try (Query.Result result = Query.newBuilder(connection)
				.setStatement("SELECT MAX(signal_seq) AS max_seq FROM jcr_cluster_signals")
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
	 * Removes signals older than the given retention period. Returns the
	 * number of rows removed.
	 */
	public static int purge(Connection connection, long retentionMillis) throws SQLException {
		long cutoff = System.currentTimeMillis() - retentionMillis;
		return Update.newBuilder(connection)
				.setStatement("DELETE FROM jcr_cluster_signals WHERE created < {{cutoff}}")
				.setVariable("cutoff", cutoff)
				.build().execute();
	}

}

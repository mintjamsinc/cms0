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

package org.mintjams.rt.cms.internal.graphql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptables;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GraphQL queries that drive the EIP Console.
 *
 * <p>Three top-level queries:
 * <ul>
 *   <li>{@code routeStats} — time-series counts banded by elapsed (under1s,
 *       under5s, over5s) for the EIP Console graph. Implemented as three
 *       JCR XPath {@code facet accumulate} queries against
 *       {@code /var/eip/history}, executed with {@code limit=0} so document
 *       fetch is skipped and only facet counts are returned.</li>
 *   <li>{@code historyExchanges} — Lucene-backed list query against the same
 *       history records, returned as a Relay-style cursor connection.</li>
 *   <li>{@code historyExchange} — full detail for a single exchange (JSON
 *       parsed and returned).</li>
 * </ul>
 */
public class EipStatsQueryExecutor {

	private static final String HISTORY_BASE = "/var/eip/history";
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
			new TypeReference<LinkedHashMap<String, Object>>() {};

	private static final long BAND_1S_MS = 1_000L;
	private static final long BAND_5S_MS = 5_000L;

	private final Session session;
	private final ObjectMapper mapper = new ObjectMapper();

	public EipStatsQueryExecutor(Session session) {
		this.session = session;
	}

	// =========================================================================
	// routeStats — three-band time series for the chart panel
	//
	// Returned series has, for every time bucket between [from, to):
	//   - under1s : count of exchanges where elapsed <  1000ms
	//   - under5s : count of exchanges where 1000 <= elapsed < 5000ms
	//   - over5s  : count of exchanges where elapsed >= 5000ms
	//
	// Each band is computed by a single facet-accumulate query that buckets
	// @mi:createdAt into N ranges, scoped by the elapsed predicate. The
	// resulting Lucene facet counts are mapped back onto the time series.
	// =========================================================================

	public Map<String, Object> executeRouteStatsQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> vars = request.getVariables();
		Instant from = Instant.parse(requireString(vars, "from"));
		Instant to = Instant.parse(requireString(vars, "to"));
		List<String> routes = optStringList(vars, "routes");
		String status = optString(vars, "status");
		Interval interval = resolveInterval(optString(vars, "interval"), from, to);

		// Build the list of time buckets (label = index, [start, end)).
		List<Bucket> buckets = buildBuckets(from, to, interval);

		// Base predicate: filters every band shares.
		StringBuilder basePred = new StringBuilder("@mi:exchangeId");
		appendRangeFilter(basePred, from, to);
		appendRouteFilter(basePred, routes);
		appendStatusFilter(basePred, status);

		long[] under1s = runBandFacet(basePred, "@mi:elapsed < " + BAND_1S_MS, buckets);
		long[] under5s = runBandFacet(basePred,
				"@mi:elapsed >= " + BAND_1S_MS + " and @mi:elapsed < " + BAND_5S_MS, buckets);
		long[] over5s = runBandFacet(basePred, "@mi:elapsed >= " + BAND_5S_MS, buckets);

		List<Map<String, Object>> points = new ArrayList<>(buckets.size());
		for (int i = 0; i < buckets.size(); i++) {
			Map<String, Object> point = new LinkedHashMap<>();
			point.put("bucket", buckets.get(i).start.toString());
			point.put("under1s", under1s[i]);
			point.put("under5s", under5s[i]);
			point.put("over5s", over5s[i]);
			points.add(point);
		}

		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("from", from.toString());
		stats.put("to", to.toString());
		stats.put("interval", interval.label);
		stats.put("points", points);

		Map<String, Object> data = new HashMap<>();
		data.put("routeStats", stats);
		return data;
	}

	/**
	 * Run one facet-accumulate query over {@code @mi:createdAt} with the given
	 * elapsed-band predicate, and return a parallel array of bucket counts.
	 */
	private long[] runBandFacet(StringBuilder basePred, String elapsedPred, List<Bucket> buckets) throws Exception {
		long[] counts = new long[buckets.size()];
		if (buckets.isEmpty()) {
			return counts;
		}

		StringBuilder xpath = new StringBuilder("/jcr:root").append(HISTORY_BASE)
				.append("//element(*, nt:file)[")
				.append(basePred);
		if (elapsedPred != null && !elapsedPred.isEmpty()) {
			xpath.append(" and ").append(elapsedPred);
		}
		xpath.append("] facet accumulate ");
		for (int i = 0; i < buckets.size(); i++) {
			if (i > 0) {
				xpath.append(", ");
			}
			Bucket b = buckets.get(i);
			xpath.append("range('").append(i).append("', ")
					.append("xs:dateTime('").append(b.start.toString()).append("') <= @mi:createdAt < ")
					.append("xs:dateTime('").append(b.end.toString()).append("'))");
		}

		// The JCR Query is Adaptable. Adapting to SearchIndex lets us bypass the
		// nodes fetch and ask only for the facet counts.
		SearchIndex searchIndex = Adaptables.getAdapter(session, SearchIndex.class);
		if (searchIndex == null) {
			// Fall back to plain JCR query — facet results will be unavailable.
			return counts;
		}

		SearchIndex.QueryResult.FacetResult result = searchIndex
				.createQuery(xpath.toString(), "jcr:xpath")
				.setOffset(0)
				.setLimit(0)
				.execute()
				.getFacetResult();
		SearchIndex.QueryResult.FacetResult.Facet facet = result.getFacet("mi:createdAt");
		if (facet == null) {
			return counts;
		}
		for (String label : facet.getLabels()) {
			int idx;
			try {
				idx = Integer.parseInt(label);
			} catch (NumberFormatException ex) {
				continue;
			}
			if (idx < 0 || idx >= counts.length) {
				continue;
			}
			counts[idx] = facet.getValue(label);
		}
		return counts;
	}

	// =========================================================================
	// historyExchanges — Relay-style cursor connection over filtered records
	// =========================================================================

	private static final int DEFAULT_PAGE_SIZE = 50;
	private static final int MAX_PAGE_SIZE = 500;

	public Map<String, Object> executeHistoryExchangesQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> vars = request.getVariables();
		List<String> routes = optStringList(vars, "routes");
		String status = optString(vars, "status");
		String from = optString(vars, "from");
		String to = optString(vars, "to");
		String filterText = optString(vars, "filter");

		Integer first = optBoxedInt(vars, "first");
		Integer last = optBoxedInt(vars, "last");
		String after = optString(vars, "after");
		String before = optString(vars, "before");

		boolean backward = (last != null) || (before != null && first == null);
		int pageSize = clampPageSize(backward ? last : first);

		// Base predicate: filters that apply to both the page query and the
		// total count query. Anchored on @mi:exchangeId so we only match actual
		// history records (and so the predicate is never empty).
		StringBuilder basePred = new StringBuilder("@mi:exchangeId");
		appendRouteFilter(basePred, routes);
		appendStatusFilter(basePred, status);
		if (from != null && !from.isEmpty()) {
			basePred.append(" and @mi:createdAt >= xs:dateTime('").append(escapeLiteral(from)).append("')");
		}
		if (to != null && !to.isEmpty()) {
			basePred.append(" and @mi:createdAt < xs:dateTime('").append(escapeLiteral(to)).append("')");
		}
		appendFilterText(basePred, filterText);

		// Cursor predicate: separate from basePred so totalCount stays stable
		// across pages. Cursor encodes (createdAt, exchangeId) so we can resume
		// deterministically even when two records share the same millisecond.
		Cursor afterCursor = decodeCursor(after);
		Cursor beforeCursor = decodeCursor(before);
		StringBuilder cursorPred = new StringBuilder();
		if (afterCursor != null) {
			// Newer-first ordering: "after" means strictly older than the cursor.
			cursorPred.append(" and (@mi:createdAt < xs:dateTime('")
					.append(escapeLiteral(afterCursor.createdAt))
					.append("') or (@mi:createdAt = xs:dateTime('")
					.append(escapeLiteral(afterCursor.createdAt))
					.append("') and @mi:exchangeId < '")
					.append(escapeLiteral(afterCursor.exchangeId))
					.append("'))");
		}
		if (beforeCursor != null) {
			cursorPred.append(" and (@mi:createdAt > xs:dateTime('")
					.append(escapeLiteral(beforeCursor.createdAt))
					.append("') or (@mi:createdAt = xs:dateTime('")
					.append(escapeLiteral(beforeCursor.createdAt))
					.append("') and @mi:exchangeId > '")
					.append(escapeLiteral(beforeCursor.exchangeId))
					.append("'))");
		}

		String order = backward ? "ascending" : "descending";
		String xpath = "/jcr:root" + HISTORY_BASE + "//element(*, nt:file)["
				+ basePred + cursorPred + "]"
				+ " order by @mi:createdAt " + order
				+ ", @mi:exchangeId " + order;

		QueryManager qm = session.getWorkspace().getQueryManager();
		Query q = qm.createQuery(xpath, Query.XPATH);
		// Fetch one extra row to detect whether another page exists.
		q.setLimit(pageSize + 1);
		QueryResult result = q.execute();

		List<EdgeRow> rows = new ArrayList<>();
		for (NodeIterator it = result.getNodes(); it.hasNext();) {
			Node fileNode = it.nextNode();
			Map<String, Object> node = buildExchangeSummary(fileNode);
			String createdAt = (String) node.get("createdAt");
			String exchangeId = (String) node.get("exchangeId");
			rows.add(new EdgeRow(node, createdAt, exchangeId));
		}

		boolean hasMore = rows.size() > pageSize;
		if (hasMore) {
			rows = rows.subList(0, pageSize);
		}
		if (backward) {
			Collections.reverse(rows);
		}

		List<Map<String, Object>> edges = new ArrayList<>(rows.size());
		for (EdgeRow r : rows) {
			Map<String, Object> edge = new LinkedHashMap<>();
			edge.put("node", r.node);
			edge.put("cursor", encodeCursor(r.createdAt, r.exchangeId));
			edges.add(edge);
		}

		Map<String, Object> pageInfo = new LinkedHashMap<>();
		boolean hasNextPage = backward ? (beforeCursor != null) : hasMore;
		boolean hasPreviousPage = backward ? hasMore : (afterCursor != null);
		pageInfo.put("hasNextPage", hasNextPage);
		pageInfo.put("hasPreviousPage", hasPreviousPage);
		pageInfo.put("startCursor", edges.isEmpty() ? null : edges.get(0).get("cursor"));
		pageInfo.put("endCursor", edges.isEmpty() ? null : edges.get(edges.size() - 1).get("cursor"));

		String countXpath = "/jcr:root" + HISTORY_BASE + "//element(*, nt:file)[" + basePred + "]";
		long total = countQuery(countXpath);

		Map<String, Object> connection = new LinkedHashMap<>();
		connection.put("edges", edges);
		connection.put("pageInfo", pageInfo);
		connection.put("totalCount", total);

		Map<String, Object> data = new HashMap<>();
		data.put("historyExchanges", connection);
		return data;
	}

	// =========================================================================
	// historyExchange — full detail for the right-pane inspector
	// =========================================================================

	public Map<String, Object> executeHistoryExchangeQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> vars = request.getVariables();
		String exchangeId = requireString(vars, "exchangeId");

		String xpath = "/jcr:root" + HISTORY_BASE + "//element(*, nt:file)["
				+ "@mi:exchangeId = '" + escapeLiteral(exchangeId) + "']";

		QueryManager qm = session.getWorkspace().getQueryManager();
		Query q = qm.createQuery(xpath, Query.XPATH);
		q.setLimit(1);
		QueryResult result = q.execute();
		NodeIterator it = result.getNodes();
		if (!it.hasNext()) {
			Map<String, Object> data = new HashMap<>();
			data.put("historyExchange", null);
			return data;
		}

		Node fileNode = it.nextNode();
		Map<String, Object> detail = buildExchangeDetail(fileNode);

		Map<String, Object> data = new HashMap<>();
		data.put("historyExchange", detail);
		return data;
	}

	// =========================================================================
	// Helpers — predicates & XPath fragments
	// =========================================================================

	private static void appendRouteFilter(StringBuilder pred, List<String> routes) {
		if (routes == null || routes.isEmpty()) {
			return;
		}
		if (routes.size() == 1) {
			pred.append(" and @mi:routeId = '").append(escapeLiteral(routes.get(0))).append("'");
			return;
		}
		pred.append(" and (");
		for (int i = 0; i < routes.size(); i++) {
			if (i > 0) pred.append(" or ");
			pred.append("@mi:routeId = '").append(escapeLiteral(routes.get(i))).append("'");
		}
		pred.append(")");
	}

	private static void appendStatusFilter(StringBuilder pred, String status) {
		if (status == null || status.isEmpty() || "all".equalsIgnoreCase(status)) {
			return;
		}
		pred.append(" and @mi:status = '").append(escapeLiteral(status)).append("'");
	}

	private static void appendRangeFilter(StringBuilder pred, Instant from, Instant to) {
		pred.append(" and @mi:createdAt >= xs:dateTime('").append(from.toString()).append("')");
		pred.append(" and @mi:createdAt < xs:dateTime('").append(to.toString()).append("')");
	}

	/**
	 * Match the supplied free-text filter against any of {@code mi:businessKey},
	 * {@code mi:exchangeId} or {@code mi:routeId} (substring, OR'd).
	 */
	private static void appendFilterText(StringBuilder pred, String filter) {
		if (filter == null || filter.isBlank()) {
			return;
		}
		String escaped = escapeLiteral(filter.trim());
		pred.append(" and (")
				.append("jcr:contains(@mi:businessKey, '").append(escaped).append("')")
				.append(" or jcr:contains(@mi:exchangeId, '").append(escaped).append("')")
				.append(" or jcr:contains(@mi:routeId, '").append(escaped).append("')")
				.append(")");
	}

	private long countQuery(String xpath) {
		try {
			QueryManager qm = session.getWorkspace().getQueryManager();
			Query q = qm.createQuery(xpath, Query.XPATH);
			q.setLimit(10_000);
			QueryResult r = q.execute();
			long n = 0;
			for (NodeIterator it = r.getNodes(); it.hasNext(); it.nextNode()) {
				n++;
			}
			return n;
		} catch (Exception ex) {
			return -1L;
		}
	}

	// =========================================================================
	// Helpers — time buckets
	// =========================================================================

	private enum Interval {
		FIVE_MINUTES("5min", ChronoUnit.MINUTES, 5),
		ONE_HOUR("1h", ChronoUnit.HOURS, 1),
		ONE_DAY("1d", ChronoUnit.DAYS, 1);

		final String label;
		final ChronoUnit unit;
		final int amount;

		Interval(String label, ChronoUnit unit, int amount) {
			this.label = label;
			this.unit = unit;
			this.amount = amount;
		}

		Instant truncate(Instant t) {
			if (unit == ChronoUnit.DAYS) {
				return t.truncatedTo(ChronoUnit.DAYS);
			}
			Instant truncated = t.truncatedTo(unit);
			if (unit == ChronoUnit.MINUTES && amount > 1) {
				long m = truncated.atZone(java.time.ZoneOffset.UTC).getMinute();
				long aligned = (m / amount) * amount;
				return truncated.minus(m - aligned, ChronoUnit.MINUTES);
			}
			return truncated;
		}

		Instant endOf(Instant start) {
			return start.plus(amount, unit);
		}

		static Interval forLabel(String label) {
			if (label == null) return null;
			for (Interval i : values()) {
				if (i.label.equalsIgnoreCase(label)) return i;
			}
			return null;
		}
	}

	private static final class Bucket {
		final Instant start;
		final Instant end;

		Bucket(Instant start, Instant end) {
			this.start = start;
			this.end = end;
		}
	}

	private Interval resolveInterval(String label, Instant from, Instant to) {
		Interval explicit = Interval.forLabel(label);
		if (explicit != null) return explicit;
		Duration span = Duration.between(from, to);
		if (span.compareTo(Duration.ofHours(2)) <= 0) return Interval.FIVE_MINUTES;
		if (span.compareTo(Duration.ofDays(2)) <= 0) return Interval.ONE_HOUR;
		return Interval.ONE_DAY;
	}

	private List<Bucket> buildBuckets(Instant from, Instant to, Interval interval) {
		List<Bucket> buckets = new ArrayList<>();
		Instant cursor = interval.truncate(from);
		while (cursor.isBefore(to)) {
			Instant next = interval.endOf(cursor);
			buckets.add(new Bucket(cursor, next));
			cursor = next;
		}
		return buckets;
	}

	// =========================================================================
	// Helpers — cursor
	// =========================================================================

	private static int clampPageSize(Integer requested) {
		if (requested == null || requested <= 0) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.min(requested, MAX_PAGE_SIZE);
	}

	private static String encodeCursor(String createdAt, String exchangeId) {
		if (createdAt == null || exchangeId == null) {
			return null;
		}
		String raw = createdAt + "|" + exchangeId;
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static Cursor decodeCursor(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			int sep = raw.indexOf('|');
			if (sep <= 0 || sep >= raw.length() - 1) {
				return null;
			}
			return new Cursor(raw.substring(0, sep), raw.substring(sep + 1));
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static final class Cursor {
		final String createdAt;
		final String exchangeId;

		Cursor(String createdAt, String exchangeId) {
			this.createdAt = createdAt;
			this.exchangeId = exchangeId;
		}
	}

	private static final class EdgeRow {
		final Map<String, Object> node;
		final String createdAt;
		final String exchangeId;

		EdgeRow(Map<String, Object> node, String createdAt, String exchangeId) {
			this.node = node;
			this.createdAt = createdAt;
			this.exchangeId = exchangeId;
		}
	}

	// =========================================================================
	// Helpers — node projection
	// =========================================================================

	private Map<String, Object> buildExchangeSummary(Node fileNode) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("exchangeId", propString(fileNode, "mi:exchangeId"));
		m.put("routeId", propString(fileNode, "mi:routeId"));
		m.put("status", propString(fileNode, "mi:status"));
		m.put("elapsed", propLong(fileNode, "mi:elapsed"));
		m.put("createdAt", propIsoDate(fileNode, "mi:createdAt"));
		m.put("businessKey", propString(fileNode, "mi:businessKey"));
		return m;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> buildExchangeDetail(Node fileNode) throws Exception {
		Map<String, Object> detail = new LinkedHashMap<>();
		String json = JCRs.getContentAsString(fileNode);
		Map<String, Object> record = (json == null || json.isEmpty())
				? new LinkedHashMap<>() : mapper.readValue(json, MAP_TYPE);

		detail.put("exchangeId", record.get("exchangeId"));
		detail.put("routeId", record.get("routeId"));
		detail.put("status", record.get("status"));
		detail.put("elapsed", record.get("elapsed"));
		detail.put("createdAt", record.get("createdAt"));
		detail.put("completedAt", record.get("completedAt"));
		detail.put("exceptionType", record.get("exceptionType"));
		detail.put("exceptionMessage", record.get("exceptionMessage"));
		detail.put("businessKey", record.get("businessKey"));
		detail.put("bodyType", record.get("bodyType"));
		detail.put("bodySize", record.get("bodySize"));
		detail.put("headers", record.get("headers"));

		Object stepsObj = record.get("steps");
		List<Map<String, Object>> steps = new ArrayList<>();
		if (stepsObj instanceof List) {
			for (Object o : (List<Object>) stepsObj) {
				if (o instanceof Map) {
					Map<String, Object> s = (Map<String, Object>) o;
					Map<String, Object> step = new LinkedHashMap<>();
					step.put("id", s.get("id"));
					step.put("endpointUri", s.get("endpointUri"));
					step.put("timeTaken", s.get("timeTaken"));
					step.put("offsetFromStart", s.get("offsetFromStart"));
					step.put("order", s.get("order"));
					steps.add(step);
				}
			}
		}
		detail.put("steps", steps);
		return detail;
	}

	private static String propString(Node fileNode, String name) throws Exception {
		Node content = JCRs.getContentNode(fileNode);
		if (!content.hasProperty(name)) {
			return null;
		}
		return content.getProperty(name).getString();
	}

	private static Long propLong(Node fileNode, String name) throws Exception {
		Node content = JCRs.getContentNode(fileNode);
		if (!content.hasProperty(name)) {
			return null;
		}
		return content.getProperty(name).getLong();
	}

	private static String propIsoDate(Node fileNode, String name) throws Exception {
		Node content = JCRs.getContentNode(fileNode);
		if (!content.hasProperty(name)) {
			return null;
		}
		return Instant.ofEpochMilli(content.getProperty(name).getDate().getTimeInMillis()).toString();
	}

	// =========================================================================
	// Helpers — request variables
	// =========================================================================

	private static String optString(Map<String, Object> vars, String key) {
		if (vars == null) {
			return null;
		}
		Object v = vars.get(key);
		return v == null ? null : v.toString();
	}

	@SuppressWarnings("unchecked")
	private static List<String> optStringList(Map<String, Object> vars, String key) {
		if (vars == null) {
			return Collections.emptyList();
		}
		Object v = vars.get(key);
		if (v == null) {
			return Collections.emptyList();
		}
		if (v instanceof List) {
			List<String> out = new ArrayList<>();
			for (Object o : (List<Object>) v) {
				if (o != null) {
					String s = o.toString();
					if (!s.isEmpty()) {
						out.add(s);
					}
				}
			}
			return out;
		}
		String s = v.toString();
		return s.isEmpty() ? Collections.emptyList() : List.of(s);
	}

	private static String requireString(Map<String, Object> vars, String key) {
		String v = optString(vars, key);
		if (v == null || v.isEmpty()) {
			throw new IllegalArgumentException("Missing required variable: " + key);
		}
		return v;
	}

	private static Integer optBoxedInt(Map<String, Object> vars, String key) {
		if (vars == null) {
			return null;
		}
		Object v = vars.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof Number) {
			return ((Number) v).intValue();
		}
		try {
			return Integer.parseInt(v.toString());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String escapeLiteral(String v) {
		return v.replace("'", "\\'");
	}

	@SuppressWarnings("unused")
	private static void ioGuard(IOException ex) {
		throw new IllegalStateException(ex);
	}
}

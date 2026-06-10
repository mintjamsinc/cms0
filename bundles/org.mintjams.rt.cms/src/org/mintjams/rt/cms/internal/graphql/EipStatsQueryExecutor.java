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
		List<String> routes = optStringList(vars, "routes");
		String status = optString(vars, "status");
		String intervalLabel = optString(vars, "interval");

		// Resolve the time window. Two modes:
		//   anchor — a single {@code at} instant + {@code buckets} count. The
		//            window's right edge is the fixed wall-clock bucket that
		//            contains the anchor; we then walk back exactly {@code buckets}
		//            whole intervals. Every bucket is a full interval (no partial
		//            edges) and the window slides one bucket at a time as the
		//            anchor advances. The live console uses this mode so that
		//            between bucket boundaries only the right-edge bucket changes.
		//   window — a legacy explicit [from, to) range, used when {@code buckets}
		//            is omitted; the interval is auto-resolved from the span.
		Instant anchor;
		Instant from;
		Instant to;
		Interval interval;
		Integer bucketCount = optBoxedInt(vars, "buckets");
		if (bucketCount != null && bucketCount > 0) {
			String at = optString(vars, "at");
			anchor = (at != null && !at.isEmpty()) ? Instant.parse(at) : Instant.now();
			interval = Interval.forLabel(intervalLabel);
			if (interval == null) {
				interval = Interval.FIVE_MINUTES;
			}
			Instant rightStart = interval.truncate(anchor);
			to = interval.endOf(rightStart);
			from = rightStart.minus((long) (bucketCount - 1) * interval.amount, interval.unit);
		} else {
			from = Instant.parse(requireString(vars, "from"));
			to = Instant.parse(requireString(vars, "to"));
			interval = resolveInterval(intervalLabel, from, to);
			anchor = to;
		}

		// Build the list of time buckets (label = index, [start, end)).
		List<Bucket> buckets = buildBuckets(from, to, interval);

		// Elapsed band boundaries (ms, ascending). N boundaries => N+1 bands.
		// The default mirrors the historical 3-band view (<1s, 1–5s, >=5s) so
		// callers that omit the argument get the same chart as before.
		List<Long> boundaries = sanitizeBoundaries(optLongList(vars, "elapsedBoundaries"));
		int bandCount = boundaries.size() + 1;

		// Base predicate: filters every band shares.
		StringBuilder basePred = new StringBuilder("@mi:exchangeId");
		appendRangeFilter(basePred, from, to);
		appendRouteFilter(basePred, routes);
		appendStatusFilter(basePred, status);

		// One facet-accumulate query per band; collect per-bucket counts.
		long[][] bandCounts = new long[bandCount][];
		for (int b = 0; b < bandCount; b++) {
			bandCounts[b] = runBandFacet(basePred, elapsedBandPredicate(boundaries, b), buckets);
		}

		List<Map<String, Object>> points = new ArrayList<>(buckets.size());
		for (int i = 0; i < buckets.size(); i++) {
			Map<String, Object> point = new LinkedHashMap<>();
			point.put("bucket", buckets.get(i).start.toString());
			List<Long> bands = new ArrayList<>(bandCount);
			for (int b = 0; b < bandCount; b++) {
				bands.add(bandCounts[b][i]);
			}
			point.put("bands", bands);
			points.add(point);
		}

		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("anchor", anchor.toString());
		stats.put("from", from.toString());
		stats.put("to", to.toString());
		stats.put("interval", interval.label);
		stats.put("boundaries", boundaries);
		stats.put("points", points);

		Map<String, Object> data = new HashMap<>();
		data.put("routeStats", stats);
		return data;
	}

	/**
	 * Normalise requested elapsed boundaries: drop non-positive / duplicate
	 * values and sort ascending. An empty request falls back to the default
	 * {@code {1000, 5000}} (the historical three-band view).
	 */
	private static List<Long> sanitizeBoundaries(List<Long> raw) {
		java.util.TreeSet<Long> set = new java.util.TreeSet<>();
		if (raw != null) {
			for (Long v : raw) {
				if (v != null && v > 0) {
					set.add(v);
				}
			}
		}
		if (set.isEmpty()) {
			set.add(BAND_1S_MS);
			set.add(BAND_5S_MS);
		}
		return new ArrayList<>(set);
	}

	/**
	 * Elapsed predicate for band index {@code b} given sorted boundaries.
	 * Band 0 is {@code elapsed < boundaries[0]}, the last band is
	 * {@code elapsed >= boundaries[last]}, and interior bands are half-open
	 * {@code [boundaries[b-1], boundaries[b])}.
	 */
	private static String elapsedBandPredicate(List<Long> boundaries, int b) {
		int n = boundaries.size();
		if (b == 0) {
			return "@mi:elapsed < " + boundaries.get(0);
		}
		if (b >= n) {
			return "@mi:elapsed >= " + boundaries.get(n - 1);
		}
		return "@mi:elapsed >= " + boundaries.get(b - 1) + " and @mi:elapsed < " + boundaries.get(b);
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
		List<String> elapsedBands = optStringList(vars, "elapsedBands");

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
		appendElapsedBandsFilter(basePred, elapsedBands);

		// Cursor predicate: separate from basePred so totalCount stays stable
		// across pages. The cursor encodes the full sort key (createdAt,
		// exchangeId, routeId) so the keyset is a strict total order. Without
		// the routeId tie-breaker, the two route-records of a multi-route
		// exchange (identical createdAt AND exchangeId) would be
		// indistinguishable at a page boundary and one would be silently
		// dropped from the next page.
		Cursor afterCursor = decodeCursor(after);
		Cursor beforeCursor = decodeCursor(before);
		StringBuilder cursorPred = new StringBuilder();
		if (afterCursor != null) {
			// Newer-first ordering: "after" means strictly older than the cursor.
			appendKeysetPredicate(cursorPred, afterCursor, "<");
		}
		if (beforeCursor != null) {
			appendKeysetPredicate(cursorPred, beforeCursor, ">");
		}

		String order = backward ? "ascending" : "descending";
		// @mi:createdAt is a DATE property (indexed with SortedNumericDocValues).
		// The XPath sort type is resolved from the order-by expression: a bare
		// @mi:createdAt resolves to a STRING sort (the field-type provider only
		// knows deployed facets), which does not match the numeric doc-values and
		// silently degrades to index (insertion) order — newest-first paging then
		// returns the OLDEST page. Wrap it in xs:dateTime(...) so the engine sorts
		// it numerically (chronologically). exchangeId/routeId are strings and sort
		// correctly as-is.
		String xpath = "/jcr:root" + HISTORY_BASE + "//element(*, nt:file)["
				+ basePred + cursorPred + "]"
				+ " order by xs:dateTime(@mi:createdAt) " + order
				+ ", @mi:exchangeId " + order
				+ ", @mi:routeId " + order;

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
			String routeId = (String) node.get("routeId");
			rows.add(new EdgeRow(node, createdAt, exchangeId, routeId));
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
			edge.put("cursor", encodeCursor(r.createdAt, r.exchangeId, r.routeId));
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
		String path = optString(vars, "path");

		Node fileNode;
		if (path != null && !path.isEmpty()) {
			// Address the exact record by its node path. A single exchange may
			// have one record per route (same exchangeId), so resolving by
			// exchangeId alone is ambiguous; the list passes the row's path.
			// Constrain to the history subtree so the argument cannot be used to
			// read arbitrary nodes.
			if (!path.equals(HISTORY_BASE) && !path.startsWith(HISTORY_BASE + "/")) {
				throw new IllegalArgumentException("path must be under " + HISTORY_BASE);
			}
			if (!session.nodeExists(path)) {
				Map<String, Object> data = new HashMap<>();
				data.put("historyExchange", null);
				return data;
			}
			fileNode = session.getNode(path);
		} else {
			// Back-compat: resolve by exchangeId (first match).
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
			fileNode = it.nextNode();
		}

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
	 * Restrict history results to one or more elapsed bands, mirroring the
	 * three bands that {@code routeStats} produces:
	 *
	 * <pre>
	 *   under1s — @mi:elapsed &lt; 1000
	 *   under5s — 1000 &lt;= @mi:elapsed &lt; 5000
	 *   over5s  — @mi:elapsed &gt;= 5000
	 * </pre>
	 *
	 * Unknown band names are ignored. When every known band is selected (or
	 * the list is empty / null) the filter is a no-op so {@code totalCount}
	 * stays comparable to the unfiltered query.
	 */
	private static void appendElapsedBandsFilter(StringBuilder pred, List<String> bands) {
		if (bands == null || bands.isEmpty()) {
			return;
		}
		boolean under1s = false, under5s = false, over5s = false;
		for (String b : bands) {
			if (b == null) continue;
			switch (b) {
				case "under1s": under1s = true; break;
				case "under5s": under5s = true; break;
				case "over5s":  over5s  = true; break;
				default: /* unknown — ignore */ break;
			}
		}
		// No recognised bands → caller is filtering with garbage. Be strict:
		// match nothing rather than silently fall through.
		if (!under1s && !under5s && !over5s) {
			pred.append(" and @mi:elapsed < 0");
			return;
		}
		// All three bands selected covers the full domain — skip the predicate.
		if (under1s && under5s && over5s) {
			return;
		}
		List<String> clauses = new ArrayList<>(3);
		if (under1s) {
			clauses.add("@mi:elapsed < " + BAND_1S_MS);
		}
		if (under5s) {
			clauses.add("(@mi:elapsed >= " + BAND_1S_MS + " and @mi:elapsed < " + BAND_5S_MS + ")");
		}
		if (over5s) {
			clauses.add("@mi:elapsed >= " + BAND_5S_MS);
		}
		pred.append(" and (");
		for (int i = 0; i < clauses.size(); i++) {
			if (i > 0) pred.append(" or ");
			pred.append(clauses.get(i));
		}
		pred.append(")");
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

	/**
	 * Exact match count for {@code totalCount}. Executed with {@code limit=0}
	 * so the query fetches no nodes at all; the count comes from the search
	 * index via {@link NodeIterator#getSize()}, which counts every match
	 * (uncapped) without materialising documents. Iterating nodes here would
	 * scale with the match volume — a wide time window over a busy history
	 * took a minute and capped the count — whereas the index count is
	 * effectively constant-time.
	 */
	private long countQuery(String xpath) {
		try {
			QueryManager qm = session.getWorkspace().getQueryManager();
			Query q = qm.createQuery(xpath, Query.XPATH);
			q.setLimit(0);
			QueryResult r = q.execute();
			return r.getNodes().getSize();
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

	/**
	 * Append a keyset (seek) predicate for the (createdAt, exchangeId, routeId)
	 * sort key, comparing strictly in {@code cmp} direction ({@code "<"} for a
	 * descending "after" page, {@code ">"} for "before"). The clause expands the
	 * lexicographic tuple comparison:
	 *
	 * <pre>
	 *   createdAt cmp C
	 *   OR (createdAt = C AND exchangeId cmp X)
	 *   OR (createdAt = C AND exchangeId = X AND routeId cmp R)
	 * </pre>
	 *
	 * The routeId tier is only emitted when the cursor carries one (legacy
	 * two-part cursors decode with a null routeId and degrade gracefully).
	 */
	private static void appendKeysetPredicate(StringBuilder pred, Cursor cursor, String cmp) {
		String c = escapeLiteral(cursor.createdAt);
		String x = escapeLiteral(cursor.exchangeId);
		pred.append(" and (@mi:createdAt ").append(cmp).append(" xs:dateTime('").append(c).append("')")
				.append(" or (@mi:createdAt = xs:dateTime('").append(c).append("') and @mi:exchangeId ")
				.append(cmp).append(" '").append(x).append("')");
		if (cursor.routeId != null) {
			String r = escapeLiteral(cursor.routeId);
			pred.append(" or (@mi:createdAt = xs:dateTime('").append(c).append("') and @mi:exchangeId = '")
					.append(x).append("' and @mi:routeId ").append(cmp).append(" '").append(r).append("')");
		}
		pred.append(")");
	}

	private static String encodeCursor(String createdAt, String exchangeId, String routeId) {
		if (createdAt == null || exchangeId == null) {
			return null;
		}
		// routeId is part of the sort key; coalesce a missing one to "" so the
		// cursor always carries all three components.
		String raw = createdAt + "|" + exchangeId + "|" + (routeId == null ? "" : routeId);
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static Cursor decodeCursor(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			// Split on the first two separators only: createdAt (ISO) and
			// exchangeId never contain '|', but a routeId might, so the third
			// component is the unsplit remainder.
			int sep1 = raw.indexOf('|');
			if (sep1 <= 0 || sep1 >= raw.length() - 1) {
				return null;
			}
			int sep2 = raw.indexOf('|', sep1 + 1);
			if (sep2 < 0) {
				// Legacy two-part cursor (no routeId tier).
				return new Cursor(raw.substring(0, sep1), raw.substring(sep1 + 1), null);
			}
			return new Cursor(raw.substring(0, sep1), raw.substring(sep1 + 1, sep2), raw.substring(sep2 + 1));
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static final class Cursor {
		final String createdAt;
		final String exchangeId;
		final String routeId;

		Cursor(String createdAt, String exchangeId, String routeId) {
			this.createdAt = createdAt;
			this.exchangeId = exchangeId;
			this.routeId = routeId;
		}
	}

	private static final class EdgeRow {
		final Map<String, Object> node;
		final String createdAt;
		final String exchangeId;
		final String routeId;

		EdgeRow(Map<String, Object> node, String createdAt, String exchangeId, String routeId) {
			this.node = node;
			this.createdAt = createdAt;
			this.exchangeId = exchangeId;
			this.routeId = routeId;
		}
	}

	// =========================================================================
	// Helpers — node projection
	// =========================================================================

	private Map<String, Object> buildExchangeSummary(Node fileNode) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		// The JCR node path is the only stable, globally-unique identity of a
		// history record. A single exchange that completes in more than one
		// route yields one record per route — same exchangeId AND same
		// createdAt (the exchange's creation time) — so exchangeId alone is not
		// unique. Clients key list rows and open the inspector by this path.
		m.put("path", fileNode.getPath());
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

		// Stable unique identity (see buildExchangeSummary).
		detail.put("path", fileNode.getPath());
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

	@SuppressWarnings("unchecked")
	private static List<Long> optLongList(Map<String, Object> vars, String key) {
		List<Long> out = new ArrayList<>();
		if (vars == null) {
			return out;
		}
		Object v = vars.get(key);
		if (v == null) {
			return out;
		}
		if (v instanceof List) {
			for (Object o : (List<Object>) v) {
				Long n = toLong(o);
				if (n != null) {
					out.add(n);
				}
			}
		} else {
			Long n = toLong(v);
			if (n != null) {
				out.add(n);
			}
		}
		return out;
	}

	private static Long toLong(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof Number) {
			return ((Number) o).longValue();
		}
		try {
			return Long.parseLong(o.toString().trim());
		} catch (NumberFormatException ex) {
			return null;
		}
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

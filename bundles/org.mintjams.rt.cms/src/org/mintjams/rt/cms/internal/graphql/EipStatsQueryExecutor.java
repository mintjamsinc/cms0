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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;

import org.HdrHistogram.Histogram;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.eip.stats.Bucket;
import org.mintjams.rt.cms.internal.eip.stats.BucketPathResolver;
import org.mintjams.rt.cms.internal.eip.stats.BucketStore;
import org.mintjams.rt.cms.internal.eip.stats.HistogramCodec;
import org.mintjams.rt.cms.internal.eip.stats.Interval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GraphQL queries that drive the EIP Console.
 *
 * <p>Three top-level queries:
 * <ul>
 *   <li>{@code routeStats} — time-series for one (or all) routes, served from
 *       the rollup buckets under {@code /var/eip/stats}.</li>
 *   <li>{@code historyExchanges} — Lucene-backed search over raw exchange
 *       records under {@code /var/eip/history}, using the {@code mi:*} JCR
 *       properties that {@code ExchangeHistoryEventNotifier} promotes.</li>
 *   <li>{@code historyExchange} — full detail for a single exchange (JSON
 *       parsed and returned).</li>
 * </ul>
 */
public class EipStatsQueryExecutor {

	private static final String HISTORY_BASE = "/var/eip/history";
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
			new TypeReference<LinkedHashMap<String, Object>>() {};

	private final Session session;
	private final BucketStore bucketStore = new BucketStore();
	private final ObjectMapper mapper = new ObjectMapper();

	public EipStatsQueryExecutor(Session session) {
		this.session = session;
	}

	// =========================================================================
	// routeStats
	// =========================================================================

	public Map<String, Object> executeRouteStatsQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> vars = request.getVariables();
		String route = optString(vars, "route");
		Instant from = Instant.parse(requireString(vars, "from"));
		Instant to = Instant.parse(requireString(vars, "to"));
		String metric = optString(vars, "metric");
		if (metric == null || metric.isEmpty()) {
			metric = "count";
		}
		String step = optString(vars, "step");
		Interval interval = resolveInterval(optString(vars, "interval"), from, to);

		List<String> routes = (route == null || route.isEmpty())
				? listRoutesUnderStats() : List.of(route);

		TreeMap<Instant, Aggregate> series = new TreeMap<>();
		Instant cursor = interval.truncate(from);
		while (cursor.isBefore(to)) {
			series.put(cursor, new Aggregate());
			cursor = interval.endOf(cursor);
		}

		for (String r : routes) {
			for (Instant t : series.keySet()) {
				String path = BucketPathResolver.bucketPath(r, interval, t);
				Bucket b;
				try {
					b = bucketStore.read(session, path);
				} catch (Exception ex) {
					CmsService.getLogger(getClass()).debug(
							"Skipping bucket {} for {}: {}", path, r, ex.getMessage());
					continue;
				}
				if (b == null) {
					continue;
				}
				Aggregate agg = series.get(t);
				if (step != null && !step.isEmpty()) {
					agg.absorbStep(b, step);
				} else {
					agg.absorb(b);
				}
			}
		}

		List<Map<String, Object>> points = new ArrayList<>(series.size());
		for (Map.Entry<Instant, Aggregate> e : series.entrySet()) {
			Map<String, Object> point = new LinkedHashMap<>();
			point.put("bucket", e.getKey().toString());
			point.put("value", e.getValue().metricValue(metric));
			point.put("count", e.getValue().count);
			point.put("errors", e.getValue().errors);
			points.add(point);
		}

		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("route", route);
		stats.put("interval", interval.label());
		stats.put("metric", metric);
		stats.put("from", from.toString());
		stats.put("to", to.toString());
		stats.put("points", points);

		Map<String, Object> data = new HashMap<>();
		data.put("routeStats", stats);
		return data;
	}

	// =========================================================================
	// historyExchanges (Lucene-backed search, Relay-style cursor pagination)
	// =========================================================================

	private static final int DEFAULT_PAGE_SIZE = 50;
	private static final int MAX_PAGE_SIZE = 500;

	@SuppressWarnings("unchecked")
	public Map<String, Object> executeHistoryExchangesQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> vars = request.getVariables();
		String route = optString(vars, "route");
		String status = optString(vars, "status");
		String from = optString(vars, "from");
		String to = optString(vars, "to");
		String businessKey = optString(vars, "businessKey");
		Object headers = vars == null ? null : vars.get("headers");

		Integer first = optBoxedInt(vars, "first");
		Integer last = optBoxedInt(vars, "last");
		String after = optString(vars, "after");
		String before = optString(vars, "before");

		// Determine direction and effective page size.
		boolean backward = (last != null) || (before != null && first == null);
		int pageSize;
		if (backward) {
			pageSize = clampPageSize(last);
		} else {
			pageSize = clampPageSize(first);
		}

		// Base predicate: filters that apply to both the page query and the total
		// count query. Anchored on @mi:exchangeId so we only match actual
		// exchange history records (and so the predicate is never empty).
		StringBuilder basePred = new StringBuilder("@mi:exchangeId");
		if (route != null && !route.isEmpty()) {
			basePred.append(" and @mi:routeId = '").append(escapeLiteral(route)).append("'");
		}
		if (status != null && !status.isEmpty()) {
			basePred.append(" and @mi:status = '").append(escapeLiteral(status)).append("'");
		}
		if (businessKey != null && !businessKey.isEmpty()) {
			basePred.append(" and @mi:businessKey = '").append(escapeLiteral(businessKey)).append("'");
		}
		if (from != null && !from.isEmpty()) {
			basePred.append(" and @mi:createdAt >= xs:dateTime('").append(escapeLiteral(from)).append("')");
		}
		if (to != null && !to.isEmpty()) {
			basePred.append(" and @mi:createdAt < xs:dateTime('").append(escapeLiteral(to)).append("')");
		}
		if (headers instanceof List) {
			for (Object o : (List<Object>) headers) {
				if (!(o instanceof Map)) {
					continue;
				}
				Map<String, Object> hf = (Map<String, Object>) o;
				String name = optString(hf, "name");
				String value = optString(hf, "value");
				if (name == null || name.isEmpty() || value == null) {
					continue;
				}
				basePred.append(" and @mi:header_").append(sanitizePropertyName(name))
						.append(" = '").append(escapeLiteral(value)).append("'");
			}
		}

		// Cursor predicate: separate from basePred so totalCount is stable across
		// pages. Cursor encodes ISO createdAt + exchangeId so we can resume
		// after/before deterministically even when two records share the same
		// millisecond timestamp.
		Cursor afterCursor = decodeCursor(after);
		Cursor beforeCursor = decodeCursor(before);
		StringBuilder cursorPred = new StringBuilder();
		if (afterCursor != null) {
			// Newer-first ordering: "after" means rows strictly older than the cursor.
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

		// For backward pagination we flip the sort and reverse afterwards so the
		// caller still sees newest-first.
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
		// hasMore semantics depend on direction.
		boolean hasNextPage = backward ? (beforeCursor != null) : hasMore;
		boolean hasPreviousPage = backward ? hasMore : (afterCursor != null);
		pageInfo.put("hasNextPage", hasNextPage);
		pageInfo.put("hasPreviousPage", hasPreviousPage);
		pageInfo.put("startCursor", edges.isEmpty() ? null : edges.get(0).get("cursor"));
		pageInfo.put("endCursor", edges.isEmpty() ? null : edges.get(edges.size() - 1).get("cursor"));

		// Total count via a separate cheap count query (cap for sanity).
		// Cursor predicate is intentionally omitted so totalCount stays stable
		// across pages.
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
	// historyExchange
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
	// Helpers
	// =========================================================================

	private Interval resolveInterval(String label, Instant from, Instant to) {
		if (label != null && !label.isEmpty() && !"auto".equalsIgnoreCase(label)) {
			return Interval.of(label);
		}
		Duration span = Duration.between(from, to);
		if (span.compareTo(Duration.ofHours(2)) <= 0) {
			return Interval.ONE_MINUTE;
		}
		if (span.compareTo(Duration.ofDays(2)) <= 0) {
			return Interval.FIVE_MINUTES;
		}
		if (span.compareTo(Duration.ofDays(30)) <= 0) {
			return Interval.ONE_HOUR;
		}
		return Interval.ONE_DAY;
	}

	private List<String> listRoutesUnderStats() throws Exception {
		List<String> routes = new ArrayList<>();
		if (!session.nodeExists(BucketPathResolver.BASE_PATH)) {
			return routes;
		}
		Node base = session.getNode(BucketPathResolver.BASE_PATH);
		for (NodeIterator it = base.getNodes(); it.hasNext();) {
			routes.add(it.nextNode().getName());
		}
		return routes;
	}

	private Map<String, Object> buildExchangeSummary(Node fileNode) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("exchangeId", propString(fileNode, "mi:exchangeId"));
		m.put("routeId", propString(fileNode, "mi:routeId"));
		m.put("status", propString(fileNode, "mi:status"));
		m.put("elapsed", propLong(fileNode, "mi:elapsed"));
		m.put("createdAt", propIsoDate(fileNode, "mi:createdAt"));
		m.put("businessKey", propString(fileNode, "mi:businessKey"));

		// Promoted headers (mi:header_*) inlined as a flat object.
		Map<String, Object> headers = new LinkedHashMap<>();
		Node content = JCRs.getContentNode(fileNode);
		for (javax.jcr.PropertyIterator pi = content.getProperties("mi:header_*"); pi.hasNext();) {
			javax.jcr.Property p = pi.nextProperty();
			String key = p.getName().substring("mi:header_".length());
			headers.put(key, propValue(p));
		}
		m.put("headers", headers);
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

	private static Object propValue(javax.jcr.Property p) throws Exception {
		switch (p.getType()) {
			case javax.jcr.PropertyType.LONG: return p.getLong();
			case javax.jcr.PropertyType.DOUBLE: return p.getDouble();
			case javax.jcr.PropertyType.BOOLEAN: return p.getBoolean();
			case javax.jcr.PropertyType.DATE: return Instant.ofEpochMilli(p.getDate().getTimeInMillis()).toString();
			default: return p.getString();
		}
	}

	private static String optString(Map<String, Object> vars, String key) {
		if (vars == null) {
			return null;
		}
		Object v = vars.get(key);
		return v == null ? null : v.toString();
	}

	private static String requireString(Map<String, Object> vars, String key) {
		String v = optString(vars, key);
		if (v == null || v.isEmpty()) {
			throw new IllegalArgumentException("Missing required variable: " + key);
		}
		return v;
	}

	private static int optInt(Map<String, Object> vars, String key, int defaultValue) {
		Integer v = optBoxedInt(vars, key);
		return v == null ? defaultValue : v.intValue();
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
		return v.replace("'", "''");
	}

	private static String sanitizePropertyName(String v) {
		// Defensive: drop characters that would never be valid in a JCR property name.
		StringBuilder sb = new StringBuilder(v.length());
		for (int i = 0; i < v.length(); i++) {
			char c = v.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	// -------------------------------------------------------------------------
	// Internal aggregate over (route × bucket) buckets for one time slot.
	// -------------------------------------------------------------------------

	private static final class Aggregate {
		long count;
		long errors;
		long sum;
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		Histogram histogram = HistogramCodec.newHistogram();

		void absorb(Bucket b) {
			absorbStats(b.elapsed().getCount(), b.elapsed().getErrors(),
					b.elapsed().getMin(), b.elapsed().getMax(), b.elapsed().getSum(),
					b.elapsed().getHistogram());
		}

		void absorbStep(Bucket b, String stepKey) {
			var stats = b.steps().get(stepKey);
			if (stats == null) {
				return;
			}
			absorbStats(stats.getCount(), 0L, stats.getMin(), stats.getMax(), stats.getSum(),
					stats.getHistogram());
		}

		private void absorbStats(long c, long e, long mn, long mx, long s, Histogram h) {
			count += c;
			errors += e;
			sum += s;
			if (c > 0) {
				min = Math.min(min, mn);
				max = Math.max(max, mx);
			}
			if (h != null) {
				histogram.add(h);
			}
		}

		double metricValue(String metric) {
			switch (metric.toLowerCase(Locale.ROOT)) {
				case "count": return count;
				case "errors": return errors;
				case "mean": return count == 0 ? 0.0 : (double) sum / count;
				case "min": return count == 0 ? 0.0 : min;
				case "max": return count == 0 ? 0.0 : max;
				case "p50": return histogram.getTotalCount() == 0 ? 0.0 : histogram.getValueAtPercentile(50.0);
				case "p95": return histogram.getTotalCount() == 0 ? 0.0 : histogram.getValueAtPercentile(95.0);
				case "p99": return histogram.getTotalCount() == 0 ? 0.0 : histogram.getValueAtPercentile(99.0);
				default: return count;
			}
		}
	}
}

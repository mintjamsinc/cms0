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

package org.mintjams.rt.cms.internal.eip;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.eip.aggregate.RecordAggregator;
import org.mintjams.rt.cms.internal.eip.aggregate.StatsConfig;
import org.mintjams.rt.cms.internal.eip.aggregate.StatsConfigCache;
import org.mintjams.rt.cms.internal.eip.stats.Bucket;
import org.mintjams.rt.cms.internal.eip.stats.BucketPathResolver;
import org.mintjams.rt.cms.internal.eip.stats.BucketStore;
import org.mintjams.rt.cms.internal.eip.stats.Interval;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Camel component for EIP statistics operations.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code eip:rollupUp?from=1min&to=5min} — merge lower-granularity
 *       buckets into a higher-granularity bucket. Designed to be called by
 *       the live aggregator route every minute and is idempotent /
 *       late-arrival resilient (rewrites the last N closed target windows).</li>
 *   <li>{@code eip:rebuild?from=2026-05-01T00:00:00Z&to=2026-05-09T00:00:00Z} —
 *       rebuild all bucket levels for a time range directly from raw
 *       {@code /var/eip/history} JSON, restoring stats after schema changes,
 *       bug fixes or disaster recovery.</li>
 * </ul>
 */
public class EipComponent extends DefaultComponent {

	public static final String COMPONENT_NAME = "eip";

	private static final String HISTORY_BASE_PATH = "/var/eip/history";
	private static final DateTimeFormatter HISTORY_HOUR_FMT =
			DateTimeFormatter.ofPattern("yyyy/MM/dd/HH").withZone(ZoneOffset.UTC);

	private final String fWorkspaceName;

	public EipComponent(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		EipEndpoint endpoint = new EipEndpoint(uri, remaining, parameters);
		parameters.clear();
		return endpoint;
	}

	public class EipEndpoint extends DefaultEndpoint {
		private final String fOperation;
		private final Map<String, Object> fParameters;
		private final BucketStore fStore = new BucketStore();

		private EipEndpoint(String endpointUri, String operation, Map<String, Object> parameters) {
			super(endpointUri, EipComponent.this);
			fOperation = operation;
			fParameters = new HashMap<>(parameters);
		}

		@Override
		public Consumer createConsumer(Processor processor) throws Exception {
			return null;
		}

		@Override
		public Producer createProducer() throws Exception {
			if ("rollupUp".equals(fOperation)) {
				return new RollupUpProducer();
			}
			if ("rebuild".equals(fOperation)) {
				return new RebuildProducer();
			}
			throw new IllegalArgumentException("Unknown operation: " + fOperation);
		}

		// ----- Producers ------------------------------------------------

		/**
		 * URI parameters:
		 *   - from: source interval (required; one of 1min, 5min, 1h, 1d)
		 *   - to: target interval (required; must be coarser than {@code from})
		 *   - route: optional route filter; if omitted all routes under /var/eip/stats are processed
		 *   - windows: number of closed target windows to recompute (default 2, for late-arrival resilience)
		 *   - at: ISO-8601 anchor instant; defaults to now
		 *
		 * Header overrides take priority over URI parameters.
		 *
		 * Sets exchange header {@code eipRollupCount} to the number of target
		 * buckets that were written.
		 */
		private class RollupUpProducer extends DefaultProducer {

			private RollupUpProducer() {
				super(EipEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				Interval from = Interval.of(stringParam(exchange, "from"));
				Interval to = Interval.of(stringParam(exchange, "to"));
				if (from.duration().compareTo(to.duration()) >= 0) {
					throw new IllegalArgumentException("'to' interval must be coarser than 'from': from="
							+ from.label() + ", to=" + to.label());
				}

				String routeFilter = stringParam(exchange, "route");
				int windows = intParam(exchange, "windows", 2);
				if (windows < 1) {
					windows = 1;
				}
				Instant anchor = parseInstant(stringParam(exchange, "at"));

				Session session = openSession();
				int written = 0;
				try {
					for (String routeId : resolveRoutes(session, routeFilter)) {
						written += rollupRoute(session, routeId, from, to, anchor, windows);
					}
					session.save();
				} finally {
					logout(session);
				}

				exchange.getIn().setHeader("eipRollupCount", written);
			}

			private int rollupRoute(Session session, String routeId, Interval from, Interval to,
					Instant anchor, int windows) throws Exception {
				String fromRoot = BucketPathResolver.intervalRoot(routeId, from);
				if (!session.nodeExists(fromRoot)) {
					return 0;
				}

				Instant latestClosedStart = to.truncate(anchor);
				if (to.endOf(latestClosedStart).isAfter(anchor)) {
					latestClosedStart = to.previousBucketStart(latestClosedStart);
				}

				int written = 0;
				Instant cursor = latestClosedStart;
				for (int i = 0; i < windows; i++) {
					if (rollupWindow(session, routeId, from, to, cursor)) {
						written++;
					}
					cursor = to.previousBucketStart(cursor);
				}
				return written;
			}
		}

		/**
		 * URI parameters:
		 *   - from: ISO-8601 inclusive lower bound (required)
		 *   - to: ISO-8601 exclusive upper bound (required)
		 *   - route: optional route filter
		 *   - rollup: whether to roll up after rebuilding 1min buckets (default true)
		 *
		 * Reads raw history records under {@value #HISTORY_BASE_PATH}, groups
		 * them by (routeId, 1-minute window), and writes fresh 1min buckets.
		 * Higher-granularity buckets (5min/1h/1d) are then regenerated for
		 * every window touched by the rebuilt range.
		 *
		 * Sets exchange header {@code eipRebuiltCount} to the total number of
		 * bucket files written (across all granularities).
		 */
		private class RebuildProducer extends DefaultProducer {

			private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
					new TypeReference<LinkedHashMap<String, Object>>() {};

			private final ObjectMapper fMapper = new ObjectMapper();

			private RebuildProducer() {
				super(EipEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				String fromStr = stringParam(exchange, "from");
				String toStr = stringParam(exchange, "to");
				if (fromStr == null || toStr == null) {
					throw new IllegalArgumentException("Both 'from' and 'to' parameters are required");
				}
				Instant rangeStart = Interval.ONE_MINUTE.truncate(Instant.parse(fromStr));
				Instant rangeEndExclusive = Interval.ONE_MINUTE.truncate(Instant.parse(toStr));
				if (!rangeStart.isBefore(rangeEndExclusive)) {
					rangeEndExclusive = rangeStart.plus(Interval.ONE_MINUTE.duration());
				}

				String routeFilter = stringParam(exchange, "route");
				boolean withRollup = !"false".equalsIgnoreCase(stringParam(exchange, "rollup"));

				StatsConfigCache cache = lookupStatsConfigCache();

				Session session = openSession();
				int written = 0;
				try {
					written += rebuildOneMinute(session, rangeStart, rangeEndExclusive, routeFilter, cache);
					session.save();

					if (withRollup) {
						written += rollupRange(session, Interval.ONE_MINUTE, Interval.FIVE_MINUTES,
								rangeStart, rangeEndExclusive, routeFilter);
						written += rollupRange(session, Interval.FIVE_MINUTES, Interval.ONE_HOUR,
								rangeStart, rangeEndExclusive, routeFilter);
						written += rollupRange(session, Interval.ONE_HOUR, Interval.ONE_DAY,
								rangeStart, rangeEndExclusive, routeFilter);
						session.save();
					}
				} finally {
					logout(session);
				}

				exchange.getIn().setHeader("eipRebuiltCount", written);
			}

			private int rebuildOneMinute(Session session, Instant rangeStart, Instant rangeEndExclusive,
					String routeFilter, StatsConfigCache cache) throws Exception {
				int written = 0;
				Instant hourCursor = rangeStart.truncatedTo(ChronoUnit.HOURS);
				while (hourCursor.isBefore(rangeEndExclusive)) {
					String hourPath = HISTORY_BASE_PATH + "/" + HISTORY_HOUR_FMT.format(hourCursor);
					if (session.nodeExists(hourPath)) {
						Node hourNode = session.getNode(hourPath);
						for (NodeIterator routeIt = hourNode.getNodes(); routeIt.hasNext();) {
							Node routeNode = routeIt.nextNode();
							String routeId = routeNode.getName();
							if (routeFilter != null && !routeFilter.isEmpty() && !routeFilter.equals(routeId)) {
								continue;
							}
							written += rebuildRouteHour(session, routeNode, routeId, rangeStart, rangeEndExclusive, cache);
						}
					}
					hourCursor = hourCursor.plus(1, ChronoUnit.HOURS);
				}
				return written;
			}

			private int rebuildRouteHour(Session session, Node routeNode, String routeId,
					Instant rangeStart, Instant rangeEndExclusive, StatsConfigCache cache) throws Exception {
				StatsConfig config = (cache == null) ? null : cache.get(routeId);
				Map<Instant, Bucket> buckets = new HashMap<>();

				for (NodeIterator fileIt = routeNode.getNodes(); fileIt.hasNext();) {
					Node fileNode = fileIt.nextNode();
					Map<String, Object> record = readRecord(fileNode);
					if (record.isEmpty()) {
						continue;
					}
					Object tsObj = record.get("timestamp");
					if (tsObj == null) {
						continue;
					}
					Instant ts;
					try {
						ts = Instant.parse(tsObj.toString());
					} catch (Exception ex) {
						continue;
					}
					if (ts.isBefore(rangeStart) || !ts.isBefore(rangeEndExclusive)) {
						continue;
					}

					Instant minute = Interval.ONE_MINUTE.truncate(ts);
					Bucket bucket = buckets.computeIfAbsent(minute,
							m -> new Bucket(routeId, m, Interval.ONE_MINUTE));
					RecordAggregator.apply(bucket, record, config);
				}

				for (Bucket bucket : buckets.values()) {
					fStore.write(session, bucket);
				}
				return buckets.size();
			}

			private Map<String, Object> readRecord(Node fileNode) {
				try {
					String json = JCRs.getContentAsString(fileNode);
					if (json == null || json.isEmpty()) {
						return new LinkedHashMap<>();
					}
					return fMapper.readValue(json, MAP_TYPE);
				} catch (Exception ex) {
					CmsService.getLogger(getClass()).warn(
							"Failed to read history record: {}", ex.getMessage());
					return new LinkedHashMap<>();
				}
			}

			private int rollupRange(Session session, Interval from, Interval to,
					Instant rangeStart, Instant rangeEndExclusive, String routeFilter) throws Exception {
				int written = 0;
				for (String routeId : resolveRoutes(session, routeFilter)) {
					Instant cursor = to.truncate(rangeStart);
					while (cursor.isBefore(rangeEndExclusive)) {
						if (rollupWindow(session, routeId, from, to, cursor)) {
							written++;
						}
						cursor = to.endOf(cursor);
					}
				}
				return written;
			}

			private StatsConfigCache lookupStatsConfigCache() {
				try {
					return getCamelContext().getRegistry()
							.lookupByNameAndType("statsConfigCache", StatsConfigCache.class);
				} catch (Throwable ignore) {
					return null;
				}
			}
		}

		// ----- Shared helpers -------------------------------------------

		private boolean rollupWindow(Session session, String routeId, Interval from, Interval to,
				Instant windowStart) throws Exception {
			Instant windowEnd = to.endOf(windowStart);
			List<Bucket> sources = new ArrayList<>();
			Instant fromCursor = from.truncate(windowStart);
			while (fromCursor.isBefore(windowEnd)) {
				Bucket b = fStore.read(session, BucketPathResolver.bucketPath(routeId, from, fromCursor));
				if (b != null) {
					sources.add(b);
				}
				fromCursor = from.endOf(fromCursor);
			}
			if (sources.isEmpty()) {
				return false;
			}
			Bucket target = new Bucket(routeId, windowStart, to);
			for (Bucket src : sources) {
				target.merge(src);
			}
			fStore.write(session, target);
			return true;
		}

		private List<String> resolveRoutes(Session session, String routeFilter) throws Exception {
			if (routeFilter != null && !routeFilter.isEmpty()) {
				List<String> single = new ArrayList<>(1);
				single.add(routeFilter);
				return single;
			}
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

		private Session openSession() throws Exception {
			return CmsService.getRepository().login(new CmsServiceCredentials(), fWorkspaceName);
		}

		private void logout(Session session) {
			if (session != null) {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}
		}

		private String stringParam(Exchange exchange, String key) {
			Object header = exchange.getIn().getHeader(key);
			if (header != null) {
				return header.toString();
			}
			Object param = fParameters.get(key);
			return param == null ? null : param.toString();
		}

		private int intParam(Exchange exchange, String key, int defaultValue) {
			String v = stringParam(exchange, key);
			if (v == null || v.isEmpty()) {
				return defaultValue;
			}
			try {
				return Integer.parseInt(v);
			} catch (NumberFormatException ex) {
				return defaultValue;
			}
		}

		private Instant parseInstant(String v) {
			if (v == null || v.isEmpty()) {
				return Instant.now();
			}
			return Instant.parse(v);
		}
	}
}

package webtop.dashboard;

import java.util.concurrent.ConcurrentHashMap;

/**
 * What one node knows of the integration routes it runs: their state, the
 * exchanges they handled and whether the systems they talk to are
 * reachable. The numbers live in the memory of each node, so every node
 * contributes its part, and the route cards are made from the parts of all
 * (collect: node).
 *
 * The three route cards ask for the same part within a moment of each
 * other; it is kept for a few seconds so that the routes are read once.
 */
class EipParts {

	private static final long KEEP_MILLIS = 5000L;
	private static final int MAX_TEXT = 200;
	private static final Map<String, List> CACHE = new ConcurrentHashMap<>();

	static Map collect(Map card) {
		String workspace = card.workspace as String;
		List cached = CACHE.get(workspace);
		long now = System.currentTimeMillis();
		if (cached != null && now - (cached[0] as long) < KEEP_MILLIS) {
			return cached[1] as Map;
		}
		Map part = read(card);
		CACHE.put(workspace, [now, part]);
		return part;
	}

	private static Map read(Map card) {
		List<Map> routes = Cards.nodes(card.graphql.query('''
			query {
				routes(first: 1000) {
					edges { node {
						routeId status exchangesTotal exchangesCompleted exchangesFailed exchangesInflight meanProcessingTime
						lastError { message }
						endpoints { uri component remote health }
					} }
				}
			}
		''').routes as Map) as List<Map>;

		int started = 0, stopped = 0, suspended = 0, remote = 0;
		long total = 0, completed = 0, failed = 0, inflight = 0;
		double meanSum = 0, meanWeight = 0;
		List<Map> failing = [];
		List<Map> down = [];
		for (Map r : routes) {
			// The engine names the states "Started", "Starting", "Suspended", "Stopped", ...
			String status = ((r.status ?: '') as String).toLowerCase();
			if (status.startsWith('start')) {
				started++;
			} else if (status.startsWith('suspend')) {
				suspended++;
			} else {
				stopped++;
			}
			total += Cards.lng(r.exchangesTotal);
			completed += Cards.lng(r.exchangesCompleted);
			failed += Cards.lng(r.exchangesFailed);
			inflight += Cards.lng(r.exchangesInflight);
			double mean = (r.meanProcessingTime instanceof Number) ? (r.meanProcessingTime as Number).doubleValue() : 0;
			long weight = Cards.lng(r.exchangesCompleted);
			if (mean > 0 && weight > 0) {
				meanSum += mean * weight;
				meanWeight += weight;
			}
			if (Cards.lng(r.exchangesFailed) > 0) {
				failing.add([routeId: r.routeId, failed: Cards.lng(r.exchangesFailed), lastError: cut((r.lastError as Map)?.message as String)]);
			}
			for (Map e : ((r.endpoints ?: []) as List<Map>)) {
				if (!e.remote) {
					continue;
				}
				remote++;
				// Only an endpoint reported down counts: one without a health check is not a problem.
				if (((e.health ?: 'UNKNOWN') as String).toUpperCase() == 'DOWN') {
					down.add([routeId: r.routeId, uri: cut(e.uri as String), component: e.component]);
				}
			}
		}
		failing.sort { a, b -> (b.failed as long) <=> (a.failed as long) };
		return [
			routes: routes.size(), started: started, stopped: stopped, suspended: suspended,
			total: total, completed: completed, failed: failed, inflight: inflight,
			meanSum: meanSum, meanWeight: meanWeight,
			failing: failing.take(Cards.TOP_N),
			remote: remote, down: down.size(), problems: down.take(6),
		];
	}

	private static String cut(String text) {
		if (!text) {
			return '';
		}
		return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
	}

	/** The parts of the nodes, by node id, in the order of the ids. */
	static Map<String, Map> partsOf(Map card) {
		Map<String, Map> parts = new TreeMap<>();
		((card.parts ?: [:]) as Map).each { node, part ->
			if (part instanceof Map) {
				parts[node as String] = part as Map;
			}
		};
		return parts;
	}

	/** "https://api.example.com/v2/..." -> "api.example.com" */
	static String endpointLabel(String uri, String component) {
		String raw = uri ?: '';
		def m = raw =~ /^[a-zA-Z0-9+.-]+:\/\/([^\/?#]+)/;
		if (m.find()) {
			return m.group(1);
		}
		String scheme = raw.contains(':') ? raw.substring(0, raw.indexOf(':')) : raw;
		return scheme ?: component ?: 'endpoint';
	}

}

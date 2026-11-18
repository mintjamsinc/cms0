package webtop.dashboard;

/**
 * "Throughput & errors": the exchanges the routes handled on all nodes
 * together, and the routes that fail; maximized, the card shows each node.
 */
class Throughput {

	static Map part(scriptAPI, Map card) {
		return EipParts.collect(card);
	}

	static Map snapshot(scriptAPI, Map card) {
		Map<String, Map> parts = EipParts.partsOf(card);
		long completed = 0, failed = 0, inflight = 0;
		double meanSum = 0, meanWeight = 0;
		Map<String, Map> failing = [:];
		for (Map part : parts.values()) {
			completed += Cards.lng(part.completed);
			failed += Cards.lng(part.failed);
			inflight += Cards.lng(part.inflight);
			meanSum += ((part.meanSum ?: 0) as double);
			meanWeight += ((part.meanWeight ?: 0) as double);
			for (Map f : ((part.failing ?: []) as List<Map>)) {
				Map merged = failing[f.routeId as String];
				if (merged == null) {
					merged = [routeId: f.routeId, failed: 0L, lastError: f.lastError];
					failing[f.routeId as String] = merged;
				}
				merged.failed = (merged.failed as long) + Cards.lng(f.failed);
				merged.lastError = merged.lastError ?: f.lastError;
			}
		}
		long mean = meanWeight > 0 ? Math.round(meanSum / meanWeight) : 0L;

		List rows = [
			[label: Cards.msg('status.failed', 'Failed'), icon: 'bi-x-octagon', value: failed, format: 'compact', badge: true,
				tone: failed > 0 ? 'danger' : 'success'],
		];
		if (inflight > 0) {
			rows.add([label: Cards.msg('throughput.inFlight', 'In flight'), icon: 'bi-hourglass-split', value: inflight]);
		}
		List blocks = [
			[type: 'metric', value: completed, format: 'compact',
				unit: Cards.msg('throughput.completedLatency', 'completed · {latency}', [latency: [duration: mean]])],
			[type: 'rows', items: rows],
		];
		List<Map> top = failing.values().sort { a, b -> (b.failed as long) <=> (a.failed as long) }.take(Cards.TOP_N);
		if (top) {
			blocks.add([type: 'list', ranked: true, items: top.collect { Map f ->
				Map row = [title: f.routeId, value: f.failed, tone: 'danger',
					open: Cards.open(Cards.EIP_CONSOLE, [routeId: f.routeId, status: 'failed'])];
				if (f.lastError) {
					row.sub = f.lastError;
				}
				return row;
			}]);
		}
		return [
			status: failed > 0 ? 'attention' : 'ok',
			facts: [completed: completed, failed: failed, inflight: inflight],
			blocks: blocks,
		];
	}

	static Map detail(scriptAPI, Map card) {
		Map summary = snapshot(scriptAPI, card);
		Map<String, Map> parts = EipParts.partsOf(card);
		(summary.blocks as List).add([type: 'list', title: Cards.msg('detail.byNode', 'By node'),
			items: parts.collect { node, part ->
				long failed = Cards.lng(part.failed);
				double weight = (part.meanWeight ?: 0) as double;
				long mean = weight > 0 ? Math.round(((part.meanSum ?: 0) as double) / weight) : 0L;
				[icon: 'bi-hdd-network', title: node,
					sub: Cards.msg('throughput.nodeSub', '{completed} completed · {latency} · {inflight} in flight',
						[completed: Cards.lng(part.completed), latency: [duration: mean], inflight: Cards.lng(part.inflight)]),
					badges: failed > 0 ? [[text: Cards.msg('throughput.nodeFailed', '{failed} failed', [failed: failed]), tone: 'danger']] : []];
			}]);
		return summary;
	}

}

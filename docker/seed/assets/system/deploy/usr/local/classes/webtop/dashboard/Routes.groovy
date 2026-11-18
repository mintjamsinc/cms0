package webtop.dashboard;

/**
 * "Integration routes": how many routes are started. Each node runs the
 * routes on its own, so a route counts as started when it is on every node;
 * maximized, the card shows each node.
 */
class Routes {

	static Map part(scriptAPI, Map card) {
		return EipParts.collect(card);
	}

	static Map snapshot(scriptAPI, Map card) {
		Map<String, Map> parts = EipParts.partsOf(card);
		int total = (parts.values().collect { Cards.num(it.routes) }.max() ?: 0) as int;
		int started = (parts.values().collect { Cards.num(it.started) }.min() ?: 0) as int;
		int stopped = (parts.values().collect { Cards.num(it.stopped) }.max() ?: 0) as int;
		int suspended = (parts.values().collect { Cards.num(it.suspended) }.max() ?: 0) as int;

		List rows = [
			[label: Cards.msg('routes.context', 'Context'), icon: 'bi-hdd-network', badge: true,
				text: started > 0 ? Cards.msg('context.started', 'Started') : (total > 0 ? Cards.msg('context.stopped', 'Stopped') : '—'),
				tone: started > 0 ? 'success' : (total > 0 ? 'danger' : 'neutral')],
		];
		if (stopped > 0) {
			rows.add([label: Cards.msg('status.stopped', 'Stopped'), icon: 'bi-stop-circle', value: stopped, badge: true, tone: 'warning']);
		}
		if (suspended > 0) {
			rows.add([label: Cards.msg('status.suspended', 'Suspended'), icon: 'bi-pause-circle', value: suspended, badge: true, tone: 'warning']);
		}
		return [
			status: stopped > 0 ? 'attention' : 'ok',
			facts: [total: total, started: started, stopped: stopped, suspended: suspended, nodes: parts.size()],
			blocks: [
				[type: 'metric', value: started, denom: total, unit: Cards.msg('routes.started', 'started')],
				[type: 'rows', items: rows],
			],
		];
	}

	static Map detail(scriptAPI, Map card) {
		Map summary = snapshot(scriptAPI, card);
		Map<String, Map> parts = EipParts.partsOf(card);
		(summary.blocks as List).add([type: 'list', title: Cards.msg('detail.byNode', 'By node'),
			items: parts.collect { node, part ->
				int stopped = Cards.num(part.stopped) + Cards.num(part.suspended);
				[icon: 'bi-hdd-network', title: node,
					sub: Cards.msg('routes.nodeSub', '{started} of {total} started', [started: Cards.num(part.started), total: Cards.num(part.routes)]),
					badges: [[text: stopped > 0 ? Cards.msg('status.stopped', 'Stopped') : Cards.msg('context.started', 'Started'),
						tone: stopped > 0 ? 'warning' : 'success']]];
			}]);
		return summary;
	}

}

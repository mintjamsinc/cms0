package webtop.dashboard;

/** "Running processes": the active instances, the processes with the most, and how many are held up. */
class Running {

	static Map snapshot(scriptAPI, Map card) {
		Map data = card.graphql.query('''
			query($first: Int) {
				active: processInstances(active: true, first: $first) { totalCount edges { node { definitionKey } } }
				suspended: processInstances(suspended: true, first: 1) { totalCount }
				withIncidents: processInstances(withIncidents: true, first: 1) { totalCount }
			}
		''', [first: Cards.SCAN_LIMIT]);
		List<Map> scanned = Cards.nodes(data.active as Map) as List<Map>;
		int active = ((data.active as Map)?.totalCount != null) ? Cards.num((data.active as Map).totalCount) : scanned.size();
		int suspended = Cards.num((data.suspended as Map)?.totalCount);
		int withIncidents = Cards.num((data.withIncidents as Map)?.totalCount);

		Map<String, Integer> byKey = [:];
		for (Map instance : scanned) {
			String key = (instance.definitionKey ?: '') as String;
			byKey[key] = (byKey[key] ?: 0) + 1;
		}
		List<Map.Entry<String, Integer>> top = byKey.entrySet().sort { a, b -> b.value <=> a.value }.take(Cards.TOP_N);
		Map<String, String> names = top ? Cards.processNames(card) : [:];

		List blocks = [
			[type: 'metric', value: active, unit: Cards.msg('running.activeInstances', 'active instances')],
			[type: 'list', ranked: true,
				items: top.collect { e ->
					[title: Cards.processName(e.key, names[e.key]), value: e.value,
						open: Cards.open(Cards.BPM_CONSOLE, [view: 'instances', processDefinitionKey: e.key])];
				},
				empty: Cards.msg('running.empty', 'No processes are running.')],
		];
		List rows = [];
		if (suspended > 0) {
			rows.add([label: Cards.msg('status.suspended', 'Suspended'), icon: 'bi-pause-circle', value: suspended, badge: true, tone: 'warning']);
		}
		if (withIncidents > 0) {
			rows.add([label: Cards.msg('running.withIncidents', 'With incidents'), icon: 'bi-exclamation-octagon', value: withIncidents,
				badge: true, tone: 'danger']);
		}
		if (rows) {
			blocks.add([type: 'rows', items: rows]);
		}
		return [
			status: withIncidents > 0 ? 'issue' : (suspended > 0 ? 'attention' : 'ok'),
			facts: [active: active, suspended: suspended, withIncidents: withIncidents],
			blocks: blocks,
		];
	}

}

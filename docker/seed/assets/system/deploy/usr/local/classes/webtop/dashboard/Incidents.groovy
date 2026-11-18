package webtop.dashboard;

/** "Incidents": the open incidents, and the processes that carry the most. */
class Incidents {

	static Map snapshot(scriptAPI, Map card) {
		Map connection = card.graphql.query('''
			query($first: Int) {
				incidents(first: $first) { totalCount edges { node { type message activityName processDefinitionKey } } }
			}
		''', [first: Cards.SCAN_LIMIT]).incidents as Map;
		List<Map> list = Cards.nodes(connection) as List<Map>;
		int total = (connection?.totalCount != null) ? Cards.num(connection.totalCount) : list.size();

		Map<String, Map> byProcess = [:];
		for (Map incident : list) {
			String key = (incident.processDefinitionKey ?: '') as String;
			Map group = byProcess[key];
			if (group == null) {
				group = [key: key, count: 0, sample: ''];
				byProcess[key] = group;
			}
			group.count = (group.count as int) + 1;
			if (!group.sample) {
				group.sample = (incident.activityName ?: incident.message ?: '') as String;
			}
		}
		List<Map> top = byProcess.values().sort { a, b -> (b.count as int) <=> (a.count as int) }.take(Cards.TOP_N);
		Map<String, String> names = top ? Cards.processNames(card) : [:];

		return [
			status: total > 0 ? 'issue' : 'ok',
			facts: [total: total],
			blocks: [
				[type: 'metric', value: total, tone: total > 0 ? 'danger' : null, unit: Cards.msg('incidents.open', 'open')],
				[type: 'list', ranked: true,
					items: top.collect { Map g ->
						String sample = g.sample as String;
						Map row = [title: Cards.processName(g.key as String, names[g.key as String]), value: g.count, tone: 'danger',
							open: Cards.open(Cards.BPM_CONSOLE, [view: 'incidents', processDefinitionKey: g.key])];
						if (sample) {
							row.sub = sample.length() > 200 ? sample.substring(0, 200) : sample;
						}
						return row;
					},
					empty: total == 0 ? Cards.msg('incidents.empty', 'No incidents. Everything is processing cleanly.') : null],
			],
		];
	}

}

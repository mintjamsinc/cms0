package webtop.dashboard;

/** "Process definitions": how many processes are deployed, and how many of them are suspended. */
class Definitions {

	static Map snapshot(scriptAPI, Map card) {
		List<Map> all = Cards.nodes(card.graphql.query('''
			query { processDefinitions(latestVersion: true, first: 1000) { edges { node { key suspended } } } }
		''').processDefinitions as Map) as List<Map>;
		int suspended = all.count { it.suspended } as int;
		return [
			status: 'ok',
			facts: [deployed: all.size(), suspended: suspended],
			blocks: [
				[type: 'metric', value: all.size(), unit: Cards.msg('defs.deployed', 'deployed')],
				[type: 'rows', items: [
					[label: Cards.msg('status.active', 'Active'), icon: 'bi-play-circle', value: all.size() - suspended],
					[label: Cards.msg('status.suspended', 'Suspended'), icon: 'bi-pause-circle', value: suspended, badge: true,
						tone: suspended > 0 ? 'warning' : 'success'],
				]],
			],
		];
	}

}

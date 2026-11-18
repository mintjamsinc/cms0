package webtop.dashboard;

/**
 * "Where work piles up": the steps most tasks are waiting at, with the age
 * of the oldest. The same for everyone. The ranking is made from the first
 * SCAN_LIMIT open tasks.
 */
class Bottlenecks {

	static Map snapshot(scriptAPI, Map card) {
		List<Map> tasks = Cards.nodes(card.graphql.query('''
			query($first: Int) {
				tasks(first: $first) { edges { node { name created processDefinitionKey taskDefinitionKey } } }
			}
		''', [first: Cards.SCAN_LIMIT]).tasks as Map) as List<Map>;

		Map<String, Map> steps = [:];
		for (Map t : tasks) {
			String processKey = (t.processDefinitionKey ?: '') as String;
			String taskKey = (t.taskDefinitionKey ?: t.name ?: '') as String;
			String key = processKey + ' ' + taskKey;
			Map step = steps[key];
			if (step == null) {
				step = [processKey: processKey, taskKey: taskKey, taskDefinitionKey: t.taskDefinitionKey, name: t.name, count: 0, oldest: null];
				steps[key] = step;
			}
			step.count = (step.count as int) + 1;
			String created = t.created as String;
			if (created && (step.oldest == null || created < (step.oldest as String))) {
				step.oldest = created;
			}
		}
		List<Map> top = steps.values().sort { a, b ->
			((b.count as int) <=> (a.count as int)) ?: (((a.oldest ?: '') as String) <=> ((b.oldest ?: '') as String));
		}.take(Cards.TOP_N);

		Map<String, String> names = top ? Cards.processNames(card) : [:];
		return [
			status: 'ok',
			facts: [steps: steps.size()],
			blocks: [
				[type: 'list', ranked: true,
					items: top.collect { Map s ->
						Object process = Cards.processName(s.processKey as String, names[s.processKey as String]);
						return [
							title: Cards.taskName(s.processKey as String, s.taskDefinitionKey as String,
								(s.name ?: Cards.humanize(s.taskKey as String)) as String),
							sub: s.oldest ?
								Cards.msg('bottlenecks.subtitle', '{process} · oldest {age}', [process: process, age: [age: s.oldest]]) :
								process,
							value: s.count,
							open: Cards.open(Cards.TASKS, [scope: 'all', processDefinitionKey: s.processKey, taskDefinitionKey: s.taskKey]),
						];
					},
					empty: Cards.msg('bottlenecks.empty', 'No tasks are waiting anywhere. The flow is clear.')],
			],
		];
	}

}

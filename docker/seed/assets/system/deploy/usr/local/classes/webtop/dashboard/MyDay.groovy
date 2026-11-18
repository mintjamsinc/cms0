package webtop.dashboard;

/**
 * "My day", the summary of Today's Work: the reader's open, due and overdue
 * tasks, what they may claim, and the tasks to do next. Made per user.
 */
class MyDay {

	static Map snapshot(scriptAPI, Map card, String userId) {
		String timeZone = card.timeZone as String;
		Map mine = Cards.taskCounts(card, [assignee: userId, timeZone: timeZone]);
		// With the user alone the engine looks up the groups and the roles the user holds.
		Map claim = Cards.taskCounts(card, [candidateUser: userId, timeZone: timeZone]);
		int open = Cards.num(mine.total);
		int dueToday = Cards.num(mine.dueToday);
		int overdue = Cards.num(mine.overdue);
		int claimable = Cards.num(claim.total);

		Map assigned = Cards.open(Cards.TASKS, [scope: 'assigned']);
		return [
			status: overdue > 0 ? 'attention' : 'ok',
			facts: [open: open, dueToday: dueToday, overdue: overdue, claimable: claimable],
			blocks: [
				[type: 'kpis', col: 1, items: [
					[label: Cards.msg('myDay.myOpen', 'My open tasks'), hint: Cards.msg('myDay.myOpen.hint', 'Tasks assigned to me'),
						value: open, open: assigned],
					[label: Cards.msg('metric.dueToday', 'Due today'), hint: Cards.msg('myDay.dueToday.hint', 'Assigned to me, due today'),
						value: dueToday, tone: dueToday > 0 ? 'warning' : null, open: assigned],
					[label: Cards.msg('metric.overdue', 'Overdue'), hint: Cards.msg('myDay.overdue.hint', 'Assigned to me, past due'),
						value: overdue, tone: overdue > 0 ? 'danger' : null, open: assigned],
					[label: Cards.msg('metric.toClaim', 'To claim'), hint: Cards.msg('myDay.toClaim.hint', 'Tasks I can claim from the team queue'),
						value: claimable, open: Cards.open(Cards.TASKS, [scope: 'candidate'])],
				]],
				[type: 'list', col: 2, ranked: true, title: Cards.msg('myDay.nextUp', 'Next up'),
					items: nextUp(card, userId),
					empty: Cards.msg('myDay.empty', 'Nothing assigned to you right now. Nice and clear.')],
			],
		];
	}

	/**
	 * The reader's tasks, soonest due first, then the ones without a due date
	 * by priority and age. Asked of the engine for this user, so that none is
	 * missed however many tasks are open.
	 */
	private static List nextUp(Map card, String userId) {
		String query = '''
			query($assignee: String, $dueAfter: DateTime, $sortBy: TaskSortField, $sortOrder: SortOrder, $first: Int) {
				tasks(assignee: $assignee, dueAfter: $dueAfter, sortBy: $sortBy, sortOrder: $sortOrder, first: $first) {
					edges { node { id name created due priority processDefinitionKey taskDefinitionKey processInstance { businessKey } } }
				}
			}
		''';
		List<Map> tasks = Cards.nodes(card.graphql.query(query, [assignee: userId, dueAfter: '1970-01-01T00:00:00Z',
			sortBy: 'DUE', sortOrder: 'ASC', first: Cards.TOP_N]).tasks as Map) as List<Map>;
		if (tasks.size() < Cards.TOP_N) {
			List<Map> rest = (Cards.nodes(card.graphql.query(query, [assignee: userId, sortBy: 'PRIORITY', sortOrder: 'DESC',
				first: 100]).tasks as Map) as List<Map>).findAll { !it.due };
			rest.sort { a, b ->
				(Cards.num(b.priority) <=> Cards.num(a.priority)) ?: ((a.created as String) <=> (b.created as String));
			};
			tasks = tasks + rest.take(Cards.TOP_N - tasks.size());
		}
		if (!tasks) {
			return [];
		}

		Map<String, String> names = Cards.processNames(card);
		return tasks.collect { Map t ->
			String processKey = t.processDefinitionKey as String;
			String businessKey = (t.processInstance as Map)?.businessKey as String;
			Object process = Cards.processName(processKey, names[processKey]);
			Map row = [
				title: Cards.taskName(processKey, t.taskDefinitionKey as String, t.name as String),
				sub: businessKey ? Cards.msg('myDay.sub', '{process} · {key}', [process: process, key: businessKey]) : process,
				open: Cards.open(Cards.TASKS, [scope: 'assigned', taskId: t.id]),
			];
			if (t.due) {
				row.due = t.due;
			}
			return row;
		};
	}

}

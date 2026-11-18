package webtop.dashboard;

/** "My tasks": how many tasks are the reader's, and how many of them are due. Made per user. */
class MyTasks {

	static Map snapshot(scriptAPI, Map card, String userId) {
		Map mine = Cards.taskCounts(card, [assignee: userId, timeZone: card.timeZone as String]);
		int total = Cards.num(mine.total);
		int overdue = Cards.num(mine.overdue);
		int dueToday = Cards.num(mine.dueToday);
		return [
			status: overdue > 0 ? 'attention' : 'ok',
			facts: [total: total, overdue: overdue, dueToday: dueToday, dueThisWeek: Cards.num(mine.dueThisWeek)],
			blocks: [
				[type: 'metric', value: total, unit: Cards.msg('myTasks.assignedToMe', 'assigned to me')],
				[type: 'rows', items: [
					[label: Cards.msg('metric.overdue', 'Overdue'), icon: 'bi-alarm', value: overdue, badge: true,
						tone: overdue > 0 ? 'danger' : 'success'],
					[label: Cards.msg('metric.dueToday', 'Due today'), value: dueToday, tone: dueToday > 0 ? 'warning' : null],
					[label: Cards.msg('metric.dueThisWeek', 'Due this week'), value: Cards.num(mine.dueThisWeek)],
				]],
			],
		];
	}

}

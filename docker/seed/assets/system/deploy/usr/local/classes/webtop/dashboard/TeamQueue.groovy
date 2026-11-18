package webtop.dashboard;

/** "Team queue": the tasks nobody has taken, and the tasks open in all. The same for everyone. */
class TeamQueue {

	static Map snapshot(scriptAPI, Map card) {
		Map counts = Cards.taskCounts(card, [:]);
		int unassigned = Cards.num(counts.unassigned);
		int total = Cards.num(counts.total);
		return [
			status: 'ok',
			facts: [unassigned: unassigned, total: total],
			blocks: [
				[type: 'metric', value: unassigned, tone: unassigned > 0 ? 'warning' : null,
					unit: Cards.msg('queue.unassigned', 'unassigned')],
				[type: 'rows', items: [
					[label: Cards.msg('queue.openAcrossTeam', 'Open across the team'), value: total],
				]],
			],
		];
	}

}

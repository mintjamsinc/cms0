package webtop.dashboard;

/**
 * "Are operations healthy?", the summary of Operations: one number from
 * each card of the section, and what they add up to. Made from the
 * snapshots of those cards (refresh.cards), so nothing is counted twice;
 * a card that has no snapshot yet shows as a dash.
 */
class Health {

	static Map snapshot(scriptAPI, Map card) {
		Map running = Cards.factsOf(card, 'running');
		Map incidents = Cards.factsOf(card, 'incidents');
		Map routes = Cards.factsOf(card, 'routes');
		Map throughput = Cards.factsOf(card, 'throughput');
		Map connections = Cards.factsOf(card, 'connections');
		Map workspaces = Cards.factsOf(card, 'workspaces');
		Map cluster = Cards.factsOf(card, 'cluster');

		int openIncidents = Cards.num(incidents?.total);
		long failed = Cards.lng(throughput?.failed);
		int down = Cards.num(connections?.down);
		int stale = Cards.num(cluster?.stale);
		int engineIssues = Cards.num(workspaces?.engineIssues);
		int failedWorkspaces = Cards.num(workspaces?.failed);
		int starting = Cards.num(workspaces?.starting);

		// A stale node, an engine that did not start and a workspace that failed are outages, like an incident;
		// a workspace still starting or a route stopped only asks for attention.
		String status = (openIncidents > 0 || down > 0 || stale > 0 || engineIssues > 0 || failedWorkspaces > 0) ? 'issue' :
			((failed > 0 || Cards.num(running?.suspended) > 0 || Cards.num(routes?.stopped) > 0 || starting > 0 ||
				Cards.num(cluster?.linkIssues) > 0) ? 'attention' : 'ok');

		List items = [
			item('ops.runningProcesses', 'Running processes', 'Running process instances', running, [value: Cards.num(running?.active)],
				Cards.open(Cards.BPM_CONSOLE, [view: 'instances'])),
			item('ops.activeRoutes', 'Active routes', 'Started routes / deployed routes', routes,
				[value: Cards.num(routes?.started), denom: Cards.num(routes?.total)], Cards.open(Cards.EIP_CONSOLE)),
			item('ops.openIncidents', 'Open incidents', 'Open process incidents', incidents,
				[value: openIncidents, tone: openIncidents > 0 ? 'danger' : null], Cards.open(Cards.BPM_CONSOLE, [view: 'incidents'])),
			item('ops.failedExchanges', 'Failed exchanges', 'Failed exchanges since context start', throughput,
				[value: failed, format: 'compact', tone: failed > 0 ? 'danger' : null], Cards.open(Cards.EIP_CONSOLE, [status: 'failed'])),
			item('ops.connectionIssues', 'Connection issues', 'External endpoints not reachable', connections,
				[value: down, tone: down > 0 ? 'danger' : null], Cards.open(Cards.EIP_CONSOLE)),
			[label: Cards.msg('workspaces.title', 'Workspaces'), hint: Cards.msg('ops.workspaces.hint', 'Workspaces online / total'),
				open: Cards.open(Cards.WORKSPACE_MANAGER)] + (workspaces == null ? [text: '—'] : [
					value: Cards.num(workspaces.online), denom: Cards.num(workspaces.total),
					tone: (failedWorkspaces > 0 || engineIssues > 0) ? 'danger' : (starting > 0 ? 'warning' : null)]),
		];
		if (cluster == null) {
			items.add([label: Cards.msg('cluster.title', 'Cluster'), text: '—']);
		} else if (cluster.enabled) {
			items.add([label: Cards.msg('cluster.title', 'Cluster'),
				hint: Cards.msg('cluster.hint', 'Nodes with a fresh heartbeat / registered nodes'),
				value: Cards.num(cluster.alive), denom: Cards.num(cluster.total), tone: stale > 0 ? 'danger' : null]);
		} else {
			items.add([label: Cards.msg('cluster.title', 'Cluster'),
				hint: Cards.msg('cluster.singleHint', 'Running as a single node without clustering'),
				badge: true, tone: 'neutral', text: Cards.msg('cluster.single', 'Single')]);
		}
		items.add([label: Cards.msg('ops.overall', 'Overall'), badge: true,
			tone: status == 'issue' ? 'danger' : (status == 'attention' ? 'warning' : 'success'),
			text: status == 'issue' ? Cards.msg('ops.overall.issues', 'Issues') :
				status == 'attention' ? Cards.msg('ops.overall.attention', 'Attention') : Cards.msg('ops.overall.healthy', 'Healthy')]);

		return [status: status, facts: [status: status], blocks: [[type: 'kpis', items: items]]];
	}

	private static Map item(String id, String label, String hint, Map facts, Map value, Map open) {
		Map item = [label: Cards.msg(id, label), hint: Cards.msg(id + '.hint', hint), open: open];
		return item + (facts == null ? [text: '—'] : value);
	}

}

package webtop.dashboard;

/**
 * "External connections": whether the systems the routes talk to are
 * reachable. A system counts as down when any node reports it down;
 * maximized, the card shows each node.
 */
class Connections {

	static Map part(scriptAPI, Map card) {
		return EipParts.collect(card);
	}

	static Map snapshot(scriptAPI, Map card) {
		Map<String, Map> parts = EipParts.partsOf(card);
		int remote = (parts.values().collect { Cards.num(it.remote) }.max() ?: 0) as int;
		Map<String, Map> problems = [:];
		parts.each { node, part ->
			for (Map p : ((part.problems ?: []) as List<Map>)) {
				String key = "${p.routeId} ${p.uri}".toString();
				Map merged = problems[key];
				if (merged == null) {
					merged = [routeId: p.routeId, uri: p.uri, component: p.component, nodes: []];
					problems[key] = merged;
				}
				(merged.nodes as List).add(node);
			}
		};
		int down = Math.max(problems.size(), (parts.values().collect { Cards.num(it.down) }.max() ?: 0) as int);

		List blocks = [
			[type: 'metric', value: Math.max(0, remote - down), denom: remote, tone: down > 0 ? 'danger' : null,
				unit: Cards.msg('connections.endpointsReachable', 'endpoints reachable')],
		];
		if (problems) {
			blocks.add([type: 'list', items: problems.values().take(6).collect { Map p ->
				[icon: 'bi-exclamation-triangle', title: EipParts.endpointLabel(p.uri as String, p.component as String),
					sub: parts.size() > 1 ?
						Cards.msg('connections.problemSub', '{route} · {nodes}', [route: p.routeId, nodes: (p.nodes as List).join(', ')]) :
						p.routeId,
					hint: p.uri,
					badges: [[text: Cards.msg('connections.down', 'Down'), tone: 'danger']],
					open: Cards.open(Cards.EIP_CONSOLE, [routeId: p.routeId])];
			}]);
		} else {
			blocks.add([type: 'note', text: remote > 0 ?
				Cards.msg('connections.allReachable', 'All external systems are reachable.') :
				Cards.msg('connections.none', 'No external connections in use.')]);
		}
		return [
			status: down > 0 ? 'issue' : 'ok',
			facts: [remote: remote, down: down],
			blocks: blocks,
		];
	}

	static Map detail(scriptAPI, Map card) {
		Map summary = snapshot(scriptAPI, card);
		Map<String, Map> parts = EipParts.partsOf(card);
		(summary.blocks as List).add([type: 'list', title: Cards.msg('detail.byNode', 'By node'),
			items: parts.collect { node, part ->
				int remote = Cards.num(part.remote);
				int down = Cards.num(part.down);
				[icon: 'bi-hdd-network', title: node,
					sub: Cards.msg('connections.nodeSub', '{reachable} of {total} reachable', [reachable: Math.max(0, remote - down), total: remote]),
					badges: [[text: down > 0 ? Cards.msg('connections.down', 'Down') : Cards.msg('cluster.link.both', 'Connected'),
						tone: down > 0 ? 'danger' : 'success']]];
			}]);
		return summary;
	}

}

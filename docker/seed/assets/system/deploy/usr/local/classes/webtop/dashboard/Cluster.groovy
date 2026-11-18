package webtop.dashboard;

/**
 * "Cluster": the nodes, whether each is alive, and whether they are
 * connected to each other — or that this is a single node, said outright.
 *
 * The snapshot is the same on every node, so it holds nothing that is true
 * of the node that made it only: a connection is shown as the two nodes
 * themselves report it.
 */
class Cluster {

	static Map snapshot(scriptAPI, Map card) {
		Map cluster = (card.graphql.query('''
			query {
				cluster {
					enabled nodeId
					members { nodeId hostName lastHeartbeat alive address reportedLinks { nodeId outbound } }
				}
			}
		''').cluster ?: [:]) as Map;

		if (!cluster.enabled) {
			List blocks = [
				[type: 'metric', value: 1, unit: Cards.msg('cluster.singleNodeUnit', 'node (single)')],
				[type: 'note', text: Cards.msg('cluster.standalone', 'Clustering is not configured; this workspace runs on a single node.')],
			];
			if (cluster.nodeId) {
				blocks.add([type: 'rows', items: [
					[label: Cards.msg('cluster.thisNode', 'This node'), icon: 'bi-hdd-network', text: cluster.nodeId],
				]]);
			}
			return [status: 'ok', facts: [enabled: false, total: 1, alive: 1, stale: 0, linkIssues: 0], blocks: blocks];
		}

		List<Map> members = (cluster.members ?: []) as List<Map>;
		List<Map> live = members.findAll { it.alive };
		List<String> liveIds = live.collect { it.nodeId as String };
		// from -> to -> the connection as "from" reports it
		Map<String, Map<String, String>> outbound = [:];
		for (Map m : live) {
			Map<String, String> links = [:];
			for (Map link : ((m.reportedLinks ?: []) as List<Map>)) {
				links[link.nodeId as String] = link.outbound as String;
			}
			outbound[m.nodeId as String] = links;
		}

		int linkIssues = 0;
		List items = [];
		for (Map m : members) {
			String id = m.nodeId as String;
			List badges = [];
			if (m.alive && liveIds.size() > 1) {
				// Connected when this node reaches every other live node and every other reaches it.
				int up = 0, expected = 0;
				for (String other : liveIds) {
					if (other == id) {
						continue;
					}
					expected += 2;
					if (outbound[id]?.get(other) == 'connected') {
						up++;
					}
					if (outbound[other]?.get(id) == 'connected') {
						up++;
					}
				}
				String link = up == expected ? 'both' : (up > 0 ? 'one' : 'none');
				if (link != 'both') {
					linkIssues++;
				}
				badges.add([
					tone: link == 'both' ? 'success' : (link == 'one' ? 'warning' : 'danger'),
					hint: Cards.msg('cluster.link.hint', 'Cluster connection in both directions'),
					text: link == 'both' ? Cards.msg('cluster.link.both', 'Connected') :
						link == 'one' ? Cards.msg('cluster.link.one', 'One way') : Cards.msg('cluster.link.none', 'Not connected'),
				]);
			}
			badges.add([tone: m.alive ? 'success' : 'danger',
				text: m.alive ? Cards.msg('cluster.alive', 'Alive') : Cards.msg('cluster.stale', 'Stale')]);
			Object address = m.address ?: Cards.msg('cluster.noAddress', 'no cluster port');
			items.add([icon: 'bi-hdd-network', title: (m.hostName ?: id), hint: id, static: true,
				// The age of the heartbeat is told for a stale node only: for a live one it would change the snapshot at every beat.
				sub: (m.alive || !m.lastHeartbeat) ?
					Cards.msg('cluster.memberSub', '{node} · {address}', [node: id, address: address]) :
					Cards.msg('cluster.staleSub', '{node} · {address} · heartbeat {age} ago', [node: id, address: address, age: [age: m.lastHeartbeat]]),
				badges: badges]);
		}

		int stale = members.size() - live.size();
		List blocks = [
			[type: 'metric', value: live.size(), denom: members.size(), tone: stale > 0 ? 'danger' : null,
				unit: Cards.msg('cluster.nodesAlive', 'nodes alive')],
			[type: 'list', items: items],
		];
		if (liveIds.size() >= 3) {
			blocks.add([type: 'matrix', caption: Cards.msg('cluster.matrix', 'Connections reported by each node (row to column)'),
				columns: liveIds,
				rows: liveIds.collect { String from ->
					[label: from, cells: liveIds.collect { String to ->
						if (to == from) {
							return 'self';
						}
						String state = outbound[from]?.get(to);
						return state == null ? 'unknown' : (state == 'connected' ? 'ok' : (state == 'connecting' ? 'warn' : 'down'));
					}];
				}]);
		}
		return [
			status: stale > 0 ? 'issue' : (linkIssues > 0 ? 'attention' : 'ok'),
			facts: [enabled: true, total: members.size(), alive: live.size(), stale: stale, linkIssues: linkIssues],
			blocks: blocks,
		];
	}

}

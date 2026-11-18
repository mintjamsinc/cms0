package webtop.dashboard;

/**
 * "Workspaces": the workspaces of the repository, whether each runs on
 * every node, and whether its engines do.
 */
class Workspaces {

	static Map snapshot(scriptAPI, Map card) {
		List<Map> list = (card.graphql.query('''
			query {
				workspaces {
					name displayName current system state stateMessage clusterState
					processEngine { enabled running }
					integrationEngine { enabled running }
					nodes {
						nodeId hostName alive state stateMessage
						processEngine { enabled running }
						integrationEngine { enabled running }
					}
				}
			}
		''').workspaces ?: []) as List<Map>;

		int online = 0, failedCount = 0, starting = 0, engineIssues = 0;
		Map open = Cards.open(Cards.WORKSPACE_MANAGER);
		List items = [];
		for (Map w : list) {
			// The state in the cluster: online means on every node that is alive.
			String state = (w.clusterState ?: w.state) as String;
			boolean isOnline = state == 'ONLINE';
			boolean partial = state == 'DEGRADED';
			boolean failed = state == 'FAILED' || partial;
			boolean stopped = state == 'STOPPED';
			List<Map> alive = ((w.nodes ?: []) as List<Map>).findAll { it.alive };
			Map bpm = engine(w.processEngine as Map, w.state == 'ONLINE');
			Map eip = engine(w.integrationEngine as Map, w.state == 'ONLINE');
			// An engine switched on that does not run on a node running the workspace failed to start there.
			boolean nodeEngineFailed = alive.any { Map n ->
				n.state == 'ONLINE' && (((n.processEngine as Map)?.enabled && !(n.processEngine as Map).running) ||
					((n.integrationEngine as Map)?.enabled && !(n.integrationEngine as Map).running));
			};
			boolean degraded = isOnline && (bpm.failed || eip.failed || nodeEngineFailed);
			int onlineNodes = alive.count { it.state == 'ONLINE' } as int;

			if (isOnline) {
				online++;
			}
			if (failed) {
				failedCount++;
			}
			if (!isOnline && !failed) {
				starting++;
			}
			if (degraded) {
				engineIssues++;
			}

			List<Map> failedNodes = alive.findAll { it.state == 'FAILED' };
			String message = failedNodes ?
				failedNodes.collect { "${it.hostName ?: it.nodeId}: ${it.stateMessage ?: ''}".toString() }.join('\n') :
				((w.stateMessage ?: '') as String);
			Map badge = [
				tone: failed ? 'danger' : (stopped ? 'neutral' : (!isOnline ? 'warning' : (degraded ? 'danger' : 'success'))),
				text: partial ?
					Cards.msg('workspaces.pill.partial', 'Degraded {online}/{total}', [online: onlineNodes, total: alive.size()]) :
					failed ? Cards.msg('workspaces.pill.failed', 'Failed') :
					stopped ? Cards.msg('workspaces.pill.stopped', 'Stopped') :
					!isOnline ? Cards.msg('workspaces.pill.starting', 'Starting') :
					degraded ? Cards.msg('workspaces.pill.degraded', 'Degraded') :
					Cards.msg('workspaces.pill.online', 'Online'),
			];
			if (message) {
				badge.hint = message.length() > 500 ? message.substring(0, 500) : message;
			}
			List tags = [];
			if (w.system) {
				tags.add(Cards.msg('workspaces.system', 'System'));
			}
			if (w.current) {
				tags.add(Cards.msg('workspaces.current', 'Current'));
			}
			items.add([icon: 'bi-collection', title: (w.displayName ?: w.name), hint: w.name, tags: tags,
				sub: Cards.msg('workspaces.engines', 'BPM {bpm} · EIP {eip}', [bpm: bpm.label, eip: eip.label]),
				badges: [badge], open: open]);
		}

		return [
			status: (failedCount > 0 || engineIssues > 0) ? 'issue' : (starting > 0 ? 'attention' : 'ok'),
			facts: [total: list.size(), online: online, failed: failedCount, starting: starting, engineIssues: engineIssues],
			blocks: [
				[type: 'metric', value: online, denom: list.size(),
					tone: (failedCount > 0 || engineIssues > 0) ? 'danger' : (starting > 0 ? 'warning' : null),
					unit: Cards.msg('workspaces.online', 'online')],
				[type: 'list', items: items, empty: Cards.msg('workspaces.empty', 'No workspace information available.')],
			],
		];
	}

	/**
	 * One engine: "enabled" is what the configuration says, "running" what
	 * is. Enabled and not running, in a workspace that is online, is a start
	 * that failed; while the workspace itself is down the engine is neither.
	 */
	private static Map engine(Map e, boolean online) {
		if (e?.running) {
			return [label: Cards.msg('workspaces.engine.running', 'running'), failed: false];
		}
		if (!online) {
			return [label: '—', failed: false];
		}
		if (e != null && e.enabled == false) {
			return [label: Cards.msg('workspaces.engine.disabled', 'off'), failed: false];
		}
		if (e != null && e.enabled == true) {
			return [label: Cards.msg('workspaces.engine.failed', 'failed'), failed: true];
		}
		return [label: '—', failed: false];
	}

}

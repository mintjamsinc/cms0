package webtop.dashboard;

/**
 * What the providers of the Dashboard's own cards share: the texts, the
 * links and the queries. A provider makes the snapshot of a card on the
 * server (webtop-dashboard.md); the Dashboard draws it, and no client
 * queries anything.
 *
 * A text of a snapshot is a string, or a message reference
 * [id, params, fallback] the Dashboard resolves in the reader's language,
 * so that one snapshot reads right for everyone. A parameter may be a text
 * again, [age: <ISO time>] (how long ago, or how far ahead) or
 * [duration: <milliseconds>].
 */
class Cards {

	/** The apps a card leads to, by folder name. */
	static final String TASKS = 'tasks';
	static final String BPM_CONSOLE = 'bpm-console';
	static final String EIP_CONSOLE = 'eip-console';
	static final String WORKSPACE_MANAGER = 'workspace-manager';

	/** How many rows a ranked list shows. */
	static final int TOP_N = 5;
	/** How many records a ranking is made from; the totals never depend on it. */
	static final int SCAN_LIMIT = 500;

	static Map msg(String id, String fallback, Map params = null) {
		Map ref = [id: 'app.dashboard.' + id, fallback: fallback];
		if (params) {
			ref.params = params;
		}
		return ref;
	}

	static Map open(String app, Map options = null) {
		return [app: app, options: options ?: [:]];
	}

	/** "order-review-flow" -> "Order review flow", for a key the engine has no name for. */
	static String humanize(String key) {
		String s = (key ?: '').replaceAll(/[._-]+/, ' ').replaceAll(/([a-z])([A-Z])/, '$1 $2').trim();
		return s ? s.substring(0, 1).toUpperCase() + s.substring(1) : '';
	}

	/** The name of a process, by the convention key the consoles read. */
	static Object processName(String key, String name) {
		String fallback = name ?: humanize(key);
		if (!key) {
			return fallback ?: msg('fallback.process', 'Process');
		}
		return [id: "bpm.process.${key}.name".toString(), params: [name: fallback], fallback: fallback];
	}

	static Object taskName(String processKey, String taskKey, String name) {
		String fallback = name ?: humanize(taskKey);
		if (!processKey || !taskKey) {
			return fallback ?: msg('fallback.task', 'Task');
		}
		return [id: "bpm.process.${processKey}.task.${taskKey}.name".toString(), params: [name: fallback], fallback: fallback];
	}

	static List nodes(Map connection) {
		return ((connection?.edges ?: []) as List).collect { it.node };
	}

	/** The names of the deployed processes, by key. */
	static Map<String, String> processNames(Map card) {
		Map data = card.graphql.query('''
			query { processDefinitions(latestVersion: true, first: 1000) { edges { node { key name } } } }
		''');
		Map<String, String> names = [:];
		for (Map d : nodes(data.processDefinitions as Map)) {
			names[d.key as String] = d.name as String;
		}
		return names;
	}

	static Map taskCounts(Map card, Map variables) {
		Map data = card.graphql.query('''
			query($assignee: String, $candidateUser: String, $candidateGroups: [String!], $timeZone: String) {
				taskCounts(assignee: $assignee, candidateUser: $candidateUser, candidateGroups: $candidateGroups, timeZone: $timeZone) {
					total unassigned overdue dueToday dueThisWeek
				}
			}
		''', variables);
		return (data.taskCounts ?: [:]) as Map;
	}

	static int num(Object value) {
		return (value instanceof Number) ? (value as Number).intValue() : 0;
	}

	static long lng(Object value) {
		return (value instanceof Number) ? (value as Number).longValue() : 0L;
	}

	/** The fact of another card the snapshot of this one is made from; null when that card has none yet. */
	static Map factsOf(Map card, String name) {
		Object snapshot = (card.snapshots as Map)?.get(card.app + '/' + name);
		return (snapshot instanceof Map) ? (snapshot.facts as Map) : null;
	}

}

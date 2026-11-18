package webtop.notices;

/**
 * Tells a user, at the desktop, that a task was assigned to them: a notice
 * in the notification center (Notices), kept for as long as the task is
 * theirs. The process engine's task events arrive on EventAdmin
 * (org/camunda/bpm/engine/task/Task/<ACTION>, bpm-eventadmin.md), on the
 * node that committed the change only; the route
 * etc/eip/routes/webtop/notices.xml hands each to {@link #taskEvent}.
 *
 * An event tells what the task is now, not what it was, so who holds the
 * notice of a task is kept beside the notices:
 *
 *   /var/lib/notices/tasks/<xx>/<taskId>    notice:user = the user told
 *
 * Every event brings the notice in line with the task: it is taken back
 * from the user who was told when the task is no longer theirs (given to
 * someone else, given back, completed, deleted), and written for the user
 * the task was just assigned to. A change missed once is therefore put
 * right by the next event of the same task.
 *
 * Nobody is told of what they did themselves: a task one claimed, or
 * assigned to oneself (ASSIGNED, with the assignee as the actor), leaves no
 * notice. A task the process creates for the user who moved it on — the
 * progress task of a process they started, the next step after one they
 * completed — is new work put before them, and is told (CREATED, whoever
 * the actor). The candidates of a task are not told; what they may claim
 * is on the Dashboard.
 *
 * The texts are the Webtop's own messages (webtop.notification.task.*),
 * resolved in the reader's language; the task's name is given as the
 * convention key the Tasks app reads (bpm.process.<process>.task.<task>.name),
 * with the engine's name as {name} and as the fallback.
 */
class TaskNotices {

	static final String APP = 'tasks';
	static final String RECORDS = '/var/lib/notices/tasks';
	static final String USER_PROPERTY = 'notice:user';
	static final String TYPE = 'application/vnd.mintjams.webtop.notice.task+json';
	/** The events that give a task to someone. */
	static final List<String> GIVEN = ['CREATED', 'ASSIGNED'];
	static final List<String> DONE = ['COMPLETED', 'DELETED'];

	/** A task event: brings the notice of the task in line with who the task is assigned to. */
	static void taskEvent(scriptAPI, event) {
		String topic = event.getTopic() as String;
		String action = topic.substring(topic.lastIndexOf('/') + 1);
		String taskId = event.getProperty('taskId') as String;
		if (!taskId || !(taskId ==~ /[A-Za-z0-9_.\-]{1,255}/)) {
			return;
		}
		String assignee = DONE.contains(action) ? null : (event.getProperty('assignee') as String);
		String actor = event.getProperty('actor') as String;

		String told = toldOf(scriptAPI, taskId);
		if (told && told != assignee) {
			Notices.withdraw(scriptAPI, key(taskId), [told]);
			forget(scriptAPI, taskId);
			told = null;
		}
		boolean ownDoing = (action == 'ASSIGNED' && assignee == actor);
		if (assignee && assignee != told && !ownDoing && GIVEN.contains(action)) {
			if (Notices.publish(scriptAPI, assigned(event, taskId), [assignee])) {
				remember(scriptAPI, taskId, assignee);
			}
		}
	}

	static String key(String taskId) {
		return 'task:' + taskId;
	}

	/** The notice: "a task was assigned to you", opening the Tasks app at the task. */
	static Map assigned(event, String taskId) {
		String definitionKey = event.getProperty('taskDefinitionKey') as String;
		String name = (event.getProperty('taskName') as String) ?: definitionKey ?: taskId;
		String processId = event.getProperty('processDefinitionId') as String;
		String processKey = processId ? processId.split(':')[0] : null;
		Object nameText = (processKey && definitionKey)
			? [id: "bpm.process.${processKey}.task.${definitionKey}.name".toString(), params: [name: name], fallback: name]
			: name;
		return [
			app: APP,
			title: [id: 'webtop.notification.task.assigned.title', fallback: 'New task'],
			body: [id: 'webtop.notification.task.assigned.body', params: [name: nameText],
				fallback: "Task \"${name}\" was assigned to you.".toString()],
			icon: 'bi-check2-square',
			options: [scope: 'assigned', taskId: taskId],
			key: key(taskId),
		];
	}

	// --- who was told -------------------------------------------------------------

	/** The records are spread over folders by the end of the id, which varies whatever the id generator. */
	private static String folderOf(String taskId) {
		String end = taskId.length() <= 2 ? taskId : taskId.substring(taskId.length() - 2);
		return "${RECORDS}/${end.toLowerCase()}".toString();
	}

	private static String recordOf(String taskId) {
		return "${folderOf(taskId)}/${taskId}".toString();
	}

	/** The user holding the notice of the task, or null. */
	private static String toldOf(scriptAPI, String taskId) {
		return Notices.withService(scriptAPI) { service ->
			def record = service.session.getResource(recordOf(taskId));
			if (!record.exists() || !record.hasProperty(USER_PROPERTY)) {
				return null;
			}
			return record.getProperty(USER_PROPERTY).getString();
		} as String;
	}

	private static void remember(scriptAPI, String taskId, String user) {
		Notices.withService(scriptAPI) { service ->
			def session = service.session;
			try {
				def folder = session.getResource(folderOf(taskId));
				if (!folder.exists()) {
					folder.createFolder();
				}
				def record = session.getResource(recordOf(taskId));
				if (!record.exists()) {
					record.createFile();
					record.write('{}');
					record.setContentType(TYPE);
					record.setContentEncoding('UTF-8');
				}
				record.setProperty(USER_PROPERTY, user);
				session.commit();
			} catch (Throwable ex) {
				session.rollback();
				throw ex;
			}
		};
	}

	private static void forget(scriptAPI, String taskId) {
		Notices.withService(scriptAPI) { service ->
			def session = service.session;
			try {
				def record = session.getResource(recordOf(taskId));
				if (record.exists()) {
					record.remove();
					session.commit();
				}
			} catch (Throwable ex) {
				session.rollback();
				throw ex;
			}
		};
	}

}

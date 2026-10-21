package webtop.notices;

/**
 * Tells a user, at the desktop, that a task was assigned to them: a notice
 * in the notification center (Notices), kept until the task is done. The
 * process engine's task events arrive on EventAdmin
 * (org/camunda/bpm/engine/task/Task/<ACTION>, bpm-eventadmin.md); the route
 * etc/eip/routes/webtop/notices.xml hands each to {@link #taskEvent}.
 *
 * The texts are the Webtop's own messages (webtop.notification.task.*),
 * resolved in the reader's language; the task's name is given as the
 * convention key the Tasks app reads (bpm.process.<process>.task.<task>.name),
 * with the engine's name as {name} and as the fallback.
 */
class TaskNotices {

	static final String APP = 'tasks';
	static final String ASSIGNED = 'ASSIGNED';
	static final List<String> DONE = ['COMPLETED', 'DELETED'];

	/** A task event: a notice for the assignee of an assignment, taken back when the task is done. */
	static void taskEvent(scriptAPI, event) {
		String topic = event.getTopic() as String;
		String action = topic.substring(topic.lastIndexOf('/') + 1);
		String taskId = event.getProperty('taskId') as String;
		String assignee = event.getProperty('assignee') as String;
		if (!taskId || !assignee) {
			return;
		}
		if (action == ASSIGNED) {
			Notices.publish(scriptAPI, assigned(event, taskId), [assignee]);
		} else if (DONE.contains(action)) {
			Notices.withdraw(scriptAPI, key(taskId), [assignee]);
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

}

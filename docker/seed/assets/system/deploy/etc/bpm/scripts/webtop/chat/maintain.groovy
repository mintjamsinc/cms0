// Removes old chat messages, and the conversations of files that are gone.
//
// Invoked by the "Remove old messages" service task of chat-maintenance.bpmn
// via CmsDelegate with runAs=chat-service-user, which owns the chat area.
// Because the session user is the service account, the ADMIN GATE below
// validates the process *initiator* — the start form's role check is display
// control only.
//
// Inputs (process variables):
//   initiator - set by camunda:initiator on the start event
//   targets   - comma-separated: channels, dm, files (what to remove from)
//   keepDays  - how many days of messages to keep; empty removes none
//   orphans   - whether to remove the conversations of files that are gone
// Outputs:
//   result    - JSON with the counts: days, conversations, signalDays,
//               mentionDays, orphans, errors

import org.camunda.bpm.engine.delegate.BpmnError

if (!initiator) {
	throw new BpmnError('chat.maintenance.unauthorized', 'The process has no initiator.')
}

// ---- ADMIN GATE -----------------------------------------------------------
def user = repositorySession.getIdentityProvider().getUser(initiator)
if (user == null || !user.hasRole('administrator')) {
	log.warn("Chat maintenance rejected: '${initiator}' does not have the administrator role.")
	throw new BpmnError('chat.maintenance.unauthorized',
			"User '${initiator}' is not permitted to run the chat maintenance.")
}

// ---- Options --------------------------------------------------------------
def optional = { Closure read ->
	try {
		return read.call()
	} catch (MissingPropertyException ignore) {
		return null
	}
}
def options = [
	targets: (optional { targets } ?: '').toString().split(',').collect { it.trim() }.findAll { it },
	keepDays: null,
	orphans: (optional { orphans }?.toString() == 'true'),
]
def days = optional { keepDays }
if (days != null && days.toString().trim()) {
	options.keepDays = days.toString().trim() as int
}

// ---- Run ------------------------------------------------------------------
log.info("Chat maintenance started by '${initiator}': ${options}")
def summary = webtop.chat.ChatMaintenance.create(repositorySession, log).run(options)
result = JSON.stringify(summary)
log.info("Chat maintenance by '${initiator}' done: ${result}")

// Dispatches a search index rebuild to every cluster node (or, on a re-run,
// to the nodes named in rerunScope).
//
// Invoked by the "Dispatch Rebuild" service task of search-index-rebuild.bpmn
// via CmsDelegate with runAs=searchindex-service-user. Because the session
// user is the service account, the ADMIN GATE below validates the process
// *initiator* — this is the actual authorization gate for the whole feature
// (the start form's role check is display control only).
//
// Inputs (process variables):
//   initiator  - set by camunda:initiator on the start event
//   rebuildId  - correlation key minted by the start form
//   rerunScope - optional; comma-separated node ids or 'all' (re-run path)
//   jobIds     - optional; the previous dispatch's JSON map (re-run path)
// Outputs:
//   jobIds     - JSON object mapping nodeId -> jobId, merged over any
//                previous dispatch so the progress form keeps seeing the
//                completed nodes' jobs alongside the re-dispatched ones.
//
// Progress itself is never written to process variables (every variable
// update emits Camunda history); the jobs write their progress to the
// /var/jobs records, which the progress form watches via jobProgress.

import org.camunda.bpm.engine.delegate.BpmnError

if (!initiator) {
	throw new BpmnError('searchindex.rebuild.unauthorized', 'The process has no initiator.')
}

// ---- ADMIN GATE -----------------------------------------------------------
def user = repositorySession.getIdentityProvider().getUser(initiator)
if (user == null || !user.hasRole('administrator')) {
	log.warn("Search index rebuild rejected: '${initiator}' does not have the administrator role.")
	throw new BpmnError('searchindex.rebuild.unauthorized',
			"User '${initiator}' is not permitted to rebuild the search index.")
}

// ---- Dispatch -------------------------------------------------------------
// rerunScope absent / empty / 'all' means every alive node.
def scope = null
try {
	if (rerunScope && rerunScope != 'all') {
		scope = rerunScope
	}
} catch (MissingPropertyException ignore) {}

def dispatched = SearchIndexAPI.requestRebuild(rebuildId, scope)

// Merge over the previous dispatch (re-run replaces only the re-dispatched
// nodes' jobs; the others keep their finished records).
def merged = [:]
try {
	if (jobIds) {
		merged.putAll(JSON.parse(jobIds))
	}
} catch (MissingPropertyException ignore) {
} catch (Throwable ex) {
	log.warn("Ignoring the unparseable previous jobIds of rebuild '${rebuildId}'.", ex)
}
merged.putAll(dispatched)

jobIds = JSON.stringify(merged)
log.info("Search index rebuild '${rebuildId}' dispatched by '${initiator}': ${jobIds}")

// Queues the uninstallation of the packages the initiator decided to
// uninstall.
//
// Invoked by the "Dispatch Uninstallation" service task of
// package-uninstall.bpmn via CmsDelegate with runAs=package-service-user.
// Because the session user is the service account, the ADMIN GATE below
// validates the process *initiator* — the start form's role check is display
// control only — and PackageAPI checks the role again for the user it acts
// for.
//
// The uninstallation runs as a background job (PackageAPI.requestUninstall):
// it checks the selection again, refuses it when a check now reports an
// error, and removes the packages together. The progress form watches the
// job; this script only queues it. The re-run path comes back here and
// queues a new job.
//
// Inputs (process variables):
//   initiator  - set by camunda:initiator on the start event
//   packageIds - the selected package ids, comma-separated
// Outputs:
//   jobId      - the uninstallation job; jobProgress(jobId) follows it

import org.camunda.bpm.engine.delegate.BpmnError

if (!initiator) {
	throw new BpmnError('packages.install.unauthorized', 'The process has no initiator.')
}

// ---- ADMIN GATE -----------------------------------------------------------
def user = repositorySession.getIdentityProvider().getUser(initiator)
if (user == null || !user.hasRole('administrator')) {
	log.warn("Package uninstallation rejected: '${initiator}' does not have the administrator role.")
	throw new BpmnError('packages.install.unauthorized',
			"User '${initiator}' is not permitted to uninstall packages.")
}

// ---- Dispatch -------------------------------------------------------------
jobId = PackageAPI.requestUninstall(packageIds, initiator)
log.info("Uninstallation of '${packageIds}' queued by '${initiator}': job ${jobId}")

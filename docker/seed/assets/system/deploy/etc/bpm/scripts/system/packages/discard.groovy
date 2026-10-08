// Removes a staged package the initiator decided not to install.
//
// Invoked by the "Discard Package" service task of package-install.bpmn via
// CmsDelegate with runAs=package-service-user. The ADMIN GATE validates the
// process initiator (see inspect.groovy); PackageAPI removes only files
// under the staging area.
//
// Inputs (process variables):
//   initiator   - set by camunda:initiator on the start event
//   packagePath - the staged package, under /var/lib/packages/incoming

import org.camunda.bpm.engine.delegate.BpmnError

if (!initiator) {
	throw new BpmnError('packages.install.unauthorized', 'The process has no initiator.')
}

// ---- ADMIN GATE -----------------------------------------------------------
def user = repositorySession.getIdentityProvider().getUser(initiator)
if (user == null || !user.hasRole('administrator')) {
	log.warn("Package discard rejected: '${initiator}' does not have the administrator role.")
	throw new BpmnError('packages.install.unauthorized',
			"User '${initiator}' is not permitted to install packages.")
}

// ---- Discard --------------------------------------------------------------
try {
	def removed = PackageAPI.discard(packagePath, initiator)
	log.info("Staged package '${packagePath}' discarded by '${initiator}': removed=${removed}")
} catch (Throwable ex) {
	log.warn("The staged package '${packagePath}' could not be removed.", ex)
}

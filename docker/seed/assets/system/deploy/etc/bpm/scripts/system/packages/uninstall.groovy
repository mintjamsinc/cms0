// Uninstalls the packages the initiator confirmed, together.
//
// Invoked by the "Uninstall Packages" service task of package-uninstall.bpmn
// via CmsDelegate with runAs=package-service-user. The ADMIN GATE validates
// the process initiator (see uninstall-inspect.groovy); the installer acts
// for the initiator.
//
// The selection is inspected again inside PackageAPI.uninstall and refused
// when a check now reports an error (something changed since the
// confirmation). A failure of any kind is reported through uninstallError,
// not thrown, so the result task shows it.
//
// Inputs (process variables):
//   initiator  - set by camunda:initiator on the start event
//   packageIds - the selected package ids, comma-separated
// Outputs:
//   uninstallResult - JSON as UTF-8 bytes: packages with counts (UninstallResult); '' on failure
//   uninstallError  - the error message; '' on success

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

// ---- Uninstall ------------------------------------------------------------
uninstallResult = ''
uninstallError = ''
try {
	def result = PackageAPI.uninstall(packageIds, initiator)
	// As bytes: a string variable holds at most 4000 characters.
	uninstallResult = JSON.stringify(result).getBytes('UTF-8')
	log.info("Packages '${packageIds}' uninstalled by '${initiator}': ${JSON.stringify(result)}")
} catch (Throwable ex) {
	uninstallError = (ex.message ?: ex.class.name).toString()
	log.error("Packages '${packageIds}' could not be uninstalled by '${initiator}': ${uninstallError}", ex)
}

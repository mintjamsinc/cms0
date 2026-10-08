// Installs a staged package the initiator confirmed.
//
// Invoked by the "Install Package" service task of package-install.bpmn via
// CmsDelegate with runAs=package-service-user. The ADMIN GATE validates the
// process initiator (see inspect.groovy); the installer acts for the
// initiator, so the files and the installation record name the
// administrator who installed.
//
// The package is inspected again inside PackageAPI.install and refused when
// a check now reports an error (something changed since the confirmation).
// A failure of any kind is reported through installError, not thrown, so
// the result task shows it. The staged package is discarded either way.
//
// Inputs (process variables):
//   initiator       - set by camunda:initiator on the start event
//   packagePath     - the staged package, under /var/lib/packages/incoming
//   packageFileName - the name of the uploaded file, for messages
// Outputs:
//   installResult   - JSON: action, id, version, counts (InstallResult); '' on failure
//   installError    - the error message; '' on success

import org.camunda.bpm.engine.delegate.BpmnError

if (!initiator) {
	throw new BpmnError('packages.install.unauthorized', 'The process has no initiator.')
}

// ---- ADMIN GATE -----------------------------------------------------------
def user = repositorySession.getIdentityProvider().getUser(initiator)
if (user == null || !user.hasRole('administrator')) {
	log.warn("Package installation rejected: '${initiator}' does not have the administrator role.")
	throw new BpmnError('packages.install.unauthorized',
			"User '${initiator}' is not permitted to install packages.")
}

// ---- Install --------------------------------------------------------------
installResult = ''
installError = ''
try {
	def result = PackageAPI.install(packagePath, initiator)
	installResult = JSON.stringify(result)
	log.info("Package '${packagePath}' installed by '${initiator}': ${installResult}")
} catch (Throwable ex) {
	installError = (ex.message ?: ex.class.name).toString()
	log.error("Package '${packagePath}' could not be installed by '${initiator}': ${installError}", ex)
} finally {
	try {
		PackageAPI.discard(packagePath, initiator)
	} catch (Throwable ex) {
		log.warn("The staged package '${packagePath}' could not be removed.", ex)
	}
}

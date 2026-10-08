// Inspects a selection of installed packages for uninstallation: every one
// is installed, nothing that stays behind requires it, and what its recorded
// files look like now.
//
// Invoked by the "Inspect Uninstallation" service task of
// package-uninstall.bpmn via CmsDelegate with runAs=package-service-user.
// Because the session user is the service account, the ADMIN GATE below
// validates the process *initiator* — the start form's role check is display
// control only — and PackageAPI checks the role again for the user it acts for.
//
// Inputs (process variables):
//   initiator  - set by camunda:initiator on the start event
//   packageIds - the selected package ids, comma-separated
// Outputs:
//   uninstallInspection - JSON as UTF-8 bytes: packages, order, findings (UninstallInspection)
//   uninstallOk         - 'true' when no finding is an error
//   packageCount        - how many of the selected packages are installed

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

// ---- Inspect --------------------------------------------------------------
def result = PackageAPI.inspectUninstall(packageIds, initiator)

// As bytes: a string variable holds at most 4000 characters, and the file
// lists of the packages are longer. The forms get it back as text.
uninstallInspection = JSON.stringify(result).getBytes('UTF-8')
uninstallOk = result.ok ? 'true' : 'false'
packageCount = result.packages.size().toString()

log.info("Uninstallation of '${packageIds}' inspected for '${initiator}': ok=${result.ok}, "
		+ "order=${result.order}, findings=${result.findings.size()}")

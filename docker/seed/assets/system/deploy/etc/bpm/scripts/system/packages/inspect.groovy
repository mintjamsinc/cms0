// Inspects a staged package: reads it, runs the checks, and tells what
// installing it into this workspace would do.
//
// Invoked by the "Inspect Package" service task of package-install.bpmn via
// CmsDelegate with runAs=package-service-user. Because the session user is
// the service account, the ADMIN GATE below validates the process
// *initiator* — the start form's role check is display control only — and
// PackageAPI checks the role again for the user it acts for.
//
// Inputs (process variables):
//   initiator       - set by camunda:initiator on the start event
//   packagePath     - the staged package, under /var/lib/packages/incoming
//   packageFileName - the name of the uploaded file, for messages
// Outputs:
//   inspection      - JSON as UTF-8 bytes: manifest, action, findings, files (PackageInspection)
//   inspectionOk    - 'true' when no finding is an error
//   packageId, packageTitle, packageVersion - for the task list and messages

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

// ---- Inspect --------------------------------------------------------------
def result = PackageAPI.inspect(packagePath, initiator)
def manifest = result.manifest ?: [:]

// As bytes: a string variable holds at most 4000 characters, and the file
// lists of a package are longer. The forms get it back as text.
inspection = JSON.stringify(result).getBytes('UTF-8')
inspectionOk = result.ok ? 'true' : 'false'
packageId = (manifest.id ?: '').toString()
packageTitle = (manifest.title ?: result.fileName ?: '').toString()
packageVersion = (manifest.version ?: '').toString()

log.info("Package '${packagePath}' inspected for '${initiator}': ok=${result.ok}, action=${result.action}, "
		+ "id=${packageId}, version=${packageVersion}, findings=${result.findings.size()}")

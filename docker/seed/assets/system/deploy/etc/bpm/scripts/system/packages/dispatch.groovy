// Queues the installation of a staged package the initiator decided to
// install.
//
// Invoked by the "Dispatch Installation" service task of package-install.bpmn
// via CmsDelegate with runAs=package-service-user. Because the session user
// is the service account, the ADMIN GATE below validates the process
// *initiator* — the start form's role check is display control only — and
// PackageAPI checks the role again for the user it acts for.
//
// The installation runs as a background job (PackageAPI.requestInstall): it
// inspects the package again, refuses it when a check now reports an error
// or when its sha256 is no longer the one the start form showed, and
// installs. The progress form watches the job; this script only queues it.
// The re-run path comes back here and queues a new job.
//
// Inputs (process variables):
//   initiator       - set by camunda:initiator on the start event
//   packagePath     - the staged package, under /var/lib/packages/incoming
//   packageFileName - the name of the uploaded file, for messages
//   packageSha256   - the sha256 of the inspected package (start form)
// Outputs:
//   jobId           - the installation job; jobProgress(jobId) follows it

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

// ---- Dispatch -------------------------------------------------------------
def sha256 = null
try {
	sha256 = packageSha256 ?: null
} catch (MissingPropertyException ignore) {}

jobId = PackageAPI.requestInstall(packagePath, initiator, sha256)
log.info("Installation of '${packagePath}' queued by '${initiator}': job ${jobId}")

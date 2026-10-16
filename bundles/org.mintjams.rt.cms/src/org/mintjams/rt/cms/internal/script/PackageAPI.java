/*
 * Copyright (c) 2026 MintJams Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.rt.cms.internal.script;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.jcr.security.User;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.job.pkg.PackageJob;
import org.mintjams.rt.cms.internal.pkg.InstalledPackage;
import org.mintjams.rt.cms.internal.pkg.PackageInstaller;
import org.mintjams.rt.cms.internal.pkg.PackageRecords;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.seed.SeedDeployer;
import org.mintjams.script.ScriptingContext;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

/**
 * Package installation for scripts, {@code PackageAPI} in a script's binding.
 *
 * <p>Inspecting, installing and discarding act <em>for</em> a named user: the
 * user must hold the administrator role, and the work runs in a service
 * session carrying that user's id, so the installation record and the files
 * name the administrator who installed, while the script itself may run as a
 * service account that can do nothing of the sort. The BPMN route passes the
 * process initiator.</p>
 *
 * <p>Every result is plain data (maps, lists, strings, numbers), ready for
 * {@code JSON.stringify} into a process variable.</p>
 */
public class PackageAPI {

	private static final String ADMINISTRATOR_ROLE = "administrator";

	private final WorkspaceScriptContext fContext;

	public PackageAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static PackageAPI get(ScriptingContext context) {
		return (PackageAPI) context.getAttribute(PackageAPI.class.getSimpleName());
	}

	/** Where a route stages an uploaded package: {@value PackageInstaller#STAGING_ROOT}. */
	public String getStagingRoot() {
		return PackageInstaller.STAGING_ROOT;
	}

	/** The version of the running platform, or {@code null} when it is not known. */
	public String getPlatformVersion() {
		return SeedDeployer.readPlatformVersion();
	}

	/**
	 * Inspects the package at the repository path for the given administrator.
	 * See {@link org.mintjams.rt.cms.internal.pkg.PackageInspection#toMap()}
	 * for the result.
	 */
	public Map<String, Object> inspect(String packagePath, String userId) throws IOException {
		requireAdministrator(userId);
		try (WorkspaceScriptContext context = openContext(userId)) {
			return installer(context).inspect(requirePath(packagePath)).toMap();
		}
	}

	/**
	 * Installs the package at the repository path for the given administrator.
	 * See {@link org.mintjams.rt.cms.internal.pkg.InstallResult#toMap()} for the
	 * result. A package the checks refuse raises an exception naming the
	 * errors.
	 */
	public Map<String, Object> install(String packagePath, String userId) throws IOException {
		requireAdministrator(userId);
		try (WorkspaceScriptContext context = openContext(userId)) {
			return installer(context).install(requirePath(packagePath), userId).toMap();
		}
	}

	/**
	 * Queues the installation of the package at the repository path for the
	 * given administrator, as a background job on this node, and returns the
	 * job id at once. The job's progress and result are read with
	 * {@code jobProgress(jobId)}; see
	 * {@link org.mintjams.rt.cms.internal.job.pkg.PackageJob}.
	 *
	 * @param sha256 the SHA-256 of the package the installation was decided
	 *               on (the inspection's {@code sha256}); the job refuses a
	 *               file that has changed since. {@code null} or empty
	 *               installs whatever the file holds when the job runs.
	 */
	public String requestInstall(String packagePath, String userId, String sha256) throws IOException {
		requireAdministrator(userId);
		String path = requirePath(packagePath);
		String digest = Strings.isEmpty(sha256) ? null : sha256.trim();
		String jobId = JobNodes.newJobId();
		createJobRecord(jobId, PackageJob.TYPE_INSTALL, userId, path, null);
		CmsService.getJobManager().submit(PackageJob.install(jobId, fContext.getWorkspaceName(), userId, path, digest));
		return jobId;
	}

	/**
	 * Queues the uninstallation of the given packages together for the given
	 * administrator, as a background job on this node, and returns the job id
	 * at once. {@code ids} is a collection or array of ids, or one
	 * comma-separated string.
	 */
	public String requestUninstall(Object ids, String userId) throws IOException {
		requireAdministrator(userId);
		List<String> packageIds = ids(ids);
		packageIds.removeIf(Strings::isEmpty);
		if (packageIds.isEmpty()) {
			throw new IOException("No package is selected.");
		}
		String jobId = JobNodes.newJobId();
		createJobRecord(jobId, PackageJob.TYPE_UNINSTALL, userId, null, packageIds);
		CmsService.getJobManager().submit(PackageJob.uninstall(jobId, fContext.getWorkspaceName(), userId, packageIds));
		return jobId;
	}

	/**
	 * Removes a staged package (under the staging root) for the given
	 * administrator. Returns false when there was nothing to remove.
	 */
	public boolean discard(String packagePath, String userId) throws IOException {
		requireAdministrator(userId);
		try (WorkspaceScriptContext context = openContext(userId)) {
			return installer(context).discard(requirePath(packagePath));
		}
	}

	/**
	 * Inspects uninstalling the given packages for the given administrator.
	 * {@code ids} is a collection or array of ids, or one comma-separated
	 * string. See {@link org.mintjams.rt.cms.internal.pkg.UninstallInspection#toMap()}
	 * for the result.
	 */
	public Map<String, Object> inspectUninstall(Object ids, String userId) throws IOException {
		requireAdministrator(userId);
		try (WorkspaceScriptContext context = openContext(userId)) {
			return installer(context).inspectUninstall(ids(ids)).toMap();
		}
	}

	/**
	 * Uninstalls the given packages together for the given administrator. See
	 * {@link org.mintjams.rt.cms.internal.pkg.UninstallResult#toMap()} for the
	 * result. A selection the checks refuse raises an exception naming the
	 * errors.
	 */
	public Map<String, Object> uninstall(Object ids, String userId) throws IOException {
		requireAdministrator(userId);
		try (WorkspaceScriptContext context = openContext(userId)) {
			return installer(context).uninstall(ids(ids), userId).toMap();
		}
	}

	/** The packages installed in this workspace, as plain data. */
	public List<Map<String, Object>> listInstalled() throws IOException {
		List<Map<String, Object>> result = new ArrayList<>();
		for (InstalledPackage p : records().list()) {
			result.add(p.toMap());
		}
		return result;
	}

	/** The installed package with this id, as plain data, or {@code null}. */
	public Map<String, Object> getInstalled(String id) throws IOException {
		InstalledPackage p = records().get(id);
		return p != null ? p.toMap() : null;
	}

	private static PackageInstaller installer(WorkspaceScriptContext context) throws IOException {
		try {
			return new PackageInstaller(context.getSession());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private PackageRecords records() throws IOException {
		try {
			return new PackageRecords(fContext.getSession());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private void requireAdministrator(String userId) throws IOException {
		if (Strings.isEmpty(userId)) {
			throw new IOException("A user is required to install packages.");
		}
		User user;
		try {
			user = fContext.getSession().getIdentityProvider().getUser(userId);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
		if (user == null || !user.hasRole(ADMINISTRATOR_ROLE)) {
			throw new IOException("User '" + userId + "' is not permitted to install packages.");
		}
	}

	/** Persists the QUEUED record of a package job, owned by this node, attributed to the user. */
	private void createJobRecord(String jobId, String jobType, String userId, String packagePath, List<String> ids)
			throws IOException {
		try {
			Session session = CmsService.getRepository()
					.login(new CmsServiceCredentials(userId), fContext.getWorkspaceName());
			try {
				Node content = JobNodes.getContent(JobNodes.createJobNode(session, jobId, jobType, userId, 0));
				if (packagePath != null) {
					content.setProperty(JobNodes.PROP_PACKAGE_PATH, packagePath);
				}
				if (ids != null) {
					content.setProperty(JobNodes.PROP_PACKAGE_IDS, ids.toArray(new String[ids.size()]));
				}
				JobNodes.setNodeId(session, content);
				JobNodes.setStatus(content, JobStatus.QUEUED);
				session.save();
			} finally {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private WorkspaceScriptContext openContext(String userId) {
		WorkspaceScriptContext context = new WorkspaceScriptContext(fContext.getWorkspaceName());
		context.setCredentials(new CmsServiceCredentials(userId));
		return context;
	}

	private static List<String> ids(Object value) {
		List<String> result = new ArrayList<>();
		if (value == null) {
			return result;
		}
		if (value instanceof java.util.Collection) {
			for (Object o : (java.util.Collection<?>) value) {
				if (o != null) {
					result.add(o.toString());
				}
			}
			return result;
		}
		if (value instanceof Object[]) {
			for (Object o : (Object[]) value) {
				if (o != null) {
					result.add(o.toString());
				}
			}
			return result;
		}
		for (String s : value.toString().split(",")) {
			result.add(s.trim());
		}
		return result;
	}

	private static String requirePath(String packagePath) throws IOException {
		if (Strings.isEmpty(packagePath) || !packagePath.trim().startsWith("/")) {
			throw new IOException("An absolute repository path to the package is required.");
		}
		return packagePath.trim();
	}

}

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

package org.mintjams.rt.cms.internal.pkg;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mintjams.jcr.cluster.ClusterLeaseStore;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.provisioning.Provisioner;
import org.mintjams.rt.cms.internal.seed.SeedDeployer;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Session;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

/**
 * Installs packages into a workspace.
 *
 * <p>The installer is the one piece every route shares: the Tasks app's
 * process and, later, a vendor's portal app both hand it a package file in
 * the repository and get the same inspection and the same installation. It
 * verifies the manifest and runs the {@link PackageChecks}; it does not
 * verify a signature, because the package was obtained by someone who chose
 * where to get it from.</p>
 *
 * <h2>Inspection</h2>
 * <p>{@link #inspect(String)} reads the package once and tells what it is,
 * whether it is a first installation, an upgrade or a reinstallation, what
 * an upgrade would remove, and what the checks found. Nothing is written.</p>
 *
 * <h2>Installation</h2>
 * <p>{@link #install(String, String)} inspects again and refuses a package
 * with an error finding. Otherwise, under the cluster lease the content
 * deployment also takes, it applies the provisioning descriptors, writes
 * every file under {@code deploy/} to its path, removes the files of the
 * previous version the package no longer ships (and the folders that leaves
 * empty), writes the installation record, and commits all of that at once.
 * A failure rolls the session back, so the workspace keeps the previous
 * version; the provisioning that already ran is idempotent and harmless.</p>
 *
 * <p>The order is place, remove, record: an interrupted run leaves old and
 * new files side by side, never a workspace without the package, and running
 * it again converges.</p>
 *
 * <p>The session must be privileged (an administrator, or a service session
 * acting for one): a package writes wherever its files say, within what
 * {@link org.mintjams.rt.cms.internal.pkg.checks.ReservedPathCheck} allows.</p>
 */
public final class PackageInstaller {

	/** The area the installer keeps for itself; closed to everyone but administrators by provisioning. */
	public static final String STAGING_AREA = "/var/lib/packages";
	/** Where a route stages an uploaded package before it is inspected and installed. */
	public static final String STAGING_ROOT = STAGING_AREA + "/incoming";

	/** The lease {@code Session.deploy()} takes, so an installation never runs during a deployment. */
	private static final String LEASE_NAME = "content-deployment";
	private static final long LEASE_TTL_MILLIS = 3600000L;

	private final Session fSession;
	private final List<PackageCheck> fChecks;

	public PackageInstaller(Session session) {
		this(session, PackageChecks.defaults());
	}

	public PackageInstaller(Session session, List<PackageCheck> checks) {
		fSession = session;
		fChecks = checks;
	}

	/**
	 * Reads the package at the given repository path and tells what installing
	 * it into this workspace would do. Never throws for a problem in the
	 * package; those are findings.
	 */
	public PackageInspection inspect(String packagePath) throws IOException {
		String workspaceName = fSession.getWorkspace().getName();
		PackageSource source = new PackageSource(fSession, packagePath);
		List<Finding> findings = new ArrayList<>();

		String fileName = null;
		PackageContents contents = null;
		try {
			if (!source.exists()) {
				findings.add(Finding.error("package.missing", "There is no package at " + packagePath + "."));
			} else {
				fileName = source.getFileName();
				contents = source.read();
			}
		} catch (IOException | ResourceException | RuntimeException ex) {
			findings.add(Finding.error("package.unreadable",
					"The package could not be read as a ZIP file: " + describe(ex)));
			contents = null;
		}

		PackageManifest manifest = null;
		if (contents != null && contents.hasManifest()) {
			try {
				manifest = PackageManifest.parse(contents.getManifestText());
			} catch (IOException ex) {
				// Reported by the manifest check as manifest.invalid.
				manifest = null;
			}
		}

		PackageRecords records = new PackageRecords(fSession);
		InstalledPackage installed = null;
		if (manifest != null && manifest.getId() != null) {
			installed = records.get(manifest.getId());
		}
		String platformVersion = SeedDeployer.readPlatformVersion();

		if (contents != null) {
			InspectionContext context = new InspectionContext(fSession, source, contents, manifest, records, installed,
					platformVersion);
			findings.addAll(PackageChecks.run(fChecks, context));
		}

		List<String> removedPaths = new ArrayList<>();
		if (installed != null && contents != null) {
			Set<String> incoming = new HashSet<>(contents.getDeployPaths());
			for (String path : installed.getFiles().keySet()) {
				if (!incoming.contains(path)) {
					removedPaths.add(path);
				}
			}
		}

		return new PackageInspection(workspaceName, packagePath, fileName, manifest, contents, installed,
				action(manifest, installed), platformVersion, findings, removedPaths);
	}

	/**
	 * Installs the package at the given repository path.
	 *
	 * @param packagePath the package file in this workspace
	 * @param installedBy who is installing, recorded in the installation record
	 * @throws PackageInstallException when the inspection has an error finding
	 */
	public InstallResult install(String packagePath, String installedBy) throws IOException {
		long started = System.currentTimeMillis();
		ClusterLeaseStore.Lease lease = null;
		ClusterLeaseStore leases = Adaptables.getAdapter(fSession.adaptTo(javax.jcr.Session.class), ClusterLeaseStore.class);
		if (leases != null) {
			lease = leases.lock(LEASE_NAME, LEASE_TTL_MILLIS);
		}
		try {
			PackageInspection inspection = inspect(packagePath);
			if (!inspection.isOk()) {
				throw new PackageInstallException(inspection);
			}
			PackageManifest manifest = inspection.getManifest();
			PackageContents contents = inspection.getContents();
			InstalledPackage installed = inspection.getInstalled();
			PackageSource source = new PackageSource(fSession, packagePath);

			// 1. Provisioning: namespaces, principals, folders and their ACLs,
			//    so the files below land in an established structure. The
			//    provisioner commits as it goes and is idempotent.
			List<Map<String, Object>> descriptors = new ArrayList<>();
			for (Map.Entry<String, String> e : contents.getProvisioning().entrySet()) {
				Map<String, Object> document = Provisioner.load(
						new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)), e.getKey());
				if (document != null) {
					descriptors.add(document);
				}
			}
			if (!descriptors.isEmpty()) {
				try (Provisioner provisioner = new Provisioner(fSession)) {
					provisioner.provision(descriptors);
				}
			}

			// 2. Place every file, streaming it from the ZIP and digesting it on
			//    the way, so the record can tell later whether a file changed.
			Map<String, String> files = new LinkedHashMap<>();
			int[] counters = new int[2]; // created, updated
			source.walk((entry, in) -> {
				if (entry.isDirectory() || !entry.getName().startsWith(PackageSource.DEPLOY_PREFIX)
						|| entry.getName().length() <= PackageSource.DEPLOY_PREFIX.length()) {
					return;
				}
				String path = "/" + entry.getName().substring(PackageSource.DEPLOY_PREFIX.length());
				Resource resource = fSession.getResource(path);
				boolean existed = resource.exists();
				if (existed && resource.isCollection()) {
					throw new IOException("A folder is in the way of the file " + path + ".");
				}
				if (!existed) {
					resource.createFile();
				}
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				resource.write(new DigestInputStream(in, digest));
				files.put(path, hex(digest.digest()));
				counters[existed ? 1 : 0]++;
			});

			// 3. Remove what the previous version placed and this one does not.
			int removed = 0;
			if (installed != null) {
				Set<String> keptFolders = ancestors(files.keySet());
				for (String path : installed.getFiles().keySet()) {
					if (files.containsKey(path)) {
						continue;
					}
					Resource resource = fSession.getResource(path);
					if (!resource.exists() || resource.isCollection()) {
						continue;
					}
					Resource parent = resource.getParent();
					resource.remove();
					removed++;
					removeEmptyFolders(parent, keptFolders);
				}
			}

			// 4. Record, then commit everything at once.
			new PackageRecords(fSession).write(manifest, files, installedBy,
					installed != null ? installed.getVersion() : null, inspection.getFileName());
			fSession.commit();

			InstallResult result = new InstallResult(inspection.getAction(), manifest.getId(), manifest.getTitle(),
					manifest.getVersion(), installed != null ? installed.getVersion() : null, counters[0], counters[1],
					removed, descriptors.size(), System.currentTimeMillis() - started);
			CmsService.getLogger(getClass()).info("Package installed by '" + installedBy + "' in workspace '"
					+ fSession.getWorkspace().getName() + "': " + result);
			return result;
		} catch (Throwable ex) {
			try {
				fSession.rollback();
			} catch (Throwable ignore) {}
			if (ex instanceof IOException) {
				throw (IOException) ex;
			}
			throw Cause.create(ex).wrap(IOException.class);
		} finally {
			if (lease != null) {
				lease.close();
			}
		}
	}

	/**
	 * Removes a staged package file, and the folder it was staged in when that
	 * is now empty. Only files under {@link #STAGING_ROOT} may be discarded.
	 *
	 * @return false when there was nothing to remove
	 */
	public boolean discard(String packagePath) throws IOException {
		if (packagePath == null || !packagePath.startsWith(STAGING_ROOT + "/")) {
			throw new IOException("Only a package staged under " + STAGING_ROOT + " may be discarded: " + packagePath);
		}
		try {
			Resource resource = fSession.getResource(packagePath);
			if (!resource.exists()) {
				return false;
			}
			Resource parent = resource.getParent();
			resource.remove();
			if (!parent.getPath().equals(STAGING_ROOT) && parent.getPath().startsWith(STAGING_ROOT + "/")
					&& !hasNodes(parent)) {
				parent.remove();
			}
			fSession.commit();
			return true;
		} catch (ResourceException ex) {
			try {
				fSession.rollback();
			} catch (Throwable ignore) {}
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	// =========================================================================
	// Uninstallation
	// =========================================================================

	/**
	 * Tells what uninstalling the given packages from this workspace would do
	 * and what the {@link UninstallChecks} found. Nothing is written.
	 */
	public UninstallInspection inspectUninstall(List<String> ids) throws IOException {
		List<String> requested = normalizeIds(ids);
		PackageRecords records = new PackageRecords(fSession);
		List<InstalledPackage> installed = records.list();
		UninstallContext context = new UninstallContext(fSession, records, requested, installed);
		List<Finding> findings = UninstallChecks.run(UninstallChecks.defaults(), context);

		List<InstalledPackage> packages = new ArrayList<>(context.getSelected().values());
		Map<String, List<String>> requiredBy = new LinkedHashMap<>();
		for (InstalledPackage p : packages) {
			requiredBy.put(p.getId(), context.getRequiredBy(p.getId()));
		}
		return new UninstallInspection(fSession.getWorkspace().getName(), requested, packages, removalOrder(packages),
				requiredBy, findings);
	}

	/**
	 * Uninstalls the given packages together: for each, in dependents-first
	 * order, removes the files its record lists (a file the image has since
	 * taken over is left in place), the folders that leaves empty, and the
	 * record; then commits all of that at once. Provisioned identity, ACLs
	 * and namespaces are left as they are, and so is anything the package
	 * did not place itself.
	 *
	 * @throws IOException when the inspection has an error finding, naming
	 *                     the errors
	 */
	public UninstallResult uninstall(List<String> ids, String uninstalledBy) throws IOException {
		long started = System.currentTimeMillis();
		ClusterLeaseStore.Lease lease = null;
		ClusterLeaseStore leases = Adaptables.getAdapter(fSession.adaptTo(javax.jcr.Session.class), ClusterLeaseStore.class);
		if (leases != null) {
			lease = leases.lock(LEASE_NAME, LEASE_TTL_MILLIS);
		}
		try {
			UninstallInspection inspection = inspectUninstall(ids);
			if (!inspection.isOk()) {
				StringBuilder sb = new StringBuilder("The packages cannot be uninstalled:");
				for (Finding f : inspection.getErrors()) {
					sb.append(' ').append(f.getMessage());
				}
				throw new IOException(sb.toString());
			}

			PackageRecords records = new PackageRecords(fSession);
			Set<String> seedPaths = PackageRecords.readSeedPaths(fSession);
			Map<String, InstalledPackage> selected = new LinkedHashMap<>();
			for (InstalledPackage p : inspection.getPackages()) {
				selected.put(p.getId(), p);
			}
			// Folders that still lead to a file of a package that stays.
			Set<String> others = new HashSet<>();
			for (InstalledPackage p : records.list()) {
				if (!selected.containsKey(p.getId())) {
					others.addAll(p.getFiles().keySet());
				}
			}
			Set<String> keptFolders = ancestors(others);

			List<UninstallResult.Entry> entries = new ArrayList<>();
			for (String id : inspection.getOrder()) {
				InstalledPackage p = selected.get(id);
				int removed = 0;
				int missing = 0;
				int kept = 0;
				for (String path : p.getFiles().keySet()) {
					if (seedPaths.contains(path)) {
						kept++;
						continue;
					}
					Resource resource = fSession.getResource(path);
					if (!resource.exists() || resource.isCollection()) {
						missing++;
						continue;
					}
					Resource parent = resource.getParent();
					resource.remove();
					removed++;
					removeEmptyFolders(parent, keptFolders);
				}
				records.remove(id);
				entries.add(new UninstallResult.Entry(id, p.getTitle(), p.getVersion(), removed, missing, kept));
			}
			fSession.commit();

			UninstallResult result = new UninstallResult(entries, System.currentTimeMillis() - started);
			CmsService.getLogger(getClass()).info("Packages uninstalled by '" + uninstalledBy + "' in workspace '"
					+ fSession.getWorkspace().getName() + "': " + result);
			return result;
		} catch (Throwable ex) {
			try {
				fSession.rollback();
			} catch (Throwable ignore) {}
			if (ex instanceof IOException) {
				throw (IOException) ex;
			}
			throw Cause.create(ex).wrap(IOException.class);
		} finally {
			if (lease != null) {
				lease.close();
			}
		}
	}

	/**
	 * The order packages are removed in: a package before the packages it
	 * requires, so that no record ever names a dependency that is already
	 * gone. Packages that require each other are removed in request order.
	 */
	static List<String> removalOrder(List<InstalledPackage> packages) {
		Map<String, InstalledPackage> remaining = new LinkedHashMap<>();
		for (InstalledPackage p : packages) {
			remaining.put(p.getId(), p);
		}
		List<String> order = new ArrayList<>();
		while (!remaining.isEmpty()) {
			List<String> free = new ArrayList<>();
			for (String id : remaining.keySet()) {
				boolean requiredByRemaining = false;
				for (InstalledPackage other : remaining.values()) {
					if (!other.getId().equals(id) && other.getRequiredPackages().containsKey(id)) {
						requiredByRemaining = true;
						break;
					}
				}
				if (!requiredByRemaining) {
					free.add(id);
				}
			}
			if (free.isEmpty()) {
				// A cycle: take what is left as it came.
				free.addAll(remaining.keySet());
			}
			for (String id : free) {
				order.add(id);
				remaining.remove(id);
			}
		}
		return order;
	}

	private static List<String> normalizeIds(List<String> ids) {
		List<String> result = new ArrayList<>();
		if (ids == null) {
			return result;
		}
		for (String id : ids) {
			if (id == null) {
				continue;
			}
			String s = id.trim();
			if (!s.isEmpty() && !result.contains(s)) {
				result.add(s);
			}
		}
		return result;
	}

	private static String action(PackageManifest manifest, InstalledPackage installed) {
		if (manifest == null || manifest.getId() == null) {
			return null;
		}
		Semver incoming = Semver.tryParse(manifest.getVersion());
		if (incoming == null) {
			return null;
		}
		if (installed == null) {
			return PackageInspection.ACTION_INSTALL;
		}
		Semver current = Semver.tryParse(installed.getVersion());
		if (current == null || incoming.compareTo(current) > 0) {
			return PackageInspection.ACTION_UPGRADE;
		}
		if (incoming.compareTo(current) == 0) {
			return PackageInspection.ACTION_REINSTALL;
		}
		// A downgrade: the version check refuses it.
		return null;
	}

	/**
	 * Removes the folders a removed file leaves empty, walking up until a
	 * folder still holds something or still leads to a file of the package.
	 */
	private void removeEmptyFolders(Resource folder, Set<String> keptFolders) throws ResourceException {
		while (!folder.isRoot() && !keptFolders.contains(folder.getPath()) && !hasNodes(folder)) {
			Resource parent = folder.getParent();
			folder.remove();
			folder = parent;
		}
	}

	private boolean hasNodes(Resource folder) throws ResourceException {
		try {
			return fSession.adaptTo(javax.jcr.Session.class).getNode(folder.getPath()).hasNodes();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	private static Set<String> ancestors(Set<String> paths) {
		Set<String> ancestors = new HashSet<>();
		for (String path : paths) {
			for (int i = path.lastIndexOf('/'); i > 0; i = path.lastIndexOf('/', i - 1)) {
				if (!ancestors.add(path.substring(0, i))) {
					break;
				}
			}
		}
		return ancestors;
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	private static String describe(Throwable ex) {
		return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
	}

}

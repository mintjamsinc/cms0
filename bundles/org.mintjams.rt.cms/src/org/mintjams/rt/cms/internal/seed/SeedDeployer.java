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

package org.mintjams.rt.cms.internal.seed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Session;
import org.mintjams.tools.lang.Strings;

/**
 * Applies the bundled assets of the image seed to a workspace.
 *
 * <p>The seed ships inside the container image and is only read. Its
 * {@code MANIFEST}, generated when the image is built, lists every file under
 * {@code assets/} with its SHA-256 digest. The {@code system} workspace
 * receives {@code assets/system}, every other workspace
 * {@code assets/workspace}; each holds a {@code deploy} folder mirrored into
 * the repository and a {@code provisioning} folder of descriptors.
 *
 * <p>The manifest applied last is recorded in the workspace itself
 * ({@value #APPLIED_MANIFEST_PATH}). Comparing the seed's manifest with that
 * record decides what happens to each file: a file that is not recorded is
 * created, a file whose digest changed is overwritten, and a recorded file the
 * seed no longer ships is removed. A file whose digest is unchanged is left
 * alone, even when it was edited in the repository. Because the record lives
 * in the repository, every node of a cluster shares it, and running an older
 * image brings the assets back to that image's state.
 */
public class SeedDeployer {

	public static final String SEED_PATH_PROPERTY = "mintjams.cms.seed.path";
	public static final String SEED_PATH_ENV_VARIABLE = "MINTJAMS_CMS_SEED_PATH";

	public static final String APPLIED_MANIFEST_PATH = "/var/seed/MANIFEST";
	public static final String APPLIED_VERSION_PATH = "/var/seed/VERSION";

	private static final String SYSTEM_WORKSPACE_NAME = "system";
	private static final int DIGEST_LENGTH = 64;
	private static final String DIGEST_SEPARATOR = "  ";

	private final Session fSession;
	private final Path fSeedPath;
	private final String fAssetsName;

	private SeedDeployer(Session session, Path seedPath) {
		fSession = session;
		fSeedPath = seedPath;
		fAssetsName = SYSTEM_WORKSPACE_NAME.equals(session.getWorkspace().getName()) ? "system" : "workspace";
	}

	/**
	 * Returns a deployer for the workspace of the given session, or
	 * {@code null} when no seed is configured: an installation outside the
	 * container image deploys from the workspace directory only.
	 */
	public static SeedDeployer create(Session session) {
		String value = CmsService.getDefault().getBundleContext().getProperty(SEED_PATH_PROPERTY);
		if (Strings.isEmpty(value)) {
			value = System.getenv(SEED_PATH_ENV_VARIABLE);
		}
		if (Strings.isEmpty(value)) {
			return null;
		}

		return new SeedDeployer(session, Path.of(value).toAbsolutePath());
	}

	public Path getProvisioningPath() {
		return getAssetsPath().resolve("provisioning");
	}

	/**
	 * Brings the bundled assets of the workspace in line with the seed.
	 *
	 * @return the repository paths of the files the seed owns; the workspace's
	 *         own deploy folder must not write to them
	 */
	public Set<String> deploy() throws ResourceException, IOException {
		long started = System.currentTimeMillis();
		String version = readSeedVersion();
		Map<String, String> assets = readSeedManifest();
		Map<String, String> applied = readAppliedManifest();
		Path deployPath = getAssetsPath().resolve("deploy");

		int created = 0;
		int updated = 0;
		int unchanged = 0;
		for (Map.Entry<String, String> e : assets.entrySet()) {
			String path = e.getKey();
			Resource resource = fSession.getResource(path);
			if (!resource.exists()) {
				resource.createFile();
				resource.write(deployPath.resolve(path.substring(1)));
				fSession.commit();
				created++;
			} else if (!e.getValue().equals(applied.get(path))) {
				resource.write(deployPath.resolve(path.substring(1)));
				fSession.commit();
				updated++;
			} else {
				unchanged++;
			}
		}

		Set<String> keptFolders = getAncestors(assets.keySet());
		int removed = 0;
		for (String path : applied.keySet()) {
			if (assets.containsKey(path)) {
				continue;
			}

			Resource resource = fSession.getResource(path);
			if (!resource.exists() || resource.isCollection()) {
				continue;
			}

			Resource parent = resource.getParent();
			resource.remove();
			fSession.commit();
			removeEmptyFolders(parent, keptFolders);
			removed++;
		}

		writeAppliedRecord(assets, version);

		CmsService.getLogger(getClass()).info("Bundled asset deployment finished (seed " + version + "): "
				+ created + " created, " + updated + " updated, " + removed + " removed, " + unchanged + " unchanged ("
				+ ((System.currentTimeMillis() - started) / 1000) + " seconds).");
		return Collections.unmodifiableSet(assets.keySet());
	}

	private Path getAssetsPath() {
		return fSeedPath.resolve("assets").resolve(fAssetsName);
	}

	private String readSeedVersion() throws IOException {
		Path versionPath = fSeedPath.resolve("VERSION");
		if (!Files.isRegularFile(versionPath)) {
			return "unknown";
		}

		return Files.readString(versionPath, StandardCharsets.UTF_8).trim();
	}

	/**
	 * Reads the entries of the seed's manifest that belong to this workspace,
	 * keyed by repository path.
	 */
	private Map<String, String> readSeedManifest() throws IOException {
		Path manifestPath = fSeedPath.resolve("MANIFEST");
		if (!Files.isRegularFile(manifestPath)) {
			throw new IOException("The seed has no MANIFEST: " + manifestPath);
		}

		String prefix = "assets/" + fAssetsName + "/deploy/";
		Map<String, String> assets = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : parseManifest(Files.readString(manifestPath, StandardCharsets.UTF_8), manifestPath.toString()).entrySet()) {
			if (e.getKey().startsWith(prefix)) {
				assets.put("/" + e.getKey().substring(prefix.length()), e.getValue());
			}
		}
		return assets;
	}

	private Map<String, String> readAppliedManifest() throws ResourceException, IOException {
		Resource resource = fSession.getResource(APPLIED_MANIFEST_PATH);
		if (!resource.exists()) {
			return Collections.emptyMap();
		}

		return parseManifest(resource.getContent(), APPLIED_MANIFEST_PATH);
	}

	/**
	 * Parses lines in the format written by {@code sha256sum}: the hexadecimal
	 * digest, a space, a space or {@code *} for the read mode, and the path.
	 */
	private static Map<String, String> parseManifest(String text, String source) throws IOException {
		Map<String, String> entries = new LinkedHashMap<>();
		for (String line : text.split("\n")) {
			if (line.isEmpty()) {
				continue;
			}

			if (line.length() <= DIGEST_LENGTH + DIGEST_SEPARATOR.length()
					|| line.charAt(DIGEST_LENGTH) != ' '
					|| (line.charAt(DIGEST_LENGTH + 1) != ' ' && line.charAt(DIGEST_LENGTH + 1) != '*')) {
				throw new IOException("Invalid manifest line in " + source + ": " + line);
			}
			entries.put(line.substring(DIGEST_LENGTH + DIGEST_SEPARATOR.length()), line.substring(0, DIGEST_LENGTH));
		}
		return entries;
	}

	private void writeAppliedRecord(Map<String, String> assets, String version) throws ResourceException {
		StringBuilder manifest = new StringBuilder();
		for (Map.Entry<String, String> e : assets.entrySet()) {
			manifest.append(e.getValue()).append(DIGEST_SEPARATOR).append(e.getKey()).append("\n");
		}

		boolean manifestChanged = writeIfChanged(APPLIED_MANIFEST_PATH, manifest.toString());
		boolean versionChanged = writeIfChanged(APPLIED_VERSION_PATH, version + "\n");
		if (manifestChanged || versionChanged) {
			fSession.commit();
		}
	}

	private boolean writeIfChanged(String path, String content) throws ResourceException {
		Resource resource = fSession.getResource(path);
		if (!resource.exists()) {
			resource.createFile();
		} else if (content.equals(resource.getContent())) {
			return false;
		}

		resource.write(content);
		return true;
	}

	/**
	 * Removes the folders a removed file leaves empty, walking up until a
	 * folder still holds something or still leads to a bundled asset.
	 */
	private void removeEmptyFolders(Resource folder, Set<String> keptFolders) throws ResourceException {
		javax.jcr.Session jcrSession = fSession.adaptTo(javax.jcr.Session.class);
		while (!folder.isRoot() && !keptFolders.contains(folder.getPath())) {
			try {
				if (jcrSession.getNode(folder.getPath()).hasNodes()) {
					return;
				}
			} catch (Throwable ex) {
				throw ResourceException.wrap(ex);
			}

			Resource parent = folder.getParent();
			folder.remove();
			fSession.commit();
			folder = parent;
		}
	}

	private static Set<String> getAncestors(Set<String> paths) {
		Set<String> ancestors = new HashSet<>();
		for (String path : paths) {
			for (int i = path.lastIndexOf('/'); i > 0; i = path.lastIndexOf('/', i - 1)) {
				// A known ancestor implies all of its own ancestors are known.
				if (!ancestors.add(path.substring(0, i))) {
					break;
				}
			}
		}
		return ancestors;
	}

}

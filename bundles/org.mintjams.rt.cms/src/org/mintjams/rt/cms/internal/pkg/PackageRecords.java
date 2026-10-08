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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Session;
import org.mintjams.tools.lang.Cause;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

/**
 * The installation records of a workspace, one folder per package under
 * {@value #ROOT}:
 *
 * <pre>
 * /etc/packages/&lt;id&gt;/package.yml     the manifest as it was in the package
 * /etc/packages/&lt;id&gt;/MANIFEST        every file placed, as "sha256  /path" lines
 * /etc/packages/&lt;id&gt;/install.yml     version, when, by whom, what it replaced
 * </pre>
 *
 * <p>The MANIFEST uses the {@code sha256sum} line format the image seed also
 * records, so the two can be read by the same code. The record is what makes
 * an upgrade exact: the files of the previous version that the new package no
 * longer ships are known, and removed.</p>
 *
 * <p>Writes go through the session they are given and are not committed here;
 * the installer commits them together with the files they describe.</p>
 */
public final class PackageRecords {

	public static final String ROOT = "/etc/packages";
	public static final String MANIFEST_FILE = "package.yml";
	public static final String FILES_FILE = "MANIFEST";
	public static final String INSTALL_FILE = "install.yml";

	private static final int DIGEST_LENGTH = 64;
	private static final String DIGEST_SEPARATOR = "  ";

	private final Session fSession;

	public PackageRecords(Session session) {
		fSession = session;
	}

	public static String folderPath(String id) {
		return ROOT + "/" + id;
	}

	/** The record of the package with this id, or {@code null} when it is not installed. */
	public InstalledPackage get(String id) throws IOException {
		if (id == null || id.isEmpty() || !PackageManifest.ID_PATTERN.matcher(id).matches()) {
			return null;
		}
		try {
			Resource folder = fSession.getResource(folderPath(id));
			if (!folder.exists() || !folder.isCollection()) {
				return null;
			}
			return read(folder);
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	/** Every installed package, in folder order. */
	public List<InstalledPackage> list() throws IOException {
		List<InstalledPackage> result = new ArrayList<>();
		try {
			Resource root = fSession.getResource(ROOT);
			if (!root.exists() || !root.isCollection()) {
				return result;
			}
			for (Resource.ResourceIterator i = root.list(); i.hasNext();) {
				Resource folder = i.next();
				if (!folder.isCollection()) {
					continue;
				}
				InstalledPackage p = read(folder);
				if (p != null) {
					result.add(p);
				}
			}
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
		result.sort((a, b) -> a.getId().compareTo(b.getId()));
		return result;
	}

	/**
	 * Writes (or replaces) the record of an installation. Nothing is committed.
	 */
	public void write(PackageManifest manifest, Map<String, String> files, String installedBy, String previousVersion,
			String sourceFileName) throws IOException {
		try {
			Resource folder = fSession.getResource(folderPath(manifest.getId()));
			if (!folder.exists()) {
				folder.createFolder();
			}
			writeFile(folder, MANIFEST_FILE, manifest.getText());
			writeFile(folder, FILES_FILE, formatManifest(files));

			Map<String, Object> install = new LinkedHashMap<>();
			install.put("id", manifest.getId());
			install.put("version", manifest.getVersion());
			install.put("title", manifest.getTitle());
			install.put("installedAt", Instant.now().toString());
			install.put("installedBy", installedBy);
			if (previousVersion != null) {
				install.put("previousVersion", previousVersion);
			}
			if (sourceFileName != null) {
				install.put("source", sourceFileName);
			}
			writeFile(folder, INSTALL_FILE, new Dump(DumpSettings.builder()
					.setDefaultFlowStyle(FlowStyle.BLOCK)
					.build()).dumpToString(install));
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private static void writeFile(Resource folder, String name, String content) throws ResourceException {
		Resource file = folder.getResource(name);
		if (!file.exists()) {
			file.createFile();
		}
		file.write(content == null ? "" : content);
	}

	@SuppressWarnings("unchecked")
	private InstalledPackage read(Resource folder) throws IOException {
		try {
			String id = folder.getName();
			Resource installFile = folder.getResource(INSTALL_FILE);
			if (!installFile.exists()) {
				// A folder without a record is not an installation; leave it alone.
				return null;
			}
			Object document = new Load(LoadSettings.builder().build()).loadFromString(installFile.getContent());
			Map<String, Object> install = (document instanceof Map) ? (Map<String, Object>) document : Collections.emptyMap();

			Map<String, String> files = Collections.emptyMap();
			Resource filesFile = folder.getResource(FILES_FILE);
			if (filesFile.exists()) {
				files = parseManifest(filesFile.getContent(), filesFile.getPath());
			}

			String manifestText = null;
			Resource manifestFile = folder.getResource(MANIFEST_FILE);
			if (manifestFile.exists()) {
				manifestText = manifestFile.getContent();
			}

			return new InstalledPackage(id,
					string(install.get("version")),
					string(install.get("title")),
					string(install.get("installedAt")),
					string(install.get("installedBy")),
					string(install.get("previousVersion")),
					string(install.get("source")),
					manifestText, files);
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	/**
	 * Removes the record of a package. Nothing is committed.
	 *
	 * @return false when there was no record
	 */
	public boolean remove(String id) throws IOException {
		try {
			Resource folder = fSession.getResource(folderPath(id));
			if (!folder.exists()) {
				return false;
			}
			folder.remove();
			return true;
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	/**
	 * For every installed package, the ids of the installed packages that
	 * require it, from their recorded manifests.
	 */
	public static Map<String, List<String>> requiredBy(List<InstalledPackage> installed) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (InstalledPackage p : installed) {
			result.put(p.getId(), new ArrayList<>());
		}
		for (InstalledPackage p : installed) {
			for (String required : p.getRequiredPackages().keySet()) {
				List<String> dependents = result.get(required);
				if (dependents != null && !dependents.contains(p.getId())) {
					dependents.add(p.getId());
				}
			}
		}
		return result;
	}

	/**
	 * The repository paths the bundled assets of the image own in the
	 * workspace of the session: the applied seed manifest, or nothing outside
	 * the container image.
	 */
	public static java.util.Set<String> readSeedPaths(Session session) throws IOException {
		try {
			Resource resource = session.getResource(org.mintjams.rt.cms.internal.seed.SeedDeployer.APPLIED_MANIFEST_PATH);
			if (!resource.exists()) {
				return Collections.emptySet();
			}
			return parseManifest(resource.getContent(), org.mintjams.rt.cms.internal.seed.SeedDeployer.APPLIED_MANIFEST_PATH).keySet();
		} catch (ResourceException ex) {
			throw new IOException("Could not read the applied seed manifest.", ex);
		}
	}

	/**
	 * Parses lines in the format written by {@code sha256sum}: the hexadecimal
	 * digest, two spaces (or a space and {@code *}), and the path.
	 */
	public static Map<String, String> parseManifest(String text, String source) throws IOException {
		Map<String, String> entries = new LinkedHashMap<>();
		if (text == null) {
			return entries;
		}
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

	public static String formatManifest(Map<String, String> files) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : files.entrySet()) {
			sb.append(e.getValue()).append(DIGEST_SEPARATOR).append(e.getKey()).append('\n');
		}
		return sb.toString();
	}

	private static String string(Object value) {
		return value == null ? null : value.toString();
	}

}

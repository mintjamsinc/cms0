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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The installation record of one package in a workspace, as
 * {@link PackageRecords} keeps it under {@code /etc/packages/<id>/}.
 */
public final class InstalledPackage {

	private final String fId;
	private final String fVersion;
	private final String fTitle;
	private final String fInstalledAt;
	private final String fInstalledBy;
	private final String fPreviousVersion;
	private final String fSourceFileName;
	private final String fManifestText;
	private final Map<String, String> fFiles;
	private final String fImagesPath;
	private PackageManifest fManifest;
	private boolean fManifestParsed;

	InstalledPackage(String id, String version, String title, String installedAt, String installedBy,
			String previousVersion, String sourceFileName, String manifestText, Map<String, String> files,
			String imagesPath) {
		fId = id;
		fVersion = version;
		fTitle = title;
		fInstalledAt = installedAt;
		fInstalledBy = installedBy;
		fPreviousVersion = previousVersion;
		fSourceFileName = sourceFileName;
		fManifestText = manifestText;
		fFiles = Collections.unmodifiableMap(new LinkedHashMap<>(files));
		fImagesPath = imagesPath;
	}

	public String getId() {
		return fId;
	}

	public String getVersion() {
		return fVersion;
	}

	public String getTitle() {
		return fTitle;
	}

	/** ISO-8601 instant of the installation. */
	public String getInstalledAt() {
		return fInstalledAt;
	}

	public String getInstalledBy() {
		return fInstalledBy;
	}

	/** The version replaced by this installation, or {@code null} for a first installation. */
	public String getPreviousVersion() {
		return fPreviousVersion;
	}

	/** The file name of the package that was installed. */
	public String getSourceFileName() {
		return fSourceFileName;
	}

	/** The manifest as it was in the package, or {@code null} when the record has none. */
	public String getManifestText() {
		return fManifestText;
	}

	/**
	 * The recorded manifest, parsed; {@code null} when the record has none or
	 * it cannot be read.
	 */
	public PackageManifest getManifest() {
		if (!fManifestParsed) {
			fManifestParsed = true;
			if (fManifestText != null) {
				try {
					fManifest = PackageManifest.parse(fManifestText);
				} catch (IOException | RuntimeException ex) {
					fManifest = null;
				}
			}
		}
		return fManifest;
	}

	/** The repository paths this installation placed, with their SHA-256 digests. */
	public Map<String, String> getFiles() {
		return fFiles;
	}

	/**
	 * The folder of the record that holds the package's images
	 * ({@code /etc/packages/<id>/images}), or {@code null} when the package
	 * shipped none. An image the manifest names as {@code images/x.png} is the
	 * file {@code x.png} under it.
	 */
	public String getImagesPath() {
		return fImagesPath;
	}

	/**
	 * The packages this package requires, as id to constraint, read from the
	 * recorded manifest; empty when the record has no manifest or it declares
	 * none.
	 */
	public Map<String, String> getRequiredPackages() {
		PackageManifest manifest = getManifest();
		return manifest != null ? manifest.getRequiredPackages() : Collections.emptyMap();
	}

	/** The record as plain data, with the texts and images of the top level. */
	public Map<String, Object> toMap() {
		return toMap(null);
	}

	/**
	 * The record as plain data. With a locale, {@code title},
	 * {@code description} and {@code images} are those a reader of that
	 * locale sees ({@link PackageManifest#localized(String)}); the whole
	 * manifest, locale blocks included, is under {@code manifest}. Image
	 * values are repository paths under {@link #getImagesPath()}.
	 */
	public Map<String, Object> toMap(String locale) {
		PackageManifest manifest = getManifest();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", fId);
		m.put("version", fVersion);
		m.put("title", fTitle);
		m.put("installedAt", fInstalledAt);
		m.put("installedBy", fInstalledBy);
		m.put("previousVersion", fPreviousVersion);
		m.put("sourceFileName", fSourceFileName);
		m.put("fileCount", fFiles.size());
		List<Map<String, Object>> requires = new ArrayList<>();
		for (Map.Entry<String, String> e : getRequiredPackages().entrySet()) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("id", e.getKey());
			r.put("constraint", e.getValue());
			requires.add(r);
		}
		m.put("requires", requires);

		String description = null;
		Map<String, Object> vendor = new LinkedHashMap<>();
		vendor.put("name", null);
		vendor.put("url", null);
		Map<String, Object> images = Collections.emptyMap();
		if (manifest != null) {
			Map<String, Object> localized = manifest.localized(locale);
			String title = (String) localized.get("title");
			if (title != null) {
				m.put("title", title);
			}
			description = (String) localized.get("description");
			vendor.put("name", manifest.getVendorName());
			vendor.put("url", manifest.getVendorUrl());
			m.put("homepage", manifest.getHomepage());
			m.put("license", manifest.getLicense());
			m.put("category", manifest.getCategory());
			m.put("tags", manifest.getTags());
			images = PackageManifest.map(localized.get("images"));
		} else {
			m.put("homepage", null);
			m.put("license", null);
			m.put("category", null);
			m.put("tags", new ArrayList<String>());
		}
		m.put("description", description);
		m.put("vendor", vendor);
		m.put("images", imagePaths(images));
		m.put("imagesPath", fImagesPath);
		m.put("manifest", manifest != null ? manifest.toMap() : null);
		return m;
	}

	/** Maps the entry names of the roles to their paths in the record folder. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> imagePaths(Map<String, Object> images) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (String role : PackageManifest.SINGLE_IMAGE_ROLES) {
			m.put(role, imagePath((String) images.get(role)));
		}
		List<Map<String, Object>> shots = new ArrayList<>();
		Object screenshots = images.get(PackageManifest.IMAGE_SCREENSHOTS);
		if (screenshots instanceof List) {
			for (Map<String, Object> shot : (List<Map<String, Object>>) screenshots) {
				String path = imagePath((String) shot.get("path"));
				if (path == null) {
					continue;
				}
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("path", path);
				s.put("caption", shot.get("caption"));
				shots.add(s);
			}
		}
		m.put(PackageManifest.IMAGE_SCREENSHOTS, shots);
		return m;
	}

	private String imagePath(String entry) {
		if (entry == null || fImagesPath == null || !entry.startsWith(PackageSource.IMAGES_PREFIX)) {
			return null;
		}
		return fImagesPath + "/" + entry.substring(PackageSource.IMAGES_PREFIX.length());
	}

}

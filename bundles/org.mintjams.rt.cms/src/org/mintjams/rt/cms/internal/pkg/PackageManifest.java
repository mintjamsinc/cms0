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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * The {@code package.yml} at the root of a package.
 *
 * <pre>
 * format: cms-package
 * formatVersion: 1
 * id: jp.example.blog           # reverse-DNS, lower case
 * version: 1.2.0                # semantic version
 * title: Example Blog
 * description: Posts and pages for the public site.
 * vendor:
 *   name: Example Inc.
 *   url: https://www.example.jp/
 * requires:
 *   platform: ">=0.1.30"        # the running cms0, see Semver constraints
 *   packages:
 *     jp.example.commons: ">=1.0"
 * </pre>
 *
 * <p>Parsing is lenient: every value is taken as it is, and
 * {@link org.mintjams.rt.cms.internal.pkg.checks.ManifestCheck} reports what is
 * missing or malformed, so one inspection can list every problem at once.
 */
public final class PackageManifest {

	public static final String FORMAT = "cms-package";
	public static final int FORMAT_VERSION = 1;

	/** Package ids are reverse-DNS names in lower case: letters, digits, dots and dashes. */
	public static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9]+([.-][a-z0-9]+)*$");
	public static final int ID_MAX_LENGTH = 128;

	private final Map<String, Object> fDocument;
	private final String fText;

	private PackageManifest(Map<String, Object> document, String text) {
		fDocument = document;
		fText = text;
	}

	/**
	 * Parses the manifest text. Throws {@link IOException} when the text is not
	 * a YAML mapping.
	 */
	@SuppressWarnings("unchecked")
	public static PackageManifest parse(String text) throws IOException {
		Object document;
		try {
			document = new Load(LoadSettings.builder().build()).loadFromString(text == null ? "" : text);
		} catch (RuntimeException ex) {
			throw new IOException("The manifest is not valid YAML: " + ex.getMessage(), ex);
		}
		if (document == null) {
			throw new IOException("The manifest is empty.");
		}
		if (!(document instanceof Map)) {
			throw new IOException("The manifest must be a mapping.");
		}
		return new PackageManifest((Map<String, Object>) document, text);
	}

	/** The manifest as written, kept verbatim in the installation record. */
	public String getText() {
		return fText;
	}

	public Map<String, Object> getDocument() {
		return Collections.unmodifiableMap(fDocument);
	}

	public String getFormat() {
		return string(fDocument.get("format"));
	}

	/** The declared format version, or {@code -1} when absent or not a number. */
	public int getFormatVersion() {
		Object value = fDocument.get("formatVersion");
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		if (value instanceof String) {
			try {
				return Integer.parseInt(((String) value).trim());
			} catch (NumberFormatException ignore) {}
		}
		return -1;
	}

	public String getId() {
		return string(fDocument.get("id"));
	}

	public String getVersion() {
		return string(fDocument.get("version"));
	}

	public String getTitle() {
		return string(fDocument.get("title"));
	}

	public String getDescription() {
		return string(fDocument.get("description"));
	}

	public String getVendorName() {
		return string(map(fDocument.get("vendor")).get("name"));
	}

	public String getVendorUrl() {
		return string(map(fDocument.get("vendor")).get("url"));
	}

	/** The platform constraint, or {@code null} when the package declares none. */
	public String getRequiredPlatform() {
		return string(map(fDocument.get("requires")).get("platform"));
	}

	/** Required packages as id to constraint; empty when none are declared. */
	public Map<String, String> getRequiredPackages() {
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : map(map(fDocument.get("requires")).get("packages")).entrySet()) {
			result.put(e.getKey(), string(e.getValue()));
		}
		return result;
	}

	/** The raw {@code requires} value, for the checks to validate its shape. */
	public Object getRequires() {
		return fDocument.get("requires");
	}

	/** The raw {@code vendor} value, for the checks to validate its shape. */
	public Object getVendor() {
		return fDocument.get("vendor");
	}

	/** The id, version and texts as plain data for scripts and forms. */
	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", getId());
		m.put("version", getVersion());
		m.put("title", getTitle());
		m.put("description", getDescription());
		Map<String, Object> vendor = new LinkedHashMap<>();
		vendor.put("name", getVendorName());
		vendor.put("url", getVendorUrl());
		m.put("vendor", vendor);
		Map<String, Object> requires = new LinkedHashMap<>();
		requires.put("platform", getRequiredPlatform());
		requires.put("packages", new LinkedHashMap<>(getRequiredPackages()));
		m.put("requires", requires);
		return m;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		if (value instanceof Map) {
			return (Map<String, Object>) value;
		}
		return Collections.emptyMap();
	}

	private static String string(Object value) {
		if (value == null) {
			return null;
		}
		String s = value.toString().trim();
		return s.isEmpty() ? null : s;
	}

}

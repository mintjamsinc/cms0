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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * homepage: https://www.example.jp/blog/
 * license: MIT
 * category: site                # one slug
 * tags: [blog, pages]           # slugs
 * images:                       # entries under images/ of the package
 *   icon: images/icon.svg
 *   thumbnail: images/thumbnail.png
 *   main: images/main.png
 *   screenshots:
 *     - path: images/editor.png
 *       caption: Writing a post
 *     - images/list.png
 * locales:                      # per language tag: title, description, images
 *   ja:
 *     title: サンプルブログ
 *     description: 公開サイトの投稿とページ。
 *     images:
 *       main: images/main.ja.png
 * requires:
 *   platform: ">=0.1.30"        # the running cms0, see Semver constraints
 *   packages:
 *     jp.example.commons: ">=1.0"
 * </pre>
 *
 * <p>Parsing is lenient: every value is taken as it is, and
 * {@link org.mintjams.rt.cms.internal.pkg.checks.ManifestCheck} and
 * {@link org.mintjams.rt.cms.internal.pkg.checks.ImagesCheck} report what is
 * missing or malformed, so one inspection can list every problem at once.
 *
 * <p>The texts and images a reader sees come from {@link #localized(String)}:
 * the block of {@code locales} whose tag matches the reader's locale (the
 * tag as a whole, then with its last subtags dropped: {@code ja-JP} falls
 * back to {@code ja}) overrides the top-level values; what the block does not
 * give stays as the top level has it. Images override per role, and the
 * screenshots as a whole list.
 */
public final class PackageManifest {

	public static final String FORMAT = "cms-package";
	public static final int FORMAT_VERSION = 1;

	/** Package ids are reverse-DNS names in lower case: letters, digits, dots and dashes. */
	public static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9]+([.-][a-z0-9]+)*$");
	public static final int ID_MAX_LENGTH = 128;

	/** Categories and tags are slugs: lower-case letters, digits and single dashes. */
	public static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
	public static final int SLUG_MAX_LENGTH = 32;
	public static final int TAGS_MAX = 20;

	/** The keys of {@code locales} are language tags (BCP 47 syntax, loosely). */
	public static final Pattern LOCALE_PATTERN = Pattern.compile("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$");

	/** The image roles a manifest assigns, in the order they are shown. */
	public static final String IMAGE_ICON = "icon";
	public static final String IMAGE_THUMBNAIL = "thumbnail";
	public static final String IMAGE_MAIN = "main";
	public static final String IMAGE_SCREENSHOTS = "screenshots";
	public static final List<String> SINGLE_IMAGE_ROLES = List.of(IMAGE_ICON, IMAGE_THUMBNAIL, IMAGE_MAIN);

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

	/** The home page of the package, or {@code null}. */
	public String getHomepage() {
		return string(fDocument.get("homepage"));
	}

	/** The license, an SPDX identifier or a short text, or {@code null}. */
	public String getLicense() {
		return string(fDocument.get("license"));
	}

	/** The category slug, or {@code null}. */
	public String getCategory() {
		return string(fDocument.get("category"));
	}

	/** The tags as written (strings only; the check reports other shapes); empty when none. */
	public List<String> getTags() {
		List<String> tags = new ArrayList<>();
		Object value = fDocument.get("tags");
		if (value instanceof List) {
			for (Object o : (List<?>) value) {
				String s = string(o);
				if (s != null) {
					tags.add(s);
				}
			}
		} else if (value instanceof String) {
			String s = string(value);
			if (s != null) {
				tags.add(s);
			}
		}
		return tags;
	}

	/** The raw {@code tags} value, for the checks to validate its shape. */
	public Object getTagsValue() {
		return fDocument.get("tags");
	}

	/** The raw {@code images} value, for the checks to validate its shape. */
	public Object getImagesValue() {
		return fDocument.get("images");
	}

	/** The raw {@code locales} value, for the checks to validate its shape. */
	public Object getLocalesValue() {
		return fDocument.get("locales");
	}

	/**
	 * The images of the top level, normalized: {@code icon}, {@code thumbnail}
	 * and {@code main} as entry names, {@code screenshots} as a list of
	 * {@code path} / {@code caption} maps. Malformed parts are left out; the
	 * images check reports them.
	 */
	public Map<String, Object> getImages() {
		return normalizeImages(fDocument.get("images"));
	}

	/** The locale blocks as written, by their tag as written; empty when none. */
	public Map<String, Map<String, Object>> getLocales() {
		Map<String, Map<String, Object>> result = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : map(fDocument.get("locales")).entrySet()) {
			if (e.getValue() instanceof Map) {
				result.put(e.getKey(), map(e.getValue()));
			}
		}
		return result;
	}

	/**
	 * Every image entry the manifest names, at the top level and in every
	 * locale block, in order of first mention. These are the entries the
	 * installer keeps.
	 */
	public Set<String> getImageEntries() {
		Set<String> entries = new LinkedHashSet<>();
		collectImageEntries(getImages(), entries);
		for (Map<String, Object> block : getLocales().values()) {
			collectImageEntries(normalizeImages(block.get("images")), entries);
		}
		return entries;
	}

	/**
	 * The title, description and images a reader of the given locale sees
	 * (see the class comment). A {@code null} or empty locale gives the top
	 * level.
	 */
	public Map<String, Object> localized(String locale) {
		String title = getTitle();
		String description = getDescription();
		Map<String, Object> images = getImages();
		Map<String, Object> block = localeBlock(locale);
		if (block != null) {
			String t = string(block.get("title"));
			if (t != null) {
				title = t;
			}
			String d = string(block.get("description"));
			if (d != null) {
				description = d;
			}
			Map<String, Object> override = normalizeImages(block.get("images"));
			for (Map.Entry<String, Object> e : override.entrySet()) {
				images.put(e.getKey(), e.getValue());
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("title", title);
		m.put("description", description);
		m.put("images", images);
		return m;
	}

	/**
	 * The locale block for a reader of the given locale: the tag as a whole,
	 * then with its last subtags dropped, compared ignoring case. Null when
	 * none applies.
	 */
	public Map<String, Object> localeBlock(String locale) {
		if (locale == null || locale.trim().isEmpty()) {
			return null;
		}
		Map<String, Map<String, Object>> blocks = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, Object>> e : getLocales().entrySet()) {
			blocks.putIfAbsent(e.getKey().trim().toLowerCase().replace('_', '-'), e.getValue());
		}
		String tag = locale.trim().toLowerCase().replace('_', '-');
		while (!tag.isEmpty()) {
			Map<String, Object> block = blocks.get(tag);
			if (block != null) {
				return block;
			}
			int dash = tag.lastIndexOf('-');
			if (dash < 0) {
				break;
			}
			tag = tag.substring(0, dash);
		}
		return null;
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

	/**
	 * The id, version, texts, metadata, images (as entry names) and locale
	 * blocks as plain data for scripts and forms.
	 */
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
		m.put("homepage", getHomepage());
		m.put("license", getLicense());
		m.put("category", getCategory());
		m.put("tags", getTags());
		m.put("images", getImages());
		Map<String, Object> locales = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, Object>> e : getLocales().entrySet()) {
			Map<String, Object> block = new LinkedHashMap<>();
			block.put("title", string(e.getValue().get("title")));
			block.put("description", string(e.getValue().get("description")));
			if (e.getValue().get("images") != null) {
				block.put("images", normalizeImages(e.getValue().get("images")));
			}
			locales.put(e.getKey(), block);
		}
		m.put("locales", locales);
		Map<String, Object> requires = new LinkedHashMap<>();
		requires.put("platform", getRequiredPlatform());
		requires.put("packages", new LinkedHashMap<>(getRequiredPackages()));
		m.put("requires", requires);
		return m;
	}

	/**
	 * Normalizes an {@code images} value: the single roles as entry names,
	 * {@code screenshots} as {@code path} / {@code caption} maps. Keeps only
	 * what is well-formed; unknown keys are ignored.
	 */
	public static Map<String, Object> normalizeImages(Object value) {
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, Object> images = map(value);
		for (String role : SINGLE_IMAGE_ROLES) {
			String entry = string(images.get(role));
			if (entry != null) {
				result.put(role, entry);
			}
		}
		Object screenshots = images.get(IMAGE_SCREENSHOTS);
		if (screenshots instanceof List) {
			List<Map<String, Object>> list = new ArrayList<>();
			for (Object o : (List<?>) screenshots) {
				Map<String, Object> shot = screenshot(o);
				if (shot != null) {
					list.add(shot);
				}
			}
			result.put(IMAGE_SCREENSHOTS, list);
		}
		return result;
	}

	/** One screenshot as {@code path} / {@code caption}, or null when it has no path. */
	public static Map<String, Object> screenshot(Object value) {
		String path = null;
		String caption = null;
		if (value instanceof Map) {
			path = string(((Map<?, ?>) value).get("path"));
			caption = string(((Map<?, ?>) value).get("caption"));
		} else {
			path = string(value);
		}
		if (path == null) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("path", path);
		m.put("caption", caption);
		return m;
	}

	@SuppressWarnings("unchecked")
	private static void collectImageEntries(Map<String, Object> images, Set<String> entries) {
		for (String role : SINGLE_IMAGE_ROLES) {
			Object entry = images.get(role);
			if (entry instanceof String) {
				entries.add((String) entry);
			}
		}
		Object screenshots = images.get(IMAGE_SCREENSHOTS);
		if (screenshots instanceof List) {
			for (Map<String, Object> shot : (List<Map<String, Object>>) screenshots) {
				entries.add((String) shot.get("path"));
			}
		}
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Object value) {
		if (value instanceof Map) {
			return (Map<String, Object>) value;
		}
		return Collections.emptyMap();
	}

	static String string(Object value) {
		if (value == null) {
			return null;
		}
		String s = value.toString().trim();
		return s.isEmpty() ? null : s;
	}

}

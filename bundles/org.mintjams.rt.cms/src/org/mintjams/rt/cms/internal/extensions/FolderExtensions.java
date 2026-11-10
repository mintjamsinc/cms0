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

package org.mintjams.rt.cms.internal.extensions;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.Session;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * The extensions of a folder: which extensions an app declares in its
 * {@code app.yml}, and which of them a folder's {@code .extensions.yml}
 * turns on.
 *
 * <p>An app declares what it extends, by name:</p>
 *
 * <pre>
 * extensions:
 *   content-browser:
 *     drafts:
 *       title: Site (drafts)
 *       module: browser/drafts.js
 * </pre>
 *
 * <p>A folder says where, in {@code .extensions.yml}; the nearest file on the
 * way up from a folder is the one that counts, as with {@code .web.yml}:</p>
 *
 * <pre>
 * content-browser:
 *   - site/drafts
 *   - use: blog/posts
 *     enabled: false
 *     options:
 *       feed: /content/public/feed
 * </pre>
 *
 * <p>The top-level key is the kind (what is extended), an item is
 * {@code <app relPath>/<name>}. The file can only name what an installed app
 * declares; it never says which code runs.</p>
 */
public final class FolderExtensions {

	/** The name of the file that turns extensions on for a folder and everything below it. */
	public static final String FILE_NAME = ".extensions.yml";

	/** A module inside the app directory: relative segments only, ending in .js or .mjs. */
	private static final Pattern MODULE_PATTERN = Pattern.compile("([A-Za-z0-9_-][A-Za-z0-9._-]*/)*[A-Za-z0-9_-][A-Za-z0-9._-]*\\.m?js");
	private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
	/** {@code <app relPath>/<name>}; the app's folder may be nested. */
	private static final Pattern USE_PATTERN = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]*/)+[A-Za-z0-9][A-Za-z0-9_-]*");

	private FolderExtensions() {}

	// =========================================================================
	// app.yml
	// =========================================================================

	/**
	 * Reads the {@code extensions} map of an {@code app.yml} into the
	 * {@code AppExtension} list of the {@code App} type. An entry without a
	 * usable name or module is dropped.
	 */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> fromDescriptor(Object extensionsObj) {
		List<Map<String, Object>> extensions = new ArrayList<>();
		if (!(extensionsObj instanceof Map)) {
			return extensions;
		}
		for (Map.Entry<String, Object> kind : ((Map<String, Object>) extensionsObj).entrySet()) {
			if (kind.getKey() == null || !NAME_PATTERN.matcher(kind.getKey()).matches()
					|| !(kind.getValue() instanceof Map)) {
				continue;
			}
			for (Map.Entry<String, Object> entry : ((Map<String, Object>) kind.getValue()).entrySet()) {
				if (entry.getKey() == null || !NAME_PATTERN.matcher(entry.getKey()).matches()
						|| !(entry.getValue() instanceof Map)) {
					continue;
				}
				Map<String, Object> value = (Map<String, Object>) entry.getValue();
				Object module = value.get("module");
				if (!(module instanceof String) || !MODULE_PATTERN.matcher((String) module).matches()
						|| ((String) module).contains("..")) {
					continue;
				}
				Map<String, Object> extension = new LinkedHashMap<>();
				extension.put("kind", kind.getKey());
				extension.put("identifier", entry.getKey());
				extension.put("title", value.get("title") instanceof String ? value.get("title") : null);
				extension.put("module", module);
				extensions.add(extension);
			}
		}
		return extensions;
	}

	/** Whether {@code use} has the form {@code <app relPath>/<name>}. */
	public static boolean isUse(String use) {
		return use != null && USE_PATTERN.matcher(use).matches() && !use.contains("..");
	}

	// =========================================================================
	// .extensions.yml
	// =========================================================================

	/** One item of a {@code .extensions.yml}. */
	public static final class Entry {
		public final String kind;
		public final String use;
		public final boolean enabled;
		public final Map<String, Object> options;

		Entry(String kind, String use, boolean enabled, Map<String, Object> options) {
			this.kind = kind;
			this.use = use;
			this.enabled = enabled;
			this.options = options;
		}
	}

	/**
	 * Reads the items of a {@code .extensions.yml}, enabled or not, in the
	 * order they are written; a second item for the same extension is dropped.
	 *
	 * @throws IllegalArgumentException when the text is not a YAML mapping
	 */
	@SuppressWarnings("unchecked")
	public static List<Entry> parse(String text) {
		Object parsed;
		try {
			parsed = new Load(LoadSettings.builder().build()).loadFromString(text == null ? "" : text);
		} catch (RuntimeException ex) {
			throw new IllegalArgumentException(ex.getMessage(), ex);
		}
		List<Entry> entries = new ArrayList<>();
		if (parsed == null) {
			return entries;
		}
		if (!(parsed instanceof Map)) {
			throw new IllegalArgumentException("The file must be a mapping of kinds to lists.");
		}
		for (Map.Entry<Object, Object> kind : ((Map<Object, Object>) parsed).entrySet()) {
			if (!(kind.getKey() instanceof String) || !(kind.getValue() instanceof List)) {
				continue;
			}
			List<String> seen = new ArrayList<>();
			for (Object item : (List<Object>) kind.getValue()) {
				String use = null;
				boolean enabled = true;
				Map<String, Object> options = null;
				if (item instanceof String) {
					use = ((String) item).trim();
				} else if (item instanceof Map) {
					Map<Object, Object> map = (Map<Object, Object>) item;
					if (map.get("use") instanceof String) {
						use = ((String) map.get("use")).trim();
					}
					Object enabledObj = map.get("enabled");
					if (enabledObj instanceof Boolean) {
						enabled = (Boolean) enabledObj;
					} else if (enabledObj instanceof String) {
						enabled = !"false".equalsIgnoreCase(((String) enabledObj).trim());
					}
					if (map.get("options") instanceof Map) {
						options = new LinkedHashMap<>();
						for (Map.Entry<Object, Object> option : ((Map<Object, Object>) map.get("options")).entrySet()) {
							options.put(String.valueOf(option.getKey()), option.getValue());
						}
					}
				}
				if (!isUse(use) || seen.contains(use)) {
					continue;
				}
				seen.add(use);
				entries.add(new Entry((String) kind.getKey(), use, enabled, options));
			}
		}
		return entries;
	}

	/**
	 * The extensions in force at {@code path}: those of the nearest
	 * {@code .extensions.yml} from the folder (or the file's folder) up to the
	 * root, read with the caller's session, so a file the caller cannot read
	 * does not count.
	 *
	 * @return {@code path}, {@code root} (the folder of the file found, or
	 *         null), {@code invalid} (the file could not be read as YAML) and
	 *         {@code entries} (the enabled items: kind, use, options)
	 */
	public static Map<String, Object> resolve(Session session, String path) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("path", path);
		result.put("root", null);
		result.put("invalid", false);
		List<Map<String, Object>> entries = new ArrayList<>();
		result.put("entries", entries);
		try {
			if (path == null || !path.startsWith("/") || !session.nodeExists(path)) {
				return result;
			}
			Node folder = session.getNode(path);
			if (folder.isNodeType("nt:file")) {
				folder = folder.getParent();
			}
			for (;;) {
				if (folder.hasNode(FILE_NAME)) {
					result.put("root", folder.getPath());
					try {
						for (Entry entry : parse(read(folder.getNode(FILE_NAME)))) {
							if (!entry.enabled) {
								continue;
							}
							Map<String, Object> map = new LinkedHashMap<>();
							map.put("kind", entry.kind);
							map.put("use", entry.use);
							map.put("options", entry.options);
							entries.add(map);
						}
					} catch (IllegalArgumentException ex) {
						result.put("invalid", true);
					}
					return result;
				}
				if (folder.getDepth() == 0) {
					return result;
				}
				folder = folder.getParent();
			}
		} catch (Throwable ex) {
			// A folder that cannot be walked has no extensions.
			return result;
		}
	}

	private static String read(Node file) throws Exception {
		if (!file.hasNode("jcr:content")) {
			return "";
		}
		Node content = file.getNode("jcr:content");
		if (!content.hasProperty("jcr:data")) {
			return "";
		}
		try (InputStream in = content.getProperty("jcr:data").getBinary().getStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	// =========================================================================
	// Adding an item without disturbing the rest of the file
	// =========================================================================

	/** The outcome of {@link #add}. */
	public static final class Addition {
		/** The new text of the file, or null when nothing is to be written. */
		public final String text;
		/** Why nothing is written: null (written), "present", "invalid" or "unsupported". */
		public final String skipped;

		private Addition(String text, String skipped) {
			this.text = text;
			this.skipped = skipped;
		}
	}

	/**
	 * Adds one item to the text of a {@code .extensions.yml}, leaving every
	 * other line, comment and the order as they are.
	 *
	 * <ul>
	 * <li>No file, or an empty one: the text is the item alone.</li>
	 * <li>An item for the same extension is there, enabled or not, with any
	 * options: nothing is written ({@code present}).</li>
	 * <li>The kind has a block list: the item goes after its last item. The
	 * kind is missing: it is appended to the file with the item.</li>
	 * <li>The file is not YAML ({@code invalid}), or the kind is written in a
	 * way a line cannot be appended to, such as a flow list
	 * ({@code unsupported}): nothing is written.</li>
	 * </ul>
	 *
	 * The result is read back before it is returned: if it does not hold
	 * every item the file had plus the new one, nothing is written
	 * ({@code unsupported}).
	 */
	public static Addition add(String text, String kind, String use, Map<String, Object> options) {
		if (!NAME_PATTERN.matcher(kind).matches() || !isUse(use)) {
			throw new IllegalArgumentException("Not an extension: " + kind + ": " + use);
		}
		String current = text == null ? "" : text;
		List<Entry> before;
		try {
			before = parse(current);
		} catch (IllegalArgumentException ex) {
			return new Addition(null, "invalid");
		}
		for (Entry entry : before) {
			if (entry.kind.equals(kind) && entry.use.equals(use)) {
				return new Addition(null, "present");
			}
		}

		String newline = current.contains("\r\n") ? "\r\n" : "\n";
		List<String> lines = new ArrayList<>();
		if (!current.isEmpty()) {
			for (String line : current.split("\r?\n", -1)) {
				lines.add(line);
			}
			// A text ending in a newline splits into a last empty line.
			if (lines.get(lines.size() - 1).isEmpty()) {
				lines.remove(lines.size() - 1);
			}
		}

		int keyLine = -1;
		Pattern key = Pattern.compile(Pattern.quote(kind) + "\\s*:\\s*(#.*)?");
		for (int i = 0; i < lines.size(); i++) {
			if (key.matcher(lines.get(i)).matches()) {
				if (keyLine >= 0) {
					return new Addition(null, "unsupported");
				}
				keyLine = i;
			}
		}

		String result;
		if (keyLine < 0) {
			boolean hasKind = false;
			for (Entry entry : before) {
				hasKind |= entry.kind.equals(kind);
			}
			if (hasKind || declaresKind(current, kind)) {
				// The kind is there, but not as a plain block key (a flow list, a quoted key).
				return new Addition(null, "unsupported");
			}
			List<String> out = new ArrayList<>(lines);
			if (!out.isEmpty() && !out.get(out.size() - 1).trim().isEmpty()) {
				out.add("");
			}
			out.add(kind + ":");
			out.addAll(item("  ", use, options));
			result = String.join(newline, out) + newline;
		} else {
			// The block of the key: every following line that is blank, a
			// comment, indented, or a list item written at the key's own column.
			int end = keyLine + 1;
			int last = keyLine;
			String indent = null;
			while (end < lines.size()) {
				String line = lines.get(end);
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					end++;
					continue;
				}
				boolean indented = line.startsWith(" ");
				boolean dash = trimmed.equals("-") || trimmed.startsWith("- ");
				if (!indented && !dash) {
					break;
				}
				if (dash && indent == null) {
					indent = line.substring(0, line.indexOf('-'));
				}
				last = end;
				end++;
			}
			if (last > keyLine && indent == null) {
				// Something other than a list is under the key.
				return new Addition(null, "unsupported");
			}
			if (indent == null) {
				indent = "  ";
			}
			List<String> out = new ArrayList<>(lines.subList(0, last + 1));
			out.addAll(item(indent, use, options));
			out.addAll(lines.subList(last + 1, lines.size()));
			result = String.join(newline, out) + newline;
		}

		// Read the result back: everything that was there, and the new item.
		try {
			List<Entry> after = parse(result);
			if (after.size() != before.size() + 1) {
				return new Addition(null, "unsupported");
			}
			boolean added = false;
			for (Entry entry : after) {
				if (entry.kind.equals(kind) && entry.use.equals(use)) {
					added = true;
					continue;
				}
				boolean kept = false;
				for (Entry old : before) {
					kept |= old.kind.equals(entry.kind) && old.use.equals(entry.use) && old.enabled == entry.enabled;
				}
				if (!kept) {
					return new Addition(null, "unsupported");
				}
			}
			if (!added) {
				return new Addition(null, "unsupported");
			}
		} catch (IllegalArgumentException ex) {
			return new Addition(null, "unsupported");
		}
		return new Addition(result, null);
	}

	/** Whether the YAML mapping has the kind as a key, however it is written. */
	private static boolean declaresKind(String text, String kind) {
		try {
			Object parsed = new Load(LoadSettings.builder().build()).loadFromString(text);
			return parsed instanceof Map && ((Map<?, ?>) parsed).containsKey(kind);
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/** The lines of one item: a plain name, or a mapping when it carries options. */
	private static List<String> item(String indent, String use, Map<String, Object> options) {
		List<String> lines = new ArrayList<>();
		if (options == null || options.isEmpty()) {
			lines.add(indent + "- " + use);
			return lines;
		}
		lines.add(indent + "- use: " + use);
		// JSON is YAML: the options go on one line, whatever they hold.
		lines.add(indent + "  options: " + json(options));
		return lines;
	}

	private static String json(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Boolean || value instanceof Number) {
			return value.toString();
		}
		if (value instanceof Map) {
			StringBuilder out = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				if (!first) {
					out.append(", ");
				}
				first = false;
				out.append(json(String.valueOf(entry.getKey()))).append(": ").append(json(entry.getValue()));
			}
			return out.append("}").toString();
		}
		if (value instanceof Iterable) {
			StringBuilder out = new StringBuilder("[");
			boolean first = true;
			for (Object item : (Iterable<?>) value) {
				if (!first) {
					out.append(", ");
				}
				first = false;
				out.append(json(item));
			}
			return out.append("]").toString();
		}
		StringBuilder out = new StringBuilder("\"");
		for (char c : value.toString().toCharArray()) {
			switch (c) {
			case '"':
				out.append("\\\"");
				break;
			case '\\':
				out.append("\\\\");
				break;
			case '\n':
				out.append("\\n");
				break;
			case '\r':
				out.append("\\r");
				break;
			case '\t':
				out.append("\\t");
				break;
			default:
				if (c < 0x20) {
					out.append(String.format("\\u%04x", (int) c));
				} else {
					out.append(c);
				}
			}
		}
		return out.append("\"").toString();
	}

}

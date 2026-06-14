/*
 * Copyright (c) 2022 MintJams Inc.
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

package org.mintjams.rt.cms.internal.web;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.tools.lang.Strings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Resolves how stored content is rendered, independent of the request scope so
 * that the same decision is shared by web serving ({@link WebResourceResolver})
 * and metadata exposure (GraphQL {@code NodeMapper}).
 *
 * <p>Two ways of binding content to a template are supported, in priority
 * order:</p>
 * <ol>
 * <li>an explicit {@code web.template} property on the file's {@code jcr:content}
 * (set per file, e.g. via the Inspector);</li>
 * <li>the nearest ancestor folder's {@value Webs#WEB_DESCRIPTOR_NAME} descriptor,
 * which binds files by glob (e.g. {@code *.md}) so a whole folder can be
 * rendered without touching each file.</li>
 * </ol>
 */
public final class WebRenders {

	private WebRenders() {}

	/**
	 * Source extensions a templated content node may carry in its natural file
	 * name (e.g. {@code index.md}), read from {@code /content/WEB-INF/web.yml}
	 * ({@code sourceExtensions}). Normalized to lower-case without a leading dot.
	 * Defaults to {@code md}.
	 */
	public static List<String> getSourceExtensions(Session session) {
		List<String> raw = stringList(configValue(session, "sourceExtensions"));
		if (raw.isEmpty()) {
			raw = Collections.singletonList("md");
		}
		return normalizeExtensions(raw);
	}

	/** Whether {@code name} ends with one of the given (normalized) source extensions. */
	public static boolean hasSourceExtension(String name, List<String> sourceExtensions) {
		return matchedSourceExtension(name, sourceExtensions) != null;
	}

	/** The source extension {@code name} carries, or {@code null} when it carries none. */
	public static String matchedSourceExtension(String name, List<String> sourceExtensions) {
		String lower = name.toLowerCase(Locale.ROOT);
		for (String extension : sourceExtensions) {
			if (lower.endsWith("." + extension)) {
				return extension;
			}
		}
		return null;
	}

	/**
	 * Effective template binding for {@code node}: the explicit
	 * {@code web.template} property if present, otherwise the nearest ancestor
	 * folder descriptor rule whose glob matches the file name. Returns
	 * {@code null} when the node is not bound to any template.
	 */
	public static Binding resolveBinding(Node node) throws RepositoryException {
		if (!node.isNodeType(NodeType.NT_FILE)) {
			return null;
		}

		// 1. Explicit per-file binding wins.
		try {
			String templatePath = node.getNode(Node.JCR_CONTENT).getProperty(Webs.WEB_TEMPLATE).getString();
			if (Strings.isNotEmpty(templatePath)) {
				return new Binding(templatePath, Collections.emptyList(), false);
			}
		} catch (PathNotFoundException ignore) {
		} catch (AccessDeniedException ignore) {}

		// 2. Nearest ancestor folder descriptor with a matching rule.
		String name = node.getName();
		Node folder;
		try {
			folder = node.getParent();
		} catch (RepositoryException ignore) {
			return null;
		}
		for (;;) {
			String path;
			try {
				path = folder.getPath();
			} catch (RepositoryException ignore) {
				break;
			}
			if (!isWithinContent(path)) {
				break;
			}

			for (Rule rule : getRules(folder)) {
				if (rule.matches(name)) {
					return new Binding(rule.fTemplatePath, rule.fOutputs, true);
				}
			}

			if (path.equals(Webs.CONTENT_PATH)) {
				break;
			}
			try {
				folder = folder.getParent();
			} catch (RepositoryException ignore) {
				break;
			}
		}
		return null;
	}

	/**
	 * Server-authoritative description of how {@code node} is rendered, suitable
	 * for exposing to clients (e.g. the text editor's preview). Keys:
	 * {@code templated}, {@code fromDescriptor}, {@code source} (matched source
	 * extension or {@code null}) and {@code outputs} (allowed output extensions;
	 * empty means any).
	 */
	public static Map<String, Object> describe(Node node) throws RepositoryException {
		Map<String, Object> result = new LinkedHashMap<>();
		Binding binding = resolveBinding(node);
		result.put("templated", binding != null);
		result.put("fromDescriptor", binding != null && binding.isFromDescriptor());
		result.put("source", matchedSourceExtension(node.getName(), getSourceExtensions(node.getSession())));
		result.put("outputs", binding != null ? binding.getOutputs() : Collections.emptyList());
		return result;
	}

	private static boolean isWithinContent(String path) {
		return path.equals(Webs.CONTENT_PATH) || path.startsWith(Webs.CONTENT_PATH + "/");
	}

	private static List<Rule> getRules(Node folder) {
		Node descriptor;
		try {
			if (!folder.hasNode(Webs.WEB_DESCRIPTOR_NAME)) {
				return Collections.emptyList();
			}
			descriptor = folder.getNode(Webs.WEB_DESCRIPTOR_NAME);
			if (!descriptor.isNodeType(NodeType.NT_FILE)) {
				return Collections.emptyList();
			}
		} catch (RepositoryException ignore) {
			return Collections.emptyList();
		}

		Object parsed;
		try (Reader in = JCRs.getContentAsReader(descriptor)) {
			parsed = new Load(LoadSettings.builder().build()).loadFromReader(in);
		} catch (Throwable ignore) {
			// A malformed descriptor must not break content serving; it simply
			// binds nothing.
			return Collections.emptyList();
		}

		if (!(parsed instanceof Map)) {
			return Collections.emptyList();
		}
		Object render = ((Map<?, ?>) parsed).get("render");
		if (!(render instanceof List)) {
			return Collections.emptyList();
		}

		List<Rule> rules = new ArrayList<>();
		for (Object entry : (List<?>) render) {
			if (!(entry instanceof Map)) {
				continue;
			}
			Map<?, ?> map = (Map<?, ?>) entry;
			String match = asString(map.get("match"));
			String template = asString(map.get("template"));
			if (Strings.isEmpty(match) || Strings.isEmpty(template)) {
				continue;
			}
			rules.add(new Rule(match, template, normalizeExtensions(stringList(map.get("output")))));
		}
		return rules;
	}

	private static Object configValue(Session session, String key) {
		Object parsed;
		try (Reader in = JCRs.getContentAsReader(session.getNode(Webs.DEFAULT_WEB_YML_PATH))) {
			parsed = new Load(LoadSettings.builder().build()).loadFromReader(in);
		} catch (Throwable ignore) {
			return null;
		}
		if (!(parsed instanceof Map)) {
			return null;
		}
		return ((Map<?, ?>) parsed).get(key);
	}

	/** Trims, strips leading dots, lower-cases and de-duplicates extension names. */
	public static List<String> normalizeExtensions(List<String> raw) {
		List<String> normalized = new ArrayList<>(raw.size());
		for (String value : raw) {
			if (value == null) {
				continue;
			}
			String extension = value.trim();
			while (extension.startsWith(".")) {
				extension = extension.substring(1);
			}
			if (extension.isEmpty()) {
				continue;
			}
			extension = extension.toLowerCase(Locale.ROOT);
			if (!normalized.contains(extension)) {
				normalized.add(extension);
			}
		}
		return normalized;
	}

	private static List<String> stringList(Object value) {
		if (value == null) {
			return Collections.emptyList();
		}
		List<String> result = new ArrayList<>();
		if (value instanceof List) {
			for (Object item : (List<?>) value) {
				String s = asString(item);
				if (s != null) {
					result.add(s);
				}
			}
		} else {
			String s = asString(value);
			if (s != null) {
				result.add(s);
			}
		}
		return result;
	}

	private static String asString(Object value) {
		return (value == null) ? null : value.toString();
	}

	/** Glob ({@code *}, {@code ?}) to anchored regex. Other characters are literal. */
	private static Pattern globToPattern(String glob) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			switch (c) {
			case '*':
				sb.append(".*");
				break;
			case '?':
				sb.append('.');
				break;
			case '.':
			case '\\':
			case '+':
			case '(':
			case ')':
			case '[':
			case ']':
			case '{':
			case '}':
			case '^':
			case '$':
			case '|':
				sb.append('\\').append(c);
				break;
			default:
				sb.append(c);
			}
		}
		return Pattern.compile(sb.toString());
	}

	/** A single rendering rule from a folder descriptor. */
	private static final class Rule {
		private final Pattern fPattern;
		private final String fTemplatePath;
		private final List<String> fOutputs;

		private Rule(String glob, String templatePath, List<String> outputs) {
			fPattern = globToPattern(glob);
			fTemplatePath = templatePath;
			fOutputs = outputs;
		}

		private boolean matches(String name) {
			return fPattern.matcher(name).matches();
		}
	}

	/** Effective binding of a content node to a template. */
	public static final class Binding {
		private final String fTemplatePath;
		private final List<String> fOutputs;
		private final boolean fFromDescriptor;

		private Binding(String templatePath, List<String> outputs, boolean fromDescriptor) {
			fTemplatePath = templatePath;
			fOutputs = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(outputs)));
			fFromDescriptor = fromDescriptor;
		}

		public String getTemplatePath() {
			return fTemplatePath;
		}

		/** Allowed output extensions (normalized, lower-case, no dot). Empty means any. */
		public List<String> getOutputs() {
			return fOutputs;
		}

		/** Whether this binding came from a folder descriptor rather than a per-file property. */
		public boolean isFromDescriptor() {
			return fFromDescriptor;
		}

		/**
		 * Whether the given output suffix (e.g. {@code ".html"} or {@code ".rss"})
		 * is allowed by this binding. A binding with no declared outputs allows any.
		 */
		public boolean allowsOutput(String suffix) {
			if (fOutputs.isEmpty()) {
				return true;
			}
			String extension = suffix;
			while (extension.startsWith(".")) {
				extension = extension.substring(1);
			}
			int p = extension.lastIndexOf('.');
			if (p != -1) {
				extension = extension.substring(p + 1);
			}
			return fOutputs.contains(extension.toLowerCase(Locale.ROOT));
		}
	}

}

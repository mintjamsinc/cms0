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

package org.mintjams.rt.cms.internal.mcp;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.dataset.Datasets;
import org.mintjams.rt.cms.internal.mcp.McpTool.Args;
import org.mintjams.rt.cms.internal.mcp.McpTool.Schema;
import org.mintjams.rt.cms.internal.web.WebRenders;
import org.mintjams.rt.cms.internal.web.Webs;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import com.google.gson.JsonObject;

/**
 * {@code explain_web_render}: why a web path is, or is not, served the way it
 * is.
 *
 * <p>Serving is deliberately forgiving. A folder descriptor that cannot be
 * parsed "binds nothing" instead of failing; a binding whose template is
 * missing makes the resolver move on to the next candidate; and the visitor is
 * only ever told 404. That keeps one bad file from taking a site down, and it
 * also means that the reason a page disappeared is recorded nowhere.
 *
 * <p>This tool walks the same decisions the web resolver makes
 * ({@code WebResourceResolver} for the candidate walk, {@link WebRenders} for
 * the binding — the binding is not re-implemented here, it is asked for), and
 * reports each one instead of swallowing it: which sources were considered,
 * which descriptor rule bound them, whether the template exists, and — the part
 * serving hides — every descriptor on the way up that exists but is broken,
 * with who last changed it and when.
 *
 * <p>It reads as the caller and changes nothing. What it cannot see are the
 * request filters configured in {@code web.yml}: they are scripts, and are
 * reported as present, not evaluated.
 */
final class WebRenderTools {

	/** Raw descriptor text larger than this is not echoed back. */
	private static final int MAX_DESCRIPTOR_CHARS = 20_000;

	private WebRenderTools() {}

	static List<McpTool> all() {
		return Collections.singletonList(explainWebRender());
	}

	private static McpTool explainWebRender() {
		return McpTool.named("explain_web_render").title("Explain web rendering")
				.description("Diagnose how a web path is served and, when it is not, why. Give the repository path",
						"that was requested (for a public URL, the path under the site's document root, e.g.",
						"/content/docs/index.html), even if no node exists there. Reports the outcome (rendered",
						"through a template, served as a file, redirected to a welcome file, not found, protected,",
						"access denied); every source and template the resolver considered; the web.yml and the",
						".web.yml descriptors that apply, including ones that exist but cannot be parsed and are",
						"therefore silently ignored when serving, with who last modified them; and whether an",
						"anonymous visitor can read the result. Start here when a page is missing, shows raw",
						"source, or stopped working after a configuration change.")
				.required("path", Schema.string("Absolute repository path as requested over the web"))
				.handler(WebRenderTools::explain).build();
	}

	private static McpToolResult explain(JsonObject arguments, McpCallContext context) throws Exception {
		String path = Args.path(arguments, "path");
		// Accept the path as it appears in a /bin/cms.cgi URL.
		String cgiPrefix = CmsConfiguration.CMS_CGI_PATH + "/" + context.getWorkspaceName();
		if (path.equals(cgiPrefix)) {
			path = "/";
		} else if (path.startsWith(cgiPrefix + "/")) {
			path = path.substring(cgiPrefix.length());
		}

		Session session = context.login();
		try {
			Map<String, Object> report = new LinkedHashMap<>();
			List<String> findings = new ArrayList<>();
			report.put("requestedPath", path);

			// ---- site configuration ---------------------------------------
			Map<String, Object> webYml = describeYamlFile(session, Webs.DEFAULT_WEB_YML_PATH);
			Object webConfig = webYml.remove("parsed");
			List<String> welcomeFiles = Arrays.asList("index.html", "index.gsp");
			if ("OK".equals(webYml.get("status"))) {
				Map<?, ?> config = (Map<?, ?>) webConfig;
				webYml.put("keys", new ArrayList<>(config.keySet()));
				webYml.put("config", config);
				List<String> configured = stringList(config.get("welcomeFiles"));
				if (!configured.isEmpty()) {
					welcomeFiles = configured;
				}
				Object filters = config.get("filters");
				if (filters instanceof List && !((List<?>) filters).isEmpty()) {
					findings.add(((List<?>) filters).size() + " request filter(s) are configured in "
							+ Webs.DEFAULT_WEB_YML_PATH + ". Filters run before the path is resolved and can"
							+ " change or block a request; this tool does not evaluate them.");
				}
			} else {
				findings.add(Webs.DEFAULT_WEB_YML_PATH + " is " + webYml.get("status")
						+ (webYml.get("error") != null ? " (" + webYml.get("error") + ")" : "")
						+ ". It is read on every web request to this workspace, so requests fail until it is a"
						+ " valid YAML mapping again.");
			}
			report.put("webYml", webYml);

			List<String> sourceExtensions = WebRenders.getSourceExtensions(session);
			String[] scriptExtensions = context.getScriptExtensions();
			report.put("sourceExtensions", sourceExtensions);

			// ---- resolution -----------------------------------------------
			Map<String, Object> resolution;
			String name = Args.nameOf(path);
			if (path.endsWith("/WEB-INF") || path.contains("/WEB-INF/")) {
				resolution = outcome("PROTECTED");
				findings.add("Paths under WEB-INF are never served over the web (404).");
			} else if (name.equals(Webs.WEB_DESCRIPTOR_NAME) || name.equals(Datasets.DESCRIPTOR_NAME)) {
				resolution = outcome("PROTECTED");
				findings.add(name + " is a folder descriptor: configuration, never served over the web (404).");
			} else {
				resolution = resolve(session, path, sourceExtensions, scriptExtensions, findings);
				if ("FOLDER".equals(resolution.get("outcome"))) {
					resolveWelcomeFile(session, path, welcomeFiles, sourceExtensions, scriptExtensions, resolution,
							findings);
				}
			}
			if (!"OK".equals(webYml.get("status"))) {
				// The resolution below is what would be served; right now nothing is.
				resolution.put("blockedBy", Webs.DEFAULT_WEB_YML_PATH);
			}
			report.put("resolution", resolution);

			// ---- descriptors that apply -----------------------------------
			String descriptorStart = (String) resolution.get("source");
			if (descriptorStart == null) {
				descriptorStart = path;
			}
			List<Map<String, Object>> descriptors = describeDescriptors(session, descriptorStart, findings);
			report.put("descriptors", descriptors);
			if (descriptors.isEmpty() && isWithinContent(path) && "NOT_FOUND".equals(resolution.get("outcome"))) {
				findings.add("No " + Webs.WEB_DESCRIPTOR_NAME + " descriptor exists in any folder from the requested"
						+ " path up to " + Webs.CONTENT_PATH + ", so only files with their own web.template"
						+ " property are rendered through a template here.");
			}

			// ---- what the public sees -------------------------------------
			anonymousAccess(context, resolution, findings);

			report.put("findings", findings);
			return McpToolResult.json(report);
		} finally {
			session.logout();
		}
	}

	/**
	 * Mirrors {@code WebResourceResolver.resolve}: the node itself if it
	 * exists, otherwise a walk over the candidate sources obtained by peeling
	 * extensions off the requested name.
	 */
	private static Map<String, Object> resolve(Session session, String path, List<String> sourceExtensions,
			String[] scriptExtensions, List<String> findings) throws RepositoryException {
		Node node;
		try {
			node = session.getNode(path);
		} catch (PathNotFoundException ex) {
			node = null;
		} catch (AccessDeniedException ex) {
			Map<String, Object> denied = outcome("ACCESS_DENIED");
			findings.add("A node exists at " + path + " but the signed-in user may not read it (403).");
			return denied;
		}

		if (node != null) {
			if (node.isNodeType(NodeType.NT_FOLDER)) {
				Map<String, Object> result = outcome("FOLDER");
				result.put("node", path);
				return result;
			}
			if (node.isNodeType(NodeType.NT_FILE)) {
				WebRenders.Binding binding = WebRenders.resolveBinding(node);
				String sourceExtension = WebRenders.matchedSourceExtension(node.getName(), sourceExtensions);
				if (binding != null && sourceExtension != null) {
					Map<String, Object> result = outcome("HIDDEN_SOURCE");
					result.put("source", path);
					result.put("binding", describe(binding));
					String base = path.substring(0, path.length() - sourceExtension.length() - 1);
					List<String> servedAt = new ArrayList<>();
					for (String output : binding.getOutputs()) {
						servedAt.add(base + "." + output);
					}
					if (servedAt.isEmpty()) {
						servedAt.add(base + ".html");
					}
					result.put("servedAt", servedAt);
					findings.add(path + " is a source bound to the template \"" + binding.getTemplatePath()
							+ "\". The raw source is not served (404); its rendered output is requested at an"
							+ " output extension, e.g. " + servedAt.get(0) + ".");
					return result;
				}
				Map<String, Object> result = outcome(isScript(node.getName(), scriptExtensions) ? "SERVED_AS_SCRIPT"
						: "SERVED_AS_FILE");
				result.put("source", path);
				result.put("mimeType", JCRs.getMimeType(node));
				if (binding != null) {
					result.put("binding", describe(binding));
				}
				return result;
			}
			Map<String, Object> result = outcome("SERVED_AS_FILE");
			result.put("node", path);
			result.put("nodeType", node.getPrimaryNodeType().getName());
			return result;
		}

		// No node at the requested path: look for a templated source.
		List<Map<String, Object>> considered = new ArrayList<>();
		int p = path.lastIndexOf('/');
		String parentPath = path.substring(0, p + 1);
		String basename = path.substring(p + 1);
		String suffix = "";
		Map<String, Object> resolved = null;
		walk: for (;;) {
			// Natural-extension sources (index.md for a request for index.html).
			for (String sourceExtension : sourceExtensions) {
				Map<String, Object> result = tryCandidate(session, parentPath + basename + "." + sourceExtension,
						suffix, scriptExtensions, considered, findings);
				if (result != null) {
					resolved = result;
					break walk;
				}
			}

			// Legacy convention: a source named without any extension. Unlike the
			// candidates above, an existing but unusable node ends the walk.
			String legacyPath = parentPath + basename;
			if (exists(session, legacyPath)) {
				Map<String, Object> result = tryCandidate(session, legacyPath, suffix, scriptExtensions, considered,
						findings);
				if (result != null) {
					resolved = result;
				}
				break;
			}

			p = basename.lastIndexOf('.');
			if (p == -1) {
				break;
			}
			suffix = basename.substring(p) + suffix;
			basename = basename.substring(0, p);
		}

		if (resolved == null) {
			resolved = outcome("NOT_FOUND");
			if (considered.isEmpty()) {
				findings.add("There is no node at " + path + ", and no source it could be rendered from (looked for"
						+ " the same name with the source extensions " + sourceExtensions
						+ ", and without an extension). If the content exists, it is stored under a different"
						+ " name or folder.");
			}
		}
		if (!considered.isEmpty()) {
			resolved.put("considered", considered);
		}
		return resolved;
	}

	/**
	 * Evaluates one candidate source for the output {@code suffix}. Returns the
	 * resolution when it renders, {@code null} otherwise; either way the
	 * candidate and the reason are recorded if the node exists.
	 */
	private static Map<String, Object> tryCandidate(Session session, String sourcePath, String suffix,
			String[] scriptExtensions, List<Map<String, Object>> considered, List<String> findings)
			throws RepositoryException {
		Node node;
		try {
			node = session.getNode(sourcePath);
		} catch (PathNotFoundException ex) {
			return null;
		} catch (AccessDeniedException ex) {
			Map<String, Object> denied = outcome("ACCESS_DENIED");
			denied.put("source", sourcePath);
			findings.add("The source " + sourcePath + " exists but the signed-in user may not read it (403).");
			return denied;
		}

		Map<String, Object> candidate = new LinkedHashMap<>();
		candidate.put("source", sourcePath);
		candidate.put("output", suffix);
		considered.add(candidate);

		WebRenders.Binding binding = WebRenders.resolveBinding(node);
		if (binding == null) {
			candidate.put("rejected", "not bound to a template");
			findings.add("The source " + sourcePath + " exists but is not bound to a template: it has no"
					+ " web.template property, and no " + Webs.WEB_DESCRIPTOR_NAME + " rule in its folder or an"
					+ " ancestor folder matches the name \"" + node.getName() + "\". See descriptors below for a"
					+ " descriptor that is missing, malformed, or whose rules do not match.");
			return null;
		}
		candidate.put("binding", describe(binding));
		if (!binding.allowsOutput(suffix)) {
			candidate.put("rejected", "output not allowed by the binding");
			findings.add("The source " + sourcePath + " is bound to the template \"" + binding.getTemplatePath()
					+ "\", but the binding only allows the outputs " + binding.getOutputs() + ", not \"" + suffix
					+ "\".");
			return null;
		}

		List<String> tried = new ArrayList<>();
		String templatePath = findTemplate(session, binding.getTemplatePath() + suffix, scriptExtensions, tried);
		if (templatePath == null) {
			candidate.put("rejected", "template not found");
			candidate.put("templatesTried", tried);
			findings.add("The source " + sourcePath + " is bound to the template \"" + binding.getTemplatePath()
					+ "\", but no template file exists for the output \"" + suffix + "\". Looked for: " + tried
					+ ".");
			return null;
		}

		Map<String, Object> result = outcome("RENDERED_THROUGH_TEMPLATE");
		result.put("source", sourcePath);
		result.put("template", templatePath);
		result.put("binding", describe(binding));
		return result;
	}

	/** Mirrors {@code WebResourceResolver.getTemplate} for a GET request. */
	private static String findTemplate(Session session, String templatePath, String[] scriptExtensions,
			List<String> tried) throws RepositoryException {
		if (!templatePath.startsWith("/")) {
			templatePath = Webs.DEFAULT_WEB_TEMPLATE_PATH + "/" + templatePath;
		}
		int p = templatePath.lastIndexOf('/');
		String parentPath = templatePath.substring(0, p + 1);
		String filename = templatePath.substring(p + 1);
		for (String basename : new String[] { "GET." + filename, filename }) {
			for (String scriptExtension : scriptExtensions) {
				String candidate = parentPath + basename + "." + scriptExtension;
				tried.add(candidate);
				if (exists(session, candidate)) {
					return candidate;
				}
			}
		}
		return null;
	}

	/** A folder is served by redirecting to the first welcome file that resolves. */
	private static void resolveWelcomeFile(Session session, String folderPath, List<String> welcomeFiles,
			List<String> sourceExtensions, String[] scriptExtensions, Map<String, Object> resolution,
			List<String> findings) throws RepositoryException {
		String prefix = folderPath.equals("/") ? "/" : folderPath + "/";
		resolution.put("welcomeFiles", welcomeFiles);
		for (String welcomeFile : welcomeFiles) {
			List<String> ignored = new ArrayList<>();
			Map<String, Object> welcome = resolve(session, prefix + welcomeFile, sourceExtensions, scriptExtensions,
					ignored);
			if (!"NOT_FOUND".equals(welcome.get("outcome"))) {
				resolution.put("outcome", "FOLDER_REDIRECT");
				resolution.put("redirectsTo", prefix + welcomeFile);
				resolution.put("welcome", welcome);
				if (welcome.get("source") != null) {
					resolution.put("source", welcome.get("source"));
				}
				if (welcome.get("template") != null) {
					resolution.put("template", welcome.get("template"));
				}
				return;
			}
		}
		resolution.put("outcome", "NOT_FOUND");
		findings.add(folderPath + " is a folder, and none of the welcome files " + welcomeFiles
				+ " resolves inside it, so the folder itself answers 404. Call this tool on " + prefix
				+ welcomeFiles.get(0) + " to see why.");
	}

	/**
	 * Every {@code .web.yml} from the folder of {@code path} (or its deepest
	 * existing ancestor) up to {@code /content}, nearest first — the order in
	 * which rules are applied. Broken ones are reported, not skipped.
	 */
	private static List<Map<String, Object>> describeDescriptors(Session session, String path,
			List<String> findings) throws RepositoryException {
		List<Map<String, Object>> descriptors = new ArrayList<>();
		if (!isWithinContent(path)) {
			return descriptors;
		}

		String folderPath = path;
		for (;;) {
			boolean isFolder = false;
			try {
				isFolder = session.getNode(folderPath).isNodeType(NodeType.NT_FOLDER);
			} catch (PathNotFoundException | AccessDeniedException ignore) {}

			if (isFolder) {
				String descriptorPath = folderPath + "/" + Webs.WEB_DESCRIPTOR_NAME;
				if (exists(session, descriptorPath)) {
					Map<String, Object> descriptor = describeYamlFile(session, descriptorPath);
					Object parsed = descriptor.remove("parsed");
					if ("OK".equals(descriptor.get("status"))) {
						describeRules((Map<?, ?>) parsed, descriptor, descriptorPath, findings);
					} else {
						findings.add(descriptorPath + " exists but is " + descriptor.get("status")
								+ (descriptor.get("error") != null ? " (" + descriptor.get("error") + ")" : "")
								+ ". When serving, a descriptor that cannot be used is ignored without an error:"
								+ " it binds no file to a template, and files under " + folderPath
								+ " fall back to the next descriptor up, or are not rendered at all."
								+ (descriptor.get("modifiedBy") != null
										? " Last modified " + descriptor.get("modified") + " by "
												+ descriptor.get("modifiedBy") + "."
										: ""));
					}
					descriptors.add(descriptor);
				}
			}

			if (folderPath.equals(Webs.CONTENT_PATH)) {
				break;
			}
			folderPath = Args.parentOf(folderPath);
			if (!isWithinContent(folderPath)) {
				break;
			}
		}
		return descriptors;
	}

	private static void describeRules(Map<?, ?> parsed, Map<String, Object> descriptor, String descriptorPath,
			List<String> findings) {
		Object site = parsed.get("site");
		if (site instanceof Map) {
			Object root = ((Map<?, ?>) site).get("root");
			descriptor.put("siteRoot", root != null && "true".equalsIgnoreCase(root.toString().trim()));
		}

		List<Map<String, Object>> rules = new ArrayList<>();
		int ignored = 0;
		Object render = parsed.get("render");
		if (render instanceof List) {
			for (Object entry : (List<?>) render) {
				if (!(entry instanceof Map)) {
					ignored++;
					continue;
				}
				Map<?, ?> map = (Map<?, ?>) entry;
				String match = (map.get("match") == null) ? null : map.get("match").toString();
				String template = (map.get("template") == null) ? null : map.get("template").toString();
				if (match == null || match.isEmpty() || template == null || template.isEmpty()) {
					ignored++;
					continue;
				}
				Map<String, Object> rule = new LinkedHashMap<>();
				rule.put("match", match);
				rule.put("template", template);
				rule.put("output", WebRenders.normalizeExtensions(stringList(map.get("output"))));
				rules.add(rule);
			}
		} else if (render != null) {
			findings.add(descriptorPath + ": \"render\" is not a list, so the descriptor defines no rendering"
					+ " rule.");
		}
		descriptor.put("rules", rules);
		if (ignored > 0) {
			descriptor.put("ignoredRules", ignored);
			findings.add(descriptorPath + ": " + ignored + " entr" + (ignored == 1 ? "y" : "ies")
					+ " under \"render\" lack a \"match\" or a \"template\" and are ignored when serving.");
		}
	}

	/**
	 * Reads a YAML file the way serving does, but keeps what serving discards:
	 * whether it exists, why it could not be used, and who last touched it.
	 * The parsed mapping is returned under the transient key {@code parsed}.
	 */
	private static Map<String, Object> describeYamlFile(Session session, String path) {
		Map<String, Object> description = new LinkedHashMap<>();
		description.put("path", path);

		Node node;
		try {
			node = session.getNode(path);
		} catch (PathNotFoundException ex) {
			description.put("status", "MISSING");
			return description;
		} catch (RepositoryException ex) {
			description.put("status", "UNREADABLE");
			description.put("error", McpServer.describe(ex));
			return description;
		}

		String text;
		try {
			if (!node.isNodeType(NodeType.NT_FILE)) {
				description.put("status", "NOT_A_FILE");
				description.put("error", "the node is a " + node.getPrimaryNodeType().getName());
				return description;
			}
			description.put("size", JCRs.getContentLength(node));
			java.util.Date modified = JCRs.getLastModified(node);
			if (modified != null) {
				description.put("modified", modified.toInstant().toString());
			}
			String modifiedBy = JCRs.getLastModifiedBy(node);
			if (modifiedBy != null) {
				description.put("modifiedBy", modifiedBy);
			}
			try (Reader in = JCRs.getContentAsReader(node)) {
				StringBuilder sb = new StringBuilder();
				char[] buffer = new char[4096];
				int n;
				while ((n = in.read(buffer)) != -1 && sb.length() <= MAX_DESCRIPTOR_CHARS) {
					sb.append(buffer, 0, n);
				}
				text = sb.toString();
			}
		} catch (Throwable ex) {
			description.put("status", "UNREADABLE");
			description.put("error", McpServer.describe(ex));
			return description;
		}

		Object parsed;
		try {
			parsed = new Load(LoadSettings.builder().build()).loadFromString(text);
		} catch (Throwable ex) {
			description.put("status", "MALFORMED");
			description.put("error", McpServer.describe(ex));
			description.put("content", text);
			return description;
		}
		if (parsed == null) {
			description.put("status", "EMPTY");
			return description;
		}
		if (!(parsed instanceof Map)) {
			description.put("status", "NOT_A_MAPPING");
			description.put("error", "the YAML document is " + ((parsed instanceof List) ? "a list" : "a single value")
					+ ", not a mapping of keys to values");
			description.put("content", text);
			return description;
		}
		description.put("status", "OK");
		description.put("parsed", parsed);
		return description;
	}

	/** Whether the anonymous web visitor can read what the caller resolved. */
	private static void anonymousAccess(McpCallContext context, Map<String, Object> resolution,
			List<String> findings) {
		String source = (String) resolution.get("source");
		String template = (String) resolution.get("template");
		if (source == null && template == null) {
			return;
		}
		Session guest = context.loginAsGuest();
		if (guest == null) {
			return;
		}
		try {
			Map<String, Object> anonymous = new LinkedHashMap<>();
			if (source != null) {
				boolean readable = exists(guest, source);
				anonymous.put("canReadSource", readable);
				if (!readable) {
					findings.add("An anonymous visitor cannot read " + source
							+ ": without signing in, this page is not available to them.");
				}
			}
			if (template != null) {
				boolean readable = exists(guest, template);
				anonymous.put("canReadTemplate", readable);
				if (!readable) {
					findings.add("An anonymous visitor cannot read the template " + template
							+ ", so the page does not render for them even though the source is readable.");
				}
			}
			resolution.put("anonymous", anonymous);
		} finally {
			guest.logout();
		}
	}

	private static Map<String, Object> describe(WebRenders.Binding binding) {
		Map<String, Object> description = new LinkedHashMap<>();
		description.put("template", binding.getTemplatePath());
		description.put("outputs", binding.getOutputs());
		description.put("from", binding.isFromDescriptor() ? Webs.WEB_DESCRIPTOR_NAME + " rule" : "web.template property");
		return description;
	}

	private static Map<String, Object> outcome(String outcome) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("outcome", outcome);
		return result;
	}

	private static boolean exists(Session session, String path) {
		try {
			return session.nodeExists(path);
		} catch (RepositoryException ex) {
			return false;
		}
	}

	private static boolean isScript(String name, String[] scriptExtensions) {
		for (String scriptExtension : scriptExtensions) {
			if (name.endsWith("." + scriptExtension)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isWithinContent(String path) {
		return path.equals(Webs.CONTENT_PATH) || path.startsWith(Webs.CONTENT_PATH + "/");
	}

	private static List<String> stringList(Object value) {
		List<String> result = new ArrayList<>();
		if (value instanceof List) {
			for (Object item : (List<?>) value) {
				if (item != null) {
					result.add(item.toString());
				}
			}
		} else if (value != null) {
			result.add(value.toString());
		}
		return result;
	}

}

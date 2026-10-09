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

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.jcr.nodetype.NodeType;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import graphql.language.Document;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.validation.ValidationError;
import graphql.validation.Validator;

/**
 * Builds a workspace's {@link McpToolRegistry} from the {@code tools.yml}
 * files under its GraphQL folders.
 *
 * <p>Each file is deployed on its own: a file that cannot be read keeps the
 * tools of its last good version (marked stale) and the others are deployed
 * as usual; within a file, a tool that is not valid is left out and the
 * others are deployed. Every such problem is recorded on the file's
 * deployment and written to the log, so nothing fails silently and nothing
 * takes the rest down.
 *
 * <p>A tool is either a GraphQL operation ({@code graphql:}) run with the
 * tool's arguments as its variables, or a script ({@code script:}) run like
 * a GraphQL resolver, as the caller. See documents/mcp-server.md for the file
 * format.
 */
public final class McpToolCompiler {

	public static final String FILE_NAME = "tools.yml";

	private static final Pattern TOOL_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
	private static final Set<String> ACCESS_VALUES = new HashSet<>(Arrays.asList("read", "write", "destructive"));
	private static final Set<String> SCHEMA_TYPES = new HashSet<>(
			Arrays.asList("string", "integer", "number", "boolean", "object", "array"));
	private static final Gson GSON = new Gson();

	private McpToolCompiler() {}

	/**
	 * Scans the roots for {@code tools.yml} files and deploys them.
	 *
	 * @param serviceSession a session that can read the roots
	 * @param workspaceName the workspace
	 * @param roots the folders to scan, recursively
	 * @param previous the registry being replaced, whose deployments are kept
	 *        for files that can no longer be read; may be null
	 * @param schema the workspace schema a GraphQL tool's operation is validated
	 *        against; null when there is none yet, in which case the operation
	 *        is only parsed
	 */
	public static McpToolRegistry compile(Session serviceSession, String workspaceName, List<String> roots,
			McpToolRegistry previous, GraphQLSchema schema) throws RepositoryException {
		// The built-in tools' names are not available to the workspace.
		Set<String> reservedNames = McpTools.names();
		Set<String> scriptExtensions = new HashSet<>(
				Arrays.asList(Scripts.getScriptExtensions(CmsService.getWorkspaceScriptEngineManager(workspaceName))));
		List<Node> files = new ArrayList<>();
		for (String root : roots) {
			Node node;
			try {
				node = serviceSession.getNode(root);
			} catch (PathNotFoundException ignore) {
				continue;
			}
			scan(node, files);
		}
		Map<String, McpToolRegistry.Deployment> deployments = new LinkedHashMap<>();
		// name -> path of the file that claimed it first
		Map<String, String> claimed = new LinkedHashMap<>();
		long now = System.currentTimeMillis();
		for (Node file : files) {
			String path = file.getPath();
			McpToolRegistry.Deployment deployment;
			try {
				deployment = deploy(file, workspaceName, scriptExtensions, reservedNames, claimed, now, schema);
			} catch (Throwable ex) {
				String problem = "The file could not be read: " + McpServer.describe(ex);
				McpToolRegistry.Deployment last = (previous == null) ? null : previous.getDeployments().get(path);
				if (last != null && !last.getEntries().isEmpty()) {
					// Keep serving what the file last said; the problem is reported.
					for (McpToolRegistry.Entry entry : last.getEntries()) {
						claimed.putIfAbsent(entry.getTool().getName(), path);
					}
					deployment = last.asStale(now, List.of(problem));
					CmsService.getLogger(McpToolCompiler.class).error("Failed to deploy the MCP tools file " + path
							+ " in workspace " + workspaceName + "; its previous tools are kept", ex);
				} else {
					deployment = new McpToolRegistry.Deployment(path, now, false, List.of(), List.of(problem));
					CmsService.getLogger(McpToolCompiler.class).error("Failed to deploy the MCP tools file " + path
							+ " in workspace " + workspaceName, ex);
				}
			}
			deployments.put(path, deployment);
		}
		if (!deployments.isEmpty()) {
			int tools = 0;
			int problems = 0;
			for (McpToolRegistry.Deployment deployment : deployments.values()) {
				tools += deployment.getEntries().size();
				problems += deployment.getProblems().size();
			}
			CmsService.getLogger(McpToolCompiler.class).info("Deployed the MCP tools of workspace \"" + workspaceName
					+ "\": " + deployments.size() + " file(s), " + tools + " tool(s), " + problems + " problem(s).");
		}
		return new McpToolRegistry(deployments);
	}

	private static void scan(Node node, List<Node> files) throws RepositoryException {
		String primaryType = node.getPrimaryNodeType().getName();
		if (primaryType.equals(NodeType.NT_FOLDER_NAME)) {
			for (NodeIterator i = node.getNodes(); i.hasNext();) {
				scan(i.nextNode(), files);
			}
			return;
		}
		if (primaryType.equals(NodeType.NT_FILE_NAME) && node.getName().equals(FILE_NAME)) {
			files.add(node);
		}
	}

	@SuppressWarnings("unchecked")
	private static McpToolRegistry.Deployment deploy(Node file, String workspaceName, Set<String> scriptExtensions,
			Set<String> reservedNames, Map<String, String> claimed, long now, GraphQLSchema schema) throws Exception {
		String path = file.getPath();
		String basePath = file.getParent().getPath();
		Map<String, Object> doc = parseYaml(file);
		List<McpToolRegistry.Entry> entries = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		Object toolsObj = doc.get("tools");
		if (toolsObj == null) {
			problems.add("The file has no \"tools\" map.");
		} else if (!(toolsObj instanceof Map)) {
			problems.add("\"tools\" must be a map of tool name to definition.");
		} else {
			for (Map.Entry<String, Object> e : ((Map<String, Object>) toolsObj).entrySet()) {
				String name = String.valueOf(e.getKey());
				try {
					if (!TOOL_NAME.matcher(name).matches()) {
						throw new IllegalArgumentException("A tool name is 1 to 64 letters, digits, \"_\" or \"-\".");
					}
					if (reservedNames.contains(name)) {
						throw new IllegalArgumentException("The name is taken by a built-in tool.");
					}
					String owner = claimed.get(name);
					if (owner != null && !owner.equals(path)) {
						throw new IllegalArgumentException("The name is already defined in " + owner + ".");
					}
					if (!(e.getValue() instanceof Map)) {
						throw new IllegalArgumentException("The definition must be a map.");
					}
					McpToolRegistry.Entry entry = build(name, (Map<String, Object>) e.getValue(), file.getSession(),
							basePath, workspaceName, scriptExtensions, schema);
					claimed.put(name, path);
					entries.add(entry);
				} catch (Throwable ex) {
					String problem = "Tool \"" + name + "\" was not deployed: " + messageOf(ex);
					problems.add(problem);
					CmsService.getLogger(McpToolCompiler.class).warn(path + ": " + problem);
				}
			}
		}
		return new McpToolRegistry.Deployment(path, now, false, entries, problems);
	}

	@SuppressWarnings("unchecked")
	private static McpToolRegistry.Entry build(String name, Map<String, Object> def, Session session,
			String basePath, String workspaceName, Set<String> scriptExtensions, GraphQLSchema schema) throws Exception {
		McpTool.Builder builder = McpTool.named(name);
		Object title = def.get("title");
		if (title != null && !String.valueOf(title).trim().isEmpty()) {
			builder.title(String.valueOf(title).trim());
		}
		String description = text(def.get("description"));
		if (description == null) {
			throw new IllegalArgumentException("\"description\" is required: it is what the model decides by.");
		}
		builder.description(description);
		boolean enabled = !Boolean.FALSE.equals(def.get("enabled"));
		boolean idempotent = Boolean.TRUE.equals(def.get("idempotent"));
		String access = null;
		if (def.get("access") != null) {
			access = String.valueOf(def.get("access")).trim().toLowerCase();
			if (!ACCESS_VALUES.contains(access)) {
				throw new IllegalArgumentException("\"access\" must be read, write or destructive.");
			}
		}
		if (def.containsKey("runAs")) {
			throw new IllegalArgumentException("\"runAs\" is not allowed: a tool runs as the caller. Put the"
					+ " privileged part in a GraphQL resolver with runAs in wiring.yml and call it from the tool.");
		}

		Object input = def.get("input");
		if (input != null) {
			if (!(input instanceof Map)) {
				throw new IllegalArgumentException("\"input\" must be a map with \"properties\" and \"required\".");
			}
			Map<String, Object> inputMap = (Map<String, Object>) input;
			Object properties = inputMap.get("properties");
			Set<String> required = new HashSet<>();
			Object requiredObj = inputMap.get("required");
			if (requiredObj instanceof List) {
				for (Object r : (List<Object>) requiredObj) {
					required.add(String.valueOf(r));
				}
			} else if (requiredObj != null) {
				throw new IllegalArgumentException("\"input.required\" must be a list of property names.");
			}
			Map<String, Object> propertyMap = new LinkedHashMap<>();
			if (properties instanceof Map) {
				propertyMap.putAll((Map<String, Object>) properties);
			} else if (properties != null) {
				throw new IllegalArgumentException("\"input.properties\" must be a map of name to JSON Schema.");
			}
			for (String r : required) {
				if (!propertyMap.containsKey(r)) {
					throw new IllegalArgumentException("\"input.required\" names \"" + r + "\", which is not a property.");
				}
			}
			for (Map.Entry<String, Object> p : propertyMap.entrySet()) {
				if (!(p.getValue() instanceof Map)) {
					throw new IllegalArgumentException("The property \"" + p.getKey() + "\" must be a JSON Schema map.");
				}
				Object type = ((Map<String, Object>) p.getValue()).get("type");
				if (type == null || !SCHEMA_TYPES.contains(String.valueOf(type))) {
					throw new IllegalArgumentException("The property \"" + p.getKey() + "\" needs a \"type\" of "
							+ "string, integer, number, boolean, object or array.");
				}
				JsonObject propertySchema = toJson(p.getValue()).getAsJsonObject();
				if (required.contains(p.getKey())) {
					builder.required(p.getKey(), propertySchema);
				} else {
					builder.optional(p.getKey(), propertySchema);
				}
			}
		}

		Object graphql = def.get("graphql");
		Object script = def.get("script");
		if ((graphql == null) == (script == null)) {
			throw new IllegalArgumentException("Give exactly one of \"graphql\" (an operation) or \"script\" (a file).");
		}
		String kind;
		if (graphql != null) {
			kind = "graphql";
			String query;
			String operationName = null;
			if (graphql instanceof Map) {
				Map<String, Object> g = (Map<String, Object>) graphql;
				query = text(g.get("query"));
				operationName = text(g.get("operationName"));
			} else {
				query = text(graphql);
			}
			if (query == null) {
				throw new IllegalArgumentException("\"graphql\" needs the operation document (\"graphql.query\").");
			}
			McpGraphQL.OperationKind operation = McpGraphQL.operationKind(query, operationName);
			switch (operation) {
			case QUERY:
				if (access == null) {
					access = "read";
				}
				break;
			case MUTATION:
				if (access == null) {
					access = "write";
				} else if (access.equals("read")) {
					throw new IllegalArgumentException("The operation is a mutation, so \"access\" cannot be read.");
				}
				break;
			case SUBSCRIPTION:
				throw new IllegalArgumentException("Subscriptions cannot be tools: a tool call returns once.");
			default:
				throw new IllegalArgumentException("The operation could not be identified: send a document that"
						+ " parses and holds a single operation, or name it with \"graphql.operationName\".");
			}
			if (schema != null) {
				// Validated against the schema now, not at the first call: a tool
				// whose operation names a field that does not exist is a problem of
				// the file, and the one who wrote it is told at once.
				Document document = Parser.parse(query);
				List<ValidationError> errors = new Validator().validateDocument(schema, document, Locale.ENGLISH);
				if (!errors.isEmpty()) {
					List<String> messages = new ArrayList<>();
					for (ValidationError error : errors) {
						messages.add(error.getMessage());
					}
					throw new IllegalArgumentException("The operation is not valid for the workspace schema: "
							+ String.join("; ", messages));
				}
			}
			builder.handler(new GraphQLToolHandler(query, operationName));
		} else {
			kind = "script";
			String scriptPath = text(script);
			if (scriptPath == null) {
				throw new IllegalArgumentException("\"script\" must be the path of the script file.");
			}
			String absolute = scriptPath.startsWith("/") ? scriptPath : (basePath + "/" + scriptPath);
			Node scriptNode;
			try {
				scriptNode = session.getNode(absolute);
			} catch (PathNotFoundException ex) {
				throw new IllegalArgumentException("The script was not found: " + absolute);
			}
			if (!scriptNode.getPrimaryNodeType().getName().equals(NodeType.NT_FILE_NAME)) {
				throw new IllegalArgumentException("The script is not a file: " + absolute);
			}
			String extension = extensionOf(scriptNode.getName());
			if (!scriptExtensions.contains(extension)) {
				throw new IllegalArgumentException("No script engine handles \"." + extension + "\": " + absolute);
			}
			if (access == null) {
				// A script is a program: it can change content, so it is assumed
				// to unless its author says otherwise.
				access = "write";
			}
			builder.handler(new ScriptToolHandler(workspaceName, scriptNode.getPath(), extension, readText(scriptNode)));
		}
		if (access.equals("destructive")) {
			builder.destructive();
		} else if (access.equals("write")) {
			builder.write();
		}
		if (idempotent) {
			builder.idempotent();
		}
		return new McpToolRegistry.Entry(builder.build(), kind, access, enabled);
	}

	/** A text value: a string, or a list of lines joined with spaces; null when absent or blank. */
	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String s;
		if (value instanceof List) {
			List<String> lines = new ArrayList<>();
			for (Object line : (List<?>) value) {
				if (line != null) {
					lines.add(String.valueOf(line).trim());
				}
			}
			s = String.join(" ", lines);
		} else {
			s = String.valueOf(value);
		}
		s = s.trim();
		return s.isEmpty() ? null : s;
	}

	private static JsonElement toJson(Object value) {
		return GSON.toJsonTree(value);
	}

	private static String messageOf(Throwable ex) {
		String message = ex.getMessage();
		return (message == null || message.isEmpty()) ? ex.getClass().getSimpleName() : message;
	}

	private static String extensionOf(String name) {
		int p = name.lastIndexOf('.');
		return (p == -1) ? "" : name.substring(p + 1);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseYaml(Node fileNode) throws Exception {
		Node content = fileNode.getNode(Node.JCR_CONTENT);
		try (InputStream in = new BufferedInputStream(
				content.getProperty(Property.JCR_DATA).getBinary().getStream())) {
			Object loaded = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			if (loaded == null) {
				return Collections.emptyMap();
			}
			if (!(loaded instanceof Map)) {
				throw new IllegalArgumentException("The file must be a YAML map.");
			}
			return (Map<String, Object>) loaded;
		}
	}

	private static String readText(Node fileNode) throws Exception {
		Node content = fileNode.getNode(Node.JCR_CONTENT);
		try (InputStream in = new BufferedInputStream(
				content.getProperty(Property.JCR_DATA).getBinary().getStream())) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}

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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.mcp.McpTool.Args;
import org.mintjams.rt.cms.internal.mcp.McpTool.Schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The content tools: browsing, reading, searching and changing files, folders
 * and their properties.
 *
 * <p>Two rules shape the tools that write.
 *
 * <p><b>Nothing is replaced by accident.</b> {@code write_file} refuses an
 * existing path unless {@code overwrite} is set, and the refusal says what is
 * there. A model asked to "add a setting" to a configuration file otherwise
 * tends to write the file it imagines rather than the file that exists.
 *
 * <p><b>Every change is logged with who made it.</b> The repository records
 * the modifying user on the node; the log additionally records that the change
 * came through MCP, which the node cannot tell you.
 */
final class ContentTools {

	/** Characters {@code read_file} returns by default, and at most, per call. */
	private static final int DEFAULT_READ_CHARS = 100_000;
	private static final int MAX_READ_CHARS = 500_000;

	/** Largest image returned inline as an image block. */
	private static final long MAX_IMAGE_BYTES = 1024L * 1024;

	private static final Set<String> INLINE_IMAGE_TYPES = new HashSet<>(
			Arrays.asList("image/png", "image/jpeg", "image/gif", "image/webp"));

	private static final Set<String> TEXT_TYPES = new HashSet<>(Arrays.asList("application/json", "application/xml",
			"application/javascript", "application/x-javascript", "application/ecmascript", "application/yaml",
			"application/x-yaml", "application/graphql", "application/sql", "application/x-sh",
			"application/x-groovy", "application/toml", "image/svg+xml"));

	private static final String CONNECTION_FIELDS = "totalCount pageInfo { hasNextPage endCursor }";

	private ContentTools() {}

	static List<McpTool> all() {
		List<McpTool> tools = new ArrayList<>();
		tools.add(getNode());
		tools.add(listChildren());
		tools.add(readFile());
		tools.add(search());
		tools.add(query());
		tools.add(versionHistory());
		tools.add(writeFile());
		tools.add(createFolder());
		tools.add(setProperties());
		tools.add(moveNode());
		tools.add(copyNode());
		tools.add(deleteNode());
		return tools;
	}

	// ---- read ---------------------------------------------------------------

	private static McpTool getNode() {
		return McpTool.named("get_node").title("Get node")
				.description("Metadata of one file or folder: node type, size, MIME type, who created and last",
						"modified it and when, lock and version state, its custom properties, and for a file how",
						"it is rendered when served over the web (webRender). Does not return file content; use",
						"read_file for that.")
				.required("path", Schema.string("Absolute repository path, e.g. /content/docs/index.md"))
				.handler((arguments, context) -> {
					String path = Args.path(arguments, "path");
					Map<String, Object> data = McpGraphQL.data(context,
							"query($path: String!) { node(path: $path) { " + McpGraphQL.NODE_FIELDS + " downloadUrl "
									+ McpGraphQL.WEB_RENDER_FIELDS + " " + McpGraphQL.PROPERTIES_FIELDS + " } }",
							vars("path", path));
					Map<String, Object> node = McpGraphQL.node(data.get("node"));
					if (node == null) {
						throw notFound(path);
					}
					return McpToolResult.json(node);
				}).build();
	}

	private static McpTool listChildren() {
		return McpTool.named("list_children").title("List folder")
				.description("Child nodes of a folder: path, type, size and last modification. Paged: when hasNextPage is true, call",
						"again with after set to the returned endCursor.")
				.required("path", Schema.string("Absolute repository path of the folder, e.g. /content"))
				.optional("first", Schema.integer("Maximum number of children to return", 50, 1, 200))
				.optional("after", Schema.string("endCursor of the previous page"))
				.handler((arguments, context) -> {
					String path = Args.path(arguments, "path");
					Map<String, Object> data = McpGraphQL.data(context,
							"query($path: String!, $first: Int, $after: String) {"
									+ " children(path: $path, first: $first, after: $after) { " + CONNECTION_FIELDS
									+ " edges { node { " + McpGraphQL.LIST_NODE_FIELDS + " } } } }",
							vars("path", path, "first", Args.optInt(arguments, "first", 50, 1, 200), "after",
									Args.optString(arguments, "after", null)));
					return McpToolResult.json(McpGraphQL.connection(data.get("children")));
				}).build();
	}

	private static McpTool readFile() {
		return McpTool.named("read_file").title("Read file")
				.description("Content of a file. Text is returned as text, decoded with the file's encoding (UTF-8",
						"when none is recorded); long files are paged by character with offset and limit. PNG, JPEG,",
						"GIF and WebP images up to 1 MB are returned as images. Other binary content is described but",
						"not returned. To read an earlier version of a file, pass a frozenNodePath from",
						"version_history.")
				.required("path", Schema.string("Absolute repository path of the file"))
				.optional("offset", Schema.integer("Character offset to start reading at", 0, 0, Integer.MAX_VALUE))
				.optional("limit",
						Schema.integer("Maximum number of characters to return", DEFAULT_READ_CHARS, 1,
								MAX_READ_CHARS))
				.handler(ContentTools::readFile).build();
	}

	private static McpToolResult readFile(JsonObject arguments, McpCallContext context) throws Exception {
		String path = Args.path(arguments, "path");
		int offset = Args.optInt(arguments, "offset", 0, 0, Integer.MAX_VALUE);
		int limit = Args.optInt(arguments, "limit", DEFAULT_READ_CHARS, 1, MAX_READ_CHARS);

		Session session = context.login();
		try {
			if (!session.nodeExists(path)) {
				throw notFound(path);
			}
			Node node = session.getNode(path);

			String mimeType;
			String encoding;
			long size;
			try {
				mimeType = JCRs.getMimeType(node);
				encoding = JCRs.getEncoding(node);
				size = JCRs.getContentLength(node);
			} catch (IllegalArgumentException ex) {
				throw new McpToolException(path + " is a " + node.getPrimaryNodeType().getName()
						+ ", not a file. Use list_children to see what it contains.");
			}
			if (mimeType == null) {
				throw new McpToolException(path + " is a folder. Use list_children to see what it contains.");
			}

			Map<String, Object> info = new LinkedHashMap<>();
			info.put("path", path);
			info.put("mimeType", mimeType);
			if (encoding != null) {
				info.put("encoding", encoding);
			}
			info.put("size", size);

			String type = mimeType.toLowerCase(Locale.ROOT);
			if (INLINE_IMAGE_TYPES.contains(type)) {
				if (size > MAX_IMAGE_BYTES) {
					info.put("note", "The image is larger than " + MAX_IMAGE_BYTES + " bytes and is not returned.");
					return McpToolResult.json(info);
				}
				try (InputStream in = JCRs.getContentAsStream(node)) {
					return McpToolResult.json(info)
							.addImage(Base64.getEncoder().encodeToString(in.readAllBytes()), type);
				}
			}

			if (!isTextType(type) && (isMediaType(type) || !looksLikeText(node, encoding))) {
				info.put("note", "Binary content is not returned.");
				return McpToolResult.json(info);
			}

			StringBuilder text = new StringBuilder();
			boolean truncated;
			try (Reader in = JCRs.getContentAsReader(node)) {
				long toSkip = offset;
				while (toSkip > 0) {
					long skipped = in.skip(toSkip);
					if (skipped <= 0) {
						break;
					}
					toSkip -= skipped;
				}
				char[] buffer = new char[8192];
				while (text.length() < limit) {
					int n = in.read(buffer, 0, Math.min(buffer.length, limit - text.length()));
					if (n == -1) {
						break;
					}
					text.append(buffer, 0, n);
				}
				truncated = (text.length() >= limit) && (in.read() != -1);
			}

			info.put("offset", offset);
			info.put("returnedChars", text.length());
			info.put("truncated", truncated);
			if (truncated) {
				info.put("nextOffset", offset + text.length());
			}
			return McpToolResult.json(info).addText(text.toString());
		} finally {
			session.logout();
		}
	}

	private static McpTool search() {
		return McpTool.named("search").title("Full-text search")
				.description("Full-text search over file content and metadata, most relevant first (score). Limited",
						"to files the signed-in user can read. Paged like list_children.")
				.required("text", Schema.string("Search text"))
				.optional("path", Schema.string("Only search under this folder (default: the whole workspace)"))
				.optional("first", Schema.integer("Maximum number of results to return", 20, 1, 100))
				.optional("after", Schema.string("endCursor of the previous page"))
				.handler((arguments, context) -> {
					String path = Args.normalizePath(Args.optString(arguments, "path", "/"), "path");
					Map<String, Object> data = McpGraphQL.data(context,
							"query($text: String!, $path: String, $first: Int, $after: String) {"
									+ " search(text: $text, path: $path, first: $first, after: $after) { "
									+ CONNECTION_FIELDS + " edges { node { " + McpGraphQL.LIST_NODE_FIELDS
									+ " score } } } }",
							vars("text", Args.string(arguments, "text"), "path", path, "first",
									Args.optInt(arguments, "first", 20, 1, 100), "after",
									Args.optString(arguments, "after", null)));
					return McpToolResult.json(McpGraphQL.connection(data.get("search")));
				}).build();
	}

	private static McpTool query() {
		return McpTool.named("query").title("XPath query")
				.description("Run a JCR XPath query (the only query language this repository supports) and return the",
						"matching nodes. Statements start at /jcr:root. Examples: every file under a folder,",
						"/jcr:root/content//element(*, nt:file); files by name, here every folder descriptor,",
						"/jcr:root/content//element(.web.yml, nt:file); by property,",
						"/jcr:root/content//element(*, nt:file)[@web.template = \"article\"]; by MIME type,",
						"/jcr:root/content//element(*, nt:file)[jcr:like(@jcr:mimeType, \"image/%\")]; full text,",
						"/jcr:root/content//element(*, nt:file)[jcr:contains(., \"invoice\")]. Paged like",
						"list_children.")
				.required("statement", Schema.string("The XPath statement"))
				.optional("first", Schema.integer("Maximum number of results to return", 20, 1, 200))
				.optional("after", Schema.string("endCursor of the previous page"))
				.handler((arguments, context) -> {
					String statement = Args.string(arguments, "statement");

					Map<String, Object> data;
					try {
						data = McpGraphQL.data(context,
								"query($statement: String!, $first: Int, $after: String) {"
										+ " xpath(query: $statement, first: $first, after: $after) { "
										+ CONNECTION_FIELDS + " edges { node { " + McpGraphQL.LIST_NODE_FIELDS
										+ " } } } }",
								vars("statement", statement, "first", Args.optInt(arguments, "first", 20, 1, 200),
										"after", Args.optString(arguments, "after", null)));
					} catch (McpToolException ex) {
						// The query layer reports a statement it cannot parse only as
						// an internal error; say what a valid statement looks like.
						throw new McpToolException("The statement could not be executed (" + ex.getMessage()
								+ "). It must be a JCR XPath statement such as"
								+ " /jcr:root/content//element(*, nt:file)[@name = \"value\"]; JCR-SQL2 and SQL are"
								+ " not supported.");
					}
					return McpToolResult.json(McpGraphQL.connection(data.get("xpath")));
				}).build();
	}

	private static McpTool versionHistory() {
		return McpTool.named("version_history").title("Version history")
				.description("Versions of a versionable node, with who created each and when. Each version has a",
						"frozenNodePath; pass it to read_file to read the content as it was in that version, e.g.",
						"to compare a file with what it contained before it was overwritten.")
				.required("path", Schema.string("Absolute repository path of the node"))
				.handler((arguments, context) -> {
					String path = Args.path(arguments, "path");
					Map<String, Object> data = McpGraphQL.data(context,
							"query($path: String!) { versionHistory(path: $path) { totalCount baseVersion { name }"
									+ " edges { node { name created createdBy frozenNodePath predecessors"
									+ " successors } } } }",
							vars("path", path));
					Object history = data.get("versionHistory");
					if (!(history instanceof Map)) {
						throw new McpToolException(path + " is not under version control, so no earlier content is"
								+ " kept for it.");
					}
					Map<?, ?> map = (Map<?, ?>) history;
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("path", path);
					result.put("totalCount", map.get("totalCount"));
					if (map.get("baseVersion") instanceof Map) {
						result.put("baseVersion", ((Map<?, ?>) map.get("baseVersion")).get("name"));
					}
					List<Object> versions = new ArrayList<>();
					if (map.get("edges") instanceof List) {
						for (Object edge : (List<?>) map.get("edges")) {
							if (edge instanceof Map) {
								versions.add(((Map<?, ?>) edge).get("node"));
							}
						}
					}
					result.put("versions", versions);
					return McpToolResult.json(result);
				}).build();
	}

	// ---- write --------------------------------------------------------------

	private static McpTool writeFile() {
		return McpTool.named("write_file").title("Write file").destructive()
				.description("Create a file, or replace the content of an existing one. Fails if a file already",
						"exists at the path unless overwrite is true. Before overwriting, read the file and carry",
						"over everything you were not asked to change: the whole content is replaced, not merged.",
						"Give exactly one of content (text) or contentBase64 (binary).")
				.required("path", Schema.string("Absolute repository path of the file, including its name"))
				.optional("content", Schema.string("The file content as text"))
				.optional("contentBase64", Schema.string("The file content as Base64, for binary files"))
				.optional("mimeType",
						Schema.string("MIME type. Default: derived from the file name for a new file, kept as it"
								+ " is for an existing one"))
				.optional("overwrite", Schema.bool("Replace the content if the file already exists", false))
				.optional("createParents", Schema.bool("Create missing parent folders", true))
				.handler(ContentTools::writeFile).build();
	}

	private static McpToolResult writeFile(JsonObject arguments, McpCallContext context) throws Exception {
		String path = Args.path(arguments, "path");
		String content = Args.optString(arguments, "content", null);
		String contentBase64 = Args.optString(arguments, "contentBase64", null);
		String mimeType = Args.optString(arguments, "mimeType", null);
		boolean overwrite = Args.optBoolean(arguments, "overwrite", false);
		boolean createParents = Args.optBoolean(arguments, "createParents", true);

		if ((content == null) == (contentBase64 == null)) {
			throw new McpToolException("Give exactly one of content or contentBase64.");
		}
		if (path.equals("/")) {
			throw new McpToolException("path must name a file.");
		}
		byte[] binary = null;
		if (contentBase64 != null) {
			try {
				binary = Base64.getDecoder().decode(contentBase64);
			} catch (IllegalArgumentException ex) {
				throw new McpToolException("contentBase64 is not valid Base64.");
			}
		}

		Session session = context.login();
		try {
			Map<String, Object> result = new LinkedHashMap<>();
			Node file;
			boolean exists = session.nodeExists(path);
			if (exists) {
				file = session.getNode(path);
				if (!JCRs.isFile(file)) {
					throw new McpToolException(path + " exists and is a " + file.getPrimaryNodeType().getName()
							+ ", not a file.");
				}
				Map<String, Object> previous = new LinkedHashMap<>();
				previous.put("size", JCRs.getContentLength(file));
				previous.put("modified", iso(JCRs.getLastModified(file)));
				previous.put("modifiedBy", JCRs.getLastModifiedBy(file));
				if (!overwrite) {
					throw new McpToolException("A file already exists at " + path + " (" + previous.get("size")
							+ " bytes, last modified " + previous.get("modified") + " by "
							+ previous.get("modifiedBy") + "). Nothing was written. If it should be replaced, read"
							+ " it with read_file first, then call write_file again with overwrite set to true.");
				}
				result.put("replaced", previous);
			} else {
				String parentPath = Args.parentOf(path);
				if (!session.nodeExists(parentPath)) {
					if (!createParents) {
						throw new McpToolException("The parent folder " + parentPath + " does not exist.");
					}
					JCRs.getOrCreateFolder(JcrPath.valueOf(parentPath), session);
				}
				file = JCRs.createFile(session.getNode(parentPath), Args.nameOf(path));
			}

			byte[] bytes;
			if (content != null) {
				// Text replaces text in the encoding the file is already read with.
				Charset charset = StandardCharsets.UTF_8;
				if (exists) {
					String encoding = JCRs.getEncoding(file);
					if (encoding != null && !encoding.isEmpty() && Charset.isSupported(encoding)) {
						charset = Charset.forName(encoding);
					}
				}
				bytes = content.getBytes(charset);
			} else {
				bytes = binary;
			}

			try (InputStream in = new ByteArrayInputStream(bytes)) {
				JCRs.write(file, in);
			}
			Node contentNode = JCRs.getContentNode(file);
			if (mimeType == null && !exists) {
				mimeType = context.probeMimeType(Args.nameOf(path));
				if (mimeType == null || mimeType.isEmpty()) {
					mimeType = "application/octet-stream";
				}
			}
			if (mimeType != null) {
				contentNode.setProperty("jcr:mimeType", mimeType);
			}
			session.save();

			CmsService.getLogger(ContentTools.class).info("MCP write_file: user=" + context.getUserId()
					+ " workspace=" + context.getWorkspaceName() + " path=" + path + " bytes=" + bytes.length
					+ (exists ? " (replaced)" : " (created)"));

			result.put("path", path);
			result.put("created", !exists);
			result.put("size", bytes.length);
			result.put("mimeType", JCRs.getMimeType(session.getNode(path)));
			return McpToolResult.json(result);
		} finally {
			session.logout();
		}
	}

	private static McpTool createFolder() {
		return McpTool.named("create_folder").title("Create folder").write().idempotent()
				.description("Create a folder. Succeeds without change when the folder already exists.")
				.required("path", Schema.string("Absolute repository path of the folder to create"))
				.optional("createParents", Schema.bool("Create missing parent folders", true))
				.handler((arguments, context) -> {
					String path = Args.path(arguments, "path");
					if (path.equals("/")) {
						throw new McpToolException("path must name a folder to create.");
					}
					Map<String, Object> existing = McpGraphQL.node(McpGraphQL.data(context,
							"query($path: String!) { node(path: $path) { " + McpGraphQL.NODE_FIELDS + " } }",
							vars("path", path)).get("node"));
					if (existing != null) {
						if (!"nt:folder".equals(existing.get("nodeType"))) {
							throw new McpToolException(path + " exists and is a " + existing.get("nodeType")
									+ ", not a folder.");
						}
						existing.put("created", false);
						return McpToolResult.json(existing);
					}

					Map<String, Object> input = vars("path", Args.parentOf(path), "name", Args.nameOf(path),
							"createParents", Args.optBoolean(arguments, "createParents", true));
					Map<String, Object> data = McpGraphQL.data(context,
							"mutation($input: CreateFolderInput!) { createFolder(input: $input) { "
									+ McpGraphQL.NODE_FIELDS + " } }",
							vars("input", input));
					log(context, "create_folder", path);
					Map<String, Object> node = McpGraphQL.node(data.get("createFolder"));
					node.put("created", true);
					return McpToolResult.json(node);
				}).build();
	}

	private static McpTool setProperties() {
		return McpTool.named("set_properties").title("Set properties").write().idempotent()
				.description("Set or delete custom properties of a node (for a file they are stored on its",
						"jcr:content). All changes are applied together or not at all. A string, number, boolean or",
						"an array of one of those sets a property of that type; null deletes it. For other JCR",
						"types pass a typed value object such as {\"dateValue\": \"2026-10-01T00:00:00Z\"} or",
						"{\"pathValue\": \"/content/x\"}. Example: {\"web.template\": \"article\"} binds one file to",
						"a template.")
				.required("path", Schema.string("Absolute repository path of the node"))
				.required("properties", Schema.object("Property names mapped to their new values (null to delete)"))
				.handler((arguments, context) -> {
					String path = Args.path(arguments, "path");
					JsonElement element = arguments.get("properties");
					if (element == null || !element.isJsonObject() || element.getAsJsonObject().size() == 0) {
						throw new McpToolException("properties must be an object with at least one property.");
					}
					List<Object> properties = new ArrayList<>();
					for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
						properties.add(vars("name", e.getKey(), "value", propertyValueInput(e.getKey(), e.getValue())));
					}

					Map<String, Object> data = McpGraphQL.data(context,
							"mutation($input: SetPropertiesInput!) { setProperties(input: $input) {"
									+ " node { path " + McpGraphQL.PROPERTIES_FIELDS + " }"
									+ " errors { propertyName message } } }",
							vars("input", vars("path", path, "properties", properties)));
					Map<?, ?> result = (Map<?, ?>) data.get("setProperties");
					Object errors = result.get("errors");
					if (errors instanceof List && !((List<?>) errors).isEmpty()) {
						List<String> messages = new ArrayList<>();
						for (Object error : (List<?>) errors) {
							Map<?, ?> map = (Map<?, ?>) error;
							messages.add((map.get("propertyName") != null ? map.get("propertyName") + ": " : "")
									+ map.get("message"));
						}
						throw new McpToolException(
								"No property was changed. " + String.join("; ", messages));
					}
					log(context, "set_properties", path + " " + element.getAsJsonObject().keySet());
					return McpToolResult.json(McpGraphQL.node(result.get("node")));
				}).build();
	}

	private static McpTool moveNode() {
		return McpTool.named("move_node").title("Move or rename").write()
				.description("Move a file or folder into another folder, rename it, or both. To rename in place,",
						"give the current parent folder as destPath and the new name.")
				.required("sourcePath", Schema.string("Absolute repository path of the node to move"))
				.required("destPath", Schema.string("Absolute repository path of the destination folder"))
				.optional("name", Schema.string("New name (default: keep the current name)"))
				.handler((arguments, context) -> relocate(arguments, context, "moveNode", "MoveNodeInput",
						"move_node"))
				.build();
	}

	private static McpTool copyNode() {
		return McpTool.named("copy_node").title("Copy").write()
				.description("Copy a file or folder (with everything under it) into another folder, optionally",
						"under a new name. Version history is not copied.")
				.required("sourcePath", Schema.string("Absolute repository path of the node to copy"))
				.required("destPath", Schema.string("Absolute repository path of the destination folder"))
				.optional("name", Schema.string("Name of the copy (default: the source name)"))
				.handler((arguments, context) -> relocate(arguments, context, "copyNode", "CopyNodeInput",
						"copy_node"))
				.build();
	}

	private static McpToolResult relocate(JsonObject arguments, McpCallContext context, String field,
			String inputType, String toolName) throws Exception {
		String sourcePath = Args.path(arguments, "sourcePath");
		String destPath = Args.path(arguments, "destPath");
		String name = Args.optString(arguments, "name", null);
		if (name != null && (name.isEmpty() || name.contains("/"))) {
			throw new McpToolException("name must be a single path segment.");
		}
		Map<String, Object> data = McpGraphQL.data(context,
				"mutation($input: " + inputType + "!) { " + field + "(input: $input) { " + McpGraphQL.NODE_FIELDS
						+ " } }",
				vars("input", vars("sourcePath", sourcePath, "destPath", destPath, "name", name)));
		log(context, toolName, sourcePath + " -> " + destPath + (name != null ? "/" + name : ""));
		return McpToolResult.json(McpGraphQL.node(data.get(field)));
	}

	private static McpTool deleteNode() {
		return McpTool.named("delete_node").title("Delete").destructive().idempotent()
				.description("Permanently delete a file, or a folder and everything under it. There is no trash:",
						"this cannot be undone. Confirm with the user before deleting anything they did not",
						"explicitly name.")
				.required("path", Schema.string("Absolute repository path of the node to delete"))
				.handler((arguments, context) -> {
					String path = Args.path(arguments, "path");
					if (path.equals("/")) {
						throw new McpToolException("The root node cannot be deleted.");
					}
					Map<String, Object> data = McpGraphQL.data(context,
							"mutation($input: DeleteNodeInput!) { deleteNode(input: $input) }",
							vars("input", vars("path", path)));
					boolean deleted = Boolean.TRUE.equals(data.get("deleteNode"));
					if (deleted) {
						log(context, "delete_node", path);
					}
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("path", path);
					result.put("deleted", deleted);
					if (!deleted) {
						result.put("note", "Nothing was deleted: there is no node at this path.");
					}
					return McpToolResult.json(result);
				}).build();
	}

	// ---- helpers ------------------------------------------------------------

	/**
	 * Maps a plain JSON value to the GraphQL {@code PropertyValueInput} of the
	 * matching JCR type. An object is passed through as an already typed input.
	 */
	static Object propertyValueInput(String name, JsonElement value) throws McpToolException {
		if (value == null || value.isJsonNull()) {
			return null;
		}
		if (value.isJsonObject()) {
			if (value.getAsJsonObject().size() != 1) {
				throw new McpToolException("Property " + name
						+ ": a typed value object must have exactly one key, e.g. {\"dateValue\": \"...\"}.");
			}
			return toJava(value);
		}
		if (value.isJsonPrimitive()) {
			JsonPrimitive primitive = value.getAsJsonPrimitive();
			if (primitive.isBoolean()) {
				return vars("booleanValue", primitive.getAsBoolean());
			}
			if (primitive.isNumber()) {
				Object number = toJava(primitive);
				return vars((number instanceof Long) ? "longValue" : "doubleValue", number);
			}
			return vars("stringValue", primitive.getAsString());
		}

		JsonArray array = value.getAsJsonArray();
		List<Object> items = new ArrayList<>();
		String kind = null;
		for (JsonElement item : array) {
			if (!item.isJsonPrimitive()) {
				throw new McpToolException("Property " + name + ": array items must be strings, numbers or"
						+ " booleans.");
			}
			JsonPrimitive primitive = item.getAsJsonPrimitive();
			Object converted = toJava(primitive);
			String itemKind = primitive.isBoolean() ? "boolean"
					: primitive.isNumber() ? ((converted instanceof Long) ? "long" : "double") : "string";
			if (kind == null) {
				kind = itemKind;
			} else if (!kind.equals(itemKind)) {
				if ((kind.equals("long") && itemKind.equals("double"))
						|| (kind.equals("double") && itemKind.equals("long"))) {
					kind = "double";
				} else {
					throw new McpToolException("Property " + name + ": array items must all be of one type.");
				}
			}
			items.add(converted);
		}
		if (kind == null) {
			kind = "string";
		}
		if (kind.equals("double")) {
			List<Object> doubles = new ArrayList<>();
			for (Object item : items) {
				doubles.add(((Number) item).doubleValue());
			}
			items = doubles;
		}
		return vars(kind + "ArrayValue", items);
	}

	/** Converts a JSON tree to plain Java values; integral numbers become {@code Long}. */
	static Object toJava(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonObject()) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
				map.put(e.getKey(), toJava(e.getValue()));
			}
			return map;
		}
		if (element.isJsonArray()) {
			List<Object> list = new ArrayList<>();
			for (JsonElement item : element.getAsJsonArray()) {
				list.add(toJava(item));
			}
			return list;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) {
			return primitive.getAsBoolean();
		}
		if (primitive.isNumber()) {
			double number = primitive.getAsDouble();
			if (number == Math.rint(number) && Math.abs(number) < 9.0e15) {
				return (long) number;
			}
			return number;
		}
		return primitive.getAsString();
	}

	/** A variables map from alternating keys and values; null values are kept. */
	static Map<String, Object> vars(Object... keysAndValues) {
		Map<String, Object> map = new HashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	static McpToolException notFound(String path) {
		return new McpToolException("There is no node at " + path
				+ ", or the signed-in user is not allowed to read it.");
	}

	private static void log(McpCallContext context, String tool, String detail) {
		CmsService.getLogger(ContentTools.class).info("MCP " + tool + ": user=" + context.getUserId()
				+ " workspace=" + context.getWorkspaceName() + " " + detail);
	}

	private static String iso(java.util.Date date) {
		return (date == null) ? null : date.toInstant().toString();
	}

	private static boolean isTextType(String mimeType) {
		return mimeType.startsWith("text/") || mimeType.endsWith("+json") || mimeType.endsWith("+xml")
				|| TEXT_TYPES.contains(mimeType);
	}

	/** Types that are binary by definition; anything else unrecognised is inspected. */
	private static boolean isMediaType(String mimeType) {
		return mimeType.startsWith("image/") || mimeType.startsWith("audio/") || mimeType.startsWith("video/")
				|| mimeType.startsWith("font/");
	}

	/**
	 * Whether the start of the file reads as text: no NUL byte, and — unless
	 * the file records its own encoding — well-formed UTF-8. MIME types are
	 * assigned from file names and many source formats (templates, scripts,
	 * Markdown) map to types that say nothing about being text, so the content
	 * is the better witness.
	 */
	private static boolean looksLikeText(Node node, String encoding) throws Exception {
		byte[] head;
		try (InputStream in = JCRs.getContentAsStream(node)) {
			head = in.readNBytes(4096);
		}
		for (byte b : head) {
			if (b == 0) {
				return false;
			}
		}
		if (encoding != null && !encoding.isEmpty()) {
			return true;
		}
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		// endOfInput=false: a multi-byte character cut off by the 4096-byte window is not an error.
		return !decoder.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.length + 1), false).isError();
	}

}

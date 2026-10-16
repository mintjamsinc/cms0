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

import static org.mintjams.rt.cms.internal.mcp.ContentTools.vars;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mintjams.rt.cms.internal.mcp.McpTool.Args;
import org.mintjams.rt.cms.internal.mcp.McpTool.Schema;

/**
 * The file tools: editing part of a text file, comparing versions, and
 * finding files by content and by name. (A document's text is read with
 * read_file, which extracts it through the same schema.)
 *
 * <p>Each wraps one operation of the platform's files schema
 * ({@code files-schema.graphqls}), so Webtop and the {@code graphql} tool can
 * do the same, and every rule the operation enforces is enforced once. Like
 * every built-in tool, their names are reserved: a workspace cannot define a
 * tool that replaces one of them.
 */
final class FileTools {

	private static final int DEFAULT_DIFF_CHARS = 100_000;
	private static final int MAX_DIFF_CHARS = 500_000;

	private static final int DEFAULT_MAX_MATCHES = 200;
	private static final int MAX_MATCHES = 1000;
	private static final int DEFAULT_MAX_FILE_SIZE = 4 * 1024 * 1024;
	private static final int MAX_FILE_SIZE = 16 * 1024 * 1024;

	private static final int DEFAULT_FIND_LIMIT = 100;
	private static final int MAX_FIND_LIMIT = 500;

	private FileTools() {}

	static List<McpTool> all() {
		List<McpTool> tools = new ArrayList<>();
		tools.add(diffVersions());
		tools.add(grepFiles());
		tools.add(findFiles());
		tools.add(editFile());
		return tools;
	}

	private static McpTool diffVersions() {
		return McpTool.named("diff_versions").title("Compare versions")
				.description("What changed in a versionable file, as a unified diff with line numbers and three",
						"lines of context. A text file is compared as it is; a PDF, Word, Excel, PowerPoint or other",
						"document by its extracted text (extracted is true then), so what changed in a contract or a",
						"deck reads as changed lines. from and to are version names from version_history; without",
						"to the comparison is with the current content, and without from it starts from the base",
						"(latest) version, so the path alone answers \"what changed since the last check-in\". A",
						"file that is not under version control has no earlier content to compare with.")
				.required("path", Schema.string("Absolute repository path of the file"))
				.optional("from", Schema.string("The version to start from (default: the base version)"))
				.optional("to", Schema.string("The version to compare with (default: the current content)"))
				.optional("limit",
						Schema.integer("Maximum characters of diff to return", DEFAULT_DIFF_CHARS, 1, MAX_DIFF_CHARS))
				.handler((arguments, context) -> {
					Map<String, Object> data = McpGraphQL.data(context,
							"query($path: String!, $from: String, $to: String, $limit: Int) {"
									+ " fileDiff(path: $path, from: $from, to: $to, limit: $limit) {"
									+ " path from fromCreated to toCreated identical extracted added removed hunks"
									+ " totalHunks diff truncated note } }",
							vars("path", Args.path(arguments, "path"), "from", Args.optString(arguments, "from", null),
									"to", Args.optString(arguments, "to", null), "limit",
									Args.optInt(arguments, "limit", DEFAULT_DIFF_CHARS, 1, MAX_DIFF_CHARS)));
					return McpToolResult.json(data.get("fileDiff"));
				}).build();
	}

	private static McpTool grepFiles() {
		return McpTool.named("grep_files").title("Grep files")
				.description("The lines matching a pattern in the text files under a folder, each with its path and",
						"line number, as grep -rn prints them. The pattern is a Java regular expression unless",
						"regex is false. Use it to find where something is used or defined; search finds documents",
						"by their words, this finds the exact place. Files that are not text (PDF and Office",
						"documents, media, other binary content) and files larger than maxFileSize are skipped and",
						"counted. Stops at maxMatches, after looking at 10000 files, or after 20 seconds, and",
						"stoppedBy says which (maxMatches, scanLimit, timeLimit): narrow the folder, the pattern or",
						"namePattern when truncated is true.")
				.required("path", Schema.string("Absolute repository path of the folder to search under"))
				.required("pattern", Schema.string("The regular expression (or literal text when regex is false)"))
				.optional("regex", Schema.bool("Whether pattern is a regular expression", true))
				.optional("ignoreCase", Schema.bool("Match regardless of case", false))
				.optional("namePattern",
						Schema.string("Only files whose name matches this glob, e.g. \"*.md\" (case-insensitive)"))
				.optional("maxMatches",
						Schema.integer("Stop after this many matching lines", DEFAULT_MAX_MATCHES, 1, MAX_MATCHES))
				.optional("maxFileSize", Schema.integer("Skip files larger than this many bytes", DEFAULT_MAX_FILE_SIZE,
						1, MAX_FILE_SIZE))
				.handler((arguments, context) -> {
					Map<String, Object> data = McpGraphQL.data(context,
							"query($path: String!, $pattern: String!, $regex: Boolean, $ignoreCase: Boolean,"
									+ " $namePattern: String, $maxMatches: Int, $maxFileSize: Int) {"
									+ " grepFiles(path: $path, pattern: $pattern, regex: $regex, ignoreCase: $ignoreCase,"
									+ " namePattern: $namePattern, maxMatches: $maxMatches, maxFileSize: $maxFileSize) {"
									+ " path pattern filesScanned filesSkipped filesMatched truncated stoppedBy"
									+ " matches { path line text } } }",
							vars("path", Args.path(arguments, "path"), "pattern", Args.string(arguments, "pattern"),
									"regex", Args.optBoolean(arguments, "regex", true), "ignoreCase",
									Args.optBoolean(arguments, "ignoreCase", false), "namePattern",
									Args.optString(arguments, "namePattern", null), "maxMatches",
									Args.optInt(arguments, "maxMatches", DEFAULT_MAX_MATCHES, 1, MAX_MATCHES),
									"maxFileSize", Args.optInt(arguments, "maxFileSize", DEFAULT_MAX_FILE_SIZE, 1,
											MAX_FILE_SIZE)));
					return McpToolResult.json(data.get("grepFiles"));
				}).build();
	}

	private static McpTool findFiles() {
		return McpTool.named("find_files").title("Find files")
				.description("The files (or folders, or both) under a folder whose name matches a glob such as",
						"\"*.md\" or \"index.*\" (* any run, ? one character, case-insensitive), with size, type and",
						"last modification. To see what changed recently, set modifiedSince (and modifiedBy for one",
						"person's changes) with orderBy modified, newest first. Paged like list_children: when",
						"hasNextPage is true, call again with after set to the returned endCursor. The search stops",
						"after looking at 50000 nodes or after 20 seconds, and stoppedBy says which (scanLimit,",
						"timeLimit): narrow the folder or the pattern then. To list one folder's children, use",
						"list_children; to find a file by an exact name, query can do it in one step.")
				.required("path", Schema.string("Absolute repository path of the folder to search under"))
				.optional("namePattern", Schema.string("The glob the name has to match, e.g. \"*.md\" (default: every"
						+ " name)"))
				.optional("type", Schema.choice("What to find", "file", "file", "folder", "any"))
				.optional("modifiedSince", Schema.string("Only what was modified at or after this ISO-8601 instant,"
						+ " e.g. 2026-10-01T00:00:00Z"))
				.optional("modifiedBy", Schema.string("Only what this user id modified last"))
				.optional("orderBy", Schema.choice("found: the order found (stops early); modified: newest first",
						"found", "found", "modified"))
				.optional("first",
						Schema.integer("Maximum number of nodes to return", DEFAULT_FIND_LIMIT, 1, MAX_FIND_LIMIT))
				.optional("after", Schema.string("endCursor of the previous page"))
				.handler((arguments, context) -> {
					Map<String, Object> data = McpGraphQL.data(context,
							"query($path: String!, $namePattern: String, $type: String, $modifiedSince: String,"
									+ " $modifiedBy: String, $orderBy: String, $offset: Int, $limit: Int) {"
									+ " findFiles(path: $path, namePattern: $namePattern, type: $type,"
									+ " modifiedSince: $modifiedSince, modifiedBy: $modifiedBy, orderBy: $orderBy,"
									+ " offset: $offset, limit: $limit) { scanned nextOffset stoppedBy"
									+ " nodes { path nodeType size mimeType modified modifiedBy } } }",
							vars("path", Args.path(arguments, "path"), "namePattern",
									Args.optString(arguments, "namePattern", "*"), "type",
									Args.optString(arguments, "type", "file"), "modifiedSince",
									Args.optString(arguments, "modifiedSince", null), "modifiedBy",
									Args.optString(arguments, "modifiedBy", null), "orderBy",
									"modified".equals(Args.optString(arguments, "orderBy", "found")) ? "modified" : null,
									"offset", offsetOf(Args.optString(arguments, "after", null)), "limit",
									Args.optInt(arguments, "first", DEFAULT_FIND_LIMIT, 1, MAX_FIND_LIMIT)));
					// The listing shape every paged tool shares: nodes, hasNextPage, endCursor.
					Map<?, ?> found = (Map<?, ?>) data.get("findFiles");
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("nodes", found.get("nodes"));
					Object nextOffset = found.get("nextOffset");
					result.put("hasNextPage", nextOffset != null);
					if (nextOffset != null) {
						result.put("endCursor", cursorOf(((Number) nextOffset).intValue()));
					}
					result.put("scanned", found.get("scanned"));
					if (found.get("stoppedBy") != null) {
						result.put("stoppedBy", found.get("stoppedBy"));
					}
					return McpToolResult.json(result);
				}).build();
	}

	private static final String CURSOR_PREFIX = "find:";

	/** An opaque cursor for the page that starts at {@code offset}. */
	private static String cursorOf(int offset) {
		return Base64.getEncoder().encodeToString((CURSOR_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
	}

	/** The offset a cursor from {@link #cursorOf} stands for; 0 without one. */
	private static int offsetOf(String cursor) throws McpToolException {
		if (cursor == null || cursor.isEmpty()) {
			return 0;
		}
		try {
			String decoded = new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
			if (decoded.startsWith(CURSOR_PREFIX)) {
				int offset = Integer.parseInt(decoded.substring(CURSOR_PREFIX.length()));
				if (offset >= 0) {
					return offset;
				}
			}
		} catch (IllegalArgumentException ignore) {}
		throw new McpToolException("after is not an endCursor that find_files returned.");
	}

	private static McpTool editFile() {
		return McpTool.named("edit_file").title("Edit file").destructive()
				.description("Replaces oldText with newText in a text file and leaves the rest of the file as it is:",
						"the way to change part of a file without rewriting it. oldText has to match the file",
						"exactly, whitespace and indentation included, and exactly once: read the file first and copy",
						"the text from it, with enough surrounding lines to make it unique, or set replaceAll to",
						"replace every occurrence. Line endings are matched to the file's. An empty newText deletes",
						"the matched text. The file keeps its encoding and MIME type. Only text files are edited: a",
						"PDF or Office document is refused, since its text as read_file shows it is extracted, not",
						"its bytes. Pass the modified that read_file returned as ifModified: the edit is then refused",
						"if someone changed the file after you read it. To create a file or replace its whole",
						"content, use write_file.")
				.required("path", Schema.string("Absolute repository path of the file"))
				.required("oldText", Schema.string("The text to replace, exactly as it is in the file"))
				.required("newText", Schema.string("The text to put in its place; empty to delete"))
				.optional("replaceAll",
						Schema.bool("Replace every occurrence instead of requiring a unique match", false))
				.optional("ifModified",
						Schema.string("The modified time read_file returned: refuse the edit if the file has"
								+ " changed since"))
				.handler((arguments, context) -> {
					String path = Args.path(arguments, "path");
					String oldText = Args.string(arguments, "oldText");
					String newText = Args.optString(arguments, "newText", null);
					if (newText == null) {
						throw new McpToolException("Missing required argument: newText (an empty string deletes"
								+ " oldText).");
					}
					Map<String, Object> data = McpGraphQL.data(context,
							"mutation($path: String!, $oldText: String!, $newText: String!, $replaceAll: Boolean,"
									+ " $ifModified: String) { editFile(path: $path, oldText: $oldText, newText: $newText,"
									+ " replaceAll: $replaceAll, ifModified: $ifModified) { path replacements line size"
									+ " modified modifiedBy } }",
							vars("path", path, "oldText", oldText, "newText", newText, "replaceAll",
									Args.optBoolean(arguments, "replaceAll", false), "ifModified",
									Args.optString(arguments, "ifModified", null)));
					Object result = data.get("editFile");
					Object replacements = (result instanceof Map) ? ((Map<?, ?>) result).get("replacements") : null;
					ContentTools.log(context, "edit_file", path + " replacements=" + replacements);
					return McpToolResult.json(result);
				}).build();
	}

}

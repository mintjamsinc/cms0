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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mintjams.rt.cms.internal.mcp.McpTool.Args;
import org.mintjams.rt.cms.internal.mcp.McpTool.Schema;

/**
 * The file tools: reading a document's text, editing part of a text file,
 * comparing versions, and finding files by content and by name.
 *
 * <p>Each wraps one operation of the platform's files schema
 * ({@code files-schema.graphqls}), so Webtop and the {@code graphql} tool can
 * do the same, and every rule the operation enforces is enforced once. Like
 * every built-in tool, their names are reserved: a workspace cannot define a
 * tool that replaces one of them.
 */
final class FileTools {

	/** Characters of text read_document returns by default, and at most, per call. */
	private static final int DEFAULT_DOCUMENT_CHARS = 50_000;
	private static final int MAX_DOCUMENT_CHARS = 500_000;

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
		tools.add(readDocument());
		tools.add(diffVersions());
		tools.add(grepFiles());
		tools.add(findFiles());
		tools.add(editFile());
		return tools;
	}

	private static McpTool readDocument() {
		return McpTool.named("read_document").title("Read document")
				.description("The text of a document: PDF, Word, Excel, PowerPoint (old and new formats),",
						"OpenDocument, RTF, EPUB, e-mail and other formats whose text can be extracted, and text",
						"files as they are. read_file returns the same text for a document; this tool never returns",
						"an image. Paged by character: when truncated is true, call again with offset set to",
						"nextOffset. Title, author and page count are returned when the document records them.",
						"Images, audio and video have no text; read_file returns images.")
				.required("path", Schema.string("Absolute repository path of the file"))
				.optional("offset", Schema.integer("Character offset to start reading at", 0, 0, Integer.MAX_VALUE))
				.optional("limit", Schema.integer("Maximum number of characters to return", DEFAULT_DOCUMENT_CHARS, 1,
						MAX_DOCUMENT_CHARS))
				.handler((arguments, context) -> {
					String path = Args.path(arguments, "path");
					int offset = Args.optInt(arguments, "offset", 0, 0, Integer.MAX_VALUE);
					int limit = Args.optInt(arguments, "limit", DEFAULT_DOCUMENT_CHARS, 1, MAX_DOCUMENT_CHARS);
					Map<String, Object> info = new LinkedHashMap<>();
					info.put("path", path);
					return ContentTools.documentText(context, path, offset, limit, info);
				}).build();
	}

	private static McpTool diffVersions() {
		return McpTool.named("diff_versions").title("Compare versions")
				.description("What changed in a versionable text file, as a unified diff with line numbers and",
						"three lines of context. from and to are version names from version_history; without to",
						"the comparison is with the current content, and without from it starts from the base",
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
									+ " path from fromCreated to toCreated identical added removed hunks totalHunks"
									+ " diff truncated } }",
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
						"counted. Stops at maxMatches: narrow the folder, the pattern or namePattern when truncated",
						"is true.")
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
									+ " path pattern filesScanned filesSkipped filesMatched truncated"
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
						"last modification. Paged with offset and limit: when truncated is true, call again with",
						"offset set to nextOffset. To list one folder's children, use list_children; to find a file",
						"by an exact name, query can do it in one step.")
				.required("path", Schema.string("Absolute repository path of the folder to search under"))
				.required("namePattern", Schema.string("The glob the name has to match, e.g. \"*.md\""))
				.optional("type", Schema.choice("What to find", "file", "file", "folder", "any"))
				.optional("offset", Schema.integer("Matches to skip", 0, 0, Integer.MAX_VALUE))
				.optional("limit",
						Schema.integer("Maximum number of nodes to return", DEFAULT_FIND_LIMIT, 1, MAX_FIND_LIMIT))
				.handler((arguments, context) -> {
					Map<String, Object> data = McpGraphQL.data(context,
							"query($path: String!, $namePattern: String!, $type: String, $offset: Int, $limit: Int) {"
									+ " findFiles(path: $path, namePattern: $namePattern, type: $type, offset: $offset,"
									+ " limit: $limit) { path namePattern scanned offset truncated nextOffset"
									+ " nodes { path nodeType size mimeType modified modifiedBy } } }",
							vars("path", Args.path(arguments, "path"), "namePattern",
									Args.string(arguments, "namePattern"), "type",
									Args.optString(arguments, "type", "file"), "offset",
									Args.optInt(arguments, "offset", 0, 0, Integer.MAX_VALUE), "limit",
									Args.optInt(arguments, "limit", DEFAULT_FIND_LIMIT, 1, MAX_FIND_LIMIT)));
					return McpToolResult.json(data.get("findFiles"));
				}).build();
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
						"its bytes. To create a file or replace its whole content, use write_file.")
				.required("path", Schema.string("Absolute repository path of the file"))
				.required("oldText", Schema.string("The text to replace, exactly as it is in the file"))
				.required("newText", Schema.string("The text to put in its place; empty to delete"))
				.optional("replaceAll",
						Schema.bool("Replace every occurrence instead of requiring a unique match", false))
				.handler((arguments, context) -> {
					String path = Args.path(arguments, "path");
					String oldText = Args.string(arguments, "oldText");
					String newText = Args.optString(arguments, "newText", null);
					if (newText == null) {
						throw new McpToolException("Missing required argument: newText (an empty string deletes"
								+ " oldText).");
					}
					Map<String, Object> data = McpGraphQL.data(context,
							"mutation($path: String!, $oldText: String!, $newText: String!, $replaceAll: Boolean) {"
									+ " editFile(path: $path, oldText: $oldText, newText: $newText,"
									+ " replaceAll: $replaceAll) { path replacements line size modified modifiedBy } }",
							vars("path", path, "oldText", oldText, "newText", newText, "replaceAll",
									Args.optBoolean(arguments, "replaceAll", false)));
					Object result = data.get("editFile");
					Object replacements = (result instanceof Map) ? ((Map<?, ?>) result).get("replacements") : null;
					ContentTools.log(context, "edit_file", path + " replacements=" + replacements);
					return McpToolResult.json(result);
				}).build();
	}

}

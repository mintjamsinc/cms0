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

package org.mintjams.rt.cms.internal.graphql.wiring;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.lock.LockException;
import javax.jcr.query.Query;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionManager;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.files.Deadline;
import org.mintjams.rt.cms.internal.files.DocumentTexts;
import org.mintjams.rt.cms.internal.files.FileKind;
import org.mintjams.rt.cms.internal.files.IfModified;
import org.mintjams.rt.cms.internal.files.TextDiff;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;
import org.mintjams.rt.cms.internal.script.resource.JcrAction;
import org.mintjams.rt.cms.internal.util.ISO8601;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

/**
 * Wires the files part of the platform schema ({@code files-schema.graphqls}):
 * reading the text of a document, editing part of a text file, comparing
 * versions, and finding files by content and by name.
 *
 * <p>These are the operations behind the MCP tools {@code edit_file},
 * {@code diff_versions}, {@code grep_files} and {@code find_files}, and
 * {@code read_file} reads documents through {@code documentText}. They are
 * part of the platform, not of a workspace:
 * every installation has the same ones, and a workspace cannot replace them.
 *
 * <p>Every operation runs in the caller's session. Whether a file is text, a
 * document or media is {@link FileKind}'s to say, for all of them.
 */
public final class PlatformFilesWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/files-schema.graphqls";

	// Every operation is bounded: in what it returns, in what it reads, and in
	// how long it runs. A request thread cannot be stopped from outside, so a
	// limit that is reached ends the work with what it has, and the result
	// says which limit it was. A larger argument is lowered to the limit.

	private static final int DEFAULT_TEXT_LIMIT = 50_000;
	/** Characters of text documentText returns at most per call. */
	private static final int MAX_TEXT_LIMIT = 500_000;
	/** How long extracting a document's text may take per call. */
	private static final long EXTRACT_MILLIS = 30_000;

	/** The largest file editFile changes in place, and the largest content fileDiff compares. */
	private static final long MAX_TEXT_FILE_BYTES = 4L * 1024 * 1024;

	private static final int DEFAULT_DIFF_LIMIT = 100_000;
	private static final int MAX_DIFF_LIMIT = 500_000;
	/** Characters of a document's text fileDiff compares, a side (what the search index reads of it too). */
	private static final int MAX_DOCUMENT_DIFF_CHARS = 1_000_000;

	private static final int DEFAULT_MAX_MATCHES = 200;
	private static final int MAX_MATCHES = 1000;
	private static final int DEFAULT_MAX_FILE_SIZE = 4 * 1024 * 1024;
	private static final int MAX_FILE_SIZE = 16 * 1024 * 1024;
	/** Files grepFiles looks at per call. */
	private static final int GREP_SCAN_LIMIT = 10_000;

	private static final int DEFAULT_FIND_LIMIT = 100;
	private static final int MAX_FIND_LIMIT = 500;
	/** Files and folders findFiles looks at per call. */
	private static final int FIND_SCAN_LIMIT = 50_000;

	/** How long a search over a folder (grepFiles, findFiles) may take per call. */
	private static final long SEARCH_MILLIS = 20_000;

	/** Why a search stopped before it covered everything (the {@code stoppedBy} of its result). */
	private static final String STOPPED_BY_MATCHES = "maxMatches";
	private static final String STOPPED_BY_SCAN = "scanLimit";
	private static final String STOPPED_BY_TIME = "timeLimit";

	private static final int MAX_LINE_CHARS = 500;
	/** Nodes a query hands back per round, while a search walks its results. */
	private static final int QUERY_PAGE = 500;

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "documentText", (DataFetcher<Object>) PlatformFilesWiringContributor::documentText)
				.dataFetcher("Query", "fileDiff", (DataFetcher<Object>) PlatformFilesWiringContributor::fileDiff)
				.dataFetcher("Query", "grepFiles", (DataFetcher<Object>) PlatformFilesWiringContributor::grepFiles)
				.dataFetcher("Query", "findFiles", (DataFetcher<Object>) PlatformFilesWiringContributor::findFiles)
				.dataFetcher("Mutation", "editFile", (DataFetcher<Object>) PlatformFilesWiringContributor::editFile);
	}

	// ---- documentText --------------------------------------------------------

	/**
	 * {@code Query.documentText(path, offset, limit)}: a page of a text file
	 * as it is, or of the text Apache Tika extracts from anything else that is
	 * not media.
	 */
	private static Object documentText(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = absolutePath(environment.getArgument("path"));
		int offset = intArgument(environment, "offset", 0);
		int limit = Math.min(intArgument(environment, "limit", DEFAULT_TEXT_LIMIT), MAX_TEXT_LIMIT);
		if (offset < 0) {
			throw new IllegalArgumentException("offset must be 0 or more.");
		}
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be 1 or more.");
		}
		Node file = file(session, path);
		String mimeType = JCRs.getMimeType(file);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("path", path);
		result.put("mimeType", mimeType);
		result.put("size", JCRs.getContentLength(file));
		result.put("modified", ISO8601.format(JCRs.getLastModified(file)));
		result.put("extracted", false);
		result.put("offset", offset);

		FileKind kind = FileKind.of(file);
		if (kind == FileKind.MEDIA) {
			throw new IllegalArgumentException(
					path + " is " + mimeType + ": it has no text to extract. Images are returned by read_file.");
		}
		DocumentTexts.Page page;
		boolean timedOut = false;
		if (kind == FileKind.TEXT) {
			page = DocumentTexts.readText(file, offset, limit);
		} else {
			DocumentTexts.Extracted extracted = DocumentTexts.extract(file, path, offset, limit,
					Deadline.in(EXTRACT_MILLIS));
			page = extracted.getPage();
			timedOut = extracted.isTimedOut();
			result.put("extracted", true);
			result.put("detectedType", extracted.getDetectedType());
			result.put("title", extracted.getTitle());
			result.put("author", extracted.getAuthor());
			result.put("pages", extracted.getPages());
			if (timedOut) {
				// Every call parses from the start, so a later offset would stop
				// at the same place: there is no next page to offer.
				result.put("note", "Extraction stopped after " + (EXTRACT_MILLIS / 1000) + " s, at character "
						+ (offset + page.getText().length()) + " of the text: the rest of this document cannot be"
						+ " extracted within the time a call may take.");
			} else if (extracted.isEmpty()) {
				result.put("note", (offset == 0)
						? "No text could be extracted: the document may hold only images (a scanned PDF, for example)."
						: "There is no text at or after this offset.");
			}
		}
		result.put("text", page.getText());
		result.put("truncated", page.isTruncated());
		if (page.isTruncated() && !timedOut) {
			result.put("nextOffset", offset + page.getText().length());
		}
		return result;
	}

	// ---- editFile ------------------------------------------------------------

	/**
	 * {@code Mutation.editFile(path, oldText, newText, replaceAll)}: replaces
	 * one exact piece of a text file and leaves the rest as it is. A vague
	 * {@code oldText} — one that matches nowhere, or in several places —
	 * changes nothing rather than the wrong place.
	 */
	private static Object editFile(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = absolutePath(environment.getArgument("path"));
		String oldText = environment.getArgument("oldText");
		String newText = environment.getArgument("newText");
		if (newText == null) {
			newText = "";
		}
		boolean replaceAll = Boolean.TRUE.equals(environment.getArgument("replaceAll"));
		if (oldText == null || oldText.isEmpty()) {
			throw new IllegalArgumentException(
					"oldText must not be empty. To create a file or replace its whole content, use write_file.");
		}
		if (oldText.equals(newText)) {
			throw new IllegalArgumentException("newText is the same as oldText: nothing to change.");
		}

		Node file = file(session, path);
		if (!JCRs.isFile(file)) {
			throw new IllegalArgumentException(path + " is not a file that can be changed (it is a "
					+ file.getPrimaryNodeType().getName() + ").");
		}
		FileKind kind = FileKind.of(file);
		if (kind != FileKind.TEXT) {
			throw new IllegalArgumentException(path + " is " + describe(kind, JCRs.getMimeType(file))
					+ ", not a text file: changing its bytes as text would corrupt it. Nothing was changed. To replace"
					+ " it as a whole, use write_file.");
		}
		long size = JCRs.getContentLength(file);
		if (size > MAX_TEXT_FILE_BYTES) {
			throw new IllegalArgumentException(path + " is " + size + " bytes, more than the " + MAX_TEXT_FILE_BYTES
					+ " bytes a file can be edited in place. Nothing was changed. To replace it as a whole, use"
					+ " write_file.");
		}
		IfModified.check(file, path, emptyToNull(environment.getArgument("ifModified")));

		Charset charset = charsetOf(file);
		String content;
		try (InputStream in = JCRs.getContentAsStream(file)) {
			content = new String(in.readAllBytes(), charset);
		}

		// Match the line endings of the file when the caller's text has none of its own.
		if (content.contains("\r\n") && !oldText.contains("\r") && !newText.contains("\r")) {
			oldText = oldText.replace("\n", "\r\n");
			newText = newText.replace("\n", "\r\n");
		}

		int count = 0;
		int first = -1;
		for (int at = content.indexOf(oldText); at >= 0; at = content.indexOf(oldText, at + oldText.length())) {
			if (first < 0) {
				first = at;
			}
			count++;
		}
		if (count == 0) {
			throw new IllegalArgumentException("oldText was not found in " + path + ". It has to match the file"
					+ " exactly, whitespace and indentation included: read the file and copy the text to replace from"
					+ " it.");
		}
		if (count > 1 && !replaceAll) {
			throw new IllegalArgumentException("oldText occurs " + count + " times in " + path + "; nothing was"
					+ " changed. Include more of the surrounding text so it matches once, or set replaceAll to replace"
					+ " every occurrence.");
		}

		int line = 1;
		for (int i = 0; i < first; i++) {
			if (content.charAt(i) == '\n') {
				line++;
			}
		}
		String updated = replaceAll ? content.replace(oldText, newText)
				: content.substring(0, first) + newText + content.substring(first + oldText.length());
		byte[] bytes = updated.getBytes(charset);

		try {
			// A file is locked deep, and the JCR layer refuses the write to its
			// content; a file locked shallow before that is refused here, as
			// every Resource write refuses it.
			JcrAction.create(file).addLockToken().checkLock();
			JCRs.write(file, new ByteArrayInputStream(bytes));
			session.save();
		} catch (LockException ex) {
			session.refresh(false);
			throw new IllegalArgumentException(ex.getMessage() + " Nothing was changed.");
		} catch (VersionException ex) {
			session.refresh(false);
			throw new IllegalArgumentException(path + " is checked in, so it cannot be changed; nothing was"
					+ " changed. Check it out first.");
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("path", path);
		result.put("replacements", count);
		result.put("line", line);
		result.put("size", bytes.length);
		result.put("modified", ISO8601.format(JCRs.getLastModified(file)));
		result.put("modifiedBy", JCRs.getLastModifiedBy(file));
		return result;
	}

	// ---- fileDiff ------------------------------------------------------------

	/**
	 * {@code Query.fileDiff(path, from, to, limit)}: a unified diff between two
	 * versions of a versionable text file, or between a version and the
	 * current content.
	 */
	private static Object fileDiff(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = absolutePath(environment.getArgument("path"));
		String fromName = emptyToNull(environment.getArgument("from"));
		String toName = emptyToNull(environment.getArgument("to"));
		int limit = Math.min(intArgument(environment, "limit", DEFAULT_DIFF_LIMIT), MAX_DIFF_LIMIT);
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be 1 or more.");
		}

		Node file = file(session, path);
		if (!file.isNodeType("mix:versionable")) {
			throw new IllegalArgumentException(path + " is not under version control, so no earlier content is kept"
					+ " for it. Make it versionable with addVersionControl, or compare it with another file by reading"
					+ " both.");
		}
		VersionManager versionManager = session.getWorkspace().getVersionManager();
		VersionHistory history = versionManager.getVersionHistory(path);
		Version fromVersion = (fromName != null) ? version(history, fromName, path)
				: versionManager.getBaseVersion(path);
		if (fromVersion == null || fromVersion.getName().equals("jcr:rootVersion")) {
			throw new IllegalArgumentException(path + " has no version to compare with yet: its history holds only"
					+ " the root version. Create one with checkpoint or checkin.");
		}
		Version toVersion = (toName != null) ? version(history, toName, path) : null;

		String fromLabel = "version " + fromVersion.getName();
		String toLabel = (toVersion != null) ? "version " + toVersion.getName() : "current";
		Deadline deadline = Deadline.in(EXTRACT_MILLIS);
		DiffText from = textOf(fromVersion.getFrozenNode(), path, fromLabel, deadline);
		DiffText to = textOf((toVersion != null) ? toVersion.getFrozenNode() : file, path, toLabel, deadline);
		boolean extracted = from.fExtracted || to.fExtracted;
		String kindLabel = extracted ? ", extracted text" : "";
		TextDiff diff = TextDiff.compare(from.fText, to.fText, path + " (" + fromLabel + kindLabel + ")",
				path + " (" + toLabel + kindLabel + ")", limit);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("extracted", extracted);
		if (!from.fComplete || !to.fComplete) {
			result.put("note", "The text of " + ((!from.fComplete && !to.fComplete) ? "both sides"
					: (!from.fComplete ? fromLabel : toLabel)) + " was compared only as far as it was extracted ("
					+ MAX_DOCUMENT_DIFF_CHARS + " characters, or what " + (EXTRACT_MILLIS / 1000) + " s allowed):"
					+ " a difference beyond that is not shown.");
		}
		result.put("path", path);
		result.put("from", fromVersion.getName());
		result.put("fromCreated", ISO8601.format(fromVersion.getCreated()));
		result.put("to", (toVersion != null) ? toVersion.getName() : "current");
		result.put("toCreated", (toVersion != null) ? ISO8601.format(toVersion.getCreated())
				: ISO8601.format(JCRs.getLastModified(file)));
		result.put("identical", diff.isIdentical());
		result.put("added", diff.getAdded());
		result.put("removed", diff.getRemoved());
		result.put("hunks", diff.getHunks());
		result.put("totalHunks", diff.getTotalHunks());
		result.put("diff", diff.getDiff());
		result.put("truncated", diff.isTruncated());
		return result;
	}

	private static Version version(VersionHistory history, String name, String path) {
		try {
			return history.getVersion(name);
		} catch (Exception ex) {
			throw new IllegalArgumentException(
					path + " has no version named " + name + ". version_history lists its versions.");
		}
	}

	/** The content of a file, or of the frozen node of one of its versions, as text. */
	/** One side of a comparison: its text, whether it was extracted from a document, and whether it is whole. */
	private static final class DiffText {
		private final String fText;
		private final boolean fExtracted;
		private final boolean fComplete;

		DiffText(String text, boolean extracted, boolean complete) {
			fText = text;
			fExtracted = extracted;
			fComplete = complete;
		}
	}

	/**
	 * The text of a file, or of the frozen node of one of its versions, to
	 * compare: a text file as it is; a document (PDF, Office, …) as its text
	 * is extracted, so that what changed in a contract or a slide deck reads
	 * as changed lines too.
	 */
	private static DiffText textOf(Node node, String path, String label, Deadline deadline) throws Exception {
		FileKind kind = FileKind.of(node);
		if (kind == FileKind.MEDIA) {
			throw new IllegalArgumentException(path + " (" + label + ") is " + describe(kind, JCRs.getMimeType(node))
					+ ": it has no text, so there is no line-by-line difference to show.");
		}
		if (kind.isExtractable()) {
			DocumentTexts.Extracted extracted = DocumentTexts.extract(node, path + " (" + label + ")", 0,
					MAX_DOCUMENT_DIFF_CHARS, deadline);
			return new DiffText(extracted.getPage().getText(), true, !extracted.getPage().isTruncated());
		}
		long size = JCRs.getContentLength(node);
		if (size > MAX_TEXT_FILE_BYTES) {
			throw new IllegalArgumentException(path + " (" + label + ") is " + size + " bytes, more than the "
					+ MAX_TEXT_FILE_BYTES + " bytes that can be compared.");
		}
		try (InputStream in = JCRs.getContentAsStream(node)) {
			return new DiffText(new String(in.readAllBytes(), charsetOf(node)), false, true);
		}
	}

	// ---- grepFiles -----------------------------------------------------------

	/**
	 * {@code Query.grepFiles(...)}: the lines of the text files under a folder
	 * that match a pattern. Full-text search finds documents by their words;
	 * this finds places. Files are found with an XPath query and read one at a
	 * time; a file that is not text, or is larger than {@code maxFileSize}, is
	 * skipped and counted, never read into memory.
	 */
	private static Object grepFiles(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = absolutePath(environment.getArgument("path"));
		String patternText = environment.getArgument("pattern");
		boolean regex = !Boolean.FALSE.equals(environment.getArgument("regex"));
		boolean ignoreCase = Boolean.TRUE.equals(environment.getArgument("ignoreCase"));
		Pattern namePattern = glob(emptyToNull(environment.getArgument("namePattern")));
		int maxMatches = Math.min(intArgument(environment, "maxMatches", DEFAULT_MAX_MATCHES), MAX_MATCHES);
		int maxFileSize = Math.min(intArgument(environment, "maxFileSize", DEFAULT_MAX_FILE_SIZE), MAX_FILE_SIZE);
		if (patternText == null || patternText.isEmpty()) {
			throw new IllegalArgumentException("pattern must not be empty.");
		}
		if (maxMatches < 1) {
			throw new IllegalArgumentException("maxMatches must be 1 or more.");
		}

		int flags = ignoreCase ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0;
		Pattern pattern;
		try {
			pattern = Pattern.compile(regex ? patternText : Pattern.quote(patternText), flags);
		} catch (PatternSyntaxException ex) {
			throw new IllegalArgumentException("pattern is not a valid regular expression: " + ex.getDescription()
					+ " at index " + ex.getIndex() + ". Set regex to false to search for the text literally.");
		}
		folder(session, path, "Read it with read_file.");

		Deadline deadline = Deadline.in(SEARCH_MILLIS);
		List<Map<String, Object>> matches = new ArrayList<>();
		int filesExamined = 0;
		int filesScanned = 0;
		int filesSkipped = 0;
		int filesMatched = 0;
		String stoppedBy = null;
		long offset = 0;
		try {
			outer: while (true) {
				NodeIterator files = filesUnder(session, path, offset);
				int returned = 0;
				while (files.hasNext()) {
					if (filesExamined >= GREP_SCAN_LIMIT) {
						stoppedBy = STOPPED_BY_SCAN;
						break outer;
					}
					deadline.check();
					Node file = files.nextNode();
					returned++;
					filesExamined++;
					if (namePattern != null && !namePattern.matcher(file.getName()).matches()) {
						continue;
					}
					if (JCRs.getContentLength(file) > maxFileSize || FileKind.of(file) != FileKind.TEXT) {
						filesSkipped++;
						continue;
					}
					filesScanned++;
					boolean matchedFile = false;
					String filePath = file.getPath();
					try (BufferedReader reader = new BufferedReader(JCRs.getContentAsReader(file))) {
						int lineNo = 0;
						String line;
						while ((line = reader.readLine()) != null) {
							lineNo++;
							// The guarded line gives up on a pattern that backtracks past the deadline.
							if (!pattern.matcher(deadline.guard(line)).find()) {
								continue;
							}
							matchedFile = true;
							Map<String, Object> match = new LinkedHashMap<>();
							match.put("path", filePath);
							match.put("line", lineNo);
							match.put("text", (line.length() > MAX_LINE_CHARS)
									? line.substring(0, MAX_LINE_CHARS) + "…" : line);
							matches.add(match);
							if (matches.size() >= maxMatches) {
								stoppedBy = STOPPED_BY_MATCHES;
								break;
							}
						}
					} finally {
						if (matchedFile) {
							filesMatched++;
						}
					}
					if (stoppedBy != null) {
						break outer;
					}
				}
				if (returned < QUERY_PAGE) {
					break;
				}
				offset += returned;
			}
		} catch (Deadline.Exceeded ex) {
			stoppedBy = STOPPED_BY_TIME;
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("path", path);
		result.put("pattern", patternText);
		result.put("filesScanned", filesScanned);
		result.put("filesSkipped", filesSkipped);
		result.put("filesMatched", filesMatched);
		result.put("matches", matches);
		result.put("truncated", stoppedBy != null);
		result.put("stoppedBy", stoppedBy);
		return result;
	}

	// ---- findFiles -----------------------------------------------------------

	/**
	 * {@code Query.findFiles(path, namePattern, type, modifiedSince,
	 * modifiedBy, orderBy, offset, limit)}: the files, folders or both under a
	 * folder whose name matches a glob, and — when asked — that were modified
	 * since an instant, or by a user: what changed recently. Files come from an
	 * XPath query, which the search index answers without walking the tree;
	 * folders are not in the index, so a search that includes them walks the
	 * tree, depth first. In the order found, the search stops as soon as the
	 * page is full; with {@code orderBy: "modified"} every match is collected
	 * (within the scan limit) and the newest come first.
	 */
	private static Object findFiles(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = absolutePath(environment.getArgument("path"));
		String namePatternText = emptyToNull(environment.getArgument("namePattern"));
		if (namePatternText == null) {
			namePatternText = "*";
		}
		String type = emptyToNull(environment.getArgument("type"));
		if (type == null) {
			type = "file";
		}
		Instant modifiedSince = instantArgument(environment, "modifiedSince");
		String modifiedBy = emptyToNull(environment.getArgument("modifiedBy"));
		String orderBy = emptyToNull(environment.getArgument("orderBy"));
		int offset = intArgument(environment, "offset", 0);
		int limit = Math.min(intArgument(environment, "limit", DEFAULT_FIND_LIMIT), MAX_FIND_LIMIT);
		if (!type.equals("file") && !type.equals("folder") && !type.equals("any")) {
			throw new IllegalArgumentException("type must be file, folder or any.");
		}
		if (orderBy != null && !orderBy.equals("modified")) {
			throw new IllegalArgumentException("orderBy must be modified (newest first), or left out for the order"
					+ " found.");
		}
		if (offset < 0) {
			throw new IllegalArgumentException("offset must be 0 or more.");
		}
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be 1 or more.");
		}
		Node folder = folder(session, path, null);

		Finder finder = new Finder(glob(namePatternText), modifiedSince, modifiedBy, orderBy != null, offset, limit,
				Deadline.in(SEARCH_MILLIS));
		if (type.equals("file")) {
			long queryOffset = 0;
			outer: while (true) {
				NodeIterator found = filesUnder(session, path, queryOffset);
				int returned = 0;
				while (found.hasNext()) {
					returned++;
					if (finder.consider(found.nextNode())) {
						break outer;
					}
				}
				if (returned < QUERY_PAGE) {
					break;
				}
				queryOffset += returned;
			}
		} else {
			finder.walk(folder, type.equals("any"));
		}
		finder.finish();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("path", path);
		result.put("namePattern", namePatternText);
		result.put("scanned", finder.fScanned);
		result.put("offset", offset);
		result.put("nodes", finder.fNodes);
		result.put("truncated", finder.fTruncated || finder.fStoppedBy != null);
		// A next page exists only when this one filled up: a search stopped by
		// its scan or time limit returned every match in what it looked at, and
		// a later offset would look at the same nodes again.
		result.put("nextOffset", finder.fTruncated ? offset + finder.fNodes.size() : null);
		result.put("stoppedBy", finder.fStoppedBy);
		return result;
	}

	/** Collects the page of nodes that match, counting what it looked at. */
	private static final class Finder {
		private final Pattern fPattern;
		private final Instant fModifiedSince;
		private final String fModifiedBy;
		private final boolean fNewestFirst;
		private final int fOffset;
		private final int fLimit;
		private final Deadline fDeadline;
		private final List<Map<String, Object>> fNodes = new ArrayList<>();
		/** Every match with its modification time, when they are sorted at the end. */
		private final List<Map.Entry<Long, Map<String, Object>>> fAll = new ArrayList<>();
		private int fScanned;
		private int fMatched;
		private boolean fTruncated;
		private String fStoppedBy;

		Finder(Pattern pattern, Instant modifiedSince, String modifiedBy, boolean newestFirst, int offset, int limit,
				Deadline deadline) {
			fPattern = pattern;
			fModifiedSince = modifiedSince;
			fModifiedBy = modifiedBy;
			fNewestFirst = newestFirst;
			fOffset = offset;
			fLimit = limit;
			fDeadline = deadline;
		}

		/** Whether a limit of the search is reached; records which. */
		private boolean overBudget() {
			if (fScanned >= FIND_SCAN_LIMIT) {
				fStoppedBy = STOPPED_BY_SCAN;
				return true;
			}
			if (fDeadline.isPassed()) {
				fStoppedBy = STOPPED_BY_TIME;
				return true;
			}
			return false;
		}

		/** Looks at one node; true when the page is full, or a limit is reached, and the search can stop. */
		boolean consider(Node node) throws Exception {
			if (overBudget()) {
				return true;
			}
			fScanned++;
			if (!fPattern.matcher(node.getName()).matches()) {
				return false;
			}
			Date modified = JCRs.getLastModified(node);
			String modifiedBy = JCRs.getLastModifiedBy(node);
			if (fModifiedSince != null && (modified == null || modified.toInstant().isBefore(fModifiedSince))) {
				return false;
			}
			if (fModifiedBy != null && !fModifiedBy.equals(modifiedBy)) {
				return false;
			}
			if (fNewestFirst) {
				fAll.add(new AbstractMap.SimpleEntry<>((modified != null) ? modified.getTime() : 0L,
						info(node, modified, modifiedBy)));
				return false;
			}
			fMatched++;
			if (fMatched <= fOffset) {
				return false;
			}
			if (fNodes.size() >= fLimit) {
				fTruncated = true;
				return true;
			}
			fNodes.add(info(node, modified, modifiedBy));
			return false;
		}

		/** Puts the collected matches in order and takes the page, when they are sorted. */
		void finish() {
			if (!fNewestFirst) {
				return;
			}
			fAll.sort((a, b) -> Long.compare(b.getKey(), a.getKey()));
			for (int i = fOffset; i < fAll.size() && fNodes.size() < fLimit; i++) {
				fNodes.add(fAll.get(i).getValue());
			}
			fTruncated = fAll.size() > fOffset + fLimit;
		}

		private static Map<String, Object> info(Node node, Date modified, String modifiedBy) throws Exception {
			boolean isFolder = JCRs.isFolder(node);
			Map<String, Object> info = new LinkedHashMap<>();
			info.put("path", node.getPath());
			info.put("nodeType", isFolder ? "nt:folder" : "nt:file");
			if (!isFolder) {
				info.put("size", JCRs.getContentLength(node));
				info.put("mimeType", JCRs.getMimeType(node));
			}
			info.put("modified", (modified != null) ? ISO8601.format(modified) : null);
			info.put("modifiedBy", modifiedBy);
			return info;
		}

		/** Walks the tree under a folder, depth first; true when the page is full. */
		boolean walk(Node parent, boolean files) throws Exception {
			for (NodeIterator children = parent.getNodes(); children.hasNext();) {
				// Files are walked past when only folders are looked for; the
				// deadline still bounds the walk.
				if (fDeadline.isPassed()) {
					fStoppedBy = STOPPED_BY_TIME;
					return true;
				}
				Node child = children.nextNode();
				boolean isFolder = JCRs.isFolder(child);
				if (!isFolder && !JCRs.isFile(child)) {
					continue;
				}
				if ((isFolder || files) && consider(child)) {
					return true;
				}
				if (isFolder && walk(child, files)) {
					return true;
				}
			}
			return false;
		}
	}

	// ---- helpers -------------------------------------------------------------

	private static Session session(DataFetchingEnvironment environment) {
		return GraphQLExecutionContext.from(environment).getCallerSession();
	}

	/** An absolute repository path, without {@code .} or {@code ..} segments. */
	private static String absolutePath(String path) {
		if (path == null || !path.startsWith("/")) {
			throw new IllegalArgumentException("path must be an absolute repository path.");
		}
		for (String segment : path.split("/")) {
			if (segment.equals(".") || segment.equals("..")) {
				throw new IllegalArgumentException("path must be an absolute repository path.");
			}
		}
		return path;
	}

	/** The file (or frozen version of a file) at a path; a folder or a missing node is refused. */
	private static Node file(Session session, String path) throws RepositoryException {
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException(notFound(path));
		}
		Node node = session.getNode(path);
		if (JCRs.isFolder(node)) {
			throw new IllegalArgumentException(path + " is a folder, not a file.");
		}
		try {
			JCRs.getMimeType(node);
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(
					path + " is a " + node.getPrimaryNodeType().getName() + ", not a file.");
		}
		return node;
	}

	/** The folder at a path; a file is refused with {@code fileHint} after the reason. */
	private static Node folder(Session session, String path, String fileHint) throws RepositoryException {
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException(notFound(path));
		}
		Node node = session.getNode(path);
		if (!path.equals("/") && !JCRs.isFolder(node)) {
			throw new IllegalArgumentException(
					path + " is a file, not a folder." + ((fileHint != null) ? " " + fileHint : ""));
		}
		return node;
	}

	private static String notFound(String path) {
		return "There is no node at " + path + ", or the signed-in user is not allowed to read it.";
	}

	/**
	 * One round of the files under a folder, from an XPath query: at most
	 * {@value #QUERY_PAGE} nodes from {@code offset}; fewer means the last round.
	 */
	private static NodeIterator filesUnder(Session session, String path, long offset) throws RepositoryException {
		String statement = (path.equals("/") ? "/jcr:root" : "/jcr:root" + path) + "//element(*, nt:file)";
		Query query = session.getWorkspace().getQueryManager().createQuery(statement, Query.XPATH);
		query.setOffset(offset);
		query.setLimit(QUERY_PAGE);
		return query.execute().getNodes();
	}

	/** A file-name glob as a pattern: {@code *} any run, {@code ?} one character, the rest literal; case-insensitive. */
	private static Pattern glob(String glob) {
		if (glob == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (char c : glob.toCharArray()) {
			if (c == '*') {
				sb.append(".*");
			} else if (c == '?') {
				sb.append('.');
			} else {
				sb.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	/** The charset a file's text is in: its recorded encoding, or UTF-8. */
	private static Charset charsetOf(Node file) throws RepositoryException {
		String encoding = JCRs.getEncoding(file);
		if (encoding != null && !encoding.isEmpty() && Charset.isSupported(encoding)) {
			return Charset.forName(encoding);
		}
		return StandardCharsets.UTF_8;
	}

	/** How a file that is not text is named in a refusal. */
	private static String describe(FileKind kind, String mimeType) {
		switch (kind) {
		case DOCUMENT:
			return "a document (" + mimeType + ")";
		case MEDIA:
			return mimeType;
		default:
			return "binary (" + mimeType + ")";
		}
	}

	private static int intArgument(DataFetchingEnvironment environment, String name, int defaultValue) {
		Object value = environment.getArgument(name);
		return (value instanceof Number) ? ((Number) value).intValue() : defaultValue;
	}

	/** An ISO-8601 instant argument (with Z or an offset), or null when it is not given. */
	private static Instant instantArgument(DataFetchingEnvironment environment, String name) {
		String value = emptyToNull(environment.getArgument(name));
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException ignore) {}
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (DateTimeParseException ex) {
			throw new IllegalArgumentException(name + " must be an ISO-8601 instant such as 2026-10-01T00:00:00Z or"
					+ " 2026-10-01T09:00:00+09:00: " + value);
		}
	}

	private static String emptyToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformFilesWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Files GraphQL schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}

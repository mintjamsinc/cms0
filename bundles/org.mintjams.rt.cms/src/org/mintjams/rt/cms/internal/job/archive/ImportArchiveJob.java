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

package org.mintjams.rt.cms.internal.job.archive;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.jcr.ImportContentHandler;
import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.Job;
import org.mintjams.rt.cms.internal.job.JobContext;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.web.Webs;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Imports a CMS Archive (see {@link ArchiveJob} and
 * {@code documents/cms-archive-export-import.md}) into the repository —
 * the symmetric counterpart of the download/export job.
 *
 * <p>The whole import runs in one session against an
 * {@link ImportContentHandler} obtained from
 * {@link org.mintjams.jcr.Session#getImportContentHandler(int, int)}: identifier
 * and path conflicts are resolved per node by the handler (which also checks out
 * and back in versionable nodes it overwrites), and the entire result is then
 * committed by a single {@code save()} (a real import) or discarded by a single
 * {@code refresh(false)} (a dry run — which therefore exercises the very same
 * code, version operations included).
 *
 * <p>Outcomes are tallied per file (each {@code nt:file} falls into exactly one
 * of new/overwrite/skip/error). A per-file error is recorded and the import
 * continues; a {@code throw} conflict policy, or a missing ACL principal when
 * ACL import is on, aborts. Either way a CSV report of every file's outcome is
 * written beside the job node and offered for download, and the first errors are
 * surfaced on the completion screen.
 */
public class ImportArchiveJob implements Job {

	public static final String TYPE = "import-archive";

	private static final long PROGRESS_THROTTLE_ITEMS = 25L;
	private static final long PROGRESS_THROTTLE_MILLIS = 300L;
	/** How many errors the completion screen shows; the rest live in the CSV report. */
	private static final int ERROR_SAMPLE_LIMIT = 20;

	private final String fJobId;
	private final String fWorkspaceName;
	private final String fUserId;
	private final int fPriority;

	private final ObjectMapper fMapper = new ObjectMapper();

	// Import options, read from the job node.
	private String fArchivePath;
	private String fDestPath;
	private int fUuidBehavior;
	private int fPathBehavior;
	private boolean fImportAcl;
	/**
	 * When true (the default), each node's original {@code jcr:created} and
	 * {@code jcr:lastModified} are carried over from the archive; otherwise the
	 * repository stamps the import time. Either way {@code jcr:createdBy} and
	 * {@code jcr:lastModifiedBy} always become the importing user.
	 */
	private boolean fPreserveTimestamps;
	private boolean fDryRun;
	/**
	 * The requester's UI locale at submit time. Only the strings this job itself
	 * produces in the report (the {@code 処理} column and its own error messages)
	 * are localized from it; library/JCR exception text is reported verbatim.
	 */
	private String fReportLocale;

	// Per-file outcome tallies (the unit the user sees). These always sum to the
	// number of files in the archive.
	private long fNew;
	private long fOverwritten;
	private long fSkipped;
	private long fError;
	private long fLastWriteAt;
	private final List<String> fWarnings = new ArrayList<>();
	private final List<String> fErrorSamples = new ArrayList<>();

	// Counts declared by the archive manifest, surfaced in the dry-run summary.
	private long fNodeCount;
	private long fBinaryCount;
	// Dry-run findings.
	private boolean fDryRunHasErrors;
	private String fDryRunDetail;

	// Working state for one apply() run.
	private String fDestRootPath;
	private final Map<String, FileResult> fResults = new LinkedHashMap<>();
	private final Map<String, String> fIdMap = new HashMap<>();      // old identifier -> new identifier
	private final Map<String, String> fPathMap = new HashMap<>();    // source path -> target path
	private final Map<String, String> fEntryMap = new HashMap<>();   // source path -> content-tree entry
	private final Map<String, String> fFileOf = new HashMap<>();     // any source path -> owning nt:file source path
	private final Set<String> fSkippedSubtree = new HashSet<>();     // skipped by policy; descendants cascade as skip
	private final Set<String> fFailedSubtree = new HashSet<>();      // errored/unimportable; descendants cascade as error

	public ImportArchiveJob(String jobId, String workspaceName, String userId, int priority) {
		fJobId = jobId;
		fWorkspaceName = workspaceName;
		fUserId = userId;
		fPriority = priority;
	}

	@Override
	public String getJobId() {
		return fJobId;
	}

	@Override
	public String getJobType() {
		return TYPE;
	}

	@Override
	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	@Override
	public String getUserId() {
		return fUserId;
	}

	@Override
	public int getPriority() {
		return fPriority;
	}

	@Override
	public String getJobNodePath() {
		return JobNodes.jobNodePath(fJobId);
	}

	@Override
	public void execute(JobContext context) throws Exception {
		context.getLogger().info("ImportArchiveJob " + fJobId + " execute() entered for workspace=" + fWorkspaceName
				+ " user=" + fUserId);
		Session jobSession;
		Session progressSession;
		try {
			jobSession = context.getJobSession();
			progressSession = context.getProgressSession();
		} catch (Throwable ex) {
			context.getLogger().error("ImportArchiveJob " + fJobId + " could not open sessions", ex);
			markFailedWithSystemSession(fJobId, fWorkspaceName, ex);
			return;
		}

		Node progressContent = null;
		String initError = null;
		try {
			Node fileNode = JobNodes.getJobNode(progressSession, fJobId);
			if (fileNode == null) {
				context.getLogger().warn("Job node missing for " + fJobId + "; aborting.");
				return;
			}
			progressContent = JobNodes.getContent(fileNode);

			fArchivePath = JobNodes.getString(progressContent, JobNodes.PROP_ARCHIVE_PATH, null);
			fDestPath = JobNodes.getString(progressContent, JobNodes.PROP_DEST_PATH, null);
			fUuidBehavior = (int) JobNodes.getLong(progressContent, JobNodes.PROP_UUID_BEHAVIOR,
					ImportContentHandler.IMPORT_UUID_THROW_ON_COLLISION);
			fPathBehavior = (int) JobNodes.getLong(progressContent, JobNodes.PROP_PATH_BEHAVIOR,
					ImportContentHandler.IMPORT_PATH_THROW_ON_CONFLICT);
			fImportAcl = JobNodes.getBoolean(progressContent, JobNodes.PROP_IMPORT_ACL, false);
			fPreserveTimestamps = JobNodes.getBoolean(progressContent, JobNodes.PROP_PRESERVE_TIMESTAMPS, true);
			fDryRun = JobNodes.getBoolean(progressContent, JobNodes.PROP_DRY_RUN, false);
			fReportLocale = JobNodes.getString(progressContent, JobNodes.PROP_REPORT_LOCALE, "");

			if (fArchivePath == null || fDestPath == null) {
				throw new IllegalStateException("archivePath and destinationPath are required");
			}

			JobNodes.setStatus(progressContent, JobStatus.RUNNING);
			progressContent.setProperty(JobNodes.PROP_STARTED_AT, Calendar.getInstance());
			progressContent.setProperty(JobNodes.PROP_ITEMS_IMPORTED, 0L);
			progressSession.save();
		} catch (Throwable ex) {
			initError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			context.getLogger().error("ImportArchiveJob " + fJobId + " could not be initialised", ex);
		}

		boolean aborted = false;
		String errorMessage = initError;
		fLastWriteAt = System.currentTimeMillis();
		Path tempZip = null;

		if (initError == null) {
			try {
				tempZip = copyArchiveToTemp(jobSession);
				try (ZipFile zip = new ZipFile(tempZip.toFile())) {
					validateManifest(zip);
					apply(jobSession, zip, context, progressContent, progressSession);
				}
			} catch (AbortedException ex) {
				aborted = true;
				try { jobSession.refresh(false); } catch (Throwable ignore) {}
			} catch (Throwable ex) {
				String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
				try { jobSession.refresh(false); } catch (Throwable ignore) {}
				if (fDryRun) {
					// The rehearsal hit exactly what the real import would hit (a
					// throw-policy conflict, a missing ACL principal, an unknown node
					// type). Report it as a finding the user can act on; the job still
					// completes.
					fDryRunHasErrors = true;
					fDryRunDetail = detail;
					context.getLogger().info("ImportArchiveJob " + fJobId
							+ " dry run found a blocking problem: " + detail);
				} else {
					errorMessage = detail;
					context.getLogger().error("ImportArchiveJob " + fJobId + " failed", ex);
				}
			} finally {
				if (tempZip != null) {
					try { Files.deleteIfExists(tempZip); } catch (Throwable ignore) {}
				}
			}
		}

		// Always finalise so the client subscription sees a terminal event.
		try {
			if (progressContent == null) {
				Node fileNode = JobNodes.getJobNode(progressSession, fJobId);
				if (fileNode != null) {
					progressContent = JobNodes.getContent(fileNode);
				}
			}
			if (progressContent != null) {
				JobStatus finalStatus;
				if (errorMessage != null) {
					finalStatus = JobStatus.FAILED;
					progressContent.setProperty(JobNodes.PROP_ERROR_MESSAGE, errorMessage);
				} else if (aborted) {
					finalStatus = JobStatus.ABORTED;
				} else {
					finalStatus = JobStatus.COMPLETED;
				}
				writeOutcome(progressContent);
				progressContent.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
				if (fDryRun) {
					progressContent.setProperty(JobNodes.PROP_DRY_RUN_HAS_ERRORS, fDryRunHasErrors || fError > 0);
					progressContent.setProperty(JobNodes.PROP_DRY_RUN_NODE_COUNT, fNodeCount);
					progressContent.setProperty(JobNodes.PROP_DRY_RUN_BINARY_COUNT, fBinaryCount);
					if (fDryRunDetail != null) {
						progressContent.setProperty(JobNodes.PROP_DRY_RUN_DETAIL, fDryRunDetail);
					}
				}
				// A report is meaningful whenever any file was processed, including a
				// failed/aborted run (report up to the failure point).
				if (!fResults.isEmpty()) {
					try {
						storeReport(progressSession);
						progressContent.setProperty(JobNodes.PROP_DOWNLOAD_URL,
								CmsConfiguration.DOWNLOAD_ARCHIVE_CGI_PATH
										+ Webs.encodePath("/" + fWorkspaceName + "/" + fJobId + "/report"));
					} catch (Throwable ex) {
						context.getLogger().warn("ImportArchiveJob " + fJobId + " could not store the report", ex);
					}
				}
				if (!fWarnings.isEmpty()) {
					context.getLogger().warn("ImportArchiveJob " + fJobId + " completed with "
							+ fWarnings.size() + " warning(s); first: " + fWarnings.get(0));
				}
				JobNodes.setStatus(progressContent, finalStatus);
				progressSession.save();
			}
		} catch (Throwable ex) {
			context.getLogger().error("ImportArchiveJob " + fJobId + " could not finalise status", ex);
		}
	}

	// =========================================================================
	// Import
	// =========================================================================

	/**
	 * Run the import in a single session against an {@link ImportContentHandler}.
	 * The tree is staged transiently with no intermediate saves and then committed
	 * by one {@code save()} (real import) or discarded by one {@code refresh()}
	 * (dry run). A {@code throw} conflict policy or a missing ACL principal throws
	 * to abort; per-file errors are recorded and the walk continues.
	 */
	private void apply(Session session, ZipFile zip, JobContext context, Node progressContent,
			Session progressSession) throws Exception {
		Node destination = JCRs.getOrCreateFolder(JcrPath.valueOf(fDestPath), session);
		fDestRootPath = destination.getPath();

		NodeDeserializer deserializer = new NodeDeserializer(session, zip);
		List<NodeDeserializer.DeferredRef> deferred = new ArrayList<>();

		try (ImportContentHandler handler =
				((org.mintjams.jcr.Session) session).getImportContentHandler(fUuidBehavior, fPathBehavior)) {

			// Pass 1 — materialise.
			try (BufferedReader reader = openNodes(zip)) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.isEmpty()) {
						continue;
					}
					if (context.isAborted()) {
						throw new AbortedException();
					}
					Map<String, Object> record = parse(line);
					try {
						materialise(handler, session, record, deserializer, deferred);
					} catch (AbortedException abort) {
						throw abort;
					} catch (ItemExistsException policy) {
						// A throw-policy conflict: record it, then abort the import.
						handleError(record, policy);
						throw policy;
					} catch (Throwable perFile) {
						handleError(record, perFile);
					}
					writeProgress(progressContent, progressSession, (String) record.get("path"), false);
				}
			}

			// Pass 2 — link references.
			for (NodeDeserializer.DeferredRef ref : deferred) {
				if (context.isAborted()) {
					throw new AbortedException();
				}
				deserializer.resolveReference(ref, fIdMap, fWarnings);
			}

			// Pass 3 — access control (opt-in; a missing principal aborts).
			if (fImportAcl) {
				applyAcls(session, zip, context);
			}

			// Check the overwritten versionable nodes back in, capturing the
			// imported content as a new version, before the single commit.
			handler.checkInOverwritten();

			// Single transactional boundary.
			if (fDryRun) {
				session.refresh(false);
			} else {
				session.save();
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void materialise(ImportContentHandler handler, Session session, Map<String, Object> record,
			NodeDeserializer deserializer, List<NodeDeserializer.DeferredRef> deferred) throws Exception {
		String srcPath = (String) record.get("path");
		String name = (String) record.get("name");
		String primaryType = (String) record.get("primaryType");
		String uuid = (String) record.get("uuid");
		String entry = (String) record.get("entry");
		List<String> mixins = (List<String>) record.get("mixins");
		List<Map<String, Object>> properties = (List<Map<String, Object>>) record.get("properties");
		boolean isFile = "nt:file".equals(primaryType);

		String parentSrc = parentPath(srcPath);

		// Cascade: a node whose ancestor was skipped or could not be imported is
		// itself unimportable. Count the file accordingly; never touch the tree.
		if (fSkippedSubtree.contains(parentSrc)) {
			fSkippedSubtree.add(srcPath);
			if (isFile) {
				recordOutcome(srcPath, computeTargetPath(record), Action.SKIP);
			}
			return;
		}
		if (fFailedSubtree.contains(parentSrc)) {
			fFailedSubtree.add(srcPath);
			if (isFile) {
				markFileError(srcPath, computeTargetPath(record), ancestorFailedMessage(parentSrc));
			}
			return;
		}

		String parentTgt = fPathMap.getOrDefault(parentSrc, fDestRootPath);

		Calendar created = fPreserveTimestamps ? parseIsoDate((String) record.get("created")) : null;

		ImportContentHandler.Result result = handler.importNode(parentTgt, name, primaryType, uuid, created);
		if (result.getDisposition() == ImportContentHandler.Disposition.SKIPPED) {
			fSkippedSubtree.add(srcPath);
			if (isFile) {
				recordOutcome(srcPath, childPath(parentTgt, name), Action.SKIP);
			}
			return;
		}

		Node node = result.getNode();
		boolean created2 = result.getDisposition() == ImportContentHandler.Disposition.CREATED;
		try {
			applyMixins(node, mixins);

			// A file body is read from the content tree using the parent file's entry.
			String bodyEntry = fEntryMap.get(parentSrc);
			deserializer.applyProperties(node, properties, bodyEntry, deferred);

			// jcr:lastModified is not protected, so (unlike jcr:created) it is carried
			// over after the body and properties are in place. Only when the node
			// actually carries the property; jcr:lastModifiedBy stays the import user.
			if (fPreserveTimestamps && node.hasProperty("jcr:lastModified")) {
				Calendar lastModified = parseIsoDate((String) record.get("lastModified"));
				if (lastModified != null) {
					node.setProperty("jcr:lastModified", lastModified);
				}
			}
		} catch (Throwable ex) {
			// Undo a freshly-created node so the failure leaves no half-built node
			// in the transient space for the single terminal save. (An in-place
			// overwrite is left as-is — best effort — but those rarely fail here.)
			if (created2) {
				try { node.remove(); } catch (Throwable ignore) {}
			}
			throw ex;
		}

		// Bookkeeping for descendants and reference relinking.
		if (entry != null) {
			fEntryMap.put(srcPath, entry);
		}
		fPathMap.put(srcPath, node.getPath());
		fFileOf.put(srcPath, isFile ? srcPath : fFileOf.get(parentSrc));
		if (uuid != null) {
			fIdMap.put(uuid, node.getIdentifier());
		}
		if (isFile) {
			recordOutcome(srcPath, node.getPath(), created2 ? Action.NEW : Action.OVERWRITE);
		}
	}

	private void applyMixins(Node node, List<String> mixins) throws Exception {
		if (mixins == null) {
			return;
		}
		for (String mixin : mixins) {
			// Version-control mixins are intentionally not imported: the archive
			// carries no version history, so re-adding mix:versionable would place
			// imported content under version control with an empty history. Version
			// control, if wanted, is enabled per file after import.
			if (isVersionControlMixin(mixin)) {
				continue;
			}
			if (!node.isNodeType(mixin)) {
				node.addMixin(mixin);
			}
		}
	}

	/**
	 * Reapply the access control lists from {@code acl.ndjson} onto the imported
	 * nodes, mapping each archived source path to where it actually landed. A
	 * principal that cannot be resolved aborts the import (ACL import is opt-in,
	 * so a missing principal is a configuration problem the operator must see).
	 */
	@SuppressWarnings("unchecked")
	private void applyAcls(Session session, ZipFile zip, JobContext context) throws Exception {
		ZipEntry aclEntry = zip.getEntry(ArchiveManifest.ACL_ENTRY);
		if (aclEntry == null) {
			return; // archive carries no ACL section
		}
		org.mintjams.jcr.security.PrincipalProvider principals =
				((org.mintjams.jcr.Workspace) session.getWorkspace()).getPrincipalProvider();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(zip.getInputStream(aclEntry), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				if (context.isAborted()) {
					throw new AbortedException();
				}
				Map<String, Object> record = parse(line);
				String srcPath = (String) record.get("path");
				String tgtPath = fPathMap.get(srcPath);
				if (tgtPath == null || !session.nodeExists(tgtPath)) {
					continue; // node was skipped or not imported
				}
				AclCodec.apply(session.getNode(tgtPath),
						(List<Map<String, Object>>) record.get("entries"), principals, fWarnings, true);
			}
		}
	}

	// =========================================================================
	// Per-file outcome tracking
	// =========================================================================

	private enum Action { NEW, OVERWRITE, SKIP, ERROR }

	private static final class FileResult {
		String path;
		Action action;
		String error;

		FileResult(String path, Action action) {
			this.path = path;
			this.action = action;
		}
	}

	/** Record (or reclassify) a file's outcome to a non-error action. */
	private void recordOutcome(String srcPath, String tgtPath, Action action) {
		FileResult fr = fResults.get(srcPath);
		if (fr == null) {
			fResults.put(srcPath, new FileResult(tgtPath, action));
			bump(action, +1);
			return;
		}
		bump(fr.action, -1);
		fr.action = action;
		fr.path = tgtPath;
		bump(action, +1);
	}

	/**
	 * Mark a file as errored, reclassifying it from a previous bucket if needed so
	 * an error never double-counts a file already counted as new or overwrite.
	 */
	private void markFileError(String srcPath, String tgtPath, String message) {
		FileResult fr = fResults.get(srcPath);
		if (fr == null) {
			FileResult created = new FileResult(tgtPath, Action.ERROR);
			created.error = message;
			fResults.put(srcPath, created);
			fError++;
			addErrorSample(tgtPath, message);
		} else if (fr.action != Action.ERROR) {
			bump(fr.action, -1);
			fr.action = Action.ERROR;
			fr.error = message;
			fError++;
			addErrorSample(fr.path, message);
		} else {
			fr.error = message;
		}
	}

	private void handleError(Map<String, Object> record, Throwable ex) {
		String srcPath = (String) record.get("path");
		String primaryType = (String) record.get("primaryType");
		String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
		fFailedSubtree.add(srcPath);
		if ("nt:file".equals(primaryType)) {
			markFileError(srcPath, computeTargetPath(record), message);
			return;
		}
		// A non-file error (a folder, or a file's jcr:content): attribute it to the
		// owning file when there is one; otherwise the descendant files cascade as
		// errors of their own.
		String owner = fFileOf.get(parentPath(srcPath));
		if (owner != null && fResults.containsKey(owner)) {
			markFileError(owner, fResults.get(owner).path, message);
		}
	}

	private void bump(Action action, int delta) {
		switch (action) {
		case NEW: fNew += delta; break;
		case OVERWRITE: fOverwritten += delta; break;
		case SKIP: fSkipped += delta; break;
		case ERROR: fError += delta; break;
		}
	}

	private void addErrorSample(String path, String message) {
		if (fErrorSamples.size() < ERROR_SAMPLE_LIMIT) {
			fErrorSamples.add((path == null ? "" : path) + "\t" + (message == null ? "" : message));
		}
	}

	private String computeTargetPath(Map<String, Object> record) {
		String srcPath = (String) record.get("path");
		String name = (String) record.get("name");
		String parentTgt = fPathMap.getOrDefault(parentPath(srcPath), fDestRootPath);
		return childPath(parentTgt, name);
	}

	private void writeOutcome(Node content) throws Exception {
		long total = fNew + fOverwritten + fSkipped + fError;
		content.setProperty(JobNodes.PROP_ITEMS_TOTAL, total);
		content.setProperty(JobNodes.PROP_ITEMS_IMPORTED, fNew + fOverwritten);
		content.setProperty(JobNodes.PROP_ITEMS_NEW, fNew);
		content.setProperty(JobNodes.PROP_ITEMS_OVERWRITTEN, fOverwritten);
		content.setProperty(JobNodes.PROP_ITEMS_SKIPPED, fSkipped);
		content.setProperty(JobNodes.PROP_ITEMS_ERROR, fError);
		if (!fErrorSamples.isEmpty()) {
			content.setProperty(JobNodes.PROP_ERROR_SAMPLES, fErrorSamples.toArray(new String[0]));
		}
	}

	// =========================================================================
	// Report
	// =========================================================================

	/**
	 * True when the report should be written in Japanese, decided from the
	 * requester's locale ({@link #fReportLocale}). Anything that is not a Japanese
	 * locale — including an absent locale — falls back to English.
	 */
	private boolean reportInJapanese() {
		return fReportLocale != null && fReportLocale.toLowerCase(Locale.ROOT).startsWith("ja");
	}

	/** Localized labels for the {@code 処理} column of the CSV report. */
	private String actionLabel(Action action) {
		boolean ja = reportInJapanese();
		switch (action) {
		case NEW: return ja ? "新規" : "New";
		case OVERWRITE: return ja ? "上書き" : "Overwritten";
		case SKIP: return ja ? "スキップ" : "Skipped";
		case ERROR: return ja ? "エラー" : "Error";
		default: return "";
		}
	}

	/**
	 * The one error message this job synthesizes itself (the rest come straight
	 * from caught exceptions and are reported verbatim), localized for the report.
	 */
	private String ancestorFailedMessage(String parentSrc) {
		return reportInJapanese()
				? "上位ノードをインポートできませんでした: " + parentSrc
				: "Ancestor could not be imported: " + parentSrc;
	}

	/**
	 * Build the CSV report (no header). Two columns ({@code path,action}) when
	 * there were no errors, three ({@code path,action,error}) otherwise. Starts
	 * with a UTF-8 BOM so Excel opens the (Japanese) content as UTF-8 rather than
	 * mis-decoding it as the system locale.
	 */
	private byte[] buildReportCsv() {
		boolean withError = fError > 0;
		StringBuilder sb = new StringBuilder();
		sb.append('\uFEFF'); // UTF-8 BOM
		for (FileResult fr : fResults.values()) {
			sb.append(csv(fr.path)).append(',').append(csv(actionLabel(fr.action)));
			if (withError) {
				sb.append(',').append(csv(fr.error == null ? "" : fr.error));
			}
			sb.append("\r\n");
		}
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String csv(String value) {
		String v = (value == null) ? "" : value;
		return "\"" + v.replace("\"", "\"\"") + "\"";
	}

	/**
	 * Write the CSV report as an {@code nt:file} sibling of the job node so the
	 * extended {@link org.mintjams.rt.cms.internal.web.ArchiveDownloadServlet} can
	 * stream it back later (URL recorded in {@link JobNodes#PROP_DOWNLOAD_URL}).
	 */
	private void storeReport(Session session) throws Exception {
		String nodePath = JobNodes.reportNodePath(fJobId);
		String parentPath = nodePath.substring(0, nodePath.lastIndexOf('/'));
		String nodeName = nodePath.substring(nodePath.lastIndexOf('/') + 1);

		Node parent = JCRs.getOrCreateFolder(JcrPath.valueOf(parentPath), session);
		Node fileNode;
		if (parent.hasNode(nodeName)) {
			fileNode = parent.getNode(nodeName);
		} else {
			fileNode = JCRs.createFile(parent, nodeName);
		}
		try (InputStream in = new ByteArrayInputStream(buildReportCsv())) {
			JCRs.write(fileNode, in);
		}
		Node content = JCRs.getContentNode(fileNode);
		content.setProperty("jcr:mimeType", "text/csv");
		content.setProperty("jcr:encoding", "UTF-8");
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		content.setProperty("jcr:lastModifiedBy", session.getUserID());
		session.save();
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private Path copyArchiveToTemp(Session session) throws Exception {
		if (!session.nodeExists(fArchivePath)) {
			throw new IllegalStateException("Archive not found: " + fArchivePath);
		}
		Node archive = session.getNode(fArchivePath);
		Path temp = Files.createTempFile("cms-import-" + fJobId + "-", ".zip");
		try (InputStream in = JCRs.getContentAsStream(archive);
				OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
		}
		return temp;
	}

	private void validateManifest(ZipFile zip) throws Exception {
		ZipEntry entry = zip.getEntry(ArchiveManifest.MANIFEST_ENTRY);
		if (entry == null) {
			throw new IllegalStateException("Not a CMS Archive: missing " + ArchiveManifest.MANIFEST_ENTRY);
		}
		Map<String, Object> manifest;
		try (InputStream in = zip.getInputStream(entry)) {
			manifest = parseStream(in);
		}
		if (!ArchiveManifest.FORMAT.equals(manifest.get("format"))) {
			throw new IllegalStateException("Unrecognised archive format: " + manifest.get("format"));
		}
		Object version = manifest.get("version");
		int v = (version instanceof Number) ? ((Number) version).intValue() : -1;
		if (v <= 0 || v > ArchiveManifest.VERSION) {
			throw new IllegalStateException("Unsupported archive version: " + version
					+ " (this server understands up to " + ArchiveManifest.VERSION + ")");
		}
		if (zip.getEntry(ArchiveManifest.NODES_ENTRY) == null) {
			throw new IllegalStateException("Archive has no node metadata: " + ArchiveManifest.NODES_ENTRY);
		}

		// Counts are advisory, recorded at export time; surfaced in the dry-run
		// summary so the user sees the scope before committing to a real import.
		Object countsObj = manifest.get("counts");
		if (countsObj instanceof Map) {
			Map<?, ?> counts = (Map<?, ?>) countsObj;
			fNodeCount = asLong(counts.get("nodes"));
			fBinaryCount = asLong(counts.get("blobs"));
		}
	}

	private static long asLong(Object value) {
		return (value instanceof Number) ? ((Number) value).longValue() : 0L;
	}

	/**
	 * Whether a mixin puts a node under version control. These are skipped on
	 * import — see {@link #applyMixins}.
	 */
	private static boolean isVersionControlMixin(String mixin) {
		return "mix:versionable".equals(mixin) || "mix:simpleVersionable".equals(mixin);
	}

	private BufferedReader openNodes(ZipFile zip) throws Exception {
		ZipEntry entry = zip.getEntry(ArchiveManifest.NODES_ENTRY);
		return new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8));
	}

	private Map<String, Object> parse(String line) throws Exception {
		@SuppressWarnings("unchecked")
		Map<String, Object> m = fMapper.readValue(line, Map.class);
		return m;
	}

	private Map<String, Object> parseStream(InputStream in) throws Exception {
		@SuppressWarnings("unchecked")
		Map<String, Object> m = fMapper.readValue(in, Map.class);
		return m;
	}

	/**
	 * Parse an archive timestamp ({@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}, UTC) into
	 * a {@link Calendar}, or {@code null} when absent. Symmetric with
	 * {@link NodeSerializer}'s date formatting.
	 */
	private static Calendar parseIsoDate(String iso) {
		if (iso == null || iso.isEmpty()) {
			return null;
		}
		java.util.Calendar cal = new java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"));
		cal.setTimeInMillis(java.time.Instant.parse(iso).toEpochMilli());
		return cal;
	}

	private static String parentPath(String path) {
		int idx = path.lastIndexOf('/');
		if (idx <= 0) {
			return "/";
		}
		return path.substring(0, idx);
	}

	private static String childPath(String parent, String name) {
		return parent.endsWith("/") ? parent + name : parent + "/" + name;
	}

	private void writeProgress(Node content, Session progressSession, String currentPath, boolean force)
			throws Exception {
		long now = System.currentTimeMillis();
		long processed = fNew + fOverwritten + fSkipped + fError;
		boolean shouldWrite = force
				|| (processed - JobNodes.getLong(content, JobNodes.PROP_ITEMS_IMPORTED, 0L)) >= PROGRESS_THROTTLE_ITEMS
				|| (now - fLastWriteAt) >= PROGRESS_THROTTLE_MILLIS;
		if (!shouldWrite) {
			return;
		}
		content.setProperty(JobNodes.PROP_ITEMS_IMPORTED, processed);
		if (currentPath != null) {
			content.setProperty(JobNodes.PROP_CURRENT_PATH, currentPath);
		}
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		progressSession.save();
		fLastWriteAt = now;
	}

	private void markFailedWithSystemSession(String jobId, String workspace, Throwable cause) {
		Session sysSession = null;
		try {
			sysSession = CmsService.getRepository().login(new CmsServiceCredentials(fUserId), workspace);
			Node fileNode = JobNodes.getJobNode(sysSession, jobId);
			if (fileNode == null) {
				return;
			}
			Node content = JobNodes.getContent(fileNode);
			content.setProperty(JobNodes.PROP_ERROR_MESSAGE,
					cause != null && cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause));
			content.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
			JobNodes.setStatus(content, JobStatus.FAILED);
			sysSession.save();
		} catch (Throwable ex) {
			CmsService.getLogger(ImportArchiveJob.class).error(
					"ImportArchiveJob " + jobId + " — fallback finaliser failed", ex);
		} finally {
			if (sysSession != null) {
				try { sysSession.logout(); } catch (Throwable ignore) {}
			}
		}
	}

	private static final class AbortedException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}

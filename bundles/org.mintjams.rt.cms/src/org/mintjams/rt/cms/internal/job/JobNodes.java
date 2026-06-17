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

package org.mintjams.rt.cms.internal.job;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.io.IOUtils;
import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.tools.adapter.Adaptables;

/**
 * Utility for the JCR persistence of generic background jobs.
 *
 * Job nodes are kept under {@code /var/jobs/YYYY/MM/job-<jobId>} as
 * {@code nt:file}. Status, counters and metadata live as properties on the
 * {@code jcr:content} child; the binary body holds the job-specific payload
 * (for delete jobs, one absolute path per line).
 *
 * The directory layout is computed deterministically from the millis-prefix
 * of the jobId so lookup is a direct path resolution — no scan required.
 */
public final class JobNodes {

	public static final String JOBS_ROOT = "/var/jobs";

	public static final String PROP_JOB_ID = "jobId";
	public static final String PROP_JOB_TYPE = "jobType";
	public static final String PROP_JOB_STATUS = "jobStatus";
	public static final String PROP_JOB_USER_ID = "jobUserId";
	public static final String PROP_JOB_PRIORITY = "jobPriority";
	public static final String PROP_ITEMS_TOTAL = "jobItemsTotal";
	public static final String PROP_ITEMS_PROCESSED = "jobItemsProcessed";
	/** Delete jobs: running count of items removed (nt:file and nt:folder only). */
	public static final String PROP_ITEMS_DELETED = "jobItemsDeleted";
	/** Archive jobs: running count of files written into the ZIP. */
	public static final String PROP_ITEMS_ARCHIVED = "jobItemsArchived";
	public static final String PROP_CURRENT_PATH = "jobCurrentPath";
	public static final String PROP_ERROR_MESSAGE = "jobErrorMessage";
	/**
	 * Coarse, human-meaningful phase of a multi-step job (e.g. workspace
	 * lifecycle jobs publish {@code creating}/{@code starting}/{@code stopping}/
	 * {@code deleting}). Unlike {@link #PROP_JOB_STATUS} — which is the generic
	 * lifecycle (RUNNING/COMPLETED/FAILED/…) the manager and recovery reason
	 * about — the phase is a job-type-specific label the UI maps to a localized
	 * progress message. Absent on jobs that have no sub-phases.
	 */
	public static final String PROP_PHASE = "jobPhase";
	/**
	 * The workspace a lifecycle job acts upon. Distinct from the job's own
	 * {@link #PROP_JOB_USER_ID owning} workspace (where the {@code /var/jobs}
	 * record lives): a delete job's record must outlive the very workspace it
	 * removes, so the two are never the same node's workspace.
	 */
	public static final String PROP_TARGET_WORKSPACE = "jobTargetWorkspace";
	public static final String PROP_STARTED_AT = "jobStartedAt";
	public static final String PROP_FINISHED_AT = "jobFinishedAt";
	/** Archive jobs: file name to expose to the browser when the ZIP is downloaded. */
	public static final String PROP_ARCHIVE_FILENAME = "jobArchiveFilename";
	/**
	 * Archive jobs: when true (the default), the ZIP also carries the
	 * {@code .cms-archive/} restore sidecar (properties, mixins, references,
	 * binaries) so the download doubles as a backup. Set false for a bare
	 * file-only ZIP.
	 */
	public static final String PROP_INCLUDE_METADATA = "jobIncludeMetadata";
	/**
	 * Archive jobs: when true, the sidecar also carries {@code acl.ndjson} so a
	 * restore can reinstate access control. Off by default. Implies
	 * {@link #PROP_INCLUDE_METADATA}.
	 */
	public static final String PROP_INCLUDE_ACL = "jobIncludeAcl";

	// Import/restore jobs ----------------------------------------------------
	/** Repository path of the uploaded CMS Archive ({@code nt:file}) to restore. */
	public static final String PROP_ARCHIVE_PATH = "jobArchivePath";
	/** Destination path the archive is restored under. */
	public static final String PROP_DEST_PATH = "jobDestPath";
	/** {@code preserve} (keep original UUIDs) or {@code new} (allocate fresh). */
	public static final String PROP_IDENTITY = "jobIdentity";
	/** When identity is {@code preserve}: {@code throw}/{@code replace}/{@code remove}. */
	public static final String PROP_UUID_COLLISION = "jobUuidCollision";
	/** Path conflict policy: {@code skip}/{@code overwrite}/{@code merge}/{@code rename}. */
	public static final String PROP_PATH_CONFLICT = "jobPathConflict";
	/**
	 * Identifier-conflict behaviour, as the integer codes of
	 * {@link org.mintjams.jcr.ImportContentHandler} ({@code 0} throw on collision,
	 * {@code 1} new on collision, {@code 2} always new). Supersedes the older
	 * {@link #PROP_IDENTITY}/{@link #PROP_UUID_COLLISION} pair.
	 */
	public static final String PROP_UUID_BEHAVIOR = "jobUuidBehavior";
	/**
	 * Path-conflict behaviour, as the integer codes of
	 * {@link org.mintjams.jcr.ImportContentHandler} ({@code 0} throw on conflict,
	 * {@code 1} skip, {@code 2} overwrite). Supersedes the older
	 * {@link #PROP_PATH_CONFLICT}.
	 */
	public static final String PROP_PATH_BEHAVIOR = "jobPathBehavior";
	/** Restore access control lists carried by the archive. */
	public static final String PROP_RESTORE_ACL = "jobRestoreAcl";
	/**
	 * Carry over each node's original {@code jcr:created}/{@code jcr:lastModified}
	 * from the archive (default true). When false the repository stamps the import
	 * time. {@code jcr:createdBy}/{@code jcr:lastModifiedBy} are always the
	 * importing user regardless.
	 */
	public static final String PROP_PRESERVE_TIMESTAMPS = "jobPreserveTimestamps";
	/** Validate and report only; make no changes. */
	public static final String PROP_DRY_RUN = "jobDryRun";
	/**
	 * Dry run result: {@code true} when the rehearsal hit a problem that would
	 * make the real restore fail (e.g. a UUID collision under the {@code throw}
	 * policy, an unknown node type). The job still completes — the finding is
	 * reported, not raised as a system failure. Absent on real (non-dry) runs.
	 */
	public static final String PROP_DRY_RUN_HAS_ERRORS = "jobDryRunHasErrors";
	/** Dry run result: human-readable detail of the blocking problem, when {@link #PROP_DRY_RUN_HAS_ERRORS} is true. */
	public static final String PROP_DRY_RUN_DETAIL = "jobDryRunDetail";
	/** Dry run result: number of nodes the archive would restore (from the manifest). */
	public static final String PROP_DRY_RUN_NODE_COUNT = "jobDryRunNodeCount";
	/** Dry run result: number of binaries the archive carries (from the manifest). */
	public static final String PROP_DRY_RUN_BINARY_COUNT = "jobDryRunBinaryCount";
	/** Import jobs: running count of nodes created/updated. */
	public static final String PROP_ITEMS_RESTORED = "jobItemsRestored";
	/**
	 * Import jobs: per-file outcome counts. The unit is the file the user sees —
	 * each {@code nt:file} in the archive falls into exactly one bucket, so the
	 * four always sum to the number of files in the archive. "Overwrite" counts
	 * the files the policy directed to be overwritten whether or not their bytes
	 * actually changed; "error" never double-counts a file already counted as
	 * new or overwrite.
	 */
	public static final String PROP_ITEMS_NEW = "jobItemsNew";
	public static final String PROP_ITEMS_OVERWRITTEN = "jobItemsOverwritten";
	public static final String PROP_ITEMS_SKIPPED = "jobItemsSkipped";
	public static final String PROP_ITEMS_ERROR = "jobItemsError";
	/**
	 * Import jobs: up to the first 20 errors, for display on the completion
	 * screen. Multi-valued; each value is {@code path + '\t' + message}. The full
	 * set is in the downloadable CSV report (see {@link #PROP_DOWNLOAD_URL}).
	 */
	public static final String PROP_ERROR_SAMPLES = "jobErrorSamples";
	/** Import jobs: the original file name of the uploaded archive. */
	public static final String PROP_IMPORT_FILENAME = "jobImportFilename";
	/**
	 * Import jobs: the requester's UI locale at submit time (e.g. {@code "ja"},
	 * {@code "en"}, {@code "ja-jp"}). Used to localize the strings the job itself
	 * produces in the CSV report — the {@code 処理} column labels and its own error
	 * messages. Empty/absent falls back to English.
	 */
	public static final String PROP_REPORT_LOCALE = "jobReportLocale";
	/** Set on completion by jobs whose result is a downloadable artifact (e.g. archive jobs). */
	public static final String PROP_DOWNLOAD_URL = "jobDownloadUrl";
	/**
	 * The cluster node that queued/executes the job. Queues and workers are
	 * node-local, so this is what lets a restarted node recover exactly its
	 * own jobs and leave the other nodes' running jobs alone.
	 */
	public static final String PROP_NODE_ID = "jobNodeId";

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final DateTimeFormatter YEAR_FMT = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC);

	private JobNodes() {}

	/**
	 * Generate a globally-unique job id of the form
	 * {@code <epochMillis>-<4hex>}. The millis prefix lets the YYYY/MM folder
	 * be reconstructed without consulting JCR.
	 */
	public static String newJobId() {
		long millis = System.currentTimeMillis();
		int rnd = RANDOM.nextInt(0x10000);
		return String.format("%d-%04x", millis, rnd);
	}

	/**
	 * Resolve the absolute job-node path for a jobId.
	 *
	 * Job ids must be produced by {@link #newJobId()} — they encode an
	 * epoch-millis prefix that's decoded back into a YYYY/MM folder pair so
	 * lookup never requires a JCR scan.  Ids without a parseable millis prefix
	 * are rejected; the caller is expected to mint a fresh id rather than
	 * route the work into a fallback bucket.
	 */
	public static String jobNodePath(String jobId) {
		long millis = parseMillis(jobId);
		Instant t = Instant.ofEpochMilli(millis);
		return JOBS_ROOT + "/" + YEAR_FMT.format(t) + "/" + MONTH_FMT.format(t) + "/job-" + jobId;
	}

	/**
	 * Resolve the absolute path of the archive artifact produced by an archive
	 * job. Stored as an {@code nt:file} sibling of the job node so it shares the
	 * job's YYYY/MM bucket but never matches {@link #isJobPath(String, String)}
	 * (the prefix is {@code archive-} rather than {@code job-}), keeping its
	 * writes out of the {@code jobProgress} event stream.
	 */
	public static String archiveNodePath(String jobId) {
		String jobNodePath = jobNodePath(jobId);
		String parentPath = jobNodePath.substring(0, jobNodePath.lastIndexOf('/'));
		return parentPath + "/archive-" + jobId;
	}

	/**
	 * Resolve the absolute path of the CSV outcome report produced by an import
	 * job. Like {@link #archiveNodePath(String)} it is an {@code nt:file} sibling
	 * of the job node (prefix {@code report-}), so it shares the YYYY/MM bucket
	 * but never matches {@link #isJobPath(String, String)} and so stays out of the
	 * {@code jobProgress} event stream.
	 */
	public static String reportNodePath(String jobId) {
		String jobNodePath = jobNodePath(jobId);
		String parentPath = jobNodePath.substring(0, jobNodePath.lastIndexOf('/'));
		return parentPath + "/report-" + jobId;
	}

	private static long parseMillis(String jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		int dash = jobId.indexOf('-');
		if (dash <= 0) {
			throw new IllegalArgumentException("jobId is not in <millis>-<hex> form: " + jobId);
		}
		try {
			return Long.parseLong(jobId.substring(0, dash));
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("jobId is not in <millis>-<hex> form: " + jobId, ex);
		}
	}

	/**
	 * Create a new job node in INIT status with an empty body. The caller is
	 * responsible for {@link Session#save() saving} the session.
	 */
	public static Node createJobNode(Session session, String jobId, String jobType, String userId, int priority)
			throws RepositoryException, IOException {
		String nodePath = jobNodePath(jobId);
		String parentPath = nodePath.substring(0, nodePath.lastIndexOf('/'));

		Node parent = JCRs.getOrCreateFolder(JcrPath.valueOf(parentPath), session);
		String fileName = nodePath.substring(nodePath.lastIndexOf('/') + 1);
		Node fileNode = parent.addNode(fileName, "nt:file");
		Node content = fileNode.addNode("jcr:content", "nt:resource");

		try (InputStream in = new ByteArrayInputStream(new byte[0])) {
			JCRs.write(fileNode, in);
		}
		content.setProperty("jcr:mimeType", "text/plain");
		content.setProperty("jcr:encoding", "UTF-8");
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		content.setProperty("jcr:lastModifiedBy", session.getUserID());

		content.setProperty(PROP_JOB_ID, jobId);
		content.setProperty(PROP_JOB_TYPE, jobType);
		content.setProperty(PROP_JOB_STATUS, JobStatus.INIT.toExternalString());
		content.setProperty(PROP_JOB_USER_ID, userId);
		content.setProperty(PROP_JOB_PRIORITY, (long) priority);
		content.setProperty(PROP_ITEMS_TOTAL, 0L);
		content.setProperty(PROP_ITEMS_PROCESSED, 0L);
		// The leaf counter is job-type-specific (PROP_ITEMS_DELETED for delete,
		// PROP_ITEMS_ARCHIVED for archive) and is initialised by the job itself
		// when it starts running, so it never appears on a job that has no such
		// notion (e.g. archive jobs must not advertise a delete count).

		return fileNode;
	}

	public static Node getJobNode(Session session, String jobId) throws RepositoryException {
		String path = jobNodePath(jobId);
		if (!session.nodeExists(path)) {
			return null;
		}
		return session.getNode(path);
	}

	public static Node getContent(Node jobFileNode) throws RepositoryException {
		return jobFileNode.getNode("jcr:content");
	}

	public static JobStatus getStatus(Node content) throws RepositoryException {
		if (!content.hasProperty(PROP_JOB_STATUS)) {
			return null;
		}
		return JobStatus.fromExternalString(content.getProperty(PROP_JOB_STATUS).getString());
	}

	public static void setStatus(Node content, JobStatus status) throws RepositoryException {
		content.setProperty(PROP_JOB_STATUS, status.toExternalString());
		content.setProperty("jcr:lastModified", Calendar.getInstance());
	}

	/**
	 * Records this node as the owner of the job. Caller saves the session.
	 */
	public static void setNodeId(Session session, Node content) throws RepositoryException {
		String nodeId = getCurrentNodeId(session);
		if (nodeId != null) {
			content.setProperty(PROP_NODE_ID, nodeId);
		}
	}

	/**
	 * Returns the identifier of the node this session runs on, or
	 * {@code null} when the repository does not expose one.
	 */
	public static String getCurrentNodeId(Session session) {
		ClusterCoordinator coordinator = Adaptables.getAdapter(session, ClusterCoordinator.class);
		return (coordinator == null) ? null : coordinator.getNodeId();
	}

	/**
	 * Read the current body as a list of trimmed, non-empty lines.
	 */
	public static List<String> readPaths(Node content) throws RepositoryException, IOException {
		Binary binary = content.getProperty("jcr:data").getBinary();
		try (InputStream in = binary.getStream()) {
			String text = IOUtils.toString(in, StandardCharsets.UTF_8);
			return splitLines(text);
		} finally {
			binary.dispose();
		}
	}

	private static List<String> splitLines(String text) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return out;
		}
		for (String line : text.split("\\r?\\n")) {
			String t = line.trim();
			if (!t.isEmpty()) {
				out.add(t);
			}
		}
		return out;
	}

	/**
	 * Append the given paths to the body and update {@link #PROP_ITEMS_TOTAL}.
	 * Caller saves the session.
	 */
	public static int appendPaths(Node fileNode, List<String> paths) throws RepositoryException, IOException {
		Node content = getContent(fileNode);
		List<String> existing = readPaths(content);
		int accepted = 0;
		StringBuilder sb = new StringBuilder();
		for (String p : existing) {
			sb.append(p).append('\n');
		}
		for (String raw : paths) {
			if (raw == null) continue;
			String t = raw.trim();
			if (t.isEmpty()) continue;
			sb.append(t).append('\n');
			accepted++;
		}
		byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
		try (InputStream in = new ByteArrayInputStream(bytes)) {
			JCRs.write(fileNode, in);
		}
		content.setProperty(PROP_ITEMS_TOTAL, (long) (existing.size() + accepted));
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		return accepted;
	}

	public static long getLong(Node content, String name, long defaultValue) throws RepositoryException {
		if (!content.hasProperty(name)) {
			return defaultValue;
		}
		return content.getProperty(name).getLong();
	}

	public static String getString(Node content, String name, String defaultValue) throws RepositoryException {
		if (!content.hasProperty(name)) {
			return defaultValue;
		}
		return content.getProperty(name).getString();
	}

	public static boolean getBoolean(Node content, String name, boolean defaultValue) throws RepositoryException {
		if (!content.hasProperty(name)) {
			return defaultValue;
		}
		return content.getProperty(name).getBoolean();
	}

	/**
	 * True when the given absolute event path identifies the {@code jcr:content}
	 * (or the {@code job-} file) of a job whose id is {@code jobId}. Used by the
	 * GraphQL subscription matcher to route job-node changes to {@code jobProgress}
	 * subscribers without requiring the subscriber to know the YYYY/MM folder.
	 */
	public static boolean isJobPath(String eventPath, String jobId) {
		if (eventPath == null || jobId == null) {
			return false;
		}
		String marker = "/job-" + jobId;
		return eventPath.endsWith(marker)
				|| eventPath.endsWith(marker + "/jcr:content")
				|| eventPath.contains(marker + "/");
	}
}

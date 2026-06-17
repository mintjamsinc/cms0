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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

import org.apache.commons.io.IOUtils;
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

/**
 * Bundles a list of JCR nodes (files and/or folders, typically chosen in the
 * Content Browser) into a single ZIP archive.
 *
 * The list of top-level paths to archive is read from the body of the job
 * node's {@code jcr:content} — one absolute path per line — written there by
 * the preceding {@code appendDownloadArchive} mutations. Folders are walked
 * recursively; each entry is named relative to its top-level item's parent so
 * the selected folder structure (but not the absolute repository path) is
 * preserved inside the archive.
 *
 * Source content is read with the requesting user's session so a user can only
 * archive what they are allowed to read. The finished ZIP is written as an
 * {@code nt:file} sibling of the job node (see {@link JobNodes#archiveNodePath})
 * using the privileged progress session, and {@link ArchiveDownloadServlet}
 * streams it back to the owner. The download URL is published on the terminal
 * {@code jobProgress} event so the client can fetch it without a follow-up
 * query.
 *
 * Progress (the path currently being archived, the running file count) is
 * written to the job node on a throttle so {@code jobProgress} subscribers see
 * live feedback without one event per file.
 */
public class ArchiveJob implements Job {

	public static final String TYPE = "archive";

	/** Throttle: write progress to the job node every N archived items. */
	private static final long PROGRESS_THROTTLE_ITEMS = 25L;
	/** Throttle: also write progress when this much wall time has elapsed. */
	private static final long PROGRESS_THROTTLE_MILLIS = 300L;

	private final String fJobId;
	private final String fWorkspaceName;
	private final String fUserId;
	private final int fPriority;

	private long fItemsTotal;
	private long fItemsProcessed;
	private long fItemsArchived;
	private long fLastWriteAt;

	/**
	 * When true (the default), the ZIP also carries the {@code .cms-archive/}
	 * sidecar that makes it a restorable backup rather than a bare file dump.
	 * The content tree is written identically either way, so a plain download
	 * still opens as ordinary files.
	 */
	private boolean fIncludeMetadata;
	/**
	 * When true, the sidecar also carries {@code acl.ndjson} — each node's
	 * explicit access control list — so a restore can reinstate permissions.
	 * Off by default; ACL is only meaningful to privileged operators.
	 */
	private boolean fIncludeAcl;
	/** Local working area for the sidecar before it is folded into the ZIP. */
	private Path fWorkDir;
	private Writer fNodesWriter;
	private Writer fAclWriter;
	private NodeSerializer fSerializer;
	private final com.fasterxml.jackson.databind.ObjectMapper fMapper =
			new com.fasterxml.jackson.databind.ObjectMapper();
	private long fNodeCount;

	public ArchiveJob(String jobId, String workspaceName, String userId, int priority) {
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
		context.getLogger().info("ArchiveJob " + fJobId + " execute() entered for workspace=" + fWorkspaceName
				+ " user=" + fUserId);
		Session jobSession;
		Session progressSession;
		try {
			jobSession = context.getJobSession();
			progressSession = context.getProgressSession();
		} catch (Throwable ex) {
			context.getLogger().error("ArchiveJob " + fJobId + " could not open sessions", ex);
			markFailedWithSystemSession(fJobId, fWorkspaceName, ex);
			return;
		}

		List<String> paths = null;
		Node progressContent = null;
		String initError = null;
		try {
			Node fileNode = JobNodes.getJobNode(progressSession, fJobId);
			if (fileNode == null) {
				context.getLogger().warn("Job node missing for " + fJobId + "; aborting.");
				return;
			}
			progressContent = JobNodes.getContent(fileNode);

			paths = JobNodes.readPaths(progressContent);
			fItemsTotal = paths.size();
			fIncludeMetadata = JobNodes.getBoolean(progressContent, JobNodes.PROP_INCLUDE_METADATA, true);
			fIncludeAcl = fIncludeMetadata
					&& JobNodes.getBoolean(progressContent, JobNodes.PROP_INCLUDE_ACL, false);

			JobNodes.setStatus(progressContent, JobStatus.RUNNING);
			progressContent.setProperty(JobNodes.PROP_STARTED_AT, Calendar.getInstance());
			progressContent.setProperty(JobNodes.PROP_ITEMS_ARCHIVED, fItemsArchived);
			progressSession.save();
		} catch (Throwable ex) {
			initError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			context.getLogger().error("ArchiveJob " + fJobId + " could not be initialised", ex);
		}

		boolean aborted = false;
		String errorMessage = initError;
		fLastWriteAt = System.currentTimeMillis();
		Path tempZip = null;

		if (initError == null && paths != null) {
			try {
				if (fIncludeMetadata) {
					openMetadata(jobSession);
				}
				tempZip = Files.createTempFile("cms-archive-" + fJobId + "-", ".zip");
				try (ZipOutputStream zos = new ZipOutputStream(
						new BufferedOutputStream(Files.newOutputStream(tempZip)))) {
					for (int i = 0; i < paths.size(); i++) {
						if (context.isAborted()) {
							aborted = true;
							break;
						}
						String top = paths.get(i);
						try {
							if (jobSession.nodeExists(top)) {
								Node node = jobSession.getNode(top);
								archiveRecursively(zos, node, parentPath(top), context,
										progressContent, progressSession);
							}
						} catch (AbortedException ex) {
							aborted = true;
							break;
						}
						fItemsProcessed = i + 1L;
						writeProgress(progressContent, progressSession, null, true);
					}

					// Fold the restore sidecar into the same ZIP. Skipped on abort
					// so a cancelled archive never advertises itself as restorable.
					if (fIncludeMetadata && !aborted) {
						writeMetadataEntries(zos, paths);
					}
				}

				if (!aborted) {
					storeArchive(progressSession, tempZip);
				}
			} catch (Throwable ex) {
				errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
				context.getLogger().error("ArchiveJob " + fJobId + " failed at item "
						+ fItemsProcessed + " of " + fItemsTotal, ex);
			} finally {
				closeMetadata();
				if (tempZip != null) {
					try { Files.deleteIfExists(tempZip); } catch (Throwable ignore) {}
				}
			}
		}

		// Always try to finalise so the client subscription gets a terminal event.
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
					progressContent.setProperty(JobNodes.PROP_DOWNLOAD_URL,
							CmsConfiguration.DOWNLOAD_ARCHIVE_CGI_PATH
									+ Webs.encodePath("/" + fWorkspaceName + "/" + fJobId));
				}
				progressContent.setProperty(JobNodes.PROP_ITEMS_PROCESSED, fItemsProcessed);
				progressContent.setProperty(JobNodes.PROP_ITEMS_ARCHIVED, fItemsArchived);
				progressContent.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
				JobNodes.setStatus(progressContent, finalStatus);
				progressSession.save();
			}
		} catch (Throwable ex) {
			context.getLogger().error("ArchiveJob " + fJobId + " could not finalise status", ex);
		}
	}

	/**
	 * Add {@code node} and (for folders) all of its descendants to the ZIP.
	 * Entry names are computed relative to {@code basePath} (the parent of the
	 * top-level selected item) so the selected structure is preserved.
	 */
	private void archiveRecursively(ZipOutputStream zos, Node node, String basePath, JobContext context,
			Node progressContent, Session progressSession) throws Exception {
		if (context.isAborted()) {
			throw new AbortedException();
		}

		if (JCRs.isFile(node)) {
			String entryName = relativeEntryName(basePath, node.getPath());
			writeProgress(progressContent, progressSession, node.getPath(), false);
			zos.putNextEntry(new ZipEntry(entryName));
			try (InputStream in = JCRs.getContentAsStream(node)) {
				IOUtils.copy(in, zos);
			}
			zos.closeEntry();
			fItemsArchived++;
			if (fIncludeMetadata) {
				// The nt:file node, then its jcr:content (whose jcr:data is the body
				// already written above) and any further descendants — so the file's
				// metadata round-trips while its bytes stay in the content tree.
				writeMetaLine(node, entryName.isEmpty() ? null : entryName, false);
				writeAclLine(node);
				for (NodeIterator it = node.getNodes(); it.hasNext();) {
					writeMetaSubtree(it.nextNode(), true);
				}
			}
			writeProgress(progressContent, progressSession, node.getPath(), false);
			return;
		}

		// Treat everything else as a container: emit a directory entry so empty
		// folders survive the round-trip, then recurse into the children.
		String dirEntry = relativeEntryName(basePath, node.getPath());
		if (!dirEntry.isEmpty()) {
			zos.putNextEntry(new ZipEntry(dirEntry.endsWith("/") ? dirEntry : dirEntry + "/"));
			zos.closeEntry();
		}
		if (fIncludeMetadata) {
			writeMetaLine(node, dirEntry.isEmpty() ? null : dirEntry, false);
			writeAclLine(node);
		}
		for (NodeIterator it = node.getNodes(); it.hasNext();) {
			archiveRecursively(zos, it.nextNode(), basePath, context, progressContent, progressSession);
		}
	}

	/** Open the local working area that accumulates the {@code .cms-archive/} sidecar. */
	private void openMetadata(Session session) throws Exception {
		fWorkDir = Files.createTempDirectory("cms-archive-meta-" + fJobId + "-");
		fNodesWriter = new BufferedWriter(new java.io.OutputStreamWriter(
				Files.newOutputStream(fWorkDir.resolve("nodes.ndjson")), StandardCharsets.UTF_8));
		// The serializer omits exactly what the repository protects by name, so a
		// restore can set back everything it writes (see NodeSerializer).
		fSerializer = new NodeSerializer(fWorkDir.resolve("blobs"),
				NodeSerializer.collectProtectedPropertyNames(session));
		if (fIncludeAcl) {
			fAclWriter = new BufferedWriter(new java.io.OutputStreamWriter(
					Files.newOutputStream(fWorkDir.resolve("acl.ndjson")), StandardCharsets.UTF_8));
		}
	}

	/**
	 * Append a node's access control list to {@code acl.ndjson}, when ACL export
	 * is enabled and the node carries explicit entries the user may read. A
	 * node whose ACL cannot be read is skipped rather than failing the archive.
	 */
	private void writeAclLine(Node node) throws Exception {
		if (!fIncludeAcl) {
			return;
		}
		Map<String, Object> acl = AclCodec.toMap(node);
		if (acl == null) {
			return;
		}
		fAclWriter.write(fMapper.writeValueAsString(acl));
		fAclWriter.write("\n");
	}

	/** Append one node's metadata as a line of {@code nodes.ndjson}. */
	private void writeMetaLine(Node node, String entry, boolean fileBodyNode) throws Exception {
		fNodesWriter.write(fSerializer.toLine(node, entry, fileBodyNode));
		fNodesWriter.write("\n");
		fNodeCount++;
	}

	/**
	 * Serialise a metadata-only subtree (no content-tree entry of its own), used
	 * for an {@code nt:file}'s {@code jcr:content} and anything beneath it. The
	 * content node's {@code jcr:data} is recorded as the body marker rather than
	 * duplicated.
	 */
	private void writeMetaSubtree(Node node, boolean parentIsFile) throws Exception {
		boolean fileBodyNode = parentIsFile && "jcr:content".equals(node.getName());
		writeMetaLine(node, null, fileBodyNode);
		for (NodeIterator it = node.getNodes(); it.hasNext();) {
			writeMetaSubtree(it.nextNode(), false);
		}
	}

	/**
	 * Fold the accumulated sidecar (node metadata, spilled blobs, manifest) into
	 * the open ZIP under {@code .cms-archive/}.
	 */
	private void writeMetadataEntries(ZipOutputStream zos, List<String> roots) throws Exception {
		fNodesWriter.flush();
		fNodesWriter.close();
		fNodesWriter = null;

		zos.putNextEntry(new ZipEntry(ArchiveManifest.NODES_ENTRY));
		try (InputStream in = Files.newInputStream(fWorkDir.resolve("nodes.ndjson"))) {
			IOUtils.copy(in, zos);
		}
		zos.closeEntry();

		if (fAclWriter != null) {
			fAclWriter.flush();
			fAclWriter.close();
			fAclWriter = null;
			Path aclFile = fWorkDir.resolve("acl.ndjson");
			if (Files.size(aclFile) > 0) {
				zos.putNextEntry(new ZipEntry(ArchiveManifest.ACL_ENTRY));
				try (InputStream in = Files.newInputStream(aclFile)) {
					IOUtils.copy(in, zos);
				}
				zos.closeEntry();
			}
		}

		Path blobsDir = fWorkDir.resolve("blobs");
		if (Files.isDirectory(blobsDir)) {
			List<String> names = new ArrayList<>();
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(blobsDir)) {
				for (Path p : ds) {
					names.add(p.getFileName().toString());
				}
			}
			names.sort(String::compareTo);
			for (String name : names) {
				zos.putNextEntry(new ZipEntry(ArchiveManifest.BLOBS_DIR + "/" + name));
				try (InputStream in = Files.newInputStream(blobsDir.resolve(name))) {
					IOUtils.copy(in, zos);
				}
				zos.closeEntry();
			}
		}

		zos.putNextEntry(new ZipEntry(ArchiveManifest.MANIFEST_ENTRY));
		new ArchiveManifest()
				.createdBy(fUserId)
				.workspace(fWorkspaceName)
				.roots(roots)
				.withProperties(true)
				.withBinaries(true)
				.withAcl(fIncludeAcl)
				.nodeCount(fNodeCount)
				.blobCount(fSerializer.getBlobCount())
				.writeTo(zos);
		zos.closeEntry();
	}

	/** Close the metadata writer (if still open) and remove the working area. */
	private void closeMetadata() {
		if (fNodesWriter != null) {
			try { fNodesWriter.close(); } catch (Throwable ignore) {}
			fNodesWriter = null;
		}
		if (fAclWriter != null) {
			try { fAclWriter.close(); } catch (Throwable ignore) {}
			fAclWriter = null;
		}
		if (fWorkDir != null) {
			deleteRecursively(fWorkDir);
			fWorkDir = null;
		}
	}

	private static void deleteRecursively(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
			walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
					.forEach(p -> {
						try { Files.deleteIfExists(p); } catch (Throwable ignore) {}
					});
		} catch (Throwable ignore) {}
	}

	/**
	 * Write the finished ZIP into the repository as an {@code nt:file} sibling of
	 * the job node so {@link ArchiveDownloadServlet} can stream it back later.
	 */
	private void storeArchive(Session session, Path tempZip) throws Exception {
		String nodePath = JobNodes.archiveNodePath(fJobId);
		String parentPath = nodePath.substring(0, nodePath.lastIndexOf('/'));
		String nodeName = nodePath.substring(nodePath.lastIndexOf('/') + 1);

		Node parent = JCRs.getOrCreateFolder(JcrPath.valueOf(parentPath), session);
		Node fileNode = JCRs.createFile(parent, nodeName);
		try (InputStream in = new BufferedInputStream(Files.newInputStream(tempZip))) {
			JCRs.write(fileNode, in);
		}
		Node content = JCRs.getContentNode(fileNode);
		content.setProperty("jcr:mimeType", "application/zip");
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		content.setProperty("jcr:lastModifiedBy", session.getUserID());
		session.save();
	}

	private void writeProgress(Node content, Session progressSession, String currentPath, boolean force)
			throws Exception {
		long now = System.currentTimeMillis();
		boolean shouldWrite = force
				|| (fItemsArchived - JobNodes.getLong(content, JobNodes.PROP_ITEMS_ARCHIVED, 0L)) >= PROGRESS_THROTTLE_ITEMS
				|| (now - fLastWriteAt) >= PROGRESS_THROTTLE_MILLIS;
		if (!shouldWrite) {
			return;
		}
		content.setProperty(JobNodes.PROP_ITEMS_PROCESSED, fItemsProcessed);
		content.setProperty(JobNodes.PROP_ITEMS_ARCHIVED, fItemsArchived);
		if (currentPath != null) {
			content.setProperty(JobNodes.PROP_CURRENT_PATH, currentPath);
		}
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		progressSession.save();
		fLastWriteAt = now;
	}

	private static String parentPath(String path) {
		int idx = path.lastIndexOf('/');
		if (idx <= 0) {
			return "/";
		}
		return path.substring(0, idx);
	}

	private static String relativeEntryName(String basePath, String fullPath) {
		String prefix = basePath.endsWith("/") ? basePath : basePath + "/";
		if (fullPath.startsWith(prefix)) {
			return fullPath.substring(prefix.length());
		}
		int idx = fullPath.lastIndexOf('/');
		return idx >= 0 ? fullPath.substring(idx + 1) : fullPath;
	}

	/**
	 * Last-resort path to record a terminal status when even opening a
	 * user-scoped progress session has failed, so the JCR record never sticks in
	 * {@code queued} and the client is guaranteed a final {@code jobProgress}
	 * event.
	 */
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
			CmsService.getLogger(ArchiveJob.class).error(
					"ArchiveJob " + jobId + " — fallback finaliser failed", ex);
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

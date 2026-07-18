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

package org.mintjams.rt.cms.internal.job.delete;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.Session;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.Job;
import org.mintjams.rt.cms.internal.job.JobContext;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

/**
 * Deletes a list of JCR nodes (typically chosen in the Content Browser).
 * The list of paths to delete is read from the body of the job node's
 * {@code jcr:content} — one absolute path per line — written there by the
 * preceding {@code appendDeleteNodes} mutations.
 *
 * Removal is set-based: a container's children are handed to
 * {@code org.mintjams.jcr.Node#removeChildTrees()} {@link #REMOVE_BATCH_ITEMS}
 * at a time, which removes them — with their subtrees, version histories and
 * binaries — in a handful of bulk statements instead of the 15+ per node the
 * per-node path costs. This is what makes deleting large trees fast. Children
 * whose subtree needs per-node checks (descendant ACLs or foreign locks) fall
 * back to the recursion below.
 *
 * The job walks the tree itself and empties each container depth first, rather
 * than offering a requested path to {@code removeTree()} whole. A single
 * removeTree() of a large subtree is one unbounded unit: it collects the entire
 * subtree in memory before its first statement, commits it in one transaction,
 * and reports one path for the lot. Batching the walk bounds all three by
 * {@link #REMOVE_BATCH_ITEMS} while keeping the statements set-based.
 *
 * Removals are committed a batch at a time; what is left to the per-node path
 * is committed in bounded batches instead ({@link #SAVE_BATCH_NODES} nodes or
 * {@link #SAVE_BATCH_MILLIS}, whichever comes first). Every {@code save()}
 * carries a fixed overhead — fsync, referential-integrity validation, journal
 * observers, index updates — so the earlier one-transaction-per-node scheme
 * paid it once per node (and twice per file: {@code jcr:content} was removed
 * in its own transaction), which dominated large deletes. Batching keeps what
 * that scheme provided:
 * <ul>
 *   <li>nodeChanged subscriptions still fire once per batch while work
 *       progresses (live UI feedback);</li>
 *   <li>aborts are checked per batch and take effect within one batch;</li>
 *   <li>transactions stay bounded however large or deep the tree — a batch is
 *       never split across saves, and a file always travels with its
 *       {@code jcr:content} and version history as one unit.</li>
 * </ul>
 *
 * A batch refused for referential integrity is retried with per-node saves
 * (see {@link #fSaveEveryNode}) so only the referenced node itself remains.
 *
 * The progress row only advances after the deletions it reports are
 * committed, so a crash between commits never skips paths that were not
 * actually deleted — at worst a resume revisits already-deleted paths, which
 * the missing-node check skips.
 *
 * Top-level paths are processed strictly in the order they appear in the body.
 */
public class DeleteJob implements Job {

	public static final String TYPE = "delete";

	/**
	 * Throttle: write progress to the job node every N item deletions. Only
	 * reached on the per-node path — a set-based batch reports unconditionally.
	 */
	private static final long PROGRESS_THROTTLE_ITEMS = 100L;
	/** Throttle: also write progress when this much wall time has elapsed. */
	private static final long PROGRESS_THROTTLE_MILLIS = 500L;
	/**
	 * Commit the delete session after this many node removals. Only reached on
	 * the per-node path — a set-based batch commits unconditionally.
	 */
	private static final int SAVE_BATCH_NODES = 64;
	/** Also commit when this much wall time has elapsed since the last commit. */
	private static final long SAVE_BATCH_MILLIS = 500L;
	/**
	 * Children handed to a single set-based removal. A batch is a unit: it is
	 * committed and reported as a whole, so this alone decides how far the
	 * progress display jumps between updates — the throttles above never widen
	 * it. Lowering it buys a livelier display at the cost of one more commit
	 * (and the index work that follows it) per batch.
	 */
	private static final int REMOVE_BATCH_ITEMS = 25;

	private final String fJobId;
	private final String fWorkspaceName;
	private final String fUserId;
	private final int fPriority;

	private long fItemsTotal;
	private long fItemsProcessed;
	private long fItemsDeleted;
	private long fLastWriteAt;
	/** Node removals performed on the job session since its last save(). */
	private int fPendingNodes;
	private long fLastFlushAt;
	/** Counter values as of the last successful save(), restored if a batch rolls back. */
	private long fItemsProcessedFlushed;
	private long fItemsDeletedFlushed;
	/**
	 * Fallback mode after a batch was refused for referential integrity: save
	 * after every removal so the blocking node fails alone instead of taking
	 * its whole batch down with it on every retry.
	 */
	private boolean fSaveEveryNode;

	public DeleteJob(String jobId, String workspaceName, String userId, int priority) {
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
		context.getLogger().info("DeleteJob " + fJobId + " execute() entered for workspace=" + fWorkspaceName
				+ " user=" + fUserId);
		Session jobSession;
		Session progressSession;
		try {
			jobSession = context.getJobSession();
			context.getLogger().info("DeleteJob " + fJobId + " jobSession opened");
			progressSession = context.getProgressSession();
			context.getLogger().info("DeleteJob " + fJobId + " progressSession opened");
		} catch (Throwable ex) {
			context.getLogger().error("DeleteJob " + fJobId + " could not open sessions", ex);
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
			fItemsProcessed = JobNodes.getLong(progressContent, JobNodes.PROP_ITEMS_PROCESSED, 0L);
			fItemsDeleted = JobNodes.getLong(progressContent, JobNodes.PROP_ITEMS_DELETED, 0L);

			JobNodes.setStatus(progressContent, JobStatus.RUNNING);
			progressContent.setProperty(JobNodes.PROP_STARTED_AT, Calendar.getInstance());
			progressContent.setProperty(JobNodes.PROP_ITEMS_DELETED, fItemsDeleted);
			progressSession.save();
		} catch (Throwable ex) {
			initError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			context.getLogger().error("DeleteJob " + fJobId + " could not be initialised", ex);
		}

		boolean aborted = false;
		String errorMessage = initError;
		fLastWriteAt = System.currentTimeMillis();
		fLastFlushAt = fLastWriteAt;
		fItemsProcessedFlushed = fItemsProcessed;
		fItemsDeletedFlushed = fItemsDeleted;

		if (initError == null && paths != null) {
			for (;;) {
				try {
					aborted = deletePass(jobSession, paths, context, progressContent, progressSession);
					break;
				} catch (Throwable ex) {
					// Drop the uncommitted batch and rewind to the committed state.
					try {
						jobSession.refresh(false);
					} catch (Throwable ignore) {}
					fPendingNodes = 0;
					fItemsProcessed = fItemsProcessedFlushed;
					fItemsDeleted = fItemsDeletedFlushed;

					if (!fSaveEveryNode && isReferentialIntegrityViolation(ex)) {
						// A batch commit was blocked by a still-referenced node.
						// Redo from the committed state saving node by node, so
						// every deletable sibling that shared the failed batch
						// still gets deleted and the job fails (if at all)
						// exactly at the blocking node — otherwise a retry
						// would rebuild and re-fail the identical batch forever.
						fSaveEveryNode = true;
						context.getLogger().info("DeleteJob " + fJobId
								+ " batch blocked by a referenced node; retrying with per-node saves.");
						continue;
					}

					errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
					context.getLogger().error("DeleteJob " + fJobId + " failed at item "
							+ fItemsProcessed + " of " + fItemsTotal, ex);
					break;
				}
			}
		}

		// Always try to finalise so the client subscription gets a terminal event.
		// Re-resolve the job content node on a fresh handle if init failed before
		// progressContent was populated.
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
				progressContent.setProperty(JobNodes.PROP_ITEMS_PROCESSED, fItemsProcessed);
				progressContent.setProperty(JobNodes.PROP_ITEMS_DELETED, fItemsDeleted);
				progressContent.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
				JobNodes.setStatus(progressContent, finalStatus);
				progressSession.save();
			}
		} catch (Throwable ex) {
			context.getLogger().error("DeleteJob " + fJobId + " could not finalise status", ex);
		}
	}

	/**
	 * One traversal of the requested paths from the last committed position.
	 * Returns whether the pass was aborted. Throws when a removal or a batch
	 * commit fails; the caller rewinds to the committed state and decides
	 * whether to retry.
	 */
	private boolean deletePass(Session jobSession, List<String> paths, JobContext context,
			Node progressContent, Session progressSession) throws Exception {
		boolean aborted = false;
		for (int i = (int) fItemsProcessed; i < paths.size(); i++) {
			if (context.isAborted()) {
				aborted = true;
				break;
			}
			try {
				deleteRecursively(jobSession, paths.get(i), context, progressContent, progressSession);
				fItemsProcessed = i + 1L;
			} catch (AbortedException ex) {
				aborted = true;
				break;
			}
		}
		// Commit the remainder — also on abort, so removals performed before
		// the abort was noticed are not lost.
		flushIfNeeded(jobSession, progressContent, progressSession, null, true);
		return aborted;
	}

	private static boolean isReferentialIntegrityViolation(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof ReferentialIntegrityException) {
				return true;
			}
		}
		return false;
	}

	private void deleteRecursively(Session session, String path, JobContext context,
			Node progressContent, Session progressSession) throws Exception {
		if (context.isAborted()) {
			throw new AbortedException();
		}
		if (!session.nodeExists(path)) {
			return;
		}
		Node node = session.getNode(path);

		// Everything that can carry children — folders, but also arbitrary
		// container types — is emptied first, a batch at a time, so that what is
		// handed to the removal below is a single node.
		if (!JCRs.isFile(node)) {
			deleteChildren(session, path, context, progressContent, progressSession);
			if (!session.nodeExists(path)) {
				return;
			}
			node = session.getNode(path);
		}

		// Fast path: a plain file or folder is removed with set-based statements —
		// itself, its jcr:content, the version histories it owns and their
		// binaries, in one unit within the current transaction. removeTree returns
		// -1 without touching anything when a per-node walk is required
		// (descendant ACLs or locks), and the removal below takes over. Skipped in
		// the per-node-save retry mode: after a referential-integrity refusal the
		// job must fail exactly at the blocking node, which only the per-node walk
		// can isolate.
		if (!fSaveEveryNode && node instanceof org.mintjams.jcr.Node) {
			long removed = ((org.mintjams.jcr.Node) node).removeTree();
			if (removed > 0) {
				fPendingNodes += (int) Math.min(removed, Integer.MAX_VALUE);
				fItemsDeleted += removed;
				flushIfNeeded(session, progressContent, progressSession, path, false);
				return;
			}
			if (removed == 0) {
				// The node disappeared since the existence check; nothing to do.
				return;
			}
		}

		// Count only the items the user thinks of as deleted (files and folders);
		// internal descendants such as jcr:content must not inflate the progress.
		boolean countable = JCRs.isFile(node) || JCRs.isFolder(node);
		node.remove();
		fPendingNodes++;
		if (countable) {
			fItemsDeleted++;
		}
		flushIfNeeded(session, progressContent, progressSession, path, false);
	}

	/**
	 * Removes everything below the container at {@code path}, leaving the
	 * container itself to the caller.
	 */
	private void deleteChildren(Session session, String path, JobContext context,
			Node progressContent, Session progressSession) throws Exception {
		// The children are listed before any of them is removed: the iterator
		// pages over the live rows by offset, so removing rows while it is open
		// would shift the pages and skip children.
		List<String> containerPaths = new ArrayList<>();
		List<String> batchPaths = new ArrayList<>();
		List<String> otherPaths = new ArrayList<>();
		for (NodeIterator it = session.getNode(path).getNodes(); it.hasNext();) {
			Node child = it.nextNode();
			String childPath = child.getPath();
			if (JCRs.isFile(child)) {
				batchPaths.add(childPath);
			} else if (JCRs.isFolder(child)) {
				containerPaths.add(childPath);
				batchPaths.add(childPath);
			} else {
				otherPaths.add(childPath);
			}
		}

		// Empty the folder children first, depth first: each of them then costs
		// one node in a batch below instead of an unbounded subtree of its own.
		for (String childPath : containerPaths) {
			if (context.isAborted()) {
				throw new AbortedException();
			}
			if (!session.nodeExists(childPath)) {
				continue;
			}
			deleteChildren(session, childPath, context, progressContent, progressSession);
		}

		// Anything that is neither a plain file nor a plain folder keeps the
		// per-node semantics of remove().
		for (String childPath : otherPaths) {
			deleteRecursively(session, childPath, context, progressContent, progressSession);
		}

		removeInBatches(session, path, batchPaths, context, progressContent, progressSession);
	}

	/**
	 * Removes the given children of a container in bounded batches, each one
	 * set-based unit. Handing the children over a batch at a time — rather than
	 * the container's whole subtree in a single {@code removeTree()} — is what
	 * bounds the transaction, the memory the removal holds for the subtree it
	 * collected, and the interval between progress reports.
	 */
	private void removeInBatches(Session session, String containerPath, List<String> childPaths,
			JobContext context, Node progressContent, Session progressSession) throws Exception {
		for (int i = 0; i < childPaths.size();) {
			if (context.isAborted()) {
				throw new AbortedException();
			}

			List<Node> batch = new ArrayList<>();
			String lastPath = null;
			while (i < childPaths.size() && batch.size() < REMOVE_BATCH_ITEMS) {
				String childPath = childPaths.get(i++);
				if (!session.nodeExists(childPath)) {
					continue;
				}
				batch.add(session.getNode(childPath));
				lastPath = childPath;
			}
			if (batch.isEmpty()) {
				continue;
			}

			long removed = -1;
			if (!fSaveEveryNode && session.nodeExists(containerPath)) {
				Node container = session.getNode(containerPath);
				if (container instanceof org.mintjams.jcr.Node) {
					removed = ((org.mintjams.jcr.Node) container).removeChildTrees(batch);
				}
			}
			if (removed < 0) {
				// The batch needs per-node checks (descendant ACLs or locks), or
				// the job is isolating a node that blocks referential integrity.
				for (Node child : batch) {
					deleteRecursively(session, child.getPath(), context, progressContent, progressSession);
				}
				continue;
			}
			if (removed == 0) {
				// Every child of the batch was already gone.
				continue;
			}

			fPendingNodes += (int) Math.min(removed, Integer.MAX_VALUE);
			fItemsDeleted += removed;
			// The batch is the unit: commit and report it whatever its size,
			// rather than letting the per-node throttles decide — they would
			// hold several batches back and report in coarser steps than the
			// batch size asks for.
			flushIfNeeded(session, progressContent, progressSession, lastPath, true);
		}
	}

	/**
	 * Commits the job session once enough removals have accumulated (or enough
	 * time has passed), then reports progress. The progress row is only ever
	 * written after the save that covers it, so its counters never run ahead of
	 * the committed state.
	 */
	private void flushIfNeeded(Session session, Node progressContent, Session progressSession,
			String currentPath, boolean force) throws Exception {
		if (fPendingNodes == 0) {
			if (force) {
				writeProgress(progressContent, progressSession, currentPath, true);
			}
			return;
		}
		long now = System.currentTimeMillis();
		if (!force && !fSaveEveryNode
				&& fPendingNodes < SAVE_BATCH_NODES && (now - fLastFlushAt) < SAVE_BATCH_MILLIS) {
			return;
		}
		session.save();
		fPendingNodes = 0;
		fLastFlushAt = now;
		fItemsProcessedFlushed = fItemsProcessed;
		fItemsDeletedFlushed = fItemsDeleted;
		writeProgress(progressContent, progressSession, currentPath, force);
	}

	private void writeProgress(Node content, Session progressSession, String currentPath, boolean force)
			throws Exception {
		long now = System.currentTimeMillis();
		boolean shouldWrite = force
				|| (fItemsDeleted - JobNodes.getLong(content, JobNodes.PROP_ITEMS_DELETED, 0L)) >= PROGRESS_THROTTLE_ITEMS
				|| (now - fLastWriteAt) >= PROGRESS_THROTTLE_MILLIS;
		if (!shouldWrite) {
			return;
		}
		content.setProperty(JobNodes.PROP_ITEMS_PROCESSED, fItemsProcessed);
		content.setProperty(JobNodes.PROP_ITEMS_DELETED, fItemsDeleted);
		if (currentPath != null) {
			content.setProperty(JobNodes.PROP_CURRENT_PATH, currentPath);
		}
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		progressSession.save();
		fLastWriteAt = now;
	}

	/**
	 * Last-resort path to record a terminal status when even opening a
	 * user-scoped progress session has failed. Uses the privileged service
	 * credentials so the JCR record never gets stuck in {@code queued} —
	 * the client is then guaranteed a final {@code jobProgress} event.
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
			CmsService.getLogger(DeleteJob.class).error(
					"DeleteJob " + jobId + " — fallback finaliser failed", ex);
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

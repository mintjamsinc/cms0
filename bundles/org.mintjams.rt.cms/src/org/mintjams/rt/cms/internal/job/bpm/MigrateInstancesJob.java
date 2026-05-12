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

package org.mintjams.rt.cms.internal.job.bpm;

import java.util.Calendar;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Session;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.batch.BatchStatistics;
import org.camunda.bpm.engine.batch.history.HistoricBatch;
import org.camunda.bpm.engine.migration.MigrationInstructionsBuilder;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.migration.MigrationPlanBuilder;
import org.camunda.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.Job;
import org.mintjams.rt.cms.internal.job.JobContext;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

/**
 * Executes a Camunda 7 process-instance migration end-to-end:
 * <ol>
 *   <li>Builds the {@link MigrationPlan} from the supplied source/target
 *       definitions and option flags.</li>
 *   <li>Calls {@code executeAsync()} to seed a Camunda Batch — the engine's
 *       batch job executor then drains the migration work in the background.</li>
 *   <li>Polls {@link BatchStatistics} every {@value #POLL_INTERVAL_MS} ms and
 *       writes {@code itemsTotal}/{@code itemsProcessed}/{@code status} to the
 *       JCR job node so that {@code jobProgress(jobId)} subscribers see live
 *       progress.</li>
 * </ol>
 *
 * <h2>Job id and storage</h2>
 * The job uses a CMS-generated id (see {@link JobNodes#newJobId()}) so its
 * record lives at the standard {@code /var/jobs/YYYY/MM/job-<id>} location,
 * indistinguishable from any other JobManager job.  The Camunda batch id is a
 * runtime detail recorded on the job node ({@link #PROP_BATCH_ID}) once the
 * migration is seeded.
 *
 * <h2>Abort behaviour</h2>
 * Camunda 7 batches cannot be safely aborted mid-flight via the public API
 * (only suspended or hard-deleted, both with severe side-effects).  The
 * mutation response therefore advertises {@code abortable=false} so the
 * client hides the Abort button.  The monitor still honours
 * {@link JobContext#isAborted()} for clean shutdown on engine termination —
 * it stops polling and marks the JCR record {@link JobStatus#ABORTED}, but
 * does not touch the running Camunda batch.
 *
 * <h2>Items counting</h2>
 * {@code itemsTotal}     ← {@code BatchStatistics.getTotalJobs()}<br>
 * {@code itemsProcessed} ← {@code completedJobs + failedJobs} (i.e. all jobs
 * the engine considers terminal — whether successfully migrated or with an
 * unrecoverable incident).  {@code totalJobs - remainingJobs} would include
 * jobs that are currently locked/running and produce a noisier progress
 * curve; the completed+failed sum maps cleanly to the user's mental model.
 */
public class MigrateInstancesJob implements Job {

	public static final String TYPE = "migrate-instances";

	/** JCR property holding the Camunda batch id once {@code executeAsync()} has been called. */
	public static final String PROP_BATCH_ID = "jobBatchId";

	/** How often Camunda's batch statistics are sampled. */
	private static final long POLL_INTERVAL_MS = 2000L;

	/**
	 * Soft cap on monitor lifetime.  Hand-tuned so a stuck/abandoned monitor
	 * eventually releases its worker rather than running forever; Camunda
	 * batches that legitimately need longer can be re-monitored on demand.
	 */
	private static final long MAX_MONITOR_DURATION_MS = 24L * 60L * 60L * 1000L;

	private final String fJobId;
	private final String fWorkspaceName;
	private final String fUserId;
	private final int fPriority;
	private final MigrationRequest fRequest;

	public MigrateInstancesJob(String jobId, String workspaceName, String userId, int priority,
			MigrationRequest request) {
		fJobId = jobId;
		fWorkspaceName = workspaceName;
		fUserId = userId;
		fPriority = priority;
		fRequest = request;
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
		context.getLogger().info("MigrateInstancesJob " + fJobId + " execute() entered for workspace="
				+ fWorkspaceName + " user=" + fUserId);

		Session progressSession;
		try {
			progressSession = context.getProgressSession();
		} catch (Throwable ex) {
			context.getLogger().error("MigrateInstancesJob " + fJobId + " could not open progress session", ex);
			markFailedWithSystemSession(fJobId, fWorkspaceName, ex);
			return;
		}

		Node progressContent;
		try {
			Node fileNode = JobNodes.getJobNode(progressSession, fJobId);
			if (fileNode == null) {
				context.getLogger().warn("Job node missing for migrate-instances " + fJobId + "; aborting.");
				return;
			}
			progressContent = JobNodes.getContent(fileNode);
			JobNodes.setStatus(progressContent, JobStatus.RUNNING);
			progressContent.setProperty(JobNodes.PROP_STARTED_AT, Calendar.getInstance());
			progressSession.save();
		} catch (Throwable ex) {
			context.getLogger().error("MigrateInstancesJob " + fJobId + " could not initialise progress", ex);
			markFailedWithSystemSession(fJobId, fWorkspaceName, ex);
			return;
		}

		ProcessEngine engine;
		try {
			engine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
		} catch (Throwable ex) {
			context.getLogger().error("MigrateInstancesJob " + fJobId + " could not obtain ProcessEngine", ex);
			finaliseWithError(progressContent, progressSession, ex);
			return;
		}

		// Honour abort requests that arrive before we've seeded the Camunda
		// batch — once executeAsync() has fired we lose the ability to cancel
		// the work cleanly, but until then a queued abort is trivially safe.
		if (context.isAborted()) {
			finaliseAborted(progressContent, progressSession);
			return;
		}

		Batch batch;
		try {
			batch = seedBatch(engine);
		} catch (Throwable ex) {
			context.getLogger().error("MigrateInstancesJob " + fJobId + " could not seed Camunda batch", ex);
			finaliseWithError(progressContent, progressSession, ex);
			return;
		}

		String batchId = batch.getId();
		long initialTotalJobs = batch.getTotalJobs();
		try {
			progressContent.setProperty(PROP_BATCH_ID, batchId);
			progressContent.setProperty(JobNodes.PROP_ITEMS_TOTAL, initialTotalJobs);
			progressContent.setProperty("jcr:lastModified", Calendar.getInstance());
			progressSession.save();
		} catch (Throwable ex) {
			context.getLogger().warn("MigrateInstancesJob " + fJobId
					+ " could not record batch id " + batchId + " on job node", ex);
		}

		long startedAt = System.currentTimeMillis();
		String errorMessage = null;
		JobStatus finalStatus = null;
		long lastItemsTotal = initialTotalJobs;
		long lastItemsProcessed = 0L;
		long lastFailedJobs = 0L;

		try {
			while (!context.isAborted()) {
				if (System.currentTimeMillis() - startedAt > MAX_MONITOR_DURATION_MS) {
					context.getLogger().warn("MigrateInstancesJob " + fJobId
							+ " exceeded monitor lifetime cap; releasing worker.");
					errorMessage = "Migration monitor exceeded maximum lifetime";
					finalStatus = JobStatus.FAILED;
					break;
				}

				BatchStatistics stats = engine.getManagementService()
						.createBatchStatisticsQuery()
						.batchId(batchId)
						.singleResult();

				if (stats != null) {
					long total = stats.getTotalJobs();
					long completed = stats.getCompletedJobs();
					long failed = stats.getFailedJobs();
					long processed = completed + failed;

					if (total != lastItemsTotal
							|| processed != lastItemsProcessed
							|| failed != lastFailedJobs) {
						writeProgress(progressContent, progressSession, total, processed, failed, null);
						lastItemsTotal = total;
						lastItemsProcessed = processed;
						lastFailedJobs = failed;
					}
				} else {
					// Batch no longer present in the runtime tables → either
					// finished or rolled back. Consult the history service to
					// distinguish.
					HistoricBatch historic = engine.getHistoryService()
							.createHistoricBatchQuery()
							.batchId(batchId)
							.singleResult();
					if (historic != null) {
						long total = historic.getTotalJobs();
						long processed = Math.max(lastItemsProcessed, total);
						writeProgress(progressContent, progressSession, total, processed, lastFailedJobs, null);
						lastItemsProcessed = processed;
						if (historic.getEndTime() != null) {
							finalStatus = (lastFailedJobs > 0) ? JobStatus.FAILED : JobStatus.COMPLETED;
							if (finalStatus == JobStatus.FAILED) {
								errorMessage = lastFailedJobs + " job(s) failed during migration";
							}
							break;
						}
					} else {
						// Neither runtime nor history knows the batch.
						// executeAsync() returned a committed Batch with a
						// valid id, so the migration was successfully seeded
						// — rollback cannot retroactively erase it. The only
						// realistic way to reach this branch is that the
						// batch ran to completion and the engine cleaned the
						// runtime row (and either the historic row was
						// pruned, or history-level is configured not to
						// retain batches). Treat this as a successful
						// completion: a small batch — a single-instance
						// migration is the common case — can finish between
						// executeAsync() and the first poll, leaving us with
						// nothing live to observe.
						long total = Math.max(lastItemsTotal, initialTotalJobs);
						long processed = Math.max(lastItemsProcessed, total);
						writeProgress(progressContent, progressSession, total, processed, lastFailedJobs, null);
						lastItemsTotal = total;
						lastItemsProcessed = processed;
						if (lastFailedJobs > 0L) {
							// Honour failures we observed earlier via
							// runtime stats — we just lost the ability to
							// refine the count.
							finalStatus = JobStatus.FAILED;
							errorMessage = lastFailedJobs + " job(s) failed during migration";
						} else {
							finalStatus = JobStatus.COMPLETED;
						}
						break;
					}
				}

				try {
					Thread.sleep(POLL_INTERVAL_MS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					break;
				}
			}

			if (finalStatus == null) {
				// Loop exited because of abort (engine shutdown). Mark as
				// aborted so the UI can decide how to react, but do not
				// pretend the migration itself was cancelled.
				finalStatus = JobStatus.ABORTED;
			}
		} catch (Throwable ex) {
			errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			finalStatus = JobStatus.FAILED;
			context.getLogger().error("MigrateInstancesJob " + fJobId + " failed during polling", ex);
		}

		try {
			if (errorMessage != null) {
				progressContent.setProperty(JobNodes.PROP_ERROR_MESSAGE, errorMessage);
			}
			progressContent.setProperty(JobNodes.PROP_ITEMS_PROCESSED, lastItemsProcessed);
			progressContent.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
			JobNodes.setStatus(progressContent, finalStatus);
			progressSession.save();
		} catch (Throwable ex) {
			context.getLogger().error("MigrateInstancesJob " + fJobId + " could not finalise status", ex);
		}
	}

	private Batch seedBatch(ProcessEngine engine) {
		MigrationPlan plan = buildMigrationPlan(engine, fRequest);
		MigrationPlanExecutionBuilder exec = engine.getRuntimeService().newMigration(plan);
		if (fRequest.processInstanceIds != null && !fRequest.processInstanceIds.isEmpty()) {
			exec = exec.processInstanceIds(fRequest.processInstanceIds);
		}
		if (fRequest.allActiveInstances) {
			ProcessInstanceQuery q = engine.getRuntimeService()
					.createProcessInstanceQuery()
					.processDefinitionId(fRequest.sourceProcessDefinitionId)
					.active();
			exec = exec.processInstanceQuery(q);
		}
		if (fRequest.skipCustomListeners) {
			exec = exec.skipCustomListeners();
		}
		if (fRequest.skipIoMappings) {
			exec = exec.skipIoMappings();
		}
		return exec.executeAsync();
	}

	private static MigrationPlan buildMigrationPlan(ProcessEngine engine, MigrationRequest req) {
		MigrationPlanBuilder builder = engine.getRuntimeService()
				.createMigrationPlan(req.sourceProcessDefinitionId, req.targetProcessDefinitionId);
		if (req.mapEqualActivities) {
			MigrationInstructionsBuilder instructions = builder.mapEqualActivities();
			if (req.updateEventTriggers) {
				instructions = instructions.updateEventTriggers();
			}
			builder = instructions;
		}
		return builder.build();
	}

	private void writeProgress(Node content, Session session,
			long itemsTotal, long itemsProcessed, long failedJobs, String message) throws Exception {
		if (itemsTotal > 0L) {
			content.setProperty(JobNodes.PROP_ITEMS_TOTAL, itemsTotal);
		}
		content.setProperty(JobNodes.PROP_ITEMS_PROCESSED, itemsProcessed);
		if (failedJobs > 0L) {
			// Surface failure count to the UI even before the batch is
			// terminal — incidents are visible through Camunda's task list
			// so it is worth telling the user something is wrong now.
			String msg = failedJobs + " job(s) currently failing";
			content.setProperty(JobNodes.PROP_ERROR_MESSAGE, msg);
		}
		if (message != null) {
			content.setProperty(JobNodes.PROP_CURRENT_PATH, message);
		}
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		session.save();
	}

	private void finaliseAborted(Node progressContent, Session progressSession) {
		try {
			progressContent.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
			JobNodes.setStatus(progressContent, JobStatus.ABORTED);
			progressSession.save();
		} catch (Throwable ex) {
			CmsService.getLogger(MigrateInstancesJob.class)
					.error("MigrateInstancesJob " + fJobId + " — failed to record aborted status", ex);
		}
	}

	private void finaliseWithError(Node progressContent, Session progressSession, Throwable cause) {
		try {
			String msg = cause != null && cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause);
			progressContent.setProperty(JobNodes.PROP_ERROR_MESSAGE, msg);
			progressContent.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
			JobNodes.setStatus(progressContent, JobStatus.FAILED);
			progressSession.save();
		} catch (Throwable ex) {
			CmsService.getLogger(MigrateInstancesJob.class)
					.error("MigrateInstancesJob " + fJobId + " — failed to record terminal error", ex);
		}
	}

	/**
	 * Last-resort path to record a terminal status when even opening a
	 * progress session has failed. Mirrors the equivalent helper on
	 * {@code DeleteJob} so the client subscription always observes a
	 * terminal event rather than a stuck {@code queued} job.
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
			CmsService.getLogger(MigrateInstancesJob.class).error(
					"MigrateInstancesJob " + jobId + " — fallback finaliser failed", ex);
		} finally {
			if (sysSession != null) {
				try { sysSession.logout(); } catch (Throwable ignore) {}
			}
		}
	}

	/**
	 * Captured migration parameters.  Kept as a plain value type so the Job
	 * carries no JCR/engine handles across the submit/execute boundary — the
	 * worker re-resolves the engine inside its own thread.
	 */
	public static final class MigrationRequest {
		public final String sourceProcessDefinitionId;
		public final String targetProcessDefinitionId;
		public final List<String> processInstanceIds;
		public final boolean allActiveInstances;
		public final boolean mapEqualActivities;
		public final boolean updateEventTriggers;
		public final boolean skipCustomListeners;
		public final boolean skipIoMappings;

		public MigrationRequest(String sourceProcessDefinitionId, String targetProcessDefinitionId,
				List<String> processInstanceIds, boolean allActiveInstances,
				boolean mapEqualActivities, boolean updateEventTriggers,
				boolean skipCustomListeners, boolean skipIoMappings) {
			this.sourceProcessDefinitionId = sourceProcessDefinitionId;
			this.targetProcessDefinitionId = targetProcessDefinitionId;
			this.processInstanceIds = processInstanceIds;
			this.allActiveInstances = allActiveInstances;
			this.mapEqualActivities = mapEqualActivities;
			this.updateEventTriggers = updateEventTriggers;
			this.skipCustomListeners = skipCustomListeners;
			this.skipIoMappings = skipIoMappings;
		}
	}
}

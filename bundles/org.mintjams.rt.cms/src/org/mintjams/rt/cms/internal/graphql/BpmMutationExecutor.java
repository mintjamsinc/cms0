/*
 * Copyright (c) 2024 MintJams Inc.
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

package org.mintjams.rt.cms.internal.graphql;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.camunda.bpm.engine.AuthorizationException;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.migration.MigrationInstruction;
import org.camunda.bpm.engine.migration.MigrationInstructionsBuilder;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.migration.MigrationPlanBuilder;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.runtime.Incident;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Comment;
import org.camunda.bpm.engine.task.Task;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.job.bpm.MigrateInstancesJob;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

/**
 * GraphQL Mutation executor for Camunda 7 BPM operations.
 * Handles process instance lifecycle, task operations, and deployment management.
 */
public class BpmMutationExecutor {

	private final Session session;
	private final BpmQueryExecutor queryExecutor;

	public BpmMutationExecutor(Session session) {
		this.session = session;
		this.queryExecutor = new BpmQueryExecutor(session);
	}

	// =========================================================================
	// Process Instance mutations
	// =========================================================================

	public Map<String, Object> executeStartProcess(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String definitionKey = (String) input.get("definitionKey");
		String definitionId = (String) input.get("definitionId");
		String businessKey = (String) input.get("businessKey");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> variableInputs = (List<Map<String, Object>>) input.get("variables");

		ProcessEngine engine = getProcessEngine();
		Map<String, Object> vars = toVariableMap(variableInputs);

		ProcessInstance instance;
		if (definitionKey != null && !definitionKey.isEmpty()) {
			if (businessKey != null) {
				instance = engine.getRuntimeService().startProcessInstanceByKey(definitionKey, businessKey, vars);
			} else {
				instance = engine.getRuntimeService().startProcessInstanceByKey(definitionKey, vars);
			}
		} else if (definitionId != null && !definitionId.isEmpty()) {
			if (businessKey != null) {
				instance = engine.getRuntimeService().startProcessInstanceById(definitionId, businessKey, vars);
			} else {
				instance = engine.getRuntimeService().startProcessInstanceById(definitionId, vars);
			}
		} else {
			throw new IllegalArgumentException("Either definitionKey or definitionId is required");
		}

		Map<String, Object> result = new HashMap<>();
		result.put("startProcess", mapProcessInstance(engine, instance));
		return result;
	}

	public Map<String, Object> executeSuspendProcessInstance(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");

		ProcessEngine engine = getProcessEngine();
		engine.getRuntimeService().suspendProcessInstanceById(id);

		HistoricProcessInstance historic = engine.getHistoryService()
				.createHistoricProcessInstanceQuery()
				.processInstanceId(id)
				.singleResult();

		Map<String, Object> result = new HashMap<>();
		result.put("suspendProcessInstance", historic != null ? mapHistoricInstance(engine, historic) : null);
		return result;
	}

	public Map<String, Object> executeActivateProcessInstance(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");

		ProcessEngine engine = getProcessEngine();
		engine.getRuntimeService().activateProcessInstanceById(id);

		HistoricProcessInstance historic = engine.getHistoryService()
				.createHistoricProcessInstanceQuery()
				.processInstanceId(id)
				.singleResult();

		Map<String, Object> result = new HashMap<>();
		result.put("activateProcessInstance", historic != null ? mapHistoricInstance(engine, historic) : null);
		return result;
	}

	public Map<String, Object> executeCancelProcessInstance(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		String reason = getStringVar(variables, "reason");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");

		ProcessEngine engine = getProcessEngine();
		engine.getRuntimeService().deleteProcessInstance(id, reason != null ? reason : "Cancelled via BPM Console");

		Map<String, Object> result = new HashMap<>();
		result.put("cancelProcessInstance", true);
		return result;
	}

	public Map<String, Object> executeDeleteProcessInstance(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		Boolean skipCustomListeners = getBoolVarOrNull(variables, "skipCustomListeners");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");

		ProcessEngine engine = getProcessEngine();
		boolean skip = Boolean.TRUE.equals(skipCustomListeners);
		engine.getRuntimeService().deleteProcessInstance(id, "Deleted via BPM Console", skip);

		Map<String, Object> result = new HashMap<>();
		result.put("deleteProcessInstance", true);
		return result;
	}

	public Map<String, Object> executeSetProcessVariables(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String processInstanceId = getStringVar(variables, "processInstanceId");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> variableInputs = (List<Map<String, Object>>) variables.get("variables");
		if (processInstanceId == null || processInstanceId.isEmpty()) {
			throw new IllegalArgumentException("processInstanceId is required");
		}

		ProcessEngine engine = getProcessEngine();
		Map<String, Object> vars = toVariableMap(variableInputs);

		// Replace semantics: remove variables that exist on the instance but are
		// not present in the incoming list. Together with the typed values produced
		// by toVariableMap, this lets the Edit Variables dialog both delete entries
		// and change their declared types (e.g. String -> Long even when the value
		// is empty / unparseable).
		Map<String, Object> existing = engine.getRuntimeService().getVariables(processInstanceId);
		List<String> toRemove = new ArrayList<>();
		for (String existingName : existing.keySet()) {
			if (!vars.containsKey(existingName)) {
				toRemove.add(existingName);
			}
		}
		if (!toRemove.isEmpty()) {
			engine.getRuntimeService().removeVariables(processInstanceId, toRemove);
		}
		if (!vars.isEmpty()) {
			engine.getRuntimeService().setVariables(processInstanceId, vars);
		}

		HistoricProcessInstance historic = engine.getHistoryService()
				.createHistoricProcessInstanceQuery()
				.processInstanceId(processInstanceId)
				.singleResult();

		Map<String, Object> result = new HashMap<>();
		result.put("setProcessVariables", historic != null ? mapHistoricInstance(engine, historic) : null);
		return result;
	}

	// =========================================================================
	// Task mutations
	// =========================================================================

	public Map<String, Object> executeClaimTask(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String taskId = getStringVar(variables, "taskId");
		if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("taskId is required");

		ProcessEngine engine = getProcessEngine();
		String userId = session.getUserID();
		engine.getTaskService().claim(taskId, userId);

		Map<String, Object> result = new HashMap<>();
		result.put("claimTask", getTaskMap(engine, taskId));
		return result;
	}

	public Map<String, Object> executeUnclaimTask(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String taskId = getStringVar(variables, "taskId");
		if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("taskId is required");

		ProcessEngine engine = getProcessEngine();
		engine.getTaskService().claim(taskId, null);

		Map<String, Object> result = new HashMap<>();
		result.put("unclaimTask", getTaskMap(engine, taskId));
		return result;
	}

	public Map<String, Object> executeAssignTask(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String taskId = getStringVar(variables, "taskId");
		String assignee = getStringVar(variables, "assignee");
		if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("taskId is required");
		if (assignee == null || assignee.isEmpty()) throw new IllegalArgumentException("assignee is required");

		ProcessEngine engine = getProcessEngine();
		engine.getTaskService().setAssignee(taskId, assignee);

		Map<String, Object> result = new HashMap<>();
		result.put("assignTask", getTaskMap(engine, taskId));
		return result;
	}

	public Map<String, Object> executeSetTaskAssignee(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String taskId = getStringVar(variables, "taskId");
		String assignee = getStringVar(variables, "assignee"); // may be null to unassign
		if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("taskId is required");

		ProcessEngine engine = getProcessEngine();
		do {
			if (org.mintjams.jcr.Session.class.cast(session).isAdmin()) {
				// Admin can assign to anyone
				break;
			}
			if (org.mintjams.jcr.Workspace.class.cast(session.getWorkspace())
					.getIdentityProvider()
					.getUser(session.getUserID())
					.hasRole("supervisor")) {
				// Supervisor can assign to anyone
				break;
			}

			Task task = engine.getTaskService().createTaskQuery()
					.taskId(taskId)
					.singleResult();
			if (task != null && session.getUserID().equals(task.getAssignee())) {
				// User can reassign their own tasks
				break;
			}

			throw new AuthorizationException("User does not have permission to assign this task");
		} while (false);

		engine.getTaskService().setAssignee(taskId, assignee);

		Map<String, Object> result = new HashMap<>();
		result.put("setTaskAssignee", getTaskMap(engine, taskId));
		return result;
	}

	public Map<String, Object> executeDelegateTask(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String taskId = getStringVar(variables, "taskId");
		String assignee = getStringVar(variables, "assignee");
		if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("taskId is required");
		if (assignee == null || assignee.isEmpty()) throw new IllegalArgumentException("assignee is required");

		ProcessEngine engine = getProcessEngine();
		engine.getTaskService().delegateTask(taskId, assignee);

		Map<String, Object> result = new HashMap<>();
		result.put("delegateTask", getTaskMap(engine, taskId));
		return result;
	}

	public Map<String, Object> executeCompleteTask(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String taskId = (String) input.get("taskId");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> variableInputs = (List<Map<String, Object>>) input.get("variables");
		if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("taskId is required");

		ProcessEngine engine = getProcessEngine();
		Map<String, Object> vars = toVariableMap(variableInputs);
		engine.getTaskService().complete(taskId, vars);

		// Task is gone after completion — return minimal info
		Map<String, Object> taskMap = new HashMap<>();
		taskMap.put("id", taskId);
		taskMap.put("name", null);

		Map<String, Object> result = new HashMap<>();
		result.put("completeTask", taskMap);
		return result;
	}

	public Map<String, Object> executeSetTaskVariables(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String taskId = getStringVar(variables, "taskId");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> variableInputs = (List<Map<String, Object>>) variables.get("variables");
		Boolean local = getBoolVarOrNull(variables, "local");
		if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("taskId is required");

		ProcessEngine engine = getProcessEngine();
		Map<String, Object> vars = toVariableMap(variableInputs);

		if (Boolean.TRUE.equals(local)) {
			engine.getTaskService().setVariablesLocal(taskId, vars);
		} else {
			engine.getTaskService().setVariables(taskId, vars);
		}

		Map<String, Object> result = new HashMap<>();
		result.put("setTaskVariables", getTaskMap(engine, taskId));
		return result;
	}

	public Map<String, Object> executeAddTaskComment(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String taskId = getStringVar(variables, "taskId");
		String message = getStringVar(variables, "message");
		if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("taskId is required");
		if (message == null || message.isEmpty()) throw new IllegalArgumentException("message is required");

		ProcessEngine engine = getProcessEngine();
		Task task = engine.getTaskService().createTaskQuery().taskId(taskId).singleResult();
		String processInstanceId = task != null ? task.getProcessInstanceId() : null;
		Comment comment = engine.getTaskService().createComment(taskId, processInstanceId, message);

		Map<String, Object> commentMap = new HashMap<>();
		commentMap.put("id", comment.getId());
		commentMap.put("taskId", comment.getTaskId());
		commentMap.put("userId", comment.getUserId());
		commentMap.put("time", formatDate(comment.getTime()));
		commentMap.put("message", comment.getFullMessage());

		Map<String, Object> result = new HashMap<>();
		result.put("addTaskComment", commentMap);
		return result;
	}

	// =========================================================================
	// Deployment mutations
	// =========================================================================

	public Map<String, Object> executeDeployProcess(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String name = (String) input.get("name");
		String source = (String) input.get("source");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> resources = (List<Map<String, Object>>) input.get("resources");

		if (name == null || name.isEmpty()) throw new IllegalArgumentException("name is required");
		if (resources == null || resources.isEmpty()) throw new IllegalArgumentException("resources is required");

		ProcessEngine engine = getProcessEngine();
		DeploymentBuilder builder = engine.getRepositoryService().createDeployment()
				.name(name);

		if (source != null && !source.isEmpty()) {
			builder.source(source);
		}

		for (Map<String, Object> resource : resources) {
			String resourceName = (String) resource.get("name");
			String content = (String) resource.get("content");
			if (resourceName != null && content != null) {
				builder.addString(resourceName, content);
			}
		}

		Deployment deployment = builder.deploy();

		Map<String, Object> deploymentMap = new HashMap<>();
		deploymentMap.put("id", deployment.getId());
		deploymentMap.put("name", deployment.getName());
		deploymentMap.put("deploymentTime", formatDate(deployment.getDeploymentTime()));
		deploymentMap.put("source", deployment.getSource());

		Map<String, Object> result = new HashMap<>();
		result.put("deployProcess", deploymentMap);
		return result;
	}

	public Map<String, Object> executeDeleteDeployment(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		Boolean cascade = getBoolVarOrNull(variables, "cascade");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");

		ProcessEngine engine = getProcessEngine();
		engine.getRepositoryService().deleteDeployment(id, Boolean.TRUE.equals(cascade));

		Map<String, Object> result = new HashMap<>();
		result.put("deleteDeployment", true);
		return result;
	}

	// =========================================================================
	// Process Definition lifecycle mutations
	// =========================================================================

	public Map<String, Object> executeSuspendProcessDefinition(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		Boolean includeInstances = getBoolVarOrNull(variables, "includeInstances");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");

		ProcessEngine engine = getProcessEngine();
		engine.getRepositoryService().suspendProcessDefinitionById(id,
				Boolean.TRUE.equals(includeInstances), null);

		org.camunda.bpm.engine.repository.ProcessDefinition def = engine.getRepositoryService()
				.createProcessDefinitionQuery().processDefinitionId(id).singleResult();

		Map<String, Object> result = new HashMap<>();
		result.put("suspendProcessDefinition", def != null ? queryExecutor.mapProcessDefinition(engine, def) : null);
		return result;
	}

	public Map<String, Object> executeActivateProcessDefinition(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		Boolean includeInstances = getBoolVarOrNull(variables, "includeInstances");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");

		ProcessEngine engine = getProcessEngine();
		engine.getRepositoryService().activateProcessDefinitionById(id,
				Boolean.TRUE.equals(includeInstances), null);

		org.camunda.bpm.engine.repository.ProcessDefinition def = engine.getRepositoryService()
				.createProcessDefinitionQuery().processDefinitionId(id).singleResult();

		Map<String, Object> result = new HashMap<>();
		result.put("activateProcessDefinition", def != null ? queryExecutor.mapProcessDefinition(engine, def) : null);
		return result;
	}

	// =========================================================================
	// Incident mutations
	// =========================================================================

	public Map<String, Object> executeSetJobRetries(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String incidentId = (String) input.get("incidentId");
		Integer retries = getIntValue(input.get("retries"));
		Boolean clearAnnotation = (Boolean) input.get("clearAnnotation");

		if (incidentId == null || incidentId.isEmpty()) {
			throw new IllegalArgumentException("incidentId is required");
		}
		if (retries == null || retries < 1) {
			throw new IllegalArgumentException("retries must be >= 1");
		}

		ProcessEngine engine = getProcessEngine();

		Incident inc = engine.getRuntimeService().createIncidentQuery()
				.incidentId(incidentId)
				.singleResult();
		if (inc == null) {
			throw new IllegalArgumentException("Incident not found: " + incidentId);
		}
		if (!"failedJob".equals(inc.getIncidentType()) || inc.getConfiguration() == null) {
			throw new IllegalArgumentException(
					"setJobRetries only applies to failedJob incidents (incident type: "
							+ inc.getIncidentType() + ")");
		}

		String jobId = inc.getConfiguration();
		engine.getManagementService().setJobRetries(jobId, retries.intValue());

		if (Boolean.TRUE.equals(clearAnnotation)) {
			try {
				engine.getRuntimeService().clearAnnotationForIncidentById(incidentId);
			} catch (Exception ignore) {}
		}

		// Re-fetch the incident — it may now be auto-resolved on the next
		// job execution cycle, but the caller probably wants to see the
		// updated retries count meanwhile.
		Incident refreshed = engine.getRuntimeService().createIncidentQuery()
				.incidentId(incidentId)
				.singleResult();

		Map<String, Object> result = new HashMap<>();
		result.put("setJobRetries", refreshed != null
				? queryExecutor.mapIncident(engine, refreshed, false)
				: null);
		return result;
	}

	public Map<String, Object> executeResolveIncident(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");

		ProcessEngine engine = getProcessEngine();
		// runtimeService.resolveIncident only accepts custom incidents; the
		// engine throws for failedJob/failedExternalTask. Let the engine's
		// exception propagate as the operation error — the frontend warns
		// users to use setJobRetries for failedJob.
		engine.getRuntimeService().resolveIncident(id);

		Map<String, Object> result = new HashMap<>();
		result.put("resolveIncident", true);
		return result;
	}

	public Map<String, Object> executeSetIncidentAnnotation(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String id = (String) input.get("id");
		String annotation = (String) input.get("annotation");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");

		ProcessEngine engine = getProcessEngine();
		if (annotation == null || annotation.isEmpty()) {
			engine.getRuntimeService().clearAnnotationForIncidentById(id);
		} else {
			engine.getRuntimeService().setAnnotationForIncidentById(id, annotation);
		}

		Incident inc = engine.getRuntimeService().createIncidentQuery()
				.incidentId(id)
				.singleResult();

		Map<String, Object> result = new HashMap<>();
		result.put("setIncidentAnnotation", inc != null
				? queryExecutor.mapIncident(engine, inc, false)
				: null);
		return result;
	}

	private Integer getIntValue(Object v) {
		if (v == null) return null;
		if (v instanceof Number) return ((Number) v).intValue();
		try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
	}

	// =========================================================================
	// Process Instance Migration mutations
	// =========================================================================

	/**
	 * Build a migration plan from a source to a target process definition.
	 *
	 * The plan returned here is built in-memory and discarded after the
	 * response is serialised — Camunda's MigrationPlan is not persisted on
	 * the engine until it is executed via migrateProcessInstance. The
	 * frontend uses this as a "preview" step so the operator can confirm the
	 * activity mappings before committing.
	 */
	public Map<String, Object> executeCreateMigrationPlan(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String sourceId = (String) input.get("sourceProcessDefinitionId");
		String targetId = (String) input.get("targetProcessDefinitionId");
		Boolean mapEqualActivities = (Boolean) input.get("mapEqualActivities");
		Boolean updateEventTriggers = (Boolean) input.get("updateEventTriggers");

		if (sourceId == null || sourceId.isEmpty()) {
			throw new IllegalArgumentException("sourceProcessDefinitionId is required");
		}
		if (targetId == null || targetId.isEmpty()) {
			throw new IllegalArgumentException("targetProcessDefinitionId is required");
		}

		ProcessEngine engine = getProcessEngine();
		MigrationPlan plan = buildMigrationPlan(
				engine, sourceId, targetId,
				mapEqualActivities, updateEventTriggers);

		Map<String, Object> result = new HashMap<>();
		result.put("createMigrationPlan", mapMigrationPlan(plan));
		return result;
	}

	/**
	 * Queue a process-instance migration as a CMS JobManager job. The job
	 * itself drives Camunda's async batch executor and republishes its
	 * progress through {@code jobProgress(jobId)} so the BPM Console can use
	 * the same subscription plumbing the Content Browser uses for bulk
	 * deletes.
	 *
	 * <p>The mutation returns immediately with the {@code jobId} the client
	 * should subscribe to. The migration's underlying Camunda batch is
	 * created later, inside the JobManager worker, so its id is not known at
	 * mutation time — clients observe it via {@code jobProgress} updates if
	 * needed. {@code abortable=false} because Camunda 7 batches cannot be
	 * safely cancelled mid-flight; the client uses that flag to hide the
	 * Abort button on the migration overlay.
	 */
	public Map<String, Object> executeMigrateProcessInstance(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String sourceId = (String) input.get("sourceProcessDefinitionId");
		String targetId = (String) input.get("targetProcessDefinitionId");
		Boolean mapEqualActivities = (Boolean) input.get("mapEqualActivities");
		Boolean updateEventTriggers = (Boolean) input.get("updateEventTriggers");
		Boolean skipCustomListeners = (Boolean) input.get("skipCustomListeners");
		Boolean skipIoMappings = (Boolean) input.get("skipIoMappings");
		Boolean allActiveInstances = (Boolean) input.get("allActiveInstances");

		@SuppressWarnings("unchecked")
		List<String> processInstanceIds = (List<String>) input.get("processInstanceIds");

		if (sourceId == null || sourceId.isEmpty()) {
			throw new IllegalArgumentException("sourceProcessDefinitionId is required");
		}
		if (targetId == null || targetId.isEmpty()) {
			throw new IllegalArgumentException("targetProcessDefinitionId is required");
		}
		boolean useAll = Boolean.TRUE.equals(allActiveInstances);
		boolean useIds = processInstanceIds != null && !processInstanceIds.isEmpty();
		if (!useAll && !useIds) {
			throw new IllegalArgumentException(
					"Either allActiveInstances=true or a non-empty processInstanceIds must be supplied");
		}

		// Build (and discard) the plan once up-front so any obvious mis-configuration
		// — unknown definition ids, incompatible activities — is reported synchronously
		// instead of disappearing into the worker thread's error log. The actual
		// execution happens inside the JobManager worker, which rebuilds the plan
		// on its own ProcessEngine handle.
		ProcessEngine engine = getProcessEngine();
		buildMigrationPlan(engine, sourceId, targetId, mapEqualActivities, updateEventTriggers);

		MigrateInstancesJob.MigrationRequest req = new MigrateInstancesJob.MigrationRequest(
				sourceId,
				targetId,
				useIds ? processInstanceIds : null,
				useAll,
				mapEqualActivities == null || Boolean.TRUE.equals(mapEqualActivities),
				Boolean.TRUE.equals(updateEventTriggers),
				Boolean.TRUE.equals(skipCustomListeners),
				Boolean.TRUE.equals(skipIoMappings));

		String jobId = enqueueMigrationJob(req);

		Map<String, Object> handle = new HashMap<>();
		handle.put("id", jobId);
		handle.put("jobType", MigrateInstancesJob.TYPE);
		handle.put("status", JobStatus.QUEUED.toExternalString());
		handle.put("abortable", false);

		Map<String, Object> result = new HashMap<>();
		result.put("migrateProcessInstance", handle);
		return result;
	}

	/**
	 * Persist a fresh JCR job record under {@code /var/jobs/YYYY/MM/job-<id>}
	 * and submit a {@link MigrateInstancesJob} that will perform the actual
	 * migration on a worker thread. Returns the generated job id so the
	 * client can subscribe to {@code jobProgress(jobId)} immediately.
	 */
	private String enqueueMigrationJob(MigrateInstancesJob.MigrationRequest req) throws Exception {
		String workspaceName = session.getWorkspace().getName();
		String userId = session.getUserID();
		String jobId = JobNodes.newJobId();

		Session mgmt = CmsService.getRepository().login(
				new CmsServiceCredentials(userId), workspaceName);
		try {
			JobNodes.createJobNode(mgmt, jobId, MigrateInstancesJob.TYPE, userId, 0);
			mgmt.save();
		} catch (Throwable ex) {
			try { mgmt.refresh(false); } catch (Throwable ignore) {}
			throw new RuntimeException(
					"Failed to create JCR job node for migration job " + jobId, ex);
		} finally {
			try { mgmt.logout(); } catch (Throwable ignore) {}
		}

		try {
			CmsService.getJobManager().submit(
					new MigrateInstancesJob(jobId, workspaceName, userId, 0, req));
		} catch (Throwable ex) {
			// The JCR record already exists; surface the error so the client
			// doesn't poll a job that will never start.
			throw new RuntimeException(
					"Failed to submit MigrateInstancesJob " + jobId, ex);
		}
		return jobId;
	}

	private MigrationPlan buildMigrationPlan(
			ProcessEngine engine,
			String sourceId,
			String targetId,
			Boolean mapEqualActivities,
			Boolean updateEventTriggers) {
		MigrationPlanBuilder builder = engine.getRuntimeService()
				.createMigrationPlan(sourceId, targetId);
		// Default to mapEqualActivities() — the only "automatic" strategy
		// the engine offers. A future iteration may accept explicit
		// instructions for the detailed migration editor UX.
		if (mapEqualActivities == null || Boolean.TRUE.equals(mapEqualActivities)) {
			MigrationInstructionsBuilder instructions = builder.mapEqualActivities();
			if (Boolean.TRUE.equals(updateEventTriggers)) {
				instructions = instructions.updateEventTriggers();
			}
			builder = instructions;
		}
		return builder.build();
	}

	private Map<String, Object> mapMigrationPlan(MigrationPlan plan) {
		Map<String, Object> m = new HashMap<>();
		m.put("sourceProcessDefinitionId", plan.getSourceProcessDefinitionId());
		m.put("targetProcessDefinitionId", plan.getTargetProcessDefinitionId());
		List<Map<String, Object>> instructions = new ArrayList<>();
		List<MigrationInstruction> list = plan.getInstructions();
		if (list != null) {
			for (MigrationInstruction inst : list) {
				Map<String, Object> i = new HashMap<>();
				String src = inst.getSourceActivityId();
				String tgt = inst.getTargetActivityId();
				i.put("sourceActivityIds", src != null ? List.of(src) : List.of());
				i.put("targetActivityIds", tgt != null ? List.of(tgt) : List.of());
				i.put("updateEventTrigger", inst.isUpdateEventTrigger());
				instructions.add(i);
			}
		}
		m.put("instructions", instructions);
		return m;
	}

	// =========================================================================
	// Mapping helpers
	// =========================================================================

	private Map<String, Object> mapProcessInstance(ProcessEngine engine, ProcessInstance instance) {
		Map<String, Object> m = new HashMap<>();
		m.put("id", instance.getId());
		m.put("definitionId", instance.getProcessDefinitionId());
		m.put("definitionKey", instance.getProcessDefinitionId()); // will be refined below
		m.put("businessKey", instance.getBusinessKey());
		m.put("suspended", instance.isSuspended());
		m.put("ended", instance.isEnded());
		m.put("startTime", null);
		m.put("endTime", null);
		m.put("durationInMillis", null);
		m.put("incidentCount", 0);
		m.put("variables", new ArrayList<>());

		// Get the definition key
		try {
			org.camunda.bpm.engine.repository.ProcessDefinition def =
					engine.getRepositoryService().createProcessDefinitionQuery()
					.processDefinitionId(instance.getProcessDefinitionId())
					.singleResult();
			if (def != null) m.put("definitionKey", def.getKey());
		} catch (Exception ignore) {}

		// Get start time from history
		try {
			HistoricProcessInstance historic = engine.getHistoryService()
					.createHistoricProcessInstanceQuery()
					.processInstanceId(instance.getId())
					.singleResult();
			if (historic != null) {
				m.put("startTime", formatDate(historic.getStartTime()));
			}
		} catch (Exception ignore) {}

		return m;
	}

	private Map<String, Object> mapHistoricInstance(ProcessEngine engine, HistoricProcessInstance inst) {
		Map<String, Object> m = new HashMap<>();
		m.put("id", inst.getId());
		m.put("definitionId", inst.getProcessDefinitionId());
		m.put("definitionKey", inst.getProcessDefinitionKey());
		m.put("businessKey", inst.getBusinessKey());
		m.put("suspended", inst.getState() != null && inst.getState().contains("SUSPENDED"));
		m.put("ended", inst.getEndTime() != null);
		m.put("startTime", formatDate(inst.getStartTime()));
		m.put("endTime", formatDate(inst.getEndTime()));
		m.put("durationInMillis", inst.getDurationInMillis());
		m.put("incidentCount", 0);
		m.put("variables", new ArrayList<>());
		return m;
	}

	private Map<String, Object> getTaskMap(ProcessEngine engine, String taskId) {
		Task task = engine.getTaskService().createTaskQuery().taskId(taskId).initializeFormKeys().singleResult();
		if (task == null) {
			Map<String, Object> m = new HashMap<>();
			m.put("id", taskId);
			m.put("name", null);
			m.put("assignee", null);
			m.put("owner", null);
			return m;
		}
		Map<String, Object> m = new HashMap<>();
		m.put("id", task.getId());
		m.put("name", task.getName());
		m.put("description", task.getDescription());
		m.put("assignee", task.getAssignee());
		m.put("owner", task.getOwner());
		m.put("created", formatDate(task.getCreateTime()));
		m.put("due", formatDate(task.getDueDate()));
		m.put("followUp", formatDate(task.getFollowUpDate()));
		m.put("priority", task.getPriority());
		m.put("suspended", task.isSuspended());
		m.put("processInstanceId", task.getProcessInstanceId());
		m.put("processDefinitionId", task.getProcessDefinitionId());
		m.put("executionId", task.getExecutionId());
		m.put("taskDefinitionKey", task.getTaskDefinitionKey());
		m.put("formKey", task.getFormKey());
		m.put("processDefinitionKey", null);
		m.put("candidateUsers", new ArrayList<>());
		m.put("candidateGroups", new ArrayList<>());
		m.put("variables", new ArrayList<>());
		m.put("localVariables", new ArrayList<>());
		return m;
	}

	// =========================================================================
	// Internal helpers
	// =========================================================================

	private ProcessEngine getProcessEngine() {
		String workspaceName = session.getWorkspace().getName();
		return CmsService.getWorkspaceProcessEngineProvider(workspaceName).getProcessEngine();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> extractInput(GraphQLRequest request) {
		Map<String, Object> variables = request.getVariables();
		Object input = variables != null ? variables.get("input") : null;
		if (input instanceof Map) return (Map<String, Object>) input;
		return new HashMap<>();
	}

	private Map<String, Object> toVariableMap(List<Map<String, Object>> variableInputs) {
		Map<String, Object> vars = new HashMap<>();
		if (variableInputs == null) return vars;
		for (Map<String, Object> v : variableInputs) {
			String name = (String) v.get("name");
			Object value = v.get("value");
			String type = (String) v.get("type");
			if (name == null) continue;
			vars.put(name, convertVariableValue(value, type));
		}
		return vars;
	}

	private Object convertVariableValue(Object value, String type) {
		String str = value != null ? value.toString() : null;
		// When no explicit type is supplied we fall back to a String value (typed,
		// so that null is preserved as a typed-null String rather than a raw null
		// which the engine cannot type-resolve).
		if (type == null) {
			return org.camunda.bpm.engine.variable.Variables.stringValue(str);
		}
		switch (type) {
			case "Long":
			case "Integer": {
				Long parsed = null;
				if (str != null && !str.isEmpty()) {
					try { parsed = Long.parseLong(str); } catch (Exception e) { /* keep null */ }
				}
				return org.camunda.bpm.engine.variable.Variables.longValue(parsed);
			}
			case "Double": {
				Double parsed = null;
				if (str != null && !str.isEmpty()) {
					try { parsed = Double.parseDouble(str); } catch (Exception e) { /* keep null */ }
				}
				return org.camunda.bpm.engine.variable.Variables.doubleValue(parsed);
			}
			case "Boolean": {
				Boolean parsed = null;
				if (str != null && !str.isEmpty()) {
					parsed = Boolean.parseBoolean(str);
				}
				return org.camunda.bpm.engine.variable.Variables.booleanValue(parsed);
			}
			case "Date": {
				Date parsed = null;
				if (str != null && !str.isEmpty()) {
					try {
						parsed = Date.from(Instant.parse(str));
					} catch (Exception e1) {
						// datetime-local format: "2026-03-27T18:33" or "2026-03-27T18:33:00"
						try {
							java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(str);
							parsed = Date.from(ldt.atZone(java.time.ZoneId.systemDefault()).toInstant());
						} catch (Exception e2) {
							// keep null — caller asked for Date but the value can't be parsed
						}
					}
				}
				return org.camunda.bpm.engine.variable.Variables.dateValue(parsed);
			}
			default:
				return org.camunda.bpm.engine.variable.Variables.stringValue(str);
		}
	}

	private String formatDate(Date date) {
		if (date == null) return null;
		return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(date);
	}

	private String getStringVar(Map<String, Object> vars, String name) {
		return vars != null ? (String) vars.get(name) : null;
	}

	private Boolean getBoolVarOrNull(Map<String, Object> vars, String name) {
		if (vars == null) return null;
		Object v = vars.get(name);
		if (v == null) return null;
		if (v instanceof Boolean) return (Boolean) v;
		return Boolean.parseBoolean(v.toString());
	}
}

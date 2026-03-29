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

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Comment;
import org.camunda.bpm.engine.task.Task;
import org.mintjams.rt.cms.internal.CmsService;

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
		engine.getRuntimeService().deleteProcessInstance(id, reason != null ? reason : "Cancelled via BPM Manager");

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
		engine.getRuntimeService().deleteProcessInstance(id, "Deleted via BPM Manager", skip);

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
		engine.getRuntimeService().setVariables(processInstanceId, vars);

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
		if (value == null) return null;
		String str = value.toString();
		if (type == null) return str;
		switch (type) {
			case "Long":
			case "Integer":
				try { return Long.parseLong(str); } catch (Exception e) { return str; }
			case "Double":
				try { return Double.parseDouble(str); } catch (Exception e) { return str; }
			case "Boolean":
				return Boolean.parseBoolean(str);
			case "Date":
				try {
					return Date.from(Instant.parse(str));
				} catch (Exception e1) {
					// datetime-local format: "2026-03-27T18:33" or "2026-03-27T18:33:00"
					try {
						java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(str);
						return Date.from(ldt.atZone(java.time.ZoneId.systemDefault()).toInstant());
					} catch (Exception e2) {
						return str;
					}
				}
			default:
				return str;
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

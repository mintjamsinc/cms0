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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstanceQuery;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstanceQuery;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.runtime.IncidentQuery;
import org.camunda.bpm.engine.task.IdentityLink;
import org.camunda.bpm.engine.task.IdentityLinkType;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.task.TaskQuery;
import org.mintjams.rt.cms.internal.CmsService;

/**
 * GraphQL Query executor for Camunda 7 BPM operations.
 * Uses the workspace-specific ProcessEngine to query process definitions,
 * instances, and tasks.
 */
public class BpmQueryExecutor {

	private final Session session;

	public BpmQueryExecutor(Session session) {
		this.session = session;
	}

	// =========================================================================
	// Process Definition queries
	// =========================================================================

	public Map<String, Object> executeProcessDefinitionQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		String key = getStringVar(variables, "key");
		Integer version = getIntVarOrNull(variables, "version");

		ProcessEngine engine = getProcessEngine();
		ProcessDefinitionQuery q = engine.getRepositoryService().createProcessDefinitionQuery();

		if (id != null) {
			q.processDefinitionId(id);
		} else if (key != null) {
			q.processDefinitionKey(key);
			if (version != null) {
				q.processDefinitionVersion(version);
			} else {
				q.latestVersion();
			}
		}

		ProcessDefinition def = q.singleResult();
		Map<String, Object> result = new HashMap<>();
		result.put("processDefinition", def != null ? mapProcessDefinition(engine, def) : null);
		return result;
	}

	public Map<String, Object> executeProcessDefinitionsQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		int first = getIntVar(variables, "first", 100);
		String afterCursor = getStringVar(variables, "after");
		String key = getStringVar(variables, "key");
		String name = getStringVar(variables, "name");
		Boolean latestVersion = getBoolVarOrNull(variables, "latestVersion");
		Boolean suspended = getBoolVarOrNull(variables, "suspended");

		ProcessEngine engine = getProcessEngine();
		ProcessDefinitionQuery q = engine.getRepositoryService().createProcessDefinitionQuery();

		if (key != null && !key.isEmpty()) {
			q.processDefinitionKeyLike("%" + key + "%");
		}
		if (name != null && !name.isEmpty()) {
			q.processDefinitionNameLike("%" + name + "%");
		}
		if (Boolean.TRUE.equals(latestVersion)) {
			q.latestVersion();
		}
		if (Boolean.TRUE.equals(suspended)) {
			q.suspended();
		} else if (Boolean.FALSE.equals(suspended)) {
			q.active();
		}

		q.orderByProcessDefinitionKey().asc().orderByProcessDefinitionVersion().desc();

		List<ProcessDefinition> defs = q.list();

		List<Map<String, Object>> items = new ArrayList<>();
		for (ProcessDefinition def : defs) {
			items.add(mapProcessDefinition(engine, def));
		}

		return buildConnection("processDefinitions", items, first, afterCursor);
	}

	public Map<String, Object> executeProcessDefinitionXmlQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");
		if (id == null || id.isEmpty()) {
			Map<String, Object> result = new HashMap<>();
			result.put("processDefinitionXml", null);
			return result;
		}

		ProcessEngine engine = getProcessEngine();
		ProcessDefinition def = engine.getRepositoryService().createProcessDefinitionQuery()
				.processDefinitionId(id).singleResult();

		Map<String, Object> result = new HashMap<>();
		if (def == null) {
			result.put("processDefinitionXml", null);
			return result;
		}

		try (InputStream is = engine.getRepositoryService().getProcessModel(id)) {
			String xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			result.put("processDefinitionXml", xml);
		}
		return result;
	}

	// =========================================================================
	// Process Instance queries
	// =========================================================================

	public Map<String, Object> executeProcessInstanceQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");

		ProcessEngine engine = getProcessEngine();
		Map<String, Object> result = new HashMap<>();
		if (id == null || id.isEmpty()) {
			result.put("processInstance", null);
			return result;
		}

		// Use history service to get both active and ended instances
		HistoricProcessInstance historic = engine.getHistoryService()
				.createHistoricProcessInstanceQuery()
				.processInstanceId(id)
				.singleResult();

		result.put("processInstance", historic != null ? mapHistoricProcessInstance(engine, historic, true) : null);
		return result;
	}

	public Map<String, Object> executeProcessInstancesQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		int first = getIntVar(variables, "first", 20);
		String afterCursor = getStringVar(variables, "after");
		String definitionKey = getStringVar(variables, "definitionKey");
		String definitionId = getStringVar(variables, "definitionId");
		String businessKey = getStringVar(variables, "businessKey");
		Boolean active = getBoolVarOrNull(variables, "active");
		Boolean suspended = getBoolVarOrNull(variables, "suspended");
		Boolean withIncidents = getBoolVarOrNull(variables, "withIncidents");
		String startedBefore = getStringVar(variables, "startedBefore");
		String startedAfter = getStringVar(variables, "startedAfter");

		ProcessEngine engine = getProcessEngine();
		HistoricProcessInstanceQuery q = engine.getHistoryService()
				.createHistoricProcessInstanceQuery();

		if (definitionKey != null && !definitionKey.isEmpty()) {
			q.processDefinitionKey(definitionKey);
		}
		if (definitionId != null && !definitionId.isEmpty()) {
			q.processDefinitionId(definitionId);
		}
		if (businessKey != null && !businessKey.isEmpty()) {
			q.processInstanceBusinessKey(businessKey);
		}
		if (Boolean.TRUE.equals(active)) {
			q.unfinished();
		} else if (Boolean.FALSE.equals(active)) {
			q.finished();
		}
		if (startedBefore != null && !startedBefore.isEmpty()) {
			q.startedBefore(parseDate(startedBefore));
		}
		if (startedAfter != null && !startedAfter.isEmpty()) {
			q.startedAfter(parseDate(startedAfter));
		}
		if (Boolean.TRUE.equals(withIncidents)) {
			q.withIncidents();
		}

		q.orderByProcessInstanceStartTime().desc();

		List<HistoricProcessInstance> instances = q.list();

		// Apply suspended filter (historic query doesn't have direct suspended filter)
		if (suspended != null) {
			List<HistoricProcessInstance> filtered = new ArrayList<>();
			for (HistoricProcessInstance inst : instances) {
				boolean isSuspended = inst.getState() != null &&
						inst.getState().contains("SUSPENDED");
				if (suspended.equals(isSuspended)) {
					filtered.add(inst);
				}
			}
			instances = filtered;
		}

		List<Map<String, Object>> items = new ArrayList<>();
		for (HistoricProcessInstance inst : instances) {
			items.add(mapHistoricProcessInstance(engine, inst, false));
		}

		return buildConnection("processInstances", items, first, afterCursor);
	}

	// =========================================================================
	// Task queries
	// =========================================================================

	public Map<String, Object> executeTaskQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String id = getStringVar(variables, "id");

		ProcessEngine engine = getProcessEngine();
		Map<String, Object> result = new HashMap<>();
		if (id == null || id.isEmpty()) {
			result.put("task", null);
			return result;
		}

		Task task = engine.getTaskService().createTaskQuery()
				.taskId(id)
				.singleResult();

		if (task == null) {
			result.put("task", null);
			return result;
		}

		result.put("task", mapTask(engine, task, true));
		return result;
	}

	public Map<String, Object> executeTasksQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		int first = getIntVar(variables, "first", 20);
		String afterCursor = getStringVar(variables, "after");
		String assignee = getStringVar(variables, "assignee");
		@SuppressWarnings("unchecked")
		List<String> assigneeIn = (List<String>) getListVar(variables, "assigneeIn");
		String candidateUser = getStringVar(variables, "candidateUser");
		String candidateGroup = getStringVar(variables, "candidateGroup");
		@SuppressWarnings("unchecked")
		List<String> candidateGroups = (List<String>) getListVar(variables, "candidateGroups");
		Boolean unassigned = getBoolVarOrNull(variables, "unassigned");
		String processInstanceId = getStringVar(variables, "processInstanceId");
		String processDefinitionKey = getStringVar(variables, "processDefinitionKey");
		String taskDefinitionKey = getStringVar(variables, "taskDefinitionKey");
		String dueBefore = getStringVar(variables, "dueBefore");
		String dueAfter = getStringVar(variables, "dueAfter");
		String createdBefore = getStringVar(variables, "createdBefore");
		String createdAfter = getStringVar(variables, "createdAfter");
		Integer priority = getIntVarOrNull(variables, "priority");
		Integer priorityGte = getIntVarOrNull(variables, "priorityHigherThanOrEquals");
		Integer priorityLte = getIntVarOrNull(variables, "priorityLowerThanOrEquals");
		String sortBy = getStringVar(variables, "sortBy");
		String sortOrder = getStringVar(variables, "sortOrder");

		ProcessEngine engine = getProcessEngine();
		TaskQuery q = engine.getTaskService().createTaskQuery();

		if (assignee != null && !assignee.isEmpty()) {
			q.taskAssignee(assignee);
		}
		if (assigneeIn != null && !assigneeIn.isEmpty()) {
			q.taskAssigneeIn(assigneeIn.toArray(new String[0]));
		}
		if (candidateUser != null && !candidateUser.isEmpty()) {
			q.taskCandidateUser(candidateUser);
		}
		if (candidateGroup != null && !candidateGroup.isEmpty()) {
			q.taskCandidateGroup(candidateGroup);
		}
		if (candidateGroups != null && !candidateGroups.isEmpty()) {
			q.taskCandidateGroupIn(candidateGroups);
		}
		if (Boolean.TRUE.equals(unassigned)) {
			q.taskUnassigned();
		}
		if (processInstanceId != null && !processInstanceId.isEmpty()) {
			q.processInstanceId(processInstanceId);
		}
		if (processDefinitionKey != null && !processDefinitionKey.isEmpty()) {
			q.processDefinitionKey(processDefinitionKey);
		}
		if (taskDefinitionKey != null && !taskDefinitionKey.isEmpty()) {
			q.taskDefinitionKey(taskDefinitionKey);
		}
		if (dueBefore != null && !dueBefore.isEmpty()) {
			q.dueBefore(parseDate(dueBefore));
		}
		if (dueAfter != null && !dueAfter.isEmpty()) {
			q.dueAfter(parseDate(dueAfter));
		}
		if (createdBefore != null && !createdBefore.isEmpty()) {
			q.taskCreatedBefore(parseDate(createdBefore));
		}
		if (createdAfter != null && !createdAfter.isEmpty()) {
			q.taskCreatedAfter(parseDate(createdAfter));
		}
		if (priority != null) {
			q.taskPriority(priority);
		}
		if (priorityGte != null) {
			q.taskMinPriority(priorityGte);
		}
		if (priorityLte != null) {
			q.taskMaxPriority(priorityLte);
		}

		// Apply sort
		if ("NAME".equals(sortBy)) {
			q.orderByTaskName();
		} else if ("CREATED".equals(sortBy)) {
			q.orderByTaskCreateTime();
		} else if ("DUE".equals(sortBy)) {
			q.orderByDueDate();
		} else if ("PRIORITY".equals(sortBy)) {
			q.orderByTaskPriority();
		} else if ("ASSIGNEE".equals(sortBy)) {
			q.orderByTaskAssignee();
		} else {
			q.orderByTaskCreateTime();
		}

		if ("ASC".equals(sortOrder)) {
			q.asc();
		} else {
			q.desc();
		}

		List<Task> tasks = q.list();

		List<Map<String, Object>> items = new ArrayList<>();
		for (Task task : tasks) {
			items.add(mapTask(engine, task, false));
		}

		return buildConnection("tasks", items, first, afterCursor);
	}

	public Map<String, Object> executeTaskCountsQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String assignee = getStringVar(variables, "assignee");
		String candidateUser = getStringVar(variables, "candidateUser");
		@SuppressWarnings("unchecked")
		List<String> candidateGroups = (List<String>) getListVar(variables, "candidateGroups");

		ProcessEngine engine = getProcessEngine();

		long total = 0;
		long unassigned = 0;
		long assigned = 0;
		long overdue = 0;
		long dueToday = 0;
		long dueThisWeek = 0;

		TaskQuery base = engine.getTaskService().createTaskQuery();
		if (assignee != null && !assignee.isEmpty()) {
			base.taskAssignee(assignee);
		} else if (candidateUser != null && !candidateUser.isEmpty()) {
			if (candidateGroups != null && !candidateGroups.isEmpty()) {
				base.taskCandidateGroupIn(candidateGroups);
			} else {
				base.taskCandidateUser(candidateUser);
			}
		}

		total = base.count();

		// Unassigned
		{
			TaskQuery q = engine.getTaskService().createTaskQuery().taskUnassigned();
			if (candidateUser != null && !candidateUser.isEmpty()) {
				q.taskCandidateUser(candidateUser);
			}
			if (candidateGroups != null && !candidateGroups.isEmpty()) {
				q.taskCandidateGroupIn(candidateGroups);
			}
			unassigned = q.count();
		}

		// Assigned
		assigned = total - unassigned;

		// Overdue: due before now
		{
			Date now = new Date();
			TaskQuery q = engine.getTaskService().createTaskQuery().dueBefore(now);
			if (assignee != null && !assignee.isEmpty()) {
				q.taskAssignee(assignee);
			}
			overdue = q.count();
		}

		// Due today
		{
			Calendar cal = Calendar.getInstance();
			cal.set(Calendar.HOUR_OF_DAY, 0);
			cal.set(Calendar.MINUTE, 0);
			cal.set(Calendar.SECOND, 0);
			cal.set(Calendar.MILLISECOND, 0);
			Date startOfDay = cal.getTime();
			cal.add(Calendar.DAY_OF_MONTH, 1);
			Date endOfDay = cal.getTime();
			TaskQuery q = engine.getTaskService().createTaskQuery()
					.dueAfter(startOfDay).dueBefore(endOfDay);
			if (assignee != null && !assignee.isEmpty()) {
				q.taskAssignee(assignee);
			}
			dueToday = q.count();
		}

		// Due this week
		{
			Calendar cal = Calendar.getInstance();
			cal.set(Calendar.HOUR_OF_DAY, 0);
			cal.set(Calendar.MINUTE, 0);
			cal.set(Calendar.SECOND, 0);
			cal.set(Calendar.MILLISECOND, 0);
			Date startOfDay = cal.getTime();
			cal.add(Calendar.DAY_OF_MONTH, 7);
			Date endOfWeek = cal.getTime();
			TaskQuery q = engine.getTaskService().createTaskQuery()
					.dueAfter(startOfDay).dueBefore(endOfWeek);
			if (assignee != null && !assignee.isEmpty()) {
				q.taskAssignee(assignee);
			}
			dueThisWeek = q.count();
		}

		Map<String, Object> counts = new HashMap<>();
		counts.put("total", total);
		counts.put("unassigned", unassigned);
		counts.put("assigned", assigned);
		counts.put("overdue", overdue);
		counts.put("dueToday", dueToday);
		counts.put("dueThisWeek", dueThisWeek);

		Map<String, Object> result = new HashMap<>();
		result.put("taskCounts", counts);
		return result;
	}

	// =========================================================================
	// Mapping helpers
	// =========================================================================

	Map<String, Object> mapProcessDefinition(ProcessEngine engine, ProcessDefinition def) {
		Map<String, Object> m = new HashMap<>();
		m.put("id", def.getId());
		m.put("key", def.getKey());
		m.put("name", def.getName());
		m.put("description", def.getDescription());
		m.put("version", def.getVersion());
		m.put("deploymentId", def.getDeploymentId());
		try {
			org.camunda.bpm.engine.repository.Deployment deployment = engine.getRepositoryService()
					.createDeploymentQuery().deploymentId(def.getDeploymentId()).singleResult();
			m.put("deploymentName", deployment != null ? deployment.getName() : null);
		} catch (Exception e) {
			m.put("deploymentName", null);
		}
		m.put("resourceName", def.getResourceName());
		m.put("diagramResourceName", def.getDiagramResourceName());
		m.put("suspended", def.isSuspended());
		m.put("category", def.getCategory());
		try {
			m.put("startFormKey", engine.getFormService().getStartFormKey(def.getId()));
		} catch (Exception e) {
			m.put("startFormKey", null);
		}
		return m;
	}

	private Map<String, Object> mapHistoricProcessInstance(
			ProcessEngine engine, HistoricProcessInstance inst, boolean includeVariables) {
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

		// Incident count
		try {
			IncidentQuery incQuery = engine.getRuntimeService().createIncidentQuery()
					.processInstanceId(inst.getId());
			m.put("incidentCount", (int) incQuery.count());
		} catch (Exception e) {
			m.put("incidentCount", 0);
		}

		if (includeVariables) {
			try {
				List<HistoricVariableInstance> vars = engine.getHistoryService()
						.createHistoricVariableInstanceQuery()
						.processInstanceId(inst.getId())
						.list();
				List<Map<String, Object>> varList = new ArrayList<>();
				for (HistoricVariableInstance v : vars) {
					varList.add(mapHistoricVariable(v));
				}
				m.put("variables", varList);
			} catch (Exception e) {
				m.put("variables", new ArrayList<>());
			}
		} else {
			m.put("variables", new ArrayList<>());
		}

		return m;
	}

	private Map<String, Object> mapHistoricVariable(HistoricVariableInstance v) {
		Map<String, Object> m = new HashMap<>();
		m.put("name", v.getName());
		m.put("type", v.getTypeName());
		Object val = v.getValue();
		m.put("value", val != null ? val.toString() : null);
		m.put("valueInfo", null);
		return m;
	}

	private Map<String, Object> mapTask(ProcessEngine engine, Task task, boolean includeDetails) {
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

		// Derive processDefinitionKey from definition id
		if (task.getProcessDefinitionId() != null) {
			try {
				ProcessDefinition def = engine.getRepositoryService()
						.createProcessDefinitionQuery()
						.processDefinitionId(task.getProcessDefinitionId())
						.singleResult();
				m.put("processDefinitionKey", def != null ? def.getKey() : null);
			} catch (Exception e) {
				m.put("processDefinitionKey", null);
			}
		} else {
			m.put("processDefinitionKey", null);
		}

		if (includeDetails) {
			// Candidate users and groups
			List<String> candidateUsers = new ArrayList<>();
			List<String> candidateGroups = new ArrayList<>();
			try {
				List<IdentityLink> links = engine.getTaskService().getIdentityLinksForTask(task.getId());
				for (IdentityLink link : links) {
					if (IdentityLinkType.CANDIDATE.equals(link.getType())) {
						if (link.getUserId() != null) candidateUsers.add(link.getUserId());
						if (link.getGroupId() != null) candidateGroups.add(link.getGroupId());
					}
				}
			} catch (Exception ignore) {}
			m.put("candidateUsers", candidateUsers);
			m.put("candidateGroups", candidateGroups);

			// Task variables
			try {
				Map<String, Object> taskVars = engine.getTaskService().getVariables(task.getId());
				List<Map<String, Object>> varList = new ArrayList<>();
				for (Map.Entry<String, Object> e : taskVars.entrySet()) {
					varList.add(mapSimpleVariable(e.getKey(), e.getValue()));
				}
				m.put("variables", varList);
			} catch (Exception e) {
				m.put("variables", new ArrayList<>());
			}

			// Task local variables
			try {
				Map<String, Object> localVars = engine.getTaskService().getVariablesLocal(task.getId());
				List<Map<String, Object>> varList = new ArrayList<>();
				for (Map.Entry<String, Object> e : localVars.entrySet()) {
					varList.add(mapSimpleVariable(e.getKey(), e.getValue()));
				}
				m.put("localVariables", varList);
			} catch (Exception e) {
				m.put("localVariables", new ArrayList<>());
			}
		} else {
			m.put("candidateUsers", new ArrayList<>());
			m.put("candidateGroups", new ArrayList<>());
			m.put("variables", new ArrayList<>());
			m.put("localVariables", new ArrayList<>());
		}

		return m;
	}

	private Map<String, Object> mapSimpleVariable(String name, Object value) {
		Map<String, Object> m = new HashMap<>();
		m.put("name", name);
		m.put("value", value != null ? value.toString() : null);
		m.put("valueInfo", null);
		if (value == null) {
			m.put("type", "Null");
		} else if (value instanceof String) {
			m.put("type", "String");
		} else if (value instanceof Long) {
			m.put("type", "Long");
		} else if (value instanceof Integer) {
			m.put("type", "Integer");
		} else if (value instanceof Double || value instanceof Float) {
			m.put("type", "Double");
		} else if (value instanceof Boolean) {
			m.put("type", "Boolean");
		} else if (value instanceof Date) {
			m.put("type", "Date");
			m.put("value", formatDate((Date) value));
		} else {
			m.put("type", "Object");
		}
		return m;
	}

	// =========================================================================
	// Relay connection builder
	// =========================================================================

	private Map<String, Object> buildConnection(
			String key, List<Map<String, Object>> items, int first, String afterCursor) {
		int totalCount = items.size();
		int startPosition = 0;
		if (afterCursor != null && !afterCursor.isEmpty()) {
			startPosition = decodeCursor(afterCursor) + 1;
		}

		List<Map<String, Object>> edges = new ArrayList<>();
		int endPosition = Math.min(startPosition + first, totalCount);
		for (int i = startPosition; i < endPosition; i++) {
			Map<String, Object> edge = new HashMap<>();
			edge.put("node", items.get(i));
			edge.put("cursor", encodeCursor(i));
			edges.add(edge);
		}

		Map<String, Object> pageInfo = new HashMap<>();
		pageInfo.put("hasNextPage", endPosition < totalCount);
		pageInfo.put("hasPreviousPage", startPosition > 0);
		pageInfo.put("startCursor", edges.isEmpty() ? null : encodeCursor(startPosition));
		pageInfo.put("endCursor", edges.isEmpty() ? null : encodeCursor(endPosition - 1));

		Map<String, Object> connection = new HashMap<>();
		connection.put("edges", edges);
		connection.put("pageInfo", pageInfo);
		connection.put("totalCount", totalCount);

		Map<String, Object> result = new HashMap<>();
		result.put(key, connection);
		return result;
	}

	// =========================================================================
	// Internal helpers
	// =========================================================================

	private ProcessEngine getProcessEngine() {
		String workspaceName = session.getWorkspace().getName();
		return CmsService.getWorkspaceProcessEngineProvider(workspaceName).getProcessEngine();
	}

	private String formatDate(Date date) {
		if (date == null) return null;
		return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(date);
	}

	private Date parseDate(String isoString) {
		try {
			return Date.from(Instant.parse(isoString));
		} catch (Exception e) {
			return null;
		}
	}

	private String encodeCursor(int position) {
		return Base64.getEncoder().encodeToString(("cursor:" + position).getBytes());
	}

	private int decodeCursor(String cursor) {
		try {
			String decoded = new String(Base64.getDecoder().decode(cursor));
			if (decoded.startsWith("cursor:")) {
				return Integer.parseInt(decoded.substring("cursor:".length()));
			}
		} catch (Exception ignore) {}
		return 0;
	}

	private String getStringVar(Map<String, Object> vars, String name) {
		return vars != null ? (String) vars.get(name) : null;
	}

	private int getIntVar(Map<String, Object> vars, String name, int defaultValue) {
		if (vars == null) return defaultValue;
		Object v = vars.get(name);
		if (v == null) return defaultValue;
		if (v instanceof Number) return ((Number) v).intValue();
		try { return Integer.parseInt(v.toString()); } catch (Exception e) { return defaultValue; }
	}

	private Integer getIntVarOrNull(Map<String, Object> vars, String name) {
		if (vars == null) return null;
		Object v = vars.get(name);
		if (v == null) return null;
		if (v instanceof Number) return ((Number) v).intValue();
		try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
	}

	private Boolean getBoolVarOrNull(Map<String, Object> vars, String name) {
		if (vars == null) return null;
		Object v = vars.get(name);
		if (v == null) return null;
		if (v instanceof Boolean) return (Boolean) v;
		return Boolean.parseBoolean(v.toString());
	}

	private Object getListVar(Map<String, Object> vars, String name) {
		if (vars == null) return null;
		return vars.get(name);
	}
}

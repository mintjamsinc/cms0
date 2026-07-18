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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;

import org.camunda.bpm.engine.AuthorizationException;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstanceQuery;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.migration.MigrationInstruction;
import org.camunda.bpm.engine.migration.MigrationInstructionsBuilder;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.migration.MigrationPlanBuilder;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.runtime.Incident;
import org.camunda.bpm.engine.runtime.IncidentQuery;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Comment;
import org.camunda.bpm.engine.task.IdentityLink;
import org.camunda.bpm.engine.task.IdentityLinkType;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.task.TaskQuery;
import org.camunda.bpm.engine.variable.Variables;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.job.bpm.MigrateInstancesJob;
import org.mintjams.rt.cms.internal.util.ISO8601;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;

/**
 * Contributes the platform's built-in BPM (Camunda 7) GraphQL schema — the
 * graphql-java migration target of the handmade {@code BpmQueryExecutor} /
 * {@code BpmMutationExecutor} — to the unified per-workspace
 * {@link org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider}.
 *
 * <p>It is a side-by-side reimplementation: its SDL ({@code bpm-schema.graphqls})
 * {@code extend}s the core Query/Mutation roots, and its {@link DataFetcher}s
 * obtain the per-workspace Camunda {@code ProcessEngine} exactly as the handmade
 * executors do ({@code CmsService.getWorkspaceProcessEngineProvider(ws)
 * .getProcessEngine()}), running under the request's workspace from
 * {@link GraphQLExecutionContext}. Camunda objects are projected into the same
 * flat maps the handmade engine produced (graphql-java then serves whatever the
 * client selects).
 *
 * <p>Coverage (all reads): process-definition / process-instance reads
 * ({@code processDefinition(s)}, {@code processDefinitionXml},
 * {@code processInstance(s)}, {@code activityHistory}), task reads
 * ({@code task}, {@code tasks}, {@code taskCounts}) and incident reads
 * ({@code incident}, {@code incidents}); process-instance mutations
 * ({@code startProcess}, suspend/activate/cancel/delete, {@code setProcessVariables})
 * and task mutations ({@code claimTask}, {@code unclaimTask}, {@code assignTask},
 * {@code setTaskAssignee}, {@code delegateTask}, {@code completeTask},
 * {@code setTaskVariables}, {@code addTaskComment}), and deployment/definition
 * mutations ({@code deployProcess}, {@code deleteDeployment},
 * {@code suspendProcessDefinition}, {@code activateProcessDefinition}),
 * incident mutations ({@code setJobRetries}, {@code resolveIncident},
 * {@code setIncidentAnnotation}) and migration mutations
 * ({@code createMigrationPlan}, and {@code migrateProcessInstance}, which
 * queues a CMS {@code MigrateInstancesJob}). The BPM subscription retyping
 * follows. All mutations run under the caller's identity (see
 * {@link #asCaller}).
 */
public final class PlatformBpmWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/bpm-schema.graphqls";

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "processDefinition",
						(DataFetcher<Object>) PlatformBpmWiringContributor::processDefinition)
				.dataFetcher("Query", "processDefinitions",
						(DataFetcher<Object>) PlatformBpmWiringContributor::processDefinitions)
				.dataFetcher("Query", "processDefinitionXml",
						(DataFetcher<Object>) PlatformBpmWiringContributor::processDefinitionXml)
				.dataFetcher("Query", "processInstance",
						(DataFetcher<Object>) PlatformBpmWiringContributor::processInstance)
				.dataFetcher("Query", "processInstances",
						(DataFetcher<Object>) PlatformBpmWiringContributor::processInstances)
				.dataFetcher("Query", "activityHistory",
						(DataFetcher<Object>) PlatformBpmWiringContributor::activityHistory)
				.dataFetcher("Query", "task", (DataFetcher<Object>) PlatformBpmWiringContributor::task)
				.dataFetcher("Query", "tasks", (DataFetcher<Object>) PlatformBpmWiringContributor::tasks)
				.dataFetcher("Query", "taskCounts", (DataFetcher<Object>) PlatformBpmWiringContributor::taskCounts)
				.dataFetcher("Query", "incident", (DataFetcher<Object>) PlatformBpmWiringContributor::incident)
				.dataFetcher("Query", "incidents", (DataFetcher<Object>) PlatformBpmWiringContributor::incidents)
				.dataFetcher("Mutation", "startProcess",
						(DataFetcher<Object>) PlatformBpmWiringContributor::startProcess)
				.dataFetcher("Mutation", "suspendProcessInstance",
						(DataFetcher<Object>) PlatformBpmWiringContributor::suspendProcessInstance)
				.dataFetcher("Mutation", "activateProcessInstance",
						(DataFetcher<Object>) PlatformBpmWiringContributor::activateProcessInstance)
				.dataFetcher("Mutation", "cancelProcessInstance",
						(DataFetcher<Object>) PlatformBpmWiringContributor::cancelProcessInstance)
				.dataFetcher("Mutation", "deleteProcessInstance",
						(DataFetcher<Object>) PlatformBpmWiringContributor::deleteProcessInstance)
				.dataFetcher("Mutation", "setProcessVariables",
						(DataFetcher<Object>) PlatformBpmWiringContributor::setProcessVariables)
				.dataFetcher("Mutation", "claimTask",
						(DataFetcher<Object>) PlatformBpmWiringContributor::claimTask)
				.dataFetcher("Mutation", "unclaimTask",
						(DataFetcher<Object>) PlatformBpmWiringContributor::unclaimTask)
				.dataFetcher("Mutation", "assignTask",
						(DataFetcher<Object>) PlatformBpmWiringContributor::assignTask)
				.dataFetcher("Mutation", "setTaskAssignee",
						(DataFetcher<Object>) PlatformBpmWiringContributor::setTaskAssignee)
				.dataFetcher("Mutation", "delegateTask",
						(DataFetcher<Object>) PlatformBpmWiringContributor::delegateTask)
				.dataFetcher("Mutation", "completeTask",
						(DataFetcher<Object>) PlatformBpmWiringContributor::completeTask)
				.dataFetcher("Mutation", "setTaskVariables",
						(DataFetcher<Object>) PlatformBpmWiringContributor::setTaskVariables)
				.dataFetcher("Mutation", "addTaskComment",
						(DataFetcher<Object>) PlatformBpmWiringContributor::addTaskComment)
				.dataFetcher("Mutation", "deployProcess",
						(DataFetcher<Object>) PlatformBpmWiringContributor::deployProcess)
				.dataFetcher("Mutation", "deleteDeployment",
						(DataFetcher<Object>) PlatformBpmWiringContributor::deleteDeployment)
				.dataFetcher("Mutation", "suspendProcessDefinition",
						(DataFetcher<Object>) PlatformBpmWiringContributor::suspendProcessDefinition)
				.dataFetcher("Mutation", "activateProcessDefinition",
						(DataFetcher<Object>) PlatformBpmWiringContributor::activateProcessDefinition)
				.dataFetcher("Mutation", "setJobRetries",
						(DataFetcher<Object>) PlatformBpmWiringContributor::setJobRetries)
				.dataFetcher("Mutation", "resolveIncident",
						(DataFetcher<Object>) PlatformBpmWiringContributor::resolveIncident)
				.dataFetcher("Mutation", "setIncidentAnnotation",
						(DataFetcher<Object>) PlatformBpmWiringContributor::setIncidentAnnotation)
				.dataFetcher("Mutation", "createMigrationPlan",
						(DataFetcher<Object>) PlatformBpmWiringContributor::createMigrationPlan)
				.dataFetcher("Mutation", "migrateProcessInstance",
						(DataFetcher<Object>) PlatformBpmWiringContributor::migrateProcessInstance);
	}

	// ---- queries -----------------------------------------------------------

	private static Object processDefinition(DataFetchingEnvironment environment) {
		ProcessEngine engine = engine(environment);
		String id = environment.getArgument("id");
		String key = environment.getArgument("key");
		Integer version = environment.getArgument("version");

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
		return (def != null) ? mapProcessDefinition(engine, def) : null;
	}

	private static Object processDefinitions(DataFetchingEnvironment environment) {
		ProcessEngine engine = engine(environment);
		int first = firstArg(environment, 100);
		String after = environment.getArgument("after");
		String key = environment.getArgument("key");
		String name = environment.getArgument("name");
		Boolean latestVersion = environment.getArgument("latestVersion");
		Boolean suspended = environment.getArgument("suspended");

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

		List<Map<String, Object>> items = new ArrayList<>();
		for (ProcessDefinition def : q.list()) {
			items.add(mapProcessDefinition(engine, def));
		}
		return connection(items, first, after);
	}

	private static Object processDefinitionXml(DataFetchingEnvironment environment) throws Exception {
		String id = environment.getArgument("id");
		if (id == null || id.isEmpty()) {
			return null;
		}
		ProcessEngine engine = engine(environment);
		ProcessDefinition def = engine.getRepositoryService().createProcessDefinitionQuery()
				.processDefinitionId(id).singleResult();
		if (def == null) {
			return null;
		}
		try (InputStream is = engine.getRepositoryService().getProcessModel(id)) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static Object processInstance(DataFetchingEnvironment environment) {
		String id = environment.getArgument("id");
		if (id == null || id.isEmpty()) {
			return null;
		}
		ProcessEngine engine = engine(environment);
		HistoricProcessInstance historic = engine.getHistoryService()
				.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult();
		return (historic != null) ? mapHistoricProcessInstance(engine, historic, true) : null;
	}

	private static Object processInstances(DataFetchingEnvironment environment) {
		ProcessEngine engine = engine(environment);
		int first = firstArg(environment, 20);
		String after = environment.getArgument("after");
		String definitionKey = environment.getArgument("definitionKey");
		String definitionId = environment.getArgument("definitionId");
		String businessKey = environment.getArgument("businessKey");
		Boolean active = environment.getArgument("active");
		Boolean suspended = environment.getArgument("suspended");
		Boolean withIncidents = environment.getArgument("withIncidents");
		String startedBefore = environment.getArgument("startedBefore");
		String startedAfter = environment.getArgument("startedAfter");

		HistoricProcessInstanceQuery q = engine.getHistoryService().createHistoricProcessInstanceQuery();
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
		if (suspended != null) {
			// Historic query has no direct suspended filter — post-filter on state.
			List<HistoricProcessInstance> filtered = new ArrayList<>();
			for (HistoricProcessInstance inst : instances) {
				boolean isSuspended = inst.getState() != null && inst.getState().contains("SUSPENDED");
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
		return connection(items, first, after);
	}

	private static Object activityHistory(DataFetchingEnvironment environment) {
		String processInstanceId = environment.getArgument("processInstanceId");
		if (processInstanceId == null || processInstanceId.isEmpty()) {
			throw new IllegalArgumentException("processInstanceId is required");
		}
		ProcessEngine engine = engine(environment);
		List<Map<String, Object>> items = new ArrayList<>();
		for (HistoricActivityInstance act : engine.getHistoryService().createHistoricActivityInstanceQuery()
				.processInstanceId(processInstanceId).orderByHistoricActivityInstanceStartTime().asc().list()) {
			Map<String, Object> m = new HashMap<>();
			m.put("id", act.getId());
			m.put("activityId", act.getActivityId());
			m.put("activityName", act.getActivityName());
			m.put("activityType", act.getActivityType());
			m.put("processInstanceId", act.getProcessInstanceId());
			m.put("startTime", formatDate(act.getStartTime()));
			m.put("endTime", (act.getEndTime() != null) ? formatDate(act.getEndTime()) : null);
			m.put("durationInMillis", act.getDurationInMillis());
			m.put("canceled", act.isCanceled());
			m.put("completeScope", act.isCompleteScope());
			items.add(m);
		}
		return items;
	}

	private static Object task(DataFetchingEnvironment environment) {
		String id = environment.getArgument("id");
		if (id == null || id.isEmpty()) {
			return null;
		}
		ProcessEngine engine = engine(environment);
		Task task = engine.getTaskService().createTaskQuery().taskId(id).initializeFormKeys().singleResult();
		if (task == null) {
			return null;
		}
		Map<String, Map<String, Object>> instanceMap = buildProcessInstanceMap(engine, Collections.singletonList(task));
		return mapTask(engine, task, true, instanceMap);
	}

	private static Object tasks(DataFetchingEnvironment environment) {
		ProcessEngine engine = engine(environment);
		int first = firstArg(environment, 20);
		String after = environment.getArgument("after");
		String assignee = environment.getArgument("assignee");
		List<String> assigneeIn = environment.getArgument("assigneeIn");
		String candidateUser = environment.getArgument("candidateUser");
		String candidateGroup = environment.getArgument("candidateGroup");
		List<String> candidateGroups = environment.getArgument("candidateGroups");
		Boolean unassigned = environment.getArgument("unassigned");
		String processInstanceId = environment.getArgument("processInstanceId");
		String processDefinitionId = environment.getArgument("processDefinitionId");
		String processDefinitionKey = environment.getArgument("processDefinitionKey");
		String taskDefinitionKey = environment.getArgument("taskDefinitionKey");
		String dueBefore = environment.getArgument("dueBefore");
		String dueAfter = environment.getArgument("dueAfter");
		String createdBefore = environment.getArgument("createdBefore");
		String createdAfter = environment.getArgument("createdAfter");
		Integer priority = environment.getArgument("priority");
		Integer priorityGte = environment.getArgument("priorityHigherThanOrEquals");
		Integer priorityLte = environment.getArgument("priorityLowerThanOrEquals");
		String sortBy = environment.getArgument("sortBy");
		String sortOrder = environment.getArgument("sortOrder");

		TaskQuery q = engine.getTaskService().createTaskQuery().initializeFormKeys();
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
		if (processDefinitionId != null && !processDefinitionId.isEmpty()) {
			q.processDefinitionId(processDefinitionId);
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

		if ("NAME".equals(sortBy)) {
			q.orderByTaskName();
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
		Map<String, Map<String, Object>> instanceMap = buildProcessInstanceMap(engine, tasks);
		List<Map<String, Object>> items = new ArrayList<>();
		for (Task task : tasks) {
			items.add(mapTask(engine, task, false, instanceMap));
		}
		return connection(items, first, after);
	}

	private static Object taskCounts(DataFetchingEnvironment environment) {
		ProcessEngine engine = engine(environment);
		String assignee = environment.getArgument("assignee");
		String candidateUser = environment.getArgument("candidateUser");
		List<String> candidateGroups = environment.getArgument("candidateGroups");
		ZoneId zone = resolveZone(environment.getArgument("timeZone"));

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
		long total = base.count();

		TaskQuery unassignedQ = engine.getTaskService().createTaskQuery().taskUnassigned();
		if (candidateUser != null && !candidateUser.isEmpty()) {
			unassignedQ.taskCandidateUser(candidateUser);
		}
		if (candidateGroups != null && !candidateGroups.isEmpty()) {
			unassignedQ.taskCandidateGroupIn(candidateGroups);
		}
		long unassigned = unassignedQ.count();
		long assigned = total - unassigned;

		TaskQuery overdueQ = engine.getTaskService().createTaskQuery().dueBefore(new Date());
		if (assignee != null && !assignee.isEmpty()) {
			overdueQ.taskAssignee(assignee);
		}
		long overdue = overdueQ.count();

		// "Due today" / "due this week" boundaries are calendar days resolved
		// in the caller's time zone (atStartOfDay is DST-safe) and converted to
		// absolute instants, because Camunda compares dueDate as an instant.
		// The window matches the original semantics: [start of today, +1 day)
		// for "today" and [start of today, +7 days) for "this week".
		ZonedDateTime startOfToday = ZonedDateTime.now(zone).toLocalDate().atStartOfDay(zone);
		Date startOfDay = Date.from(startOfToday.toInstant());
		Date endOfDay = Date.from(startOfToday.plusDays(1).toInstant());
		TaskQuery dueTodayQ = engine.getTaskService().createTaskQuery().dueAfter(startOfDay).dueBefore(endOfDay);
		if (assignee != null && !assignee.isEmpty()) {
			dueTodayQ.taskAssignee(assignee);
		}
		long dueToday = dueTodayQ.count();

		Date endOfWeek = Date.from(startOfToday.plusDays(7).toInstant());
		TaskQuery dueWeekQ = engine.getTaskService().createTaskQuery().dueAfter(startOfDay).dueBefore(endOfWeek);
		if (assignee != null && !assignee.isEmpty()) {
			dueWeekQ.taskAssignee(assignee);
		}
		long dueThisWeek = dueWeekQ.count();

		Map<String, Object> counts = new HashMap<>();
		counts.put("total", (int) total);
		counts.put("unassigned", (int) unassigned);
		counts.put("assigned", (int) assigned);
		counts.put("overdue", (int) overdue);
		counts.put("dueToday", (int) dueToday);
		counts.put("dueThisWeek", (int) dueThisWeek);
		return counts;
	}

	private static Object incident(DataFetchingEnvironment environment) {
		String id = environment.getArgument("id");
		if (id == null || id.isEmpty()) {
			return null;
		}
		// default true for a single fetch
		boolean withStack = !Boolean.FALSE.equals(environment.getArgument("includeStackTrace"));
		ProcessEngine engine = engine(environment);
		Incident inc = engine.getRuntimeService().createIncidentQuery().incidentId(id).singleResult();
		return (inc != null) ? mapIncident(engine, inc, withStack) : null;
	}

	private static Object incidents(DataFetchingEnvironment environment) {
		ProcessEngine engine = engine(environment);
		int first = firstArg(environment, 50);
		String after = environment.getArgument("after");
		String processInstanceId = environment.getArgument("processInstanceId");
		String processDefinitionId = environment.getArgument("processDefinitionId");
		String processDefinitionKey = environment.getArgument("processDefinitionKey");
		String incidentType = environment.getArgument("incidentType");
		String activityId = environment.getArgument("activityId");
		boolean withStack = Boolean.TRUE.equals(environment.getArgument("includeStackTrace"));

		IncidentQuery q = engine.getRuntimeService().createIncidentQuery();
		if (processInstanceId != null && !processInstanceId.isEmpty()) {
			q.processInstanceId(processInstanceId);
		}
		if (processDefinitionId != null && !processDefinitionId.isEmpty()) {
			q.processDefinitionId(processDefinitionId);
		}
		if (processDefinitionKey != null && !processDefinitionKey.isEmpty()) {
			q.processDefinitionKeyIn(processDefinitionKey);
		}
		if (incidentType != null && !incidentType.isEmpty()) {
			q.incidentType(incidentType);
		}
		if (activityId != null && !activityId.isEmpty()) {
			q.activityId(activityId);
		}
		List<Map<String, Object>> items = new ArrayList<>();
		for (Incident inc : q.list()) {
			items.add(mapIncident(engine, inc, withStack));
		}
		return connection(items, first, after);
	}

	// ---- mutations: process instances (mirror BpmMutationExecutor) ---------

	@SuppressWarnings("unchecked")
	private static Object startProcess(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> raw = environment.getArgument("input");
		Map<String, Object> input = (raw != null) ? raw : new HashMap<>();
		String definitionKey = (String) input.get("definitionKey");
		String definitionId = (String) input.get("definitionId");
		String businessKey = (String) input.get("businessKey");
		Map<String, Object> vars = toVariableMap((List<Map<String, Object>>) input.get("variables"));
		return asCaller(environment, engine -> {
			ProcessInstance instance;
			if (definitionKey != null && !definitionKey.isEmpty()) {
				instance = (businessKey != null)
						? engine.getRuntimeService().startProcessInstanceByKey(definitionKey, businessKey, vars)
						: engine.getRuntimeService().startProcessInstanceByKey(definitionKey, vars);
			} else if (definitionId != null && !definitionId.isEmpty()) {
				instance = (businessKey != null)
						? engine.getRuntimeService().startProcessInstanceById(definitionId, businessKey, vars)
						: engine.getRuntimeService().startProcessInstanceById(definitionId, vars);
			} else {
				throw new IllegalArgumentException("Either definitionKey or definitionId is required");
			}
			return mapProcessInstance(engine, instance);
		});
	}

	private static Object suspendProcessInstance(DataFetchingEnvironment environment) throws Exception {
		String id = requireId(environment.getArgument("id"));
		return asCaller(environment, engine -> {
			engine.getRuntimeService().suspendProcessInstanceById(id);
			return historicInstance(engine, id);
		});
	}

	private static Object activateProcessInstance(DataFetchingEnvironment environment) throws Exception {
		String id = requireId(environment.getArgument("id"));
		return asCaller(environment, engine -> {
			engine.getRuntimeService().activateProcessInstanceById(id);
			return historicInstance(engine, id);
		});
	}

	private static Object cancelProcessInstance(DataFetchingEnvironment environment) throws Exception {
		String id = requireId(environment.getArgument("id"));
		String reason = environment.getArgument("reason");
		return asCaller(environment, engine -> {
			engine.getRuntimeService()
					.deleteProcessInstance(id, (reason != null) ? reason : "Cancelled via BPM Console");
			return true;
		});
	}

	private static Object deleteProcessInstance(DataFetchingEnvironment environment) throws Exception {
		String id = requireId(environment.getArgument("id"));
		boolean skip = Boolean.TRUE.equals(environment.getArgument("skipCustomListeners"));
		return asCaller(environment, engine -> {
			engine.getRuntimeService().deleteProcessInstance(id, "Deleted via BPM Console", skip);
			return true;
		});
	}

	@SuppressWarnings("unchecked")
	private static Object setProcessVariables(DataFetchingEnvironment environment) throws Exception {
		String processInstanceId = requireId(environment.getArgument("processInstanceId"));
		Map<String, Object> vars = toVariableMap((List<Map<String, Object>>) environment.getArgument("variables"));
		return asCaller(environment, engine -> {
			// REPLACE semantics: remove existing variables not present in the incoming set.
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
			Map<String, Object> instance = historicInstance(engine, processInstanceId);
			if (instance != null) {
				// Echo the variables as they stand after this write. mapHistoricInstance
				// leaves variables empty (it serves metadata-only mutation results), and
				// historic variable records lag the current transaction — so read the
				// authoritative runtime state we just applied. Without this the result's
				// `variables` came back as an empty list.
				instance.put("variables",
						variableList(engine.getRuntimeService().getVariables(processInstanceId)));
			}
			return instance;
		});
	}

	/** A BPM mutation body, given the per-workspace engine; may throw. */
	@FunctionalInterface
	private interface BpmMutation {
		Object run(ProcessEngine engine) throws Exception;
	}

	/**
	 * Runs a BPM mutation with the caller's identity propagated to the Camunda
	 * engine, so Camunda attributes the work to the caller — process initiator
	 * ({@code startUserId}), task history, and comment authors. The thread-local
	 * authentication is always cleared afterward so it never leaks to the next
	 * pooled request. (These engines run with Camunda authorization disabled, so
	 * this affects attribution only, not permission checks.)
	 */
	private static Object asCaller(DataFetchingEnvironment environment, BpmMutation mutation) throws Exception {
		ProcessEngine engine = engine(environment);
		String userId = callerSession(environment).getUserID();
		try {
			engine.getIdentityService().setAuthenticatedUserId(userId);
			return mutation.run(engine);
		} catch (org.camunda.bpm.engine.BadUserRequestException ex) {
			// A bad request rejected by the engine (e.g. resolving a failedJob
			// incident, which only accepts custom incidents) is a client error, not
			// a server fault. Rethrow as IllegalArgumentException so the platform
			// exception handler classifies it BAD_REQUEST and surfaces the engine's
			// message ("Cannot resolve an incident of type failedJob") instead of a
			// generic "Internal server error".
			throw new IllegalArgumentException(ex.getMessage(), ex);
		} finally {
			engine.getIdentityService().clearAuthentication();
		}
	}

	private static String requireId(String id) {
		if (id == null || id.isEmpty()) {
			throw new IllegalArgumentException("id is required");
		}
		return id;
	}

	/** Re-reads a process instance from history and maps it (or null if gone). */
	private static Map<String, Object> historicInstance(ProcessEngine engine, String id) {
		HistoricProcessInstance historic = engine.getHistoryService()
				.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult();
		return (historic != null) ? mapHistoricInstance(engine, historic) : null;
	}

	/** Camunda runtime ProcessInstance → map (definitionKey + startTime resolved). */
	private static Map<String, Object> mapProcessInstance(ProcessEngine engine, ProcessInstance instance) {
		Map<String, Object> m = new HashMap<>();
		m.put("id", instance.getId());
		m.put("definitionId", instance.getProcessDefinitionId());
		m.put("definitionKey", instance.getProcessDefinitionId());
		m.put("businessKey", instance.getBusinessKey());
		m.put("suspended", instance.isSuspended());
		m.put("ended", instance.isEnded());
		m.put("startTime", null);
		m.put("endTime", null);
		m.put("durationInMillis", null);
		m.put("incidentCount", 0);
		m.put("variables", new ArrayList<>());
		try {
			ProcessDefinition def = engine.getRepositoryService().createProcessDefinitionQuery()
					.processDefinitionId(instance.getProcessDefinitionId()).singleResult();
			if (def != null) {
				m.put("definitionKey", def.getKey());
			}
		} catch (Exception ignore) {}
		try {
			HistoricProcessInstance historic = engine.getHistoryService()
					.createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
			if (historic != null) {
				m.put("startTime", formatDate(historic.getStartTime()));
			}
		} catch (Exception ignore) {}
		return m;
	}

	/** Camunda HistoricProcessInstance → map (post-mutation re-read). */
	private static Map<String, Object> mapHistoricInstance(ProcessEngine engine, HistoricProcessInstance inst) {
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

	/** Builds a Camunda variable map from ProcessVariableInput list (typed values). */
	private static Map<String, Object> toVariableMap(List<Map<String, Object>> variableInputs) {
		Map<String, Object> vars = new HashMap<>();
		if (variableInputs == null) {
			return vars;
		}
		for (Map<String, Object> v : variableInputs) {
			String name = (String) v.get("name");
			if (name == null) {
				continue;
			}
			vars.put(name, convertVariableValue(v.get("value"), (String) v.get("type")));
		}
		return vars;
	}

	/** Coerces a value to a Camunda TypedValue per the declared type (mirrors convertVariableValue). */
	private static Object convertVariableValue(Object value, String type) {
		String str = (value != null) ? value.toString() : null;
		if (type == null) {
			return Variables.stringValue(str);
		}
		switch (type) {
		case "Long":
		case "Integer": {
			Long parsed = null;
			if (str != null && !str.isEmpty()) {
				try {
					parsed = Long.parseLong(str);
				} catch (Exception ignore) {}
			}
			return Variables.longValue(parsed);
		}
		case "Double": {
			Double parsed = null;
			if (str != null && !str.isEmpty()) {
				try {
					parsed = Double.parseDouble(str);
				} catch (Exception ignore) {}
			}
			return Variables.doubleValue(parsed);
		}
		case "Boolean": {
			Boolean parsed = null;
			if (str != null && !str.isEmpty()) {
				parsed = Boolean.parseBoolean(str);
			}
			return Variables.booleanValue(parsed);
		}
		case "Date": {
			Date parsed = null;
			if (str != null && !str.isEmpty()) {
				try {
					parsed = Date.from(ISO8601.parseInstant(str));
				} catch (Exception e1) {
					try {
						// A zoneless value is interpreted as UTC so the stored
						// instant never depends on the server's OS time zone.
						java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(str);
						parsed = Date.from(ldt.toInstant(ZoneOffset.UTC));
					} catch (Exception ignore) {}
				}
			}
			return Variables.dateValue(parsed);
		}
		default:
			return Variables.stringValue(str);
		}
	}

	// ---- mutations: tasks (mirror BpmMutationExecutor) ---------------------

	private static Object claimTask(DataFetchingEnvironment environment) throws Exception {
		String taskId = requireTaskId(environment.getArgument("taskId"));
		String userId = callerSession(environment).getUserID();
		return asCaller(environment, engine -> {
			engine.getTaskService().claim(taskId, userId);
			return getTaskMap(engine, taskId);
		});
	}

	private static Object unclaimTask(DataFetchingEnvironment environment) throws Exception {
		String taskId = requireTaskId(environment.getArgument("taskId"));
		return asCaller(environment, engine -> {
			engine.getTaskService().claim(taskId, null);
			return getTaskMap(engine, taskId);
		});
	}

	private static Object assignTask(DataFetchingEnvironment environment) throws Exception {
		String taskId = requireTaskId(environment.getArgument("taskId"));
		String assignee = environment.getArgument("assignee");
		if (assignee == null || assignee.isEmpty()) {
			throw new IllegalArgumentException("assignee is required");
		}
		return asCaller(environment, engine -> {
			engine.getTaskService().setAssignee(taskId, assignee);
			return getTaskMap(engine, taskId);
		});
	}

	/**
	 * Sets a task's assignee (null unassigns). Authorized — exactly as the handmade
	 * executor — for an Admin, a Supervisor, or the task's current assignee.
	 */
	private static Object setTaskAssignee(DataFetchingEnvironment environment) throws Exception {
		String taskId = requireTaskId(environment.getArgument("taskId"));
		String assignee = environment.getArgument("assignee"); // may be null to unassign
		javax.jcr.Session session = callerSession(environment);
		return asCaller(environment, engine -> {
			boolean authorized = false;
			if (org.mintjams.jcr.Session.class.cast(session).isAdmin()) {
				// Admin can assign to anyone.
				authorized = true;
			} else if (org.mintjams.jcr.Workspace.class.cast(session.getWorkspace())
					.getIdentityProvider()
					.getUser(session.getUserID())
					.hasRole("supervisor")) {
				// Supervisor can assign to anyone.
				authorized = true;
			} else {
				Task task = engine.getTaskService().createTaskQuery().taskId(taskId).singleResult();
				if (task != null && session.getUserID().equals(task.getAssignee())) {
					// A user can reassign their own task.
					authorized = true;
				}
			}
			if (!authorized) {
				throw new AuthorizationException("User does not have permission to assign this task");
			}
			engine.getTaskService().setAssignee(taskId, assignee);
			return getTaskMap(engine, taskId);
		});
	}

	private static Object delegateTask(DataFetchingEnvironment environment) throws Exception {
		String taskId = requireTaskId(environment.getArgument("taskId"));
		String assignee = environment.getArgument("assignee");
		if (assignee == null || assignee.isEmpty()) {
			throw new IllegalArgumentException("assignee is required");
		}
		return asCaller(environment, engine -> {
			engine.getTaskService().delegateTask(taskId, assignee);
			return getTaskMap(engine, taskId);
		});
	}

	@SuppressWarnings("unchecked")
	private static Object completeTask(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> raw = environment.getArgument("input");
		Map<String, Object> input = (raw != null) ? raw : new HashMap<>();
		String taskId = (String) input.get("taskId");
		if (taskId == null || taskId.isEmpty()) {
			throw new IllegalArgumentException("taskId is required");
		}
		Map<String, Object> vars = toVariableMap((List<Map<String, Object>>) input.get("variables"));
		return asCaller(environment, engine -> {
			engine.getTaskService().complete(taskId, vars);
			// The task is gone after completion — return minimal info (mirrors handmade).
			Map<String, Object> m = new HashMap<>();
			m.put("id", taskId);
			m.put("name", null);
			return m;
		});
	}

	@SuppressWarnings("unchecked")
	private static Object setTaskVariables(DataFetchingEnvironment environment) throws Exception {
		String taskId = requireTaskId(environment.getArgument("taskId"));
		Map<String, Object> vars = toVariableMap((List<Map<String, Object>>) environment.getArgument("variables"));
		boolean local = Boolean.TRUE.equals(environment.getArgument("local"));
		return asCaller(environment, engine -> {
			if (local) {
				engine.getTaskService().setVariablesLocal(taskId, vars);
			} else {
				engine.getTaskService().setVariables(taskId, vars);
			}
			return getTaskMap(engine, taskId);
		});
	}

	private static Object addTaskComment(DataFetchingEnvironment environment) throws Exception {
		String taskId = requireTaskId(environment.getArgument("taskId"));
		String message = environment.getArgument("message");
		if (message == null || message.isEmpty()) {
			throw new IllegalArgumentException("message is required");
		}
		return asCaller(environment, engine -> {
			Task task = engine.getTaskService().createTaskQuery().taskId(taskId).singleResult();
			String processInstanceId = (task != null) ? task.getProcessInstanceId() : null;
			Comment comment = engine.getTaskService().createComment(taskId, processInstanceId, message);
			Map<String, Object> m = new HashMap<>();
			m.put("id", comment.getId());
			m.put("taskId", comment.getTaskId());
			m.put("userId", comment.getUserId());
			m.put("time", formatDate(comment.getTime()));
			m.put("message", comment.getFullMessage());
			return m;
		});
	}

	// ---- mutations: deployments / definitions (mirror BpmMutationExecutor) --

	@SuppressWarnings("unchecked")
	private static Object deployProcess(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> raw = environment.getArgument("input");
		Map<String, Object> input = (raw != null) ? raw : new HashMap<>();
		String name = (String) input.get("name");
		String source = (String) input.get("source");
		List<Map<String, Object>> resources = (List<Map<String, Object>>) input.get("resources");
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("name is required");
		}
		if (resources == null || resources.isEmpty()) {
			throw new IllegalArgumentException("resources is required");
		}
		// enableDuplicateFiltering / deployChangedOnly are accepted by the input
		// but not applied — handmade parity (the defaults are no-ops anyway).
		return asCaller(environment, engine -> {
			DeploymentBuilder builder = engine.getRepositoryService().createDeployment().name(name);
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
			Map<String, Object> m = new HashMap<>();
			m.put("id", deployment.getId());
			m.put("name", deployment.getName());
			m.put("deploymentTime", formatDate(deployment.getDeploymentTime()));
			m.put("source", deployment.getSource());
			m.put("tenantId", deployment.getTenantId());
			return m;
		});
	}

	private static Object deleteDeployment(DataFetchingEnvironment environment) throws Exception {
		String id = requireId(environment.getArgument("id"));
		boolean cascade = Boolean.TRUE.equals(environment.getArgument("cascade"));
		return asCaller(environment, engine -> {
			engine.getRepositoryService().deleteDeployment(id, cascade);
			return true;
		});
	}

	private static Object suspendProcessDefinition(DataFetchingEnvironment environment) throws Exception {
		String id = requireId(environment.getArgument("id"));
		boolean includeInstances = Boolean.TRUE.equals(environment.getArgument("includeInstances"));
		return asCaller(environment, engine -> {
			engine.getRepositoryService().suspendProcessDefinitionById(id, includeInstances, null);
			ProcessDefinition def = engine.getRepositoryService()
					.createProcessDefinitionQuery().processDefinitionId(id).singleResult();
			return (def != null) ? mapProcessDefinition(engine, def) : null;
		});
	}

	private static Object activateProcessDefinition(DataFetchingEnvironment environment) throws Exception {
		String id = requireId(environment.getArgument("id"));
		boolean includeInstances = Boolean.TRUE.equals(environment.getArgument("includeInstances"));
		return asCaller(environment, engine -> {
			engine.getRepositoryService().activateProcessDefinitionById(id, includeInstances, null);
			ProcessDefinition def = engine.getRepositoryService()
					.createProcessDefinitionQuery().processDefinitionId(id).singleResult();
			return (def != null) ? mapProcessDefinition(engine, def) : null;
		});
	}

	// ---- mutations: incidents (mirror BpmMutationExecutor) -----------------

	private static Object setJobRetries(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> raw = environment.getArgument("input");
		Map<String, Object> input = (raw != null) ? raw : new HashMap<>();
		String incidentId = (String) input.get("incidentId");
		Integer retries = intValue(input.get("retries"));
		boolean clearAnnotation = Boolean.TRUE.equals(input.get("clearAnnotation"));
		if (incidentId == null || incidentId.isEmpty()) {
			throw new IllegalArgumentException("incidentId is required");
		}
		if (retries == null || retries < 1) {
			throw new IllegalArgumentException("retries must be >= 1");
		}
		return asCaller(environment, engine -> {
			Incident inc = engine.getRuntimeService().createIncidentQuery().incidentId(incidentId).singleResult();
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
			if (clearAnnotation) {
				try {
					engine.getRuntimeService().clearAnnotationForIncidentById(incidentId);
				} catch (Exception ignore) {}
			}
			// Re-fetch — the incident may auto-resolve on the next executor cycle, but the
			// caller wants to see the updated retries meanwhile.
			Incident refreshed = engine.getRuntimeService().createIncidentQuery()
					.incidentId(incidentId).singleResult();
			return (refreshed != null) ? mapIncident(engine, refreshed, false) : null;
		});
	}

	private static Object resolveIncident(DataFetchingEnvironment environment) throws Exception {
		String id = requireId(environment.getArgument("id"));
		return asCaller(environment, engine -> {
			// resolveIncident only accepts custom incidents; the engine throws for
			// failedJob/failedExternalTask — let that propagate as the operation error.
			engine.getRuntimeService().resolveIncident(id);
			return true;
		});
	}

	private static Object setIncidentAnnotation(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> raw = environment.getArgument("input");
		Map<String, Object> input = (raw != null) ? raw : new HashMap<>();
		String id = (String) input.get("id");
		String annotation = (String) input.get("annotation");
		if (id == null || id.isEmpty()) {
			throw new IllegalArgumentException("id is required");
		}
		return asCaller(environment, engine -> {
			if (annotation == null || annotation.isEmpty()) {
				engine.getRuntimeService().clearAnnotationForIncidentById(id);
			} else {
				engine.getRuntimeService().setAnnotationForIncidentById(id, annotation);
			}
			Incident inc = engine.getRuntimeService().createIncidentQuery().incidentId(id).singleResult();
			return (inc != null) ? mapIncident(engine, inc, false) : null;
		});
	}

	/** Coerces a JSON value to an Integer (mirrors BpmMutationExecutor.getIntValue). */
	private static Integer intValue(Object v) {
		if (v == null) {
			return null;
		}
		if (v instanceof Number) {
			return ((Number) v).intValue();
		}
		try {
			return Integer.parseInt(v.toString());
		} catch (Exception ex) {
			return null;
		}
	}

	// ---- mutations: migration (mirror BpmMutationExecutor) -----------------

	/**
	 * Builds (and returns) an in-memory migration plan preview. The plan is not
	 * persisted on the engine — the BPM Console uses it to confirm the activity
	 * mappings before committing via {@link #migrateProcessInstance}.
	 */
	private static Object createMigrationPlan(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> raw = environment.getArgument("input");
		Map<String, Object> input = (raw != null) ? raw : new HashMap<>();
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
		return asCaller(environment, engine ->
				mapMigrationPlan(buildMigrationPlan(engine, sourceId, targetId, mapEqualActivities, updateEventTriggers)));
	}

	/**
	 * Queues a process-instance migration as a CMS JobManager job (its worker
	 * drives Camunda's batch and republishes progress via {@code jobProgress}).
	 * Returns the CMS job id immediately; the plan is built once up-front so
	 * obvious mis-configuration is reported synchronously. {@code abortable=false}
	 * because Camunda 7 batches cannot be safely cancelled mid-flight.
	 */
	@SuppressWarnings("unchecked")
	private static Object migrateProcessInstance(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> raw = environment.getArgument("input");
		Map<String, Object> input = (raw != null) ? raw : new HashMap<>();
		String sourceId = (String) input.get("sourceProcessDefinitionId");
		String targetId = (String) input.get("targetProcessDefinitionId");
		Boolean mapEqualActivities = (Boolean) input.get("mapEqualActivities");
		Boolean updateEventTriggers = (Boolean) input.get("updateEventTriggers");
		Boolean skipCustomListeners = (Boolean) input.get("skipCustomListeners");
		Boolean skipIoMappings = (Boolean) input.get("skipIoMappings");
		Boolean allActiveInstances = (Boolean) input.get("allActiveInstances");
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

		// Build (and discard) the plan up-front so obvious mis-configuration — unknown
		// definition ids, incompatible activities — is reported synchronously instead
		// of disappearing into the worker thread's log. The worker rebuilds the plan on
		// its own engine handle.
		asCaller(environment, engine -> {
			buildMigrationPlan(engine, sourceId, targetId, mapEqualActivities, updateEventTriggers);
			// Reject an empty batch here too. The worker's executeAsync() would throw
			// "processInstanceIds is empty", which only reaches the caller as a job
			// that fails minutes after a successful-looking submit. Mirrors the query
			// MigrateInstancesJob seeds the batch with — running AND not suspended,
			// which is narrower than the "unfinished" counts the console's tree shows.
			// Only when the caller relies solely on allActiveInstances: combined with
			// explicit ids the batch covers the union, so an empty query is legitimate.
			if (useAll && !useIds) {
				long migratable = engine.getRuntimeService()
						.createProcessInstanceQuery()
						.processDefinitionId(sourceId)
						.active()
						.count();
				if (migratable == 0) {
					throw new IllegalArgumentException(
							"No running instances of the source process definition to migrate"
									+ " (suspended instances are not migrated)");
				}
			}
			return null;
		});

		MigrateInstancesJob.MigrationRequest req = new MigrateInstancesJob.MigrationRequest(
				sourceId,
				targetId,
				useIds ? processInstanceIds : null,
				useAll,
				mapEqualActivities == null || Boolean.TRUE.equals(mapEqualActivities),
				Boolean.TRUE.equals(updateEventTriggers),
				Boolean.TRUE.equals(skipCustomListeners),
				Boolean.TRUE.equals(skipIoMappings));

		String jobId = enqueueMigrationJob(environment, req);

		Map<String, Object> handle = new HashMap<>();
		handle.put("id", jobId);
		handle.put("jobType", MigrateInstancesJob.TYPE);
		handle.put("status", JobStatus.QUEUED.toExternalString());
		handle.put("abortable", false);
		return handle;
	}

	/** Builds the in-memory MigrationPlan (default mapEqualActivities; optional updateEventTriggers). */
	private static MigrationPlan buildMigrationPlan(ProcessEngine engine, String sourceId, String targetId,
			Boolean mapEqualActivities, Boolean updateEventTriggers) {
		MigrationPlanBuilder builder = engine.getRuntimeService().createMigrationPlan(sourceId, targetId);
		// Default to mapEqualActivities() — the only automatic strategy the engine offers.
		if (mapEqualActivities == null || Boolean.TRUE.equals(mapEqualActivities)) {
			MigrationInstructionsBuilder instructions = builder.mapEqualActivities();
			if (Boolean.TRUE.equals(updateEventTriggers)) {
				instructions = instructions.updateEventTriggers();
			}
			builder = instructions;
		}
		return builder.build();
	}

	private static Map<String, Object> mapMigrationPlan(MigrationPlan plan) {
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
				i.put("sourceActivityIds", (src != null) ? List.of(src) : List.of());
				i.put("targetActivityIds", (tgt != null) ? List.of(tgt) : List.of());
				i.put("updateEventTrigger", inst.isUpdateEventTrigger());
				instructions.add(i);
			}
		}
		m.put("instructions", instructions);
		return m;
	}

	/**
	 * Persists a fresh JCR job record under {@code /var/jobs/YYYY/MM/job-<id>} and
	 * submits a {@link MigrateInstancesJob}; returns the generated job id so the
	 * client can subscribe to {@code jobProgress(jobId)} immediately. The job runs
	 * as the caller (its node and worker carry the caller's user id).
	 */
	private static String enqueueMigrationJob(DataFetchingEnvironment environment,
			MigrateInstancesJob.MigrationRequest req) throws Exception {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		String workspaceName = context.getWorkspaceName();
		String userId = context.getCallerSession().getUserID();
		String jobId = JobNodes.newJobId();

		javax.jcr.Session mgmt = context.openServiceSession(userId);
		try {
			Node jobNode = JobNodes.createJobNode(mgmt, jobId, MigrateInstancesJob.TYPE, userId, 0);
			JobNodes.setNodeId(mgmt, JobNodes.getContent(jobNode));
			mgmt.save();
		} catch (Throwable ex) {
			try {
				mgmt.refresh(false);
			} catch (Throwable ignore) {}
			throw new RuntimeException("Failed to create JCR job node for migration job " + jobId, ex);
		} finally {
			try {
				mgmt.logout();
			} catch (Throwable ignore) {}
		}

		try {
			CmsService.getJobManager().submit(new MigrateInstancesJob(jobId, workspaceName, userId, 0, req));
		} catch (Throwable ex) {
			// The JCR record already exists; surface the error so the client doesn't poll
			// a job that will never start.
			throw new RuntimeException("Failed to submit MigrateInstancesJob " + jobId, ex);
		}
		return jobId;
	}

	private static String requireTaskId(String taskId) {
		if (taskId == null || taskId.isEmpty()) {
			throw new IllegalArgumentException("taskId is required");
		}
		return taskId;
	}

	private static javax.jcr.Session callerSession(DataFetchingEnvironment environment) {
		return GraphQLExecutionContext.from(environment).getCallerSession();
	}

	/**
	 * Partial {@code Task} projection returned by the task mutations (mirrors the
	 * handmade {@code BpmMutationExecutor.getTaskMap}): a flat map of the scalar
	 * fields, with candidate users/groups and variables left empty — the Webtop
	 * re-queries the task to refresh those. {@code processInstance} is intentionally
	 * not embedded; mutation selection sets never request it.
	 */
	private static Map<String, Object> getTaskMap(ProcessEngine engine, String taskId) {
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

	// ---- mappers (mirror BpmQueryExecutor) ---------------------------------

	private static Map<String, Object> mapProcessDefinition(ProcessEngine engine, ProcessDefinition def) {
		Map<String, Object> m = new HashMap<>();
		m.put("id", def.getId());
		m.put("key", def.getKey());
		m.put("name", def.getName());
		m.put("description", def.getDescription());
		m.put("version", def.getVersion());
		m.put("deploymentId", def.getDeploymentId());
		try {
			Deployment deployment = engine.getRepositoryService().createDeploymentQuery()
					.deploymentId(def.getDeploymentId()).singleResult();
			m.put("deploymentName", (deployment != null) ? deployment.getName() : null);
		} catch (Exception ex) {
			m.put("deploymentName", null);
		}
		m.put("resourceName", def.getResourceName());
		m.put("diagramResourceName", def.getDiagramResourceName());
		m.put("suspended", def.isSuspended());
		m.put("category", def.getCategory());
		try {
			m.put("startFormKey", engine.getFormService().getStartFormKey(def.getId()));
		} catch (Exception ex) {
			m.put("startFormKey", null);
		}
		return m;
	}

	private static Map<String, Object> mapHistoricProcessInstance(ProcessEngine engine, HistoricProcessInstance inst,
			boolean includeVariables) {
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
		try {
			IncidentQuery incQuery = engine.getRuntimeService().createIncidentQuery().processInstanceId(inst.getId());
			m.put("incidentCount", (int) incQuery.count());
		} catch (Exception ex) {
			m.put("incidentCount", 0);
		}
		List<Map<String, Object>> varList = new ArrayList<>();
		if (includeVariables) {
			try {
				for (HistoricVariableInstance v : engine.getHistoryService().createHistoricVariableInstanceQuery()
						.processInstanceId(inst.getId()).list()) {
					varList.add(mapHistoricVariable(v));
				}
			} catch (Exception ignore) {
				// fall through with whatever was collected
			}
		}
		m.put("variables", varList);
		return m;
	}

	private static Map<String, Object> mapHistoricVariable(HistoricVariableInstance v) {
		Map<String, Object> m = new HashMap<>();
		m.put("name", v.getName());
		m.put("type", v.getTypeName());
		Object val = v.getValue();
		m.put("value", (val instanceof Date) ? formatDate((Date) val) : (val != null ? val.toString() : null));
		m.put("valueInfo", null);
		return m;
	}

	private static Map<String, Object> mapTask(ProcessEngine engine, Task task, boolean includeDetails,
			Map<String, Map<String, Object>> instanceMap) {
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

		// Embed the parent process instance so `task { processInstance { businessKey } }`
		// resolves (Camunda's Task does not expose businessKey directly).
		Map<String, Object> piMap = (instanceMap != null && task.getProcessInstanceId() != null)
				? instanceMap.get(task.getProcessInstanceId())
				: null;
		if (piMap == null) {
			piMap = new HashMap<>();
			piMap.put("id", task.getProcessInstanceId());
			piMap.put("definitionId", task.getProcessDefinitionId());
			piMap.put("definitionKey", null);
			piMap.put("businessKey", null);
			piMap.put("suspended", task.isSuspended());
			piMap.put("ended", false);
			piMap.put("startTime", null);
			piMap.put("endTime", null);
			piMap.put("durationInMillis", null);
			piMap.put("incidentCount", 0);
			piMap.put("variables", new ArrayList<>());
		}
		m.put("processInstance", piMap);

		Object piDefKey = piMap.get("definitionKey");
		if (piDefKey instanceof String && !((String) piDefKey).isEmpty()) {
			m.put("processDefinitionKey", piDefKey);
		} else if (task.getProcessDefinitionId() != null) {
			try {
				ProcessDefinition def = engine.getRepositoryService().createProcessDefinitionQuery()
						.processDefinitionId(task.getProcessDefinitionId()).singleResult();
				m.put("processDefinitionKey", (def != null) ? def.getKey() : null);
			} catch (Exception ex) {
				m.put("processDefinitionKey", null);
			}
		} else {
			m.put("processDefinitionKey", null);
		}

		if (includeDetails) {
			List<String> candidateUsers = new ArrayList<>();
			List<String> candidateGroups = new ArrayList<>();
			try {
				for (IdentityLink link : engine.getTaskService().getIdentityLinksForTask(task.getId())) {
					if (IdentityLinkType.CANDIDATE.equals(link.getType())) {
						if (link.getUserId() != null) {
							candidateUsers.add(link.getUserId());
						}
						if (link.getGroupId() != null) {
							candidateGroups.add(link.getGroupId());
						}
					}
				}
			} catch (Exception ignore) {}
			m.put("candidateUsers", candidateUsers);
			m.put("candidateGroups", candidateGroups);

			m.put("variables", variableList(engine.getTaskService().getVariables(task.getId())));
			m.put("localVariables", variableList(engine.getTaskService().getVariablesLocal(task.getId())));
		} else {
			m.put("candidateUsers", new ArrayList<>());
			m.put("candidateGroups", new ArrayList<>());
			m.put("variables", new ArrayList<>());
			m.put("localVariables", new ArrayList<>());
		}
		return m;
	}

	private static List<Map<String, Object>> variableList(Map<String, Object> vars) {
		List<Map<String, Object>> varList = new ArrayList<>();
		if (vars != null) {
			for (Map.Entry<String, Object> e : vars.entrySet()) {
				varList.add(mapSimpleVariable(e.getKey(), e.getValue()));
			}
		}
		return varList;
	}

	/** Batch-fetch HistoricProcessInstance for the tasks → {id → ProcessInstance map} (mirrors buildProcessInstanceMap). */
	private static Map<String, Map<String, Object>> buildProcessInstanceMap(ProcessEngine engine, List<Task> tasks) {
		if (tasks == null || tasks.isEmpty()) {
			return Collections.emptyMap();
		}
		Set<String> ids = new HashSet<>();
		for (Task t : tasks) {
			String pid = t.getProcessInstanceId();
			if (pid != null && !pid.isEmpty()) {
				ids.add(pid);
			}
		}
		if (ids.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, Map<String, Object>> out = new HashMap<>();
		try {
			for (HistoricProcessInstance hpi : engine.getHistoryService().createHistoricProcessInstanceQuery()
					.processInstanceIds(ids).list()) {
				Map<String, Object> m = new HashMap<>();
				m.put("id", hpi.getId());
				m.put("definitionId", hpi.getProcessDefinitionId());
				m.put("definitionKey", hpi.getProcessDefinitionKey());
				m.put("businessKey", hpi.getBusinessKey());
				m.put("suspended", hpi.getState() != null && hpi.getState().contains("SUSPENDED"));
				m.put("ended", hpi.getEndTime() != null);
				m.put("startTime", formatDate(hpi.getStartTime()));
				m.put("endTime", formatDate(hpi.getEndTime()));
				m.put("durationInMillis", hpi.getDurationInMillis());
				// Complete the ProcessInstance shape so non-null fields resolve if selected.
				m.put("incidentCount", 0);
				m.put("variables", new ArrayList<>());
				out.put(hpi.getId(), m);
			}
		} catch (Exception ignore) {
			// Callers handle a missing entry gracefully (fallback piMap in mapTask).
		}
		return out;
	}

	private static Map<String, Object> mapSimpleVariable(String name, Object value) {
		Map<String, Object> m = new HashMap<>();
		m.put("name", name);
		m.put("valueInfo", null);
		if (value == null) {
			m.put("type", "Null");
			m.put("value", null);
		} else if (value instanceof Date) {
			m.put("type", "Date");
			m.put("value", formatDate((Date) value));
		} else if (value instanceof String) {
			m.put("type", "String");
			m.put("value", value.toString());
		} else if (value instanceof Long) {
			m.put("type", "Long");
			m.put("value", value.toString());
		} else if (value instanceof Integer) {
			m.put("type", "Integer");
			m.put("value", value.toString());
		} else if (value instanceof Double || value instanceof Float) {
			m.put("type", "Double");
			m.put("value", value.toString());
		} else if (value instanceof Boolean) {
			m.put("type", "Boolean");
			m.put("value", value.toString());
		} else {
			m.put("type", "Object");
			m.put("value", value.toString());
		}
		return m;
	}

	private static Map<String, Object> mapIncident(ProcessEngine engine, Incident inc, boolean withStack) {
		Map<String, Object> m = new HashMap<>();
		m.put("id", inc.getId());
		m.put("type", inc.getIncidentType());
		m.put("message", inc.getIncidentMessage());
		m.put("incidentTimestamp", formatDate(inc.getIncidentTimestamp()));
		m.put("activityId", inc.getActivityId());
		m.put("executionId", inc.getExecutionId());
		m.put("processInstanceId", inc.getProcessInstanceId());
		m.put("processDefinitionId", inc.getProcessDefinitionId());
		m.put("causeIncidentId", inc.getCauseIncidentId());
		m.put("rootCauseIncidentId", inc.getRootCauseIncidentId());
		m.put("configuration", inc.getConfiguration());
		m.put("annotation", inc.getAnnotation());
		m.put("tenantId", inc.getTenantId());
		// activityName falls back to activityId (display names are resolved client-side).
		m.put("activityName", inc.getActivityId());

		String defKey = null;
		try {
			ProcessDefinition def = engine.getRepositoryService().createProcessDefinitionQuery()
					.processDefinitionId(inc.getProcessDefinitionId()).singleResult();
			if (def != null) {
				defKey = def.getKey();
			}
		} catch (Exception ignore) {}
		m.put("processDefinitionKey", defKey);

		// For failedJob incidents, configuration holds the Job id → jobRetries / stackTrace.
		String jobId = null;
		int retries = 0;
		String stackTrace = null;
		if ("failedJob".equals(inc.getIncidentType()) && inc.getConfiguration() != null) {
			jobId = inc.getConfiguration();
			try {
				Job job = engine.getManagementService().createJobQuery().jobId(jobId).singleResult();
				if (job != null) {
					retries = job.getRetries();
				}
			} catch (Exception ignore) {}
			if (withStack) {
				try {
					stackTrace = engine.getManagementService().getJobExceptionStacktrace(jobId);
				} catch (Exception ignore) {}
			}
		}
		m.put("jobId", jobId);
		m.put("jobRetries", retries);
		m.put("stackTrace", stackTrace);
		return m;
	}

	// ---- helpers (mirror BpmQueryExecutor) ---------------------------------

	private static ProcessEngine engine(DataFetchingEnvironment environment) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		return CmsService.getWorkspaceProcessEngineProvider(workspaceName).getProcessEngine();
	}

	private static int firstArg(DataFetchingEnvironment environment, int defaultValue) {
		Integer first = environment.getArgument("first");
		return (first != null) ? first : defaultValue;
	}

	/** Relay connection over the full list (cursor = base64("cursor:" + index)), mirroring buildConnection. */
	private static Map<String, Object> connection(List<Map<String, Object>> items, int first, String afterCursor) {
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
		return connection;
	}

	private static String encodeCursor(int position) {
		return Base64.getEncoder().encodeToString(("cursor:" + position).getBytes(StandardCharsets.UTF_8));
	}

	private static int decodeCursor(String cursor) {
		try {
			String decoded = new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
			if (decoded.startsWith("cursor:")) {
				return Integer.parseInt(decoded.substring("cursor:".length()));
			}
		} catch (Exception ignore) {}
		return 0;
	}

	private static String formatDate(Date date) {
		return ISO8601.format(date);
	}

	/**
	 * Parses an ISO-8601 date filter argument. An unparseable value is a
	 * client error and surfaces as such, rather than being swallowed and the
	 * filter silently dropped from the query.
	 */
	private static Date parseDate(String isoString) {
		return Date.from(ISO8601.parseInstant(isoString));
	}

	/**
	 * Resolves the time zone in which calendar-day boundaries ("today", "this
	 * week") are computed for {@code taskCounts}. A blank argument falls back
	 * to UTC so the result is deterministic and independent of the server's
	 * operating-system time zone. A malformed or tzdata-unknown zone name (for
	 * example a client sending {@code Europe/Kyiv} to a JVM whose bundled
	 * tzdata predates the rename) also falls back to UTC rather than failing:
	 * the zone only shifts two of the six counts by a day boundary, so it must
	 * not abort the whole aggregate and blank the dashboard.
	 */
	private static ZoneId resolveZone(String timeZone) {
		if (timeZone != null && !timeZone.isEmpty()) {
			try {
				return ZoneId.of(timeZone);
			} catch (RuntimeException ignore) {
				// Fall through to UTC (graceful degradation, see above).
			}
		}
		return ZoneOffset.UTC;
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformBpmWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("BPM GraphQL schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}

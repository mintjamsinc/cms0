/**
 * BPM Service (GraphQL-based)
 *
 * Provides business process management operations using GraphQL API.
 */

import { GraphQLClient } from '../graphql/client.js';
import { BPM_QUERIES, BPM_MUTATIONS } from '../graphql/queries/bpm.js';
import type {
  Task,
  TaskConnection,
  TaskCounts,
  TaskSortField,
  SortOrder,
  ProcessDefinition,
  ProcessDefinitionConnection,
  ProcessInstance,
  ProcessInstanceConnection,
  ProcessVariable,
  ProcessVariableInput,
  ActivityInstance,
  Deployment,
  TaskComment,
  StartProcessInput,
  CompleteTaskInput,
  DeployProcessInput,
  Incident,
  IncidentConnection,
  MigrationPlan,
  MigrationJob,
  CreateMigrationPlanInput,
  MigrateProcessInstanceInput,
} from '../graphql/types.js';

export interface ListIncidentsOptions {
  first?: number;
  after?: string;
  processInstanceId?: string;
  processDefinitionId?: string;
  processDefinitionKey?: string;
  incidentType?: string;
  activityId?: string;
}

export interface SetJobRetriesInput {
  incidentId: string;
  retries: number;
  clearAnnotation?: boolean;
}

export interface SetIncidentAnnotationInput {
  id: string;
  annotation: string | null;
}

export interface ListTasksOptions {
  first?: number;
  after?: string;
  assignee?: string;
  assigneeIn?: string[];
  candidateUser?: string;
  candidateGroup?: string;
  candidateGroups?: string[];
  unassigned?: boolean;
  processInstanceId?: string;
  processDefinitionId?: string;
  processDefinitionKey?: string;
  taskDefinitionKey?: string;
  dueBefore?: string;
  dueAfter?: string;
  createdBefore?: string;
  createdAfter?: string;
  priority?: number;
  priorityHigherThanOrEquals?: number;
  priorityLowerThanOrEquals?: number;
  sortBy?: TaskSortField;
  sortOrder?: SortOrder;
}

export interface ListProcessDefinitionsOptions {
  first?: number;
  after?: string;
  key?: string;
  name?: string;
  latestVersion?: boolean;
  suspended?: boolean;
}

export interface ListProcessInstancesOptions {
  first?: number;
  after?: string;
  definitionKey?: string;
  definitionId?: string;
  businessKey?: string;
  active?: boolean;
  suspended?: boolean;
  withIncidents?: boolean;
  startedBefore?: string;
  startedAfter?: string;
}

/**
 * BPM Service for Camunda process management
 */
export class BpmServiceGraphQL {
  #client: GraphQLClient;

  constructor(client: GraphQLClient) {
    this.#client = client;
  }

  // =========================================================================
  // Task Queries
  // =========================================================================

  /**
   * Get a single task by ID
   */
  async getTask(id: string): Promise<Task | null> {
    const data = await this.#client.query<{ task: Task | null }>(
      BPM_QUERIES.GET_TASK,
      { id }
    );
    return data.task;
  }

  /**
   * List tasks with filters
   */
  async listTasks(options: ListTasksOptions = {}): Promise<TaskConnection> {
    const data = await this.#client.query<{ tasks: TaskConnection }>(
      BPM_QUERIES.LIST_TASKS,
      {
        first: options.first ?? 20,
        after: options.after,
        assignee: options.assignee,
        assigneeIn: options.assigneeIn,
        candidateUser: options.candidateUser,
        candidateGroup: options.candidateGroup,
        candidateGroups: options.candidateGroups,
        unassigned: options.unassigned,
        processInstanceId: options.processInstanceId,
        processDefinitionId: options.processDefinitionId,
        processDefinitionKey: options.processDefinitionKey,
        taskDefinitionKey: options.taskDefinitionKey,
        dueBefore: options.dueBefore,
        dueAfter: options.dueAfter,
        createdBefore: options.createdBefore,
        createdAfter: options.createdAfter,
        priority: options.priority,
        priorityHigherThanOrEquals: options.priorityHigherThanOrEquals,
        priorityLowerThanOrEquals: options.priorityLowerThanOrEquals,
        sortBy: options.sortBy,
        sortOrder: options.sortOrder,
      }
    );
    return data.tasks;
  }

  /**
   * Get tasks assigned to current user
   */
  async getMyTasks(userId: string, options: Omit<ListTasksOptions, 'assignee'> = {}): Promise<TaskConnection> {
    return this.listTasks({ ...options, assignee: userId });
  }

  /**
   * Get tasks available to claim by current user
   */
  async getClaimableTasks(
    userId: string,
    groups: string[] = [],
    options: Omit<ListTasksOptions, 'candidateUser' | 'candidateGroups' | 'unassigned'> = {}
  ): Promise<TaskConnection> {
    return this.listTasks({
      ...options,
      candidateUser: userId,
      candidateGroups: groups.length > 0 ? groups : undefined,
      unassigned: true,
    });
  }

  /**
   * Get task counts for dashboard
   */
  async getTaskCounts(options: {
    assignee?: string;
    candidateUser?: string;
    candidateGroups?: string[];
  } = {}): Promise<TaskCounts> {
    const data = await this.#client.query<{ taskCounts: TaskCounts }>(
      BPM_QUERIES.GET_TASK_COUNTS,
      options
    );
    return data.taskCounts;
  }

  // =========================================================================
  // Process Definition Queries
  // =========================================================================

  /**
   * Get a single process definition
   */
  async getProcessDefinition(options: {
    id?: string;
    key?: string;
    version?: number;
  }): Promise<ProcessDefinition | null> {
    const data = await this.#client.query<{ processDefinition: ProcessDefinition | null }>(
      BPM_QUERIES.GET_PROCESS_DEFINITION,
      options
    );
    return data.processDefinition;
  }

  /**
   * List process definitions
   */
  async listProcessDefinitions(
    options: ListProcessDefinitionsOptions = {}
  ): Promise<ProcessDefinitionConnection> {
    const data = await this.#client.query<{ processDefinitions: ProcessDefinitionConnection }>(
      BPM_QUERIES.LIST_PROCESS_DEFINITIONS,
      {
        first: options.first ?? 100,
        after: options.after,
        key: options.key,
        name: options.name,
        latestVersion: options.latestVersion ?? true,
        suspended: options.suspended,
      }
    );
    return data.processDefinitions;
  }

  /**
   * Get BPMN XML for a process definition by ID
   */
  async getProcessDefinitionXml(id: string): Promise<string | null> {
    const data = await this.#client.query<{ processDefinitionXml: string | null }>(
      BPM_QUERIES.GET_PROCESS_DEFINITION_XML,
      { id }
    );
    return data.processDefinitionXml;
  }

  /**
   * Get latest version of all process definitions
   */
  async getLatestProcessDefinitions(): Promise<ProcessDefinition[]> {
    const result = await this.listProcessDefinitions({ latestVersion: true, first: 1000 });
    return result.edges.map(e => e.node);
  }

  // =========================================================================
  // Process Instance Queries
  // =========================================================================

  /**
   * Get a single process instance
   */
  async getProcessInstance(id: string): Promise<ProcessInstance | null> {
    const data = await this.#client.query<{ processInstance: ProcessInstance | null }>(
      BPM_QUERIES.GET_PROCESS_INSTANCE,
      { id }
    );
    return data.processInstance;
  }

  /**
   * Get activity history for a process instance
   */
  async getActivityHistory(processInstanceId: string): Promise<ActivityInstance[]> {
    const data = await this.#client.query<{ activityHistory: ActivityInstance[] }>(
      BPM_QUERIES.GET_ACTIVITY_HISTORY,
      { processInstanceId }
    );
    return data.activityHistory;
  }

  /**
   * List process instances
   */
  async listProcessInstances(
    options: ListProcessInstancesOptions = {}
  ): Promise<ProcessInstanceConnection> {
    const data = await this.#client.query<{ processInstances: ProcessInstanceConnection }>(
      BPM_QUERIES.LIST_PROCESS_INSTANCES,
      {
        first: options.first ?? 20,
        after: options.after,
        definitionKey: options.definitionKey,
        definitionId: options.definitionId,
        businessKey: options.businessKey,
        active: options.active,
        suspended: options.suspended,
        withIncidents: options.withIncidents,
        startedBefore: options.startedBefore,
        startedAfter: options.startedAfter,
      }
    );
    return data.processInstances;
  }

  // =========================================================================
  // Process Mutations
  // =========================================================================

  /**
   * Start a new process instance
   */
  async startProcess(input: StartProcessInput): Promise<ProcessInstance> {
    const data = await this.#client.mutation<{ startProcess: ProcessInstance }>(
      BPM_MUTATIONS.START_PROCESS,
      { input }
    );
    return data.startProcess;
  }

  /**
   * Start a process by key
   */
  async startProcessByKey(
    definitionKey: string,
    options: {
      businessKey?: string;
      variables?: ProcessVariableInput[];
    } = {}
  ): Promise<ProcessInstance> {
    return this.startProcess({
      definitionKey,
      businessKey: options.businessKey,
      variables: options.variables,
    });
  }

  /**
   * Suspend a process instance
   */
  async suspendProcessInstance(id: string): Promise<ProcessInstance> {
    const data = await this.#client.mutation<{ suspendProcessInstance: ProcessInstance }>(
      BPM_MUTATIONS.SUSPEND_PROCESS_INSTANCE,
      { id }
    );
    return data.suspendProcessInstance;
  }

  /**
   * Activate a suspended process instance
   */
  async activateProcessInstance(id: string): Promise<ProcessInstance> {
    const data = await this.#client.mutation<{ activateProcessInstance: ProcessInstance }>(
      BPM_MUTATIONS.ACTIVATE_PROCESS_INSTANCE,
      { id }
    );
    return data.activateProcessInstance;
  }

  /**
   * Cancel a process instance
   */
  async cancelProcessInstance(id: string, reason?: string): Promise<boolean> {
    const data = await this.#client.mutation<{ cancelProcessInstance: boolean }>(
      BPM_MUTATIONS.CANCEL_PROCESS_INSTANCE,
      { id, reason }
    );
    return data.cancelProcessInstance;
  }

  /**
   * Delete a process instance
   */
  async deleteProcessInstance(id: string, skipCustomListeners = false): Promise<boolean> {
    const data = await this.#client.mutation<{ deleteProcessInstance: boolean }>(
      BPM_MUTATIONS.DELETE_PROCESS_INSTANCE,
      { id, skipCustomListeners }
    );
    return data.deleteProcessInstance;
  }

  /**
   * Set variables on a process instance
   */
  async setProcessVariables(
    processInstanceId: string,
    variables: ProcessVariableInput[]
  ): Promise<ProcessInstance> {
    const data = await this.#client.mutation<{ setProcessVariables: ProcessInstance }>(
      BPM_MUTATIONS.SET_PROCESS_VARIABLES,
      { processInstanceId, variables }
    );
    return data.setProcessVariables;
  }

  // =========================================================================
  // Task Mutations
  // =========================================================================

  /**
   * Claim a task for the current user
   */
  async claimTask(taskId: string): Promise<Task> {
    const data = await this.#client.mutation<{ claimTask: Task }>(
      BPM_MUTATIONS.CLAIM_TASK,
      { taskId }
    );
    return data.claimTask;
  }

  /**
   * Release a claimed task
   */
  async unclaimTask(taskId: string): Promise<Task> {
    const data = await this.#client.mutation<{ unclaimTask: Task }>(
      BPM_MUTATIONS.UNCLAIM_TASK,
      { taskId }
    );
    return data.unclaimTask;
  }

  /**
   * Assign a task to a specific user
   */
  async assignTask(taskId: string, assignee: string): Promise<Task> {
    const data = await this.#client.mutation<{ assignTask: Task }>(
      BPM_MUTATIONS.ASSIGN_TASK,
      { taskId, assignee }
    );
    return data.assignTask;
  }

  /**
   * Set task assignee (can be null to unassign)
   */
  async setTaskAssignee(taskId: string, assignee: string | null): Promise<Task> {
    const data = await this.#client.mutation<{ setTaskAssignee: Task }>(
      BPM_MUTATIONS.SET_TASK_ASSIGNEE,
      { taskId, assignee }
    );
    return data.setTaskAssignee;
  }

  /**
   * Delegate a task to another user
   */
  async delegateTask(taskId: string, assignee: string): Promise<Task> {
    const data = await this.#client.mutation<{ delegateTask: Task }>(
      BPM_MUTATIONS.DELEGATE_TASK,
      { taskId, assignee }
    );
    return data.delegateTask;
  }

  /**
   * Complete a task
   */
  async completeTask(input: CompleteTaskInput): Promise<Task | null> {
    const data = await this.#client.mutation<{ completeTask: Task | null }>(
      BPM_MUTATIONS.COMPLETE_TASK,
      { input }
    );
    return data.completeTask;
  }

  /**
   * Complete a task with variables
   */
  async completeTaskWithVariables(
    taskId: string,
    variables: ProcessVariableInput[]
  ): Promise<Task | null> {
    return this.completeTask({ taskId, variables });
  }

  /**
   * Set variables on a task
   */
  async setTaskVariables(
    taskId: string,
    variables: ProcessVariableInput[],
    local = false
  ): Promise<Task> {
    const data = await this.#client.mutation<{ setTaskVariables: Task }>(
      BPM_MUTATIONS.SET_TASK_VARIABLES,
      { taskId, variables, local }
    );
    return data.setTaskVariables;
  }

  /**
   * Add a comment to a task
   */
  async addTaskComment(taskId: string, message: string): Promise<TaskComment> {
    const data = await this.#client.mutation<{ addTaskComment: TaskComment }>(
      BPM_MUTATIONS.ADD_TASK_COMMENT,
      { taskId, message }
    );
    return data.addTaskComment;
  }

  // =========================================================================
  // Deployment Mutations
  // =========================================================================

  /**
   * Deploy a process definition
   */
  async deployProcess(input: DeployProcessInput): Promise<Deployment> {
    const data = await this.#client.mutation<{ deployProcess: Deployment }>(
      BPM_MUTATIONS.DEPLOY_PROCESS,
      { input }
    );
    return data.deployProcess;
  }

  /**
   * Deploy a BPMN file
   */
  async deployBpmn(
    name: string,
    bpmnXml: string,
    options: { source?: string; deployChangedOnly?: boolean } = {}
  ): Promise<Deployment> {
    return this.deployProcess({
      name,
      source: options.source,
      resources: [{ name: `${name}.bpmn`, content: bpmnXml }],
      deployChangedOnly: options.deployChangedOnly,
    });
  }

  /**
   * Delete a deployment
   */
  async deleteDeployment(id: string, cascade = false): Promise<boolean> {
    const data = await this.#client.mutation<{ deleteDeployment: boolean }>(
      BPM_MUTATIONS.DELETE_DEPLOYMENT,
      { id, cascade }
    );
    return data.deleteDeployment;
  }

  /**
   * Suspend a process definition
   */
  async suspendProcessDefinition(
    id: string,
    includeInstances = false
  ): Promise<ProcessDefinition> {
    const data = await this.#client.mutation<{ suspendProcessDefinition: ProcessDefinition }>(
      BPM_MUTATIONS.SUSPEND_PROCESS_DEFINITION,
      { id, includeInstances }
    );
    return data.suspendProcessDefinition;
  }

  /**
   * Activate a process definition
   */
  async activateProcessDefinition(
    id: string,
    includeInstances = false
  ): Promise<ProcessDefinition> {
    const data = await this.#client.mutation<{ activateProcessDefinition: ProcessDefinition }>(
      BPM_MUTATIONS.ACTIVATE_PROCESS_DEFINITION,
      { id, includeInstances }
    );
    return data.activateProcessDefinition;
  }

  // =========================================================================
  // Utility Methods
  // =========================================================================

  // ===========================================================================
  // Incidents
  // ===========================================================================

  /**
   * List incidents. Stack trace is omitted by default (use getIncident for that).
   */
  async listIncidents(options: ListIncidentsOptions = {}): Promise<IncidentConnection> {
    const data = await this.#client.query<{ incidents: IncidentConnection }>(
      BPM_QUERIES.LIST_INCIDENTS,
      {
        first: options.first ?? 100,
        after: options.after,
        processInstanceId: options.processInstanceId,
        processDefinitionId: options.processDefinitionId,
        processDefinitionKey: options.processDefinitionKey,
        incidentType: options.incidentType,
        activityId: options.activityId,
      },
    );
    return data.incidents;
  }

  /**
   * Get a single incident by ID. Pass includeStackTrace=true to retrieve the
   * full Job exception stack trace (potentially large).
   */
  async getIncident(id: string, includeStackTrace = true): Promise<Incident | null> {
    const data = await this.#client.query<{ incident: Incident | null }>(
      BPM_QUERIES.GET_INCIDENT,
      { id, includeStackTrace },
    );
    return data.incident;
  }

  /**
   * Restore retries on a failed-job incident. The engine's Job Executor
   * picks the job up on its next cycle and the incident auto-resolves on
   * success.
   */
  async setJobRetries(input: SetJobRetriesInput): Promise<Incident | null> {
    const data = await this.#client.mutation<{ setJobRetries: Incident | null }>(
      BPM_MUTATIONS.SET_JOB_RETRIES,
      { input },
    );
    return data.setJobRetries;
  }

  /**
   * Resolve a custom incident. The engine throws for failedJob/
   * failedExternalTask incidents — use setJobRetries for those.
   */
  async resolveIncident(id: string): Promise<boolean> {
    const data = await this.#client.mutation<{ resolveIncident: boolean }>(
      BPM_MUTATIONS.RESOLVE_INCIDENT,
      { id },
    );
    return data.resolveIncident;
  }

  /**
   * Set or clear an operator annotation on an incident. Pass null/empty
   * annotation to clear.
   */
  async setIncidentAnnotation(input: SetIncidentAnnotationInput): Promise<Incident | null> {
    const data = await this.#client.mutation<{ setIncidentAnnotation: Incident | null }>(
      BPM_MUTATIONS.SET_INCIDENT_ANNOTATION,
      { input },
    );
    return data.setIncidentAnnotation;
  }

  // ===========================================================================
  // Migration
  // ===========================================================================

  /**
   * Build a migration plan preview. Defaults to mapEqualActivities(); the
   * returned plan is in-memory only and is not applied until
   * migrateProcessInstance() is called.
   */
  async createMigrationPlan(input: CreateMigrationPlanInput): Promise<MigrationPlan> {
    const data = await this.#client.mutation<{ createMigrationPlan: MigrationPlan }>(
      BPM_MUTATIONS.CREATE_MIGRATION_PLAN,
      { input },
    );
    return data.createMigrationPlan;
  }

  /**
   * Submit a migration job. The server queues a CMS JobManager job that
   * seeds the Camunda batch on its worker thread; this call returns the
   * CMS job id (subscribe via `jobProgress(jobId)` for live counts) and an
   * `abortable` flag that's `false` for Camunda 7 — the migration overlay
   * uses it to hide the Abort button.
   */
  async migrateProcessInstance(input: MigrateProcessInstanceInput): Promise<MigrationJob> {
    const data = await this.#client.mutation<{ migrateProcessInstance: MigrationJob }>(
      BPM_MUTATIONS.MIGRATE_PROCESS_INSTANCE,
      { input },
    );
    return data.migrateProcessInstance;
  }

  /**
   * Create a process variable input
   */
  createVariable(name: string, value: unknown, type?: string): ProcessVariableInput {
    return {
      name,
      value,
      type: type ?? this.#inferType(value),
    };
  }

  /**
   * Infer variable type from value
   */
  #inferType(value: unknown): string {
    if (typeof value === 'string') return 'String';
    if (typeof value === 'number') {
      return Number.isInteger(value) ? 'Long' : 'Double';
    }
    if (typeof value === 'boolean') return 'Boolean';
    if (value instanceof Date) return 'Date';
    if (value === null) return 'Null';
    return 'Object';
  }
}

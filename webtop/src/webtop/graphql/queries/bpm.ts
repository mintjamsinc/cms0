/**
 * Business Process Management (Camunda BPM) GraphQL Queries and Mutations
 */

// =============================================================================
// Field sets
//
// The backend GraphQL AST parser does not support GraphQL fragment definitions
// (`fragment X on T { ... }` / `...X`) — see documents/CMS0_GRAPHQL_API.md.
// Sending a document that begins with a `fragment` definition fails with
// "Unsupported operation type" because the parser only recognises
// `query` / `mutation` / `subscription` at the document root.
//
// To keep DRY without using fragments, these constants expose a bare selection
// set (just the field list) that can be interpolated directly inside any
// selection block via `${X_FIELDS}`.
// =============================================================================

export const TASK_BASIC_FIELDS = `
  id
  name
  description
  assignee
  created
  due
  priority
  suspended
  processInstanceId
  processDefinitionKey
  taskDefinitionKey
  formKey
`;

export const TASK_FULL_FIELDS = `
  id
  name
  description
  assignee
  owner
  created
  due
  followUp
  priority
  suspended
  processInstanceId
  processDefinitionId
  processDefinitionKey
  executionId
  taskDefinitionKey
  formKey
  candidateUsers
  candidateGroups
`;

export const PROCESS_INSTANCE_FIELDS = `
  id
  definitionId
  definitionKey
  businessKey
  suspended
  ended
  startTime
  endTime
  durationInMillis
  incidentCount
`;

export const INCIDENT_FIELDS = `
  id
  type
  message
  incidentTimestamp
  activityId
  activityName
  executionId
  processInstanceId
  processDefinitionId
  processDefinitionKey
  jobId
  jobRetries
  causeIncidentId
  rootCauseIncidentId
  configuration
  annotation
  tenantId
`;

export const PROCESS_VARIABLE_FIELDS = `
  name
  type
  value
  valueInfo
`;

// =============================================================================
// Queries
// =============================================================================

export const BPM_QUERIES = {
  /** Get a single task by ID */
  GET_TASK: `
    query GetTask($id: ID!) {
      task(id: $id) {
        id
        name
        description
        assignee
        owner
        created
        due
        followUp
        priority
        suspended
        processInstanceId
        processDefinitionId
        processDefinitionKey
        executionId
        taskDefinitionKey
        formKey
        candidateUsers
        candidateGroups
        processInstance {
          businessKey
        }
        variables {
          name
          type
          value
          valueInfo
        }
        localVariables {
          name
          type
          value
          valueInfo
        }
      }
    }
  `,

  /** List tasks with filters */
  LIST_TASKS: `
    query ListTasks(
      $first: Int
      $after: String
      $assignee: String
      $assigneeIn: [String!]
      $candidateUser: String
      $candidateGroup: String
      $candidateGroups: [String!]
      $unassigned: Boolean
      $processInstanceId: String
      $processDefinitionId: String
      $processDefinitionKey: String
      $taskDefinitionKey: String
      $dueBefore: DateTime
      $dueAfter: DateTime
      $createdBefore: DateTime
      $createdAfter: DateTime
      $priority: Int
      $priorityHigherThanOrEquals: Int
      $priorityLowerThanOrEquals: Int
      $sortBy: TaskSortField
      $sortOrder: SortOrder
    ) {
      tasks(
        first: $first
        after: $after
        assignee: $assignee
        assigneeIn: $assigneeIn
        candidateUser: $candidateUser
        candidateGroup: $candidateGroup
        candidateGroups: $candidateGroups
        unassigned: $unassigned
        processInstanceId: $processInstanceId
        processDefinitionId: $processDefinitionId
        processDefinitionKey: $processDefinitionKey
        taskDefinitionKey: $taskDefinitionKey
        dueBefore: $dueBefore
        dueAfter: $dueAfter
        createdBefore: $createdBefore
        createdAfter: $createdAfter
        priority: $priority
        priorityHigherThanOrEquals: $priorityHigherThanOrEquals
        priorityLowerThanOrEquals: $priorityLowerThanOrEquals
        sortBy: $sortBy
        sortOrder: $sortOrder
      ) {
        edges {
          node {
            id
            name
            description
            assignee
            created
            due
            priority
            suspended
            processInstanceId
            processDefinitionKey
            taskDefinitionKey
            formKey
            processInstance {
              businessKey
            }
          }
          cursor
        }
        pageInfo {
          hasNextPage
          hasPreviousPage
          startCursor
          endCursor
        }
        totalCount
      }
    }
  `,

  /** Get task counts for dashboard */
  GET_TASK_COUNTS: `
    query GetTaskCounts(
      $assignee: String
      $candidateUser: String
      $candidateGroups: [String!]
    ) {
      taskCounts(
        assignee: $assignee
        candidateUser: $candidateUser
        candidateGroups: $candidateGroups
      ) {
        total
        unassigned
        assigned
        overdue
        dueToday
        dueThisWeek
      }
    }
  `,

  /** Get a single process definition */
  GET_PROCESS_DEFINITION: `
    query GetProcessDefinition($id: ID, $key: String, $version: Int) {
      processDefinition(id: $id, key: $key, version: $version) {
        id
        key
        name
        description
        version
        deploymentId
        deploymentName
        resourceName
        diagramResourceName
        suspended
        category
        startFormKey
      }
    }
  `,

  /** Get BPMN XML for a process definition */
  GET_PROCESS_DEFINITION_XML: `
    query GetProcessDefinitionXml($id: ID!) {
      processDefinitionXml(id: $id)
    }
  `,

  /** Get activity history for a process instance */
  GET_ACTIVITY_HISTORY: `
    query GetActivityHistory($processInstanceId: ID!) {
      activityHistory(processInstanceId: $processInstanceId) {
        id
        activityId
        activityName
        activityType
        startTime
        endTime
        durationInMillis
        canceled
        completeScope
      }
    }
  `,

  /** List process definitions */
  LIST_PROCESS_DEFINITIONS: `
    query ListProcessDefinitions(
      $first: Int
      $after: String
      $key: String
      $name: String
      $latestVersion: Boolean
      $suspended: Boolean
    ) {
      processDefinitions(
        first: $first
        after: $after
        key: $key
        name: $name
        latestVersion: $latestVersion
        suspended: $suspended
      ) {
        edges {
          node {
            id
            key
            name
            description
            version
            deploymentId
            deploymentName
            suspended
            category
            startFormKey
          }
          cursor
        }
        pageInfo {
          hasNextPage
          endCursor
        }
        totalCount
      }
    }
  `,

  /** Get a single process instance */
  GET_PROCESS_INSTANCE: `
    query GetProcessInstance($id: ID!) {
      processInstance(id: $id) {
        id
        definitionId
        definitionKey
        businessKey
        suspended
        ended
        startTime
        endTime
        durationInMillis
        incidentCount
        variables {
          name
          type
          value
          valueInfo
        }
      }
    }
  `,

  /** List process instances */
  LIST_PROCESS_INSTANCES: `
    query ListProcessInstances(
      $first: Int
      $after: String
      $definitionKey: String
      $definitionId: String
      $businessKey: String
      $active: Boolean
      $suspended: Boolean
      $withIncidents: Boolean
      $startedBefore: DateTime
      $startedAfter: DateTime
    ) {
      processInstances(
        first: $first
        after: $after
        definitionKey: $definitionKey
        definitionId: $definitionId
        businessKey: $businessKey
        active: $active
        suspended: $suspended
        withIncidents: $withIncidents
        startedBefore: $startedBefore
        startedAfter: $startedAfter
      ) {
        edges {
          node {
            id
            definitionId
            definitionKey
            businessKey
            suspended
            ended
            startTime
            endTime
            incidentCount
          }
          cursor
        }
        pageInfo {
          hasNextPage
          endCursor
        }
        totalCount
      }
    }
  `,

  /** List incidents (stack trace omitted by default for performance) */
  LIST_INCIDENTS: `
    query ListIncidents(
      $first: Int
      $after: String
      $processInstanceId: ID
      $processDefinitionId: String
      $processDefinitionKey: String
      $incidentType: String
      $activityId: String
    ) {
      incidents(
        first: $first
        after: $after
        processInstanceId: $processInstanceId
        processDefinitionId: $processDefinitionId
        processDefinitionKey: $processDefinitionKey
        incidentType: $incidentType
        activityId: $activityId
      ) {
        edges {
          node {
            ${INCIDENT_FIELDS}
          }
          cursor
        }
        pageInfo {
          hasNextPage
          endCursor
        }
        totalCount
      }
    }
  `,

  /** Get a single incident with optional stack trace */
  GET_INCIDENT: `
    query GetIncident($id: ID!, $includeStackTrace: Boolean) {
      incident(id: $id, includeStackTrace: $includeStackTrace) {
        ${INCIDENT_FIELDS}
        stackTrace
      }
    }
  `,
} as const;

// =============================================================================
// Mutations
// =============================================================================

export const BPM_MUTATIONS = {
  /** Start a new process instance */
  START_PROCESS: `
    mutation StartProcess($input: StartProcessInput!) {
      startProcess(input: $input) {
        id
        definitionId
        definitionKey
        businessKey
        startTime
      }
    }
  `,

  /** Suspend a process instance */
  SUSPEND_PROCESS_INSTANCE: `
    mutation SuspendProcessInstance($id: ID!) {
      suspendProcessInstance(id: $id) {
        id
        suspended
      }
    }
  `,

  /** Activate a suspended process instance */
  ACTIVATE_PROCESS_INSTANCE: `
    mutation ActivateProcessInstance($id: ID!) {
      activateProcessInstance(id: $id) {
        id
        suspended
      }
    }
  `,

  /** Cancel a process instance */
  CANCEL_PROCESS_INSTANCE: `
    mutation CancelProcessInstance($id: ID!, $reason: String) {
      cancelProcessInstance(id: $id, reason: $reason)
    }
  `,

  /** Delete a process instance */
  DELETE_PROCESS_INSTANCE: `
    mutation DeleteProcessInstance($id: ID!, $skipCustomListeners: Boolean) {
      deleteProcessInstance(id: $id, skipCustomListeners: $skipCustomListeners)
    }
  `,

  /** Set variables on a process instance */
  SET_PROCESS_VARIABLES: `
    mutation SetProcessVariables($processInstanceId: ID!, $variables: [ProcessVariableInput!]!) {
      setProcessVariables(processInstanceId: $processInstanceId, variables: $variables) {
        id
        variables {
          name
          type
          value
        }
      }
    }
  `,

  /** Claim a task for the current user */
  CLAIM_TASK: `
    mutation ClaimTask($taskId: ID!) {
      claimTask(taskId: $taskId) {
        id
        assignee
      }
    }
  `,

  /** Release a claimed task */
  UNCLAIM_TASK: `
    mutation UnclaimTask($taskId: ID!) {
      unclaimTask(taskId: $taskId) {
        id
        assignee
      }
    }
  `,

  /** Assign a task to a specific user */
  ASSIGN_TASK: `
    mutation AssignTask($taskId: ID!, $assignee: String!) {
      assignTask(taskId: $taskId, assignee: $assignee) {
        id
        assignee
      }
    }
  `,

  /** Set task assignee (can be null to unassign) */
  SET_TASK_ASSIGNEE: `
    mutation SetTaskAssignee($taskId: ID!, $assignee: String) {
      setTaskAssignee(taskId: $taskId, assignee: $assignee) {
        id
        assignee
      }
    }
  `,

  /** Delegate a task to another user */
  DELEGATE_TASK: `
    mutation DelegateTask($taskId: ID!, $assignee: String!) {
      delegateTask(taskId: $taskId, assignee: $assignee) {
        id
        assignee
        owner
      }
    }
  `,

  /** Complete a task */
  COMPLETE_TASK: `
    mutation CompleteTask($input: CompleteTaskInput!) {
      completeTask(input: $input) {
        id
        name
      }
    }
  `,

  /** Set variables on a task */
  SET_TASK_VARIABLES: `
    mutation SetTaskVariables($taskId: ID!, $variables: [ProcessVariableInput!]!, $local: Boolean) {
      setTaskVariables(taskId: $taskId, variables: $variables, local: $local) {
        id
        variables {
          name
          type
          value
        }
        localVariables {
          name
          type
          value
        }
      }
    }
  `,

  /** Add a comment to a task */
  ADD_TASK_COMMENT: `
    mutation AddTaskComment($taskId: ID!, $message: String!) {
      addTaskComment(taskId: $taskId, message: $message) {
        id
        taskId
        userId
        time
        message
      }
    }
  `,

  /** Deploy a process definition */
  DEPLOY_PROCESS: `
    mutation DeployProcess($input: DeployProcessInput!) {
      deployProcess(input: $input) {
        id
        name
        deploymentTime
        source
      }
    }
  `,

  /** Delete a deployment */
  DELETE_DEPLOYMENT: `
    mutation DeleteDeployment($id: ID!, $cascade: Boolean) {
      deleteDeployment(id: $id, cascade: $cascade)
    }
  `,

  /** Suspend a process definition */
  SUSPEND_PROCESS_DEFINITION: `
    mutation SuspendProcessDefinition($id: ID!, $includeInstances: Boolean) {
      suspendProcessDefinition(id: $id, includeInstances: $includeInstances) {
        id
        key
        name
        version
        deploymentId
        deploymentName
        suspended
        category
      }
    }
  `,

  /** Activate a process definition */
  ACTIVATE_PROCESS_DEFINITION: `
    mutation ActivateProcessDefinition($id: ID!, $includeInstances: Boolean) {
      activateProcessDefinition(id: $id, includeInstances: $includeInstances) {
        id
        key
        name
        version
        deploymentId
        deploymentName
        suspended
        category
      }
    }
  `,

  /** Restore retries on a failed job (resolves a failedJob incident on next cycle). */
  SET_JOB_RETRIES: `
    mutation SetJobRetries($input: SetJobRetriesInput!) {
      setJobRetries(input: $input) {
        ${INCIDENT_FIELDS}
      }
    }
  `,

  /** Resolve a custom incident. The engine rejects this for failedJob incidents. */
  RESOLVE_INCIDENT: `
    mutation ResolveIncident($id: ID!) {
      resolveIncident(id: $id)
    }
  `,

  /** Set or clear the operator annotation on an incident. */
  SET_INCIDENT_ANNOTATION: `
    mutation SetIncidentAnnotation($input: SetIncidentAnnotationInput!) {
      setIncidentAnnotation(input: $input) {
        ${INCIDENT_FIELDS}
      }
    }
  `,

  /** Build a migration plan preview (mapEqualActivities by default). */
  CREATE_MIGRATION_PLAN: `
    mutation CreateMigrationPlan($input: CreateMigrationPlanInput!) {
      createMigrationPlan(input: $input) {
        sourceProcessDefinitionId
        targetProcessDefinitionId
        instructions {
          sourceActivityIds
          targetActivityIds
          updateEventTrigger
        }
      }
    }
  `,

  /**
   * Migrate process instances. The server queues a CMS JobManager job that
   * seeds a Camunda batch from its worker thread and republishes progress
   * through `jobProgress(jobId)`. The mutation returns the CMS job id (not
   * the Camunda batch id) plus an `abortable` flag the client uses to show
   * or hide the Abort button (always false for Camunda 7).
   */
  MIGRATE_PROCESS_INSTANCE: `
    mutation MigrateProcessInstance($input: MigrateProcessInstanceInput!) {
      migrateProcessInstance(input: $input) {
        id
        jobType
        status
        abortable
      }
    }
  `,
} as const;

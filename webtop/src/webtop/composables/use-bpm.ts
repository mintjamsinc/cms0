/**
 * BPM Composables
 *
 * Composables for working with Camunda BPM via GraphQL.
 */

import type {
  Task,
  TaskCounts,
  ProcessDefinition,
  ProcessInstance,
  ProcessVariableInput,
  TaskSortField,
  SortOrder,
} from '../graphql/types.js';
import type { BpmServiceGraphQL } from '../services/bpm-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';
import { useAsync, usePaginated, type Connection } from './use-async.js';
import { createStore } from '../stores/create-store.js';

/**
 * Hook for fetching and watching a single task
 */
export interface UseTaskOptions {
  /** Whether to watch for changes */
  watch?: boolean;
  /** Cache duration in ms */
  cacheDuration?: number;
}

export interface UseTaskReturn {
  /** Current state */
  state: {
    task: Task | null;
    loading: boolean;
    error: string | null;
  };
  /** Fetch the task */
  fetch: () => Promise<Task | null>;
  /** Refresh the task */
  refresh: () => Promise<Task | null>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseTaskReturn['state']) => void) => () => void;
  /** Clean up */
  dispose: () => void;
}

export function useTask(
  bpmService: BpmServiceGraphQL,
  taskId: string,
  options: UseTaskOptions = {},
  eventHub?: EventHub
): UseTaskReturn {
  const { watch = false, cacheDuration } = options;

  const async = useAsync(
    () => bpmService.getTask(taskId),
    { cacheDuration }
  );

  let unwatch: (() => void) | null = null;

  if (watch && eventHub) {
    unwatch = eventHub.watchTask(taskId, (event) => {
      if (event.eventType === 'COMPLETED' || event.eventType === 'DELETED') {
        async.setData(null);
      } else {
        async.execute();
      }
    });
  }

  return {
    get state() {
      const s = async.state;
      return {
        task: s.data,
        loading: s.loading,
        error: s.error,
      };
    },
    fetch: async.execute,
    refresh: async.execute,
    subscribe: (listener) => async.subscribe((s) => listener({
      task: s.data,
      loading: s.loading,
      error: s.error,
    })),
    dispose: () => {
      unwatch?.();
    },
  };
}

/**
 * Hook for listing tasks with pagination
 */
export interface UseTaskListOptions {
  /** Filter by assignee */
  assignee?: string;
  /** Filter by candidate user */
  candidateUser?: string;
  /** Filter by candidate groups */
  candidateGroups?: string[];
  /** Filter unassigned tasks */
  unassigned?: boolean;
  /** Filter by process definition key */
  processDefinitionKey?: string;
  /** Sort field */
  sortBy?: TaskSortField;
  /** Sort order */
  sortOrder?: SortOrder;
  /** Page size */
  pageSize?: number;
  /** Watch for real-time updates */
  watch?: boolean;
}

export interface UseTaskListReturn {
  /** Current state */
  state: {
    tasks: Task[];
    loading: boolean;
    loadingMore: boolean;
    error: string | null;
    hasMore: boolean;
    totalCount: number | null;
  };
  /** Load first page */
  load: () => Promise<void>;
  /** Load more items */
  loadMore: () => Promise<void>;
  /** Refresh the list */
  refresh: () => Promise<void>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseTaskListReturn['state']) => void) => () => void;
  /** Clean up */
  dispose: () => void;
}

export function useTaskList(
  bpmService: BpmServiceGraphQL,
  options: UseTaskListOptions = {},
  eventHub?: EventHub
): UseTaskListReturn {
  const {
    assignee,
    candidateUser,
    candidateGroups,
    unassigned,
    processDefinitionKey,
    sortBy,
    sortOrder,
    pageSize = 20,
    watch = false,
  } = options;

  const paginated = usePaginated<Task>(
    async (cursor, size) => {
      const result = await bpmService.listTasks({
        first: size,
        after: cursor ?? undefined,
        assignee,
        candidateUser,
        candidateGroups,
        unassigned,
        processDefinitionKey,
        sortBy,
        sortOrder,
      });
      return result as Connection<Task>;
    },
    { pageSize }
  );

  let unwatchers: Array<() => void> = [];

  if (watch && eventHub) {
    // Watch for task assignments to current user
    if (assignee) {
      unwatchers.push(
        eventHub.watchTaskAssignment(assignee, (event) => {
          if (event.eventType === 'ASSIGNED') {
            paginated.load();
          }
        })
      );
    }
  }

  return {
    get state() {
      const s = paginated.state;
      return {
        tasks: s.items,
        loading: s.loading,
        loadingMore: s.loadingMore,
        error: s.error,
        hasMore: s.hasMore,
        totalCount: s.totalCount,
      };
    },
    load: paginated.load,
    loadMore: paginated.loadMore,
    refresh: paginated.load,
    subscribe: (listener) => paginated.subscribe((s) => listener({
      tasks: s.items,
      loading: s.loading,
      loadingMore: s.loadingMore,
      error: s.error,
      hasMore: s.hasMore,
      totalCount: s.totalCount,
    })),
    dispose: () => {
      unwatchers.forEach(u => u());
    },
  };
}

/**
 * Hook for task counts
 */
export interface UseTaskCountsOptions {
  assignee?: string;
  candidateUser?: string;
  candidateGroups?: string[];
  /** Polling interval in ms (default: no polling) */
  pollInterval?: number;
}

export interface UseTaskCountsReturn {
  /** Current state */
  state: {
    counts: TaskCounts | null;
    loading: boolean;
    error: string | null;
  };
  /** Fetch counts */
  fetch: () => Promise<TaskCounts | null>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseTaskCountsReturn['state']) => void) => () => void;
  /** Stop polling */
  dispose: () => void;
}

export function useTaskCounts(
  bpmService: BpmServiceGraphQL,
  options: UseTaskCountsOptions = {}
): UseTaskCountsReturn {
  const { assignee, candidateUser, candidateGroups, pollInterval } = options;

  const async = useAsync(
    () => bpmService.getTaskCounts({ assignee, candidateUser, candidateGroups })
  );

  let intervalId: number | null = null;

  if (pollInterval && pollInterval > 0) {
    intervalId = window.setInterval(() => {
      async.execute();
    }, pollInterval);
  }

  return {
    get state() {
      const s = async.state;
      return {
        counts: s.data,
        loading: s.loading,
        error: s.error,
      };
    },
    fetch: async.execute,
    subscribe: (listener) => async.subscribe((s) => listener({
      counts: s.data,
      loading: s.loading,
      error: s.error,
    })),
    dispose: () => {
      if (intervalId !== null) {
        clearInterval(intervalId);
      }
    },
  };
}

/**
 * Hook for task mutations
 */
export interface UseTaskMutationsReturn {
  /** Claim a task */
  claim: (taskId: string) => Promise<Task | null>;
  /** Unclaim a task */
  unclaim: (taskId: string) => Promise<Task | null>;
  /** Complete a task */
  complete: (taskId: string, variables?: ProcessVariableInput[]) => Promise<Task | null>;
  /** Delegate a task */
  delegate: (taskId: string, userId: string) => Promise<Task | null>;
  /** Assign a task */
  assign: (taskId: string, assignee: string) => Promise<Task | null>;
  /** Add a comment */
  addComment: (taskId: string, message: string) => Promise<boolean>;
  /** Current state */
  state: {
    pending: boolean;
    error: string | null;
  };
  /** Subscribe to state changes */
  subscribe: (listener: (state: { pending: boolean; error: string | null }) => void) => () => void;
}

export function useTaskMutations(
  bpmService: BpmServiceGraphQL
): UseTaskMutationsReturn {
  const store = createStore<{ pending: boolean; error: string | null }>({
    pending: false,
    error: null,
  });

  const wrap = async <T>(fn: () => Promise<T>): Promise<T | null> => {
    store.setState({ pending: true, error: null });
    try {
      const result = await fn();
      store.setState({ pending: false });
      return result;
    } catch (err) {
      const error = err instanceof Error ? err.message : String(err);
      store.setState({ pending: false, error });
      return null;
    }
  };

  return {
    claim: (taskId) => wrap(() => bpmService.claimTask(taskId)),
    unclaim: (taskId) => wrap(() => bpmService.unclaimTask(taskId)),
    complete: (taskId, variables) =>
      wrap(() => bpmService.completeTask({ taskId, variables })),
    delegate: (taskId, userId) => wrap(() => bpmService.delegateTask(taskId, userId)),
    assign: (taskId, assignee) => wrap(() => bpmService.assignTask(taskId, assignee)),
    addComment: (taskId, message) =>
      wrap(() => bpmService.addTaskComment(taskId, message)).then(r => r !== null),

    get state() {
      return store.state;
    },
    subscribe: store.subscribe.bind(store),
  };
}

/**
 * Hook for process definitions
 */
export interface UseProcessDefinitionsReturn {
  /** Current state */
  state: {
    definitions: ProcessDefinition[];
    loading: boolean;
    error: string | null;
  };
  /** Fetch definitions */
  fetch: () => Promise<void>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseProcessDefinitionsReturn['state']) => void) => () => void;
}

export function useProcessDefinitions(
  bpmService: BpmServiceGraphQL,
  options: { latestVersion?: boolean } = {}
): UseProcessDefinitionsReturn {
  const { latestVersion = true } = options;

  const async = useAsync(
    async () => {
      const result = await bpmService.listProcessDefinitions({ latestVersion });
      return result.edges.map(e => e.node);
    }
  );

  return {
    get state() {
      const s = async.state;
      return {
        definitions: s.data ?? [],
        loading: s.loading,
        error: s.error,
      };
    },
    fetch: async () => { await async.execute(); },
    subscribe: (listener) => async.subscribe((s) => listener({
      definitions: s.data ?? [],
      loading: s.loading,
      error: s.error,
    })),
  };
}

/**
 * Hook for process instances
 */
export interface UseProcessInstanceOptions {
  watch?: boolean;
  cacheDuration?: number;
}

export interface UseProcessInstanceReturn {
  /** Current state */
  state: {
    instance: ProcessInstance | null;
    loading: boolean;
    error: string | null;
  };
  /** Fetch the instance */
  fetch: () => Promise<ProcessInstance | null>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseProcessInstanceReturn['state']) => void) => () => void;
  /** Clean up */
  dispose: () => void;
}

export function useProcessInstance(
  bpmService: BpmServiceGraphQL,
  instanceId: string,
  options: UseProcessInstanceOptions = {},
  eventHub?: EventHub
): UseProcessInstanceReturn {
  const { watch = false, cacheDuration } = options;

  const async = useAsync(
    () => bpmService.getProcessInstance(instanceId),
    { cacheDuration }
  );

  let unwatch: (() => void) | null = null;

  if (watch && eventHub) {
    unwatch = eventHub.watchProcess(instanceId, (event) => {
      if (event.eventType === 'ENDED' || event.eventType === 'CANCELLED') {
        async.execute();
      }
    });
  }

  return {
    get state() {
      const s = async.state;
      return {
        instance: s.data,
        loading: s.loading,
        error: s.error,
      };
    },
    fetch: async.execute,
    subscribe: (listener) => async.subscribe((s) => listener({
      instance: s.data,
      loading: s.loading,
      error: s.error,
    })),
    dispose: () => {
      unwatch?.();
    },
  };
}

/**
 * Hook for process operations
 */
export interface UseProcessOperationsReturn {
  /** Start a process */
  start: (
    definitionKey: string,
    variables?: ProcessVariableInput[],
    businessKey?: string
  ) => Promise<ProcessInstance | null>;
  /** Cancel a process */
  cancel: (instanceId: string, reason?: string) => Promise<boolean>;
  /** Modify process variables */
  setVariables: (instanceId: string, variables: ProcessVariableInput[]) => Promise<boolean>;
  /** Current state */
  state: {
    pending: boolean;
    error: string | null;
  };
  /** Subscribe to state changes */
  subscribe: (listener: (state: { pending: boolean; error: string | null }) => void) => () => void;
}

export function useProcessOperations(
  bpmService: BpmServiceGraphQL
): UseProcessOperationsReturn {
  const store = createStore<{ pending: boolean; error: string | null }>({
    pending: false,
    error: null,
  });

  const wrap = async <T>(fn: () => Promise<T>): Promise<T | null> => {
    store.setState({ pending: true, error: null });
    try {
      const result = await fn();
      store.setState({ pending: false });
      return result;
    } catch (err) {
      const error = err instanceof Error ? err.message : String(err);
      store.setState({ pending: false, error });
      return null;
    }
  };

  return {
    start: (definitionKey, variables, businessKey) =>
      wrap(() => bpmService.startProcess({ definitionKey, variables, businessKey })),

    cancel: (instanceId, reason) =>
      wrap(() => bpmService.cancelProcessInstance(instanceId, reason)).then(r => r ?? false),

    setVariables: (instanceId, variables) =>
      wrap(() => bpmService.setProcessVariables(instanceId, variables)).then(r => r !== null),

    get state() {
      return store.state;
    },
    subscribe: store.subscribe.bind(store),
  };
}

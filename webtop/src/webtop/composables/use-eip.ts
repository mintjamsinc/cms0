/**
 * EIP Composables
 *
 * Composables for working with Apache Camel routes via GraphQL.
 */

import type {
  Route,
  CamelContext,
  RouteState,
  ValidationResult,
} from '../graphql/types.js';
import type { EipServiceGraphQL } from '../services/eip-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';
import { useAsync, usePaginated, type Connection } from './use-async.js';
import { createStore } from '../stores/create-store.js';

/**
 * Hook for fetching and watching a single route
 */
export interface UseRouteOptions {
  /** Whether to watch for changes */
  watch?: boolean;
  /** Cache duration in ms */
  cacheDuration?: number;
}

export interface UseRouteReturn {
  /** Current state */
  state: {
    route: Route | null;
    loading: boolean;
    error: string | null;
  };
  /** Fetch the route */
  fetch: () => Promise<Route | null>;
  /** Refresh the route */
  refresh: () => Promise<Route | null>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseRouteReturn['state']) => void) => () => void;
  /** Clean up */
  dispose: () => void;
}

export function useRoute(
  eipService: EipServiceGraphQL,
  routeId: string,
  options: UseRouteOptions = {},
  eventHub?: EventHub
): UseRouteReturn {
  const { watch = false, cacheDuration } = options;

  const async = useAsync(
    () => eipService.getRoute(routeId),
    { cacheDuration }
  );

  let unwatch: (() => void) | null = null;

  if (watch && eventHub) {
    unwatch = eventHub.watchRoute(routeId, (event) => {
      // Route state changed, refresh
      async.execute();
    });
  }

  return {
    get state() {
      const s = async.state;
      return {
        route: s.data,
        loading: s.loading,
        error: s.error,
      };
    },
    fetch: async.execute,
    refresh: async.execute,
    subscribe: (listener) => async.subscribe((s) => listener({
      route: s.data,
      loading: s.loading,
      error: s.error,
    })),
    dispose: () => {
      unwatch?.();
    },
  };
}

/**
 * Hook for listing routes with pagination
 */
export interface UseRouteListOptions {
  /** Filter by status */
  status?: RouteState;
  /** Filter by group */
  group?: string;
  /** Page size */
  pageSize?: number;
  /** Watch for real-time updates */
  watch?: boolean;
}

export interface UseRouteListReturn {
  /** Current state */
  state: {
    routes: Route[];
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
  subscribe: (listener: (state: UseRouteListReturn['state']) => void) => () => void;
  /** Clean up */
  dispose: () => void;
}

export function useRouteList(
  eipService: EipServiceGraphQL,
  options: UseRouteListOptions = {},
  eventHub?: EventHub
): UseRouteListReturn {
  const { status, group, pageSize = 50, watch = false } = options;

  const paginated = usePaginated<Route>(
    async (cursor, size) => {
      const result = await eipService.listRoutes({
        status,
        group,
        first: size,
        after: cursor ?? undefined,
      });
      return result as Connection<Route>;
    },
    { pageSize }
  );

  let unwatchers: Array<() => void> = [];

  if (watch && eventHub) {
    // Watch for route changes (would need a generic route change subscription)
    // For now, we can poll or manually refresh
  }

  return {
    get state() {
      const s = paginated.state;
      return {
        routes: s.items,
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
      routes: s.items,
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
 * Hook for Camel context
 */
export interface UseCamelContextOptions {
  /** Watch for status changes */
  watch?: boolean;
  /** Polling interval for statistics in ms */
  statsInterval?: number;
}

export interface UseCamelContextReturn {
  /** Current state */
  state: {
    context: CamelContext | null;
    loading: boolean;
    error: string | null;
  };
  /** Fetch context */
  fetch: () => Promise<CamelContext | null>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseCamelContextReturn['state']) => void) => () => void;
  /** Clean up */
  dispose: () => void;
}

export function useCamelContext(
  eipService: EipServiceGraphQL,
  options: UseCamelContextOptions = {}
): UseCamelContextReturn {
  const { statsInterval } = options;

  const async = useAsync(
    () => eipService.getCamelContext()
  );

  let intervalId: number | null = null;

  if (statsInterval && statsInterval > 0) {
    intervalId = window.setInterval(() => {
      async.execute();
    }, statsInterval);
  }

  return {
    get state() {
      const s = async.state;
      return {
        context: s.data,
        loading: s.loading,
        error: s.error,
      };
    },
    fetch: async.execute,
    subscribe: (listener) => async.subscribe((s) => listener({
      context: s.data,
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
 * Hook for listing Camel contexts
 */
export interface UseCamelContextListReturn {
  /** Current state */
  state: {
    contexts: CamelContext[];
    loading: boolean;
    error: string | null;
  };
  /** Fetch contexts */
  fetch: () => Promise<void>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseCamelContextListReturn['state']) => void) => () => void;
}

export function useCamelContextList(
  eipService: EipServiceGraphQL
): UseCamelContextListReturn {
  const async = useAsync(
    async () => {
      // getCamelContexts returns CamelContext[] directly, not a Connection
      return eipService.getCamelContexts();
    }
  );

  return {
    get state() {
      const s = async.state;
      return {
        contexts: s.data ?? [],
        loading: s.loading,
        error: s.error,
      };
    },
    fetch: async () => { await async.execute(); },
    subscribe: (listener) => async.subscribe((s) => listener({
      contexts: s.data ?? [],
      loading: s.loading,
      error: s.error,
    })),
  };
}

/**
 * Route statistics type (from EipServiceGraphQL.getRouteStatistics)
 */
export interface RouteStatisticsData {
  total: number;
  completed: number;
  failed: number;
  inflight: number;
  successRate: number;
  meanTime: number | null;
}

/**
 * Hook for route statistics
 */
export interface UseRouteStatisticsReturn {
  /** Current state */
  state: {
    statistics: RouteStatisticsData | null;
    loading: boolean;
    error: string | null;
  };
  /** Fetch statistics */
  fetch: () => Promise<RouteStatisticsData | null>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseRouteStatisticsReturn['state']) => void) => () => void;
  /** Stop polling */
  dispose: () => void;
}

export function useRouteStatistics(
  eipService: EipServiceGraphQL,
  routeId: string,
  options: { pollInterval?: number } = {}
): UseRouteStatisticsReturn {
  const { pollInterval } = options;

  const async = useAsync(
    () => eipService.getRouteStatistics(routeId)
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
        statistics: s.data,
        loading: s.loading,
        error: s.error,
      };
    },
    fetch: async.execute,
    subscribe: (listener) => async.subscribe((s) => listener({
      statistics: s.data,
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
 * Hook for route mutations
 */
export interface UseRouteMutationsReturn {
  /** Start a route */
  start: (routeId: string) => Promise<Route | null>;
  /** Stop a route */
  stop: (routeId: string) => Promise<Route | null>;
  /** Suspend a route */
  suspend: (routeId: string) => Promise<Route | null>;
  /** Resume a route */
  resume: (routeId: string) => Promise<Route | null>;
  /** Create a route from YAML */
  create: (routeId: string, yaml: string, options?: { description?: string; group?: string }) => Promise<Route | null>;
  /** Update a route */
  update: (routeId: string, yaml: string, description?: string) => Promise<Route | null>;
  /** Delete a route */
  remove: (routeId: string) => Promise<boolean>;
  /** Validate route definition */
  validate: (yaml: string) => Promise<ValidationResult>;
  /** Current state */
  state: {
    pending: boolean;
    error: string | null;
  };
  /** Subscribe to state changes */
  subscribe: (listener: (state: { pending: boolean; error: string | null }) => void) => () => void;
}

export function useRouteMutations(
  eipService: EipServiceGraphQL
): UseRouteMutationsReturn {
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
    start: (routeId) => wrap(() => eipService.startRoute({ id: routeId })),
    stop: (routeId) => wrap(() => eipService.stopRoute({ id: routeId })),
    suspend: (routeId) => wrap(() => eipService.suspendRoute({ id: routeId })),
    resume: (routeId) => wrap(() => eipService.resumeRoute({ id: routeId })),
    create: (routeId, yaml, options) =>
      wrap(() => eipService.createRoute({
        routeId,
        yaml,
        description: options?.description,
        group: options?.group,
      })),
    update: (routeId, yaml, description) =>
      wrap(() => eipService.updateRoute({ id: routeId, yaml, description })),
    remove: (routeId) =>
      wrap(() => eipService.deleteRoute(routeId)).then(r => r ?? false),
    validate: async (yaml) => {
      const result = await wrap(() => eipService.validateRouteDefinition(yaml));
      return result ?? { valid: false, errors: [], warnings: [] };
    },

    get state() {
      return store.state;
    },
    subscribe: store.subscribe.bind(store),
  };
}

/**
 * Hook for route editor with validation
 */
export interface UseRouteEditorReturn {
  /** Current state */
  state: {
    definition: string;
    isValid: boolean;
    validationErrors: string[];
    isDirty: boolean;
    saving: boolean;
    saveError: string | null;
  };
  /** Set the definition */
  setDefinition: (definition: string) => void;
  /** Validate the definition */
  validate: () => Promise<boolean>;
  /** Save the route */
  save: (routeId: string, description?: string) => Promise<Route | null>;
  /** Reset to original */
  reset: (original: string) => void;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseRouteEditorReturn['state']) => void) => () => void;
}

export function useRouteEditor(
  eipService: EipServiceGraphQL,
  initialDefinition: string = ''
): UseRouteEditorReturn {
  interface EditorState {
    definition: string;
    originalDefinition: string;
    isValid: boolean;
    validationErrors: string[];
    saving: boolean;
    saveError: string | null;
  }

  const store = createStore<EditorState>({
    definition: initialDefinition,
    originalDefinition: initialDefinition,
    isValid: true,
    validationErrors: [],
    saving: false,
    saveError: null,
  });

  const setDefinition = (definition: string) => {
    store.setState({ definition });
  };

  const validate = async (): Promise<boolean> => {
    const { definition } = store.state;
    try {
      const result = await eipService.validateRouteDefinition(definition);
      const errorMessages = result.errors.map(e => typeof e === 'string' ? e : e.message);
      store.setState({
        isValid: result.valid,
        validationErrors: errorMessages,
      });
      return result.valid;
    } catch (err) {
      store.setState({
        isValid: false,
        validationErrors: [err instanceof Error ? err.message : String(err)],
      });
      return false;
    }
  };

  const save = async (routeId: string, description?: string): Promise<Route | null> => {
    const { definition, isValid } = store.state;

    if (!isValid) {
      store.setState({ saveError: 'Cannot save invalid route definition' });
      return null;
    }

    store.setState({ saving: true, saveError: null });

    try {
      const route = await eipService.updateRoute({ id: routeId, yaml: definition, description });
      store.setState({
        saving: false,
        originalDefinition: definition,
      });
      return route;
    } catch (err) {
      const error = err instanceof Error ? err.message : String(err);
      store.setState({ saving: false, saveError: error });
      return null;
    }
  };

  const reset = (original: string) => {
    store.setState({
      definition: original,
      originalDefinition: original,
      isValid: true,
      validationErrors: [],
      saveError: null,
    });
  };

  return {
    get state() {
      const s = store.state;
      return {
        definition: s.definition,
        isValid: s.isValid,
        validationErrors: s.validationErrors,
        isDirty: s.definition !== s.originalDefinition,
        saving: s.saving,
        saveError: s.saveError,
      };
    },
    setDefinition,
    validate,
    save,
    reset,
    subscribe: (listener) => store.subscribe((s) => listener({
      definition: s.definition,
      isValid: s.isValid,
      validationErrors: s.validationErrors,
      isDirty: s.definition !== s.originalDefinition,
      saving: s.saving,
      saveError: s.saveError,
    })),
  };
}

/**
 * EIP Composables
 *
 * Composables for working with Apache Camel routes via GraphQL.
 */

import type {
  Route,
  RouteState,
} from '../graphql/types.js';
import type { EipServiceGraphQL } from '../services/eip-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';
import { useAsync, usePaginated, type Connection } from './use-async.js';

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

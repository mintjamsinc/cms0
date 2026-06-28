/**
 * EIP Service (GraphQL-based)
 *
 * Provides Enterprise Integration Patterns (Apache Camel) operations using GraphQL API.
 */

import { GraphQLClient } from '../graphql/client.js';
import { EIP_QUERIES, EIP_MUTATIONS } from '../graphql/queries/eip.js';
import type {
  Route,
  RouteConnection,
  RouteState,
  StartRouteInput,
  StopRouteInput,
  SuspendRouteInput,
  ResumeRouteInput,
  RouteStats,
  StatInterval,
  HistoryExchangeConnection,
  HistoryExchange,
} from '../graphql/types.js';

export interface RouteStatsOptions {
  /** Route IDs to include. Empty / undefined means all routes. */
  routes?: string[];
  /**
   * Anchor mode: instant the window is right-aligned to (ISO). The right-edge
   * bucket is the fixed wall-clock bucket containing this; omit → server now.
   */
  at?: string;
  /**
   * Anchor mode: number of buckets to return, walking back from the anchor
   * bucket. With {@link interval} this fully defines the window.
   */
  buckets?: number;
  /** Legacy explicit window (used when {@link buckets} is omitted). */
  from?: string;
  to?: string;
  /** Status filter (all | completed | failed). */
  status?: string;
  /** Bucket interval (5min | 1h | 1d). Required in anchor mode; auto-resolved otherwise. */
  interval?: StatInterval;
  /** Ascending elapsed-band boundaries in ms (N boundaries => N+1 bands). */
  elapsedBoundaries?: number[];
}

export interface HistorySearchOptions {
  /** Relay-style forward pagination — page size. */
  first?: number;
  /** Relay-style forward pagination — cursor returned by previous page. */
  after?: string;
  /** Relay-style backward pagination — page size. */
  last?: number;
  /** Relay-style backward pagination — cursor returned by previous page. */
  before?: string;
  routes?: string[];
  status?: string;
  from?: string;
  to?: string;
  /** Free-text — partial match on businessKey / exchangeId / routeId. */
  filter?: string;
  /**
   * Elapsed bands to include — any combination of `under1s` / `under5s` /
   * `over5s`. Undefined or all three values means no elapsed filtering.
   */
  elapsedBands?: string[];
}

export interface ListRoutesOptions {
  first?: number;
  after?: string;
  status?: RouteState;
  group?: string;
  search?: string;
}

/**
 * EIP Service for Apache Camel route management
 */
export class EipServiceGraphQL {
  #client: GraphQLClient;

  constructor(client: GraphQLClient) {
    this.#client = client;
  }

  // =========================================================================
  // Route Queries
  // =========================================================================

  /**
   * Get a single route by ID
   */
  async getRoute(id: string): Promise<Route | null> {
    const data = await this.#client.query<{ route: Route | null }>(
      EIP_QUERIES.GET_ROUTE,
      { id }
    );
    return data.route;
  }

  /**
   * List routes with filters
   */
  async listRoutes(options: ListRoutesOptions = {}): Promise<RouteConnection> {
    const data = await this.#client.query<{ routes: RouteConnection }>(
      EIP_QUERIES.LIST_ROUTES,
      {
        first: options.first ?? 50,
        after: options.after,
        status: options.status,
        group: options.group,
        search: options.search,
      }
    );
    return data.routes;
  }

  /**
   * Get all routes
   */
  async getAllRoutes(): Promise<Route[]> {
    const result = await this.listRoutes({ first: 1000 });
    return result.edges.map(e => e.node);
  }

  /**
   * Dashboard route snapshot — returns every route with both its
   * throughput/error stats and its endpoint connectivity (remote endpoint
   * state) populated in a single round trip. Used by the cross-cutting
   * Dashboard for the route, throughput and external-connection panels.
   */
  async getDashboardRoutes(first = 1000): Promise<Route[]> {
    const data = await this.#client.query<{ routes: RouteConnection }>(
      EIP_QUERIES.DASHBOARD_ROUTES,
      { first }
    );
    return data.routes.edges.map(e => e.node);
  }

  /**
   * Get routes by status
   */
  async getRoutesByStatus(status: RouteState): Promise<Route[]> {
    const result = await this.listRoutes({ status, first: 1000 });
    return result.edges.map(e => e.node);
  }

  /**
   * Get routes by group
   */
  async getRoutesByGroup(group: string): Promise<Route[]> {
    const result = await this.listRoutes({ group, first: 1000 });
    return result.edges.map(e => e.node);
  }

  // =========================================================================
  // Route Control Mutations
  // =========================================================================

  /**
   * Start a route
   */
  async startRoute(input: StartRouteInput): Promise<Route> {
    const data = await this.#client.mutation<{ startRoute: Route }>(
      EIP_MUTATIONS.START_ROUTE,
      { input }
    );
    return data.startRoute;
  }

  /**
   * Stop a route
   */
  async stopRoute(input: StopRouteInput): Promise<Route> {
    const data = await this.#client.mutation<{ stopRoute: Route }>(
      EIP_MUTATIONS.STOP_ROUTE,
      { input }
    );
    return data.stopRoute;
  }

  /**
   * Suspend a route
   */
  async suspendRoute(input: SuspendRouteInput): Promise<Route> {
    const data = await this.#client.mutation<{ suspendRoute: Route }>(
      EIP_MUTATIONS.SUSPEND_ROUTE,
      { input }
    );
    return data.suspendRoute;
  }

  /**
   * Resume a suspended route
   */
  async resumeRoute(input: ResumeRouteInput): Promise<Route> {
    const data = await this.#client.mutation<{ resumeRoute: Route }>(
      EIP_MUTATIONS.RESUME_ROUTE,
      { input }
    );
    return data.resumeRoute;
  }

  // =========================================================================
  // Utility Methods
  // =========================================================================

  /**
   * Check if route is running
   */
  async isRouteRunning(id: string): Promise<boolean> {
    const route = await this.getRoute(id);
    return route?.status === 'Started';
  }

  // =========================================================================
  // EIP Console — time-series + history search
  // =========================================================================

  /**
   * Fetch the three-band exchange-count time series for the EIP Console
   * graph panel.
   */
  async getRouteStats(options: RouteStatsOptions): Promise<RouteStats> {
    const data = await this.#client.query<{ routeStats: RouteStats }>(
      EIP_QUERIES.ROUTE_STATS,
      {
        routes: options.routes && options.routes.length ? options.routes : undefined,
        at: options.at,
        buckets: options.buckets,
        from: options.from,
        to: options.to,
        status: options.status,
        interval: options.interval,
        elapsedBoundaries: options.elapsedBoundaries && options.elapsedBoundaries.length
          ? options.elapsedBoundaries
          : undefined,
      }
    );
    return data.routeStats;
  }

  /**
   * Lucene-backed search over exchange history records.
   * Returns a Relay-style cursor connection.
   */
  async listHistoryExchanges(options: HistorySearchOptions = {}): Promise<HistoryExchangeConnection> {
    const data = await this.#client.query<{ historyExchanges: HistoryExchangeConnection }>(
      EIP_QUERIES.HISTORY_EXCHANGES,
      {
        first: options.first,
        after: options.after,
        last: options.last,
        before: options.before,
        routes: options.routes && options.routes.length ? options.routes : undefined,
        status: options.status,
        from: options.from,
        to: options.to,
        filter: options.filter,
        elapsedBands: options.elapsedBands && options.elapsedBands.length
          ? options.elapsedBands
          : undefined,
      }
    );
    return data.historyExchanges;
  }

  /**
   * Fetch the full detail of a single exchange record (Inspector).
   *
   * Prefer addressing by node {@link HistoryExchange.path} — a single exchange
   * may have one record per route (same exchangeId), so an id is ambiguous.
   * Passing a bare exchangeId remains supported for callers that only have one.
   */
  async getHistoryExchange(ref: string | { path?: string; exchangeId?: string }): Promise<HistoryExchange | null> {
    const vars = typeof ref === 'string' ? { exchangeId: ref } : ref;
    const data = await this.#client.query<{ historyExchange: HistoryExchange | null }>(
      EIP_QUERIES.HISTORY_EXCHANGE,
      vars
    );
    return data.historyExchange;
  }

  /**
   * Get route statistics summary
   */
  async getRouteStatistics(id: string): Promise<{
    total: number;
    completed: number;
    failed: number;
    inflight: number;
    successRate: number;
    meanTime: number | null;
  } | null> {
    const route = await this.getRoute(id);
    if (!route) return null;

    const total = route.exchangesTotal;
    const completed = route.exchangesCompleted;
    const failed = route.exchangesFailed;
    const inflight = route.exchangesInflight;
    const successRate = total > 0 ? (completed / total) * 100 : 0;

    return {
      total,
      completed,
      failed,
      inflight,
      successRate,
      meanTime: route.meanProcessingTime ?? null,
    };
  }
}

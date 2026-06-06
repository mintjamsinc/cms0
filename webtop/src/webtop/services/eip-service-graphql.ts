/**
 * EIP Service (GraphQL-based)
 *
 * Provides Enterprise Integration Patterns (Apache Camel) operations using GraphQL API.
 */

import { GraphQLClient } from '../graphql/client.js';
import { EIP_QUERIES, EIP_MUTATIONS } from '../graphql/queries/eip.js';
import type {
  CamelContext,
  Route,
  RouteConnection,
  RouteState,
  Component,
  Endpoint,
  RouteTemplate,
  ValidationResult,
  ExchangeResult,
  CreateRouteInput,
  UpdateRouteInput,
  SendToEndpointInput,
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
  from: string;
  to: string;
  /** Status filter (all | completed | failed). */
  status?: string;
  /** Bucket interval (5min | 1h | 1d). Server auto-resolves when omitted. */
  interval?: StatInterval;
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
  // Camel Context Queries
  // =========================================================================

  /**
   * Get the Camel context
   */
  async getCamelContext(): Promise<CamelContext> {
    const data = await this.#client.query<{ camelContext: CamelContext }>(
      EIP_QUERIES.GET_CAMEL_CONTEXT
    );
    return data.camelContext;
  }

  /**
   * Get all Camel contexts (multi-context setup)
   */
  async getCamelContexts(): Promise<CamelContext[]> {
    const data = await this.#client.query<{ camelContexts: CamelContext[] }>(
      EIP_QUERIES.GET_CAMEL_CONTEXTS
    );
    return data.camelContexts;
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
  // Component Queries
  // =========================================================================

  /**
   * Get a component by name
   */
  async getComponent(name: string): Promise<Component | null> {
    const data = await this.#client.query<{ component: Component | null }>(
      EIP_QUERIES.GET_COMPONENT,
      { name }
    );
    return data.component;
  }

  /**
   * List all components
   */
  async listComponents(): Promise<Component[]> {
    const data = await this.#client.query<{ components: Component[] }>(
      EIP_QUERIES.LIST_COMPONENTS
    );
    return data.components;
  }

  // =========================================================================
  // Template Queries
  // =========================================================================

  /**
   * List route templates
   */
  async listRouteTemplates(): Promise<RouteTemplate[]> {
    const data = await this.#client.query<{ routeTemplates: RouteTemplate[] }>(
      EIP_QUERIES.LIST_ROUTE_TEMPLATES
    );
    return data.routeTemplates;
  }

  /**
   * Get a route template by ID
   */
  async getRouteTemplate(id: string): Promise<RouteTemplate | null> {
    const data = await this.#client.query<{ routeTemplate: RouteTemplate | null }>(
      EIP_QUERIES.GET_ROUTE_TEMPLATE,
      { id }
    );
    return data.routeTemplate;
  }

  // =========================================================================
  // Endpoint Queries
  // =========================================================================

  /**
   * List endpoints
   */
  async listEndpoints(component?: string): Promise<Endpoint[]> {
    const data = await this.#client.query<{ endpoints: Endpoint[] }>(
      EIP_QUERIES.LIST_ENDPOINTS,
      { component }
    );
    return data.endpoints;
  }

  // =========================================================================
  // Validation
  // =========================================================================

  /**
   * Validate a route definition
   */
  async validateRouteDefinition(yaml: string): Promise<ValidationResult> {
    const data = await this.#client.query<{ validateRouteDefinition: ValidationResult }>(
      EIP_QUERIES.VALIDATE_ROUTE_DEFINITION,
      { yaml }
    );
    return data.validateRouteDefinition;
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
  // Route CRUD Mutations
  // =========================================================================

  /**
   * Create a new route
   */
  async createRoute(input: CreateRouteInput): Promise<Route> {
    const data = await this.#client.mutation<{ createRoute: Route }>(
      EIP_MUTATIONS.CREATE_ROUTE,
      { input }
    );
    return data.createRoute;
  }

  /**
   * Create a route from YAML
   */
  async createRouteFromYaml(
    routeId: string,
    yaml: string,
    options: { description?: string; group?: string; autoStart?: boolean } = {}
  ): Promise<Route> {
    return this.createRoute({
      routeId,
      yaml,
      description: options.description,
      group: options.group,
      autoStart: options.autoStart,
    });
  }

  /**
   * Update an existing route
   */
  async updateRoute(input: UpdateRouteInput): Promise<Route> {
    const data = await this.#client.mutation<{ updateRoute: Route }>(
      EIP_MUTATIONS.UPDATE_ROUTE,
      { input }
    );
    return data.updateRoute;
  }

  /**
   * Delete a route
   */
  async deleteRoute(id: string): Promise<boolean> {
    const data = await this.#client.mutation<{ deleteRoute: boolean }>(
      EIP_MUTATIONS.DELETE_ROUTE,
      { id }
    );
    return data.deleteRoute;
  }

  /**
   * Create a route from a template
   */
  async createRouteFromTemplate(
    templateId: string,
    routeId: string,
    parameters?: Record<string, unknown>
  ): Promise<Route> {
    const data = await this.#client.mutation<{ createRouteFromTemplate: Route }>(
      EIP_MUTATIONS.CREATE_ROUTE_FROM_TEMPLATE,
      { templateId, routeId, parameters }
    );
    return data.createRouteFromTemplate;
  }

  // =========================================================================
  // Bulk Operations
  // =========================================================================

  /**
   * Start all routes
   */
  async startAllRoutes(): Promise<Route[]> {
    const data = await this.#client.mutation<{ startAllRoutes: Route[] }>(
      EIP_MUTATIONS.START_ALL_ROUTES
    );
    return data.startAllRoutes;
  }

  /**
   * Stop all routes
   */
  async stopAllRoutes(timeout?: number): Promise<Route[]> {
    const data = await this.#client.mutation<{ stopAllRoutes: Route[] }>(
      EIP_MUTATIONS.STOP_ALL_ROUTES,
      { timeout }
    );
    return data.stopAllRoutes;
  }

  // =========================================================================
  // Context Control Mutations
  // =========================================================================

  /**
   * Start the Camel context
   */
  async startCamelContext(): Promise<CamelContext> {
    const data = await this.#client.mutation<{ startCamelContext: CamelContext }>(
      EIP_MUTATIONS.START_CAMEL_CONTEXT
    );
    return data.startCamelContext;
  }

  /**
   * Stop the Camel context
   */
  async stopCamelContext(timeout?: number): Promise<CamelContext> {
    const data = await this.#client.mutation<{ stopCamelContext: CamelContext }>(
      EIP_MUTATIONS.STOP_CAMEL_CONTEXT,
      { timeout }
    );
    return data.stopCamelContext;
  }

  /**
   * Suspend the Camel context
   */
  async suspendCamelContext(timeout?: number): Promise<CamelContext> {
    const data = await this.#client.mutation<{ suspendCamelContext: CamelContext }>(
      EIP_MUTATIONS.SUSPEND_CAMEL_CONTEXT,
      { timeout }
    );
    return data.suspendCamelContext;
  }

  /**
   * Resume the Camel context
   */
  async resumeCamelContext(): Promise<CamelContext> {
    const data = await this.#client.mutation<{ resumeCamelContext: CamelContext }>(
      EIP_MUTATIONS.RESUME_CAMEL_CONTEXT
    );
    return data.resumeCamelContext;
  }

  // =========================================================================
  // Statistics Mutations
  // =========================================================================

  /**
   * Reset statistics for a route
   */
  async resetRouteStatistics(id: string): Promise<Route> {
    const data = await this.#client.mutation<{ resetRouteStatistics: Route }>(
      EIP_MUTATIONS.RESET_ROUTE_STATISTICS,
      { id }
    );
    return data.resetRouteStatistics;
  }

  /**
   * Reset all statistics
   */
  async resetAllStatistics(): Promise<CamelContext> {
    const data = await this.#client.mutation<{ resetAllStatistics: CamelContext }>(
      EIP_MUTATIONS.RESET_ALL_STATISTICS
    );
    return data.resetAllStatistics;
  }

  // =========================================================================
  // Debugging Mutations
  // =========================================================================

  /**
   * Send a test message to an endpoint
   */
  async sendToEndpoint(input: SendToEndpointInput): Promise<ExchangeResult> {
    const data = await this.#client.mutation<{ sendToEndpoint: ExchangeResult }>(
      EIP_MUTATIONS.SEND_TO_ENDPOINT,
      { input }
    );
    return data.sendToEndpoint;
  }

  /**
   * Send a simple message to an endpoint
   */
  async sendMessage(
    endpointUri: string,
    body: string,
    headers?: Record<string, unknown>
  ): Promise<ExchangeResult> {
    return this.sendToEndpoint({ endpointUri, body, headers });
  }

  /**
   * Enable tracing
   */
  async enableTracing(): Promise<CamelContext> {
    const data = await this.#client.mutation<{ enableTracing: CamelContext }>(
      EIP_MUTATIONS.ENABLE_TRACING
    );
    return data.enableTracing;
  }

  /**
   * Disable tracing
   */
  async disableTracing(): Promise<CamelContext> {
    const data = await this.#client.mutation<{ disableTracing: CamelContext }>(
      EIP_MUTATIONS.DISABLE_TRACING
    );
    return data.disableTracing;
  }

  // =========================================================================
  // Utility Methods
  // =========================================================================

  /**
   * Check if context is running
   */
  async isContextRunning(): Promise<boolean> {
    const ctx = await this.getCamelContext();
    return ctx.state === 'STARTED';
  }

  /**
   * Check if route is running
   */
  async isRouteRunning(id: string): Promise<boolean> {
    const route = await this.getRoute(id);
    return route?.status === 'STARTED';
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
        from: options.from,
        to: options.to,
        status: options.status,
        interval: options.interval,
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
   * Fetch the full detail of a single exchange (Inspector).
   */
  async getHistoryExchange(exchangeId: string): Promise<HistoryExchange | null> {
    const data = await this.#client.query<{ historyExchange: HistoryExchange | null }>(
      EIP_QUERIES.HISTORY_EXCHANGE,
      { exchangeId }
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

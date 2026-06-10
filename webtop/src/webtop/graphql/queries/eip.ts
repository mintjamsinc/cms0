/**
 * Enterprise Integration Patterns (Apache Camel) GraphQL Queries and Mutations
 */

// =============================================================================
// Fragments
// =============================================================================

export const ROUTE_BASIC_FIELDS = `
  fragment RouteBasicFields on Route {
    id
    routeId
    description
    group
    status
    uptime
    exchangesTotal
    exchangesCompleted
    exchangesFailed
    exchangesInflight
  }
`;

export const ROUTE_STATS_FIELDS = `
  fragment RouteStatsFields on Route {
    exchangesTotal
    exchangesCompleted
    exchangesFailed
    exchangesInflight
    meanProcessingTime
    maxProcessingTime
    minProcessingTime
    lastProcessingTime
    totalProcessingTime
  }
`;

export const CAMEL_CONTEXT_FIELDS = `
  fragment CamelContextFields on CamelContext {
    name
    version
    state
    uptime
    uptimeMillis
    exchangesTotal
    exchangesCompleted
    exchangesFailed
    exchangesInflight
    tracing
    messageHistory
    logMask
  }
`;

// =============================================================================
// Queries
// =============================================================================

export const EIP_QUERIES = {
  /** Get the Camel context */
  GET_CAMEL_CONTEXT: `
    query GetCamelContext {
      camelContext {
        name
        version
        state
        uptime
        uptimeMillis
        exchangesTotal
        exchangesCompleted
        exchangesFailed
        exchangesInflight
        meanProcessingTime
        maxProcessingTime
        minProcessingTime
        totalProcessingTime
        tracing
        messageHistory
        logMask
      }
    }
  `,

  /** Get all Camel contexts (multi-context setup) */
  GET_CAMEL_CONTEXTS: `
    query GetCamelContexts {
      camelContexts {
        name
        version
        state
        uptime
        uptimeMillis
        exchangesTotal
        exchangesCompleted
        exchangesFailed
        exchangesInflight
      }
    }
  `,

  /** Get a single route by ID */
  GET_ROUTE: `
    query GetRoute($id: ID!) {
      route(id: $id) {
        id
        routeId
        description
        group
        status
        uptime
        uptimeMillis
        exchangesTotal
        exchangesCompleted
        exchangesFailed
        exchangesInflight
        meanProcessingTime
        maxProcessingTime
        minProcessingTime
        lastProcessingTime
        totalProcessingTime
        lastError {
          exchangeId
          timestamp
          message
          stackTrace
          endpoint
        }
        firstExchangeCompletedTime
        lastExchangeCompletedTime
        firstExchangeFailureTime
        lastExchangeFailureTime
        definition {
          id
          xml
          yaml
        }
        endpoints {
          uri
          component
          state
          remote
          singleton
        }
        consumers {
          uri
          component
          state
        }
        producers {
          uri
          component
          state
        }
      }
    }
  `,

  /** List routes with filters */
  LIST_ROUTES: `
    query ListRoutes(
      $first: Int
      $after: String
      $status: RouteState
      $group: String
      $search: String
    ) {
      routes(
        first: $first
        after: $after
        status: $status
        group: $group
        search: $search
      ) {
        edges {
          node {
            id
            routeId
            description
            group
            status
            uptime
            exchangesTotal
            exchangesCompleted
            exchangesFailed
            exchangesInflight
            meanProcessingTime
            lastError {
              timestamp
              message
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

  /**
   * Dashboard route snapshot — one fetch that carries both the per-route
   * throughput/error stats AND the route's endpoint connectivity (remote
   * endpoint state). The cross-cutting Dashboard derives its EIP route,
   * throughput and external-connection panels from this single query so it
   * never has to issue an N+1 `getRoute` per route just to learn whether the
   * remote systems a route talks to are reachable.
   */
  DASHBOARD_ROUTES: `
    query DashboardRoutes($first: Int) {
      routes(first: $first) {
        edges {
          node {
            id
            routeId
            description
            group
            status
            health
            uptime
            uptimeMillis
            exchangesTotal
            exchangesCompleted
            exchangesFailed
            exchangesInflight
            meanProcessingTime
            maxProcessingTime
            lastExchangeFailureTime
            lastError {
              timestamp
              message
              endpoint
            }
            endpoints {
              uri
              component
              state
              remote
              health
            }
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

  /** Get a component by name */
  GET_COMPONENT: `
    query GetComponent($name: String!) {
      component(name: $name) {
        name
        state
        class
        supportedSchemes
      }
    }
  `,

  /** List all components */
  LIST_COMPONENTS: `
    query ListComponents {
      components {
        name
        state
        class
        supportedSchemes
      }
    }
  `,

  /** List route templates */
  LIST_ROUTE_TEMPLATES: `
    query ListRouteTemplates {
      routeTemplates {
        id
        description
        parameters {
          name
          description
          required
          defaultValue
        }
        definition {
          id
          xml
          yaml
        }
      }
    }
  `,

  /** Get a route template by ID */
  GET_ROUTE_TEMPLATE: `
    query GetRouteTemplate($id: ID!) {
      routeTemplate(id: $id) {
        id
        description
        parameters {
          name
          description
          required
          defaultValue
        }
        definition {
          id
          xml
          yaml
        }
      }
    }
  `,

  /** List endpoints */
  LIST_ENDPOINTS: `
    query ListEndpoints($component: String) {
      endpoints(component: $component) {
        uri
        component
        state
        remote
        singleton
        exchangesTotal
        exchangesCompleted
        exchangesFailed
      }
    }
  `,

  /** Validate a route definition */
  VALIDATE_ROUTE_DEFINITION: `
    query ValidateRouteDefinition($yaml: String!) {
      validateRouteDefinition(yaml: $yaml) {
        valid
        errors {
          line
          column
          message
          element
        }
        warnings {
          line
          column
          message
          element
        }
      }
    }
  `,

  /**
   * Banded exchange-count time series for the EIP Console graph.
   *
   * Routes may be filtered server-side; passing an empty / null list
   * aggregates across every route.
   *
   * Window — two modes:
   *   anchor — `at` (instant) + `buckets` (count). The right-edge bucket is the
   *            fixed wall-clock bucket containing `at`; the window walks back
   *            `buckets` whole `interval`s. Used by the live console so that
   *            between bucket boundaries only the right-edge bucket changes.
   *   window — legacy explicit `from`/`to` (used when `buckets` is omitted); the
   *            server auto-resolves the interval from the span.
   */
  ROUTE_STATS: `
    query RouteStats(
      $routes: [String!],
      $at: String,
      $buckets: Int,
      $from: String,
      $to: String,
      $status: String,
      $interval: String,
      $elapsedBoundaries: [Int!]
    ) {
      routeStats(
        routes: $routes,
        at: $at,
        buckets: $buckets,
        from: $from,
        to: $to,
        status: $status,
        interval: $interval,
        elapsedBoundaries: $elapsedBoundaries
      ) {
        anchor
        from
        to
        interval
        boundaries
        points {
          bucket
          bands
        }
      }
    }
  `,

  /** Lucene-backed search over exchange history (Relay-style cursor pagination). */
  HISTORY_EXCHANGES: `
    query HistoryExchanges(
      $first: Int,
      $after: String,
      $last: Int,
      $before: String,
      $routes: [String!],
      $status: String,
      $from: String,
      $to: String,
      $filter: String,
      $elapsedBands: [String!]
    ) {
      historyExchanges(
        first: $first,
        after: $after,
        last: $last,
        before: $before,
        routes: $routes,
        status: $status,
        from: $from,
        to: $to,
        filter: $filter,
        elapsedBands: $elapsedBands
      ) {
        edges {
          node {
            path
            exchangeId
            routeId
            status
            elapsed
            createdAt
            businessKey
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

  /**
   * Full detail for one exchange record (Inspector).
   *
   * Addressed by node `path` because a single exchange may have one record per
   * route it completed in (same exchangeId), making exchangeId ambiguous. The
   * legacy `exchangeId` argument is retained for callers that only have an id.
   */
  HISTORY_EXCHANGE: `
    query HistoryExchange($path: String, $exchangeId: String) {
      historyExchange(path: $path, exchangeId: $exchangeId) {
        path
        exchangeId
        routeId
        status
        elapsed
        createdAt
        completedAt
        exceptionType
        exceptionMessage
        businessKey
        bodyType
        bodySize
        headers
        steps {
          id
          endpointUri
          timeTaken
          offsetFromStart
          order
        }
      }
    }
  `,
} as const;

// =============================================================================
// Mutations
// =============================================================================

export const EIP_MUTATIONS = {
  /** Start a route */
  START_ROUTE: `
    mutation StartRoute($input: StartRouteInput!) {
      startRoute(input: $input) {
        id
        routeId
        status
        uptime
      }
    }
  `,

  /** Stop a route */
  STOP_ROUTE: `
    mutation StopRoute($input: StopRouteInput!) {
      stopRoute(input: $input) {
        id
        routeId
        status
      }
    }
  `,

  /** Suspend a route */
  SUSPEND_ROUTE: `
    mutation SuspendRoute($input: SuspendRouteInput!) {
      suspendRoute(input: $input) {
        id
        routeId
        status
      }
    }
  `,

  /** Resume a suspended route */
  RESUME_ROUTE: `
    mutation ResumeRoute($input: ResumeRouteInput!) {
      resumeRoute(input: $input) {
        id
        routeId
        status
        uptime
      }
    }
  `,

  /** Create a new route */
  CREATE_ROUTE: `
    mutation CreateRoute($input: CreateRouteInput!) {
      createRoute(input: $input) {
        id
        routeId
        description
        group
        status
        definition {
          id
          xml
          yaml
        }
      }
    }
  `,

  /** Update an existing route */
  UPDATE_ROUTE: `
    mutation UpdateRoute($input: UpdateRouteInput!) {
      updateRoute(input: $input) {
        id
        routeId
        description
        group
        status
        definition {
          id
          xml
          yaml
        }
      }
    }
  `,

  /** Delete a route */
  DELETE_ROUTE: `
    mutation DeleteRoute($id: ID!) {
      deleteRoute(id: $id)
    }
  `,

  /** Create a route from a template */
  CREATE_ROUTE_FROM_TEMPLATE: `
    mutation CreateRouteFromTemplate($templateId: ID!, $routeId: String!, $parameters: JSON) {
      createRouteFromTemplate(templateId: $templateId, routeId: $routeId, parameters: $parameters) {
        id
        routeId
        description
        group
        status
        definition {
          id
          xml
          yaml
        }
      }
    }
  `,

  /** Start all routes */
  START_ALL_ROUTES: `
    mutation StartAllRoutes {
      startAllRoutes {
        id
        routeId
        status
      }
    }
  `,

  /** Stop all routes */
  STOP_ALL_ROUTES: `
    mutation StopAllRoutes($timeout: Int) {
      stopAllRoutes(timeout: $timeout) {
        id
        routeId
        status
      }
    }
  `,

  /** Start the Camel context */
  START_CAMEL_CONTEXT: `
    mutation StartCamelContext {
      startCamelContext {
        name
        state
        uptime
      }
    }
  `,

  /** Stop the Camel context */
  STOP_CAMEL_CONTEXT: `
    mutation StopCamelContext($timeout: Int) {
      stopCamelContext(timeout: $timeout) {
        name
        state
      }
    }
  `,

  /** Suspend the Camel context */
  SUSPEND_CAMEL_CONTEXT: `
    mutation SuspendCamelContext($timeout: Int) {
      suspendCamelContext(timeout: $timeout) {
        name
        state
      }
    }
  `,

  /** Resume the Camel context */
  RESUME_CAMEL_CONTEXT: `
    mutation ResumeCamelContext {
      resumeCamelContext {
        name
        state
        uptime
      }
    }
  `,

  /** Reset statistics for a route */
  RESET_ROUTE_STATISTICS: `
    mutation ResetRouteStatistics($id: ID!) {
      resetRouteStatistics(id: $id) {
        id
        routeId
        exchangesTotal
        exchangesCompleted
        exchangesFailed
      }
    }
  `,

  /** Reset all statistics */
  RESET_ALL_STATISTICS: `
    mutation ResetAllStatistics {
      resetAllStatistics {
        name
        exchangesTotal
        exchangesCompleted
        exchangesFailed
      }
    }
  `,

  /** Send a test message to an endpoint (debugging) */
  SEND_TO_ENDPOINT: `
    mutation SendToEndpoint($input: SendToEndpointInput!) {
      sendToEndpoint(input: $input) {
        exchangeId
        success
        body
        headers
        exception
        processingTime
      }
    }
  `,

  /** Enable tracing */
  ENABLE_TRACING: `
    mutation EnableTracing {
      enableTracing {
        name
        tracing
      }
    }
  `,

  /** Disable tracing */
  DISABLE_TRACING: `
    mutation DisableTracing {
      disableTracing {
        name
        tracing
      }
    }
  `,
} as const;

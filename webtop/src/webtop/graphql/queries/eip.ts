/**
 * Enterprise Integration Patterns (Apache Camel) GraphQL Queries and Mutations
 */

// =============================================================================
// Queries
// =============================================================================

export const EIP_QUERIES = {
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
} as const;

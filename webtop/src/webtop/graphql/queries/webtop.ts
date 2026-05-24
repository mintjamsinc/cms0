/**
 * Webtop-specific GraphQL Queries
 *
 * Queries for discovering and loading webtop applications.
 */

// =============================================================================
// Queries
// =============================================================================

export const WEBTOP_QUERIES = {
  /**
   * List Webtop applications as a Relay-style cursor connection.
   *
   * The server discovers every app.yml descriptor under `path`, parses it, and
   * resolves the derived fields (icon fallback, index.html modified time) in a
   * single round trip. This replaces the legacy approach of issuing roughly
   * three requests per app.
   */
  LIST_APPS: `
    query ListApps($path: String!, $first: Int, $after: String) {
      apps(path: $path, first: $first, after: $after) {
        edges {
          node {
            identifier
            name
            title
            icon
            path
            relPath
            modified
            editor
            contentTypes
            enableStartMenu
            isAdminOnly
            singleton
            customWindowControls
            minimumWidth
            minimumHeight
            actions {
              identifier
              label
              icon
              title
              handler
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
} as const;

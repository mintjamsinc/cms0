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
   * Find all app.yml files under the apps directory using XPath.
   *
   * This query returns the paths of all app.yml files, which can then be
   * loaded individually to get the full app configuration.
   */
  FIND_APP_FILES: `
    query FindAppFiles($appsPath: String!, $first: Int, $after: String) {
      xpath(
        query: $appsPath
        first: $first
        after: $after
      ) {
        edges {
          node {
            path
            name
            nodeType
            modified
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
   * Get the content of a YAML file (app.yml).
   * The content is returned as a text file that needs to be parsed as YAML.
   */
  GET_FILE_CONTENT: `
    query GetFileContent($path: String!) {
      node(path: $path) {
        path
        name
        mimeType
        size
        downloadUrl
      }
    }
  `,

  /**
   * Check if a node exists at the given path.
   * Useful for checking icon.svg existence.
   */
  CHECK_NODE_EXISTS: `
    query CheckNodeExists($path: String!) {
      node(path: $path) {
        path
        name
      }
    }
  `,

  /**
   * Get basic info about the app directory (to get modified time of index.html).
   */
  GET_APP_INFO: `
    query GetAppInfo($path: String!) {
      node(path: $path) {
        path
        name
        modified
      }
    }
  `,
} as const;

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
            category
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

  /**
   * List the workspaces available in the repository. The system workspace
   * (the identity store) comes first, the rest in alphabetical order.
   * `current` marks the workspace the queried endpoint is bound to.
   * `state` and the engine blocks report the workspace's operational
   * health (see WorkspaceInfo).
   */
  WORKSPACES: `
    query Workspaces {
      workspaces {
        name
        displayName
        current
        system
        autoStart
        state
        stateMessage
        processEngine { enabled running }
        integrationEngine { enabled running }
      }
    }
  `,

  /**
   * Report the cluster topology of the queried workspace: whether the node
   * runs as part of a cluster, the identifier of the node serving the
   * request, and every registered member with the server's own
   * heartbeat-freshness judgement (`alive`). In standalone deployments
   * `enabled` is false and `members` is empty. Administrators only.
   */
  CLUSTER: `
    query Cluster {
      cluster {
        enabled
        nodeId
        members {
          nodeId
          hostName
          started
          lastHeartbeat
          alive
          self
        }
      }
    }
  `,
} as const;

// =============================================================================
// Mutations
// =============================================================================

export const WEBTOP_MUTATIONS = {
  /**
   * Start creating a workspace. Administrators only. Creation runs as a
   * background job (provisioning and content deployment can take minutes and
   * either step can fail), so this returns a `jobId` to watch via
   * `jobProgress(jobId)`; `errors` is non-null only for synchronous validation
   * failures.
   */
  CREATE_WORKSPACE: `
    mutation CreateWorkspace($input: CreateWorkspaceInput!) {
      createWorkspace(input: $input) {
        jobId
        status
        errors {
          field
          message
          code
        }
      }
    }
  `,

  /**
   * Start deleting a workspace including everything stored in it.
   * Administrators only. Deletion runs as a background job (stop the
   * workspace's services, wait for them to come fully down, then remove the
   * directory), so this returns a `jobId` to watch via `jobProgress(jobId)`.
   * The system workspace cannot be deleted.
   */
  DELETE_WORKSPACE: `
    mutation DeleteWorkspace($input: DeleteWorkspaceInput!) {
      deleteWorkspace(input: $input) {
        jobId
        status
        name
        errors {
          field
          message
          code
        }
      }
    }
  `,

  /**
   * Update a workspace's editable settings (display name, auto-start, and the
   * BPM/EIP engine switches) in one synchronous call. Administrators only.
   * Display name and auto-start take effect immediately; the engine switches
   * are read only when the workspace's services start, so the caller restarts
   * the workspace to apply them. Returns the refreshed workspace.
   */
  UPDATE_WORKSPACE: `
    mutation UpdateWorkspace($input: UpdateWorkspaceInput!) {
      updateWorkspace(input: $input) {
        workspace {
          name
          displayName
          current
          system
          autoStart
          state
          stateMessage
          processEngine { enabled running }
          integrationEngine { enabled running }
        }
        errors {
          field
          message
          code
        }
      }
    }
  `,

  /**
   * Start a stopped workspace's services. Administrators only. Runs as a
   * background job (provisioning and content deployment can take minutes), so
   * this returns a `jobId` to watch via `jobProgress(jobId)`.
   */
  START_WORKSPACE: `
    mutation StartWorkspace($input: StartWorkspaceInput!) {
      startWorkspace(input: $input) {
        jobId
        status
        name
        errors {
          field
          message
          code
        }
      }
    }
  `,

  /**
   * Stop a running workspace's services. Administrators only. Runs as a
   * background job. The system workspace and the workspace this session is
   * bound to cannot be stopped.
   */
  STOP_WORKSPACE: `
    mutation StopWorkspace($input: StopWorkspaceInput!) {
      stopWorkspace(input: $input) {
        jobId
        status
        name
        errors {
          field
          message
          code
        }
      }
    }
  `,

  /**
   * Restart a workspace's services. Administrators only. Runs as a background
   * job (stop, wait for full shutdown, then start). This is how the BPM/EIP
   * engine switches — read only at start time — are applied to a running
   * workspace. The system workspace and the workspace this session is bound to
   * cannot be restarted.
   */
  RESTART_WORKSPACE: `
    mutation RestartWorkspace($input: RestartWorkspaceInput!) {
      restartWorkspace(input: $input) {
        jobId
        status
        name
        errors {
          field
          message
          code
        }
      }
    }
  `,
} as const;

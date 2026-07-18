/**
 * Webtop Service (GraphQL)
 *
 * GraphQL-based service for loading and managing webtop applications.
 * Replaces the legacy GetApps.groovy endpoint.
 */

import { GraphQLClient } from '../graphql/client.js';
import { WEBTOP_MUTATIONS, WEBTOP_QUERIES } from '../graphql/queries/webtop.js';
import { UrlUtils } from '../utils/url.js';
import { Application } from './webtop-service.js';

// =============================================================================
// Types
// =============================================================================

export interface AppAction {
  identifier: string;
  label?: string;
  title?: string;
  icon?: string;
  handler?: string;
}

export interface ListAppsResult {
  total: number;
  hasMore: boolean;
  apps: Application[];
}

/**
 * Lifecycle state of a workspace's CMS services. The JCR workspace itself
 * is open in any state — a workspace that is not open is not listed.
 * STARTING means startup is still in progress (e.g. a freshly created
 * workspace, or one just discovered on another cluster node); FAILED means
 * the start threw and the workspace will not come up without intervention
 * (see `stateMessage`).
 */
export type WorkspaceState = 'ONLINE' | 'STARTING' | 'STOPPED' | 'FAILED';

/**
 * State of a per-workspace engine (process / integration engine).
 * `enabled` is the configuration switch (bpm.yml / eip.yml), `running` is
 * the actual state: enabled but not running means the engine failed to
 * start. `enabled` is null while the workspace's services are not running
 * (the configuration has not been read).
 */
export interface WorkspaceEngineInfo {
  enabled: boolean | null;
  running: boolean;
}

/** A repository workspace as reported by the `workspaces` query. */
export interface WorkspaceInfo {
  /** Workspace name; also the URL segment the workspace is served under. */
  name: string;
  /**
   * Human-friendly label for the workspace, or null when none is configured.
   * Editable; the UI falls back to {@link name} when it is null/empty.
   */
  displayName: string | null;
  /** Whether this is the workspace the queried endpoint is bound to. */
  current: boolean;
  /** Whether this is the system workspace (the identity store). */
  system: boolean;
  /**
   * Whether the workspace's services start automatically when the node boots.
   * Always true for the system workspace (auto-start does not apply to it).
   */
  autoStart: boolean;
  /** Lifecycle state of the workspace's CMS services on this node. */
  state: WorkspaceState;
  /** When `state` is FAILED, why the services failed to start; null otherwise. */
  stateMessage: string | null;
  /** State of the workspace's process engine (Camunda). */
  processEngine: WorkspaceEngineInfo;
  /** State of the workspace's integration engine (Apache Camel). */
  integrationEngine: WorkspaceEngineInfo;
}

export interface WorkspaceMutationError {
  field: string | null;
  message: string;
  code: string;
}

/**
 * Editable workspace settings for `updateWorkspace`. Only the fields that are
 * provided are written; omit a field to leave it unchanged. `displayName` and
 * `autoStart` apply immediately; `bpmEnabled` / `eipEnabled` apply on the next
 * workspace restart.
 */
export interface UpdateWorkspaceInput {
  name: string;
  displayName?: string | null;
  autoStart?: boolean;
  bpmEnabled?: boolean;
  eipEnabled?: boolean;
}

/**
 * Handle to the background job started by `createWorkspace` / `deleteWorkspace`.
 * Both operations run as JobManager jobs (they can take minutes and either can
 * fail), so the mutation returns immediately with a `jobId` the caller watches
 * via `eventHub.watchJobProgress(jobId, …)` for phase updates and the terminal
 * completed/failed event.
 */
export interface WorkspaceJobHandle {
  jobId: string;
  status: string;
}

/** A node registered in the cluster, as reported by the `cluster` query. */
export interface ClusterMember {
  /** Identifier under which the node appears in the cluster. */
  nodeId: string;
  /** Host name of the node, when it could be determined. */
  hostName: string | null;
  /** When the node joined (ISO-8601 date-time in UTC). */
  started: string;
  /** The node's last heartbeat (ISO-8601 date-time in UTC). */
  lastHeartbeat: string;
  /**
   * The server's own heartbeat-freshness judgement: a member whose
   * heartbeat went stale is presumed dead. Clients never re-derive this
   * from `lastHeartbeat`, so the staleness policy stays server-side.
   */
  alive: boolean;
  /** Whether this member is the node that served the request. */
  self: boolean;
}

/** Cluster topology of a workspace, as reported by the `cluster` query. */
export interface ClusterInfo {
  /** Whether the node runs as part of a cluster. */
  enabled: boolean;
  /** Identifier of the node serving the request. */
  nodeId: string | null;
  /** Registered members; empty in standalone deployments. */
  members: ClusterMember[];
}

/** Number of apps fetched per page from the `apps` connection. */
const APPS_PAGE_SIZE = 100;

interface AppActionNode {
  identifier: string;
  label?: string | null;
  title?: string | null;
  icon?: string | null;
  handler?: string | null;
}

interface AppNode {
  identifier: string;
  name: string;
  title?: string | null;
  icon?: string | null;
  path: string;
  relPath: string;
  modified?: string | null;
  editor?: boolean | null;
  contentTypes?: string[] | null;
  category?: string | null;
  enableStartMenu?: boolean | null;
  isAdminOnly?: boolean | null;
  singleton?: boolean | null;
  customWindowControls?: boolean | null;
  minimumWidth?: number | null;
  minimumHeight?: number | null;
  actions?: AppActionNode[] | null;
}

interface ListAppsQueryResult {
  apps: {
    edges: Array<{ node: AppNode; cursor: string }>;
    pageInfo: { hasNextPage: boolean; endCursor: string | null };
    totalCount: number;
  };
}

// =============================================================================
// Service
// =============================================================================

export class WebtopServiceGraphQL {
  #client: GraphQLClient;
  #appsPath: string;
  #isAdmin: boolean;

  constructor(client: GraphQLClient, options: { isAdmin?: boolean, rootPath?: string } = {}) {
    this.#client = client;
    this.#appsPath = options.rootPath ? options.rootPath + '/apps' : UrlUtils.getAppsPath();
    this.#isAdmin = options.isAdmin ?? false;
  }

  /**
   * Set admin mode (affects visibility of admin-only apps).
   */
  setAdminMode(isAdmin: boolean): void {
    this.#isAdmin = isAdmin;
  }

  /**
   * List all available applications.
   *
   * Issues the `apps` GraphQL query, which returns each app's full metadata in
   * a single Relay-style cursor connection (instead of the legacy ~3 requests
   * per app). The connection is paged at {@link APPS_PAGE_SIZE} apps per
   * request; all pages are accumulated. Admin-only apps are filtered out for
   * non-admin users.
   */
  async listApps(): Promise<ListAppsResult> {
    const apps: Application[] = [];

    try {
      let after: string | null = null;
      do {
        const result = await this.#client.query<ListAppsQueryResult>(
          WEBTOP_QUERIES.LIST_APPS,
          { path: this.#appsPath, first: APPS_PAGE_SIZE, after }
        );

        const connection = result.apps;
        for (const edge of connection.edges) {
          const app = this.#toApplication(edge.node);
          if (app) {
            apps.push(app);
          }
        }

        after = connection.pageInfo.hasNextPage ? connection.pageInfo.endCursor : null;
      } while (after);

      console.log('[WebtopServiceGraphQL] Loaded', apps.length, 'apps');

      return {
        total: apps.length,
        hasMore: false,
        apps,
      };
    } catch (error) {
      console.error('[WebtopServiceGraphQL] Failed to list apps:', error);
      throw error;
    }
  }

  /**
   * List the workspaces available in the repository. The system workspace
   * comes first, the rest in alphabetical order.
   */
  async listWorkspaces(): Promise<WorkspaceInfo[]> {
    const result = await this.#client.query<{ workspaces: WorkspaceInfo[] }>(
      WEBTOP_QUERIES.WORKSPACES,
      {}
    );
    return result.workspaces ?? [];
  }

  /**
   * Report the cluster topology of the queried workspace. In standalone
   * deployments `enabled` is false and `members` is empty, so callers can
   * state "running as a single node". Administrators only.
   */
  async getCluster(): Promise<ClusterInfo> {
    const result = await this.#client.query<{ cluster: ClusterInfo }>(
      WEBTOP_QUERIES.CLUSTER,
      {}
    );
    const cluster = result.cluster;
    return {
      enabled: !!cluster?.enabled,
      nodeId: cluster?.nodeId ?? null,
      members: cluster?.members ?? [],
    };
  }

  /**
   * Start creating a workspace. Administrators only. Creation runs as a
   * background job — provisioning and content deployment can take minutes and
   * either step can fail — so this returns a `jobId` as soon as the request is
   * accepted; watch `eventHub.watchJobProgress(jobId, …)` for the
   * `creating`/`starting` phases and the terminal completed/failed event.
   * Throws when the server rejects the request synchronously (privileges,
   * name, already exists).
   */
  async createWorkspace(name: string): Promise<WorkspaceJobHandle> {
    const result = await this.#client.query<{
      createWorkspace: { jobId: string | null; status: string | null; errors: WorkspaceMutationError[] | null };
    }>(WEBTOP_MUTATIONS.CREATE_WORKSPACE, { input: { name } });

    const payload = result.createWorkspace;
    if (payload.errors?.length) {
      throw new Error(payload.errors[0].message);
    }
    return { jobId: payload.jobId!, status: payload.status ?? 'queued' };
  }

  /**
   * Start deleting a workspace including everything stored in it.
   * Administrators only. Deletion runs as a background job — it stops the
   * workspace's services and waits for them to come fully down before removing
   * the directory (the `stopping` then `deleting` phases) — so this returns a
   * `jobId` to watch via `eventHub.watchJobProgress(jobId, …)`. Throws when the
   * server rejects the request synchronously.
   */
  async deleteWorkspace(name: string): Promise<WorkspaceJobHandle> {
    const result = await this.#client.query<{
      deleteWorkspace: { jobId: string | null; status: string | null; name: string | null; errors: WorkspaceMutationError[] | null };
    }>(WEBTOP_MUTATIONS.DELETE_WORKSPACE, { input: { name } });

    const payload = result.deleteWorkspace;
    if (payload.errors?.length) {
      throw new Error(payload.errors[0].message);
    }
    return { jobId: payload.jobId!, status: payload.status ?? 'queued' };
  }

  /**
   * Update a workspace's editable settings in one call. Administrators only.
   * Only the fields present in `input` are written. `displayName` and
   * `autoStart` take effect immediately; `bpmEnabled` / `eipEnabled` are read
   * only when the workspace's services start, so restart the workspace to
   * apply them. Returns the refreshed workspace. Throws when the server
   * rejects the request (privileges, not found).
   */
  async updateWorkspace(input: UpdateWorkspaceInput): Promise<WorkspaceInfo> {
    const result = await this.#client.query<{
      updateWorkspace: { workspace: WorkspaceInfo | null; errors: WorkspaceMutationError[] | null };
    }>(WEBTOP_MUTATIONS.UPDATE_WORKSPACE, { input });

    const payload = result.updateWorkspace;
    if (payload.errors?.length) {
      throw new Error(payload.errors[0].message);
    }
    return payload.workspace!;
  }

  /**
   * Start a stopped workspace's services. Administrators only. Runs as a
   * background job; watch `eventHub.watchJobProgress(jobId, …)`. Throws when
   * the server rejects the request synchronously.
   */
  async startWorkspace(name: string): Promise<WorkspaceJobHandle> {
    return this.#submitWorkspaceLifecycle('startWorkspace', WEBTOP_MUTATIONS.START_WORKSPACE, name);
  }

  /**
   * Stop a running workspace's services. Administrators only. Runs as a
   * background job. The system workspace and the bound workspace cannot be
   * stopped.
   */
  async stopWorkspace(name: string): Promise<WorkspaceJobHandle> {
    return this.#submitWorkspaceLifecycle('stopWorkspace', WEBTOP_MUTATIONS.STOP_WORKSPACE, name);
  }

  /**
   * Restart a workspace's services. Administrators only. Runs as a background
   * job (stop then start). Applies the BPM/EIP engine switches. The system
   * workspace and the bound workspace cannot be restarted.
   */
  async restartWorkspace(name: string): Promise<WorkspaceJobHandle> {
    return this.#submitWorkspaceLifecycle('restartWorkspace', WEBTOP_MUTATIONS.RESTART_WORKSPACE, name);
  }

  /** Shared body for the start/stop/restart lifecycle mutations. */
  async #submitWorkspaceLifecycle(field: string, mutation: string, name: string): Promise<WorkspaceJobHandle> {
    const result = await this.#client.query<Record<string, {
      jobId: string | null; status: string | null; name: string | null; errors: WorkspaceMutationError[] | null;
    }>>(mutation, { input: { name } });

    const payload = result[field];
    if (payload.errors?.length) {
      throw new Error(payload.errors[0].message);
    }
    return { jobId: payload.jobId!, status: payload.status ?? 'queued' };
  }

  /**
   * Convert an App node from the GraphQL response into an Application, applying
   * the admin-only visibility rule. Returns null when the app must be hidden.
   */
  #toApplication(node: AppNode): Application | null {
    if (node.isAdminOnly && !this.#isAdmin) {
      return null;
    }

    // Rebuild the action map keyed by identifier (the shape Application exposes).
    const actions: Record<string, AppAction> = {};
    for (const a of node.actions ?? []) {
      if (!a.identifier) {
        continue;
      }
      const action: AppAction = { identifier: a.identifier };
      if (a.label != null) action.label = a.label;
      if (a.title != null) action.title = a.title;
      if (a.icon != null) action.icon = a.icon;
      if (a.handler != null) action.handler = a.handler;
      actions[a.identifier] = action;
    }

    return new Application({
      identifier: node.identifier,
      name: node.name,
      title: node.title ?? undefined,
      appHome: node.path,
      relPath: node.relPath,
      icon: node.icon ?? undefined,
      // Preserve legacy behavior: default to "now" when no index.html timestamp.
      modified: node.modified ? new Date(node.modified).getTime() : Date.now(),
      // null means "unset" → undefined so the default (visible) start-menu rule applies.
      enableStartMenu: node.enableStartMenu ?? undefined,
      editor: node.editor ?? undefined,
      contentTypes: node.contentTypes ?? [],
      category: node.category ?? undefined,
      actions,
      minimumWidth: node.minimumWidth ?? undefined,
      minimumHeight: node.minimumHeight ?? undefined,
      customWindowControls: node.customWindowControls ?? undefined,
      singleton: node.singleton ?? undefined,
    });
  }

  /**
   * Broadcast a message to all app iframes and to the main window via CustomEvent.
   */
  postMessage(message: unknown): void {
    for (const iframe of document.querySelectorAll('iframe')) {
      iframe.contentWindow?.postMessage(message, window.location.origin);
    }
    // Allow the main window itself to react to broadcasted messages
    document.dispatchEvent(new CustomEvent('webtop-message', { detail: message }));
  }
}

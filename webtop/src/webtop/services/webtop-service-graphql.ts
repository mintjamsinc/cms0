/**
 * Webtop Service (GraphQL)
 *
 * GraphQL-based service for loading and managing webtop applications.
 * Replaces the legacy GetApps.groovy endpoint.
 */

import { GraphQLClient } from '../graphql/client.js';
import { WEBTOP_QUERIES } from '../graphql/queries/webtop.js';
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

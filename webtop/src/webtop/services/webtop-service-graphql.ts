/**
 * Webtop Service (GraphQL)
 *
 * GraphQL-based service for loading and managing webtop applications.
 * Replaces the legacy GetApps.groovy endpoint.
 */

import { GraphQLClient } from '../graphql/client.js';
import { WEBTOP_QUERIES } from '../graphql/queries/webtop.js';
import { CONTENT_QUERIES } from '../graphql/queries/content.js';
import { UrlUtils } from '../utils/url.js';
import { YamlParser } from '../utils/yaml.js';
import { Application } from './webtop-service.js';

// =============================================================================
// Types
// =============================================================================

export interface AppAction {
  identifier: string;
  title?: string;
  icon?: string;
  handler?: string;
}

export interface ListAppsResult {
  total: number;
  hasMore: boolean;
  apps: Application[];
}

interface NodeQueryResult {
  path: string;
  name: string;
  nodeType?: string;
  modified?: string;
  downloadUrl?: string;
}

interface XPathQueryResult {
  xpath: {
    edges: Array<{ node: NodeQueryResult; cursor: string }>;
    pageInfo: { hasNextPage: boolean; endCursor: string };
    totalCount: number;
  };
}

interface NodeResult {
  node: NodeQueryResult | null;
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
   * This method:
   * 1. Searches for all app.yml files using XPath
   * 2. Fetches and parses each app.yml file
   * 3. Checks for icon.svg existence
   * 4. Returns the complete app configurations
   */
  async listApps(): Promise<ListAppsResult> {
    const apps: Application[] = [];

    try {
      // Step 1: Find all app.yml files using XPath
      const xpathQuery = `/jcr:root${this.#appsPath}//element(app.yml,nt:file)`;
      console.log('[WebtopServiceGraphQL] Searching for apps:', xpathQuery);

      const result = await this.#client.query<XPathQueryResult>(
        CONTENT_QUERIES.XPATH,
        { query: xpathQuery, first: 1000 }
      );

      console.log('[WebtopServiceGraphQL] Found', result.xpath.totalCount, 'app.yml files');

      // Step 2: Process each app.yml file
      for (const edge of result.xpath.edges) {
        try {
          const app = await this.#processAppYml(edge.node);
          if (app) {
            apps.push(app);
          }
        } catch (error) {
          console.warn('[WebtopServiceGraphQL] Failed to process app:', edge.node.path, error);
        }
      }

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
   * Process a single app.yml file and return the application instance.
   */
  async #processAppYml(appYmlNode: NodeQueryResult): Promise<Application | null> {
    // app.yml is at: {appsPath}/.../{appName}/app.yml
    // Parent is the app directory; apps may live at any depth under appsPath.
    const appDirPath = appYmlNode.path.replace(/\/app\.yml$/, '');
    const appName = appDirPath.split('/').pop() || '';

    // Fetch and parse app.yml content
    const appYmlContent = await this.#fetchFileContent(appYmlNode.path);
    if (!appYmlContent) {
      console.warn('[WebtopServiceGraphQL] Failed to fetch app.yml:', appYmlNode.path);
      return null;
    }

    const appData = YamlParser.parse(appYmlContent) as Record<string, unknown>;

    // Check admin-only restriction
    if (appData.isAdminOnly && !this.#isAdmin) {
      console.log('[WebtopServiceGraphQL] Skipping admin-only app:', appDirPath);
      return null;
    }

    // Check for icon.svg (if not specified in app.yml)
    if (!appData.icon) {
      const iconSvgPath = `${appDirPath}/icon.svg`;
      if (await this.#nodeExists(iconSvgPath)) {
        appData.icon = 'icon.svg';
      }
    }

    // Get modified time from index.html if it exists
    let modified = Date.now();
    const indexHtmlPath = `${appDirPath}/index.html`;
    const indexHtmlInfo = await this.#getNodeInfo(indexHtmlPath);
    if (indexHtmlInfo?.modified) {
      modified = new Date(indexHtmlInfo.modified).getTime();
    }

    // Annotate each action with its key as identifier (mirrors legacy GetApps.groovy behavior)
    const actions = appData.actions as Record<string, unknown> | undefined;
    if (actions) {
      for (const [key, value] of Object.entries(actions)) {
        if (typeof value === 'object' && value !== null) {
          (value as AppAction).identifier = key;
        }
      }
    }

    // Compose the raw data shape consumed by Application's getters
    return new Application({
      ...appData,
      name: appName,
      appHome: appDirPath,
      relPath: appDirPath.substring(this.#appsPath.length + 1),
      modified,
    });
  }

  /**
   * Check if a node exists at the given path.
   */
  async #nodeExists(path: string): Promise<boolean> {
    try {
      const result = await this.#client.query<NodeResult>(
        WEBTOP_QUERIES.CHECK_NODE_EXISTS,
        { path }
      );
      return result.node !== null;
    } catch {
      return false;
    }
  }

  /**
   * Get basic node info.
   */
  async #getNodeInfo(path: string): Promise<NodeQueryResult | null> {
    try {
      const result = await this.#client.query<NodeResult>(
        WEBTOP_QUERIES.GET_APP_INFO,
        { path }
      );
      return result.node;
    } catch {
      return null;
    }
  }

  /**
   * Fetch the content of a file.
   */
  async #fetchFileContent(path: string): Promise<string | null> {
    try {
      // First, get the download URL
      const result = await this.#client.query<NodeResult>(
        WEBTOP_QUERIES.GET_FILE_CONTENT,
        { path }
      );

      if (!result.node?.downloadUrl) {
        return null;
      }

      // Fetch the actual content
      const response = await fetch(result.node.downloadUrl, {
        credentials: 'include',
      });

      if (!response.ok) {
        console.warn('[WebtopServiceGraphQL] Failed to fetch file:', path, response.status);
        return null;
      }

      return await response.text();
    } catch (error) {
      console.warn('[WebtopServiceGraphQL] Error fetching file:', path, error);
      return null;
    }
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

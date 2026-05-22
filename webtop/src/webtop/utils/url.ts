/**
 * URL Utilities
 *
 * Utility class for deriving API URLs from the current page URL.
 *
 * URL Pattern:
 *   https://example.com/bin/cms.cgi/{workspace}/content/webtop/index.html
 *
 * From this URL, we can derive:
 *   - Workspace: {workspace} (e.g., "system")
 *   - GraphQL API URL: /bin/graphql.cgi/{workspace}
 *   - SSE Stream URL: /bin/graphql.cgi/{workspace}/stream
 */

export interface UrlInfo {
  /** The workspace name extracted from URL (e.g., "system") */
  workspace: string;
  /** The base path to cms.cgi (e.g., "/bin/cms.cgi") */
  cmsBasePath: string;
  /** The webtop root path (e.g., "/system/webtop") */
  webtopRootPath: string;
  /** Full URL to the webtop root */
  webtopRootUrl: string;
  /** The webtop content path (e.g., "/content/webtop") */
  webtopContentPath: string;
}

export class UrlUtils {
  /**
   * Extract URL information from the current page URL.
   *
   * Expected URL format:
   *   /bin/cms.cgi/{workspace}/content/webtop/...
   *   /bin/cms.cgi/{workspace}/content/webtop/index.html
   *
   * @param url - Optional URL to parse (defaults to current location)
   * @returns UrlInfo object with extracted information
   */
  static getUrlInfo(url: string = window.location.href): UrlInfo {
    const parsedUrl = new URL(url, window.location.origin);
    const pathname = parsedUrl.pathname;

    // Match pattern: /bin/cms.cgi/{workspace}/content/webtop/...
    const match = pathname.match(/^(\/bin\/cms\.cgi)\/([^/]+)\/([^/]+)\/([^/]+)(\/.*)?$/);

    if (!match) {
      // Fallback to default workspace if pattern doesn't match
      console.warn('[UrlUtils] URL pattern not matched, using default workspace');
      return {
        workspace: 'system',
        cmsBasePath: '/bin/cms.cgi',
        webtopRootPath: '/system/content/webtop',
        webtopRootUrl: `${parsedUrl.origin}/bin/cms.cgi/system/content/webtop`,
        webtopContentPath: '/content/webtop',
      };
    }

    const cmsBasePath = match[1]; // /bin/cms.cgi
    const workspace = match[2]; // system, webpub, etc.
    const contentPath = match[3]; // content or other path segment
    const webtopPath = match[4]; // webtop

    return {
      workspace,
      cmsBasePath,
      webtopRootPath: `/${workspace}/${webtopPath}`,
      webtopRootUrl: `${parsedUrl.origin}${cmsBasePath}/${workspace}/${webtopPath}`,
      webtopContentPath: `/${contentPath}/${webtopPath}`,
    };
  }

  /**
   * Get the workspace name from the current URL.
   *
   * @param url - Optional URL to parse (defaults to current location)
   * @returns Workspace name (e.g., "system")
   */
  static getWorkspace(url?: string): string {
    return this.getUrlInfo(url).workspace;
  }

  /**
   * Get the GraphQL API endpoint URL for the current workspace.
   *
   * @param url - Optional URL to parse (defaults to current location)
   * @returns GraphQL API URL (e.g., "/bin/graphql.cgi/system")
   */
  static getGraphQLEndpoint(url?: string): string {
    const workspace = this.getWorkspace(url);
    return `/bin/graphql.cgi/${workspace}`;
  }

  /**
   * Get the SSE stream endpoint URL for the current workspace.
   *
   * @param url - Optional URL to parse (defaults to current location)
   * @returns SSE stream URL (e.g., "/bin/graphql.cgi/system/stream")
   */
  static getSSEEndpoint(url?: string): string {
    const workspace = this.getWorkspace(url);
    return `/bin/graphql.cgi/${workspace}/stream`;
  }

  /**
   * Get the apps directory path within the webtop.
   *
   * Note: The content structure does not include workspace name in the path.
   * Content is stored at /content/webtop/apps regardless of workspace.
   *
   * @param url - Optional URL to parse (defaults to current location)
   * @returns Apps directory path (e.g., "/content/webtop/apps")
   */
  static getAppsPath(url?: string): string {
    // Content path does not include workspace name
    return '/content/webtop/apps';
  }

  /**
   * Get the relative path from webtop root for a full content path.
   *
   * @param contentPath - Full content path (e.g., "/system/content/webtop/apps/content-browser")
   * @param url - Optional URL to parse (defaults to current location)
   * @returns Relative path (e.g., "content-browser")
   */
  static getRelativeAppPath(contentPath: string, url?: string): string {
    const appsPath = this.getAppsPath(url);
    if (contentPath.startsWith(appsPath + '/')) {
      return contentPath.substring(appsPath.length + 1);
    }
    return contentPath;
  }

  /**
   * Convert a relative app path to a URL for loading resources.
   *
   * @param relPath - Relative path within apps (e.g., "content-browser")
   * @returns URL path for loading (e.g., "./apps/content-browser")
   */
  static getAppResourceUrl(relPath: string): string {
    return `./apps/${relPath}`;
  }
}

export default UrlUtils;

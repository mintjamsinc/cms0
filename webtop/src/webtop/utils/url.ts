/**
 * URL Utilities
 *
 * Derives where the webtop is mounted from where it is served, instead of
 * assuming a fixed repository location. The caller passes the webtop root URL
 * — the shell uses the directory of its own bundle (webtop.js always sits at
 * the webtop root), i.e. `new URL('./', import.meta.url)`.
 *
 * The CMS serves repository content as `{cmsBasePath}/{workspace}{jcrPath}`,
 * so a root URL such as
 *   https://example.com/bin/cms.cgi/system/content/webtop/
 * splits into:
 *   - Workspace: "system"
 *   - Webtop root path (JCR): "/content/webtop"
 *
 * The webtop root may sit at any depth; only the CMS servlet mount
 * ({@link CMS_BASE_PATH}) is a fixed server contract.
 */

/** Mount point of the CMS content servlet. */
export const CMS_BASE_PATH = '/bin/cms.cgi';

export interface UrlInfo {
  /** The workspace the webtop is served from (e.g. "system") */
  workspace: string;
  /** The base path to cms.cgi (e.g. "/bin/cms.cgi") */
  cmsBasePath: string;
  /** JCR path of the webtop root within the workspace (e.g. "/content/webtop") */
  webtopRootPath: string;
  /** Absolute URL of the webtop root, with a trailing slash */
  webtopRootUrl: string;
}

export class UrlUtils {
  /**
   * Split a webtop root URL into its workspace and repository path.
   *
   * @param rootUrl - URL of the webtop root directory
   *   (e.g. "https://example.com/bin/cms.cgi/system/content/webtop/")
   * @returns UrlInfo describing where the webtop is mounted
   * @throws Error when the URL is not served by the CMS content servlet
   */
  static getUrlInfo(rootUrl: string | URL): UrlInfo {
    const parsedUrl = new URL('./', rootUrl);
    const pathname = decodeURI(parsedUrl.pathname);
    const prefix = CMS_BASE_PATH + '/';
    if (!pathname.startsWith(prefix)) {
      throw new Error(`[UrlUtils] Webtop is not served under ${CMS_BASE_PATH}: ${parsedUrl.href}`);
    }

    // {workspace}/{jcr path}/ — the trailing slash comes from resolving './'.
    const rest = pathname.substring(prefix.length);
    const slash = rest.indexOf('/');
    const workspace = rest.substring(0, slash);
    if (!workspace) {
      throw new Error(`[UrlUtils] Workspace missing from webtop URL: ${parsedUrl.href}`);
    }

    return {
      workspace,
      cmsBasePath: CMS_BASE_PATH,
      webtopRootPath: rest.substring(slash).replace(/\/$/, ''),
      webtopRootUrl: parsedUrl.href,
    };
  }

  /**
   * Get the URL of the webtop root in another workspace. The webtop is
   * deployed at the same repository path in every workspace.
   *
   * @param info - Location of the current webtop
   * @param workspace - Target workspace name
   * @returns Root-relative URL (e.g. "/bin/cms.cgi/webpub/content/webtop/")
   */
  static getWorkspaceRootUrl(info: UrlInfo, workspace: string): string {
    return `${info.cmsBasePath}/${encodeURIComponent(workspace)}${encodeURI(info.webtopRootPath)}/`;
  }

  /**
   * Get the apps directory path within the webtop.
   *
   * @param rootPath - JCR path of the webtop root (e.g. "/content/webtop")
   * @returns Apps directory path (e.g. "/content/webtop/apps")
   */
  static getAppsPath(rootPath: string): string {
    return `${rootPath}/apps`;
  }
}

export default UrlUtils;

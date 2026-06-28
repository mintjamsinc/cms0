/**
 * Content-version cache token for repository download URLs.
 *
 * Sibling to {@link ./build-version.ts}: where that stamps a single build-time
 * token onto bundled static assets, this stamps a *per-file* token onto a
 * download served straight from the repository (`/bin/download.cgi/...`), so the
 * browser can cache the bytes immutably and skip re-fetching them on the next
 * Webtop boot.
 */

/**
 * Append a content-version token to a repository download URL so the browser may
 * cache the bytes immutably across Webtop boots.
 *
 * The token is the file's last-modified time in **epoch milliseconds** — the very
 * value the download servlet derives its `ETag` from — so the URL becomes
 * *content-addressed*: it changes whenever the file is overwritten. The server
 * (`DownloadServlet` + `HttpCaching`) recognises a `?v=` that matches the live
 * last-modified time and replies `Cache-Control: private, max-age=1y, immutable`;
 * an unchanged file keeps the same URL and is served straight from cache with no
 * network round-trip, while a changed file gets a new URL and is re-fetched.
 *
 * `modified` is the ISO-8601 string GraphQL returns for `node.modified`;
 * {@link Date.parse} yields the same epoch-millisecond value the server compares
 * against. Falls back to the bare URL when no usable timestamp is available, in
 * which case the server keeps its revalidate-always policy (correct, just not
 * cached). The `?`/`&` separator is chosen so an existing query string is
 * preserved, matching how the rest of the shell extends download URLs.
 */
export function withContentVersion(downloadUrl: string, modified?: string): string {
	if (!downloadUrl || !modified) return downloadUrl;
	const millis = Date.parse(modified);
	if (Number.isNaN(millis)) return downloadUrl;
	const sep = downloadUrl.includes('?') ? '&' : '?';
	return `${downloadUrl}${sep}v=${millis}`;
}

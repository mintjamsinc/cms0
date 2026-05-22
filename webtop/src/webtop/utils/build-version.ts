// Build-time cache-busting token. The literal '__BUILD_VERSION__' is
// substituted by the rollup config's versionStampPlugin at build time, so
// every rebuild produces a fresh value. Append "?v=${BUILD_VERSION}" to
// asset URLs (static <link>/<script> hrefs are stamped by the HTML copy
// transform; this constant covers dynamic fetches and iframe sources).
export const BUILD_VERSION = '__BUILD_VERSION__';

// Convenience: returns "?v=<token>" for direct concatenation onto a URL
// that has no existing query string. For URLs that may already carry one,
// use the appendCacheBuster helper below.
export const CACHE_BUSTER = `?v=${BUILD_VERSION}`;

export function appendCacheBuster(url: string): string {
	if (!url) return url;
	const sep = url.includes('?') ? '&' : '?';
	return `${url}${sep}v=${BUILD_VERSION}`;
}

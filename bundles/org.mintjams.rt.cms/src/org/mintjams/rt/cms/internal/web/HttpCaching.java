/*
 * Copyright (c) 2022 MintJams Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.rt.cms.internal.web;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HTTP conditional-request and cache-control helper for raw (non-rendered)
 * resources streamed straight from the repository &mdash; downloads and the
 * wt-inspector preview.
 *
 * <p>Such resources are <em>validated</em> rather than blindly cached. Every
 * response carries an {@code ETag} and {@code Last-Modified} derived from the
 * stored content's modification time together with
 * {@code Cache-Control: no-cache}, so a client may keep a copy but must
 * revalidate before reusing it. When the client's validator still matches the
 * current content this collapses to a {@code 304 Not Modified} with no body;
 * when the file has been overwritten its modification time &mdash; and
 * therefore the ETag &mdash; changes, so the client is handed the fresh bytes.
 * This keeps previews and downloads in step with edits while sparing a full
 * re-download of unchanged files (which a blanket {@code no-store} forces, and
 * which a long {@code max-age} would instead leave stale).</p>
 *
 * <p>A request whose URL is <em>content-addressed</em> &mdash; it maps to one
 * fixed set of bytes &mdash; may instead opt in to immutable caching, served
 * with {@code Cache-Control: max-age=1y, immutable} and reused without
 * revalidation until the URL changes. Two callers qualify, by two different
 * proofs that the URL tracks its content:</p>
 * <ul>
 * <li><b>Build-versioned assets</b> via {@link
 * #applyAndCheckNotModified(HttpServletRequest, HttpServletResponse, long,
 * boolean)}: any non-empty {@code v} suffices, because the Webtop build stamps a
 * fresh {@code ?v=<token>} onto every asset reference on each rebuild, so a given
 * URL always maps to the same bytes. These are public static assets, cached
 * {@code public} so a shared cache may serve them too.</li>
 * <li><b>Path-addressed downloads and previews</b> via {@link
 * #applyAndCheckNotModifiedContentAddressed(HttpServletRequest,
 * HttpServletResponse, long)}: their stable URL <em>may</em> change in place, so
 * a bare {@code ?v=} proves nothing; the token is honoured only when it equals
 * the resource's own version (its last-modified time), and the response is then
 * cached {@code private}. A {@code ?v=} that does not track the content
 * therefore falls back to revalidate-always and can never pin a stale body, and
 * the {@code private} scope keeps a possibly access-controlled body out of
 * shared caches.</li>
 * </ul>
 */
public final class HttpCaching {

	private HttpCaching() {}

	/**
	 * Query parameter that marks a request URL as version-stamped, e.g.
	 * {@code app.js?v=ln3k9q}. The Webtop build writes this token onto every
	 * asset reference and changes it on every rebuild, so a URL bearing a
	 * non-empty {@code v} is content-addressed: its bytes do not change for a
	 * given value, and the response may therefore be cached immutably rather
	 * than revalidated on every use. Only callers that serve such versioned
	 * assets opt in (see the {@code allowImmutable} flag); the value itself is
	 * opaque.
	 */
	private static final String VERSION_PARAM = "v";

	/**
	 * Freshness lifetime for an immutable, version-stamped response: one year in
	 * seconds &mdash; the conventional ceiling for fingerprinted assets.
	 */
	private static final long IMMUTABLE_MAX_AGE_SECONDS = 31536000L;

	/**
	 * The strong validator for a representation whose freshness is keyed on its
	 * last-modified time. Quoted per RFC 7232 so it round-trips unchanged
	 * through {@code If-None-Match} and {@code If-Range}.
	 */
	public static String toETag(long lastModifiedMillis) {
		return "\"" + lastModifiedMillis + "\"";
	}

	/**
	 * Writes the validators ({@code ETag}, {@code Last-Modified}) and the
	 * revalidate-always cache policy ({@code Cache-Control: no-cache}) for a raw
	 * resource and, when the request already holds the current representation,
	 * completes it as {@code 304 Not Modified}.
	 *
	 * <p>The caller streams the entity only when this returns {@code false}; on
	 * {@code true} the status is already set and no body must be written.</p>
	 *
	 * <p>The pure revalidate-always baseline the other overloads build on:
	 * equivalent to {@link #applyAndCheckNotModified(HttpServletRequest,
	 * HttpServletResponse, long, boolean)} with immutable caching disabled. A
	 * caller whose URL may be content-addressed opts into immutable caching
	 * instead &mdash; {@link #applyAndCheckNotModifiedContentAddressed} for
	 * path-addressed resources that verify a per-content {@code ?v=} (downloads,
	 * previews), or the {@code allowImmutable} overload for build-stamped
	 * assets.</p>
	 *
	 * @return {@code true} when a 304 was sent; {@code false} when the caller
	 *         should proceed to write the entity.
	 */
	public static boolean applyAndCheckNotModified(
			HttpServletRequest request, HttpServletResponse response, long lastModifiedMillis) {
		return applyAndCheckNotModified(request, response, lastModifiedMillis, false);
	}

	/**
	 * As {@link #applyAndCheckNotModified(HttpServletRequest,
	 * HttpServletResponse, long)}, but when {@code allowImmutable} is set and the
	 * request URL is version-stamped (carries a non-empty {@code v} parameter,
	 * see {@link #VERSION_PARAM}) the response is marked immutably cacheable
	 * ({@code Cache-Control: public, max-age=1y, immutable}) instead of
	 * revalidate-always. Only a caller serving content-addressed build assets
	 * should pass {@code true}: such a URL changes whenever its bytes do, so the
	 * client may reuse it for a long period without a conditional request, and a
	 * later build refetches via a fresh URL. A version-less request always falls
	 * back to revalidation regardless of this flag.
	 *
	 * @param allowImmutable whether a version-stamped URL may be cached immutably
	 * @return {@code true} when a 304 was sent; {@code false} when the caller
	 *         should proceed to write the entity.
	 */
	public static boolean applyAndCheckNotModified(
			HttpServletRequest request, HttpServletResponse response, long lastModifiedMillis,
			boolean allowImmutable) {
		String eTag = toETag(lastModifiedMillis);
		response.setHeader("ETag", eTag);
		response.setDateHeader("Last-Modified", lastModifiedMillis);

		if (allowImmutable && isVersioned(request)) {
			// The URL carries an explicit version token (?v=...), so it changes
			// whenever the bytes do. Let the client keep it for a long period
			// without checking back; a new build hands the page fresh URLs that
			// miss the cache and refetch. 'immutable' additionally suppresses the
			// revalidation some browsers perform on an explicit reload.
			response.setHeader("Cache-Control", "public, max-age=" + IMMUTABLE_MAX_AGE_SECONDS + ", immutable");
		} else {
			// Stable URL whose bytes may change in place: keep a copy but check
			// back every time, collapsing to 304 while unchanged.
			response.setHeader("Cache-Control", "no-cache, must-revalidate");
			response.setHeader("Pragma", "no-cache");
			response.setHeader("Expires", "0");
		}

		if (isNotModified(request, lastModifiedMillis, eTag)) {
			// A 304 carries the validators set above but no entity headers/body.
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return true;
		}
		return false;
	}

	/**
	 * As {@link #applyAndCheckNotModified(HttpServletRequest, HttpServletResponse,
	 * long)}, but grants immutable caching when the request URL is
	 * <em>content-addressed</em> &mdash; its {@code v} query parameter equals this
	 * resource's own version token, the last-modified time in epoch milliseconds
	 * ({@code lastModifiedMillis}).
	 *
	 * <p>This is the safe counterpart to {@link
	 * #applyAndCheckNotModified(HttpServletRequest, HttpServletResponse, long,
	 * boolean)} for resources streamed straight from the repository under a stable
	 * path (downloads, previews) whose bytes may be overwritten in place. There,
	 * merely <em>carrying</em> a {@code ?v=} cannot prove the URL tracks content
	 * &mdash; a hardcoded or stale token would pin an out-of-date body &mdash; so
	 * this method instead <em>verifies</em> the token against the live content
	 * version and only then caches immutably. A token that does not match (absent,
	 * stale, or arbitrary) falls back to the revalidate-always policy, so an
	 * overwritten file is always served fresh: its version changes, the previous
	 * URL is never requested again, and a request bearing the old token no longer
	 * matches. The bytes returned are always the current ones &mdash; the token
	 * governs only the {@code Cache-Control} header, never which content is
	 * streamed.</p>
	 *
	 * <p>The immutable response is marked {@code private} rather than {@code public}:
	 * such a download is authenticated and may be access-controlled, so only the
	 * requesting user's own browser may retain it &mdash; never a shared or
	 * intermediary cache that could hand it to a different principal. Per-browser
	 * caching is all that boot performance needs, since a user's repeated launches
	 * reuse the same content-addressed URLs.</p>
	 *
	 * @param lastModifiedMillis the content's last-modified time in epoch millis;
	 *        also the version token a content-addressed URL must carry in {@code ?v=}
	 * @return {@code true} when a 304 was sent; {@code false} when the caller should
	 *         proceed to write the entity.
	 */
	public static boolean applyAndCheckNotModifiedContentAddressed(
			HttpServletRequest request, HttpServletResponse response, long lastModifiedMillis) {
		String eTag = toETag(lastModifiedMillis);
		response.setHeader("ETag", eTag);
		response.setDateHeader("Last-Modified", lastModifiedMillis);

		if (matchesVersion(request, lastModifiedMillis)) {
			// The ?v= token equals this resource's own version (its last-modified
			// time), so the URL is provably content-addressed: these exact bytes
			// never change for this URL. Let the client reuse it without revalidation
			// until the content — and therefore the URL — changes. 'private' keeps a
			// possibly access-controlled body out of shared caches; 'immutable'
			// additionally suppresses the revalidation some browsers do on reload.
			response.setHeader("Cache-Control", "private, max-age=" + IMMUTABLE_MAX_AGE_SECONDS + ", immutable");
		} else {
			// Stable URL whose bytes may change in place: keep a copy but check back
			// every time, collapsing to 304 while unchanged.
			response.setHeader("Cache-Control", "no-cache, must-revalidate");
			response.setHeader("Pragma", "no-cache");
			response.setHeader("Expires", "0");
		}

		if (isNotModified(request, lastModifiedMillis, eTag)) {
			// A 304 carries the validators set above but no entity headers/body.
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return true;
		}
		return false;
	}

	/**
	 * Whether the request URL is version-stamped &mdash; it carries a non-empty
	 * {@code v} query parameter (see {@link #VERSION_PARAM}). Only the presence
	 * of the parameter is significant; its value is opaque.
	 */
	private static boolean isVersioned(HttpServletRequest request) {
		String v = request.getParameter(VERSION_PARAM);
		return v != null && !v.isEmpty();
	}

	/**
	 * Whether the request's {@code v} parameter pins this resource's exact version
	 * &mdash; it equals {@code lastModifiedMillis} rendered as a decimal string.
	 * Unlike {@link #isVersioned}, which accepts any non-empty token (build-stamped
	 * assets carry an opaque build id), this requires the token to track the
	 * content, so a stale or arbitrary {@code ?v=} can never pin an outdated body.
	 */
	private static boolean matchesVersion(HttpServletRequest request, long lastModifiedMillis) {
		String v = request.getParameter(VERSION_PARAM);
		return v != null && v.equals(Long.toString(lastModifiedMillis));
	}

	/**
	 * Evaluates the request preconditions against the current validators.
	 * {@code If-None-Match} takes precedence over {@code If-Modified-Since}
	 * (RFC 7232 &sect;3.3). Returns {@code true} when the client's cached copy is
	 * still current.
	 */
	private static boolean isNotModified(HttpServletRequest request, long lastModifiedMillis, String eTag) {
		String ifNoneMatch = request.getHeader("If-None-Match");
		if (ifNoneMatch != null) {
			return matchesAny(ifNoneMatch, eTag);
		}

		long ifModifiedSince = parseDateHeader(request, "If-Modified-Since");
		if (ifModifiedSince != -1) {
			// HTTP dates carry second precision; compare at that granularity so a
			// sub-second last-modified is not mistaken for "newer" than an equal date.
			return floorSeconds(lastModifiedMillis) <= floorSeconds(ifModifiedSince);
		}

		return false;
	}

	/**
	 * Whether an {@code If-None-Match} field value matches the current ETag.
	 * Accepts the wildcard {@code *} and a comma-separated list, comparing with
	 * the weak comparison function (the {@code W/} prefix is not significant for
	 * {@code If-None-Match}).
	 */
	private static boolean matchesAny(String ifNoneMatch, String eTag) {
		String value = ifNoneMatch.trim();
		if (value.equals("*")) {
			return true;
		}
		String current = opaqueTag(eTag);
		for (String candidate : value.split(",")) {
			if (opaqueTag(candidate.trim()).equals(current)) {
				return true;
			}
		}
		return false;
	}

	private static String opaqueTag(String eTag) {
		if (eTag.startsWith("W/")) {
			return eTag.substring(2);
		}
		return eTag;
	}

	private static long parseDateHeader(HttpServletRequest request, String name) {
		try {
			return request.getDateHeader(name);
		} catch (IllegalArgumentException ex) {
			// A malformed conditional date is ignored rather than failing the request.
			return -1;
		}
	}

	private static long floorSeconds(long millis) {
		return millis / 1000L;
	}

}

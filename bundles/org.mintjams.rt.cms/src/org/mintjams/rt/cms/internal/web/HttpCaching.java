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
 */
public final class HttpCaching {

	private HttpCaching() {}

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
	 * @return {@code true} when a 304 was sent; {@code false} when the caller
	 *         should proceed to write the entity.
	 */
	public static boolean applyAndCheckNotModified(
			HttpServletRequest request, HttpServletResponse response, long lastModifiedMillis) {
		String eTag = toETag(lastModifiedMillis);
		response.setHeader("ETag", eTag);
		response.setDateHeader("Last-Modified", lastModifiedMillis);
		response.setHeader("Cache-Control", "no-cache, must-revalidate");
		response.setHeader("Pragma", "no-cache");
		response.setHeader("Expires", "0");

		if (isNotModified(request, lastModifiedMillis, eTag)) {
			// A 304 carries the validators set above but no entity headers/body.
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return true;
		}
		return false;
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

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

package org.mintjams.rt.cms.internal.security.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2Credentials;
import org.mintjams.tools.lang.Strings;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The cluster-portable authentication token: the authenticated state that
 * used to live only in the node-local HTTP session, carried in a cookie
 * encrypted with the cluster-shared secret key (AES/GCM via the CMS
 * encryptor, so the token is confidential and tamper-evident). Any node
 * that receives the cookie can restore the login, which removes the hard
 * dependency on sticky sessions: after a failover or a node switch the
 * user stays signed in.
 *
 * <p>The token is bearer-style: possession proves authentication until the
 * embedded expiry. Logging out clears the cookie on the client; there is
 * no server-side revocation list, so the lifetime should stay moderate.
 */
public final class AuthToken {

	public static final String COOKIE_NAME = "mintjams.cms.auth";

	public static final long DEFAULT_TTL_MILLIS = 12L * 60 * 60 * 1000;

	private static final ObjectMapper fObjectMapper = new ObjectMapper();

	private AuthToken() {}

	/**
	 * Issues the authentication cookie for the given login.
	 */
	public static void issue(HttpServletRequest request, HttpServletResponse response,
			Saml2Credentials credentials, String authenticatedFactors, long ttlMillis) {
		try {
			Map<String, Object> payload = new HashMap<>();
			payload.put("n", credentials.getName());
			payload.put("a", credentials.getAttributes());
			payload.put("f", authenticatedFactors);
			payload.put("e", System.currentTimeMillis() + ttlMillis);

			String token = Base64.getUrlEncoder().withoutPadding().encodeToString(
					CmsService.getEncryptor().encrypt(fObjectMapper.writeValueAsString(payload))
							.getBytes(StandardCharsets.UTF_8));

			setCookie(request, response, token, ttlMillis / 1000);
		} catch (Throwable ex) {
			// The login itself succeeded; without the cookie the session is
			// simply not portable across nodes.
			CmsService.getLogger(AuthToken.class)
					.error("An error occurred while issuing the authentication token.", ex);
		}
	}

	/**
	 * Restores the authenticated state from the request's authentication
	 * cookie, or returns {@code null} when there is none, it has expired,
	 * or it cannot be validated.
	 */
	public static Restored restore(HttpServletRequest request) {
		try {
			String token = getCookieValue(request);
			if (Strings.isEmpty(token)) {
				return null;
			}

			String decrypted = CmsService.getEncryptor().decrypt(
					new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));

			@SuppressWarnings("unchecked")
			Map<String, Object> payload = fObjectMapper.readValue(decrypted, Map.class);

			Object expires = payload.get("e");
			if (!(expires instanceof Number) || ((Number) expires).longValue() < System.currentTimeMillis()) {
				return null;
			}

			String name = (String) payload.get("n");
			if (Strings.isEmpty(name)) {
				return null;
			}

			@SuppressWarnings("unchecked")
			Map<String, List<String>> attributes = (Map<String, List<String>>) payload.get("a");

			return new Restored(new Saml2Credentials(name, attributes), (String) payload.get("f"));
		} catch (Throwable ignore) {
			// An invalid or foreign cookie is treated as no authentication.
			return null;
		}
	}

	/**
	 * Clears the authentication cookie on the client.
	 */
	public static void clear(HttpServletRequest request, HttpServletResponse response) {
		setCookie(request, response, "", 0);
	}

	private static String getCookieValue(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (COOKIE_NAME.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private static void setCookie(HttpServletRequest request, HttpServletResponse response, String value,
			long maxAgeSeconds) {
		// Written as a raw header: the servlet Cookie API predates SameSite.
		StringBuilder header = new StringBuilder()
				.append(COOKIE_NAME).append('=').append(value)
				.append("; Path=/")
				.append("; Max-Age=").append(maxAgeSeconds)
				.append("; HttpOnly")
				.append("; SameSite=Lax");
		if (request.isSecure()) {
			header.append("; Secure");
		}
		response.addHeader("Set-Cookie", header.toString());
	}

	/**
	 * Authenticated state restored from a token.
	 */
	public static class Restored {
		private final Saml2Credentials fCredentials;
		private final String fAuthenticatedFactors;

		private Restored(Saml2Credentials credentials, String authenticatedFactors) {
			fCredentials = credentials;
			fAuthenticatedFactors = authenticatedFactors;
		}

		public Saml2Credentials getCredentials() {
			return fCredentials;
		}

		public String getAuthenticatedFactors() {
			return fAuthenticatedFactors;
		}
	}

}

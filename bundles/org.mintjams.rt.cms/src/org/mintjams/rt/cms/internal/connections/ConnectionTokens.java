/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.rt.cms.internal.connections;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.lock.LockException;
import javax.jcr.lock.LockManager;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.script.ConsentRequiredException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Access tokens for linked accounts: the one thing an app asks for.
 *
 * <p>Only the refresh token is stored ({@link LinkedAccounts}). An access
 * token lives an hour or so; it is kept in memory on the node that obtained
 * it, keyed by user, provider, bundle and audience, and renewed from the
 * refresh token a minute before it expires. Losing the cache (a restart,
 * another node) costs one renewal.
 *
 * <p>Renewals of one account are serialized: in this JVM with a monitor, and
 * across the cluster with a lock on {@code /var/lock/oauth/<user>-<provider>},
 * because Microsoft hands out a new refresh token with every renewal and two
 * nodes renewing at once would each store their own. The stored token is read
 * again after the lock is taken, so a renewal another node just made is used
 * rather than repeated. When the lock cannot be taken in time the renewal
 * goes ahead anyway: a stale token is worse than a rare double renewal.
 *
 * <p>A renewal the provider refuses with {@code invalid_grant} (the user
 * revoked access at the provider, the grant expired, the password changed)
 * marks the account as needing a new link and reports it as consent required.
 * One refused because a scope of the bundle was never consented to (Microsoft
 * says {@code consent_required}: the bundle gained a scope after the account
 * was linked) takes the bundle off the account instead, so Preferences and
 * the apps offer to allow it again; the other bundles keep working.
 */
public final class ConnectionTokens {

	static final String LOCK_ROOT = "/var/lock/oauth";
	private static final long LOCK_TIMEOUT_SECONDS = 60;
	private static final long LOCK_WAIT_MILLIS = 10_000;
	private static final long EXPIRY_MARGIN_MILLIS = 60_000;
	private static final long DEFAULT_LIFETIME_SECONDS = 3600;

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER).build();

	private static final Map<String, Cached> fCache = new ConcurrentHashMap<>();
	private static final Map<String, Object> fMonitors = new ConcurrentHashMap<>();

	private ConnectionTokens() {}

	private static final class Cached {
		final String token;
		final long expiresAtMillis;

		Cached(String token, long expiresAtMillis) {
			this.token = token;
			this.expiresAtMillis = expiresAtMillis;
		}

		boolean isFresh(long now) {
			return now < expiresAtMillis - EXPIRY_MARGIN_MILLIS;
		}
	}

	/** What a token endpoint answered. */
	public static final class Tokens {
		private final String fAccessToken;
		private final String fRefreshToken;
		private final String fIdToken;
		private final long fExpiresInSeconds;
		private final List<String> fScopes;

		Tokens(String accessToken, String refreshToken, String idToken, long expiresInSeconds, List<String> scopes) {
			fAccessToken = accessToken;
			fRefreshToken = refreshToken;
			fIdToken = idToken;
			fExpiresInSeconds = expiresInSeconds;
			fScopes = scopes;
		}

		public String getAccessToken() {
			return fAccessToken;
		}

		/** Null when the provider did not send one. */
		public String getRefreshToken() {
			return fRefreshToken;
		}

		public String getIdToken() {
			return fIdToken;
		}

		public long getExpiresInSeconds() {
			return fExpiresInSeconds;
		}

		/** The scopes the provider says the token carries; empty when it did not say. */
		public List<String> getScopes() {
			return fScopes;
		}
	}

	/** The provider's answer to a request, with the OAuth error code when it refused. */
	public static final class ProviderException extends IOException {
		private static final long serialVersionUID = 1L;
		private final String fError;
		private final String fSuberror;

		ProviderException(String error, String suberror, String message) {
			super(message);
			fError = error;
			fSuberror = suberror;
		}

		public String getError() {
			return fError;
		}

		/** Microsoft's refinement of the error ({@code consent_required}, ...); null when there is none. */
		public String getSuberror() {
			return fSuberror;
		}
	}

	/**
	 * An access token of the user for the bundle's default audience, from the
	 * cache or renewed.
	 *
	 * @throws ConsentRequiredException when the user has to link, grant or link again
	 * @throws IOException when the provider could not be reached or refused
	 */
	public static String accessToken(String userId, String provider, String bundle)
			throws IOException, RepositoryException {
		return accessToken(userId, provider, bundle, null);
	}

	/**
	 * An access token of the user for one audience of the bundle (see
	 * {@link ConnectionProviders.Audience}); the bundle's first when
	 * {@code audience} is null.
	 *
	 * @throws ConsentRequiredException when the user has to link, grant or link again
	 * @throws IOException when the provider could not be reached or refused
	 */
	public static String accessToken(String userId, String provider, String bundle, String audience)
			throws IOException, RepositoryException {
		ConnectionProviders.Provider p = ConnectionProviders.get(provider);
		if (p == null) {
			throw new IllegalArgumentException("Unknown provider: " + provider);
		}
		if (!ConnectionProviders.isBundle(bundle)) {
			throw new IllegalArgumentException("Unknown bundle: " + bundle);
		}
		ConnectionProviders.Audience a = p.getAudience(bundle, audience);
		if (a == null) {
			throw new IllegalArgumentException("The " + bundle + " bundle of " + provider + " has no audience "
					+ audience + ".");
		}
		ConnectionClients.Client client = ConnectionClients.get().get(provider);
		if (client == null) {
			throw new ConsentRequiredException(provider, bundle, ConsentRequiredException.NOT_CONFIGURED);
		}
		LinkedAccounts.Account account = LinkedAccounts.get(userId, provider);
		if (account == null) {
			throw new ConsentRequiredException(provider, bundle, ConsentRequiredException.NOT_LINKED);
		}
		if (account.isNeedsReauth()) {
			throw new ConsentRequiredException(provider, bundle, ConsentRequiredException.REAUTHORIZE);
		}
		if (!account.getBundles().contains(bundle)) {
			throw new ConsentRequiredException(provider, bundle, ConsentRequiredException.BUNDLE_NOT_GRANTED);
		}

		String key = userId + "|" + provider + "|" + (p.isAudiencePerBundle() ? bundle + ":" + a.getName() : "");
		long now = System.currentTimeMillis();
		Cached cached = fCache.get(key);
		if (cached != null && cached.isFresh(now)) {
			return cached.token;
		}

		Object monitor = fMonitors.computeIfAbsent(userId + "|" + provider, k -> new Object());
		synchronized (monitor) {
			cached = fCache.get(key);
			if (cached != null && cached.isFresh(System.currentTimeMillis())) {
				return cached.token;
			}
			ClusterLock lock = ClusterLock.take(userId, provider);
			try {
				String refreshToken = LinkedAccounts.refreshToken(userId, provider);
				if (refreshToken == null) {
					LinkedAccounts.save(userId, account.withNeedsReauth(true), null);
					throw new ConsentRequiredException(provider, bundle, ConsentRequiredException.REAUTHORIZE);
				}
				Tokens tokens;
				try {
					tokens = refresh(client, refreshToken, p.isAudiencePerBundle() ? a.getScopes() : null);
				} catch (ProviderException ex) {
					if ("invalid_grant".equals(ex.getError()) && "consent_required".equals(ex.getSuberror())) {
						// The grant stands, but not for every scope of this bundle: it was
						// linked before the bundle gained one. The bundle has to be allowed
						// again; everything else keeps working.
						fCache.keySet().removeIf(k -> k.startsWith(userId + "|" + provider + "|" + bundle + ":"));
						Set<String> bundles = new LinkedHashSet<>(account.getBundles());
						bundles.remove(bundle);
						LinkedAccounts.save(userId, account.withBundles(bundles), null);
						CmsService.getLogger(ConnectionTokens.class).info(
								"The {} grant of {} does not cover the {} bundle any more; it has to be allowed again.",
								provider, userId, bundle);
						throw new ConsentRequiredException(provider, bundle, ConsentRequiredException.BUNDLE_NOT_GRANTED);
					}
					if ("invalid_grant".equals(ex.getError())) {
						invalidate(userId, provider);
						LinkedAccounts.save(userId, account.withNeedsReauth(true), null);
						CmsService.getLogger(ConnectionTokens.class).info(
								"The {} grant of {} is no longer accepted; the account needs a new link.", provider,
								userId);
						throw new ConsentRequiredException(provider, bundle, ConsentRequiredException.REAUTHORIZE);
					}
					throw ex;
				}
				long expiresAt = System.currentTimeMillis() + tokens.getExpiresInSeconds() * 1000;
				fCache.put(key, new Cached(tokens.getAccessToken(), expiresAt));
				// A rotated refresh token replaces the stored one; the record notes the use.
				LinkedAccounts.save(userId, account.withLastUsed(System.currentTimeMillis()),
						tokens.getRefreshToken());
				return tokens.getAccessToken();
			} finally {
				lock.release();
			}
		}
	}

	/** Forgets the cached tokens of an account: after unlinking, and after a failed renewal. */
	public static void invalidate(String userId, String provider) {
		fCache.keySet().removeIf(key -> key.startsWith(userId + "|" + provider + "|"));
	}

	// ---- the provider's endpoints ------------------------------------------------

	/**
	 * Redeems an authorization code. {@code scopes} names the scopes of the
	 * token when the provider needs them (Microsoft issues a token for one
	 * resource, while the authorization may have asked consent for several);
	 * null leaves the choice to the provider.
	 */
	public static Tokens exchangeCode(ConnectionClients.Client client, String code, String redirectUri,
			String codeVerifier, List<String> scopes) throws IOException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("redirect_uri", redirectUri);
		form.put("client_id", client.getClientId());
		if (!client.getClientSecret().isEmpty()) {
			form.put("client_secret", client.getClientSecret());
		}
		if (codeVerifier != null) {
			form.put("code_verifier", codeVerifier);
		}
		if (scopes != null && !scopes.isEmpty()) {
			form.put("scope", String.join(" ", scopes));
		}
		return tokens(client.getTokenEndpoint(), form);
	}

	/** Renews with a refresh token; {@code scopes} names the audience when the provider needs one. */
	public static Tokens refresh(ConnectionClients.Client client, String refreshToken, List<String> scopes)
			throws IOException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("refresh_token", refreshToken);
		form.put("client_id", client.getClientId());
		if (!client.getClientSecret().isEmpty()) {
			form.put("client_secret", client.getClientSecret());
		}
		if (scopes != null && !scopes.isEmpty()) {
			form.put("scope", String.join(" ", scopes));
		}
		return tokens(client.getTokenEndpoint(), form);
	}

	/**
	 * Tells the provider the grant is over, when it has an endpoint for that
	 * (Google; Microsoft has none). Best effort: a failure is logged, not thrown.
	 */
	public static void revoke(ConnectionClients.Client client, String refreshToken) {
		String endpoint = client.getRevocationEndpoint();
		if (endpoint == null || refreshToken == null) {
			return;
		}
		try {
			Map<String, String> form = new LinkedHashMap<>();
			form.put("token", refreshToken);
			form.put("client_id", client.getClientId());
			if (!client.getClientSecret().isEmpty()) {
				form.put("client_secret", client.getClientSecret());
			}
			HttpResponse<String> response = post(endpoint, form);
			if (response.statusCode() / 100 != 2) {
				CmsService.getLogger(ConnectionTokens.class).info("{} did not revoke the grant: HTTP {}",
						client.getProvider().getId(), response.statusCode());
			}
		} catch (Throwable ex) {
			CmsService.getLogger(ConnectionTokens.class).info("{} could not be told to revoke the grant: {}",
					client.getProvider().getId(), ex.toString());
		}
	}

	/** The OpenID userinfo of the account the access token belongs to. */
	public static JsonObject userinfo(ConnectionClients.Client client, String accessToken) throws IOException {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(client.getUserinfoEndpoint()))
				.timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + accessToken)
				.header("Accept", "application/json").GET().build();
		HttpResponse<String> response;
		try {
			response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while asking " + client.getProvider().getId() + " who the account is.");
		}
		if (response.statusCode() != 200) {
			throw new IOException(client.getProvider().getId() + " did not say who the account is: HTTP "
					+ response.statusCode());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	/** The claims of an ID token, read without verifying the signature: it came from the token endpoint over TLS. */
	public static JsonObject idTokenClaims(String idToken) {
		if (idToken == null) {
			return null;
		}
		String[] parts = idToken.split("\\.");
		if (parts.length < 2) {
			return null;
		}
		try {
			String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
			return JsonParser.parseString(json).getAsJsonObject();
		} catch (Throwable ex) {
			return null;
		}
	}

	private static Tokens tokens(String endpoint, Map<String, String> form) throws IOException {
		HttpResponse<String> response = post(endpoint, form);
		JsonObject json;
		try {
			json = JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (Throwable ex) {
			throw new IOException("The token endpoint answered HTTP " + response.statusCode() + " without JSON.");
		}
		if (response.statusCode() != 200 || json.has("error")) {
			String error = json.has("error") ? json.get("error").getAsString() : "http_" + response.statusCode();
			String description = json.has("error_description") ? json.get("error_description").getAsString() : "";
			throw new ProviderException(error, optional(json, "suberror"), "The token endpoint refused: " + error
					+ (description.isEmpty() ? "" : " (" + description + ")"));
		}
		if (!json.has("access_token")) {
			throw new IOException("The token endpoint sent no access token.");
		}
		long expiresIn = DEFAULT_LIFETIME_SECONDS;
		if (json.has("expires_in")) {
			try {
				expiresIn = json.get("expires_in").getAsLong();
			} catch (Throwable ex) {
				// Some providers send it as a string.
				expiresIn = Long.parseLong(json.get("expires_in").getAsString().trim());
			}
		}
		List<String> scopes = new ArrayList<>();
		if (json.has("scope") && !json.get("scope").isJsonNull()) {
			for (String scope : json.get("scope").getAsString().trim().split("\\s+")) {
				if (!scope.isEmpty()) {
					scopes.add(scope);
				}
			}
		}
		return new Tokens(json.get("access_token").getAsString(), optional(json, "refresh_token"),
				optional(json, "id_token"), expiresIn, scopes);
	}

	private static String optional(JsonObject json, String name) {
		JsonElement e = json.get(name);
		return (e == null || e.isJsonNull()) ? null : e.getAsString();
	}

	private static HttpResponse<String> post(String endpoint, Map<String, String> form) throws IOException {
		StringBuilder body = new StringBuilder();
		for (Map.Entry<String, String> e : form.entrySet()) {
			if (body.length() > 0) {
				body.append('&');
			}
			body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
					.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(endpoint)).timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		try {
			return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while calling " + endpoint);
		}
	}

	// ---- the cluster lock --------------------------------------------------------

	/**
	 * The lock on {@code /var/lock/oauth/<user>-<provider>}, held with a service
	 * session for the time of one renewal. {@link #release()} always succeeds.
	 */
	private static final class ClusterLock {
		private final Session fSession;
		private final String fPath;

		private ClusterLock(Session session, String path) {
			fSession = session;
			fPath = path;
		}

		static ClusterLock take(String userId, String provider) {
			String path = LOCK_ROOT + "/" + userId.replaceAll("[^A-Za-z0-9_.@-]", "_") + "-" + provider;
			Session session = null;
			try {
				session = CmsService.getRepository().login(new CmsServiceCredentials(), LinkedAccounts.SYSTEM_WORKSPACE);
				if (!session.nodeExists(path)) {
					Node folder = JCRs.getOrCreateFolder(JCRs.getOrCreateFolder(
							JCRs.getOrCreateFolder(session.getRootNode(), "var"), "lock"), "oauth");
					String name = path.substring(path.lastIndexOf('/') + 1);
					if (!JCRs.exists(folder, name)) {
						JCRs.createFile(folder, name);
					}
					session.save();
				}
				LockManager lockManager = session.getWorkspace().getLockManager();
				long deadline = System.currentTimeMillis() + LOCK_WAIT_MILLIS;
				for (;;) {
					try {
						lockManager.lock(path, false, true, LOCK_TIMEOUT_SECONDS, null);
						return new ClusterLock(session, path);
					} catch (LockException held) {
						if (System.currentTimeMillis() > deadline) {
							CmsService.getLogger(ConnectionTokens.class).info(
									"Renewing without the lock {}: another node has held it for over {} ms.", path,
									LOCK_WAIT_MILLIS);
							return new ClusterLock(session, null);
						}
						Thread.sleep(250);
					}
				}
			} catch (Throwable ex) {
				CmsService.getLogger(ConnectionTokens.class).info("Renewing without the lock {}: {}", path,
						ex.toString());
				return new ClusterLock(session, null);
			}
		}

		void release() {
			try {
				if (fPath != null && fSession != null) {
					fSession.getWorkspace().getLockManager().unlock(fPath);
				}
			} catch (Throwable ignore) {
				// The lock times out by itself.
			}
			try {
				if (fSession != null) {
					fSession.logout();
				}
			} catch (Throwable ignore) {
			}
		}
	}

	/** For the servlet: the scopes of an authorization request, identity first, then every scope of the bundle. */
	static List<String> scopesFor(ConnectionProviders.Provider provider, String bundle) {
		List<String> scopes = new ArrayList<>(provider.getIdentityScopes());
		if (bundle != null) {
			for (String scope : provider.getBundleScopes(bundle)) {
				if (!scopes.contains(scope)) {
					scopes.add(scope);
				}
			}
		}
		return scopes;
	}

	/**
	 * For the servlet: the scopes to name when redeeming the code. A provider
	 * that issues a token per resource gets the identity scopes and the
	 * bundle's first audience, the one the consent was asked for alongside;
	 * for the others the provider decides (null).
	 */
	static List<String> tokenScopesFor(ConnectionProviders.Provider provider, String bundle) {
		if (!provider.isAudiencePerBundle()) {
			return null;
		}
		List<String> scopes = new ArrayList<>(provider.getIdentityScopes());
		if (bundle != null) {
			ConnectionProviders.Audience audience = provider.getAudience(bundle, null);
			if (audience != null) {
				for (String scope : audience.getScopes()) {
					if (!scopes.contains(scope)) {
						scopes.add(scope);
					}
				}
			}
		}
		return scopes;
	}

	static List<String> split(String scopes) {
		if (scopes == null || scopes.isBlank()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(Arrays.asList(scopes.trim().split("\\s+")));
	}

}

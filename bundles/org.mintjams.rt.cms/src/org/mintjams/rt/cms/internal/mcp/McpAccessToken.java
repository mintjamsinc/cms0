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


package org.mintjams.rt.cms.internal.mcp;

import java.security.SecureRandom;

import org.mintjams.cms.security.Encryptor;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2Credentials;

import com.google.gson.JsonObject;

/**
 * The bearer token an MCP client presents in {@code Authorization: Bearer …}.
 *
 * <p>A client obtains it through the authorization flow (see {@link McpOAuth})
 * and renews it by itself; the user never sees it. The token carries the
 * user's identity exactly as the login established it, so every call runs as
 * that user and the repository ACLs decide what it can reach.
 *
 * <p>It is sealed with the cluster-shared secret key (see {@link McpSealed}),
 * so any node can verify it without shared state. What it adds to the identity
 * is what ties it to the connection the user turned on:
 *
 * <ul>
 * <li><b>workspace binding</b> — the token is only valid at the workspace it
 * was issued for;</li>
 * <li><b>generation</b> — the generation of the user's connection (see
 * {@link McpConnections}) at the time the client was authorized. Turning the
 * connection off ends that generation, and every token of it with it;</li>
 * <li><b>authorization time</b> — when the user approved the client, which
 * {@code mcp.yml#token.notBefore} can cut off for everyone at once.</li>
 * </ul>
 *
 * <p>The token is short-lived and says nothing about whether the client may
 * change content: that is read from the connection on every request, so a
 * change made in Preferences applies to the next call.
 *
 * <p>The payload deliberately shares no field name with the authentication
 * cookie's, and is tagged with its type: a token presented as the cookie fails
 * the cookie's validation, and the cookie presented as a token fails this one.
 */
public final class McpAccessToken {

	/** Makes the token recognisable in logs and secret scanners. */
	public static final String PREFIX = "mimcp_";

	private static final String TYPE = "mcp";
	private static final SecureRandom fRandom = new SecureRandom();

	private McpAccessToken() {}

	/**
	 * Issues a token for {@code credentials}, valid at {@code workspaceName} for
	 * {@code ttlSeconds} from {@code nowMillis}.
	 */
	public static Issued issue(Encryptor encryptor, Saml2Credentials credentials, String workspaceName,
			long generation, long authorizedAtMillis, long ttlSeconds, long nowMillis) {
		if (ttlSeconds <= 0) {
			throw new IllegalArgumentException("ttlSeconds must be positive");
		}

		byte[] idBytes = new byte[6];
		fRandom.nextBytes(idBytes);
		StringBuilder id = new StringBuilder();
		for (byte b : idBytes) {
			id.append(String.format("%02x", b & 0xff));
		}
		long expires = nowMillis + ttlSeconds * 1000L;

		JsonObject payload = new JsonObject();
		payload.addProperty("id", id.toString());
		McpSealed.putIdentity(payload, credentials);
		payload.addProperty("ws", workspaceName);
		payload.addProperty("gen", generation);
		payload.addProperty("aat", authorizedAtMillis);
		payload.addProperty("exp", expires);

		return new Issued(McpSealed.seal(encryptor, PREFIX, TYPE, payload), id.toString(), expires);
	}

	/**
	 * Validates {@code token} for a request to {@code workspaceName} at
	 * {@code nowMillis}. Whether the connection it belongs to is still on is the
	 * caller's check (see {@link Verified#getGeneration()}). The failure message
	 * is returned to the client, so it names the reason without echoing
	 * anything from the token.
	 */
	public static Verified verify(Encryptor encryptor, String token, String workspaceName, McpConfiguration config,
			long nowMillis) throws McpAuthException {
		if (token == null || !token.startsWith(PREFIX)) {
			throw new McpAuthException("The access token is not an MCP access token.");
		}

		JsonObject payload = McpSealed.open(encryptor, PREFIX, TYPE, token);
		if (payload == null) {
			throw new McpAuthException("The access token is invalid.");
		}

		String id;
		Saml2Credentials credentials;
		String workspace;
		long generation;
		long authorizedAt;
		long expires;
		try {
			id = payload.get("id").getAsString();
			credentials = McpSealed.getIdentity(payload);
			workspace = payload.get("ws").getAsString();
			generation = payload.get("gen").getAsLong();
			authorizedAt = payload.get("aat").getAsLong();
			expires = payload.get("exp").getAsLong();
		} catch (Throwable ex) {
			throw new McpAuthException("The access token is invalid.");
		}

		if (expires < nowMillis) {
			throw new McpAuthException("The access token has expired.");
		}
		if (authorizedAt < config.getTokensNotBeforeMillis()) {
			throw new McpAuthException("The connection has been revoked. Authorize the client again.");
		}
		if (!workspace.equals(workspaceName)) {
			throw new McpAuthException("The access token was not issued for this workspace.");
		}

		return new Verified(credentials, id, generation, expires);
	}

	/** A freshly issued token. It is stored nowhere on the server. */
	public static final class Issued {
		private final String fToken;
		private final String fId;
		private final long fExpiresMillis;

		private Issued(String token, String id, long expiresMillis) {
			fToken = token;
			fId = id;
			fExpiresMillis = expiresMillis;
		}

		public String getToken() {
			return fToken;
		}

		/** A short id that names the token in the log without revealing it. */
		public String getId() {
			return fId;
		}

		public long getExpiresMillis() {
			return fExpiresMillis;
		}
	}

	/** The identity and limits a valid token carries. */
	public static final class Verified {
		private final Saml2Credentials fCredentials;
		private final String fId;
		private final long fGeneration;
		private final long fExpiresMillis;

		private Verified(Saml2Credentials credentials, String id, long generation, long expiresMillis) {
			fCredentials = credentials;
			fId = id;
			fGeneration = generation;
			fExpiresMillis = expiresMillis;
		}

		public Saml2Credentials getCredentials() {
			return fCredentials;
		}

		public String getId() {
			return fId;
		}

		/** The generation of the connection the token was issued under. */
		public long getGeneration() {
			return fGeneration;
		}

		public long getExpiresMillis() {
			return fExpiresMillis;
		}
	}

}

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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mintjams.cms.security.Encryptor;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2Credentials;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The bearer token an MCP client presents in {@code Authorization: Bearer …}.
 *
 * <p>An MCP client is not a browser: it cannot follow the SAML redirect flow or
 * hold the session cookie. A signed-in user therefore issues a token for
 * themselves (see {@link McpTokenPage}) and hands it to the client. The token
 * carries the user's identity exactly as the login established it, so every
 * call runs as that user and the repository ACLs decide what it can reach.
 *
 * <p>Like the cluster-portable authentication cookie
 * ({@code AuthToken}), the token is the identity encrypted with the
 * cluster-shared secret key (AES/GCM through the CMS encryptor: confidential
 * and tamper-evident), so any node can verify it without shared state. It adds
 * what a credential handed to a third-party program needs:
 *
 * <ul>
 * <li><b>scope</b> — {@code read} confines the client to the read-only tools,
 * whatever the user's own privileges are;</li>
 * <li><b>workspace binding</b> — the token is only valid at the workspace it
 * was issued for;</li>
 * <li><b>revocation</b> — an id that {@code mcp.yml} can list as revoked, and
 * an issue time that {@code token.notBefore} can cut off.</li>
 * </ul>
 *
 * <p>The payload deliberately shares no field name with the authentication
 * cookie's, and is tagged {@code "t":"mcp"}: a token presented as the cookie
 * fails the cookie's validation, and the cookie presented as a token fails this
 * one. A read-only token therefore cannot be upgraded into a full session by
 * replaying it somewhere else.
 */
public final class McpAccessToken {

	/** Makes the token recognisable in logs and secret scanners. */
	public static final String PREFIX = "mjmcp_";

	public static final String SCOPE_READ = "read";
	public static final String SCOPE_WRITE = "write";

	private static final String TYPE = "mcp";
	private static final SecureRandom fRandom = new SecureRandom();

	private McpAccessToken() {}

	/**
	 * Issues a token for {@code credentials}, valid at {@code workspaceName} for
	 * {@code ttlSeconds} from {@code nowMillis}.
	 */
	public static Issued issue(Encryptor encryptor, Saml2Credentials credentials, String workspaceName, String scope,
			long ttlSeconds, long nowMillis) {
		if (!SCOPE_READ.equals(scope) && !SCOPE_WRITE.equals(scope)) {
			throw new IllegalArgumentException("Unknown scope: " + scope);
		}
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
		payload.addProperty("t", TYPE);
		payload.addProperty("id", id.toString());
		payload.addProperty("sub", credentials.getName());
		JsonObject attributes = new JsonObject();
		for (Map.Entry<String, List<String>> e : credentials.getAttributes().entrySet()) {
			JsonArray values = new JsonArray();
			if (e.getValue() != null) {
				for (String value : e.getValue()) {
					values.add(value);
				}
			}
			attributes.add(e.getKey(), values);
		}
		payload.add("attr", attributes);
		payload.addProperty("ws", workspaceName);
		payload.addProperty("scp", scope);
		payload.addProperty("iat", nowMillis);
		payload.addProperty("exp", expires);

		String token = PREFIX + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(encryptor.encrypt(payload.toString()).getBytes(StandardCharsets.UTF_8));
		return new Issued(token, id.toString(), scope, expires);
	}

	/**
	 * Validates {@code token} for a request to {@code workspaceName} at
	 * {@code nowMillis}. The failure message is returned to the client, so it
	 * names the reason without echoing anything from the token.
	 */
	public static Verified verify(Encryptor encryptor, String token, String workspaceName, McpConfiguration config,
			long nowMillis) throws McpAuthException {
		if (token == null || !token.startsWith(PREFIX)) {
			throw new McpAuthException("The access token is not an MCP access token.");
		}

		JsonObject payload;
		try {
			String decrypted = encryptor.decrypt(new String(
					Base64.getUrlDecoder().decode(token.substring(PREFIX.length())), StandardCharsets.UTF_8));
			payload = JsonParser.parseString(decrypted).getAsJsonObject();
		} catch (Throwable ex) {
			throw new McpAuthException("The access token is invalid.");
		}

		String id;
		String name;
		String workspace;
		String scope;
		long issued;
		long expires;
		Map<String, List<String>> attributes = new HashMap<>();
		try {
			if (!TYPE.equals(payload.get("t").getAsString())) {
				throw new IllegalArgumentException();
			}
			id = payload.get("id").getAsString();
			name = payload.get("sub").getAsString();
			workspace = payload.get("ws").getAsString();
			scope = payload.get("scp").getAsString();
			issued = payload.get("iat").getAsLong();
			expires = payload.get("exp").getAsLong();
			for (Map.Entry<String, JsonElement> e : payload.getAsJsonObject("attr").entrySet()) {
				List<String> values = new ArrayList<>();
				for (JsonElement value : e.getValue().getAsJsonArray()) {
					values.add(value.isJsonNull() ? null : value.getAsString());
				}
				attributes.put(e.getKey(), values);
			}
			if (name.isEmpty() || id.isEmpty()) {
				throw new IllegalArgumentException();
			}
			if (!SCOPE_READ.equals(scope) && !SCOPE_WRITE.equals(scope)) {
				throw new IllegalArgumentException();
			}
		} catch (Throwable ex) {
			throw new McpAuthException("The access token is invalid.");
		}

		if (expires < nowMillis) {
			throw new McpAuthException("The access token has expired. Issue a new one.");
		}
		if (issued < config.getTokensNotBeforeMillis() || config.getRevokedTokenIds().contains(id)) {
			throw new McpAuthException("The access token has been revoked.");
		}
		if (!workspace.equals(workspaceName)) {
			throw new McpAuthException("The access token was not issued for this workspace.");
		}
		if (SCOPE_WRITE.equals(scope) && !config.isWriteAllowed()) {
			throw new McpAuthException("Write-scoped access tokens are disabled on this server.");
		}

		return new Verified(new Saml2Credentials(name, attributes), id, scope, expires);
	}

	/** A freshly issued token. The token string is shown once and never stored. */
	public static final class Issued {
		private final String fToken;
		private final String fId;
		private final String fScope;
		private final long fExpiresMillis;

		private Issued(String token, String id, String scope, long expiresMillis) {
			fToken = token;
			fId = id;
			fScope = scope;
			fExpiresMillis = expiresMillis;
		}

		public String getToken() {
			return fToken;
		}

		/** The id to list under {@code token.revoked} to revoke this token. */
		public String getId() {
			return fId;
		}

		public String getScope() {
			return fScope;
		}

		public long getExpiresMillis() {
			return fExpiresMillis;
		}
	}

	/** The identity and limits a valid token carries. */
	public static final class Verified {
		private final Saml2Credentials fCredentials;
		private final String fId;
		private final String fScope;
		private final long fExpiresMillis;

		private Verified(Saml2Credentials credentials, String id, String scope, long expiresMillis) {
			fCredentials = credentials;
			fId = id;
			fScope = scope;
			fExpiresMillis = expiresMillis;
		}

		public Saml2Credentials getCredentials() {
			return fCredentials;
		}

		public String getId() {
			return fId;
		}

		public boolean canWrite() {
			return SCOPE_WRITE.equals(fScope);
		}

		public long getExpiresMillis() {
			return fExpiresMillis;
		}
	}

}

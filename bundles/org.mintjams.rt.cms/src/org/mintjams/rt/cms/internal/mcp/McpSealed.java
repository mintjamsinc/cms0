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
 * The envelope every credential of the MCP authorization flow travels in: a
 * JSON payload encrypted with the cluster-shared secret key (AES/GCM through
 * the CMS encryptor: confidential and tamper-evident), so any node can verify
 * what another node issued without shared state.
 *
 * <p>Each kind of credential — client registration, authorization code,
 * refresh token, access token — has its own prefix and its own {@code "t"}
 * tag, and is opened only as the kind it claims to be: an authorization code
 * presented as an access token fails, and so does the reverse.
 */
final class McpSealed {

	private McpSealed() {}

	static String seal(Encryptor encryptor, String prefix, String type, JsonObject payload) {
		payload.addProperty("t", type);
		return prefix + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(encryptor.encrypt(payload.toString()).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Opens a sealed value, or returns {@code null} when it is not one this
	 * server sealed as {@code type}.
	 */
	static JsonObject open(Encryptor encryptor, String prefix, String type, String value) {
		if (value == null || !value.startsWith(prefix)) {
			return null;
		}
		try {
			String sealed = new String(Base64.getUrlDecoder().decode(value.substring(prefix.length())),
					StandardCharsets.UTF_8);
			// The encryptor hands back anything that is not one of its envelopes
			// unchanged. Without this check a payload written in the clear would
			// be read as if this server had sealed it.
			if (!encryptor.isEncrypted(sealed)) {
				return null;
			}
			JsonObject payload = JsonParser.parseString(encryptor.decrypt(sealed)).getAsJsonObject();
			if (!type.equals(payload.get("t").getAsString())) {
				return null;
			}
			return payload;
		} catch (Throwable ex) {
			return null;
		}
	}

	/** Writes the user's identity, exactly as the login established it. */
	static void putIdentity(JsonObject payload, Saml2Credentials credentials) {
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
	}

	/** Reads the identity written by {@link #putIdentity}; throws when it is missing or malformed. */
	static Saml2Credentials getIdentity(JsonObject payload) {
		String name = payload.get("sub").getAsString();
		if (name.isEmpty()) {
			throw new IllegalArgumentException();
		}
		Map<String, List<String>> attributes = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : payload.getAsJsonObject("attr").entrySet()) {
			List<String> values = new ArrayList<>();
			for (JsonElement value : e.getValue().getAsJsonArray()) {
				values.add(value.isJsonNull() ? null : value.getAsString());
			}
			attributes.put(e.getKey(), values);
		}
		return new Saml2Credentials(name, attributes);
	}

}

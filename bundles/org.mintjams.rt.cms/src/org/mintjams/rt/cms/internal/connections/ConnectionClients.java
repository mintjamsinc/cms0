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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Session;

import org.mintjams.cms.config.ConfigValues;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * The OAuth clients this installation registered with the providers:
 * {@code /etc/oauth/clients.yml} in the system workspace, which only an
 * administrator can read or write.
 *
 * <pre>
 * google:
 *   clientID: ...
 *   clientSecret: ...          # in the clear, ENC[v1:...], or ${env.NAME}
 * microsoft:
 *   clientID: ...
 *   clientSecret: ...
 *   tenant: organizations      # organizations (default) | common | a tenant id
 *   endpoints:                 # optional, for a proxy or a test double
 *     authorization: https://...
 *     token: https://...
 *     userinfo: https://...
 * </pre>
 *
 * <p>Nothing is shipped: a provider without a client id here is not offered
 * to anyone. The file is re-read when it changes; no restart is needed. A
 * value may be written as {@code ${env.NAME}} (an environment variable, or
 * the file {@code NAME_FILE} names) or as {@code ENC[v1:...]} (encrypted
 * with the installation key), the way the other configuration files allow.
 */
public final class ConnectionClients {

	public static final String PATH = "/etc/oauth/clients.yml";
	private static final String SYSTEM_WORKSPACE = "system";

	private static final Pattern VARIABLE = Pattern.compile("\\$\\{(.+?)\\}");

	private static volatile ConnectionClients fCached;

	private final long fLastModified;
	private final String fError;
	private final Map<String, Client> fClients;

	private ConnectionClients(long lastModified, String error, Map<String, Client> clients) {
		fLastModified = lastModified;
		fError = error;
		fClients = clients;
	}

	/** The current configuration; an unreadable file yields one with an error and no clients. */
	public static ConnectionClients get() {
		Session session;
		try {
			session = CmsService.getRepository().login(new CmsServiceCredentials(), SYSTEM_WORKSPACE);
		} catch (Throwable ex) {
			return new ConnectionClients(-1, "The system workspace is not available.", Collections.emptyMap());
		}
		try {
			Node file;
			try {
				file = session.getNode(PATH);
			} catch (PathNotFoundException ex) {
				ConnectionClients cached = fCached;
				if (cached != null && cached.fLastModified == 0 && cached.fError == null) {
					return cached;
				}
				ConnectionClients none = new ConnectionClients(0, null, Collections.emptyMap());
				fCached = none;
				return none;
			}
			long lastModified = JCRs.getLastModified(file).getTime();
			ConnectionClients cached = fCached;
			if (cached != null && cached.fLastModified == lastModified) {
				return cached;
			}
			ConnectionClients loaded;
			try {
				Object parsed = new Load(LoadSettings.builder().build()).loadFromString(JCRs.getContentAsString(file));
				if (parsed != null && !(parsed instanceof Map)) {
					throw new IllegalArgumentException("the document is not a mapping");
				}
				loaded = new ConnectionClients(lastModified, null, parse((Map<?, ?>) parsed));
			} catch (Throwable ex) {
				CmsService.getLogger(ConnectionClients.class)
						.error("Invalid " + PATH + "; no account can be linked until it is fixed.", ex);
				loaded = new ConnectionClients(lastModified, "Invalid " + PATH + ": " + ex.getMessage(),
						Collections.emptyMap());
			}
			fCached = loaded;
			return loaded;
		} catch (Throwable ex) {
			CmsService.getLogger(ConnectionClients.class).error("Could not read " + PATH, ex);
			return new ConnectionClients(-1, "Could not read " + PATH + ".", Collections.emptyMap());
		} finally {
			session.logout();
		}
	}

	private static Map<String, Client> parse(Map<?, ?> root) {
		Map<String, Client> clients = new LinkedHashMap<>();
		if (root == null) {
			return clients;
		}
		for (ConnectionProviders.Provider provider : ConnectionProviders.all()) {
			Object section = root.get(provider.getId());
			if (!(section instanceof Map)) {
				continue;
			}
			Map<?, ?> m = (Map<?, ?>) section;
			String source = PATH + "#" + provider.getId();
			String clientId = resolve(m.get("clientID"), source + ".clientID");
			if (clientId == null || clientId.isBlank()) {
				continue;
			}
			String secret = resolve(m.get("clientSecret"), source + ".clientSecret");
			String tenant = resolve(m.get("tenant"), source + ".tenant");
			Map<String, String> endpoints = new LinkedHashMap<>();
			if (m.get("endpoints") instanceof Map) {
				for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("endpoints")).entrySet()) {
					String value = resolve(e.getValue(), source + ".endpoints." + e.getKey());
					if (value != null && !value.isBlank()) {
						endpoints.put(String.valueOf(e.getKey()), value.trim());
					}
				}
			}
			clients.put(provider.getId(), new Client(provider, clientId.trim(),
					(secret == null) ? "" : secret.trim(), tenant, endpoints));
		}
		return clients;
	}

	/**
	 * A configuration value: {@code ${env.NAME}} references replaced, then
	 * decrypted when it is an {@code ENC[...]} envelope. The error messages name
	 * the setting, never the value.
	 */
	static String resolve(Object raw, String source) {
		if (raw == null) {
			return null;
		}
		String value = String.valueOf(raw);
		Matcher matcher = VARIABLE.matcher(value);
		StringBuilder sb = new StringBuilder();
		while (matcher.find()) {
			String name = matcher.group(1).trim();
			String replacement = ConfigValues.isEnvironmentVariable(name)
					? ConfigValues.getEnvironmentVariable(name) : matcher.group(0);
			matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(sb);
		return ConfigValues.decrypt(sb.toString(), source);
	}

	/** Why the file could not be used, or null. */
	public String getError() {
		return fError;
	}

	/** The client for a provider, or null when the provider is not configured. */
	public Client get(String provider) {
		return (provider == null) ? null : fClients.get(provider);
	}

	/** The configured providers, in catalogue order. */
	public List<Client> all() {
		return new ArrayList<>(fClients.values());
	}

	public static final class Client {
		private final ConnectionProviders.Provider fProvider;
		private final String fClientId;
		private final String fClientSecret;
		private final String fTenant;
		private final Map<String, String> fEndpoints;

		private Client(ConnectionProviders.Provider provider, String clientId, String clientSecret, String tenant,
				Map<String, String> endpoints) {
			fProvider = provider;
			fClientId = clientId;
			fClientSecret = clientSecret;
			fTenant = tenant;
			fEndpoints = endpoints;
		}

		public ConnectionProviders.Provider getProvider() {
			return fProvider;
		}

		public String getClientId() {
			return fClientId;
		}

		public String getClientSecret() {
			return fClientSecret;
		}

		public String getTenant() {
			return fTenant;
		}

		public String getAuthorizationEndpoint() {
			return fEndpoints.getOrDefault("authorization", fProvider.getAuthorizationEndpoint(fTenant));
		}

		public String getTokenEndpoint() {
			return fEndpoints.getOrDefault("token", fProvider.getTokenEndpoint(fTenant));
		}

		public String getUserinfoEndpoint() {
			return fEndpoints.getOrDefault("userinfo", fProvider.getUserinfoEndpoint(fTenant));
		}

		public String getRevocationEndpoint() {
			return fEndpoints.getOrDefault("revocation", fProvider.getRevocationEndpoint(fTenant));
		}
	}

}

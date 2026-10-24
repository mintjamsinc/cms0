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

package org.mintjams.script;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.connections.ConnectionClients;
import org.mintjams.rt.cms.internal.connections.ConnectionProviders;
import org.mintjams.rt.cms.internal.connections.ConnectionTokens;
import org.mintjams.rt.cms.internal.connections.LinkedAccounts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;

/**
 * The linked accounts of the user a script runs as: the {@code Connections}
 * binding.
 *
 * <pre>
 * String token = Connections.accessToken('google', 'mail');            // XOAUTH2, Drive, ...
 * String graph = Connections.accessToken('microsoft', 'mail', 'graph'); // the Graph API, same consent
 * </pre>
 *
 * <p>{@link #accessToken} returns a token from the cache or renews one; when
 * the user has to act first (no account linked, the bundle not granted, or
 * the provider no longer honouring the grant) it throws
 * {@link ConsentRequiredException}, whose message starts with
 * {@code consent_required}, so the app can open the grant page
 * ({@code /bin/auth.cgi/oauth/authorize?provider=&bundle=}) and try again.
 * Tokens are never written anywhere by the script; only used.
 */
public class Connections {

	private final WorkspaceScriptContext fContext;

	public Connections(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static Connections get(ScriptingContext context) {
		return (Connections) context.getAttribute(Connections.class.getSimpleName());
	}

	private String userId() {
		Session session = fContext.adaptTo(Session.class);
		if (session == null) {
			throw new IllegalStateException("No session.");
		}
		return session.getUserID();
	}

	/** The providers an administrator configured: google, microsoft. */
	public List<String> providers() {
		List<String> ids = new ArrayList<>();
		for (ConnectionClients.Client client : ConnectionClients.get().all()) {
			ids.add(client.getProvider().getId());
		}
		return ids;
	}

	/** The bundles an app may ask for: mail, files, contacts, calendar. */
	public List<String> bundles() {
		return new ArrayList<>(ConnectionProviders.BUNDLES);
	}

	/**
	 * The user's linked account of the provider, or null: provider, subject,
	 * email, displayName, bundles, linkedAt, lastUsed, needsReauth.
	 */
	public Map<String, Object> account(String provider) throws RepositoryException {
		LinkedAccounts.Account account = LinkedAccounts.get(userId(), provider);
		if (account == null) {
			return null;
		}
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("provider", account.getProvider());
		map.put("subject", account.getSubject());
		map.put("email", account.getEmail());
		map.put("displayName", account.getDisplayName());
		map.put("bundles", new ArrayList<>(account.getBundles()));
		map.put("linkedAt", account.getLinkedAtMillis());
		map.put("lastUsed", account.getLastUsedMillis());
		map.put("needsReauth", account.isNeedsReauth());
		return map;
	}

	/** Whether the user has the bundle of the provider, usable right now. */
	public boolean has(String provider, String bundle) throws RepositoryException {
		LinkedAccounts.Account account = LinkedAccounts.get(userId(), provider);
		return account != null && !account.isNeedsReauth() && account.getBundles().contains(bundle);
	}

	/**
	 * An access token of the user for the bundle.
	 *
	 * @throws ConsentRequiredException when the user has to link, grant or link again
	 * @throws IOException when the provider could not be reached or refused
	 */
	public String accessToken(String provider, String bundle) throws IOException, RepositoryException {
		return ConnectionTokens.accessToken(userId(), provider, bundle);
	}

	/**
	 * An access token of the user for one audience of the bundle. Microsoft
	 * issues a token per resource: the {@code mail} bundle has {@code outlook}
	 * (IMAP and SMTP, the default) and {@code graph} (sending through the
	 * Graph API); the other bundles have {@code graph} only. Google's bundles
	 * have one audience each, so the name may be left null.
	 *
	 * @throws ConsentRequiredException when the user has to link, grant or link again
	 * @throws IOException when the provider could not be reached or refused
	 * @throws IllegalArgumentException when the bundle has no such audience
	 */
	public String accessToken(String provider, String bundle, String audience) throws IOException, RepositoryException {
		return ConnectionTokens.accessToken(userId(), provider, bundle, audience);
	}

	/**
	 * The base URL of a provider API, as the administrator may have overridden
	 * it in {@code clients.yml} ({@code endpoints:}), or {@code fallback}: the
	 * Graph API is {@code endpoint('microsoft', 'graph', 'https://graph.microsoft.com/v1.0')}.
	 * Null when the provider is not configured.
	 */
	public String endpoint(String provider, String name, String fallback) {
		ConnectionClients.Client client = ConnectionClients.get().get(provider);
		if (client == null) {
			return null;
		}
		String url = client.getEndpoint(name, fallback);
		while (url != null && url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

}

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

package org.mintjams.rt.cms.internal.graphql.wiring;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.connections.ConnectionClients;
import org.mintjams.rt.cms.internal.connections.ConnectionProviders;
import org.mintjams.rt.cms.internal.connections.ConnectionTokens;
import org.mintjams.rt.cms.internal.connections.ConnectionsServlet;
import org.mintjams.rt.cms.internal.connections.LinkedAccounts;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;
import org.mintjams.rt.cms.internal.util.ISO8601;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

/**
 * The linked-accounts fields: what Preferences shows and does. Like every
 * platform field, they run in the workspace the call came to, under the
 * caller's session: the accounts are the ones in the caller's home there.
 * Linking stores them in the system workspace and tokens are renewed from
 * there, so the Webtop asks the system workspace's endpoint, as it does for
 * the IdP.
 */
public final class PlatformConnectionsWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/connections-schema.graphqls";

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "linkedAccounts",
						(DataFetcher<Object>) PlatformConnectionsWiringContributor::linkedAccounts)
				.dataFetcher("Mutation", "unlinkAccount",
						(DataFetcher<Object>) PlatformConnectionsWiringContributor::unlinkAccount)
				.dataFetcher("Mutation", "revokeLinkedAccountBundle",
						(DataFetcher<Object>) PlatformConnectionsWiringContributor::revokeBundle);
	}

	private static Object linkedAccounts(DataFetchingEnvironment environment) throws Exception {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		ConnectionClients clients = ConnectionClients.get();
		Map<String, Object> map = new LinkedHashMap<>();
		List<Map<String, Object>> providers = new ArrayList<>();
		for (ConnectionClients.Client client : clients.all()) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("id", client.getProvider().getId());
			p.put("name", client.getProvider().getDisplayName());
			providers.add(p);
		}
		map.put("providers", providers);
		List<Map<String, Object>> accounts = new ArrayList<>();
		if (isUser(context.getCallerSession())) {
			for (LinkedAccounts.Account account : LinkedAccounts.list(context.getCallerSession())) {
				accounts.add(map(account));
			}
		}
		map.put("accounts", accounts);
		map.put("bundles", ConnectionProviders.BUNDLES);
		map.put("authorizePath", ConnectionsServlet.PATH + "/" + ConnectionsServlet.AUTHORIZE);
		map.put("configurationError", clients.getError());
		return map;
	}

	private static Object unlinkAccount(DataFetchingEnvironment environment) throws Exception {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		Session session = context.getCallerSession();
		if (!isUser(session)) {
			throw new IllegalStateException("Only a signed-in user has linked accounts.");
		}
		String provider = environment.getArgument("provider");
		if (ConnectionProviders.get(provider) == null) {
			throw new IllegalArgumentException("Unknown provider: " + provider);
		}
		String userId = session.getUserID();
		// Tell the provider first, while the grant is still there to be named.
		ConnectionClients.Client client = ConnectionClients.get().get(provider);
		if (client != null) {
			ConnectionTokens.revoke(client, LinkedAccounts.refreshToken(session, provider));
		}
		ConnectionTokens.invalidate(userId, provider);
		boolean removed = LinkedAccounts.remove(session, provider);
		if (removed) {
			CmsService.getLogger(PlatformConnectionsWiringContributor.class).info("{} account unlinked: user={}",
					provider, userId);
		}
		return removed;
	}

	private static Object revokeBundle(DataFetchingEnvironment environment) throws Exception {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		Session session = context.getCallerSession();
		if (!isUser(session)) {
			throw new IllegalStateException("Only a signed-in user has linked accounts.");
		}
		String provider = environment.getArgument("provider");
		String bundle = environment.getArgument("bundle");
		if (ConnectionProviders.get(provider) == null) {
			throw new IllegalArgumentException("Unknown provider: " + provider);
		}
		if (!ConnectionProviders.isBundle(bundle)) {
			throw new IllegalArgumentException("Unknown bundle: " + bundle);
		}
		String userId = session.getUserID();
		LinkedAccounts.Account account = LinkedAccounts.get(session, provider);
		if (account == null) {
			throw new IllegalStateException("No " + provider + " account is linked.");
		}
		Set<String> bundles = new LinkedHashSet<>(account.getBundles());
		if (bundles.remove(bundle)) {
			account = account.withBundles(bundles);
			LinkedAccounts.save(session, account, null);
			ConnectionTokens.invalidate(userId, provider);
			CmsService.getLogger(PlatformConnectionsWiringContributor.class).info(
					"{} bundle {} revoked: user={}", provider, bundle, userId);
		}
		return map(account);
	}

	private static Map<String, Object> map(LinkedAccounts.Account account) {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("provider", account.getProvider());
		a.put("subject", account.getSubject());
		a.put("email", account.getEmail());
		a.put("displayName", account.getDisplayName());
		a.put("bundles", new ArrayList<>(account.getBundles()));
		a.put("linkedAt", ISO8601.format(Instant.ofEpochMilli(account.getLinkedAtMillis())));
		a.put("lastUsed", account.getLastUsedMillis() > 0
				? ISO8601.format(Instant.ofEpochMilli(account.getLastUsedMillis())) : null);
		a.put("needsReauth", account.isNeedsReauth());
		ConnectionProviders.Provider provider = ConnectionProviders.get(account.getProvider());
		a.put("bundlesNeedingGrant", (provider != null) ? account.bundlesNeedingGrant(provider) : new ArrayList<>());
		return a;
	}

	private static boolean isUser(Session session) {
		org.mintjams.jcr.Session jcrSession = org.mintjams.jcr.Session.class.cast(session);
		return !(jcrSession.isGuest() || jcrSession.isAnonymous() || jcrSession.isSystem() || jcrSession.isService());
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformConnectionsWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}

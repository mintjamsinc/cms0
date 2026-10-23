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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.NamespaceException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.cms.security.Encryptor;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The accounts a user linked, one file per provider in the user's home in
 * the system workspace:
 *
 * <pre>
 * /home/users/&lt;user&gt;/connections/&lt;provider&gt;.json   who the account is, what was granted
 *   oauth:refreshToken                              the grant, encrypted with the installation key
 * </pre>
 *
 * <p>One account per provider: the file name is the provider, so a second
 * cannot exist. The identity is the provider's stable {@code sub}; the email
 * is a label. The refresh token is a property, never part of the JSON, so
 * reading the file shows nothing secret, and the GraphQL API never returns
 * it. Access tokens are not stored at all (see {@link ConnectionTokens}).
 *
 * <p>Everything is read and written with a session of the user, so the
 * home's access control applies and the files are the user's own; deleting
 * the user takes them away with the home.
 */
public final class LinkedAccounts {

	public static final String SYSTEM_WORKSPACE = "system";
	public static final String HOMES = "/home/users";
	public static final String FOLDER_NAME = "connections";
	public static final String FILE_SUFFIX = ".json";
	public static final String ACCOUNT_TYPE = "application/vnd.mintjams.webtop.connection+json";

	static final String NAMESPACE_PREFIX = "oauth";
	static final String NAMESPACE_URI = "http://www.mintjams.jp/webtop/oauth/1.0";
	static final String REFRESH_TOKEN = NAMESPACE_PREFIX + ":refreshToken";

	private static volatile boolean fNamespaceChecked;

	private LinkedAccounts() {}

	/** What is known about a linked account; never the tokens. */
	public static final class Account {
		private final String fProvider;
		private final String fSubject;
		private final String fEmail;
		private final String fDisplayName;
		private final Set<String> fBundles;
		private final long fLinkedAtMillis;
		private final long fLastUsedMillis;
		private final boolean fNeedsReauth;

		public Account(String provider, String subject, String email, String displayName, Set<String> bundles,
				long linkedAtMillis, long lastUsedMillis, boolean needsReauth) {
			fProvider = provider;
			fSubject = subject;
			fEmail = email;
			fDisplayName = displayName;
			fBundles = new LinkedHashSet<>(bundles);
			fLinkedAtMillis = linkedAtMillis;
			fLastUsedMillis = lastUsedMillis;
			fNeedsReauth = needsReauth;
		}

		public String getProvider() {
			return fProvider;
		}

		public String getSubject() {
			return fSubject;
		}

		public String getEmail() {
			return fEmail;
		}

		public String getDisplayName() {
			return fDisplayName;
		}

		public Set<String> getBundles() {
			return fBundles;
		}

		public long getLinkedAtMillis() {
			return fLinkedAtMillis;
		}

		public long getLastUsedMillis() {
			return fLastUsedMillis;
		}

		public boolean isNeedsReauth() {
			return fNeedsReauth;
		}

		public Account withBundles(Set<String> bundles) {
			return new Account(fProvider, fSubject, fEmail, fDisplayName, bundles, fLinkedAtMillis, fLastUsedMillis,
					fNeedsReauth);
		}

		public Account withNeedsReauth(boolean needsReauth) {
			return new Account(fProvider, fSubject, fEmail, fDisplayName, fBundles, fLinkedAtMillis, fLastUsedMillis,
					needsReauth);
		}

		public Account withLastUsed(long lastUsedMillis) {
			return new Account(fProvider, fSubject, fEmail, fDisplayName, fBundles, fLinkedAtMillis, lastUsedMillis,
					fNeedsReauth);
		}
	}

	static String folderPath(String userId) {
		return HOMES + "/" + userId + "/" + FOLDER_NAME;
	}

	static String filePath(String userId, String provider) {
		return folderPath(userId) + "/" + provider + FILE_SUFFIX;
	}

	/** A session of the user on the system workspace, where the home is. */
	static Session openAs(String userId) throws RepositoryException {
		return CmsService.getRepository().login(new ServiceUserCredentials(userId), SYSTEM_WORKSPACE);
	}

	/** The linked account of a provider, or null. */
	public static Account get(String userId, String provider) throws RepositoryException {
		Session session = openAs(userId);
		try {
			Node file;
			try {
				file = session.getNode(filePath(userId, provider));
			} catch (PathNotFoundException ex) {
				return null;
			}
			return read(file, provider);
		} finally {
			session.logout();
		}
	}

	/** Every linked account of the user, in catalogue order of the providers. */
	public static List<Account> list(String userId) throws RepositoryException {
		List<Account> accounts = new ArrayList<>();
		Session session = openAs(userId);
		try {
			Node folder;
			try {
				folder = session.getNode(folderPath(userId));
			} catch (PathNotFoundException ex) {
				return accounts;
			}
			for (ConnectionProviders.Provider provider : ConnectionProviders.all()) {
				String name = provider.getId() + FILE_SUFFIX;
				if (JCRs.exists(folder, name)) {
					Account account = read(folder.getNode(name), provider.getId());
					if (account != null) {
						accounts.add(account);
					}
				}
			}
			return accounts;
		} finally {
			session.logout();
		}
	}

	private static Account read(Node file, String provider) {
		try {
			JsonObject json = JsonParser.parseString(JCRs.getContentAsString(file)).getAsJsonObject();
			Set<String> bundles = new LinkedHashSet<>();
			if (json.has("bundles")) {
				for (JsonElement e : json.getAsJsonArray("bundles")) {
					bundles.add(e.getAsString());
				}
			}
			return new Account(provider, string(json, "subject"), string(json, "email"), string(json, "displayName"),
					bundles, json.has("linkedAt") ? json.get("linkedAt").getAsLong() : 0,
					json.has("lastUsed") ? json.get("lastUsed").getAsLong() : 0,
					json.has("needsReauth") && json.get("needsReauth").getAsBoolean());
		} catch (Throwable ex) {
			// A record that cannot be read grants nothing.
			CmsService.getLogger(LinkedAccounts.class).warn("Unreadable linked account record: {}", safePath(file), ex);
			return null;
		}
	}

	private static String string(JsonObject json, String name) {
		return (json.has(name) && !json.get(name).isJsonNull()) ? json.get(name).getAsString() : null;
	}

	private static String safePath(Node node) {
		try {
			return node.getPath();
		} catch (Throwable ex) {
			return "?";
		}
	}

	/**
	 * Writes the account; a non-null refresh token replaces the stored one, null
	 * keeps it. The home must exist (it does for every user who has signed in).
	 */
	public static void save(String userId, Account account, String refreshToken) throws RepositoryException {
		ensureNamespace();
		Session session = openAs(userId);
		try {
			Node home = session.getNode(HOMES + "/" + userId);
			Node folder = JCRs.getOrCreateFolder(home, FOLDER_NAME);
			String name = account.getProvider() + FILE_SUFFIX;
			Node file = JCRs.exists(folder, name) ? folder.getNode(name) : JCRs.createFile(folder, name);
			JCRs.write(file, new ByteArrayInputStream(toJson(account).toString().getBytes(StandardCharsets.UTF_8)));
			JCRs.setProperty(file, "jcr:mimeType", ACCOUNT_TYPE);
			JCRs.setProperty(file, "jcr:encoding", "UTF-8");
			if (refreshToken != null) {
				JCRs.setProperty(file, REFRESH_TOKEN, encryptor().encrypt(refreshToken));
			}
			session.save();
		} finally {
			session.logout();
		}
	}

	/** The stored refresh token, decrypted, or null when there is none. */
	public static String refreshToken(String userId, String provider) throws RepositoryException {
		Session session = openAs(userId);
		try {
			Node file;
			try {
				file = session.getNode(filePath(userId, provider));
			} catch (PathNotFoundException ex) {
				return null;
			}
			Node content = JCRs.getContentNode(file);
			if (!content.hasProperty(REFRESH_TOKEN)) {
				return null;
			}
			return encryptor().decrypt(content.getProperty(REFRESH_TOKEN).getString());
		} finally {
			session.logout();
		}
	}

	/** Removes the account and its grant. Returns false when there was none. */
	public static boolean remove(String userId, String provider) throws RepositoryException {
		Session session = openAs(userId);
		try {
			Node file;
			try {
				file = session.getNode(filePath(userId, provider));
			} catch (PathNotFoundException ex) {
				return false;
			}
			file.remove();
			session.save();
			return true;
		} finally {
			session.logout();
		}
	}

	private static JsonObject toJson(Account account) {
		JsonObject json = new JsonObject();
		json.addProperty("provider", account.getProvider());
		json.addProperty("subject", account.getSubject());
		json.addProperty("email", account.getEmail());
		json.addProperty("displayName", account.getDisplayName());
		JsonArray bundles = new JsonArray();
		for (String bundle : account.getBundles()) {
			bundles.add(bundle);
		}
		json.add("bundles", bundles);
		json.addProperty("linkedAt", account.getLinkedAtMillis());
		json.addProperty("lastUsed", account.getLastUsedMillis());
		json.addProperty("needsReauth", account.isNeedsReauth());
		return json;
	}

	private static Encryptor encryptor() {
		return CmsService.getEncryptor();
	}

	/**
	 * Registers the {@code oauth} namespace unless provisioning did
	 * (provisioning/connections.yml), so an installation that predates it works
	 * too.
	 */
	private static void ensureNamespace() throws RepositoryException {
		if (fNamespaceChecked) {
			return;
		}
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), SYSTEM_WORKSPACE);
		try {
			NamespaceRegistry registry = session.getWorkspace().getNamespaceRegistry();
			try {
				registry.getURI(NAMESPACE_PREFIX);
			} catch (NamespaceException notBound) {
				registry.registerNamespace(NAMESPACE_PREFIX, NAMESPACE_URI);
				CmsService.getLogger(LinkedAccounts.class).info("Registered namespace: {} -> {}", NAMESPACE_PREFIX,
						NAMESPACE_URI);
			}
			fNamespaceChecked = true;
		} finally {
			session.logout();
		}
	}

	/** Whether any user has a file in a connections folder: for diagnostics only. */
	static boolean hasAny(Session session) throws RepositoryException {
		try {
			NodeIterator homes = session.getNode(HOMES).getNodes();
			while (homes.hasNext()) {
				Node home = homes.nextNode();
				if (JCRs.exists(home, FOLDER_NAME) && home.getNode(FOLDER_NAME).hasNodes()) {
					return true;
				}
			}
		} catch (PathNotFoundException ex) {
			// No homes yet.
		}
		return false;
	}

}

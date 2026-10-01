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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A user's MCP connection to a workspace: whether it is on, and what the
 * clients using it may do.
 *
 * <p>The user turns the connection on and off in Preferences. While it is on,
 * a client the user authorizes works as that user — also after the user has
 * signed out of the browser, which is what lets a client wait for something or
 * watch over it. Turning it off stops every client at once.
 *
 * <p>The connection is the only state the MCP server keeps. It lives in the
 * workspace it belongs to, at {@code /var/mcp/connections/<user>.json}, written
 * with a service session, so every cluster node sees the same answer and a
 * client cannot edit it through its own tools unless the workspace lets its
 * user write there.
 *
 * <p>Each time the connection is turned on it starts a new <em>generation</em>.
 * Tokens carry the generation they were issued under (see
 * {@link McpAccessToken}) and are honoured only while it is the current one, so
 * no token survives an off-and-on.
 */
public final class McpConnections {

	private static final String VAR_FOLDER_NAME = "var";
	private static final String MCP_FOLDER_NAME = "mcp";
	private static final String CONNECTIONS_FOLDER_NAME = "connections";
	private static final String FILE_SUFFIX = ".json";

	/** How many authorized clients a connection remembers, most recent first. */
	private static final int MAX_CLIENTS = 20;

	private McpConnections() {}

	/** The connection of {@code userId} to the workspace; off when the user never turned it on. */
	public static Connection get(String workspaceName, String userId) throws RepositoryException {
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), workspaceName);
		try {
			return read(session, userId);
		} finally {
			session.logout();
		}
	}

	/**
	 * Turns the connection on or off and sets whether its clients may change
	 * content. Turning it on while it is on only changes that setting; turning
	 * it on from off starts a new generation; turning it off forgets its clients.
	 */
	public static Connection set(String workspaceName, String userId, boolean enabled, boolean write, long nowMillis)
			throws RepositoryException {
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), workspaceName);
		try {
			Connection current = read(session, userId);
			Connection updated;
			if (!enabled) {
				updated = new Connection(false, current.getGeneration(), false, 0, Collections.emptyList());
			} else if (current.isEnabled()) {
				updated = new Connection(true, current.getGeneration(), write, current.getEnabledAtMillis(),
						current.getClients());
			} else {
				// Later than any generation before it, even if the clock stepped back.
				updated = new Connection(true, Math.max(nowMillis, current.getGeneration() + 1), write, nowMillis,
						Collections.emptyList());
			}
			write(session, userId, updated);
			return updated;
		} finally {
			session.logout();
		}
	}

	/**
	 * Remembers that the user authorized a client, so that Preferences can show
	 * what is connected. Ignored when the connection is off or has moved on to
	 * another generation.
	 */
	public static void addClient(String workspaceName, String userId, long generation, Client client)
			throws RepositoryException {
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), workspaceName);
		try {
			Connection current = read(session, userId);
			if (!current.isEnabled() || current.getGeneration() != generation) {
				return;
			}
			List<Client> clients = new ArrayList<>();
			clients.add(client);
			for (Client existing : current.getClients()) {
				if (clients.size() >= MAX_CLIENTS) {
					break;
				}
				// Authorizing the same client again replaces its entry.
				if (!existing.getName().equals(client.getName())
						|| !existing.getRedirectTarget().equals(client.getRedirectTarget())) {
					clients.add(existing);
				}
			}
			write(session, userId, new Connection(true, current.getGeneration(), current.isWrite(),
					current.getEnabledAtMillis(), clients));
		} finally {
			session.logout();
		}
	}

	private static Connection read(Session session, String userId) throws RepositoryException {
		Node file;
		try {
			file = session.getNode("/" + VAR_FOLDER_NAME + "/" + MCP_FOLDER_NAME + "/" + CONNECTIONS_FOLDER_NAME
					+ "/" + fileName(userId));
		} catch (PathNotFoundException ex) {
			return Connection.OFF;
		}

		try {
			JsonObject json = JsonParser.parseString(JCRs.getContentAsString(file)).getAsJsonObject();
			List<Client> clients = new ArrayList<>();
			if (json.has("clients")) {
				for (JsonElement e : json.getAsJsonArray("clients")) {
					JsonObject client = e.getAsJsonObject();
					clients.add(new Client(client.get("name").getAsString(), client.get("redirectTarget").getAsString(),
							client.get("authorizedAt").getAsLong()));
				}
			}
			return new Connection(json.get("enabled").getAsBoolean(), json.get("generation").getAsLong(),
					json.get("write").getAsBoolean(), json.get("enabledAt").getAsLong(), clients);
		} catch (Throwable ex) {
			// A record that cannot be read grants nothing.
			CmsService.getLogger(McpConnections.class).warn("Unreadable MCP connection record: " + file.getPath(), ex);
			return Connection.OFF;
		}
	}

	private static void write(Session session, String userId, Connection connection) throws RepositoryException {
		JsonObject json = new JsonObject();
		json.addProperty("enabled", connection.isEnabled());
		json.addProperty("generation", connection.getGeneration());
		json.addProperty("write", connection.isWrite());
		json.addProperty("enabledAt", connection.getEnabledAtMillis());
		JsonArray clients = new JsonArray();
		for (Client client : connection.getClients()) {
			JsonObject c = new JsonObject();
			c.addProperty("name", client.getName());
			c.addProperty("redirectTarget", client.getRedirectTarget());
			c.addProperty("authorizedAt", client.getAuthorizedAtMillis());
			clients.add(c);
		}
		json.add("clients", clients);

		try {
			Node folder = JCRs.getOrCreateFolder(JCRs.getOrCreateFolder(
					JCRs.getOrCreateFolder(session.getRootNode(), VAR_FOLDER_NAME), MCP_FOLDER_NAME),
					CONNECTIONS_FOLDER_NAME);
			String fileName = fileName(userId);
			Node file = JCRs.exists(folder, fileName) ? folder.getNode(fileName) : JCRs.createFile(folder, fileName);
			JCRs.write(file, new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)));
			JCRs.setProperty(file, "jcr:mimeType", "application/json");
			session.save();
		} catch (RepositoryException | RuntimeException ex) {
			try {
				session.refresh(false);
			} catch (Throwable ignore) {}
			throw ex;
		}
	}

	private static String fileName(String userId) throws RepositoryException {
		if (userId == null || userId.isEmpty() || userId.indexOf('/') != -1) {
			throw new RepositoryException("Not a user id an MCP connection can be kept for.");
		}
		return userId + FILE_SUFFIX;
	}

	/** The state of one user's connection to one workspace. */
	public static final class Connection {
		static final Connection OFF = new Connection(false, 0, false, 0, Collections.emptyList());

		private final boolean fEnabled;
		private final long fGeneration;
		private final boolean fWrite;
		private final long fEnabledAtMillis;
		private final List<Client> fClients;

		private Connection(boolean enabled, long generation, boolean write, long enabledAtMillis,
				List<Client> clients) {
			fEnabled = enabled;
			fGeneration = generation;
			fWrite = write;
			fEnabledAtMillis = enabledAtMillis;
			fClients = Collections.unmodifiableList(new ArrayList<>(clients));
		}

		public boolean isEnabled() {
			return fEnabled;
		}

		/** Changes each time the connection is turned on; tokens of an earlier generation are dead. */
		public long getGeneration() {
			return fGeneration;
		}

		/** Whether the user lets clients change content. */
		public boolean isWrite() {
			return fWrite;
		}

		/** When the connection was turned on; {@code 0} while it is off. */
		public long getEnabledAtMillis() {
			return fEnabledAtMillis;
		}

		/** The clients authorized since the connection was turned on, most recent first. */
		public List<Client> getClients() {
			return fClients;
		}
	}

	/** A client the user authorized. */
	public static final class Client {
		private final String fName;
		private final String fRedirectTarget;
		private final long fAuthorizedAtMillis;

		public Client(String name, String redirectTarget, long authorizedAtMillis) {
			fName = name;
			fRedirectTarget = redirectTarget;
			fAuthorizedAtMillis = authorizedAtMillis;
		}

		/** The name the client registered under. It is the client's own claim. */
		public String getName() {
			return fName;
		}

		/** Where the authorization was delivered: the host of the client's redirect URI. */
		public String getRedirectTarget() {
			return fRedirectTarget;
		}

		public long getAuthorizedAtMillis() {
			return fAuthorizedAtMillis;
		}
	}

}

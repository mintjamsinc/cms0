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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.security.Privilege;

import org.mintjams.cms.security.BCrypt;
import org.mintjams.jcr.Workspace;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.WorkspaceUserHomes;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.util.ISO8601;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;

/**
 * Contributes the platform's IdP (identity provider) GraphQL schema — the
 * graphql-java migration target of the handmade {@code IdpQueryExecutor} /
 * {@code IdpMutationExecutor} — to the unified per-workspace
 * {@link org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider}.
 *
 * <p>It is a side-by-side reimplementation: its SDL ({@code idp-schema.graphqls})
 * {@code extend}s the core Query/Mutation roots, and its {@link DataFetcher}s
 * read users/groups/roles from {@code /home} in the system workspace exactly as
 * the handmade executors did, projecting them into the same flat maps (graphql-java
 * then serves whatever the client selects). All reads run under the caller's JCR
 * session from {@link GraphQLExecutionContext}.
 *
 * <p>Coverage: the {@code user}, {@code me} and {@code users} reads plus the
 * shared User/Role/Group mappers and connection helpers (IDP-1a); the
 * {@code role}, {@code roles} and {@code roleTree} reads (IDP-1b); the
 * {@code group}, {@code groups} and {@code groupTree} reads (IDP-1c); and the
 * user mutations {@code createUser}/{@code updateUser}/{@code deleteUser}/
 * {@code changePassword}/{@code resetPassword} (IDP-2a) and the role-assignment
 * mutations {@code assignRoles}/{@code revokeRoles} (IDP-2b) and the role CRUD
 * mutations {@code createRole}/{@code updateRole}/{@code deleteRole} (IDP-2c)
 * and the group CRUD/move mutations {@code createGroup}/{@code updateGroup}/
 * {@code deleteGroup}/{@code moveGroup} (IDP-2d), the group-membership mutations
 * {@code addGroupMembers}/{@code removeGroupMembers} and {@code updatePreferences}
 * (IDP-2e/2f) — all writing under the caller's session and reporting validation
 * failures in-band as an {@code errors} list. This completes the IdP domain.
 */
public final class PlatformIdpWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/idp-schema.graphqls";

	static final String USERS_ROOT = "/home/users";
	static final String ROLES_ROOT = "/home/roles";
	static final String GROUPS_ROOT = "/home/groups";

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "user", (DataFetcher<Object>) PlatformIdpWiringContributor::user)
				.dataFetcher("Query", "me", (DataFetcher<Object>) PlatformIdpWiringContributor::me)
				.dataFetcher("Query", "users", (DataFetcher<Object>) PlatformIdpWiringContributor::users)
				.dataFetcher("Query", "role", (DataFetcher<Object>) PlatformIdpWiringContributor::role)
				.dataFetcher("Query", "roles", (DataFetcher<Object>) PlatformIdpWiringContributor::roles)
				.dataFetcher("Query", "roleTree", (DataFetcher<Object>) PlatformIdpWiringContributor::roleTree)
				.dataFetcher("Query", "group", (DataFetcher<Object>) PlatformIdpWiringContributor::group)
				.dataFetcher("Query", "groups", (DataFetcher<Object>) PlatformIdpWiringContributor::groups)
				.dataFetcher("Query", "groupTree", (DataFetcher<Object>) PlatformIdpWiringContributor::groupTree)
				.dataFetcher("Mutation", "createUser", (DataFetcher<Object>) PlatformIdpWiringContributor::createUser)
				.dataFetcher("Mutation", "updateUser", (DataFetcher<Object>) PlatformIdpWiringContributor::updateUser)
				.dataFetcher("Mutation", "deleteUser", (DataFetcher<Object>) PlatformIdpWiringContributor::deleteUser)
				.dataFetcher("Mutation", "changePassword",
						(DataFetcher<Object>) PlatformIdpWiringContributor::changePassword)
				.dataFetcher("Mutation", "resetPassword",
						(DataFetcher<Object>) PlatformIdpWiringContributor::resetPassword)
				.dataFetcher("Mutation", "assignRoles",
						(DataFetcher<Object>) PlatformIdpWiringContributor::assignRoles)
				.dataFetcher("Mutation", "revokeRoles",
						(DataFetcher<Object>) PlatformIdpWiringContributor::revokeRoles)
				.dataFetcher("Mutation", "createRole", (DataFetcher<Object>) PlatformIdpWiringContributor::createRole)
				.dataFetcher("Mutation", "updateRole", (DataFetcher<Object>) PlatformIdpWiringContributor::updateRole)
				.dataFetcher("Mutation", "deleteRole",
						(DataFetcher<Object>) PlatformIdpWiringContributor::deleteRole)
				.dataFetcher("Mutation", "createGroup",
						(DataFetcher<Object>) PlatformIdpWiringContributor::createGroup)
				.dataFetcher("Mutation", "updateGroup",
						(DataFetcher<Object>) PlatformIdpWiringContributor::updateGroup)
				.dataFetcher("Mutation", "deleteGroup",
						(DataFetcher<Object>) PlatformIdpWiringContributor::deleteGroup)
				.dataFetcher("Mutation", "moveGroup",
						(DataFetcher<Object>) PlatformIdpWiringContributor::moveGroup)
				.dataFetcher("Mutation", "addGroupMembers",
						(DataFetcher<Object>) PlatformIdpWiringContributor::addGroupMembers)
				.dataFetcher("Mutation", "removeGroupMembers",
						(DataFetcher<Object>) PlatformIdpWiringContributor::removeGroupMembers)
				.dataFetcher("Mutation", "updatePreferences",
						(DataFetcher<Object>) PlatformIdpWiringContributor::updatePreferences);
	}

	// ---- user queries (mirror IdpQueryExecutor) ----------------------------

	private static Object user(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		String username = environment.getArgument("username");
		if (username == null) {
			return null;
		}
		String profilePath = USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return null;
		}
		Node profileNode = session.getNode(profilePath);
		return mapUser(session, username, profileNode, JCRs.getContentNode(profileNode));
	}

	private static Object me(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		String username = session.getUserID();
		String profilePath = USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return null;
		}
		Node profileNode = session.getNode(profilePath);
		return mapUser(session, username, profileNode, JCRs.getContentNode(profileNode));
	}

	private static Object users(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		int first = intArg(environment, "first", 20);
		String afterCursor = environment.getArgument("after");
		String query = environment.getArgument("query");
		String roleId = environment.getArgument("roleId");
		String groupId = environment.getArgument("groupId");
		boolean includeDescendants = Boolean.TRUE.equals(environment.getArgument("includeDescendants"));

		if (!session.nodeExists(USERS_ROOT)) {
			return emptyConnection();
		}

		List<Node[]> entries = new ArrayList<>();
		NodeIterator it = session.getNode(USERS_ROOT).getNodes();
		while (it.hasNext()) {
			Node userFolder = it.nextNode();
			if (!userFolder.hasNode("profile")) {
				continue;
			}
			Node profileNode = userFolder.getNode("profile");
			Node contentNode = JCRs.getContentNode(profileNode);
			String uname = userFolder.getName();

			// Filter: query (username, displayName, mail)
			if (query != null && !query.isEmpty()) {
				boolean matches = uname.contains(query);
				if (!matches && contentNode.hasProperty("displayName")) {
					matches = contentNode.getProperty("displayName").getString().contains(query);
				}
				if (!matches && contentNode.hasProperty("mail")) {
					matches = contentNode.getProperty("mail").getString().contains(query);
				}
				if (!matches) {
					continue;
				}
			}
			// Filter: roleId
			if (roleId != null && !roleId.isEmpty() && !userHasRole(session, contentNode, roleId)) {
				continue;
			}
			// Filter: groupId
			if (groupId != null && !groupId.isEmpty() && !userInGroup(session, contentNode, groupId, includeDescendants)) {
				continue;
			}
			entries.add(new Node[] { profileNode, contentNode });
		}

		return buildConnection(entries, first, afterCursor, entry -> {
			String uname = entry[0].getParent().getName();
			return mapUser(session, uname, entry[0], entry[1]);
		});
	}

	// ---- role queries (mirror IdpQueryExecutor) ----------------------------

	private static Object role(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		String roleId = environment.getArgument("roleId");
		if (roleId == null) {
			return null;
		}
		String profilePath = ROLES_ROOT + "/" + roleId + "/profile";
		if (!session.nodeExists(profilePath)) {
			return null;
		}
		Node profileNode = session.getNode(profilePath);
		return mapRole(session, roleId, profileNode, JCRs.getContentNode(profileNode));
	}

	private static Object roles(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		int first = intArg(environment, "first", 100);
		String afterCursor = environment.getArgument("after");
		String query = environment.getArgument("query");

		if (!session.nodeExists(ROLES_ROOT)) {
			return emptyConnection();
		}

		List<Node[]> entries = new ArrayList<>();
		collectAllRoles(session.getNode(ROLES_ROOT), entries, query);
		return buildConnection(entries, first, afterCursor, entry -> {
			String rId = getRoleId(entry[0]);
			return mapRole(session, rId, entry[0], entry[1]);
		});
	}

	private static Object roleTree(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		String rootRoleId = environment.getArgument("rootRoleId");
		Integer maxDepth = environment.getArgument("maxDepth");

		Node startNode;
		if (rootRoleId != null && !rootRoleId.isEmpty()) {
			String rootPath = ROLES_ROOT + "/" + rootRoleId;
			if (!session.nodeExists(rootPath)) {
				return new ArrayList<>();
			}
			startNode = session.getNode(rootPath);
		} else {
			if (!session.nodeExists(ROLES_ROOT)) {
				return new ArrayList<>();
			}
			startNode = session.getNode(ROLES_ROOT);
		}
		return buildRoleTree(startNode, maxDepth, 0);
	}

	/** Recursively collects every role under {@code folder} ({profile, content} pairs), filtered by an optional text query. */
	private static void collectAllRoles(Node folder, List<Node[]> entries, String query) throws Exception {
		NodeIterator it = folder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if ("profile".equals(child.getName())) {
				continue;
			}
			if (!child.hasNode("profile")) {
				continue;
			}
			Node profileNode = child.getNode("profile");
			Node contentNode = JCRs.getContentNode(profileNode);
			String rId = getRoleId(profileNode);
			if (query != null && !query.isEmpty()) {
				boolean matches = rId.contains(query);
				if (!matches && contentNode.hasProperty("displayName")) {
					matches = contentNode.getProperty("displayName").getString().contains(query);
				}
				if (matches) {
					entries.add(new Node[] { profileNode, contentNode });
				}
			} else {
				entries.add(new Node[] { profileNode, contentNode });
			}
			collectAllRoles(child, entries, query);
		}
	}

	/** Builds the role hierarchy under {@code parentFolder} as nested tree nodes (lighter than Role). */
	private static List<Map<String, Object>> buildRoleTree(Node parentFolder, Integer maxDepth, int currentDepth)
			throws Exception {
		List<Map<String, Object>> nodes = new ArrayList<>();
		if (maxDepth != null && currentDepth >= maxDepth) {
			return nodes;
		}
		NodeIterator it = parentFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if ("profile".equals(child.getName())) {
				continue;
			}
			if (!child.hasNode("profile")) {
				continue;
			}
			Node profileNode = child.getNode("profile");
			Node contentNode = JCRs.getContentNode(profileNode);
			String roleId = child.getPath().substring(ROLES_ROOT.length() + 1);

			Map<String, Object> treeNode = new HashMap<>();
			treeNode.put("roleId", roleId);
			treeNode.put("name", roleId.contains("/") ? roleId.substring(roleId.lastIndexOf('/') + 1) : roleId);
			treeNode.put("displayName",
					contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
			treeNode.put("depth", currentDepth);

			List<Map<String, Object>> children = buildRoleTree(child, maxDepth, currentDepth + 1);
			treeNode.put("hasChildren", !children.isEmpty() || hasChildRoles(child));
			treeNode.put("children", children);
			nodes.add(treeNode);
		}
		return nodes;
	}

	// ---- group queries (mirror IdpQueryExecutor) ---------------------------

	private static Object group(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		String groupId = environment.getArgument("groupId");
		if (groupId == null) {
			return null;
		}
		String profilePath = GROUPS_ROOT + "/" + groupId + "/profile";
		if (!session.nodeExists(profilePath)) {
			return null;
		}
		Node profileNode = session.getNode(profilePath);
		return mapGroup(session, groupId, profileNode, JCRs.getContentNode(profileNode));
	}

	/** Top-level groups only (flat iteration), mirroring the handmade groups query. */
	private static Object groups(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		int first = intArg(environment, "first", 50);
		String afterCursor = environment.getArgument("after");
		String query = environment.getArgument("query");

		if (!session.nodeExists(GROUPS_ROOT)) {
			return emptyConnection();
		}

		List<Node[]> entries = new ArrayList<>();
		NodeIterator it = session.getNode(GROUPS_ROOT).getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if (!child.hasNode("profile")) {
				continue;
			}
			Node profileNode = child.getNode("profile");
			Node contentNode = JCRs.getContentNode(profileNode);
			String gId = child.getName();
			if (query != null && !query.isEmpty()) {
				boolean matches = gId.contains(query);
				if (!matches && contentNode.hasProperty("displayName")) {
					matches = contentNode.getProperty("displayName").getString().contains(query);
				}
				if (!matches) {
					continue;
				}
			}
			entries.add(new Node[] { profileNode, contentNode });
		}

		return buildConnection(entries, first, afterCursor, entry -> {
			String gId = getGroupId(entry[0]);
			return mapGroup(session, gId, entry[0], entry[1]);
		});
	}

	private static Object groupTree(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		String rootGroupId = environment.getArgument("rootGroupId");
		Integer maxDepth = environment.getArgument("maxDepth");

		Node startNode;
		if (rootGroupId != null && !rootGroupId.isEmpty()) {
			String rootPath = GROUPS_ROOT + "/" + rootGroupId;
			if (!session.nodeExists(rootPath)) {
				return new ArrayList<>();
			}
			startNode = session.getNode(rootPath);
		} else {
			if (!session.nodeExists(GROUPS_ROOT)) {
				return new ArrayList<>();
			}
			startNode = session.getNode(GROUPS_ROOT);
		}
		return buildGroupTree(startNode, maxDepth, 0);
	}

	/** Group ancestors root-first (the group itself excluded), mirroring the handmade buildAncestors. */
	private static void buildGroupAncestors(Session session, String groupId, List<Map<String, Object>> ancestors)
			throws Exception {
		String[] segments = groupId.split("/");
		for (int i = 0; i < segments.length - 1; i++) {
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j <= i; j++) {
				if (j > 0) {
					sb.append("/");
				}
				sb.append(segments[j]);
			}
			String ancestorId = sb.toString();
			String profilePath = GROUPS_ROOT + "/" + ancestorId + "/profile";
			if (session.nodeExists(profilePath)) {
				Node p = session.getNode(profilePath);
				ancestors.add(mapGroupBasic(session, ancestorId, p, JCRs.getContentNode(p)));
			}
		}
	}

	/** Builds the group hierarchy under {@code parentFolder} as nested tree nodes (memberCount is 0 in tree view). */
	private static List<Map<String, Object>> buildGroupTree(Node parentFolder, Integer maxDepth, int currentDepth)
			throws Exception {
		List<Map<String, Object>> nodes = new ArrayList<>();
		if (maxDepth != null && currentDepth >= maxDepth) {
			return nodes;
		}
		NodeIterator it = parentFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if ("profile".equals(child.getName())) {
				continue;
			}
			if (!child.hasNode("profile")) {
				continue;
			}
			Node profileNode = child.getNode("profile");
			Node contentNode = JCRs.getContentNode(profileNode);
			String groupId = child.getPath().substring(GROUPS_ROOT.length() + 1);

			Map<String, Object> treeNode = new HashMap<>();
			treeNode.put("groupId", groupId);
			treeNode.put("name", groupId.contains("/") ? groupId.substring(groupId.lastIndexOf('/') + 1) : groupId);
			treeNode.put("displayName",
					contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
			treeNode.put("depth", currentDepth);
			treeNode.put("memberCount", 0); // expensive to compute; left 0 for the tree view (handmade parity)

			List<Map<String, Object>> children = buildGroupTree(child, maxDepth, currentDepth + 1);
			treeNode.put("hasChildren", !children.isEmpty() || hasChildGroups(child));
			treeNode.put("children", children);
			nodes.add(treeNode);
		}
		return nodes;
	}

	// ---- user mutations (mirror IdpMutationExecutor) -----------------------

	@SuppressWarnings("unchecked")
	private static Object createUser(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		String password = (String) input.get("password");
		// Service accounts are non-interactive (assumed only via runAs), so they never
		// sign in and need no password; every other user does.
		boolean service = getBoolInput(input, "service", false);

		if (username == null) {
			return errorResult("username is required", "INVALID_INPUT");
		}
		if (!service && password == null) {
			return errorResult("password is required", "INVALID_INPUT");
		}
		try {
			Workspace.class.cast(session.getWorkspace()).getPrincipalProvider().getPrincipal(username);
			return errorResult("User or group with the same name already exists: " + username, "ALREADY_EXISTS");
		} catch (PrincipalNotFoundException ignore) {
			// expected when the principal does not exist — continue
		}

		Node usersFolder = ensureFolder(session, USERS_ROOT);
		Node userFolder = JCRs.getOrCreateFolder(usersFolder, username);
		Node profileFile = JCRs.createFile(userFolder, "profile");
		JCRs.setProperty(profileFile, "jcr:mimeType", "application/vnd.webtop.user");
		if (password != null && !password.isEmpty()) {
			JCRs.setProperty(profileFile, "password", "{bcrypt}" + BCrypt.hash(password));
		}

		Node contentNode = JCRs.getContentNode(profileFile);
		contentNode.setProperty("identifier", username);
		contentNode.setProperty("isGroup", false);
		contentNode.setProperty("isService", service);
		setStringIfPresent(contentNode, "sn", input);
		setStringIfPresent(contentNode, "givenName", input);
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "mail", input);
		contentNode.setProperty("enabled", getBoolInput(input, "enabled", true));

		List<String> roleIds = (List<String>) input.get("roles");
		if (roleIds != null) {
			setRoleReferences(session, contentNode, roleIds);
		}
		List<String> groupIds = (List<String>) input.get("memberOf");
		if (groupIds != null) {
			setGroupReferences(session, contentNode, groupIds);
		}

		JCRs.getOrCreateFolder(userFolder, "preferences");
		JCRs.getOrCreateFolder(userFolder, "Desktop");
		session.save();

		// Grant the user full control over their own home.
		final String principalName = username;
		JCRs.setAccessControlEntry(userFolder, new Principal() {
			@Override
			public String getName() {
				return principalName;
			}
		}, true, Privilege.JCR_ALL);
		session.save();

		Node savedProfile = session.getNode(USERS_ROOT + "/" + username + "/profile");
		return userResult(mapUser(session, username, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	private static Object updateUser(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		if (username == null) {
			return errorResult("username is required", "INVALID_INPUT");
		}
		// A signed-in user must not update their own account here: disabling it or
		// changing its profile is a self-lockout risk, and self-service belongs in
		// account settings. The identity-manager UI also blocks editing yourself,
		// but the server is authoritative.
		if (username.equals(session.getUserID())) {
			return errorResult("You cannot update your own account", "SELF_MODIFICATION_FORBIDDEN");
		}
		String profilePath = USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorResult("User not found: " + username, "NOT_FOUND");
		}
		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		setStringIfPresent(contentNode, "sn", input);
		setStringIfPresent(contentNode, "givenName", input);
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "mail", input);
		if (input.get("enabled") instanceof Boolean) {
			boolean enabled = (Boolean) input.get("enabled");
			contentNode.setProperty("enabled", enabled);
		}
		session.save();
		Node savedProfile = session.getNode(profilePath);
		return userResult(mapUser(session, username, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	private static Object deleteUser(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		if (username == null) {
			return errorResult("username is required", "INVALID_INPUT");
		}
		// A signed-in user must not delete their own account — the ultimate
		// self-lockout. The identity-manager UI hides the delete action for
		// yourself, but the server is authoritative.
		if (username.equals(session.getUserID())) {
			return errorResult("You cannot delete your own account", "SELF_MODIFICATION_FORBIDDEN");
		}
		String userFolderPath = USERS_ROOT + "/" + username;
		if (!session.nodeExists(userFolderPath)) {
			return errorResult("User not found: " + username, "NOT_FOUND");
		}
		session.getNode(userFolderPath).remove();
		session.save();
		// The identity home lives in system; each content workspace also holds the
		// user's working area (Desktop), created lazily — remove those too.
		removeWorkspaceHomes(session, username);
		Map<String, Object> result = new HashMap<>();
		result.put("username", username);
		result.put("errors", null);
		return result;
	}

	private static Object changePassword(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		String currentPassword = (String) input.get("currentPassword");
		String newPassword = (String) input.get("newPassword");
		if (username == null || newPassword == null) {
			return errorResult("username and newPassword are required", "INVALID_INPUT");
		}
		String profilePath = USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorResult("User not found: " + username, "NOT_FOUND");
		}
		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		if (currentPassword != null) {
			if (!contentNode.hasProperty("password")
					|| !verifyPassword(currentPassword, contentNode.getProperty("password").getString())) {
				return errorResult("Current password is incorrect", "INVALID_CREDENTIALS");
			}
		}
		contentNode.setProperty("password", "{bcrypt}" + BCrypt.hash(newPassword));
		session.save();
		Node savedProfile = session.getNode(profilePath);
		return userResult(mapUser(session, username, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	private static Object resetPassword(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		String newPassword = (String) input.get("newPassword");
		if (username == null || newPassword == null) {
			return errorResult("username and newPassword are required", "INVALID_INPUT");
		}
		String profilePath = USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorResult("User not found: " + username, "NOT_FOUND");
		}
		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		contentNode.setProperty("password", "{bcrypt}" + BCrypt.hash(newPassword));
		session.save();
		Node savedProfile = session.getNode(profilePath);
		return userResult(mapUser(session, username, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	// ---- role-assignment mutations (mirror IdpMutationExecutor) ------------

	@SuppressWarnings("unchecked")
	private static Object assignRoles(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		List<String> roleIds = (List<String>) input.get("roles");
		if (username == null || roleIds == null) {
			return errorResult("username and roles are required", "INVALID_INPUT");
		}
		// A signed-in user must not change their own roles (e.g. self-removing an
		// admin role and locking themselves out). The UI also blocks this.
		if (username.equals(session.getUserID())) {
			return errorResult("You cannot change the roles of your own account", "SELF_MODIFICATION_FORBIDDEN");
		}
		String profilePath = USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorResult("User not found: " + username, "NOT_FOUND");
		}
		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		List<Value> current = getWeakRefs(contentNode, "roles");
		for (String roleId : roleIds) {
			String rolePath = ROLES_ROOT + "/" + roleId + "/profile";
			if (!session.nodeExists(rolePath)) {
				continue;
			}
			Node roleProfile = session.getNode(rolePath);
			ensureMixReferenceable(roleProfile);
			if (!hasRef(current, roleProfile.getIdentifier())) {
				current.add(session.getValueFactory().createValue(roleProfile, true));
			}
		}
		contentNode.setProperty("roles", current.toArray(new Value[0]));
		session.save();
		Node savedProfile = session.getNode(profilePath);
		return userResult(mapUser(session, username, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	@SuppressWarnings("unchecked")
	private static Object revokeRoles(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		List<String> roleIds = (List<String>) input.get("roles");
		if (username == null || roleIds == null) {
			return errorResult("username and roles are required", "INVALID_INPUT");
		}
		// A signed-in user must not change their own roles (self-lockout). The UI
		// also blocks this.
		if (username.equals(session.getUserID())) {
			return errorResult("You cannot change the roles of your own account", "SELF_MODIFICATION_FORBIDDEN");
		}
		String profilePath = USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorResult("User not found: " + username, "NOT_FOUND");
		}
		List<String> uuidsToRemove = new ArrayList<>();
		for (String roleId : roleIds) {
			String rolePath = ROLES_ROOT + "/" + roleId + "/profile";
			if (session.nodeExists(rolePath)) {
				uuidsToRemove.add(session.getNode(rolePath).getIdentifier());
			}
		}
		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		List<Value> current = getWeakRefs(contentNode, "roles");
		current.removeIf(v -> {
			try {
				return uuidsToRemove.contains(v.getString());
			} catch (Exception ex) {
				return false;
			}
		});
		if (current.isEmpty()) {
			if (contentNode.hasProperty("roles")) {
				contentNode.getProperty("roles").remove();
			}
		} else {
			contentNode.setProperty("roles", current.toArray(new Value[0]));
		}
		session.save();
		Node savedProfile = session.getNode(profilePath);
		return userResult(mapUser(session, username, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	// ---- role mutations (mirror IdpMutationExecutor) -----------------------

	private static Object createRole(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String name = (String) input.get("name");
		String parentRoleId = (String) input.get("parentRoleId");
		if (name == null || name.isEmpty()) {
			return errorResult("name is required", "INVALID_INPUT");
		}
		String roleFolderPath = (parentRoleId != null && !parentRoleId.isEmpty())
				? ROLES_ROOT + "/" + parentRoleId + "/" + name
				: ROLES_ROOT + "/" + name;
		if (session.nodeExists(roleFolderPath)) {
			return errorResult("Role already exists", "ALREADY_EXISTS");
		}
		Node parentFolder;
		if (parentRoleId != null && !parentRoleId.isEmpty()) {
			String parentPath = ROLES_ROOT + "/" + parentRoleId;
			if (!session.nodeExists(parentPath)) {
				return errorResult("Parent role not found: " + parentRoleId, "NOT_FOUND");
			}
			parentFolder = session.getNode(parentPath);
		} else {
			parentFolder = ensureFolder(session, ROLES_ROOT);
		}
		Node roleFolder = JCRs.getOrCreateFolder(parentFolder, name);
		Node profileFile = JCRs.createFile(roleFolder, "profile");
		profileFile.addMixin("mix:referenceable");
		JCRs.setProperty(profileFile, "jcr:mimeType", "application/vnd.webtop.role");
		Node contentNode = JCRs.getContentNode(profileFile);
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "description", input);
		session.save();
		String roleId = roleFolderPath.substring(ROLES_ROOT.length() + 1);
		Node savedProfile = session.getNode(roleFolderPath + "/profile");
		return roleResult(mapRole(session, roleId, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	private static Object updateRole(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String roleId = (String) input.get("roleId");
		if (roleId == null) {
			return errorResult("roleId is required", "INVALID_INPUT");
		}
		String profilePath = ROLES_ROOT + "/" + roleId + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorResult("Role not found: " + roleId, "NOT_FOUND");
		}
		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "description", input);
		session.save();
		Node savedProfile = session.getNode(profilePath);
		return roleResult(mapRole(session, roleId, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	private static Object deleteRole(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String roleId = (String) input.get("roleId");
		boolean removeFromUsers = getBoolInput(input, "removeFromUsers", false);
		boolean recursive = getBoolInput(input, "recursive", false);
		if (roleId == null) {
			return errorResult("roleId is required", "INVALID_INPUT");
		}
		String roleFolderPath = ROLES_ROOT + "/" + roleId;
		if (!session.nodeExists(roleFolderPath)) {
			return errorResult("Role not found: " + roleId, "NOT_FOUND");
		}
		Node roleFolder = session.getNode(roleFolderPath);
		if (!recursive && hasChildRoles(roleFolder)) {
			return errorResult("Role has children. Use recursive=true to delete", "HAS_CHILDREN");
		}
		if (removeFromUsers) {
			removeRoleSubtreeFromUsers(roleFolder);
		}
		roleFolder.remove();
		session.save();
		Map<String, Object> result = new HashMap<>();
		result.put("roleId", roleId);
		result.put("errors", null);
		return result;
	}

	/** Strips a role's profile (and its subtree's) from every user's weak references. */
	private static void removeRoleSubtreeFromUsers(Node roleFolder) throws Exception {
		if (roleFolder.hasNode("profile")) {
			removeRoleFromAllUsers(roleFolder.getNode("profile"));
		}
		NodeIterator it = roleFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if (!"profile".equals(child.getName()) && child.hasNode("profile")) {
				removeRoleSubtreeFromUsers(child);
			}
		}
	}

	private static void removeRoleFromAllUsers(Node roleProfile) throws Exception {
		if (!roleProfile.isNodeType("mix:referenceable")) {
			return;
		}
		String roleUuid = roleProfile.getIdentifier();
		PropertyIterator refs = roleProfile.getWeakReferences("roles");
		while (refs.hasNext()) {
			Property refProp = refs.nextProperty();
			List<Value> current = new ArrayList<>(Arrays.asList(refProp.getValues()));
			current.removeIf(v -> {
				try {
					return roleUuid.equals(v.getString());
				} catch (Exception ex) {
					return false;
				}
			});
			if (current.isEmpty()) {
				refProp.remove();
			} else {
				refProp.setValue(current.toArray(new Value[0]));
			}
		}
	}

	/** Success result {role, errors: null} for the role create/update mutations. */
	private static Map<String, Object> roleResult(Map<String, Object> role) {
		Map<String, Object> result = new HashMap<>();
		result.put("role", role);
		result.put("errors", null);
		return result;
	}

	// ---- group mutations (mirror IdpMutationExecutor) ----------------------

	private static Object createGroup(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String parentGroupId = (String) input.get("parentGroupId");
		String name = (String) input.get("name");
		if (name == null) {
			return errorResult("name is required", "INVALID_INPUT");
		}
		String groupFolderPath = (parentGroupId != null && !parentGroupId.isEmpty())
				? GROUPS_ROOT + "/" + parentGroupId + "/" + name
				: GROUPS_ROOT + "/" + name;
		try {
			Workspace.class.cast(session.getWorkspace()).getPrincipalProvider().getPrincipal(name);
			return errorResult("User or group with the same name already exists: " + name, "ALREADY_EXISTS");
		} catch (PrincipalNotFoundException ignore) {
			// expected when the principal does not exist — continue
		}
		Node parentFolder;
		if (parentGroupId != null && !parentGroupId.isEmpty()) {
			String parentPath = GROUPS_ROOT + "/" + parentGroupId;
			if (!session.nodeExists(parentPath)) {
				return errorResult("Parent group not found: " + parentGroupId, "NOT_FOUND");
			}
			parentFolder = session.getNode(parentPath);
		} else {
			parentFolder = ensureFolder(session, GROUPS_ROOT);
		}
		Node groupFolder = JCRs.getOrCreateFolder(parentFolder, name);
		Node profileFile = JCRs.createFile(groupFolder, "profile");
		profileFile.addMixin("mix:referenceable");
		JCRs.setProperty(profileFile, "jcr:mimeType", "application/vnd.webtop.group");
		Node contentNode = JCRs.getContentNode(profileFile);
		contentNode.setProperty("identifier", name);
		contentNode.setProperty("isGroup", true);
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "description", input);
		session.save();
		String groupId = groupFolderPath.substring(GROUPS_ROOT.length() + 1);
		Node savedProfile = session.getNode(groupFolderPath + "/profile");
		return groupResult(mapGroup(session, groupId, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	private static Object updateGroup(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String groupId = (String) input.get("groupId");
		if (groupId == null) {
			return errorResult("groupId is required", "INVALID_INPUT");
		}
		String profilePath = GROUPS_ROOT + "/" + groupId + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorResult("Group not found: " + groupId, "NOT_FOUND");
		}
		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "description", input);
		session.save();
		Node savedProfile = session.getNode(profilePath);
		return groupResult(mapGroup(session, groupId, savedProfile, JCRs.getContentNode(savedProfile)));
	}

	private static Object deleteGroup(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String groupId = (String) input.get("groupId");
		boolean recursive = getBoolInput(input, "recursive", false);
		if (groupId == null) {
			return errorResult("groupId is required", "INVALID_INPUT");
		}
		String groupFolderPath = GROUPS_ROOT + "/" + groupId;
		if (!session.nodeExists(groupFolderPath)) {
			return errorResult("Group not found: " + groupId, "NOT_FOUND");
		}
		Node groupFolder = session.getNode(groupFolderPath);
		if (!recursive && hasChildGroups(groupFolder)) {
			return errorResult("Group has children. Use recursive=true to delete", "HAS_CHILDREN");
		}
		groupFolder.remove();
		session.save();
		Map<String, Object> result = new HashMap<>();
		result.put("groupId", groupId);
		result.put("errors", null);
		return result;
	}

	private static Object moveGroup(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String groupId = (String) input.get("groupId");
		String newParentGroupId = (String) input.get("newParentGroupId");
		String newName = (String) input.get("newName");
		if (groupId == null) {
			return errorResult("groupId is required", "INVALID_INPUT");
		}
		String srcPath = GROUPS_ROOT + "/" + groupId;
		if (!session.nodeExists(srcPath)) {
			return errorResult("Group not found: " + groupId, "NOT_FOUND");
		}
		String currentName = groupId.contains("/") ? groupId.substring(groupId.lastIndexOf('/') + 1) : groupId;
		String finalName = (newName != null && !newName.isEmpty()) ? newName : currentName;
		String destParentPath;
		if (newParentGroupId != null && !newParentGroupId.isEmpty()) {
			destParentPath = GROUPS_ROOT + "/" + newParentGroupId;
			if (!session.nodeExists(destParentPath)) {
				return errorResult("New parent group not found: " + newParentGroupId, "NOT_FOUND");
			}
		} else {
			destParentPath = GROUPS_ROOT;
		}
		String destPath = destParentPath + "/" + finalName;
		session.move(srcPath, destPath);
		session.save();
		String newGroupId = destPath.substring(GROUPS_ROOT.length() + 1);
		Node savedProfile = session.getNode(destPath + "/profile");
		Map<String, Object> result = new HashMap<>();
		result.put("group", mapGroup(session, newGroupId, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("previousGroupId", groupId);
		result.put("errors", null);
		return result;
	}

	/** Success result {group, errors: null} for the group create/update mutations. */
	private static Map<String, Object> groupResult(Map<String, Object> group) {
		Map<String, Object> result = new HashMap<>();
		result.put("group", group);
		result.put("errors", null);
		return result;
	}

	// ---- group-membership & preference mutations (mirror IdpMutationExecutor) ----

	@SuppressWarnings("unchecked")
	private static Object addGroupMembers(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String groupId = (String) input.get("groupId");
		List<String> usernames = (List<String>) input.get("usernames");
		if (groupId == null || usernames == null) {
			return errorResult("groupId and usernames are required", "INVALID_INPUT");
		}
		String groupProfilePath = GROUPS_ROOT + "/" + groupId + "/profile";
		if (!session.nodeExists(groupProfilePath)) {
			return errorResult("Group not found: " + groupId, "NOT_FOUND");
		}
		Node groupProfileNode = session.getNode(groupProfilePath);
		ensureMixReferenceable(groupProfileNode);
		String groupUuid = groupProfileNode.getIdentifier();
		for (String username : usernames) {
			String userProfilePath = USERS_ROOT + "/" + username + "/profile";
			if (!session.nodeExists(userProfilePath)) {
				continue;
			}
			Node userContent = JCRs.getContentNode(session.getNode(userProfilePath));
			List<Value> current = getWeakRefs(userContent, "memberOf");
			if (!hasRef(current, groupUuid)) {
				current.add(session.getValueFactory().createValue(groupProfileNode, true));
				userContent.setProperty("memberOf", current.toArray(new Value[0]));
			}
		}
		session.save();
		Node savedGroupProfile = session.getNode(groupProfilePath);
		return groupResult(mapGroup(session, groupId, savedGroupProfile, JCRs.getContentNode(savedGroupProfile)));
	}

	@SuppressWarnings("unchecked")
	private static Object removeGroupMembers(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String groupId = (String) input.get("groupId");
		List<String> usernames = (List<String>) input.get("usernames");
		if (groupId == null || usernames == null) {
			return errorResult("groupId and usernames are required", "INVALID_INPUT");
		}
		String groupProfilePath = GROUPS_ROOT + "/" + groupId + "/profile";
		if (!session.nodeExists(groupProfilePath)) {
			return errorResult("Group not found: " + groupId, "NOT_FOUND");
		}
		Node groupProfileNode = session.getNode(groupProfilePath);
		ensureMixReferenceable(groupProfileNode);
		String groupUuid = groupProfileNode.getIdentifier();
		for (String username : usernames) {
			String userProfilePath = USERS_ROOT + "/" + username + "/profile";
			if (!session.nodeExists(userProfilePath)) {
				continue;
			}
			Node userContent = JCRs.getContentNode(session.getNode(userProfilePath));
			if (!userContent.hasProperty("memberOf")) {
				continue;
			}
			List<Value> current = getWeakRefs(userContent, "memberOf");
			current.removeIf(v -> {
				try {
					return groupUuid.equals(v.getString());
				} catch (Exception ex) {
					return false;
				}
			});
			if (current.isEmpty()) {
				userContent.getProperty("memberOf").remove();
			} else {
				userContent.setProperty("memberOf", current.toArray(new Value[0]));
			}
		}
		session.save();
		Node savedGroupProfile = session.getNode(groupProfilePath);
		return groupResult(mapGroup(session, groupId, savedGroupProfile, JCRs.getContentNode(savedGroupProfile)));
	}

	@SuppressWarnings("unchecked")
	private static Object updatePreferences(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		String category = (String) input.get("category");
		Map<String, Object> data = (Map<String, Object>) input.get("data");
		if (username == null || category == null) {
			return errorResult("username and category are required", "INVALID_INPUT");
		}
		if (data == null) {
			return errorResult("data is required", "INVALID_INPUT");
		}
		// Prevent path traversal in the category name.
		if (!category.matches("[a-z][a-z0-9-]*")) {
			return errorResult("Invalid category name: " + category, "INVALID_INPUT");
		}
		String userFolderPath = USERS_ROOT + "/" + username;
		if (!session.nodeExists(userFolderPath)) {
			return errorResult("User not found: " + username, "NOT_FOUND");
		}
		Node userFolder = session.getNode(userFolderPath);
		Node preferencesFolder = JCRs.getOrCreateFolder(userFolder, "preferences");
		Node categoryFile = preferencesFolder.hasNode(category)
				? preferencesFolder.getNode(category)
				: JCRs.createFile(preferencesFolder, category);
		JCRs.setProperty(categoryFile, "jcr:mimeType", "application/vnd.webtop." + category);
		Node contentNode = JCRs.getContentNode(categoryFile);
		for (Map.Entry<String, Object> entry : data.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof Number) {
				JCRs.setProperty(categoryFile, entry.getKey(), new BigDecimal(value.toString()));
			} else {
				JCRs.setProperty(categoryFile, entry.getKey(), value);
			}
		}
		session.save();
		// Read back the saved (non-jcr) properties.
		Map<String, Object> savedData = new LinkedHashMap<>();
		PropertyIterator props = contentNode.getProperties();
		while (props.hasNext()) {
			Property prop = props.nextProperty();
			String propName = prop.getName();
			if (propName.startsWith("jcr:")) {
				continue;
			}
			if (!prop.isMultiple()) {
				if (prop.getType() == PropertyType.STRING) {
					savedData.put(propName, prop.getString());
				} else if (prop.getType() == PropertyType.BOOLEAN) {
					savedData.put(propName, prop.getBoolean());
				} else if (prop.getType() == PropertyType.DOUBLE || prop.getType() == PropertyType.LONG
						|| prop.getType() == PropertyType.DECIMAL) {
					// Numbers are written via setProperty(BigDecimal), so they persist as
					// DECIMAL; without this case every numeric preference (e.g. fontSize)
					// was silently dropped on read-back. Preserve integer-ness so a whole
					// number mirrors what the client sent (14, not 14.0).
					double d = prop.getDouble();
					boolean whole = !Double.isInfinite(d) && d == Math.rint(d);
					savedData.put(propName, whole ? (Object) (long) d : (Object) d);
				}
			}
		}
		Map<String, Object> result = new HashMap<>();
		result.put("category", category);
		result.put("data", savedData);
		result.put("errors", null);
		return result;
	}

	private static void removeWorkspaceHomes(Session session, String username) throws Exception {
		for (String workspaceName : session.getWorkspace().getAccessibleWorkspaceNames()) {
			if ("system".equals(workspaceName)) {
				continue;
			}
			try {
				Session workspaceSession = CmsService.getRepository().login(new CmsServiceCredentials(), workspaceName);
				try {
					String path = USERS_ROOT + "/" + username;
					if (workspaceSession.nodeExists(path)) {
						workspaceSession.getNode(path).remove();
						workspaceSession.save();
					}
				} finally {
					try {
						workspaceSession.logout();
					} catch (Throwable ignore) {}
				}
			} catch (Throwable ex) {
				CmsService.getLogger(PlatformIdpWiringContributor.class).warn(
						"An error occurred while removing the user home directory: " + username + " (" + workspaceName + ")",
						ex);
			}
		}
		WorkspaceUserHomes.invalidateUser(username);
	}

	// ---- mutation helpers (mirror IdpMutationExecutor) ---------------------

	/** Success result {user, errors: null} for the user mutations that return a User. */
	private static Map<String, Object> userResult(Map<String, Object> user) {
		Map<String, Object> result = new HashMap<>();
		result.put("user", user);
		result.put("errors", null);
		return result;
	}

	/**
	 * In-band validation error result. Carries every IdP-payload key as null plus a
	 * single {field, message, code} error; each payload type resolves only the fields
	 * it declares, so one shape serves every IdP mutation.
	 */
	private static Map<String, Object> errorResult(String message, String code) {
		Map<String, Object> error = new HashMap<>();
		error.put("field", null);
		error.put("message", message);
		error.put("code", code);

		Map<String, Object> result = new HashMap<>();
		result.put("user", null);
		result.put("role", null);
		result.put("group", null);
		result.put("username", null);
		result.put("roleId", null);
		result.put("groupId", null);
		result.put("previousGroupId", null);
		result.put("errors", List.of(error));
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> inputArg(DataFetchingEnvironment environment) {
		Object input = environment.getArgument("input");
		if (input instanceof Map) {
			return (Map<String, Object>) input;
		}
		return new HashMap<>();
	}

	private static boolean getBoolInput(Map<String, Object> input, String name, boolean defaultValue) {
		Object v = input.get(name);
		return (v instanceof Boolean) ? (Boolean) v : defaultValue;
	}

	private static Node ensureFolder(Session session, String path) throws Exception {
		if (session.nodeExists(path)) {
			return session.getNode(path);
		}
		String[] parts = path.substring(1).split("/");
		Node current = session.getRootNode();
		for (String part : parts) {
			current = JCRs.getOrCreateFolder(current, part);
		}
		return current;
	}

	private static void setStringIfPresent(Node node, String name, Map<String, Object> input) throws Exception {
		if (input.get(name) instanceof String) {
			node.setProperty(name, (String) input.get(name));
		}
	}

	private static void setRoleReferences(Session session, Node contentNode, List<String> roleIds) throws Exception {
		List<Value> values = new ArrayList<>();
		for (String roleId : roleIds) {
			String rolePath = ROLES_ROOT + "/" + roleId + "/profile";
			if (!session.nodeExists(rolePath)) {
				continue;
			}
			Node roleProfile = session.getNode(rolePath);
			ensureMixReferenceable(roleProfile);
			values.add(session.getValueFactory().createValue(roleProfile, true));
		}
		if (!values.isEmpty()) {
			contentNode.setProperty("roles", values.toArray(new Value[0]));
		}
	}

	private static void setGroupReferences(Session session, Node contentNode, List<String> groupIds) throws Exception {
		List<Value> values = new ArrayList<>();
		for (String groupId : groupIds) {
			String groupPath = GROUPS_ROOT + "/" + groupId + "/profile";
			if (!session.nodeExists(groupPath)) {
				continue;
			}
			Node groupProfile = session.getNode(groupPath);
			ensureMixReferenceable(groupProfile);
			values.add(session.getValueFactory().createValue(groupProfile, true));
		}
		if (!values.isEmpty()) {
			contentNode.setProperty("memberOf", values.toArray(new Value[0]));
		}
	}

	private static void ensureMixReferenceable(Node node) throws Exception {
		if (!node.isNodeType("mix:referenceable")) {
			node.addMixin("mix:referenceable");
		}
	}

	private static List<Value> getWeakRefs(Node node, String propertyName) throws Exception {
		List<Value> values = new ArrayList<>();
		if (node.hasProperty(propertyName)) {
			for (Value v : node.getProperty(propertyName).getValues()) {
				values.add(v);
			}
		}
		return values;
	}

	private static boolean hasRef(List<Value> values, String uuid) {
		for (Value v : values) {
			try {
				if (uuid.equals(v.getString())) {
					return true;
				}
			} catch (Exception ignore) {}
		}
		return false;
	}

	private static boolean verifyPassword(String input, String stored) {
		if (stored.startsWith("{bcrypt}")) {
			return BCrypt.verify(input, stored.substring("{bcrypt}".length()));
		}
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			String hex = sb.toString();
			if (stored.startsWith("{sha256}")) {
				return hex.equalsIgnoreCase(stored.substring("{sha256}".length()));
			}
			return hex.equalsIgnoreCase(stored);
		} catch (Exception ex) {
			return false;
		}
	}

	// ---- mappers (mirror IdpQueryExecutor) ---------------------------------

	static Map<String, Object> mapUser(Session session, String username, Node profileNode, Node contentNode)
			throws Exception {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("sn", contentNode.hasProperty("sn") ? contentNode.getProperty("sn").getString() : null);
		user.put("givenName",
				contentNode.hasProperty("givenName") ? contentNode.getProperty("givenName").getString() : null);
		user.put("displayName",
				contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
		user.put("mail", contentNode.hasProperty("mail") ? contentNode.getProperty("mail").getString() : null);
		user.put("enabled", contentNode.hasProperty("enabled") ? contentNode.getProperty("enabled").getBoolean() : true);
		// Service accounts are non-interactive identities; the marker defaults to false.
		user.put("isService",
				contentNode.hasProperty("isService") ? contentNode.getProperty("isService").getBoolean() : false);

		// Roles via WeakReference.
		List<Map<String, Object>> roles = new ArrayList<>();
		if (contentNode.hasProperty("roles")) {
			for (Value v : contentNode.getProperty("roles").getValues()) {
				try {
					Node roleProfileNode = session.getNodeByIdentifier(v.getString());
					roles.add(mapRole(session, getRoleId(roleProfileNode), roleProfileNode,
							JCRs.getContentNode(roleProfileNode)));
				} catch (ItemNotFoundException ignore) {}
			}
		}
		user.put("roles", roles);

		// Groups via WeakReference (basic projection to avoid deep recursion).
		List<Map<String, Object>> memberOf = new ArrayList<>();
		if (contentNode.hasProperty("memberOf")) {
			for (Value v : contentNode.getProperty("memberOf").getValues()) {
				try {
					Node groupProfileNode = session.getNodeByIdentifier(v.getString());
					memberOf.add(mapGroupBasic(session, getGroupId(groupProfileNode), groupProfileNode,
							JCRs.getContentNode(groupProfileNode)));
				} catch (ItemNotFoundException ignore) {}
			}
		}
		user.put("memberOf", memberOf);

		// effectiveGroups: direct groups + ancestors (deduplicated).
		List<Map<String, Object>> effectiveGroups = new ArrayList<>();
		for (Map<String, Object> group : memberOf) {
			String gId = (String) group.get("groupId");
			if (!containsGroup(effectiveGroups, gId)) {
				effectiveGroups.add(group);
			}
			collectAncestorGroups(session, gId, effectiveGroups);
		}
		user.put("effectiveGroups", effectiveGroups);

		user.put("hasAvatar", false);
		user.put("avatarUrl", null);
		user.put("lastLogin", null);
		user.put("created", formatDate(profileNode.getProperty("jcr:created").getDate()));
		user.put("lastModified", contentNode.hasProperty("jcr:lastModified")
				? formatDate(contentNode.getProperty("jcr:lastModified").getDate()) : null);
		return user;
	}

	static Map<String, Object> mapRoleBasic(Session session, String roleId, Node profileNode, Node contentNode)
			throws Exception {
		Map<String, Object> role = new HashMap<>();
		role.put("roleId", roleId);
		role.put("name", roleId.contains("/") ? roleId.substring(roleId.lastIndexOf('/') + 1) : roleId);
		role.put("displayName",
				contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
		role.put("description",
				contentNode.hasProperty("description") ? contentNode.getProperty("description").getString() : null);
		role.put("depth", segmentDepth(roleId));
		Node roleFolder = profileNode.getParent();
		role.put("hasChildren", hasChildRoles(roleFolder));
		role.put("descendantCount", countRoleDescendants(roleFolder));
		role.put("created", formatDate(profileNode.getProperty("jcr:created").getDate()));
		role.put("lastModified", contentNode.hasProperty("jcr:lastModified")
				? formatDate(contentNode.getProperty("jcr:lastModified").getDate()) : null);
		return role;
	}

	static Map<String, Object> mapRole(Session session, String roleId, Node profileNode, Node contentNode)
			throws Exception {
		Map<String, Object> role = new HashMap<>();
		role.put("roleId", roleId);
		role.put("name", roleId.contains("/") ? roleId.substring(roleId.lastIndexOf('/') + 1) : roleId);
		role.put("displayName",
				contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
		role.put("description",
				contentNode.hasProperty("description") ? contentNode.getProperty("description").getString() : null);
		role.put("depth", segmentDepth(roleId));

		// Parent role (basic projection to avoid recursion).
		if (roleId.contains("/")) {
			String parentRoleId = roleId.substring(0, roleId.lastIndexOf('/'));
			String parentProfilePath = ROLES_ROOT + "/" + parentRoleId + "/profile";
			if (session.nodeExists(parentProfilePath)) {
				Node p = session.getNode(parentProfilePath);
				role.put("parent", mapRoleBasic(session, parentRoleId, p, JCRs.getContentNode(p)));
			} else {
				role.put("parent", null);
			}
		} else {
			role.put("parent", null);
		}

		List<Map<String, Object>> ancestors = new ArrayList<>();
		buildRoleAncestors(session, roleId, ancestors);
		role.put("ancestors", ancestors);

		Node roleFolder = profileNode.getParent();
		role.put("hasChildren", hasChildRoles(roleFolder));
		role.put("descendantCount", countRoleDescendants(roleFolder));
		role.put("members", emptyConnection());
		role.put("created", formatDate(profileNode.getProperty("jcr:created").getDate()));
		role.put("lastModified", contentNode.hasProperty("jcr:lastModified")
				? formatDate(contentNode.getProperty("jcr:lastModified").getDate()) : null);
		return role;
	}

	/**
	 * Maps a group with basic fields only (no parent/ancestors/children/members
	 * resolution); used by parent, ancestors and memberOf references to avoid
	 * infinite recursion.
	 */
	static Map<String, Object> mapGroupBasic(Session session, String groupId, Node profileNode, Node contentNode)
			throws Exception {
		Map<String, Object> group = new HashMap<>();
		group.put("groupId", groupId);
		group.put("name", groupId.contains("/") ? groupId.substring(groupId.lastIndexOf('/') + 1) : groupId);
		group.put("displayName",
				contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
		group.put("description",
				contentNode.hasProperty("description") ? contentNode.getProperty("description").getString() : null);
		group.put("depth", segmentDepth(groupId));
		Node groupFolder = profileNode.getParent();
		group.put("hasChildren", hasChildGroups(groupFolder));
		group.put("descendantCount", countDescendants(groupFolder));
		group.put("created", formatDate(profileNode.getProperty("jcr:created").getDate()));
		group.put("lastModified", contentNode.hasProperty("jcr:lastModified")
				? formatDate(contentNode.getProperty("jcr:lastModified").getDate()) : null);
		return group;
	}

	/**
	 * Maps a group fully (parent + ancestors + child counts), used by the top-level
	 * group queries. {@code members}/{@code effectiveMembers} are empty connections
	 * — exactly as the handmade engine returns them (computing them is an expensive
	 * full scan that the directory UI does not need here).
	 */
	static Map<String, Object> mapGroup(Session session, String groupId, Node profileNode, Node contentNode)
			throws Exception {
		Map<String, Object> group = new HashMap<>();
		group.put("groupId", groupId);
		group.put("name", groupId.contains("/") ? groupId.substring(groupId.lastIndexOf('/') + 1) : groupId);
		group.put("displayName",
				contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
		group.put("description",
				contentNode.hasProperty("description") ? contentNode.getProperty("description").getString() : null);
		group.put("depth", segmentDepth(groupId));

		// Parent group (basic projection to avoid recursion).
		if (groupId.contains("/")) {
			String parentGroupId = groupId.substring(0, groupId.lastIndexOf('/'));
			String parentProfilePath = GROUPS_ROOT + "/" + parentGroupId + "/profile";
			if (session.nodeExists(parentProfilePath)) {
				Node p = session.getNode(parentProfilePath);
				group.put("parent", mapGroupBasic(session, parentGroupId, p, JCRs.getContentNode(p)));
			} else {
				group.put("parent", null);
			}
		} else {
			group.put("parent", null);
		}

		List<Map<String, Object>> ancestors = new ArrayList<>();
		buildGroupAncestors(session, groupId, ancestors);
		group.put("ancestors", ancestors);

		Node groupFolder = profileNode.getParent();
		group.put("hasChildren", hasChildGroups(groupFolder));
		group.put("descendantCount", countDescendants(groupFolder));
		group.put("members", emptyConnection());
		group.put("effectiveMembers", emptyConnection());
		group.put("created", formatDate(profileNode.getProperty("jcr:created").getDate()));
		group.put("lastModified", contentNode.hasProperty("jcr:lastModified")
				? formatDate(contentNode.getProperty("jcr:lastModified").getDate()) : null);
		return group;
	}

	// ---- internal helpers (mirror IdpQueryExecutor) ------------------------

	static String getRoleId(Node roleProfileNode) throws Exception {
		String path = roleProfileNode.getPath();
		return path.substring(ROLES_ROOT.length() + 1, path.length() - "/profile".length());
	}

	static String getGroupId(Node groupProfileNode) throws Exception {
		String path = groupProfileNode.getPath();
		return path.substring(GROUPS_ROOT.length() + 1, path.length() - "/profile".length());
	}

	private static int segmentDepth(String id) {
		int depth = 0;
		for (int i = 0; i < id.length(); i++) {
			if (id.charAt(i) == '/') {
				depth++;
			}
		}
		return depth;
	}

	private static boolean userHasRole(Session session, Node contentNode, String roleId) throws Exception {
		if (!contentNode.hasProperty("roles")) {
			return false;
		}
		String rolePath = ROLES_ROOT + "/" + roleId + "/profile";
		if (!session.nodeExists(rolePath)) {
			return false;
		}
		String roleUuid = session.getNode(rolePath).getIdentifier();
		for (Value v : contentNode.getProperty("roles").getValues()) {
			if (roleUuid.equals(v.getString())) {
				return true;
			}
		}
		return false;
	}

	private static boolean userInGroup(Session session, Node contentNode, String groupId, boolean includeDescendants)
			throws Exception {
		if (!contentNode.hasProperty("memberOf")) {
			return false;
		}
		String groupPath = GROUPS_ROOT + "/" + groupId + "/profile";
		if (!session.nodeExists(groupPath)) {
			return false;
		}
		String groupUuid = session.getNode(groupPath).getIdentifier();
		for (Value v : contentNode.getProperty("memberOf").getValues()) {
			if (groupUuid.equals(v.getString())) {
				return true;
			}
		}
		if (includeDescendants && session.nodeExists(GROUPS_ROOT + "/" + groupId)) {
			return userInDescendantGroup(contentNode, session.getNode(GROUPS_ROOT + "/" + groupId));
		}
		return false;
	}

	private static boolean userInDescendantGroup(Node contentNode, Node groupFolder) throws Exception {
		NodeIterator it = groupFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if ("profile".equals(child.getName())) {
				continue;
			}
			if (child.hasNode("profile")) {
				String childUuid = child.getNode("profile").getIdentifier();
				if (contentNode.hasProperty("memberOf")) {
					for (Value v : contentNode.getProperty("memberOf").getValues()) {
						if (childUuid.equals(v.getString())) {
							return true;
						}
					}
				}
				if (userInDescendantGroup(contentNode, child)) {
					return true;
				}
			}
		}
		return false;
	}

	private static void buildRoleAncestors(Session session, String roleId, List<Map<String, Object>> ancestors)
			throws Exception {
		String[] segments = roleId.split("/");
		for (int i = 0; i < segments.length - 1; i++) {
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j <= i; j++) {
				if (j > 0) {
					sb.append("/");
				}
				sb.append(segments[j]);
			}
			String ancestorId = sb.toString();
			String profilePath = ROLES_ROOT + "/" + ancestorId + "/profile";
			if (session.nodeExists(profilePath)) {
				Node p = session.getNode(profilePath);
				ancestors.add(mapRoleBasic(session, ancestorId, p, JCRs.getContentNode(p)));
			}
		}
	}

	private static void collectAncestorGroups(Session session, String groupId, List<Map<String, Object>> result)
			throws Exception {
		if (!groupId.contains("/")) {
			return;
		}
		String[] segments = groupId.split("/");
		for (int i = segments.length - 1; i >= 1; i--) {
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j < i; j++) {
				if (j > 0) {
					sb.append("/");
				}
				sb.append(segments[j]);
			}
			String ancestorId = sb.toString();
			if (!containsGroup(result, ancestorId)) {
				String profilePath = GROUPS_ROOT + "/" + ancestorId + "/profile";
				if (session.nodeExists(profilePath)) {
					Node p = session.getNode(profilePath);
					result.add(mapGroupBasic(session, ancestorId, p, JCRs.getContentNode(p)));
				}
			}
		}
	}

	private static boolean containsGroup(List<Map<String, Object>> groups, String groupId) {
		for (Map<String, Object> g : groups) {
			if (groupId.equals(g.get("groupId"))) {
				return true;
			}
		}
		return false;
	}

	static boolean hasChildRoles(Node roleFolder) throws Exception {
		NodeIterator it = roleFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if (!"profile".equals(child.getName()) && child.hasNode("profile")) {
				return true;
			}
		}
		return false;
	}

	static int countRoleDescendants(Node roleFolder) throws Exception {
		int count = 0;
		NodeIterator it = roleFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if ("profile".equals(child.getName())) {
				continue;
			}
			if (child.hasNode("profile")) {
				count++;
				count += countRoleDescendants(child);
			}
		}
		return count;
	}

	static boolean hasChildGroups(Node groupFolder) throws Exception {
		NodeIterator it = groupFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if (!"profile".equals(child.getName()) && child.hasNode("profile")) {
				return true;
			}
		}
		return false;
	}

	static int countDescendants(Node groupFolder) throws Exception {
		int count = 0;
		NodeIterator it = groupFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if ("profile".equals(child.getName())) {
				continue;
			}
			if (child.hasNode("profile")) {
				count++;
				count += countDescendants(child);
			}
		}
		return count;
	}

	// ---- connection / cursor / arg helpers ---------------------------------

	@FunctionalInterface
	interface NodeMapperFn {
		Map<String, Object> map(Node[] entry) throws Exception;
	}

	/** Relay connection over the full list (cursor = base64("cursor:" + index)); returns the connection value. */
	static Map<String, Object> buildConnection(List<Node[]> entries, int first, String afterCursor, NodeMapperFn mapper)
			throws Exception {
		int totalCount = entries.size();
		int startPosition = 0;
		if (afterCursor != null && !afterCursor.isEmpty()) {
			startPosition = decodeCursor(afterCursor) + 1;
		}
		List<Map<String, Object>> edges = new ArrayList<>();
		int endPosition = Math.min(startPosition + first, totalCount);
		for (int i = startPosition; i < endPosition; i++) {
			Map<String, Object> edge = new HashMap<>();
			edge.put("node", mapper.map(entries.get(i)));
			edge.put("cursor", encodeCursor(i));
			edges.add(edge);
		}
		Map<String, Object> pageInfo = new HashMap<>();
		pageInfo.put("hasNextPage", endPosition < totalCount);
		pageInfo.put("hasPreviousPage", startPosition > 0);
		pageInfo.put("startCursor", edges.isEmpty() ? null : encodeCursor(startPosition));
		pageInfo.put("endCursor", edges.isEmpty() ? null : encodeCursor(endPosition - 1));
		Map<String, Object> connection = new HashMap<>();
		connection.put("edges", edges);
		connection.put("pageInfo", pageInfo);
		connection.put("totalCount", totalCount);
		return connection;
	}

	/** An empty Relay connection (also the empty member-set for a role/group). */
	static Map<String, Object> emptyConnection() {
		Map<String, Object> pageInfo = new HashMap<>();
		pageInfo.put("hasNextPage", false);
		pageInfo.put("hasPreviousPage", false);
		pageInfo.put("startCursor", null);
		pageInfo.put("endCursor", null);
		Map<String, Object> connection = new HashMap<>();
		connection.put("edges", new ArrayList<>());
		connection.put("pageInfo", pageInfo);
		connection.put("totalCount", 0);
		return connection;
	}

	static String encodeCursor(int position) {
		return Base64.getEncoder().encodeToString(("cursor:" + position).getBytes(StandardCharsets.UTF_8));
	}

	static int decodeCursor(String cursor) {
		try {
			String decoded = new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
			if (decoded.startsWith("cursor:")) {
				return Integer.parseInt(decoded.substring("cursor:".length()));
			}
		} catch (Exception ignore) {}
		return 0;
	}

	static String formatDate(Calendar cal) {
		return ISO8601.format(cal);
	}

	private static Session callerSession(DataFetchingEnvironment environment) {
		return GraphQLExecutionContext.from(environment).getCallerSession();
	}

	private static int intArg(DataFetchingEnvironment environment, String name, int defaultValue) {
		Object v = environment.getArgument(name);
		if (v instanceof Number) {
			return ((Number) v).intValue();
		}
		return defaultValue;
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformIdpWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("IdP GraphQL schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}

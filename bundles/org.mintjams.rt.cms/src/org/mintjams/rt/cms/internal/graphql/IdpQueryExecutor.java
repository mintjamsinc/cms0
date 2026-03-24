/*
 * Copyright (c) 2024 MintJams Inc.
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

package org.mintjams.rt.cms.internal.graphql;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;
import javax.jcr.Value;

import org.mintjams.jcr.util.JCRs;

/**
 * GraphQL Query executor for IdP user/group/role management.
 * Operates against /home/ in the JCR system workspace.
 */
public class IdpQueryExecutor {

	static final String USERS_ROOT = "/home/users";
	static final String ROLES_ROOT = "/home/roles";
	static final String GROUPS_ROOT = "/home/groups";

	private final Session session;

	private static final DateTimeFormatter ISO8601_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);

	public IdpQueryExecutor(Session session) {
		this.session = session;
	}

	// =========================================================================
	// User queries
	// =========================================================================

	public Map<String, Object> executeUserQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String username = getStringVar(variables, "username");

		Map<String, Object> result = new HashMap<>();
		if (username != null) {
			String profilePath = USERS_ROOT + "/" + username + "/profile";
			if (session.nodeExists(profilePath)) {
				Node profileNode = session.getNode(profilePath);
				Node contentNode = JCRs.getContentNode(profileNode);
				result.put("user", mapUser(username, profileNode, contentNode));
			} else {
				result.put("user", null);
			}
		} else {
			result.put("user", null);
		}
		return result;
	}

	public Map<String, Object> executeMeQuery(GraphQLRequest request) throws Exception {
		String username = session.getUserID();
		Map<String, Object> result = new HashMap<>();
		String profilePath = USERS_ROOT + "/" + username + "/profile";
		if (session.nodeExists(profilePath)) {
			Node profileNode = session.getNode(profilePath);
			Node contentNode = JCRs.getContentNode(profileNode);
			result.put("me", mapUser(username, profileNode, contentNode));
		} else {
			result.put("me", null);
		}
		return result;
	}

	public Map<String, Object> executeUsersQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		int first = getIntVar(variables, "first", 20);
		String afterCursor = getStringVar(variables, "after");
		String query = getStringVar(variables, "query");
		String roleId = getStringVar(variables, "roleId");
		String groupId = getStringVar(variables, "groupId");
		boolean includeDescendants = getBoolVar(variables, "includeDescendants", false);

		if (!session.nodeExists(USERS_ROOT)) {
			return buildEmptyConnection("users");
		}

		List<Node[]> entries = new ArrayList<>();
		NodeIterator it = session.getNode(USERS_ROOT).getNodes();
		while (it.hasNext()) {
			Node userFolder = it.nextNode();
			if (!userFolder.hasNode("profile")) continue;
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
				if (!matches) continue;
			}

			// Filter: roleId
			if (roleId != null && !roleId.isEmpty()) {
				if (!userHasRole(contentNode, roleId)) continue;
			}

			// Filter: groupId
			if (groupId != null && !groupId.isEmpty()) {
				if (!userInGroup(contentNode, groupId, includeDescendants)) continue;
			}

			entries.add(new Node[] { profileNode, contentNode });
		}

		return buildConnection("users", entries, first, afterCursor, entry -> {
			String uname = entry[0].getParent().getName();
			return mapUser(uname, entry[0], entry[1]);
		});
	}

	// =========================================================================
	// Role queries
	// =========================================================================

	public Map<String, Object> executeRoleQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String roleId = getStringVar(variables, "roleId");

		Map<String, Object> result = new HashMap<>();
		if (roleId != null) {
			String profilePath = ROLES_ROOT + "/" + roleId + "/profile";
			if (session.nodeExists(profilePath)) {
				Node profileNode = session.getNode(profilePath);
				Node contentNode = JCRs.getContentNode(profileNode);
				result.put("role", mapRole(roleId, profileNode, contentNode));
			} else {
				result.put("role", null);
			}
		} else {
			result.put("role", null);
		}
		return result;
	}

	public Map<String, Object> executeRolesQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		int first = getIntVar(variables, "first", 100);
		String afterCursor = getStringVar(variables, "after");
		String query = getStringVar(variables, "query");

		if (!session.nodeExists(ROLES_ROOT)) {
			return buildEmptyConnection("roles");
		}

		List<Node[]> entries = new ArrayList<>();
		NodeIterator it = session.getNode(ROLES_ROOT).getNodes();
		while (it.hasNext()) {
			Node roleFolder = it.nextNode();
			if (!roleFolder.hasNode("profile")) continue;
			Node profileNode = roleFolder.getNode("profile");
			Node contentNode = JCRs.getContentNode(profileNode);
			String rId = roleFolder.getName();

			if (query != null && !query.isEmpty()) {
				boolean matches = rId.contains(query);
				if (!matches && contentNode.hasProperty("displayName")) {
					matches = contentNode.getProperty("displayName").getString().contains(query);
				}
				if (!matches) continue;
			}

			entries.add(new Node[] { profileNode, contentNode });
		}

		return buildConnection("roles", entries, first, afterCursor, entry -> {
			String rId = entry[0].getParent().getName();
			return mapRole(rId, entry[0], entry[1]);
		});
	}

	// =========================================================================
	// Group queries
	// =========================================================================

	public Map<String, Object> executeGroupQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String groupId = getStringVar(variables, "groupId");

		Map<String, Object> result = new HashMap<>();
		if (groupId != null) {
			String profilePath = GROUPS_ROOT + "/" + groupId + "/profile";
			if (session.nodeExists(profilePath)) {
				Node profileNode = session.getNode(profilePath);
				Node contentNode = JCRs.getContentNode(profileNode);
				result.put("group", mapGroup(groupId, profileNode, contentNode));
			} else {
				result.put("group", null);
			}
		} else {
			result.put("group", null);
		}
		return result;
	}

	public Map<String, Object> executeGroupsQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		int first = getIntVar(variables, "first", 50);
		String afterCursor = getStringVar(variables, "after");
		String query = getStringVar(variables, "query");

		if (!session.nodeExists(GROUPS_ROOT)) {
			return buildEmptyConnection("groups");
		}

		List<Node[]> entries = new ArrayList<>();
		NodeIterator it = session.getNode(GROUPS_ROOT).getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if (!child.hasNode("profile")) continue;
			Node profileNode = child.getNode("profile");
			Node contentNode = JCRs.getContentNode(profileNode);
			String gId = child.getName();

			if (query != null && !query.isEmpty()) {
				boolean matches = gId.contains(query);
				if (!matches && contentNode.hasProperty("displayName")) {
					matches = contentNode.getProperty("displayName").getString().contains(query);
				}
				if (!matches) continue;
			}

			entries.add(new Node[] { profileNode, contentNode });
		}

		return buildConnection("groups", entries, first, afterCursor, entry -> {
			String gId = entry[0].getParent().getPath().substring(GROUPS_ROOT.length() + 1);
			return mapGroup(gId, entry[0], entry[1]);
		});
	}

	public Map<String, Object> executeGroupTreeQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> variables = request.getVariables();
		String rootGroupId = getStringVar(variables, "rootGroupId");
		Integer maxDepth = getIntVarOrNull(variables, "maxDepth");

		Node startNode;
		if (rootGroupId != null && !rootGroupId.isEmpty()) {
			String rootPath = GROUPS_ROOT + "/" + rootGroupId;
			if (!session.nodeExists(rootPath)) {
				Map<String, Object> result = new HashMap<>();
				result.put("groupTree", new ArrayList<>());
				return result;
			}
			startNode = session.getNode(rootPath);
		} else {
			if (!session.nodeExists(GROUPS_ROOT)) {
				Map<String, Object> result = new HashMap<>();
				result.put("groupTree", new ArrayList<>());
				return result;
			}
			startNode = session.getNode(GROUPS_ROOT);
		}

		List<Map<String, Object>> tree = buildGroupTree(startNode, maxDepth, 0);

		Map<String, Object> result = new HashMap<>();
		result.put("groupTree", tree);
		return result;
	}

	// =========================================================================
	// Mapping helpers (package-private for use by IdpMutationExecutor)
	// =========================================================================

	Map<String, Object> mapUser(String username, Node profileNode, Node contentNode) throws Exception {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);

		user.put("sn", contentNode.hasProperty("sn") ? contentNode.getProperty("sn").getString() : null);
		user.put("givenName", contentNode.hasProperty("givenName") ? contentNode.getProperty("givenName").getString() : null);
		user.put("displayName", contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
		user.put("mail", contentNode.hasProperty("mail") ? contentNode.getProperty("mail").getString() : null);
		user.put("enabled", contentNode.hasProperty("enabled") ? contentNode.getProperty("enabled").getBoolean() : true);

		// Roles via WeakReference
		List<Map<String, Object>> roles = new ArrayList<>();
		if (contentNode.hasProperty("roles")) {
			for (Value v : contentNode.getProperty("roles").getValues()) {
				try {
					Node roleProfileNode = session.getNodeByIdentifier(v.getString());
					String rId = getRoleId(roleProfileNode);
					roles.add(mapRole(rId, roleProfileNode, JCRs.getContentNode(roleProfileNode)));
				} catch (ItemNotFoundException ignore) {}
			}
		}
		user.put("roles", roles);

		// Groups via WeakReference (use mapGroupBasic to avoid deep recursion)
		List<Map<String, Object>> memberOf = new ArrayList<>();
		if (contentNode.hasProperty("memberOf")) {
			for (Value v : contentNode.getProperty("memberOf").getValues()) {
				try {
					Node groupProfileNode = session.getNodeByIdentifier(v.getString());
					String gId = getGroupId(groupProfileNode);
					memberOf.add(mapGroupBasic(gId, groupProfileNode, JCRs.getContentNode(groupProfileNode)));
				} catch (ItemNotFoundException ignore) {}
			}
		}
		user.put("memberOf", memberOf);

		// effectiveGroups: direct groups + ancestors
		List<Map<String, Object>> effectiveGroups = new ArrayList<>();
		for (Map<String, Object> group : memberOf) {
			String gId = (String) group.get("groupId");
			if (!containsGroup(effectiveGroups, gId)) {
				effectiveGroups.add(group);
			}
			collectAncestorGroups(gId, effectiveGroups);
		}
		user.put("effectiveGroups", effectiveGroups);

		user.put("hasAvatar", false);
		user.put("avatarUrl", null);
		user.put("lastLogin", null);

		// Timestamps
		user.put("created", formatDate(profileNode.getProperty("jcr:created").getDate()));
		user.put("lastModified", contentNode.hasProperty("jcr:lastModified")
				? formatDate(contentNode.getProperty("jcr:lastModified").getDate()) : null);

		return user;
	}

	Map<String, Object> mapRole(String roleId, Node profileNode, Node contentNode) throws Exception {
		Map<String, Object> role = new HashMap<>();
		role.put("roleId", roleId);
		role.put("displayName", contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
		role.put("description", contentNode.hasProperty("description") ? contentNode.getProperty("description").getString() : null);
		role.put("members", buildEmptyUserConnection());
		role.put("created", formatDate(profileNode.getProperty("jcr:created").getDate()));
		role.put("lastModified", contentNode.hasProperty("jcr:lastModified")
				? formatDate(contentNode.getProperty("jcr:lastModified").getDate()) : null);
		return role;
	}

	/**
	 * Map group with basic fields only (no parent/ancestors/children resolution).
	 * Used by parent, ancestors, and memberOf references to avoid infinite recursion.
	 */
	Map<String, Object> mapGroupBasic(String groupId, Node profileNode, Node contentNode) throws Exception {
		Map<String, Object> group = new HashMap<>();
		group.put("groupId", groupId);

		String name = groupId.contains("/") ? groupId.substring(groupId.lastIndexOf('/') + 1) : groupId;
		group.put("name", name);
		group.put("displayName", contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
		group.put("description", contentNode.hasProperty("description") ? contentNode.getProperty("description").getString() : null);

		int depth = 0;
		for (char c : groupId.toCharArray()) {
			if (c == '/') depth++;
		}
		group.put("depth", depth);

		Node groupFolder = profileNode.getParent();
		group.put("hasChildren", hasChildGroups(groupFolder));
		group.put("descendantCount", countDescendants(groupFolder));

		group.put("created", formatDate(profileNode.getProperty("jcr:created").getDate()));
		group.put("lastModified", contentNode.hasProperty("jcr:lastModified")
				? formatDate(contentNode.getProperty("jcr:lastModified").getDate()) : null);

		return group;
	}

	Map<String, Object> mapGroup(String groupId, Node profileNode, Node contentNode) throws Exception {
		Map<String, Object> group = new HashMap<>();
		group.put("groupId", groupId);

		String name = groupId.contains("/") ? groupId.substring(groupId.lastIndexOf('/') + 1) : groupId;
		group.put("name", name);
		group.put("displayName", contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null);
		group.put("description", contentNode.hasProperty("description") ? contentNode.getProperty("description").getString() : null);

		// Depth = number of '/' in groupId
		int depth = 0;
		for (char c : groupId.toCharArray()) {
			if (c == '/') depth++;
		}
		group.put("depth", depth);

		// Parent group (use mapGroupBasic to avoid recursion)
		if (groupId.contains("/")) {
			String parentGroupId = groupId.substring(0, groupId.lastIndexOf('/'));
			String parentProfilePath = GROUPS_ROOT + "/" + parentGroupId + "/profile";
			if (session.nodeExists(parentProfilePath)) {
				Node p = session.getNode(parentProfilePath);
				group.put("parent", mapGroupBasic(parentGroupId, p, JCRs.getContentNode(p)));
			} else {
				group.put("parent", null);
			}
		} else {
			group.put("parent", null);
		}

		// Ancestors (use mapGroupBasic to avoid recursion)
		List<Map<String, Object>> ancestors = new ArrayList<>();
		buildAncestors(groupId, ancestors);
		group.put("ancestors", ancestors);

		// Children
		Node groupFolder = profileNode.getParent();
		List<Node[]> childEntries = new ArrayList<>();
		NodeIterator childIt = groupFolder.getNodes();
		while (childIt.hasNext()) {
			Node child = childIt.nextNode();
			if ("profile".equals(child.getName())) continue;
			if (child.hasNode("profile")) {
				childEntries.add(new Node[] { child.getNode("profile"), JCRs.getContentNode(child.getNode("profile")) });
			}
		}
		group.put("hasChildren", !childEntries.isEmpty());
		group.put("descendantCount", countDescendants(groupFolder));

		// members/effectiveMembers: expensive full scan — return empty connection here
		group.put("members", buildEmptyUserConnection());
		group.put("effectiveMembers", buildEmptyUserConnection());

		group.put("created", formatDate(profileNode.getProperty("jcr:created").getDate()));
		group.put("lastModified", contentNode.hasProperty("jcr:lastModified")
				? formatDate(contentNode.getProperty("jcr:lastModified").getDate()) : null);

		return group;
	}

	// =========================================================================
	// Internal helpers
	// =========================================================================

	String getRoleId(Node roleProfileNode) throws Exception {
		String path = roleProfileNode.getPath(); // /home/roles/administration/profile
		return path.substring(ROLES_ROOT.length() + 1, path.length() - "/profile".length());
	}

	String getGroupId(Node groupProfileNode) throws Exception {
		String path = groupProfileNode.getPath(); // /home/groups/mintjams/sales/profile
		return path.substring(GROUPS_ROOT.length() + 1, path.length() - "/profile".length());
	}

	private boolean userHasRole(Node contentNode, String roleId) throws Exception {
		if (!contentNode.hasProperty("roles")) return false;
		String rolePath = ROLES_ROOT + "/" + roleId + "/profile";
		if (!session.nodeExists(rolePath)) return false;
		String roleUuid = session.getNode(rolePath).getIdentifier();
		for (Value v : contentNode.getProperty("roles").getValues()) {
			if (roleUuid.equals(v.getString())) return true;
		}
		return false;
	}

	private boolean userInGroup(Node contentNode, String groupId, boolean includeDescendants) throws Exception {
		if (!contentNode.hasProperty("memberOf")) return false;
		String groupPath = GROUPS_ROOT + "/" + groupId + "/profile";
		if (!session.nodeExists(groupPath)) return false;
		String groupUuid = session.getNode(groupPath).getIdentifier();
		for (Value v : contentNode.getProperty("memberOf").getValues()) {
			if (groupUuid.equals(v.getString())) return true;
		}
		if (includeDescendants && session.nodeExists(GROUPS_ROOT + "/" + groupId)) {
			return userInDescendantGroup(contentNode, session.getNode(GROUPS_ROOT + "/" + groupId));
		}
		return false;
	}

	private boolean userInDescendantGroup(Node contentNode, Node groupFolder) throws Exception {
		NodeIterator it = groupFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if ("profile".equals(child.getName())) continue;
			if (child.hasNode("profile")) {
				String childUuid = child.getNode("profile").getIdentifier();
				if (contentNode.hasProperty("memberOf")) {
					for (Value v : contentNode.getProperty("memberOf").getValues()) {
						if (childUuid.equals(v.getString())) return true;
					}
				}
				if (userInDescendantGroup(contentNode, child)) return true;
			}
		}
		return false;
	}

	private void buildAncestors(String groupId, List<Map<String, Object>> ancestors) throws Exception {
		String[] segments = groupId.split("/");
		// Exclude the group itself (last segment) from ancestors
		for (int i = 0; i < segments.length - 1; i++) {
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j <= i; j++) {
				if (j > 0) sb.append("/");
				sb.append(segments[j]);
			}
			String ancestorId = sb.toString();
			String profilePath = GROUPS_ROOT + "/" + ancestorId + "/profile";
			if (session.nodeExists(profilePath)) {
				Node p = session.getNode(profilePath);
				ancestors.add(mapGroupBasic(ancestorId, p, JCRs.getContentNode(p)));
			}
		}
	}

	private void collectAncestorGroups(String groupId, List<Map<String, Object>> result) throws Exception {
		if (!groupId.contains("/")) return;
		String[] segments = groupId.split("/");
		// Add from parent up to root
		for (int i = segments.length - 1; i >= 1; i--) {
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j < i; j++) {
				if (j > 0) sb.append("/");
				sb.append(segments[j]);
			}
			String ancestorId = sb.toString();
			if (!containsGroup(result, ancestorId)) {
				String profilePath = GROUPS_ROOT + "/" + ancestorId + "/profile";
				if (session.nodeExists(profilePath)) {
					Node p = session.getNode(profilePath);
					result.add(mapGroupBasic(ancestorId, p, JCRs.getContentNode(p)));
				}
			}
		}
	}

	private boolean containsGroup(List<Map<String, Object>> groups, String groupId) {
		for (Map<String, Object> g : groups) {
			if (groupId.equals(g.get("groupId"))) return true;
		}
		return false;
	}

	private int countDescendants(Node groupFolder) throws Exception {
		int count = 0;
		NodeIterator it = groupFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if ("profile".equals(child.getName())) continue;
			if (child.hasNode("profile")) {
				count++;
				count += countDescendants(child);
			}
		}
		return count;
	}

	private List<Map<String, Object>> buildGroupTree(Node parentFolder, Integer maxDepth, int currentDepth) throws Exception {
		List<Map<String, Object>> nodes = new ArrayList<>();
		if (maxDepth != null && currentDepth >= maxDepth) return nodes;

		NodeIterator it = parentFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if ("profile".equals(child.getName())) continue;
			if (!child.hasNode("profile")) continue;

			Node profileNode = child.getNode("profile");
			Node contentNode = JCRs.getContentNode(profileNode);
			String groupId = child.getPath().substring(GROUPS_ROOT.length() + 1);

			Map<String, Object> treeNode = new HashMap<>();
			treeNode.put("groupId", groupId);
			String tname = groupId.contains("/") ? groupId.substring(groupId.lastIndexOf('/') + 1) : groupId;
			treeNode.put("name", tname);
			treeNode.put("displayName", contentNode.hasProperty("displayName")
					? contentNode.getProperty("displayName").getString() : null);
			treeNode.put("depth", currentDepth);
			treeNode.put("memberCount", 0); // Expensive to compute; left as 0 for tree view

			List<Map<String, Object>> children = buildGroupTree(child, maxDepth, currentDepth + 1);
			treeNode.put("hasChildren", !children.isEmpty() || hasChildGroups(child));
			treeNode.put("children", children);

			nodes.add(treeNode);
		}
		return nodes;
	}

	private boolean hasChildGroups(Node groupFolder) throws Exception {
		NodeIterator it = groupFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if (!"profile".equals(child.getName()) && child.hasNode("profile")) return true;
		}
		return false;
	}

	// =========================================================================
	// Connection building
	// =========================================================================

	@FunctionalInterface
	interface NodeMapper {
		Map<String, Object> map(Node[] entry) throws Exception;
	}

	private Map<String, Object> buildConnection(String key, List<Node[]> entries, int first,
			String afterCursor, NodeMapper mapper) throws Exception {
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

		Map<String, Object> result = new HashMap<>();
		result.put(key, connection);
		return result;
	}

	private Map<String, Object> buildEmptyConnection(String key) {
		Map<String, Object> result = new HashMap<>();
		result.put(key, buildEmptyUserConnection());
		return result;
	}

	Map<String, Object> buildEmptyUserConnection() {
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

	// =========================================================================
	// Cursor helpers
	// =========================================================================

	private String encodeCursor(int position) {
		return Base64.getEncoder().encodeToString(("cursor:" + position).getBytes());
	}

	private int decodeCursor(String cursor) {
		try {
			String decoded = new String(Base64.getDecoder().decode(cursor));
			if (decoded.startsWith("cursor:")) {
				return Integer.parseInt(decoded.substring("cursor:".length()));
			}
		} catch (Exception ignore) {}
		return 0;
	}

	// =========================================================================
	// Variable extraction helpers
	// =========================================================================

	private String getStringVar(Map<String, Object> vars, String name) {
		return vars != null ? (String) vars.get(name) : null;
	}

	private int getIntVar(Map<String, Object> vars, String name, int defaultValue) {
		if (vars == null) return defaultValue;
		Object v = vars.get(name);
		if (v == null) return defaultValue;
		if (v instanceof Number) return ((Number) v).intValue();
		try { return Integer.parseInt(v.toString()); } catch (Exception e) { return defaultValue; }
	}

	private Integer getIntVarOrNull(Map<String, Object> vars, String name) {
		if (vars == null) return null;
		Object v = vars.get(name);
		if (v == null) return null;
		if (v instanceof Number) return ((Number) v).intValue();
		try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
	}

	private boolean getBoolVar(Map<String, Object> vars, String name, boolean defaultValue) {
		if (vars == null) return defaultValue;
		Object v = vars.get(name);
		if (v == null) return defaultValue;
		if (v instanceof Boolean) return (Boolean) v;
		return Boolean.parseBoolean(v.toString());
	}

	// =========================================================================
	// Date formatting
	// =========================================================================

	private String formatDate(Calendar cal) {
		if (cal == null) return null;
		return ISO8601_FORMAT.format(cal.toInstant());
	}
}

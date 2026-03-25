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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.security.Privilege;

import org.mintjams.cms.security.BCrypt;
import org.mintjams.jcr.util.JCRs;

/**
 * GraphQL Mutation executor for IdP user/group/role management.
 * Operates against /home/idp/ in the JCR system workspace.
 */
public class IdpMutationExecutor {

	private final Session session;
	private final IdpQueryExecutor queryExecutor;

	public IdpMutationExecutor(Session session) {
		this.session = session;
		this.queryExecutor = new IdpQueryExecutor(session);
	}

	// =========================================================================
	// User mutations
	// =========================================================================

	public Map<String, Object> executeCreateUser(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String username = (String) input.get("username");
		String password = (String) input.get("password");

		if (username == null || password == null) {
			return errorPayload("createUser", "username and password are required", "INVALID_INPUT");
		}

		String userFolderPath = IdpQueryExecutor.USERS_ROOT + "/" + username;
		if (session.nodeExists(userFolderPath)) {
			return errorPayload("createUser", "User already exists: " + username, "ALREADY_EXISTS");
		}

		Node usersFolder = ensureFolder(IdpQueryExecutor.USERS_ROOT);
		Node userFolder = JCRs.getOrCreateFolder(usersFolder, username);
		Node profileFile = JCRs.createFile(userFolder, "profile");
		JCRs.setProperty(profileFile, "jcr:mimeType", "application/vnd.webtop.user");
		JCRs.setProperty(profileFile, "password", "{bcrypt}" + BCrypt.hash(password));

		Node contentNode = JCRs.getContentNode(profileFile);
		setStringIfPresent(contentNode, "sn", input);
		setStringIfPresent(contentNode, "givenName", input);
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "mail", input);

		boolean enabled = getBoolInput(input, "enabled", true);
		contentNode.setProperty("enabled", enabled);

		// Assign roles as WeakReference[]
		@SuppressWarnings("unchecked")
		List<String> roleIds = (List<String>) input.get("roles");
		if (roleIds != null) {
			setRoleReferences(contentNode, roleIds);
		}

		// Assign group memberships as WeakReference[]
		@SuppressWarnings("unchecked")
		List<String> groupIds = (List<String>) input.get("memberOf");
		if (groupIds != null) {
			setGroupReferences(contentNode, groupIds);
		}

		JCRs.getOrCreateFolder(userFolder, "preferences");
		session.save();

		// Grant full permissions to the user on their own node
		JCRs.setAccessControlEntry(userFolder, new Principal() {
			@Override
			public String getName() {
				return username;
			}
		}, true, Privilege.JCR_ALL);
		session.save();

		Node savedProfile = session.getNode(IdpQueryExecutor.USERS_ROOT + "/" + username + "/profile");
		Map<String, Object> result = new HashMap<>();
		result.put("user", queryExecutor.mapUser(username, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("createUser", result);
	}

	public Map<String, Object> executeUpdateUser(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String username = (String) input.get("username");

		if (username == null) {
			return errorPayload("updateUser", "username is required", "INVALID_INPUT");
		}

		String profilePath = IdpQueryExecutor.USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorPayload("updateUser", "User not found: " + username, "NOT_FOUND");
		}

		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		setStringIfPresent(contentNode, "sn", input);
		setStringIfPresent(contentNode, "givenName", input);
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "mail", input);

		if (input.containsKey("enabled") && input.get("enabled") instanceof Boolean) {
			contentNode.setProperty("enabled", (Boolean) input.get("enabled"));
		}

		session.save();

		Node savedProfile = session.getNode(profilePath);
		Map<String, Object> result = new HashMap<>();
		result.put("user", queryExecutor.mapUser(username, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("updateUser", result);
	}

	public Map<String, Object> executeDeleteUser(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String username = (String) input.get("username");

		if (username == null) {
			return errorPayload("deleteUser", "username is required", "INVALID_INPUT");
		}

		String userFolderPath = IdpQueryExecutor.USERS_ROOT + "/" + username;
		if (!session.nodeExists(userFolderPath)) {
			return errorPayload("deleteUser", "User not found: " + username, "NOT_FOUND");
		}

		session.getNode(userFolderPath).remove();
		session.save();

		Map<String, Object> result = new HashMap<>();
		result.put("username", username);
		result.put("errors", null);
		return wrap("deleteUser", result);
	}

	public Map<String, Object> executeChangePassword(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String username = (String) input.get("username");
		String currentPassword = (String) input.get("currentPassword");
		String newPassword = (String) input.get("newPassword");

		if (username == null || newPassword == null) {
			return errorPayload("changePassword", "username and newPassword are required", "INVALID_INPUT");
		}

		String profilePath = IdpQueryExecutor.USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorPayload("changePassword", "User not found: " + username, "NOT_FOUND");
		}

		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));

		// Verify current password if provided
		if (currentPassword != null) {
			if (!contentNode.hasProperty("password")
					|| !verifyPassword(currentPassword, contentNode.getProperty("password").getString())) {
				return errorPayload("changePassword", "Current password is incorrect", "INVALID_CREDENTIALS");
			}
		}

		contentNode.setProperty("password", "{bcrypt}" + BCrypt.hash(newPassword));
		session.save();

		Node savedProfile = session.getNode(profilePath);
		Map<String, Object> result = new HashMap<>();
		result.put("user", queryExecutor.mapUser(username, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("changePassword", result);
	}

	public Map<String, Object> executeResetPassword(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String username = (String) input.get("username");
		String newPassword = (String) input.get("newPassword");

		if (username == null || newPassword == null) {
			return errorPayload("resetPassword", "username and newPassword are required", "INVALID_INPUT");
		}

		String profilePath = IdpQueryExecutor.USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorPayload("resetPassword", "User not found: " + username, "NOT_FOUND");
		}

		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		contentNode.setProperty("password", "{bcrypt}" + BCrypt.hash(newPassword));
		session.save();

		Node savedProfile = session.getNode(profilePath);
		Map<String, Object> result = new HashMap<>();
		result.put("user", queryExecutor.mapUser(username, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("resetPassword", result);
	}

	// =========================================================================
	// Role assignment mutations
	// =========================================================================

	public Map<String, Object> executeAssignRoles(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String username = (String) input.get("username");
		@SuppressWarnings("unchecked")
		List<String> roleIds = (List<String>) input.get("roles");

		if (username == null || roleIds == null) {
			return errorPayload("assignRoles", "username and roles are required", "INVALID_INPUT");
		}

		String profilePath = IdpQueryExecutor.USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorPayload("assignRoles", "User not found: " + username, "NOT_FOUND");
		}

		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		List<Value> current = getWeakRefs(contentNode, "roles");
		for (String roleId : roleIds) {
			String rolePath = IdpQueryExecutor.ROLES_ROOT + "/" + roleId + "/profile";
			if (!session.nodeExists(rolePath)) continue;
			Node roleProfile = session.getNode(rolePath);
			ensureMixReferenceable(roleProfile);
			if (!hasRef(current, roleProfile.getIdentifier())) {
				current.add(session.getValueFactory().createValue(roleProfile, true));
			}
		}
		contentNode.setProperty("roles", current.toArray(new Value[0]));
		session.save();

		Node savedProfile = session.getNode(profilePath);
		Map<String, Object> result = new HashMap<>();
		result.put("user", queryExecutor.mapUser(username, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("assignRoles", result);
	}

	public Map<String, Object> executeRevokeRoles(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String username = (String) input.get("username");
		@SuppressWarnings("unchecked")
		List<String> roleIds = (List<String>) input.get("roles");

		if (username == null || roleIds == null) {
			return errorPayload("revokeRoles", "username and roles are required", "INVALID_INPUT");
		}

		String profilePath = IdpQueryExecutor.USERS_ROOT + "/" + username + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorPayload("revokeRoles", "User not found: " + username, "NOT_FOUND");
		}

		List<String> uuidsToRemove = new ArrayList<>();
		for (String roleId : roleIds) {
			String rolePath = IdpQueryExecutor.ROLES_ROOT + "/" + roleId + "/profile";
			if (session.nodeExists(rolePath)) {
				uuidsToRemove.add(session.getNode(rolePath).getIdentifier());
			}
		}

		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		List<Value> current = getWeakRefs(contentNode, "roles");
		current.removeIf(v -> {
			try { return uuidsToRemove.contains(v.getString()); } catch (Exception e) { return false; }
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
		Map<String, Object> result = new HashMap<>();
		result.put("user", queryExecutor.mapUser(username, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("revokeRoles", result);
	}

	// =========================================================================
	// Role mutations
	// =========================================================================

	public Map<String, Object> executeCreateRole(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String roleId = (String) input.get("roleId");

		if (roleId == null) {
			return errorPayload("createRole", "roleId is required", "INVALID_INPUT");
		}

		String roleFolderPath = IdpQueryExecutor.ROLES_ROOT + "/" + roleId;
		if (session.nodeExists(roleFolderPath)) {
			return errorPayload("createRole", "Role already exists: " + roleId, "ALREADY_EXISTS");
		}

		Node rolesFolder = ensureFolder(IdpQueryExecutor.ROLES_ROOT);
		Node roleFolder = JCRs.getOrCreateFolder(rolesFolder, roleId);
		Node profileFile = JCRs.createFile(roleFolder, "profile");
		profileFile.addMixin("mix:referenceable");
		JCRs.setProperty(profileFile, "jcr:mimeType", "application/vnd.webtop.role");

		Node contentNode = JCRs.getContentNode(profileFile);
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "description", input);
		session.save();

		Node savedProfile = session.getNode(roleFolderPath + "/profile");
		Map<String, Object> result = new HashMap<>();
		result.put("role", queryExecutor.mapRole(roleId, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("createRole", result);
	}

	public Map<String, Object> executeUpdateRole(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String roleId = (String) input.get("roleId");

		if (roleId == null) {
			return errorPayload("updateRole", "roleId is required", "INVALID_INPUT");
		}

		String profilePath = IdpQueryExecutor.ROLES_ROOT + "/" + roleId + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorPayload("updateRole", "Role not found: " + roleId, "NOT_FOUND");
		}

		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "description", input);
		session.save();

		Node savedProfile = session.getNode(profilePath);
		Map<String, Object> result = new HashMap<>();
		result.put("role", queryExecutor.mapRole(roleId, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("updateRole", result);
	}

	public Map<String, Object> executeDeleteRole(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String roleId = (String) input.get("roleId");
		boolean removeFromUsers = getBoolInput(input, "removeFromUsers", false);

		if (roleId == null) {
			return errorPayload("deleteRole", "roleId is required", "INVALID_INPUT");
		}

		String roleFolderPath = IdpQueryExecutor.ROLES_ROOT + "/" + roleId;
		if (!session.nodeExists(roleFolderPath)) {
			return errorPayload("deleteRole", "Role not found: " + roleId, "NOT_FOUND");
		}

		if (removeFromUsers) {
			String roleProfilePath = roleFolderPath + "/profile";
			if (session.nodeExists(roleProfilePath)) {
				String roleUuid = session.getNode(roleProfilePath).getIdentifier();
				removeRoleFromAllUsers(roleUuid);
			}
		}

		session.getNode(roleFolderPath).remove();
		session.save();

		Map<String, Object> result = new HashMap<>();
		result.put("roleId", roleId);
		result.put("errors", null);
		return wrap("deleteRole", result);
	}

	// =========================================================================
	// Group mutations
	// =========================================================================

	public Map<String, Object> executeCreateGroup(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String parentGroupId = (String) input.get("parentGroupId");
		String name = (String) input.get("name");

		if (name == null) {
			return errorPayload("createGroup", "name is required", "INVALID_INPUT");
		}

		String groupFolderPath = (parentGroupId != null && !parentGroupId.isEmpty())
				? IdpQueryExecutor.GROUPS_ROOT + "/" + parentGroupId + "/" + name
				: IdpQueryExecutor.GROUPS_ROOT + "/" + name;

		if (session.nodeExists(groupFolderPath)) {
			return errorPayload("createGroup", "Group already exists", "ALREADY_EXISTS");
		}

		Node parentFolder;
		if (parentGroupId != null && !parentGroupId.isEmpty()) {
			String parentPath = IdpQueryExecutor.GROUPS_ROOT + "/" + parentGroupId;
			if (!session.nodeExists(parentPath)) {
				return errorPayload("createGroup", "Parent group not found: " + parentGroupId, "NOT_FOUND");
			}
			parentFolder = session.getNode(parentPath);
		} else {
			parentFolder = ensureFolder(IdpQueryExecutor.GROUPS_ROOT);
		}

		Node groupFolder = JCRs.getOrCreateFolder(parentFolder, name);
		Node profileFile = JCRs.createFile(groupFolder, "profile");
		profileFile.addMixin("mix:referenceable");
		JCRs.setProperty(profileFile, "jcr:mimeType", "application/vnd.webtop.group");

		Node contentNode = JCRs.getContentNode(profileFile);
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "description", input);
		session.save();

		String groupId = groupFolderPath.substring(IdpQueryExecutor.GROUPS_ROOT.length() + 1);
		Node savedProfile = session.getNode(groupFolderPath + "/profile");
		Map<String, Object> result = new HashMap<>();
		result.put("group", queryExecutor.mapGroup(groupId, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("createGroup", result);
	}

	public Map<String, Object> executeUpdateGroup(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String groupId = (String) input.get("groupId");

		if (groupId == null) {
			return errorPayload("updateGroup", "groupId is required", "INVALID_INPUT");
		}

		String profilePath = IdpQueryExecutor.GROUPS_ROOT + "/" + groupId + "/profile";
		if (!session.nodeExists(profilePath)) {
			return errorPayload("updateGroup", "Group not found: " + groupId, "NOT_FOUND");
		}

		Node contentNode = JCRs.getContentNode(session.getNode(profilePath));
		setStringIfPresent(contentNode, "displayName", input);
		setStringIfPresent(contentNode, "description", input);
		session.save();

		Node savedProfile = session.getNode(profilePath);
		Map<String, Object> result = new HashMap<>();
		result.put("group", queryExecutor.mapGroup(groupId, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("errors", null);
		return wrap("updateGroup", result);
	}

	public Map<String, Object> executeDeleteGroup(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String groupId = (String) input.get("groupId");
		boolean recursive = getBoolInput(input, "recursive", false);

		if (groupId == null) {
			return errorPayload("deleteGroup", "groupId is required", "INVALID_INPUT");
		}

		String groupFolderPath = IdpQueryExecutor.GROUPS_ROOT + "/" + groupId;
		if (!session.nodeExists(groupFolderPath)) {
			return errorPayload("deleteGroup", "Group not found: " + groupId, "NOT_FOUND");
		}

		Node groupFolder = session.getNode(groupFolderPath);
		if (!recursive && hasChildGroups(groupFolder)) {
			return errorPayload("deleteGroup", "Group has children. Use recursive=true to delete", "HAS_CHILDREN");
		}

		groupFolder.remove();
		session.save();

		Map<String, Object> result = new HashMap<>();
		result.put("groupId", groupId);
		result.put("errors", null);
		return wrap("deleteGroup", result);
	}

	public Map<String, Object> executeMoveGroup(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String groupId = (String) input.get("groupId");
		String newParentGroupId = (String) input.get("newParentGroupId");
		String newName = (String) input.get("newName");

		if (groupId == null) {
			return errorPayload("moveGroup", "groupId is required", "INVALID_INPUT");
		}

		String srcPath = IdpQueryExecutor.GROUPS_ROOT + "/" + groupId;
		if (!session.nodeExists(srcPath)) {
			return errorPayload("moveGroup", "Group not found: " + groupId, "NOT_FOUND");
		}

		String currentName = groupId.contains("/") ? groupId.substring(groupId.lastIndexOf('/') + 1) : groupId;
		String finalName = (newName != null && !newName.isEmpty()) ? newName : currentName;

		String destParentPath;
		if (newParentGroupId != null && !newParentGroupId.isEmpty()) {
			destParentPath = IdpQueryExecutor.GROUPS_ROOT + "/" + newParentGroupId;
			if (!session.nodeExists(destParentPath)) {
				return errorPayload("moveGroup", "New parent group not found: " + newParentGroupId, "NOT_FOUND");
			}
		} else {
			destParentPath = IdpQueryExecutor.GROUPS_ROOT;
		}

		String destPath = destParentPath + "/" + finalName;
		session.move(srcPath, destPath);
		session.save();

		String newGroupId = destPath.substring(IdpQueryExecutor.GROUPS_ROOT.length() + 1);
		Node savedProfile = session.getNode(destPath + "/profile");
		Map<String, Object> result = new HashMap<>();
		result.put("group", queryExecutor.mapGroup(newGroupId, savedProfile, JCRs.getContentNode(savedProfile)));
		result.put("previousGroupId", groupId);
		result.put("errors", null);
		return wrap("moveGroup", result);
	}

	// =========================================================================
	// Preference mutations
	// =========================================================================

	public Map<String, Object> executeUpdatePreferences(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String username = (String) input.get("username");
		String category = (String) input.get("category");
		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) input.get("data");

		if (username == null || category == null) {
			return errorPayload("updatePreferences", "username and category are required", "INVALID_INPUT");
		}
		if (data == null) {
			return errorPayload("updatePreferences", "data is required", "INVALID_INPUT");
		}
		// Prevent path traversal in category name
		if (!category.matches("[a-z][a-z0-9-]*")) {
			return errorPayload("updatePreferences", "Invalid category name: " + category, "INVALID_INPUT");
		}

		String userFolderPath = IdpQueryExecutor.USERS_ROOT + "/" + username;
		if (!session.nodeExists(userFolderPath)) {
			return errorPayload("updatePreferences", "User not found: " + username, "NOT_FOUND");
		}

		Node userFolder = session.getNode(userFolderPath);
		Node preferencesFolder = JCRs.getOrCreateFolder(userFolder, "preferences");

		// Get or create the category file
		Node categoryFile;
		if (preferencesFolder.hasNode(category)) {
			categoryFile = preferencesFolder.getNode(category);
		} else {
			categoryFile = JCRs.createFile(preferencesFolder, category);
		}
		JCRs.setProperty(categoryFile, "jcr:mimeType", "application/vnd.webtop." + category);

		Node contentNode = JCRs.getContentNode(categoryFile);
		for (Map.Entry<String, Object> entry : data.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if (value instanceof Number) {
				JCRs.setProperty(categoryFile, key, new BigDecimal(value.toString()));
			} else {
				JCRs.setProperty(categoryFile, key, value);
			}
		}
		session.save();

		// Read back saved properties
		Map<String, Object> savedData = new LinkedHashMap<>();
		PropertyIterator props = contentNode.getProperties();
		while (props.hasNext()) {
			Property prop = props.nextProperty();
			String propName = prop.getName();
			if (propName.startsWith("jcr:")) continue;
			if (!prop.isMultiple()) {
				if (prop.getType() == PropertyType.STRING) {
					savedData.put(propName, prop.getString());
				} else if (prop.getType() == PropertyType.BOOLEAN) {
					savedData.put(propName, prop.getBoolean());
				} else if (prop.getType() == PropertyType.DOUBLE || prop.getType() == PropertyType.LONG) {
					savedData.put(propName, prop.getDouble());
				}
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("category", category);
		result.put("data", savedData);
		result.put("errors", null);
		return wrap("updatePreferences", result);
	}

	// =========================================================================
	// Group membership mutations
	// =========================================================================

	public Map<String, Object> executeAddGroupMembers(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String groupId = (String) input.get("groupId");
		@SuppressWarnings("unchecked")
		List<String> usernames = (List<String>) input.get("usernames");

		if (groupId == null || usernames == null) {
			return errorPayload("addGroupMembers", "groupId and usernames are required", "INVALID_INPUT");
		}

		String groupProfilePath = IdpQueryExecutor.GROUPS_ROOT + "/" + groupId + "/profile";
		if (!session.nodeExists(groupProfilePath)) {
			return errorPayload("addGroupMembers", "Group not found: " + groupId, "NOT_FOUND");
		}

		Node groupProfileNode = session.getNode(groupProfilePath);
		ensureMixReferenceable(groupProfileNode);
		String groupUuid = groupProfileNode.getIdentifier();

		for (String username : usernames) {
			String userProfilePath = IdpQueryExecutor.USERS_ROOT + "/" + username + "/profile";
			if (!session.nodeExists(userProfilePath)) continue;
			Node userContent = JCRs.getContentNode(session.getNode(userProfilePath));
			List<Value> current = getWeakRefs(userContent, "memberOf");
			if (!hasRef(current, groupUuid)) {
				current.add(session.getValueFactory().createValue(groupProfileNode, true));
				userContent.setProperty("memberOf", current.toArray(new Value[0]));
			}
		}
		session.save();

		Node savedGroupProfile = session.getNode(groupProfilePath);
		Map<String, Object> result = new HashMap<>();
		result.put("group", queryExecutor.mapGroup(groupId, savedGroupProfile, JCRs.getContentNode(savedGroupProfile)));
		result.put("errors", null);
		return wrap("addGroupMembers", result);
	}

	public Map<String, Object> executeRemoveGroupMembers(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String groupId = (String) input.get("groupId");
		@SuppressWarnings("unchecked")
		List<String> usernames = (List<String>) input.get("usernames");

		if (groupId == null || usernames == null) {
			return errorPayload("removeGroupMembers", "groupId and usernames are required", "INVALID_INPUT");
		}

		String groupProfilePath = IdpQueryExecutor.GROUPS_ROOT + "/" + groupId + "/profile";
		if (!session.nodeExists(groupProfilePath)) {
			return errorPayload("removeGroupMembers", "Group not found: " + groupId, "NOT_FOUND");
		}

		Node groupProfileNode = session.getNode(groupProfilePath);
		ensureMixReferenceable(groupProfileNode);
		String groupUuid = groupProfileNode.getIdentifier();

		for (String username : usernames) {
			String userProfilePath = IdpQueryExecutor.USERS_ROOT + "/" + username + "/profile";
			if (!session.nodeExists(userProfilePath)) continue;
			Node userContent = JCRs.getContentNode(session.getNode(userProfilePath));
			if (!userContent.hasProperty("memberOf")) continue;
			List<Value> current = getWeakRefs(userContent, "memberOf");
			current.removeIf(v -> {
				try { return groupUuid.equals(v.getString()); } catch (Exception e) { return false; }
			});
			if (current.isEmpty()) {
				userContent.getProperty("memberOf").remove();
			} else {
				userContent.setProperty("memberOf", current.toArray(new Value[0]));
			}
		}
		session.save();

		Node savedGroupProfile = session.getNode(groupProfilePath);
		Map<String, Object> result = new HashMap<>();
		result.put("group", queryExecutor.mapGroup(groupId, savedGroupProfile, JCRs.getContentNode(savedGroupProfile)));
		result.put("errors", null);
		return wrap("removeGroupMembers", result);
	}

	// =========================================================================
	// Internal helpers
	// =========================================================================

	@SuppressWarnings("unchecked")
	private Map<String, Object> extractInput(GraphQLRequest request) {
		Map<String, Object> variables = request.getVariables();
		if (variables != null && variables.containsKey("input")) {
			Object inp = variables.get("input");
			if (inp instanceof Map) return (Map<String, Object>) inp;
		}
		return new HashMap<>();
	}

	private Node ensureFolder(String path) throws Exception {
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

	private void setStringIfPresent(Node node, String name, Map<String, Object> input) throws Exception {
		if (input.containsKey(name) && input.get(name) instanceof String) {
			node.setProperty(name, (String) input.get(name));
		}
	}

	private boolean getBoolInput(Map<String, Object> input, String name, boolean defaultValue) {
		if (!input.containsKey(name)) return defaultValue;
		Object v = input.get(name);
		if (v instanceof Boolean) return (Boolean) v;
		return defaultValue;
	}

	private void setRoleReferences(Node contentNode, List<String> roleIds) throws Exception {
		List<Value> values = new ArrayList<>();
		for (String roleId : roleIds) {
			String rolePath = IdpQueryExecutor.ROLES_ROOT + "/" + roleId + "/profile";
			if (!session.nodeExists(rolePath)) continue;
			Node roleProfile = session.getNode(rolePath);
			ensureMixReferenceable(roleProfile);
			values.add(session.getValueFactory().createValue(roleProfile, true));
		}
		if (!values.isEmpty()) {
			contentNode.setProperty("roles", values.toArray(new Value[0]));
		}
	}

	private void setGroupReferences(Node contentNode, List<String> groupIds) throws Exception {
		List<Value> values = new ArrayList<>();
		for (String groupId : groupIds) {
			String groupPath = IdpQueryExecutor.GROUPS_ROOT + "/" + groupId + "/profile";
			if (!session.nodeExists(groupPath)) continue;
			Node groupProfile = session.getNode(groupPath);
			ensureMixReferenceable(groupProfile);
			values.add(session.getValueFactory().createValue(groupProfile, true));
		}
		if (!values.isEmpty()) {
			contentNode.setProperty("memberOf", values.toArray(new Value[0]));
		}
	}

	private List<Value> getWeakRefs(Node node, String propertyName) throws Exception {
		List<Value> values = new ArrayList<>();
		if (node.hasProperty(propertyName)) {
			for (Value v : node.getProperty(propertyName).getValues()) {
				values.add(v);
			}
		}
		return values;
	}

	private boolean hasRef(List<Value> values, String uuid) {
		for (Value v : values) {
			try { if (uuid.equals(v.getString())) return true; } catch (Exception ignore) {}
		}
		return false;
	}

	private void ensureMixReferenceable(Node node) throws Exception {
		if (!node.isNodeType("mix:referenceable")) {
			node.addMixin("mix:referenceable");
		}
	}

	private void removeRoleFromAllUsers(String roleUuid) throws Exception {
		if (!session.nodeExists(IdpQueryExecutor.USERS_ROOT)) return;
		NodeIterator it = session.getNode(IdpQueryExecutor.USERS_ROOT).getNodes();
		while (it.hasNext()) {
			Node userFolder = it.nextNode();
			if (!userFolder.hasNode("profile")) continue;
			Node contentNode = JCRs.getContentNode(userFolder.getNode("profile"));
			if (!contentNode.hasProperty("roles")) continue;
			List<Value> current = getWeakRefs(contentNode, "roles");
			current.removeIf(v -> {
				try { return roleUuid.equals(v.getString()); } catch (Exception e) { return false; }
			});
			if (current.isEmpty()) {
				contentNode.getProperty("roles").remove();
			} else {
				contentNode.setProperty("roles", current.toArray(new Value[0]));
			}
		}
	}

	private boolean hasChildGroups(Node groupFolder) throws Exception {
		NodeIterator it = groupFolder.getNodes();
		while (it.hasNext()) {
			Node child = it.nextNode();
			if (!"profile".equals(child.getName()) && child.hasNode("profile")) return true;
		}
		return false;
	}

	private boolean verifyPassword(String input, String stored) {
		if (stored.startsWith("{bcrypt}")) {
			return BCrypt.verify(input, stored.substring("{bcrypt}".length()));
		}
		// SHA-256 fallback
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : hash) sb.append(String.format("%02x", b));
			String hex = sb.toString();
			if (stored.startsWith("{sha256}")) {
				return hex.equalsIgnoreCase(stored.substring("{sha256}".length()));
			}
			return hex.equalsIgnoreCase(stored);
		} catch (Exception e) {
			return false;
		}
	}

	private Map<String, Object> errorPayload(String mutationName, String message, String code) {
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
		return wrap(mutationName, result);
	}

	private Map<String, Object> wrap(String mutationName, Map<String, Object> result) {
		Map<String, Object> payload = new HashMap<>();
		payload.put(mutationName, result);
		return payload;
	}
}

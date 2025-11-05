/*
 * Copyright (c) 2022 MintJams Inc.
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

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockManager;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

/**
 * Class for executing GraphQL Mutation operations
 */
public class MutationExecutor {

	private final Session session;

	public MutationExecutor(Session session) {
		this.session = session;
	}

	/**
	 * Execute createFolder mutation Example: mutation { createFolder(input: { path:
	 * "/content", name: "newfolder" }) { path name } }
	 */
	public Map<String, Object> executeCreateFolder(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String parentPath = (String) input.get("path");
		String name = (String) input.get("name");
		String nodeType = input.containsKey("nodeType") ? (String) input.get("nodeType") : "nt:folder";

		if (parentPath == null || name == null) {
			throw new IllegalArgumentException("path and name are required");
		}

		if (!session.nodeExists(parentPath)) {
			throw new IllegalArgumentException("Parent node not found: " + parentPath);
		}

		Node parentNode = session.getNode(parentPath);
		Node folder = parentNode.addNode(name, nodeType);

		// jcr:created and jcr:createdBy are automatically set by JCR, no manual setting
		// required

		session.save();

		// Re-get node after save to get latest information
		String folderPath = folder.getPath();
		Node savedFolder = session.getNode(folderPath);

		Map<String, Object> result = new HashMap<>();
		result.put("createFolder", NodeMapper.toGraphQL(savedFolder));

		return result;
	}

	/**
	 * Execute createFile mutation Example: mutation { createFile(input: { path:
	 * "/content", name: "file.txt", mimeType: "text/plain", content: "..." }) {
	 * path name } }
	 */
	public Map<String, Object> executeCreateFile(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String parentPath = (String) input.get("path");
		String name = (String) input.get("name");
		String mimeType = (String) input.get("mimeType");
		String contentBase64 = (String) input.get("content");
		String nodeType = input.containsKey("nodeType") ? (String) input.get("nodeType") : "nt:file";

		if (parentPath == null || name == null || mimeType == null || contentBase64 == null) {
			throw new IllegalArgumentException("path, name, mimeType, and content are required");
		}

		if (!session.nodeExists(parentPath)) {
			throw new IllegalArgumentException("Parent node not found: " + parentPath);
		}

		Node parentNode = session.getNode(parentPath);

		// Create nt:file node
		Node fileNode = parentNode.addNode(name, nodeType);

		// jcr:created and jcr:createdBy are automatically set by JCR, no manual setting
		// required

		// Create jcr:content node
		Node contentNode = fileNode.addNode("jcr:content", "nt:resource");

		// Base64 decode
		byte[] data = Base64.getDecoder().decode(contentBase64);

		// Current time (for jcr:lastModified)
		Calendar now = Calendar.getInstance();

		// jcr:data, jcr:mimeType, jcr:lastModified, jcr:lastModifiedBy belong to
		// jcr:content
		contentNode.setProperty("jcr:data", session.getValueFactory().createBinary(new ByteArrayInputStream(data)));
		contentNode.setProperty("jcr:mimeType", mimeType);
		contentNode.setProperty("jcr:lastModified", now);
		contentNode.setProperty("jcr:lastModifiedBy", session.getUserID());

		session.save();

		// Re-get node after save to get latest information
		String filePath = fileNode.getPath();
		Node savedFile = session.getNode(filePath);

		Map<String, Object> result = new HashMap<>();
		result.put("createFile", NodeMapper.toGraphQL(savedFile));

		return result;
	}

	/**
	 * Execute deleteNode mutation Example: mutation { deleteNode(path:
	 * "/content/page1") }
	 */
	public Map<String, Object> executeDeleteNode(GraphQLRequest request) throws Exception {
		String path = extractPathFromMutation(request.getQuery());

		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}

		boolean deleted = false;
		if (session.nodeExists(path)) {
			Node node = session.getNode(path);
			node.remove();
			session.save();
			deleted = true;
		}

		Map<String, Object> result = new HashMap<>();
		result.put("deleteNode", deleted);

		return result;
	}

	/**
	 * Execute lockNode mutation Example: mutation { lockNode(input: { path:
	 * "/content/page1", isDeep: false, isSessionScoped: true }) { path isLocked
	 * lockOwner } }
	 */
	public Map<String, Object> executeLockNode(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		if (path == null || path.isEmpty()) {
			throw new IllegalArgumentException("path is required");
		}

		// Default values
		boolean isDeep = false;
		if (input.containsKey("isDeep") && input.get("isDeep") instanceof Boolean) {
			isDeep = ((Boolean) input.get("isDeep")).booleanValue();
		}
		// Default to false for GraphQL API to persist locks across requests
		boolean isSessionScoped = false;
		if (input.containsKey("isSessionScoped") && input.get("isSessionScoped") instanceof Boolean) {
			isSessionScoped = ((Boolean) input.get("isSessionScoped")).booleanValue();
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);

		// Add mix:lockable mixin if not already present
		if (!node.isNodeType("mix:lockable")) {
			node.addMixin("mix:lockable");
			this.session.save();
		}

		// Lock the node
		LockManager lockManager = this.session.getWorkspace().getLockManager();
		Lock lock = lockManager.lock(path, isDeep, isSessionScoped, Long.MAX_VALUE, this.session.getUserID());

		// Refresh node to get latest state
		Node lockedNode = this.session.getNode(path);

		Map<String, Object> result = new HashMap<>();
		result.put("lockNode", NodeMapper.toGraphQL(lockedNode));

		return result;
	}

	/**
	 * Execute unlockNode mutation Example: mutation { unlockNode(path:
	 * "/content/page1") }
	 */
	public Map<String, Object> executeUnlockNode(GraphQLRequest request) throws Exception {
		String path = extractPathFromMutation(request.getQuery());

		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		// Unlock the node
		LockManager lockManager = this.session.getWorkspace().getLockManager();
		lockManager.unlock(path);

		Map<String, Object> result = new HashMap<>();
		result.put("unlockNode", true);

		return result;
	}

	/**
	 * Execute setProperty mutation Example: mutation { setProperty(input: { path:
	 * "/content/page1", name: "myRef", value: "uuid", type: "Reference" }) }
	 */
	public Map<String, Object> executeSetProperty(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		String name = (String) input.get("name");
		Object value = input.get("value");
		String type = (String) input.get("type");

		if (path == null || name == null || value == null) {
			throw new IllegalArgumentException("path, name, and value are required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);

		// Set property based on type
		if (type != null) {
			int propertyType = getPropertyType(type);

			if (propertyType == PropertyType.REFERENCE || propertyType == PropertyType.WEAKREFERENCE) {
				// For Reference/WeakReference, value should be a UUID
				Node targetNode = this.session.getNodeByIdentifier(value.toString());
				Value refValue = this.session.getValueFactory().createValue(targetNode,
						propertyType == PropertyType.WEAKREFERENCE);
				node.setProperty(name, refValue);
			} else {
				// For other types, use standard property setting
				switch (propertyType) {
				case PropertyType.BOOLEAN:
					node.setProperty(name, Boolean.parseBoolean(value.toString()));
					break;
				case PropertyType.LONG:
					node.setProperty(name, Long.parseLong(value.toString()));
					break;
				case PropertyType.DOUBLE:
					node.setProperty(name, Double.parseDouble(value.toString()));
					break;
				default:
					node.setProperty(name, value.toString());
					break;
				}
			}
		} else {
			// Auto-detect type
			node.setProperty(name, value.toString());
		}

		this.session.save();

		Map<String, Object> result = new HashMap<>();
		result.put("setProperty", NodeMapper.toGraphQL(node));

		return result;
	}

	/**
	 * Execute addMixin mutation Example: mutation { addMixin(input: { path:
	 * "/content/page1", mixinType: "mix:referenceable" }) }
	 */
	public Map<String, Object> executeAddMixin(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		String mixinType = (String) input.get("mixinType");

		if (path == null || mixinType == null) {
			throw new IllegalArgumentException("path and mixinType are required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);

		// Add mixin if not already present
		if (!node.isNodeType(mixinType)) {
			node.addMixin(mixinType);
			this.session.save();
		}

		Map<String, Object> result = new HashMap<>();
		result.put("addMixin", NodeMapper.toGraphQL(node));

		return result;
	}

	/**
	 * Execute deleteMixin mutation Example: mutation { deleteMixin(input: { path:
	 * "/content/page1", mixinType: "mix:referenceable" }) }
	 */
	public Map<String, Object> executeDeleteMixin(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		String mixinType = (String) input.get("mixinType");

		if (path == null || mixinType == null) {
			throw new IllegalArgumentException("path and mixinType are required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);

		// Remove mixin if present
		if (node.isNodeType(mixinType)) {
			node.removeMixin(mixinType);
			this.session.save();
		}

		Map<String, Object> result = new HashMap<>();
		result.put("deleteMixin", NodeMapper.toGraphQL(node));

		return result;
	}

	/**
	 * Execute deleteProperty mutation Example: mutation { deleteProperty(input: {
	 * path: "/content/page1", name: "myProperty" }) }
	 */
	public Map<String, Object> executeDeleteProperty(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		String name = (String) input.get("name");

		if (path == null || name == null) {
			throw new IllegalArgumentException("path and name are required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);

		// Check if property exists
		if (!node.hasProperty(name)) {
			throw new IllegalArgumentException("Property not found: " + name);
		}

		// Remove property
		node.getProperty(name).remove();
		this.session.save();

		Map<String, Object> result = new HashMap<>();
		result.put("deleteProperty", NodeMapper.toGraphQL(node));

		return result;
	}

	/**
	 * Execute setAccessControl mutation Sets or modifies ACL entry for a principal
	 * Example: mutation { setAccessControl(input: { path: "/content/page1",
	 * principal: "user1", privileges: ["jcr:read", "jcr:write"], allow: true }) }
	 */
	public Map<String, Object> executeSetAccessControl(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		String principalName = (String) input.get("principal");
		Object privilegesObj = input.get("privileges");

		// Default to allow if not specified
		boolean allow = true;
		if (input.containsKey("allow") && input.get("allow") instanceof Boolean) {
			allow = ((Boolean) input.get("allow")).booleanValue();
		}

		if (path == null || principalName == null || privilegesObj == null) {
			throw new IllegalArgumentException("path, principal, and privileges are required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		// Parse privileges array
		java.util.List<String> privilegeNames = new java.util.ArrayList<>();
		if (privilegesObj instanceof java.util.List) {
			@SuppressWarnings("unchecked")
			java.util.List<Object> list = (java.util.List<Object>) privilegesObj;
			for (Object item : list) {
				privilegeNames.add(item.toString());
			}
		} else if (privilegesObj instanceof String) {
			privilegeNames.add(privilegesObj.toString());
		} else {
			throw new IllegalArgumentException("privileges must be an array or string");
		}

		// Get AccessControlManager
		AccessControlManager acm = this.session.getAccessControlManager();

		// Get or create AccessControlList
		AccessControlList acl = null;
		AccessControlPolicy[] policies = acm.getPolicies(path);
		for (AccessControlPolicy policy : policies) {
			if (policy instanceof AccessControlList) {
				acl = (AccessControlList) policy;
				break;
			}
		}

		// If no ACL exists, get applicable policy
		if (acl == null) {
			AccessControlPolicyIterator applicablePolicies = acm.getApplicablePolicies(path);
			while (applicablePolicies.hasNext()) {
				AccessControlPolicy applicablePolicy = applicablePolicies.nextAccessControlPolicy();
				if (applicablePolicy instanceof AccessControlList) {
					acl = (AccessControlList) applicablePolicy;
					break;
				}
			}
		}

		if (acl == null) {
			throw new IllegalStateException("No AccessControlList available for path: " + path);
		}

		// Get Principal
		java.security.Principal principal = new java.security.Principal() {
			@Override
			public String getName() {
				return principalName;
			}
		};

		// Convert privilege names to Privilege[]
		Privilege[] privileges = new Privilege[privilegeNames.size()];
		for (int i = 0; i < privilegeNames.size(); i++) {
			privileges[i] = acm.privilegeFromName(privilegeNames.get(i));
		}

		// Remove existing entry for this principal
		AccessControlEntry[] existingEntries = acl.getAccessControlEntries();
		for (AccessControlEntry entry : existingEntries) {
			if (entry.getPrincipal().getName().equals(principalName)) {
				acl.removeAccessControlEntry(entry);
			}
		}

		// Add new entry
		acl.addAccessControlEntry(principal, privileges);

		// Save policy
		acm.setPolicy(path, acl);
		this.session.save();

		// Return updated ACL entries
		java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();
		AccessControlEntry[] updatedEntries = acl.getAccessControlEntries();
		for (AccessControlEntry entry : updatedEntries) {
			Map<String, Object> entryMap = new HashMap<>();
			entryMap.put("principal", entry.getPrincipal().getName());

			java.util.List<String> privList = new java.util.ArrayList<>();
			for (Privilege priv : entry.getPrivileges()) {
				privList.add(priv.getName());
			}
			entryMap.put("privileges", privList);
			entryMap.put("allow", true);

			entries.add(entryMap);
		}

		Map<String, Object> aclData = new HashMap<>();
		aclData.put("entries", entries);

		Map<String, Object> result = new HashMap<>();
		result.put("setAccessControl", aclData);

		return result;
	}

	/**
	 * Execute deleteAccessControl mutation Removes ACL entry for a principal
	 * Example: mutation { deleteAccessControl(input: { path: "/content/page1",
	 * principal: "user1" }) }
	 */
	public Map<String, Object> executeDeleteAccessControl(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		String principalName = (String) input.get("principal");

		if (path == null || principalName == null) {
			throw new IllegalArgumentException("path and principal are required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		// Get AccessControlManager
		AccessControlManager acm = this.session.getAccessControlManager();

		// Get AccessControlList
		AccessControlList acl = null;
		AccessControlPolicy[] policies = acm.getPolicies(path);
		for (AccessControlPolicy policy : policies) {
			if (policy instanceof AccessControlList) {
				acl = (AccessControlList) policy;
				break;
			}
		}

		if (acl == null) {
			throw new IllegalStateException("No AccessControlList found for path: " + path);
		}

		// Remove entry for this principal
		boolean removed = false;
		AccessControlEntry[] existingEntries = acl.getAccessControlEntries();
		for (AccessControlEntry entry : existingEntries) {
			if (entry.getPrincipal().getName().equals(principalName)) {
				acl.removeAccessControlEntry(entry);
				removed = true;
			}
		}

		if (!removed) {
			throw new IllegalArgumentException("No ACL entry found for principal: " + principalName);
		}

		// Save policy
		acm.setPolicy(path, acl);
		this.session.save();

		Map<String, Object> result = new HashMap<>();
		result.put("deleteAccessControl", true);

		return result;
	}

	/**
	 * Convert type string to JCR PropertyType constant
	 */
	private int getPropertyType(String type) {
		switch (type.toUpperCase()) {
		case "STRING":
			return PropertyType.STRING;
		case "BINARY":
			return PropertyType.BINARY;
		case "LONG":
			return PropertyType.LONG;
		case "DOUBLE":
			return PropertyType.DOUBLE;
		case "DECIMAL":
			return PropertyType.DECIMAL;
		case "DATE":
			return PropertyType.DATE;
		case "BOOLEAN":
			return PropertyType.BOOLEAN;
		case "NAME":
			return PropertyType.NAME;
		case "PATH":
			return PropertyType.PATH;
		case "REFERENCE":
			return PropertyType.REFERENCE;
		case "WEAKREFERENCE":
			return PropertyType.WEAKREFERENCE;
		case "URI":
			return PropertyType.URI;
		default:
			return PropertyType.STRING;
		}
	}

	/**
	 * Extract input parameter (simple implementation)
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> extractInput(GraphQLRequest request) {
		Map<String, Object> variables = request.getVariables();

		// Get input directly from variables
		if (variables.containsKey("input")) {
			Object input = variables.get("input");
			if (input instanceof Map) {
				return (Map<String, Object>) input;
			}
		}

		// Parse input from query string
		String query = request.getQuery();
		int inputStart = query.indexOf("input:");
		if (inputStart == -1) {
			throw new IllegalArgumentException("input parameter not found");
		}

		// Extract input: { ... } part
		int braceStart = query.indexOf("{", inputStart);
		if (braceStart == -1) {
			throw new IllegalArgumentException("Invalid input format");
		}

		// Find matching closing brace
		int braceEnd = findMatchingBrace(query, braceStart);
		if (braceEnd == -1) {
			throw new IllegalArgumentException("Invalid input format: unmatched braces");
		}

		// Extract as JSON format string
		String inputJson = query.substring(braceStart, braceEnd + 1);

		// Parse JSON (simple implementation)
		return parseSimpleJson(inputJson);
	}

	/**
	 * Find matching closing brace
	 */
	private int findMatchingBrace(String str, int openBrace) {
		int depth = 1;
		for (int i = openBrace + 1; i < str.length(); i++) {
			char c = str.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Parse GraphQL input object to Map Manual parser for GraphQL input syntax
	 */
	private Map<String, Object> parseSimpleJson(String graphqlInput) {
		// Manual parsing for GraphQL input: { path: "/content" mixinType:
		// "mix:referenceable" }
		Map<String, Object> result = new HashMap<>();

		// Remove outer braces and normalize whitespace
		String content = graphqlInput.trim().replaceAll("\\r\\n", " ").replaceAll("\\n", " ").replaceAll("\\r", " ")
				.replaceAll("\\s+", " ");

		if (content.startsWith("{")) {
			content = content.substring(1);
		}
		if (content.endsWith("}")) {
			content = content.substring(0, content.length() - 1);
		}
		content = content.trim();

		// Parse key-value pairs
		int i = 0;
		while (i < content.length()) {
			// Skip whitespace
			while (i < content.length() && Character.isWhitespace(content.charAt(i))) {
				i++;
			}
			if (i >= content.length())
				break;

			// Extract key (identifier before ':')
			int keyStart = i;
			while (i < content.length() && content.charAt(i) != ':') {
				i++;
			}
			if (i >= content.length())
				break;

			String key = content.substring(keyStart, i).trim();
			i++; // skip ':'

			// Skip whitespace after colon
			while (i < content.length() && Character.isWhitespace(content.charAt(i))) {
				i++;
			}
			if (i >= content.length())
				break;

			// Extract value
			Object value = null;
			if (content.charAt(i) == '"') {
				// String value
				i++; // skip opening quote
				int valueStart = i;
				while (i < content.length() && content.charAt(i) != '"') {
					i++;
				}
				value = content.substring(valueStart, i);
				i++; // skip closing quote
			} else if (content.startsWith("true", i)) {
				value = Boolean.TRUE;
				i += 4;
			} else if (content.startsWith("false", i)) {
				value = Boolean.FALSE;
				i += 5;
			} else {
				// Number or other
				int valueStart = i;
				while (i < content.length() && !Character.isWhitespace(content.charAt(i)) && content.charAt(i) != '}') {
					i++;
				}
				String valueStr = content.substring(valueStart, i).trim();
				try {
					value = Long.parseLong(valueStr);
				} catch (NumberFormatException e) {
					try {
						value = Double.parseDouble(valueStr);
					} catch (NumberFormatException e2) {
						value = valueStr;
					}
				}
			}

			// Skip optional comma and whitespace after value
			while (i < content.length() && (Character.isWhitespace(content.charAt(i)) || content.charAt(i) == ',')) {
				i++;
			}

			result.put(key, value);
		}

		return result;
	}

	/**
	 * Extract path (simple implementation)
	 */
	private String extractPathFromMutation(String query) {
		// Extract path from pattern like deleteNode(path: "/content/page1")
		int start = query.indexOf("path:");
		if (start == -1) {
			return null;
		}

		start = query.indexOf("\"", start);
		if (start == -1) {
			return null;
		}

		int end = query.indexOf("\"", start + 1);
		if (end == -1) {
			return null;
		}

		return query.substring(start + 1, end);
	}
}

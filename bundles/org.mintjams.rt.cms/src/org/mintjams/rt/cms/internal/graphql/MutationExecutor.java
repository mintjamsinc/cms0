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
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.lock.LockManager;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;

/**
 * Class for executing GraphQL Mutation operations
 */
public class MutationExecutor {

	private final Session session;
	private static final SimpleDateFormat ISO8601_FORMAT = createISO8601Format();

	private static SimpleDateFormat createISO8601Format() {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format;
	}

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
	 * Execute deleteNode mutation
	 * Example: mutation { deleteNode(input: { path: "/content/page1" }) }
	 */
	public Map<String, Object> executeDeleteNode(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");

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
		lockManager.lock(path, isDeep, isSessionScoped, Long.MAX_VALUE, this.session.getUserID());

		// Refresh node to get latest state
		Node lockedNode = this.session.getNode(path);

		Map<String, Object> result = new HashMap<>();
		result.put("lockNode", NodeMapper.toGraphQL(lockedNode));

		return result;
	}

	/**
	 * Execute unlockNode mutation
	 * Example: mutation { unlockNode(input: { path: "/content/page1" }) }
	 */
	public Map<String, Object> executeUnlockNode(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");

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
	 * Execute setProperties mutation
	 * Sets multiple properties atomically with type-safe Union type values
	 * Example: mutation { setProperties(input: { path: "/content/page1", properties: [
	 *   { name: "title", value: { stringValue: "Hello" } },
	 *   { name: "count", value: { longValue: 100 } }
	 * ]}) { node { path } errors { message } } }
	 */
	public Map<String, Object> executeSetProperties(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		Object propertiesObj = input.get("properties");

		if (path == null || propertiesObj == null) {
			throw new IllegalArgumentException("path and properties are required");
		}

		if (!(propertiesObj instanceof java.util.List)) {
			throw new IllegalArgumentException("properties must be an array");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);
		java.util.List<Map<String, Object>> errors = new java.util.ArrayList<>();

		@SuppressWarnings("unchecked")
		java.util.List<Object> propertiesList = (java.util.List<Object>) propertiesObj;

		// Process each property
		for (Object propObj : propertiesList) {
			if (!(propObj instanceof Map)) {
				errors.add(createError("Each property must be an object with name and value fields"));
				continue;
			}

			@SuppressWarnings("unchecked")
			Map<String, Object> propertyInput = (Map<String, Object>) propObj;

			String name = (String) propertyInput.get("name");
			Object valueObj = propertyInput.get("value");

			if (name == null) {
				errors.add(createError("Property name is required"));
				continue;
			}

			try {
				Node targetNode = node.getNode("jcr:content");

				// If value is null or the string "null", delete the property
				if (valueObj == null || "null".equals(valueObj)) {
					if (targetNode.hasProperty(name)) {
						targetNode.getProperty(name).remove();
					}
					// If property doesn't exist, silently succeed (idempotent)
					continue;
				}

				if (!(valueObj instanceof Map)) {
					errors.add(createError("Property value must be a PropertyValueInput object or null"));
					continue;
				}

				@SuppressWarnings("unchecked")
				Map<String, Object> valueInput = (Map<String, Object>) valueObj;

				// Validate and extract property value using PropertyValue
				PropertyValue propValue = PropertyValue.fromInput(valueInput);

				// Set the property based on type
				setPropertyValue(targetNode, name, propValue);
			} catch (Throwable ex) {
				errors.add(createError("Failed to set property '" + name + "': " + ex.getMessage()));
			}
		}

		if (errors.isEmpty()) {
			// No errors, save changes
			this.session.save();
		} else {
			// There were errors, do not save partial changes
			this.session.refresh(false);
		}

		// Build response
		Map<String, Object> payload = new HashMap<>();
		payload.put("node", NodeMapper.toGraphQL(node));

		if (!errors.isEmpty()) {
			payload.put("errors", errors);
		} else {
			payload.put("errors", null);
		}

		Map<String, Object> result = new HashMap<>();
		result.put("setProperties", payload);

		return result;
	}

	/**
	 * Set a property value on a node using PropertyValue
	 */
	private void setPropertyValue(Node node, String name, PropertyValue propValue) throws Exception {
		int propertyType = propValue.getPropertyType();
		Object value = propValue.getValue();

		if (propValue.isMultiple()) {
			// Handle array values
			if (!(value instanceof java.util.List)) {
				throw new IllegalArgumentException("Array value must be a List");
			}

			@SuppressWarnings("unchecked")
			java.util.List<Object> valueList = (java.util.List<Object>) value;

			if (propertyType == javax.jcr.PropertyType.REFERENCE || propertyType == javax.jcr.PropertyType.WEAKREFERENCE) {
				// Handle reference arrays
				Value[] values = new Value[valueList.size()];
				for (int i = 0; i < valueList.size(); i++) {
					String uuid = valueList.get(i).toString();
					Node targetNode = this.session.getNodeByIdentifier(uuid);
					values[i] = this.session.getValueFactory().createValue(targetNode,
							propertyType == javax.jcr.PropertyType.WEAKREFERENCE);
				}
				node.setProperty(name, values);
			} else {
				// Handle other array types
				switch (propertyType) {
				case javax.jcr.PropertyType.STRING:
				case javax.jcr.PropertyType.NAME:
				case javax.jcr.PropertyType.PATH:
				case javax.jcr.PropertyType.URI:
					String[] strValues = valueList.stream().map(Object::toString).toArray(String[]::new);
					node.setProperty(name, strValues);
					break;
				case javax.jcr.PropertyType.BOOLEAN:
					Value[] boolValues = new Value[valueList.size()];
					for (int i = 0; i < valueList.size(); i++) {
						boolValues[i] = this.session.getValueFactory().createValue(Boolean.parseBoolean(valueList.get(i).toString()));
					}
					node.setProperty(name, boolValues);
					break;
				case javax.jcr.PropertyType.LONG:
					Value[] longValues = new Value[valueList.size()];
					for (int i = 0; i < valueList.size(); i++) {
						Object item = valueList.get(i);
						long longVal = (item instanceof Number) ? ((Number) item).longValue() : Long.parseLong(item.toString());
						longValues[i] = this.session.getValueFactory().createValue(longVal);
					}
					node.setProperty(name, longValues);
					break;
				case javax.jcr.PropertyType.DOUBLE:
				case javax.jcr.PropertyType.DECIMAL:
					Value[] doubleValues = new Value[valueList.size()];
					for (int i = 0; i < valueList.size(); i++) {
						Object item = valueList.get(i);
						double doubleVal = (item instanceof Number) ? ((Number) item).doubleValue() : Double.parseDouble(item.toString());
						doubleValues[i] = this.session.getValueFactory().createValue(doubleVal);
					}
					node.setProperty(name, doubleValues);
					break;
				case javax.jcr.PropertyType.DATE:
					Value[] dateValues = new Value[valueList.size()];
					for (int i = 0; i < valueList.size(); i++) {
						Calendar cal = parseISO8601Date(valueList.get(i).toString());
						dateValues[i] = this.session.getValueFactory().createValue(cal);
					}
					node.setProperty(name, dateValues);
					break;
				default:
					throw new IllegalArgumentException("Unsupported array property type: " + propValue.getType());
				}
			}
		} else {
			// Handle single values
			if (propertyType == javax.jcr.PropertyType.REFERENCE || propertyType == javax.jcr.PropertyType.WEAKREFERENCE) {
				// For Reference/WeakReference, value should be a UUID
				Node targetNode = this.session.getNodeByIdentifier(value.toString());
				Value refValue = this.session.getValueFactory().createValue(targetNode,
						propertyType == javax.jcr.PropertyType.WEAKREFERENCE);
				node.setProperty(name, refValue);
			} else if (propertyType == javax.jcr.PropertyType.BINARY) {
				// For Binary, value should be Base64 encoded
				byte[] data = Base64.getDecoder().decode(value.toString());
				node.setProperty(name, this.session.getValueFactory().createBinary(new ByteArrayInputStream(data)));
			} else if (propertyType == javax.jcr.PropertyType.DATE) {
				// For Date, value should be ISO 8601 string
				Calendar cal = parseISO8601Date(value.toString());
				node.setProperty(name, cal);
			} else {
				// For other types, use standard property setting
				switch (propertyType) {
				case javax.jcr.PropertyType.BOOLEAN:
					node.setProperty(name, Boolean.parseBoolean(value.toString()));
					break;
				case javax.jcr.PropertyType.LONG:
					long longVal = (value instanceof Number) ? ((Number) value).longValue() : Long.parseLong(value.toString());
					node.setProperty(name, longVal);
					break;
				case javax.jcr.PropertyType.DOUBLE:
				case javax.jcr.PropertyType.DECIMAL:
					double doubleVal = (value instanceof Number) ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
					node.setProperty(name, doubleVal);
					break;
				default:
					node.setProperty(name, value.toString());
					break;
				}
			}
		}
	}

	/**
	 * Create an error object for GraphQL response
	 */
	private Map<String, Object> createError(String message) {
		Map<String, Object> error = new HashMap<>();
		error.put("message", message);
		return error;
	}

	/**
	 * Parse ISO 8601 date string to Calendar
	 */
	private Calendar parseISO8601Date(String dateString) throws Exception {
		synchronized (ISO8601_FORMAT) {
			Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			cal.setTime(ISO8601_FORMAT.parse(dateString));
			return cal;
		}
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
	 * Execute setAccessControl mutation Sets or modifies ACL entry for a principal
	 * Supports both single entry and batch entry modes:
	 *
	 * Single entry mode:
	 * mutation { setAccessControl(input: { path: "/content/page1",
	 * principal: "user1", privileges: ["jcr:read", "jcr:write"], allow: true }) }
	 *
	 * Batch entry mode (array):
	 * mutation { setAccessControl(input: { path: "/content/page1",
	 * entries: [
	 *   { principal: "user1", privileges: ["jcr:read", "jcr:write"], allow: true },
	 *   { principal: "user2", privileges: ["jcr:read"], allow: true }
	 * ]}) }
	 */
	public Map<String, Object> executeSetAccessControl(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");

		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		// Get AccessControlManager
		AccessControlManager acm = this.session.getAccessControlManager();

		// Get or create AccessControlList
		AccessControlList acl = getOrCreateAccessControlList(acm, path);

		// Check if this is batch mode (entries array) or single entry mode
		if (input.containsKey("entries")) {
			// Batch mode: process multiple entries
			Object entriesObj = input.get("entries");
			if (!(entriesObj instanceof java.util.List)) {
				throw new IllegalArgumentException("entries must be an array");
			}

			@SuppressWarnings("unchecked")
			java.util.List<Object> entriesList = (java.util.List<Object>) entriesObj;

			for (Object entryObj : entriesList) {
				if (!(entryObj instanceof Map)) {
					throw new IllegalArgumentException("Each entry must be an object with principal, privileges, and optional allow fields");
				}

				@SuppressWarnings("unchecked")
				Map<String, Object> entry = (Map<String, Object>) entryObj;
				processAccessControlEntry(acm, acl, entry);
			}
		} else {
			// Single entry mode: backward compatibility
			processAccessControlEntry(acm, acl, input);
		}

		// Save policy
		acm.setPolicy(path, acl);
		this.session.save();

		// Return updated ACL entries
		java.util.List<Map<String, Object>> entries = buildAccessControlEntriesResponse(acl);

		Map<String, Object> aclData = new HashMap<>();
		aclData.put("entries", entries);

		Map<String, Object> result = new HashMap<>();
		result.put("setAccessControl", aclData);

		return result;
	}

	/**
	 * Get or create AccessControlList for the specified path
	 */
	private AccessControlList getOrCreateAccessControlList(
			AccessControlManager acm, String path) throws Exception {
		AccessControlList acl = null;

		// Try to get existing ACL
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

		return acl;
	}

	/**
	 * Process a single access control entry (add or update)
	 */
	private void processAccessControlEntry(AccessControlManager acm,
			AccessControlList acl, Map<String, Object> entryData) throws Exception {
		String principalName = (String) entryData.get("principal");
		Object privilegesObj = entryData.get("privileges");

		// Default to allow if not specified
		boolean allow = true;
		if (entryData.containsKey("allow") && entryData.get("allow") instanceof Boolean) {
			allow = ((Boolean) entryData.get("allow")).booleanValue();
		}

		if (principalName == null || privilegesObj == null) {
			throw new IllegalArgumentException("principal and privileges are required for each entry");
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
		if (acl instanceof org.mintjams.jcr.security.AccessControlList) {
			((org.mintjams.jcr.security.AccessControlList) acl).addAccessControlEntry(principal, allow, privileges);
		} else {
			// Standard JCR ACL does not support allow/deny, so we just add the entry
			acl.addAccessControlEntry(principal, privileges);
		}
	}

	/**
	 * Build response with all ACL entries
	 */
	private java.util.List<Map<String, Object>> buildAccessControlEntriesResponse(AccessControlList acl) throws Exception {
		java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();
		AccessControlEntry[] aclEntries = acl.getAccessControlEntries();

		for (AccessControlEntry entry : aclEntries) {
			Map<String, Object> entryMap = new HashMap<>();
			entryMap.put("principal", entry.getPrincipal().getName());

			java.util.List<String> privList = new java.util.ArrayList<>();
			for (Privilege priv : entry.getPrivileges()) {
				privList.add(priv.getName());
			}
			entryMap.put("privileges", privList);
			if (entry instanceof org.mintjams.jcr.security.AccessControlEntry) {
				entryMap.put("allow", ((org.mintjams.jcr.security.AccessControlEntry) entry).isAllow());
			} else {
				entryMap.put("allow", true);
			}

			entries.add(entryMap);
		}

		return entries;
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
	 * Execute checkin mutation
	 * Creates a new version by checking in a versionable node
	 * Example: mutation { checkin(input: { path: "/content/page1" }) }
	 */
	public Map<String, Object> executeCheckin(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");

		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);

		// Check if node is versionable
		if (!node.isNodeType("mix:versionable")) {
			throw new IllegalArgumentException("Node is not versionable: " + path);
		}

		// Get VersionManager
		VersionManager versionManager = this.session.getWorkspace().getVersionManager();

		// Check if already checked in
		if (!versionManager.isCheckedOut(path)) {
			throw new IllegalStateException("Node is already checked in: " + path);
		}

		// Checkin (creates a new version and locks the node)
		Version version = versionManager.checkin(path);

		// Build response with version info
		Map<String, Object> versionData = new HashMap<>();
		versionData.put("name", version.getName());
		if (version.hasProperty("jcr:created")) {
			versionData.put("created", version.getProperty("jcr:created").getDate().getTime().toString());
		}

		Map<String, Object> result = new HashMap<>();
		result.put("checkin", versionData);

		return result;
	}

	/**
	 * Execute checkout mutation
	 * Checks out a versionable node for editing
	 * Example: mutation { checkout(input: { path: "/content/page1" }) }
	 */
	public Map<String, Object> executeCheckout(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");

		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);

		// Check if node is versionable
		if (!node.isNodeType("mix:versionable")) {
			throw new IllegalArgumentException("Node is not versionable: " + path);
		}

		// Get VersionManager
		VersionManager versionManager = this.session.getWorkspace().getVersionManager();

		// Check if already checked out
		if (versionManager.isCheckedOut(path)) {
			throw new IllegalStateException("Node is already checked out: " + path);
		}

		// Checkout (unlocks the node for editing)
		versionManager.checkout(path);

		Map<String, Object> result = new HashMap<>();
		result.put("checkout", true);

		return result;
	}

	/**
	 * Execute renameNode mutation
	 * Renames a node by moving it to a new name within the same parent
	 * Example: mutation { renameNode(input: { path: "/content/oldname", name: "newname" }) { path name } }
	 */
	public Map<String, Object> executeRenameNode(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		String newName = (String) input.get("name");

		if (path == null || newName == null) {
			throw new IllegalArgumentException("path and name are required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);
		String parentPath = node.getParent().getPath();

		// Build new path
		String newPath;
		if ("/".equals(parentPath)) {
			newPath = "/" + newName;
		} else {
			newPath = parentPath + "/" + newName;
		}

		// Check if target path already exists
		if (this.session.nodeExists(newPath)) {
			throw new IllegalArgumentException("Node already exists at path: " + newPath);
		}

		// Rename by moving to new path
		this.session.move(path, newPath);
		this.session.save();

		// Get renamed node
		Node renamedNode = this.session.getNode(newPath);

		Map<String, Object> result = new HashMap<>();
		result.put("renameNode", NodeMapper.toGraphQL(renamedNode));

		return result;
	}

	/**
	 * Execute restoreVersion mutation
	 * Restores a node to a specific version
	 * Example: mutation { restoreVersion(input: { path: "/content/page1", versionName: "1.0" }) }
	 */
	public Map<String, Object> executeRestoreVersion(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String path = (String) input.get("path");
		String versionName = (String) input.get("versionName");

		if (path == null || versionName == null) {
			throw new IllegalArgumentException("path and versionName are required");
		}

		if (!this.session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = this.session.getNode(path);

		// Check if node is versionable
		if (!node.isNodeType("mix:versionable")) {
			throw new IllegalArgumentException("Node is not versionable: " + path);
		}

		// Get VersionManager
		VersionManager versionManager = this.session.getWorkspace().getVersionManager();

		// Must be checked out to restore
		if (!versionManager.isCheckedOut(path)) {
			versionManager.checkout(path);
		}

		// Restore to specified version
		// removeExisting=true allows restore even if there are local changes
		versionManager.restore(path, versionName, true);

		this.session.save();

		// Get restored node
		Node restoredNode = this.session.getNode(path);

		Map<String, Object> result = new HashMap<>();
		result.put("restoreVersion", NodeMapper.toGraphQL(restoredNode));

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
		boolean inString = false;
		for (int i = openBrace + 1; i < str.length(); i++) {
			char c = str.charAt(i);
			if (c == '"' && (i == 0 || str.charAt(i - 1) != '\\')) {
				inString = !inString;
			}
			if (!inString) {
				if (c == '{') {
					depth++;
				} else if (c == '}') {
					depth--;
					if (depth == 0) {
						return i;
					}
				}
			}
		}
		return -1;
	}

	/**
	 * Find matching closing bracket
	 */
	private int findMatchingBracket(String str, int openBracket) {
		int depth = 1;
		boolean inString = false;
		for (int i = openBracket + 1; i < str.length(); i++) {
			char c = str.charAt(i);
			if (c == '"' && (i == 0 || str.charAt(i - 1) != '\\')) {
				inString = !inString;
			}
			if (!inString) {
				if (c == '[') {
					depth++;
				} else if (c == ']') {
					depth--;
					if (depth == 0) {
						return i;
					}
				}
			}
		}
		return -1;
	}

	/**
	 * Parse array from GraphQL syntax
	 */
	private java.util.List<Object> parseArray(String arrayInput) {
		java.util.List<Object> result = new java.util.ArrayList<>();

		// Remove outer brackets and normalize whitespace
		String content = arrayInput.trim();
		if (content.startsWith("[")) {
			content = content.substring(1);
		}
		if (content.endsWith("]")) {
			content = content.substring(0, content.length() - 1);
		}
		content = content.trim();

		if (content.isEmpty()) {
			return result;
		}

		// Parse array elements
		int i = 0;
		while (i < content.length()) {
			// Skip whitespace
			while (i < content.length() && Character.isWhitespace(content.charAt(i))) {
				i++;
			}
			if (i >= content.length())
				break;

			// Extract element value
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
			} else if (content.charAt(i) == '{') {
				// Object value
				int objStart = i;
				int objEnd = findMatchingBrace(content, objStart);
				if (objEnd == -1) {
					throw new IllegalArgumentException("Invalid object in array: unmatched braces");
				}
				String objContent = content.substring(objStart, objEnd + 1);
				value = parseSimpleJson(objContent);
				i = objEnd + 1;
			} else if (content.charAt(i) == '[') {
				// Nested array value
				int arrayStart = i;
				int arrayEnd = findMatchingBracket(content, arrayStart);
				if (arrayEnd == -1) {
					throw new IllegalArgumentException("Invalid nested array: unmatched brackets");
				}
				String nestedArrayContent = content.substring(arrayStart, arrayEnd + 1);
				value = parseArray(nestedArrayContent);
				i = arrayEnd + 1;
			} else if (content.startsWith("true", i)) {
				value = Boolean.TRUE;
				i += 4;
			} else if (content.startsWith("false", i)) {
				value = Boolean.FALSE;
				i += 5;
			} else if (content.startsWith("null", i)) {
				value = null;
				i += 4;
			} else {
				// Number or other
				int valueStart = i;
				while (i < content.length() && !Character.isWhitespace(content.charAt(i))
						&& content.charAt(i) != ',' && content.charAt(i) != ']') {
					i++;
				}
				String valueStr = content.substring(valueStart, i).trim();
				if (!valueStr.isEmpty()) {
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
			}

			if (value != null || content.startsWith("null", i - 4)) {
				result.add(value);
			}

			// Skip optional comma and whitespace after value
			while (i < content.length() && (Character.isWhitespace(content.charAt(i)) || content.charAt(i) == ',')) {
				i++;
			}
		}

		return result;
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
			} else if (content.charAt(i) == '[') {
				// Array value
				int arrayStart = i;
				int arrayEnd = findMatchingBracket(content, arrayStart);
				if (arrayEnd == -1) {
					throw new IllegalArgumentException("Invalid array format: unmatched brackets");
				}
				String arrayContent = content.substring(arrayStart, arrayEnd + 1);
				value = parseArray(arrayContent);
				i = arrayEnd + 1;
			} else if (content.charAt(i) == '{') {
				// Nested object value
				int objStart = i;
				int objEnd = findMatchingBrace(content, objStart);
				if (objEnd == -1) {
					throw new IllegalArgumentException("Invalid object format: unmatched braces");
				}
				String objContent = content.substring(objStart, objEnd + 1);
				value = parseSimpleJson(objContent);
				i = objEnd + 1;
			} else if (content.startsWith("true", i)) {
				value = Boolean.TRUE;
				i += 4;
			} else if (content.startsWith("false", i)) {
				value = Boolean.FALSE;
				i += 5;
			} else {
				// Number or other
				int valueStart = i;
				while (i < content.length() && !Character.isWhitespace(content.charAt(i)) && content.charAt(i) != '}' && content.charAt(i) != ',') {
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

	// =============================================================================
	// Multipart Upload Mutations
	// =============================================================================

	/**
	 * Execute initiateMultipartUpload mutation
	 * Creates a new multipart upload session
	 * Example: mutation { initiateMultipartUpload(input: {}) { uploadId totalSize } }
	 */
	public Map<String, Object> executeInitiateMultipartUpload(GraphQLRequest request) throws Exception {
		// Input is optional and reserved for future use
		// Map<String, Object> input = extractInput(request);

		MultipartUploadManager uploadManager = new MultipartUploadManager(this.session);
		Map<String, Object> uploadInfo = uploadManager.initiate();

		Map<String, Object> result = new HashMap<>();
		result.put("initiateMultipartUpload", uploadInfo);

		return result;
	}

	/**
	 * Execute appendMultipartUploadChunk mutation
	 * Appends a Base64 encoded chunk to an existing upload
	 * Example: mutation { appendMultipartUploadChunk(input: { uploadId: "...", data: "..." }) { uploadId totalSize } }
	 */
	public Map<String, Object> executeAppendMultipartUploadChunk(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String uploadId = (String) input.get("uploadId");
		String data = (String) input.get("data");

		if (uploadId == null || uploadId.trim().isEmpty()) {
			throw new IllegalArgumentException("uploadId is required");
		}

		if (data == null || data.trim().isEmpty()) {
			throw new IllegalArgumentException("data is required");
		}

		MultipartUploadManager uploadManager = new MultipartUploadManager(this.session);
		Map<String, Object> uploadInfo = uploadManager.append(uploadId, data);

		Map<String, Object> result = new HashMap<>();
		result.put("appendMultipartUploadChunk", uploadInfo);

		return result;
	}

	/**
	 * Execute completeMultipartUpload mutation
	 * Completes the upload and creates the JCR node
	 * Example: mutation { completeMultipartUpload(input: { uploadId: "...", path: "/content", name: "file.txt", mimeType: "text/plain" }) { path name } }
	 */
	public Map<String, Object> executeCompleteMultipartUpload(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String uploadId = (String) input.get("uploadId");
		String path = (String) input.get("path");
		String name = (String) input.get("name");
		String mimeType = (String) input.get("mimeType");

		// Default overwrite to false
		boolean overwrite = false;
		if (input.containsKey("overwrite") && input.get("overwrite") instanceof Boolean) {
			overwrite = ((Boolean) input.get("overwrite")).booleanValue();
		}

		if (uploadId == null || uploadId.trim().isEmpty()) {
			throw new IllegalArgumentException("uploadId is required");
		}

		if (path == null || path.trim().isEmpty()) {
			throw new IllegalArgumentException("path is required");
		}

		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("name is required");
		}

		if (mimeType == null || mimeType.trim().isEmpty()) {
			throw new IllegalArgumentException("mimeType is required");
		}

		MultipartUploadManager uploadManager = new MultipartUploadManager(this.session);
		Node createdNode = uploadManager.complete(uploadId, path, name, mimeType, overwrite);

		Map<String, Object> result = new HashMap<>();
		result.put("completeMultipartUpload", NodeMapper.toGraphQL(createdNode));

		return result;
	}

	/**
	 * Execute abortMultipartUpload mutation
	 * Aborts the upload and cleans up temporary files
	 * Example: mutation { abortMultipartUpload(input: { uploadId: "..." }) }
	 */
	public Map<String, Object> executeAbortMultipartUpload(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);

		String uploadId = (String) input.get("uploadId");

		if (uploadId == null || uploadId.trim().isEmpty()) {
			throw new IllegalArgumentException("uploadId is required");
		}

		MultipartUploadManager uploadManager = new MultipartUploadManager(this.session);
		boolean aborted = uploadManager.abort(uploadId);

		Map<String, Object> result = new HashMap<>();
		result.put("abortMultipartUpload", aborted);

		return result;
	}
}

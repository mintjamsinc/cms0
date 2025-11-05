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

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.Session;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.Privilege;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

/**
 * Class for executing GraphQL Query operations
 */
public class QueryExecutor {

	private final Session session;

	// Query patterns (simple implementation)
	private static final Pattern NODE_QUERY_PATTERN = Pattern
			.compile("node\\s*\\(\\s*path\\s*:\\s*\"([^\"]+)\"\\s*\\)");
	private static final Pattern CHILDREN_QUERY_PATTERN = Pattern.compile(
			"children\\s*\\(\\s*path\\s*:\\s*\"([^\"]+)\"(?:\\s*,\\s*first\\s*:\\s*(\\d+))?(?:\\s*,\\s*after\\s*:\\s*\"([^\"]+)\")?\\s*\\)");

	public QueryExecutor(Session session) {
		this.session = session;
	}

	/**
	 * Execute node query Example: { node(path: "/content/page1") { name path } }
	 */
	public Map<String, Object> executeNodeQuery(GraphQLRequest request) throws Exception {
		String query = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Extract path
		String path = extractPath(query, NODE_QUERY_PATTERN, variables);
		if (path == null) {
			throw new IllegalArgumentException("Invalid node query: path not found");
		}

		Map<String, Object> result = new HashMap<>();
		if (session.nodeExists(path)) {
			Node node = session.getNode(path);
			result.put("node", NodeMapper.toGraphQL(node));
		} else {
			result.put("node", null);
		}

		return result;
	}

	/**
	 * Execute children query (Relay Connection specification)
	 * Example: { children(path: "/content", first: 10, after: "cursor") { edges { node { name } cursor } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }
	 */
	public Map<String, Object> executeChildrenQuery(GraphQLRequest request) throws Exception {
		String query = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Extract parameters
		Matcher matcher = CHILDREN_QUERY_PATTERN.matcher(query);
		if (!matcher.find()) {
			throw new IllegalArgumentException("Invalid children query");
		}

		String path = resolveVariable(matcher.group(1), variables);
		int first = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 20;
		String afterCursor = matcher.group(3) != null ? resolveVariable(matcher.group(3), variables) : null;

		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node parentNode = session.getNode(path);
		NodeIterator iterator = parentNode.getNodes();

		long totalCount = iterator.getSize();

		// If totalCount is unknown, count manually
		if (totalCount == -1) {
			totalCount = 0;
			while (iterator.hasNext()) {
				iterator.nextNode();
				totalCount++;
			}
			// Re-get iterator
			iterator = parentNode.getNodes();
		}

		// Decode cursor to get starting position
		int startPosition = 0;
		if (afterCursor != null && !afterCursor.isEmpty()) {
			startPosition = decodeCursor(afterCursor) + 1;
		}

		// Skip to start position
		if (startPosition > 0) {
			iterator.skip(startPosition);
		}

		// Build edges with cursors
		List<Map<String, Object>> edges = new ArrayList<>();
		int currentPosition = startPosition;
		int count = 0;

		while (iterator.hasNext() && count < first) {
			Node child = iterator.nextNode();

			Map<String, Object> edge = new HashMap<>();
			edge.put("node", NodeMapper.toGraphQL(child));
			edge.put("cursor", encodeCursor(currentPosition));

			edges.add(edge);
			currentPosition++;
			count++;
		}

		// Build pageInfo
		Map<String, Object> pageInfo = new HashMap<>();
		pageInfo.put("hasNextPage", currentPosition < totalCount);
		pageInfo.put("hasPreviousPage", startPosition > 0);

		if (!edges.isEmpty()) {
			pageInfo.put("startCursor", edges.get(0).get("cursor"));
			pageInfo.put("endCursor", edges.get(edges.size() - 1).get("cursor"));
		} else {
			pageInfo.put("startCursor", null);
			pageInfo.put("endCursor", null);
		}

		// Build connection
		Map<String, Object> connection = new HashMap<>();
		connection.put("edges", edges);
		connection.put("pageInfo", pageInfo);
		connection.put("totalCount", totalCount);

		Map<String, Object> result = new HashMap<>();
		result.put("children", connection);

		return result;
	}

	/**
	 * Encode position to Base64 cursor
	 */
	private String encodeCursor(int position) {
		String cursorString = "arrayconnection:" + position;
		return Base64.getEncoder().encodeToString(cursorString.getBytes());
	}

	/**
	 * Decode Base64 cursor to position
	 */
	private int decodeCursor(String cursor) {
		try {
			String decoded = new String(Base64.getDecoder().decode(cursor));
			if (decoded.startsWith("arrayconnection:")) {
				return Integer.parseInt(decoded.substring("arrayconnection:".length()));
			}
			return 0;
		} catch (Exception e) {
			return 0;
		}
	}

	/**
	 * Execute references query Returns nodes that reference the specified node
	 * Example: { references(path: "/content/target") { nodes { path name } } }
	 */
	public Map<String, Object> executeReferencesQuery(GraphQLRequest request) throws Exception {
		String query = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Extract path using simple regex
		Pattern pattern = Pattern.compile("references\\s*\\(\\s*path\\s*:\\s*\"([^\"]+)\"");
		String path = extractPath(query, pattern, variables);

		if (path == null) {
			throw new IllegalArgumentException("Invalid references query: path not found");
		}

		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node targetNode = session.getNode(path);

		// Get UUID if node is referenceable
		if (!targetNode.isNodeType("mix:referenceable")) {
			// If not referenceable, return empty list
			Map<String, Object> connection = new HashMap<>();
			connection.put("nodes", new ArrayList<>());
			connection.put("totalCount", 0);

			Map<String, Object> result = new HashMap<>();
			result.put("references", connection);
			return result;
		}

		String uuid = targetNode.getIdentifier();
		List<Map<String, Object>> referencingNodes = new ArrayList<>();

		// Search all nodes that have Reference or WeakReference properties pointing to
		// this UUID
		// Using property iterator approach (more efficient than full tree traversal)
		PropertyIterator refProps = targetNode.getReferences();
		while (refProps.hasNext()) {
			Property prop = refProps.nextProperty();
			Node referencingNode = prop.getParent();
			referencingNodes.add(NodeMapper.toGraphQL(referencingNode));
		}

		// Also check for weak references
		PropertyIterator weakRefProps = targetNode.getWeakReferences();
		while (weakRefProps.hasNext()) {
			Property prop = weakRefProps.nextProperty();
			Node referencingNode = prop.getParent();
			referencingNodes.add(NodeMapper.toGraphQL(referencingNode));
		}

		// Build response
		Map<String, Object> connection = new HashMap<>();
		connection.put("nodes", referencingNodes);
		connection.put("totalCount", referencingNodes.size());

		Map<String, Object> result = new HashMap<>();
		result.put("references", connection);

		return result;
	}

	/**
	 * Execute accessControl query Returns access control entries for the specified
	 * node Example: { accessControl(path: "/content/page1") { entries { principal
	 * privileges allow } } }
	 */
	public Map<String, Object> executeAccessControlQuery(GraphQLRequest request) throws Exception {
		String query = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Extract path using simple regex
		Pattern pattern = Pattern.compile("accessControl\\s*\\(\\s*path\\s*:\\s*\"([^\"]+)\"");
		String path = extractPath(query, pattern, variables);

		if (path == null) {
			throw new IllegalArgumentException("Invalid accessControl query: path not found");
		}

		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		// Get access control manager
		AccessControlManager acm = session.getAccessControlManager();
		List<Map<String, Object>> entries = new ArrayList<>();

		// Get access control policies
		AccessControlPolicy[] policies = acm.getPolicies(path);
		for (AccessControlPolicy policy : policies) {
			if (policy instanceof AccessControlList) {
				AccessControlList acl = (AccessControlList) policy;
				AccessControlEntry[] aclEntries = acl.getAccessControlEntries();

				for (AccessControlEntry entry : aclEntries) {
					Map<String, Object> entryMap = new HashMap<>();
					entryMap.put("principal", entry.getPrincipal().getName());

					// Get privileges
					List<String> privileges = new ArrayList<>();
					for (Privilege privilege : entry.getPrivileges()) {
						privileges.add(privilege.getName());
					}
					entryMap.put("privileges", privileges);

					// Check if this is allow or deny (JCR 2.0 doesn't have direct API, assume
					// allow)
					// Extended implementations may have isAllow() method
					entryMap.put("allow", true);

					entries.add(entryMap);
				}
			}
		}

		// Build response
		Map<String, Object> aclData = new HashMap<>();
		aclData.put("entries", entries);

		Map<String, Object> result = new HashMap<>();
		result.put("accessControl", aclData);

		return result;
	}

	/**
	 * Execute versionHistory query
	 * Retrieves version history for a versionable node
	 * Example: { versionHistory(path: "/content/page1") { versions { name created createdBy } } }
	 */
	public Map<String, Object> executeVersionHistoryQuery(GraphQLRequest request) throws Exception {
		// Extract path from query
		String query = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Pattern: versionHistory(path: "...") or versionHistory(path: $path)
		Pattern pattern = Pattern.compile("versionHistory\\(\\s*path\\s*:\\s*\"([^\"]+)\"");
		String path = extractPath(query, pattern, variables);

		if (path == null) {
			// Try variable pattern
			pattern = Pattern.compile("versionHistory\\(\\s*path\\s*:\\s*\\$([^\\s,)]+)");
			Matcher matcher = pattern.matcher(query);
			if (matcher.find()) {
				String varName = matcher.group(1);
				path = variables != null ? (String) variables.get(varName) : null;
			}
		}

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

		// Get version history
		VersionHistory versionHistory = versionManager.getVersionHistory(path);

		// Build versions list
		List<Map<String, Object>> versions = new ArrayList<>();
		VersionIterator versionIterator = versionHistory.getAllVersions();

		while (versionIterator.hasNext()) {
			Version version = versionIterator.nextVersion();

			// Skip root version (jcr:rootVersion)
			if (version.getName().equals("jcr:rootVersion")) {
				continue;
			}

			Map<String, Object> versionData = new HashMap<>();
			versionData.put("name", version.getName());

			// Get created date
			if (version.hasProperty("jcr:created")) {
				versionData.put("created", version.getProperty("jcr:created").getDate().getTime().toString());
			}

			// Get predecessor versions
			Version[] predecessors = version.getPredecessors();
			List<String> predecessorNames = new ArrayList<>();
			for (Version pred : predecessors) {
				if (!pred.getName().equals("jcr:rootVersion")) {
					predecessorNames.add(pred.getName());
				}
			}
			if (!predecessorNames.isEmpty()) {
				versionData.put("predecessors", predecessorNames);
			}

			// Get successor versions
			Version[] successors = version.getSuccessors();
			List<String> successorNames = new ArrayList<>();
			for (Version succ : successors) {
				successorNames.add(succ.getName());
			}
			if (!successorNames.isEmpty()) {
				versionData.put("successors", successorNames);
			}

			// Get frozen node (snapshot of the node at this version)
			Node frozenNode = version.getFrozenNode();
			if (frozenNode != null) {
				versionData.put("frozenNodePath", frozenNode.getPath());
			}

			versions.add(versionData);
		}

		// Get base version (current version)
		Version baseVersion = versionManager.getBaseVersion(path);
		String baseVersionName = baseVersion != null ? baseVersion.getName() : null;

		// Build response
		Map<String, Object> historyData = new HashMap<>();
		historyData.put("versions", versions);
		historyData.put("baseVersion", baseVersionName);
		historyData.put("versionableUuid", versionHistory.getVersionableIdentifier());

		Map<String, Object> result = new HashMap<>();
		result.put("versionHistory", historyData);

		return result;
	}

	/**
	 * Extract path (with variable support)
	 */
	private String extractPath(String query, Pattern pattern, Map<String, Object> variables) {
		Matcher matcher = pattern.matcher(query);
		if (matcher.find()) {
			String path = matcher.group(1);
			return resolveVariable(path, variables);
		}
		return null;
	}

	/**
	 * Resolve variable Example: $path -> variables["path"]
	 */
	private String resolveVariable(String value, Map<String, Object> variables) {
		if (value == null) {
			return null;
		}

		if (value.startsWith("$")) {
			String varName = value.substring(1);
			Object varValue = variables.get(varName);
			return varValue != null ? varValue.toString() : null;
		}

		return value;
	}
}

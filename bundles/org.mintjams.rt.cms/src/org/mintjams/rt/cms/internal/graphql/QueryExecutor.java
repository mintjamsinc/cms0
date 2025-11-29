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

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.Privilege;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

import org.mintjams.rt.cms.internal.graphql.ast.Field;
import org.mintjams.rt.cms.internal.graphql.ast.GraphQLParser;
import org.mintjams.rt.cms.internal.graphql.ast.Operation;
import org.mintjams.rt.cms.internal.graphql.ast.SelectionSet;
import org.mintjams.tools.lang.Strings;

/**
 * Class for executing GraphQL Query operations with advanced parsing and field selection optimization
 */
public class QueryExecutor {

	private final Session session;
	private final GraphQLParser parser;

	// ISO8601 date format for version dates
	private static final DateTimeFormatter ISO8601_FORMAT;
	static {
		ISO8601_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);
	}

	// Query patterns (simple implementation)
	private static final Pattern NODE_QUERY_PATTERN = Pattern
			.compile("node\\s*\\(\\s*path\\s*:\\s*\"([^\"]+)\"\\s*\\)");
	private static final Pattern CHILDREN_QUERY_PATTERN = Pattern.compile(
			"children\\s*\\(\\s*path\\s*:\\s*\"([^\"]+)\"(?:\\s*,\\s*first\\s*:\\s*(\\d+))?(?:\\s*,\\s*after\\s*:\\s*\"([^\"]+)\")?\\s*\\)");
	private static final Pattern REFERENCES_QUERY_PATTERN = Pattern.compile(
			"references\\s*\\(\\s*path\\s*:\\s*\"([^\"]+)\"(?:\\s*,\\s*first\\s*:\\s*(\\d+))?(?:\\s*,\\s*after\\s*:\\s*\"([^\"]+)\")?\\s*\\)");

	public QueryExecutor(Session session) {
		this.session = session;
		this.parser = new GraphQLParser();
	}

	/**
	 * Execute node query with field selection optimization
	 * Example: { node(path: "/content/page1") { name path } }
	 * Also supports variable syntax: query Node($path: String!) { node(path: $path) { name path } }
	 */
	public Map<String, Object> executeNodeQuery(GraphQLRequest request) throws Exception {
		String query = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Parse query to get field selection
		Operation operation = parser.parse(query, variables);
		Field rootField = operation.getRootField();
		SelectionSet nodeSelection = rootField != null ? rootField.getSelectionSet() : null;

		// Extract path - try literal pattern first, then variable pattern
		String path = extractPath(query, NODE_QUERY_PATTERN, variables);

		if (path == null) {
			// Try variable pattern: node(path: $path)
			Pattern varPattern = Pattern.compile("node\\s*\\(\\s*path\\s*:\\s*\\$([^\\s,)]+)");
			Matcher varMatcher = varPattern.matcher(query);
			if (varMatcher.find()) {
				String varName = varMatcher.group(1);
				path = variables != null ? (String) variables.get(varName) : null;
			}
		}

		if (path == null) {
			throw new IllegalArgumentException("Invalid node query: path not found " + query);
		}

		Map<String, Object> result = new HashMap<>();
		if (session.nodeExists(path)) {
			Node node = session.getNode(path);
			// Use optimized mapper with field selection
			result.put("node", NodeMapper.toGraphQL(node, nodeSelection));
		} else {
			result.put("node", null);
		}

		return result;
	}

	/**
	 * Execute children query (Relay Connection specification) with field selection optimization
	 * Example: { children(path: "/content", first: 10, after: "cursor") { edges { node { name } cursor } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }
	 * Also supports variable syntax: query ListChildren($path: String!, $first: Int, $after: String) { children(path: $path, first: $first, after: $after) { ... } }
	 */
	public Map<String, Object> executeChildrenQuery(GraphQLRequest request) throws Exception {
		String query = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Parse query to get field selection
		Operation operation = parser.parse(query, variables);
		Field rootField = operation.getRootField();
		SelectionSet childrenSelection = rootField != null ? rootField.getSelectionSet() : null;

		// Extract node selection from edges.node
		SelectionSet nodeSelection = null;
		if (childrenSelection != null && childrenSelection.hasField("edges")) {
			SelectionSet edgesSelection = childrenSelection.getNestedSelectionSet("edges");
			if (edgesSelection != null && edgesSelection.hasField("node")) {
				nodeSelection = edgesSelection.getNestedSelectionSet("node");
			}
		}

		// Extract parameters - try literal pattern first, then variable pattern
		String path = null;
		int first = 20; // default
		String afterCursor = null;

		Matcher matcher = CHILDREN_QUERY_PATTERN.matcher(query);
		if (matcher.find()) {
			// Literal pattern matched
			path = resolveVariable(matcher.group(1), variables);
			first = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 20;
			afterCursor = matcher.group(3) != null ? resolveVariable(matcher.group(3), variables) : null;
		} else {
			// Try variable pattern: children(path: $path, first: $first, after: $after)
			// Extract path variable
			Pattern pathVarPattern = Pattern.compile("children\\s*\\([^)]*path\\s*:\\s*\\$([^\\s,)]+)");
			Matcher pathVarMatcher = pathVarPattern.matcher(query);
			if (pathVarMatcher.find()) {
				String varName = pathVarMatcher.group(1);
				path = variables != null ? (String) variables.get(varName) : null;
			}

			// Extract first variable
			Pattern firstVarPattern = Pattern.compile("children\\s*\\([^)]*first\\s*:\\s*\\$([^\\s,)]+)");
			Matcher firstVarMatcher = firstVarPattern.matcher(query);
			if (firstVarMatcher.find()) {
				String varName = firstVarMatcher.group(1);
				Object varValue = variables != null ? variables.get(varName) : null;
				if (varValue != null) {
					first = varValue instanceof Number ? ((Number) varValue).intValue() : Integer.parseInt(varValue.toString());
				}
			}

			// Extract after variable
			Pattern afterVarPattern = Pattern.compile("children\\s*\\([^)]*after\\s*:\\s*\\$([^\\s,)]+)");
			Matcher afterVarMatcher = afterVarPattern.matcher(query);
			if (afterVarMatcher.find()) {
				String varName = afterVarMatcher.group(1);
				afterCursor = variables != null ? (String) variables.get(varName) : null;
			}
		}

		if (path == null) {
			throw new IllegalArgumentException("Invalid children query: path not found " + query);
		}

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

		// Build edges with cursors (using optimized field selection)
		List<Map<String, Object>> edges = new ArrayList<>();
		int currentPosition = startPosition;
		int count = 0;

		while (iterator.hasNext() && count < first) {
			Node child = iterator.nextNode();

			Map<String, Object> edge = new HashMap<>();
			// Use optimized mapper with field selection
			edge.put("node", NodeMapper.toGraphQL(child, nodeSelection));
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
		} catch (Throwable ex) {
			return 0;
		}
	}

	/**
	 * Execute references query with Relay Connection format and pagination
	 * Returns nodes that reference the specified node in Connection format
	 * Example: { references(path: "/content/target", first: 10, after: "cursor") { edges { node { path name } cursor } pageInfo { hasNextPage } totalCount } }
	 * Also supports variable syntax: query ListReferences($path: String!, $first: Int, $after: String) { references(path: $path, first: $first, after: $after) { ... } }
	 */
	public Map<String, Object> executeReferencesQuery(GraphQLRequest request) throws Exception {
		String query = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Parse query to get field selection
		Operation operation = parser.parse(query, variables);
		Field rootField = operation.getRootField();
		SelectionSet referencesSelection = rootField != null ? rootField.getSelectionSet() : null;

		// Extract node selection from edges.node
		SelectionSet nodeSelection = null;
		if (referencesSelection != null && referencesSelection.hasField("edges")) {
			SelectionSet edgesSelection = referencesSelection.getNestedSelectionSet("edges");
			if (edgesSelection != null && edgesSelection.hasField("node")) {
				nodeSelection = edgesSelection.getNestedSelectionSet("node");
			}
		}

		// Extract parameters - try literal pattern first, then variable pattern
		String path = null;
		int first = 20; // default
		String afterCursor = null;

		Matcher matcher = REFERENCES_QUERY_PATTERN.matcher(query);
		if (matcher.find()) {
			// Literal pattern matched
			path = resolveVariable(matcher.group(1), variables);
			first = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 20;
			afterCursor = matcher.group(3) != null ? resolveVariable(matcher.group(3), variables) : null;
		} else {
			// Try variable pattern: references(path: $path, first: $first, after: $after)
			// Extract path variable
			Pattern pathVarPattern = Pattern.compile("references\\s*\\([^)]*path\\s*:\\s*\\$([^\\s,)]+)");
			Matcher pathVarMatcher = pathVarPattern.matcher(query);
			if (pathVarMatcher.find()) {
				String varName = pathVarMatcher.group(1);
				path = variables != null ? (String) variables.get(varName) : null;
			}

			// Extract first variable
			Pattern firstVarPattern = Pattern.compile("references\\s*\\([^)]*first\\s*:\\s*\\$([^\\s,)]+)");
			Matcher firstVarMatcher = firstVarPattern.matcher(query);
			if (firstVarMatcher.find()) {
				String varName = firstVarMatcher.group(1);
				Object varValue = variables != null ? variables.get(varName) : null;
				if (varValue != null) {
					first = varValue instanceof Number ? ((Number) varValue).intValue() : Integer.parseInt(varValue.toString());
				}
			}

			// Extract after variable
			Pattern afterVarPattern = Pattern.compile("references\\s*\\([^)]*after\\s*:\\s*\\$([^\\s,)]+)");
			Matcher afterVarMatcher = afterVarPattern.matcher(query);
			if (afterVarMatcher.find()) {
				String varName = afterVarMatcher.group(1);
				afterCursor = variables != null ? (String) variables.get(varName) : null;
			}
		}

		if (path == null) {
			throw new IllegalArgumentException("Invalid references query: path not found " + query);
		}

		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node targetNode = session.getNode(path);

		// Get UUID if node is referenceable
		if (!targetNode.isNodeType("mix:referenceable")) {
			// If not referenceable, return empty connection
			Map<String, Object> pageInfo = new HashMap<>();
			pageInfo.put("hasNextPage", false);
			pageInfo.put("hasPreviousPage", false);
			pageInfo.put("startCursor", null);
			pageInfo.put("endCursor", null);

			Map<String, Object> connection = new HashMap<>();
			connection.put("edges", new ArrayList<>());
			connection.put("pageInfo", pageInfo);
			connection.put("totalCount", 0);

			Map<String, Object> result = new HashMap<>();
			result.put("references", connection);
			return result;
		}

		// Get property iterators for both reference types
		// Memory efficient approach using iterator.skip()
		PropertyIterator refProps = targetNode.getReferences();
		PropertyIterator weakRefProps = targetNode.getWeakReferences();

		// Calculate total count from both iterators
		long refCount = refProps.getSize();
		long weakRefCount = weakRefProps.getSize();
		long totalCount = refCount + weakRefCount;

		// Decode cursor to get starting position
		int startPosition = 0;
		if (afterCursor != null && !afterCursor.isEmpty()) {
			startPosition = decodeCursor(afterCursor) + 1;
		}

		// Build edges with cursors (using optimized field selection)
		List<Map<String, Object>> edges = new ArrayList<>();
		int currentPosition = startPosition;
		int count = 0;

		// Skip to start position and build edges
		// First process regular references
		if (startPosition < refCount) {
			// Skip to start position in regular references
			if (startPosition > 0) {
				refProps.skip(startPosition);
			}

			// Add edges from regular references
			while (refProps.hasNext() && count < first) {
				Property prop = refProps.nextProperty();
				Node referencingNode = prop.getParent();
				if (referencingNode.getName().equals("jcr:content")) {
					referencingNode = referencingNode.getParent();
				}

				Map<String, Object> edge = new HashMap<>();
				edge.put("node", NodeMapper.toGraphQL(referencingNode, nodeSelection));
				edge.put("cursor", encodeCursor(currentPosition));

				edges.add(edge);
				currentPosition++;
				count++;
			}
		}

		// Then process weak references if needed
		if (count < first && startPosition < totalCount) {
			// Calculate position in weak references iterator
			long weakRefStartPos = Math.max(0, startPosition - refCount);

			if (weakRefStartPos > 0) {
				weakRefProps.skip(weakRefStartPos);
			}

			// Add edges from weak references
			while (weakRefProps.hasNext() && count < first) {
				Property prop = weakRefProps.nextProperty();
				Node referencingNode = prop.getParent();

				Map<String, Object> edge = new HashMap<>();
				edge.put("node", NodeMapper.toGraphQL(referencingNode, nodeSelection));
				edge.put("cursor", encodeCursor(currentPosition));

				edges.add(edge);
				currentPosition++;
				count++;
			}
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
	 * Retrieves version history for a versionable node with edges/pageInfo format
	 * Example: { versionHistory(path: "/content/page1") { edges { node { name created createdBy } cursor } pageInfo { hasNextPage } totalCount baseVersion { name created } } }
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

		// Build versions list in edges format
		List<Map<String, Object>> edges = new ArrayList<>();
		VersionIterator versionIterator = versionHistory.getAllVersions();
		int index = 0;

		while (versionIterator.hasNext()) {
			Version version = versionIterator.nextVersion();

			// Skip root version (jcr:rootVersion)
			if (version.getName().equals("jcr:rootVersion")) {
				continue;
			}

			Map<String, Object> versionData = new HashMap<>();
			versionData.put("name", version.getName());

			// Get created date in ISO8601 format
			if (version.hasProperty("jcr:created")) {
				java.util.Calendar createdCal = version.getProperty("jcr:created").getDate();
				versionData.put("created", ISO8601_FORMAT.format(createdCal.toInstant()));
			}

			// Get createdBy from frozen node
			Node frozenNode = version.getFrozenNode();
			if (frozenNode != null) {
				if (frozenNode.hasProperty("jcr:createdBy")) {
					versionData.put("createdBy", frozenNode.getProperty("jcr:createdBy").getString());
				}
				versionData.put("frozenNodePath", frozenNode.getPath());
			}

			// Get predecessor versions
			Version[] predecessors = version.getPredecessors();
			List<String> predecessorNames = new ArrayList<>();
			for (Version pred : predecessors) {
				if (!pred.getName().equals("jcr:rootVersion")) {
					predecessorNames.add(pred.getName());
				}
			}
			versionData.put("predecessors", predecessorNames);

			// Get successor versions
			Version[] successors = version.getSuccessors();
			List<String> successorNames = new ArrayList<>();
			for (Version succ : successors) {
				successorNames.add(succ.getName());
			}
			versionData.put("successors", successorNames);

			// Build edge with cursor
			Map<String, Object> edge = new HashMap<>();
			edge.put("node", versionData);
			edge.put("cursor", Base64.getEncoder().encodeToString(("version:" + index).getBytes()));
			edges.add(edge);
			index++;
		}

		// Get base version (current version)
		Version baseVersion = versionManager.getBaseVersion(path);
		Map<String, Object> baseVersionData = null;
		if (baseVersion != null) {
			baseVersionData = new HashMap<>();
			baseVersionData.put("name", baseVersion.getName());
			if (baseVersion.hasProperty("jcr:created")) {
				java.util.Calendar createdCal = baseVersion.getProperty("jcr:created").getDate();
				baseVersionData.put("created", ISO8601_FORMAT.format(createdCal.toInstant()));
			}
		}

		// Build pageInfo
		Map<String, Object> pageInfo = new HashMap<>();
		pageInfo.put("hasNextPage", false);
		pageInfo.put("hasPreviousPage", false);
		if (!edges.isEmpty()) {
			pageInfo.put("startCursor", edges.get(0).get("cursor"));
			pageInfo.put("endCursor", edges.get(edges.size() - 1).get("cursor"));
		}

		// Build response
		Map<String, Object> historyData = new HashMap<>();
		historyData.put("edges", edges);
		historyData.put("pageInfo", pageInfo);
		historyData.put("totalCount", edges.size());
		historyData.put("baseVersion", baseVersionData);
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

	/**
	 * Execute XPath search query with field selection optimization
	 * Example: { xpath(query: "//element(*, nt:file)", first: 20, after: "cursor") { edges { node { path name } cursor } pageInfo { hasNextPage } totalCount } }
	 */
	public Map<String, Object> executeXPathQuery(GraphQLRequest request) throws Exception {
		String queryStr = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Parse query to extract node selection
		SelectionSet nodeSelection = extractNodeSelection(request);

		// Extract query string - try literal pattern first, then variable pattern
		String jcrQuery = null;
		Pattern literalPattern = Pattern.compile("xpath\\s*\\(\\s*query\\s*:\\s*\"([^\"]+)\"");
		Matcher literalMatcher = literalPattern.matcher(queryStr);
		if (literalMatcher.find()) {
			jcrQuery = resolveVariable(literalMatcher.group(1), variables);
		}

		if (jcrQuery == null) {
			// Try variable pattern: xpath(query: $query, ...)
			Pattern varPattern = Pattern.compile("xpath\\s*\\(\\s*query\\s*:\\s*\\$([^\\s,)]+)");
			Matcher varMatcher = varPattern.matcher(queryStr);
			if (varMatcher.find()) {
				String varName = varMatcher.group(1);
				jcrQuery = variables != null ? (String) variables.get(varName) : null;
			}
		}

		if (jcrQuery == null) {
			throw new IllegalArgumentException("Invalid xpath query: query parameter not found " + queryStr);
		}

		// Extract pagination parameters
		int first = 20; // default
		String afterCursor = null;

		// Try literal first, then variable pattern for 'first'
		Pattern firstPattern = Pattern.compile("first\\s*:\\s*(\\d+)");
		Matcher firstMatcher = firstPattern.matcher(queryStr);
		if (firstMatcher.find()) {
			first = Integer.parseInt(firstMatcher.group(1));
		} else {
			// Try variable pattern: first: $first
			Pattern firstVarPattern = Pattern.compile("first\\s*:\\s*\\$([^\\s,)]+)");
			Matcher firstVarMatcher = firstVarPattern.matcher(queryStr);
			if (firstVarMatcher.find()) {
				String varName = firstVarMatcher.group(1);
				Object varValue = variables != null ? variables.get(varName) : null;
				if (varValue != null) {
					first = varValue instanceof Number ? ((Number) varValue).intValue() : Integer.parseInt(varValue.toString());
				}
			}
		}

		// Try literal first, then variable pattern for 'after'
		Pattern afterPattern = Pattern.compile("after\\s*:\\s*\"([^\"]+)\"");
		Matcher afterMatcher = afterPattern.matcher(queryStr);
		if (afterMatcher.find()) {
			afterCursor = resolveVariable(afterMatcher.group(1), variables);
		} else {
			// Try variable pattern: after: $after
			Pattern afterVarPattern = Pattern.compile("after\\s*:\\s*\\$([^\\s,)]+)");
			Matcher afterVarMatcher = afterVarPattern.matcher(queryStr);
			if (afterVarMatcher.find()) {
				String varName = afterVarMatcher.group(1);
				afterCursor = variables != null ? (String) variables.get(varName) : null;
			}
		}

		// Execute query
		QueryManager queryManager = session.getWorkspace().getQueryManager();
		Query query = queryManager.createQuery(jcrQuery, Query.XPATH);
		QueryResult queryResult = query.execute();

		// Get node iterator and total count
		NodeIterator nodeIterator = queryResult.getNodes();
		long totalCount = nodeIterator.getSize();

		// Calculate pagination
		int startPosition = 0;
		if (afterCursor != null && !afterCursor.isEmpty()) {
			startPosition = decodeCursor(afterCursor) + 1;
		}

		// Skip to start position (memory efficient)
		if (startPosition > 0) {
			nodeIterator.skip(startPosition);
		}

		// Build edges with optimized field selection (only fetch 'first' items)
		List<Map<String, Object>> edges = new ArrayList<>();
		int currentPosition = startPosition;
		int count = 0;

		while (nodeIterator.hasNext() && count < first) {
			Node node = nodeIterator.nextNode();

			Map<String, Object> edge = new HashMap<>();
			edge.put("node", NodeMapper.toGraphQL(node, nodeSelection));
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
		result.put("xpath", connection);

		return result;
	}

	/**
	 * Execute fulltext search query with field selection optimization
	 * Example: { search(text: "hello world", path: "/content", first: 20, after: "cursor") { edges { node { path name score } cursor } pageInfo { hasNextPage } totalCount } }
	 */
	public Map<String, Object> executeSearchQuery(GraphQLRequest request) throws Exception {
		String queryStr = request.getQuery();

		// Parse query to extract node selection
		SelectionSet nodeSelection = extractNodeSelection(request);
		Map<String, Object> variables = request.getVariables();

		// Extract search text - try literal pattern first, then variable pattern
		String searchText = null;
		Pattern textLiteralPattern = Pattern.compile("search\\s*\\([^)]*text\\s*:\\s*\"([^\"]+)\"");
		Matcher textLiteralMatcher = textLiteralPattern.matcher(queryStr);
		if (textLiteralMatcher.find()) {
			searchText = resolveVariable(textLiteralMatcher.group(1), variables);
		}

		if (searchText == null) {
			// Try variable pattern: text: $text
			Pattern textVarPattern = Pattern.compile("search\\s*\\([^)]*text\\s*:\\s*\\$([^\\s,)]+)");
			Matcher textVarMatcher = textVarPattern.matcher(queryStr);
			if (textVarMatcher.find()) {
				String varName = textVarMatcher.group(1);
				searchText = variables != null ? (String) variables.get(varName) : null;
			}
		}

		if (searchText == null) {
			throw new IllegalArgumentException("Invalid search query: text parameter not found " + queryStr);
		}

		// Extract optional path parameter (search root) - try literal first, then variable
		String searchPath = "/";
		Pattern pathLiteralPattern = Pattern.compile("path\\s*:\\s*\"([^\"]+)\"");
		Matcher pathLiteralMatcher = pathLiteralPattern.matcher(queryStr);
		if (pathLiteralMatcher.find()) {
			searchPath = resolveVariable(pathLiteralMatcher.group(1), variables);
		} else {
			// Try variable pattern: path: $path
			Pattern pathVarPattern = Pattern.compile("[^a-z]path\\s*:\\s*\\$([^\\s,)]+)");
			Matcher pathVarMatcher = pathVarPattern.matcher(queryStr);
			if (pathVarMatcher.find()) {
				String varName = pathVarMatcher.group(1);
				String varValue = variables != null ? (String) variables.get(varName) : null;
				if (varValue != null) {
					searchPath = varValue;
				}
			}
		}

		// Extract pagination parameters
		int first = 20; // default
		String afterCursor = null;

		// Try literal first, then variable pattern for 'first'
		Pattern firstPattern = Pattern.compile("first\\s*:\\s*(\\d+)");
		Matcher firstMatcher = firstPattern.matcher(queryStr);
		if (firstMatcher.find()) {
			first = Integer.parseInt(firstMatcher.group(1));
		} else {
			// Try variable pattern: first: $first
			Pattern firstVarPattern = Pattern.compile("first\\s*:\\s*\\$([^\\s,)]+)");
			Matcher firstVarMatcher = firstVarPattern.matcher(queryStr);
			if (firstVarMatcher.find()) {
				String varName = firstVarMatcher.group(1);
				Object varValue = variables != null ? variables.get(varName) : null;
				if (varValue != null) {
					first = varValue instanceof Number ? ((Number) varValue).intValue() : Integer.parseInt(varValue.toString());
				}
			}
		}

		// Try literal first, then variable pattern for 'after'
		Pattern afterPattern = Pattern.compile("after\\s*:\\s*\"([^\"]+)\"");
		Matcher afterMatcher = afterPattern.matcher(queryStr);
		if (afterMatcher.find()) {
			afterCursor = resolveVariable(afterMatcher.group(1), variables);
		} else {
			// Try variable pattern: after: $after
			Pattern afterVarPattern = Pattern.compile("after\\s*:\\s*\\$([^\\s,)]+)");
			Matcher afterVarMatcher = afterVarPattern.matcher(queryStr);
			if (afterVarMatcher.find()) {
				String varName = afterVarMatcher.group(1);
				afterCursor = variables != null ? (String) variables.get(varName) : null;
			}
		}

		// Build XPath query for fulltext search
		if (Strings.isEmpty(searchPath)) {
			// Use root path
			searchPath = "";
		} else if (!searchPath.startsWith("/")) {
			throw new IllegalArgumentException("Invalid search path: " + searchPath);
		} else if (searchPath.endsWith("/")) {
			// Remove trailing slash
			searchPath = searchPath.substring(0, searchPath.length() - 1);
		}
		if (!Strings.isEmpty(searchPath)) {
			searchPath = "/jcr:root" + searchPath;
		}
		String xpathQuery = searchPath + "//element(*, nt:file)[jcr:contains(., '" + searchText.replaceAll("'", "\\'") + "')]";

		// Execute XPath query
		QueryManager queryManager = session.getWorkspace().getQueryManager();
		Query query = queryManager.createQuery(xpathQuery, Query.XPATH);
		QueryResult queryResult = query.execute();

		// Get row iterator - memory efficient approach using iterator.skip()
		RowIterator rowIterator = queryResult.getRows();
		long totalCount = rowIterator.getSize();

		// Calculate pagination
		int startPosition = 0;
		if (afterCursor != null && !afterCursor.isEmpty()) {
			startPosition = decodeCursor(afterCursor) + 1;
		}

		// Skip to start position (memory efficient)
		if (startPosition > 0) {
			rowIterator.skip(startPosition);
		}

		// Build edges with optimized field selection (only fetch 'first' items)
		List<Map<String, Object>> edges = new ArrayList<>();
		int currentPosition = startPosition;
		int count = 0;

		while (rowIterator.hasNext() && count < first) {
			Row row = rowIterator.nextRow();
			Node node = row.getNode();
			double score = row.getScore();

			Map<String, Object> nodeData = NodeMapper.toGraphQL(node, nodeSelection);
			nodeData.put("score", score);

			Map<String, Object> edge = new HashMap<>();
			edge.put("node", nodeData);
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
		result.put("search", connection);

		return result;
	}

	/**
	 * Escape SQL special characters for CONTAINS clause
	 */
	private String escapeSql(String text) {
		// Escape single quotes for JCR-SQL2
		return text.replace("'", "''");
	}

	/**
	 * Execute generic query with specified language and field selection optimization
	 * Supports: XPath, JCR-SQL2, SQL (deprecated)
	 * Example: { query(statement: "SELECT * FROM [nt:base]", language: "JCR-SQL2", first: 20) { edges { node { path name } cursor } pageInfo { hasNextPage } totalCount } }
	 */
	public Map<String, Object> executeGenericQuery(GraphQLRequest request) throws Exception {
		String queryStr = request.getQuery();
		Map<String, Object> variables = request.getVariables();

		// Parse query to extract node selection
		SelectionSet nodeSelection = extractNodeSelection(request);

		// Extract query statement
		Pattern statementPattern = Pattern.compile("query\\s*\\([^)]*statement\\s*:\\s*\"([^\"]+)\"");
		Matcher statementMatcher = statementPattern.matcher(queryStr);
		if (!statementMatcher.find()) {
			throw new IllegalArgumentException("Invalid query: statement parameter not found");
		}
		String statement = resolveVariable(statementMatcher.group(1), variables);

		// Extract language parameter (required for generic query)
		String language = Query.JCR_SQL2; // default
		Pattern languagePattern = Pattern.compile("language\\s*:\\s*\"([^\"]+)\"");
		Matcher languageMatcher = languagePattern.matcher(queryStr);
		if (languageMatcher.find()) {
			String langParam = resolveVariable(languageMatcher.group(1), variables);
			language = normalizeLanguage(langParam);
		}

		// Extract pagination parameters
		int first = 20; // default
		String afterCursor = null;

		Pattern firstPattern = Pattern.compile("first\\s*:\\s*(\\d+)");
		Matcher firstMatcher = firstPattern.matcher(queryStr);
		if (firstMatcher.find()) {
			first = Integer.parseInt(firstMatcher.group(1));
		}

		Pattern afterPattern = Pattern.compile("after\\s*:\\s*\"([^\"]+)\"");
		Matcher afterMatcher = afterPattern.matcher(queryStr);
		if (afterMatcher.find()) {
			afterCursor = resolveVariable(afterMatcher.group(1), variables);
		}

		// Execute query
		QueryManager queryManager = session.getWorkspace().getQueryManager();
		Query query = queryManager.createQuery(statement, language);
		QueryResult queryResult = query.execute();

		// Get node iterator and total count
		NodeIterator nodeIterator = queryResult.getNodes();
		long totalCount = nodeIterator.getSize();

		// Calculate pagination
		int startPosition = 0;
		if (afterCursor != null && !afterCursor.isEmpty()) {
			startPosition = decodeCursor(afterCursor) + 1;
		}

		// Skip to start position (memory efficient)
		if (startPosition > 0) {
			nodeIterator.skip(startPosition);
		}

		// Build edges with optimized field selection (only fetch 'first' items)
		List<Map<String, Object>> edges = new ArrayList<>();
		int currentPosition = startPosition;
		int count = 0;

		while (nodeIterator.hasNext() && count < first) {
			Node node = nodeIterator.nextNode();

			Map<String, Object> edge = new HashMap<>();
			edge.put("node", NodeMapper.toGraphQL(node, nodeSelection));
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
		result.put("query", connection);

		return result;
	}

	/**
	 * Normalize query language parameter
	 * Supports user-friendly aliases
	 */
	private String normalizeLanguage(String language) {
		if (language == null || language.isEmpty()) {
			return Query.JCR_SQL2; // default
		}

		// Normalize to uppercase for comparison
		String normalized = language.toUpperCase().trim();

		// Support various formats
		switch (normalized) {
			case "XPATH":
			case "JCR-XPATH":
				return Query.XPATH;

			case "SQL2":
			case "JCR-SQL2":
			case "JCRSQL2":
				return Query.JCR_SQL2;

			case "SQL":
			case "JCR-SQL":
			case "JCRSQL":
				return Query.SQL; // deprecated but still supported

			default:
				// Try to use as-is (for future language support)
				return language;
		}
	}

	/**
	 * Helper method to extract node selection from edges.node
	 * Common pattern for connection queries (children, search, xpath, etc.)
	 */
	private SelectionSet extractNodeSelection(GraphQLRequest request) {
		try {
			Operation operation = parser.parse(request.getQuery(), request.getVariables());
			Field rootField = operation.getRootField();
			if (rootField == null) {
				return null;
			}

			SelectionSet rootSelection = rootField.getSelectionSet();
			if (rootSelection == null || !rootSelection.hasField("edges")) {
				return null;
			}

			SelectionSet edgesSelection = rootSelection.getNestedSelectionSet("edges");
			if (edgesSelection == null || !edgesSelection.hasField("node")) {
				return null;
			}

			return edgesSelection.getNestedSelectionSet("node");
		} catch (Throwable ex) {
			// If parsing fails, return null (fallback to include all fields)
			return null;
		}
	}
}

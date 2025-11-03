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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

/**
 * Class for executing GraphQL Query operations
 */
public class QueryExecutor {

	private final Session session;

	// Query patterns (simple implementation)
	private static final Pattern NODE_QUERY_PATTERN = Pattern.compile("node\\s*\\(\\s*path\\s*:\\s*\"([^\"]+)\"\\s*\\)");
	private static final Pattern CHILDREN_QUERY_PATTERN = Pattern
			.compile("children\\s*\\(\\s*path\\s*:\\s*\"([^\"]+)\"(?:\\s*,\\s*limit\\s*:\\s*(\\d+))?(?:\\s*,\\s*offset\\s*:\\s*(\\d+))?\\s*\\)");

	public QueryExecutor(Session session) {
		this.session = session;
	}

	/**
	 * Execute node query
	 * Example: { node(path: "/content/page1") { name path } }
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
	 * Execute children query
	 * Example: { children(path: "/content", limit: 10, offset: 0) { nodes { name } } }
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
		int limit = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 20;
		int offset = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;

		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node parentNode = session.getNode(path);
		NodeIterator iterator = parentNode.getNodes();

		long totalCount = iterator.getSize();
		List<Map<String, Object>> nodes = new ArrayList<>();

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

		// Skip to offset (only if offset > 0)
		if (offset > 0) {
			iterator.skip(offset);
		}

		// Retrieve up to limit items
		int count = 0;
		while (iterator.hasNext() && count < limit) {
			Node child = iterator.nextNode();
			nodes.add(NodeMapper.toGraphQL(child));
			count++;
		}

		// Build response
		Map<String, Object> connection = new HashMap<>();
		connection.put("nodes", nodes);
		connection.put("totalCount", totalCount);

		Map<String, Object> pageInfo = new HashMap<>();
		pageInfo.put("hasNextPage", offset + limit < totalCount);
		pageInfo.put("hasPreviousPage", offset > 0);
		connection.put("pageInfo", pageInfo);

		Map<String, Object> result = new HashMap<>();
		result.put("children", connection);

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
	 * Resolve variable
	 * Example: $path -> variables["path"]
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

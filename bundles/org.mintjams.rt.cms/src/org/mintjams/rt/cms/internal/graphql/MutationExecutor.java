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
import javax.jcr.Session;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockManager;

/**
 * Class for executing GraphQL Mutation operations
 */
public class MutationExecutor {

	private final Session session;

	public MutationExecutor(Session session) {
		this.session = session;
	}

	/**
	 * Execute createFolder mutation
	 * Example: mutation { createFolder(input: { path: "/content", name: "newfolder" }) { path name } }
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

		// jcr:created and jcr:createdBy are automatically set by JCR, no manual setting required

		session.save();

		// Re-get node after save to get latest information
		String folderPath = folder.getPath();
		Node savedFolder = session.getNode(folderPath);

		Map<String, Object> result = new HashMap<>();
		result.put("createFolder", NodeMapper.toGraphQL(savedFolder));

		return result;
	}

	/**
	 * Execute createFile mutation
	 * Example: mutation { createFile(input: { path: "/content", name: "file.txt", mimeType: "text/plain", content: "..." }) { path name } }
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

		// jcr:created and jcr:createdBy are automatically set by JCR, no manual setting required

		// Create jcr:content node
		Node contentNode = fileNode.addNode("jcr:content", "nt:resource");

		// Base64 decode
		byte[] data = Base64.getDecoder().decode(contentBase64);

		// Current time (for jcr:lastModified)
		Calendar now = Calendar.getInstance();

		// jcr:data, jcr:mimeType, jcr:lastModified, jcr:lastModifiedBy belong to jcr:content
		contentNode.setProperty("jcr:data",
				session.getValueFactory().createBinary(new ByteArrayInputStream(data)));
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
	 * Example: mutation { deleteNode(path: "/content/page1") }
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
	 * Execute lockNode mutation
	 * Example: mutation { lockNode(input: { path: "/content/page1", isDeep: false, isSessionScoped: true }) { path isLocked lockOwner } }
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
	 * Execute unlockNode mutation
	 * Example: mutation { unlockNode(path: "/content/page1") }
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
	 * Parse GraphQL input object to Map
	 * Converts GraphQL syntax (no quotes on keys, no commas) to JSON format
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> parseSimpleJson(String graphqlInput) {
		// Convert GraphQL input syntax to JSON by adding quotes to keys and commas
		// GraphQL: { path: "/content" isDeep: false isSessionScoped: false }
		// JSON:    { "path": "/content", "isDeep": false, "isSessionScoped": false }

		String normalized = graphqlInput.trim();

		// Add quotes to unquoted keys: word: -> "word":
		normalized = normalized.replaceAll("(\\w+)\\s*:", "\"$1\":");

		// Add commas between key-value pairs
		// Look for patterns like: value word: and insert comma between them
		// String value followed by key
		normalized = normalized.replaceAll("\"\\s+(\"\\w+\":)", "\", $1");
		// Boolean value followed by key
		normalized = normalized.replaceAll("(false|true)\\s+(\"\\w+\":)", "$1, $2");
		// Number value followed by key
		normalized = normalized.replaceAll("(\\d+)\\s+(\"\\w+\":)", "$1, $2");

		// Parse using Gson
		com.google.gson.Gson gson = new com.google.gson.Gson();
		return gson.fromJson(normalized, Map.class);
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

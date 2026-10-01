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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import graphql.language.Definition;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;

/**
 * The GraphQL the content tools are built from, and the reshaping that turns
 * its responses into something compact for a model to read.
 *
 * <p>The tools go through the workspace GraphQL schema rather than the JCR API
 * wherever a field or mutation already exists: that keeps an MCP client's view
 * of a node identical to Webtop's, and every rule a mutation enforces (parent
 * creation, name collisions, property typing) is enforced once, in one place.
 */
final class McpGraphQL {

	enum OperationKind {
		QUERY, MUTATION, SUBSCRIPTION,
		/** Unparseable, or several operations with no {@code operationName} to pick one. */
		UNKNOWN
	}

	/** Scalar metadata of a node; null fields are dropped from the tool output. */
	static final String NODE_FIELDS = "path name nodeType id created createdBy modified modifiedBy"
			+ " mimeType size encoding hasChildren isLocked isVersionable isCheckedOut baseVersionName";

	/**
	 * What a listing shows per node: enough to choose what to open next. A
	 * folder of two hundred files is read far more often than any one of them
	 * is inspected, so the rest is left to {@code get_node}.
	 */
	static final String LIST_NODE_FIELDS = "path nodeType modified modifiedBy mimeType size hasChildren";

	static final String WEB_RENDER_FIELDS = "webRender { templated fromDescriptor source outputs documentRoot }";

	/** Selection of {@code Node.properties}, covering every member of the PropertyValue union. */
	static final String PROPERTIES_FIELDS;
	static {
		StringBuilder sb = new StringBuilder("properties { name propertyValue { __typename");
		for (String kind : new String[] { "String", "Long", "Double", "Decimal", "Boolean", "Date", "Name", "Path",
				"Uri" }) {
			sb.append(" ... on ").append(kind).append("PropertyValue { type value }");
			sb.append(" ... on ").append(kind).append("PropertyValueArray { type values }");
		}
		for (String kind : new String[] { "Reference", "Weakreference" }) {
			sb.append(" ... on ").append(kind).append("PropertyValue { type value path }");
			sb.append(" ... on ").append(kind).append("PropertyValueArray { type values paths }");
		}
		// Binary values are described, never inlined: a property can be megabytes.
		sb.append(" ... on BinaryPropertyValue { type mimeType size }");
		sb.append(" ... on BinaryPropertyValueArray { type mimeTypes sizes }");
		sb.append(" } }");
		PROPERTIES_FIELDS = sb.toString();
	}

	private McpGraphQL() {}

	/**
	 * Which kind of operation a request would run, honouring
	 * {@code operationName}. A read-scoped caller is only let through on
	 * {@link OperationKind#QUERY}, so anything that cannot be determined is
	 * reported as {@link OperationKind#UNKNOWN} and refused there.
	 */
	static OperationKind operationKind(String query, String operationName) {
		if (query == null) {
			return OperationKind.UNKNOWN;
		}
		try {
			OperationDefinition selected = null;
			int count = 0;
			for (Definition<?> definition : Parser.parse(query).getDefinitions()) {
				if (!(definition instanceof OperationDefinition)) {
					continue;
				}
				OperationDefinition operation = (OperationDefinition) definition;
				count++;
				if (operationName == null || operationName.isEmpty()) {
					selected = operation;
				} else if (operationName.equals(operation.getName())) {
					selected = operation;
					count = 1;
					break;
				}
			}
			if (selected == null || count != 1) {
				return OperationKind.UNKNOWN;
			}
			if (operationName != null && !operationName.isEmpty() && !operationName.equals(selected.getName())) {
				return OperationKind.UNKNOWN;
			}
			OperationDefinition.Operation operation = selected.getOperation();
			if (operation == OperationDefinition.Operation.MUTATION) {
				return OperationKind.MUTATION;
			}
			if (operation == OperationDefinition.Operation.SUBSCRIPTION) {
				return OperationKind.SUBSCRIPTION;
			}
			return OperationKind.QUERY;
		} catch (Throwable ex) {
			return OperationKind.UNKNOWN;
		}
	}

	/**
	 * Executes {@code query} as the caller and returns its {@code data}. Any
	 * GraphQL error fails the tool call: the built-in tools select only what
	 * they need, so a partial response is never something to pass on.
	 */
	@SuppressWarnings("unchecked")
	static Map<String, Object> data(McpCallContext context, String query, Map<String, Object> variables)
			throws McpToolException {
		Map<String, Object> result = context.executeGraphQL(query, null, variables);
		String errors = errorMessages(result);
		if (errors != null) {
			throw new McpToolException(errors);
		}
		Object data = result.get("data");
		if (!(data instanceof Map)) {
			throw new McpToolException("The server returned no data.");
		}
		return (Map<String, Object>) data;
	}

	/** The response's error messages joined, or {@code null} when it has none. */
	static String errorMessages(Map<String, Object> result) {
		Object errors = result.get("errors");
		if (!(errors instanceof List) || ((List<?>) errors).isEmpty()) {
			return null;
		}
		List<String> messages = new ArrayList<>();
		for (Object error : (List<?>) errors) {
			Object message = (error instanceof Map) ? ((Map<?, ?>) error).get("message") : error;
			messages.add(String.valueOf(message));
		}
		return String.join("; ", messages);
	}

	/**
	 * Reshapes a GraphQL {@code Node} for a model: null fields are dropped, and
	 * the property list — an array of {@code {name, propertyValue: {__typename,
	 * type, value}}} — becomes a map of {@code name -> {type, value}}.
	 */
	static Map<String, Object> node(Object node) {
		if (!(node instanceof Map)) {
			return null;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
			String key = String.valueOf(e.getKey());
			Object value = e.getValue();
			if (value == null) {
				continue;
			}
			if (key.equals("properties") && value instanceof List) {
				Map<String, Object> properties = new LinkedHashMap<>();
				for (Object item : (List<?>) value) {
					if (!(item instanceof Map)) {
						continue;
					}
					Map<?, ?> property = (Map<?, ?>) item;
					Map<String, Object> described = new LinkedHashMap<>();
					if (property.get("propertyValue") instanceof Map) {
						for (Map.Entry<?, ?> pv : ((Map<?, ?>) property.get("propertyValue")).entrySet()) {
							if (!"__typename".equals(pv.getKey()) && pv.getValue() != null) {
								described.put(String.valueOf(pv.getKey()), pv.getValue());
							}
						}
					}
					properties.put(String.valueOf(property.get("name")), described);
				}
				result.put(key, properties);
				continue;
			}
			result.put(key, value);
		}
		return result;
	}

	/**
	 * Reshapes a Relay {@code NodeConnection} into {@code {totalCount, nodes,
	 * hasNextPage, endCursor}}; pass {@code endCursor} back as {@code after} to
	 * read the next page.
	 */
	static Map<String, Object> connection(Object connection) {
		Map<String, Object> result = new LinkedHashMap<>();
		List<Object> nodes = new ArrayList<>();
		if (connection instanceof Map) {
			Map<?, ?> map = (Map<?, ?>) connection;
			if (map.get("totalCount") != null) {
				result.put("totalCount", map.get("totalCount"));
			}
			if (map.get("edges") instanceof List) {
				for (Object edge : (List<?>) map.get("edges")) {
					if (edge instanceof Map) {
						Object node = node(((Map<?, ?>) edge).get("node"));
						if (node != null) {
							nodes.add(node);
						}
					}
				}
			}
			result.put("nodes", nodes);
			if (map.get("pageInfo") instanceof Map) {
				Map<?, ?> pageInfo = (Map<?, ?>) map.get("pageInfo");
				boolean hasNextPage = Boolean.TRUE.equals(pageInfo.get("hasNextPage"));
				result.put("hasNextPage", hasNextPage);
				if (hasNextPage && pageInfo.get("endCursor") != null) {
					result.put("endCursor", pageInfo.get("endCursor"));
				}
			}
		} else {
			result.put("nodes", nodes);
		}
		return result;
	}

}

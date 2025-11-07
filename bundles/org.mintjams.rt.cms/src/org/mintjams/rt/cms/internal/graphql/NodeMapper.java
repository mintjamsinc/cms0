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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockManager;

import org.mintjams.rt.cms.internal.graphql.ast.SelectionSet;

/**
 * Mapper to convert JCR nodes to GraphQL format with field selection optimization
 */
public class NodeMapper {

	private static final SimpleDateFormat ISO8601_FORMAT = createISO8601Format();

	private static SimpleDateFormat createISO8601Format() {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format;
	}

	/**
	 * Convert JCR node to GraphQL format Map (backward compatibility)
	 */
	public static Map<String, Object> toGraphQL(Node node) throws RepositoryException {
		return toGraphQL(node, null);
	}

	/**
	 * Convert JCR node to GraphQL format Map with field selection optimization
	 * Only requested fields are included in the result
	 */
	public static Map<String, Object> toGraphQL(Node node, SelectionSet selectionSet) throws RepositoryException {
		if (node == null) {
			return null;
		}

		Map<String, Object> result = new HashMap<>();
		String nodeType = node.getPrimaryNodeType().getName();

		// If no selection set provided, include all fields (backward compatibility)
		boolean includeAll = (selectionSet == null);

		// Common properties (only include if selected or includeAll)
		if (includeAll || selectionSet.hasField("path")) {
			result.put("path", node.getPath());
		}
		if (includeAll || selectionSet.hasField("name")) {
			result.put("name", node.getName());
		}
		if (includeAll || selectionSet.hasField("nodeType")) {
			result.put("nodeType", nodeType);
		}

		// Creation date/time and creator
		if (includeAll || selectionSet.hasField("created")) {
			if (node.hasProperty("jcr:created")) {
				result.put("created", formatDate(node.getProperty("jcr:created").getDate()));
			}
		}
		if (includeAll || selectionSet.hasField("createdBy")) {
			if (node.hasProperty("jcr:createdBy")) {
				result.put("createdBy", node.getProperty("jcr:createdBy").getString());
			}
		}

		// UUID for referenceable nodes
		if (includeAll || selectionSet.hasField("uuid")) {
			if (node.isNodeType("mix:referenceable")) {
				result.put("uuid", node.getIdentifier());
			} else {
				result.put("uuid", null);
			}
		}

		// Lock information (expensive operation, only if requested)
		if (includeAll || selectionSet.hasField("isLocked") || selectionSet.hasField("lockOwner")
				|| selectionSet.hasField("isDeep") || selectionSet.hasField("isSessionScoped")
				|| selectionSet.hasField("isLockOwningSession")) {
			addLockInfo(node, result, selectionSet, includeAll);
		}

		// Properties list (expensive operation, only if requested)
		if (includeAll || selectionSet.hasField("properties")) {
			SelectionSet propertiesSelection = selectionSet != null ? selectionSet.getNestedSelectionSet("properties") : null;
			addProperties(node, result, propertiesSelection, includeAll);
		}

		// Processing based on node type
		if ("nt:file".equals(nodeType)) {
			mapFileNode(node, result, selectionSet, includeAll);
		} else if ("nt:folder".equals(nodeType)) {
			mapFolderNode(node, result, selectionSet, includeAll);
		} else {
			// Other node types: generic processing
			mapGenericNode(node, result, selectionSet, includeAll);
		}

		return result;
	}

	/**
	 * Mapping for nt:file node
	 */
	private static void mapFileNode(Node node, Map<String, Object> result, SelectionSet selectionSet, boolean includeAll) throws RepositoryException {
		if (node.hasNode("jcr:content")) {
			Node contentNode = node.getNode("jcr:content");

			// Last modified date/time and modifier (properties of jcr:content)
			if (includeAll || selectionSet.hasField("modified")) {
				if (contentNode.hasProperty("jcr:lastModified")) {
					result.put("modified", formatDate(contentNode.getProperty("jcr:lastModified").getDate()));
				}
			}
			if (includeAll || selectionSet.hasField("modifiedBy")) {
				if (contentNode.hasProperty("jcr:lastModifiedBy")) {
					result.put("modifiedBy", contentNode.getProperty("jcr:lastModifiedBy").getString());
				}
			}

			// MIME type
			if (includeAll || selectionSet.hasField("mimeType")) {
				if (contentNode.hasProperty("jcr:mimeType")) {
					result.put("mimeType", contentNode.getProperty("jcr:mimeType").getString());
				}
			}

			// File size
			if (includeAll || selectionSet.hasField("size")) {
				if (contentNode.hasProperty("jcr:data")) {
					result.put("size", contentNode.getProperty("jcr:data").getLength());
				}
			}

			// Encoding (optional)
			if (includeAll || selectionSet.hasField("encoding")) {
				if (contentNode.hasProperty("jcr:encoding")) {
					result.put("encoding", contentNode.getProperty("jcr:encoding").getString());
				}
			}
		}

		// Download URL (relative path)
		if (includeAll || selectionSet.hasField("downloadUrl")) {
			result.put("downloadUrl", node.getPath());
		}
	}

	/**
	 * Mapping for nt:folder node
	 */
	private static void mapFolderNode(Node node, Map<String, Object> result, SelectionSet selectionSet, boolean includeAll) throws RepositoryException {
		// Folder last modified date/time (node's own property, or fallback to creation date/time)
		if (includeAll || selectionSet.hasField("modified")) {
			if (node.hasProperty("jcr:lastModified")) {
				result.put("modified", formatDate(node.getProperty("jcr:lastModified").getDate()));
			} else if (node.hasProperty("jcr:created")) {
				result.put("modified", formatDate(node.getProperty("jcr:created").getDate()));
			}
		}

		// Modifier (or fallback to creator)
		if (includeAll || selectionSet.hasField("modifiedBy")) {
			if (node.hasProperty("jcr:lastModifiedBy")) {
				result.put("modifiedBy", node.getProperty("jcr:lastModifiedBy").getString());
			} else if (node.hasProperty("jcr:createdBy")) {
				result.put("modifiedBy", node.getProperty("jcr:createdBy").getString());
			}
		}

		// Check if has child nodes (potentially expensive for large folders)
		if (includeAll || selectionSet.hasField("hasChildren")) {
			result.put("hasChildren", node.hasNodes());
		}
	}

	/**
	 * Generic mapping for other node types
	 */
	private static void mapGenericNode(Node node, Map<String, Object> result, SelectionSet selectionSet, boolean includeAll) throws RepositoryException {
		// First check the node itself
		if (includeAll || selectionSet.hasField("modified")) {
			if (node.hasProperty("jcr:lastModified")) {
				result.put("modified", formatDate(node.getProperty("jcr:lastModified").getDate()));
			} else if (node.hasNode("jcr:content")) {
				// If jcr:content exists, check that too
				Node contentNode = node.getNode("jcr:content");
				if (contentNode.hasProperty("jcr:lastModified")) {
					result.put("modified", formatDate(contentNode.getProperty("jcr:lastModified").getDate()));
				}
			} else if (node.hasProperty("jcr:created")) {
				// If neither exists, fallback to creation date/time
				result.put("modified", formatDate(node.getProperty("jcr:created").getDate()));
			}
		}

		// Same for modifier
		if (includeAll || selectionSet.hasField("modifiedBy")) {
			if (node.hasProperty("jcr:lastModifiedBy")) {
				result.put("modifiedBy", node.getProperty("jcr:lastModifiedBy").getString());
			} else if (node.hasNode("jcr:content")) {
				Node contentNode = node.getNode("jcr:content");
				if (contentNode.hasProperty("jcr:lastModifiedBy")) {
					result.put("modifiedBy", contentNode.getProperty("jcr:lastModifiedBy").getString());
				}
			} else if (node.hasProperty("jcr:createdBy")) {
				result.put("modifiedBy", node.getProperty("jcr:createdBy").getString());
			}
		}
	}

	/**
	 * Add properties list to result with field selection optimization
	 */
	private static void addProperties(Node node, Map<String, Object> result, SelectionSet propertiesSelection, boolean includeAll) throws RepositoryException {
		try {
			List<Map<String, Object>> properties = new ArrayList<>();
			PropertyIterator propIterator = node.getProperties();

			while (propIterator.hasNext()) {
				Property prop = propIterator.nextProperty();

				// Skip system properties (starting with jcr:)
				String propName = prop.getName();
				if (propName.startsWith("jcr:")) {
					continue;
				}

				Map<String, Object> propMap = new HashMap<>();

				// Only include requested sub-fields
				if (includeAll || propertiesSelection == null || propertiesSelection.hasField("name")) {
					propMap.put("name", propName);
				}
				if (includeAll || propertiesSelection == null || propertiesSelection.hasField("type")) {
					propMap.put("type", PropertyType.nameFromValue(prop.getType()));
				}

				// Get property value
				if (includeAll || propertiesSelection == null || propertiesSelection.hasField("value")) {
					if (prop.isMultiple()) {
						// Multiple values
						List<String> values = new ArrayList<>();
						for (javax.jcr.Value value : prop.getValues()) {
							values.add(getPropertyValueAsString(value));
						}
						propMap.put("value", values);
					} else {
						// Single value
						propMap.put("value", getPropertyValueAsString(prop.getValue()));
					}
				}

				properties.add(propMap);
			}

			result.put("properties", properties);
		} catch (Exception e) {
			result.put("properties", new ArrayList<>());
		}
	}

	/**
	 * Convert property value to string
	 */
	private static String getPropertyValueAsString(javax.jcr.Value value) {
		try {
			int type = value.getType();
			switch (type) {
			case PropertyType.REFERENCE:
			case PropertyType.WEAKREFERENCE:
				// Return UUID for reference types
				return value.getString();
			case PropertyType.BINARY:
				return "[Binary]";
			case PropertyType.DATE:
				return formatDate(value.getDate());
			default:
				return value.getString();
			}
		} catch (Exception e) {
			return "[Error]";
		}
	}

	/**
	 * Add lock information to result with field selection optimization
	 */
	private static void addLockInfo(Node node, Map<String, Object> result, SelectionSet selectionSet, boolean includeAll) throws RepositoryException {
		try {
			LockManager lockManager = node.getSession().getWorkspace().getLockManager();

			// Check if node is locked (only if lock-related fields are requested)
			boolean isLocked = lockManager.isLocked(node.getPath());

			if (includeAll || selectionSet.hasField("isLocked")) {
				result.put("isLocked", isLocked);
			}

			if (isLocked) {
				Lock lock = lockManager.getLock(node.getPath());
				if (includeAll || selectionSet.hasField("lockOwner")) {
					result.put("lockOwner", lock.getLockOwner());
				}
				if (includeAll || selectionSet.hasField("isDeep")) {
					result.put("isDeep", lock.isDeep());
				}
				if (includeAll || selectionSet.hasField("isSessionScoped")) {
					result.put("isSessionScoped", lock.isSessionScoped());
				}
				if (includeAll || selectionSet.hasField("isLockOwningSession")) {
					result.put("isLockOwningSession", lock.isLockOwningSession());
				}
			} else {
				if (includeAll || selectionSet.hasField("lockOwner")) {
					result.put("lockOwner", null);
				}
				if (includeAll || selectionSet.hasField("isDeep")) {
					result.put("isDeep", false);
				}
				if (includeAll || selectionSet.hasField("isSessionScoped")) {
					result.put("isSessionScoped", false);
				}
				if (includeAll || selectionSet.hasField("isLockOwningSession")) {
					result.put("isLockOwningSession", false);
				}
			}
		} catch (Exception e) {
			// If lock info retrieval fails, set default values only for requested fields
			if (includeAll || selectionSet.hasField("isLocked")) {
				result.put("isLocked", false);
			}
			if (includeAll || selectionSet.hasField("lockOwner")) {
				result.put("lockOwner", null);
			}
			if (includeAll || selectionSet.hasField("isDeep")) {
				result.put("isDeep", false);
			}
			if (includeAll || selectionSet.hasField("isSessionScoped")) {
				result.put("isSessionScoped", false);
			}
			if (includeAll || selectionSet.hasField("isLockOwningSession")) {
				result.put("isLockOwningSession", false);
			}
		}
	}

	/**
	 * Convert Calendar to ISO8601 format string
	 */
	private static String formatDate(java.util.Calendar calendar) {
		if (calendar == null) {
			return null;
		}
		synchronized (ISO8601_FORMAT) {
			return ISO8601_FORMAT.format(calendar.getTime());
		}
	}
}

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

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockManager;
import javax.jcr.version.VersionManager;

import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.graphql.ast.SelectionSet;
import org.mintjams.rt.cms.internal.web.Webs;

/**
 * Mapper to convert JCR nodes to GraphQL format with field selection optimization
 */
public class NodeMapper {

	private static final DateTimeFormatter ISO8601_FORMAT;
	static {
		ISO8601_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);
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
		if (includeAll || selectionSet.hasField("isLocked") || selectionSet.hasField("lockInfo")) {
			addLockInfo(node, result, selectionSet, includeAll);
		}

		// Version information (only if requested)
		if (includeAll || selectionSet.hasField("isVersionable") || selectionSet.hasField("isCheckedOut") || selectionSet.hasField("baseVersionName")) {
			addVersionInfo(node, result, selectionSet, includeAll);
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
			String url;
			try {
				url = CmsConfiguration.DOWNLOAD_CGI_PATH + Webs.encodePath("/" + node.getSession().getWorkspace().getName() + node.getPath());
			} catch (IOException ex) {
				throw new RepositoryException("Failed to encode download URL", ex);
			}
			result.put("downloadUrl", url);
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
	 * Returns properties in Union type format (PropertyValue)
	 */
	private static void addProperties(Node node, Map<String, Object> result, SelectionSet propertiesSelection, boolean includeAll) throws RepositoryException {
		try {
			List<Map<String, Object>> properties = new ArrayList<>();
			if (node.hasNode("jcr:content")) {
				PropertyIterator propIterator = node.getNode("jcr:content").getProperties();

				while (propIterator.hasNext()) {
					Property prop = propIterator.nextProperty();

					// Skip system properties (starting with jcr:)
					String propName = prop.getName();
					if (propName.startsWith("jcr:")) {
						continue;
					}

					Map<String, Object> nodeProperty = new HashMap<>();

					// Always include name
					if (includeAll || propertiesSelection == null || propertiesSelection.hasField("name")) {
						nodeProperty.put("name", propName);
					}

					// Get propertyValue as Union type
					if (includeAll || propertiesSelection == null || propertiesSelection.hasField("propertyValue")) {
						String typeName = PropertyType.nameFromValue(prop.getType());
						Object value;

						if (prop.isMultiple()) {
							// Multiple values
							List<Object> values = new ArrayList<>();
							for (javax.jcr.Value jcrValue : prop.getValues()) {
								values.add(getPropertyValue(jcrValue));
							}
							value = values;
							nodeProperty.put("propertyValue", PropertyValue.toGraphQL(typeName, value, true));
						} else {
							// Single value
							value = getPropertyValue(prop.getValue());
							nodeProperty.put("propertyValue", PropertyValue.toGraphQL(typeName, value, false));
						}
					}

					properties.add(nodeProperty);
				}
			}
			result.put("properties", properties);
		} catch (Throwable ex) {
			result.put("properties", new ArrayList<>());
		}
	}

	/**
	 * Get property value as typed Object for Union type representation
	 */
	private static Object getPropertyValue(javax.jcr.Value value) {
		try {
			int type = value.getType();
			switch (type) {
			case PropertyType.STRING:
			case PropertyType.NAME:
			case PropertyType.PATH:
			case PropertyType.URI:
			case PropertyType.REFERENCE:
			case PropertyType.WEAKREFERENCE:
				return value.getString();
			case PropertyType.BOOLEAN:
				return value.getBoolean();
			case PropertyType.LONG:
				return value.getLong();
			case PropertyType.DOUBLE:
			case PropertyType.DECIMAL:
				return value.getDouble();
			case PropertyType.DATE:
				return formatDate(value.getDate());
			case PropertyType.BINARY:
				// For Binary, return Base64 encoded string
				return java.util.Base64.getEncoder().encodeToString(value.getBinary().getStream().readAllBytes());
			default:
				return value.getString();
			}
		} catch (Throwable ex) {
			return "[Error]";
		}
	}

	/**
	 * Convert property value to string (legacy method, kept for compatibility)
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
		} catch (Throwable ex) {
			return "[Error]";
		}
	}

	/**
	 * Add lock information to result with field selection optimization
	 * Returns isLocked as boolean and lockInfo as object (only when locked)
	 */
	private static void addLockInfo(Node node, Map<String, Object> result, SelectionSet selectionSet, boolean includeAll) throws RepositoryException {
		try {
			LockManager lockManager = node.getSession().getWorkspace().getLockManager();

			// Check if node is locked
			boolean isLocked = lockManager.isLocked(node.getPath());

			if (includeAll || selectionSet.hasField("isLocked")) {
				result.put("isLocked", isLocked);
			}

			// Build lockInfo object only when locked and requested
			if (includeAll || selectionSet.hasField("lockInfo")) {
				if (isLocked) {
					Lock lock = lockManager.getLock(node.getPath());
					Map<String, Object> lockInfo = new HashMap<>();
					lockInfo.put("lockOwner", lock.getLockOwner());
					lockInfo.put("isDeep", lock.isDeep());
					lockInfo.put("isSessionScoped", lock.isSessionScoped());
					lockInfo.put("isLockOwningSession", lock.isLockOwningSession());
					result.put("lockInfo", lockInfo);
				} else {
					result.put("lockInfo", null);
				}
			}
		} catch (Throwable ex) {
			// If lock info retrieval fails, set default values only for requested fields
			if (includeAll || selectionSet.hasField("isLocked")) {
				result.put("isLocked", false);
			}
			if (includeAll || selectionSet.hasField("lockInfo")) {
				result.put("lockInfo", null);
			}
		}
	}

	/**
	 * Add version information to result with field selection optimization
	 * Returns isVersionable, isCheckedOut booleans, and baseVersionName string
	 */
	private static void addVersionInfo(Node node, Map<String, Object> result, SelectionSet selectionSet, boolean includeAll) throws RepositoryException {
		try {
			// Check if node is versionable
			boolean isVersionable = node.isNodeType("mix:versionable");

			if (includeAll || selectionSet.hasField("isVersionable")) {
				result.put("isVersionable", isVersionable);
			}

			VersionManager versionManager = null;
			if (isVersionable) {
				versionManager = node.getSession().getWorkspace().getVersionManager();
			}

			// Check if node is checked out (only meaningful for versionable nodes)
			if (includeAll || selectionSet.hasField("isCheckedOut")) {
				if (isVersionable && versionManager != null) {
					result.put("isCheckedOut", versionManager.isCheckedOut(node.getPath()));
				} else {
					result.put("isCheckedOut", false);
				}
			}

			// Get base version name (only meaningful for versionable nodes)
			if (includeAll || selectionSet.hasField("baseVersionName")) {
				if (isVersionable && versionManager != null) {
					result.put("baseVersionName", versionManager.getBaseVersion(node.getPath()).getName());
				} else {
					result.put("baseVersionName", null);
				}
			}
		} catch (Throwable ex) {
			// If version info retrieval fails, set default values only for requested fields
			if (includeAll || selectionSet.hasField("isVersionable")) {
				result.put("isVersionable", false);
			}
			if (includeAll || selectionSet.hasField("isCheckedOut")) {
				result.put("isCheckedOut", false);
			}
			if (includeAll || selectionSet.hasField("baseVersionName")) {
				result.put("baseVersionName", null);
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
		return ISO8601_FORMAT.format(calendar.toInstant());
	}
}

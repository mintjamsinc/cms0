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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.PropertyType;

/**
 * Represents a JCR property value in GraphQL Union type format
 * Provides type-safe property values for both reading and writing
 */
public class PropertyValue {

	private final String type;
	private final Object value;
	private final boolean isMultiple;

	private PropertyValue(String type, Object value, boolean isMultiple) {
		this.type = type;
		this.value = value;
		this.isMultiple = isMultiple;
	}

	/**
	 * Create a PropertyValue from a PropertyValueInput map
	 * Validates that exactly one value field is set
	 */
	public static PropertyValue fromInput(Map<String, Object> input) {
		String setField = null;
		Object setValue = null;
		boolean isMultiple = false;

		// Check single value fields
		if (input.containsKey("stringValue")) {
			setField = "stringValue";
			setValue = input.get("stringValue");
		}
		if (input.containsKey("longValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and longValue");
			}
			setField = "longValue";
			setValue = input.get("longValue");
		}
		if (input.containsKey("doubleValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and doubleValue");
			}
			setField = "doubleValue";
			setValue = input.get("doubleValue");
		}
		if (input.containsKey("booleanValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and booleanValue");
			}
			setField = "booleanValue";
			setValue = input.get("booleanValue");
		}
		if (input.containsKey("dateValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and dateValue");
			}
			setField = "dateValue";
			setValue = input.get("dateValue");
		}
		if (input.containsKey("binaryValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and binaryValue");
			}
			setField = "binaryValue";
			setValue = input.get("binaryValue");
		}
		if (input.containsKey("decimalValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and decimalValue");
			}
			setField = "decimalValue";
			setValue = input.get("decimalValue");
		}
		if (input.containsKey("nameValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and nameValue");
			}
			setField = "nameValue";
			setValue = input.get("nameValue");
		}
		if (input.containsKey("pathValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and pathValue");
			}
			setField = "pathValue";
			setValue = input.get("pathValue");
		}
		if (input.containsKey("referenceValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and referenceValue");
			}
			setField = "referenceValue";
			setValue = input.get("referenceValue");
		}
		if (input.containsKey("weakReferenceValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and weakReferenceValue");
			}
			setField = "weakReferenceValue";
			setValue = input.get("weakReferenceValue");
		}
		if (input.containsKey("uriValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and uriValue");
			}
			setField = "uriValue";
			setValue = input.get("uriValue");
		}

		// Check array value fields
		if (input.containsKey("stringArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and stringArrayValue");
			}
			setField = "stringArrayValue";
			setValue = input.get("stringArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("longArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and longArrayValue");
			}
			setField = "longArrayValue";
			setValue = input.get("longArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("doubleArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and doubleArrayValue");
			}
			setField = "doubleArrayValue";
			setValue = input.get("doubleArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("booleanArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and booleanArrayValue");
			}
			setField = "booleanArrayValue";
			setValue = input.get("booleanArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("dateArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and dateArrayValue");
			}
			setField = "dateArrayValue";
			setValue = input.get("dateArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("decimalArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and decimalArrayValue");
			}
			setField = "decimalArrayValue";
			setValue = input.get("decimalArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("nameArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and nameArrayValue");
			}
			setField = "nameArrayValue";
			setValue = input.get("nameArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("pathArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and pathArrayValue");
			}
			setField = "pathArrayValue";
			setValue = input.get("pathArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("referenceArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and referenceArrayValue");
			}
			setField = "referenceArrayValue";
			setValue = input.get("referenceArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("weakReferenceArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and weakReferenceArrayValue");
			}
			setField = "weakReferenceArrayValue";
			setValue = input.get("weakReferenceArrayValue");
			isMultiple = true;
		}
		if (input.containsKey("uriArrayValue")) {
			if (setField != null) {
				throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field. Found: " + setField + " and uriArrayValue");
			}
			setField = "uriArrayValue";
			setValue = input.get("uriArrayValue");
			isMultiple = true;
		}

		if (setField == null) {
			throw new IllegalArgumentException("PropertyValueInput must contain exactly one value field");
		}

		// Map field name to JCR property type
		String jcrType = getJcrTypeFromFieldName(setField);
		return new PropertyValue(jcrType, setValue, isMultiple);
	}

	/**
	 * Create a PropertyValue from JCR property data for GraphQL output
	 */
	public static Map<String, Object> toGraphQL(String typeName, Object value, boolean isMultiple) {
		Map<String, Object> result = new HashMap<>();
		result.put("__typename", getGraphQLTypeName(typeName, isMultiple));
		result.put("type", typeName);

		if (isMultiple) {
			result.put("values", value);
		} else {
			result.put("value", value);
		}

		return result;
	}

	private static String getJcrTypeFromFieldName(String fieldName) {
		if (fieldName.equals("stringValue") || fieldName.equals("stringArrayValue")) {
			return "STRING";
		} else if (fieldName.equals("longValue") || fieldName.equals("longArrayValue")) {
			return "LONG";
		} else if (fieldName.equals("doubleValue") || fieldName.equals("doubleArrayValue")) {
			return "DOUBLE";
		} else if (fieldName.equals("booleanValue") || fieldName.equals("booleanArrayValue")) {
			return "BOOLEAN";
		} else if (fieldName.equals("dateValue") || fieldName.equals("dateArrayValue")) {
			return "DATE";
		} else if (fieldName.equals("binaryValue")) {
			return "BINARY";
		} else if (fieldName.equals("decimalValue") || fieldName.equals("decimalArrayValue")) {
			return "DECIMAL";
		} else if (fieldName.equals("nameValue") || fieldName.equals("nameArrayValue")) {
			return "NAME";
		} else if (fieldName.equals("pathValue") || fieldName.equals("pathArrayValue")) {
			return "PATH";
		} else if (fieldName.equals("referenceValue") || fieldName.equals("referenceArrayValue")) {
			return "REFERENCE";
		} else if (fieldName.equals("weakReferenceValue") || fieldName.equals("weakReferenceArrayValue")) {
			return "WEAKREFERENCE";
		} else if (fieldName.equals("uriValue") || fieldName.equals("uriArrayValue")) {
			return "URI";
		}
		throw new IllegalArgumentException("Unknown field name: " + fieldName);
	}

	private static String getGraphQLTypeName(String jcrTypeName, boolean isMultiple) {
		String baseName = jcrTypeName.substring(0, 1).toUpperCase() + jcrTypeName.substring(1).toLowerCase();
		return baseName + "PropertyValue" + (isMultiple ? "Array" : "");
	}

	public String getType() {
		return type;
	}

	public Object getValue() {
		return value;
	}

	public boolean isMultiple() {
		return isMultiple;
	}

	/**
	 * Get JCR PropertyType constant
	 */
	public int getPropertyType() {
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
}

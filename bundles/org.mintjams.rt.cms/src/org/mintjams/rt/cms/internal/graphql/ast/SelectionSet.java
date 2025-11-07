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

package org.mintjams.rt.cms.internal.graphql.ast;

import java.util.*;

/**
 * Represents a selection set in GraphQL query
 * Example: { name path properties { name value } }
 */
public class SelectionSet {

	private final List<Field> fields;
	private final Map<String, Field> fieldMap;

	public SelectionSet(List<Field> fields) {
		this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
		this.fieldMap = new HashMap<>();
		for (Field field : fields) {
			fieldMap.put(field.getResponseKey(), field);
		}
	}

	public List<Field> getFields() {
		return fields;
	}

	public Field getField(String name) {
		return fieldMap.get(name);
	}

	public boolean hasField(String name) {
		return fieldMap.containsKey(name);
	}

	public boolean isEmpty() {
		return fields.isEmpty();
	}

	public int size() {
		return fields.size();
	}

	/**
	 * Check if a specific field is selected
	 * This supports nested field paths like "properties.name"
	 */
	public boolean isFieldSelected(String fieldPath) {
		if (fieldPath == null || fieldPath.isEmpty()) {
			return false;
		}

		String[] parts = fieldPath.split("\\.", 2);
		String fieldName = parts[0];

		if (!hasField(fieldName)) {
			return false;
		}

		// If no nested path, return true
		if (parts.length == 1) {
			return true;
		}

		// Check nested selection
		Field field = getField(fieldName);
		if (field.hasSelectionSet()) {
			return field.getSelectionSet().isFieldSelected(parts[1]);
		}

		return false;
	}

	/**
	 * Get nested SelectionSet for a field
	 */
	public SelectionSet getNestedSelectionSet(String fieldName) {
		Field field = getField(fieldName);
		return field != null ? field.getSelectionSet() : null;
	}

	@Override
	public String toString() {
		return "{ " + String.join(", ", fields.stream().map(Field::toString).toArray(String[]::new)) + " }";
	}

	/**
	 * Create an empty SelectionSet
	 */
	public static SelectionSet empty() {
		return new SelectionSet(Collections.emptyList());
	}

	/**
	 * Create a SelectionSet with single field
	 */
	public static SelectionSet single(String fieldName) {
		return new SelectionSet(Collections.singletonList(new Field(fieldName)));
	}
}

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

/**
 * Represents a GraphQL operation (query or mutation)
 */
public class Operation {

	public enum Type {
		QUERY,
		MUTATION
	}

	private final Type type;
	private final String name;
	private final SelectionSet selectionSet;

	public Operation(Type type, String name, SelectionSet selectionSet) {
		this.type = type;
		this.name = name;
		this.selectionSet = selectionSet;
	}

	public Type getType() {
		return type;
	}

	public String getName() {
		return name;
	}

	public SelectionSet getSelectionSet() {
		return selectionSet;
	}

	public boolean isQuery() {
		return type == Type.QUERY;
	}

	public boolean isMutation() {
		return type == Type.MUTATION;
	}

	/**
	 * Get the root field (the main operation field)
	 * For example: "node" in { node(path: "/content") { name } }
	 */
	public Field getRootField() {
		if (selectionSet == null || selectionSet.isEmpty()) {
			return null;
		}
		// Return the first field as root field
		return selectionSet.getFields().get(0);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(type.name().toLowerCase());
		if (name != null) {
			sb.append(" ").append(name);
		}
		sb.append(" ").append(selectionSet);
		return sb.toString();
	}
}

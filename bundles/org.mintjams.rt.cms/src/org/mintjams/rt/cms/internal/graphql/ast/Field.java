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

import java.util.Collections;
import java.util.Map;

/**
 * Represents a field in GraphQL query
 */
public class Field {

	private final String name;
	private final String alias;
	private final Map<String, Object> arguments;
	private final SelectionSet selectionSet;

	public Field(String name) {
		this(name, null, Collections.emptyMap(), null);
	}

	public Field(String name, String alias, Map<String, Object> arguments, SelectionSet selectionSet) {
		this.name = name;
		this.alias = alias;
		this.arguments = arguments != null ? arguments : Collections.emptyMap();
		this.selectionSet = selectionSet;
	}

	public String getName() {
		return name;
	}

	public String getAlias() {
		return alias;
	}

	public String getResponseKey() {
		return alias != null ? alias : name;
	}

	public Map<String, Object> getArguments() {
		return arguments;
	}

	public Object getArgument(String name) {
		return arguments.get(name);
	}

	public boolean hasArgument(String name) {
		return arguments.containsKey(name);
	}

	public SelectionSet getSelectionSet() {
		return selectionSet;
	}

	public boolean hasSelectionSet() {
		return selectionSet != null && !selectionSet.isEmpty();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		if (alias != null) {
			sb.append(alias).append(": ");
		}
		sb.append(name);
		if (!arguments.isEmpty()) {
			sb.append("(").append(arguments).append(")");
		}
		if (hasSelectionSet()) {
			sb.append(" ").append(selectionSet);
		}
		return sb.toString();
	}
}

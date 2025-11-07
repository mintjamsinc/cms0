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
 * Advanced GraphQL parser that creates AST for field selection optimization
 */
public class GraphQLParser {

	private String query;
	private int position;
	private Map<String, Object> variables;

	public GraphQLParser() {
		this.variables = Collections.emptyMap();
	}

	/**
	 * Parse GraphQL query string into Operation
	 */
	public Operation parse(String query, Map<String, Object> variables) {
		this.query = removeComments(query.trim());
		this.position = 0;
		this.variables = variables != null ? variables : Collections.emptyMap();

		skipWhitespace();

		// Determine operation type
		Operation.Type operationType = Operation.Type.QUERY;
		String operationName = null;

		if (query.startsWith("mutation")) {
			operationType = Operation.Type.MUTATION;
			position += "mutation".length();
			skipWhitespace();
			operationName = tryParseOperationName();
		} else if (query.startsWith("query")) {
			operationType = Operation.Type.QUERY;
			position += "query".length();
			skipWhitespace();
			operationName = tryParseOperationName();
		} else if (query.startsWith("{")) {
			// Anonymous query
			operationType = Operation.Type.QUERY;
		}

		SelectionSet selectionSet = parseSelectionSet();

		return new Operation(operationType, operationName, selectionSet);
	}

	/**
	 * Remove comments from query
	 */
	private String removeComments(String query) {
		// Remove single-line comments (# ...)
		return query.replaceAll("#[^\n]*", "");
	}

	/**
	 * Try to parse operation name (optional)
	 */
	private String tryParseOperationName() {
		int start = position;
		while (position < query.length() && (Character.isLetterOrDigit(query.charAt(position)) || query.charAt(position) == '_')) {
			position++;
		}
		if (position > start) {
			String name = query.substring(start, position);
			skipWhitespace();
			return name;
		}
		return null;
	}

	/**
	 * Parse selection set: { field1 field2 ... }
	 */
	private SelectionSet parseSelectionSet() {
		skipWhitespace();

		if (position >= query.length() || query.charAt(position) != '{') {
			return SelectionSet.empty();
		}

		position++; // skip '{'
		skipWhitespace();

		List<Field> fields = new ArrayList<>();

		while (position < query.length() && query.charAt(position) != '}') {
			Field field = parseField();
			if (field != null) {
				fields.add(field);
			}
			skipWhitespace();
		}

		if (position < query.length() && query.charAt(position) == '}') {
			position++; // skip '}'
		}

		return new SelectionSet(fields);
	}

	/**
	 * Parse a field: name or alias: name or name(args) or name { nested }
	 */
	private Field parseField() {
		skipWhitespace();

		// Parse field name or alias
		String firstIdentifier = parseIdentifier();
		if (firstIdentifier == null) {
			return null;
		}

		skipWhitespace();

		String alias = null;
		String fieldName = firstIdentifier;

		// Check for alias (name: actualField)
		if (position < query.length() && query.charAt(position) == ':') {
			position++; // skip ':'
			skipWhitespace();
			alias = firstIdentifier;
			fieldName = parseIdentifier();
			if (fieldName == null) {
				throw new IllegalArgumentException("Expected field name after alias at position " + position);
			}
			skipWhitespace();
		}

		// Parse arguments if present
		Map<String, Object> arguments = Collections.emptyMap();
		if (position < query.length() && query.charAt(position) == '(') {
			arguments = parseArguments();
			skipWhitespace();
		}

		// Parse nested selection set if present
		SelectionSet selectionSet = null;
		if (position < query.length() && query.charAt(position) == '{') {
			selectionSet = parseSelectionSet();
			skipWhitespace();
		}

		return new Field(fieldName, alias, arguments, selectionSet);
	}

	/**
	 * Parse arguments: (arg1: value1, arg2: value2)
	 */
	private Map<String, Object> parseArguments() {
		Map<String, Object> arguments = new LinkedHashMap<>();

		position++; // skip '('
		skipWhitespace();

		while (position < query.length() && query.charAt(position) != ')') {
			String argName = parseIdentifier();
			if (argName == null) {
				break;
			}

			skipWhitespace();
			if (position >= query.length() || query.charAt(position) != ':') {
				throw new IllegalArgumentException("Expected ':' after argument name at position " + position);
			}

			position++; // skip ':'
			skipWhitespace();

			Object value = parseValue();
			arguments.put(argName, value);

			skipWhitespace();
			if (position < query.length() && query.charAt(position) == ',') {
				position++; // skip ','
				skipWhitespace();
			}
		}

		if (position < query.length() && query.charAt(position) == ')') {
			position++; // skip ')'
		}

		return arguments;
	}

	/**
	 * Parse value: string, number, boolean, variable, array, object
	 */
	private Object parseValue() {
		skipWhitespace();

		if (position >= query.length()) {
			return null;
		}

		char ch = query.charAt(position);

		// String
		if (ch == '"') {
			return parseString();
		}

		// Variable
		if (ch == '$') {
			return parseVariable();
		}

		// Array
		if (ch == '[') {
			return parseArray();
		}

		// Object
		if (ch == '{') {
			return parseObject();
		}

		// Number or boolean or null
		return parseLiteral();
	}

	/**
	 * Parse string: "..."
	 */
	private String parseString() {
		position++; // skip opening '"'
		StringBuilder sb = new StringBuilder();

		while (position < query.length()) {
			char ch = query.charAt(position);

			if (ch == '"') {
				position++; // skip closing '"'
				return sb.toString();
			}

			if (ch == '\\' && position + 1 < query.length()) {
				// Handle escape sequences
				position++;
				char next = query.charAt(position);
				switch (next) {
					case 'n': sb.append('\n'); break;
					case 't': sb.append('\t'); break;
					case 'r': sb.append('\r'); break;
					case '"': sb.append('"'); break;
					case '\\': sb.append('\\'); break;
					default: sb.append(next);
				}
				position++;
			} else {
				sb.append(ch);
				position++;
			}
		}

		throw new IllegalArgumentException("Unclosed string at position " + position);
	}

	/**
	 * Parse variable: $varName
	 */
	private Object parseVariable() {
		position++; // skip '$'
		String varName = parseIdentifier();
		if (varName == null) {
			throw new IllegalArgumentException("Expected variable name after $ at position " + position);
		}
		return variables.getOrDefault(varName, null);
	}

	/**
	 * Parse array: [value1, value2, ...]
	 */
	private List<Object> parseArray() {
		List<Object> list = new ArrayList<>();
		position++; // skip '['
		skipWhitespace();

		while (position < query.length() && query.charAt(position) != ']') {
			list.add(parseValue());
			skipWhitespace();
			if (position < query.length() && query.charAt(position) == ',') {
				position++;
				skipWhitespace();
			}
		}

		if (position < query.length() && query.charAt(position) == ']') {
			position++; // skip ']'
		}

		return list;
	}

	/**
	 * Parse object: { key1: value1, key2: value2 }
	 */
	private Map<String, Object> parseObject() {
		Map<String, Object> map = new LinkedHashMap<>();
		position++; // skip '{'
		skipWhitespace();

		while (position < query.length() && query.charAt(position) != '}') {
			String key = parseIdentifier();
			if (key == null) {
				// Try to parse as string key
				if (query.charAt(position) == '"') {
					key = parseString();
				} else {
					break;
				}
			}

			skipWhitespace();
			if (position >= query.length() || query.charAt(position) != ':') {
				throw new IllegalArgumentException("Expected ':' after object key at position " + position);
			}

			position++; // skip ':'
			skipWhitespace();

			Object value = parseValue();
			map.put(key, value);

			skipWhitespace();
			if (position < query.length() && query.charAt(position) == ',') {
				position++;
				skipWhitespace();
			}
		}

		if (position < query.length() && query.charAt(position) == '}') {
			position++; // skip '}'
		}

		return map;
	}

	/**
	 * Parse literal: number, boolean, null
	 */
	private Object parseLiteral() {
		int start = position;
		while (position < query.length() && isLiteralChar(query.charAt(position))) {
			position++;
		}

		if (position == start) {
			throw new IllegalArgumentException("Expected value at position " + position);
		}

		String literal = query.substring(start, position);

		// Boolean
		if ("true".equals(literal)) {
			return true;
		}
		if ("false".equals(literal)) {
			return false;
		}

		// Null
		if ("null".equals(literal)) {
			return null;
		}

		// Number
		try {
			if (literal.contains(".")) {
				return Double.parseDouble(literal);
			} else {
				return Long.parseLong(literal);
			}
		} catch (NumberFormatException e) {
			// If not a valid number, return as string
			return literal;
		}
	}

	/**
	 * Parse identifier: [a-zA-Z_][a-zA-Z0-9_]*
	 */
	private String parseIdentifier() {
		int start = position;
		if (position < query.length() && (Character.isLetter(query.charAt(position)) || query.charAt(position) == '_')) {
			position++;
			while (position < query.length() && (Character.isLetterOrDigit(query.charAt(position)) || query.charAt(position) == '_')) {
				position++;
			}
			return query.substring(start, position);
		}
		return null;
	}

	/**
	 * Check if character is part of literal
	 */
	private boolean isLiteralChar(char ch) {
		return Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '+';
	}

	/**
	 * Skip whitespace and commas
	 */
	private void skipWhitespace() {
		while (position < query.length()) {
			char ch = query.charAt(position);
			if (Character.isWhitespace(ch) || ch == ',') {
				position++;
			} else {
				break;
			}
		}
	}
}

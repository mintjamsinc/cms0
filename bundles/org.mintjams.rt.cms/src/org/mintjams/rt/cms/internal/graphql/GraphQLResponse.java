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

/**
 * Class representing a GraphQL response
 */
public class GraphQLResponse {

	private Map<String, Object> data;
	private List<GraphQLError> errors;

	public GraphQLResponse() {
		this.data = new HashMap<>();
		this.errors = new ArrayList<>();
	}

	public void setData(Map<String, Object> data) {
		this.data = data;
	}

	public Map<String, Object> getData() {
		return data;
	}

	public void addError(GraphQLError error) {
		this.errors.add(error);
	}

	public void addError(String message) {
		this.errors.add(new GraphQLError(message));
	}

	public void addError(String message, Throwable cause) {
		this.errors.add(new GraphQLError(message, cause));
	}

	public List<GraphQLError> getErrors() {
		return errors;
	}

	public boolean hasErrors() {
		return !errors.isEmpty();
	}

	/**
	 * Convert to JSON format
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> result = new HashMap<>();
		if (data != null && !data.isEmpty()) {
			result.put("data", data);
		}
		if (!errors.isEmpty()) {
			List<Map<String, Object>> errorList = new ArrayList<>();
			for (GraphQLError error : errors) {
				errorList.add(error.toMap());
			}
			result.put("errors", errorList);
		}
		return result;
	}

	/**
	 * GraphQL error representation
	 */
	public static class GraphQLError {
		private final String message;
		private final String path;
		private final Map<String, Object> extensions;

		public GraphQLError(String message) {
			this.message = message;
			this.path = null;
			this.extensions = new HashMap<>();
		}

		public GraphQLError(String message, Throwable cause) {
			this.message = message;
			this.path = null;
			this.extensions = new HashMap<>();
			if (cause != null) {
				this.extensions.put("exception", cause.getClass().getName());
				if (cause.getMessage() != null) {
					this.extensions.put("exceptionMessage", cause.getMessage());
				}
			}
		}

		public GraphQLError(String message, String path) {
			this.message = message;
			this.path = path;
			this.extensions = new HashMap<>();
		}

		public String getMessage() {
			return message;
		}

		public String getPath() {
			return path;
		}

		public Map<String, Object> getExtensions() {
			return extensions;
		}

		public Map<String, Object> toMap() {
			Map<String, Object> result = new HashMap<>();
			result.put("message", message);
			if (path != null) {
				result.put("path", path);
			}
			if (!extensions.isEmpty()) {
				result.put("extensions", extensions);
			}
			return result;
		}
	}
}

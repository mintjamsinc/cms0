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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * One MCP tool: what the client is told about it ({@code tools/list}) and what
 * runs when it is called ({@code tools/call}).
 *
 * <p>A tool declares whether it {@linkplain #isWrite() changes the repository}.
 * That single flag drives three things that must never disagree: whether a
 * read-scoped caller is shown the tool, whether it may call it, and the
 * {@code readOnlyHint} the client uses to decide if a call needs confirming.
 */
public final class McpTool {

	/** The tool body. Throw {@link McpToolException} for failures the caller can act on. */
	public interface Handler {
		McpToolResult call(JsonObject arguments, McpCallContext context) throws Exception;
	}

	private final String fName;
	private final String fTitle;
	private final String fDescription;
	private final boolean fWrite;
	private final boolean fDestructive;
	private final boolean fIdempotent;
	private final boolean fWritesWhenAllowed;
	private final JsonObject fProperties;
	private final JsonArray fRequired;
	private final Handler fHandler;

	private McpTool(Builder builder) {
		fName = builder.fName;
		fTitle = builder.fTitle;
		fDescription = builder.fDescription;
		fWrite = builder.fWrite;
		fDestructive = builder.fDestructive;
		fIdempotent = builder.fIdempotent;
		fWritesWhenAllowed = builder.fWritesWhenAllowed;
		fProperties = builder.fProperties;
		fRequired = builder.fRequired;
		fHandler = builder.fHandler;
	}

	public static Builder named(String name) {
		return new Builder(name);
	}

	public String getName() {
		return fName;
	}

	public String getTitle() {
		return fTitle;
	}

	/** Whether the tool can change the repository, and so needs the write scope. */
	public boolean isWrite() {
		return fWrite;
	}

	public McpToolResult call(JsonObject arguments, McpCallContext context) throws Exception {
		return fHandler.call(arguments, context);
	}

	/**
	 * The tool's entry in a {@code tools/list} result, for a caller that
	 * {@code canWrite} or not. The hints describe what the tool can do <em>on
	 * this connection</em>: a client that skips confirmation for read-only
	 * tools must not be told a tool is read-only when this caller can use it
	 * to write.
	 */
	public JsonObject describe(boolean canWrite) {
		boolean writes = fWrite || (fWritesWhenAllowed && canWrite);
		boolean destructive = fDestructive || (fWritesWhenAllowed && canWrite);
		JsonObject tool = new JsonObject();
		tool.addProperty("name", fName);
		tool.addProperty("title", fTitle);
		tool.addProperty("description", fDescription);

		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", fProperties);
		if (fRequired.size() > 0) {
			schema.add("required", fRequired);
		}
		schema.addProperty("additionalProperties", false);
		tool.add("inputSchema", schema);

		JsonObject annotations = new JsonObject();
		annotations.addProperty("title", fTitle);
		annotations.addProperty("readOnlyHint", !writes);
		if (writes) {
			annotations.addProperty("destructiveHint", destructive);
			annotations.addProperty("idempotentHint", fIdempotent);
		}
		// Every tool acts on this repository only; none reaches the open web.
		annotations.addProperty("openWorldHint", false);
		tool.add("annotations", annotations);
		return tool;
	}

	public static final class Builder {
		private final String fName;
		private String fTitle;
		private String fDescription;
		private boolean fWrite;
		private boolean fDestructive;
		private boolean fIdempotent;
		private boolean fWritesWhenAllowed;
		private final JsonObject fProperties = new JsonObject();
		private final JsonArray fRequired = new JsonArray();
		private Handler fHandler;

		private Builder(String name) {
			fName = name;
			fTitle = name;
		}

		public Builder title(String title) {
			fTitle = title;
			return this;
		}

		public Builder description(String... lines) {
			fDescription = String.join(" ", lines);
			return this;
		}

		/** Marks the tool as changing the repository (hidden from, and refused to, read-scoped callers). */
		public Builder write() {
			fWrite = true;
			return this;
		}

		/** The change may destroy or replace existing content. Implies {@link #write()}. */
		public Builder destructive() {
			fWrite = true;
			fDestructive = true;
			return this;
		}

		/**
		 * The tool is available to every caller but can change anything when
		 * the caller has the write scope; the handler enforces the scope
		 * itself. Unlike {@link #write()}, read-scoped callers still see it.
		 */
		public Builder writesWhenAllowed() {
			fWritesWhenAllowed = true;
			return this;
		}

		/** Repeating the call with the same arguments has no further effect. */
		public Builder idempotent() {
			fIdempotent = true;
			return this;
		}

		public Builder required(String name, JsonObject schema) {
			fProperties.add(name, schema);
			fRequired.add(name);
			return this;
		}

		public Builder optional(String name, JsonObject schema) {
			fProperties.add(name, schema);
			return this;
		}

		public Builder handler(Handler handler) {
			fHandler = handler;
			return this;
		}

		public McpTool build() {
			if (fDescription == null || fHandler == null) {
				throw new IllegalStateException("Tool " + fName + " needs a description and a handler");
			}
			return new McpTool(this);
		}
	}

	/** JSON Schema fragments for tool parameters. */
	public static final class Schema {
		private Schema() {}

		public static JsonObject string(String description) {
			return of("string", description);
		}

		public static JsonObject bool(String description, boolean defaultValue) {
			JsonObject schema = of("boolean", description);
			schema.addProperty("default", defaultValue);
			return schema;
		}

		public static JsonObject integer(String description, int defaultValue, int minimum, int maximum) {
			JsonObject schema = of("integer", description);
			schema.addProperty("default", defaultValue);
			schema.addProperty("minimum", minimum);
			schema.addProperty("maximum", maximum);
			return schema;
		}

		public static JsonObject choice(String description, String defaultValue, String... values) {
			JsonObject schema = of("string", description);
			JsonArray choices = new JsonArray();
			for (String value : values) {
				choices.add(value);
			}
			schema.add("enum", choices);
			schema.addProperty("default", defaultValue);
			return schema;
		}

		public static JsonObject object(String description) {
			JsonObject schema = of("object", description);
			schema.addProperty("additionalProperties", true);
			return schema;
		}

		private static JsonObject of(String type, String description) {
			JsonObject schema = new JsonObject();
			schema.addProperty("type", type);
			schema.addProperty("description", description);
			return schema;
		}
	}

	/**
	 * Reads tool arguments. A model can send anything the schema merely
	 * discourages, so every accessor checks the JSON type and fails with a
	 * message that says which argument was wrong and how.
	 */
	public static final class Args {
		private Args() {}

		public static String string(JsonObject arguments, String name) throws McpToolException {
			String value = optString(arguments, name, null);
			if (value == null || value.isEmpty()) {
				throw new McpToolException("Missing required argument: " + name);
			}
			return value;
		}

		public static String optString(JsonObject arguments, String name, String defaultValue)
				throws McpToolException {
			JsonElement value = arguments.get(name);
			if (value == null || value.isJsonNull()) {
				return defaultValue;
			}
			if (!value.isJsonPrimitive() || !((JsonPrimitive) value).isString()) {
				throw new McpToolException("Argument " + name + " must be a string.");
			}
			return value.getAsString();
		}

		public static boolean optBoolean(JsonObject arguments, String name, boolean defaultValue)
				throws McpToolException {
			JsonElement value = arguments.get(name);
			if (value == null || value.isJsonNull()) {
				return defaultValue;
			}
			if (!value.isJsonPrimitive() || !((JsonPrimitive) value).isBoolean()) {
				throw new McpToolException("Argument " + name + " must be true or false.");
			}
			return value.getAsBoolean();
		}

		/** An integer argument, clamped into {@code [minimum, maximum]}. */
		public static int optInt(JsonObject arguments, String name, int defaultValue, int minimum, int maximum)
				throws McpToolException {
			JsonElement value = arguments.get(name);
			if (value == null || value.isJsonNull()) {
				return defaultValue;
			}
			if (!value.isJsonPrimitive() || !((JsonPrimitive) value).isNumber()) {
				throw new McpToolException("Argument " + name + " must be an integer.");
			}
			double number = value.getAsDouble();
			if (number != Math.rint(number)) {
				throw new McpToolException("Argument " + name + " must be an integer.");
			}
			return (int) Math.max(minimum, Math.min(maximum, number));
		}

		/**
		 * An absolute repository path, normalized: no trailing slash, no empty,
		 * {@code .} or {@code ..} segments. Relative paths are refused rather
		 * than guessed at — there is no "current folder" to resolve them against.
		 */
		public static String path(JsonObject arguments, String name) throws McpToolException {
			return normalizePath(string(arguments, name), name);
		}

		public static String normalizePath(String path, String name) throws McpToolException {
			String value = path.trim();
			if (!value.startsWith("/")) {
				throw new McpToolException(
						"Argument " + name + " must be an absolute repository path starting with \"/\".");
			}
			StringBuilder normalized = new StringBuilder();
			for (String segment : value.split("/")) {
				if (segment.isEmpty()) {
					continue;
				}
				if (segment.equals(".") || segment.equals("..")) {
					throw new McpToolException("Argument " + name + " must not contain \".\" or \"..\" segments.");
				}
				normalized.append('/').append(segment);
			}
			return (normalized.length() == 0) ? "/" : normalized.toString();
		}

		/** The parent of a normalized absolute path ({@code "/"} for a top-level node). */
		public static String parentOf(String path) {
			int p = path.lastIndexOf('/');
			return (p <= 0) ? "/" : path.substring(0, p);
		}

		/** The last segment of a normalized absolute path. */
		public static String nameOf(String path) {
			return path.substring(path.lastIndexOf('/') + 1);
		}
	}

}

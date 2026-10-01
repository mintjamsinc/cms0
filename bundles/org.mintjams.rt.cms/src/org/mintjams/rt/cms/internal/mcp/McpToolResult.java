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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The result of a tool call: an ordered list of MCP content blocks and whether
 * the call failed. Structured values are sent as one JSON text block — compact,
 * with nulls dropped, because the reader is a model paying per token.
 */
public final class McpToolResult {

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final JsonArray fContent = new JsonArray();
	private boolean fError;

	private McpToolResult() {}

	/** A successful result carrying {@code value} serialized as JSON. */
	public static McpToolResult json(Object value) {
		return new McpToolResult().addJson(value);
	}

	/** A successful result carrying plain text. */
	public static McpToolResult text(String text) {
		return new McpToolResult().addText(text);
	}

	/** A failed call; {@code message} tells the model what to do differently. */
	public static McpToolResult error(String message) {
		McpToolResult result = new McpToolResult().addText(message);
		result.fError = true;
		return result;
	}

	public McpToolResult addJson(Object value) {
		return addText(GSON.toJson(value));
	}

	public McpToolResult addText(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		fContent.add(block);
		return this;
	}

	public McpToolResult addImage(String base64Data, String mimeType) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "image");
		block.addProperty("data", base64Data);
		block.addProperty("mimeType", mimeType);
		fContent.add(block);
		return this;
	}

	public boolean isError() {
		return fError;
	}

	/** The {@code tools/call} result object. */
	public JsonObject toJson() {
		JsonObject result = new JsonObject();
		result.add("content", fContent);
		result.addProperty("isError", fError);
		return result;
	}

}

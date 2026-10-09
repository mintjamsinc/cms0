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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The MCP protocol layer: JSON-RPC 2.0 messages in, JSON-RPC 2.0 messages out.
 *
 * <p>It knows nothing about HTTP, authentication or the repository — the
 * servlet resolves the caller into an {@link McpCallContext} and the tools do
 * the work — so the protocol can be exercised without a running CMS.
 *
 * <p>The server is <strong>stateless</strong>. It issues no session id and
 * keeps nothing between requests: {@code initialize} is answered from
 * constants, and every later request carries its own credentials. Any cluster
 * node can therefore answer any request, and a restart loses nothing.
 *
 * <p>Only the {@code tools} capability is offered. Tool failures are reported
 * inside a successful JSON-RPC response ({@code isError: true}) so the model
 * can read the message and correct itself; JSON-RPC errors are reserved for
 * requests that are malformed at the protocol level.
 */
public final class McpServer {

	/** Protocol revisions this server can speak, newest first. */
	static final List<String> SUPPORTED_PROTOCOL_VERSIONS = Collections
			.unmodifiableList(Arrays.asList("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"));

	/** Offered when the client asks for a revision this server does not know. */
	static final String FALLBACK_PROTOCOL_VERSION = "2025-06-18";

	static final int PARSE_ERROR = -32700;
	static final int INVALID_REQUEST = -32600;
	static final int METHOD_NOT_FOUND = -32601;
	static final int INVALID_PARAMS = -32602;

	private final String fServerVersion;
	private final Map<String, McpTool> fTools = new LinkedHashMap<>();

	public McpServer(String serverVersion, List<McpTool> tools) {
		fServerVersion = serverVersion;
		for (McpTool tool : tools) {
			if (fTools.put(tool.getName(), tool) != null) {
				throw new IllegalArgumentException("Duplicate tool name: " + tool.getName());
			}
		}
	}

	public static boolean isSupportedProtocolVersion(String version) {
		return SUPPORTED_PROTOCOL_VERSIONS.contains(version);
	}

	/**
	 * Handles one HTTP request body: a single message or a batch. Returns the
	 * response to send, or {@code null} when the body held only notifications
	 * or responses, which are acknowledged without a body.
	 */
	public JsonElement handle(JsonElement body, McpCallContext context) {
		if (body != null && body.isJsonArray()) {
			JsonArray batch = body.getAsJsonArray();
			if (batch.size() == 0) {
				return error(JsonNull.INSTANCE, INVALID_REQUEST, "Empty batch");
			}
			JsonArray responses = new JsonArray();
			for (JsonElement message : batch) {
				JsonObject response = handleMessage(message, context);
				if (response != null) {
					responses.add(response);
				}
			}
			return (responses.size() == 0) ? null : responses;
		}
		return handleMessage(body, context);
	}

	private JsonObject handleMessage(JsonElement message, McpCallContext context) {
		if (message == null || !message.isJsonObject()) {
			return error(JsonNull.INSTANCE, INVALID_REQUEST, "A JSON-RPC message must be an object");
		}
		JsonObject object = message.getAsJsonObject();

		JsonElement id = object.get("id");
		boolean hasId = (id != null && !id.isJsonNull());
		if (hasId && !(id.isJsonPrimitive()
				&& (((JsonPrimitive) id).isString() || ((JsonPrimitive) id).isNumber()))) {
			return error(JsonNull.INSTANCE, INVALID_REQUEST, "id must be a string or a number");
		}

		JsonElement method = object.get("method");
		if (method == null) {
			// A response to a request this server never sends: nothing to do.
			if (object.has("result") || object.has("error")) {
				return null;
			}
			return error(hasId ? id : JsonNull.INSTANCE, INVALID_REQUEST, "method is required");
		}
		if (!method.isJsonPrimitive() || !((JsonPrimitive) method).isString()) {
			return error(hasId ? id : JsonNull.INSTANCE, INVALID_REQUEST, "method must be a string");
		}
		JsonElement version = object.get("jsonrpc");
		if (version == null || !version.isJsonPrimitive() || !"2.0".equals(version.getAsString())) {
			return error(hasId ? id : JsonNull.INSTANCE, INVALID_REQUEST, "jsonrpc must be \"2.0\"");
		}

		if (!hasId) {
			// A notification (initialized, cancelled, …): never answered, and
			// the server holds no state for it to update.
			return null;
		}

		JsonElement paramsElement = object.get("params");
		if (paramsElement != null && !paramsElement.isJsonNull() && !paramsElement.isJsonObject()) {
			return error(id, INVALID_PARAMS, "params must be an object");
		}
		JsonObject params = (paramsElement != null && paramsElement.isJsonObject()) ? paramsElement.getAsJsonObject()
				: new JsonObject();

		switch (method.getAsString()) {
		case "initialize":
			return result(id, initialize(params, context));
		case "ping":
			return result(id, new JsonObject());
		case "tools/list":
			return result(id, listTools(context));
		case "tools/call":
			return callTool(id, params, context);
		default:
			return error(id, METHOD_NOT_FOUND, "Method not found: " + method.getAsString());
		}
	}

	private JsonObject initialize(JsonObject params, McpCallContext context) {
		String requested = null;
		JsonElement requestedVersion = params.get("protocolVersion");
		if (requestedVersion != null && requestedVersion.isJsonPrimitive()) {
			requested = requestedVersion.getAsString();
		}

		JsonObject result = new JsonObject();
		// Echo a revision we speak; otherwise offer ours and let the client decide.
		result.addProperty("protocolVersion",
				isSupportedProtocolVersion(requested) ? requested : FALLBACK_PROTOCOL_VERSION);

		JsonObject capabilities = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		capabilities.add("tools", tools);
		result.add("capabilities", capabilities);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "mintjams-cms");
		serverInfo.addProperty("title", "MintJams CMS (" + context.getServerLabel() + ")");
		serverInfo.addProperty("version", fServerVersion);
		result.add("serverInfo", serverInfo);

		result.addProperty("instructions", instructions(context));
		return result;
	}

	private String instructions(McpCallContext context) {
		StringBuilder sb = new StringBuilder();
		sb.append("MintJams CMS content repository (JCR) on the server \"").append(context.getServerLabel())
				.append("\", workspace \"").append(context.getWorkspaceName())
				.append("\", signed in as \"").append(context.getUserId()).append("\". ");
		sb.append("The user may have other servers connected (development, staging, production): say which "
				+ "server you are about to change when it matters. ");
		sb.append("Every call runs as that user: the repository's access control decides what is visible and "
				+ "changeable, and a node you cannot read looks like a node that does not exist. ");
		sb.append("Paths are absolute repository paths. Files are nt:file nodes, folders nt:folder. ");
		sb.append("Web content lives under /content; /content/WEB-INF holds templates and web.yml, and a folder's "
				+ ".web.yml binds its files to templates. ");
		sb.append("When a page is missing, broken or not rendered, call explain_web_render with the path that was "
				+ "requested before reading files one by one. ");
		if (context.canWrite()) {
			sb.append("This connection may change content. write_file refuses to replace an existing file unless "
					+ "overwrite is true: read the current content first and keep what you were not asked to "
					+ "change, especially in configuration files such as web.yml and .web.yml. ");
			sb.append("The workspace can define further tools of its own in tools.yml files under /etc/graphql, "
					+ "each a GraphQL operation or a script; they are deployed as soon as the file is written, and "
					+ "the GraphQL query mcpToolDeployments reports what deployed and what did not.");
		} else {
			sb.append("This connection is read-only: tools that change content are not available.");
		}
		return sb.toString();
	}

	private JsonObject listTools(McpCallContext context) {
		JsonArray tools = new JsonArray();
		List<McpTool> all = new ArrayList<>(fTools.values());
		// The tools the workspace defines for itself come after the built-in
		// ones; their names cannot collide (the compiler refuses a built-in name).
		all.addAll(context.getWorkspaceTools().getTools());
		for (McpTool tool : all) {
			if (tool.isWrite() && !context.canWrite()) {
				continue;
			}
			tools.add(tool.describe(context.canWrite()));
		}
		JsonObject result = new JsonObject();
		result.add("tools", tools);
		return result;
	}

	private McpTool findTool(String name, McpCallContext context) {
		McpTool tool = fTools.get(name);
		return (tool != null) ? tool : context.getWorkspaceTools().getTool(name);
	}

	private JsonObject callTool(JsonElement id, JsonObject params, McpCallContext context) {
		JsonElement name = params.get("name");
		if (name == null || !name.isJsonPrimitive() || !((JsonPrimitive) name).isString()) {
			return error(id, INVALID_PARAMS, "params.name is required");
		}
		McpTool tool = findTool(name.getAsString(), context);
		if (tool == null) {
			return error(id, INVALID_PARAMS, "Unknown tool: " + name.getAsString());
		}

		JsonElement argumentsElement = params.get("arguments");
		if (argumentsElement != null && !argumentsElement.isJsonNull() && !argumentsElement.isJsonObject()) {
			return error(id, INVALID_PARAMS, "params.arguments must be an object");
		}
		JsonObject arguments = (argumentsElement != null && argumentsElement.isJsonObject())
				? argumentsElement.getAsJsonObject()
				: new JsonObject();

		McpToolResult toolResult;
		if (tool.isWrite() && !context.canWrite()) {
			// Checked here, not only in tools/list: hiding a tool is not access control.
			toolResult = McpToolResult.error("The tool " + tool.getName()
					+ " changes content, and this connection is read-only. Its user can allow changes in the MCP "
					+ "section of Preferences.");
		} else {
			try {
				toolResult = tool.call(arguments, context);
			} catch (McpToolException ex) {
				toolResult = McpToolResult.error(ex.getMessage());
			} catch (Throwable ex) {
				toolResult = McpToolResult.error(describe(ex));
			}
		}
		return result(id, toolResult.toJson());
	}

	/** A failure message a model can act on: the exception type says what kind of refusal it was. */
	static String describe(Throwable ex) {
		String message = ex.getMessage();
		String type = ex.getClass().getSimpleName();
		if (message == null || message.isEmpty()) {
			return type;
		}
		return type + ": " + message;
	}

	static JsonObject result(JsonElement id, JsonElement result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("result", result);
		return response;
	}

	static JsonObject error(JsonElement id, int code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("error", error);
		return response;
	}

}

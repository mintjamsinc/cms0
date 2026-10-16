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

import java.nio.file.Path;
import java.nio.file.spi.FileTypeDetector;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.jcr.Credentials;
import javax.jcr.GuestCredentials;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.GraphQLRequest;
import org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.tools.adapter.Adaptables;

/**
 * Who is calling, where, and with what limits — the per-request state every
 * tool works from.
 *
 * <p>Tools reach the repository only through this context, and both ways it
 * offers run <strong>as the caller</strong>: {@link #login()} opens a JCR
 * session with the caller's credentials, and {@link #executeGraphQL} runs the
 * workspace's GraphQL schema under them. No tool opens a privileged session, so
 * the repository ACLs are the authority on what an MCP client can see and
 * change; the {@linkplain #canWrite() scope} only narrows that further.
 */
public class McpCallContext {

	private final String fWorkspaceName;
	private final Credentials fCredentials;
	private final String fUserId;
	private final boolean fCanWrite;
	private final String fServerLabel;

	public McpCallContext(String workspaceName, Credentials credentials, String userId, boolean canWrite,
			String serverLabel) {
		fWorkspaceName = workspaceName;
		fCredentials = credentials;
		fUserId = userId;
		fCanWrite = canWrite;
		fServerLabel = serverLabel;
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	/** The caller's user id, for messages and the audit log. */
	public String getUserId() {
		return fUserId;
	}

	/** Whether the caller may use tools that change the repository. */
	public boolean canWrite() {
		return fCanWrite;
	}

	/** The label that tells this server from the user's others (e.g. {@code prod}, or its host name). */
	public String getServerLabel() {
		return fServerLabel;
	}

	/** The caller's credentials: what every session a tool opens is logged in with. */
	public Credentials getCredentials() {
		return fCredentials;
	}

	/** The tools the workspace defines for itself (tools.yml); empty when it defines none. */
	public McpToolRegistry getWorkspaceTools() {
		WorkspaceGraphQLEngineProvider engine = CmsService.getWorkspaceGraphQLEngineProvider(fWorkspaceName);
		return (engine == null) ? McpToolRegistry.EMPTY : engine.getMcpTools();
	}

	/**
	 * Opens a JCR session on the workspace as the caller. The caller of this
	 * method owns the session and must {@code logout()} it.
	 */
	public Session login() throws RepositoryException {
		return CmsService.getRepository().login(fCredentials, fWorkspaceName);
	}

	/**
	 * Opens a JCR session on the workspace as the anonymous web visitor, or
	 * returns {@code null} when the workspace does not admit one. Used only to
	 * answer "can the public see this?"; never to read on the caller's behalf.
	 */
	public Session loginAsGuest() {
		try {
			return CmsService.getRepository().login(new GuestCredentials(), fWorkspaceName);
		} catch (Throwable ex) {
			return null;
		}
	}

	/** Script file extensions (e.g. {@code gsp}) the workspace evaluates when serving. */
	public String[] getScriptExtensions() {
		return Scripts.getScriptExtensions(CmsService.getWorkspaceScriptEngineManager(fWorkspaceName));
	}

	/**
	 * The MIME type the repository associates with a file name, or
	 * {@code null} when it has none — the same detector uploads go through.
	 */
	public String probeMimeType(String fileName) {
		try {
			return Adaptables.getAdapter(CmsService.getRepository(), FileTypeDetector.class)
					.probeContentType(Path.of(fileName));
		} catch (Throwable ex) {
			return null;
		}
	}

	/**
	 * Whether the workspace schema's {@code Query} type has this field. A
	 * built-in tool uses it to lean on an operation the workspace defines for
	 * itself (such as {@code documentText}) when it is there, and to say what
	 * is missing when it is not.
	 */
	public boolean hasQueryField(String name) {
		WorkspaceGraphQLEngineProvider engine = CmsService.getWorkspaceGraphQLEngineProvider(fWorkspaceName);
		return engine != null && engine.hasQueryField(name);
	}

	/**
	 * A tool result carrying {@code value} as JSON — for a script tool that
	 * wants to add content blocks to its result (see {@link #image}); a script
	 * that returns a map or a list gets the same without asking.
	 */
	public McpToolResult result(Object value) {
		return McpToolResult.json(ScriptToolHandler.normalize(value));
	}

	/** A tool result that is plain text. */
	public McpToolResult text(String text) {
		return McpToolResult.text(text);
	}

	/**
	 * A tool result carrying an image, for a script tool: {@code mcp.image(bytes,
	 * 'image/png')}. The returned result accepts further blocks
	 * ({@code addJson}, {@code addText}, {@code addImage}). A script's return
	 * value is otherwise text or JSON; this is the only way a script hands the
	 * model something to look at.
	 */
	public McpToolResult image(byte[] data, String mimeType) {
		if (data == null || data.length == 0) {
			throw new IllegalArgumentException("image: the data is empty.");
		}
		if (mimeType == null || !mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
			throw new IllegalArgumentException("image: mimeType must be an image type such as image/png.");
		}
		return McpToolResult.json(new LinkedHashMap<String, Object>())
				.addImage(Base64.getEncoder().encodeToString(data), mimeType);
	}

	/** {@link #image(byte[], String)} with the data already Base64-encoded. */
	public McpToolResult image(String base64, String mimeType) {
		if (base64 == null || base64.isEmpty()) {
			throw new IllegalArgumentException("image: the data is empty.");
		}
		if (mimeType == null || !mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
			throw new IllegalArgumentException("image: mimeType must be an image type such as image/png.");
		}
		return McpToolResult.json(new LinkedHashMap<String, Object>()).addImage(base64, mimeType);
	}

	/**
	 * Executes a GraphQL operation on the workspace schema as the caller and
	 * returns the GraphQL-spec response map ({@code data} / {@code errors}).
	 */
	public Map<String, Object> executeGraphQL(String query, String operationName, Map<String, Object> variables)
			throws McpToolException {
		WorkspaceGraphQLEngineProvider engine = CmsService.getWorkspaceGraphQLEngineProvider(fWorkspaceName);
		if (engine == null || !engine.isAvailable()) {
			throw new McpToolException("The GraphQL schema is not available for the workspace: " + fWorkspaceName);
		}
		return engine.execute(new GraphQLRequest(query, operationName, variables), fCredentials);
	}

}

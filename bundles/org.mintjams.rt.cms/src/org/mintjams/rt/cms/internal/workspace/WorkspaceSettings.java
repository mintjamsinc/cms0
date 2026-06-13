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

package org.mintjams.rt.cms.internal.workspace;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mintjams.rt.cms.internal.CmsService;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

/**
 * Per-workspace operational settings persisted to
 * {@code workspaces/<name>/etc/workspace.yml}.
 *
 * <p>This is the workspace's own metadata — properties that belong to the
 * workspace as a managed unit rather than to one of its engines. It sits
 * alongside {@code etc/bpm/bpm.yml} and {@code etc/eip/eip.yml}, which remain
 * the authoritative homes for the process- and integration-engine switches;
 * this file never duplicates them.
 *
 * <ul>
 *   <li>{@code displayName} — a human-friendly label shown wherever the
 *       workspace is presented (the desktop's workspace switcher, the
 *       Workspace Manager). Optional; the UI falls back to the workspace name
 *       (the immutable URL segment) when it is unset.</li>
 *   <li>{@code autoStart} — whether the workspace's CMS services start
 *       automatically when the node boots. Defaults to {@code true} so existing
 *       workspaces keep starting exactly as before this property was
 *       introduced. When {@code false}, the workspace stays stopped at boot
 *       until an operator starts it from the Workspace Manager.</li>
 * </ul>
 *
 * <p>The file is read on demand and written in place, preserving any keys it
 * does not own, so it round-trips safely as the schema grows.
 */
public class WorkspaceSettings {

	private static final String FILE_NAME = "workspace.yml";
	private static final String KEY_DISPLAY_NAME = "displayName";
	private static final String KEY_AUTO_START = "autoStart";

	private final String fWorkspaceName;
	private Map<String, Object> fConfig = new LinkedHashMap<>();

	public WorkspaceSettings(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	/**
	 * Reads {@code workspace.yml} into memory. A missing or empty file is not
	 * an error — the workspace simply has no overrides yet, and every getter
	 * returns its documented default.
	 */
	@SuppressWarnings("unchecked")
	public WorkspaceSettings load() throws IOException {
		Path file = getConfigPath().resolve(FILE_NAME);
		if (!Files.exists(file)) {
			fConfig = new LinkedHashMap<>();
			return this;
		}
		try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
			Object loaded = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			fConfig = (loaded instanceof Map) ? (Map<String, Object>) loaded : new LinkedHashMap<>();
		}
		return this;
	}

	/**
	 * Writes the current settings back to {@code workspace.yml}, creating the
	 * {@code etc} directory if necessary.
	 */
	public void save() throws IOException {
		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path file = configPath.resolve(FILE_NAME);
		String yamlString = new Dump(DumpSettings.builder()
				.setIndent(4)
				.setIndicatorIndent(2)
				.setDefaultFlowStyle(FlowStyle.BLOCK)
				.build()).dumpToString(fConfig);
		try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			out.append(yamlString);
		}
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	/** The workspace's display label, or {@code null} when none is configured. */
	public String getDisplayName() {
		Object v = fConfig.get(KEY_DISPLAY_NAME);
		if (v == null) {
			return null;
		}
		String s = v.toString().trim();
		return s.isEmpty() ? null : s;
	}

	public WorkspaceSettings setDisplayName(String displayName) {
		if (displayName == null || displayName.trim().isEmpty()) {
			fConfig.remove(KEY_DISPLAY_NAME);
		} else {
			fConfig.put(KEY_DISPLAY_NAME, displayName.trim());
		}
		return this;
	}

	/** Whether the workspace starts automatically at boot. Defaults to {@code true}. */
	public boolean isAutoStart() {
		Object v = fConfig.get(KEY_AUTO_START);
		if (v instanceof Boolean) {
			return (Boolean) v;
		}
		if (v instanceof String) {
			return Boolean.parseBoolean(((String) v).trim());
		}
		return true;
	}

	public WorkspaceSettings setAutoStart(boolean autoStart) {
		fConfig.put(KEY_AUTO_START, autoStart);
		return this;
	}

	private Path getConfigPath() {
		return CmsService.getRepositoryPath().resolve("workspaces").resolve(fWorkspaceName).resolve("etc").normalize();
	}

	// =========================================================================
	// Convenience accessors
	//
	// Read paths swallow IO failures and fall back to the documented default:
	// a malformed workspace.yml must never stop a workspace from being listed
	// or from booting.
	// =========================================================================

	/** Display label for {@code workspaceName}, or {@code null} when unset or unreadable. */
	public static String displayNameOf(String workspaceName) {
		try {
			return new WorkspaceSettings(workspaceName).load().getDisplayName();
		} catch (IOException ex) {
			CmsService.getLogger(WorkspaceSettings.class)
					.warn("Could not read workspace settings for: " + workspaceName, ex);
			return null;
		}
	}

	/** Auto-start policy for {@code workspaceName}; {@code true} when unset or unreadable. */
	public static boolean isAutoStartOf(String workspaceName) {
		try {
			return new WorkspaceSettings(workspaceName).load().isAutoStart();
		} catch (IOException ex) {
			CmsService.getLogger(WorkspaceSettings.class)
					.warn("Could not read workspace settings for: " + workspaceName, ex);
			return true;
		}
	}
}

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

package org.mintjams.rt.cms.internal.eip;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.collections.AdaptableMap;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

public class WorkspaceIntegrationEngineProviderConfiguration {

	private final String fWorkspaceName;
	private Map<String, Object> fConfig;

	public WorkspaceIntegrationEngineProviderConfiguration(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	public void load() throws IOException {
		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path eipPath = configPath.resolve("eip.yml");
		if (!Files.exists(eipPath)) {
			try (Writer out = Files.newBufferedWriter(eipPath, StandardCharsets.UTF_8)) {
				String yamlString = new Dump(DumpSettings.builder()
						.setIndent(4)
						.setIndicatorIndent(2)
						.setDefaultFlowStyle(FlowStyle.BLOCK)
						.build()).dumpToString(AdaptableMap.<String, Object>newBuilder()
						.build());
				out.append(yamlString);
			}
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(eipPath))) {
			fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
		}
	}

	/**
	 * Returns whether the integration engine runs for this workspace
	 * ({@code eip.yml#enabled}). Enabled by default, so existing workspaces
	 * keep behaving exactly as before this property was introduced; disable
	 * it for workspaces that run no routes to save the Camel context's
	 * resources.
	 */
	public boolean isEnabled() {
		if (fConfig == null) {
			// A freshly generated eip.yml is empty and parses to null.
			return true;
		}
		Object v = fConfig.get("enabled");
		if (v instanceof Boolean) {
			return (Boolean) v;
		}
		if (v instanceof String) {
			return Boolean.parseBoolean(((String) v).trim());
		}
		return true;
	}

	/**
	 * Reads the persisted {@code eip.yml#enabled} switch for
	 * {@code workspaceName} without starting the engine or generating the
	 * default file. Returns the configured intent regardless of whether the
	 * workspace's services are running, which is what lets the Workspace Manager
	 * show and edit the switch for a stopped workspace. Defaults to {@code true}
	 * when the file is absent or unreadable, matching {@link #isEnabled()}.
	 */
	@SuppressWarnings("unchecked")
	public static boolean isEnabledOnDisk(String workspaceName) {
		Path eipPath = new WorkspaceIntegrationEngineProviderConfiguration(workspaceName)
				.getConfigPath().resolve("eip.yml");
		if (!Files.exists(eipPath)) {
			return true;
		}
		try (InputStream in = new BufferedInputStream(Files.newInputStream(eipPath))) {
			Object loaded = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			if (!(loaded instanceof Map)) {
				return true;
			}
			Object v = ((Map<String, Object>) loaded).get("enabled");
			if (v instanceof Boolean) {
				return (Boolean) v;
			}
			if (v instanceof String) {
				return Boolean.parseBoolean(((String) v).trim());
			}
			return true;
		} catch (IOException ex) {
			CmsService.getLogger(WorkspaceIntegrationEngineProviderConfiguration.class)
					.warn("Could not read eip.yml for workspace: " + workspaceName, ex);
			return true;
		}
	}

	/**
	 * Switches the integration engine on or off for {@code workspaceName} by
	 * persisting {@code eip.yml#enabled}, preserving every other key in the
	 * file. The change is a configuration edit only: it takes effect the next
	 * time the workspace's services start (the provider reads {@code enabled}
	 * in {@link WorkspaceIntegrationEngineProvider#open()}), so callers restart
	 * the workspace to apply it.
	 */
	public static void setEnabled(String workspaceName, boolean enabled) throws IOException {
		WorkspaceIntegrationEngineProviderConfiguration config = new WorkspaceIntegrationEngineProviderConfiguration(workspaceName);
		config.load();
		if (config.fConfig == null) {
			config.fConfig = new java.util.LinkedHashMap<>();
		}
		config.fConfig.put("enabled", enabled);
		config.save();
	}

	/**
	 * Writes the in-memory configuration back to {@code eip.yml}, using the
	 * same block style {@link #load()} generates the default file in.
	 */
	public void save() throws IOException {
		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path eipPath = configPath.resolve("eip.yml");
		String yamlString = new Dump(DumpSettings.builder()
				.setIndent(4)
				.setIndicatorIndent(2)
				.setDefaultFlowStyle(FlowStyle.BLOCK)
				.build()).dumpToString(fConfig);
		try (Writer out = Files.newBufferedWriter(eipPath, StandardCharsets.UTF_8)) {
			out.append(yamlString);
		}
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public Path getWorkspacePath() {
		return CmsService.getRepositoryPath().resolve("workspaces").resolve(getWorkspaceName()).normalize();
	}

	public Path getDataPath() {
		return getWorkspacePath().resolve("var/eip").normalize();
	}

	public Path getConfigPath() {
		return getWorkspacePath().resolve("etc/eip").normalize();
	}

}

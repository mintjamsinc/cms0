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

package org.mintjams.rt.cms.internal.graphql.engine;

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

/**
 * Per-workspace configuration for the application-defined (dynamic) part of the
 * unified GraphQL engine, persisted as
 * {@code workspaces/<name>/etc/graphql/graphql.yml}.
 *
 * <p>Application GraphQL is enabled by default so that a workspace immediately
 * serves any schema dropped under its watched folders; with no schema files
 * present nothing extra is exposed, so leaving it on costs nothing. Disable it
 * ({@code graphql.yml#enabled: false}) for workspaces that must never expose an
 * application-defined GraphQL schema. This switch gates only the application
 * schema scan — the platform's built-in {@code /bin/graphql.cgi} API is always
 * served.
 *
 * <p>This mirrors {@code WorkspaceProcessEngineProviderConfiguration} and
 * {@code WorkspaceIntegrationEngineProviderConfiguration} so operators find a
 * familiar {@code enabled} switch in the same place.
 */
public class WorkspaceGraphQLEngineProviderConfiguration {

	private final String fWorkspaceName;
	private Map<String, Object> fConfig;

	public WorkspaceGraphQLEngineProviderConfiguration(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@SuppressWarnings("unchecked")
	public void load() throws IOException {
		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path graphqlPath = configPath.resolve("graphql.yml");
		if (!Files.exists(graphqlPath)) {
			try (Writer out = Files.newBufferedWriter(graphqlPath, StandardCharsets.UTF_8)) {
				String yamlString = new Dump(DumpSettings.builder()
						.setIndent(4)
						.setIndicatorIndent(2)
						.setDefaultFlowStyle(FlowStyle.BLOCK)
						.build()).dumpToString(AdaptableMap.<String, Object>newBuilder()
						.put("enabled", true)
						.build());
				out.append(yamlString);
			}
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(graphqlPath))) {
			fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
		}
	}

	/**
	 * Returns whether application (dynamic) GraphQL is enabled for this workspace
	 * ({@code graphql.yml#enabled}); gates only the application schema scan, not
	 * the always-served platform schema. Enabled by default.
	 */
	public boolean isEnabled() {
		if (fConfig == null) {
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

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public Path getWorkspacePath() {
		return CmsService.getRepositoryPath().resolve("workspaces").resolve(getWorkspaceName()).normalize();
	}

	public Path getConfigPath() {
		return getWorkspacePath().resolve("etc/graphql").normalize();
	}

}

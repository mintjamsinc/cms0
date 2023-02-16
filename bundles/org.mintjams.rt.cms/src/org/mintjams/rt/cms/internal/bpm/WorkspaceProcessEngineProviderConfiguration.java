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

package org.mintjams.rt.cms.internal.bpm;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.scripting.engine.BeansResolverFactory;
import org.camunda.bpm.engine.impl.scripting.engine.DefaultScriptEngineResolver;
import org.camunda.bpm.engine.impl.scripting.engine.ResolverFactory;
import org.camunda.bpm.engine.impl.scripting.engine.ScriptBindingsFactory;
import org.camunda.bpm.engine.impl.scripting.engine.ScriptingEngines;
import org.camunda.bpm.engine.impl.scripting.engine.VariableScopeResolverFactory;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.WorkspaceDelegatingClassLoader;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.osgi.Configuration;
import org.mintjams.tools.osgi.VariableProvider;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class WorkspaceProcessEngineProviderConfiguration {

	private final String fWorkspaceName;
	private Map<String, Object> fConfig;

	public WorkspaceProcessEngineProviderConfiguration(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	public void load() throws IOException {
		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path bpmPath = configPath.resolve("bpm.yml");
		if (!Files.exists(bpmPath)) {
			try (Writer out = Files.newBufferedWriter(bpmPath)) {
				String yamlString = new Dump(DumpSettings.builder().build()).dumpToString(AdaptableMap.<String, Object>newBuilder()
						.put("jdbcURL", "jdbc:h2:" + getDataPath().resolve("data").toAbsolutePath())
						.build());
				out.append(yamlString);
			}
		}

		Map<String, Object> bpmYaml;
		try (InputStream in = new BufferedInputStream(Files.newInputStream(bpmPath))) {
			fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
		}
	}

	public ProcessEngine createProcessEngine() {
		ScriptingEngines scriptingEngines = new ScriptingEngines(new DefaultScriptEngineResolver(CmsService.getWorkspaceScriptEngineManager(getWorkspaceName())));
		List<ResolverFactory> resolverFactories = new ArrayList<>();
		resolverFactories.add(new VariableScopeResolverFactory());
		resolverFactories.add(new BeansResolverFactory());
		scriptingEngines.setScriptBindingsFactory(new ScriptBindingsFactory(resolverFactories));
		ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration();
		config.setClassLoader(new WorkspaceDelegatingClassLoader(getWorkspaceName()))
				.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
				.setJdbcUrl(getJdbcURL())
				.setScriptingEngines(scriptingEngines)
				.setJobExecutorActivate(true);
		return config.buildProcessEngine();
	}

	private String getJdbcURL() {
		String v = adapt(fConfig.get("jdbcURL"), String.class).trim();
		v = Configuration.create(CmsService.getDefault().getBundleContext()).with(new VariableProviderImpl()).replaceVariables(v);
		return v;
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public Path getWorkspacePath() {
		return CmsService.getRepositoryPath().resolve("workspaces").resolve(getWorkspaceName()).normalize();
	}

	public Path getDataPath() {
		return getWorkspacePath().resolve("var/bpm").normalize();
	}

	public Path getConfigPath() {
		return getWorkspacePath().resolve("etc/bpm").normalize();
	}

	private <AdapterType> AdapterType adapt(Object value, Class<AdapterType> adapterType) {
		return AdaptableList.<Object>newBuilder()
				.setEncoding(StandardCharsets.UTF_8.name())
				.add(value).build().adapt(0, adapterType).getValue();
	}

	private class VariableProviderImpl implements VariableProvider {
		private final java.util.Properties fSystemProperties = System.getProperties();

		private VariableProviderImpl() {
			fSystemProperties.setProperty("repository.home", CmsService.getRepositoryPath().toString());
			fSystemProperties.setProperty("workspace.home", CmsService.getWorkspacePath(getWorkspaceName()).toString());
		}

		@Override
		public Object getVariable(String name) {
			String value = CmsService.getDefault().getBundleContext().getProperty(name);
			if (value != null) {
				return value;
			}

			value = fSystemProperties.getProperty(name);
			if (value != null) {
				return value;
			}

			if ("osgi.instance.area".equals(name)) {
				return ".";
			}

			return null;
		}
	}

}

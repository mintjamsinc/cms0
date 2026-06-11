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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.camunda.bpm.engine.impl.scripting.ExecutableScript;
import org.camunda.bpm.engine.impl.scripting.ScriptFactory;
import org.camunda.bpm.engine.impl.scripting.engine.BeansResolverFactory;
import org.camunda.bpm.engine.impl.scripting.engine.DefaultScriptEngineResolver;
import org.camunda.bpm.engine.impl.scripting.engine.ResolverFactory;
import org.camunda.bpm.engine.impl.scripting.engine.ScriptBindingsFactory;
import org.camunda.bpm.engine.impl.scripting.engine.ScriptingEngines;
import org.camunda.bpm.engine.impl.scripting.engine.VariableScopeResolverFactory;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.WorkspaceDelegatingClassLoader;
import org.mintjams.rt.cms.internal.bpm.event.EventAdminProcessEnginePlugin;
import org.mintjams.rt.cms.internal.util.ResourceLoader;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.osgi.Configuration;
import org.mintjams.tools.osgi.VariableProvider;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

public class WorkspaceProcessEngineProviderConfiguration {

	/**
	 * Camunda history levels supported for {@code bpm.yml#history}, including the
	 * special {@code auto} value that lets the engine adopt the level already
	 * recorded in the database. The deprecated {@code variable} level is
	 * intentionally excluded.
	 */
	private static final List<String> SUPPORTED_HISTORY_LEVELS = Arrays.asList(
			ProcessEngineConfiguration.HISTORY_NONE,
			ProcessEngineConfiguration.HISTORY_ACTIVITY,
			ProcessEngineConfiguration.HISTORY_AUDIT,
			ProcessEngineConfiguration.HISTORY_FULL,
			ProcessEngineConfiguration.HISTORY_AUTO);

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
			try (Writer out = Files.newBufferedWriter(bpmPath, StandardCharsets.UTF_8)) {
				String yamlString = new Dump(DumpSettings.builder()
						.setIndent(4)
						.setIndicatorIndent(2)
						.setDefaultFlowStyle(FlowStyle.BLOCK)
						.build()).dumpToString(AdaptableMap.<String, Object>newBuilder()
						.put("jdbcURL", "jdbc:h2:" + getDataPath().resolve("data").toAbsolutePath())
						.put("history", ProcessEngineConfiguration.HISTORY_AUDIT)
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
		String username = getUsername();
		if (username != null) {
			config.setJdbcUsername(username);
		}
		String password = getPassword();
		if (password != null) {
			config.setJdbcPassword(password);
		}
		String driverClassName = getDriverClassName();
		if (driverClassName != null) {
			prepareJdbcDriver(driverClassName);
			config.setJdbcDriver(driverClassName);
		}
		config.setJobExecutorAcquireByPriority(true);
		config.setHistory(getHistory());
		config.setScriptFactory(new WorkspaceScriptFactory());
		config.setIdentityProviderSessionFactory(new CmsIdentityProviderFactory());
		List<ProcessEnginePlugin> plugins = config.getProcessEnginePlugins();
		if (plugins == null) {
			plugins = new ArrayList<>();
			config.setProcessEnginePlugins(plugins);
		}
		plugins.add(new EventAdminProcessEnginePlugin(getWorkspaceName()));
		return config.buildProcessEngine();
	}

	/**
	 * Loads the configured JDBC driver class eagerly through the Camunda
	 * bundle's class loader — the loader MyBatis resolves drivers with — so
	 * that a missing driver bundle fails fast with a clear message instead
	 * of surfacing as an obscure connection error during engine bootstrap.
	 */
	private void prepareJdbcDriver(String driverClassName) {
		try {
			Class.forName(driverClassName, true, ProcessEngineConfiguration.class.getClassLoader());
		} catch (Throwable ex) {
			throw new ProcessEngineException("The JDBC driver class could not be loaded: " + driverClassName
					+ " (is the driver bundle installed?)", ex);
		}
	}

	private String getJdbcURL() {
		String v = adapt(fConfig.get("jdbcURL"), String.class).trim();
		v = Configuration.create(CmsService.getDefault().getBundleContext()).with(new VariableProviderImpl()).replaceVariables(v);
		return v;
	}

	/**
	 * Returns the database user for the engine database
	 * ({@code bpm.yml#username}, with {@code ${...}} variable substitution),
	 * or {@code null} to keep the engine default ({@code sa}, matching the
	 * embedded H2 database that is generated by default).
	 */
	private String getUsername() {
		return getOptionalString("username");
	}

	/**
	 * Returns the database password for the engine database
	 * ({@code bpm.yml#password}, with {@code ${...}} variable substitution),
	 * or {@code null} to keep the engine default (empty, matching the
	 * embedded H2 database that is generated by default).
	 */
	private String getPassword() {
		return getOptionalString("password");
	}

	/**
	 * Returns the JDBC driver class name to load before the engine connects
	 * ({@code bpm.yml#driverClassName}), or {@code null} to let the engine
	 * resolve the driver from the JDBC URL. Needed for drivers (such as
	 * PostgreSQL's) that live in their own OSGi bundle.
	 */
	private String getDriverClassName() {
		return getOptionalString("driverClassName");
	}

	private String getOptionalString(String key) {
		Object value = (fConfig == null) ? null : fConfig.get(key);
		if (value == null) {
			return null;
		}
		String v = adapt(value, String.class);
		if (v == null || v.trim().isEmpty()) {
			return null;
		}
		return Configuration.create(CmsService.getDefault().getBundleContext()).with(new VariableProviderImpl())
				.replaceVariables(v.trim());
	}

	/**
	 * Returns the configured Camunda history level for this workspace.
	 *
	 * <p>The level is read from {@code bpm.yml#history}. When the property is
	 * absent or blank, the engine's documented default ({@code audit}) is used,
	 * so existing workspaces keep behaving exactly as before this property was
	 * introduced. An unknown value is rejected eagerly rather than silently
	 * falling back, so a typo can never quietly disable history recording.
	 *
	 * <p>Note: Camunda persists the resolved level into the database on the
	 * first engine bootstrap ({@code ACT_GE_PROPERTY#historyLevel}) and, on
	 * subsequent boots, fails with a "historyLevel mismatch" exception if the
	 * configured level no longer matches the stored one. Changing this value for
	 * an existing workspace therefore requires a deliberate migration; see
	 * {@code documents/bpm-configuration.md}.
	 */
	private String getHistory() {
		Object value = (fConfig == null) ? null : fConfig.get("history");
		String history = adapt(value, String.class);
		if (history != null) {
			history = history.trim().toLowerCase();
		}
		if (history == null || history.isEmpty()) {
			return ProcessEngineConfiguration.HISTORY_DEFAULT;
		}
		if (!SUPPORTED_HISTORY_LEVELS.contains(history)) {
			throw new ProcessEngineException("Unsupported BPM history level: \"" + history
					+ "\". Supported levels are: " + SUPPORTED_HISTORY_LEVELS + ".");
		}
		return history;
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
		@Override
		public Object getVariable(String name) {
			// Resolved directly instead of via mutated global system
			// properties: each workspace must see its own values even when
			// several workspaces resolve their configuration concurrently.
			if ("repository.home".equals(name)) {
				return CmsService.getRepositoryPath().toString();
			}
			if ("workspace.home".equals(name)) {
				return CmsService.getWorkspacePath(getWorkspaceName()).toString();
			}
			if ("workspace.name".equals(name)) {
				return getWorkspaceName();
			}

			String value = CmsService.getDefault().getBundleContext().getProperty(name);
			if (value != null) {
				return value;
			}

			if ("osgi.instance.area".equals(name)) {
				return ".";
			}

			return System.getProperty(name);
		}
	}

	private class WorkspaceScriptFactory extends ScriptFactory {
		@Override
		public ExecutableScript createScriptFromResource(String language, String resource) {
			if (resource.startsWith("jcr:///")) {
				try {
					String scriptSource = ResourceLoader.load(getWorkspaceName(), new URL(resource).getPath());
					return createScriptFromSource(language, scriptSource);
				} catch (Throwable ex) {
					throw Cause.create(ex).wrap(ProcessEngineException.class);
				}
			}

			return super.createScriptFromResource(language, resource);
		}
	}

}

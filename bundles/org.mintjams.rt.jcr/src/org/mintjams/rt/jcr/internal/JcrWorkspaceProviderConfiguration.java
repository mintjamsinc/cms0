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

package org.mintjams.rt.jcr.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.osgi.Configuration;
import org.mintjams.tools.osgi.VariableProvider;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class JcrWorkspaceProviderConfiguration {

	private final String fWorkspaceName;
	private Map<String, Object> fConfig;

	public JcrWorkspaceProviderConfiguration(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	public void load() throws IOException {
		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path jcrPath = configPath.resolve("jcr.yml");
		if (!Files.exists(jcrPath)) {
			try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(jcrPath))) {
				try (InputStream in = getClass().getResourceAsStream("jcr.yml")) {
					IOs.copy(in, out);
				}
			}
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(jcrPath))) {
			fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
		}
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public Path getWorkspacePath() {
		return Activator.getDefault().getRepository().getConfiguration().getRepositoryPath().resolve("workspaces").resolve(getWorkspaceName()).normalize();
	}

	public Path getConfigPath() {
		return getWorkspacePath().resolve("etc/jcr").normalize();
	}

	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> getDefaultNodes() {
		return (List<Map<String, Object>>) fConfig.get("defaultNodes");
	}

	/**
	 * Returns the JDBC URL of the workspace database
	 * ({@code jcr.yml#datasource.jdbcURL}, with {@code ${...}} variable
	 * substitution). When the property is absent, the embedded H2 database
	 * under {@code var/jcr/data} is used, so existing workspaces keep
	 * behaving exactly as before this property was introduced. For a
	 * clustered deployment, point every node at the same shared database
	 * (e.g. PostgreSQL).
	 */
	public String getDatasourceJdbcUrl(String defaultJdbcUrl) {
		return replaceVariables(getDatasourceString("jdbcURL", defaultJdbcUrl));
	}

	public String getDatasourceUsername(String defaultUsername) {
		return replaceVariables(getDatasourceString("username", defaultUsername));
	}

	public String getDatasourcePassword(String defaultPassword) {
		return replaceVariables(getDatasourceString("password", defaultPassword));
	}

	/**
	 * Returns the JDBC driver class name to load before opening the
	 * connection pool ({@code jcr.yml#datasource.driverClassName}), or
	 * {@code null} to let the pool resolve the driver from the JDBC URL.
	 * Needed for drivers (such as PostgreSQL's) that live in their own OSGi
	 * bundle and are not discoverable through {@code java.sql.DriverManager}.
	 */
	public String getDatasourceDriverClassName() {
		return getDatasourceString("driverClassName", null);
	}

	private String getDatasourceString(String name, String defaultValue) {
		try {
			String value = ExpressionContext.create().setVariable("config", fConfig)
					.defaultString("config.datasource." + name, null);
			if (Strings.isNotEmpty(value)) {
				return value.trim();
			}
		} catch (Throwable ignore) {}
		return defaultValue;
	}

	/**
	 * Returns the blob store type ({@code jcr.yml#blobstore.type}). The
	 * default ({@code fs}) stores blobs as files under the directory
	 * returned by {@link #getBlobStoreDirectory(java.nio.file.Path)}.
	 */
	public String getBlobStoreType() {
		try {
			String value = ExpressionContext.create().setVariable("config", fConfig)
					.defaultString("config.blobstore.type", null);
			if (Strings.isNotEmpty(value)) {
				return value.trim();
			}
		} catch (Throwable ignore) {}
		return "fs";
	}

	/**
	 * Returns the blob store directory ({@code jcr.yml#blobstore.directory},
	 * with {@code ${...}} variable substitution). Defaults to the
	 * workspace's {@code var/jcr/bin} directory; for a clustered deployment
	 * this must resolve to storage shared by all nodes.
	 */
	public Path getBlobStoreDirectory(Path defaultDirectory) {
		try {
			String value = ExpressionContext.create().setVariable("config", fConfig)
					.defaultString("config.blobstore.directory", null);
			if (Strings.isNotEmpty(value)) {
				return Path.of(replaceVariables(value.trim())).normalize();
			}
		} catch (Throwable ignore) {}
		return defaultDirectory;
	}

	/**
	 * Returns the directory holding this node's search index
	 * ({@code jcr.yml#search.indexPath}, with {@code ${...}} variable
	 * substitution), or {@code null} when not configured. The search index
	 * is node-local: in a clustered deployment every node maintains its own
	 * index, so when the workspace directory itself lives on shared storage
	 * this must resolve to a per-node directory.
	 */
	public Path getSearchIndexPath() {
		try {
			String value = ExpressionContext.create().setVariable("config", fConfig)
					.defaultString("config.search.indexPath", null);
			if (Strings.isNotEmpty(value)) {
				return Path.of(replaceVariables(value.trim())).normalize();
			}
		} catch (Throwable ignore) {}
		return null;
	}

	private String replaceVariables(String value) {
		if (value == null) {
			return null;
		}
		return Configuration.create(Activator.getDefault().getBundleContext())
				.with(new VariableProviderImpl()).replaceVariables(value);
	}

	private class VariableProviderImpl implements VariableProvider {
		@Override
		public Object getVariable(String name) {
			if ("repository.home".equals(name)) {
				return Activator.getDefault().getRepository().getConfiguration().getRepositoryPath().toString();
			}
			if ("workspace.home".equals(name)) {
				return getWorkspacePath().toString();
			}
			if ("workspace.name".equals(name)) {
				return getWorkspaceName();
			}
			if ("cluster.nodeId".equals(name)) {
				return Activator.getDefault().getRepository().getConfiguration().getClusterNodeId();
			}

			String value = Activator.getDefault().getBundleContext().getProperty(name);
			if (value != null) {
				return value;
			}

			return System.getProperty(name);
		}
	}

	private List<String> fSuggestionPropertyKeys = null;
	public List<String> getSuggestionPropertyKeys() {
		if (fSuggestionPropertyKeys == null) {
			ExpressionContext el = ExpressionContext.create().setVariable("config", fConfig);
			try {
				fSuggestionPropertyKeys = Arrays.asList(el.getStringArray("config.search.suggestion.propertyKeys"));
			} catch (Throwable ignore) {
				fSuggestionPropertyKeys = new ArrayList<>();
			}
		}
		return fSuggestionPropertyKeys;
	}

	private List<Map<String, Object>> fAccessControlFilters = null;
	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> getAccessControlFilters() {
		if (fAccessControlFilters == null) {
			ExpressionContext el = ExpressionContext.create().setVariable("config", fConfig);
			fAccessControlFilters = (List<Map<String, Object>>) el.evaluate("config.security.filters");
		}
		return fAccessControlFilters;
	}

	public boolean isPublicAccess(JcrSession session, JcrPath absPath) {
		for (Map<String, Object> e : getAccessControlFilters()) {
			AdaptableMap<String, Object> filter = AdaptableMap.<String, Object>newBuilder().putAll(e).build();

			String type = filter.getString("type").trim();
			if (!type.equals("publicAccess")) {
				continue;
			}

			String path = filter.getString("path").trim();
			if (path.endsWith("*")) {
				if (!absPath.toString().startsWith(path.substring(0, path.length() - 1))) {
					continue;
				}
			} else if (path.startsWith("*")) {
				if (!absPath.toString().endsWith(path.substring(1))) {
					continue;
				}
			} else {
				if (!absPath.toString().equals(path)) {
					continue;
				}
			}

			String user = filter.getString("user").trim();
			if (Strings.isNotEmpty(user)) {
				if (!session.getUserID().equals(user)) {
					continue;
				}
			}

			return true;
		}
		return false;
	}

}

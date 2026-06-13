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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.jcr.Credentials;
import javax.jcr.RepositoryException;

import org.mintjams.jcr.security.ServiceCredentials;
import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.osgi.Properties;
import org.osgi.framework.BundleContext;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class JcrRepositoryConfiguration implements Adaptable {

	private final Properties fProperties;
	private Map<String, Object> fConfig;

	private JcrRepositoryConfiguration(Properties properties) {
		fProperties = properties;
	}

	public static JcrRepositoryConfiguration create(Properties properties) {
		return new JcrRepositoryConfiguration(properties);
	}

	public JcrRepositoryConfiguration load() throws IOException {
		Path etcPath = getEtcPath();
		if (!Files.exists(etcPath)) {
			Files.createDirectories(etcPath);
		}
		Path repositoryPath = etcPath.resolve("repository.yml");
		if (!Files.exists(repositoryPath)) {
			try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(repositoryPath))) {
				try (InputStream in = getClass().getResourceAsStream("repository.yml")) {
					IOs.copy(in, out);
				}
			}
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(repositoryPath))) {
			fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
		}

		return this;
	}

	public Path getRepositoryPath() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		return Path.of(Strings.defaultIfEmpty(bc.getProperty("org.mintjams.jcr.repository.rootdir"), "repository")).normalize();
	}

	public Path getWorkspaceRootPath() {
		return getRepositoryPath().resolve("workspaces").normalize();
	}

	public Path getSystemWorkspacePath() {
		return getWorkspaceRootPath().resolve(JcrWorkspaceProvider.SYSTEM_WORKSPACE_NAME).normalize();
	}

	public Path getEtcPath() {
		return getRepositoryPath().resolve("etc").normalize();
	}

	/**
	 * Returns this node's temporary directory. The directory is wiped on
	 * startup and must therefore never be shared between nodes: it can be
	 * redirected per node via the framework property
	 * {@code org.mintjams.jcr.repository.tmpdir} (e.g. to fast local disk),
	 * and in cluster mode — where the repository directory itself usually
	 * lives on shared storage — it defaults to a per-node subdirectory.
	 */
	public Path getTmpPath() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		String configured = bc.getProperty("org.mintjams.jcr.repository.tmpdir");
		if (Strings.isNotEmpty(configured)) {
			return Path.of(configured.trim()).normalize();
		}
		if (isClusterEnabled()) {
			return getRepositoryPath().resolve("tmp/nodes").resolve(getClusterNodeId()).normalize();
		}
		return getRepositoryPath().resolve("tmp").normalize();
	}

	/**
	 * Returns the workspace used when a login does not specify one. Resolved
	 * from the framework property {@code org.mintjams.jcr.workspace.default}
	 * first, then {@code repository.yml#defaultWorkspace}; the system
	 * workspace is the default so existing deployments keep behaving exactly
	 * as before this property was introduced. Once content lives in its own
	 * workspaces, point this at the content workspace so unqualified logins
	 * no longer land in the identity store.
	 */
	public String getDefaultWorkspaceName() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		String value = bc.getProperty("org.mintjams.jcr.workspace.default");
		if (Strings.isNotEmpty(value)) {
			return value.trim();
		}

		try {
			String configured = ExpressionContext.create().setVariable("config", fConfig)
					.defaultString("config.defaultWorkspace", null);
			if (Strings.isNotEmpty(configured)) {
				return configured.trim();
			}
		} catch (Throwable ignore) {}
		return JcrWorkspaceProvider.SYSTEM_WORKSPACE_NAME;
	}

	/**
	 * Returns the interval, in seconds, at which this node rescans the
	 * workspace root for workspaces created or deleted by other cluster
	 * nodes ({@code org.mintjams.jcr.workspace.discoveryInterval}). Only
	 * used in cluster mode; standalone nodes manage workspaces locally and
	 * need no discovery.
	 */
	public int getWorkspaceDiscoveryInterval() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		int value = Integer.parseInt(Strings.defaultIfEmpty(
				bc.getProperty("org.mintjams.jcr.workspace.discoveryInterval"), "30"));
		if (value < 5) {
			value = 5;
		}
		return value;
	}

	public int getMaxSessions() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		int value = Integer.parseInt(Strings.defaultIfEmpty(bc.getProperty("org.mintjams.jcr.workspace.maxSessions"), "32"));
		if (value < 8) {
			value = 8;
		}
		return value;
	}

	/**
	 * Returns the target size of the workspace database page cache, in
	 * <strong>megabytes</strong> ({@code org.mintjams.jcr.workspace.cacheSizeMB};
	 * default 256).
	 * <p>
	 * Operators configure the cache in MB; each backend boundary converts to
	 * the unit it expects (for example H2's {@code CACHE_SIZE}, which is in KB).
	 * Keeping the operator-facing unit fixed at MB avoids the byte/KB confusion
	 * that previously sized the cache ~1000x too large and exhausted the heap.
	 * <p>
	 * For backward compatibility the deprecated byte-valued property
	 * {@code org.mintjams.jcr.workspace.cacheSize} is still honoured when the MB
	 * property is not set; its value is converted from bytes to MB and a warning
	 * is logged. The MB property takes precedence when both are set.
	 */
	public int getCacheSizeMB() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		int value;
		String configured = bc.getProperty("org.mintjams.jcr.workspace.cacheSizeMB");
		if (Strings.isNotEmpty(configured)) {
			value = Integer.parseInt(configured.trim());
		} else {
			String legacyBytes = bc.getProperty("org.mintjams.jcr.workspace.cacheSize");
			if (Strings.isNotEmpty(legacyBytes)) {
				value = (int) Math.min(Long.parseLong(legacyBytes.trim()) / (1024L * 1024L), Integer.MAX_VALUE);
				Activator.getDefault().getLogger(getClass()).warn(
						"Property 'org.mintjams.jcr.workspace.cacheSize' (bytes) is deprecated; "
						+ "use 'org.mintjams.jcr.workspace.cacheSizeMB' (megabytes) instead.");
			} else {
				value = 256;
			}
		}
		// Clamp to a sane floor so caching is never effectively disabled.
		if (value < 1) {
			value = 1;
		}
		return value;
	}

	public int getNodeCacheSize() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		int cacheSize = Integer.parseInt(Strings.defaultIfEmpty(bc.getProperty("org.mintjams.jcr.session.nodeCacheSize"), "64"));
		int value = cacheSize;
		try {
			value = BigDecimal.valueOf(Runtime.getRuntime().freeMemory())
					.multiply(BigDecimal.valueOf(0.2))
					.divide(BigDecimal.valueOf(1048576)).intValue();
		} catch (Throwable ignore) {}
		if (value < 8) {
			value = 8;
		}
		if (value > cacheSize) {
			value = cacheSize;
		}
		return value;
	}

	public int getWorkspaceNodeCacheSize() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		int value = Integer.parseInt(
				Strings.defaultIfEmpty(bc.getProperty("org.mintjams.jcr.workspace.nodeCacheSize"), "8192"));
		if (value < 64) {
			value = 64;
		}
		return value;
	}

	public int getAccessControlStoreWarningThreshold() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		return Integer.parseInt(Strings.defaultIfEmpty(
				bc.getProperty("org.mintjams.jcr.workspace.accessControlStoreWarningThreshold"), "100000"));
	}

	/**
	 * Returns whether this repository runs as part of a cluster of nodes
	 * sharing the same workspace databases and blob storage. Resolved from
	 * the framework property {@code org.mintjams.jcr.cluster.enabled} first,
	 * then {@code repository.yml#cluster.enabled}; standalone is the default.
	 */
	public boolean isClusterEnabled() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		String value = bc.getProperty("org.mintjams.jcr.cluster.enabled");
		if (Strings.isNotEmpty(value)) {
			return Boolean.parseBoolean(value.trim());
		}

		try {
			return ExpressionContext.create().setVariable("config", fConfig).getBoolean("config.cluster.enabled", false);
		} catch (Throwable ignore) {}
		return false;
	}

	private String fClusterNodeId;

	/**
	 * Returns the identifier under which this node appears in the cluster.
	 * Resolved from the framework property
	 * {@code org.mintjams.jcr.cluster.nodeId}, the environment variable
	 * {@code CMS_CLUSTER_NODE_ID}, or {@code repository.yml#cluster.nodeId};
	 * when none of these is set, the host name is used, with a random
	 * identifier as the last resort. The identifier must be unique per node;
	 * give every node an explicit identifier when host names are not unique.
	 */
	public synchronized String getClusterNodeId() {
		if (fClusterNodeId != null) {
			return fClusterNodeId;
		}

		BundleContext bc = Activator.getDefault().getBundleContext();
		String value = bc.getProperty("org.mintjams.jcr.cluster.nodeId");
		if (Strings.isEmpty(value)) {
			try {
				value = System.getenv("CMS_CLUSTER_NODE_ID");
			} catch (Throwable ignore) {}
		}
		if (Strings.isEmpty(value)) {
			try {
				value = ExpressionContext.create().setVariable("config", fConfig).defaultString("config.cluster.nodeId", null);
			} catch (Throwable ignore) {}
		}
		if (Strings.isEmpty(value)) {
			try {
				value = java.net.InetAddress.getLocalHost().getHostName();
			} catch (Throwable ignore) {}
		}
		if (Strings.isEmpty(value)) {
			value = java.util.UUID.randomUUID().toString();
		}

		fClusterNodeId = value.trim();
		return fClusterNodeId;
	}

	public void validate() {
		Path repositoryPath = getRepositoryPath();
		if (Files.exists(repositoryPath)) {
			if (!Files.isDirectory(repositoryPath)) {
				throw new IllegalStateException("Path is not a directory: " + repositoryPath.toString());
			}
		}
	}

	public JcrRepository createRepository() throws IOException {
		return JcrRepository.create(this);
	}

	private List<String> fServiceCredentials = null;
	@SuppressWarnings("unchecked")
	public boolean isServiceCredentials(Credentials credentials) throws RepositoryException {
		if (!(credentials instanceof ServiceCredentials)) {
			return false;
		}

		try {
			if (fServiceCredentials == null) {
				ExpressionContext el = ExpressionContext.create().setVariable("config", fConfig);
				fServiceCredentials = (List<String>) el.evaluate("config.security.serviceCredentials");
				if (fServiceCredentials == null) {
					Activator.getDefault().getLogger(getClass())
							.warn("repository.yml has no security.serviceCredentials entries;"
									+ " all service logins will be rejected.");
					fServiceCredentials = Collections.emptyList();
				}
			}

			for (String name : fServiceCredentials) {
				if (Strings.isEmpty(name)) {
					continue;
				}
				name = name.trim();
				if (name.equals("*")) {
					return true;
				}
				if (name.endsWith("*") && credentials.getClass().getName().startsWith(name.substring(0, name.length() - 1))) {
					return true;
				}
				if (credentials.getClass().getName().equals(name)) {
					return true;
				}
			}
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
		return false;
	}

	private List<String> fPrincipalProviderServices = null;
	@SuppressWarnings("unchecked")
	public List<String> getPrincipalProviderServices() throws RepositoryException {
		try {
			if (fPrincipalProviderServices == null) {
				ExpressionContext el = ExpressionContext.create().setVariable("config", fConfig);
				fPrincipalProviderServices = (List<String>) el.evaluate("config.security.principalProviders");
				if (fPrincipalProviderServices == null) {
					fPrincipalProviderServices = Collections.emptyList();
				}
			}
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
		return Collections.unmodifiableList(fPrincipalProviderServices);
	}

	private List<String> fIdentityProviderServices = null;
	@SuppressWarnings("unchecked")
	public List<String> getIdentityProviderServices() throws RepositoryException {
		try {
			if (fIdentityProviderServices == null) {
				ExpressionContext el = ExpressionContext.create().setVariable("config", fConfig);
				fIdentityProviderServices = (List<String>) el.evaluate("config.security.identityProviders");
				if (fIdentityProviderServices == null) {
					fIdentityProviderServices = Collections.emptyList();
				}
			}
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
		return Collections.unmodifiableList(fIdentityProviderServices);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return null;
	}

}

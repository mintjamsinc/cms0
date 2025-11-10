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

	public Path getTmpPath() {
		return getRepositoryPath().resolve("tmp").normalize();
	}

	public int getMaxSessions() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		int value = Integer.parseInt(Strings.defaultIfEmpty(bc.getProperty("org.mintjams.jcr.workspace.maxSessions"), "32"));
		if (value < 8) {
			value = 8;
		}
		return value;
	}

	public int getCacheSize() {
		BundleContext bc = Activator.getDefault().getBundleContext();
		return Integer.parseInt(Strings.defaultIfEmpty(bc.getProperty("org.mintjams.jcr.workspace.cacheSize"), "268435456"));
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
			}
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
		return Collections.unmodifiableList(fPrincipalProviderServices);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return null;
	}

}

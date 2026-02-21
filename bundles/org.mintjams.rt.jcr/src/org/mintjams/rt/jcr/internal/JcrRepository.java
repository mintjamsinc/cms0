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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileTypeDetector;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.jcr.Credentials;
import javax.jcr.GuestCredentials;
import javax.jcr.LoginException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.query.Query;

import org.mintjams.jcr.Repository;
import org.mintjams.jcr.security.AuthenticatedCredentials;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.GuestPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.PrincipalProvider;
import org.mintjams.jcr.security.ServiceCredentials;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.spi.security.JcrAuthenticator;
import org.mintjams.rt.jcr.internal.security.ServicePrincipal;
import org.mintjams.rt.jcr.internal.security.SystemCredentials;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.osgi.BundleLocalization;

public class JcrRepository implements Repository, Closeable, Adaptable {

	@SuppressWarnings("deprecation")
	private static final Collection<String> STANDARD_DESCRIPTOR_KEYS = Collections.unmodifiableCollection(Arrays.asList(
			new String[] { Repository.SPEC_VERSION_DESC, Repository.SPEC_NAME_DESC, Repository.REP_VENDOR_DESC,
					Repository.REP_VENDOR_URL_DESC, Repository.REP_NAME_DESC, Repository.REP_VERSION_DESC,
					Repository.WRITE_SUPPORTED, Repository.IDENTIFIER_STABILITY, Repository.OPTION_XML_IMPORT_SUPPORTED,
					Repository.OPTION_UNFILED_CONTENT_SUPPORTED, Repository.OPTION_SIMPLE_VERSIONING_SUPPORTED,
					Repository.OPTION_ACTIVITIES_SUPPORTED, Repository.OPTION_BASELINES_SUPPORTED,
					Repository.OPTION_ACCESS_CONTROL_SUPPORTED, Repository.OPTION_LOCKING_SUPPORTED,
					Repository.OPTION_OBSERVATION_SUPPORTED, Repository.OPTION_JOURNALED_OBSERVATION_SUPPORTED,
					Repository.OPTION_RETENTION_SUPPORTED, Repository.OPTION_LIFECYCLE_SUPPORTED,
					Repository.OPTION_TRANSACTIONS_SUPPORTED, Repository.OPTION_WORKSPACE_MANAGEMENT_SUPPORTED,
					Repository.OPTION_NODE_AND_PROPERTY_WITH_SAME_NAME_SUPPORTED,
					Repository.OPTION_UPDATE_PRIMARY_NODE_TYPE_SUPPORTED,
					Repository.OPTION_UPDATE_MIXIN_NODE_TYPES_SUPPORTED, Repository.OPTION_SHAREABLE_NODES_SUPPORTED,
					Repository.OPTION_NODE_TYPE_MANAGEMENT_SUPPORTED, Repository.NODE_TYPE_MANAGEMENT_INHERITANCE,
					Repository.NODE_TYPE_MANAGEMENT_OVERRIDES_SUPPORTED,
					Repository.NODE_TYPE_MANAGEMENT_PRIMARY_ITEM_NAME_SUPPORTED,
					Repository.NODE_TYPE_MANAGEMENT_ORDERABLE_CHILD_NODES_SUPPORTED,
					Repository.NODE_TYPE_MANAGEMENT_RESIDUAL_DEFINITIONS_SUPPORTED,
					Repository.NODE_TYPE_MANAGEMENT_AUTOCREATED_DEFINITIONS_SUPPORTED,
					Repository.NODE_TYPE_MANAGEMENT_SAME_NAME_SIBLINGS_SUPPORTED,
					Repository.NODE_TYPE_MANAGEMENT_PROPERTY_TYPES,
					Repository.NODE_TYPE_MANAGEMENT_MULTIVALUED_PROPERTIES_SUPPORTED,
					Repository.NODE_TYPE_MANAGEMENT_MULTIPLE_BINARY_PROPERTIES_SUPPORTED,
					Repository.NODE_TYPE_MANAGEMENT_VALUE_CONSTRAINTS_SUPPORTED,
					Repository.NODE_TYPE_MANAGEMENT_UPDATE_IN_USE_SUPORTED, Repository.QUERY_LANGUAGES,
					Repository.QUERY_STORED_QUERIES_SUPPORTED, Repository.QUERY_FULL_TEXT_SEARCH_SUPPORTED,
					Repository.QUERY_JOINS, Repository.LEVEL_1_SUPPORTED, Repository.LEVEL_2_SUPPORTED,
					Repository.OPTION_QUERY_SQL_SUPPORTED, Repository.QUERY_XPATH_POS_INDEX,
					Repository.QUERY_XPATH_DOC_ORDER, Repository.OPTION_XML_EXPORT_SUPPORTED,
					Repository.OPTION_VERSIONING_SUPPORTED }));

	private static final Collection<String> INTERNAL_PRINCIPAL_NAMES = Collections.unmodifiableCollection(
			Arrays.asList(new String[] { "system", SystemPrincipal.INTERNAL_NAME, GuestPrincipal.NAME }));

	private final JcrRepositoryConfiguration fConfiguration;
	private final Closer fCloser = Closer.create();
	private final Map<String, Object> fDescriptors = new HashMap<>();
	private final JcrValueFactory fValueFactory;
	private MimeTypeDetector fMimeTypeDetector;
	private final Map<String, JcrWorkspaceProvider> fWorkspaceProviders = new HashMap<>();
	private boolean fLive = false;
	private PrincipalProviderImpl fPrincipalProvider = new PrincipalProviderImpl();

	private JcrRepository(JcrRepositoryConfiguration configuration) throws IOException {
		fConfiguration = configuration;

		fConfiguration.validate();

		fValueFactory = JcrValueFactory.create();
		prepare();
	}

	public static JcrRepository create(JcrRepositoryConfiguration configuration) throws IOException {
		return new JcrRepository(configuration);
	}

	private void prepare() throws IOException {
		prepareRepositoryDescriptors();

		IOs.deleteIfExists(fConfiguration.getTmpPath());

		for (Path path : new Path[] {
				fConfiguration.getEtcPath(),
				fConfiguration.getTmpPath(),
				fConfiguration.getWorkspaceRootPath()
		}) {
			if (!Files.exists(path)) {
				Files.createDirectories(path);
			}
		}

		prepareWorkspaces();
	}

	@SuppressWarnings("deprecation")
	private void prepareRepositoryDescriptors() {
		BundleLocalization localization = BundleLocalization.create(Activator.getDefault().getBundleContext().getBundle());

		// Repository Information
		fDescriptors.put(Repository.SPEC_VERSION_DESC, fValueFactory.createValue(localization.getString(Repository.SPEC_VERSION_DESC)));
		fDescriptors.put(Repository.SPEC_NAME_DESC, fValueFactory.createValue(localization.getString(Repository.SPEC_NAME_DESC)));
		fDescriptors.put(Repository.REP_VENDOR_DESC, fValueFactory.createValue(localization.getVendor()));
		fDescriptors.put(Repository.REP_VENDOR_URL_DESC, fValueFactory.createValue(localization.getString(Repository.REP_VENDOR_URL_DESC)));
		fDescriptors.put(Repository.REP_NAME_DESC, fValueFactory.createValue(localization.getString(Repository.REP_NAME_DESC)));
		fDescriptors.put(Repository.REP_VERSION_DESC, fValueFactory.createValue(localization.getString(Repository.REP_VERSION_DESC)));
		// General
		fDescriptors.put(Repository.WRITE_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.IDENTIFIER_STABILITY, fValueFactory.createValue(Repository.IDENTIFIER_STABILITY_INDEFINITE_DURATION));
		fDescriptors.put(Repository.OPTION_XML_IMPORT_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_UNFILED_CONTENT_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_SIMPLE_VERSIONING_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_ACTIVITIES_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_BASELINES_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_ACCESS_CONTROL_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.OPTION_LOCKING_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.OPTION_OBSERVATION_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_JOURNALED_OBSERVATION_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_RETENTION_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_LIFECYCLE_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_TRANSACTIONS_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_WORKSPACE_MANAGEMENT_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_NODE_AND_PROPERTY_WITH_SAME_NAME_SUPPORTED, fValueFactory.createValue(false));
		// Node Operations
		fDescriptors.put(Repository.OPTION_UPDATE_PRIMARY_NODE_TYPE_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_UPDATE_MIXIN_NODE_TYPES_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.OPTION_SHAREABLE_NODES_SUPPORTED, fValueFactory.createValue(false));
		// Node Type Management
		fDescriptors.put(Repository.OPTION_NODE_TYPE_MANAGEMENT_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_INHERITANCE, fValueFactory.createValue(Repository.NODE_TYPE_MANAGEMENT_INHERITANCE_MULTIPLE));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_OVERRIDES_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_PRIMARY_ITEM_NAME_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_ORDERABLE_CHILD_NODES_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_RESIDUAL_DEFINITIONS_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_AUTOCREATED_DEFINITIONS_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_SAME_NAME_SIBLINGS_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_PROPERTY_TYPES, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_MULTIVALUED_PROPERTIES_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_MULTIPLE_BINARY_PROPERTIES_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_VALUE_CONSTRAINTS_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.NODE_TYPE_MANAGEMENT_UPDATE_IN_USE_SUPORTED, fValueFactory.createValue(true));
		// Query
		fDescriptors.put(Repository.QUERY_LANGUAGES, new Value[] { fValueFactory.createValue(Query.XPATH) });
		fDescriptors.put(Repository.QUERY_STORED_QUERIES_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.QUERY_FULL_TEXT_SEARCH_SUPPORTED, fValueFactory.createValue(true));
		fDescriptors.put(Repository.QUERY_JOINS, fValueFactory.createValue(Repository.QUERY_JOINS_NONE));
		// Deprecated Descriptors
		fDescriptors.put(Repository.LEVEL_1_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.LEVEL_2_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_QUERY_SQL_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.QUERY_XPATH_POS_INDEX, fValueFactory.createValue(false));
		fDescriptors.put(Repository.QUERY_XPATH_DOC_ORDER, fValueFactory.createValue(false));
		// Other
		fDescriptors.put(Repository.OPTION_XML_EXPORT_SUPPORTED, fValueFactory.createValue(false));
		fDescriptors.put(Repository.OPTION_VERSIONING_SUPPORTED, fValueFactory.createValue(true));

		// Extended
		fDescriptors.put(Repository.REPOSITORY_PATH, fValueFactory.createValue(fConfiguration.getRepositoryPath().toAbsolutePath().toString()));
	}

	private void prepareWorkspaces() throws IOException {
		prepareSystemWorkspace();

		try (Stream<Path> stream = Files.list(fConfiguration.getWorkspaceRootPath())) {
			stream.forEach(path -> {
				if (!Files.isDirectory(path)) {
					return;
				}

				JcrWorkspaceProvider workspaceProvider = JcrWorkspaceProvider.create(path.getFileName().toString(), this);
				fWorkspaceProviders.put(workspaceProvider.getWorkspaceName(), workspaceProvider);
			});
		}
	}

	private void prepareSystemWorkspace() throws IOException {
		Path systemWorkspacePath = fConfiguration.getSystemWorkspacePath();
		if (Files.exists(systemWorkspacePath)) {
			return;
		}

		Files.createDirectories(systemWorkspacePath);
	}

	public synchronized JcrRepository open() throws IOException {
		fMimeTypeDetector = fCloser.register(MimeTypeDetector.create(this));
		fMimeTypeDetector.open();

		fCloser.register(fWorkspaceProviders.get(JcrWorkspaceProvider.SYSTEM_WORKSPACE_NAME)).open();

		for (JcrWorkspaceProvider workspaceProvider : fWorkspaceProviders.values()) {
			if (JcrWorkspaceProvider.SYSTEM_WORKSPACE_NAME.equals(workspaceProvider.getWorkspaceName())) {
				continue;
			}

			try {
				fCloser.register(workspaceProvider).open();
			} catch (Throwable ex) {
				Activator.getDefault().getLogger(getClass()).warn("An error occurred during the start of the workspace: " + workspaceProvider.getWorkspaceName(), ex);
			}
		}

		fLive = true;
		return this;
	}

	@Override
	public synchronized void close() throws IOException {
		fLive = false;
		fCloser.close();
	}

	public boolean isLive() {
		return fLive;
	}

	@Override
	public String getDescriptor(String key) {
		Value v = getDescriptorValue(key);
		if (v == null) {
			return null;
		}
		try {
			return v.getString();
		} catch (RepositoryException ex) {
			throw (IllegalStateException) new IllegalStateException(ex.getMessage()).initCause(ex);
		}
	}

	@Override
	public String[] getDescriptorKeys() {
		return fDescriptors.keySet().toArray(String[]::new);
	}

	@Override
	public Value getDescriptorValue(String key) {
		if (key == null || !fDescriptors.containsKey(key)) {
			return null;
		}
		Object v = fDescriptors.get(key);
		if (v != null && v.getClass().isArray()) {
			return null;
		}
		return (Value) v;
	}

	@Override
	public Value[] getDescriptorValues(String key) {
		if (key == null || !fDescriptors.containsKey(key)) {
			return null;
		}
		Object v = fDescriptors.get(key);
		if (v == null || !v.getClass().isArray()) {
			return null;
		}
		return (Value[]) v;
	}

	@Override
	public boolean isSingleValueDescriptor(String key) {
		if (key == null || !fDescriptors.containsKey(key)) {
			return false;
		}
		Object v = fDescriptors.get(key);
		return (v == null || !v.getClass().isArray());
	}

	@Override
	public boolean isStandardDescriptor(String key) {
		return STANDARD_DESCRIPTOR_KEYS.contains(key);
	}

	@Override
	public Session login() throws LoginException, RepositoryException {
		return login(null, null);
	}

	@Override
	public Session login(Credentials credentials) throws LoginException, RepositoryException {
		return login(credentials, null);
	}

	@Override
	public Session login(String workspaceName) throws LoginException, NoSuchWorkspaceException, RepositoryException {
		return login(null, workspaceName);
	}

	@Override
	public Session login(Credentials credentials, String workspaceName) throws LoginException, NoSuchWorkspaceException, RepositoryException {
		workspaceName = Strings.defaultIfEmpty(workspaceName, JcrWorkspaceProvider.SYSTEM_WORKSPACE_NAME);
		JcrWorkspaceProvider workspaceProvider = fWorkspaceProviders.get(workspaceName);
		if (workspaceProvider == null) {
			throw new NoSuchWorkspaceException("Invalid workspace name: " + workspaceName);
		}

		UserPrincipal principal = null;
		if (credentials instanceof SystemCredentials) {
			principal = new SystemPrincipal();
		} else if (credentials instanceof AuthenticatedCredentials) {
			principal = ((AuthenticatedCredentials) credentials).getUserPrincipal();
		} else if (getConfiguration().isServiceCredentials(credentials)) {
			ServiceCredentials creds = (ServiceCredentials) credentials;
			principal = new ServicePrincipal(Strings.defaultIfEmpty(creds.getUserID(), "service"));
		} else if (credentials instanceof GuestCredentials) {
			principal = new GuestPrincipal();
		} else {
			for (JcrAuthenticator authenticator : Activator.getDefault().getAuthenticators()) {
				if (!authenticator.canAuthenticate(credentials)) {
					continue;
				}

				try {
					principal = authenticator.authenticate(credentials).getUserPrincipal();
					break;
				} catch (javax.security.auth.login.LoginException ex) {
					throw Cause.create(ex).wrap(LoginException.class);
				}
			}
			if (principal != null && (principal instanceof SystemPrincipal || principal instanceof ServicePrincipal
					|| principal instanceof GuestPrincipal || INTERNAL_PRINCIPAL_NAMES.contains(principal.getName()))) {
				throw new LoginException("Invalid credentials.");
			}
		}
		if (principal == null || principal instanceof GroupPrincipal) {
			throw new LoginException("Invalid credentials.");
		}

		return workspaceProvider.createSession(principal).getSession();
	}

	@Override
	public String[] getAvailableWorkspaceNames() {
		List<String> l = new ArrayList<>();
		for (JcrWorkspaceProvider e : fWorkspaceProviders.values()) {
			if (e.isLive()) {
				l.add(e.getWorkspaceName());
			}
		}
		return l.toArray(String[]::new);
	}

	@Override
	public PrincipalProvider getPrincipalProvider() {
		return fPrincipalProvider;
	}

	public JcrRepositoryConfiguration getConfiguration() {
		return fConfiguration;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(ValueFactory.class) || adapterType.equals(JcrValueFactory.class)) {
			return (AdapterType) fValueFactory;
		}

		if (adapterType.equals(FileTypeDetector.class) || adapterType.equals(MimeTypeDetector.class)) {
			return (AdapterType) fMimeTypeDetector;
		}

		if (adapterType.equals(PrincipalProvider.class)) {
			return (AdapterType) fPrincipalProvider;
		}

		return Adaptables.getAdapter(fConfiguration, adapterType);
	}

	private class PrincipalProviderImpl implements PrincipalProvider {
		@Override
		public Principal getPrincipal(String name) throws PrincipalNotFoundException, RepositoryException {
			return Activator.getDefault().getPrincipal(name);
		}

		@Override
		public UserPrincipal getUserPrincipal(String name) throws PrincipalNotFoundException, RepositoryException {
			Principal p = getPrincipal(name);
			if (p instanceof UserPrincipal) {
				return (UserPrincipal) p;
			}

			throw new PrincipalNotFoundException(name);
		}

		@Override
		public GroupPrincipal getGroupPrincipal(String name) throws PrincipalNotFoundException, RepositoryException {
			Principal p = getPrincipal(name);
			if (p instanceof GroupPrincipal) {
				return (GroupPrincipal) p;
			}

			throw new PrincipalNotFoundException(name);
		}
	}

}

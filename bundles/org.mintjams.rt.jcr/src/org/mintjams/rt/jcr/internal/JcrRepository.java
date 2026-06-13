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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileTypeDetector;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
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
import org.mintjams.jcr.security.Group;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.GuestPrincipal;
import org.mintjams.jcr.security.IdentityProvider;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.PrincipalProvider;
import org.mintjams.jcr.security.Role;
import org.mintjams.jcr.security.ServiceCredentials;
import org.mintjams.jcr.security.User;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.spi.security.Authenticator;
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

	/**
	 * Valid workspace names. Workspace names appear in URLs
	 * ({@code /bin/graphql.cgi/<workspace>}) and as directory names on every
	 * supported filesystem, so they are restricted to lowercase letters,
	 * digits, hyphens and underscores, starting with a letter.
	 */
	private static final Pattern WORKSPACE_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");

	/** How many times to retry removing a workspace directory before giving up. */
	private static final int DELETE_RETRY_ATTEMPTS = 3;
	/** Base backoff between directory-removal retries; grows linearly per attempt. */
	private static final long DELETE_RETRY_BACKOFF_MILLIS = 200L;

	private final JcrRepositoryConfiguration fConfiguration;
	private final Closer fCloser = Closer.create();
	private final Map<String, Object> fDescriptors = new HashMap<>();
	private final JcrValueFactory fValueFactory;
	private MimeTypeDetector fMimeTypeDetector;
	private final Map<String, JcrWorkspaceProvider> fWorkspaceProviders = new ConcurrentHashMap<>();
	private final Object fWorkspaceManagementLock = new Object();
	private WorkspaceDiscoverer fWorkspaceDiscoverer;
	private boolean fLive = false;
	private PrincipalProviderImpl fPrincipalProvider = new PrincipalProviderImpl();
	private IdentityProviderImpl fIdentityProvider = new IdentityProviderImpl();

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
		fDescriptors.put(Repository.OPTION_WORKSPACE_MANAGEMENT_SUPPORTED, fValueFactory.createValue(true));
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
		fDescriptors.put(Repository.REPOSITORY_TMP_PATH, fValueFactory.createValue(fConfiguration.getTmpPath().toAbsolutePath().toString()));
	}

	private void prepareWorkspaces() throws IOException {
		prepareSystemWorkspace();

		try (Stream<Path> stream = Files.list(fConfiguration.getWorkspaceRootPath())) {
			stream.forEach(path -> {
				if (!Files.isDirectory(path)) {
					return;
				}
				if (path.getFileName().toString().startsWith(".")) {
					// Dot-directories are workspaces still being staged (or
					// leftovers of an interrupted creation), never live ones.
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

		if (fConfiguration.isClusterEnabled()) {
			// Workspaces created or deleted on another cluster node appear on
			// the shared storage; events only reach local listeners, so each
			// node keeps itself in sync by rescanning the workspace root.
			fWorkspaceDiscoverer = fCloser.register(new WorkspaceDiscoverer());
			fWorkspaceDiscoverer.open();
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
		workspaceName = Strings.defaultIfEmpty(workspaceName, fConfiguration.getDefaultWorkspaceName());
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
			for (Authenticator authenticator : Activator.getDefault().getAuthenticators()) {
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

	/**
	 * Creates and starts a new workspace. The workspace directory is staged
	 * under a dot-prefixed name and moved into place only when fully
	 * populated, so a concurrent rescan (or a crash) never sees a
	 * half-created workspace. When {@code <repository>/etc/workspace-template}
	 * exists, its contents seed the new workspace directory — this is how
	 * operators supply per-workspace configuration that must be present
	 * before first start (e.g. a shared {@code etc/jcr/jcr.yml} datasource
	 * in a clustered deployment) and initial provisioning or deploy content.
	 */
	public void createWorkspace(String workspaceName) throws RepositoryException {
		if (!fLive) {
			throw new RepositoryException("The repository is not available.");
		}
		if (workspaceName == null || !WORKSPACE_NAME_PATTERN.matcher(workspaceName).matches()) {
			throw new RepositoryException("Invalid workspace name: " + workspaceName
					+ " (lowercase letters, digits, hyphens and underscores, starting with a letter)");
		}

		synchronized (fWorkspaceManagementLock) {
			Path workspacePath = fConfiguration.getWorkspaceRootPath().resolve(workspaceName).normalize();
			if (fWorkspaceProviders.containsKey(workspaceName)) {
				throw new RepositoryException("Workspace already exists: " + workspaceName);
			}
			if (Files.exists(workspacePath)) {
				// A directory with no live provider is debris from a previous
				// delete that could not remove every file (e.g. a handle was
				// still held while the workspace was shutting down). Creating
				// and deleting workspaces is expected to be repeatable, so
				// clear the leftovers before staging the new one rather than
				// refusing with a confusing "already exists".
				Activator.getDefault().getLogger(getClass())
						.warn("Removing leftover workspace directory before recreating: " + workspaceName);
				deleteWorkspaceDirectory(workspacePath);
				if (Files.exists(workspacePath)) {
					throw new RepositoryException(
							"A leftover directory for workspace '" + workspaceName + "' could not be removed.");
				}
			}

			Path stagingPath = fConfiguration.getWorkspaceRootPath()
					.resolve(".creating-" + UUID.randomUUID()).normalize();
			try {
				Files.createDirectories(stagingPath);
				copyWorkspaceTemplate(stagingPath);
				Files.move(stagingPath, workspacePath, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException ex) {
				try {
					IOs.deleteIfExists(stagingPath);
				} catch (Throwable ignore) {}
				throw Cause.create(ex).wrap(RepositoryException.class);
			}

			JcrWorkspaceProvider workspaceProvider = JcrWorkspaceProvider.create(workspaceName, this);
			try {
				fCloser.register(workspaceProvider).open();
			} catch (Throwable ex) {
				try {
					fCloser.unregister(workspaceProvider);
					workspaceProvider.close();
				} catch (Throwable ignore) {}
				try {
					IOs.deleteIfExists(workspacePath);
				} catch (Throwable ignore) {}
				throw Cause.create(ex).wrap(RepositoryException.class);
			}
			fWorkspaceProviders.put(workspaceName, workspaceProvider);

			Activator.getDefault().getLogger(getClass()).info("JCR workspace '" + workspaceName + "' has been created.");
			postWorkspaceEvent("CREATED", workspaceName);
		}
	}

	/**
	 * Stops and deletes a workspace, including its directory and everything
	 * in it. The system workspace — the repository's identity store — can
	 * never be deleted. Open sessions on the workspace are invalidated.
	 */
	public void deleteWorkspace(String workspaceName) throws NoSuchWorkspaceException, RepositoryException {
		if (!fLive) {
			throw new RepositoryException("The repository is not available.");
		}
		if (JcrWorkspaceProvider.SYSTEM_WORKSPACE_NAME.equals(workspaceName)) {
			throw new RepositoryException("The system workspace cannot be deleted.");
		}

		synchronized (fWorkspaceManagementLock) {
			JcrWorkspaceProvider workspaceProvider = fWorkspaceProviders.remove(workspaceName);
			if (workspaceProvider == null) {
				throw new NoSuchWorkspaceException("Invalid workspace name: " + workspaceName);
			}

			try {
				fCloser.unregister(workspaceProvider);
			} catch (Throwable ignore) {}
			try {
				workspaceProvider.close();
			} catch (Throwable ex) {
				Activator.getDefault().getLogger(getClass())
						.warn("An error occurred while stopping the workspace: " + workspaceName, ex);
			}
			deleteWorkspaceDirectory(workspaceProvider.getWorkspacePath());
			if (Files.exists(workspaceProvider.getWorkspacePath())) {
				throw new RepositoryException(
						"The workspace directory for '" + workspaceName + "' could not be fully removed.");
			}

			Activator.getDefault().getLogger(getClass()).info("JCR workspace '" + workspaceName + "' has been deleted.");
			postWorkspaceEvent("DELETED", workspaceName);
		}
	}

	/**
	 * Removes a workspace directory and everything under it, best-effort and
	 * resiliently. A workspace that has just been stopped may still have a
	 * handle being released by a background flush (search index, datasource),
	 * so a single pass can leave a file or two behind; we retry a few times
	 * with a short backoff before giving up. The caller verifies the directory
	 * is actually gone and reports failure, so leftovers never masquerade as a
	 * clean delete — and a subsequent create clears whatever remained.
	 */
	private void deleteWorkspaceDirectory(Path workspacePath) throws RepositoryException {
		if (workspacePath == null) {
			return;
		}

		IOException last = null;
		for (int attempt = 0; attempt < DELETE_RETRY_ATTEMPTS; attempt++) {
			try {
				IOs.deleteIfExists(workspacePath);
				if (!Files.exists(workspacePath)) {
					return;
				}
				last = new IOException("The directory still exists after deletion: " + workspacePath);
			} catch (IOException ex) {
				last = ex;
			}

			if (attempt < DELETE_RETRY_ATTEMPTS - 1) {
				Activator.getDefault().getLogger(getClass()).warn(
						"Workspace directory could not be removed yet; retrying: " + workspacePath, last);
				try {
					Thread.sleep(DELETE_RETRY_BACKOFF_MILLIS * (attempt + 1));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw Cause.create(last).wrap(RepositoryException.class);
				}
			}
		}

		if (Files.exists(workspacePath)) {
			Activator.getDefault().getLogger(getClass())
					.warn("Workspace directory still has leftovers after retries: " + workspacePath, last);
		}
	}

	private void copyWorkspaceTemplate(Path workspacePath) throws IOException {
		Path templatePath = fConfiguration.getEtcPath().resolve("workspace-template").normalize();
		if (!Files.isDirectory(templatePath)) {
			return;
		}

		Files.walkFileTree(templatePath, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(workspacePath.resolve(templatePath.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, workspacePath.resolve(templatePath.relativize(file).toString()),
						StandardCopyOption.REPLACE_EXISTING);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Posts a workspace lifecycle event
	 * ({@code org/mintjams/jcr/Workspace/CREATED|DELETED}) so that higher
	 * layers (e.g. the CMS) can start or stop their per-workspace services
	 * without polling the repository.
	 */
	private void postWorkspaceEvent(String modifier, String workspaceName) {
		try {
			Activator.getDefault().postEvent(
					org.mintjams.jcr.Workspace.class.getName().replace(".", "/") + "/" + modifier,
					Map.of("workspace", workspaceName));
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(getClass())
					.warn("An error occurred while posting the workspace event: " + workspaceName, ex);
		}
	}

	@Override
	public PrincipalProvider getPrincipalProvider() {
		return fPrincipalProvider;
	}

	@Override
	public IdentityProvider getIdentityProvider() {
		return fIdentityProvider;
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
		public Principal getPrincipal(String name) throws PrincipalNotFoundException {
			return Activator.getDefault().getPrincipal(name);
		}

		@Override
		public UserPrincipal getUserPrincipal(String name) throws PrincipalNotFoundException {
			Principal p = getPrincipal(name);
			if (p instanceof UserPrincipal) {
				return (UserPrincipal) p;
			}

			throw new PrincipalNotFoundException(name);
		}

		@Override
		public GroupPrincipal getGroupPrincipal(String name) throws PrincipalNotFoundException {
			Principal p = getPrincipal(name);
			if (p instanceof GroupPrincipal) {
				return (GroupPrincipal) p;
			}

			throw new PrincipalNotFoundException(name);
		}

		@Override
		public Collection<GroupPrincipal> getMemberOf(Principal principal) throws PrincipalNotFoundException {
			return Activator.getDefault().getMemberOf(principal);
		}
	}

	/**
	 * Keeps this node's workspace registry in sync with the shared storage
	 * in a clustered deployment. Workspace creation and deletion only post
	 * events on the node that performed them; the other nodes notice the
	 * change by periodically rescanning the workspace root. A directory is
	 * only picked up once its {@code etc/jcr/jcr.yml} exists — the creating
	 * node materialises it during first start, and in a cluster it must
	 * carry the shared datasource configuration, so its presence marks the
	 * workspace as safe to open.
	 */
	private class WorkspaceDiscoverer implements Closeable, Runnable {
		private Thread fThread;
		private boolean fCloseRequested;

		public void open() {
			fThread = new Thread(this, getClass().getSimpleName());
			fThread.setDaemon(true);
			fThread.start();
		}

		@Override
		public void close() throws IOException {
			fCloseRequested = true;
			if (fThread != null) {
				try {
					fThread.interrupt();
					fThread.join(10000);
				} catch (InterruptedException ignore) {
				} finally {
					fThread = null;
				}
			}
		}

		@Override
		public void run() {
			while (!fCloseRequested) {
				try {
					Thread.sleep(fConfiguration.getWorkspaceDiscoveryInterval() * 1000L);
				} catch (InterruptedException ex) {
					continue;
				}

				try {
					discover();
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(getClass())
							.warn("An error occurred while discovering workspaces.", ex);
				}
			}
		}

		private void discover() throws IOException {
			synchronized (fWorkspaceManagementLock) {
				Collection<String> found = new HashSet<>();
				try (Stream<Path> stream = Files.list(fConfiguration.getWorkspaceRootPath())) {
					for (Path path : stream.toArray(Path[]::new)) {
						if (!Files.isDirectory(path) || path.getFileName().toString().startsWith(".")) {
							continue;
						}

						String workspaceName = path.getFileName().toString();
						found.add(workspaceName);
						if (fWorkspaceProviders.containsKey(workspaceName)) {
							continue;
						}
						if (!Files.exists(path.resolve("etc/jcr/jcr.yml"))) {
							// Still being created on another node; pick it up
							// on a later pass once its configuration exists.
							continue;
						}

						JcrWorkspaceProvider workspaceProvider = JcrWorkspaceProvider.create(workspaceName, JcrRepository.this);
						try {
							fCloser.register(workspaceProvider).open();
						} catch (Throwable ex) {
							try {
								fCloser.unregister(workspaceProvider);
								workspaceProvider.close();
							} catch (Throwable ignore) {}
							Activator.getDefault().getLogger(getClass())
									.warn("An error occurred during the start of the discovered workspace: " + workspaceName, ex);
							continue;
						}
						fWorkspaceProviders.put(workspaceName, workspaceProvider);

						Activator.getDefault().getLogger(getClass())
								.info("JCR workspace '" + workspaceName + "' has been discovered.");
						postWorkspaceEvent("CREATED", workspaceName);
					}
				}

				for (String workspaceName : fWorkspaceProviders.keySet().toArray(String[]::new)) {
					if (found.contains(workspaceName)
							|| JcrWorkspaceProvider.SYSTEM_WORKSPACE_NAME.equals(workspaceName)) {
						continue;
					}

					JcrWorkspaceProvider workspaceProvider = fWorkspaceProviders.remove(workspaceName);
					if (workspaceProvider == null) {
						continue;
					}
					try {
						fCloser.unregister(workspaceProvider);
					} catch (Throwable ignore) {}
					try {
						workspaceProvider.close();
					} catch (Throwable ex) {
						Activator.getDefault().getLogger(getClass())
								.warn("An error occurred while stopping the workspace: " + workspaceName, ex);
					}

					Activator.getDefault().getLogger(getClass())
							.info("JCR workspace '" + workspaceName + "' has been deleted on another node.");
					postWorkspaceEvent("DELETED", workspaceName);
				}
			}
		}
	}

	private class IdentityProviderImpl implements IdentityProvider {
		@Override
		public User getUser(String identifier) {
			return Activator.getDefault().getUser(identifier);
		}

		@Override
		public Group getGroup(String identifier) {
			return Activator.getDefault().getGroup(identifier);
		}

		@Override
		public Role getRole(String identifier) {
			return Activator.getDefault().getRole(identifier);
		}
	}

}

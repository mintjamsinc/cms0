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

package org.mintjams.rt.cms.internal;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.Privilege;

import org.apache.commons.io.IOUtils;
import org.apache.felix.webconsole.WebConsoleSecurityProvider;
import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.jcr.security.AccessControlManager;
import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.jcr.security.GuestPrincipal;
import org.mintjams.jcr.spi.security.JcrAuthenticator;
import org.mintjams.jcr.spi.security.JcrPrincipalProvider;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProvider;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProvider;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceClassLoaderProvider;
import org.mintjams.rt.cms.internal.script.WorkspaceFacetProvider;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptEngineManager;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.security.DefaultPrincipalProvider;
import org.mintjams.rt.cms.internal.security.FelixWebConsoleSecurityProvider;
import org.mintjams.rt.cms.internal.security.ServiceAccountPrincipalProvider;
import org.mintjams.rt.cms.internal.security.UserServiceAuthenticator;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2Authenticator;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2PrincipalProvider;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2ServiceProvider;
import org.mintjams.rt.cms.internal.web.RepositoryServletsProvider;
import org.mintjams.rt.cms.internal.web.WorkspaceWebServletProvider;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.osgi.Properties;
import org.mintjams.tools.osgi.Registration;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;

@Component(name = CmsService.COMPONENT_NAME, configurationPolicy = ConfigurationPolicy.OPTIONAL, enabled = true, immediate = true)
public class CmsService {

	public static final String COMPONENT_NAME = "org.mintjams.rt.cms.CmsService";

	@Reference(cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.STATIC)
	private LoggerFactory fLoggerFactory;

	@Reference(cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.STATIC)
	private Repository fRepository;

	@Reference(cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.STATIC)
	private EventAdmin fEventAdmin;

	private static CmsService fCmsService;
	private BundleContext fBundleContext;
	private String fBootIdentifier;
	private Properties fProperties;
	private CmsConfiguration fConfig;
	private final Map<String, WorkspaceClassLoaderProvider> fWorkspaceClassLoaderProviders = new HashMap<>();
	private final Map<String, WorkspaceScriptEngineManager> fWorkspaceScriptEngineManagers = new HashMap<>();
	private final Map<String, WorkspaceFacetProvider> fWorkspaceFacetProviders = new HashMap<>();
	private final Map<String, WorkspaceProcessEngineProvider> fWorkspaceProcessEngineProviders = new HashMap<>();
	private final Map<String, WorkspaceIntegrationEngineProvider> fWorkspaceIntegrationEngineProviders = new HashMap<>();
	private final Map<String, WorkspaceWebServletProvider> fWorkspaceServletProviders = new HashMap<>();
	private final Closer fCloser = Closer.create();

	@Activate
	void activate(ComponentContext cc, BundleContext bc, Map<String, Object> config) {
		fBundleContext = bc;
		fCmsService = this;

		try {
			Path etcPath = CmsService.getRepositoryPath().resolve("etc");
			if (!Files.exists(etcPath)) {
				Files.createDirectories(etcPath);
			}
			Path bootIdPath = etcPath.resolve("boot.id");
			if (!Files.exists(bootIdPath)) {
				try (Writer out = Files.newBufferedWriter(bootIdPath)) {
					out.write(UUID.randomUUID().toString());
				}
			}
			try (InputStream in = new BufferedInputStream(Files.newInputStream(bootIdPath))) {
				fBootIdentifier = IOUtils.toString(in, StandardCharsets.UTF_8.toString()).trim();
				UUID.fromString(fBootIdentifier);
			}

			fProperties = Properties.create(config);
			open();
		} catch (Throwable ex) {
			fLoggerFactory.getLogger(getClass()).error("CMS service could not be started.", ex);
		}
	}

	@Deactivate
	void deactivate(ComponentContext cc, BundleContext bc) {
		try {
			close();
		} catch (Throwable ex) {
			fLoggerFactory.getLogger(getClass()).warn("An error occurred while stopping the CMS service.", ex);
		}
		fCmsService = null;
		fBundleContext = null;
	}

	private synchronized void open() throws IOException, RepositoryException {
		fConfig = new CmsConfiguration();
		fCloser.register(new RepositoryServletsProvider(fConfig)).open();

		prepareStandardFolders();

		try {
			loadContent("system");
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
		for (String workspaceName : getWorkspaceNames()) {
			if (workspaceName.equals("system")) {
				continue;
			}

			try {
				loadContent(workspaceName);
			} catch (Throwable ex) {
				fLoggerFactory.getLogger(getClass()).warn("An error occurred while loading workspace content: " + workspaceName, ex);
			}
		}

		prepareDefaultAuthorizables();

		prepareServices("system");
		for (String workspaceName : getWorkspaceNames()) {
			if (workspaceName.equals("system")) {
				continue;
			}

			try {
				prepareServices(workspaceName);
			} catch (Throwable ex) {
				fLoggerFactory.getLogger(getClass()).error("An error occurred while starting the workspace service: " + workspaceName, ex);
			}
		}

		fCloser.register(Registration.newBuilder(WebConsoleSecurityProvider.class)
				.setService(new FelixWebConsoleSecurityProvider())
				.setProperty(Constants.SERVICE_PID, FelixWebConsoleSecurityProvider.SERVICE_PID)
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());

		// The default principal provider
		fCloser.register(Registration.newBuilder(JcrPrincipalProvider.class)
				.setService(new DefaultPrincipalProvider())
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());

		// The user service authenticator and principal provider
		fCloser.register(Registration.newBuilder(JcrAuthenticator.class)
				.setService(new UserServiceAuthenticator())
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());
		fCloser.register(Registration.newBuilder(JcrPrincipalProvider.class)
				.setService(new ServiceAccountPrincipalProvider())
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());

		// The SAML2 authenticator and principal provider
		Saml2ServiceProvider saml2ServiceProvider = new Saml2ServiceProvider();
		fCloser.register(saml2ServiceProvider).open();
		fCloser.register(Registration.newBuilder(JcrAuthenticator.class)
				.setService(new Saml2Authenticator(saml2ServiceProvider.getConfiguration()))
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());
		fCloser.register(Registration.newBuilder(JcrPrincipalProvider.class)
				.setService(new Saml2PrincipalProvider(saml2ServiceProvider.getConfiguration()))
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build());
	}

	private void prepareStandardFolders() throws IOException, RepositoryException {
		for (String workspaceName : getWorkspaceNames()) {
			Session session = null;
			try {
				session = fRepository.login(new CmsServiceCredentials(), workspaceName);
				Node root = session.getRootNode();
				if (!JCRs.exists(root, "content")) {
					Node content = JCRs.createFolder(root, "content");
					AccessControlManager acm = Adaptables.getAdapter(content.getSession().getAccessControlManager(), AccessControlManager.class);
					AccessControlList acl = (AccessControlList) acm.getPolicies(content.getPath())[0];
					acl.addAccessControlEntry(new GuestPrincipal(), true, Privilege.JCR_READ);
					acm.setPolicy(content.getPath(), acl);
				}
				JCRs.getOrCreateFolder(root, "etc");
				JCRs.getOrCreateFolder(root, "lib");
				JCRs.getOrCreateFolder(root, "opt");
				Node usrFolder = JCRs.getOrCreateFolder(root, "usr");
				JCRs.getOrCreateFolder(usrFolder, "local");
				JCRs.getOrCreateFolder(usrFolder, "classes");
				JCRs.getOrCreateFolder(usrFolder, "lib");
				if (workspaceName.equals("system")) {
					Node shareFolder = JCRs.getOrCreateFolder(usrFolder, "share");
					JCRs.getOrCreateFolder(shareFolder, "classes");
					JCRs.getOrCreateFolder(shareFolder, "lib");
				}
				session.save();
			} catch (Throwable ex) {
				try {
					session.refresh(false);
				} catch (Throwable ignore) {}
				throw Cause.create(ex).wrap(IOException.class);
			} finally {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}
		}
	}

	private void prepareDefaultAuthorizables() throws IOException {
		try (WorkspaceScriptContext context = new WorkspaceScriptContext("system")) {
			context.setCredentials(new CmsServiceCredentials());
			context.getSession().getUserManager().registerIfNotExists(new GuestPrincipal(), new EveryonePrincipal());
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private String[] getWorkspaceNames() throws RepositoryException {
		Session session = null;
		try {
			session = fRepository.login(new CmsServiceCredentials(), "system");
			return session.getWorkspace().getAccessibleWorkspaceNames();
		} finally {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}
	}

	private void prepareServices(String workspaceName) throws IOException, RepositoryException {
		WorkspaceClassLoaderProvider classLoaderProvider = fCloser.register(new WorkspaceClassLoaderProvider(workspaceName));
		fWorkspaceClassLoaderProviders.put(workspaceName, classLoaderProvider);
		classLoaderProvider.open();

		WorkspaceScriptEngineManager scriptEngineManager = fCloser.register(new WorkspaceScriptEngineManager(workspaceName));
		fWorkspaceScriptEngineManagers.put(workspaceName, scriptEngineManager);
		scriptEngineManager.open();

		WorkspaceFacetProvider facetProvider = fCloser.register(new WorkspaceFacetProvider(workspaceName));
		fWorkspaceFacetProviders.put(workspaceName, facetProvider);
		facetProvider.open();

		WorkspaceProcessEngineProvider processEngineProvider = fCloser.register(new WorkspaceProcessEngineProvider(workspaceName));
		fWorkspaceProcessEngineProviders.put(workspaceName, processEngineProvider);
		try {
			processEngineProvider.open();
		} catch (Throwable ex) {
			fLoggerFactory.getLogger(getClass()).error("An error occurred while starting the workspace process engine: " + workspaceName, ex);
		}

		WorkspaceIntegrationEngineProvider integrationEngineProvider = fCloser.register(new WorkspaceIntegrationEngineProvider(workspaceName));
		fWorkspaceIntegrationEngineProviders.put(workspaceName, integrationEngineProvider);
		try {
			integrationEngineProvider.open();
		} catch (Throwable ex) {
			fLoggerFactory.getLogger(getClass()).error("An error occurred while starting the workspace integration engine: " + workspaceName, ex);
		}

		WorkspaceWebServletProvider servletProvider = fCloser.register(new WorkspaceWebServletProvider(workspaceName));
		fWorkspaceServletProviders.put(workspaceName, servletProvider);
		servletProvider.open();
	}

	private void loadContent(String workspaceName) throws ResourceException, IOException {
		getLogger(getClass()).info("Content update has started.");
		try (WorkspaceScriptContext context = new WorkspaceScriptContext(workspaceName)) {
			context.setCredentials(new CmsServiceCredentials());
			Scripts.prepareAPIs(context);

			context.getResourceResolver().getSession().deploy();
		}
		getLogger(getClass()).info("Content update is complete.");
	}

	private synchronized void close() throws IOException {
		fCloser.close();
	}

	public static CmsService getDefault() {
		return fCmsService;
	}

	public BundleContext getBundleContext() {
		return fBundleContext;
	}

	public Bundle getBundle() {
		return getBundleContext().getBundle();
	}

	public ClassLoader getBundleClassLoader() {
		return fBundleContext.getBundle().adapt(BundleWiring.class).getClassLoader();
	}

	public static String getBootIdentifier() {
		return getDefault().fBootIdentifier;
	}

	public static Logger getLogger(Class<?> type) {
		return getDefault().fLoggerFactory.getLogger(type);
	}

	public static void postEvent(String topic, Map<String, ?> properties) {
		postEvent(new Event(topic, properties));
	}

	public static void postEvent(Event event) {
		getDefault().fEventAdmin.postEvent(event);
	}

	public static CmsConfiguration getConfiguration() {
		return getDefault().fConfig;
	}

	public static Repository getRepository() {
		return getDefault().fRepository;
	}

	public static Path getRepositoryPath() {
		return Path.of(Strings.defaultIfEmpty(getDefault().getBundleContext().getProperty("org.mintjams.jcr.repository.rootdir"), "repository"));
	}

	public static Path getWorkspacePath(String workspaceName) {
		return getRepositoryPath().resolve("workspaces").resolve(workspaceName).toAbsolutePath();
	}

	public static WorkspaceClassLoaderProvider getWorkspaceClassLoaderProvider(String workspaceName) {
		return getDefault().fWorkspaceClassLoaderProviders.get(workspaceName);
	}

	public static WorkspaceScriptEngineManager getWorkspaceScriptEngineManager(String workspaceName) {
		return getDefault().fWorkspaceScriptEngineManagers.get(workspaceName);
	}

	public static WorkspaceFacetProvider getWorkspaceFacetProvider(String workspaceName) {
		return getDefault().fWorkspaceFacetProviders.get(workspaceName);
	}

	public static WorkspaceProcessEngineProvider getWorkspaceProcessEngineProvider(String workspaceName) {
		return getDefault().fWorkspaceProcessEngineProviders.get(workspaceName);
	}

	public static WorkspaceIntegrationEngineProvider getWorkspaceIntegrationEngineProvider(String workspaceName) {
		return getDefault().fWorkspaceIntegrationEngineProviders.get(workspaceName);
	}

	public static WorkspaceWebServletProvider getWorkspaceServletProvider(String workspaceName) {
		return getDefault().fWorkspaceServletProviders.get(workspaceName);
	}

	public static Path getTemporaryDirectoryPath() {
		return getRepositoryPath().resolve("tmp");
	}

}

/*
 * Copyright (c) 2024 MintJams Inc.
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

package org.mintjams.idp.internal;

import java.io.IOException;

import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.Session;

import org.mintjams.cms.CmsService;
import org.mintjams.idp.internal.auth.JcrUserStore;
import org.mintjams.idp.internal.auth.UserStore;
import org.mintjams.idp.internal.security.CryptoService;
import org.mintjams.idp.internal.security.FileKeyStoreManager;
import org.mintjams.idp.internal.security.FileSecretKeyProvider;
import org.mintjams.idp.internal.security.IdpServiceCredentials;
import org.mintjams.idp.internal.security.KeyStoreManager;
import org.mintjams.idp.internal.security.SecretKeyProvider;
import org.mintjams.idp.internal.servlet.LoginServlet;
import org.mintjams.idp.internal.servlet.MetadataServlet;
import org.mintjams.idp.internal.servlet.SsoServlet;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.osgi.Tracker;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi Bundle Activator for the MintJams SAML 2.0 Identity Provider.
 *
 * <p>On activation, this class tracks the JCR Repository service. Once available,
 * it initializes the IdP by reading configuration, user data, and signing keys
 * from JCR nodes in the system workspace.</p>
 *
 * <p>JCR node structure:</p>
 * <pre>
 * /home/users/{username}/profile (nt:file)
 *   └─ jcr:content (nt:resource)
 *        ├─ password, displayName, mail, memberOf
 *
 * /home/idp/keystore.p12 (nt:file)
 *   └─ jcr:content (nt:resource) ← PKCS12 binary
 *
 * /home/idp/config (nt:file)
 *   └─ jcr:content (nt:resource)
 *        ├─ entityId, roleAttribute, assertionValiditySeconds
 * </pre>
 *
 * <p>Configuration is read from system properties with fallback defaults:</p>
 * <ul>
 *   <li>{@code idp.baseUrl} - Base URL of the IdP (default: https://localhost:8443)</li>
 *   <li>{@code idp.contextPath} - Servlet context path (default: /idp)</li>
 *   <li>{@code idp.keystorePassword} - Keystore password (default: changeit)</li>
 * </ul>
 */
public class Activator implements BundleActivator {

	private static final Logger log = LoggerFactory.getLogger(Activator.class);

	private static final String DEFAULT_ADMIN_PASSWORD_SHA256 =
			"8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918"; // "admin"

	private static Activator fActivator;
	private BundleContext fBundleContext;
	private IdpConfiguration fConfig;
	private final Closer fCloser = Closer.create();
	private Tracker<CmsService> fCmsServiceTracker;
	private Tracker<HttpService> fHttpServiceTracker;
	private SecretKeyProvider fSecretKeyProvider;
	private KeyStoreManager fKeyStoreManager;
	private CryptoService fCryptoService;
	private UserStore fUserStore;

	private Tracker.Listener<Object> fTrackerListener = new Tracker.Listener<Object>() {
		@Override
		public void on(Tracker.Event<Object> event) {
			if (event instanceof Tracker.ServiceAddingEvent) {
				try {
					open();
				} catch (Throwable ignore) {}
				return;
			}

			if (event instanceof Tracker.ServiceRemovedEvent) {
				try {
					close();
				} catch (Throwable ignore) {}
				return;
			}
		}
	};

	@Override
	public void start(BundleContext context) throws Exception {
		log.info("Starting MintJams IdP bundle...");
		fBundleContext = context;
		fActivator = this;

		fCmsServiceTracker = fCloser.register(Tracker.newBuilder(CmsService.class)
				.setBundleContext(fBundleContext)
				.setListener(fTrackerListener)
				.build());
		fCmsServiceTracker.open();

		log.info("MintJams IdP bundle started. Waiting for CMS service...");
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		log.info("Stopping MintJams IdP bundle...");

		close();
		fActivator = null;
		fBundleContext = null;

		log.info("MintJams IdP bundle stopped.");
	}

	private synchronized void open() throws IOException {
		if (fCmsServiceTracker.getTrackingCount() == 0 || fHttpServiceTracker.getTrackingCount() == 0) {
			return;
		}

		Session jcrSession = null;
		try {
			jcrSession = getRepository().login(new IdpServiceCredentials(), "system");

			// Initialize SecretKeyProvider
			fSecretKeyProvider = new FileSecretKeyProvider();

			// Initialize CryptoService
			fCryptoService = new CryptoService();

			// Load IdP configuration
			fConfig = new IdpConfiguration();

			// Ensure JCR node structure is initialized
			initializeJcrStructure(jcrSession);

			// Initialize KeyStore
			fKeyStoreManager = new FileKeyStoreManager();

			// Initialize UserStore
			fUserStore = new JcrUserStore();

			jcrSession.save();
		} catch (Throwable ex) {
			try {
				jcrSession.refresh(false);
			} catch (Throwable ignore) {}
			log.error("Failed to initialize IdP from JCR", ex);
			return;
		} finally {
			try {
				jcrSession.logout();
			} catch (Throwable ignore) {}
		}

		HttpService httpService = fHttpServiceTracker.getService();
		try {
			HttpContext sharedContext = httpService.createDefaultHttpContext();

			httpService.registerServlet(fConfig.getMetadataPath(), new MetadataServlet(), null, sharedContext);
			httpService.registerServlet(fConfig.getLoginPath(), new LoginServlet(), null, sharedContext);
			httpService.registerServlet(fConfig.getSsoPath(), new SsoServlet(), null, sharedContext);

			log.info("IdP servlets registered at: {}", fConfig.getConfigPath());
			log.info("  Entity ID : {}", fConfig.getEntityId());
			log.info("  SSO URL   : {}", fConfig.getSsoUrl());
			log.info("  Metadata  : {}", fConfig.getMetadataUrl());
			log.info("  Login     : {}", fConfig.getLoginUrl());
		} catch (Throwable ex) {
			log.error("Failed to register IdP servlets", ex);
		}
	}

	private void initializeJcrStructure(Session jcrSession) throws Exception {
		try {
			Node homeFolder = JCRs.getOrCreateFolder(jcrSession.getRootNode(), "home");
			Node idpFolder = JCRs.getOrCreateFolder(homeFolder, "idp");
			Node usersFolder = JCRs.getOrCreateFolder(idpFolder, "users");
			JCRs.getOrCreateFolder(idpFolder, "groups");

			initializeAdminUser(usersFolder);
		} catch (Throwable ex) {
			log.error("Failed to initialize JCR node structure", ex);
			throw ex;
		}
	}

	private void initializeAdminUser(Node usersFolder) throws Exception {
		String adminProfilePath = "/home/users/admin/profile";
		if (usersFolder.hasNode("admin/profile")) {
			return;
		}

		Node adminFolder = JCRs.getOrCreateFolder(usersFolder, "admin");

		Node profileFile = JCRs.createFile(adminFolder, "profile");
		JCRs.setProperty(profileFile, "jcr:mimeType", "application/x-idp-profile");
		JCRs.setProperty(profileFile, "displayName", "Administrator");
		JCRs.setProperty(profileFile, "mail", "admin@example.com");
		JCRs.setProperty(profileFile, "password", "{sha256}" + DEFAULT_ADMIN_PASSWORD_SHA256);
		JCRs.setProperty(profileFile, "role", new String[] { "administration" });

		JCRs.getOrCreateFolder(adminFolder, "preferences");

		log.info("Created default admin user profile at {}", adminProfilePath);
	}

	private synchronized void close() throws IOException {
		fCloser.close();
	}

	public static Activator getDefault() {
		return fActivator;
	}

	public BundleContext getBundleContext() {
		return fBundleContext;
	}

	public CmsService getCmsService() {
		return fCmsServiceTracker.getService();
	}

	public IdpConfiguration getConfiguration() {
		return fConfig;
	}

	public Repository getRepository() {
		return getCmsService().getRepository();
	}

	public SecretKeyProvider getSecretKeyProvider() {
		return fSecretKeyProvider;
	}

	public KeyStoreManager getKeyStoreManager() {
		return fKeyStoreManager;
	}

	public CryptoService getCryptoService() {
		return fCryptoService;
	}

	public UserStore getUserStore() {
		return fUserStore;
	}

}

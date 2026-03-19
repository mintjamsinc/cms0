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

package org.mintjams.idp;

import java.io.File;

import org.mintjams.idp.auth.PropertiesUserStore;
import org.mintjams.idp.auth.UserStore;
import org.mintjams.idp.model.IdpSettings;
import org.mintjams.idp.security.KeyStoreManager;
import org.mintjams.idp.servlet.LoginServlet;
import org.mintjams.idp.servlet.MetadataServlet;
import org.mintjams.idp.servlet.SsoServlet;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi Bundle Activator for the MintJams SAML 2.0 Identity Provider.
 *
 * <p>On activation, this class:</p>
 * <ol>
 *   <li>Initializes the signing key store (auto-generates certificate if needed)</li>
 *   <li>Initializes the user store (creates default admin user if needed)</li>
 *   <li>Registers servlets with the OSGi HttpService</li>
 * </ol>
 *
 * <p>Configuration is read from system properties or defaults:</p>
 * <ul>
 *   <li>{@code idp.baseUrl} - Base URL of the IdP (default: https://localhost:8443)</li>
 *   <li>{@code idp.contextPath} - Servlet context path (default: /idp)</li>
 *   <li>{@code idp.dataDir} - Data directory for keystore and users file</li>
 *   <li>{@code idp.roleAttribute} - SAML attribute name for roles (default: Role)</li>
 *   <li>{@code idp.keystorePassword} - Keystore password (default: changeit)</li>
 * </ul>
 */
public class IdpActivator implements BundleActivator {

	private static final Logger LOG = LoggerFactory.getLogger(IdpActivator.class);

	private ServiceTracker<HttpService, HttpService> httpServiceTracker;
	private String contextPath;

	@Override
	public void start(BundleContext context) throws Exception {
		LOG.info("Starting MintJams IdP bundle...");

		// Read configuration
		String baseUrl = getConfig(context, "idp.baseUrl", "https://localhost:8443");
		contextPath = getConfig(context, "idp.contextPath", "/idp");
		String dataDir = getConfig(context, "idp.dataDir",
				new File(System.getProperty("user.home"), ".mintjams-idp").getAbsolutePath());
		String roleAttribute = getConfig(context, "idp.roleAttribute", "Role");
		String keystorePassword = getConfig(context, "idp.keystorePassword", "changeit");

		// Initialize KeyStore
		File keystoreFile = new File(dataDir, "idp-keystore.p12");
		KeyStoreManager keyStoreManager = new KeyStoreManager(keystoreFile, keystorePassword);
		keyStoreManager.init();

		// Initialize UserStore
		File usersFile = new File(dataDir, "idp-users.properties");
		PropertiesUserStore userStore = new PropertiesUserStore(usersFile);
		userStore.init();

		// Build IdpSettings
		IdpSettings settings = new IdpSettings();
		settings.setBaseUrl(baseUrl);
		settings.setContextPath(contextPath);
		settings.setEntityId(baseUrl + contextPath);
		settings.setCertificate(keyStoreManager.getCertificate());
		settings.setPrivateKey(keyStoreManager.getPrivateKey());

		// Track HttpService and register servlets
		httpServiceTracker = new ServiceTracker<>(context, HttpService.class,
				new HttpServiceTrackerCustomizer(context, settings, userStore, roleAttribute));
		httpServiceTracker.open();

		LOG.info("MintJams IdP bundle started.");
		LOG.info("  Entity ID : {}", settings.getEntityId());
		LOG.info("  SSO URL   : {}", settings.getSsoUrl());
		LOG.info("  Metadata  : {}", settings.getMetadataUrl());
		LOG.info("  Login     : {}", settings.getLoginUrl());
		LOG.info("  Data dir  : {}", dataDir);
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		LOG.info("Stopping MintJams IdP bundle...");

		if (httpServiceTracker != null) {
			httpServiceTracker.close();
			httpServiceTracker = null;
		}

		LOG.info("MintJams IdP bundle stopped.");
	}

	private String getConfig(BundleContext context, String key, String defaultValue) {
		// Try system property first, then bundle context property
		String value = System.getProperty(key);
		if (value == null) {
			value = context.getProperty(key);
		}
		return value != null ? value : defaultValue;
	}

	/**
	 * Tracks the HttpService and registers/unregisters servlets.
	 */
	private class HttpServiceTrackerCustomizer
			implements ServiceTrackerCustomizer<HttpService, HttpService> {

		private final BundleContext context;
		private final IdpSettings settings;
		private final UserStore userStore;
		private final String roleAttribute;

		HttpServiceTrackerCustomizer(BundleContext context, IdpSettings settings,
				UserStore userStore, String roleAttribute) {
			this.context = context;
			this.settings = settings;
			this.userStore = userStore;
			this.roleAttribute = roleAttribute;
		}

		@Override
		public HttpService addingService(ServiceReference<HttpService> reference) {
			HttpService httpService = context.getService(reference);
			try {
				// Create a shared HttpContext for our servlets
				HttpContext sharedContext = httpService.createDefaultHttpContext();

				// Register servlets
				httpService.registerServlet(contextPath + "/metadata",
						new MetadataServlet(settings), null, sharedContext);
				httpService.registerServlet(contextPath + "/login",
						new LoginServlet(userStore, contextPath), null, sharedContext);
				httpService.registerServlet(contextPath + "/sso",
						new SsoServlet(settings, roleAttribute), null, sharedContext);

				LOG.info("IdP servlets registered at: {}", contextPath);

			} catch (Exception e) {
				LOG.error("Failed to register IdP servlets", e);
			}
			return httpService;
		}

		@Override
		public void modifiedService(ServiceReference<HttpService> reference, HttpService service) {
			// No action needed
		}

		@Override
		public void removedService(ServiceReference<HttpService> reference, HttpService service) {
			try {
				service.unregister(contextPath + "/metadata");
				service.unregister(contextPath + "/login");
				service.unregister(contextPath + "/sso");
				LOG.info("IdP servlets unregistered.");
			} catch (Exception e) {
				LOG.warn("Error unregistering IdP servlets", e);
			}
			context.ungetService(reference);
		}
	}

}

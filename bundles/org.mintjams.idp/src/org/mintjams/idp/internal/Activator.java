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
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.security.Privilege;

import org.mintjams.cms.CmsService;
import org.mintjams.cms.security.BCrypt;
import org.mintjams.cms.security.Encryptor;
import org.mintjams.cms.security.PasswordGenerator;
import org.mintjams.idp.internal.auth.JcrUserStore;
import org.mintjams.idp.internal.auth.UserStore;
import org.mintjams.idp.internal.security.FileKeyStoreManager;
import org.mintjams.idp.internal.security.IdpServiceCredentials;
import org.mintjams.idp.internal.security.KeyStoreManager;
import org.mintjams.idp.internal.servlet.LoginApiServlet;
import org.mintjams.idp.internal.servlet.SpApiServlet;
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OSGi Bundle Activator for the MintJams SAML 2.0 Identity Provider.
 * 
 * <p>This class manages the lifecycle of the IdP bundle, including initializing
 * the JCR repository structure, loading configuration, and registering HTTP servlets
 * for metadata, login, and SSO endpoints.</p>
 */
public class Activator implements BundleActivator {

	private static final Logger log = LoggerFactory.getLogger(Activator.class);

	private static Activator fActivator;
	private BundleContext fBundleContext;
	private IdpConfiguration fConfig;
	private final Closer fCloser = Closer.create();
	private Tracker<CmsService> fCmsServiceTracker;
	private Tracker<HttpService> fHttpServiceTracker;
	private KeyStoreManager fKeyStoreManager;
	private Encryptor fEncryptor;
	private UserStore fUserStore;
	private final ObjectMapper fObjectMapper = new ObjectMapper();

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

		fHttpServiceTracker = fCloser.register(Tracker.newBuilder(HttpService.class)
				.setBundleContext(fBundleContext)
				.setListener(fTrackerListener)
				.build());
		fHttpServiceTracker.open();

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

			// Initialize Encryptor
			fEncryptor = fCmsServiceTracker.getService().getEncryptor();

			// Load IdP configuration
			fConfig = new IdpConfiguration();

			// Ensure JCR node structure is initialized
			initializeJcrStructure(jcrSession);

			// Initialize KeyStore
			fKeyStoreManager = new FileKeyStoreManager();

			// Initialize UserStore
			fUserStore = new JcrUserStore();
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
			httpService.registerServlet(fConfig.getLoginApiPath(), new LoginApiServlet(), null, sharedContext);
			httpService.registerServlet(fConfig.getSpApiPath(), new SpApiServlet(), null, sharedContext);
			httpService.registerServlet(fConfig.getSsoPath(), new SsoServlet(), null, sharedContext);

			log.info("IdP servlets registered at: {}", fConfig.getBaseURL());
			log.info("  Entity ID : {}", fConfig.getEntityId());
			log.info("  SSO URL   : {}", fConfig.getSsoUrl());
			log.info("  Metadata  : {}", fConfig.getMetadataUrl());
			log.info("  Login     : {}", fConfig.getLoginUrl());
			log.info("  Login API : {}", fConfig.getBaseURL() + fConfig.getLoginApiPath());
			if (fConfig.getCustomLoginPageURL() != null) {
				log.info("  Custom Login: {}", fConfig.getCustomLoginPageURL());
			}
		} catch (Throwable ex) {
			log.error("Failed to register IdP servlets", ex);
		}
	}

	private void initializeJcrStructure(Session jcrSession) throws Exception {
		try {
			Node homeFolder = JCRs.getOrCreateFolder(jcrSession.getRootNode(), "home");
			Node usersFolder = JCRs.getOrCreateFolder(homeFolder, "users");
			JCRs.getOrCreateFolder(homeFolder, "groups");
			Node rolesFolder = JCRs.getOrCreateFolder(homeFolder, "roles");
			if (jcrSession.hasPendingChanges()) {
				jcrSession.save();
			}

			initializeAdminRole(rolesFolder);
			initializeAdminUser(usersFolder, rolesFolder);
		} catch (Throwable ex) {
			log.error("Failed to initialize JCR node structure", ex);
			throw ex;
		}
	}

	private void initializeAdminRole(Node rolesFolder) throws Exception {
		Session jcrSession = rolesFolder.getSession();

		if (rolesFolder.hasNode("administration/profile")) {
			// Ensure mix:referenceable is present (migration for existing installations)
			Node roleProfile = rolesFolder.getNode("administration/profile");
			if (!roleProfile.isNodeType("mix:referenceable")) {
				roleProfile.addMixin("mix:referenceable");
			}
			if (jcrSession.hasPendingChanges()) {
				jcrSession.save();
			}
			return;
		}

		Node roleFolder = JCRs.getOrCreateFolder(rolesFolder, "administration");
		Node profileFile = JCRs.createFile(roleFolder, "profile");
		profileFile.addMixin("mix:referenceable");
		JCRs.setProperty(profileFile, "jcr:mimeType", "application/vnd.webtop.role");
		JCRs.setProperty(profileFile, "displayName", "Administration");
		JCRs.setProperty(profileFile, "description", "Full administrative access");
		if (jcrSession.hasPendingChanges()) {
			jcrSession.save();
		}
		log.info("Created default 'administration' role at {}", profileFile.getPath());
	}

	private void initializeAdminUser(Node usersFolder, Node rolesFolder) throws Exception {
		Session jcrSession = usersFolder.getSession();

		if (usersFolder.hasNode("admin/profile")) {
			// Migrate roles from String[] to WEAKREFERENCE[] if needed
			migrateUserRolesToWeakReference(usersFolder, rolesFolder);
			return;
		}

		Node adminFolder = JCRs.getOrCreateFolder(usersFolder, "admin");

		String password = PasswordGenerator.generate(16);
		String passwordHash = "{bcrypt}" + BCrypt.hash(password);

		Node profileFile = JCRs.createFile(adminFolder, "profile");
		JCRs.setProperty(profileFile, "jcr:mimeType", "application/vnd.webtop.user");
		JCRs.setProperty(profileFile, "displayName", "Administrator");
		JCRs.setProperty(profileFile, "mail", "admin@example.com");
		JCRs.setProperty(profileFile, "enabled", true);
		JCRs.setProperty(profileFile, "password", passwordHash);

		// Assign administration role as WEAKREFERENCE
		Node adminRoleProfile = rolesFolder.getNode("administration/profile");
		Node adminContentNode = JCRs.getContentNode(profileFile);
		Value weakRef = usersFolder.getSession().getValueFactory().createValue(adminRoleProfile, true);
		adminContentNode.setProperty("roles", new Value[] { weakRef });

		JCRs.getOrCreateFolder(adminFolder, "preferences");

		log.info("""
				Created default admin user profile at %s
				
				**********************************************************************
				*                                                                    *
				* [MintJams CMS] Initial Setup Required                              *
				*                                                                    *
				* An initial password has been generated for the 'admin' user.       *
				* Initial Password: %s                                 *
				*                                                                    *
				* Please log in and update your password as soon as possible.        *
				*                                                                    *
				**********************************************************************
				""".formatted(profileFile.getPath(), password));
		password = null; // Clear password variable for security

		jcrSession.save();

		// Grant full access to the admin user on their own profile
		JCRs.setAccessControlEntry(adminFolder, new Principal() {
			@Override
			public String getName() {
				return "admin";
			}
		}, true, Privilege.JCR_ALL);
		jcrSession.save();
	}

	private void migrateUserRolesToWeakReference(Node usersFolder, Node rolesFolder) throws Exception {
		Session jcrSession = usersFolder.getSession();
		javax.jcr.NodeIterator userIt = usersFolder.getNodes();
		while (userIt.hasNext()) {
			Node userFolder = userIt.nextNode();
			if (!userFolder.hasNode("profile")) continue;
			Node contentNode = JCRs.getContentNode(userFolder.getNode("profile"));
			if (!contentNode.hasProperty("roles")) continue;
			if (contentNode.getProperty("roles").getType() == javax.jcr.PropertyType.WEAKREFERENCE) continue;

			// Migrate String[] to WEAKREFERENCE[]
			List<Value> weakRefs = new ArrayList<>();
			for (Value v : contentNode.getProperty("roles").getValues()) {
				String roleId = v.getString();
				String rolePath = rolesFolder.getPath() + "/" + roleId + "/profile";
				if (!jcrSession.nodeExists(rolePath)) continue;
				Node roleProfile = jcrSession.getNode(rolePath);
				if (!roleProfile.isNodeType("mix:referenceable")) {
					roleProfile.addMixin("mix:referenceable");
				}
				weakRefs.add(jcrSession.getValueFactory().createValue(roleProfile, true));
			}
			if (!weakRefs.isEmpty()) {
				contentNode.setProperty("roles", weakRefs.toArray(new Value[0]));
				log.info("Migrated roles to WeakReference for user: {}", userFolder.getName());
			}
		}
		if (jcrSession.hasPendingChanges()) {
			jcrSession.save();
		}
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

	public KeyStoreManager getKeyStoreManager() {
		return fKeyStoreManager;
	}

	public Encryptor getEncryptor() {
		return fEncryptor;
	}

	public UserStore getUserStore() {
		return fUserStore;
	}

	public String toJSON(Object value) throws IOException {
		return fObjectMapper.writeValueAsString(value);
	}

	@SuppressWarnings("unchecked")
	public <T> T parseJSON(String value) throws IOException {
		if (value == null) {
			return null;
		}
		return (T) fObjectMapper.readValue(value, new TypeReference<>() {});
	}

}

package org.mintjams.rt.jcr.internal;

import javax.jcr.Repository;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.junit.After;
import org.junit.Before;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Abstract base class for OSGi-based JCR tests.
 * Provides common setup and teardown logic for accessing the JCR repository
 * as an OSGi service.
 */
public abstract class AbstractOSGiTest {

	protected BundleContext bundleContext;
	protected Repository repository;
	protected Session session;

	@Before
	public void setUp() throws Exception {
		// Get the bundle context
		bundleContext = FrameworkUtil.getBundle(getClass()).getBundleContext();

		// Get the repository service
		ServiceReference<Repository> serviceRef = bundleContext.getServiceReference(Repository.class);
		if (serviceRef != null) {
			repository = bundleContext.getService(serviceRef);
		}

		// Create a session if repository is available
		if (repository != null) {
			try {
				// Try to login with system credentials or guest
				session = repository.login();
			} catch (Exception e) {
				// If default login fails, try with simple credentials
				try {
					SimpleCredentials credentials = new SimpleCredentials("admin", "admin".toCharArray());
					session = repository.login(credentials);
				} catch (Exception ex) {
					// Session will remain null if login fails
					System.err.println("Failed to create session: " + ex.getMessage());
				}
			}
		}
	}

	@After
	public void tearDown() throws Exception {
		// Logout session if it exists
		if (session != null && session.isLive()) {
			session.logout();
		}

		// Unget the service reference
		if (repository != null) {
			ServiceReference<Repository> serviceRef = bundleContext.getServiceReference(Repository.class);
			if (serviceRef != null) {
				bundleContext.ungetService(serviceRef);
			}
		}
	}

	/**
	 * Check if the test environment is properly initialized.
	 * Tests should call this method and skip if it returns false.
	 */
	protected boolean isInitialized() {
		return repository != null && session != null && session.isLive();
	}
}

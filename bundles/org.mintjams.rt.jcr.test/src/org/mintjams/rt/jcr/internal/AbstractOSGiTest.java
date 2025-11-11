package org.mintjams.rt.jcr.internal;

import javax.jcr.Credentials;
import javax.jcr.GuestCredentials;
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
	private ServiceReference<Repository> repositoryServiceRef;

	@Before
	public void setUp() throws Exception {
		// Get the bundle context
		bundleContext = FrameworkUtil.getBundle(getClass()).getBundleContext();

		// Get the repository service
		repositoryServiceRef = bundleContext.getServiceReference(Repository.class);
		if (repositoryServiceRef != null) {
			repository = bundleContext.getService(repositoryServiceRef);
		} else {
			repository = null;
		}

		// Create a session if repository is available
		if (repository != null) {
			try {
				// Try to login with guest credentials
				Credentials credentials = new GuestCredentials();
				session = repository.login(credentials, "system");
				System.out.println("Logged in with guest credentials.");
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
		session = null;

		// Unget the service reference
		if (bundleContext != null && repositoryServiceRef != null) {
			bundleContext.ungetService(repositoryServiceRef);
			repositoryServiceRef = null;
		}

		repository = null;
		bundleContext = null;
	}

	/**
	 * Check if the test environment is properly initialized.
	 * Tests should call this method and skip if it returns false.
	 */
	protected boolean isInitialized() {
		return repository != null && session != null && session.isLive();
	}
}

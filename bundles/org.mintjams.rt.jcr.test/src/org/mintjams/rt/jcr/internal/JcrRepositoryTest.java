package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import javax.jcr.LoginException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.junit.Test;

/**
 * Test class for JcrRepository to verify the fixes made in review-notes.md
 * This test runs in an OSGi container and accesses the JCR repository as an OSGi service.
 */
public class JcrRepositoryTest extends AbstractOSGiTest {

	/**
	 * Test that SPEC_VERSION_DESC returns the specification version, not the specification name
	 * Issue: review-notes.md #1
	 */
	@Test
	public void testGetDescriptor_SpecVersionDesc() {
		assumeTrue("Repository service is not available for repository descriptor tests.", isInitialized());

		String specVersion = repository.getDescriptor(Repository.SPEC_VERSION_DESC);
		assertNotNull("SPEC_VERSION_DESC should not be null", specVersion);

		// The spec version should be in format like "2.0"
		// It should NOT be the spec name like "Content Repository for Java Technology API"
		assertFalse("SPEC_VERSION_DESC should not contain 'Content Repository'",
				specVersion.contains("Content Repository"));
		assertFalse("SPEC_VERSION_DESC should not contain 'Java Technology'",
				specVersion.contains("Java Technology"));

		// Spec version should be a version number format (e.g., "2.0", "1.0")
		assertTrue("SPEC_VERSION_DESC should look like a version number",
				specVersion.matches("\\d+\\.\\d+.*"));
	}

	/**
	 * Test that SPEC_NAME_DESC returns the specification name
	 */
	@Test
	public void testGetDescriptor_SpecNameDesc() {
		assumeTrue("Repository service is not available for repository descriptor tests.", isInitialized());

		String specName = repository.getDescriptor(Repository.SPEC_NAME_DESC);
		assertNotNull("SPEC_NAME_DESC should not be null", specName);

		assertTrue("SPEC_NAME_DESC should describe the Content Repository specification",
				specName.contains("Content Repository"));
		assertFalse("SPEC_NAME_DESC should differ from SPEC_VERSION_DESC values",
				specName.matches("\\d+\\.\\d+.*"));
	}

	/**
	 * Test that login() handles null principal from authenticator without NullPointerException
	 * Issue: review-notes.md #2
	 */
	@Test
	public void testLogin_WithNullPrincipal() {
		assumeTrue("Repository service is not available for login tests.", isInitialized());

		try {
			// Attempt to login with credentials that might result in null principal
			SimpleCredentials credentials = new SimpleCredentials("invalid", "invalid".toCharArray());
			Session session = repository.login(credentials);

			// If we get here, login succeeded (which is fine)
			if (session != null) {
				session.logout();
			}

			// The test passes if no NullPointerException was thrown
		} catch (LoginException e) {
			// Expected exception for invalid credentials
			// The important thing is that it's a LoginException, not NullPointerException
		} catch (NullPointerException e) {
			fail("login() should not throw NullPointerException when authenticator returns null principal");
		} catch (RepositoryException e) {
			// Other repository exceptions are acceptable
		}
	}

	/**
	 * Test that login() properly rejects internal principal names from external authenticators
	 * Issue: review-notes.md #2
	 */
	@Test
	public void testLogin_RejectsInternalPrincipalNames() {
		assumeTrue("Repository service is not available for login tests.", isInitialized());

		try {
			SimpleCredentials credentials = new SimpleCredentials("system", "system".toCharArray());
			repository.login(credentials);
			fail("login() should reject internal principal names.");
		} catch (LoginException expected) {
			// Expected path: internal names must not be accepted
		} catch (RepositoryException ex) {
			fail("login() should throw LoginException for internal principal names, but got: " + ex.getClass().getSimpleName());
		}
	}

	/**
	 * Test that all required repository descriptors are present
	 */
	@Test
	public void testGetDescriptor_RequiredDescriptors() {
		assumeTrue("Repository service is not available for repository descriptor tests.", isInitialized());

		// Verify that all required descriptors are present
		String[] requiredDescriptors = {
			Repository.SPEC_VERSION_DESC,
			Repository.SPEC_NAME_DESC,
			Repository.REP_VENDOR_DESC,
			Repository.REP_VENDOR_URL_DESC,
			Repository.REP_NAME_DESC,
			Repository.REP_VERSION_DESC
		};

		for (String descriptorKey : requiredDescriptors) {
			String value = repository.getDescriptor(descriptorKey);
			assertNotNull("Descriptor " + descriptorKey + " should not be null", value);
			assertFalse("Descriptor " + descriptorKey + " should not be empty",
					value.trim().isEmpty());
		}
	}

	/**
	 * Test that repository descriptors are properly initialized
	 */
	@Test
	public void testGetDescriptorKeys() {
		assumeTrue("Repository service is not available for repository descriptor tests.", isInitialized());

		String[] descriptorKeys = repository.getDescriptorKeys();
		assertNotNull("getDescriptorKeys() should not return null", descriptorKeys);
		assertTrue("Repository should have at least one descriptor", descriptorKeys.length > 0);

		// Verify that key descriptors are included
		boolean hasSpecVersion = false;
		boolean hasSpecName = false;

		for (String key : descriptorKeys) {
			if (Repository.SPEC_VERSION_DESC.equals(key)) {
				hasSpecVersion = true;
			}
			if (Repository.SPEC_NAME_DESC.equals(key)) {
				hasSpecName = true;
			}
		}

		assertTrue("Repository should include SPEC_VERSION_DESC", hasSpecVersion);
		assertTrue("Repository should include SPEC_NAME_DESC", hasSpecName);
	}
}

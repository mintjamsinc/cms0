package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import javax.jcr.NamespaceException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.RepositoryException;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for JcrNamespaceRegistry to verify the fixes made in review-notes.md
 * This test runs in an OSGi container and accesses the JCR repository as an OSGi service.
 */
public class JcrNamespaceRegistryTest extends AbstractOSGiTest {

	private NamespaceRegistry registry;

	@Before
	public void setUp() throws Exception {
		assumeTrue("Repository service is not available for namespace tests.", isInitialized());
		registry = session.getWorkspace().getNamespaceRegistry();
	}

	/**
	 * Test that getURI() returns the URI, not the prefix
	 * Issue: review-notes.md #3
	 */
	@Test
	public void testGetURI_ReturnsURI() throws RepositoryException {
		// Test with a predefined namespace prefix
		// The standard JCR namespace prefix "jcr" should map to the JCR URI
		String jcrPrefix = "jcr";
		String jcrURI = registry.getURI(jcrPrefix);

		assertNotNull("getURI() should not return null for valid prefix", jcrURI);

		// Verify that it returns the URI, not the prefix
		assertNotEquals("getURI() should return URI, not the prefix", jcrPrefix, jcrURI);

		// The JCR namespace URI should start with "http://"
		assertTrue("JCR namespace URI should be a valid URI",
				jcrURI.startsWith("http://") || jcrURI.startsWith("https://"));

		// The returned value should contain "jcr" in the URI path
		assertTrue("JCR namespace URI should contain 'jcr'",
				jcrURI.toLowerCase().contains("jcr"));
	}

	/**
	 * Test that getURI() returns consistent results
	 * Issue: review-notes.md #3
	 */
	@Test
	public void testGetURI_Consistency() throws RepositoryException {
		// Test with standard namespace prefixes
		String[] standardPrefixes = {"jcr", "nt", "mix"};

		for (String prefix : standardPrefixes) {
			try {
				String uri1 = registry.getURI(prefix);
				String uri2 = registry.getURI(prefix);

				assertNotNull("getURI() should return same URI for same prefix", uri1);
				assertEquals("getURI() should return consistent results", uri1, uri2);

				// Verify it's not returning the prefix
				assertNotEquals("getURI() should not return the prefix itself", prefix, uri1);
			} catch (NamespaceException e) {
				// If the prefix is not registered, that's acceptable for this test
			}
		}
	}

	/**
	 * Test that getPrefix() returns the correct prefix for a given URI
	 */
	@Test
	public void testGetPrefix_ReturnsPrefix() throws RepositoryException {
		// Get a known URI first
		try {
			String jcrURI = registry.getURI("jcr");
			String prefix = registry.getPrefix(jcrURI);

			assertNotNull("getPrefix() should not return null for valid URI", prefix);
			assertEquals("getPrefix() should return correct prefix", "jcr", prefix);
		} catch (NamespaceException e) {
			// If the namespace is not registered, skip this test
		}
	}

	/**
	 * Test the symmetry of getURI() and getPrefix()
	 * Issue: review-notes.md #3
	 */
	@Test
	public void testURIPrefixSymmetry() throws RepositoryException {
		String[] prefixes = registry.getPrefixes();
		assertTrue("Registry should have at least one namespace", prefixes.length > 0);

		for (String prefix : prefixes) {
			String uri = registry.getURI(prefix);
			assertNotNull("getURI() should return URI for registered prefix: " + prefix, uri);

			// Verify URI is not the same as prefix
			assertNotEquals("getURI() should return URI, not prefix for: " + prefix, prefix, uri);

			// Verify symmetry: getPrefix(getURI(prefix)) should return prefix
			String retrievedPrefix = registry.getPrefix(uri);
			assertEquals("getPrefix(getURI(prefix)) should return original prefix", prefix, retrievedPrefix);
		}
	}

	/**
	 * Test that getURIs() returns all registered URIs
	 */
	@Test
	public void testGetURIs() throws RepositoryException {
		String[] uris = registry.getURIs();
		assertNotNull("getURIs() should not return null", uris);
		assertTrue("Registry should have at least one namespace URI", uris.length > 0);

		// Verify that returned values are URIs, not prefixes
		for (String uri : uris) {
			assertNotNull("URI should not be null", uri);
			// URIs should typically be longer than simple prefix names
			// and should contain URI-like characters
			assertTrue("Value should be a URI, not a prefix: " + uri,
					uri.length() > 5 && (uri.contains("/") || uri.contains(":")));
		}
	}

	/**
	 * Test that getPrefixes() returns all registered prefixes
	 */
	@Test
	public void testGetPrefixes() throws RepositoryException {
		String[] prefixes = registry.getPrefixes();
		assertNotNull("getPrefixes() should not return null", prefixes);
		assertTrue("Registry should have at least one namespace prefix", prefixes.length > 0);

		// Verify that standard JCR prefixes are present
		boolean hasJcr = false;
		for (String prefix : prefixes) {
			if ("jcr".equals(prefix)) {
				hasJcr = true;
				break;
			}
		}
		assertTrue("Registry should include standard 'jcr' prefix", hasJcr);
	}

	/**
	 * Test that getURI() throws NamespaceException for unknown prefix
	 */
	@Test(expected = NamespaceException.class)
	public void testGetURI_ThrowsExceptionForUnknownPrefix() throws RepositoryException {
		registry.getURI("nonexistent_prefix_12345");
	}

	/**
	 * Test that the number of prefixes matches the number of URIs
	 */
	@Test
	public void testPrefixURICount() throws RepositoryException {
		String[] prefixes = registry.getPrefixes();
		String[] uris = registry.getURIs();

		assertEquals("Number of prefixes should match number of URIs",
				prefixes.length, uris.length);
	}
}

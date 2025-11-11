package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import javax.jcr.NamespaceException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for JcrSession to verify the fixes made in review-notes.md
 * This test runs in an OSGi container and accesses the JCR repository as an OSGi service.
 */
public class JcrSessionTest extends AbstractOSGiTest {

	@Before
	public void ensureSessionAvailable() {
		assumeTrue("Repository service is not available for session tests.", isInitialized());
	}

	/**
	 * Test that getAttributeNames() returns an empty array instead of null
	 * Issue: review-notes.md #7
	 */
	@Test
	public void testGetAttributeNames_ReturnsEmptyArray() {
		String[] attributeNames = session.getAttributeNames();
		assertNotNull("getAttributeNames() should not return null", attributeNames);
		assertEquals("getAttributeNames() should return empty array", 0, attributeNames.length);
	}

	/**
	 * Test that itemExists() checks both nodes and properties
	 * Issue: review-notes.md #5
	 */
	@Test
	public void testItemExists_ChecksNodesAndProperties() throws RepositoryException {
		// Test with non-existent path
		assertFalse("Non-existent path should return false", session.itemExists("/non/existent/path"));

		// Root node must exist
		assertTrue("Root node should return true", session.itemExists("/"));

		// Root node primary type property should exist
		assertTrue("Root node primary type property should return true", session.itemExists("/jcr:primaryType"));
	}

	/**
	 * Test that hasPermission() handles root node path without NullPointerException
	 * Issue: review-notes.md #6
	 */
	@Test
	public void testHasPermission_HandlesRootPath() throws RepositoryException {
		// Should not throw NullPointerException for root path
		try {
			boolean hasPermission = session.hasPermission("/", Session.ACTION_READ);
			// The actual permission result depends on the user's privileges
			// We're just testing that it doesn't throw NullPointerException
		} catch (NullPointerException e) {
			fail("hasPermission() should not throw NullPointerException for root path");
		}
	}

	/**
	 * Test that hasPermission() correctly checks ADD_NODE action without NPE
	 * Issue: review-notes.md #6
	 */
	@Test
	public void testHasPermission_AddNodeAction() throws RepositoryException {
		// Should not throw NullPointerException for root path with ADD_NODE action
		try {
			session.hasPermission("/", Session.ACTION_ADD_NODE);
		} catch (NullPointerException e) {
			fail("hasPermission() should not throw NullPointerException for ADD_NODE on root path");
		}
	}

	/**
	 * Test that hasPermission() correctly checks REMOVE action without NPE
	 * Issue: review-notes.md #6
	 */
	@Test
	public void testHasPermission_RemoveAction() throws RepositoryException {
		// Should not throw NullPointerException for root path with REMOVE action
		try {
			session.hasPermission("/", Session.ACTION_REMOVE);
		} catch (NullPointerException e) {
			fail("hasPermission() should not throw NullPointerException for REMOVE on root path");
		}
	}

	/**
	 * Test that setNamespacePrefix() uses containsValue() for URI check
	 * Issue: review-notes.md #4
	 */
	@Test
	public void testSetNamespacePrefix_RejectsPredefinedURI() {
		try {
			// Try to set a predefined URI with a different prefix
			session.setNamespacePrefix("custom", "http://www.jcp.org/jcr/1.0");
			fail("setNamespacePrefix() should reject predefined URI");
		} catch (NamespaceException e) {
			// Expected exception
			assertTrue("Error message should mention predefined URI",
					e.getMessage().contains("Pre-defined URI"));
		} catch (RepositoryException e) {
			fail("Unexpected exception: " + e.getMessage());
		}
	}

	/**
	 * Test that exportDocumentView throws UnsupportedRepositoryOperationException
	 * Issue: review-notes.md #8
	 */
	@Test(expected = UnsupportedRepositoryOperationException.class)
	public void testExportDocumentView_ThrowsUnsupportedException() throws Exception {
		session.exportDocumentView("/", System.out, false, false);
	}

	/**
	 * Test that exportSystemView throws UnsupportedRepositoryOperationException
	 * Issue: review-notes.md #8
	 */
	@Test(expected = UnsupportedRepositoryOperationException.class)
	public void testExportSystemView_ThrowsUnsupportedException() throws Exception {
		session.exportSystemView("/", System.out, false, false);
	}

	/**
	 * Test that getImportContentHandler throws UnsupportedRepositoryOperationException
	 * Issue: review-notes.md #8
	 */
	@Test(expected = UnsupportedRepositoryOperationException.class)
	public void testGetImportContentHandler_ThrowsUnsupportedException() throws Exception {
		session.getImportContentHandler("/", 0);
	}

	/**
	 * Test that importXML throws UnsupportedRepositoryOperationException
	 * Issue: review-notes.md #8
	 */
	@Test(expected = UnsupportedRepositoryOperationException.class)
	public void testImportXML_ThrowsUnsupportedException() throws Exception {
		session.importXML("/", null, 0);
	}

	/**
	 * Test that move throws UnsupportedRepositoryOperationException
	 * Issue: review-notes.md #8
	 */
	@Test(expected = UnsupportedRepositoryOperationException.class)
	public void testMove_ThrowsUnsupportedException() throws Exception {
		session.move("/source", "/destination");
	}

	/**
	 * Test that impersonate throws UnsupportedRepositoryOperationException
	 * Issue: review-notes.md #8
	 */
	@Test(expected = UnsupportedRepositoryOperationException.class)
	public void testImpersonate_ThrowsUnsupportedException() throws Exception {
		session.impersonate(null);
	}
}

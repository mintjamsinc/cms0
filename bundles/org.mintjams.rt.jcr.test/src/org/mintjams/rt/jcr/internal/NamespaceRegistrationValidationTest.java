package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import javax.jcr.NamespaceException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.RepositoryException;

import org.junit.Before;
import org.junit.Test;
import org.mintjams.jcr.util.JCRs;

/**
 * Tests for {@link JCRs#checkNamespaceRegistered(String, NamespaceRegistry)}.
 * <p>
 * This is the central guard used by every JCR write operation that accepts a
 * caller-supplied name (property names via {@code Node.setProperty}, node names
 * via {@code Node.addNode}, and the destination names of {@code Session.move},
 * {@code Workspace.move} and {@code Workspace.copy}). Verifying it here exercises
 * the namespace-registration enforcement shared by all of those paths without
 * requiring write privileges.
 */
public class NamespaceRegistrationValidationTest extends AbstractOSGiTest {

	private NamespaceRegistry registry;

	@Before
	public void ensureRegistryAvailable() throws RepositoryException {
		assumeTrue("Repository service is not available for namespace validation tests.", isInitialized());
		registry = session.getWorkspace().getNamespaceRegistry();
	}

	/**
	 * A name in the default (empty) namespace has no prefix and is always valid.
	 */
	@Test
	public void testPlainNameIsAlwaysValid() throws RepositoryException {
		JCRs.checkNamespaceRegistered("title", registry);
	}

	/**
	 * {@code null} and empty names are treated as valid (the caller validates emptiness elsewhere).
	 */
	@Test
	public void testNullAndEmptyNamesAreValid() throws RepositoryException {
		JCRs.checkNamespaceRegistered(null, registry);
		JCRs.checkNamespaceRegistered("", registry);
	}

	/**
	 * A qualified name using a registered (predefined) prefix is accepted.
	 */
	@Test
	public void testRegisteredPrefixIsAccepted() throws RepositoryException {
		JCRs.checkNamespaceRegistered("jcr:primaryType", registry);
		JCRs.checkNamespaceRegistered("nt:base", registry);
	}

	/**
	 * A qualified name using an unregistered prefix must be rejected. This is the
	 * core of the fix: such a name previously slipped through to the repository.
	 */
	@Test(expected = NamespaceException.class)
	public void testUnregisteredPrefixIsRejected() throws RepositoryException {
		JCRs.checkNamespaceRegistered("nonexistent_prefix_12345:price", registry);
	}

	/**
	 * An expanded name using a registered namespace URI is accepted.
	 */
	@Test
	public void testRegisteredNamespaceUriIsAccepted() throws RepositoryException {
		String jcrURI = registry.getURI("jcr");
		JCRs.checkNamespaceRegistered("{" + jcrURI + "}primaryType", registry);
	}

	/**
	 * An expanded name using an unregistered namespace URI must be rejected.
	 */
	@Test(expected = NamespaceException.class)
	public void testUnregisteredNamespaceUriIsRejected() throws RepositoryException {
		JCRs.checkNamespaceRegistered("{http://www.example.com/ns/nonexistent/12345}price", registry);
	}

	/**
	 * The empty namespace in expanded form ({@code {}name}) is the default namespace and is valid.
	 */
	@Test
	public void testEmptyExpandedNamespaceIsValid() throws RepositoryException {
		JCRs.checkNamespaceRegistered("{}title", registry);
	}

	/**
	 * A malformed expanded name (missing closing brace) is rejected.
	 */
	@Test(expected = NamespaceException.class)
	public void testMalformedExpandedNameIsRejected() throws RepositoryException {
		JCRs.checkNamespaceRegistered("{http://www.example.com/ns/broken", registry);
	}

	/**
	 * Validation must not silently swallow an unregistered prefix.
	 */
	@Test
	public void testUnregisteredPrefixReportsTheName() throws RepositoryException {
		try {
			JCRs.checkNamespaceRegistered("acme:price", registry);
			fail("Expected NamespaceException for unregistered prefix 'acme'.");
		} catch (NamespaceException expected) {
			// expected
		}
	}
}

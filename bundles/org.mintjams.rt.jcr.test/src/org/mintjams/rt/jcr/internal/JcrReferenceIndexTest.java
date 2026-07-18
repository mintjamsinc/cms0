package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mintjams.jcr.security.AdminPrincipal;
import org.mintjams.jcr.security.AuthenticatedCredentials;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Verifies the referential integrity behaviour that is backed by the
 * normalized reference index ({@code jcr_references}): REFERENCE properties
 * block the removal of their target, WEAKREFERENCE properties do not, and the
 * index follows property updates, property removal and node removal, so a
 * target becomes removable exactly when its last strong reference goes away.
 * {@code getReferences()}/{@code getWeakReferences()} are exercised both for
 * their size (the count query) and their contents (the row listing).
 */
public class JcrReferenceIndexTest {

	/** nt:folder is accepted under the root and accepts custom properties. */
	private static final String FOLDER = "nt:folder";
	private static final String REF_NAME = "testRef";

	private BundleContext bundleContext;
	private Repository repository;
	private ServiceReference<Repository> repositoryServiceRef;

	private Session session;
	private Node testRoot;
	private String testRootPath;

	@Before
	public void setUp() throws Exception {
		String basePath = System.getProperty("jcr.test.path", "/");
		bundleContext = FrameworkUtil.getBundle(getClass()).getBundleContext();
		repositoryServiceRef = bundleContext.getServiceReference(Repository.class);
		if (repositoryServiceRef != null) {
			repository = bundleContext.getService(repositoryServiceRef);
		}
		if (repository == null) {
			return;
		}

		try {
			session = repository.login(new AuthenticatedCredentials(new AdminPrincipal() {
				@Override
				public String getName() {
					return "admin";
				}
			}));
			testRoot = session.getNode(basePath).addNode("jcrReferenceIndexTest-" + UUID.randomUUID(), FOLDER);
			session.save();
			testRootPath = testRoot.getPath();
		} catch (RepositoryException ex) {
			System.out.println("[JcrReferenceIndexTest] No writable admin session: " + ex.getMessage());
			if (session != null && session.isLive()) {
				try {
					session.refresh(false);
				} catch (RepositoryException ignore) {
				}
				session.logout();
			}
			session = null;
			testRoot = null;
		}
	}

	@After
	public void tearDown() {
		try {
			if (session != null && session.isLive() && testRootPath != null) {
				session.refresh(false);
				if (session.nodeExists(testRootPath)) {
					session.getNode(testRootPath).remove();
					session.save();
				}
			}
		} catch (Exception ignore) {
		}
		if (session != null && session.isLive()) {
			session.logout();
		}
		session = null;
		testRoot = null;
		testRootPath = null;
		if (bundleContext != null && repositoryServiceRef != null) {
			bundleContext.ungetService(repositoryServiceRef);
			repositoryServiceRef = null;
		}
		repository = null;
		bundleContext = null;
	}

	private void requireWritable() {
		assumeTrue("No writable admin session for the reference index tests.",
				session != null && session.isLive() && testRoot != null);
	}

	private Node addFolder(String name) throws RepositoryException {
		return testRoot.addNode(name, FOLDER);
	}

	private void setReference(Node source, Node target, int type) throws RepositoryException {
		source.setProperty(REF_NAME,
				session.getValueFactory().createValue(target.getIdentifier(), type));
	}

	private static long count(PropertyIterator i) {
		long n = 0;
		while (i.hasNext()) {
			i.nextProperty();
			n++;
		}
		return n;
	}

	@Test
	public void referencedNodeCannotBeRemoved() throws RepositoryException {
		requireWritable();

		Node target = addFolder("target");
		Node source = addFolder("source");
		setReference(source, target, PropertyType.REFERENCE);
		session.save();

		assertEquals(1, target.getReferences().getSize());
		assertEquals(1, count(target.getReferences()));
		assertEquals(REF_NAME, target.getReferences().nextProperty().getName());
		assertEquals(0, target.getWeakReferences().getSize());

		target.remove();
		try {
			session.save();
			fail("Removing a node that is still referenced must fail.");
		} catch (ReferentialIntegrityException expected) {
			session.refresh(false);
		}
		assertTrue(session.nodeExists(testRootPath + "/target"));
	}

	@Test
	public void removalSucceedsOnceReferenceIsRemoved() throws RepositoryException {
		requireWritable();

		Node target = addFolder("target");
		Node source = addFolder("source");
		setReference(source, target, PropertyType.REFERENCE);
		session.save();

		source.getProperty(REF_NAME).remove();
		session.save();
		assertEquals(0, target.getReferences().getSize());

		target.remove();
		session.save();
		assertFalse(session.nodeExists(testRootPath + "/target"));
	}

	@Test
	public void weakReferenceDoesNotBlockRemoval() throws RepositoryException {
		requireWritable();

		Node target = addFolder("target");
		Node source = addFolder("source");
		setReference(source, target, PropertyType.WEAKREFERENCE);
		session.save();

		assertEquals(1, target.getWeakReferences().getSize());
		assertEquals(1, count(target.getWeakReferences()));
		assertEquals(0, target.getReferences().getSize());

		target.remove();
		session.save();
		assertFalse(session.nodeExists(testRootPath + "/target"));
	}

	@Test
	public void retargetingReferenceUnblocksPreviousTarget() throws RepositoryException {
		requireWritable();

		Node first = addFolder("first");
		Node second = addFolder("second");
		Node source = addFolder("source");
		setReference(source, first, PropertyType.REFERENCE);
		session.save();

		setReference(source, second, PropertyType.REFERENCE);
		session.save();
		assertEquals(0, first.getReferences().getSize());
		assertEquals(1, second.getReferences().getSize());

		first.remove();
		session.save();
		assertFalse(session.nodeExists(testRootPath + "/first"));

		second.remove();
		try {
			session.save();
			fail("Removing the new target while it is still referenced must fail.");
		} catch (ReferentialIntegrityException expected) {
			session.refresh(false);
		}
	}

	@Test
	public void removingReferencingNodeUnblocksTarget() throws RepositoryException {
		requireWritable();

		Node target = addFolder("target");
		Node source = addFolder("source");
		setReference(source, target, PropertyType.REFERENCE);
		session.save();

		source.remove();
		session.save();
		assertEquals(0, target.getReferences().getSize());

		target.remove();
		session.save();
		assertFalse(session.nodeExists(testRootPath + "/target"));
	}

	@Test
	public void removingReferencedSubtreeTogetherSucceeds() throws RepositoryException {
		requireWritable();

		// Source and target live in the same subtree; removing the whole
		// subtree soft-deletes the referencing property row in the same
		// transaction, so the check must pass.
		Node container = addFolder("container");
		Node target = container.addNode("target", FOLDER);
		Node source = container.addNode("source", FOLDER);
		setReference(source, target, PropertyType.REFERENCE);
		session.save();

		container.remove();
		session.save();
		assertFalse(session.nodeExists(testRootPath + "/container"));
	}
}

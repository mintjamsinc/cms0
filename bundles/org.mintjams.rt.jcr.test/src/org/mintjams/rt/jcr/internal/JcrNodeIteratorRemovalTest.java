package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
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
 * Verifies that a child iteration reaches every child when the children are
 * removed while it runs. The iterator reads the children a page at a time (at
 * most half the session node cache size, 32 by default), so {@link #COUNT}
 * children span several pages; a page addressed by a row offset would pass
 * over as many children as were removed before it, which is how
 * {@code Node.remove()} on a folder once left children live under a removed
 * parent.
 */
public class JcrNodeIteratorRemovalTest {

	private static final String FOLDER = "nt:folder";
	private static final String FILE = "nt:file";
	private static final String RESOURCE = "nt:resource";
	private static final String CONTENT = "jcr:content";
	private static final int COUNT = 100;

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
			testRoot = session.getNode(basePath).addNode("jcrNodeIteratorRemovalTest-" + UUID.randomUUID(), FOLDER);
			session.save();
			testRootPath = testRoot.getPath();
		} catch (RepositoryException ex) {
			System.out.println("[JcrNodeIteratorRemovalTest] No writable admin session: " + ex.getMessage());
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
		assumeTrue("No writable admin session for the iterator removal tests.",
				session != null && session.isLive() && testRoot != null);
	}

	private List<String> addFiles(Node parent, int count) throws RepositoryException {
		List<String> identifiers = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			String name = "file-" + i + ".txt";
			Node file = parent.addNode(name, FILE);
			Node content = file.addNode(CONTENT, RESOURCE);
			content.setProperty("jcr:data", session.getValueFactory()
					.createBinary(new ByteArrayInputStream(name.getBytes(StandardCharsets.UTF_8))));
			content.setProperty("jcr:mimeType", "text/plain");
			content.setProperty("jcr:lastModified", Calendar.getInstance());
			identifiers.add(file.getIdentifier());
		}
		return identifiers;
	}

	private void assertAllGone(List<String> identifiers) throws RepositoryException {
		int left = 0;
		for (String identifier : identifiers) {
			try {
				session.getNodeByIdentifier(identifier);
				left++;
			} catch (ItemNotFoundException expected) {
			}
		}
		assertEquals("children still live after their removal", 0, left);
	}

	@Test
	public void testRemoveFolderRemovesEveryChild() throws Exception {
		requireWritable();

		Node folder = testRoot.addNode("folder", FOLDER);
		List<String> identifiers = addFiles(folder, COUNT);
		session.save();

		folder.remove();
		session.save();

		assertFalse(session.nodeExists(testRootPath + "/folder"));
		assertAllGone(identifiers);
	}

	@Test
	public void testRemoveWhileIteratingVisitsEveryChild() throws Exception {
		requireWritable();

		Node folder = testRoot.addNode("folder", FOLDER);
		List<String> identifiers = addFiles(folder, COUNT);
		session.save();

		int visited = 0;
		for (NodeIterator i = folder.getNodes(); i.hasNext();) {
			i.nextNode().remove();
			visited++;
		}
		assertEquals(COUNT, visited);
		assertFalse(folder.getNodes().hasNext());

		session.save();
		assertAllGone(identifiers);
	}

	@Test
	public void testSkipStillAddressesThePosition() throws Exception {
		requireWritable();

		Node folder = testRoot.addNode("folder", FOLDER);
		addFiles(folder, COUNT);
		session.save();

		List<String> all = new ArrayList<>();
		for (NodeIterator i = folder.getNodes(); i.hasNext();) {
			all.add(i.nextNode().getName());
		}
		assertEquals(COUNT, all.size());

		for (int skip : new int[] { 0, 1, 31, 32, 33, 70, COUNT - 1, COUNT }) {
			NodeIterator i = folder.getNodes();
			i.skip(skip);
			List<String> page = new ArrayList<>();
			while (i.hasNext() && page.size() < 50) {
				page.add(i.nextNode().getName());
			}
			assertEquals("page after skip(" + skip + ")",
					all.subList(skip, Math.min(skip + 50, COUNT)), page);
		}

		// Reading on after a skip continues from where the skip landed.
		NodeIterator i = folder.getNodes();
		List<String> mixed = new ArrayList<>();
		for (int n = 0; n < 10; n++) {
			mixed.add(i.nextNode().getName());
		}
		i.skip(40);
		while (i.hasNext()) {
			mixed.add(i.nextNode().getName());
		}
		List<String> expected = new ArrayList<>(all.subList(0, 10));
		expected.addAll(all.subList(50, COUNT));
		if (!expected.equals(mixed)) {
			fail("read 10, skip 40, read on: expected " + expected + " but was " + mixed);
		}
	}
}

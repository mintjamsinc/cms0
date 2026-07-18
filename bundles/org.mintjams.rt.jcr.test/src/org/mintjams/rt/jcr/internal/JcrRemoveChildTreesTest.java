package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.jcr.Node;
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
 * Verifies the batched form of the set-based removal,
 * {@code org.mintjams.jcr.Node#removeChildTrees(Collection)}: a container's
 * children are removed with their subtrees as one unit, counted the way
 * {@code removeTree()} counts (files and folders, never {@code jcr:content}),
 * and a batch stays replayable after a rollback.
 */
public class JcrRemoveChildTreesTest {

	private static final String FOLDER = "nt:folder";
	private static final String FILE = "nt:file";
	private static final String RESOURCE = "nt:resource";
	private static final String CONTENT = "jcr:content";

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
			testRoot = session.getNode(basePath).addNode("jcrRemoveChildTreesTest-" + UUID.randomUUID(), FOLDER);
			session.save();
			testRootPath = testRoot.getPath();
		} catch (RepositoryException ex) {
			System.out.println("[JcrRemoveChildTreesTest] No writable admin session: " + ex.getMessage());
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
		assumeTrue("No writable admin session for the batched removal tests.",
				session != null && session.isLive() && testRoot != null);
	}

	private Node addFile(Node parent, String name) throws RepositoryException {
		Node file = parent.addNode(name, FILE);
		Node content = file.addNode(CONTENT, RESOURCE);
		content.setProperty("jcr:data", session.getValueFactory()
				.createBinary(new ByteArrayInputStream(name.getBytes(StandardCharsets.UTF_8))));
		content.setProperty("jcr:mimeType", "text/plain");
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		return file;
	}

	private List<Node> addFiles(Node parent, int count) throws RepositoryException {
		List<Node> files = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			files.add(addFile(parent, "file-" + i + ".txt"));
		}
		return files;
	}

	private static long removeChildTrees(Node container, java.util.Collection<Node> children)
			throws RepositoryException {
		return ((org.mintjams.jcr.Node) container).removeChildTrees(children);
	}

	@Test
	public void removesFilesAndCountsThemWithoutTheirContent() throws RepositoryException {
		requireWritable();

		Node folder = testRoot.addNode("folder", FOLDER);
		List<Node> files = addFiles(folder, 5);
		session.save();

		// Five files, each with a jcr:content — only the files are counted.
		assertEquals(5, removeChildTrees(folder, files));
		session.save();

		assertTrue("The container itself must survive.", session.nodeExists(folder.getPath()));
		assertFalse(session.nodeExists(folder.getPath() + "/file-0.txt"));
		assertFalse(session.nodeExists(folder.getPath() + "/file-4.txt"));
		assertFalse(session.getNode(folder.getPath()).getNodes().hasNext());
	}

	@Test
	public void removesAFolderChildWithItsWholeSubtree() throws RepositoryException {
		requireWritable();

		Node folder = testRoot.addNode("folder", FOLDER);
		Node subFolder = folder.addNode("sub", FOLDER);
		addFiles(subFolder, 3);
		Node deep = subFolder.addNode("deep", FOLDER);
		addFiles(deep, 2);
		session.save();

		String subPath = subFolder.getPath();

		// sub + 3 files + deep + 2 files = 7 countable items.
		assertEquals(7, removeChildTrees(folder, Collections.singletonList(subFolder)));
		session.save();

		assertFalse(session.nodeExists(subPath));
		assertTrue(session.nodeExists(folder.getPath()));
	}

	@Test
	public void removesFilesAndFoldersInOneBatch() throws RepositoryException {
		requireWritable();

		Node folder = testRoot.addNode("folder", FOLDER);
		List<Node> children = new ArrayList<>(addFiles(folder, 2));
		Node empty = folder.addNode("empty", FOLDER);
		children.add(empty);
		session.save();

		assertEquals(3, removeChildTrees(folder, children));
		session.save();

		assertFalse(session.getNode(folder.getPath()).getNodes().hasNext());
	}

	@Test
	public void countsTheSameAsRemoveTree() throws RepositoryException {
		requireWritable();

		// Two identical subtrees: one removed whole, one child by child.
		Node whole = testRoot.addNode("whole", FOLDER);
		addFiles(whole, 4);
		whole.addNode("sub", FOLDER);
		Node batched = testRoot.addNode("batched", FOLDER);
		addFiles(batched, 4);
		batched.addNode("sub", FOLDER);
		session.save();

		long byRemoveTree = ((org.mintjams.jcr.Node) whole).removeTree();

		List<Node> children = new ArrayList<>();
		for (javax.jcr.NodeIterator it = batched.getNodes(); it.hasNext();) {
			children.add(it.nextNode());
		}
		// The container is not part of the batch, so it accounts for the odd one.
		long byBatch = removeChildTrees(batched, children) + 1;

		assertEquals(byRemoveTree, byBatch);
		session.save();
	}

	@Test
	public void ignoresChildrenThatAreNoLongerLive() throws RepositoryException {
		requireWritable();

		Node folder = testRoot.addNode("folder", FOLDER);
		List<Node> files = addFiles(folder, 2);
		session.save();

		// Remove one of them first: the batch must count only what it removed,
		// so that replaying it after a rollback cannot double-count.
		assertEquals(1, ((org.mintjams.jcr.Node) files.get(0)).removeTree());
		assertEquals(1, removeChildTrees(folder, files));
		session.save();

		assertFalse(session.getNode(folder.getPath()).getNodes().hasNext());
	}

	@Test
	public void removesNothingForAnEmptyBatch() throws RepositoryException {
		requireWritable();

		Node folder = testRoot.addNode("folder", FOLDER);
		addFiles(folder, 1);
		session.save();

		assertEquals(0, removeChildTrees(folder, Collections.<Node>emptyList()));
		session.save();

		assertTrue(session.nodeExists(folder.getPath() + "/file-0.txt"));
	}

	@Test
	public void rejectsANodeThatIsNotAChild() throws RepositoryException {
		requireWritable();

		Node folder = testRoot.addNode("folder", FOLDER);
		Node sub = folder.addNode("sub", FOLDER);
		Node grandChild = addFile(sub, "deep.txt");
		session.save();

		try {
			removeChildTrees(folder, Collections.singletonList(grandChild));
			fail("A node that is not a child must be rejected: the subtree-wide"
					+ " checks are answered for the container and would not cover it.");
		} catch (IllegalArgumentException expected) {
		}

		session.refresh(false);
		assertTrue("Nothing may be removed when the batch is rejected.",
				session.nodeExists(sub.getPath() + "/deep.txt"));
	}
}

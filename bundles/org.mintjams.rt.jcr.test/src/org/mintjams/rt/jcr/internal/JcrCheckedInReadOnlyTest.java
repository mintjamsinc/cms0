package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Collections;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mintjams.jcr.security.AdminPrincipal;
import org.mintjams.jcr.security.AuthenticatedCredentials;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Verifies that a checked-in versionable node and everything under it are
 * read-only until the node is checked out again (JCR 2.0, 15.2.2), while the
 * node itself can still be removed or moved as a whole from a parent that is
 * checked out.
 */
public class JcrCheckedInReadOnlyTest {

	private static final String FOLDER = "nt:folder";
	private static final String FILE = "nt:file";
	private static final String RESOURCE = "nt:resource";
	private static final String CONTENT = "jcr:content";
	private static final String VERSIONABLE = "mix:versionable";

	private BundleContext bundleContext;
	private Repository repository;
	private ServiceReference<Repository> repositoryServiceRef;

	private Session session;
	private VersionManager versionManager;
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
			versionManager = session.getWorkspace().getVersionManager();
			testRoot = session.getNode(basePath).addNode("jcrCheckedInReadOnlyTest-" + UUID.randomUUID(), FOLDER);
			session.save();
			testRootPath = testRoot.getPath();
		} catch (RepositoryException ex) {
			System.out.println("[JcrCheckedInReadOnlyTest] No writable admin session: " + ex.getMessage());
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
		versionManager = null;
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
		assumeTrue("No writable admin session for the checked-in tests.",
				session != null && session.isLive() && testRoot != null);
	}

	private Node addFile(Node parent, String name, String text) throws RepositoryException {
		Node file = parent.addNode(name, FILE);
		Node content = file.addNode(CONTENT, RESOURCE);
		content.setProperty("jcr:data", session.getValueFactory()
				.createBinary(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
		content.setProperty("jcr:mimeType", "text/plain");
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		return file;
	}

	/** Put the saved node under version control and check it in, as the GraphQL addVersionControl does. */
	private void checkedIn(Node node) throws RepositoryException {
		node.addMixin(VERSIONABLE);
		session.save();
		versionManager.checkin(node.getPath());
	}

	private Node content(Node file) throws RepositoryException {
		return session.getNode(file.getPath() + "/" + CONTENT);
	}

	@Test
	public void refusesPropertyChangesUnderACheckedInFile() throws RepositoryException {
		requireWritable();

		Node file = addFile(testRoot, "a.txt", "one");
		session.save();
		checkedIn(file);

		try {
			content(file).setProperty("jcr:mimeType", "text/markdown");
			fail("A property under a checked-in file must not be changed.");
		} catch (VersionException expected) {
			assertTrue("The refusal names the checked-in node: " + expected.getMessage(),
					expected.getMessage().contains(file.getPath() + "'"));
		}
		try {
			session.getNode(file.getPath()).setProperty("note", "x");
			fail("A property on a checked-in node must not be set.");
		} catch (VersionException expected) {
		}
		try {
			content(file).getProperty("jcr:mimeType").remove();
			fail("A property under a checked-in file must not be removed.");
		} catch (VersionException expected) {
		}
		try {
			content(file).getProperty("jcr:mimeType").setValue("text/markdown");
			fail("Property.setValue under a checked-in file must be refused too.");
		} catch (VersionException expected) {
		}
		session.refresh(false);
		assertEquals("text/plain", content(file).getProperty("jcr:mimeType").getString());
	}

	@Test
	public void allowsChangesOnceCheckedOutAndRecordsThemOnCheckin() throws RepositoryException {
		requireWritable();

		Node file = addFile(testRoot, "a.txt", "one");
		session.save();
		checkedIn(file);

		versionManager.checkout(file.getPath());
		content(file).setProperty("jcr:mimeType", "text/markdown");
		session.save();
		String version = versionManager.checkin(file.getPath()).getName();

		assertEquals("1.1", version);
		assertFalse(versionManager.isCheckedOut(file.getPath()));
		assertEquals("text/markdown", content(file).getProperty("jcr:mimeType").getString());
	}

	@Test
	public void refusesAddingAndRemovingNodesUnderACheckedInFolder() throws RepositoryException {
		requireWritable();

		Node folder = testRoot.addNode("versioned", FOLDER);
		Node file = addFile(folder, "inside.txt", "one");
		session.save();
		checkedIn(folder);

		try {
			session.getNode(folder.getPath()).addNode("new", FOLDER);
			fail("A child must not be added to a checked-in folder.");
		} catch (VersionException expected) {
		}
		try {
			session.getNode(file.getPath()).remove();
			fail("A child of a checked-in folder must not be removed.");
		} catch (VersionException expected) {
		}
		try {
			((org.mintjams.jcr.Node) session.getNode(file.getPath())).removeTree();
			fail("removeTree must refuse a child of a checked-in folder.");
		} catch (VersionException expected) {
		}
		try {
			((org.mintjams.jcr.Node) session.getNode(folder.getPath()))
					.removeChildTrees(Collections.singletonList(session.getNode(file.getPath())));
			fail("removeChildTrees must refuse the children of a checked-in folder.");
		} catch (VersionException expected) {
		}
		try {
			content(file).setProperty("jcr:mimeType", "text/markdown");
			fail("A file under a checked-in folder is read-only too.");
		} catch (VersionException expected) {
		}
		session.refresh(false);
		assertTrue(session.nodeExists(file.getPath()));
	}

	@Test
	public void aCheckedOutVersionableNodeUnderACheckedInOneIsWritable() throws RepositoryException {
		requireWritable();

		Node folder = testRoot.addNode("versioned", FOLDER);
		Node file = addFile(folder, "inside.txt", "one");
		session.save();
		checkedIn(file);
		checkedIn(folder);

		// The nearest versionable node decides.
		versionManager.checkout(file.getPath());
		content(file).setProperty("jcr:mimeType", "text/markdown");
		session.save();
		assertTrue(versionManager.isCheckedOut(file.getPath()));
		assertFalse(versionManager.isCheckedOut(folder.getPath()));
	}

	@Test
	public void removesACheckedInFileFromAFolderThatIsCheckedOut() throws RepositoryException {
		requireWritable();

		Node first = addFile(testRoot, "first.txt", "one");
		Node second = addFile(testRoot, "second.txt", "two");
		session.save();
		checkedIn(first);
		checkedIn(second);

		// The parent is what changes; the checked-in node goes as a whole.
		session.getNode(first.getPath()).remove();
		assertEquals(1, ((org.mintjams.jcr.Node) session.getNode(second.getPath())).removeTree());
		session.save();

		assertFalse(session.nodeExists(first.getPath()));
		assertFalse(session.nodeExists(second.getPath()));
	}

	@Test
	public void movesACheckedInFileButNotIntoOrOutOfACheckedInFolder() throws RepositoryException {
		requireWritable();

		Node from = testRoot.addNode("from", FOLDER);
		Node to = testRoot.addNode("to", FOLDER);
		Node locked = testRoot.addNode("locked", FOLDER);
		Node file = addFile(from, "a.txt", "one");
		Node inside = addFile(locked, "inside.txt", "two");
		session.save();
		checkedIn(file);
		checkedIn(locked);

		session.move(file.getPath(), to.getPath() + "/a.txt");
		session.save();
		assertTrue(session.nodeExists(to.getPath() + "/a.txt"));

		try {
			session.move(to.getPath() + "/a.txt", locked.getPath() + "/a.txt");
			fail("Nothing may be moved into a checked-in folder.");
		} catch (VersionException expected) {
		}
		try {
			session.move(inside.getPath(), to.getPath() + "/inside.txt");
			fail("Nothing may be moved out of a checked-in folder.");
		} catch (VersionException expected) {
		}
		try {
			session.getWorkspace().copy(to.getPath() + "/a.txt", locked.getPath() + "/copy.txt");
			fail("Nothing may be copied into a checked-in folder.");
		} catch (VersionException expected) {
		}
		session.refresh(false);
		assertTrue(session.nodeExists(inside.getPath()));
		assertFalse(session.nodeExists(locked.getPath() + "/a.txt"));
	}

	@Test
	public void reportsANodeOutsideVersionControlAsCheckedOut() throws RepositoryException {
		requireWritable();

		Node file = addFile(testRoot, "plain.txt", "one");
		session.save();

		// Walks up to the root without finding jcr:isCheckedOut.
		assertTrue(versionManager.isCheckedOut(file.getPath()));
		assertTrue(versionManager.isCheckedOut(content(file).getPath()));
		assertTrue(versionManager.isCheckedOut("/"));
	}
}

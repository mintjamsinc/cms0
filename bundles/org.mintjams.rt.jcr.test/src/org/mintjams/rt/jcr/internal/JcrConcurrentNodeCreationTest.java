package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Verifies that the repository never stores two live nodes at the same path,
 * even when two sessions race to create it, and that the delete-then-recreate
 * pattern keeps working within a single session once that guarantee is enforced.
 *
 * <p>These tests need a session that can write. The test bundle can only see the
 * public JCR API (not the internal system credentials), so a writable session is
 * obtained through {@link Repository#login}, trying, in order:
 * <ol>
 *   <li>credentials from the system properties {@code jcr.test.user} /
 *       {@code jcr.test.password} (and the optional {@code jcr.test.workspace}), then</li>
 *   <li>{@code admin}/{@code admin}.</li>
 * </ol>
 * The probe creates a node under {@code jcr.test.path} (default {@code /}); set it
 * to a folder the test user may write to when the workspace root is not writable
 * for ordinary users.
 *
 * <p>When a writable session was requested — {@code jcr.test.user} is set, or
 * {@code -Djcr.test.requireWritable=true} — but none can be obtained, the tests
 * fail with a diagnostic listing each attempt and why it was rejected, so the
 * reason is visible in the result file. Otherwise (e.g. a minimal runtime with
 * only a read-only guest) they self-skip, consistent with the rest of this suite.
 *
 * <p>Children are created as {@code nt:folder} because the root node type
 * ({@code mi:root}) only allows {@code nt:hierarchyNode} children.
 */
public class JcrConcurrentNodeCreationTest {

	/** nt:folder is an nt:hierarchyNode, so it is accepted under the root and under another folder. */
	private static final String FOLDER = "nt:folder";

	private BundleContext bundleContext;
	private Repository repository;
	private ServiceReference<Repository> repositoryServiceRef;

	/** Owns {@link #testRoot}; used for setup, verification and cleanup. */
	private Session rootSession;
	/** A dedicated container created fresh for each test and removed afterwards. */
	private Node testRoot;
	private String testRootPath;

	/** Credentials proven to be able to write, discovered once in {@link #setUp()}. */
	private String workUser;
	private String workPassword;
	private String workWorkspace;

	/**
	 * Path under which the test creates its container. The workspace root is often
	 * not writable for ordinary (role-based) users, so point this at a folder the
	 * test user may write to via {@code -Djcr.test.path}. Defaults to the root.
	 */
	private String basePath;
	/**
	 * Whether the operator asked for these tests to run (credentials given, or
	 * {@code -Djcr.test.requireWritable=true}). When true, an unavailable writable
	 * session is a hard failure with a diagnostic instead of a silent skip, so the
	 * reason shows up in the result file.
	 */
	private boolean writableRequested;
	/** Per-attempt explanation of why a writable session could not be obtained. */
	private final StringBuilder writableDiagnostic = new StringBuilder();

	@Before
	public void setUp() throws Exception {
		basePath = System.getProperty("jcr.test.path", "/");
		writableRequested = isNotEmpty(System.getProperty("jcr.test.user"))
				|| Boolean.getBoolean("jcr.test.requireWritable");

		bundleContext = FrameworkUtil.getBundle(getClass()).getBundleContext();
		repositoryServiceRef = bundleContext.getServiceReference(Repository.class);
		if (repositoryServiceRef != null) {
			repository = bundleContext.getService(repositoryServiceRef);
		}
		if (repository == null) {
			writableDiagnostic.append("- no javax.jcr.Repository service is registered\n");
			return;
		}

		rootSession = loginWritable();
		if (rootSession == null) {
			return;
		}

		Node base = rootSession.getNode(basePath);
		testRoot = base.addNode("jcrConcurrencyTest-" + UUID.randomUUID(), FOLDER);
		rootSession.save();
		testRootPath = testRoot.getPath();
	}

	@After
	public void tearDown() {
		try {
			if (rootSession != null && rootSession.isLive() && testRoot != null) {
				rootSession.refresh(false);
				if (rootSession.nodeExists(testRootPath)) {
					rootSession.getNode(testRootPath).remove();
					rootSession.save();
				}
			}
		} catch (Exception ignore) {
		}
		if (rootSession != null && rootSession.isLive()) {
			rootSession.logout();
		}
		rootSession = null;
		testRoot = null;
		testRootPath = null;
		if (bundleContext != null && repositoryServiceRef != null) {
			bundleContext.ungetService(repositoryServiceRef);
			repositoryServiceRef = null;
		}
		repository = null;
		bundleContext = null;
	}

	private boolean isWritableReady() {
		return repository != null && rootSession != null && rootSession.isLive() && testRoot != null;
	}

	/**
	 * Gate every test on a writable session. When one was requested (credentials
	 * given, or {@code -Djcr.test.requireWritable=true}) but could not be obtained,
	 * fail with a diagnostic so the reason is visible in the result file; otherwise
	 * skip, as before, in environments with only a read-only guest.
	 */
	private void requireWritable() {
		if (isWritableReady()) {
			return;
		}
		String message = "No writable JCR session for the concurrency tests."
				+ " Provide one with -Djcr.test.user=<user> -Djcr.test.password=<pass>"
				+ " [-Djcr.test.workspace=<ws>] [-Djcr.test.path=<writable base path>]."
				+ " Attempts:\n" + (writableDiagnostic.length() == 0 ? "  (none)\n" : writableDiagnostic);
		System.out.println("[JcrConcurrentNodeCreationTest] " + message);
		if (writableRequested) {
			fail(message);
		}
		assumeTrue(message, false);
	}

	private static boolean isNotEmpty(String s) {
		return s != null && !s.isEmpty();
	}

	/**
	 * The historical race: two sessions create the same path at the same time.
	 * With path uniqueness enforced, exactly one wins each round and the
	 * repository never ends up with two live nodes at that path. The barrier
	 * lines the two sessions up just before the create+save so they genuinely
	 * overlap; without the fix this reliably produced duplicates within a few
	 * rounds.
	 */
	@Test
	public void testConcurrentSamePathCreation_NeverDuplicates() throws Exception {
		requireWritable();

		final int rounds = 25;
		for (int round = 0; round < rounds; round++) {
			final String name = "race-" + round;
			final String childPath = testRootPath + "/" + name;
			final CyclicBarrier barrier = new CyclicBarrier(2);
			final CountDownLatch done = new CountDownLatch(2);
			final AtomicInteger successes = new AtomicInteger(0);
			final AtomicInteger conflicts = new AtomicInteger(0);
			final AtomicInteger unexpected = new AtomicInteger(0);

			Runnable creator = () -> {
				Session session = null;
				try {
					session = login(workUser, workPassword, workWorkspace);
					Node parent = session.getNode(testRootPath);
					// Align both threads here so the existence checks interleave.
					barrier.await(30, TimeUnit.SECONDS);
					parent.addNode(name, FOLDER);
					session.save();
					successes.incrementAndGet();
				} catch (javax.jcr.ItemExistsException ex) {
					// The clean, expected outcome for the losing session.
					conflicts.incrementAndGet();
				} catch (RepositoryException ex) {
					// A duplicate was rejected by the database under contention but did
					// not surface as ItemExistsException (e.g. a lock timeout). Still a
					// rejection, not a silent duplicate, so it is acceptable.
					conflicts.incrementAndGet();
				} catch (Exception ex) {
					unexpected.incrementAndGet();
				} finally {
					if (session != null && session.isLive()) {
						session.logout();
					}
					done.countDown();
				}
			};

			Thread t1 = new Thread(creator, "creator-1-round-" + round);
			Thread t2 = new Thread(creator, "creator-2-round-" + round);
			t1.start();
			t2.start();
			assertTrue("Round " + round + " timed out.", done.await(60, TimeUnit.SECONDS));
			t1.join(TimeUnit.SECONDS.toMillis(10));
			t2.join(TimeUnit.SECONDS.toMillis(10));

			assertEquals("Round " + round + ": no unexpected failures expected.", 0, unexpected.get());
			assertEquals("Round " + round + ": exactly one session must create the node.",
					1, successes.get());
			assertEquals("Round " + round + ": the other session must be rejected.",
					1, conflicts.get());

			// The decisive check: the storage must hold exactly one live node here.
			rootSession.refresh(false);
			assertTrue("Round " + round + ": the node must exist after the race.",
					rootSession.nodeExists(childPath));
			assertEquals("Round " + round + ": exactly one live node must exist at the path.",
					1, countChildren(testRootPath, name));
		}
	}

	/**
	 * Delete then recreate the same path in separate transactions. This always
	 * worked, and must keep working: the removed row is purged on commit, so the
	 * recreate sees a clean slate.
	 */
	@Test
	public void testDeleteThenRecreate_SeparateSaves() throws Exception {
		requireWritable();

		final String name = "recreate-separate";
		final String childPath = testRootPath + "/" + name;

		String firstId = rootSession.getNode(testRootPath).addNode(name, FOLDER).getIdentifier();
		rootSession.save();
		assertTrue(rootSession.nodeExists(childPath));

		rootSession.getNode(childPath).remove();
		rootSession.save();
		assertFalse("The node must be gone after removal.", rootSession.nodeExists(childPath));

		String secondId = rootSession.getNode(testRootPath).addNode(name, FOLDER).getIdentifier();
		rootSession.save();

		assertTrue("The node must exist again after recreation.", rootSession.nodeExists(childPath));
		assertNotEquals("Recreation must produce a genuinely new node.", firstId, secondId);
		assertEquals("Exactly one live node must exist at the path.", 1, countChildren(testRootPath, name));
	}

	/**
	 * Delete and recreate the same path within a single transaction. This is the
	 * case the unique-path guarantee must not break: the removed row still
	 * physically exists (it is only purged on commit) while the replacement is
	 * inserted, so path uniqueness has to ignore removed rows.
	 */
	@Test
	public void testDeleteThenRecreate_SameSave() throws Exception {
		requireWritable();

		final String name = "recreate-same-save";
		final String childPath = testRootPath + "/" + name;

		// Commit an initial node so the removal below acts on committed state.
		String firstId = rootSession.getNode(testRootPath).addNode(name, FOLDER).getIdentifier();
		rootSession.save();

		// Remove and recreate without an intervening save: both the soft delete of
		// the old row and the insert of the new row are committed together.
		rootSession.getNode(childPath).remove();
		String secondId = rootSession.getNode(testRootPath).addNode(name, FOLDER).getIdentifier();
		rootSession.save();

		assertTrue("The node must exist after delete+recreate in one save.", rootSession.nodeExists(childPath));
		assertNotEquals("The recreated node must be a new node.", firstId, secondId);
		assertEquals("Exactly one live node must exist at the path.", 1, countChildren(testRootPath, name));
	}

	/**
	 * Create, delete and recreate the same path within a single transaction,
	 * where the node was never committed before. Exercises the same path-key
	 * lifecycle entirely on transient, uncommitted rows.
	 */
	@Test
	public void testCreateDeleteRecreate_SameSave() throws Exception {
		requireWritable();

		final String name = "recreate-transient";
		final String childPath = testRootPath + "/" + name;

		rootSession.getNode(testRootPath).addNode(name, FOLDER);
		rootSession.getNode(childPath).remove();
		rootSession.getNode(testRootPath).addNode(name, FOLDER);
		rootSession.save();

		assertTrue("The node must exist after create+delete+recreate in one save.",
				rootSession.nodeExists(childPath));
		assertEquals("Exactly one live node must exist at the path.", 1, countChildren(testRootPath, name));
	}

	/**
	 * Within a single session: remove a node, list with getNodes(), recreate it,
	 * and list again. getNodes() must reflect each transient step before any save
	 * — the removed node must drop out of the listing, and after recreation the
	 * listing must show exactly the new node, never the stale removed row beside
	 * its replacement.
	 */
	@Test
	public void testDeleteListRecreateList_SameSession() throws Exception {
		requireWritable();

		final String name = "recreate-listing";
		final String childPath = testRootPath + "/" + name;
		Node parent = rootSession.getNode(testRootPath);

		// Commit an initial node so the removal below acts on committed state.
		String firstId = parent.addNode(name, FOLDER).getIdentifier();
		rootSession.save();
		assertEquals("Precondition: the node is listed once.", 1, countChildren(testRootPath, name));

		// Remove (transient, not yet saved): getNodes() must stop listing it.
		rootSession.getNode(childPath).remove();
		assertEquals("After remove, getNodes() must not list the removed node.",
				0, countChildren(testRootPath, name));
		assertFalse("After remove, the path must not exist transiently.", rootSession.nodeExists(childPath));

		// Recreate (transient, not yet saved): getNodes() must list exactly the new node.
		String secondId = parent.addNode(name, FOLDER).getIdentifier();
		assertNotEquals("The recreated node must be a new node.", firstId, secondId);
		assertEquals("After recreate, getNodes() must list exactly one node.",
				1, countChildren(testRootPath, name));
		assertEquals("getNodes() must return the recreated node, not the removed one.",
				secondId, rootSession.getNode(testRootPath).getNodes(name).nextNode().getIdentifier());

		// Commit, then read back committed state.
		rootSession.save();
		rootSession.refresh(false);
		assertEquals("After save, exactly one node must be listed.", 1, countChildren(testRootPath, name));
		assertEquals("After save, getNodes() must still return the recreated node.",
				secondId, rootSession.getNode(testRootPath).getNodes(name).nextNode().getIdentifier());
	}

	/** Counts the live child nodes of the given parent that carry the given name. */
	private int countChildren(String parentPath, String name) throws RepositoryException {
		int count = 0;
		NodeIterator i = rootSession.getNode(parentPath).getNodes(name);
		while (i.hasNext()) {
			i.nextNode();
			count++;
		}
		return count;
	}

	/**
	 * Logs in a session that can create nodes, remembering the credentials that
	 * worked so later logins (including the racing threads) can skip the probe.
	 * Returns {@code null} if no writable session is available in this runtime.
	 */
	private Session loginWritable() {
		String workspace = System.getProperty("jcr.test.workspace");
		String[][] attempts = new String[][] {
			{ System.getProperty("jcr.test.user"), System.getProperty("jcr.test.password") },
			{ "admin", "admin" },
		};
		for (String[] attempt : attempts) {
			if (attempt[0] == null || attempt[0].isEmpty()) {
				continue;
			}
			Session candidate;
			try {
				candidate = login(attempt[0], attempt[1], workspace);
			} catch (RepositoryException ex) {
				writableDiagnostic.append("- user='").append(attempt[0])
						.append("' workspace='").append(workspace == null ? "(default)" : workspace)
						.append("': login failed: ").append(ex.getClass().getSimpleName())
						.append(": ").append(ex.getMessage()).append("\n");
				continue;
			}
			String denial = writeDenialReason(candidate);
			if (denial == null) {
				workUser = attempt[0];
				workPassword = attempt[1];
				workWorkspace = workspace;
				return candidate;
			}
			writableDiagnostic.append("- user='").append(attempt[0])
					.append("' workspace='").append(safeWorkspaceName(candidate))
					.append("': authenticated but cannot write under '").append(basePath)
					.append("': ").append(denial).append("\n");
			if (candidate.isLive()) {
				candidate.logout();
			}
		}
		return null;
	}

	private Session login(String user, String password, String workspace) throws RepositoryException {
		AuthenticatedCredentials credentials = new AuthenticatedCredentials(new AdminPrincipal() {
			@Override
			public String getName() {
				return user;
			}
		});
		return (workspace == null || workspace.isEmpty())
				? repository.login(credentials)
				: repository.login(credentials, workspace);
	}

	/**
	 * Probes whether the session may create and remove a node under {@link #basePath}.
	 * Returns {@code null} when it can, otherwise a short description of the denial
	 * (used to explain a skip/failure).
	 */
	private String writeDenialReason(Session session) {
		if (session == null || !session.isLive()) {
			return "session is not live";
		}
		try {
			Node base = session.getNode(basePath);
			Node probe = base.addNode("jcrWriteProbe-" + UUID.randomUUID(), FOLDER);
			session.save();
			probe.remove();
			session.save();
			return null;
		} catch (RepositoryException denied) {
			try {
				session.refresh(false);
			} catch (RepositoryException ignore) {
			}
			return denied.getClass().getSimpleName() + ": " + denied.getMessage();
		}
	}

	private static String safeWorkspaceName(Session session) {
		try {
			return session.getWorkspace().getName();
		} catch (Throwable ex) {
			return "(unknown)";
		}
	}
}

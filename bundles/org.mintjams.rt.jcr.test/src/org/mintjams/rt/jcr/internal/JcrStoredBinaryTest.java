package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Calendar;
import java.util.UUID;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.Property;
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
 * A saved binary property is read in place from the blob store: getBinary()
 * reports the stored size without opening the blob, its stream can be opened
 * repeatedly and yields the stored bytes, and positional reads see the same
 * bytes.
 */
public class JcrStoredBinaryTest {

	private static final int SIZE = 300 * 1024 + 7;

	private BundleContext bundleContext;
	private Repository repository;
	private ServiceReference<Repository> repositoryServiceRef;

	private Session session;
	private String testRootPath;
	private Node content;
	private byte[] data;

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

		data = new byte[SIZE];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) (i * 31 + (i >> 8));
		}

		try {
			session = repository.login(new AuthenticatedCredentials(new AdminPrincipal() {
				@Override
				public String getName() {
					return "admin";
				}
			}));
			Node testRoot = session.getNode(basePath).addNode("jcrStoredBinaryTest-" + UUID.randomUUID(), "nt:folder");
			Node file = testRoot.addNode("file.bin", "nt:file");
			content = file.addNode("jcr:content", "nt:resource");
			content.setProperty("jcr:data", session.getValueFactory().createBinary(new ByteArrayInputStream(data)));
			content.setProperty("jcr:mimeType", "application/octet-stream");
			content.setProperty("jcr:lastModified", Calendar.getInstance());
			session.save();
			testRootPath = testRoot.getPath();
		} catch (RepositoryException ex) {
			System.out.println("[JcrStoredBinaryTest] No writable admin session: " + ex.getMessage());
			if (session != null && session.isLive()) {
				try {
					session.refresh(false);
				} catch (RepositoryException ignore) {
				}
				session.logout();
			}
			session = null;
			content = null;
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
		testRootPath = null;
		content = null;
		if (bundleContext != null && repositoryServiceRef != null) {
			bundleContext.ungetService(repositoryServiceRef);
			repositoryServiceRef = null;
		}
		repository = null;
		bundleContext = null;
	}

	private void requireWritable() {
		assumeTrue("No writable admin session for the stored binary tests.",
				session != null && session.isLive() && content != null);
	}

	private static byte[] readAll(InputStream in) throws Exception {
		try (in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			for (int n; (n = in.read(buffer)) != -1;) {
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	@Test
	public void testStoredBinaryReportsSizeAndContent() throws Exception {
		requireWritable();
		Property p = content.getProperty("jcr:data");
		assertEquals(SIZE, p.getLength());
		Binary binary = p.getBinary();
		try {
			assertEquals(SIZE, binary.getSize());
			assertArrayEquals(data, readAll(binary.getStream()));
		} finally {
			binary.dispose();
		}
	}

	@Test
	public void testStoredBinaryStreamCanBeOpenedRepeatedly() throws Exception {
		requireWritable();
		Binary binary = content.getProperty("jcr:data").getBinary();
		try {
			// The first stream is read to the end and closed; a second one
			// must still yield the whole content.
			assertArrayEquals(data, readAll(binary.getStream()));
			assertArrayEquals(data, readAll(binary.getStream()));
			assertEquals(SIZE, binary.getSize());
		} finally {
			binary.dispose();
		}
	}

	@Test
	public void testStoredBinaryPositionalRead() throws Exception {
		requireWritable();
		Binary binary = content.getProperty("jcr:data").getBinary();
		try {
			byte[] buffer = new byte[1000];
			long position = SIZE - 500;
			int n = binary.read(buffer, position);
			assertEquals(500, n);
			byte[] expected = new byte[500];
			System.arraycopy(data, (int) position, expected, 0, 500);
			byte[] actual = new byte[500];
			System.arraycopy(buffer, 0, actual, 0, 500);
			assertArrayEquals(expected, actual);
			assertEquals(-1, binary.read(buffer, SIZE));
		} finally {
			binary.dispose();
		}
	}

}

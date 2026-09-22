package org.mintjams.rt.jcr.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.UUID;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mintjams.jcr.security.AdminPrincipal;
import org.mintjams.jcr.security.AuthenticatedCredentials;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Node.setProperty with null and empty values (JCR 2.0, 10.4.2): a null value or
 * array removes the property, an empty array leaves a multi-valued property
 * without values.
 */
public class JcrPropertyNullAndEmptyTest {

	private static final String PROPERTY = "emptyTest";

	private BundleContext bundleContext;
	private Repository repository;
	private ServiceReference<Repository> repositoryServiceRef;

	private Session session;
	private Node testRoot;
	private String testRootPath;
	private Node content;

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
			testRoot = session.getNode(basePath).addNode("jcrPropertyNullAndEmptyTest-" + UUID.randomUUID(), "nt:folder");
			Node file = testRoot.addNode("file.txt", "nt:file");
			content = file.addNode("jcr:content", "nt:resource");
			content.setProperty("jcr:data", session.getValueFactory()
					.createBinary(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
			content.setProperty("jcr:mimeType", "text/plain");
			content.setProperty("jcr:lastModified", Calendar.getInstance());
			session.save();
			testRootPath = testRoot.getPath();
		} catch (RepositoryException ex) {
			System.out.println("[JcrPropertyNullAndEmptyTest] No writable admin session: " + ex.getMessage());
			if (session != null && session.isLive()) {
				try {
					session.refresh(false);
				} catch (RepositoryException ignore) {
				}
				session.logout();
			}
			session = null;
			testRoot = null;
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
		testRoot = null;
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
		assumeTrue("No writable admin session for the null/empty value tests.",
				session != null && session.isLive() && content != null);
	}

	private void assertEmptyMultiValued(int expectedType) throws RepositoryException {
		assertTrue(content.hasProperty(PROPERTY));
		Property p = content.getProperty(PROPERTY);
		assertTrue(p.isMultiple());
		assertEquals(0, p.getValues().length);
		assertEquals(expectedType, p.getType());
	}

	@Test
	public void testEmptyStringArrayIsStored() throws RepositoryException {
		requireWritable();
		content.setProperty(PROPERTY, new String[0]);
		session.save();
		assertEmptyMultiValued(PropertyType.STRING);
	}

	@Test
	public void testEmptyValueArrayWithoutTypeIsString() throws RepositoryException {
		requireWritable();
		content.setProperty(PROPERTY, new Value[0]);
		session.save();
		assertEmptyMultiValued(PropertyType.STRING);
	}

	@Test
	public void testEmptyValueArrayKeepsGivenType() throws RepositoryException {
		requireWritable();
		content.setProperty(PROPERTY, new Value[0], PropertyType.LONG);
		session.save();
		assertEmptyMultiValued(PropertyType.LONG);
	}

	@Test
	public void testNullElementsAreDropped() throws RepositoryException {
		requireWritable();
		content.setProperty(PROPERTY, new Value[] { null, null }, PropertyType.STRING);
		session.save();
		assertEmptyMultiValued(PropertyType.STRING);

		Value a = session.getValueFactory().createValue("a");
		content.setProperty(PROPERTY, new Value[] { null, a, null });
		session.save();
		Value[] values = content.getProperty(PROPERTY).getValues();
		assertEquals(1, values.length);
		assertEquals("a", values[0].getString());
	}

	@Test
	public void testValuesCanBeClearedAndSetAgain() throws RepositoryException {
		requireWritable();
		content.setProperty(PROPERTY, new String[] { "a", "b" });
		session.save();
		assertEquals(2, content.getProperty(PROPERTY).getValues().length);

		content.setProperty(PROPERTY, new String[0]);
		session.save();
		assertEmptyMultiValued(PropertyType.STRING);

		content.setProperty(PROPERTY, new String[] { "c" });
		session.save();
		assertEquals("c", content.getProperty(PROPERTY).getValues()[0].getString());
	}

	@Test
	public void testNullArrayRemovesProperty() throws RepositoryException {
		requireWritable();
		content.setProperty(PROPERTY, new String[] { "a" });
		session.save();
		assertNull(content.setProperty(PROPERTY, (String[]) null));
		session.save();
		assertFalse(content.hasProperty(PROPERTY));

		content.setProperty(PROPERTY, new String[] { "a" });
		assertNull(content.setProperty(PROPERTY, (Value[]) null));
		assertNull(content.setProperty(PROPERTY + "2", (String[]) null, PropertyType.STRING));
		session.save();
		assertFalse(content.hasProperty(PROPERTY));
	}

	@Test
	public void testNullValueRemovesProperty() throws RepositoryException {
		requireWritable();
		content.setProperty(PROPERTY, "a");
		session.save();
		assertNull(content.setProperty(PROPERTY, (String) null));
		session.save();
		assertFalse(content.hasProperty(PROPERTY));

		content.setProperty(PROPERTY, "a");
		assertNull(content.setProperty(PROPERTY, (Value) null));
		assertFalse(content.hasProperty(PROPERTY));

		content.setProperty(PROPERTY, "a");
		assertNull(content.setProperty(PROPERTY, (String) null, PropertyType.STRING));
		assertFalse(content.hasProperty(PROPERTY));
	}

	@Test
	public void testNullOfEveryTypeRemovesProperty() throws RepositoryException {
		requireWritable();
		content.setProperty(PROPERTY, Calendar.getInstance());
		assertNull(content.setProperty(PROPERTY, (Calendar) null));
		assertFalse(content.hasProperty(PROPERTY));

		content.setProperty(PROPERTY, BigDecimal.ONE);
		assertNull(content.setProperty(PROPERTY, (BigDecimal) null));
		assertFalse(content.hasProperty(PROPERTY));

		content.setProperty(PROPERTY, new ByteArrayInputStream(new byte[] { 1 }));
		assertNull(content.setProperty(PROPERTY, (InputStream) null));
		assertFalse(content.hasProperty(PROPERTY));

		content.setProperty(PROPERTY, session.getValueFactory().createBinary(new ByteArrayInputStream(new byte[] { 1 })));
		assertNull(content.setProperty(PROPERTY, (Binary) null));
		assertFalse(content.hasProperty(PROPERTY));

		assertNull(content.setProperty(PROPERTY, (Node) null));
		assertFalse(content.hasProperty(PROPERTY));
		session.save();
	}

	@Test
	public void testNullForMissingPropertyIsNoOp() throws RepositoryException {
		requireWritable();
		assertFalse(content.hasProperty(PROPERTY));
		assertNull(content.setProperty(PROPERTY, (String) null));
		assertNull(content.setProperty(PROPERTY, (String[]) null));
		assertNull(content.setProperty(PROPERTY, (Value) null));
		session.save();
		assertFalse(content.hasProperty(PROPERTY));
	}

}

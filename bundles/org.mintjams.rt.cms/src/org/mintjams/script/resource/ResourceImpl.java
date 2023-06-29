/*
 * Copyright (c) 2022 MintJams Inc.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.script.resource;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileTypeDetector;
import java.util.Calendar;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.NamespaceRegistry;
import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.jcr.security.Privilege;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.Mask;
import org.mintjams.rt.cms.internal.script.resource.JcrAction;
import org.mintjams.script.resource.security.AccessControlManager;
import org.mintjams.script.resource.security.AccessDeniedException;
import org.mintjams.script.resource.version.Version;
import org.mintjams.script.resource.version.VersionHistory;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class ResourceImpl implements Resource, Adaptable {

	private final Session fSession;
	private final String fJcrPath;
	private Node fNode;

	public ResourceImpl(String absPath, Session session) throws ResourceException {
		if (Strings.isEmpty(absPath)) {
			throw new IllegalArgumentException("Path must not be null or empty.");
		}

		fJcrPath = JCRs.normalizePath(absPath.trim());
		fSession = session;
	}

	public ResourceImpl(Node node, Session session) throws ResourceException {
		if (node == null) {
			throw new IllegalArgumentException("Node must not be null.");
		}

		try {
			fJcrPath = node.getPath();
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(ResourceException.class);
		}
		fSession = session;
		fNode = node;
	}

	protected Node getNode() throws ResourceException {
		if (fNode == null) {
			try {
				fNode = fSession.adaptTo(javax.jcr.Session.class).getNode(fJcrPath);
			} catch (PathNotFoundException ex) {
				throw new ResourceNotFoundException(getPath());
			} catch (javax.jcr.AccessDeniedException ex) {
				throw new AccessDeniedException(getPath());
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ResourceException.class);
			}
		}
		return fNode;
	}

	protected Node getContentNode() throws ResourceException {
		if (isCollection()) {
			throw new ResourceTypeMismatchException(getPath());
		}

		try {
			return getNode().getNode(Node.JCR_CONTENT);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public String getIdentifier() throws ResourceException {
		try {
			return getNode().getIdentifier();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public String getName() throws ResourceException {
		String[] a = JCRs.normalizeAndSplitPath(fJcrPath);
		if (a.length == 0) {
			return "";
		}
		return a[a.length - 1];
	}

	public String getPath() throws ResourceException {
		return fJcrPath;
	}

	public boolean exists() throws ResourceException {
		try {
			getNode();
			return true;
		} catch (ResourceNotFoundException ignore) {
			return false;
		} catch (AccessDeniedException ignore) {
			return true;
		}
	}

	public Resource getParent() throws ResourceException {
		return new ResourceImpl(JcrPath.valueOf(fJcrPath).getParent().toString(), fSession);
	}

	public Resource getResource(String relPath) throws ResourceException {
		if (Strings.isEmpty(relPath)) {
			throw new IllegalArgumentException("Path must not be null or empty.");
		}

		if (relPath.startsWith("/")) {
			return new ResourceImpl(relPath, fSession);
		}

		try {
			String absPath;
			if (isCollection()) {
				absPath = getNode().getPath();
			} else {
				absPath = getNode().getParent().getPath();
			}
			if (!absPath.endsWith("/")) {
				absPath += "/";
			}
			absPath += relPath;
			return new ResourceImpl(absPath, fSession);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public ResourceIterator list() throws ResourceException {
		return list((ResourceFilter) null);
	}

	public ResourceIterator list(ResourceFilter filter) throws ResourceException {
		if (!isCollection()) {
			throw new ResourceTypeMismatchException(getPath());
		}

		try {
			return new ResourceIteratorImpl(getNode().getNodes(), fSession, filter);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public ResourceIterator list(ResourceNameFilter filter) throws ResourceException {
		if (!isCollection()) {
			throw new ResourceTypeMismatchException(getPath());
		}

		try {
			return new ResourceIteratorImpl(getNode().getNodes(), fSession, filter);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public boolean isCollection() throws ResourceException {
		try {
			return JCRs.isFolder(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public boolean isLocked() throws ResourceException {
		try {
			return JcrAction.create(getNode()).isLocked();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public boolean holdsLock() throws ResourceException {
		try {
			return JcrAction.create(getNode()).holdsLock();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public String getLockedBy() throws ResourceException {
		try {
			if (!JcrAction.create(getNode()).isLocked()) {
				return null;
			}

			return JcrAction.create(getNode()).getLock().getLockOwner();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource addReferenceable() throws ResourceException {
		try {
			JcrAction jcrAction = JcrAction.create(getNode()).addLockToken();
			if (jcrAction.isLockable()) {
				jcrAction.checkLock();
			}
			jcrAction.addReferenceable();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

	@Override
	public Resource removeReferenceable() throws ResourceException {
		try {
			JcrAction jcrAction = JcrAction.create(getNode()).addLockToken();
			if (jcrAction.isLockable()) {
				jcrAction.checkLock();
			}
			jcrAction.removeReferenceable();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

	@Override
	public boolean isReferenceable() throws ResourceException {
		try {
			return JcrAction.create(getNode()).isReferenceable() || JcrAction.create(getNode()).isVersionControlled();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public PropertyIterator getProperties() throws ResourceException {
		try {
			return getProperties(getContentNode().getProperties());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public PropertyIterator getProperties(String namePattern) throws ResourceException {
		try {
			return getProperties(getContentNode().getProperties(namePattern));
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public PropertyIterator getProperties(String[] nameGlobs) throws ResourceException {
		try {
			return getProperties(getContentNode().getProperties(nameGlobs));
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	private PropertyIterator getProperties(javax.jcr.PropertyIterator i) throws ResourceException {
		return new PropertyIteratorImpl(i, this);
	}

	@Override
	public Property getProperty(String name) throws ResourceException {
		try {
			return new Property(getContentNode().getProperty(name), this);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public boolean hasProperty(String name) throws ResourceException {
		try {
			return getContentNode().hasProperty(name);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public String[] getPropertyKeys() throws ResourceException {
		try {
			return org.mintjams.jcr.Node.class.cast(getContentNode()).getPropertyKeys();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public String getContent() throws ResourceException {
		try {
			return Strings.readAll(getContentAsReader());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Reader getContentAsReader() throws ResourceException {
		try {
			if (isCollection()) {
				throw new ResourceTypeMismatchException(getPath());
			}

			return JCRs.getContentAsReader(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public InputStream getContentAsStream() throws ResourceException {
		try {
			if (isCollection()) {
				throw new ResourceTypeMismatchException(getPath());
			}

			return JCRs.getContentAsStream(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public byte[] getContentAsByteArray() throws ResourceException {
		try {
			if (isCollection()) {
				throw new ResourceTypeMismatchException(getPath());
			}

			return IOs.toByteArray(getContentAsStream());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public String getContentType() throws ResourceException {
		try {
			return JCRs.getMimeType(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public String getContentEncoding() throws ResourceException {
		try {
			return JCRs.getEncoding(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public long getContentLength() throws ResourceException {
		try {
			return JCRs.getContentLength(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public java.util.Date getCreated() throws ResourceException {
		try {
			return JCRs.getCreatedDate(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public java.util.Date getLastModified() throws ResourceException {
		try {
			return JCRs.getLastModified(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public String getCreatedBy() throws ResourceException {
		try {
			return JCRs.getCreatedBy(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public String getLastModifiedBy() throws ResourceException {
		try {
			return JCRs.getLastModifiedBy(getNode());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource lock() throws ResourceException {
		return lock(true, true);
	}

	@Override
	public Resource lock(boolean isDeep) throws ResourceException {
		return lock(isDeep, true);
	}

	@Override
	public Resource lock(boolean isDeep, boolean isSessionScoped) throws ResourceException {
		try {
			JcrAction.create(getNode()).addLockToken().addLockable().checkLock().lock(isDeep, isSessionScoped);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

	@Override
	public Resource unlock() throws ResourceException {
		try {
			JcrAction.create(getNode()).addLockToken().checkLock().unlock();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

	@Override
	public Resource addVersionControl() throws ResourceException {
		try {
			JcrAction jcrAction = JcrAction.create(getNode()).addLockToken();
			if (jcrAction.isLockable()) {
				jcrAction.checkLock();
			}
			jcrAction.addVersionControl();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

	@Override
	public boolean isVersionControlled() throws ResourceException {
		try {
			return JcrAction.create(getNode()).isVersionControlled();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Version checkpoint() throws ResourceException {
		try {
			return new Version(JcrAction.create(getNode()).addLockToken().checkLock().checkpoint(), getVersionHistory());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource checkout() throws ResourceException {
		try {
			JcrAction.create(getNode()).addLockToken().checkLock().checkout();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

	@Override
	public Version checkin() throws ResourceException {
		try {
			return new Version(JcrAction.create(getNode()).addLockToken().checkLock().checkin(), getVersionHistory());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public boolean isCheckedOut() throws ResourceException {
		try {
			return JcrAction.create(getNode()).isCheckedOut();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Version getBaseVersion() throws ResourceException {
		try {
			return new Version(getNode().getSession().getWorkspace().getVersionManager().getBaseVersion(getNode().getPath()), getVersionHistory());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public VersionHistory getVersionHistory() throws ResourceException {
		try {
			return new VersionHistory(getNode().getSession().getWorkspace().getVersionManager().getVersionHistory(getNode().getPath()), this);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource createFolder() throws ResourceException {
		ResourceImpl r = (ResourceImpl) getParent().createFolder(getName());
		fNode = r.fNode;
		return this;
	}

	@Override
	public Resource createFolder(String name) throws ResourceException {
		if (!isCollection()) {
			throw new ResourceTypeMismatchException(getPath());
		}

		Resource child = getResource(name);
		if (child.exists()) {
			throw new ResourceAlreadyExistsException(child.getPath());
		}

		try {
			JcrAction.create(getNode()).addLockToken().checkDeepLock();
			Node childNode = JCRs.createFolder(getNode(), name);
			JcrAction.create(childNode).addLockable();
			return new ResourceImpl(childNode, fSession);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource getFolder(String name) throws ResourceException {
		Resource child = getResource(name);
		if (!child.isCollection()) {
			throw new ResourceTypeMismatchException(child.getPath());
		}

		return child;
	}

	@Override
	public Resource getOrCreateFolder(String name) throws ResourceException {
		try {
			return getFolder(name);
		} catch (ResourceNotFoundException ex) {
			return createFolder(name);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource createFile() throws ResourceException {
		ResourceImpl r = (ResourceImpl) getParent().createFile(getName());
		fNode = r.fNode;
		return this;
	}

	@Override
	public Resource createFile(String name) throws ResourceException {
		if (!isCollection()) {
			throw new ResourceTypeMismatchException(getPath());
		}

		Resource child = getResource(name);
		if (child.exists()) {
			throw new ResourceAlreadyExistsException(child.getPath());
		}

		try {
			JcrAction.create(getNode()).addLockToken().checkDeepLock();
			Node childNode = JCRs.createFile(getNode(), name);
			String mimeType = Adaptables.getAdapter(CmsService.getRepository(), FileTypeDetector.class).probeContentType(Path.of(name));
			if (Strings.isNotEmpty(mimeType)) {
				childNode.getNode(Node.JCR_CONTENT).setProperty(javax.jcr.Property.JCR_MIMETYPE, mimeType);
			}
			JcrAction.create(childNode).addLockable();
			return new ResourceImpl(childNode, fSession);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource getFile(String name) throws ResourceException {
		Resource child = getResource(name);
		if (child.isCollection()) {
			throw new ResourceTypeMismatchException(child.getPath());
		}

		return child;
	}

	@Override
	public Resource getOrCreateFile(String name) throws ResourceException {
		try {
			return getFile(name);
		} catch (ResourceNotFoundException ex) {
			return createFile(name);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource moveTo(String destAbsPath) throws ResourceException {
		try {
			Resource dest = getResourceResolver().getResource(destAbsPath);
			Resource destParent = dest.getParent();
			if (!destParent.exists()) {
				throw new ResourceNotFoundException(destParent.getPath());
			}

			JcrAction.create(getNode()).addLockToken().checkLock();
			if (dest.exists()) {
				Node destNode = Adaptables.getAdapter(dest, Node.class);
				JcrAction.create(destNode).addLockToken().checkLock();
			} else {
				Node destParentNode = Adaptables.getAdapter(destParent, Node.class);
				JcrAction.create(destParentNode).addLockToken().checkDeepLock();
			}

			if (dest.exists()) {
				dest.remove();
			}

			getNode().getSession().getWorkspace().move(fJcrPath, destAbsPath);

			return new ResourceImpl(destAbsPath, fSession);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource copyTo(String destAbsPath) throws ResourceException {
		try {
			Resource dest = getResourceResolver().getResource(destAbsPath);
			Resource destParent = dest.getParent();
			if (!destParent.exists()) {
				throw new ResourceNotFoundException(destParent.getPath());
			}

			JcrAction.create(getNode()).addLockToken();
			if (dest.exists()) {
				Node destNode = Adaptables.getAdapter(dest, Node.class);
				JcrAction.create(destNode).addLockToken().checkLock();
			} else {
				Node destParentNode = Adaptables.getAdapter(destParent, Node.class);
				JcrAction.create(destParentNode).addLockToken().checkDeepLock();
			}

			if (dest.exists()) {
				dest.write(getContentAsStream());

				Node destNode = Adaptables.getAdapter(dest, Node.class);
				Node destContentNode = destNode.getNode(Node.JCR_CONTENT);
				for (javax.jcr.PropertyIterator i = getContentNode().getProperties(); i.hasNext();) {
					javax.jcr.Property p = i.nextProperty();
					if (p.getName().startsWith(NamespaceRegistry.PREFIX_JCR + ":")) {
						if (!p.getName().equals(org.mintjams.jcr.Property.JCR_MIMETYPE_NAME)
								&& !p.getName().equals(org.mintjams.jcr.Property.JCR_ENCODING_NAME)) {
							continue;
						}
					}

					if (!p.getName().startsWith(NamespaceRegistry.PREFIX_JCR + ":")) {
						if (destContentNode.hasProperty(p.getName())) {
							destContentNode.getProperty(p.getName()).remove();
						}
					}

					if (!p.isMultiple()) {
						destContentNode.setProperty(p.getName(), p.getValue());
					} else {
						destContentNode.setProperty(p.getName(), p.getValues());
					}
				}
			} else {
				getNode().getSession().getWorkspace().copy(fJcrPath, destAbsPath);
			}

			return new ResourceImpl(destAbsPath, fSession);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource remove() throws ResourceException {
		try {
			JcrAction.create(getNode()).addLockToken().checkLock();
			getNode().remove();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

	private void write(InputStream content) throws ResourceException {
		if (isCollection()) {
			throw new ResourceTypeMismatchException(getPath());
		}

		try {
			JcrAction.create(getNode()).addLockToken().checkLock();
			JCRs.write(getNode(), content);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource write(Object content) throws ResourceException {
		if (content == null) {
			throw new IllegalArgumentException("Content must not be null.");
		}

		try {
			// String
			{
				String s = Adaptables.getAdapter(content, String.class);
				if (s != null) {
					try {
						write(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8.name())));
					} catch (UnsupportedEncodingException ex) {
						throw (ResourceException) new ResourceException(ex.getMessage()).initCause(ex);
					}
					return this;
				}
			}

			// InputStream
			{
				InputStream in = Adaptables.getAdapter(content, InputStream.class);
				if (in != null) {
					try {
						write(in);
					} finally {
						try {
							in.close();
						} catch (Throwable ignore) {}
					}
					return this;
				}
			}

			// byte[]
			{
				byte[] b = Adaptables.getAdapter(content, byte[].class);
				if (b != null) {
					write(new ByteArrayInputStream(b));
					return this;
				}
			}

			// Path
			{
				Path f = Adaptables.getAdapter(content, Path.class);
				if (f != null) {
					try (InputStream in = new BufferedInputStream(Files.newInputStream(f))) {
						write(in);
					}
					return this;
				}
			}

			// File
			{
				File f = Adaptables.getAdapter(content, File.class);
				if (f != null) {
					try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
						write(in);
					}
					return this;
				}
			}

			throw new IllegalArgumentException("Unsupported content: " + content.getClass().getName());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource setProperty(String name, String value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, String[] value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, String value, boolean mask) throws ResourceException {
		if (mask && value != null) {
			try {
				value = Mask.mask(value, name);
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ValueFormatException.class);
			}
		}
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, String[] value, boolean mask) throws ResourceException {
		if (mask && value != null) {
			try {
				for (int i = 0; i < value.length; i++) {
					value[i] = Mask.mask(value[i], name);
				}
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ValueFormatException.class);
			}
		}
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, BigDecimal value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, BigDecimal[] value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, double value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, double[] value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, long value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, long[] value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, int value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, int[] value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, Calendar value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, Calendar[] value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, java.util.Date value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, java.util.Date[] value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, boolean value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, boolean[] value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, byte[] value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, InputStream value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	@Override
	public Resource setProperty(String name, Resource value) throws ResourceException {
		_setProperty(name, value);
		return this;
	}

	private void _setProperty(String name, Object value) throws ResourceException {
		if (isCollection()) {
			throw new ResourceTypeMismatchException(getPath());
		}

		try {
			JcrAction.create(getNode()).addLockToken().checkLock();
			if (value instanceof Resource) {
				value = Adaptables.getAdapter(value, Node.class);
			}
			JCRs.setProperty(getNode(), name, value);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Resource allowAnyProperties() throws ResourceException {
		try {
			JcrAction.create(getNode()).addLockToken().checkLock().addMixin(getContentNode(), "mi:anyProperties");
			return this;
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@Override
	public Session getSession() {
		return fSession;
	}

	@Override
	public ResourceResolver getResourceResolver() {
		return fSession.getResourceResolver();
	}

	@Override
	public AccessControlList getAccessControlList() throws ResourceException {
		return fSession.getAccessControlManager().getAccessControlList(getPath());
	}

	@Override
	public Resource setAccessControlList(AccessControlList acl) throws ResourceException {
		fSession.getAccessControlManager().setAccessControlList(getPath(), acl);
		return this;
	}

	@Override
	public boolean canRead() throws ResourceException {
		AccessControlManager acm = fSession.getAccessControlManager();
		return acm.hasPrivileges(getPath(), acm.privilegeFromName(Privilege.JCR_READ));
	}

	@Override
	public boolean canWrite() throws ResourceException {
		AccessControlManager acm = fSession.getAccessControlManager();
		if (isCollection()) {
			return acm.hasPrivileges(getPath(), acm.privilegeFromName(Privilege.JCR_ADD_CHILD_NODES));
		}
		return acm.hasPrivileges(getPath(), acm.privilegeFromName(Privilege.JCR_MODIFY_PROPERTIES));
	}

	@Override
	public boolean canReadACL() throws ResourceException {
		AccessControlManager acm = fSession.getAccessControlManager();
		return acm.hasPrivileges(getPath(), acm.privilegeFromName(Privilege.JCR_READ_ACCESS_CONTROL));
	}

	@Override
	public boolean canWriteACL() throws ResourceException {
		AccessControlManager acm = fSession.getAccessControlManager();
		return acm.hasPrivileges(getPath(), acm.privilegeFromName(Privilege.JCR_MODIFY_ACCESS_CONTROL));
	}

	@Override
	public boolean canRemove() throws ResourceException {
		AccessControlManager acm = fSession.getAccessControlManager();
		return acm.hasPrivileges(getPath(), acm.privilegeFromName(Privilege.JCR_REMOVE_NODE));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(Node.class)) {
			try {
				return (AdapterType) getNode();
			} catch (Throwable ignore) {}
			return null;
		}

		return Adaptables.getAdapter(fSession, adapterType);
	}

}

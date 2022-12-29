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

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;

import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.script.resource.version.Version;
import org.mintjams.script.resource.version.VersionHistory;

public class UnknownIdentifierResource implements Resource {
	private final String fIdentifier;
	private final Session fSession;

	protected UnknownIdentifierResource(String id, Session session) {
		fIdentifier = id;
		fSession = session;
	}

	@Override
	public String getIdentifier() throws ResourceException {
		return fIdentifier;
	}

	@Override
	public String getName() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getPath() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean exists() throws ResourceException {
		return false;
	}

	@Override
	public Resource getParent() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource getResource(String relPath) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public ResourceIterator list() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public ResourceIterator list(ResourceFilter filter) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public ResourceIterator list(ResourceNameFilter filter) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isCollection() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isLocked() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean holdsLock() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getLockedBy() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource addReferenceable() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource removeReferenceable() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isReferenceable() throws ResourceException {
		return false;
	}

	@Override
	public PropertyIterator getProperties() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public PropertyIterator getProperties(String namePattern) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public PropertyIterator getProperties(String[] nameGlobs) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Property getProperty(String name) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean hasProperty(String name) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String[] getPropertyKeys() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getContent() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Reader getContentAsReader() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public InputStream getContentAsStream() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public byte[] getContentAsByteArray() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getContentType() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getContentEncoding() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getContentLength() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Date getCreated() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Date getLastModified() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getCreatedBy() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getLastModifiedBy() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource lock() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource lock(boolean isDeep) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource lock(boolean isDeep, boolean isSessionScoped) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource unlock() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource addVersionControl() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isVersionControlled() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Version checkpoint() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource checkout() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Version checkin() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isCheckedOut() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Version getBaseVersion() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public VersionHistory getVersionHistory() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource createFolder() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource createFolder(String name) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource getFolder(String name) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource getOrCreateFolder(String name) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource createFile() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource createFile(String name) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource getFile(String name) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource getOrCreateFile(String name) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource moveTo(String destAbsPath) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource copyTo(String destAbsPath) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource remove() throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource write(Object content) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, String value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, String[] value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, String value, boolean mask) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, String[] value, boolean mask) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, BigDecimal value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, BigDecimal[] value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, double value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, double[] value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, long value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, long[] value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, int value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, int[] value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, Calendar value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, Calendar[] value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, Date value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, Date[] value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, boolean value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, boolean[] value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, byte[] value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, InputStream value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setProperty(String name, Resource value) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource allowAnyProperties() throws ResourceException {
		throw new UnsupportedOperationException();
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
	public AccessControlList getAccessControlList() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Resource setAccessControlList(AccessControlList acl) throws ResourceException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean canRead() throws ResourceException {
		return false;
	}

	@Override
	public boolean canWrite() throws ResourceException {
		return false;
	}

	@Override
	public boolean canReadACL() throws ResourceException {
		return false;
	}

	@Override
	public boolean canWriteACL() throws ResourceException {
		return false;
	}

	@Override
	public boolean canRemove() throws ResourceException {
		return false;
	}

}

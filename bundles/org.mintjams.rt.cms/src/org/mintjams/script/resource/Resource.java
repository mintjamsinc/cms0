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
import java.util.Iterator;

import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.script.resource.version.Version;
import org.mintjams.script.resource.version.VersionHistory;

public interface Resource {

	String getIdentifier() throws ResourceException;

	String getName() throws ResourceException;

	String getPath() throws ResourceException;

	boolean exists() throws ResourceException;

	Resource getParent() throws ResourceException;

	Resource getResource(String relPath) throws ResourceException;

	ResourceIterator list() throws ResourceException;

	ResourceIterator list(ResourceFilter filter) throws ResourceException;

	ResourceIterator list(ResourceNameFilter filter) throws ResourceException;

	boolean isCollection() throws ResourceException;

	boolean isLocked() throws ResourceException;

	boolean holdsLock() throws ResourceException;

	String getLockedBy() throws ResourceException;

	Resource addVersionControl() throws ResourceException;

	boolean isVersionControlled() throws ResourceException;

	Version checkpoint() throws ResourceException;

	Resource checkout() throws ResourceException;

	Version checkin() throws ResourceException;

	boolean isCheckedOut() throws ResourceException;

	Version getBaseVersion() throws ResourceException;

	VersionHistory getVersionHistory() throws ResourceException;

	Resource addReferenceable() throws ResourceException;

	Resource removeReferenceable() throws ResourceException;

	boolean isReferenceable() throws ResourceException;

	PropertyIterator getProperties() throws ResourceException;

	PropertyIterator getProperties(String namePattern) throws ResourceException;

	PropertyIterator getProperties(String[] nameGlobs) throws ResourceException;

	Property getProperty(String name) throws ResourceException;

	boolean hasProperty(String name) throws ResourceException;

	String[] getPropertyKeys() throws ResourceException;

	String getContent() throws ResourceException;

	Reader getContentAsReader() throws ResourceException;

	InputStream getContentAsStream() throws ResourceException;

	byte[] getContentAsByteArray() throws ResourceException;

	String getContentType() throws ResourceException;

	String getContentEncoding() throws ResourceException;

	long getContentLength() throws ResourceException;

	java.util.Date getCreated() throws ResourceException;

	java.util.Date getLastModified() throws ResourceException;

	String getCreatedBy() throws ResourceException;

	String getLastModifiedBy() throws ResourceException;

	Resource lock() throws ResourceException;

	Resource lock(boolean isDeep) throws ResourceException;

	Resource lock(boolean isDeep, boolean isSessionScoped) throws ResourceException;

	Resource unlock() throws ResourceException;

	Resource createFolder() throws ResourceException;

	Resource createFolder(String name) throws ResourceException;

	Resource getFolder(String name) throws ResourceException;

	Resource getOrCreateFolder(String name) throws ResourceException;

	Resource createFile() throws ResourceException;

	Resource createFile(String name) throws ResourceException;

	Resource getFile(String name) throws ResourceException;

	Resource getOrCreateFile(String name) throws ResourceException;

	Resource moveTo(String destAbsPath) throws ResourceException;

	Resource copyTo(String destAbsPath) throws ResourceException;

	Resource remove() throws ResourceException;

	Resource write(Object content) throws ResourceException;

	Resource setProperty(String name, String value) throws ResourceException;

	Resource setProperty(String name, String[] value) throws ResourceException;

	Resource setProperty(String name, String value, boolean mask) throws ResourceException;

	Resource setProperty(String name, String[] value, boolean mask) throws ResourceException;

	Resource setProperty(String name, BigDecimal value) throws ResourceException;

	Resource setProperty(String name, BigDecimal[] value) throws ResourceException;

	Resource setProperty(String name, double value) throws ResourceException;

	Resource setProperty(String name, double[] value) throws ResourceException;

	Resource setProperty(String name, long value) throws ResourceException;

	Resource setProperty(String name, long[] value) throws ResourceException;

	Resource setProperty(String name, int value) throws ResourceException;

	Resource setProperty(String name, int[] value) throws ResourceException;

	Resource setProperty(String name, Calendar value) throws ResourceException;

	Resource setProperty(String name, Calendar[] value) throws ResourceException;

	Resource setProperty(String name, java.util.Date value) throws ResourceException;

	Resource setProperty(String name, java.util.Date[] value) throws ResourceException;

	Resource setProperty(String name, boolean value) throws ResourceException;

	Resource setProperty(String name, boolean[] value) throws ResourceException;

	Resource setProperty(String name, byte[] value) throws ResourceException;

	Resource setProperty(String name, InputStream value) throws ResourceException;

	Resource setProperty(String name, Resource value) throws ResourceException;

	Resource allowAnyProperties() throws ResourceException;

	Session getSession();

	ResourceResolver getResourceResolver();

	AccessControlList getAccessControlList() throws ResourceException;

	Resource setAccessControlList(AccessControlList acl) throws ResourceException;

	boolean canRead() throws ResourceException;

	boolean canWrite() throws ResourceException;

	boolean canReadACL() throws ResourceException;

	boolean canWriteACL() throws ResourceException;

	boolean canRemove() throws ResourceException;

	interface ResourceIterator extends Iterator<Resource> {
		void skip(long skipNum);
	}

	interface PropertyIterator extends Iterator<Property> {
		void skip(long skipNum);
	}

}

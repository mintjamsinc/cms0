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

package org.mintjams.jcr;

import java.util.Collection;

import javax.jcr.RepositoryException;

import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.UserPrincipal;

public interface Session extends javax.jcr.Session {

	void checkPrivileges(String absPath, String... privileges) throws RepositoryException;

	boolean hasPrivileges(String absPath, String... privileges) throws RepositoryException;

	UserPrincipal getUserPrincipal();

	Collection<GroupPrincipal> getGroups();

	boolean isAdmin();

	boolean isGuest();

	boolean isAnonymous();

	boolean isService();

	boolean isSystem();

	/**
	 * Returns a content-import handler bound to this session, configured with the
	 * given identifier- and path-conflict behaviours.
	 *
	 * <p>Unlike {@link javax.jcr.Session#getImportContentHandler(String, int)},
	 * this is <em>not</em> a SAX handler for system/document-view XML: the caller
	 * drives it node by node (see {@link ImportContentHandler}). While the
	 * returned handler is open the session runs in an import scope where
	 * version-control operations stay in this session's transient space and the
	 * unsaved-changes guard is bypassed, so the whole import can be committed by a
	 * single {@code save()} or discarded by a single {@code refresh(false)}.
	 *
	 * @param uuidBehavior one of {@link ImportContentHandler#IMPORT_UUID_THROW_ON_COLLISION},
	 *                     {@link ImportContentHandler#IMPORT_UUID_NEW_ON_COLLISION},
	 *                     {@link ImportContentHandler#IMPORT_UUID_ALWAYS_NEW}.
	 * @param pathBehavior one of {@link ImportContentHandler#IMPORT_PATH_THROW_ON_CONFLICT},
	 *                     {@link ImportContentHandler#IMPORT_PATH_SKIP},
	 *                     {@link ImportContentHandler#IMPORT_PATH_OVERWRITE}.
	 */
	ImportContentHandler getImportContentHandler(int uuidBehavior, int pathBehavior) throws RepositoryException;

}

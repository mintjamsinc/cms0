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

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemExistsException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.version.VersionException;

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
	 * Copies the node at {@code srcAbsPath}, with everything beneath it, to
	 * {@code destAbsPath} in this session's transient space. The copy is
	 * persisted by the next {@link #save()} together with the session's other
	 * changes and discarded by {@code refresh(false)}, like a move done with
	 * {@link #move(String, String)}. {@link javax.jcr.Workspace#copy(String, String)}
	 * is the immediate form: it writes the copy at once, in a transaction of its
	 * own, and only sees what has been saved.
	 *
	 * <p>The source must be an {@code nt:folder} or {@code nt:file} that this
	 * session can read in full, and the parent of the destination an
	 * {@code nt:folder} this session may add children to. The root cannot be
	 * copied, and the destination may not lie inside the source. A folder with
	 * more than 1000 descendants is refused, as it is by {@code move}. The copy
	 * is not versionable even when the source is; version histories are never
	 * copied.
	 *
	 * @param srcAbsPath  the node to copy
	 * @param destAbsPath the path of the copy, name included
	 * @throws PathNotFoundException        the source or the destination's parent does not exist
	 * @throws ItemExistsException          there is a node at the destination already
	 * @throws ConstraintViolationException the types do not allow the copy, or the source is too large
	 * @throws AccessDeniedException        the session cannot read the whole source or write the destination
	 * @throws LockException                the source or the destination's parent is locked by another session
	 */
	void copy(String srcAbsPath, String destAbsPath) throws ItemExistsException, PathNotFoundException,
			VersionException, ConstraintViolationException, LockException, AccessDeniedException, RepositoryException;

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

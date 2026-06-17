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

import java.util.Calendar;

import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Drives a repository content import on a single session, obtained from
 * {@link Session#getImportContentHandler(int, int)}.
 *
 * <p>This is <em>not</em> a SAX {@link org.xml.sax.ContentHandler} for
 * system/document-view XML — the caller drives it node by node. It exists so
 * that an importer (e.g. the CMS Archive restore) can keep its own archive
 * format and parsing while delegating the JCR-level semantics — identifier and
 * path conflict resolution, and version-controlled overwrites — to the
 * repository.
 *
 * <p>While the handler is open the owning session is placed in an
 * <em>import scope</em>: version-control operations (checkout/checkin) run
 * inside this session's transient space instead of a separate system session,
 * and the "node has unsaved changes" ({@link javax.jcr.InvalidItemStateException})
 * guard is bypassed. This lets a whole import — including version-controlled
 * overwrites — be staged transiently and then committed by a single
 * {@code session.save()} (a real import) or discarded by a single
 * {@code session.refresh(false)} (a dry run, which therefore exercises exactly
 * the same code path).
 *
 * <p>The handler is {@link AutoCloseable} and MUST be used with
 * try-with-resources: {@link #close()} leaves the import scope and restores the
 * session's normal version-control behaviour, and must run even on error.
 */
public interface ImportContentHandler extends AutoCloseable {

	// uuidBehavior --------------------------------------------------------
	/**
	 * Abort the import when the archived identifier already belongs to a node at
	 * a different path (a genuine collision). The conflict is signalled by an
	 * {@link ItemExistsException} from {@link #importNode}.
	 */
	int IMPORT_UUID_THROW_ON_COLLISION = 0;
	/** Assign a fresh identifier only when the archived one collides; otherwise keep it. */
	int IMPORT_UUID_NEW_ON_COLLISION = 1;
	/** Always assign a fresh identifier, never reusing the archived one. */
	int IMPORT_UUID_ALWAYS_NEW = 2;

	// pathBehavior --------------------------------------------------------
	/**
	 * Abort the import when a node already exists at the target path. The
	 * conflict is signalled by an {@link ItemExistsException} from
	 * {@link #importNode}.
	 */
	int IMPORT_PATH_THROW_ON_CONFLICT = 0;
	/** Keep the existing node untouched and skip the incoming one (and its subtree). */
	int IMPORT_PATH_SKIP = 1;
	/**
	 * Overwrite the existing node. A versionable node is checked out, overwritten
	 * in place and checked back in (see {@link #checkInOverwritten()}); a
	 * non-versionable node is removed and recreated.
	 */
	int IMPORT_PATH_OVERWRITE = 2;

	/** How {@link #importNode} handled a node. */
	enum Disposition {
		/** A new node was created (the identifier may be the archived one or fresh). */
		CREATED,
		/** An existing node was overwritten (in place, or removed and recreated). */
		OVERWRITTEN,
		/** The path conflict policy left the existing node and skipped the incoming one. */
		SKIPPED
	}

	/** Outcome of a single {@link #importNode} call. */
	interface Result {
		Disposition getDisposition();

		/**
		 * The live node to populate (set mixins, properties and body, then resolve
		 * references). {@code null} when the node was {@link Disposition#SKIPPED}.
		 */
		Node getNode();
	}

	/**
	 * Resolve the identifier- and path-conflict policy for one node beneath
	 * {@code parentAbsPath} and, unless skipped, make it ready for the caller to
	 * populate: a new node is created (honouring the archived identifier and
	 * creation time), an existing versionable node chosen for overwrite is
	 * checked out, and an existing non-versionable node chosen for overwrite is
	 * removed and recreated. The parent must already exist.
	 *
	 * @param parentAbsPath       absolute path of the (already imported) parent.
	 * @param name                child name of the node to import.
	 * @param primaryType         primary node type, or {@code null} to let the
	 *                            parent's child-node definitions decide.
	 * @param suggestedIdentifier the archived identifier, or {@code null}.
	 * @param created             original {@code jcr:created} to preserve, or
	 *                            {@code null} to let the repository stamp it.
	 * @throws ItemExistsException under the {@code THROW} policies, signalling the
	 *                            import must abort.
	 */
	Result importNode(String parentAbsPath, String name, String primaryType,
			String suggestedIdentifier, Calendar created) throws RepositoryException;

	/**
	 * Check in every versionable node that was checked out for an in-place
	 * overwrite during this import. Call exactly once, after all nodes,
	 * properties and references have been staged and immediately before the
	 * terminal {@code session.save()} (a dry run skips this and just refreshes,
	 * which discards the staged checkouts too). No-op when nothing was checked
	 * out.
	 */
	void checkInOverwritten() throws RepositoryException;

	/** The identifier-conflict behaviour this handler was created with. */
	int getUuidBehavior();

	/** The path-conflict behaviour this handler was created with. */
	int getPathBehavior();

	/** Leaves the import scope, restoring the session's normal behaviour. */
	@Override
	void close();
}

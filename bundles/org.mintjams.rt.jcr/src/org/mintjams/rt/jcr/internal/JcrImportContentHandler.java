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

package org.mintjams.rt.jcr.internal;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.VersionManager;

import org.mintjams.jcr.ImportContentHandler;

/**
 * The node-by-node import driver returned by
 * {@link JcrSession#getImportContentHandler(int, int)}. See
 * {@link ImportContentHandler} for the contract.
 *
 * <p>Construction enters the session's import scope and {@link #close()} leaves
 * it, so version-controlled overwrites are staged in the session's transient
 * space and committed (or rolled back) with the rest of the import by a single
 * {@code save()} / {@code refresh(false)}.
 *
 * <p>Conflict resolution is per node:
 * <ul>
 * <li>no node at the target path — create it (honouring the identifier policy);</li>
 * <li>a descendant of a node already being overwritten in place — overwrite it
 * in place (the ancestor's checkout already covers it for versioning);</li>
 * <li>otherwise the target path is a top-level conflict, resolved by the path
 * policy: throw (abort), skip, or overwrite. A versionable node is checked out
 * and overwritten in place; a non-versionable node is removed and recreated.</li>
 * </ul>
 */
final class JcrImportContentHandler implements ImportContentHandler {

	private final JcrSession fSession;
	private final int fUuidBehavior;
	private final int fPathBehavior;
	/**
	 * Absolute paths of versionable nodes checked out for an in-place overwrite.
	 * Their subtrees are overwritten in place (one checkout/checkin per root
	 * covers the whole subtree), and they are checked back in by
	 * {@link #checkInOverwritten()}.
	 */
	private final Set<String> fOverwriteRoots = new LinkedHashSet<>();
	private boolean fClosed;

	private JcrImportContentHandler(JcrSession session, int uuidBehavior, int pathBehavior) {
		fSession = session;
		fUuidBehavior = uuidBehavior;
		fPathBehavior = pathBehavior;
	}

	static JcrImportContentHandler create(JcrSession session, int uuidBehavior, int pathBehavior) {
		JcrImportContentHandler handler = new JcrImportContentHandler(session, uuidBehavior, pathBehavior);
		session.beginImportScope();
		return handler;
	}

	@Override
	public Result importNode(String parentAbsPath, String name, String primaryType,
			String suggestedIdentifier, Calendar created) throws RepositoryException {
		Node parent = fSession.getNode(parentAbsPath);
		String tgtPath = childPath(parentAbsPath, name);

		if (!fSession.nodeExists(tgtPath)) {
			// No conflict: create the node, honouring the identifier policy.
			return new ResultImpl(Disposition.CREATED, createNode(parent, name, primaryType, suggestedIdentifier, created));
		}

		if (isUnderOverwriteRoot(tgtPath)) {
			// A descendant of a node we are overwriting in place: overwrite it in
			// place too. No separate checkout — the ancestor's checkout (and the
			// later checkin) captures the whole subtree.
			return new ResultImpl(Disposition.OVERWRITTEN, fSession.getNode(tgtPath));
		}

		// Top-level path conflict: the user's path policy decides.
		switch (fPathBehavior) {
		case IMPORT_PATH_SKIP:
			return new ResultImpl(Disposition.SKIPPED, null);
		case IMPORT_PATH_OVERWRITE:
			return overwrite(parent, name, primaryType, suggestedIdentifier, created, tgtPath);
		case IMPORT_PATH_THROW_ON_CONFLICT:
		default:
			throw new ItemExistsException("A node already exists at the target path: " + tgtPath);
		}
	}

	private Result overwrite(Node parent, String name, String primaryType, String suggestedIdentifier,
			Calendar created, String tgtPath) throws RepositoryException {
		Node existing = fSession.getNode(tgtPath);
		if (existing.isNodeType(NodeType.MIX_VERSIONABLE)) {
			// Overwrite in place so identity and version history survive: check out
			// (in-session, thanks to the import scope), keep the node for the caller
			// to overwrite, and remember it for the terminal checkin.
			fSession.getWorkspace().getVersionManager().checkout(tgtPath);
			fOverwriteRoots.add(tgtPath);
			return new ResultImpl(Disposition.OVERWRITTEN, existing);
		}
		// Not versionable: remove and recreate. The removal is staged transiently
		// and takes effect (or is discarded) with the single terminal save/refresh.
		existing.remove();
		return new ResultImpl(Disposition.OVERWRITTEN, createNode(parent, name, primaryType, suggestedIdentifier, created));
	}

	private Node createNode(Node parent, String name, String primaryType, String suggestedIdentifier, Calendar created)
			throws RepositoryException {
		String identifier = resolveIdentifier(suggestedIdentifier);
		return ((org.mintjams.jcr.Node) parent).addNode(name, primaryType, identifier, created);
	}

	/**
	 * Apply the identifier policy: a fresh identifier (null) when always-new or
	 * when the archived one is already taken under the new-on-collision policy;
	 * the archived identifier otherwise; and an abort (ItemExistsException) under
	 * the throw policy when the archived identifier is already taken at another
	 * path.
	 */
	private String resolveIdentifier(String suggestedIdentifier) throws RepositoryException {
		boolean hasSuggested = suggestedIdentifier != null && !suggestedIdentifier.isEmpty();
		switch (fUuidBehavior) {
		case IMPORT_UUID_ALWAYS_NEW:
			return null;
		case IMPORT_UUID_NEW_ON_COLLISION:
			return (hasSuggested && identifierInUse(suggestedIdentifier)) ? null : suggestedIdentifier;
		case IMPORT_UUID_THROW_ON_COLLISION:
		default:
			if (hasSuggested && identifierInUse(suggestedIdentifier)) {
				throw new ItemExistsException("A node with identifier already exists: " + suggestedIdentifier);
			}
			return suggestedIdentifier;
		}
	}

	private boolean identifierInUse(String identifier) {
		try {
			fSession.getNodeByIdentifier(identifier);
			return true;
		} catch (RepositoryException notFound) {
			return false;
		}
	}

	private boolean isUnderOverwriteRoot(String path) {
		for (String root : fOverwriteRoots) {
			if (path.startsWith(root + "/")) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void checkInOverwritten() throws RepositoryException {
		if (fOverwriteRoots.isEmpty()) {
			return;
		}
		VersionManager versionManager = fSession.getWorkspace().getVersionManager();
		// Snapshot to a list: checkin reads each node's final, imported state.
		List<String> roots = new ArrayList<>(fOverwriteRoots);
		for (String root : roots) {
			if (fSession.nodeExists(root)) {
				versionManager.checkin(root);
			}
		}
	}

	@Override
	public int getUuidBehavior() {
		return fUuidBehavior;
	}

	@Override
	public int getPathBehavior() {
		return fPathBehavior;
	}

	@Override
	public void close() {
		if (fClosed) {
			return;
		}
		fClosed = true;
		fSession.endImportScope();
	}

	private static String childPath(String parent, String name) {
		return parent.endsWith("/") ? parent + name : parent + "/" + name;
	}

	private static final class ResultImpl implements Result {
		private final Disposition fDisposition;
		private final Node fNode;

		ResultImpl(Disposition disposition, Node node) {
			fDisposition = disposition;
			fNode = node;
		}

		@Override
		public Disposition getDisposition() {
			return fDisposition;
		}

		@Override
		public Node getNode() {
			return fNode;
		}
	}
}

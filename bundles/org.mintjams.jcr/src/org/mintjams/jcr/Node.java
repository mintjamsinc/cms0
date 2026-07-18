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

import javax.jcr.RepositoryException;

public interface Node extends javax.jcr.Node {

	String JCR_SYSTEM_NAME = "jcr:system";
	String JCR_VERSION_STORAGE_NAME = "jcr:versionStorage";
	String JCR_CONTENT_NAME = "jcr:content";
	String JCR_ROOT_VERSION_NAME = "jcr:rootVersion";
	String JCR_FROZEN_NODE_NAME = "jcr:frozenNode";

	String[] getPropertyKeys() throws RepositoryException;

	/**
	 * Add a child node with a caller-supplied identifier (UUID), preserving node
	 * identity across an export/import round-trip so that references — which are
	 * stored as the target's identifier — keep resolving after a restore. This
	 * is the identity-preserving counterpart of
	 * {@link javax.jcr.Node#addNode(String, String)}, which always mints a fresh
	 * identifier.
	 *
	 * <p>The identifier must be free: if a node with it already exists anywhere
	 * in the workspace, {@link javax.jcr.ItemExistsException} is thrown. A
	 * restore that means to overwrite existing data removes the colliding node
	 * first; a clone/copy omits the identifier (using the standard
	 * {@code addNode}) so a new one is allocated. An empty or {@code null}
	 * identifier behaves exactly like {@code addNode(relPath, primaryNodeTypeName)}.
	 *
	 * <p>Intended for trusted backup/restore tooling; ordinary content editing
	 * uses the standard {@code addNode}.
	 *
	 * @param relPath              the path of the new node, relative to this node.
	 * @param primaryNodeTypeName  the primary node type, or {@code null} to let
	 *                             the parent's child-node definitions decide.
	 * @param identifier           the identifier (UUID) to assign to the new node.
	 */
	javax.jcr.Node addNode(String relPath, String primaryNodeTypeName, String identifier) throws RepositoryException;

	/**
	 * Add a child node with a caller-supplied identifier (UUID) <em>and</em>
	 * creation timestamp, so an export/import round-trip can bring a node back as
	 * the same node with its original {@code jcr:created} preserved.
	 *
	 * <p>{@code jcr:created} is a protected property: once the repository sets it
	 * on first persist it can never be written again through {@code setProperty}.
	 * The only principled way to carry the original value across a restore is to
	 * supply it at creation time, which is what this method does. When
	 * {@code created} is {@code null} the repository assigns the current time,
	 * exactly as the standard creation path does.
	 *
	 * <p>{@code jcr:createdBy} is intentionally <em>not</em> caller-supplied: it
	 * always records the user performing the creation (on restore, the importing
	 * user), so provenance of who introduced the node into this repository is
	 * never falsified.
	 *
	 * <p>Identifier semantics are identical to
	 * {@link #addNode(String, String, String)}: a non-empty identifier must be
	 * free or {@link javax.jcr.ItemExistsException} is thrown; an empty or
	 * {@code null} identifier allocates a fresh one. Intended for trusted
	 * backup/restore tooling; ordinary content editing uses the standard
	 * {@code addNode}.
	 *
	 * @param relPath              the path of the new node, relative to this node.
	 * @param primaryNodeTypeName  the primary node type, or {@code null} to let
	 *                             the parent's child-node definitions decide.
	 * @param identifier           the identifier (UUID) to assign, or {@code null}
	 *                             to allocate a fresh one.
	 * @param created              the {@code jcr:created} timestamp to assign, or
	 *                             {@code null} to use the current time.
	 */
	javax.jcr.Node addNode(String relPath, String primaryNodeTypeName, String identifier, java.util.Calendar created) throws RepositoryException;

	/**
	 * Removes this node and its entire subtree — including {@code jcr:content}
	 * nodes, the version histories the subtree owns, and the binaries they
	 * reference — with set-based statements instead of per-node recursion.
	 * Everything is staged in the session's current transaction as one unit
	 * (an {@code nt:file} and its {@code jcr:content} can never end up in
	 * different {@code save()} calls) and is persisted by the next
	 * {@link javax.jcr.Session#save()}.
	 *
	 * <p>The set-based path applies a single privilege/lock/protected check to
	 * the subtree root, so it is only taken when that check is guaranteed to
	 * hold for every descendant: the node is a plain {@code nt:file} or
	 * {@code nt:folder}, no descendant carries its own access control entries
	 * (unless the session is privileged), and no descendant is locked by
	 * another session. When any of these does not hold, <em>nothing is
	 * removed</em> and {@code -1} is returned; the caller falls back to
	 * {@link javax.jcr.Node#remove()}, which checks each node individually.
	 *
	 * @return the number of removed content items (subtree nodes other than
	 *         {@code jcr:content} nodes and version storage internals), or
	 *         {@code -1} when this node is not eligible for set-based removal
	 *         and nothing was removed.
	 * @throws RepositoryException if the root check fails (access denied, the
	 *         node is locked, or the node is protected) or the removal itself
	 *         fails.
	 */
	default long removeTree() throws RepositoryException {
		return -1;
	}

	/**
	 * Removes the named children of this node, each with its entire subtree, as
	 * one set-based unit — the batched form of {@link #removeTree()}.
	 *
	 * <p>Deleting a large tree through {@link #removeTree()} on its root is a
	 * single unit of unbounded size: the transaction, the memory held for the
	 * collected subtree and the interval between progress reports all grow with
	 * the tree. Handing a container's children over a batch at a time keeps the
	 * statements set-based while bounding all three by the batch size.
	 *
	 * <p>The eligibility rules of {@link #removeTree()} apply, but the checks
	 * that cover a whole subtree are answered once for this node — which
	 * contains every batch — instead of once per child.
	 *
	 * <p>Children that are no longer live are ignored, so a batch may be
	 * replayed after a rollback.
	 *
	 * @param children the children to remove. Every one of them must be a child
	 *                 of this node — the subtree-wide checks are answered for
	 *                 this node and would not cover anything outside it.
	 * @return the number of removed content items (as {@link #removeTree()}
	 *         counts them), or {@code -1} when the children are not eligible for
	 *         set-based removal and nothing was removed.
	 * @throws RepositoryException if a check fails (access denied, a node is
	 *         locked, or a node is protected) or the removal itself fails.
	 */
	default long removeChildTrees(java.util.Collection<javax.jcr.Node> children) throws RepositoryException {
		return -1;
	}

//	AccessControlPolicy[] getPolicies() throws PathNotFoundException, AccessDeniedException, RepositoryException;

}

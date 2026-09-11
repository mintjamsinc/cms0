/*
 * Copyright (c) 2026 MintJams Inc.
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

/**
 * Opens and closes existing workspaces on this repository node, without
 * creating or deleting them. Obtained by adapting an administrative session
 * (admin, system or service) to this interface.
 *
 * <p>{@link javax.jcr.Workspace#createWorkspace(String)} and
 * {@link javax.jcr.Workspace#deleteWorkspace(String)} create or remove a
 * workspace directory and open or close it on the node that performs them.
 * In a cluster the directory lives on shared storage, so the other nodes
 * only need to open or close the workspace; this interface is how the layer
 * that coordinates the cluster does that. The repository never opens or
 * closes a workspace on its own after startup.
 */
public interface WorkspaceManager {

	/**
	 * Returns the names of the workspaces open on this node.
	 */
	String[] getOpenWorkspaceNames();

	/**
	 * Returns whether the workspace directory exists and is ready to be
	 * opened (its {@code etc/jcr/jcr.yml} is present), whether or not the
	 * workspace is open on this node.
	 */
	boolean isWorkspaceAvailable(String workspaceName);

	/**
	 * Opens an existing workspace directory on this node. Does nothing when
	 * the workspace is already open.
	 */
	void openWorkspace(String workspaceName) throws RepositoryException;

	/**
	 * Closes a workspace on this node and keeps its directory. Does nothing
	 * when the workspace is not open. The system workspace cannot be closed.
	 */
	void closeWorkspace(String workspaceName) throws RepositoryException;

	/**
	 * Removes the directory of a workspace that is not open on this node.
	 * Does nothing when the directory does not exist. The caller is
	 * responsible for making sure no other node still has it open.
	 */
	void removeWorkspaceDirectory(String workspaceName) throws RepositoryException;

}

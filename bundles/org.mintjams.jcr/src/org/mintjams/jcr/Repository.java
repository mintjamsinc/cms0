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
import org.mintjams.jcr.security.IdentityProvider;
import org.mintjams.jcr.security.PrincipalProvider;
import org.mintjams.jcr.security.UserPrincipal;

public interface Repository extends javax.jcr.Repository {

	String REPOSITORY_PATH = "repository.path";

	/**
	 * Descriptor key for this node's temporary directory. The directory is
	 * node-local — in a clustered deployment every node has its own, even
	 * when the repository directory itself lives on shared storage — and
	 * its contents do not survive a restart.
	 */
	String REPOSITORY_TMP_PATH = "repository.tmp.path";

	String[] getAvailableWorkspaceNames();

	PrincipalProvider getPrincipalProvider();

	IdentityProvider getIdentityProvider();

	/**
	 * Decides whether a user holds the given privileges on a path of a workspace
	 * from the committed access control entries, without opening a session. The
	 * rules are those a session of the user would be subject to; the user and
	 * the groups are the ones {@link #getPrincipalProvider()} resolves. The node
	 * need not exist. Meant for callers that evaluate many paths for many users,
	 * such as event delivery, where a login per decision would be the cost.
	 *
	 * @throws javax.jcr.NoSuchWorkspaceException when the workspace is not live
	 */
	boolean hasPrivileges(String workspaceName, UserPrincipal user, Collection<GroupPrincipal> groups, String absPath,
			String... privileges) throws RepositoryException;

	/**
	 * The revision of the committed access control entries of a workspace. It
	 * changes whenever an access control change is observed on this node, so a
	 * decision of {@link #hasPrivileges} taken at one revision stays valid while
	 * the revision does.
	 *
	 * @throws javax.jcr.NoSuchWorkspaceException when the workspace is not live
	 */
	long getAccessControlRevision(String workspaceName) throws RepositoryException;

}

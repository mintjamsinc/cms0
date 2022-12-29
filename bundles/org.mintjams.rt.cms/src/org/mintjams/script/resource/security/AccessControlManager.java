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

package org.mintjams.script.resource.security;

import java.util.Arrays;

import javax.jcr.RepositoryException;
import javax.jcr.security.AccessControlPolicy;

import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.jcr.security.Privilege;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Session;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class AccessControlManager implements Adaptable {

	private final Session fSession;

	public AccessControlManager(Session session) {
		fSession = session;
	}

	public AccessControlList getAccessControlList(String absPath) throws ResourceException {
		try {
			return (AccessControlList) getJcrAccessControlManager().getPolicies(absPath)[0];
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public void setAccessControlList(String absPath, AccessControlList acl) throws ResourceException {
		setPolicy(absPath, acl);
	}

	public AccessControlList[] getEffectiveAccessControlList(String absPath) throws ResourceException {
		try {
			return Arrays.asList(getJcrAccessControlManager().getEffectivePolicies(absPath))
					.stream().map(e -> e).toArray(AccessControlList[]::new);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Privilege[] getPrivileges(String absPath) throws ResourceException {
		try {
			return Arrays.asList(getJcrAccessControlManager().getPrivileges(absPath))
					.stream().map(e -> e).toArray(Privilege[]::new);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Privilege[] getSupportedPrivileges(String absPath) throws ResourceException {
		try {
			return Arrays.asList(getJcrAccessControlManager().getSupportedPrivileges(absPath))
					.stream().map(e -> e).toArray(Privilege[]::new);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public boolean hasPrivileges(String absPath, javax.jcr.security.Privilege... privileges) throws ResourceException {
		try {
			return getJcrAccessControlManager().hasPrivileges(absPath, privileges);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Privilege privilegeFromName(String privilegeName) throws ResourceException {
		try {
			return (Privilege) getJcrAccessControlManager().privilegeFromName(privilegeName);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public void setPolicy(String absPath, AccessControlPolicy policy) throws ResourceException {
		try {
			getJcrAccessControlManager().setPolicy(absPath, policy);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public void removePolicy(String absPath) throws ResourceException {
		try {
			getJcrAccessControlManager().removePolicy(absPath);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	private org.mintjams.jcr.security.AccessControlManager getJcrAccessControlManager() throws RepositoryException {
		return (org.mintjams.jcr.security.AccessControlManager) fSession.adaptTo(javax.jcr.Session.class).getAccessControlManager();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fSession, adapterType);
	}

}

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

package org.mintjams.rt.cms.internal.security;

import java.security.Principal;
import java.util.Collection;

import javax.jcr.RepositoryException;

import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.spi.security.PrincipalProvider;
import org.mintjams.rt.cms.internal.CmsService;

public class DefaultPrincipalProvider implements PrincipalProvider {

	@Override
	public Principal getPrincipal(String name) throws PrincipalNotFoundException {
		javax.jcr.Session systemSession = null;
		try {
			systemSession = CmsService.getRepository().login(new CmsServiceCredentials(), "system");

			if (systemSession.nodeExists("/home/users/" + name + "/profile")) {
				return new DefaultUserPrincipal(name);
			}

			if (systemSession.nodeExists("/home/groups/" + name + "/profile")) {
				return new DefaultGroupPrincipal(name);
			}

			throw new PrincipalNotFoundException(name);
		} catch (RepositoryException ex) {
			CmsService.getLogger(getClass()).error("Failed to get principal: " + name, ex);
			throw new PrincipalNotFoundException(name, ex);
		} finally {
			try {
				systemSession.logout();
			} catch (Throwable ignore) {}
			systemSession = null;
		}
	}

	@Override
	public UserPrincipal getUserPrincipal(String name) throws PrincipalNotFoundException {
		Principal p = getPrincipal(name);
		if (p instanceof UserPrincipal) {
			return (UserPrincipal) p;
		}

		throw new PrincipalNotFoundException(name);
	}

	@Override
	public GroupPrincipal getGroupPrincipal(String name) throws PrincipalNotFoundException {
		Principal p = getPrincipal(name);
		if (p instanceof GroupPrincipal) {
			return (GroupPrincipal) p;
		}

		throw new PrincipalNotFoundException(name);
	}

	@Override
	public Collection<GroupPrincipal> getMemberOf(Principal principal) {
		throw new UnsupportedOperationException();
	}

}

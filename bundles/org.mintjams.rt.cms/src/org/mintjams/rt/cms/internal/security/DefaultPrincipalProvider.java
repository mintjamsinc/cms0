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

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;

import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.GuestPrincipal;
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

			// Handle special principals
			if (name.equals("anonymous")) {
				return new GuestPrincipal();
			}
			if (name.equals("everyone")) {
				return new EveryonePrincipal();
			}

			// First, try to find a user with the given name
			try {
				Node contentNode = systemSession.getNode("/home/users/" + name + "/profile/jcr:content");
				if (contentNode.getProperty("identifier").getString().equals(name) &&
						!contentNode.getProperty("isGroup").getBoolean()) {
					return new DefaultUserPrincipal(name);
				}
			} catch (PathNotFoundException ignore) {}

			// If not found, try to find a group with the given name
			{
				String escapedName = name.replace("'", "\\'");
				String stmt = "/jcr:root/home/groups//*[@identifier = '" + escapedName + "' and @isGroup = true]";
				Query q = systemSession.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH);
				q.setOffset(0);
				q.setLimit(1);
				if (q.execute().getNodes().hasNext()) {
					return new DefaultGroupPrincipal(name);
				}
			}

			// Not found
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

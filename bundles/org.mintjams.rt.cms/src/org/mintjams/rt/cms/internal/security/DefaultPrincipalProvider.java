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
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;

import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.spi.security.JcrPrincipalProvider;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;

public class DefaultPrincipalProvider implements JcrPrincipalProvider {

	@Override
	public Principal getPrincipal(String name) throws PrincipalNotFoundException {
		javax.jcr.Session systemSession = null;
		try {
			systemSession = CmsService.getRepository().login(new CmsServiceCredentials(), "system");

			String escapedName = name.replace("'", "\\'");
			String stmt = "/jcr:root/home//*[@identifier = '" + escapedName + "']";

			Query q = systemSession.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH);
			q.setOffset(0);
			q.setLimit(1);
			QueryResult qr = q.execute();

			NodeIterator nodes = qr.getNodes();
			if (!nodes.hasNext()) {
				throw new PrincipalNotFoundException(name);
			}

			Node node = nodes.nextNode();
			if (!JCRs.isContentNode(node)) {
				node = JCRs.getContentNode(node);
			}

			Principal p;
			if (node.getProperty("isGroup").getBoolean()) {
				p = new DefaultGroupPrincipal(node.getProperty("identifier").getString());
			} else {
				p = new DefaultUserPrincipal(node.getProperty("identifier").getString());
			}
			return p;
		} catch (PrincipalNotFoundException ex) {
			throw new PrincipalNotFoundException(name, ex);
		} catch (RepositoryException ex) {
			CmsService.getLogger(getClass()).error("Failed to get principal: " + name, ex);
			throw new PrincipalNotFoundException(name, ex);
		} finally {
			if (systemSession != null) {
				systemSession.logout();
				systemSession = null;
			}
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

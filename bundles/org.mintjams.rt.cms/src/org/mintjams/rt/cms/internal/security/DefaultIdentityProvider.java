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

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;

import org.mintjams.jcr.security.Group;
import org.mintjams.jcr.security.Role;
import org.mintjams.jcr.security.User;
import org.mintjams.jcr.spi.security.IdentityProvider;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.lang.Cause;

public class DefaultIdentityProvider implements IdentityProvider {

	@Override
	public User getUser(String identifier) {
		javax.jcr.Session systemSession = null;
		try {
			systemSession = CmsService.getRepository().login(new CmsServiceCredentials(), "system");

			try {
				Node node = systemSession.getNode("/home/users/" + identifier + "/profile");
				Node contentNode = JCRs.getContentNode(node);
				if (contentNode.getProperty("identifier").getString().equals(identifier) &&
						!contentNode.getProperty("isGroup").getBoolean()) {
					return new UserImpl(node);
				}
			} catch (PathNotFoundException ignore) {}

			return null;
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		} finally {
			try {
				systemSession.logout();
			} catch (Throwable ignore) {}
			systemSession = null;
		}
	}

	@Override
	public Group getGroup(String identifier) {
		javax.jcr.Session systemSession = null;
		try {
			systemSession = CmsService.getRepository().login(new CmsServiceCredentials(), "system");

			{
				String escapedIdentifier = identifier.replace("'", "\\'");
				String stmt = "/jcr:root/home/groups//*[@identifier = '" + escapedIdentifier + "' and @isGroup = true]";
				Query q = systemSession.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH);
				q.setOffset(0);
				q.setLimit(1);
				if (q.execute().getNodes().hasNext()) {
					return new GroupImpl(q.execute().getNodes().nextNode());
				}
			}

			return null;
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		} finally {
			try {
				systemSession.logout();
			} catch (Throwable ignore) {}
			systemSession = null;
		}
	}

	@Override
	public Role getRole(String identifier) {
		javax.jcr.Session systemSession = null;
		try {
			systemSession = CmsService.getRepository().login(new CmsServiceCredentials(), "system");

			{
				String escapedIdentifier = identifier.replace("'", "\\'");
				String stmt = "/jcr:root/home/roles//*[@identifier = '" + escapedIdentifier + "']";
				Query q = systemSession.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH);
				q.setOffset(0);
				q.setLimit(1);
				if (q.execute().getNodes().hasNext()) {
					return new RoleImpl(q.execute().getNodes().nextNode());
				}
			}

			return null;
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		} finally {
			try {
				systemSession.logout();
			} catch (Throwable ignore) {}
			systemSession = null;
		}
	}

	private static final class UserImpl implements User {
		private final String fIdentifier;
		private final String fDisplayName;
		private final String fEmail;

		public UserImpl(Node node) throws RepositoryException {
			Node contentNode = JCRs.getContentNode(node);
			fIdentifier = contentNode.getProperty("identifier").getString();
			fDisplayName = contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null;
			fEmail = contentNode.hasProperty("mail") ? contentNode.getProperty("mail").getString() : null;
		}

		@Override
		public String getIdentifier() {
			return fIdentifier;
		}

		@Override
		public String getDisplayName() {
			return fDisplayName;
		}

		@Override
		public String getEmail() {
			return fEmail;
		}
	}

	private static final class GroupImpl implements Group {
		private final String fIdentifier;
		private final String fDisplayName;
		private final String fDescription;

		public GroupImpl(Node node) throws RepositoryException {
			Node contentNode = JCRs.getContentNode(node);
			fIdentifier = contentNode.getProperty("identifier").getString();
			fDisplayName = contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null;
			fDescription = contentNode.hasProperty("description") ? contentNode.getProperty("description").getString() : null;
		}

		@Override
		public String getIdentifier() {
			return fIdentifier;
		}

		@Override
		public String getDisplayName() {
			return fDisplayName;
		}

		@Override
		public String getDescription() {
			return fDescription;
		}
	}

	private static final class RoleImpl implements Role {
		private final String fIdentifier;
		private final String fDisplayName;
		private final String fDescription;

		public RoleImpl(Node node) throws RepositoryException {
			Node contentNode = JCRs.getContentNode(node);
			fIdentifier = contentNode.getProperty("identifier").getString();
			fDisplayName = contentNode.hasProperty("displayName") ? contentNode.getProperty("displayName").getString() : null;
			fDescription = contentNode.hasProperty("description") ? contentNode.getProperty("description").getString() : null;
		}

		@Override
		public String getIdentifier() {
			return fIdentifier;
		}

		@Override
		public String getDisplayName() {
			return fDisplayName;
		}

		@Override
		public String getDescription() {
			return fDescription;
		}
	}

}

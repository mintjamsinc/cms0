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

package org.mintjams.rt.cms.internal.bpm;

import java.util.Collections;
import java.util.List;

import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.identity.GroupQuery;
import org.camunda.bpm.engine.identity.NativeUserQuery;
import org.camunda.bpm.engine.identity.Tenant;
import org.camunda.bpm.engine.identity.TenantQuery;
import org.camunda.bpm.engine.identity.User;
import org.camunda.bpm.engine.identity.UserQuery;
import org.camunda.bpm.engine.impl.GroupQueryImpl;
import org.camunda.bpm.engine.impl.Page;
import org.camunda.bpm.engine.impl.UserQueryImpl;
import org.camunda.bpm.engine.impl.identity.ReadOnlyIdentityProvider;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.mintjams.rt.cms.internal.CmsService;

public class CmsIdentityProvider implements ReadOnlyIdentityProvider {

	@Override
	public void close() {
		// nothing to do
	}

	@Override
	public void flush() {
		// nothing to do
	}

	@Override
	public boolean checkPassword(String userId, String password) {
		throw new UnsupportedOperationException("checkPassword is not supported");
	}

	@Override
	public GroupQuery createGroupQuery() {
		return new CmsGroupQuery();
	}

	@Override
	public GroupQuery createGroupQuery(CommandContext commandContext) {
		return createGroupQuery();
	}

	@Override
	public NativeUserQuery createNativeUserQuery() {
		throw new UnsupportedOperationException("NativeUserQuery is not supported");
	}

	@Override
	public TenantQuery createTenantQuery() {
		throw new UnsupportedOperationException("TenantQuery is not supported");
	}

	@Override
	public TenantQuery createTenantQuery(CommandContext commandContext) {
		throw new UnsupportedOperationException("TenantQuery is not supported");
	}

	@Override
	public UserQuery createUserQuery() {
		return new CmsUserQuery();
	}

	@Override
	public UserQuery createUserQuery(CommandContext commandContext) {
		return createUserQuery();
	}

	@Override
	public Group findGroupById(String groupId) {
		try {
			if (!CmsService.groupExists(groupId)) {
				return null;
			}
			return new CmsGroup(groupId);
		} catch (Throwable ex) {
			throw new IllegalStateException("Failed to find group by id: " + groupId, ex);
		}
	}

	@Override
	public Tenant findTenantById(String tenantId) {
		throw new UnsupportedOperationException("Tenant is not supported");
	}

	@Override
	public User findUserById(String userId) {
		try {
			if (!CmsService.userExists(userId)) {
				return null;
			}
			return new CmsUser(userId);
		} catch (Throwable ex) {
			throw new IllegalStateException("Failed to find user by id: " + userId, ex);
		}
	}

	private static class CmsUser implements User {
		private static final long serialVersionUID = 1L;

		private final String id;

		public CmsUser(String id) {
			this.id = id;
		}

		@Override
		public String getEmail() {
			return null;
		}

		@Override
		public String getFirstName() {
			return null;
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public String getLastName() {
			return null;
		}

		@Override
		public String getPassword() {
			return null;
		}

		@Override
		public void setEmail(String email) {
			// read-only
		}

		@Override
		public void setFirstName(String firstName) {
			// read-only
		}

		@Override
		public void setId(String id) {
			// read-only
		}

		@Override
		public void setLastName(String lastName) {
			// read-only
		}

		@Override
		public void setPassword(String password) {
			// read-only
		}
	}

	private static class CmsGroup implements Group {
		private static final long serialVersionUID = 1L;

		private final String id;

		public CmsGroup(String id) {
			this.id = id;
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public String getName() {
			return null;
		}

		@Override
		public String getType() {
			return null;
		}

		@Override
		public void setId(String id) {
			// read-only
		}

		@Override
		public void setName(String name) {
			// read-only
		}

		@Override
		public void setType(String type) {
			// read-only
		}
	}

	private class CmsUserQuery extends UserQueryImpl {
		private static final long serialVersionUID = 1L;

		@Override
		public long executeCount(CommandContext commandContext) {
			return executeList(commandContext, null).size();
		}

		@Override
		public List<User> executeList(CommandContext commandContext, Page page) {
			if (this.getId() != null) {
				try {
					if (CmsService.userExists(this.getId())) {
						return Collections.singletonList(new CmsUser(this.getId()));
					}
				} catch (Throwable ex) {
					throw new IllegalStateException("Failed to execute user query", ex);
				}
			}
			return Collections.emptyList();
		}
	}

	private static class CmsGroupQuery extends GroupQueryImpl {
		private static final long serialVersionUID = 1L;

		@Override
		public long executeCount(CommandContext commandContext) {
			return executeList(commandContext, null).size();
		}

		@Override
		public List<Group> executeList(CommandContext commandContext, Page page) {
			if (this.getId() != null) {
				try {
					if (CmsService.groupExists(this.getId())) {
						return Collections.singletonList(new CmsGroup(this.getId()));
					}
				} catch (Throwable ex) {
					throw new IllegalStateException("Failed to execute group query", ex);
				}
			}
			return Collections.emptyList();
		}
	}
}

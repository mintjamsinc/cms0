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

import javax.jcr.Credentials;
import javax.security.auth.login.LoginException;

import org.mintjams.jcr.Workspace;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.spi.security.Authenticator;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.lang.Cause;

public class UserServiceAuthenticator implements Authenticator {

	@Override
	public boolean canAuthenticate(Credentials credentials) {
		return (credentials instanceof UserServiceCredentials);
	}

	@Override
	public Result authenticate(Credentials credentials) throws LoginException {
		if (!canAuthenticate(credentials)) {
			throw new LoginException("The specified credential is not a user service credential.");
		}

		javax.jcr.Session systemSession = null;
		try {
			systemSession = CmsService.getRepository().login(new CmsServiceCredentials(), "system");
			UserPrincipal principal = Workspace.class.cast(systemSession.getWorkspace())
					.getPrincipalProvider()
					.getUserPrincipal(((UserServiceCredentials) credentials).getUserID());
			return new ResultImpl(principal);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(LoginException.class);
		} finally {
			try {
				systemSession.logout();
			} catch (Throwable ignore) {}
			systemSession = null;
		}
	}

	public class ResultImpl implements Result {
		private final UserPrincipal fUserPrincipal;

		private ResultImpl(UserPrincipal principal) {
			fUserPrincipal = principal;
		}

		@Override
		public UserPrincipal getUserPrincipal() {
			return fUserPrincipal;
		}
	}

}

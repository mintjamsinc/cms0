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

package org.mintjams.rt.cms.internal.security.auth.saml2;

import javax.jcr.Credentials;
import javax.security.auth.login.LoginException;

import org.mintjams.jcr.Repository;
import org.mintjams.jcr.security.User;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.spi.security.Authenticator;
import org.mintjams.rt.cms.internal.CmsService;

public class Saml2Authenticator implements Authenticator {

	private final Saml2ServiceProviderConfiguration fConfig;

	public Saml2Authenticator(Saml2ServiceProviderConfiguration config) {
		fConfig = config;
	}

	@Override
	public boolean canAuthenticate(Credentials credentials) {
		return (credentials instanceof Saml2Credentials);
	}

	@Override
	public Result authenticate(Credentials credentials) throws LoginException {
		if (!canAuthenticate(credentials)) {
			throw new LoginException("The specified credential is not a SAML2 credential.");
		}

		Saml2Credentials creds = (Saml2Credentials) credentials;
		User user = Repository.class.cast(CmsService.getRepository()).getIdentityProvider().getUser(creds.getName());
		if (user == null) {
			throw new LoginException("User not found: " + creds.getName());
		}
		if (!user.isEnabled()) {
			throw new LoginException("User is disabled: " + creds.getName());
		}
		return new ResultImpl((Saml2Credentials) credentials, user.hasRole("administrator"));
	}

	public class ResultImpl implements Result {
		private final Saml2UserPrincipal fUserPrincipal;

		private ResultImpl(Saml2Credentials credentials, boolean isAdmin) {
			if (isAdmin) {
				fUserPrincipal = new Saml2AdminPrincipal(credentials);
			} else {
				fUserPrincipal = new Saml2UserPrincipal(credentials);
			}
		}

		@Override
		public UserPrincipal getUserPrincipal() {
			return fUserPrincipal;
		}
	}

}

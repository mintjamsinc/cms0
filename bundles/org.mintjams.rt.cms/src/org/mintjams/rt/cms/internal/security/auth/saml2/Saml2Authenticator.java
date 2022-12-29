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

import java.util.Arrays;
import java.util.List;

import javax.jcr.Credentials;
import javax.security.auth.login.LoginException;

import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.spi.security.JcrAuthenticator;
import org.mintjams.jcr.util.ExpressionContext;

public class Saml2Authenticator implements JcrAuthenticator {

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

		return new ResultImpl((Saml2Credentials) credentials);
	}

	public class ResultImpl implements Result {
		private final Saml2Credentials fCredentials;
		private final Saml2UserPrincipal fUserPrincipal;

		private ResultImpl(Saml2Credentials credentials) {
			fCredentials = credentials;
			if (isAdmin()) {
				fUserPrincipal = new Saml2AdminPrincipal(credentials);
			} else {
				fUserPrincipal = new Saml2UserPrincipal(credentials);
			}
		}

		private boolean isAdmin() {
			ExpressionContext el = fConfig.getExpressionContext();
			List<String> roleNames = Arrays.asList(el.getStringArray("config.user.attributes.role.name"));
			List<String> adminRoles = Arrays.asList(el.getStringArray("config.user.attributes.role.adminRole"));
			for (String roleName : roleNames) {
				List<String> roles = fCredentials.getAttributes().get(roleName);
				if (roles == null) {
					continue;
				}

				for (String adminRole : adminRoles) {
					if (roles.contains(adminRole)) {
						return true;
					}
				}
			}
			return false;
		}

		@Override
		public UserPrincipal getUserPrincipal() {
			return fUserPrincipal;
		}
	}

}

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

package org.mintjams.jcr.spi.security;

import javax.jcr.Credentials;
import javax.security.auth.login.LoginException;

import org.mintjams.jcr.security.UserPrincipal;

public interface Authenticator {

	/**
	 * Returns {@code true} if this authenticator can authenticate the specified credentials; otherwise returns {@code false}.
	 * 
	 * @param credentials the credentials to be authenticated
	 * @return {@code true} if this authenticator can authenticate the specified credentials; otherwise returns {@code false}
	 */
	boolean canAuthenticate(Credentials credentials);

	/**
	 * Authenticates the specified credentials.
	 * 
	 * @param credentials the credentials to be authenticated
	 * @return the result of authentication
	 * @throws LoginException if the authentication fails
	 */
	Result authenticate(Credentials credentials) throws LoginException;

	/**
	 * The result of authentication.
	 */
	interface Result {
		/**
		 * Returns the user principal of the authenticated user.
		 * 
		 * @return the user principal of the authenticated user
		 */
		UserPrincipal getUserPrincipal();
	}

}

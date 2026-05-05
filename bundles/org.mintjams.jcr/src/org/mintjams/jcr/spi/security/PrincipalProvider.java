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

import java.security.Principal;
import java.util.Collection;

import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.UserPrincipal;

public interface PrincipalProvider {

	/**
	 * Returns the principal with the specified name.
	 * 
	 * @param name the principal name
	 * @return the principal with the specified name
	 * @throws PrincipalNotFoundException if no such principal exists
	 */
	Principal getPrincipal(String name) throws PrincipalNotFoundException;

	/**
	 * Returns the user principal with the specified name.
	 * 
	 * @param name the user principal name
	 * @return the user principal with the specified name
	 * @throws PrincipalNotFoundException if no such user principal exists
	 */
	UserPrincipal getUserPrincipal(String name) throws PrincipalNotFoundException;

	/**
	 * Returns the group principal with the specified name.
	 * 
	 * @param name the group principal name
	 * @return the group principal with the specified name
	 * @throws PrincipalNotFoundException if no such group principal exists
	 */
	GroupPrincipal getGroupPrincipal(String name) throws PrincipalNotFoundException;

	/**
	 * Returns the collection of group principals that the specified principal is a member of.
	 * 
	 * @param principal the principal
	 * @return the collection of group principals that the specified principal is a member of
	 * @throws PrincipalNotFoundException if the specified principal does not exist
	 */
	Collection<GroupPrincipal> getMemberOf(Principal principal) throws PrincipalNotFoundException;

}

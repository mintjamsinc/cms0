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
import java.util.List;
import java.util.stream.Collectors;

import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.spi.security.JcrPrincipalProvider;
import org.mintjams.rt.cms.internal.CmsService;

public class ServiceAccountPrincipalProvider implements JcrPrincipalProvider {

	@Override
	public UserPrincipal getUserPrincipal(String name) throws PrincipalNotFoundException {
		if (!CmsService.getConfiguration().getServiceUserAccounts().containsKey(name)) {
			throw new PrincipalNotFoundException(name);
		}
		return new DefaultUserPrincipal(name);
	}

	@Override
	public GroupPrincipal getGroupPrincipal(String name) throws PrincipalNotFoundException {
		if (!CmsService.getConfiguration().getServiceGroupAccounts().containsKey(name)) {
			throw new PrincipalNotFoundException(name);
		}
		return new DefaultGroupPrincipal(name);
	}

	@Override
	public Collection<GroupPrincipal> getMemberOf(Principal principal) {
		@SuppressWarnings("unchecked")
		List<String> groups = (List<String>) CmsService.getConfiguration().getServiceUserAccounts().get(principal.getName()).get("groups");
		if (groups == null) {
			return List.of();
		}
		return groups.stream().map(g -> new DefaultGroupPrincipal(g)).collect(Collectors.toList());
	}

}

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

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.spi.security.JcrPrincipalProvider;
import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.DefaultGroupPrincipal;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Strings;

public class Saml2PrincipalProvider implements JcrPrincipalProvider {

	private final Saml2ServiceProviderConfiguration fConfig;

	public Saml2PrincipalProvider(Saml2ServiceProviderConfiguration config) {
		fConfig = config;
	}

	@Override
	public UserPrincipal getUserPrincipal(String name) throws PrincipalNotFoundException {
		throw new UnsupportedOperationException();
	}

	@Override
	public GroupPrincipal getGroupPrincipal(String name) throws PrincipalNotFoundException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Collection<GroupPrincipal> getMemberOf(Principal principal) {
		List<GroupPrincipal> l = new ArrayList<>();
		if (principal instanceof Saml2UserPrincipal) {
			ExpressionContext el = fConfig.getExpressionContext();
			String name = el.getString("config.user.attributes.memberOf.name");
			Saml2Credentials creds = Adaptables.getAdapter(principal, Saml2Credentials.class);
			List<String> groups = creds.getAttributes().get(name);
			if (groups != null) {
				for (String e : groups) {
					for (String group : e.split("\\s*[,\\/]\\s*")) {
						if (Strings.isEmpty(group)) {
							continue;
						}

						GroupPrincipal p = new DefaultGroupPrincipal(group);
						if (!l.contains(p)) {
							l.add(p);
						}
					}
				}
			} else {
				CmsService.getLogger(getClass()).warn("SAML attribute '" + name + "' is not found.");
			}
		}
		return l;
	}

}

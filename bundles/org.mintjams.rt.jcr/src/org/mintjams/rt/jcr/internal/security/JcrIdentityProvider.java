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

package org.mintjams.rt.jcr.internal.security;

import org.mintjams.jcr.security.Group;
import org.mintjams.jcr.security.IdentityProvider;
import org.mintjams.jcr.security.Role;
import org.mintjams.jcr.security.User;
import org.mintjams.rt.jcr.internal.Activator;
import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class JcrIdentityProvider implements IdentityProvider, Adaptable {

	private final JcrWorkspace fWorkspace;

	private JcrIdentityProvider(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static JcrIdentityProvider create(JcrWorkspace workspace) {
		return new JcrIdentityProvider(workspace);
	}

	@Override
	public User getUser(String identifier) {
		return Activator.getDefault().getUser(identifier);
	}

	@Override
	public Group getGroup(String identifier) {
		return Activator.getDefault().getGroup(identifier);
	}

	@Override
	public Role getRole(String identifier) {
		return Activator.getDefault().getRole(identifier);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}

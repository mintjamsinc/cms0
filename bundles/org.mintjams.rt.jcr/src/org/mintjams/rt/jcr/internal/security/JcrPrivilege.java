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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.JcrName;
import org.mintjams.jcr.NamespaceProvider;
import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class JcrPrivilege implements org.mintjams.jcr.security.Privilege, Adaptable {

	private final String fName;
	private final JcrWorkspace fWorkspace;
	private final List<JcrPrivilege> fAggregatePrivileges = new ArrayList<>();
	private boolean fAbstract;

	private JcrPrivilege(String name, JcrWorkspace workspace) {
		fName = name;
		fWorkspace = workspace;
	}

	public static JcrPrivilege create(String name, JcrWorkspace workspace) {
		return new JcrPrivilege(name, workspace);
	}

	@Override
	public Privilege[] getAggregatePrivileges() {
		List<Privilege> l = new ArrayList<>();
		for (Privilege e : getDeclaredAggregatePrivileges()) {
			l.add(e);
			if (e.isAggregate()) {
				l.addAll(Arrays.asList(e.getAggregatePrivileges()));
			}
		}
		return l.toArray(Privilege[]::new);
	}

	@Override
	public Privilege[] getDeclaredAggregatePrivileges() {
		return fAggregatePrivileges.toArray(Privilege[]::new);
	}

	@Override
	public String getName() {
		return JcrName.valueOf(fName).with(adaptTo(NamespaceProvider.class)).toString();
	}

	@Override
	public boolean isAbstract() {
		return fAbstract;
	}

	public JcrPrivilege addAggregatePrivilege(JcrPrivilege privilege) {
		if (fAggregatePrivileges.contains(privilege)) {
			throw new IllegalArgumentException("The specified privilege has already been added.");
		}

		fAggregatePrivileges.add(privilege);
		return this;
	}

	@Override
	public boolean isAggregate() {
		return !fAggregatePrivileges.isEmpty();
	}

	@Override
	public boolean contains(Privilege privilege) {
		if (privilege == null) {
			throw new IllegalArgumentException("Privilege must not be null.");
		}

		for (Privilege e : getDeclaredAggregatePrivileges()) {
			if (e.getName().equals(privilege.getName())) {
				return true;
			}
			if (((JcrPrivilege) e).contains(privilege)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean contains(String privilege) throws RepositoryException {
		return contains(adaptTo(JcrAccessControlManager.class).privilegeFromName(privilege));
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

	@Override
	public int hashCode() {
		return (JcrPrivilege.class.getSimpleName() + "|" + getName()).hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof Privilege)) {
			return false;
		}
		return (hashCode() == obj.hashCode());
	}

	@Override
	public String toString() {
		return getName();
	}

}

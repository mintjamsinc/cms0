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

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.jcr.security.Privilege;

import org.mintjams.jcr.security.AccessControlEntry;

public class JcrAccessControlEntry implements AccessControlEntry {

	private final Principal fPrincipal;
	private final List<Privilege> fPrivileges = new ArrayList<>();
	private boolean fAllow;

	private JcrAccessControlEntry(Principal principal) {
		fPrincipal = principal;
	}

	public static JcrAccessControlEntry create(Principal principal) {
		return new JcrAccessControlEntry(principal);
	}

	@Override
	public Principal getPrincipal() {
		return fPrincipal;
	}

	@Override
	public Privilege[] getPrivileges() {
		return fPrivileges.toArray(Privilege[]::new);
	}

	public JcrAccessControlEntry addPrivilege(Privilege privilege) {
		if (!fPrivileges.contains(privilege)) {
			fPrivileges.add(privilege);
		}
		return this;
	}

	public JcrAccessControlEntry removePrivilege(Privilege privilege) {
		fPrivileges.remove(privilege);
		return this;
	}

	@Override
	public boolean isAllow() {
		return fAllow;
	}

	public JcrAccessControlEntry setAllow(boolean isAllow) {
		fAllow = isAllow;
		return this;
	}

	@Override
	public int hashCode() {
		StringBuilder buf = new StringBuilder();
		buf.append(getPrincipal().getName());
		buf.append(" <").append(fAllow ? "allow" : "deny").append(">");
		return buf.toString().hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof JcrAccessControlEntry)) {
			return false;
		}
		return (hashCode() == obj.hashCode());
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append(getPrincipal().getName());
		buf.append(" (");
		Privilege[] privileges = getPrivileges();
		Arrays.sort(privileges, new Comparator<Privilege>() {
			@Override
			public int compare(Privilege o1, Privilege o2) {
				return o1.getName().compareTo(o2.getName());
			}
		});
		for (int i = 0; i < privileges.length; i++) {
			if (i > 0) {
				buf.append(", ");
			}
			buf.append(privileges[i].getName());
		}
		buf.append(")");
		buf.append(" <").append(fAllow ? "allow" : "deny").append(">");
		return buf.toString();
	}

}

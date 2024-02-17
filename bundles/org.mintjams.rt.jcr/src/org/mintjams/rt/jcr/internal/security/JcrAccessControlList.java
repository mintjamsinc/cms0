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
import java.util.Iterator;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

public class JcrAccessControlList implements AccessControlList, Adaptable {

	private final String fPath;
	private final JcrAccessControlManager fAccessControlManager;
	private final List<JcrAccessControlEntry> fAccessControlEntries = new ArrayList<>();

	private JcrAccessControlList(String path, JcrAccessControlManager accessControlManager) {
		fPath = path;
		fAccessControlManager = accessControlManager;
	}

	public static JcrAccessControlList create(String path, JcrAccessControlManager accessControlManager) {
		return new JcrAccessControlList(path, accessControlManager);
	}

	@Override
	public boolean addAccessControlEntry(Principal principal, Privilege[] privileges)
			throws AccessControlException, RepositoryException {
		return addAccessControlEntry(principal, true, privileges);
	}

	@Override
	public AccessControlEntry[] getAccessControlEntries() throws RepositoryException {
		return fAccessControlEntries.toArray(AccessControlEntry[]::new);
	}

	@Override
	public void removeAccessControlEntry(AccessControlEntry ace) throws AccessControlException, RepositoryException {
		if (!fAccessControlEntries.remove(ace)) {
			throw new AccessControlException("Invalid access control entry.");
		}
	}

	@Override
	public String getPath() {
		return fPath;
	}

	@Override
	public boolean addAccessControlEntry(Principal principal, boolean isAllow, Privilege... privileges)
			throws AccessControlException, RepositoryException {
		if (principal == null) {
			throw new IllegalArgumentException("Principal must not be null.");
		}
		if (privileges == null || privileges.length == 0) {
			throw new IllegalArgumentException("Principal must not be null or empty.");
		}

		boolean modified = false;
		List<Privilege> privilegeList = new ArrayList<>(Arrays.asList(privileges));
		for (JcrAccessControlEntry e : fAccessControlEntries) {
			for (Privilege privilege : privileges) {
				if (e.getPrincipal().equals(principal) && e.getPrivileges()[0].equals(privilege)) {
					privilegeList.remove(privilege);
					if (e.isAllow() != isAllow) {
						e.setAllow(isAllow);
						modified = true;
					}
				}
			}

			if (privilegeList.isEmpty()) {
				break;
			}
		}
		if (!privilegeList.isEmpty()) {
			JcrAccessControlEntry ace = JcrAccessControlEntry.create(principal).setAllow(isAllow);
			for (Privilege privilege : privilegeList) {
				ace.addPrivilege(privilege);
			}
			fAccessControlEntries.add(ace);
			modified = true;
		}
		return modified;
	}

	@Override
	public boolean addAccessControlEntry(Principal principal, boolean isAllow, String... privileges)
			throws AccessControlException, RepositoryException {
		List<Privilege> l = new ArrayList<>();
		for (String privilege : privileges) {
			l.add(fAccessControlManager.privilegeFromName(privilege));
		}
		return addAccessControlEntry(principal, isAllow, l.toArray(Privilege[]::new));
	}

	@Override
	public boolean isEmpty() {
		return fAccessControlEntries.isEmpty();
	}

	@Override
	public void clear() {
		fAccessControlEntries.clear();
	}

	@Override
	public Iterator<AccessControlEntry> iterator() {
		try {
			return Arrays.stream(getAccessControlEntries()).iterator();
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fAccessControlManager, adapterType);
	}

	@Override
	public int hashCode() {
		return (JcrAccessControlList.class.getSimpleName() + "|" + getPath()).hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof JcrAccessControlList)) {
			return false;
		}
		return (hashCode() == obj.hashCode());
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append(getPath());
		buf.append(" [");
		AccessControlEntry[] aces;
		try {
			aces = getAccessControlEntries();
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
		for (int i = 0; i < aces.length; i++) {
			if (i > 0) {
				buf.append(", ");
			}
			buf.append(aces[i].toString());
		}
		buf.append("]");
		return buf.toString();
	}

}

/*
 * Copyright (c) 2024 MintJams Inc.
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

package org.mintjams.idp.internal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mintjams.idp.internal.Activator;

/**
 * Represents an authenticated user in the IdP.
 */
public class IdpUser {

	private String fUsername;
	private String fDisplayName;
	private String fEmail;
	private List<String> fGroups = new ArrayList<>();
	private List<String> fRoles = new ArrayList<>();
	private Map<String, List<String>> fAttributes = new HashMap<>();

	public String getUsername() {
		return fUsername;
	}

	public void setUsername(String username) {
		fUsername = username;
	}

	public String getDisplayName() {
		return fDisplayName;
	}

	public void setDisplayName(String displayName) {
		fDisplayName = displayName;
	}

	public String getEmail() {
		return fEmail;
	}

	public void setEmail(String email) {
		fEmail = email;
	}

	public List<String> getGroups() {
		return Collections.unmodifiableList(fGroups);
	}

	public void setMemberOf(List<String> groups) {
		fGroups = new ArrayList<>(groups);
	}

	public void addMemberOf(String group) {
		fGroups.add(group);
	}

	public List<String> getRoles() {
		return Collections.unmodifiableList(fRoles);
	}

	public void setRoles(List<String> roles) {
		fRoles = new ArrayList<>(roles);
	}

	public void addRole(String role) {
		fRoles.add(role);
	}

	public Map<String, List<String>> getAttributes() {
		return Collections.unmodifiableMap(fAttributes);
	}

	public void addAttribute(String name, String value) {
		fAttributes.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
	}

	/**
	 * Gets all attributes including roles, suitable for SAML assertion.
	 * Roles are mapped to the configured role attribute name.
	 */
	public Map<String, List<String>> getAllAttributes() {
		String roleAttributeName = Activator.getDefault().getConfiguration().getRoleAttribute();

		Map<String, List<String>> all = new HashMap<>(fAttributes);
		if (roleAttributeName != null && !fRoles.isEmpty()) {
			all.put(roleAttributeName, new ArrayList<>(fRoles));
		}
		if (!fGroups.isEmpty()) {
			all.put("memberOf", new ArrayList<>(fGroups));
		}
		if (fEmail != null) {
			all.computeIfAbsent("email", k -> new ArrayList<>()).add(fEmail);
		}
		if (fDisplayName != null) {
			all.computeIfAbsent("displayName", k -> new ArrayList<>()).add(fDisplayName);
		}
		return all;
	}

}

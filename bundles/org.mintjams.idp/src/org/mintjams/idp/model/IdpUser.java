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

package org.mintjams.idp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents an authenticated user in the IdP.
 */
public class IdpUser {

	private String username;
	private String displayName;
	private String email;
	private List<String> roles = new ArrayList<>();
	private Map<String, List<String>> attributes = new HashMap<>();

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public List<String> getRoles() {
		return Collections.unmodifiableList(roles);
	}

	public void setRoles(List<String> roles) {
		this.roles = new ArrayList<>(roles);
	}

	public void addRole(String role) {
		this.roles.add(role);
	}

	public Map<String, List<String>> getAttributes() {
		return Collections.unmodifiableMap(attributes);
	}

	public void addAttribute(String name, String value) {
		attributes.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
	}

	/**
	 * Gets all attributes including roles, suitable for SAML assertion.
	 * Roles are mapped to the configured role attribute name.
	 */
	public Map<String, List<String>> getAllAttributes(String roleAttributeName) {
		Map<String, List<String>> all = new HashMap<>(attributes);
		if (roleAttributeName != null && !roles.isEmpty()) {
			all.put(roleAttributeName, new ArrayList<>(roles));
		}
		if (email != null) {
			all.computeIfAbsent("email", k -> new ArrayList<>()).add(email);
		}
		if (displayName != null) {
			all.computeIfAbsent("displayName", k -> new ArrayList<>()).add(displayName);
		}
		return all;
	}

}

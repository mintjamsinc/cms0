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

package org.mintjams.saml2.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a SAML 2.0 Response containing assertions and user attributes.
 * This class supports multiple attributes with the same name, which is a
 * common requirement when receiving roles or groups from identity providers.
 */
public class Saml2Response {

	private String nameId;
	private String nameIdFormat;
	private String sessionIndex;
	private Map<String, List<String>> attributes;
	private boolean authenticated;
	private List<String> errors;

	public Saml2Response() {
		this.attributes = new HashMap<>();
		this.errors = new ArrayList<>();
		this.authenticated = false;
	}

	/**
	 * Gets the NameID from the SAML assertion.
	 *
	 * @return the NameID value
	 */
	public String getNameId() {
		return nameId;
	}

	public void setNameId(String nameId) {
		this.nameId = nameId;
	}

	/**
	 * Gets the NameID format.
	 *
	 * @return the NameID format
	 */
	public String getNameIdFormat() {
		return nameIdFormat;
	}

	public void setNameIdFormat(String nameIdFormat) {
		this.nameIdFormat = nameIdFormat;
	}

	/**
	 * Gets the session index.
	 *
	 * @return the session index
	 */
	public String getSessionIndex() {
		return sessionIndex;
	}

	public void setSessionIndex(String sessionIndex) {
		this.sessionIndex = sessionIndex;
	}

	/**
	 * Gets all attributes from the SAML assertion.
	 * This method properly handles multiple attributes with the same name.
	 *
	 * @return map of attribute names to lists of values
	 */
	public Map<String, List<String>> getAttributes() {
		return Collections.unmodifiableMap(attributes);
	}

	/**
	 * Gets values for a specific attribute name.
	 *
	 * @param name the attribute name
	 * @return list of attribute values, or null if not found
	 */
	public List<String> getAttribute(String name) {
		return attributes.get(name);
	}

	/**
	 * Gets the first value for a specific attribute name.
	 *
	 * @param name the attribute name
	 * @return the first attribute value, or null if not found
	 */
	public String getAttributeValue(String name) {
		List<String> values = attributes.get(name);
		return (values != null && !values.isEmpty()) ? values.get(0) : null;
	}

	/**
	 * Adds an attribute value. If the attribute name already exists,
	 * the value is added to the existing list.
	 *
	 * @param name the attribute name
	 * @param value the attribute value
	 */
	public void addAttribute(String name, String value) {
		attributes.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
	}

	/**
	 * Sets all values for an attribute, replacing any existing values.
	 *
	 * @param name the attribute name
	 * @param values the attribute values
	 */
	public void setAttribute(String name, List<String> values) {
		attributes.put(name, new ArrayList<>(values));
	}

	/**
	 * Checks if the user is authenticated.
	 *
	 * @return true if authenticated, false otherwise
	 */
	public boolean isAuthenticated() {
		return authenticated;
	}

	public void setAuthenticated(boolean authenticated) {
		this.authenticated = authenticated;
	}

	/**
	 * Gets the list of validation errors.
	 *
	 * @return list of error messages
	 */
	public List<String> getErrors() {
		return Collections.unmodifiableList(errors);
	}

	/**
	 * Adds a validation error.
	 *
	 * @param error the error message
	 */
	public void addError(String error) {
		this.errors.add(error);
		this.authenticated = false;
	}

	/**
	 * Checks if there are any validation errors.
	 *
	 * @return true if there are errors, false otherwise
	 */
	public boolean hasErrors() {
		return !errors.isEmpty();
	}

}

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

package org.mintjams.saml2;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.saml2.exception.Saml2Exception;
import org.mintjams.saml2.exception.ValidationException;
import org.mintjams.saml2.model.Saml2Response;
import org.mintjams.saml2.model.Saml2Settings;
import org.mintjams.tools.lang.Strings;

/**
 * Main SAML 2.0 authentication handler, compatible with OneLogin Auth
 * interface. This class provides support for multiple attributes with the same
 * name.
 */
public class Saml2Auth {

	private final Saml2Settings settings;
	private final HttpServletRequest request;
	private final HttpServletResponse response;

	private Saml2Response samlResponse;
	private ValidationException lastValidationException;
	private List<String> errors = new ArrayList<>();

	public Saml2Auth(Saml2Settings settings, HttpServletRequest request, HttpServletResponse response) {
		this.settings = settings;
		this.request = request;
		this.response = response;
	}

	/**
	 * Returns true if the request contains a non-empty SAMLResponse parameter.
	 */
	public boolean hasSAMLResponse(HttpServletRequest request) {
		if (request == null) {
			return false;
		}
		String samlResponse = request.getParameter("SAMLResponse");
		return Strings.isNotEmpty(samlResponse);
	}

	/**
	 * Initiates the SSO process by redirecting to the IdP.
	 *
	 * @param relayState optional relay state for return URL
	 * @throws IOException if redirect fails
	 */
	public void login(String relayState) throws IOException {
		login(relayState, false, false);
	}

	/**
	 * Initiates the SSO process with options.
	 *
	 * @param relayState optional relay state for return URL
	 * @param forceAuthn whether to force re-authentication
	 * @param isPassive  whether to use passive authentication
	 * @throws IOException if redirect fails
	 */
	public void login(String relayState, boolean forceAuthn, boolean isPassive) throws IOException {
		try {
			Saml2AuthnRequestBuilder builder = new Saml2AuthnRequestBuilder(settings).setForceAuthn(forceAuthn)
					.setIsPassive(isPassive);

			String samlRequest = builder.buildBase64Deflated();

			StringBuilder redirectUrl = new StringBuilder(settings.getIdpSingleSignOnServiceUrl());
			redirectUrl.append(settings.getIdpSingleSignOnServiceUrl().contains("?") ? "&" : "?");
			redirectUrl.append("SAMLRequest=").append(URLEncoder.encode(samlRequest, StandardCharsets.UTF_8.name()));

			if (relayState != null && !relayState.isEmpty()) {
				redirectUrl.append("&RelayState=").append(URLEncoder.encode(relayState, StandardCharsets.UTF_8.name()));
			}

			response.sendRedirect(redirectUrl.toString());

		} catch (Saml2Exception ex) {
			errors.add("Failed to initiate login: " + ex.getMessage());
			throw new IOException("Failed to initiate login", ex);
		}
	}

	/**
	 * Processes the SAML response from the IdP.
	 *
	 * @throws Saml2Exception if processing fails
	 */
	public void processResponse() throws Saml2Exception {
		String samlResponseParam = request.getParameter("SAMLResponse");
		if (samlResponseParam == null || samlResponseParam.isEmpty()) {
			throw new ValidationException("No SAMLResponse parameter found");
		}

		try {
			Saml2ResponseProcessor processor = new Saml2ResponseProcessor(settings);
			samlResponse = processor.processResponse(samlResponseParam);

			if (samlResponse.hasErrors()) {
				errors.addAll(samlResponse.getErrors());
				throw new ValidationException("SAML Response validation failed: " + String.join(", ", errors));
			}

		} catch (ValidationException ex) {
			lastValidationException = ex;
			throw ex;
		}
	}

	/**
	 * Initiates the SLO (Single Logout) process.
	 *
	 * @param relayState optional relay state for return URL
	 * @throws IOException if redirect fails
	 */
	public void logout(String relayState) throws IOException {
		if (samlResponse == null) {
			throw new IOException("Cannot logout: no authenticated session");
		}

		try {
			Saml2LogoutRequestBuilder builder = new Saml2LogoutRequestBuilder(settings)
					.setNameId(samlResponse.getNameId()).setNameIdFormat(samlResponse.getNameIdFormat())
					.setSessionIndex(samlResponse.getSessionIndex());

			String logoutRequest = builder.buildBase64Deflated();

			StringBuilder redirectUrl = new StringBuilder(settings.getIdpSingleLogoutServiceUrl());
			redirectUrl.append(settings.getIdpSingleLogoutServiceUrl().contains("?") ? "&" : "?");
			redirectUrl.append("SAMLRequest=").append(URLEncoder.encode(logoutRequest, StandardCharsets.UTF_8.name()));

			if (relayState != null && !relayState.isEmpty()) {
				redirectUrl.append("&RelayState=").append(URLEncoder.encode(relayState, StandardCharsets.UTF_8.name()));
			}

			response.sendRedirect(redirectUrl.toString());

		} catch (Saml2Exception ex) {
			errors.add("Failed to initiate logout: " + ex.getMessage());
			throw new IOException("Failed to initiate logout", ex);
		}
	}

	/**
	 * Processes SLO request or response. This is a simplified implementation for
	 * basic SLO support.
	 */
	public void processSLO() {
		// For now, just clear the session
		// A full implementation would parse LogoutRequest/Response
		request.getSession().invalidate();
	}

	/**
	 * Checks if the user is authenticated.
	 *
	 * @return true if authenticated, false otherwise
	 */
	public boolean isAuthenticated() {
		return samlResponse != null && samlResponse.isAuthenticated();
	}

	/**
	 * Gets the NameID from the assertion.
	 *
	 * @return the NameID
	 */
	public String getNameId() {
		return samlResponse != null ? samlResponse.getNameId() : null;
	}

	/**
	 * Gets all attributes from the assertion. IMPORTANT: This properly handles
	 * multiple attributes with the same name.
	 *
	 * @return map of attribute names to lists of values
	 */
	public Map<String, List<String>> getAttributes() {
		if (samlResponse != null) {
			return samlResponse.getAttributes();
		}
		return new HashMap<>();
	}

	/**
	 * Gets values for a specific attribute.
	 *
	 * @param name the attribute name
	 * @return list of attribute values
	 */
	public List<String> getAttribute(String name) {
		return samlResponse != null ? samlResponse.getAttribute(name) : null;
	}

	/**
	 * Gets the first value for a specific attribute.
	 *
	 * @param name the attribute name
	 * @return the first attribute value, or null if not found
	 */
	public String getAttributeValue(String name) {
		return samlResponse != null ? samlResponse.getAttributeValue(name) : null;
	}

	/**
	 * Gets the session index.
	 *
	 * @return the session index
	 */
	public String getSessionIndex() {
		return samlResponse != null ? samlResponse.getSessionIndex() : null;
	}

	/**
	 * Gets the last validation exception.
	 *
	 * @return the last validation exception, or null
	 */
	public ValidationException getLastValidationException() {
		return lastValidationException;
	}

	/**
	 * Gets the list of errors.
	 *
	 * @return list of error messages
	 */
	public List<String> getErrors() {
		return new ArrayList<>(errors);
	}

	/**
	 * Gets the SAML settings.
	 *
	 * @return the SAML settings
	 */
	public Saml2Settings getSettings() {
		return settings;
	}

}

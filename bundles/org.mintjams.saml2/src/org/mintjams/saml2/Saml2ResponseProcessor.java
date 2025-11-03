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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.mintjams.saml2.exception.Saml2Exception;
import org.mintjams.saml2.exception.ValidationException;
import org.mintjams.saml2.model.Saml2Response;
import org.mintjams.saml2.model.Saml2Settings;
import org.mintjams.saml2.util.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Processes SAML 2.0 responses with support for multiple attributes with the same name.
 * This is critical for handling roles and groups from identity providers like Keycloak.
 */
public class Saml2ResponseProcessor {

	private final Saml2Settings settings;

	public Saml2ResponseProcessor(Saml2Settings settings) {
		this.settings = settings;
	}

	/**
	 * Processes a Base64 encoded SAML response.
	 *
	 * @param base64Response the Base64 encoded SAML response
	 * @return the processed Saml2Response
	 * @throws Saml2Exception if processing fails
	 */
	public Saml2Response processResponse(String base64Response) throws Saml2Exception {
		Document document = XmlUtils.parseBase64Response(base64Response);
		return processResponseDocument(document);
	}

	/**
	 * Processes a SAML response document.
	 *
	 * @param document the SAML response document
	 * @return the processed Saml2Response
	 * @throws Saml2Exception if processing fails
	 */
	public Saml2Response processResponseDocument(Document document) throws Saml2Exception {
		Saml2Response response = new Saml2Response();

		try {
			Element root = document.getDocumentElement();

			// Validate it's a Response element
			if (!"Response".equals(root.getLocalName()) ||
				!XmlUtils.getProtocolNamespace().equals(root.getNamespaceURI())) {
				throw new ValidationException("Invalid SAML Response: root element is not samlp:Response");
			}

			// Get Status
			Element statusElement = XmlUtils.getFirstElement(root, XmlUtils.getProtocolNamespace(), "Status");
			if (statusElement == null) {
				throw new ValidationException("SAML Response missing Status element");
			}

			Element statusCodeElement = XmlUtils.getFirstElement(statusElement, XmlUtils.getProtocolNamespace(), "StatusCode");
			if (statusCodeElement == null) {
				throw new ValidationException("SAML Response missing StatusCode element");
			}

			String statusValue = XmlUtils.getAttribute(statusCodeElement, "Value");
			if (!"urn:oasis:names:tc:SAML:2.0:status:Success".equals(statusValue)) {
				String statusMessage = XmlUtils.getFirstElementText(statusElement, XmlUtils.getProtocolNamespace(), "StatusMessage");
				throw new ValidationException("SAML Response status is not Success: " + statusValue +
					(statusMessage != null ? " - " + statusMessage : ""));
			}

			// Get Assertion
			NodeList assertions = XmlUtils.getElements(root, XmlUtils.getAssertionNamespace(), "Assertion");
			if (assertions.getLength() == 0) {
				throw new ValidationException("SAML Response contains no assertions");
			}

			Element assertion = (Element) assertions.item(0);

			// Validate Assertion
			validateAssertion(assertion, response);

			// Process Subject
			processSubject(assertion, response);

			// Process Attributes - THIS IS THE KEY PART FOR MULTIPLE ATTRIBUTES
			processAttributes(assertion, response);

			// If we got here, authentication is successful
			response.setAuthenticated(true);

		} catch (ValidationException ex) {
			response.addError(ex.getMessage());
			throw ex;
		} catch (Exception ex) {
			response.addError("Failed to process SAML response: " + ex.getMessage());
			throw new Saml2Exception("Failed to process SAML response", ex);
		}

		return response;
	}

	/**
	 * Validates the assertion conditions, including time validity.
	 */
	private void validateAssertion(Element assertion, Saml2Response response) throws ValidationException {
		// Validate Conditions
		Element conditions = XmlUtils.getFirstElement(assertion, XmlUtils.getAssertionNamespace(), "Conditions");
		if (conditions != null) {
			String notBefore = XmlUtils.getAttribute(conditions, "NotBefore");
			String notOnOrAfter = XmlUtils.getAttribute(conditions, "NotOnOrAfter");

			Instant now = Instant.now();

			if (notBefore != null) {
				Instant nbInstant = Instant.parse(notBefore);
				if (now.isBefore(nbInstant)) {
					throw new ValidationException("Assertion is not yet valid (NotBefore: " + notBefore + ")");
				}
			}

			if (notOnOrAfter != null) {
				Instant noaInstant = Instant.parse(notOnOrAfter);
				if (now.isAfter(noaInstant) || now.equals(noaInstant)) {
					throw new ValidationException("Assertion has expired (NotOnOrAfter: " + notOnOrAfter + ")");
				}
			}

			// Validate Audience
			if (settings.isStrict()) {
				NodeList audienceRestrictions = XmlUtils.getElements(conditions, XmlUtils.getAssertionNamespace(), "AudienceRestriction");
				if (audienceRestrictions.getLength() > 0) {
					Element audienceRestriction = (Element) audienceRestrictions.item(0);
					NodeList audiences = XmlUtils.getElements(audienceRestriction, XmlUtils.getAssertionNamespace(), "Audience");

					boolean found = false;
					for (int i = 0; i < audiences.getLength(); i++) {
						String audience = audiences.item(i).getTextContent();
						if (settings.getSpEntityId().equals(audience)) {
							found = true;
							break;
						}
					}

					if (!found) {
						throw new ValidationException("Assertion audience does not match SP entity ID");
					}
				}
			}
		}
	}

	/**
	 * Processes the Subject element to extract NameID and SessionIndex.
	 */
	private void processSubject(Element assertion, Saml2Response response) throws ValidationException {
		Element subject = XmlUtils.getFirstElement(assertion, XmlUtils.getAssertionNamespace(), "Subject");
		if (subject == null) {
			throw new ValidationException("Assertion missing Subject element");
		}

		Element nameId = XmlUtils.getFirstElement(subject, XmlUtils.getAssertionNamespace(), "NameID");
		if (nameId == null) {
			throw new ValidationException("Subject missing NameID element");
		}

		response.setNameId(nameId.getTextContent());
		response.setNameIdFormat(XmlUtils.getAttribute(nameId, "Format"));

		// Get SessionIndex from AuthnStatement
		NodeList authnStatements = XmlUtils.getElements(assertion, XmlUtils.getAssertionNamespace(), "AuthnStatement");
		if (authnStatements.getLength() > 0) {
			Element authnStatement = (Element) authnStatements.item(0);
			String sessionIndex = XmlUtils.getAttribute(authnStatement, "SessionIndex");
			response.setSessionIndex(sessionIndex);
		}
	}

	/**
	 * Processes attribute statements with support for multiple attributes with the same name.
	 * This is the critical fix for the Keycloak role/group issue.
	 */
	private void processAttributes(Element assertion, Saml2Response response) {
		NodeList attributeStatements = XmlUtils.getElements(assertion, XmlUtils.getAssertionNamespace(), "AttributeStatement");

		for (int i = 0; i < attributeStatements.getLength(); i++) {
			Element attributeStatement = (Element) attributeStatements.item(i);
			NodeList attributes = XmlUtils.getElements(attributeStatement, XmlUtils.getAssertionNamespace(), "Attribute");

			for (int j = 0; j < attributes.getLength(); j++) {
				Element attribute = (Element) attributes.item(j);
				String attributeName = XmlUtils.getAttribute(attribute, "Name");

				// Get all AttributeValue elements for this Attribute
				NodeList attributeValues = XmlUtils.getElements(attribute, XmlUtils.getAssertionNamespace(), "AttributeValue");

				// Add each value - this supports multiple values within a single Attribute element
				for (int k = 0; k < attributeValues.getLength(); k++) {
					String value = attributeValues.item(k).getTextContent();
					if (value != null && !value.trim().isEmpty()) {
						response.addAttribute(attributeName, value.trim());
					}
				}
			}
		}

		// IMPORTANT: This implementation also naturally handles multiple Attribute elements
		// with the same name, because addAttribute() appends to the list rather than replacing it.
		// This is the key difference from OneLogin's implementation.
	}

}

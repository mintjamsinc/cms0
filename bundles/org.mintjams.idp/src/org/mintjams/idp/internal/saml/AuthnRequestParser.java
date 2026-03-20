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

package org.mintjams.idp.internal.saml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.mintjams.idp.internal.model.AuthnRequest;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses SAML 2.0 AuthnRequest messages received from Service Providers.
 * Supports both HTTP-Redirect (Base64 + Deflate) and HTTP-POST (Base64) bindings.
 */
public class AuthnRequestParser {

	private static final String SAML2_PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
	private static final String SAML2_ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";

	/**
	 * Parses an AuthnRequest from HTTP-Redirect binding (Base64 + Deflated).
	 *
	 * @param samlRequest the SAMLRequest parameter value
	 * @param relayState the RelayState parameter value (may be null)
	 * @return the parsed AuthnRequest
	 * @throws Exception if parsing fails
	 */
	public AuthnRequest parseRedirectBinding(String samlRequest, String relayState) throws Exception {
		byte[] decoded = Base64.getDecoder().decode(samlRequest);

		// Inflate
		ByteArrayInputStream bais = new ByteArrayInputStream(decoded);
		InflaterInputStream iis = new InflaterInputStream(bais, new Inflater(true));
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		int length;
		while ((length = iis.read(buffer)) > 0) {
			baos.write(buffer, 0, length);
		}

		Document document = parseXml(baos.toByteArray());
		return parseDocument(document, relayState);
	}

	/**
	 * Parses an AuthnRequest from HTTP-POST binding (Base64).
	 *
	 * @param samlRequest the SAMLRequest parameter value
	 * @param relayState the RelayState parameter value (may be null)
	 * @return the parsed AuthnRequest
	 * @throws Exception if parsing fails
	 */
	public AuthnRequest parsePostBinding(String samlRequest, String relayState) throws Exception {
		byte[] decoded = Base64.getDecoder().decode(samlRequest);
		Document document = parseXml(decoded);
		return parseDocument(document, relayState);
	}

	private Document parseXml(byte[] xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setExpandEntityReferences(false);
		DocumentBuilder builder = factory.newDocumentBuilder();
		return builder.parse(new ByteArrayInputStream(xml));
	}

	private AuthnRequest parseDocument(Document document, String relayState) throws Exception {
		Element root = document.getDocumentElement();

		// Validate root element
		if (!"AuthnRequest".equals(root.getLocalName()) ||
				!SAML2_PROTOCOL_NS.equals(root.getNamespaceURI())) {
			throw new IllegalArgumentException("Invalid AuthnRequest: root element is not samlp:AuthnRequest");
		}

		AuthnRequest request = new AuthnRequest();

		// ID
		request.setId(root.getAttribute("ID"));

		// Destination
		if (root.hasAttribute("Destination")) {
			request.setDestination(root.getAttribute("Destination"));
		}

		// AssertionConsumerServiceURL
		if (root.hasAttribute("AssertionConsumerServiceURL")) {
			request.setAssertionConsumerServiceUrl(root.getAttribute("AssertionConsumerServiceURL"));
		}

		// ForceAuthn
		if (root.hasAttribute("ForceAuthn")) {
			request.setForceAuthn("true".equalsIgnoreCase(root.getAttribute("ForceAuthn")));
		}

		// IsPassive
		if (root.hasAttribute("IsPassive")) {
			request.setPassive("true".equalsIgnoreCase(root.getAttribute("IsPassive")));
		}

		// Issuer
		NodeList issuers = root.getElementsByTagNameNS(SAML2_ASSERTION_NS, "Issuer");
		if (issuers.getLength() > 0) {
			request.setIssuer(issuers.item(0).getTextContent());
		}

		// RelayState
		request.setRelayState(relayState);

		return request;
	}

}

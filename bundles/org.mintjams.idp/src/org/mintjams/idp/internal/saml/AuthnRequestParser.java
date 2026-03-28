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
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.servlet.http.HttpServletRequest;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
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

	/**
	 * Verifies the XML signature of a Redirect binding AuthnRequest.
	 * The signature covers the URL query string parameters, not the XML itself.
	 *
	 * @param request the HTTP request containing SAMLRequest, SigAlg, and Signature parameters
	 * @param spCertificate the SP signing certificate to verify against
	 * @throws SecurityException if verification fails or signature is missing
	 * @throws Exception if an error occurs during verification
	 */
	public void verifyRedirectSignature(HttpServletRequest request, X509Certificate spCertificate) throws Exception {
		String sigAlg = request.getParameter("SigAlg");
		String signatureParam = request.getParameter("Signature");

		if (sigAlg == null || signatureParam == null) {
			throw new SecurityException("Signed AuthnRequest required: SigAlg and Signature parameters are missing");
		}

		// Reconstruct the signed query string using raw (URL-encoded) parameter values
		// to match what the SP signed. The order must be: SAMLRequest[&RelayState]&SigAlg
		String queryString = request.getQueryString();
		String samlRequestRaw = getRawQueryParam(queryString, "SAMLRequest");
		String relayStateRaw = getRawQueryParam(queryString, "RelayState");
		String sigAlgRaw = getRawQueryParam(queryString, "SigAlg");

		StringBuilder signedString = new StringBuilder();
		signedString.append("SAMLRequest=").append(samlRequestRaw);
		if (relayStateRaw != null) {
			signedString.append("&RelayState=").append(relayStateRaw);
		}
		signedString.append("&SigAlg=").append(sigAlgRaw);

		byte[] signedBytes = signedString.toString().getBytes(StandardCharsets.UTF_8);
		byte[] signatureBytes = Base64.getDecoder().decode(signatureParam);

		String jcaAlgorithm = sigAlgUriToJca(sigAlg);
		java.security.Signature sig = java.security.Signature.getInstance(jcaAlgorithm);
		sig.initVerify(spCertificate.getPublicKey());
		sig.update(signedBytes);
		if (!sig.verify(signatureBytes)) {
			throw new SecurityException("AuthnRequest redirect binding signature verification failed");
		}
	}

	/**
	 * Verifies the XML signature of a POST binding AuthnRequest.
	 * The signature is embedded as a ds:Signature element within the AuthnRequest XML.
	 *
	 * @param base64SamlRequest the Base64-encoded SAMLRequest parameter value
	 * @param spCertificate the SP signing certificate to verify against
	 * @throws SecurityException if verification fails or signature is missing
	 * @throws Exception if an error occurs during verification
	 */
	public void verifyPostSignature(String base64SamlRequest, X509Certificate spCertificate) throws Exception {
		byte[] decoded = Base64.getDecoder().decode(base64SamlRequest);
		Document document = parseXml(decoded);

		NodeList signatureNodes = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
		if (signatureNodes.getLength() == 0) {
			throw new SecurityException("Signed AuthnRequest required: no XML signature found in POST binding request");
		}

		// Mark the ID attribute so the signature reference can resolve it
		document.getDocumentElement().setIdAttribute("ID", true);

		XMLSignatureFactory sigFactory = XMLSignatureFactory.getInstance("DOM");
		DOMValidateContext validateContext = new DOMValidateContext(
				spCertificate.getPublicKey(), signatureNodes.item(0));
		XMLSignature xmlSignature = sigFactory.unmarshalXMLSignature(validateContext);

		if (!xmlSignature.validate(validateContext)) {
			throw new SecurityException("AuthnRequest POST binding signature verification failed");
		}
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

	/**
	 * Extracts the raw (URL-encoded) value of a query parameter from the query string.
	 * Uses the raw query string to preserve the exact encoding used by the SP when signing.
	 */
	private static String getRawQueryParam(String queryString, String name) {
		if (queryString == null) {
			return null;
		}
		String prefix = name + "=";
		for (String part : queryString.split("&")) {
			if (part.startsWith(prefix)) {
				return part.substring(prefix.length());
			}
		}
		return null;
	}

	/**
	 * Maps a SAML SigAlg URI to the corresponding JCA algorithm name.
	 */
	private static String sigAlgUriToJca(String sigAlgUri) {
		switch (sigAlgUri) {
			case "http://www.w3.org/2000/09/xmldsig#rsa-sha1":
				return "SHA1withRSA";
			case "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256":
				return "SHA256withRSA";
			case "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512":
				return "SHA512withRSA";
			case "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256":
				return "SHA256withECDSA";
			default:
				throw new IllegalArgumentException("Unsupported SigAlg URI: " + sigAlgUri);
		}
	}

}

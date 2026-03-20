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

import java.io.StringWriter;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.IdpConfiguration;
import org.mintjams.idp.internal.model.AuthnRequest;
import org.mintjams.idp.internal.model.IdpUser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Builds signed SAML 2.0 Response messages.
 *
 * <p>This is the IdP counterpart to the SP's {@code Saml2ResponseProcessor}.
 * It builds the Response/Assertion XML using Java standard DOM API and
 * signs it using {@code javax.xml.crypto.dsig}.</p>
 */
public class SamlResponseBuilder {

	private static final String SAML2_PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
	private static final String SAML2_ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
	private static final String STATUS_SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";
	private static final String NAMEID_FORMAT_UNSPECIFIED = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified";
	private static final String AUTHN_CONTEXT_PASSWORD = "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport";
	private static final String CM_BEARER = "urn:oasis:names:tc:SAML:2.0:cm:bearer";

	private final IdpConfiguration config;

	public SamlResponseBuilder(IdpConfiguration config) {
		this.config = config;
	}

	/**
	 * Builds a signed SAML Response for the given AuthnRequest and authenticated user.
	 *
	 * @param authnRequest the original AuthnRequest from the SP
	 * @param user the authenticated user
	 * @return Base64 encoded SAML Response
	 * @throws Exception if building or signing fails
	 */
	public String buildBase64Response(AuthnRequest authnRequest, IdpUser user)
			throws Exception {
		Document assertionDoc = buildAssertionDocument(authnRequest, user);
		signAssertion(assertionDoc);
		Document responseDoc = buildResponseEnvelope(authnRequest);
		Element signedAssertion = assertionDoc.getDocumentElement();
		org.w3c.dom.Node imported = responseDoc.importNode(signedAssertion, true);
		responseDoc.getDocumentElement().appendChild(imported);

		String xml = documentToString(responseDoc);
		return Base64.getEncoder().encodeToString(xml.getBytes("UTF-8"));
	}

	/**
	 * Builds the Response element with Issuer and Status, but without the Assertion.
	 * The Assertion will be created and signed separately, then imported into this Response.
	 *
	 * @param authnRequest the original AuthnRequest (for Destination and InResponseTo)
	 * @return Document containing the Response element
	 * @throws Exception if building fails
	 */
	private Document buildResponseEnvelope(AuthnRequest authnRequest) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document document = builder.newDocument();

		Instant now = Instant.now();
		String responseId = "_" + UUID.randomUUID().toString();

		Element response = document.createElementNS(SAML2_PROTOCOL_NS, "samlp:Response");
		response.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:saml", SAML2_ASSERTION_NS);
		response.setAttribute("ID", responseId);
		response.setAttribute("Version", "2.0");
		response.setAttribute("IssueInstant", now.toString());
		response.setAttribute("Destination", authnRequest.getAssertionConsumerServiceUrl());
		if (authnRequest.getId() != null) {
			response.setAttribute("InResponseTo", authnRequest.getId());
		}
		document.appendChild(response);

		// Issuer
		Element responseIssuer = document.createElementNS(SAML2_ASSERTION_NS, "saml:Issuer");
		responseIssuer.setTextContent(config.getEntityId());
		response.appendChild(responseIssuer);

		// Status
		Element status = document.createElementNS(SAML2_PROTOCOL_NS, "samlp:Status");
		Element statusCode = document.createElementNS(SAML2_PROTOCOL_NS, "samlp:StatusCode");
		statusCode.setAttribute("Value", STATUS_SUCCESS);
		status.appendChild(statusCode);
		response.appendChild(status);

		return document;
	}

	/**
	 * Builds the Assertion element with Subject, Conditions, AuthnStatement, and AttributeStatement.
	 *
	 * @param authnRequest the original AuthnRequest (for Audience and InResponseTo)
	 * @param user the authenticated user (for NameID and attributes)
	 * @return Document containing the Assertion element
	 * @throws Exception if building fails
	 */
	private Document buildAssertionDocument(AuthnRequest authnRequest, IdpUser user)
			throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document document = builder.newDocument();

		Instant now = Instant.now();
		Instant notOnOrAfter = now.plusSeconds(config.getAssertionValiditySeconds());
		String assertionId = "_" + UUID.randomUUID().toString();
		String sessionIndex = "_" + UUID.randomUUID().toString();

		// Assertion（このDocumentのルート要素として作成）
		Element assertion = document.createElementNS(SAML2_ASSERTION_NS, "saml:Assertion");
		assertion.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:saml", SAML2_ASSERTION_NS);
		assertion.setAttribute("ID", assertionId);
		assertion.setAttribute("Version", "2.0");
		assertion.setAttribute("IssueInstant", now.toString());
		document.appendChild(assertion);

		// Issuer
		Element assertionIssuer = document.createElementNS(SAML2_ASSERTION_NS, "saml:Issuer");
		assertionIssuer.setTextContent(config.getEntityId());
		assertion.appendChild(assertionIssuer);

		// Subject
		Element subject = document.createElementNS(SAML2_ASSERTION_NS, "saml:Subject");
		assertion.appendChild(subject);

		Element nameId = document.createElementNS(SAML2_ASSERTION_NS, "saml:NameID");
		nameId.setAttribute("Format", NAMEID_FORMAT_UNSPECIFIED);
		nameId.setTextContent(user.getUsername());
		subject.appendChild(nameId);

		Element subjectConfirmation = document.createElementNS(SAML2_ASSERTION_NS, "saml:SubjectConfirmation");
		subjectConfirmation.setAttribute("Method", CM_BEARER);
		subject.appendChild(subjectConfirmation);

		Element subjectConfirmationData = document.createElementNS(SAML2_ASSERTION_NS, "saml:SubjectConfirmationData");
		if (authnRequest.getId() != null) {
			subjectConfirmationData.setAttribute("InResponseTo", authnRequest.getId());
		}
		subjectConfirmationData.setAttribute("NotOnOrAfter", notOnOrAfter.toString());
		subjectConfirmationData.setAttribute("Recipient", authnRequest.getAssertionConsumerServiceUrl());
		subjectConfirmation.appendChild(subjectConfirmationData);

		// Conditions
		Element conditions = document.createElementNS(SAML2_ASSERTION_NS, "saml:Conditions");
		conditions.setAttribute("NotBefore", now.toString());
		conditions.setAttribute("NotOnOrAfter", notOnOrAfter.toString());
		assertion.appendChild(conditions);

		Element audienceRestriction = document.createElementNS(SAML2_ASSERTION_NS, "saml:AudienceRestriction");
		conditions.appendChild(audienceRestriction);

		Element audience = document.createElementNS(SAML2_ASSERTION_NS, "saml:Audience");
		audience.setTextContent(authnRequest.getIssuer() != null ? authnRequest.getIssuer() : "");
		audienceRestriction.appendChild(audience);

		// AuthnStatement
		Element authnStatement = document.createElementNS(SAML2_ASSERTION_NS, "saml:AuthnStatement");
		authnStatement.setAttribute("AuthnInstant", now.toString());
		authnStatement.setAttribute("SessionIndex", sessionIndex);
		assertion.appendChild(authnStatement);

		Element authnContext = document.createElementNS(SAML2_ASSERTION_NS, "saml:AuthnContext");
		authnStatement.appendChild(authnContext);

		Element authnContextClassRef = document.createElementNS(SAML2_ASSERTION_NS, "saml:AuthnContextClassRef");
		authnContextClassRef.setTextContent(AUTHN_CONTEXT_PASSWORD);
		authnContext.appendChild(authnContextClassRef);

		// AttributeStatement
		Map<String, List<String>> attributes = user.getAllAttributes();
		if (!attributes.isEmpty()) {
			Element attributeStatement = document.createElementNS(SAML2_ASSERTION_NS, "saml:AttributeStatement");
			assertion.appendChild(attributeStatement);

			for (Map.Entry<String, List<String>> entry : attributes.entrySet()) {
				Element attribute = document.createElementNS(SAML2_ASSERTION_NS, "saml:Attribute");
				attribute.setAttribute("Name", entry.getKey());
				attribute.setAttribute("NameFormat", "urn:oasis:names:tc:SAML:2.0:attrname-format:basic");
				attributeStatement.appendChild(attribute);

				for (String value : entry.getValue()) {
					Element attributeValue = document.createElementNS(SAML2_ASSERTION_NS, "saml:AttributeValue");
					attributeValue.setTextContent(value);
					attribute.appendChild(attributeValue);
				}
			}
		}

		return document;
	}

	/**
	 * Signs the Assertion element within the Response document using javax.xml.crypto.dsig.
	 * The signature is inserted as the second child of the Assertion element
	 * (after Issuer, before Subject), per the SAML specification.
	 */
	private void signAssertion(Document document) throws Exception {
		PrivateKey privateKey = Activator.getDefault().getKeyStoreManager().getPrivateKey();
		X509Certificate certificate = Activator.getDefault().getKeyStoreManager().getCertificate();

		// Find the Assertion element
		Element assertion = (Element) document.getElementsByTagNameNS(SAML2_ASSERTION_NS, "Assertion").item(0);
		String assertionId = assertion.getAttribute("ID");

		// Mark the ID attribute so the signature reference can find it
		assertion.setIdAttribute("ID", true);

		// Create XMLSignatureFactory
		XMLSignatureFactory sigFactory = XMLSignatureFactory.getInstance("DOM");

		// Create Reference to the Assertion (using its ID)
		List<Transform> transforms = new ArrayList<>();
		transforms.add(sigFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null));
		transforms.add(sigFactory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null));

		Reference reference = sigFactory.newReference(
				"#" + assertionId,
				sigFactory.newDigestMethod(DigestMethod.SHA256, null),
				transforms,
				null,
				null);

		// Create SignedInfo
		SignedInfo signedInfo = sigFactory.newSignedInfo(
				sigFactory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
				sigFactory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
				Collections.singletonList(reference));

		// Create KeyInfo with X509Data
		KeyInfoFactory keyInfoFactory = sigFactory.getKeyInfoFactory();
		List<Object> x509Content = new ArrayList<>();
		x509Content.add(certificate);
		X509Data x509Data = keyInfoFactory.newX509Data(x509Content);
		KeyInfo keyInfo = keyInfoFactory.newKeyInfo(Collections.singletonList(x509Data));

		// Create the XMLSignature
		XMLSignature signature = sigFactory.newXMLSignature(signedInfo, keyInfo);

		// Sign - insert signature after the Issuer element (second child of Assertion)
		Element issuerElement = (Element) assertion.getElementsByTagNameNS(SAML2_ASSERTION_NS, "Issuer").item(0);
		DOMSignContext signContext = new DOMSignContext(privateKey, assertion, issuerElement.getNextSibling());
		signContext.setDefaultNamespacePrefix("ds");

		signature.sign(signContext);
	}

	/**
	 * Builds the HTML auto-submit form for HTTP-POST binding.
	 *
	 * @param acsUrl the SP's Assertion Consumer Service URL
	 * @param base64Response the Base64 encoded SAML Response
	 * @param relayState the RelayState (may be null)
	 * @return HTML string with auto-submit form
	 */
	public String buildPostForm(String acsUrl, String base64Response, String relayState) {
		StringBuilder html = new StringBuilder();
		html.append("<!DOCTYPE html>\n");
		html.append("<html><head><meta charset=\"UTF-8\"></head>\n");
		html.append("<body onload=\"document.forms[0].submit();\">\n");
		html.append("<noscript><p>JavaScript is disabled. Click the button to continue.</p></noscript>\n");
		html.append("<form method=\"POST\" action=\"").append(escapeHtml(acsUrl)).append("\">\n");
		html.append("<input type=\"hidden\" name=\"SAMLResponse\" value=\"")
				.append(escapeHtml(base64Response)).append("\"/>\n");
		if (relayState != null && !relayState.isEmpty()) {
			html.append("<input type=\"hidden\" name=\"RelayState\" value=\"")
					.append(escapeHtml(relayState)).append("\"/>\n");
		}
		html.append("<noscript><button type=\"submit\">Continue</button></noscript>\n");
		html.append("</form>\n");
		html.append("</body></html>");
		return html.toString();
	}

	private String documentToString(Document document) throws Exception {
		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer transformer = tf.newTransformer();
		transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
		transformer.setOutputProperty(OutputKeys.METHOD, "xml");
		transformer.setOutputProperty(OutputKeys.INDENT, "no");
		transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		StringWriter writer = new StringWriter();
		transformer.transform(new DOMSource(document), new StreamResult(writer));
		return writer.toString();
	}

	private static String escapeHtml(String s) {
		if (s == null) return "";
		return s.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}

}

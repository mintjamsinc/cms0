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

package org.mintjams.idp.saml;

import java.io.StringWriter;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.mintjams.idp.model.IdpSettings;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Builds SAML 2.0 IdP Metadata XML.
 *
 * <p>The generated metadata document tells Service Providers:
 * <ul>
 *   <li>The IdP's entity ID</li>
 *   <li>The SSO endpoint URL and binding</li>
 *   <li>The signing certificate</li>
 * </ul>
 *
 * <p>SP administrators can point their SAML configuration at the
 * {@code /idp/metadata} endpoint to auto-configure trust.</p>
 */
public class MetadataBuilder {

	private static final String MD_NS = "urn:oasis:names:tc:SAML:2.0:metadata";
	private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";
	private static final String BINDING_HTTP_REDIRECT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect";
	private static final String BINDING_HTTP_POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";
	private static final String NAMEID_FORMAT_UNSPECIFIED = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified";
	private static final String USE_SIGNING = "signing";

	private final IdpSettings settings;

	public MetadataBuilder(IdpSettings settings) {
		this.settings = settings;
	}

	/**
	 * Builds the IdP metadata XML string.
	 *
	 * @return the metadata XML
	 * @throws Exception if building fails
	 */
	public String build() throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document document = builder.newDocument();

		// EntityDescriptor
		Element entityDescriptor = document.createElementNS(MD_NS, "md:EntityDescriptor");
		entityDescriptor.setAttribute("xmlns:ds", DS_NS);
		entityDescriptor.setAttribute("entityID", settings.getEntityId());
		document.appendChild(entityDescriptor);

		// IDPSSODescriptor
		Element idpSsoDescriptor = document.createElementNS(MD_NS, "md:IDPSSODescriptor");
		idpSsoDescriptor.setAttribute("WantAuthnRequestsSigned", "false");
		idpSsoDescriptor.setAttribute("protocolSupportEnumeration", "urn:oasis:names:tc:SAML:2.0:protocol");
		entityDescriptor.appendChild(idpSsoDescriptor);

		// KeyDescriptor (signing)
		X509Certificate cert = settings.getCertificate();
		if (cert != null) {
			Element keyDescriptor = document.createElementNS(MD_NS, "md:KeyDescriptor");
			keyDescriptor.setAttribute("use", USE_SIGNING);
			idpSsoDescriptor.appendChild(keyDescriptor);

			Element keyInfo = document.createElementNS(DS_NS, "ds:KeyInfo");
			keyDescriptor.appendChild(keyInfo);

			Element x509Data = document.createElementNS(DS_NS, "ds:X509Data");
			keyInfo.appendChild(x509Data);

			Element x509Certificate = document.createElementNS(DS_NS, "ds:X509Certificate");
			x509Certificate.setTextContent(Base64.getEncoder().encodeToString(cert.getEncoded()));
			x509Data.appendChild(x509Certificate);
		}

		// NameIDFormat
		Element nameIdFormat = document.createElementNS(MD_NS, "md:NameIDFormat");
		nameIdFormat.setTextContent(NAMEID_FORMAT_UNSPECIFIED);
		idpSsoDescriptor.appendChild(nameIdFormat);

		// SingleSignOnService (HTTP-Redirect)
		Element ssoRedirect = document.createElementNS(MD_NS, "md:SingleSignOnService");
		ssoRedirect.setAttribute("Binding", BINDING_HTTP_REDIRECT);
		ssoRedirect.setAttribute("Location", settings.getSsoUrl());
		idpSsoDescriptor.appendChild(ssoRedirect);

		// SingleSignOnService (HTTP-POST)
		Element ssoPost = document.createElementNS(MD_NS, "md:SingleSignOnService");
		ssoPost.setAttribute("Binding", BINDING_HTTP_POST);
		ssoPost.setAttribute("Location", settings.getSsoUrl());
		idpSsoDescriptor.appendChild(ssoPost);

		// SingleLogoutService (for future use)
		Element sloRedirect = document.createElementNS(MD_NS, "md:SingleLogoutService");
		sloRedirect.setAttribute("Binding", BINDING_HTTP_REDIRECT);
		sloRedirect.setAttribute("Location", settings.getSloUrl());
		idpSsoDescriptor.appendChild(sloRedirect);

		return documentToString(document);
	}

	private String documentToString(Document document) throws Exception {
		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer transformer = tf.newTransformer();
		transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
		transformer.setOutputProperty(OutputKeys.METHOD, "xml");
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
		StringWriter writer = new StringWriter();
		transformer.transform(new DOMSource(document), new StreamResult(writer));
		return writer.toString();
	}

}

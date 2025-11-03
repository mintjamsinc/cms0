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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import javax.xml.parsers.DocumentBuilder;

import org.mintjams.saml2.exception.Saml2Exception;
import org.mintjams.saml2.model.Saml2Settings;
import org.mintjams.saml2.util.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Builds SAML 2.0 AuthnRequest messages.
 */
public class Saml2AuthnRequestBuilder {

	private final Saml2Settings settings;
	private boolean forceAuthn = false;
	private boolean isPassive = false;
	private String protocolBinding = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

	public Saml2AuthnRequestBuilder(Saml2Settings settings) {
		this.settings = settings;
	}

	/**
	 * Sets whether to force re-authentication.
	 *
	 * @param forceAuthn true to force re-authentication
	 * @return this builder
	 */
	public Saml2AuthnRequestBuilder setForceAuthn(boolean forceAuthn) {
		this.forceAuthn = forceAuthn;
		return this;
	}

	/**
	 * Sets whether the request is passive.
	 *
	 * @param isPassive true for passive authentication
	 * @return this builder
	 */
	public Saml2AuthnRequestBuilder setIsPassive(boolean isPassive) {
		this.isPassive = isPassive;
		return this;
	}

	/**
	 * Sets the protocol binding.
	 *
	 * @param protocolBinding the protocol binding URI
	 * @return this builder
	 */
	public Saml2AuthnRequestBuilder setProtocolBinding(String protocolBinding) {
		this.protocolBinding = protocolBinding;
		return this;
	}

	/**
	 * Builds the AuthnRequest as an XML document.
	 *
	 * @return the AuthnRequest document
	 * @throws Saml2Exception if building fails
	 */
	public Document buildDocument() throws Saml2Exception {
		try {
			DocumentBuilder builder = XmlUtils.createSecureDocumentBuilder();
			Document document = builder.newDocument();

			Element authnRequest = document.createElementNS(XmlUtils.getProtocolNamespace(), "samlp:AuthnRequest");
			document.appendChild(authnRequest);

			// Set attributes
			String requestId = "_" + UUID.randomUUID().toString();
			authnRequest.setAttribute("ID", requestId);
			authnRequest.setAttribute("Version", "2.0");
			authnRequest.setAttribute("IssueInstant", Instant.now().toString());
			authnRequest.setAttribute("Destination", settings.getIdpSingleSignOnServiceUrl());
			authnRequest.setAttribute("ProtocolBinding", protocolBinding);
			authnRequest.setAttribute("AssertionConsumerServiceURL", settings.getSpAssertionConsumerServiceUrl());

			if (forceAuthn) {
				authnRequest.setAttribute("ForceAuthn", "true");
			}
			if (isPassive) {
				authnRequest.setAttribute("IsPassive", "true");
			}

			// Add Issuer
			Element issuer = document.createElementNS(XmlUtils.getAssertionNamespace(), "saml:Issuer");
			issuer.setTextContent(settings.getSpEntityId());
			authnRequest.appendChild(issuer);

			// Add NameIDPolicy
			Element nameIdPolicy = document.createElementNS(XmlUtils.getProtocolNamespace(), "samlp:NameIDPolicy");
			nameIdPolicy.setAttribute("Format", "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified");
			nameIdPolicy.setAttribute("AllowCreate", "true");
			authnRequest.appendChild(nameIdPolicy);

			return document;

		} catch (Exception ex) {
			throw new Saml2Exception("Failed to build AuthnRequest", ex);
		}
	}

	/**
	 * Builds the AuthnRequest as a Base64 encoded string.
	 *
	 * @return the Base64 encoded AuthnRequest
	 * @throws Saml2Exception if building fails
	 */
	public String buildBase64() throws Saml2Exception {
		Document document = buildDocument();
		String xml = XmlUtils.documentToString(document);
		return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Builds the AuthnRequest as a Base64 encoded and deflated string (for HTTP-Redirect binding).
	 *
	 * @return the Base64 encoded and deflated AuthnRequest
	 * @throws Saml2Exception if building fails
	 */
	public String buildBase64Deflated() throws Saml2Exception {
		try {
			Document document = buildDocument();
			String xml = XmlUtils.documentToString(document);

			// Deflate
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			Deflater deflater = new Deflater(Deflater.DEFLATED, true);
			DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater);
			dos.write(xml.getBytes(StandardCharsets.UTF_8));
			dos.finish();
			dos.close();

			// Base64 encode
			return Base64.getEncoder().encodeToString(baos.toByteArray());

		} catch (Exception ex) {
			throw new Saml2Exception("Failed to build deflated AuthnRequest", ex);
		}
	}

}

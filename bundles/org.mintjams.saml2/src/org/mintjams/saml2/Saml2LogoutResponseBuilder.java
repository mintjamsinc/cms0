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
 * Builds SAML 2.0 LogoutResponse messages.
 */
public class Saml2LogoutResponseBuilder {

	private final Saml2Settings settings;
	private String inResponseTo;
	private String statusCode = "urn:oasis:names:tc:SAML:2.0:status:Success";
	private String statusMessage;

	public Saml2LogoutResponseBuilder(Saml2Settings settings) {
		this.settings = settings;
	}

	/**
	 * Sets the ID of the request this is responding to.
	 *
	 * @param inResponseTo the request ID
	 * @return this builder
	 */
	public Saml2LogoutResponseBuilder setInResponseTo(String inResponseTo) {
		this.inResponseTo = inResponseTo;
		return this;
	}

	/**
	 * Sets the status code.
	 *
	 * @param statusCode the status code URI
	 * @return this builder
	 */
	public Saml2LogoutResponseBuilder setStatusCode(String statusCode) {
		this.statusCode = statusCode;
		return this;
	}

	/**
	 * Sets the status message.
	 *
	 * @param statusMessage the status message
	 * @return this builder
	 */
	public Saml2LogoutResponseBuilder setStatusMessage(String statusMessage) {
		this.statusMessage = statusMessage;
		return this;
	}

	/**
	 * Builds the LogoutResponse as an XML document.
	 *
	 * @return the LogoutResponse document
	 * @throws Saml2Exception if building fails
	 */
	public Document buildDocument() throws Saml2Exception {
		try {
			DocumentBuilder builder = XmlUtils.createSecureDocumentBuilder();
			Document document = builder.newDocument();

			Element logoutResponse = document.createElementNS(XmlUtils.getProtocolNamespace(), "samlp:LogoutResponse");
			document.appendChild(logoutResponse);

			// Set attributes
			String responseId = "_" + UUID.randomUUID().toString();
			logoutResponse.setAttribute("ID", responseId);
			logoutResponse.setAttribute("Version", "2.0");
			logoutResponse.setAttribute("IssueInstant", Instant.now().toString());
			logoutResponse.setAttribute("Destination", settings.getIdpSingleLogoutServiceUrl());

			if (inResponseTo != null) {
				logoutResponse.setAttribute("InResponseTo", inResponseTo);
			}

			// Add Issuer
			Element issuer = document.createElementNS(XmlUtils.getAssertionNamespace(), "saml:Issuer");
			issuer.setTextContent(settings.getSpEntityId());
			logoutResponse.appendChild(issuer);

			// Add Status
			Element status = document.createElementNS(XmlUtils.getProtocolNamespace(), "samlp:Status");
			logoutResponse.appendChild(status);

			Element statusCodeElement = document.createElementNS(XmlUtils.getProtocolNamespace(), "samlp:StatusCode");
			statusCodeElement.setAttribute("Value", statusCode);
			status.appendChild(statusCodeElement);

			if (statusMessage != null) {
				Element statusMessageElement = document.createElementNS(XmlUtils.getProtocolNamespace(), "samlp:StatusMessage");
				statusMessageElement.setTextContent(statusMessage);
				status.appendChild(statusMessageElement);
			}

			return document;

		} catch (Exception ex) {
			throw new Saml2Exception("Failed to build LogoutResponse", ex);
		}
	}

	/**
	 * Builds the LogoutResponse as a Base64 encoded string.
	 *
	 * @return the Base64 encoded LogoutResponse
	 * @throws Saml2Exception if building fails
	 */
	public String buildBase64() throws Saml2Exception {
		Document document = buildDocument();
		String xml = XmlUtils.documentToString(document);
		return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Builds the LogoutResponse as a Base64 encoded and deflated string (for HTTP-Redirect binding).
	 *
	 * @return the Base64 encoded and deflated LogoutResponse
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
			throw new Saml2Exception("Failed to build deflated LogoutResponse", ex);
		}
	}

}

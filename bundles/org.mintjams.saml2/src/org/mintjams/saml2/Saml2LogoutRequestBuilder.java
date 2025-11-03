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
 * Builds SAML 2.0 LogoutRequest messages.
 */
public class Saml2LogoutRequestBuilder {

	private final Saml2Settings settings;
	private String nameId;
	private String nameIdFormat;
	private String sessionIndex;

	public Saml2LogoutRequestBuilder(Saml2Settings settings) {
		this.settings = settings;
	}

	/**
	 * Sets the NameID of the user to logout.
	 *
	 * @param nameId the NameID
	 * @return this builder
	 */
	public Saml2LogoutRequestBuilder setNameId(String nameId) {
		this.nameId = nameId;
		return this;
	}

	/**
	 * Sets the NameID format.
	 *
	 * @param nameIdFormat the NameID format
	 * @return this builder
	 */
	public Saml2LogoutRequestBuilder setNameIdFormat(String nameIdFormat) {
		this.nameIdFormat = nameIdFormat;
		return this;
	}

	/**
	 * Sets the session index.
	 *
	 * @param sessionIndex the session index
	 * @return this builder
	 */
	public Saml2LogoutRequestBuilder setSessionIndex(String sessionIndex) {
		this.sessionIndex = sessionIndex;
		return this;
	}

	/**
	 * Builds the LogoutRequest as an XML document.
	 *
	 * @return the LogoutRequest document
	 * @throws Saml2Exception if building fails
	 */
	public Document buildDocument() throws Saml2Exception {
		if (nameId == null) {
			throw new Saml2Exception("NameID is required for LogoutRequest");
		}

		try {
			DocumentBuilder builder = XmlUtils.createSecureDocumentBuilder();
			Document document = builder.newDocument();

			Element logoutRequest = document.createElementNS(XmlUtils.getProtocolNamespace(), "samlp:LogoutRequest");
			document.appendChild(logoutRequest);

			// Set attributes
			String requestId = "_" + UUID.randomUUID().toString();
			logoutRequest.setAttribute("ID", requestId);
			logoutRequest.setAttribute("Version", "2.0");
			logoutRequest.setAttribute("IssueInstant", Instant.now().toString());
			logoutRequest.setAttribute("Destination", settings.getIdpSingleLogoutServiceUrl());

			// Add Issuer
			Element issuer = document.createElementNS(XmlUtils.getAssertionNamespace(), "saml:Issuer");
			issuer.setTextContent(settings.getSpEntityId());
			logoutRequest.appendChild(issuer);

			// Add NameID
			Element nameIdElement = document.createElementNS(XmlUtils.getAssertionNamespace(), "saml:NameID");
			nameIdElement.setTextContent(nameId);
			if (nameIdFormat != null) {
				nameIdElement.setAttribute("Format", nameIdFormat);
			}
			logoutRequest.appendChild(nameIdElement);

			// Add SessionIndex if provided
			if (sessionIndex != null) {
				Element sessionIndexElement = document.createElementNS(XmlUtils.getProtocolNamespace(), "samlp:SessionIndex");
				sessionIndexElement.setTextContent(sessionIndex);
				logoutRequest.appendChild(sessionIndexElement);
			}

			return document;

		} catch (Exception ex) {
			throw new Saml2Exception("Failed to build LogoutRequest", ex);
		}
	}

	/**
	 * Builds the LogoutRequest as a Base64 encoded string.
	 *
	 * @return the Base64 encoded LogoutRequest
	 * @throws Saml2Exception if building fails
	 */
	public String buildBase64() throws Saml2Exception {
		Document document = buildDocument();
		String xml = XmlUtils.documentToString(document);
		return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Builds the LogoutRequest as a Base64 encoded and deflated string (for HTTP-Redirect binding).
	 *
	 * @return the Base64 encoded and deflated LogoutRequest
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
			throw new Saml2Exception("Failed to build deflated LogoutRequest", ex);
		}
	}

}

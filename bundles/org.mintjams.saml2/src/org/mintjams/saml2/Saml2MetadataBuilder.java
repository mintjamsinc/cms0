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

import java.security.cert.CertificateEncodingException;
import java.util.Base64;

import org.mintjams.saml2.model.Saml2Settings;

/**
 * Builds a SAML 2.0 SP EntityDescriptor XML from Saml2Settings.
 */
public class Saml2MetadataBuilder {

	private final Saml2Settings fSettings;

	public Saml2MetadataBuilder(Saml2Settings settings) {
		fSettings = settings;
	}

	public String build() throws CertificateEncodingException {
		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<md:EntityDescriptor");
		sb.append(" xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\"");
		sb.append(" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"");
		if (fSettings.getSpEntityId() != null) {
			sb.append(" entityID=\"").append(escapeXml(fSettings.getSpEntityId())).append("\"");
		}
		sb.append(">\n");

		// SPSSODescriptor
		sb.append("  <md:SPSSODescriptor");
		sb.append(" AuthnRequestsSigned=\"").append(fSettings.isSignAuthnRequest()).append("\"");
		sb.append(" WantAssertionsSigned=\"").append(fSettings.isWantAssertionsSigned()).append("\"");
		sb.append(" protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\"");
		sb.append(">\n");

		if (fSettings.getSpCertificate() != null) {
			String certB64 = Base64.getMimeEncoder(64, new byte[]{'\n'})
					.encodeToString(fSettings.getSpCertificate().getEncoded());
			appendKeyDescriptor(sb, "signing", certB64);
			appendKeyDescriptor(sb, "encryption", certB64);
		}

		if (fSettings.getSpSingleLogoutServiceUrl() != null) {
			sb.append("    <md:SingleLogoutService");
			sb.append(" Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\"");
			sb.append(" Location=\"").append(escapeXml(fSettings.getSpSingleLogoutServiceUrl())).append("\"");
			sb.append("/>\n");
		}

		sb.append("    <md:NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</md:NameIDFormat>\n");

		if (fSettings.getSpAssertionConsumerServiceUrl() != null) {
			sb.append("    <md:AssertionConsumerService");
			sb.append(" Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\"");
			sb.append(" Location=\"").append(escapeXml(fSettings.getSpAssertionConsumerServiceUrl())).append("\"");
			sb.append(" index=\"1\"");
			sb.append("/>\n");
		}

		sb.append("  </md:SPSSODescriptor>\n");

		// Organization
		if (fSettings.getOrganizationName() != null
				|| fSettings.getOrganizationDisplayName() != null
				|| fSettings.getOrganizationUrl() != null) {
			String lang = fSettings.getOrganizationLanguage() != null ? fSettings.getOrganizationLanguage() : "en";
			sb.append("  <md:Organization>\n");
			if (fSettings.getOrganizationName() != null) {
				sb.append("    <md:OrganizationName xml:lang=\"").append(escapeXml(lang)).append("\">");
				sb.append(escapeXml(fSettings.getOrganizationName()));
				sb.append("</md:OrganizationName>\n");
			}
			if (fSettings.getOrganizationDisplayName() != null) {
				sb.append("    <md:OrganizationDisplayName xml:lang=\"").append(escapeXml(lang)).append("\">");
				sb.append(escapeXml(fSettings.getOrganizationDisplayName()));
				sb.append("</md:OrganizationDisplayName>\n");
			}
			if (fSettings.getOrganizationUrl() != null) {
				sb.append("    <md:OrganizationURL xml:lang=\"").append(escapeXml(lang)).append("\">");
				sb.append(escapeXml(fSettings.getOrganizationUrl()));
				sb.append("</md:OrganizationURL>\n");
			}
			sb.append("  </md:Organization>\n");
		}

		// Contact persons
		if (fSettings.getTechnicalContactName() != null || fSettings.getTechnicalContactEmail() != null) {
			appendContactPerson(sb, "technical", fSettings.getTechnicalContactName(), fSettings.getTechnicalContactEmail());
		}
		if (fSettings.getSupportContactName() != null || fSettings.getSupportContactEmail() != null) {
			appendContactPerson(sb, "support", fSettings.getSupportContactName(), fSettings.getSupportContactEmail());
		}

		sb.append("</md:EntityDescriptor>");
		return sb.toString();
	}

	private void appendKeyDescriptor(StringBuilder sb, String use, String certB64) {
		sb.append("    <md:KeyDescriptor use=\"").append(use).append("\">\n");
		sb.append("      <ds:KeyInfo>\n");
		sb.append("        <ds:X509Data>\n");
		sb.append("          <ds:X509Certificate>").append(certB64).append("</ds:X509Certificate>\n");
		sb.append("        </ds:X509Data>\n");
		sb.append("      </ds:KeyInfo>\n");
		sb.append("    </md:KeyDescriptor>\n");
	}

	private void appendContactPerson(StringBuilder sb, String type, String name, String email) {
		sb.append("  <md:ContactPerson contactType=\"").append(type).append("\">\n");
		if (name != null) {
			sb.append("    <md:GivenName>").append(escapeXml(name)).append("</md:GivenName>\n");
		}
		if (email != null) {
			sb.append("    <md:EmailAddress>").append(escapeXml(email)).append("</md:EmailAddress>\n");
		}
		sb.append("  </md:ContactPerson>\n");
	}

	private static String escapeXml(String value) {
		if (value == null) {
			return "";
		}
		return value
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&apos;");
	}

}

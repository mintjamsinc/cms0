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

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Properties;

import org.mintjams.saml2.exception.Saml2Exception;
import org.mintjams.saml2.model.Saml2Settings;
import org.mintjams.saml2.util.CertificateUtils;

/**
 * Builder for creating Saml2Settings from various sources.
 */
public class Saml2SettingsBuilder {

	private final Saml2Settings settings;

	public Saml2SettingsBuilder() {
		this.settings = new Saml2Settings();
	}

	/**
	 * Loads settings from Properties (similar to OneLogin's format).
	 *
	 * @param properties the properties
	 * @return this builder
	 * @throws Saml2Exception if loading fails
	 */
	public Saml2SettingsBuilder fromProperties(Properties properties) throws Saml2Exception {
		// SP settings
		String spEntityId = properties.getProperty("saml2.sp.entityid");
		if (spEntityId != null) {
			settings.setSpEntityId(spEntityId);
		}

		String spAcsUrl = properties.getProperty("saml2.sp.assertion_consumer_service.url");
		if (spAcsUrl != null) {
			settings.setSpAssertionConsumerServiceUrl(spAcsUrl);
		}

		String spSloUrl = properties.getProperty("saml2.sp.single_logout_service.url");
		if (spSloUrl != null) {
			settings.setSpSingleLogoutServiceUrl(spSloUrl);
		}

		String spCertStr = properties.getProperty("saml2.sp.x509cert");
		if (spCertStr != null && !spCertStr.trim().isEmpty()) {
			X509Certificate spCert = CertificateUtils.parseCertificate(spCertStr);
			settings.setSpCertificate(spCert);
		}

		String spKeyStr = properties.getProperty("saml2.sp.privatekey");
		if (spKeyStr != null && !spKeyStr.trim().isEmpty()) {
			PrivateKey spKey = CertificateUtils.parsePrivateKey(spKeyStr);
			settings.setSpPrivateKey(spKey);
		}

		// IdP settings
		String idpEntityId = properties.getProperty("saml2.idp.entityid");
		if (idpEntityId != null) {
			settings.setIdpEntityId(idpEntityId);
		}

		String idpSsoUrl = properties.getProperty("saml2.idp.single_sign_on_service.url");
		if (idpSsoUrl != null) {
			settings.setIdpSingleSignOnServiceUrl(idpSsoUrl);
		}

		String idpSloUrl = properties.getProperty("saml2.idp.single_logout_service.url");
		if (idpSloUrl != null) {
			settings.setIdpSingleLogoutServiceUrl(idpSloUrl);
		}

		String idpCertStr = properties.getProperty("saml2.idp.x509cert");
		if (idpCertStr != null && !idpCertStr.trim().isEmpty()) {
			X509Certificate idpCert = CertificateUtils.parseCertificate(idpCertStr);
			settings.setIdpCertificate(idpCert);
		}

		// Security settings
		String strict = properties.getProperty("saml2.strict");
		if (strict != null) {
			settings.setStrict(Boolean.parseBoolean(strict));
		}

		String debug = properties.getProperty("saml2.debug");
		if (debug != null) {
			settings.setDebug(Boolean.parseBoolean(debug));
		}

		String wantAssertionsSigned = properties.getProperty("saml2.security.want_assertions_signed");
		if (wantAssertionsSigned != null) {
			settings.setWantAssertionsSigned(Boolean.parseBoolean(wantAssertionsSigned));
		}

		String wantMessagesSigned = properties.getProperty("saml2.security.want_messages_signed");
		if (wantMessagesSigned != null) {
			settings.setWantMessagesSigned(Boolean.parseBoolean(wantMessagesSigned));
		}

		String signAuthnRequest = properties.getProperty("saml2.security.authnrequest_signed");
		if (signAuthnRequest != null) {
			settings.setSignAuthnRequest(Boolean.parseBoolean(signAuthnRequest));
		}

		String signLogoutRequest = properties.getProperty("saml2.security.logoutrequest_signed");
		if (signLogoutRequest != null) {
			settings.setSignLogoutRequest(Boolean.parseBoolean(signLogoutRequest));
		}

		String signLogoutResponse = properties.getProperty("saml2.security.logoutresponse_signed");
		if (signLogoutResponse != null) {
			settings.setSignLogoutResponse(Boolean.parseBoolean(signLogoutResponse));
		}

		String wantNameIdEncrypted = properties.getProperty("saml2.security.want_nameid_encrypted");
		if (wantNameIdEncrypted != null) {
			settings.setWantNameIdEncrypted(Boolean.parseBoolean(wantNameIdEncrypted));
		}

		String wantAssertionsEncrypted = properties.getProperty("saml2.security.want_assertions_encrypted");
		if (wantAssertionsEncrypted != null) {
			settings.setWantAssertionsEncrypted(Boolean.parseBoolean(wantAssertionsEncrypted));
		}

		// Organization
		String orgName = properties.getProperty("saml2.organization.name");
		if (orgName != null && !orgName.trim().isEmpty()) {
			settings.setOrganizationName(orgName);
		}

		String orgDisplayName = properties.getProperty("saml2.organization.displayname");
		if (orgDisplayName != null && !orgDisplayName.trim().isEmpty()) {
			settings.setOrganizationDisplayName(orgDisplayName);
		}

		String orgUrl = properties.getProperty("saml2.organization.url");
		if (orgUrl != null && !orgUrl.trim().isEmpty()) {
			settings.setOrganizationUrl(orgUrl);
		}

		String orgLang = properties.getProperty("saml2.organization.lang");
		if (orgLang != null && !orgLang.trim().isEmpty()) {
			settings.setOrganizationLanguage(orgLang);
		}

		// Contacts
		String techName = properties.getProperty("saml2.contacts.technical.given_name");
		if (techName != null && !techName.trim().isEmpty()) {
			settings.setTechnicalContactName(techName);
		}

		String techEmail = properties.getProperty("saml2.contacts.technical.email_address");
		if (techEmail != null && !techEmail.trim().isEmpty()) {
			settings.setTechnicalContactEmail(techEmail);
		}

		String supportName = properties.getProperty("saml2.contacts.support.given_name");
		if (supportName != null && !supportName.trim().isEmpty()) {
			settings.setSupportContactName(supportName);
		}

		String supportEmail = properties.getProperty("saml2.contacts.support.email_address");
		if (supportEmail != null && !supportEmail.trim().isEmpty()) {
			settings.setSupportContactEmail(supportEmail);
		}

		return this;
	}

	/**
	 * Builds the Saml2Settings.
	 *
	 * @return the configured Saml2Settings
	 */
	public Saml2Settings build() {
		return settings;
	}

	/**
	 * Gets the settings (for direct manipulation).
	 *
	 * @return the settings
	 */
	public Saml2Settings getSettings() {
		return settings;
	}

}

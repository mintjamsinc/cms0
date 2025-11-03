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

package org.mintjams.saml2.model;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Configuration settings for SAML 2.0 Service Provider.
 */
public class Saml2Settings {

	// SP settings
	private String spEntityId;
	private String spAssertionConsumerServiceUrl;
	private String spSingleLogoutServiceUrl;
	private X509Certificate spCertificate;
	private PrivateKey spPrivateKey;

	// IdP settings
	private String idpEntityId;
	private String idpSingleSignOnServiceUrl;
	private String idpSingleLogoutServiceUrl;
	private X509Certificate idpCertificate;

	// Security settings
	private boolean strict = true;
	private boolean debug = false;
	private boolean wantAssertionsSigned = true;
	private boolean wantMessagesSigned = true;
	private boolean signAuthnRequest = true;
	private boolean signLogoutRequest = true;
	private boolean signLogoutResponse = true;
	private boolean wantNameIdEncrypted = false;
	private boolean wantAssertionsEncrypted = false;

	// SP Entity ID
	public String getSpEntityId() {
		return spEntityId;
	}

	public void setSpEntityId(String spEntityId) {
		this.spEntityId = spEntityId;
	}

	// SP Assertion Consumer Service URL
	public String getSpAssertionConsumerServiceUrl() {
		return spAssertionConsumerServiceUrl;
	}

	public void setSpAssertionConsumerServiceUrl(String spAssertionConsumerServiceUrl) {
		this.spAssertionConsumerServiceUrl = spAssertionConsumerServiceUrl;
	}

	// SP Single Logout Service URL
	public String getSpSingleLogoutServiceUrl() {
		return spSingleLogoutServiceUrl;
	}

	public void setSpSingleLogoutServiceUrl(String spSingleLogoutServiceUrl) {
		this.spSingleLogoutServiceUrl = spSingleLogoutServiceUrl;
	}

	// SP Certificate
	public X509Certificate getSpCertificate() {
		return spCertificate;
	}

	public void setSpCertificate(X509Certificate spCertificate) {
		this.spCertificate = spCertificate;
	}

	// SP Private Key
	public PrivateKey getSpPrivateKey() {
		return spPrivateKey;
	}

	public void setSpPrivateKey(PrivateKey spPrivateKey) {
		this.spPrivateKey = spPrivateKey;
	}

	// IdP Entity ID
	public String getIdpEntityId() {
		return idpEntityId;
	}

	public void setIdpEntityId(String idpEntityId) {
		this.idpEntityId = idpEntityId;
	}

	// IdP Single Sign-On Service URL
	public String getIdpSingleSignOnServiceUrl() {
		return idpSingleSignOnServiceUrl;
	}

	public void setIdpSingleSignOnServiceUrl(String idpSingleSignOnServiceUrl) {
		this.idpSingleSignOnServiceUrl = idpSingleSignOnServiceUrl;
	}

	// IdP Single Logout Service URL
	public String getIdpSingleLogoutServiceUrl() {
		return idpSingleLogoutServiceUrl;
	}

	public void setIdpSingleLogoutServiceUrl(String idpSingleLogoutServiceUrl) {
		this.idpSingleLogoutServiceUrl = idpSingleLogoutServiceUrl;
	}

	// IdP Certificate
	public X509Certificate getIdpCertificate() {
		return idpCertificate;
	}

	public void setIdpCertificate(X509Certificate idpCertificate) {
		this.idpCertificate = idpCertificate;
	}

	// Strict mode
	public boolean isStrict() {
		return strict;
	}

	public void setStrict(boolean strict) {
		this.strict = strict;
	}

	// Debug mode
	public boolean isDebug() {
		return debug;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	// Want Assertions Signed
	public boolean isWantAssertionsSigned() {
		return wantAssertionsSigned;
	}

	public void setWantAssertionsSigned(boolean wantAssertionsSigned) {
		this.wantAssertionsSigned = wantAssertionsSigned;
	}

	// Want Messages Signed
	public boolean isWantMessagesSigned() {
		return wantMessagesSigned;
	}

	public void setWantMessagesSigned(boolean wantMessagesSigned) {
		this.wantMessagesSigned = wantMessagesSigned;
	}

	// Sign AuthnRequest
	public boolean isSignAuthnRequest() {
		return signAuthnRequest;
	}

	public void setSignAuthnRequest(boolean signAuthnRequest) {
		this.signAuthnRequest = signAuthnRequest;
	}

	// Sign LogoutRequest
	public boolean isSignLogoutRequest() {
		return signLogoutRequest;
	}

	public void setSignLogoutRequest(boolean signLogoutRequest) {
		this.signLogoutRequest = signLogoutRequest;
	}

	// Sign LogoutResponse
	public boolean isSignLogoutResponse() {
		return signLogoutResponse;
	}

	public void setSignLogoutResponse(boolean signLogoutResponse) {
		this.signLogoutResponse = signLogoutResponse;
	}

	// Want NameId Encrypted
	public boolean isWantNameIdEncrypted() {
		return wantNameIdEncrypted;
	}

	public void setWantNameIdEncrypted(boolean wantNameIdEncrypted) {
		this.wantNameIdEncrypted = wantNameIdEncrypted;
	}

	// Want Assertions Encrypted
	public boolean isWantAssertionsEncrypted() {
		return wantAssertionsEncrypted;
	}

	public void setWantAssertionsEncrypted(boolean wantAssertionsEncrypted) {
		this.wantAssertionsEncrypted = wantAssertionsEncrypted;
	}

}

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

package org.mintjams.idp.model;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration settings for the SAML 2.0 Identity Provider.
 */
public class IdpSettings {

	private String entityId;
	private String baseUrl;
	private String contextPath = "/idp";
	private X509Certificate certificate;
	private PrivateKey privateKey;
	private List<TrustedSP> trustedSPs = new ArrayList<>();
	private int assertionValiditySeconds = 300;

	public String getEntityId() {
		return entityId;
	}

	public void setEntityId(String entityId) {
		this.entityId = entityId;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getContextPath() {
		return contextPath;
	}

	public void setContextPath(String contextPath) {
		this.contextPath = contextPath;
	}

	public X509Certificate getCertificate() {
		return certificate;
	}

	public void setCertificate(X509Certificate certificate) {
		this.certificate = certificate;
	}

	public PrivateKey getPrivateKey() {
		return privateKey;
	}

	public void setPrivateKey(PrivateKey privateKey) {
		this.privateKey = privateKey;
	}

	public List<TrustedSP> getTrustedSPs() {
		return Collections.unmodifiableList(trustedSPs);
	}

	public void addTrustedSP(TrustedSP sp) {
		trustedSPs.add(sp);
	}

	public int getAssertionValiditySeconds() {
		return assertionValiditySeconds;
	}

	public void setAssertionValiditySeconds(int assertionValiditySeconds) {
		this.assertionValiditySeconds = assertionValiditySeconds;
	}

	/**
	 * Gets the SSO endpoint URL.
	 */
	public String getSsoUrl() {
		return baseUrl + contextPath + "/sso";
	}

	/**
	 * Gets the SLO endpoint URL.
	 */
	public String getSloUrl() {
		return baseUrl + contextPath + "/slo";
	}

	/**
	 * Gets the metadata endpoint URL.
	 */
	public String getMetadataUrl() {
		return baseUrl + contextPath + "/metadata";
	}

	/**
	 * Gets the login endpoint URL.
	 */
	public String getLoginUrl() {
		return baseUrl + contextPath + "/login";
	}

	/**
	 * Checks if the given SP entity ID is trusted.
	 * If no trusted SPs are configured, all SPs are trusted (starter mode).
	 */
	public boolean isTrustedSP(String spEntityId) {
		if (trustedSPs.isEmpty()) {
			return true;
		}
		for (TrustedSP sp : trustedSPs) {
			if (sp.getEntityId().equals(spEntityId)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Represents a trusted Service Provider.
	 */
	public static class TrustedSP {
		private String entityId;
		private String acsUrl;

		public TrustedSP() {
		}

		public TrustedSP(String entityId, String acsUrl) {
			this.entityId = entityId;
			this.acsUrl = acsUrl;
		}

		public String getEntityId() {
			return entityId;
		}

		public void setEntityId(String entityId) {
			this.entityId = entityId;
		}

		public String getAcsUrl() {
			return acsUrl;
		}

		public void setAcsUrl(String acsUrl) {
			this.acsUrl = acsUrl;
		}
	}

}

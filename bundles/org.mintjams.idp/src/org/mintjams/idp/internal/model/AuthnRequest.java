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

package org.mintjams.idp.internal.model;

/**
 * Represents a parsed SAML 2.0 AuthnRequest from a Service Provider.
 */
public class AuthnRequest {

	private String id;
	private String issuer;
	private String assertionConsumerServiceUrl;
	private String destination;
	private boolean forceAuthn;
	private boolean isPassive;
	private String relayState;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	public String getAssertionConsumerServiceUrl() {
		return assertionConsumerServiceUrl;
	}

	public void setAssertionConsumerServiceUrl(String assertionConsumerServiceUrl) {
		this.assertionConsumerServiceUrl = assertionConsumerServiceUrl;
	}

	public String getDestination() {
		return destination;
	}

	public void setDestination(String destination) {
		this.destination = destination;
	}

	public boolean isForceAuthn() {
		return forceAuthn;
	}

	public void setForceAuthn(boolean forceAuthn) {
		this.forceAuthn = forceAuthn;
	}

	public boolean isPassive() {
		return isPassive;
	}

	public void setPassive(boolean isPassive) {
		this.isPassive = isPassive;
	}

	public String getRelayState() {
		return relayState;
	}

	public void setRelayState(String relayState) {
		this.relayState = relayState;
	}

}

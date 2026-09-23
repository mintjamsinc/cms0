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

package org.mintjams.cms.security.mfa;

/**
 * A TOTP enrollment that is waiting for the user to confirm it with a first
 * valid code. The secret is shown to the user exactly once, here.
 */
public final class TotpEnrollment {

	private final String fSecret;
	private final String fIssuer;
	private final String fAccountName;
	private final String fOtpauthUri;

	public TotpEnrollment(String secret, String issuer, String accountName, String otpauthUri) {
		fSecret = secret;
		fIssuer = issuer;
		fAccountName = accountName;
		fOtpauthUri = otpauthUri;
	}

	/** The shared secret, Base32-encoded, for manual entry. */
	public String getSecret() {
		return fSecret;
	}

	public String getIssuer() {
		return fIssuer;
	}

	public String getAccountName() {
		return fAccountName;
	}

	/** The {@code otpauth://totp/...} URI for QR-code enrollment. */
	public String getOtpauthUri() {
		return fOtpauthUri;
	}

}

/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.script;

/**
 * Thrown by {@link Connections} when the user has to act before a token can
 * be issued: link the account, grant a bundle, or link again after the
 * provider stopped honouring the stored grant. The message starts with
 * {@code consent_required} so a client can tell it from other errors, and the
 * reason says which of the three it is.
 */
public class ConsentRequiredException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** The provider is not configured on this server (no client in clients.yml). */
	public static final String NOT_CONFIGURED = "not_configured";
	/** The user has not linked an account of this provider. */
	public static final String NOT_LINKED = "not_linked";
	/** The account is linked, but the bundle has not been granted. */
	public static final String BUNDLE_NOT_GRANTED = "bundle_not_granted";
	/** The provider no longer accepts the stored grant; the user has to link again. */
	public static final String REAUTHORIZE = "reauthorize";

	private final String fProvider;
	private final String fBundle;
	private final String fReason;

	public ConsentRequiredException(String provider, String bundle, String reason) {
		super("consent_required: provider=" + provider + " bundle=" + bundle + " reason=" + reason);
		fProvider = provider;
		fBundle = bundle;
		fReason = reason;
	}

	public String getProvider() {
		return fProvider;
	}

	public String getBundle() {
		return fBundle;
	}

	public String getReason() {
		return fReason;
	}

}

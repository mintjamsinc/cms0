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

package org.mintjams.cms.security;

/**
 * Which ways of signing in the identity provider offers right now. Published
 * by the IdP bundle so that the platform (Preferences, Identity Manager) can
 * tell users what applies to them without reading {@code idp.yml} itself.
 *
 * <p>The answer is computed on every call: the administrator's settings are
 * read at start-up, but whether a sign-in code can be sent depends on the
 * mail transport, which may appear or disappear while the server runs.</p>
 */
public interface SignInPolicy {

	/** The state of one sign-in method. */
	enum State {
		/** Offered on the sign-in page. */
		AVAILABLE,
		/** Switched off by the administrator; shown on the sign-in page as unavailable. */
		DISABLED,
		/** Not possible in this installation (no mail transport, no passkey relying party). Not shown. */
		UNAVAILABLE;

		/** The lower-case name used in JSON and GraphQL ({@code available}, {@code disabled}, {@code unavailable}). */
		public String key() {
			return name().toLowerCase();
		}
	}

	/** Username and password. */
	State getPassword();

	/** A one-time code sent to the user's registered email address. */
	State getEmailCode();

	/** A passkey (WebAuthn). */
	State getPasskey();

	/**
	 * Whether the administrator asked to disable passwords and the server is
	 * honouring that. {@code false} also when the setting was ignored because
	 * no other method could sign anyone in, or a start-up override allowed
	 * passwords again.
	 */
	default boolean isPasswordDisabled() {
		return getPassword() == State.DISABLED;
	}

}

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

package org.mintjams.idp.internal.auth;

import java.util.concurrent.atomic.AtomicReference;

import org.mintjams.cms.security.SignInPolicy;
import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.IdpConfiguration;
import org.mintjams.tools.lang.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which sign-in methods the IdP offers, from {@code idp.yml}'s
 * {@code signIn} section, the mail transport and the passkey relying party.
 *
 * <p>The rules, in order:</p>
 * <ol>
 *   <li>{@code CMS_IDP_ALLOW_PASSWORD=true} (or {@code -Dcms.idp.allowPassword=true})
 *       allows passwords for this run whatever the file says: the way back
 *       in for an administrator who disabled passwords and then found that
 *       no one can sign in.</li>
 *   <li>A sign-in code can be offered only when {@code signIn.emailCode} is
 *       on and a mail transport is configured.</li>
 *   <li>{@code signIn.password: false} is honoured only while a sign-in code
 *       can be offered. Otherwise nobody could sign in, so passwords stay on
 *       and the log says why.</li>
 * </ol>
 *
 * <p>The answer is computed on every call (the mail transport may appear or
 * go away while the server runs); the log carries a line whenever it
 * changes.</p>
 */
public class SignInPolicyImpl implements SignInPolicy {

	private static final Logger LOG = LoggerFactory.getLogger(SignInPolicyImpl.class);

	/** Environment variable that re-enables passwords for this run. */
	public static final String ENV_ALLOW_PASSWORD = "CMS_IDP_ALLOW_PASSWORD";
	/** System property that re-enables passwords for this run. */
	public static final String PROPERTY_ALLOW_PASSWORD = "cms.idp.allowPassword";

	private final AtomicReference<String> fLastSummary = new AtomicReference<>();

	/** The three states at one instant, plus why passwords were kept on. */
	public static final class Snapshot {
		private final State fPassword;
		private final State fEmailCode;
		private final State fPasskey;
		private final String fNote;

		Snapshot(State password, State emailCode, State passkey, String note) {
			fPassword = password;
			fEmailCode = emailCode;
			fPasskey = passkey;
			fNote = note;
		}

		public State getPassword() {
			return fPassword;
		}

		public State getEmailCode() {
			return fEmailCode;
		}

		public State getPasskey() {
			return fPasskey;
		}

		/** Why passwords stay on although the file disables them, or {@code null}. */
		public String getNote() {
			return fNote;
		}
	}

	/** Whether the start-up override for passwords is set. */
	public static boolean isPasswordOverridden() {
		String value = System.getenv(ENV_ALLOW_PASSWORD);
		if (value == null || value.isBlank()) {
			value = System.getProperty(PROPERTY_ALLOW_PASSWORD);
		}
		return value != null && "true".equalsIgnoreCase(value.trim());
	}

	@Override
	public State getPassword() {
		return evaluate().getPassword();
	}

	@Override
	public State getEmailCode() {
		return evaluate().getEmailCode();
	}

	@Override
	public State getPasskey() {
		return evaluate().getPasskey();
	}

	@Override
	public String getIssuer() {
		return Activator.getDefault().getConfiguration().getTotpIssuer();
	}

	/**
	 * Whether the password the administrator switched off is being accepted
	 * because of the start-up override (so that each such sign-in can be
	 * logged).
	 */
	public boolean isPasswordAllowedByOverride() {
		return isPasswordOverridden() && !Activator.getDefault().getConfiguration().isPasswordSignInConfigured();
	}

	public Snapshot evaluate() {
		IdpConfiguration config = Activator.getDefault().getConfiguration();

		State emailCode;
		String emailCodeReason = null;
		if (!config.isEmailCodeSignInConfigured()) {
			emailCode = State.DISABLED;
			emailCodeReason = "signIn.emailCode is false";
		} else if (!Activator.getDefault().isMailTransportAvailable()) {
			emailCode = State.UNAVAILABLE;
			emailCodeReason = "no mail transport is configured (etc/mail.yml)";
		} else {
			emailCode = State.AVAILABLE;
		}

		State passkey = Strings.isNotEmpty(config.getWebAuthnRpId()) ? State.AVAILABLE : State.UNAVAILABLE;

		State password;
		String note = null;
		if (config.isPasswordSignInConfigured()) {
			password = State.AVAILABLE;
		} else if (isPasswordOverridden()) {
			password = State.AVAILABLE;
			note = "signIn.password is false in idp.yml, but " + ENV_ALLOW_PASSWORD + " / -D" + PROPERTY_ALLOW_PASSWORD
					+ " re-enables passwords for this run. Remove it once the sign-in settings are repaired.";
		} else if (emailCode != State.AVAILABLE) {
			password = State.AVAILABLE;
			note = "signIn.password is false in idp.yml, but no other method could sign anyone in (" + emailCodeReason
					+ "); passwords stay enabled.";
		} else {
			password = State.DISABLED;
		}

		Snapshot snapshot = new Snapshot(password, emailCode, passkey, note);
		String summary = password + "/" + emailCode + "/" + passkey + "/" + note;
		if (!summary.equals(fLastSummary.getAndSet(summary))) {
			if (note != null) {
				LOG.warn(note);
			}
			LOG.info("Sign-in methods: password={}, emailCode={}, passkey={}", password.key(), emailCode.key(), passkey.key());
		}
		return snapshot;
	}

	/**
	 * Logs the state at start-up and warns when passwords are disabled while
	 * no administrator has a verified email address: such an administrator
	 * would be locked out by a typo in their own address.
	 */
	public void logStartup(UserStore userStore) {
		Snapshot snapshot = evaluate();
		if (snapshot.getPassword() != State.DISABLED) {
			return;
		}
		int verified;
		try {
			verified = userStore.countAdministratorsWithVerifiedMail();
		} catch (Throwable ex) {
			LOG.warn("Passwords are disabled; could not count the administrators with a verified email address.", ex);
			return;
		}
		if (verified == 0) {
			LOG.warn("Passwords are disabled, but no administrator has a verified email address."
					+ " If administrators cannot sign in, start with " + ENV_ALLOW_PASSWORD + "=true, repair the addresses, and restart without it.");
		} else {
			LOG.info("Passwords are disabled; {} administrator(s) have a verified email address.", verified);
		}
	}

}

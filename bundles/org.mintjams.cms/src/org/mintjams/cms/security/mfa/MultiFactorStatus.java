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

import java.util.Collections;
import java.util.List;

/**
 * A user's second-factor enrollment state.
 */
public final class MultiFactorStatus {

	private final boolean fTotpEnabled;
	private final int fBackupCodesRemaining;
	private final List<PasskeyInfo> fPasskeys;
	private final boolean fPasskeysAvailable;

	public MultiFactorStatus(boolean totpEnabled, int backupCodesRemaining, List<PasskeyInfo> passkeys, boolean passkeysAvailable) {
		fTotpEnabled = totpEnabled;
		fBackupCodesRemaining = backupCodesRemaining;
		fPasskeys = passkeys == null ? Collections.emptyList() : List.copyOf(passkeys);
		fPasskeysAvailable = passkeysAvailable;
	}

	public boolean isTotpEnabled() {
		return fTotpEnabled;
	}

	/** Unused backup codes left; 0 when TOTP is not enabled. */
	public int getBackupCodesRemaining() {
		return fBackupCodesRemaining;
	}

	public List<PasskeyInfo> getPasskeys() {
		return fPasskeys;
	}

	/** Whether the server is configured for WebAuthn (a relying-party id is known). */
	public boolean isPasskeysAvailable() {
		return fPasskeysAvailable;
	}

}

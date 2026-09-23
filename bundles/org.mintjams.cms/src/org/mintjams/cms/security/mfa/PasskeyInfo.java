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
import java.util.Date;
import java.util.List;

/**
 * A registered passkey (WebAuthn credential) as shown to its owner. The
 * public key and counters stay server-side; {@link #getId()} is an opaque
 * identifier for rename and delete.
 */
public final class PasskeyInfo {

	private final String fId;
	private final String fDisplayName;
	private final Date fCreated;
	private final Date fLastUsed;
	private final List<String> fTransports;
	private final boolean fBackedUp;

	public PasskeyInfo(String id, String displayName, Date created, Date lastUsed, List<String> transports, boolean backedUp) {
		fId = id;
		fDisplayName = displayName;
		fCreated = created;
		fLastUsed = lastUsed;
		fTransports = transports == null ? Collections.emptyList() : List.copyOf(transports);
		fBackedUp = backedUp;
	}

	public String getId() {
		return fId;
	}

	public String getDisplayName() {
		return fDisplayName;
	}

	public Date getCreated() {
		return fCreated;
	}

	public Date getLastUsed() {
		return fLastUsed;
	}

	/** The authenticator transports reported at registration (usb, nfc, ble, internal, hybrid). */
	public List<String> getTransports() {
		return fTransports;
	}

	/** Whether the authenticator reported the credential as backed up (a synced passkey). */
	public boolean isBackedUp() {
		return fBackedUp;
	}

}

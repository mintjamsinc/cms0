/*
 * Copyright (c) 2022 MintJams Inc.
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

package org.mintjams.rt.jcr.internal;

import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.namespace.QName;

import org.mintjams.jcr.security.AdminPrincipal;
import org.mintjams.jcr.security.GuestPrincipal;
import org.mintjams.rt.jcr.internal.security.ServicePrincipal;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.lang.Strings;

public class SessionIdentifier extends QName {

	private static final long serialVersionUID = 1L;

	public static final String SYSTEM_NS_URI = "https://www.mintjams.jp/jcr/1.0/session/system";
	public static final String SERVICE_NS_URI = "https://www.mintjams.jp/jcr/1.0/session/service";
	public static final String GUEST_NS_URI = "https://www.mintjams.jp/jcr/1.0/session/guest";
	public static final String ADMIN_NS_URI = "https://www.mintjams.jp/jcr/1.0/session/admin";
	public static final String GENERIC_NS_URI = "https://www.mintjams.jp/jcr/1.0/session/generic";
	public static final String DEFAULT_NS_PREFIX = "";

	private final AtomicLong fTransactionSequence = new AtomicLong(1);

	private SessionIdentifier(String namespaceURI, String localPart, String prefix) {
		super(namespaceURI, localPart, Strings.defaultString(prefix, DEFAULT_NS_PREFIX));
	}

	private SessionIdentifier(String namespaceURI, String localPart) {
		this(namespaceURI, localPart, null);
	}

	public static SessionIdentifier create(Principal principal) {
		String id = UUID.randomUUID().toString() + "/" + System.nanoTime();

		if (principal instanceof SystemPrincipal) {
			return new SessionIdentifier(SYSTEM_NS_URI, id);
		}

		if (principal instanceof ServicePrincipal) {
			return new SessionIdentifier(SERVICE_NS_URI, id);
		}

		if (principal instanceof GuestPrincipal) {
			return new SessionIdentifier(GUEST_NS_URI, id);
		}

		if (principal instanceof AdminPrincipal) {
			return new SessionIdentifier(ADMIN_NS_URI, id);
		}

		return new SessionIdentifier(GENERIC_NS_URI, id);
	}

	public long getCreated() {
		return Long.valueOf(getLocalPart().split("/")[1]);
	}

	public String getTransactionIdentifier() {
		return toString() + "-" + fTransactionSequence.get();
	}

	public String nextTransaction() {
		return toString() + "-" + fTransactionSequence.incrementAndGet();
	}

	public static SessionIdentifier valueOf(String qNameAsString) {
		QName qName = QName.valueOf(qNameAsString);
		return new SessionIdentifier(qName.getNamespaceURI(), qName.getLocalPart(), qName.getPrefix());
	}

}

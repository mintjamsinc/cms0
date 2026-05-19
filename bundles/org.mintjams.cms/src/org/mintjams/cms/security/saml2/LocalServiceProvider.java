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

package org.mintjams.cms.security.saml2;

import java.security.cert.X509Certificate;

/**
 * Exposes the SAML 2.0 Service Provider co-located in the same JVM as an
 * OSGi service so the Identity Provider can discover the SP's entityId,
 * Assertion Consumer Service URL and signing certificate without requiring
 * a corresponding entry in {@code idp.yml#trustedSPs}.
 *
 * <p>Implementations are registered by the CMS bundle once its SP keystore
 * is initialized. The IdP treats the local SP as implicitly trusted so a
 * fresh installation works end-to-end without manual exchange of metadata.</p>
 */
public interface LocalServiceProvider {

	String getEntityId();

	String getAcsUrl();

	String getDisplayName();

	X509Certificate getSigningCertificate();

}

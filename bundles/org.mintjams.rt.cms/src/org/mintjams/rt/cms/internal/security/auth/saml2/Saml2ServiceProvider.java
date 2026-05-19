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

package org.mintjams.rt.cms.internal.security.auth.saml2;

import java.io.Closeable;
import java.io.IOException;
import java.security.cert.X509Certificate;

import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.mintjams.cms.security.saml2.LocalIdentityProvider;
import org.mintjams.cms.security.saml2.LocalServiceProvider;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.osgi.Registration;
import org.mintjams.tools.osgi.Tracker;
import org.osgi.framework.BundleContext;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

public class Saml2ServiceProvider implements Closeable {

	private final Saml2ServiceProviderConfiguration fConfig;
	private final Closer fCloser = Closer.create();
	private HttpServlet fServlet;

	public Saml2ServiceProvider() {
		fConfig = new Saml2ServiceProviderConfiguration();
	}

	public synchronized void open() throws IOException, RepositoryException {
		BundleContext bundleContext = CmsService.getDefault().getBundleContext();
		fConfig.setBundleContext(bundleContext);
		fServlet = fConfig.createServlet();

		fCloser.register(Registration.newBuilder(Servlet.class)
				.setService(fServlet)
				.setProperty(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, fConfig.getContextPath() + "/*")
				.setProperty(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(osgi.http.whiteboard.context.name=org.osgi.service.http)")
				.setBundleContext(bundleContext)
				.build());

		// Invalidate the cached SAML2 settings whenever the co-located IdP
		// service appears or disappears so blank idp.* fields in saml2.yml are
		// re-resolved from the live OSGi service.
		try {
			Tracker.Listener<Object> idpListener = new Tracker.Listener<Object>() {
				@Override
				public void on(Tracker.Event<Object> event) {
					fConfig.invalidateSaml2Settings();
				}
			};
			Tracker<LocalIdentityProvider> idpTracker = Tracker.newBuilder(LocalIdentityProvider.class)
					.setBundleContext(bundleContext)
					.setListener(idpListener)
					.build();
			fCloser.register(idpTracker);
			idpTracker.open();
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}

		// Publish ourselves so the local IdP can implicitly trust this SP
		// without manual entry in idp.yml#trustedSPs.
		fCloser.register(Registration.newBuilder(LocalServiceProvider.class)
				.setService(new LocalSpServiceImpl(fConfig))
				.setBundleContext(bundleContext)
				.build());
	}

	@Override
	public synchronized void close() throws IOException {
		fCloser.close();
	}

	public HttpServlet getServlet() {
		return fServlet;
	}

	public Saml2ServiceProviderConfiguration getConfiguration() {
		return fConfig;
	}

	private static class LocalSpServiceImpl implements LocalServiceProvider {
		private final Saml2ServiceProviderConfiguration fConfig;

		LocalSpServiceImpl(Saml2ServiceProviderConfiguration config) {
			fConfig = config;
		}

		@Override
		public String getEntityId() {
			return fConfig.getSpEntityID();
		}

		@Override
		public String getAcsUrl() {
			return fConfig.getSpAssertionConsumerServiceURL();
		}

		@Override
		public String getDisplayName() {
			return fConfig.getSpDisplayName();
		}

		@Override
		public X509Certificate getSigningCertificate() {
			return fConfig.getKeyStoreManager().getCertificate();
		}
	}

}

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

package org.mintjams.idp.internal.servlet;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.IdpConfiguration;
import org.mintjams.idp.internal.saml.MetadataBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the IdP SAML metadata XML at {@code /idp/metadata}.
 *
 * <p>Service Providers use this endpoint to auto-configure trust with the IdP.
 * The metadata includes the IdP entity ID, SSO endpoint URL, and signing certificate.</p>
 */
public class MetadataServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(MetadataServlet.class);

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		IdpConfiguration config = Activator.getDefault().getConfiguration();

		try {
			MetadataBuilder builder = new MetadataBuilder(config);
			String metadata = builder.build();

			response.setContentType("application/samlmetadata+xml");
			response.setCharacterEncoding("UTF-8");
			response.getWriter().write(metadata);

		} catch (Exception e) {
			LOG.error("Failed to generate IdP metadata", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to generate metadata");
		}
	}

}

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
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.IdpConfiguration;
import org.mintjams.idp.internal.model.AuthnRequest;
import org.mintjams.idp.internal.saml.AuthnRequestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API servlet that returns display information about the Service Provider
 * requesting authentication, at {@code /idp/api/sp}.
 *
 * <p>Reads the pending SAMLRequest from the HTTP session (stored by SsoServlet),
 * parses the issuer, and looks up the SP display name from the trusted SPs
 * configuration.</p>
 *
 * <p>Success response:</p>
 * <pre>{"status": "success", "data": {"entityId": "...", "name": "...", "url": "..."}}</pre>
 *
 * <p>Returns 204 No Content if no pending SAML request is found in the session.</p>
 */
public class SpApiServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger log = LoggerFactory.getLogger(SpApiServlet.class);

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setContentType("application/json; charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");

		HttpSession session = request.getSession(false);
		if (session == null) {
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return;
		}

		String samlRequest = (String) session.getAttribute(LoginServlet.SESSION_SAML_REQUEST);
		if (samlRequest == null) {
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return;
		}

		String binding = (String) session.getAttribute(LoginServlet.SESSION_BINDING);
		String relayState = (String) session.getAttribute(LoginServlet.SESSION_RELAY_STATE);

		try {
			AuthnRequestParser parser = new AuthnRequestParser();
			AuthnRequest authnRequest;
			if ("POST".equals(binding)) {
				authnRequest = parser.parsePostBinding(samlRequest, relayState);
			} else {
				authnRequest = parser.parseRedirectBinding(samlRequest, relayState);
			}

			String entityId = authnRequest.getIssuer();
			IdpConfiguration config = Activator.getDefault().getConfiguration();
			IdpConfiguration.TrustedSP trustedSP = config.getTrustedSP(entityId);

			Map<String, Object> data = new HashMap<>();
			data.put("entityId", entityId);
			if (trustedSP != null && trustedSP.getDisplayName() != null) {
				data.put("name", trustedSP.getDisplayName());
				data.put("url", entityId);
			} else {
				data.put("name", entityId);
			}

			Map<String, Object> result = Map.of("status", "success", "data", data);
			response.setStatus(HttpServletResponse.SC_OK);
			PrintWriter out = response.getWriter();
			out.write(Activator.getDefault().toJSON(result));

		} catch (Exception e) {
			log.warn("Failed to parse SP info from session: {}", e.getMessage());
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
		}
	}

}

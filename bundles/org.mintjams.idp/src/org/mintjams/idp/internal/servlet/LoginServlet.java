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

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.IdpConfiguration;
import org.mintjams.idp.internal.model.IdpUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Login form servlet at {@code /idp/login}.
 *
 * <p>GET displays the login form. POST authenticates the user.
 * On successful authentication, the user is stored in the HTTP session
 * and redirected back to the SSO endpoint to complete the SAML flow.</p>
 *
 * <p>Session attributes used:</p>
 * <ul>
 *   <li>{@code idp.user} - the authenticated {@link IdpUser}</li>
 *   <li>{@code idp.samlRequest} - the original SAMLRequest parameter</li>
 *   <li>{@code idp.relayState} - the original RelayState parameter</li>
 * </ul>
 */
public class LoginServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(LoginServlet.class);

	static final String SESSION_USER = "idp.user";
	static final String SESSION_SAML_REQUEST = "idp.samlRequest";
	static final String SESSION_RELAY_STATE = "idp.relayState";
	static final String SESSION_BINDING = "idp.binding";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		renderLoginForm(response, null);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		IdpConfiguration config = Activator.getDefault().getConfiguration();

		String username = request.getParameter("username");
		String password = request.getParameter("password");

		if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
			renderLoginForm(response, "Username and password are required.");
			return;
		}

		IdpUser user = Activator.getDefault().getUserStore().authenticate(username, password);
		if (user == null) {
			LOG.warn("Authentication failed for user: {}", username);
			renderLoginForm(response, "Invalid username or password.");
			return;
		}

		LOG.info("User authenticated: {}", username);

		// Store user in session
		HttpSession session = request.getSession(true);
		session.setAttribute(SESSION_USER, user);
		session.setAttribute("idp.freshLogin", Boolean.TRUE);

		// Check if there's a pending SAML request
		String samlRequest = (String) session.getAttribute(SESSION_SAML_REQUEST);
		if (samlRequest != null) {
			// Redirect back to SSO endpoint to complete SAML flow
			String binding = (String) session.getAttribute(SESSION_BINDING);
			String relayState = (String) session.getAttribute(SESSION_RELAY_STATE);

			StringBuilder redirectUrl = new StringBuilder(config.getSsoPath());
			redirectUrl.append("?SAMLRequest=").append(java.net.URLEncoder.encode(samlRequest, "UTF-8"));
			if (relayState != null) {
				redirectUrl.append("&RelayState=").append(java.net.URLEncoder.encode(relayState, "UTF-8"));
			}
			if ("POST".equals(binding)) {
				redirectUrl.append("&binding=POST");
			}

			// Clean up session
			session.removeAttribute(SESSION_SAML_REQUEST);
			session.removeAttribute(SESSION_RELAY_STATE);
			session.removeAttribute(SESSION_BINDING);

			response.sendRedirect(redirectUrl.toString());
		} else {
			// No pending SAML request - just show success
			response.setContentType("text/html; charset=UTF-8");
			PrintWriter out = response.getWriter();
			out.println("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
			out.println("<title>MintJams IdP</title></head><body>");
			out.println("<h2>Logged in as: " + escapeHtml(username) + "</h2>");
			out.println("<p>No pending authentication request.</p>");
			out.println("</body></html>");
		}
	}

	private void renderLoginForm(HttpServletResponse response, String errorMessage) throws IOException {
		response.setContentType("text/html; charset=UTF-8");
		PrintWriter out = response.getWriter();

		out.println("<!DOCTYPE html>");
		out.println("<html><head>");
		out.println("<meta charset=\"UTF-8\">");
		out.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
		out.println("<title>Sign In - MintJams IdP</title>");
		out.println("<style>");
		out.println("* { margin: 0; padding: 0; box-sizing: border-box; }");
		out.println("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;");
		out.println("  background: #f5f5f5; display: flex; justify-content: center; align-items: center;");
		out.println("  min-height: 100vh; }");
		out.println(".login-card { background: #fff; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1);");
		out.println("  padding: 2rem; width: 100%; max-width: 380px; }");
		out.println(".login-card h1 { font-size: 1.4rem; color: #333; margin-bottom: 1.5rem; text-align: center; }");
		out.println(".form-group { margin-bottom: 1rem; }");
		out.println(".form-group label { display: block; font-size: 0.875rem; color: #555; margin-bottom: 0.25rem; }");
		out.println(".form-group input { width: 100%; padding: 0.6rem 0.75rem; border: 1px solid #ddd;");
		out.println("  border-radius: 4px; font-size: 1rem; }");
		out.println(".form-group input:focus { outline: none; border-color: #4a90d9; box-shadow: 0 0 0 2px rgba(74,144,217,0.2); }");
		out.println(".btn { width: 100%; padding: 0.7rem; background: #4a90d9; color: #fff; border: none;");
		out.println("  border-radius: 4px; font-size: 1rem; cursor: pointer; margin-top: 0.5rem; }");
		out.println(".btn:hover { background: #357abd; }");
		out.println(".error { background: #fee; color: #c33; border: 1px solid #fcc; border-radius: 4px;");
		out.println("  padding: 0.5rem 0.75rem; margin-bottom: 1rem; font-size: 0.875rem; }");
		out.println(".footer { text-align: center; margin-top: 1.5rem; font-size: 0.75rem; color: #999; }");
		out.println("</style>");
		out.println("</head><body>");
		out.println("<div class=\"login-card\">");
		out.println("<h1>Sign In</h1>");

		if (errorMessage != null) {
			out.println("<div class=\"error\">" + escapeHtml(errorMessage) + "</div>");
		}

		out.println("<form method=\"POST\">");
		out.println("<div class=\"form-group\">");
		out.println("<label for=\"username\">Username</label>");
		out.println("<input type=\"text\" id=\"username\" name=\"username\" autocomplete=\"username\" required autofocus>");
		out.println("</div>");
		out.println("<div class=\"form-group\">");
		out.println("<label for=\"password\">Password</label>");
		out.println("<input type=\"password\" id=\"password\" name=\"password\" autocomplete=\"current-password\" required>");
		out.println("</div>");
		out.println("<button type=\"submit\" class=\"btn\">Sign In</button>");
		out.println("</form>");
		out.println("<div class=\"footer\">MintJams Identity Provider</div>");
		out.println("</div>");
		out.println("</body></html>");
	}

	private static String escapeHtml(String s) {
		if (s == null) return "";
		return s.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;");
	}

}

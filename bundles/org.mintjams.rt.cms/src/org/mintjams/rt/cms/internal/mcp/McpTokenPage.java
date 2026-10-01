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

package org.mintjams.rt.cms.internal.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

import javax.jcr.Credentials;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2Credentials;

/**
 * {@code /bin/mcp.cgi/{workspace}/token}: where a signed-in user issues an
 * {@link McpAccessToken} for themselves.
 *
 * <p>The page is plain server-rendered HTML with no script, so that it works
 * before any Webtop application knows about MCP and carries nothing that could
 * read the token it displays. The token is shown once, in the response to the
 * form post, and is stored nowhere on the server.
 *
 * <p>Issuing is the one operation here that turns a browser login into a
 * long-lived credential, so it is guarded accordingly:
 *
 * <ul>
 * <li>only the browser login is accepted — a bearer token cannot mint another
 * token, so a leaked token dies at its own expiry;</li>
 * <li>the post must come from this page: same origin, and carrying the
 * per-session CSRF value the form was rendered with;</li>
 * <li>the lifetime is capped by {@code mcp.yml#token.maxTtl}, and the write
 * scope is offered only when {@code token.allowWrite} permits it;</li>
 * <li>every issue is logged with the user, the token id, the scope and the
 * expiry — never the token.</li>
 * </ul>
 */
final class McpTokenPage {

	private static final String CSRF_ATTRIBUTE = "org.mintjams.cms.mcp.TokenPageCsrf";
	private static final long DAY_SECONDS = 24L * 60 * 60;
	private static final SecureRandom fRandom = new SecureRandom();

	private final String fWorkspaceName;
	private final McpConfiguration fConfig;

	McpTokenPage(String workspaceName, McpConfiguration config) {
		fWorkspaceName = workspaceName;
		fConfig = config;
	}

	void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("X-Frame-Options", "DENY");
		// Not "no-referrer": under that policy a browser sends "Origin: null" with
		// the form post, and the post could not be told from a cross-site one.
		response.setHeader("Referrer-Policy", "same-origin");
		response.setHeader("Content-Security-Policy",
				"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'");

		String method = request.getMethod();
		boolean post = "POST".equalsIgnoreCase(method);
		if (!post && !"GET".equalsIgnoreCase(method)) {
			response.setHeader("Allow", "GET, POST");
			sendPage(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "<p>Method not allowed.</p>");
			return;
		}

		Credentials credentials = McpServlet.getBrowserCredentials(request);
		if (credentials == null) {
			sendPage(response, HttpServletResponse.SC_UNAUTHORIZED,
					"<p>You are not signed in. <a href=\"" + escape(CmsService.getConfiguration().getStartWebURI())
							+ "\">Sign in to the CMS</a> in this browser, then open this page again.</p>");
			return;
		}
		if (!(credentials instanceof Saml2Credentials)) {
			sendPage(response, HttpServletResponse.SC_FORBIDDEN,
					"<p>Access tokens can only be issued for an interactive sign-in.</p>");
			return;
		}
		Saml2Credentials user = (Saml2Credentials) credentials;

		if (!post) {
			sendPage(response, HttpServletResponse.SC_OK, form(request, user, null));
			return;
		}

		// ---- issue ----------------------------------------------------------
		if (!isSameOriginPost(request)) {
			sendPage(response, HttpServletResponse.SC_FORBIDDEN,
					"<p>This request did not come from the token page.</p>");
			return;
		}
		HttpSession session = request.getSession(false);
		Object expected = (session == null) ? null : session.getAttribute(CSRF_ATTRIBUTE);
		String presented = request.getParameter("csrf");
		if (!(expected instanceof String) || presented == null || !MessageDigest.isEqual(
				((String) expected).getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
			sendPage(response, HttpServletResponse.SC_FORBIDDEN,
					form(request, user, "The form had expired. Nothing was issued; please try again."));
			return;
		}

		String scope = McpAccessToken.SCOPE_WRITE.equals(request.getParameter("scope")) ? McpAccessToken.SCOPE_WRITE
				: McpAccessToken.SCOPE_READ;
		if (McpAccessToken.SCOPE_WRITE.equals(scope) && !fConfig.isWriteAllowed()) {
			sendPage(response, HttpServletResponse.SC_FORBIDDEN,
					form(request, user, "Write access tokens are disabled on this server. Nothing was issued."));
			return;
		}

		long ttlSeconds;
		try {
			ttlSeconds = Math.multiplyExact(Long.parseLong(request.getParameter("days").trim()), DAY_SECONDS);
		} catch (Throwable ex) {
			ttlSeconds = fConfig.getDefaultTokenTtlSeconds();
		}
		ttlSeconds = Math.max(1, Math.min(ttlSeconds, fConfig.getMaxTokenTtlSeconds()));

		McpAccessToken.Issued issued = McpAccessToken.issue(CmsService.getEncryptor(), user, fWorkspaceName, scope,
				ttlSeconds, System.currentTimeMillis());
		// One form, one token: a reload of the result page must not issue again.
		session.removeAttribute(CSRF_ATTRIBUTE);

		CmsService.getLogger(McpTokenPage.class).info("MCP access token issued: user=" + user.getName()
				+ " workspace=" + fWorkspaceName + " id=" + issued.getId() + " scope=" + issued.getScope()
				+ " expires=" + Instant.ofEpochMilli(issued.getExpiresMillis()));

		sendPage(response, HttpServletResponse.SC_OK, issued(request, user, issued));
	}

	/**
	 * Whether the form post was made by a page of this origin. The browser's
	 * own verdict ({@code Sec-Fetch-Site}, which page script cannot set) is
	 * authoritative when present; it is sent in secure contexts only, so
	 * otherwise the {@code Origin} header decides. {@code Origin} alone is not
	 * relied on when the verdict is available, because privacy settings and
	 * referrer policies legitimately turn it into {@code null}. A request with
	 * neither header comes from no browser and is left to the CSRF value.
	 */
	private static boolean isSameOriginPost(HttpServletRequest request) {
		String fetchSite = request.getHeader("Sec-Fetch-Site");
		if (fetchSite != null) {
			return "same-origin".equals(fetchSite);
		}
		String origin = request.getHeader("Origin");
		return origin == null || McpServlet.isOwnOrigin(request, origin);
	}

	private String form(HttpServletRequest request, Saml2Credentials user, String error) {
		HttpSession session = request.getSession();
		String csrf = (String) session.getAttribute(CSRF_ATTRIBUTE);
		if (csrf == null) {
			csrf = randomHex(16);
			session.setAttribute(CSRF_ATTRIBUTE, csrf);
		}

		long maxDays = Math.max(1, fConfig.getMaxTokenTtlSeconds() / DAY_SECONDS);
		long defaultDays = Math.max(1, Math.min(maxDays, fConfig.getDefaultTokenTtlSeconds() / DAY_SECONDS));

		StringBuilder sb = new StringBuilder();
		if (error != null) {
			sb.append("<p class=\"error\">").append(escape(error)).append("</p>");
		}
		sb.append("<p>Issue an access token that lets an MCP client (such as Claude) work in the workspace <b>")
				.append(escape(fWorkspaceName)).append("</b> as <b>").append(escape(user.getName()))
				.append("</b>. The client can do what you can do, limited by the scope below, until the token")
				.append(" expires or is revoked.</p>");
		sb.append("<form method=\"post\">");
		sb.append("<input type=\"hidden\" name=\"csrf\" value=\"").append(escape(csrf)).append("\">");
		sb.append("<fieldset><legend>Scope</legend>");
		sb.append("<label><input type=\"radio\" name=\"scope\" value=\"read\" checked> <b>Read</b> — browse, read,")
				.append(" search and diagnose. Nothing can be changed.</label>");
		if (fConfig.isWriteAllowed()) {
			sb.append("<label><input type=\"radio\" name=\"scope\" value=\"write\"> <b>Write</b> — also create,")
					.append(" overwrite, move and delete content, and run GraphQL mutations.</label>");
		}
		sb.append("</fieldset>");
		sb.append("<p><label>Valid for <input type=\"number\" name=\"days\" min=\"1\" max=\"").append(maxDays)
				.append("\" value=\"").append(defaultDays).append("\" required> day(s) (at most ").append(maxDays)
				.append(")</label></p>");
		sb.append("<p><button type=\"submit\">Issue token</button></p>");
		sb.append("</form>");
		return sb.toString();
	}

	private String issued(HttpServletRequest request, Saml2Credentials user, McpAccessToken.Issued issued) {
		String host = request.getHeader("Host");
		// A proxy may forward the Host with an explicit default port (host:443).
		String url = request.getScheme() + "://"
				+ McpServlet.stripDefaultPort((host != null) ? host : request.getServerName(), request.getScheme())
				+ CmsConfiguration.MCP_CGI_PATH + "/" + fWorkspaceName;
		String name = "cms-" + fWorkspaceName;

		StringBuilder sb = new StringBuilder();
		sb.append("<p class=\"ok\">Token issued for <b>").append(escape(user.getName())).append("</b> — scope <b>")
				.append(escape(issued.getScope())).append("</b>, expires <b>")
				.append(escape(Instant.ofEpochMilli(issued.getExpiresMillis()).toString())).append("</b>, id <code>")
				.append(escape(issued.getId())).append("</code>.</p>");
		sb.append("<p><b>Copy it now.</b> It is not stored and cannot be shown again. Treat it like a password:")
				.append(" anyone holding it can act as you within its scope.</p>");
		sb.append("<h2>Token</h2><textarea readonly rows=\"4\">").append(escape(issued.getToken()))
				.append("</textarea>");
		sb.append("<h2>Endpoint</h2><textarea readonly rows=\"1\">").append(escape(url)).append("</textarea>");
		sb.append("<h2>Claude Code</h2><textarea readonly rows=\"4\">")
				.append(escape("claude mcp add --transport http " + name + " " + url
						+ " --header \"Authorization: Bearer " + issued.getToken() + "\""))
				.append("</textarea>");
		sb.append("<h2>.mcp.json</h2><textarea readonly rows=\"10\">")
				.append(escape("{\n  \"mcpServers\": {\n    \"" + name + "\": {\n      \"type\": \"http\",\n"
						+ "      \"url\": \"" + url + "\",\n      \"headers\": {\n"
						+ "        \"Authorization\": \"Bearer " + issued.getToken() + "\"\n      }\n    }\n  }\n}"))
				.append("</textarea>");
		sb.append("<p>To revoke this token before it expires, an administrator adds its id to <code>token.revoked")
				.append("</code> in <code>etc/mcp.yml</code>.</p>");
		sb.append("<p><a href=\"\">Issue another token</a></p>");
		return sb.toString();
	}

	private void sendPage(HttpServletResponse response, int status, String body) throws IOException {
		response.setStatus(status);
		response.setContentType("text/html");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
				+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
				+ "<title>MCP access token</title><style>"
				+ "body{font:15px/1.5 system-ui,sans-serif;max-width:44rem;margin:2rem auto;padding:0 1rem;"
				+ "color:#222;background:#fff}"
				+ "h1{font-size:1.4rem}h2{font-size:1rem;margin:1.2rem 0 .3rem}"
				+ "fieldset{border:1px solid #ccc;border-radius:6px}label{display:block;margin:.4rem 0}"
				+ "textarea{width:100%;box-sizing:border-box;font:13px/1.4 ui-monospace,monospace;"
				+ "padding:.5rem;border:1px solid #ccc;border-radius:6px;resize:vertical}"
				+ "input[type=number]{width:5rem}button{font:inherit;padding:.4rem 1rem}"
				+ ".error{color:#a40000}.ok{background:#eef7ee;border:1px solid #9c9;border-radius:6px;padding:.6rem}"
				+ "@media (prefers-color-scheme:dark){body{color:#ddd;background:#1c1c1c}"
				+ "textarea,fieldset{border-color:#555;background:#262626;color:#ddd}"
				+ ".ok{background:#1f2f1f;border-color:#474}.error{color:#f88}a{color:#8bf}}"
				+ "</style></head><body><h1>MCP access token</h1>" + body + "</body></html>");
		response.getWriter().flush();
	}

	private static String randomHex(int bytes) {
		byte[] buffer = new byte[bytes];
		fRandom.nextBytes(buffer);
		StringBuilder sb = new StringBuilder();
		for (byte b : buffer) {
			sb.append(String.format("%02x", b & 0xff));
		}
		return sb.toString();
	}

	static String escape(String text) {
		if (text == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
			case '&':
				sb.append("&amp;");
				break;
			case '<':
				sb.append("&lt;");
				break;
			case '>':
				sb.append("&gt;");
				break;
			case '"':
				sb.append("&quot;");
				break;
			case '\'':
				sb.append("&#39;");
				break;
			default:
				sb.append(c);
			}
		}
		return sb.toString();
	}

}

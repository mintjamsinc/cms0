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

package org.mintjams.rt.cms.internal.connections;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.jcr.Credentials;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.mcp.McpSealed;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2Credentials;
import org.mintjams.rt.cms.internal.web.Webs;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

import com.google.gson.JsonObject;

/**
 * Links a Google or Microsoft account to the signed-in user, and grants a
 * bundle to a linked account: the browser half of OAuth 2.0 with PKCE, as a
 * confidential client.
 *
 * <pre>
 * GET {@value #PATH}/authorize?provider=google&amp;bundle=mail&amp;return=/...
 *     redirects to the provider's consent screen
 * GET {@value #PATH}/callback?code=...&amp;state=...
 *     redeems the code, stores the grant, tells the opener and closes
 * </pre>
 *
 * <p>The authorize step asks for the identity scopes and, when a bundle is
 * named, that bundle's scopes: an app asks for what it needs when it needs
 * it, never for everything at once (incremental authorization). Nothing is
 * kept for the flow itself: the user, provider, bundle, PKCE verifier and
 * return path travel in the sealed {@code state}, so any node can take the
 * callback.
 *
 * <p>The callback page is meant for a popup: it posts
 * {@code {type: "connection", ok, provider, bundle}} on the
 * {@code webtop-connections} BroadcastChannel and closes itself; opened as a
 * page, it shows the result and a link back.
 *
 * <p>The redirect URI the administrator registers with the provider is
 * {@code <CMS_PUBLIC_BASE_URL>}{@value #PATH}/callback.
 */
@Component(service = Servlet.class, property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + ConnectionsServlet.PATH + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
				+ HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=org.osgi.service.http)" })
public class ConnectionsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	public static final String PATH = "/bin/auth.cgi/oauth";
	public static final String AUTHORIZE = "authorize";
	public static final String CALLBACK = "callback";
	public static final String CHANNEL = "webtop-connections";

	private static final String LOGIN_PATH = "/bin/auth.cgi/saml2/login";
	private static final String ENV_PUBLIC_BASE_URL = "CMS_PUBLIC_BASE_URL";

	private static final String STATE_PREFIX = "cx_";
	private static final String STATE_TYPE = "connection-state";
	private static final long STATE_TTL_MILLIS = 10L * 60 * 1000;

	private static final SecureRandom fRandom = new SecureRandom();

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Referrer-Policy", "no-referrer");
		String pathInfo = Webs.getEffectivePathInfo(request);
		String action = (pathInfo == null) ? "" : pathInfo.replaceAll("^/+|/+$", "");
		Messages m = new Messages(request);
		try {
			if (AUTHORIZE.equals(action)) {
				authorize(request, response, m);
			} else if (CALLBACK.equals(action)) {
				callback(request, response, m);
			} else {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			}
		} catch (IOException | ServletException ex) {
			throw ex;
		} catch (Throwable ex) {
			CmsService.getLogger(ConnectionsServlet.class).error("The account link failed.", ex);
			result(response, m, null, null, null, false,
					m.get("The account could not be linked because of an error on the server.",
							"サーバーのエラーのため、アカウントを連携できませんでした。"));
		}
	}

	// ---- authorize ---------------------------------------------------------------

	private void authorize(HttpServletRequest request, HttpServletResponse response, Messages m)
			throws Exception {
		String providerId = request.getParameter("provider");
		String bundle = blankToNull(request.getParameter("bundle"));
		String returnPath = safeReturnPath(request.getParameter("return"));

		ConnectionProviders.Provider provider = ConnectionProviders.get(providerId);
		if (provider == null) {
			result(response, m, providerId, bundle, returnPath, false,
					m.get("Unknown provider.", "不明なプロバイダーです。"));
			return;
		}
		if (bundle != null && !ConnectionProviders.isBundle(bundle)) {
			result(response, m, providerId, bundle, returnPath, false,
					m.get("Unknown permission bundle.", "不明な権限の束です。"));
			return;
		}
		ConnectionClients.Client client = ConnectionClients.get().get(providerId);
		if (client == null) {
			result(response, m, providerId, bundle, returnPath, false,
					m.format("Linking a {0} account is not enabled on this server.",
							"このサーバーでは {0} アカウントの連携が有効になっていません。", escape(provider.getDisplayName())));
			return;
		}

		Credentials credentials = Webs.getCredentials(request);
		if (credentials == null) {
			// Sign in, then come back to this request.
			response.sendRedirect(LOGIN_PATH + "?RelayState=" + encode(selfUrl(request)));
			return;
		}
		if (!(credentials instanceof Saml2Credentials)) {
			result(response, m, providerId, bundle, returnPath, false,
					m.get("An account can only be linked from an interactive sign-in.",
							"アカウントの連携は、対話的なサインインからのみ行えます。"));
			return;
		}
		String userId = ((Saml2Credentials) credentials).getName();

		// The account linked so far, if any: the provider is asked to show the same one.
		LinkedAccounts.Account existing = LinkedAccounts.get(userId, providerId);

		String verifier = randomUrlSafe(32);
		JsonObject state = new JsonObject();
		state.addProperty("u", userId);
		state.addProperty("p", providerId);
		if (bundle != null) {
			state.addProperty("b", bundle);
		}
		state.addProperty("r", returnPath);
		state.addProperty("v", verifier);
		state.addProperty("n", randomUrlSafe(16));
		state.addProperty("x", System.currentTimeMillis() + STATE_TTL_MILLIS);

		Map<String, String> params = new LinkedHashMap<>();
		params.put("client_id", client.getClientId());
		params.put("redirect_uri", callbackUrl(request));
		params.put("response_type", "code");
		params.put("scope", String.join(" ", ConnectionTokens.scopesFor(provider, bundle)));
		params.put("state", McpSealed.seal(CmsService.getEncryptor(), STATE_PREFIX, STATE_TYPE, state));
		params.put("code_challenge", challengeOf(verifier));
		params.put("code_challenge_method", "S256");
		params.putAll(provider.getAuthorizeParameters());
		if (existing != null && existing.getEmail() != null) {
			params.put("login_hint", existing.getEmail());
		}

		StringBuilder url = new StringBuilder(client.getAuthorizationEndpoint());
		url.append(url.indexOf("?") >= 0 ? '&' : '?');
		boolean first = true;
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (!first) {
				url.append('&');
			}
			first = false;
			url.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
		}
		response.sendRedirect(url.toString());
	}

	// ---- callback ----------------------------------------------------------------

	private void callback(HttpServletRequest request, HttpServletResponse response, Messages m) throws Exception {
		JsonObject state = McpSealed.open(CmsService.getEncryptor(), STATE_PREFIX, STATE_TYPE,
				request.getParameter("state"));
		if (state == null || !state.has("x") || state.get("x").getAsLong() < System.currentTimeMillis()) {
			result(response, m, null, null, null, false,
					m.get("This link request is no longer valid. Start again from Preferences.",
							"この連携の要求は無効になりました。環境設定からやり直してください。"));
			return;
		}
		String userId = state.get("u").getAsString();
		String providerId = state.get("p").getAsString();
		String bundle = state.has("b") ? state.get("b").getAsString() : null;
		String returnPath = state.has("r") ? state.get("r").getAsString() : "/";
		ConnectionProviders.Provider provider = ConnectionProviders.get(providerId);
		ConnectionClients.Client client = ConnectionClients.get().get(providerId);
		if (provider == null || client == null) {
			result(response, m, providerId, bundle, returnPath, false,
					m.get("The provider is no longer configured on this server.",
							"このプロバイダーは、このサーバーで設定されていません。"));
			return;
		}

		// The browser that comes back must be the one that started, signed in as the same user.
		Credentials credentials = Webs.getCredentials(request);
		String currentUser = (credentials instanceof Saml2Credentials) ? ((Saml2Credentials) credentials).getName()
				: null;
		if (currentUser == null || !currentUser.equals(userId)) {
			result(response, m, providerId, bundle, returnPath, false,
					m.get("You are not signed in as the user who started this link. Sign in and start again.",
							"この連携を始めた利用者としてサインインしていません。サインインしてやり直してください。"));
			return;
		}

		String error = request.getParameter("error");
		if (error != null) {
			String description = request.getParameter("error_description");
			boolean declined = "access_denied".equals(error) || "consent_required".equals(error)
					|| "interaction_required".equals(error);
			result(response, m, providerId, bundle, returnPath, false, declined
					? m.get("The request was not approved.", "承認されませんでした。")
					: m.format("{0} reported an error: {1}", "{0} からエラーが返されました: {1}",
							escape(provider.getDisplayName()),
							escape(error + ((description != null && !description.isBlank()) ? " (" + description + ")" : ""))));
			return;
		}
		String code = request.getParameter("code");
		if (code == null || code.isEmpty()) {
			result(response, m, providerId, bundle, returnPath, false,
					m.get("No authorization code was returned.", "認可コードが返されませんでした。"));
			return;
		}

		ConnectionTokens.Tokens tokens;
		try {
			tokens = ConnectionTokens.exchangeCode(client, code, callbackUrl(request), state.get("v").getAsString());
		} catch (IOException ex) {
			CmsService.getLogger(ConnectionsServlet.class).warn("The {} code could not be redeemed for {}: {}",
					providerId, userId, ex.getMessage());
			result(response, m, providerId, bundle, returnPath, false,
					m.format("{0} did not issue a token.", "{0} からトークンが発行されませんでした。",
							escape(provider.getDisplayName())));
			return;
		}

		// Who the account is: the ID token, or the userinfo endpoint.
		JsonObject claims = ConnectionTokens.idTokenClaims(tokens.getIdToken());
		if (claims == null || !claims.has("sub")) {
			claims = ConnectionTokens.userinfo(client, tokens.getAccessToken());
		}
		String subject = claims.has("sub") ? claims.get("sub").getAsString() : null;
		if (subject == null || subject.isEmpty()) {
			result(response, m, providerId, bundle, returnPath, false,
					m.format("{0} did not say which account was used.", "{0} からアカウントの識別子が返されませんでした。",
							escape(provider.getDisplayName())));
			return;
		}
		String email = optional(claims, "email");
		if (email == null) {
			email = optional(claims, "preferred_username");
		}
		String displayName = optional(claims, "name");

		LinkedAccounts.Account existing = LinkedAccounts.get(userId, providerId);
		if (existing != null && !subject.equals(existing.getSubject())) {
			// One account per provider: a different one was chosen at the consent screen.
			result(response, m, providerId, bundle, returnPath, false,
					m.format("A different {0} account is already linked ({1}). Unlink it first to use another one.",
							"別の {0} アカウント（{1}）がすでに連携されています。別のアカウントを使うには、先に連携を解除してください。",
							escape(provider.getDisplayName()), escape(existing.getEmail() != null ? existing.getEmail() : existing.getSubject())));
			return;
		}

		Set<String> bundles = new LinkedHashSet<>();
		if (existing != null) {
			bundles.addAll(existing.getBundles());
		}
		if (bundle != null) {
			bundles.add(bundle);
		}
		// What the provider says was granted, when it says: Google lists every
		// scope the account has granted this client, so bundles granted before
		// and still in force are picked up as well.
		List<String> granted = tokens.getScopes();
		if (!granted.isEmpty()) {
			bundles.addAll(provider.bundlesGrantedBy(granted));
		}

		String refreshToken = tokens.getRefreshToken();
		if (refreshToken == null && existing == null) {
			result(response, m, providerId, bundle, returnPath, false,
					m.format("{0} did not issue a refresh token, so the link cannot be kept. Check that the client is registered as a web application.",
							"{0} からリフレッシュトークンが発行されなかったため、連携を保持できません。クライアントが Web アプリケーションとして登録されているか確認してください。",
							escape(provider.getDisplayName())));
			return;
		}

		long now = System.currentTimeMillis();
		LinkedAccounts.Account account = new LinkedAccounts.Account(providerId, subject, email,
				displayName, bundles, (existing != null) ? existing.getLinkedAtMillis() : now,
				(existing != null) ? existing.getLastUsedMillis() : 0, false);
		LinkedAccounts.save(userId, account, refreshToken);
		ConnectionTokens.invalidate(userId, providerId);
		CmsService.getLogger(ConnectionsServlet.class).info("{} account linked: user={} bundles={}", providerId,
				userId, String.join(",", bundles));

		result(response, m, providerId, bundle, returnPath, true, null);
	}

	// ---- the result page ---------------------------------------------------------

	/**
	 * The page the popup ends on: it tells the opener over the BroadcastChannel
	 * and closes; opened as a page, it shows the result and a link back.
	 */
	private static void result(HttpServletResponse response, Messages m, String provider, String bundle,
			String returnPath, boolean ok, String message) throws IOException {
		JsonObject event = new JsonObject();
		event.addProperty("type", "connection");
		event.addProperty("ok", ok);
		event.addProperty("provider", provider);
		event.addProperty("bundle", bundle);
		if (message != null) {
			event.addProperty("error", unescapeForJson(message));
		}
		String title = ok ? m.get("Account linked", "アカウントを連携しました")
				: m.get("The account was not linked", "アカウントは連携されませんでした");
		String body = ok ? m.get("You can close this window.", "このウィンドウは閉じてかまいません。") : message;
		String back = (returnPath != null) ? returnPath : "/";

		StringBuilder html = new StringBuilder();
		html.append("<!doctype html><html lang=\"").append(m.getLanguage()).append("\"><head><meta charset=\"utf-8\">");
		html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
		html.append("<title>").append(escape(title)).append("</title>");
		html.append("<style>body{font-family:system-ui,sans-serif;margin:0;padding:3rem 1.5rem;color:#222;background:#f6f6f6}");
		html.append("main{max-width:28rem;margin:0 auto;background:#fff;border-radius:12px;padding:1.5rem 2rem;box-shadow:0 1px 4px rgba(0,0,0,.08)}");
		html.append("h1{font-size:1.1rem;margin:0 0 .75rem}p{margin:.5rem 0;line-height:1.5}.error{color:#b3261e}");
		html.append("a.button{display:inline-block;margin-top:1rem;padding:.4rem 1rem;border-radius:6px;background:#1a73e8;color:#fff;text-decoration:none}</style>");
		html.append("</head><body><main><h1>").append(escape(title)).append("</h1>");
		html.append("<p").append(ok ? "" : " class=\"error\"").append(">").append(body).append("</p>");
		html.append("<p><a class=\"button\" href=\"").append(escape(back)).append("\">")
				.append(escape(m.get("Back", "戻る"))).append("</a></p></main>");
		html.append("<script>(function(){try{var c=new BroadcastChannel(").append(jsString(CHANNEL))
				.append(");c.postMessage(").append(event.toString()).append(");c.close();}catch(e){}")
				.append("if(window.opener||window.history.length<=1){setTimeout(function(){window.close();},")
				.append(ok ? "400" : "4000").append(");}})();</script>");
		html.append("</body></html>");

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/html; charset=UTF-8");
		response.getWriter().write(html.toString());
	}

	// ---- helpers -----------------------------------------------------------------

	/** The public URL of the callback: what the administrator registered with the provider. */
	static String callbackUrl(HttpServletRequest request) {
		return publicBaseUrl(request) + PATH + "/" + CALLBACK;
	}

	/** {@code CMS_PUBLIC_BASE_URL} when set, else the origin this request was addressed to. */
	static String publicBaseUrl(HttpServletRequest request) {
		String base = System.getenv(ENV_PUBLIC_BASE_URL);
		if (base == null || base.isBlank()) {
			String host = request.getHeader("Host");
			base = request.getScheme() + "://" + ((host != null && !host.isEmpty()) ? host : request.getServerName());
		}
		return base.replaceAll("/+$", "");
	}

	private static String selfUrl(HttpServletRequest request) {
		String query = request.getQueryString();
		return request.getRequestURI() + ((query != null) ? "?" + query : "");
	}

	/** A path on this origin to return to; anything else becomes the root. */
	private static String safeReturnPath(String value) {
		if (value == null || value.isEmpty() || !value.startsWith("/") || value.startsWith("//")
				|| value.contains("\\") || value.contains("\r") || value.contains("\n")) {
			return "/";
		}
		return value;
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	private static String optional(JsonObject json, String name) {
		return (json.has(name) && !json.get(name).isJsonNull()) ? json.get(name).getAsString() : null;
	}

	private static String challengeOf(String verifier) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

	private static String randomUrlSafe(int bytes) {
		byte[] b = new byte[bytes];
		fRandom.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	static String escape(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '<': sb.append("&lt;"); break;
			case '>': sb.append("&gt;"); break;
			case '&': sb.append("&amp;"); break;
			case '"': sb.append("&quot;"); break;
			case '\'': sb.append("&#39;"); break;
			default: sb.append(c);
			}
		}
		return sb.toString();
	}

	/** The message texts are built escaped for HTML; the event wants them plain. */
	private static String unescapeForJson(String html) {
		return html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
				.replace("&amp;", "&");
	}

	private static String jsString(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\"";
	}

	/**
	 * The two languages the result page speaks. It renders before any Webtop
	 * code runs, so it goes by the browser's preferred language.
	 */
	private static final class Messages {
		private final boolean fJapanese;

		private Messages(HttpServletRequest request) {
			String language = request.getHeader("Accept-Language");
			fJapanese = language != null && language.trim().toLowerCase(Locale.ROOT).startsWith("ja");
		}

		private String getLanguage() {
			return fJapanese ? "ja" : "en";
		}

		private String get(String english, String japanese) {
			return escape(fJapanese ? japanese : english);
		}

		/** A message with {@code {0}}, {@code {1}} placeholders; the arguments must already be escaped. */
		private String format(String english, String japanese, String... arguments) {
			String message = get(english, japanese);
			for (int i = 0; i < arguments.length; i++) {
				message = message.replace("{" + i + "}", arguments[i]);
			}
			return message;
		}
	}

}

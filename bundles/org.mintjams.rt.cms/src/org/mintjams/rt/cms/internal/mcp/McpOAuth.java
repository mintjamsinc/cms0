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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.Credentials;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.mintjams.cms.security.Encryptor;
import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.auth.saml2.Saml2Credentials;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The authorization flow that connects an MCP client to a workspace without
 * the user ever handling a token: OAuth 2.1 with PKCE, as the MCP
 * authorization specification lays it out.
 *
 * <pre>
 * GET  {endpoint}/.well-known/oauth-protected-resource     where to authorize
 * GET  {endpoint}/.well-known/oauth-authorization-server   how to authorize
 * POST {endpoint}/register                                 the client introduces itself
 * GET  {endpoint}/authorize                                the user approves, in the browser
 * POST {endpoint}/token                                    the client collects, then renews, its token
 * </pre>
 *
 * <p>Each workspace endpoint is its own authorization server, so the workspace
 * a client is authorized for is the one in the URL it connected to.
 *
 * <p>The user approves in a browser that is signed in to the CMS, and only
 * while they have their MCP connection to the workspace turned on in
 * Preferences (see {@link McpConnections}). From then on the client works as
 * that user until the connection is turned off — signing out of the browser
 * does not stop it.
 *
 * <p>Nothing is stored for the flow itself. A client registration, an
 * authorization code and a refresh token are each a sealed value (see
 * {@link McpSealed}) that carries what it stands for, so any cluster node can
 * honour what another node issued. The one thing kept in memory is the list of
 * authorization codes this node has redeemed in the last minute, to refuse a
 * second use; PKCE is what protects a code redeemed on another node.
 *
 * <p>Clients are public (no client secret) and anyone may register one, as
 * dynamic registration requires. A registration therefore proves nothing about
 * who the client is: the approval page shows where the authorization will be
 * delivered, and that address is fixed at registration.
 */
final class McpOAuth {

	static final String AUTHORIZE_PATH = "authorize";
	static final String TOKEN_PATH = "token";
	static final String REGISTER_PATH = "register";
	static final String WELL_KNOWN = ".well-known";
	static final String PROTECTED_RESOURCE = "oauth-protected-resource";
	static final String AUTHORIZATION_SERVER = "oauth-authorization-server";
	static final String OPENID_CONFIGURATION = "openid-configuration";

	private static final String CLIENT_PREFIX = "mjmcpc_";
	private static final String CLIENT_TYPE = "mcp-client";
	private static final String CODE_PREFIX = "mjmcpa_";
	private static final String CODE_TYPE = "mcp-code";
	private static final String REFRESH_PREFIX = "mjmcpr_";
	private static final String REFRESH_TYPE = "mcp-refresh";

	private static final String SCOPE_READ = "read";
	private static final String SCOPE_WRITE = "write";

	/** Where the browser signs in; the same address Webtop sends it to. */
	private static final String LOGIN_PATH = "/bin/auth.cgi/saml2/login";

	private static final String CSRF_ATTRIBUTE = "org.mintjams.cms.mcp.AuthorizeCsrf";
	private static final long CODE_TTL_MILLIS = 60L * 1000;
	private static final int MAX_BODY_BYTES = 64 * 1024;
	private static final int MAX_REDIRECT_URIS = 10;
	private static final int MAX_REDIRECT_URI_LENGTH = 2000;
	private static final int MAX_CLIENT_NAME_LENGTH = 100;

	/** The parameters of an authorization request, carried from the approval page back to it. */
	private static final String[] AUTHORIZE_PARAMETERS = { "response_type", "client_id", "redirect_uri",
			"code_challenge", "code_challenge_method", "state", "scope", "resource" };

	private static final SecureRandom fRandom = new SecureRandom();

	/** Authorization codes redeemed on this node, by id, until they would have expired anyway. */
	private static final Map<String, Long> fRedeemedCodes = new ConcurrentHashMap<>();

	private final String fWorkspaceName;
	private final McpConfiguration fConfig;

	McpOAuth(String workspaceName, McpConfiguration config) {
		fWorkspaceName = workspaceName;
		fConfig = config;
	}

	/**
	 * The address clients know the workspace's MCP endpoint by. It is also the
	 * identifier of its authorization server.
	 */
	static String endpointUrl(HttpServletRequest request, String workspaceName) {
		return origin(request) + CmsConfiguration.MCP_CGI_PATH + "/" + workspaceName;
	}

	static String origin(HttpServletRequest request) {
		String host = request.getHeader("Host");
		// A proxy may forward the Host with an explicit default port (host:443).
		return request.getScheme() + "://"
				+ McpServlet.stripDefaultPort((host != null) ? host : request.getServerName(), request.getScheme());
	}

	/** The {@code WWW-Authenticate} challenge that tells a client where to start. */
	static String challenge(HttpServletRequest request, String workspaceName, boolean invalidToken) {
		return "Bearer realm=\"MintJams CMS MCP\"" + (invalidToken ? ", error=\"invalid_token\"" : "")
				+ ", resource_metadata=\"" + endpointUrl(request, workspaceName) + "/" + WELL_KNOWN + "/"
				+ PROTECTED_RESOURCE + "\"";
	}

	// ---- discovery ------------------------------------------------------------

	void protectedResourceMetadata(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!allowCrossOriginRead(request, response, "GET")) {
			return;
		}
		String endpoint = endpointUrl(request, fWorkspaceName);
		JsonObject json = new JsonObject();
		json.addProperty("resource", endpoint);
		JsonArray servers = new JsonArray();
		servers.add(endpoint);
		json.add("authorization_servers", servers);
		json.add("scopes_supported", scopes(fConfig.isWriteAllowed()));
		JsonArray methods = new JsonArray();
		methods.add("header");
		json.add("bearer_methods_supported", methods);
		json.addProperty("resource_name", "MintJams CMS (" + serverLabel(request, fConfig) + ") — " + fWorkspaceName);
		sendJson(response, HttpServletResponse.SC_OK, json);
	}

	void authorizationServerMetadata(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!allowCrossOriginRead(request, response, "GET")) {
			return;
		}
		String endpoint = endpointUrl(request, fWorkspaceName);
		JsonObject json = new JsonObject();
		json.addProperty("issuer", endpoint);
		json.addProperty("authorization_endpoint", endpoint + "/" + AUTHORIZE_PATH);
		json.addProperty("token_endpoint", endpoint + "/" + TOKEN_PATH);
		json.addProperty("registration_endpoint", endpoint + "/" + REGISTER_PATH);
		json.add("response_types_supported", array("code"));
		json.add("grant_types_supported", array("authorization_code", "refresh_token"));
		json.add("code_challenge_methods_supported", array("S256"));
		json.add("token_endpoint_auth_methods_supported", array("none"));
		json.add("scopes_supported", scopes(fConfig.isWriteAllowed()));
		json.addProperty("authorization_response_iss_parameter_supported", true);
		sendJson(response, HttpServletResponse.SC_OK, json);
	}

	/**
	 * The label that tells this server from the user's others: the configured
	 * {@code serverName}, or the host name the client connects to.
	 */
	static String serverLabel(HttpServletRequest request, McpConfiguration config) {
		if (config.getServerName() != null) {
			return config.getServerName();
		}
		String host = request.getHeader("Host");
		if (host == null || host.isEmpty()) {
			host = request.getServerName();
		}
		return McpServlet.stripDefaultPort(host, request.getScheme());
	}

	// ---- registration ---------------------------------------------------------

	void register(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!allowCrossOriginRead(request, response, "POST")) {
			return;
		}

		JsonObject metadata;
		try {
			byte[] body = readBody(request.getInputStream());
			metadata = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Throwable ex) {
			sendOAuthError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_client_metadata",
					"The body must be a JSON object of at most " + MAX_BODY_BYTES + " bytes.");
			return;
		}

		List<String> redirectUris = new ArrayList<>();
		try {
			for (JsonElement e : metadata.getAsJsonArray("redirect_uris")) {
				redirectUris.add(e.getAsString());
			}
		} catch (Throwable ex) {
			redirectUris.clear();
		}
		if (redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECT_URIS) {
			sendOAuthError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_redirect_uri",
					"redirect_uris must list between 1 and " + MAX_REDIRECT_URIS + " URIs.");
			return;
		}
		for (String redirectUri : redirectUris) {
			if (redirectTarget(redirectUri) == null) {
				sendOAuthError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_redirect_uri",
						"A redirect URI must be https, http on the loopback interface, or an application's own "
								+ "scheme, and must not carry a fragment.");
				return;
			}
		}

		String name = "";
		try {
			name = metadata.get("client_name").getAsString().trim();
		} catch (Throwable ignore) {}
		if (name.isEmpty()) {
			name = "MCP client";
		}
		if (name.length() > MAX_CLIENT_NAME_LENGTH) {
			name = name.substring(0, MAX_CLIENT_NAME_LENGTH);
		}

		long now = System.currentTimeMillis();
		JsonArray uris = new JsonArray();
		for (String redirectUri : redirectUris) {
			uris.add(redirectUri);
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("name", name);
		payload.add("uris", uris);
		payload.addProperty("iat", now);

		JsonObject json = new JsonObject();
		json.addProperty("client_id", McpSealed.seal(CmsService.getEncryptor(), CLIENT_PREFIX, CLIENT_TYPE, payload));
		json.addProperty("client_id_issued_at", now / 1000);
		json.addProperty("client_name", name);
		json.add("redirect_uris", uris);
		json.add("grant_types", array("authorization_code", "refresh_token"));
		json.add("response_types", array("code"));
		json.addProperty("token_endpoint_auth_method", "none");
		sendJson(response, HttpServletResponse.SC_CREATED, json);
	}

	// ---- authorization --------------------------------------------------------

	void authorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setHeader("X-Frame-Options", "DENY");
		// Not "no-referrer": under that policy a browser sends "Origin: null" with
		// the form post, and the post could not be told from a cross-site one.
		response.setHeader("Referrer-Policy", "same-origin");

		Messages m = new Messages(request);
		String method = request.getMethod();
		boolean post = "POST".equalsIgnoreCase(method);
		if (!post && !"GET".equalsIgnoreCase(method)) {
			response.setHeader("Allow", "GET, POST");
			sendPage(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, m, null, "<p>Method not allowed.</p>");
			return;
		}

		// Until the client and its redirect URI are known to belong together,
		// nothing is sent to the redirect URI: the user is told instead.
		Client client = openClient(request.getParameter("client_id"));
		String redirectUri = request.getParameter("redirect_uri");
		if (client == null || redirectUri == null || !client.fRedirectUris.contains(redirectUri)) {
			sendPage(response, HttpServletResponse.SC_BAD_REQUEST, m, null,
					"<p class=\"error\">" + escape(m.get("This authorization request is not valid. Start the "
							+ "connection again from the client.", "この認可リクエストは無効です。クライアントから接続を"
							+ "やり直してください。")) + "</p>");
			return;
		}
		String state = request.getParameter("state");
		String issuer = endpointUrl(request, fWorkspaceName);

		if (!"code".equals(request.getParameter("response_type"))) {
			redirectError(response, redirectUri, state, issuer, "unsupported_response_type",
					"response_type must be code.");
			return;
		}
		String codeChallenge = request.getParameter("code_challenge");
		if (codeChallenge == null || codeChallenge.isEmpty()
				|| !"S256".equals(request.getParameter("code_challenge_method"))) {
			redirectError(response, redirectUri, state, issuer, "invalid_request",
					"PKCE is required: send code_challenge with code_challenge_method=S256.");
			return;
		}
		String resource = request.getParameter("resource");
		if (resource != null && !resource.isEmpty() && !sameEndpoint(resource, issuer)) {
			redirectError(response, redirectUri, state, issuer, "invalid_target",
					"This authorization server issues tokens for " + issuer + " only.");
			return;
		}

		Credentials credentials = McpServlet.getBrowserCredentials(request);
		if (credentials == null) {
			// Sign in, then come back to this request.
			response.sendRedirect(LOGIN_PATH + "?RelayState=" + encode(authorizeUrl(request, issuer)));
			return;
		}
		if (!(credentials instanceof Saml2Credentials)) {
			sendPage(response, HttpServletResponse.SC_FORBIDDEN, m, null,
					"<p class=\"error\">" + escape(m.get("A client can only be authorized from an interactive sign-in.",
							"クライアントの認可は、対話的なサインインからのみ行えます。")) + "</p>");
			return;
		}
		Saml2Credentials user = (Saml2Credentials) credentials;

		McpConnections.Connection connection;
		try {
			connection = McpConnections.get(fWorkspaceName, user.getName());
		} catch (Throwable ex) {
			CmsService.getLogger(McpOAuth.class).error("Could not read the MCP connection of " + user.getName(), ex);
			redirectError(response, redirectUri, state, issuer, "server_error", "The connection could not be read.");
			return;
		}
		if (!connection.isEnabled()) {
			sendPage(response, HttpServletResponse.SC_OK, m, null,
					"<p>" + m.format("The MCP connection of <b>{0}</b> to the workspace <b>{1}</b> is off.",
							"<b>{0}</b> のワークスペース <b>{1}</b> への MCP 接続はオフになっています。",
							escape(user.getName()), escape(fWorkspaceName)) + "</p>"
							+ "<p>" + escape(m.get("Turn it on in Preferences › MCP, then try again.",
									"「環境設定」›「MCP」でオンにしてから、もう一度お試しください。")) + "</p>"
							+ "<p><a class=\"button\" href=\"" + escape(authorizeUrl(request, issuer)) + "\">"
							+ escape(m.get("Try again", "再試行")) + "</a></p>");
			return;
		}

		if (!post) {
			sendPage(response, HttpServletResponse.SC_OK, m, redirectUri,
					approvalForm(request, m, user, client, redirectUri, connection, null));
			return;
		}

		// ---- decision ---------------------------------------------------------
		if (!isSameOriginPost(request)) {
			sendPage(response, HttpServletResponse.SC_FORBIDDEN, m, null,
					"<p class=\"error\">This request did not come from the approval page.</p>");
			return;
		}
		HttpSession session = request.getSession(false);
		Object expected = (session == null) ? null : session.getAttribute(CSRF_ATTRIBUTE);
		String presented = request.getParameter("csrf");
		if (!(expected instanceof String) || presented == null || !MessageDigest.isEqual(
				((String) expected).getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
			sendPage(response, HttpServletResponse.SC_FORBIDDEN, m, redirectUri,
					approvalForm(request, m, user, client, redirectUri, connection,
							m.get("The page had expired. Nothing was authorized; please try again.",
									"ページの有効期限が切れました。認可は行われていません。もう一度お試しください。")));
			return;
		}
		// One page, one decision: a reload of the redirect must not approve again.
		session.removeAttribute(CSRF_ATTRIBUTE);

		if (!"approve".equals(request.getParameter("decision"))) {
			redirectError(response, redirectUri, state, issuer, "access_denied", "The user declined.");
			return;
		}

		long now = System.currentTimeMillis();
		String target = redirectTarget(redirectUri);
		JsonObject payload = new JsonObject();
		payload.addProperty("jti", randomHex(12));
		McpSealed.putIdentity(payload, user);
		payload.addProperty("ws", fWorkspaceName);
		payload.addProperty("cid", fingerprint(client.fId));
		payload.addProperty("ru", redirectUri);
		payload.addProperty("cc", codeChallenge);
		payload.addProperty("gen", connection.getGeneration());
		payload.addProperty("exp", now + CODE_TTL_MILLIS);
		String code = McpSealed.seal(CmsService.getEncryptor(), CODE_PREFIX, CODE_TYPE, payload);

		try {
			McpConnections.addClient(fWorkspaceName, user.getName(), connection.getGeneration(),
					new McpConnections.Client(client.fName, target, now));
		} catch (Throwable ex) {
			// The list is there for the user to look at; the authorization does not depend on it.
			CmsService.getLogger(McpOAuth.class).warn("Could not record the MCP client of " + user.getName(), ex);
		}
		CmsService.getLogger(McpOAuth.class).info("MCP client authorized: user=" + user.getName() + " workspace="
				+ fWorkspaceName + " client=" + client.fName + " redirect=" + target);

		StringBuilder location = new StringBuilder(redirectUri);
		appendParameter(location, "code", code);
		if (state != null) {
			appendParameter(location, "state", state);
		}
		appendParameter(location, "iss", issuer);
		response.sendRedirect(location.toString());
	}

	private String approvalForm(HttpServletRequest request, Messages m, Saml2Credentials user, Client client,
			String redirectUri, McpConnections.Connection connection, String error) {
		HttpSession session = request.getSession();
		String csrf = (String) session.getAttribute(CSRF_ATTRIBUTE);
		if (csrf == null) {
			csrf = randomHex(16);
			session.setAttribute(CSRF_ATTRIBUTE, csrf);
		}
		boolean write = connection.isWrite() && fConfig.isWriteAllowed();

		StringBuilder sb = new StringBuilder();
		if (error != null) {
			sb.append("<p class=\"error\">").append(escape(error)).append("</p>");
		}
		sb.append("<p>").append(m.format("<b>{0}</b> wants to connect to this CMS.",
				"<b>{0}</b> がこの CMS への接続を求めています。", escape(client.fName))).append("</p>");
		sb.append("<dl>");
		sb.append("<dt>").append(escape(m.get("Server", "サーバー"))).append("</dt><dd>")
				.append(escape(serverLabel(request, fConfig))).append("</dd>");
		sb.append("<dt>").append(escape(m.get("Workspace", "ワークスペース"))).append("</dt><dd>")
				.append(escape(fWorkspaceName)).append("</dd>");
		sb.append("<dt>").append(escape(m.get("Works as", "実行ユーザー"))).append("</dt><dd>")
				.append(escape(user.getName())).append("</dd>");
		sb.append("<dt>").append(escape(m.get("May", "許可する操作"))).append("</dt><dd>")
				.append(escape(write ? m.get("Read and change content", "コンテンツの読み取りと変更")
						: m.get("Read content", "コンテンツの読み取り")))
				.append("</dd>");
		sb.append("<dt>").append(escape(m.get("Delivered to", "認可の送り先"))).append("</dt><dd>")
				.append(escape(redirectTarget(redirectUri))).append("</dd>");
		sb.append("</dl>");
		sb.append("<p class=\"note\">").append(escape(m.get(
				"The client keeps working as you after you sign out, until you turn the connection off in "
						+ "Preferences › MCP.",
				"サインアウトした後も、「環境設定」›「MCP」で接続をオフにするまで、クライアントはあなたとして動作します。")))
				.append("</p>");
		sb.append("<form method=\"post\" action=\"").append(escape(CmsConfiguration.MCP_CGI_PATH + "/"
				+ fWorkspaceName + "/" + AUTHORIZE_PATH)).append("\">");
		sb.append("<input type=\"hidden\" name=\"csrf\" value=\"").append(escape(csrf)).append("\">");
		for (String name : AUTHORIZE_PARAMETERS) {
			String value = request.getParameter(name);
			if (value != null) {
				sb.append("<input type=\"hidden\" name=\"").append(name).append("\" value=\"").append(escape(value))
						.append("\">");
			}
		}
		sb.append("<p class=\"actions\"><button type=\"submit\" name=\"decision\" value=\"approve\" class=\"primary\">")
				.append(escape(m.get("Allow", "許可"))).append("</button>");
		sb.append("<button type=\"submit\" name=\"decision\" value=\"deny\">").append(escape(m.get("Cancel", "キャンセル")))
				.append("</button></p>");
		sb.append("</form>");
		return sb.toString();
	}

	/** The authorization request as a GET, for the sign-in to come back to and for "Try again". */
	private static String authorizeUrl(HttpServletRequest request, String issuer) {
		StringBuilder url = new StringBuilder(issuer).append('/').append(AUTHORIZE_PATH);
		for (String name : AUTHORIZE_PARAMETERS) {
			String value = request.getParameter(name);
			if (value != null) {
				appendParameter(url, name, value);
			}
		}
		return url.toString();
	}

	// ---- token ----------------------------------------------------------------

	void token(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!allowCrossOriginRead(request, response, "POST")) {
			return;
		}
		response.setHeader("Pragma", "no-cache");

		String grantType = request.getParameter("grant_type");
		if ("authorization_code".equals(grantType)) {
			redeemCode(request, response);
		} else if ("refresh_token".equals(grantType)) {
			refresh(request, response);
		} else {
			sendOAuthError(response, HttpServletResponse.SC_BAD_REQUEST, "unsupported_grant_type",
					"grant_type must be authorization_code or refresh_token.");
		}
	}

	private void redeemCode(HttpServletRequest request, HttpServletResponse response) throws IOException {
		Encryptor encryptor = CmsService.getEncryptor();
		long now = System.currentTimeMillis();

		JsonObject code = McpSealed.open(encryptor, CODE_PREFIX, CODE_TYPE, request.getParameter("code"));
		String clientId = request.getParameter("client_id");
		String verifier = request.getParameter("code_verifier");
		// OAuth 2.1 no longer has the client repeat the redirect URI here; when it
		// does, it has to be the one the code was issued for.
		String redirectUri = request.getParameter("redirect_uri");
		Saml2Credentials user;
		long generation;
		try {
			if (code == null || code.get("exp").getAsLong() < now || !fWorkspaceName.equals(code.get("ws").getAsString())
					|| clientId == null || !fingerprint(clientId).equals(code.get("cid").getAsString())
					|| (redirectUri != null && !redirectUri.equals(code.get("ru").getAsString()))
					|| verifier == null || !MessageDigest.isEqual(
							challengeOf(verifier).getBytes(StandardCharsets.US_ASCII),
							code.get("cc").getAsString().getBytes(StandardCharsets.US_ASCII))) {
				throw new IllegalArgumentException();
			}
			user = McpSealed.getIdentity(code);
			generation = code.get("gen").getAsLong();

			// A code is good for one token.
			purgeRedeemedCodes(now);
			if (fRedeemedCodes.putIfAbsent(code.get("jti").getAsString(), code.get("exp").getAsLong()) != null) {
				throw new IllegalArgumentException();
			}
		} catch (Throwable ex) {
			sendOAuthError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant",
					"The authorization code is invalid, expired or already used.");
			return;
		}

		McpConnections.Connection connection = currentConnection(response, user, generation);
		if (connection == null) {
			return;
		}

		JsonObject refresh = new JsonObject();
		McpSealed.putIdentity(refresh, user);
		refresh.addProperty("ws", fWorkspaceName);
		refresh.addProperty("cid", fingerprint(clientId));
		refresh.addProperty("gen", generation);
		refresh.addProperty("aat", now);
		refresh.addProperty("exp", now + fConfig.getRefreshTokenTtlSeconds() * 1000L);

		sendTokens(response, user, connection, now, now,
				McpSealed.seal(encryptor, REFRESH_PREFIX, REFRESH_TYPE, refresh));
	}

	private void refresh(HttpServletRequest request, HttpServletResponse response) throws IOException {
		long now = System.currentTimeMillis();

		JsonObject refresh = McpSealed.open(CmsService.getEncryptor(), REFRESH_PREFIX, REFRESH_TYPE,
				request.getParameter("refresh_token"));
		String clientId = request.getParameter("client_id");
		Saml2Credentials user;
		long generation;
		long authorizedAt;
		try {
			if (refresh == null || refresh.get("exp").getAsLong() < now
					|| !fWorkspaceName.equals(refresh.get("ws").getAsString())
					|| clientId == null || !fingerprint(clientId).equals(refresh.get("cid").getAsString())) {
				throw new IllegalArgumentException();
			}
			user = McpSealed.getIdentity(refresh);
			generation = refresh.get("gen").getAsLong();
			authorizedAt = refresh.get("aat").getAsLong();
			if (authorizedAt < fConfig.getTokensNotBeforeMillis()) {
				throw new IllegalArgumentException();
			}
		} catch (Throwable ex) {
			sendOAuthError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant",
					"The refresh token is invalid, expired or revoked. Authorize the client again.");
			return;
		}

		McpConnections.Connection connection = currentConnection(response, user, generation);
		if (connection == null) {
			return;
		}

		// The refresh token stays as it is: it expires a fixed time after the
		// user's approval, so the identity it carries is confirmed again by then.
		sendTokens(response, user, connection, authorizedAt, now, null);
	}

	/**
	 * The user's connection, when it is still the one the grant was made under.
	 * Otherwise answers {@code invalid_grant} and returns {@code null}.
	 */
	private McpConnections.Connection currentConnection(HttpServletResponse response, Saml2Credentials user,
			long generation) throws IOException {
		McpConnections.Connection connection;
		try {
			connection = McpConnections.get(fWorkspaceName, user.getName());
		} catch (Throwable ex) {
			CmsService.getLogger(McpOAuth.class).error("Could not read the MCP connection of " + user.getName(), ex);
			sendOAuthError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
					"The connection could not be read.");
			return null;
		}
		if (!connection.isEnabled() || connection.getGeneration() != generation) {
			sendOAuthError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant",
					"The MCP connection has been turned off.");
			return null;
		}
		return connection;
	}

	private void sendTokens(HttpServletResponse response, Saml2Credentials user,
			McpConnections.Connection connection, long authorizedAt, long now, String refreshToken)
			throws IOException {
		McpAccessToken.Issued issued = McpAccessToken.issue(CmsService.getEncryptor(), user, fWorkspaceName,
				connection.getGeneration(), authorizedAt, fConfig.getAccessTokenTtlSeconds(), now);

		JsonObject json = new JsonObject();
		json.addProperty("access_token", issued.getToken());
		json.addProperty("token_type", "Bearer");
		json.addProperty("expires_in", fConfig.getAccessTokenTtlSeconds());
		if (refreshToken != null) {
			json.addProperty("refresh_token", refreshToken);
		}
		json.addProperty("scope", (connection.isWrite() && fConfig.isWriteAllowed()) ? SCOPE_READ + " " + SCOPE_WRITE
				: SCOPE_READ);
		sendJson(response, HttpServletResponse.SC_OK, json);
	}

	private static void purgeRedeemedCodes(long now) {
		for (Iterator<Map.Entry<String, Long>> i = fRedeemedCodes.entrySet().iterator(); i.hasNext();) {
			if (i.next().getValue() < now) {
				i.remove();
			}
		}
	}

	/** The PKCE S256 challenge of a code verifier. */
	private static String challengeOf(String verifier) throws Exception {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
	}

	// ---- clients --------------------------------------------------------------

	private static final class Client {
		private final String fId;
		private final String fName;
		private final List<String> fRedirectUris;

		private Client(String id, String name, List<String> redirectUris) {
			fId = id;
			fName = name;
			fRedirectUris = redirectUris;
		}
	}

	private static Client openClient(String clientId) {
		JsonObject payload = McpSealed.open(CmsService.getEncryptor(), CLIENT_PREFIX, CLIENT_TYPE, clientId);
		if (payload == null) {
			return null;
		}
		try {
			List<String> redirectUris = new ArrayList<>();
			for (JsonElement e : payload.getAsJsonArray("uris")) {
				redirectUris.add(e.getAsString());
			}
			return new Client(clientId, payload.get("name").getAsString(), redirectUris);
		} catch (Throwable ex) {
			return null;
		}
	}

	/** A short digest that binds a code or a refresh token to the client it was issued to. */
	private static String fingerprint(String clientId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(clientId.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Where a redirect URI delivers to, in the form shown to the user — the
	 * origin of an {@code https} URI, the loopback origin of an {@code http}
	 * one, or the scheme of an application's own — or {@code null} when the URI
	 * is not one a client may register.
	 */
	static String redirectTarget(String redirectUri) {
		if (redirectUri == null || redirectUri.length() > MAX_REDIRECT_URI_LENGTH) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(redirectUri);
		} catch (Exception ex) {
			return null;
		}
		String scheme = uri.getScheme();
		if (scheme == null || uri.getRawFragment() != null) {
			return null;
		}
		scheme = scheme.toLowerCase(Locale.ROOT);

		if (scheme.equals("https") || scheme.equals("http")) {
			String host = uri.getHost();
			if (host == null || uri.getRawUserInfo() != null) {
				return null;
			}
			host = host.toLowerCase(Locale.ROOT);
			if (scheme.equals("http") && !host.equals("localhost") && !host.equals("127.0.0.1")
					&& !host.equals("[::1]")) {
				return null;
			}
			return scheme + "://" + host + ((uri.getPort() == -1) ? "" : ":" + uri.getPort());
		}

		// An application's own scheme (e.g. cursor://…). Schemes a browser runs
		// or reads as a document are not an application's.
		switch (scheme) {
		case "javascript":
		case "data":
		case "vbscript":
		case "file":
		case "blob":
		case "about":
		case "ftp":
		case "ws":
		case "wss":
			return null;
		default:
			return scheme + ":";
		}
	}

	/** Whether {@code resource} names this endpoint, give or take a trailing slash and letter case of the host. */
	private static boolean sameEndpoint(String resource, String endpoint) {
		try {
			URI a = new URI(resource);
			URI b = new URI(endpoint);
			String pathA = (a.getPath() == null) ? "" : a.getPath().replaceAll("/+$", "");
			return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
					&& a.getRawAuthority() != null
					&& McpServlet.stripDefaultPort(a.getRawAuthority(), a.getScheme()).equalsIgnoreCase(b.getRawAuthority())
					&& pathA.equals(b.getPath());
		} catch (Exception ex) {
			return false;
		}
	}

	// ---- plumbing -------------------------------------------------------------

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

	/**
	 * Discovery, registration and the token endpoint carry no cookie and answer
	 * with nothing a caller could not get by asking directly, so a browser-based
	 * client on any origin may read them. Returns {@code false} when the request
	 * has been answered here (a preflight, or a method the endpoint does not take).
	 */
	private static boolean allowCrossOriginRead(HttpServletRequest request, HttpServletResponse response,
			String method) throws IOException {
		response.setHeader("Access-Control-Allow-Origin", "*");
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			response.setHeader("Access-Control-Allow-Methods", method + ", OPTIONS");
			response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, MCP-Protocol-Version");
			response.setHeader("Access-Control-Max-Age", "600");
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return false;
		}
		if (!method.equalsIgnoreCase(request.getMethod())) {
			response.setHeader("Allow", method + ", OPTIONS");
			sendOAuthError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "invalid_request", "Use " + method + ".");
			return false;
		}
		return true;
	}

	private static void redirectError(HttpServletResponse response, String redirectUri, String state, String issuer,
			String error, String description) throws IOException {
		StringBuilder location = new StringBuilder(redirectUri);
		appendParameter(location, "error", error);
		appendParameter(location, "error_description", description);
		if (state != null) {
			appendParameter(location, "state", state);
		}
		appendParameter(location, "iss", issuer);
		response.sendRedirect(location.toString());
	}

	private static void appendParameter(StringBuilder url, String name, String value) {
		url.append((url.indexOf("?") == -1) ? '?' : '&').append(name).append('=').append(encode(value));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static JsonArray array(String... values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	private static JsonArray scopes(boolean writeAllowed) {
		return writeAllowed ? array(SCOPE_READ, SCOPE_WRITE) : array(SCOPE_READ);
	}

	/** Reads the body; throws when it exceeds {@link #MAX_BODY_BYTES}. */
	private static byte[] readBody(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int n;
		while ((n = in.read(buffer)) != -1) {
			if (out.size() + n > MAX_BODY_BYTES) {
				throw new IOException("Body too large");
			}
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}

	private static void sendOAuthError(HttpServletResponse response, int status, String error, String description)
			throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("error", error);
		json.addProperty("error_description", description);
		sendJson(response, status, json);
	}

	private static void sendJson(HttpServletResponse response, int status, JsonObject body) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(body.toString());
		response.getWriter().flush();
	}

	/**
	 * Sends a page of the approval flow: plain server-rendered HTML with no
	 * script. {@code redirectUri} is where the form on the page may end up
	 * sending the browser, or {@code null} when the page has no form.
	 */
	private void sendPage(HttpServletResponse response, int status, Messages m, String redirectUri, String body)
			throws IOException {
		// A browser applies form-action to the redirect that answers a form post
		// as well, so the client's redirect URI has to be allowed next to 'self'.
		String formAction = "'self'";
		String target = redirectTarget(redirectUri);
		if (target != null) {
			formAction += " " + target;
		}
		response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action "
				+ formAction + "; frame-ancestors 'none'");

		String title = m.get("Connect to MintJams CMS", "MintJams CMS への接続");
		response.setStatus(status);
		response.setContentType("text/html");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("<!DOCTYPE html><html lang=\"" + m.getLanguage() + "\"><head><meta charset=\"utf-8\">"
				+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
				+ "<title>" + escape(title) + "</title><style>"
				+ "body{font:15px/1.6 system-ui,sans-serif;max-width:30rem;margin:3rem auto;padding:0 1rem;"
				+ "color:#222;background:#fff}"
				+ "h1{font-size:1.3rem}"
				+ "dl{display:grid;grid-template-columns:max-content 1fr;gap:.35rem 1rem;margin:1.2rem 0;"
				+ "padding:.8rem 1rem;border:1px solid #ccc;border-radius:6px}"
				+ "dt{color:#666}dd{margin:0;font-weight:600;overflow-wrap:anywhere}"
				+ ".note{color:#666;font-size:.9em}.actions{display:flex;gap:.6rem;margin-top:1.4rem}"
				+ "button,.button{font:inherit;padding:.45rem 1.2rem;border:1px solid #999;border-radius:6px;"
				+ "background:#f4f4f4;color:inherit;cursor:pointer;text-decoration:none;display:inline-block}"
				+ "button.primary{background:#0b63ce;border-color:#0b63ce;color:#fff}"
				+ ".error{color:#a40000}"
				+ "@media (prefers-color-scheme:dark){body{color:#ddd;background:#1c1c1c}"
				+ "dl{border-color:#555}dt,.note{color:#aaa}"
				+ "button,.button{background:#2c2c2c;border-color:#666}"
				+ "button.primary{background:#2f7de1;border-color:#2f7de1}.error{color:#f88}}"
				+ "</style></head><body><h1>" + escape(title) + "</h1>" + body + "</body></html>");
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

	/**
	 * The two languages the approval page speaks. The page is rendered before
	 * any Webtop code runs, so it cannot use Webtop's message bundles; it goes by
	 * the browser's preferred language instead.
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
			return fJapanese ? japanese : english;
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

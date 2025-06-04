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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.jcr.Credentials;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import com.onelogin.saml2.Auth;
import com.onelogin.saml2.authn.AuthnRequestParams;
import com.onelogin.saml2.exception.ValidationError;
import com.onelogin.saml2.settings.Saml2Settings;
import com.onelogin.saml2.settings.SettingsBuilder;

public class Saml2ServiceProviderConfiguration {

	private Map<String, Object> fConfig = new HashMap<>();
	private Saml2Settings fSaml2Settings;

	public HttpServlet createServlet() throws IOException {
		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path saml2Path = configPath.resolve("saml2.yml");
		if (!Files.exists(saml2Path)) {
			Properties p = new Properties();
			try (InputStream in = Saml2ServiceProviderConfiguration.class.getResourceAsStream("onelogin.saml.properties")) {
				p.load(in);
			}

			AdaptableMap<String, Object> yaml = AdaptableMap.<String, Object>newBuilder()
					.put("contextPath", "/bin/auth.cgi/saml2")
					.put("strict", "true")
					.put("debug", "false")
					.put("sp", AdaptableMap.<String, Object>newBuilder()
							.put("entityID", p.getProperty("onelogin.saml2.sp.entityid"))
							.put("rootURL", p.getProperty("onelogin.saml2.sp.entityid"))
							.put("certificate", p.getProperty("onelogin.saml2.sp.x509cert"))
							.put("privateKey", p.getProperty("onelogin.saml2.sp.privatekey"))
							.build())
					.put("idp", AdaptableMap.<String, Object>newBuilder()
							.put("entityID", p.getProperty("onelogin.saml2.idp.entityid"))
							.put("loginURL", p.getProperty("onelogin.saml2.idp.single_sign_on_service.url"))
							.put("logoutURL", p.getProperty("onelogin.saml2.idp.single_logout_service.url"))
							.put("logoutResponseURL", p.getProperty("onelogin.saml2.idp.single_logout_service.response.url"))
							.put("certificate", p.getProperty("onelogin.saml2.idp.x509cert"))
							.build())
					.put("security", AdaptableMap.<String, Object>newBuilder()
							.put("willBeSigned", "authnRequest, logoutRequest, logoutResponse")
							.put("wantSigned", "messages, assertions")
							.put("willBeEncrypted", "nameID")
							.put("wantEncrypted", "nameID, assertions")
							.build())
					.put("organization", AdaptableMap.<String, Object>newBuilder()
							.put("name", p.getProperty("onelogin.saml2.organization.name"))
							.put("displayName", p.getProperty("onelogin.saml2.organization.displayname"))
							.put("url", p.getProperty("onelogin.saml2.organization.url"))
							.put("language", p.getProperty("onelogin.saml2.organization.lang"))
							.build())
					.put("contacts", AdaptableMap.<String, Object>newBuilder()
							.put("technical", AdaptableMap.<String, Object>newBuilder()
									.put("name", p.getProperty("onelogin.saml2.contacts.technical.given_name"))
									.put("email", p.getProperty("onelogin.saml2.contacts.technical.email_address"))
									.build())
							.put("support", AdaptableMap.<String, Object>newBuilder()
									.put("name", p.getProperty("onelogin.saml2.contacts.support.given_name"))
									.put("email", p.getProperty("onelogin.saml2.contacts.support.email_address"))
									.build())
							.build())
					.build();
			try (Writer out = Files.newBufferedWriter(saml2Path)) {
				String yamlString = new Dump(DumpSettings.builder().build()).dumpToString(yaml);
				out.append(yamlString);
			}
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(saml2Path))) {
			fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
		}

		prepareSettings();

		return new Saml2Servlet(this);
	}

	private void prepareSettings() throws IOException {
		Properties p = new Properties();
		try (InputStream in = Saml2ServiceProviderConfiguration.class.getResourceAsStream("onelogin.saml.properties")) {
			p.load(in);
		}

		ExpressionContext el = ExpressionContext.create().setVariable("config", fConfig);

		p.setProperty("onelogin.saml2.strict", el.defaultIfEmpty("config.strict", "true"));
		p.setProperty("onelogin.saml2.debug", el.defaultIfEmpty("config.debug", "false"));

		String rootURL = el.getString("config.sp.rootURL");
		if (rootURL.endsWith("/")) {
			rootURL = rootURL.substring(0, rootURL.length() - 1);
		}
		p.setProperty("onelogin.saml2.sp.entityid", el.defaultIfEmpty("config.sp.entityID", rootURL));
		p.setProperty("onelogin.saml2.sp.assertion_consumer_service.url", rootURL + Saml2Servlet.LOGIN_PATH);
		p.setProperty("onelogin.saml2.sp.single_logout_service.url", rootURL + Saml2Servlet.LOGOUT_PATH);
		p.setProperty("onelogin.saml2.sp.x509cert", el.getString("config.sp.certificate"));
		p.setProperty("onelogin.saml2.sp.privatekey", el.getString("config.sp.privateKey"));

		p.setProperty("onelogin.saml2.idp.entityid", el.getString("config.idp.entityID"));
		p.setProperty("onelogin.saml2.idp.single_sign_on_service.url", el.getString("config.idp.loginURL"));
		p.setProperty("onelogin.saml2.idp.single_logout_service.url", el.getString("config.idp.logoutURL"));
		p.setProperty("onelogin.saml2.idp.single_logout_service.response.url", el.getString("config.idp.logoutResponseURL"));
		p.setProperty("onelogin.saml2.idp.x509cert", el.getString("config.idp.certificate"));

		List<String> willBeSigned = Arrays.asList(el.getStringArray("config.security.willBeSigned"));
		p.setProperty("onelogin.saml2.security.authnrequest_signed", "" + willBeSigned.contains("authnRequest"));
		p.setProperty("onelogin.saml2.security.logoutrequest_signed", "" + willBeSigned.contains("logoutRequest"));
		p.setProperty("onelogin.saml2.security.logoutresponse_signed", "" + willBeSigned.contains("logoutResponse"));
		List<String> wantSigned = Arrays.asList(el.getStringArray("config.security.wantSigned"));
		p.setProperty("onelogin.saml2.security.want_messages_signed", "" + wantSigned.contains("messages"));
		p.setProperty("onelogin.saml2.security.want_assertions_signed", "" + wantSigned.contains("assertions"));
		List<String> willBeEncrypted = Arrays.asList(el.getStringArray("config.security.willBeEncrypted"));
		p.setProperty("onelogin.saml2.security.nameid_encrypted", "" + willBeEncrypted.contains("nameID"));
		List<String> wantEncrypted = Arrays.asList(el.getStringArray("config.security.wantEncrypted"));
		p.setProperty("onelogin.saml2.security.want_nameid_encrypted", "" + wantEncrypted.contains("nameID"));
		p.setProperty("onelogin.saml2.security.want_assertions_encrypted", "" + wantEncrypted.contains("assertions"));

		p.setProperty("onelogin.saml2.organization.name", el.getString("config.organization.name"));
		p.setProperty("onelogin.saml2.organization.displayname", el.getString("config.organization.displayName"));
		p.setProperty("onelogin.saml2.organization.url", el.getString("config.organization.url"));
		p.setProperty("onelogin.saml2.organization.lang", el.getString("config.organization.language"));

		p.setProperty("onelogin.saml2.contacts.technical.given_name", el.getString("config.contacts.technical.name"));
		p.setProperty("onelogin.saml2.contacts.technical.email_address", el.getString("config.contacts.technical.email"));
		p.setProperty("onelogin.saml2.contacts.support.given_name", el.getString("config.contacts.support.name"));
		p.setProperty("onelogin.saml2.contacts.support.email_address", el.getString("config.contacts.support.email"));

		fSaml2Settings = new SettingsBuilder().fromProperties(p).build();
		List<String> errors = fSaml2Settings.checkSettings();
		if (!errors.isEmpty()) {
			throw new IOException("Missing SAML2 settings: " + String.join(", ", errors));
		}
	}

	public Path getConfigPath() {
		return CmsService.getRepositoryPath().resolve("etc").normalize();
	}

	public String getContextPath() {
		return getExpressionContext().defaultIfEmpty("config.contextPath", "/bin/auth.cgi/saml2");
	}

	public ExpressionContext getExpressionContext() {
		return ExpressionContext.create().setVariable("config", fConfig);
	}

	private static class Saml2Servlet extends HttpServlet {
		private static final long serialVersionUID = 1L;

		private static final String LOGIN_PATH = "/login";
		private static final String LOGOUT_PATH = "/logout";
		private static final String DESCRIPTOR_PATH = "/descriptor";

		private final Saml2ServiceProviderConfiguration fConfig;

		private Saml2Servlet(Saml2ServiceProviderConfiguration config) {
			fConfig = config;
		}

		@Override
		protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
			String path = request.getPathInfo();
			try {
				if (LOGIN_PATH.equals(path)) {
					Auth auth = new Auth(fConfig.fSaml2Settings, request, response);
					try {
						auth.processResponse();
					} catch (ValidationError ex) {
						throw Cause.create(ex).wrap(ServletException.class);
					} catch (Throwable ignore) {
						AuthnRequestParams authnRequestParams = new AuthnRequestParams(true, false, true);
						String redirectURI = getRedirectURI(request);
						if (Strings.isNotEmpty(redirectURI)) {
							auth.login(redirectURI, authnRequestParams);
						} else {
							auth.login(null, authnRequestParams);
						}
						return;
					}
					if (auth.getLastValidationException() != null) {
						throw Cause.create(auth.getLastValidationException()).wrap(ServletException.class);
					}
					if (!auth.isAuthenticated()) {
						response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
						return;
					}

					request.getSession().setAttribute(Credentials.class.getName(), new Saml2Credentials(auth));
					request.getSession().setAttribute("org.mintjams.cms.security.auth.AuthenticatedFactors", "saml2");

					String relayState = getRelayState(request);
					if (Strings.isNotEmpty(relayState)) {
						response.sendRedirect(relayState);
						return;
					}

					response.setStatus(HttpServletResponse.SC_OK);
					return;
				}

				if (LOGOUT_PATH.equals(path)) {
					Auth auth = new Auth(fConfig.fSaml2Settings, request, response);
					auth.processSLO();
					List<String> errors = auth.getErrors();
					if (!errors.isEmpty()) {
						throw new ServletException("An unexpected error occurred while processing your request: " + String.join(", ", errors));
					}

                                        String redirectURI = getRedirectURI(request);
                                        if (Strings.isNotEmpty(redirectURI)) {
                                                response.sendRedirect(redirectURI);
						return;
					}

					response.setStatus(HttpServletResponse.SC_OK);
					return;
				}

				if (DESCRIPTOR_PATH.equals(path)) {
					response.setContentType("text/xml");
					response.setCharacterEncoding(StandardCharsets.UTF_8.name());
					response.getWriter().append(fConfig.fSaml2Settings.getSPMetadata());
					return;
				}
			} catch (ServletException | IOException ex) {
				throw ex;
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ServletException.class);
			}

			response.sendError(HttpServletResponse.SC_NOT_FOUND);
		}

		private String getRedirectURI(HttpServletRequest request) {
			String redirectURI = request.getParameter("redirect_uri");
			if (Strings.isEmpty(redirectURI)) {
				return null;
			}
			return redirectURI;
		}

		private String getRelayState(HttpServletRequest request) throws URISyntaxException {
			String relayState = request.getParameter("RelayState");
			if (Strings.isEmpty(relayState)) {
				return null;
			}
			if (relayState.equals(fConfig.fSaml2Settings.getSpAssertionConsumerServiceUrl().toURI().toASCIIString())) {
				return null;
			}
			return relayState;
		}
	}

}

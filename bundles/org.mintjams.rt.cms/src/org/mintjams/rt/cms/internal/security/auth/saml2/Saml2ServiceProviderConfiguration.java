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
import java.security.cert.CertificateEncodingException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.jcr.Credentials;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.cms.security.saml2.LocalIdentityProvider;
import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.mintjams.saml2.Saml2Auth;
import org.mintjams.saml2.Saml2MetadataBuilder;
import org.mintjams.saml2.exception.Saml2Exception;
import org.mintjams.saml2.exception.ValidationException;
import org.mintjams.saml2.model.Saml2Settings;
import org.mintjams.saml2.Saml2SettingsBuilder;

public class Saml2ServiceProviderConfiguration {

	/**
	 * Environment variable consulted when {@code sp.rootURL} (and indirectly
	 * {@code sp.entityID}) is left blank in {@code saml2.yml}. This is the
	 * single source of truth for the public-facing base URL in containerized
	 * deployments.
	 */
	public static final String ENV_PUBLIC_BASE_URL = "CMS_PUBLIC_BASE_URL";

	private Map<String, Object> fConfig = new HashMap<>();
	private Saml2Settings fSaml2Settings;
	private SpKeyStoreManager fKeyStoreManager;
	private BundleContext fBundleContext;

	@SuppressWarnings("unchecked")
	public HttpServlet createServlet() throws IOException {
		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path saml2Path = configPath.resolve("saml2.yml");
		if (!Files.exists(saml2Path)) {
			Properties p = new Properties();
			try (InputStream in = Saml2ServiceProviderConfiguration.class.getResourceAsStream("saml.properties")) {
				p.load(in);
			}

			AdaptableMap<String, Object> yaml = AdaptableMap.<String, Object>newBuilder()
					.put("contextPath", "/bin/auth.cgi/saml2")
					.put("strict", "true")
					.put("debug", "false")
					.put("sp", AdaptableMap.<String, Object>newBuilder()
							.put("entityID", p.getProperty("saml2.sp.entityid"))
							.put("displayName", p.getProperty("saml2.sp.displayName"))
							.put("rootURL", p.getProperty("saml2.sp.entityid"))
							.put("keystore", AdaptableMap.<String, Object>newBuilder()
									.put("type", "PKCS12")
									.put("password", CmsService.getEncryptor().encrypt("changeit"))
									.put("alias", "sp-signing")
									.build())
							.put("certificateTemplate", AdaptableMap.<String, Object>newBuilder()
									.put("subjectDN", "CN=Sample Service Provider, OU=Quick Start, O=Open Components Project")
									.put("keyAlgorithm", "RSA")
									.put("keySize", 2048)
									.put("signatureAlgorithm", "SHA256withRSA")
									.put("validity", 7300)
									.build())
							.build())
					.put("idp", AdaptableMap.<String, Object>newBuilder()
							.put("entityID", p.getProperty("saml2.idp.entityid"))
							.put("loginURL", p.getProperty("saml2.idp.single_sign_on_service.url"))
							.put("logoutURL", p.getProperty("saml2.idp.single_logout_service.url"))
							.put("logoutResponseURL", p.getProperty("saml2.idp.single_logout_service.response.url"))
							.put("certificate", p.getProperty("saml2.idp.x509cert"))
							.build())
					.put("security", AdaptableMap.<String, Object>newBuilder()
							.put("willBeSigned", "authnRequest, logoutRequest, logoutResponse")
							.put("wantSigned", "messages, assertions")
							.put("willBeEncrypted", "nameID")
							.put("wantEncrypted", "nameID, assertions")
							.build())
					.put("organization", AdaptableMap.<String, Object>newBuilder()
							.put("name", p.getProperty("saml2.organization.name"))
							.put("displayName", p.getProperty("saml2.organization.displayname"))
							.put("url", p.getProperty("saml2.organization.url"))
							.put("language", p.getProperty("saml2.organization.lang"))
							.build())
					.put("contacts", AdaptableMap.<String, Object>newBuilder()
							.put("technical", AdaptableMap.<String, Object>newBuilder()
									.put("name", p.getProperty("saml2.contacts.technical.given_name"))
									.put("email", p.getProperty("saml2.contacts.technical.email_address"))
									.build())
							.put("support", AdaptableMap.<String, Object>newBuilder()
									.put("name", p.getProperty("saml2.contacts.support.given_name"))
									.put("email", p.getProperty("saml2.contacts.support.email_address"))
									.build())
							.build())
					.build();
			try (Writer out = Files.newBufferedWriter(saml2Path, StandardCharsets.UTF_8)) {
				String yamlString = new Dump(DumpSettings.builder()
						.setIndent(4)
						.setIndicatorIndent(2)
						.setDefaultFlowStyle(FlowStyle.BLOCK)
						.build()).dumpToString(yaml);
				out.append(yamlString);
			}
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(saml2Path))) {
			fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);

			String password = ExpressionContext.create()
					.setVariable("config", fConfig)
					.defaultIfEmpty("config.sp.keystore.password", "changeit");
			if (!CmsService.getEncryptor().isEncrypted(password)) {
				((Map<String, Object>) ((Map<String, Object>) fConfig.get("sp")).get("keystore"))
						.put("password", CmsService.getEncryptor().encrypt(password));
				try (Writer out = Files.newBufferedWriter(saml2Path, StandardCharsets.UTF_8)) {
					String yamlString = new Dump(DumpSettings.builder()
							.setIndent(4)
							.setIndicatorIndent(2)
							.setDefaultFlowStyle(FlowStyle.BLOCK)
							.build()).dumpToString(fConfig);
					out.append(yamlString);
				}
				CmsService.getLogger(Saml2ServiceProviderConfiguration.class).info("The sp.keystore.password parameter has been encrypted and the configuration file has been updated.");
			}
		}

		fKeyStoreManager = new SpKeyStoreManager(configPath, fConfig);

		return new Saml2Servlet(this);
	}

	/**
	 * Returns the SAML2 settings, building them lazily on first access.
	 *
	 * <p>The lazy build is essential to support the local IdP↔SP bridge:
	 * the {@link LocalIdentityProvider} OSGi service is published by the IdP
	 * bundle only after this configuration has been instantiated, so we must
	 * defer reading IdP metadata until it is actually needed (i.e., when a
	 * SAML request arrives).</p>
	 */
	public synchronized Saml2Settings getSaml2Settings() throws IOException {
		if (fSaml2Settings == null) {
			prepareSettings();
		}
		return fSaml2Settings;
	}

	/**
	 * Invalidates the cached SAML2 settings so they are rebuilt on next access.
	 * Called when the local IdP service appears or disappears.
	 */
	public synchronized void invalidateSaml2Settings() {
		fSaml2Settings = null;
	}

	public void setBundleContext(BundleContext bundleContext) {
		fBundleContext = bundleContext;
	}

	private void prepareSettings() throws IOException {
		Properties p = new Properties();
		try (InputStream in = Saml2ServiceProviderConfiguration.class.getResourceAsStream("saml.properties")) {
			p.load(in);
		}

		ExpressionContext el = ExpressionContext.create().setVariable("config", fConfig);

		p.setProperty("saml2.strict", el.defaultIfEmpty("config.strict", "true"));
		p.setProperty("saml2.debug", el.defaultIfEmpty("config.debug", "false"));

		String rootURL = el.getString("config.sp.rootURL");
		if (Strings.isEmpty(rootURL)) {
			rootURL = getDefaultRootURL();
		}
		if (rootURL != null && rootURL.endsWith("/")) {
			rootURL = rootURL.substring(0, rootURL.length() - 1);
		}
		p.setProperty("saml2.sp.entityid", el.defaultIfEmpty("config.sp.entityID", rootURL));
		p.setProperty("saml2.sp.assertion_consumer_service.url", rootURL + Saml2Servlet.LOGIN_PATH);
		p.setProperty("saml2.sp.single_logout_service.url", rootURL + Saml2Servlet.LOGOUT_PATH);
		try {
			p.setProperty("saml2.sp.x509cert", Base64.getMimeEncoder(64, new byte[]{'\n'})
					.encodeToString(fKeyStoreManager.getCertificate().getEncoded()));
			p.setProperty("saml2.sp.privatekey", Base64.getEncoder()
					.encodeToString(fKeyStoreManager.getPrivateKey().getEncoded()));
		} catch (CertificateEncodingException ex) {
			throw new IOException("Failed to encode SP certificate", ex);
		}

		LocalIdentityProvider localIdp = lookupLocalIdentityProvider();
		p.setProperty("saml2.idp.entityid",
				resolveIdpField(el, "config.idp.entityID", localIdp, LocalIdentityProvider::getEntityId));
		p.setProperty("saml2.idp.single_sign_on_service.url",
				resolveIdpField(el, "config.idp.loginURL", localIdp, LocalIdentityProvider::getLoginUrl));
		p.setProperty("saml2.idp.single_logout_service.url",
				resolveIdpField(el, "config.idp.logoutURL", localIdp, LocalIdentityProvider::getLogoutUrl));
		p.setProperty("saml2.idp.single_logout_service.response.url",
				resolveIdpField(el, "config.idp.logoutResponseURL", localIdp, LocalIdentityProvider::getLogoutResponseUrl));
		p.setProperty("saml2.idp.x509cert", resolveIdpCertificate(el, localIdp));

		List<String> willBeSigned = Arrays.asList(el.getStringArray("config.security.willBeSigned"));
		p.setProperty("saml2.security.authnrequest_signed", "" + willBeSigned.contains("authnRequest"));
		p.setProperty("saml2.security.logoutrequest_signed", "" + willBeSigned.contains("logoutRequest"));
		p.setProperty("saml2.security.logoutresponse_signed", "" + willBeSigned.contains("logoutResponse"));
		List<String> wantSigned = Arrays.asList(el.getStringArray("config.security.wantSigned"));
		p.setProperty("saml2.security.want_messages_signed", "" + wantSigned.contains("messages"));
		p.setProperty("saml2.security.want_assertions_signed", "" + wantSigned.contains("assertions"));
		List<String> willBeEncrypted = Arrays.asList(el.getStringArray("config.security.willBeEncrypted"));
		p.setProperty("saml2.security.nameid_encrypted", "" + willBeEncrypted.contains("nameID"));
		List<String> wantEncrypted = Arrays.asList(el.getStringArray("config.security.wantEncrypted"));
		p.setProperty("saml2.security.want_nameid_encrypted", "" + wantEncrypted.contains("nameID"));
		p.setProperty("saml2.security.want_assertions_encrypted", "" + wantEncrypted.contains("assertions"));

		p.setProperty("saml2.organization.name", el.getString("config.organization.name"));
		p.setProperty("saml2.organization.displayname", el.getString("config.organization.displayName"));
		p.setProperty("saml2.organization.url", el.getString("config.organization.url"));
		p.setProperty("saml2.organization.lang", el.getString("config.organization.language"));

		p.setProperty("saml2.contacts.technical.given_name", el.getString("config.contacts.technical.name"));
		p.setProperty("saml2.contacts.technical.email_address", el.getString("config.contacts.technical.email"));
		p.setProperty("saml2.contacts.support.given_name", el.getString("config.contacts.support.name"));
		p.setProperty("saml2.contacts.support.email_address", el.getString("config.contacts.support.email"));

		try {
			fSaml2Settings = new Saml2SettingsBuilder().fromProperties(p).build();
		} catch (Saml2Exception ex) {
			throw new IOException("Failed to build SAML2 settings: " + ex.getMessage(), ex);
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

	public SpKeyStoreManager getKeyStoreManager() {
		return fKeyStoreManager;
	}

	/**
	 * Returns the resolved SP root URL (without trailing slash).
	 * Cheap accessor — does not build the full SAML2 settings, so it can be
	 * invoked even before the local IdP service appears.
	 */
	public String getSpRootURL() {
		ExpressionContext el = getExpressionContext();
		String rootURL = el.getString("config.sp.rootURL");
		if (Strings.isEmpty(rootURL)) {
			rootURL = getDefaultRootURL();
		}
		if (rootURL != null && rootURL.endsWith("/")) {
			rootURL = rootURL.substring(0, rootURL.length() - 1);
		}
		return rootURL;
	}

	public String getSpEntityID() {
		String rootURL = getSpRootURL();
		return getExpressionContext().defaultIfEmpty("config.sp.entityID", rootURL);
	}

	public String getSpDisplayName() {
		return getExpressionContext().defaultIfEmpty("config.sp.displayName", "Content Management System");
	}

	public String getSpAssertionConsumerServiceURL() {
		String rootURL = getSpRootURL();
		return rootURL == null ? null : rootURL + Saml2Servlet.LOGIN_PATH;
	}

	/**
	 * Computes the default SP root URL when {@code sp.rootURL} is blank in
	 * {@code saml2.yml}. Uses {@code $CMS_PUBLIC_BASE_URL + contextPath} so a
	 * single environment variable controls the public-facing URL across the
	 * whole product.
	 */
	private String getDefaultRootURL() {
		String baseUrl = System.getenv(ENV_PUBLIC_BASE_URL);
		if (Strings.isEmpty(baseUrl)) {
			return null;
		}
		if (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		return baseUrl + getContextPath();
	}

	private LocalIdentityProvider lookupLocalIdentityProvider() {
		if (fBundleContext == null) {
			return null;
		}
		ServiceReference<LocalIdentityProvider> ref = fBundleContext.getServiceReference(LocalIdentityProvider.class);
		if (ref == null) {
			return null;
		}
		return fBundleContext.getService(ref);
	}

	private static String resolveIdpField(ExpressionContext el, String yamlPath, LocalIdentityProvider localIdp,
			java.util.function.Function<LocalIdentityProvider, String> fallback) {
		String value = el.getString(yamlPath);
		if (Strings.isNotEmpty(value)) {
			return value;
		}
		if (localIdp == null) {
			return "";
		}
		String resolved = fallback.apply(localIdp);
		return Strings.isEmpty(resolved) ? "" : resolved;
	}

	private static String resolveIdpCertificate(ExpressionContext el, LocalIdentityProvider localIdp) {
		String value = el.getString("config.idp.certificate");
		if (Strings.isNotEmpty(value)) {
			return value;
		}
		if (localIdp == null || localIdp.getSigningCertificate() == null) {
			return "";
		}
		try {
			return Base64.getMimeEncoder(64, new byte[]{'\n'})
					.encodeToString(localIdp.getSigningCertificate().getEncoded());
		} catch (CertificateEncodingException ex) {
			CmsService.getLogger(Saml2ServiceProviderConfiguration.class)
					.warn("Failed to encode local IdP certificate; SP will operate without it.", ex);
			return "";
		}
	}

	private static class Saml2Servlet extends HttpServlet {
		private static final long serialVersionUID = 1L;

		static final String LOGIN_PATH = "/login";
		static final String LOGOUT_PATH = "/logout";
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
					Saml2Auth auth = new Saml2Auth(fConfig.getSaml2Settings(), request, response);

					// Check if this is a SAML Response (from IdP) or initial login request
					if (!auth.hasSAMLResponse(request)) {
						// No SAML response, initiate login by redirecting to IdP
						String relayState = getRelayState(request);
						if (relayState == null) {
							// Legacy support for "redirect_uri" parameter as relay state
							relayState = getRedirectURI(request);
						}
						if (Strings.isNotEmpty(relayState)) {
							auth.login(relayState, true, false);
						} else {
							auth.login(null, true, false);
						}
						return;
					}

					// Process SAML Response from IdP
					try {
						auth.processResponse();
					} catch (ValidationException ex) {
						throw Cause.create(ex).wrap(ServletException.class);
					}

					if (auth.getLastValidationException() != null) {
						throw Cause.create(auth.getLastValidationException()).wrap(ServletException.class);
					}
					if (!auth.isAuthenticated()) {
						response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
						return;
					}

					// Log SAML attributes for verification
					CmsService.getLogger(getClass()).info("SAML Authentication successful for user: " + auth.getNameId());
					CmsService.getLogger(getClass()).info("SAML Attributes count: " + auth.getAttributes().size());
					for (Map.Entry<String, List<String>> entry : auth.getAttributes().entrySet()) {
						String attributeName = entry.getKey();
						List<String> attributeValues = entry.getValue();
						CmsService.getLogger(getClass()).info("  Attribute '" + attributeName + "' has " + attributeValues.size() + " value(s):");
						for (int i = 0; i < attributeValues.size(); i++) {
							CmsService.getLogger(getClass()).info("    [" + i + "] = " + attributeValues.get(i));
						}
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
					Saml2Auth auth = new Saml2Auth(fConfig.getSaml2Settings(), request, response);
					auth.processSLO();
					List<String> errors = auth.getErrors();
					if (!errors.isEmpty()) {
						throw new ServletException("An unexpected error occurred while processing your request: " + String.join(", ", errors));
					}

					String relayState = getRelayState(request);
					if (relayState == null) {
						// Legacy support for "redirect_uri" parameter as relay state
						relayState = getRedirectURI(request);
					}
					if (Strings.isNotEmpty(relayState)) {
						response.sendRedirect(relayState);
						return;
					}

					response.setStatus(HttpServletResponse.SC_OK);
					return;
				}

				if (DESCRIPTOR_PATH.equals(path)) {
					response.setContentType("application/samlmetadata+xml");
					response.setCharacterEncoding(StandardCharsets.UTF_8.name());
					try {
						String metadata = new Saml2MetadataBuilder(fConfig.getSaml2Settings()).build();
						response.getWriter().append(metadata);
					} catch (java.security.cert.CertificateEncodingException ex) {
						throw new IOException("Failed to encode SP certificate for metadata", ex);
					}
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

		private String getRelayState(HttpServletRequest request) throws URISyntaxException, IOException {
			String relayState = request.getParameter("RelayState");
			if (Strings.isEmpty(relayState)) {
				return null;
			}
			if (relayState.equals(fConfig.getSaml2Settings().getSpAssertionConsumerServiceUrl())) {
				return null;
			}
			return relayState;
		}
	}

}

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

package org.mintjams.idp.internal;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.mintjams.jcr.util.ExpressionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class IdpConfiguration {

	private static final Logger log = LoggerFactory.getLogger(IdpConfiguration.class);

	public static final String DEFAULT_BASE_URL = "https://localhost:8443";
	public static final String DEFAULT_CONTEXT_PATH = "/idp";
	public static final String DEFAULT_ROLE_ATTRIBUTE = "Role";
	public static final String DEFAULT_KEYSTORE_TYPE = "PKCS12";
	public static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";
	public static final String DEFAULT_KEYSTORE_ALIAS = "idp-signing";
	public static final String DEFAULT_DISTINGUISHED_NAME = "CN=Sample Identity Provider, OU=Quick Start, O=Open Components Project";
	public static final String DEFAULT_KEY_ALGORITHM = "RSA";
	public static final int DEFAULT_KEY_SIZE = 2048;
	public static final String DEFAULT_SIGNATURE_ALGORITHM = "SHA256withRSA";
	public static final int DEFAULT_CERTIFICATE_VALIDITY = 3650;
	public static final int DEFAULT_ASSERTION_VALIDITY_SECONDS = 300;

	private Map<String, Object> fConfig;

	@SuppressWarnings("unchecked")
	private Map<String, Object> getConfig() throws Exception {
		if (fConfig == null) {
			Path configPath = getConfigPath();
			Path idpPath = configPath.resolve("idp.yml");
			if (!Files.exists(idpPath)) {
				try (Writer out = Files.newBufferedWriter(idpPath, StandardCharsets.UTF_8)) {
					String yamlString = new Dump(DumpSettings.builder().build()).dumpToString(Map.of(
							"entityId", DEFAULT_BASE_URL + DEFAULT_CONTEXT_PATH, // Entity ID of the IdP (default: https://localhost:8443/idp)
							"baseUrl", DEFAULT_BASE_URL, // Base URL of the IdP (default: https://localhost:8443)
							"contextPath", DEFAULT_CONTEXT_PATH, // Servlet context path (default: /idp)
							"roleAttribute", DEFAULT_ROLE_ATTRIBUTE, // SAML attribute name for roles (default: Role)
							"keystore", Map.of(
								"type", DEFAULT_KEYSTORE_TYPE, // Keystore type (default: PKCS12)
								"password", Activator.getDefault().getCryptoService().encrypt(DEFAULT_KEYSTORE_PASSWORD), // Keystore password (default: changeit)
								"alias", DEFAULT_KEYSTORE_ALIAS // Alias of the signing key in the keystore (default: idp-signing)
								),
							"certificate", Map.of(
								"distinguishedName", DEFAULT_DISTINGUISHED_NAME, // Distinguished name for the self-signed certificate (default: CN=Sample Identity Provider, OU=Quick Start, O=Open Components Project)
								"keyAlgorithm", DEFAULT_KEY_ALGORITHM, // Key algorithm for the signing key (default: RSA)
								"keySize", DEFAULT_KEY_SIZE, // Key size for the signing key (default: 2048)
								"signatureAlgorithm", DEFAULT_SIGNATURE_ALGORITHM, // Signature algorithm for certificate generation (default: SHA256withRSA)
								"validity", DEFAULT_CERTIFICATE_VALIDITY // Validity period of the self-signed certificate in days (default: 3650, i.e., 10 years)
								),
							"assertionValiditySeconds", DEFAULT_ASSERTION_VALIDITY_SECONDS, // Validity period of SAML assertions in seconds (default: 300)
							"trustedSPs", Collections.emptyList() // List of trusted Service Providers (default: empty list)
						));
					out.append(yamlString);
				}
			}

			try (InputStream in = new BufferedInputStream(Files.newInputStream(idpPath))) {
				fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);

				String password = ExpressionContext.create()
						.setVariable("config", getConfig())
						.defaultIfEmpty("config.keystorePassword", DEFAULT_KEYSTORE_PASSWORD);
				if (!Activator.getDefault().getCryptoService().isEncrypted(password)) {
					((Map<String, Object>) fConfig.get("keystore")).put("password", Activator.getDefault().getCryptoService().encrypt(password));
					try (Writer out = Files.newBufferedWriter(idpPath, StandardCharsets.UTF_8)) {
						String yamlString = new Dump(DumpSettings.builder().build()).dumpToString(fConfig);
						out.append(yamlString);
					}
					log.info("The keystorePassword parameter has been encrypted and the configuration file has been updated.");
				}
			}
		}
		return fConfig;
	}

	public Path getConfigPath() {
		return Activator.getDefault().getCmsService().getEtcPath().normalize();
	}

	public String getBaseURL() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.baseUrl", DEFAULT_BASE_URL);
		} catch (Throwable ex) {
			log.warn("The baseUrl parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_BASE_URL;
	}

	public String geContextPath() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.contextPath", DEFAULT_CONTEXT_PATH);
		} catch (Throwable ex) {
			log.warn("The contextPath parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_CONTEXT_PATH;
	}

	public String getRoleAttribute() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.roleAttribute", DEFAULT_ROLE_ATTRIBUTE);
		} catch (Throwable ex) {
			log.warn("The roleAttribute parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_ROLE_ATTRIBUTE;
	}

	public String getKeystoreType() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.keystoreType", DEFAULT_KEYSTORE_TYPE);
		} catch (Throwable ex) {
			log.warn("The keystoreType parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_KEYSTORE_TYPE;
	}

	public String getKeystorePassword() {
		try {
			String password = ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.keystorePassword", DEFAULT_KEYSTORE_PASSWORD);
			return Activator.getDefault().getCryptoService().decrypt(password);
		} catch (Throwable ex) {
			log.warn("The keystorePassword parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_KEYSTORE_PASSWORD;
	}

	public String getKeystoreAlias() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.keystoreKeyAlias", DEFAULT_KEYSTORE_ALIAS);
		} catch (Throwable ex) {
			log.warn("The keystoreKeyAlias parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_KEYSTORE_ALIAS;
	}

	public String getDistinguishedName() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.certificateDistinguishedName", DEFAULT_DISTINGUISHED_NAME);
		} catch (Throwable ex) {
			log.warn("The certificateDistinguishedName parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_DISTINGUISHED_NAME;
	}

	public String getKeyAlgorithm() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.certificateKeyAlgorithm", DEFAULT_KEY_ALGORITHM);
		} catch (Throwable ex) {
			log.warn("The certificateKeyAlgorithm parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_KEY_ALGORITHM;
	}

	public int getKeySize() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.getInt("config.certificateKeySize", DEFAULT_KEY_SIZE);
		} catch (Throwable ex) {
			log.warn("The certificateKeySize parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_KEY_SIZE;
	}

	public int getCertificateValidity() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.getInt("config.certificateValidity", DEFAULT_CERTIFICATE_VALIDITY);
		} catch (Throwable ex) {
			log.warn("The certificateValidity parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_CERTIFICATE_VALIDITY;
	}

	public String getSignatureAlgorithm() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.certificateSignatureAlgorithm", DEFAULT_SIGNATURE_ALGORITHM);
		} catch (Throwable ex) {
			log.warn("The certificateSignatureAlgorithm parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_SIGNATURE_ALGORITHM;
	}

	public int getAssertionValiditySeconds() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.getInt("config.assertionValiditySeconds", DEFAULT_ASSERTION_VALIDITY_SECONDS);
		} catch (Throwable ex) {
			log.warn("The assertionValiditySeconds parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_ASSERTION_VALIDITY_SECONDS;
	}

	public String getEntityId() {
		return getBaseURL() + geContextPath();
	}

	public String getSsoPath() {
		return geContextPath() + "/sso";
	}

	public String getSsoUrl() {
		return getBaseURL() + getSsoPath();
	}

	public String getSloUrl() {
		return getBaseURL() + geContextPath() + "/slo";
	}

	public String getMetadataPath() {
		return geContextPath() + "/metadata";
	}

	public String getMetadataUrl() {
		return getBaseURL() + getMetadataPath();
	}

	public String getLoginPath() {
		return geContextPath() + "/login";
	}

	public String getLoginUrl() {
		return getBaseURL() + getLoginPath();
	}

	public List<TrustedSP> getTrustedSPs() {
		try {
			List<?> entries = ExpressionContext.create()
					.setVariable("config", getConfig())
					.getList("config.trustedSPs");
			if (entries == null) {
				return Collections.emptyList();
			}

			List<TrustedSP> trustedSPs = new ArrayList<>();
			for (Object e : entries) {
				if (e instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> spConfig = (Map<String, Object>) e;
					String entityId = (String) spConfig.get("entityId");
					String acsUrl = (String) spConfig.get("acsUrl");
					if (entityId != null && acsUrl != null) {
						trustedSPs.add(new TrustedSP(entityId, acsUrl));
					} else {
						log.warn("Invalid trusted SP configuration: {}", spConfig);
					}
				} else {
					log.warn("Invalid trusted SP entry: {}", e);
				}
			}
			return trustedSPs;
		} catch (Throwable ex) {
			log.warn("The trustedSPs parameter is invalid. Default values will be used instead.");
		}
		return Collections.emptyList();
	}

	/**
	 * Checks if the given SP entity ID is trusted.
	 * If no trusted SPs are configured, all SPs are trusted (starter mode).
	 */
	public boolean isTrustedSP(String spEntityId) {
		List<TrustedSP> trustedSPs = getTrustedSPs();
		if (trustedSPs.isEmpty()) {
			return true;
		}

		for (TrustedSP sp : trustedSPs) {
			if (sp.getEntityId().equals(spEntityId)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Represents a trusted Service Provider.
	 */
	public static class TrustedSP {
		private String entityId;
		private String acsUrl;

		public TrustedSP() {
		}

		public TrustedSP(String entityId, String acsUrl) {
			this.entityId = entityId;
			this.acsUrl = acsUrl;
		}

		public String getEntityId() {
			return entityId;
		}

		public void setEntityId(String entityId) {
			this.entityId = entityId;
		}

		public String getAcsUrl() {
			return acsUrl;
		}

		public void setAcsUrl(String acsUrl) {
			this.acsUrl = acsUrl;
		}
	}

}

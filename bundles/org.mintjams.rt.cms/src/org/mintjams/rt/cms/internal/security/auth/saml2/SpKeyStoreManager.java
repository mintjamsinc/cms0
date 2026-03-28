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

package org.mintjams.rt.cms.internal.security.auth.saml2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.rt.cms.internal.CmsService;

public class SpKeyStoreManager {

	private static final String DEFAULT_KEYSTORE_TYPE = "PKCS12";
	private static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";
	private static final String DEFAULT_KEYSTORE_ALIAS = "sp-signing";
	private static final String DEFAULT_SUBJECT_DN = "CN=Sample Service Provider, OU=Quick Start, O=Open Components Project";
	private static final String DEFAULT_KEY_ALGORITHM = "RSA";
	private static final int DEFAULT_KEY_SIZE = 2048;
	private static final String DEFAULT_SIGNATURE_ALGORITHM = "SHA256withRSA";
	private static final int DEFAULT_VALIDITY = 7300;

	private final Path fKeyStorePath;
	private final Map<String, Object> fConfig;
	private KeyPair fKeyPair;
	private X509Certificate fCertificate;

	public SpKeyStoreManager(Path configPath, Map<String, Object> config) throws IOException {
		fKeyStorePath = configPath.resolve("sp-keystore.p12");
		fConfig = config;
		if (Files.exists(fKeyStorePath)) {
			loadKeyStore();
			CmsService.getLogger(getClass()).info("Loaded SP signing certificate from: " + fKeyStorePath.toAbsolutePath());
		} else {
			generateAndSave();
			CmsService.getLogger(getClass()).info("Generated new SP signing certificate: " + fKeyStorePath.toAbsolutePath());
		}
	}

	private ExpressionContext el() {
		return ExpressionContext.create().setVariable("config", fConfig);
	}

	private String getKeystoreType() {
		return el().defaultIfEmpty("config.keystore.type", DEFAULT_KEYSTORE_TYPE);
	}

	private String getKeystorePassword() {
		return el().defaultIfEmpty("config.keystore.password", DEFAULT_KEYSTORE_PASSWORD);
	}

	private String getKeystoreAlias() {
		return el().defaultIfEmpty("config.keystore.alias", DEFAULT_KEYSTORE_ALIAS);
	}

	private String getSubjectDN() {
		return el().defaultIfEmpty("config.certificateTemplate.subjectDN", DEFAULT_SUBJECT_DN);
	}

	private String getKeyAlgorithm() {
		return el().defaultIfEmpty("config.certificateTemplate.keyAlgorithm", DEFAULT_KEY_ALGORITHM);
	}

	private int getKeySize() {
		return el().getInt("config.certificateTemplate.keySize", DEFAULT_KEY_SIZE);
	}

	private String getSignatureAlgorithm() {
		return el().defaultIfEmpty("config.certificateTemplate.signatureAlgorithm", DEFAULT_SIGNATURE_ALGORITHM);
	}

	private int getCertificateValidity() {
		return el().getInt("config.certificateTemplate.validity", DEFAULT_VALIDITY);
	}

	private void loadKeyStore() throws IOException {
		try {
			KeyStore ks = KeyStore.getInstance(getKeystoreType());
			try (InputStream in = Files.newInputStream(fKeyStorePath)) {
				ks.load(in, getKeystorePassword().toCharArray());
			}
			PrivateKey privateKey = (PrivateKey) ks.getKey(getKeystoreAlias(), getKeystorePassword().toCharArray());
			fCertificate = (X509Certificate) ks.getCertificate(getKeystoreAlias());
			fKeyPair = new KeyPair(fCertificate.getPublicKey(), privateKey);
		} catch (IOException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw new IllegalStateException("Failed to load SP signing certificate from: " + fKeyStorePath, ex);
		}
	}

	private void generateAndSave() throws IOException {
		try {
			KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance(getKeyAlgorithm());
			keyPairGen.initialize(getKeySize(), new SecureRandom());
			fKeyPair = keyPairGen.generateKeyPair();

			Instant now = Instant.now();
			X500Name issuer = new X500Name(getSubjectDN());
			BigInteger serial = BigInteger.valueOf(now.toEpochMilli());
			X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
					issuer,
					serial,
					Date.from(now),
					Date.from(now.plus(Duration.ofDays(getCertificateValidity()))),
					issuer,
					fKeyPair.getPublic());

			ContentSigner signer = new JcaContentSignerBuilder(getSignatureAlgorithm()).build(fKeyPair.getPrivate());
			fCertificate = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

			KeyStore ks = KeyStore.getInstance(getKeystoreType());
			ks.load(null, getKeystorePassword().toCharArray());
			ks.setKeyEntry(getKeystoreAlias(), fKeyPair.getPrivate(), getKeystorePassword().toCharArray(),
					new X509Certificate[] { fCertificate });

			try (OutputStream out = Files.newOutputStream(fKeyStorePath)) {
				ks.store(out, getKeystorePassword().toCharArray());
			}
		} catch (IOException ex) {
			throw ex;
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error("Failed to generate SP signing certificate: " + ex.getMessage());
			throw new IllegalStateException("Failed to generate SP signing certificate", ex);
		}
	}

	public X509Certificate getCertificate() {
		return fCertificate;
	}

	public PrivateKey getPrivateKey() {
		return fKeyPair.getPrivate();
	}

}

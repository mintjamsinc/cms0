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

package org.mintjams.idp.security;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the IdP signing key pair and certificate.
 * Generates a self-signed certificate on first run using BouncyCastle,
 * and persists it in a Java KeyStore file.
 */
public class KeyStoreManager {

	private static final Logger LOG = LoggerFactory.getLogger(KeyStoreManager.class);

	private static final String KEYSTORE_TYPE = "PKCS12";
	private static final String KEY_ALIAS = "idp-signing";
	private static final int KEY_SIZE = 2048;
	private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
	private static final Duration CERTIFICATE_VALIDITY = Duration.ofDays(3650); // 10 years

	private final File keystoreFile;
	private final char[] keystorePassword;
	private KeyPair keyPair;
	private X509Certificate certificate;

	public KeyStoreManager(File keystoreFile, String keystorePassword) {
		this.keystoreFile = keystoreFile;
		this.keystorePassword = keystorePassword.toCharArray();
	}

	/**
	 * Initializes the key store. Loads existing keys or generates new ones.
	 */
	public void init() throws Exception {
		if (keystoreFile.exists()) {
			loadKeyStore();
			LOG.info("Loaded IdP signing certificate from: {}", keystoreFile.getAbsolutePath());
		} else {
			generateAndSave();
			LOG.info("Generated new IdP signing certificate: {}", keystoreFile.getAbsolutePath());
		}
	}

	private void loadKeyStore() throws Exception {
		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
		try (FileInputStream fis = new FileInputStream(keystoreFile)) {
			ks.load(fis, keystorePassword);
		}
		PrivateKey privateKey = (PrivateKey) ks.getKey(KEY_ALIAS, keystorePassword);
		certificate = (X509Certificate) ks.getCertificate(KEY_ALIAS);
		keyPair = new KeyPair(certificate.getPublicKey(), privateKey);
	}

	private void generateAndSave() throws Exception {
		// Generate RSA key pair
		KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");
		keyPairGen.initialize(KEY_SIZE, new SecureRandom());
		keyPair = keyPairGen.generateKeyPair();

		// Build self-signed certificate using BouncyCastle
		Instant now = Instant.now();
		X500Name issuer = new X500Name("CN=MintJams IdP, O=MintJams Inc.");
		BigInteger serial = BigInteger.valueOf(now.toEpochMilli());

		X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
				issuer,
				serial,
				Date.from(now),
				Date.from(now.plus(CERTIFICATE_VALIDITY)),
				issuer,
				keyPair.getPublic());

		ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
				.build(keyPair.getPrivate());

		X509CertificateHolder certHolder = certBuilder.build(signer);
		certificate = new JcaX509CertificateConverter().getCertificate(certHolder);

		// Save to KeyStore
		keystoreFile.getParentFile().mkdirs();
		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
		ks.load(null, keystorePassword);
		ks.setKeyEntry(KEY_ALIAS, keyPair.getPrivate(), keystorePassword,
				new X509Certificate[] { certificate });

		try (FileOutputStream fos = new FileOutputStream(keystoreFile)) {
			ks.store(fos, keystorePassword);
		}
	}

	public X509Certificate getCertificate() {
		return certificate;
	}

	public PrivateKey getPrivateKey() {
		return keyPair.getPrivate();
	}

	public KeyPair getKeyPair() {
		return keyPair;
	}

}

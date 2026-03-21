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

package org.mintjams.idp.internal.security;

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

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.mintjams.idp.internal.Activator;
import org.mintjams.idp.internal.IdpConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileKeyStoreManager implements KeyStoreManager {

	private static final Logger log = LoggerFactory.getLogger(FileKeyStoreManager.class);

	private final Path fKeystorePath;
	private KeyPair fKeyPair;
	private X509Certificate fCertificate;

	public FileKeyStoreManager() throws Exception {
		IdpConfiguration config = Activator.getDefault().getConfiguration();
		fKeystorePath = config.getConfigPath().resolve("idp.p12");
		if (!Files.exists(fKeystorePath)) {
			loadKeyStore();
			log.info("Loaded IdP signing certificate from: {}", fKeystorePath.toFile().getAbsolutePath());
		} else {
			generateAndSave();
			log.info("Generated new IdP signing certificate: {}", fKeystorePath.toFile().getAbsolutePath());
		}
	}

	private void loadKeyStore() throws Exception {
		IdpConfiguration config = Activator.getDefault().getConfiguration();

		KeyStore ks = KeyStore.getInstance(config.getKeystoreType());
		try (InputStream in = Files.newInputStream(fKeystorePath)) {
			ks.load(in, config.getKeystorePassword().toCharArray());
		}

		PrivateKey privateKey = (PrivateKey) ks.getKey(config.getKeystoreAlias(), config.getKeystorePassword().toCharArray());
		fCertificate = (X509Certificate) ks.getCertificate(config.getKeystoreAlias());
		fKeyPair = new KeyPair(fCertificate.getPublicKey(), privateKey);
	}

	private void generateAndSave() throws Exception {
		IdpConfiguration config = Activator.getDefault().getConfiguration();

		KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance(config.getKeyAlgorithm());
		keyPairGen.initialize(config.getKeySize(), new SecureRandom());
		fKeyPair = keyPairGen.generateKeyPair();

		Instant now = Instant.now();
		X500Name issuer = new X500Name(config.getSubjectDN());
		BigInteger serial = BigInteger.valueOf(now.toEpochMilli());
		X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
				issuer,
				serial,
				Date.from(now),
				Date.from(now.plus(Duration.ofDays(config.getCertificateValidity()))),
				issuer,
				fKeyPair.getPublic());

		ContentSigner signer = new JcaContentSignerBuilder(config.getSignatureAlgorithm()).build(fKeyPair.getPrivate());

		X509CertificateHolder certHolder = certBuilder.build(signer);
		fCertificate = new JcaX509CertificateConverter().getCertificate(certHolder);

		// Save to KeyStore
		KeyStore ks = KeyStore.getInstance(config.getKeystoreType());
		ks.load(null, config.getKeystorePassword().toCharArray());
		ks.setKeyEntry(config.getKeystoreAlias(), fKeyPair.getPrivate(), config.getKeystorePassword().toCharArray(), new X509Certificate[] { fCertificate });

		try (OutputStream out = Files.newOutputStream(fKeystorePath)) {
			ks.store(out, config.getKeystorePassword().toCharArray());
		}
	}

	public X509Certificate getCertificate() {
		return fCertificate;
	}

	public PrivateKey getPrivateKey() {
		return fKeyPair.getPrivate();
	}

	public KeyPair getKeyPair() {
		return fKeyPair;
	}

}

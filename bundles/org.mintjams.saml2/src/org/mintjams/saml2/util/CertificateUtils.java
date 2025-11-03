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

package org.mintjams.saml2.util;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import org.mintjams.saml2.exception.Saml2Exception;

/**
 * Utility class for certificate and key operations.
 */
public class CertificateUtils {

	/**
	 * Parses a PEM formatted X.509 certificate.
	 *
	 * @param pemCertificate the PEM formatted certificate string
	 * @return the X509Certificate
	 * @throws Saml2Exception if parsing fails
	 */
	public static X509Certificate parseCertificate(String pemCertificate) throws Saml2Exception {
		try {
			String cert = pemCertificate
					.replace("-----BEGIN CERTIFICATE-----", "")
					.replace("-----END CERTIFICATE-----", "")
					.replaceAll("\\s", "");

			byte[] decoded = Base64.getDecoder().decode(cert);
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(decoded));
		} catch (Exception ex) {
			throw new Saml2Exception("Failed to parse certificate", ex);
		}
	}

	/**
	 * Parses a PEM formatted private key.
	 *
	 * @param pemPrivateKey the PEM formatted private key string
	 * @return the PrivateKey
	 * @throws Saml2Exception if parsing fails
	 */
	public static PrivateKey parsePrivateKey(String pemPrivateKey) throws Saml2Exception {
		try {
			String key = pemPrivateKey
					.replace("-----BEGIN PRIVATE KEY-----", "")
					.replace("-----END PRIVATE KEY-----", "")
					.replace("-----BEGIN RSA PRIVATE KEY-----", "")
					.replace("-----END RSA PRIVATE KEY-----", "")
					.replaceAll("\\s", "");

			byte[] decoded = Base64.getDecoder().decode(key);
			PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
			KeyFactory keyFactory = KeyFactory.getInstance("RSA");
			return keyFactory.generatePrivate(keySpec);
		} catch (Exception ex) {
			throw new Saml2Exception("Failed to parse private key", ex);
		}
	}

	/**
	 * Converts a certificate to PEM format.
	 *
	 * @param certificate the X509Certificate
	 * @return the PEM formatted certificate string
	 * @throws Saml2Exception if conversion fails
	 */
	public static String certificateToPem(X509Certificate certificate) throws Saml2Exception {
		try {
			String encoded = Base64.getEncoder().encodeToString(certificate.getEncoded());
			StringBuilder pem = new StringBuilder();
			pem.append("-----BEGIN CERTIFICATE-----\n");
			int index = 0;
			while (index < encoded.length()) {
				int endIndex = Math.min(index + 64, encoded.length());
				pem.append(encoded.substring(index, endIndex)).append("\n");
				index = endIndex;
			}
			pem.append("-----END CERTIFICATE-----\n");
			return pem.toString();
		} catch (Exception ex) {
			throw new Saml2Exception("Failed to convert certificate to PEM", ex);
		}
	}

}

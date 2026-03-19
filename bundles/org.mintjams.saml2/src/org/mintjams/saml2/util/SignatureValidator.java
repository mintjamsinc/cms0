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

import java.security.Key;
import java.security.cert.X509Certificate;
import java.util.Iterator;

import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;

import org.mintjams.saml2.exception.ValidationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Validates XML digital signatures on SAML Response/Assertion elements.
 *
 * <p>This class should be used by the SP to verify that the SAML Response
 * received from the IdP is genuinely signed by the trusted IdP certificate.</p>
 *
 * <p>Usage in Saml2ResponseProcessor:</p>
 * <pre>
 * // After parsing the document, before extracting attributes:
 * SignatureValidator.validate(document, settings.getIdpCertificate());
 * </pre>
 */
public class SignatureValidator {

	private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";
	private static final String SAML2_ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";

	/**
	 * Validates the XML signature on the SAML Response or Assertion.
	 *
	 * @param document the SAML Response document
	 * @param trustedCertificate the IdP's trusted X509 certificate
	 * @throws ValidationException if signature is missing, invalid, or not trusted
	 */
	public static void validate(Document document, X509Certificate trustedCertificate) throws ValidationException {
		if (trustedCertificate == null) {
			// No certificate configured - skip validation (for backward compatibility)
			return;
		}

		try {
			// Find Signature element
			NodeList signatureNodes = document.getElementsByTagNameNS(DS_NS, "Signature");
			if (signatureNodes.getLength() == 0) {
				throw new ValidationException("No XML signature found in SAML Response");
			}

			Element signatureElement = (Element) signatureNodes.item(0);

			// Mark ID attributes on Assertion and Response for reference resolution
			markIdAttributes(document);

			// Create validation context
			DOMValidateContext validateContext = new DOMValidateContext(
					new TrustedCertificateKeySelector(trustedCertificate),
					signatureElement);

			// Validate
			XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
			XMLSignature signature = factory.unmarshalXMLSignature(validateContext);

			boolean coreValid = signature.validate(validateContext);
			if (!coreValid) {
				// Detailed validation for diagnostics
				boolean signatureValid = signature.getSignatureValue().validate(validateContext);
				StringBuilder detail = new StringBuilder("Signature validation failed.");
				if (!signatureValid) {
					detail.append(" SignatureValue is invalid.");
				}

				@SuppressWarnings("unchecked")
				Iterator<Reference> refs = signature.getSignedInfo().getReferences().iterator();
				int refIndex = 0;
				while (refs.hasNext()) {
					Reference ref = refs.next();
					boolean refValid = ref.validate(validateContext);
					if (!refValid) {
						detail.append(" Reference[").append(refIndex).append("] (")
								.append(ref.getURI()).append(") digest is invalid.");
					}
					refIndex++;
				}

				throw new ValidationException(detail.toString());
			}

		} catch (ValidationException e) {
			throw e;
		} catch (Exception e) {
			throw new ValidationException("Signature validation error: " + e.getMessage());
		}
	}

	/**
	 * Marks ID attributes on Assertion and Response elements so that
	 * the signature reference URI (#id) can resolve them.
	 */
	private static void markIdAttributes(Document document) {
		// Mark Assertion ID
		NodeList assertions = document.getElementsByTagNameNS(SAML2_ASSERTION_NS, "Assertion");
		for (int i = 0; i < assertions.getLength(); i++) {
			Element assertion = (Element) assertions.item(i);
			if (assertion.hasAttribute("ID")) {
				assertion.setIdAttribute("ID", true);
			}
		}

		// Mark Response ID
		Element root = document.getDocumentElement();
		if (root.hasAttribute("ID")) {
			root.setIdAttribute("ID", true);
		}
	}

	/**
	 * KeySelector that only accepts the pre-configured trusted certificate.
	 * This prevents an attacker from embedding their own certificate in the
	 * KeyInfo and signing with it.
	 */
	private static class TrustedCertificateKeySelector extends KeySelector {
		private final X509Certificate trustedCertificate;

		TrustedCertificateKeySelector(X509Certificate trustedCertificate) {
			this.trustedCertificate = trustedCertificate;
		}

		@Override
		public KeySelectorResult select(KeyInfo keyInfo, Purpose purpose,
				AlgorithmMethod method, XMLCryptoContext context) throws KeySelectorException {
			// Always return the trusted certificate's public key,
			// regardless of what's in the KeyInfo.
			// This is critical for security - we trust our configured certificate,
			// not whatever the message claims.
			final Key key = trustedCertificate.getPublicKey();
			return () -> key;
		}
	}

}

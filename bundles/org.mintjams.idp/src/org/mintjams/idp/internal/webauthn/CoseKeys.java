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

package org.mintjams.idp.internal.webauthn;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

/**
 * COSE key (RFC 8152) decoding for the algorithms the identity provider
 * offers to authenticators: ES256 (-7), RS256 (-257) and EdDSA/Ed25519 (-8).
 * The JDK's providers cover all three, so no extra cryptography library is
 * involved.
 */
public final class CoseKeys {

	/** COSE algorithm identifiers offered in {@code pubKeyCredParams}, in order of preference. */
	public static final List<Long> SUPPORTED_ALGORITHMS = List.of(-7L, -257L, -8L);

	private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

	private static final long KTY_OKP = 1;
	private static final long KTY_EC2 = 2;
	private static final long KTY_RSA = 3;
	private static final long CRV_P256 = 1;
	private static final long CRV_ED25519 = 6;

	private CoseKeys() {}

	/**
	 * Decodes a CBOR-encoded COSE key. Trailing bytes (authenticator extensions
	 * that follow the key in attested credential data) are ignored.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> decode(byte[] cbor) throws WebAuthnException {
		try {
			return CBOR.readValue(cbor, Map.class);
		} catch (Exception ex) {
			throw new WebAuthnException("The credential public key is not a valid COSE key.", ex);
		}
	}

	/**
	 * The COSE algorithm identifier of a key.
	 */
	public static long algorithm(Map<String, Object> cose) throws WebAuthnException {
		return longValue(cose, "3", "alg");
	}

	/**
	 * The JCA signature algorithm that verifies signatures made with the key.
	 */
	public static String signatureAlgorithm(long alg) throws WebAuthnException {
		if (alg == -7) {
			return "SHA256withECDSA";
		}
		if (alg == -257) {
			return "SHA256withRSA";
		}
		if (alg == -8) {
			return "Ed25519";
		}
		throw new WebAuthnException("Unsupported COSE algorithm: " + alg);
	}

	/**
	 * Converts a decoded COSE key to a JCA public key.
	 */
	public static PublicKey toPublicKey(Map<String, Object> cose) throws WebAuthnException {
		long kty = longValue(cose, "1", "kty");
		long alg = algorithm(cose);
		try {
			if (kty == KTY_EC2) {
				if (alg != -7 || longValue(cose, "-1", "crv") != CRV_P256) {
					throw new WebAuthnException("Unsupported EC2 key (alg " + alg + ").");
				}
				AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
				params.init(new ECGenParameterSpec("secp256r1"));
				ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
				ECPoint point = new ECPoint(new BigInteger(1, bytes(cose, "-2", "x")), new BigInteger(1, bytes(cose, "-3", "y")));
				return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
			}
			if (kty == KTY_RSA) {
				if (alg != -257) {
					throw new WebAuthnException("Unsupported RSA key (alg " + alg + ").");
				}
				BigInteger n = new BigInteger(1, bytes(cose, "-1", "n"));
				BigInteger e = new BigInteger(1, bytes(cose, "-2", "e"));
				return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
			}
			if (kty == KTY_OKP) {
				if (alg != -8 || longValue(cose, "-1", "crv") != CRV_ED25519) {
					throw new WebAuthnException("Unsupported OKP key (alg " + alg + ").");
				}
				byte[] encoded = bytes(cose, "-2", "x");
				if (encoded.length != 32) {
					throw new WebAuthnException("An Ed25519 public key must be 32 bytes.");
				}
				// RFC 8032 encoding: little-endian y with the parity of x in the top bit.
				byte[] reversed = new byte[32];
				for (int i = 0; i < 32; i++) {
					reversed[i] = encoded[31 - i];
				}
				boolean xOdd = (reversed[0] & 0x80) != 0;
				reversed[0] &= 0x7f;
				EdECPoint point = new EdECPoint(xOdd, new BigInteger(1, reversed));
				return KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
			}
		} catch (WebAuthnException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new WebAuthnException("The credential public key could not be decoded.", ex);
		}
		throw new WebAuthnException("Unsupported COSE key type: " + kty);
	}

	private static long longValue(Map<String, Object> cose, String key, String name) throws WebAuthnException {
		Object value = cose.get(key);
		if (!(value instanceof Number)) {
			throw new WebAuthnException("The COSE key has no " + name + ".");
		}
		return ((Number) value).longValue();
	}

	private static byte[] bytes(Map<String, Object> cose, String key, String name) throws WebAuthnException {
		Object value = cose.get(key);
		if (!(value instanceof byte[])) {
			throw new WebAuthnException("The COSE key has no " + name + ".");
		}
		return (byte[]) value;
	}

}

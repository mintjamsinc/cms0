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

package org.mintjams.cms.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM encryptor for values stored in configuration files and content,
 * producing {@code ENC[<tag>:<base64>]} where the base64 part is the
 * initialization vector followed by the ciphertext.
 *
 * <p>This lives in the API bundle rather than in the CMS runtime because the
 * JCR runtime needs it before the CMS is active: a workspace opens its
 * datasource while {@code org.mintjams.rt.cms} may still be starting, and an
 * encrypted {@code jcr.yml#datasource.password} has to be readable at that
 * point.
 */
public class DefaultEncryptor implements Encryptor {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	/** Opens an encrypted value; {@code ENC[<tag>:<base64>]}. */
	public static final String PREFIX = "ENC[";

	/** Closes an encrypted value. */
	public static final String SUFFIX = "]";


	private static final String CURRENT_TAG = "v1";
	private static final int IV_LENGTH = 12;
	private static final int TAG_LENGTH = 128;

	private final SecureRandom fRandom = new SecureRandom();
	private final SecretKeyProvider fSecretKeyProvider;

	public DefaultEncryptor(SecretKeyProvider secretKeyProvider) {
		fSecretKeyProvider = secretKeyProvider;
	}

	@Override
	public String encrypt(String input) {
		try {
			byte[] iv = new byte[IV_LENGTH];
			fRandom.nextBytes(iv);

			SecretKey key = fSecretKeyProvider.getSecretKey(CURRENT_TAG);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH, iv);
			cipher.init(Cipher.ENCRYPT_MODE, key, spec);

			byte[] encrypted = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));

			byte[] combined = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

			return PREFIX + CURRENT_TAG + ":" + Base64.getEncoder().encodeToString(combined) + SUFFIX;
		} catch (Throwable ex) {
			throw new IllegalStateException("Encryption failed", ex);
		}
	}

	@Override
	public String decrypt(String input) {
		if (input == null || !input.startsWith(PREFIX)) {
			return input;
		}
		if (!input.endsWith(SUFFIX)) {
			throw new IllegalStateException("Malformed encrypted value: missing '" + SUFFIX + "'");
		}

		try {
			String body = input.substring(PREFIX.length(), input.length() - SUFFIX.length());
			int separator = body.indexOf(':');
			if (separator < 0) {
				throw new IllegalArgumentException("Malformed encrypted value: no key tag");
			}
			String tag = body.substring(0, separator);
			byte[] combined = Base64.getDecoder().decode(body.substring(separator + 1));

			byte[] iv = new byte[IV_LENGTH];
			byte[] ciphertext = new byte[combined.length - IV_LENGTH];

			System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
			System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

			SecretKey key = fSecretKeyProvider.getSecretKey(tag);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH, iv);
			cipher.init(Cipher.DECRYPT_MODE, key, spec);

			byte[] decrypted = cipher.doFinal(ciphertext);

			return new String(decrypted, StandardCharsets.UTF_8);
		} catch (Throwable ex) {
			// Neither the ciphertext nor the plaintext may reach the log.
			throw new IllegalStateException("Decryption failed", ex);
		}
	}

	@Override
	public boolean isEncrypted(String input) {
		return isEncryptedValue(input);
	}

	/**
	 * Whether a value is an encrypted one, answered without a key: callers
	 * that only want to know whether decryption is needed must not bring the
	 * secret key into existence by asking.
	 */
	public static boolean isEncryptedValue(String input) {
		return input != null && input.startsWith(PREFIX) && input.endsWith(SUFFIX);
	}

}

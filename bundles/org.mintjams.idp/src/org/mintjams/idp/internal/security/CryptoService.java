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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.apache.commons.lang3.StringUtils;
import org.mintjams.idp.internal.Activator;

public class CryptoService {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int IV_LENGTH = 12;
	private static final int TAG_LENGTH = 128;

	private final SecureRandom random = new SecureRandom();

	public String encrypt(String plaintext) throws Exception {
		byte[] iv = new byte[IV_LENGTH];
		random.nextBytes(iv);

		SecretKey key = Activator.getDefault().getSecretKeyProvider().getKey("v1");
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH, iv);
		cipher.init(Cipher.ENCRYPT_MODE, key, spec);

		byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

		byte[] combined = new byte[iv.length + encrypted.length];
		System.arraycopy(iv, 0, combined, 0, iv.length);
		System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

		return "ENC[v1:" + Base64.getEncoder().encodeToString(combined) + "]";
	}

	public String decrypt(String input) throws Exception {
		if (!input.startsWith("ENC[")) {
			return input;
		}

		String[] v = StringUtils.split(input.substring(4, input.length() - 1), ":");
		String tag = v[0];
		String base64 = v[1];
		byte[] combined = Base64.getDecoder().decode(base64);

		byte[] iv = new byte[IV_LENGTH];
		byte[] ciphertext = new byte[combined.length - IV_LENGTH];

		System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
		System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

		SecretKey key = Activator.getDefault().getSecretKeyProvider().getKey(tag);
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH, iv);
		cipher.init(Cipher.DECRYPT_MODE, key, spec);

		byte[] decrypted = cipher.doFinal(ciphertext);

		return new String(decrypted, StandardCharsets.UTF_8);
	}

	public boolean isEncrypted(String input) {
		return input != null && input.startsWith("ENC[") && input.endsWith("]");
	}

}

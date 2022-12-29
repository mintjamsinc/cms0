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

package org.mintjams.rt.cms.internal.script;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.lang.Strings;

public class Mask {

	private static final int KEY_LENGTH = 128;
	private static final String MASK_AES = "{AES}";

	private Mask() {}

	private static SecretKey getSecretKey(String phrases) throws GeneralSecurityException, IOException {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		md.update(CmsService.getBootIdentifier().getBytes(StandardCharsets.UTF_8));
		PBEKeySpec keySpec = new PBEKeySpec(phrases.toCharArray(), md.digest(), 10240, KEY_LENGTH);
		SecretKey key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec);
		return new SecretKeySpec(key.getEncoded(), "AES");
	}

	public static String mask(String plain, String phrases) throws GeneralSecurityException, IOException {
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(phrases));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(cipher.getParameters().getParameterSpec(IvParameterSpec.class).getIV());
		out.write(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
		return MASK_AES + new String(Base64.encodeBase64(out.toByteArray()), StandardCharsets.ISO_8859_1);
	}

	public static boolean isMasked(String text) {
		if (Strings.isEmpty(text)) {
			return false;
		}
		return text.startsWith(MASK_AES);
	}

	public static String unmask(String masked, String phrases) throws GeneralSecurityException, IOException {
		if (!isMasked(masked)) {
			return masked;
		}

		byte[] encrypted = Base64.decodeBase64(masked.substring(MASK_AES.length()));
		byte[] iv = new byte[KEY_LENGTH / 8];
		System.arraycopy(encrypted, 0, iv, 0, iv.length);
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, getSecretKey(phrases), new IvParameterSpec(iv));
		return new String(cipher.doFinal(encrypted, iv.length, encrypted.length - iv.length), StandardCharsets.UTF_8);
	}

}

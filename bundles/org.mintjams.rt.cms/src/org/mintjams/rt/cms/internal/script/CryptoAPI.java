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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.mintjams.jcr.util.FileCache;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.script.ScriptingContext;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Strings;

public class CryptoAPI {

	private static final int KEY_LENGTH = 128;
	private static final String CRYPT_AES = "{AES}";

	public CryptoAPI() {}

	public static CryptoAPI get(ScriptingContext context) {
		return (CryptoAPI) context.getAttribute(CryptoAPI.class.getSimpleName());
	}

	private SecretKey getSecretKey(String phrases) throws GeneralSecurityException, IOException {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		md.update(CmsService.getBootIdentifier().getBytes(StandardCharsets.UTF_8));
		PBEKeySpec keySpec = new PBEKeySpec(phrases.toCharArray(), md.digest(), 10240, KEY_LENGTH);
		SecretKey key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec);
		return new SecretKeySpec(key.getEncoded(), "AES");
	}

	public String encrypt(String value, String phrases) throws GeneralSecurityException, IOException {
		return newEncryptor().setPhrases(phrases).encrypt(value);
	}

	public InputStream encrypt(InputStream value, String phrases) throws GeneralSecurityException, IOException {
		return newEncryptor().setPhrases(phrases).encrypt(value);
	}

	public byte[] encrypt(byte[] value, String phrases) throws GeneralSecurityException, IOException {
		return newEncryptor().setPhrases(phrases).encrypt(value);
	}

	public boolean isEncrypted(String value) {
		if (Strings.isEmpty(value)) {
			return false;
		}
		return value.startsWith(CRYPT_AES);
	}

	public String decrypt(String value, String phrases) throws GeneralSecurityException, IOException {
		return newDecryptor().setPhrases(phrases).decrypt(value);
	}

	public InputStream decrypt(InputStream value, String phrases) throws GeneralSecurityException, IOException {
		return newDecryptor().setPhrases(phrases).decrypt(value);
	}

	public byte[] decrypt(byte[] value, String phrases) throws GeneralSecurityException, IOException {
		return newDecryptor().setPhrases(phrases).decrypt(value);
	}

	public Encryptor newEncryptor() {
		return new Encryptor();
	}

	public Decryptor newDecryptor() {
		return new Decryptor();
	}

	public class Encryptor {
		private SecretKey fKey;

		private Encryptor() {}

		public Encryptor setPhrases(String phrases) throws GeneralSecurityException, IOException {
			fKey = getSecretKey(phrases);
			return this;
		}

		public Encryptor setSecretKey(SecretKey key) throws GeneralSecurityException {
			fKey = key;
			return this;
		}

		public String encrypt(String value) throws GeneralSecurityException, IOException {
			Objects.requireNonNull(fKey);
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, fKey);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(cipher.getParameters().getParameterSpec(IvParameterSpec.class).getIV());
			out.write(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
			return CRYPT_AES + new String(Base64.encodeBase64(out.toByteArray()), StandardCharsets.ISO_8859_1);
		}

		public InputStream encrypt(InputStream value) throws GeneralSecurityException, IOException {
			Objects.requireNonNull(fKey);
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, fKey);
			FileCache cache = FileCache.newBuilder(CmsService.getTemporaryDirectoryPath())
					.write(CRYPT_AES)
					.write(cipher.getParameters().getParameterSpec(IvParameterSpec.class).getIV())
					.write(new CipherInputStream(value, cipher))
					.build();
			return cache.getInputStream();
		}

		public byte[] encrypt(byte[] value) throws GeneralSecurityException, IOException {
			try (InputStream in = encrypt(new ByteArrayInputStream(value))) {
				return IOs.toByteArray(in);
			}
		}
	}

	public class Decryptor {
		private SecretKey fKey;

		private Decryptor() {}

		public Decryptor setPhrases(String phrases) throws GeneralSecurityException, IOException {
			fKey = getSecretKey(phrases);
			return this;
		}

		public Decryptor setSecretKey(SecretKey key) throws GeneralSecurityException {
			fKey = key;
			return this;
		}

		public String decrypt(String value) throws GeneralSecurityException {
			Objects.requireNonNull(fKey);

			if (!isEncrypted(value)) {
				return value;
			}

			byte[] encrypted = Base64.decodeBase64(value.substring(CRYPT_AES.length()));
			byte[] iv = new byte[KEY_LENGTH / 8];
			System.arraycopy(encrypted, 0, iv, 0, iv.length);
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, fKey, new IvParameterSpec(iv));
			return new String(cipher.doFinal(encrypted, iv.length, encrypted.length - iv.length), StandardCharsets.UTF_8);
		}

		public InputStream decrypt(InputStream value) throws GeneralSecurityException, IOException {
			Objects.requireNonNull(fKey);

			if (!value.markSupported()) {
				value = new BufferedInputStream(value);
			}
			value.mark(0);
			String prefix = new String(value.readNBytes(5), StandardCharsets.ISO_8859_1);
			if (!isEncrypted(prefix)) {
				value.reset();
				return value;
			}

			byte[] iv = value.readNBytes(KEY_LENGTH / 8);
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, fKey, new IvParameterSpec(iv));
			return new CipherInputStream(value, cipher);
		}

		public byte[] decrypt(byte[] value) throws GeneralSecurityException, IOException {
			try (InputStream in = decrypt(new ByteArrayInputStream(value))) {
				return IOs.toByteArray(in);
			}
		}
	}

}

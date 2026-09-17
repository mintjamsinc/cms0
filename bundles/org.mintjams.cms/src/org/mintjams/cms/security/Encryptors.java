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

/**
 * The encryptor and the secret key of this installation, shared by every
 * bundle that reads or writes {@code ENC[...]} values.
 *
 * <p>Both are created on first use rather than by a bundle activator: the JCR
 * runtime resolves an encrypted {@code jcr.yml#datasource.password} while its
 * own service starts, which is before {@code org.mintjams.rt.cms} is
 * necessarily active. The secret key file itself is created when missing, so
 * the first caller of either method mints it.
 */
public final class Encryptors {

	private static volatile SecretKeyProvider fSecretKeyProvider;
	private static volatile Encryptor fEncryptor;

	private Encryptors() {}

	public static SecretKeyProvider getSecretKeyProvider() {
		SecretKeyProvider provider = fSecretKeyProvider;
		if (provider == null) {
			synchronized (Encryptors.class) {
				provider = fSecretKeyProvider;
				if (provider == null) {
					try {
						provider = new FileSecretKeyProvider();
					} catch (Throwable ex) {
						throw new IllegalStateException("Failed to read the secret key file: "
								+ FileSecretKeyProvider.resolveKeyPath(), ex);
					}
					fSecretKeyProvider = provider;
				}
			}
		}
		return provider;
	}

	public static Encryptor getDefault() {
		Encryptor encryptor = fEncryptor;
		if (encryptor == null) {
			synchronized (Encryptors.class) {
				encryptor = fEncryptor;
				if (encryptor == null) {
					encryptor = new DefaultEncryptor(getSecretKeyProvider());
					fEncryptor = encryptor;
				}
			}
		}
		return encryptor;
	}

}

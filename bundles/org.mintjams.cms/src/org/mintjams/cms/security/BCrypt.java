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

package org.mintjams.cms.security;

import java.security.SecureRandom;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

/**
 * Utility class for hashing and verifying passwords using the bcrypt algorithm.
 *
 * <p>This class provides methods to hash a raw password with a specified cost factor
 * and to verify a raw password against a stored bcrypt hash.</p>
 */
public class BCrypt {

	public static String hash(String raw) {
		return hash(raw, 12);
	}

	public static String hash(String raw, int cost) {
		if (cost < 4 || cost > 31) {
			throw new IllegalArgumentException("Cost factor must be between 4 and 31");
		}

		return OpenBSDBCrypt.generate(raw.toCharArray(), generateSalt(), cost);
	}

	private static byte[] generateSalt() {
		byte[] salt = new byte[16];
		new SecureRandom().nextBytes(salt);
		return salt;
	}

	public static boolean verify(String raw, String hash) {
		return OpenBSDBCrypt.checkPassword(hash, raw.toCharArray());
	}

}

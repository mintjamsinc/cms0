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

package org.mintjams.idp.internal.mfa;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.mintjams.cms.security.BCrypt;

/**
 * Single-use recovery codes for users who lose their authenticator. Codes are
 * shown once and stored as bcrypt hashes; a used code is removed.
 */
public final class BackupCodes {

	/** The number of codes in a set. */
	public static final int COUNT = 10;

	/** Unambiguous characters only: no 0/O, 1/I. */
	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int GROUP = 5;
	private static final SecureRandom RANDOM = new SecureRandom();

	private BackupCodes() {}

	/**
	 * Generates a new set of codes in plain text, formatted {@code XXXXX-XXXXX}.
	 */
	public static List<String> generate() {
		List<String> codes = new ArrayList<>(COUNT);
		for (int i = 0; i < COUNT; i++) {
			StringBuilder sb = new StringBuilder(GROUP * 2 + 1);
			for (int j = 0; j < GROUP * 2; j++) {
				if (j == GROUP) {
					sb.append('-');
				}
				sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
			}
			codes.add(sb.toString());
		}
		return codes;
	}

	/**
	 * Hashes plain-text codes for storage.
	 */
	public static List<String> hash(List<String> codes) {
		List<String> hashes = new ArrayList<>(codes.size());
		for (String code : codes) {
			hashes.add(BCrypt.hash(normalize(code)));
		}
		return hashes;
	}

	/**
	 * Finds the stored hash the code matches.
	 *
	 * @return the index into {@code hashes}, or -1
	 */
	public static int match(List<String> hashes, String code) {
		if (hashes == null || code == null) {
			return -1;
		}
		String normalized = normalize(code);
		if (normalized.length() != GROUP * 2) {
			return -1;
		}
		for (int i = 0; i < hashes.size(); i++) {
			if (BCrypt.verify(normalized, hashes.get(i))) {
				return i;
			}
		}
		return -1;
	}

	private static String normalize(String code) {
		return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
	}

}

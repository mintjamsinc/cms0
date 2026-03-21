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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class for generating secure random passwords.
 *
 * <p>This class provides a method to generate a random password of a specified length,
 * containing a mix of uppercase letters, lowercase letters, digits, and special characters.</p>
 */
public class PasswordGenerator {

	private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
	private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final String DIGIT = "0123456789";
	private static final String SYMBOL = "!@#$%^&*()-_=+[]{};:,.<>?";

	private static final String ALL = LOWER + UPPER + DIGIT + SYMBOL;

	public static String generate(int length) {
		if (length < 12) {
			throw new IllegalArgumentException("Password length must be at least 12 characters");
		}

		SecureRandom random = new SecureRandom();
		List<Character> password = new ArrayList<>();

		password.add(randomChar(LOWER, random));
		password.add(randomChar(UPPER, random));
		password.add(randomChar(DIGIT, random));
		password.add(randomChar(SYMBOL, random));

		for (int i = password.size(); i < length; i++) {
			password.add(randomChar(ALL, random));
		}

		Collections.shuffle(password, random);

		StringBuilder sb = new StringBuilder();
		for (char c : password) {
			sb.append(c);
		}

		return sb.toString();
	}

	private static char randomChar(String chars, SecureRandom random) {
		return chars.charAt(random.nextInt(chars.length()));
	}

}

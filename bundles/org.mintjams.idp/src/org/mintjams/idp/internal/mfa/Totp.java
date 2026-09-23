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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base32;

/**
 * Time-based one-time passwords (RFC 6238) with the parameters every
 * authenticator app supports: HMAC-SHA1, 6 digits, 30-second steps.
 */
public final class Totp {

	/** The number of digits in a code. */
	public static final int DIGITS = 6;

	/** The length of a time step in seconds. */
	public static final int PERIOD_SECONDS = 30;

	/** How many steps on either side of the current one are accepted (clock drift). */
	public static final int WINDOW_STEPS = 1;

	private static final int SECRET_BYTES = 20;
	private static final SecureRandom RANDOM = new SecureRandom();

	private Totp() {}

	/**
	 * Generates a fresh shared secret, Base32-encoded without padding.
	 */
	public static String generateSecret() {
		byte[] bytes = new byte[SECRET_BYTES];
		RANDOM.nextBytes(bytes);
		return new Base32().encodeToString(bytes).replace("=", "");
	}

	/**
	 * The current time step.
	 */
	public static long currentCounter() {
		return System.currentTimeMillis() / 1000L / PERIOD_SECONDS;
	}

	/**
	 * Verifies a code against the secret within the drift window.
	 *
	 * @param lastCounter the time step of the last accepted code, or -1; a code
	 *        for that step or an earlier one is rejected so a captured code
	 *        cannot be replayed
	 * @return the time step the code matched, or -1 when it did not verify
	 */
	public static long verify(String base32Secret, String code, long lastCounter) {
		if (base32Secret == null || code == null) {
			return -1;
		}
		String normalized = code.replaceAll("[\\s-]", "");
		if (normalized.length() != DIGITS || !normalized.chars().allMatch(Character::isDigit)) {
			return -1;
		}
		byte[] key = new Base32().decode(base32Secret);
		long now = currentCounter();
		for (long counter = now - WINDOW_STEPS; counter <= now + WINDOW_STEPS; counter++) {
			if (counter <= lastCounter) {
				continue;
			}
			if (MessageDigest.isEqual(generate(key, counter).getBytes(StandardCharsets.US_ASCII),
					normalized.getBytes(StandardCharsets.US_ASCII))) {
				return counter;
			}
		}
		return -1;
	}

	/**
	 * Builds the {@code otpauth://} URI that authenticator apps read from a QR code.
	 */
	public static String buildOtpauthUri(String issuer, String accountName, String base32Secret) {
		String label = encode(issuer) + ":" + encode(accountName);
		return "otpauth://totp/" + label +
				"?secret=" + base32Secret +
				"&issuer=" + encode(issuer) +
				"&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
	}

	static String generate(byte[] key, long counter) {
		try {
			byte[] message = new byte[8];
			for (int i = 7; i >= 0; i--) {
				message[i] = (byte) (counter & 0xff);
				counter >>>= 8;
			}
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			byte[] hash = mac.doFinal(message);
			int offset = hash[hash.length - 1] & 0x0f;
			int binary = ((hash[offset] & 0x7f) << 24) |
					((hash[offset + 1] & 0xff) << 16) |
					((hash[offset + 2] & 0xff) << 8) |
					(hash[offset + 3] & 0xff);
			int otp = binary % 1_000_000;
			return String.format("%06d", otp);
		} catch (Exception ex) {
			throw new IllegalStateException("HmacSHA1 is not available", ex);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

}

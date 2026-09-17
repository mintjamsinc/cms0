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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Turns a value read from standard input into the {@code ENC[...]} form that
 * configuration files accept, using this installation's secret key.
 *
 * <p>The value is never taken from the command line: arguments are visible in
 * the process list and in shell history. The key file is created when it does
 * not exist yet, so this can be run against an installation that has never
 * started — which is how a new cluster gets an encrypted database password
 * into its shared configuration before the first node comes up.
 *
 * <p>The container image wraps this as {@code cms-encrypt}:
 *
 * <pre>
 * $ docker exec -i cms cms-encrypt
 * &lt;the value, on one line&gt;
 * ENC[v1:...]
 * </pre>
 */
public final class EncryptTool {

	private EncryptTool() {}

	public static void main(String[] args) {
		if (args.length > 0) {
			System.err.println("usage: cms-encrypt < the value is read from standard input, one line >");
			System.err.println("The value is not taken as an argument: arguments are visible to every"
					+ " process on the host.");
			System.exit(2);
		}

		String value;
		try {
			BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			value = in.readLine();
		} catch (Throwable ex) {
			System.err.println("Could not read the value: " + ex);
			System.exit(1);
			return;
		}

		if (value == null || value.isEmpty()) {
			System.err.println("No value was given on standard input.");
			System.exit(2);
			return;
		}

		try {
			System.out.println(Encryptors.getDefault().encrypt(value));
		} catch (Throwable ex) {
			System.err.println("Encryption failed, using the secret key at "
					+ FileSecretKeyProvider.resolveKeyPath() + ": " + ex);
			System.exit(1);
		}
	}

}

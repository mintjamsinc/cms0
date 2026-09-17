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

package org.mintjams.cms.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.mintjams.cms.security.DefaultEncryptor;
import org.mintjams.cms.security.Encryptors;

/**
 * The parts of configuration value resolution that every configuration file
 * shares: the {@code ${env.NAME}} variable and {@code ENC[...]} decryption.
 *
 * <p>A clustered deployment keeps one copy of each configuration file on
 * shared storage, so values that differ per environment or per node cannot be
 * written into the file itself. They are named in the file and supplied from
 * outside:
 *
 * <pre>
 * datasource:
 *     jdbcURL: jdbc:postgresql://${env.CMS_DB_HOST}:${env.CMS_DB_PORT:-5432}/jcr_${workspace.name}
 *     username: ${env.CMS_DB_USER}
 *     password: ${env.CMS_DB_PASSWORD}
 * </pre>
 *
 * <p>Secrets have three interchangeable forms, all ending up in the same
 * field: the environment variable itself, a file named by {@code NAME_FILE}
 * (a Docker secret or a mounted Kubernetes Secret), or an {@code ENC[...]}
 * value written straight into the file and decrypted with the installation's
 * secret key. The value of an environment variable may itself be
 * {@code ENC[...]}.
 *
 * <p>No method here puts a resolved value into an exception message: these
 * values are passwords more often than not.
 */
public final class ConfigValues {

	/** Marks a variable as naming an environment variable. */
	public static final String ENVIRONMENT_PREFIX = "env.";

	/** Appended to the variable name to name a file holding the value. */
	public static final String FILE_SUFFIX = "_FILE";

	/** Separates the variable name from its default value, spelled as in a shell. */
	private static final String DEFAULT_SEPARATOR = ":-";

	private ConfigValues() {}

	/**
	 * Returns whether the given variable name (the text between {@code ${}
	 * and }{@code }}) names an environment variable.
	 */
	public static boolean isEnvironmentVariable(String name) {
		return name != null && name.startsWith(ENVIRONMENT_PREFIX);
	}

	/**
	 * Resolves {@code env.NAME} or {@code env.NAME:-default} in this order:
	 * <ol>
	 *   <li>the file named by the environment variable {@code NAME_FILE},
	 *       with one trailing line terminator removed;</li>
	 *   <li>the environment variable {@code NAME};</li>
	 *   <li>the default value, when one is written and {@code NAME} is unset
	 *       or empty (the {@code :-} of a shell, spelled the same way).</li>
	 * </ol>
	 * The file is read first so that a deployment which sets both by mistake
	 * keeps using the secret rather than the value on the command line.
	 *
	 * @throws IllegalArgumentException when nothing supplies a value
	 */
	public static String getEnvironmentVariable(String name) {
		String variableName = name.substring(ENVIRONMENT_PREFIX.length()).trim();
		String defaultValue = null;
		int separator = variableName.indexOf(DEFAULT_SEPARATOR);
		if (separator >= 0) {
			defaultValue = variableName.substring(separator + DEFAULT_SEPARATOR.length());
			variableName = variableName.substring(0, separator).trim();
		}
		if (variableName.isEmpty()) {
			throw new IllegalArgumentException("No environment variable is named in '${" + name + "}'.");
		}

		String path = System.getenv(variableName + FILE_SUFFIX);
		if (path != null && !path.isBlank()) {
			return readValueFile(variableName, path.trim());
		}

		String value = System.getenv(variableName);
		if (value != null && !(value.isEmpty() && defaultValue != null)) {
			return value;
		}

		if (defaultValue != null) {
			return defaultValue;
		}

		throw new IllegalArgumentException("The environment variable " + variableName + " is not set."
				+ " Set it, or set " + variableName + FILE_SUFFIX + " to a file holding the value,"
				+ " or write a default as ${" + ENVIRONMENT_PREFIX + variableName + DEFAULT_SEPARATOR + "...}.");
	}

	/**
	 * Decrypts a resolved value when it is an {@code ENC[...]} one, and
	 * returns it unchanged otherwise.
	 *
	 * @param source where the value came from, for the error message
	 *               (e.g. {@code jcr.yml#datasource.password})
	 */
	public static String decrypt(String value, String source) {
		// Asked statically: reading the secret key creates it when missing,
		// and a configuration that encrypts nothing must not mint one.
		if (!DefaultEncryptor.isEncryptedValue(value)) {
			return value;
		}

		try {
			return Encryptors.getDefault().decrypt(value);
		} catch (Throwable ex) {
			// The cause carries no value either: see DefaultEncryptor.
			throw new IllegalStateException("Failed to decrypt " + source + ".", ex);
		}
	}

	private static String readValueFile(String variableName, String path) {
		String value;
		try {
			value = Files.readString(Path.of(path), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException ex) {
			throw new IllegalArgumentException("The file named by " + variableName + FILE_SUFFIX
					+ " could not be read: " + path, ex);
		}

		// Secrets are written as text files and usually end with a newline;
		// exactly one terminator is dropped so a value that deliberately ends
		// with a blank line survives.
		if (value.endsWith("\r\n")) {
			return value.substring(0, value.length() - 2);
		}
		if (value.endsWith("\n") || value.endsWith("\r")) {
			return value.substring(0, value.length() - 1);
		}
		return value;
	}

}

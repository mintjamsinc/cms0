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

package org.mintjams.rt.cms.internal.mail;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import org.mintjams.cms.config.ConfigValues;
import org.mintjams.cms.config.Secrets;
import org.mintjams.rt.cms.internal.CmsService;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * The outgoing-mail configuration, {@code etc/mail.yml}:
 *
 * <pre>
 * smtp:
 *     host: smtp.example.com
 *     port: 587
 *     starttls: true            # upgrade the connection with STARTTLS (default true)
 *     ssl: false                # connect over TLS from the start (SMTPS, usually port 465)
 *     username: ${secrets.SMTP_USER}
 *     password: ${secrets.SMTP_PASSWORD}
 *     timeoutSeconds: 15
 * from: MintJams CMS &lt;no-reply@example.com&gt;
 * </pre>
 *
 * <p>The file is optional: without it there is no transport and the server
 * sends nothing. It is re-read when it changes on disk, and when
 * {@code etc/secrets.yml} does. String values may name a secret as
 * {@code ${secrets.KEY}}, an environment variable as {@code ${env.NAME}} (or
 * {@code ${env.NAME:-default}}, with {@code NAME_FILE} consulted first) and
 * may be encrypted as {@code ENC[v1:...]}; see
 * {@link ConfigValues}.</p>
 */
public final class MailConfiguration {

	public static final String FILE_NAME = "mail.yml";

	public static final int DEFAULT_PORT = 587;
	public static final int DEFAULT_TIMEOUT_SECONDS = 15;

	private static volatile MailConfiguration fCached;

	private final long fLastModified;
	private final long fSecretsStamp;
	private final String fError;
	private final String fHost;
	private final int fPort;
	private final boolean fStartTls;
	private final boolean fSsl;
	private final String fUsername;
	private final String fPassword;
	private final int fTimeoutSeconds;
	private final String fFrom;

	/** The current configuration, reloaded when {@code mail.yml} changed on disk. */
	public static MailConfiguration get() {
		Path file = CmsService.getEtcPath().resolve(FILE_NAME);
		if (!Files.exists(file)) {
			MailConfiguration cached = fCached;
			if (cached != null && cached.fLastModified == -1) {
				return cached;
			}
			MailConfiguration missing = new MailConfiguration(-1, null, null);
			fCached = missing;
			return missing;
		}

		long lastModified;
		try {
			lastModified = Files.getLastModifiedTime(file).toMillis();
		} catch (Throwable ex) {
			return new MailConfiguration(-2, null, "Could not read " + FILE_NAME + ": " + ex.getMessage());
		}

		// The secrets behind ${secrets.KEY} count as part of this file.
		long secretsStamp = Secrets.getStamp();
		MailConfiguration cached = fCached;
		if (cached != null && cached.fLastModified == lastModified && cached.fSecretsStamp == secretsStamp) {
			return cached;
		}

		MailConfiguration loaded;
		try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
			Object parsed = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			if (!(parsed instanceof Map)) {
				throw new IllegalArgumentException("the document is not a mapping");
			}
			loaded = new MailConfiguration(lastModified, secretsStamp, (Map<?, ?>) parsed, null);
			CmsService.getLogger(MailConfiguration.class)
					.info("Outgoing mail: " + loaded.fHost + ":" + loaded.fPort + " from " + loaded.fFrom);
		} catch (Throwable ex) {
			CmsService.getLogger(MailConfiguration.class)
					.error("Invalid " + file + "; the server cannot send mail until it is fixed.", ex);
			loaded = new MailConfiguration(lastModified, secretsStamp, null, "Invalid " + FILE_NAME + ": " + ex.getMessage());
		}
		fCached = loaded;
		return loaded;
	}

	private MailConfiguration(long lastModified, Map<?, ?> config, String error) {
		this(lastModified, 0, config, error);
	}

	private MailConfiguration(long lastModified, long secretsStamp, Map<?, ?> config, String error) {
		fLastModified = lastModified;
		fSecretsStamp = secretsStamp;
		if (config == null) {
			fError = error;
			fHost = null;
			fPort = DEFAULT_PORT;
			fStartTls = true;
			fSsl = false;
			fUsername = null;
			fPassword = null;
			fTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
			fFrom = null;
			return;
		}

		Map<?, ?> smtp = (config.get("smtp") instanceof Map) ? (Map<?, ?>) config.get("smtp") : Collections.emptyMap();
		fHost = required(resolve(smtp.get("host"), "smtp.host"), "smtp.host");
		fPort = asInt(resolve(smtp.get("port"), "smtp.port"), DEFAULT_PORT, "smtp.port");
		fStartTls = asBoolean(resolve(smtp.get("starttls"), "smtp.starttls"), true);
		fSsl = asBoolean(resolve(smtp.get("ssl"), "smtp.ssl"), false);
		fUsername = emptyToNull(resolve(smtp.get("username"), "smtp.username"));
		fPassword = emptyToNull(resolve(smtp.get("password"), "smtp.password"));
		fTimeoutSeconds = asInt(resolve(smtp.get("timeoutSeconds"), "smtp.timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS, "smtp.timeoutSeconds");
		fFrom = required(resolve(config.get("from"), "from"), "from");
		fError = null;
	}

	/** Whether a transport is configured. */
	public boolean isAvailable() {
		return fError == null && fHost != null;
	}

	/** Why the file could not be used, or {@code null} when it loaded (or is absent). */
	public String getError() {
		return fError;
	}

	public String getHost() {
		return fHost;
	}

	public int getPort() {
		return fPort;
	}

	public boolean isStartTls() {
		return fStartTls;
	}

	public boolean isSsl() {
		return fSsl;
	}

	public String getUsername() {
		return fUsername;
	}

	public String getPassword() {
		return fPassword;
	}

	public int getTimeoutSeconds() {
		return fTimeoutSeconds;
	}

	public String getFrom() {
		return fFrom;
	}

	// ---- value helpers -------------------------------------------------------

	/**
	 * Turns a YAML value into a string with {@code ${secrets.KEY}} and
	 * {@code ${env.NAME}} references resolved and {@code ENC[...]} decrypted.
	 * Any other variable is a mistake in this file.
	 */
	private static String resolve(Object value, String source) {
		if (value == null) {
			return null;
		}
		return ConfigValues.resolve(value.toString(), FILE_NAME + "#" + source, name -> {
			throw new IllegalArgumentException("unknown variable '${" + name
					+ "}' (only ${secrets.KEY} and ${env.NAME} are supported here)");
		});
	}

	private static String required(String value, String source) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(source + " is required");
		}
		return value.trim();
	}

	private static String emptyToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

	private static int asInt(String value, int defaultValue, String source) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			int parsed = Integer.parseInt(value.trim());
			if (parsed <= 0) {
				throw new IllegalArgumentException(source + " must be positive: " + value);
			}
			return parsed;
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(source + " is not a number: " + value);
		}
	}

	private static boolean asBoolean(String value, boolean defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		return Boolean.parseBoolean(value.trim());
	}

}

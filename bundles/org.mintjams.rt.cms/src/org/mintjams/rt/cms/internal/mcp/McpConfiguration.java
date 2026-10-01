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

package org.mintjams.rt.cms.internal.mcp;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import org.mintjams.rt.cms.internal.CmsService;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Configuration of the MCP server, read from {@code <repository>/etc/mcp.yml}.
 *
 * <p>The file is generated with its defaults on first use and re-read whenever
 * its modification time changes, so cutting every connection off or disabling
 * the endpoint takes effect on the next request without a restart.
 *
 * <p>A file that exists but cannot be read or parsed <strong>disables the
 * endpoint</strong> rather than falling back to the defaults: the file carries
 * {@code token.notBefore}, and serving requests without it would silently
 * re-admit every connection it had cut off.
 */
public final class McpConfiguration {

	public static final String FILE_NAME = "mcp.yml";

	public static final long DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 60L * 60;
	public static final long DEFAULT_REFRESH_TOKEN_TTL_SECONDS = 90L * 24 * 60 * 60;

	private static final String TEMPLATE = String.join("\n",
			"# MCP (Model Context Protocol) server. See documents/mcp-server.md.",
			"# This file is re-read when it changes; no restart is needed.",
			"",
			"# Serve /bin/mcp.cgi/{workspace}. When false the endpoint answers 404.",
			"enabled: true",
			"",
			"# Short label that tells this server from your others (e.g. prod, staging).",
			"# It is part of the server name shown to MCP clients. Defaults to the host",
			"# name the client connects to.",
			"serverName:",
			"",
			"token:",
			"    # Lifetime, in seconds, of an access token. A connected client renews it",
			"    # by itself; a connection turned off stops within this time at the latest.",
			"    accessTtl: " + DEFAULT_ACCESS_TOKEN_TTL_SECONDS,
			"    # How long, in seconds, a client stays connected before the user has to",
			"    # authorize it again in the browser.",
			"    refreshTtl: " + DEFAULT_REFRESH_TOKEN_TTL_SECONDS,
			"    # Whether connections may change content. When false every connection",
			"    # is read-only, whatever its user chose.",
			"    allowWrite: true",
			"    # Cut off every connection authorized before this instant (ISO-8601, e.g.",
			"    # 2026-10-01T00:00:00Z). Set it to now to make every client authorize again.",
			"    notBefore:",
			"");

	private static volatile McpConfiguration fCached;

	private final long fLastModified;
	private final String fError;
	private final boolean fEnabled;
	private final String fServerName;
	private final long fAccessTokenTtlSeconds;
	private final long fRefreshTokenTtlSeconds;
	private final boolean fWriteAllowed;
	private final long fTokensNotBeforeMillis;

	/** The current configuration, reloaded when {@code mcp.yml} changed on disk. */
	public static McpConfiguration get() {
		Path file = CmsService.getEtcPath().resolve(FILE_NAME);
		if (!Files.exists(file)) {
			try {
				Files.createDirectories(file.getParent());
				Files.write(file, TEMPLATE.getBytes(StandardCharsets.UTF_8));
			} catch (Throwable ex) {
				// Read-only configuration directory: run on the defaults.
				CmsService.getLogger(McpConfiguration.class)
						.warn("Could not create " + file + "; using the default MCP configuration.", ex);
				return parse(-1, null);
			}
		}

		long lastModified;
		try {
			lastModified = Files.getLastModifiedTime(file).toMillis();
		} catch (Throwable ex) {
			return failed(-1, "Could not read " + FILE_NAME + ": " + ex.getMessage());
		}

		McpConfiguration cached = fCached;
		if (cached != null && cached.fLastModified == lastModified) {
			return cached;
		}

		McpConfiguration loaded;
		try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
			Object parsed = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			if (parsed != null && !(parsed instanceof Map)) {
				throw new IllegalArgumentException("the document is not a mapping");
			}
			loaded = parse(lastModified, (Map<?, ?>) parsed);
		} catch (Throwable ex) {
			CmsService.getLogger(McpConfiguration.class)
					.error("Invalid " + file + "; the MCP endpoint is disabled until it is fixed.", ex);
			loaded = failed(lastModified, "Invalid " + FILE_NAME + ": " + ex.getMessage());
		}
		fCached = loaded;
		return loaded;
	}

	/**
	 * Builds a configuration from an already parsed document ({@code null} for
	 * the defaults). Throws when a value is of the wrong shape, so that a typo
	 * disables the endpoint instead of being read as a default.
	 */
	static McpConfiguration parse(long lastModified, Map<?, ?> config) {
		return new McpConfiguration(lastModified, config, null);
	}

	/** A configuration that could not be loaded: the endpoint is disabled. */
	static McpConfiguration failed(long lastModified, String error) {
		return new McpConfiguration(lastModified, null, error);
	}

	private McpConfiguration(long lastModified, Map<?, ?> config, String error) {
		fLastModified = lastModified;
		fError = error;

		Map<?, ?> root = (config != null) ? config : Collections.emptyMap();
		Map<?, ?> token = (root.get("token") instanceof Map) ? (Map<?, ?>) root.get("token") : Collections.emptyMap();

		fEnabled = (error == null) && asBoolean(root.get("enabled"), true);
		String serverName = (root.get("serverName") == null) ? "" : root.get("serverName").toString().trim();
		fServerName = serverName.isEmpty() ? null : serverName;
		fAccessTokenTtlSeconds = asPositiveLong(token.get("accessTtl"), DEFAULT_ACCESS_TOKEN_TTL_SECONDS);
		fRefreshTokenTtlSeconds = asPositiveLong(token.get("refreshTtl"), DEFAULT_REFRESH_TOKEN_TTL_SECONDS);
		fWriteAllowed = asBoolean(token.get("allowWrite"), true);
		fTokensNotBeforeMillis = asInstantMillis(token.get("notBefore"));
	}

	public boolean isEnabled() {
		return fEnabled;
	}

	/** Why the configuration could not be loaded, or {@code null} when it loaded. */
	public String getError() {
		return fError;
	}

	/** The configured label of this server, or {@code null} to derive one from the host name. */
	public String getServerName() {
		return fServerName;
	}

	public long getAccessTokenTtlSeconds() {
		return fAccessTokenTtlSeconds;
	}

	/** How long after its authorization a client may keep renewing its access token. */
	public long getRefreshTokenTtlSeconds() {
		return fRefreshTokenTtlSeconds;
	}

	/** Whether connections may change content at all. */
	public boolean isWriteAllowed() {
		return fWriteAllowed;
	}

	/** Connections authorized before this instant are rejected; {@code 0} when unset. */
	public long getTokensNotBeforeMillis() {
		return fTokensNotBeforeMillis;
	}

	private static boolean asBoolean(Object value, boolean defaultValue) {
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value == null) {
			return defaultValue;
		}
		String s = value.toString().trim();
		if (s.equalsIgnoreCase("true")) {
			return true;
		}
		if (s.equalsIgnoreCase("false")) {
			return false;
		}
		throw new IllegalArgumentException("not a boolean: " + value);
	}

	private static long asPositiveLong(Object value, long defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		long n;
		if (value instanceof Number) {
			n = ((Number) value).longValue();
		} else {
			n = Long.parseLong(value.toString().trim());
		}
		if (n <= 0) {
			throw new IllegalArgumentException("must be positive: " + value);
		}
		return n;
	}

	private static long asInstantMillis(Object value) {
		if (value == null) {
			return 0;
		}
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		String s = value.toString().trim();
		if (s.isEmpty()) {
			return 0;
		}
		return Instant.parse(s).toEpochMilli();
	}

}

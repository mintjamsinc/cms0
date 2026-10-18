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

package org.mintjams.rt.jcr.internal.cluster;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.mintjams.rt.jcr.internal.Activator;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * The key of the cluster's own connections ({@code cluster-secret-key.yml}).
 * It is held by the repository bundle alone and never leaves it, and it is a
 * different key from the one that encrypts configuration values
 * ({@code secret-key.yml}), so that one leaking never exposes the other.
 *
 * <p>The file is created when it is missing, like the other identity files
 * of a cluster: on shared storage every node reads the same file; with
 * node-local repository directories it is copied from the first node. The
 * key itself is never used directly: each purpose derives its own key from it
 * with a label ({@code transport/v1} signs the frames between the nodes).
 */
final class ClusterSecretKey {

	private static final String TAG = "v1";
	private static final int KEY_BYTES = 32;

	private ClusterSecretKey() {}

	/**
	 * Reads the key at the given path, creating the file first when it does
	 * not exist, and returns the key derived for the given label.
	 */
	static byte[] derive(Path path, String label) throws IOException {
		byte[] key = load(path);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException ex) {
			throw new IOException("The cluster key could not be derived.", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static byte[] load(Path path) throws IOException {
		if (!Files.exists(path)) {
			create(path);
		}

		Map<String, Object> config;
		try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
			config = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
		}
		try {
			for (Map<String, Object> entry : (List<Map<String, Object>>) config.get("keys")) {
				if (TAG.equals(entry.get("tag"))) {
					byte[] key = Base64.getDecoder().decode((String) entry.get("data"));
					if (key.length < KEY_BYTES) {
						throw new IOException("The cluster key is shorter than " + (KEY_BYTES * 8) + " bits: " + path);
					}
					return key;
				}
			}
		} catch (IOException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw new IOException("The cluster key file could not be read: " + path, ex);
		}
		throw new IOException("The cluster key file has no key tagged '" + TAG + "': " + path);
	}

	private static void create(Path path) throws IOException {
		byte[] key = new byte[KEY_BYTES];
		new SecureRandom().nextBytes(key);

		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		try (Writer out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			out.append("keys:\n");
			out.append("  - tag: ").append(TAG).append("\n");
			out.append("    data: ").append(Base64.getEncoder().encodeToString(key)).append("\n");
		}

		try {
			Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException ex) {
			File file = path.toFile();
			file.setReadable(false, false);
			file.setWritable(false, false);
			file.setReadable(true, true);
			file.setWritable(true, true);
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(ClusterSecretKey.class).warn("Failed to set file permissions for the cluster key file '"
					+ path.toAbsolutePath() + "'. Please ensure that the file is only accessible by the owner.", ex);
		}
		Activator.getDefault().getLogger(ClusterSecretKey.class).info("The cluster key file has been created: " + path.toAbsolutePath());
	}

}

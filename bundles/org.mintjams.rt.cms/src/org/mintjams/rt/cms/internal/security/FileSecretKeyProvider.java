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

package org.mintjams.rt.cms.internal.security;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.mintjams.cms.security.SecretKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class FileSecretKeyProvider implements SecretKeyProvider {

	private static final Logger log = LoggerFactory.getLogger(FileSecretKeyProvider.class);

	private final Map<String, Object> fKeyConfig;

	@SuppressWarnings("unchecked")
	public FileSecretKeyProvider() throws IOException {
		Path path = Path.of(System.getProperty("user.home"), ".mintjams/cms/secret-key.yml");
		if (!Files.exists(path)) {
			fKeyConfig = Map.of(
					"keys", List.of(
						Map.of(
							"tag", "v1",
							"data", Base64.getEncoder().encodeToString(generateKey().getEncoded())
						)
				));

			Files.createDirectories(path.getParent());
			try (Writer out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				out.append(new Dump(DumpSettings.builder().build()).dumpToString(fKeyConfig));
			}

			try {
				// POSIX (Linux/macOS)
				Set<PosixFilePermission> perms = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
				Files.setPosixFilePermissions(path, perms);
			} catch (UnsupportedOperationException e) {
				// Windows fallback
				File file = path.toFile();
				file.setReadable(false, false);
				file.setWritable(false, false);
				file.setReadable(true, true);
				file.setWritable(true, true);
			} catch (Throwable ex) {
				log.warn("Failed to set file permissions for secret key file '{}'. please ensure that the file is only accessible by the owner.", path.toFile().getAbsolutePath(), ex);
			}
			return;
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
			fKeyConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
		}
	}

	@Override
	public SecretKey getSecretKey(String tag) {
		Map<String, Object> entry = getKeyEntry(tag);
		byte[] keyBytes = Base64.getDecoder().decode((String) entry.get("data"));
		return new SecretKeySpec(keyBytes, "AES");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getKeyEntry(String tag) {
		return ((List<Map<String, Object>>) fKeyConfig.get("keys")).stream()
				.filter(entry -> tag.equals(entry.get("tag")))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No key found for tag: " + tag));
	}

	private SecretKey generateKey() {
		try {
			KeyGenerator keyGen = KeyGenerator.getInstance("AES");
			keyGen.init(256, new SecureRandom());
			return keyGen.generateKey();
		} catch (Throwable ex) {
			throw new IllegalStateException("Failed to generate secret key", ex);
		}
	}

}

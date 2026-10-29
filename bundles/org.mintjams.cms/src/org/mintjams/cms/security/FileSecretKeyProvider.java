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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.mintjams.cms.config.RepositoryLocation;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

/**
 * The installation's secret key, {@code <repository>/etc/secret.key}: the key
 * that every {@code ENC[...]} value is encrypted with.
 *
 * <p>The file lives with the other identity files of the repository, which
 * every node of a cluster shares (or copies before its first start). It ends
 * in {@code .key} so that a backup can leave every key out with a single
 * pattern, {@code etc/*.key}, and keep the keys elsewhere, encrypted. See
 * {@link #resolveKeyPath()} for the places an existing key is looked for.
 */
public class FileSecretKeyProvider implements SecretKeyProvider {

	private static final Logger log = System.getLogger(FileSecretKeyProvider.class.getName());

	public static final String SYSTEM_PROPERTY = "mintjams.cms.secret-key.path";
	public static final String ENV_VARIABLE = "MINTJAMS_CMS_SECRET_KEY_PATH";

	/** The file name under {@code <repository>/etc}. */
	public static final String FILE_NAME = "secret.key";

	/** The name the file had before it moved to {@code etc/} and became a {@code .key}. */
	public static final String LEGACY_FILE_NAME = "secret-key.yml";

	private static final String TAG = "v1";

	private static final AtomicBoolean fWarnedAboutPlace = new AtomicBoolean();

	private final Map<String, Object> fKeyConfig;

	@SuppressWarnings("unchecked")
	public FileSecretKeyProvider() throws IOException {
		Path path = resolveKeyPath();
		if (!Files.exists(path)) {
			create(path);
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

	/**
	 * Resolves the location of the secret key file, in this order:
	 * <ol>
	 *   <li>the system property {@code mintjams.cms.secret-key.path};</li>
	 *   <li>the environment variable {@code MINTJAMS_CMS_SECRET_KEY_PATH};</li>
	 *   <li>{@code <repository>/etc/secret.key}, when it exists;</li>
	 *   <li>{@code <repository>/etc/secret-key.yml}, the name before this
	 *       one, when it exists (a warning asks for the rename);</li>
	 *   <li>{@code $HOME/.mintjams/cms/secret-key.yml}, the place before
	 *       {@code etc/}, when it exists (a warning asks for the move);</li>
	 *   <li>otherwise {@code <repository>/etc/secret.key}, which is then
	 *       created.</li>
	 * </ol>
	 * A path given explicitly (1, 2) is used as it is, whatever its name, and
	 * is created when missing without looking anywhere else. The fallbacks
	 * (4, 5) exist so that an installation made before the file moved keeps
	 * reading what it encrypted: a new key minted beside an old one would make
	 * every existing {@code ENC[...]} value unreadable.
	 */
	public static Path resolveKeyPath() {
		String override = System.getProperty(SYSTEM_PROPERTY);
		if (override == null || override.isBlank()) {
			override = System.getenv(ENV_VARIABLE);
		}
		if (override != null && !override.isBlank()) {
			return Path.of(override.trim());
		}

		Path etc = RepositoryLocation.getEtcPath();
		Path current = etc.resolve(FILE_NAME);
		if (Files.exists(current)) {
			return current;
		}

		Path renamed = etc.resolve(LEGACY_FILE_NAME);
		if (Files.exists(renamed)) {
			warnOnce("The secret key is " + renamed.toAbsolutePath() + ". Rename it to " + FILE_NAME
					+ " so that a backup can leave every key out with etc/*.key.");
			return renamed;
		}

		Path legacy = Path.of(System.getProperty("user.home"), ".mintjams/cms", LEGACY_FILE_NAME);
		if (Files.exists(legacy)) {
			warnOnce("The secret key is " + legacy.toAbsolutePath() + ". Move it to " + current.toAbsolutePath()
					+ ", where every node of a cluster shares it with the other identity files.");
			return legacy;
		}

		return current;
	}

	private static void warnOnce(String message) {
		if (fWarnedAboutPlace.compareAndSet(false, true)) {
			log.log(Level.WARNING, message);
		}
	}

	/**
	 * Writes a new key. The file is written next to its final place and moved
	 * there in one step, so that a process starting at the same time sees
	 * either no file or the whole file, never a half-written one. When the
	 * file appears in the meantime, the new key is discarded and the existing
	 * file is kept: two keys for one installation would make the values one
	 * of them encrypted unreadable to the other.
	 */
	private static void create(Path path) throws IOException {
		Map<String, Object> keyConfig = Map.of(
				"keys", List.of(
					Map.of(
						"tag", TAG,
						"data", Base64.getEncoder().encodeToString(generateKey().getEncoded())
					)
			));

		Path directory = (path.getParent() != null) ? path.getParent() : Path.of(".");
		Files.createDirectories(directory);
		Path temp = Files.createTempFile(directory, path.getFileName().toString() + ".", ".tmp");
		try {
			try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				out.append(new Dump(DumpSettings.builder()
						.setIndent(4)
						.setIndicatorIndent(2)
						.setDefaultFlowStyle(FlowStyle.BLOCK)
						.build()).dumpToString(keyConfig));
			}
			restrictToOwner(temp, path);

			if (Files.exists(path)) {
				return;
			}
			try {
				Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
			} catch (FileAlreadyExistsException ex) {
				return;
			}
		} finally {
			Files.deleteIfExists(temp);
		}
		log.log(Level.INFO, "The secret key file has been created: " + path.toAbsolutePath());
	}

	private static void restrictToOwner(Path file, Path reportedAs) {
		try {
			// POSIX (Linux/macOS)
			Set<PosixFilePermission> perms = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
			Files.setPosixFilePermissions(file, perms);
		} catch (UnsupportedOperationException e) {
			// Windows fallback
			File f = file.toFile();
			f.setReadable(false, false);
			f.setWritable(false, false);
			f.setReadable(true, true);
			f.setWritable(true, true);
		} catch (Throwable ex) {
			log.log(Level.WARNING, "Failed to set file permissions for secret key file '"
					+ reportedAs.toAbsolutePath()
					+ "'. please ensure that the file is only accessible by the owner.", ex);
		}
	}

	private static SecretKey generateKey() {
		try {
			KeyGenerator keyGen = KeyGenerator.getInstance("AES");
			keyGen.init(256, new SecureRandom());
			return keyGen.generateKey();
		} catch (Throwable ex) {
			throw new IllegalStateException("Failed to generate secret key", ex);
		}
	}

}

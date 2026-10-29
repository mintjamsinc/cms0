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
import java.nio.file.attribute.BasicFileAttributes;

import org.mintjams.cms.security.Encryptors;

/**
 * The secrets of this installation, {@code <repository>/etc/secrets.yml}: one
 * flat mapping of names to passwords, API keys and the like, that every
 * configuration file refers to as {@code ${secrets.NAME}}.
 *
 * <pre>
 * SMTP_PASSWORD: ENC[v1:...]       # encrypted by the server
 * SHOPIFY_API_KEY: shpat_xxxxxxxx  # just placed; encrypted within seconds
 * </pre>
 *
 * <p>Reading is deliberately tolerant of the clear: a value works the moment
 * the file is placed, whether or not the CMS runtime has encrypted it yet, and
 * whether or not the runtime is up at all — the repository runtime reads
 * {@code jcr.yml} before the CMS is active. Encrypting the file is the CMS
 * runtime's job (its watcher); nothing here writes.
 *
 * <p>The file is re-read when it changes on disk, and
 * {@link #getStamp()} tells the configuration classes that cache their own
 * file by its modification time when the secrets behind it changed.
 *
 * <p>No method puts a value into an exception or a log message; the key name
 * and the file are all a message carries.
 */
public final class Secrets {

	public static final String FILE_NAME = "secrets.yml";

	private static final class Snapshot {
		final boolean exists;
		final long modified;
		final long size;
		final SecretsFile file;
		final long stamp;

		Snapshot(boolean exists, long modified, long size, SecretsFile file, long stamp) {
			this.exists = exists;
			this.modified = modified;
			this.size = size;
			this.file = file;
			this.stamp = stamp;
		}
	}

	private static volatile Snapshot fSnapshot;

	private Secrets() {}

	/** The file: {@code <repository>/etc/secrets.yml}. */
	public static Path getPath() {
		return RepositoryLocation.getEtcPath().resolve(FILE_NAME);
	}

	/**
	 * A number that changes whenever the file is re-read (because it changed,
	 * appeared or disappeared), for callers that cache what they resolved from
	 * it.
	 */
	public static long getStamp() {
		return current().stamp;
	}

	/** Whether the file has a set (non-empty, usable) value for the key. */
	public static boolean has(String key) {
		SecretsFile.Entry entry = entry(current(), key);
		return entry != null && entry.getProblem() == null && !entry.isEmpty();
	}

	/**
	 * The value of a secret, decrypted when the file holds it encrypted.
	 *
	 * @throws IllegalArgumentException when the file or the key is missing,
	 *         the key is empty, or the entry does not fit the format
	 * @throws IllegalStateException when the value cannot be decrypted
	 */
	public static String get(String key) {
		Snapshot snapshot = current();
		Path path = getPath();
		if (!snapshot.exists) {
			throw new IllegalArgumentException("The secret " + key + " is referenced, but there is no " + path + ".");
		}
		if (snapshot.file.getError() != null) {
			throw new IllegalArgumentException("The secret " + key + " cannot be read: " + path + " "
					+ snapshot.file.getError() + ".");
		}
		SecretsFile.Entry entry = entry(snapshot, key);
		if (entry == null) {
			throw new IllegalArgumentException("The secret " + key + " is not in " + path + ".");
		}
		if (entry.getProblem() != null) {
			throw new IllegalArgumentException("The secret " + key + " in " + path + " " + entry.getProblem() + ".");
		}
		if (entry.isEmpty()) {
			throw new IllegalArgumentException("The secret " + key + " in " + path + " is empty.");
		}
		if (!entry.isEncrypted()) {
			return entry.getValue();
		}
		try {
			return Encryptors.getDefault().decrypt(entry.getValue());
		} catch (Throwable ex) {
			// The cause carries no value either: see DefaultEncryptor.
			throw new IllegalStateException("The secret " + key + " in " + path + " could not be decrypted;"
					+ " is the secret key the one it was encrypted with?", ex);
		}
	}

	private static SecretsFile.Entry entry(Snapshot snapshot, String key) {
		if (!snapshot.exists || snapshot.file.getError() != null) {
			return null;
		}
		return snapshot.file.get(key);
	}

	/**
	 * The current state of the file: the cached one while the file's
	 * modification time and size are what they were, a fresh parse otherwise.
	 */
	private static Snapshot current() {
		Path path = getPath();
		Snapshot cached = fSnapshot;
		BasicFileAttributes attributes;
		try {
			attributes = Files.readAttributes(path, BasicFileAttributes.class);
		} catch (IOException ex) {
			// Missing (or unreadable, which is reported when a key is asked for).
			if (cached != null && !cached.exists) {
				return cached;
			}
			synchronized (Secrets.class) {
				cached = fSnapshot;
				if (cached != null && !cached.exists) {
					return cached;
				}
				Snapshot none = new Snapshot(false, 0, 0, null, nextStamp(cached));
				fSnapshot = none;
				return none;
			}
		}

		long modified = attributes.lastModifiedTime().toMillis();
		long size = attributes.size();
		if (cached != null && cached.exists && cached.modified == modified && cached.size == size) {
			return cached;
		}
		synchronized (Secrets.class) {
			cached = fSnapshot;
			if (cached != null && cached.exists && cached.modified == modified && cached.size == size) {
				return cached;
			}
			SecretsFile file;
			try {
				file = SecretsFile.parse(Files.readString(path, StandardCharsets.UTF_8));
			} catch (IOException ex) {
				file = SecretsFile.unreadable("could not be read: " + ex.getMessage());
			}
			Snapshot loaded = new Snapshot(true, modified, size, file, nextStamp(cached));
			fSnapshot = loaded;
			return loaded;
		}
	}

	private static long nextStamp(Snapshot previous) {
		return (previous == null) ? 1 : previous.stamp + 1;
	}

}

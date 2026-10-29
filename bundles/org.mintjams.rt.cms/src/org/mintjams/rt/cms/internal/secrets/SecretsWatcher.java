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

package org.mintjams.rt.cms.internal.secrets;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.jcr.Session;

import org.mintjams.cms.config.Secrets;
import org.mintjams.cms.config.SecretsFile;
import org.mintjams.cms.security.Encryptors;
import org.mintjams.jcr.cluster.ClusterLeaseStore;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.tools.adapter.Adaptables;

/**
 * Keeps {@code etc/secrets.yml} encrypted: every couple of seconds it looks
 * whether the file changed, and when a value in it is written in the clear,
 * encrypts that value with the installation key and puts it back in the
 * file's place.
 *
 * <p>Only the value is replaced — the text from where the scalar begins to
 * where it ends, as the YAML parser saw it — so comments, blank lines, the
 * order of the keys and everything else stay as the operator wrote them.
 * The new text is written to a temporary file beside the original and moved
 * over it in one step; a reader sees either the old file or the new one.
 *
 * <p>In a cluster every node runs this watcher over the same shared file.
 * The {@code secrets-encryption} lease lets one node write at a time, and the
 * file's attributes are compared once more right before the move, so that an
 * edit the operator saved in between is kept rather than overwritten (the
 * next tick then encrypts what the edit left in the clear). Two nodes that
 * encrypt the same value independently produce different ciphertexts, both
 * valid — the lease is about not losing edits, not about agreeing on bytes.
 *
 * <p>Nothing here logs a value or a ciphertext: messages carry key names,
 * counts and the file.
 */
public final class SecretsWatcher implements Closeable {

	/** How often the file is looked at. */
	public static final long INTERVAL_SECONDS = 2;

	/** The lease that serializes the writers of a cluster. */
	public static final String LEASE_NAME = "secrets-encryption";

	private static final long LEASE_TTL_MILLIS = 30_000L;

	private static final String SYSTEM_WORKSPACE = "system";

	private ScheduledExecutorService fScheduler;

	/** The attributes the file had when it was last examined, or null. Touched by the scheduler thread only. */
	private Stamp fExamined;

	/** The attributes the file had when its format was last reported as unusable, or null. */
	private Stamp fReported;

	private static final class Stamp {
		final long modified;
		final long size;

		Stamp(BasicFileAttributes attributes) {
			modified = attributes.lastModifiedTime().toMillis();
			size = attributes.size();
		}

		@Override
		public boolean equals(Object other) {
			return (other instanceof Stamp) && ((Stamp) other).modified == modified && ((Stamp) other).size == size;
		}

		@Override
		public int hashCode() {
			return Objects.hash(modified, size);
		}
	}

	public synchronized SecretsWatcher open() {
		if (fScheduler != null) {
			return this;
		}
		fScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cms-secrets");
			thread.setDaemon(true);
			return thread;
		});
		fScheduler.scheduleWithFixedDelay(this::tick, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
		return this;
	}

	@Override
	public synchronized void close() throws IOException {
		if (fScheduler != null) {
			fScheduler.shutdownNow();
			fScheduler = null;
		}
	}

	private void tick() {
		try {
			examine();
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Could not examine " + Secrets.getPath() + ".", ex);
		}
	}

	private void examine() throws IOException {
		Path path = Secrets.getPath();
		BasicFileAttributes attributes;
		try {
			attributes = Files.readAttributes(path, BasicFileAttributes.class);
		} catch (IOException ex) {
			// Absent: nothing to encrypt. The next file placed is examined afresh.
			fExamined = null;
			fReported = null;
			return;
		}
		Stamp stamp = new Stamp(attributes);
		if (stamp.equals(fExamined)) {
			return;
		}

		String text = Files.readString(path, StandardCharsets.UTF_8);
		SecretsFile file = SecretsFile.parse(text);
		if (file.getError() != null) {
			report(stamp, path + " " + file.getError() + "; nothing in it is encrypted until it is fixed.");
			fExamined = stamp;
			return;
		}
		List<String> problems = new ArrayList<>();
		List<SecretsFile.Entry> plain = new ArrayList<>();
		for (SecretsFile.Entry entry : file.getEntries()) {
			if (entry.getProblem() != null) {
				problems.add(entry.getKey() + " " + entry.getProblem());
			} else if (entry.isPlain()) {
				plain.add(entry);
			}
		}
		if (!problems.isEmpty()) {
			report(stamp, path + " has values that do not fit its format (one key: value per line): "
					+ String.join(", ", problems) + ". Nothing in it is encrypted until they are fixed.");
			fExamined = stamp;
			return;
		}
		fReported = null;
		if (plain.isEmpty()) {
			fExamined = stamp;
			return;
		}

		// Not recorded as examined: when the write below does not happen (the
		// lease is held elsewhere, the file changed meanwhile), the next tick
		// looks again.
		encrypt(path, text, plain, stamp);
	}

	private void report(Stamp stamp, String message) {
		if (stamp.equals(fReported)) {
			return;
		}
		fReported = stamp;
		CmsService.getLogger(getClass()).error(message);
	}

	private void encrypt(Path path, String text, List<SecretsFile.Entry> plain, Stamp read) throws IOException {
		Session session;
		try {
			session = CmsService.getRepository().login(new CmsServiceCredentials(), SYSTEM_WORKSPACE);
		} catch (Throwable ex) {
			// The system workspace is not up (yet); the values are readable in
			// the clear meanwhile, and the next tick tries again.
			return;
		}
		try {
			ClusterLeaseStore leases = Adaptables.getAdapter(session, ClusterLeaseStore.class);
			ClusterLeaseStore.Lease lease = (leases == null) ? null : leases.tryLock(LEASE_NAME, LEASE_TTL_MILLIS);
			if (leases != null && lease == null) {
				return;
			}
			try {
				if (!read.equals(new Stamp(Files.readAttributes(path, BasicFileAttributes.class)))) {
					return;
				}
				write(path, SecretsFile.rewrite(text, plain, value -> Encryptors.getDefault().encrypt(value)), read);
			} finally {
				if (lease != null) {
					lease.close();
				}
			}
		} finally {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}

		List<String> keys = new ArrayList<>();
		for (SecretsFile.Entry entry : plain) {
			keys.add(entry.getKey());
		}
		CmsService.getLogger(getClass()).info(keys.size() + " value" + (keys.size() == 1 ? "" : "s") + " in " + path
				+ " " + (keys.size() == 1 ? "was" : "were") + " encrypted: " + String.join(", ", keys));
	}

	/**
	 * Writes the text beside the file and moves it over the file in one step,
	 * unless the file changed since it was read.
	 */
	private static void write(Path path, String text, Stamp read) throws IOException {
		Path directory = (path.getParent() != null) ? path.getParent() : Path.of(".");
		Path temp = Files.createTempFile(directory, path.getFileName().toString() + ".", ".tmp");
		try {
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			restrictToOwner(temp, path);
			if (!read.equals(new Stamp(Files.readAttributes(path, BasicFileAttributes.class)))) {
				return;
			}
			Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static void restrictToOwner(Path file, Path reportedAs) {
		try {
			Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException ex) {
			File f = file.toFile();
			f.setReadable(false, false);
			f.setWritable(false, false);
			f.setReadable(true, true);
			f.setWritable(true, true);
		} catch (Throwable ex) {
			CmsService.getLogger(SecretsWatcher.class).warn("Failed to set file permissions for '"
					+ reportedAs.toAbsolutePath() + "'. Please ensure that the file is only accessible by the owner.", ex);
		}
	}

}

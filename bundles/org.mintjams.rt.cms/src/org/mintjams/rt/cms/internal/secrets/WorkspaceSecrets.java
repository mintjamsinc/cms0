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

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.lock.LockException;
import javax.jcr.lock.LockManager;
import javax.jcr.nodetype.NodeType;

import org.mintjams.cms.config.SecretsFile;
import org.mintjams.cms.security.Encryptors;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

/**
 * The secrets of one workspace, {@code /etc/security/secrets.yml}: the same
 * flat {@code key: value} format as the server's {@code etc/secrets.yml}, kept
 * inside the workspace so that whoever maintains the workspace can set them
 * in the Text Editor, and read by the workspace's scripts through
 * {@code SecretsAPI}.
 *
 * <p>This service does two things:
 * <ul>
 *   <li>it keeps the file encrypted: when the file changes (a JCR event) or
 *       the workspace starts, a value written in the clear is encrypted with
 *       the installation key and put back in its place, nothing else in the
 *       text moving;</li>
 *   <li>it holds the parsed file for the readers, dropping it when the file
 *       changes.</li>
 * </ul>
 *
 * <p>The rewrite reads, replaces and saves under a session-scoped deep JCR
 * lock on the file. The lock lives in the workspace database, so in a cluster
 * it lets one node write at a time, and while it is held a save by anyone
 * else fails rather than being overwritten by content read before it. Every
 * node receives every change, so a node that goes down before it writes is
 * covered by the others.
 *
 * <p>The file is read and written with a service session, which access
 * control does not apply to: the folder is closed to everyone by the
 * platform's provisioning, and neither the service nor the readers need a
 * grant.
 *
 * <p>Nothing here logs a value or a ciphertext: messages carry the workspace,
 * the path, key names and counts.
 */
public final class WorkspaceSecrets implements EventHandler, Closeable {

	/** The folder of the file, closed to everyone by the platform's provisioning. */
	public static final String FOLDER_PATH = "/etc/security";

	/** The file. */
	public static final String FILE_PATH = FOLDER_PATH + "/secrets.yml";

	/** How long the rewrite may hold the lock before it lapses by itself. */
	private static final long LOCK_TIMEOUT_SECONDS = 30;

	private final String fWorkspaceName;
	private ExecutorService fExecutor;
	private Registration<EventHandler> fRegistration;
	private final AtomicBoolean fExaminePending = new AtomicBoolean();

	/** The parsed file, or null when it has to be read again. */
	private volatile Snapshot fSnapshot;

	/** What was last reported about the file's format or its lock, to say it once. */
	private String fReported;

	/** The state of the file as the readers see it. */
	public static final class Snapshot {
		private final boolean fExists;
		private final SecretsFile fFile;

		private Snapshot(boolean exists, SecretsFile file) {
			fExists = exists;
			fFile = file;
		}

		public boolean exists() {
			return fExists;
		}

		/** The parsed file; null when it does not exist. */
		public SecretsFile getFile() {
			return fFile;
		}
	}

	public WorkspaceSecrets(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public synchronized WorkspaceSecrets open() {
		if (fExecutor != null) {
			return this;
		}
		fExecutor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cms-secrets-" + fWorkspaceName);
			thread.setDaemon(true);
			return thread;
		});
		fRegistration = Registration.newBuilder(EventHandler.class)
				.setService(this)
				.setProperty(EventConstants.EVENT_TOPIC, new String[] { javax.jcr.Node.class.getName().replace(".", "/") + "/*" })
				.setProperty(EventConstants.EVENT_FILTER, "(workspace=" + fWorkspaceName + ")")
				.setBundleContext(CmsService.getDefault().getBundleContext())
				.build();
		// A value saved while the workspace was down is encrypted now, without
		// waiting for the next change.
		requestExamine();
		return this;
	}

	@Override
	public synchronized void close() throws IOException {
		IOs.closeQuietly(fRegistration);
		fRegistration = null;
		if (fExecutor != null) {
			fExecutor.shutdownNow();
			fExecutor = null;
		}
		fSnapshot = null;
	}

	@Override
	public void handleEvent(Event event) {
		if (!isRelevant(event.getProperty("path")) && !isRelevant(event.getProperty("source_path"))) {
			return;
		}
		fSnapshot = null;
		requestExamine();
	}

	/**
	 * Whether a change at the path can concern the file: the file itself, its
	 * content, or a folder above it that was moved or removed.
	 */
	private static boolean isRelevant(Object path) {
		if (!(path instanceof String)) {
			return false;
		}
		String p = (String) path;
		return p.equals(FILE_PATH) || p.startsWith(FILE_PATH + "/") || FILE_PATH.startsWith(p + "/");
	}

	/** Queues one examination; a burst of events while one is pending adds nothing. */
	private void requestExamine() {
		ExecutorService executor = fExecutor;
		if (executor == null || !fExaminePending.compareAndSet(false, true)) {
			return;
		}
		try {
			executor.execute(() -> {
				fExaminePending.set(false);
				try {
					examine();
				} catch (Throwable ex) {
					CmsService.getLogger(getClass()).warn("Could not examine " + describe() + ".", ex);
				}
			});
		} catch (Throwable ex) {
			// Shut down meanwhile.
			fExaminePending.set(false);
		}
	}

	/**
	 * The file as the readers see it: the held parse while the file has not
	 * changed, a fresh read otherwise.
	 */
	public Snapshot getSnapshot() throws RepositoryException, IOException {
		Snapshot snapshot = fSnapshot;
		if (snapshot != null) {
			return snapshot;
		}
		snapshot = read(fWorkspaceName);
		fSnapshot = snapshot;
		return snapshot;
	}

	/**
	 * Reads the file of a workspace with a service session, for a reader that
	 * runs before the workspace's service is up (content deployment).
	 */
	public static Snapshot read(String workspaceName) throws RepositoryException, IOException {
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), workspaceName);
		try {
			if (!session.nodeExists(FILE_PATH)) {
				return new Snapshot(false, null);
			}
			Node node = session.getNode(FILE_PATH);
			if (!JCRs.isFile(node)) {
				return new Snapshot(true, SecretsFile.unreadable("is not a file"));
			}
			return new Snapshot(true, SecretsFile.parse(JCRs.getContentAsString(node)));
		} finally {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}
	}

	private void examine() throws RepositoryException, IOException {
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), fWorkspaceName);
		try {
			if (!session.nodeExists(FILE_PATH)) {
				fReported = null;
				return;
			}
			Node node = session.getNode(FILE_PATH);
			if (!JCRs.isFile(node)) {
				report("not a file", describe() + " is not a file; nothing is encrypted.");
				return;
			}

			List<SecretsFile.Entry> plain = findPlain(node);
			if (plain == null || plain.isEmpty()) {
				return;
			}

			// A plain nt:file is not lockable unless the mixin was added when it
			// was created (the resource layer does, JCRs does not).
			if (!node.isNodeType(NodeType.MIX_LOCKABLE)) {
				node.addMixin(NodeType.MIX_LOCKABLE);
				session.save();
			}

			LockManager lockManager = session.getWorkspace().getLockManager();
			try {
				lockManager.lock(FILE_PATH, true, true, LOCK_TIMEOUT_SECONDS, session.getUserID());
			} catch (LockException held) {
				// Another node is encrypting it (its save brings us back here), or
				// someone holds a lock on the file: releasing a lock changes the
				// file's lock properties, and that event brings us back here too.
				warn("locked " + JCRs.getLastModified(node).getTime(), describe() + " is locked; "
						+ plain.size() + " value" + (plain.size() == 1 ? "" : "s") + " in the clear ("
						+ keys(plain) + ") will be encrypted when the lock is released.");
				return;
			}
			try {
				// Read again under the lock: the content the event announced may
				// have been replaced, or encrypted by another node, meanwhile.
				session.refresh(false);
				node = session.getNode(FILE_PATH);
				String text = JCRs.getContentAsString(node);
				SecretsFile file = SecretsFile.parse(text);
				plain = findPlain(file, node);
				if (plain == null || plain.isEmpty()) {
					return;
				}
				String encrypted = SecretsFile.rewrite(text, plain, value -> Encryptors.getDefault().encrypt(value));
				JCRs.write(node, new ByteArrayInputStream(encrypted.getBytes(StandardCharsets.UTF_8)));
				session.save();
			} catch (RepositoryException | IOException | RuntimeException ex) {
				try {
					session.refresh(false);
				} catch (Throwable ignore) {}
				throw ex;
			} finally {
				try {
					lockManager.unlock(FILE_PATH);
				} catch (Throwable ignore) {
					// Released with the session, or lapses by itself.
				}
			}
			fSnapshot = null;
			fReported = null;
			CmsService.getLogger(getClass()).info(plain.size() + " value" + (plain.size() == 1 ? "" : "s") + " in "
					+ describe() + " " + (plain.size() == 1 ? "was" : "were") + " encrypted: " + keys(plain));
		} finally {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}
	}

	private List<SecretsFile.Entry> findPlain(Node node) throws RepositoryException, IOException {
		return findPlain(SecretsFile.parse(JCRs.getContentAsString(node)), node);
	}

	/**
	 * The entries to encrypt, or null when the file does not fit its format
	 * (then nothing in it is touched, and that is reported once per change).
	 */
	private List<SecretsFile.Entry> findPlain(SecretsFile file, Node node) throws RepositoryException, IOException {
		String modified = String.valueOf(JCRs.getLastModified(node).getTime());
		if (file.getError() != null) {
			report("format " + modified, describe() + " " + file.getError()
					+ "; nothing in it is encrypted until it is fixed.");
			return null;
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
			report("format " + modified, describe() + " has values that do not fit its format (one key: value per line): "
					+ String.join(", ", problems) + ". Nothing in it is encrypted until they are fixed.");
			return null;
		}
		return plain;
	}

	private void report(String what, String message) {
		if (what.equals(fReported)) {
			return;
		}
		fReported = what;
		CmsService.getLogger(getClass()).error(message);
	}

	private void warn(String what, String message) {
		if (what.equals(fReported)) {
			return;
		}
		fReported = what;
		CmsService.getLogger(getClass()).warn(message);
	}

	private static String keys(List<SecretsFile.Entry> entries) {
		List<String> keys = new ArrayList<>();
		for (SecretsFile.Entry entry : entries) {
			keys.add(entry.getKey());
		}
		return String.join(", ", keys);
	}

	private String describe() {
		return fWorkspaceName + ":" + FILE_PATH;
	}

}

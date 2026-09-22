package webtop.mail;

import javax.jcr.lock.LockException;

/**
 * A lease on a lock file, cluster-wide: the lock rows live in the workspace
 * database. The timeout only frees the lock after a crash, so a pass that runs
 * longer renews it with refresh().
 */
class PassLock {

	def context;
	String path;
	long timeoutSeconds;
	def lock;
	long renewedAt;

	private PassLock(context, String path, long timeoutSeconds) {
		this.context = context;
		this.path = path;
		this.timeoutSeconds = timeoutSeconds;
	}

	/** Takes the lock, or returns null when another pass holds it. */
	static PassLock tryAcquire(context, String path, long timeoutSeconds) {
		PassLock passLock = new PassLock(context, path, timeoutSeconds);
		return passLock.take() ? passLock : null;
	}

	private boolean take() {
		def session = context.session;
		def lockFile = session.getResource(path);
		if (!lockFile.exists()) {
			session.getResource(path.substring(0, path.lastIndexOf('/'))).getOrCreateFolder();
			lockFile.createFile();
			// The lock needs a saved node.
			session.commit();
		}
		if (lockFile.tryLock(false, true, timeoutSeconds) == null) {
			return false;
		}
		lock = lockFile.getLock();
		renewedAt = System.currentTimeMillis();
		return true;
	}

	/**
	 * Extends the lease once a third of it has gone by; cheap enough to call from
	 * every step of a loop. Throws IllegalStateException when the lease has passed
	 * to another pass.
	 */
	void refresh() {
		long now = System.currentTimeMillis();
		if ((now - renewedAt) < (timeoutSeconds * 1000L).intdiv(3)) {
			return;
		}
		try {
			lock.refresh();
		} catch (LockException ex) {
			throw new IllegalStateException("The lock on ${path} has passed to another pass: ${ex.message}".toString(), ex);
		}
		renewedAt = now;
	}

	void release() {
		try {
			context.session.getResource(path).unlock();
		} catch (Throwable ignore) {}
	}

}

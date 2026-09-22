package webtop.mail;

/**
 * Runs the synchronization passes that are due. Called by the mail-sync route
 * on a timer, in a context of the mail service user.
 *
 * The service user owns the index (MailAccounts.INDEX_ROOT) and the locks
 * (MailAccounts.LOCK_ROOT) but cannot read the users' homes: each pass runs in a
 * context of the account's owner, so it can touch nothing else.
 *
 * An account is due when its interval has elapsed or a run was requested (new
 * settings, the Refresh button, a flag change, older mail). The timer fires on
 * every cluster node; the per-account lock makes sure one node runs a pass. The
 * lock is taken before the request is consumed, so a request made while a pass
 * is running stays queued for the next one.
 */
class MailScheduler {

	static final long LOCK_SECONDS = 600L;
	static final long ROUND_MILLIS = 4L * 60 * 1000;

	def context;
	def session;
	def log;
	def ScriptAPI;

	protected MailScheduler(context) {
		this.context = context;
		this.session = context.session;
		this.log = context.getAttribute('log');
		this.ScriptAPI = context.getAttribute('ScriptAPI');
	}

	static MailScheduler create(context) {
		return new MailScheduler(context);
	}

	/** Runs every due pass, stopping new ones once ROUND_MILLIS have gone by. */
	void runDue() {
		long startedAt = System.currentTimeMillis();
		for (Map entry : dueEntries()) {
			if (System.currentTimeMillis() - startedAt >= ROUND_MILLIS) {
				break;
			}
			try {
				runOne(entry);
			} catch (Throwable ex) {
				log?.warn("Mail pass for ${entry.userId}/${entry.accountId} failed: ${ex.message}".toString(), ex);
				try {
					session.rollback();
				} catch (Throwable ignore) {}
			}
		}
	}

	private List<Map> dueEntries() {
		def root = session.getResource(MailAccounts.INDEX_ROOT);
		if (!root.exists()) {
			return [];
		}
		long now = System.currentTimeMillis();
		List<Map> l = [];
		root.list().each { user ->
			if (!user.isCollection()) {
				return;
			}
			user.list().each { file ->
				if (file.isCollection() || !file.hasProperty('mail:accountId')) {
					return;
				}
				boolean enabled = !file.hasProperty('mail:enabled') || file.getProperty('mail:enabled').getBoolean();
				boolean requested = file.hasProperty('mail:requested') && file.getProperty('mail:requested').getBoolean();
				long nextRun = file.hasProperty('mail:nextRun') ? file.getProperty('mail:nextRun').getDate().getTimeInMillis() : 0L;
				if (enabled && (requested || nextRun <= now)) {
					l.add([
						path: file.path,
						userId: file.getProperty('mail:userId').getString(),
						accountId: file.getProperty('mail:accountId').getString(),
						requested: requested,
						nextRun: nextRun,
						intervalMinutes: file.hasProperty('mail:intervalMinutes') ? file.getProperty('mail:intervalMinutes').getLong() : MailAccounts.DEFAULT_INTERVAL_MINUTES,
					]);
				}
			};
		};
		// Requests first, then whatever has waited longest.
		l.sort { a, b -> (b.requested <=> a.requested) ?: (a.nextRun <=> b.nextRun); };
		return l;
	}

	private void runOne(Map entry) {
		String lockPath = "${MailAccounts.LOCK_ROOT}/${entry.userId}/${entry.accountId}.lock".toString();
		def lock = PassLock.tryAcquire(context, lockPath, LOCK_SECONDS);
		if (lock == null) {
			// Another node or an earlier round is running this account.
			return;
		}
		try {
			def file = session.getResource(entry.path as String);
			if (!file.exists()) {
				return;
			}
			file.setProperty('mail:requested', false);
			file.setProperty('mail:nextRun', new Date(System.currentTimeMillis() + (entry.intervalMinutes as long) * 60000L));
			session.commit();

			boolean more;
			def owner = ScriptAPI.createServiceUserContext(entry.userId as String);
			try {
				more = MailSync.create(owner, entry.accountId as String, lock).run();
			} finally {
				owner.close();
			}

			if (more) {
				// Work is left over: run again on the next tick.
				file.setProperty('mail:requested', true);
				session.commit();
			}
		} finally {
			lock.release();
		}
	}

}

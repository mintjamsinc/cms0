package webtop.mail;

/**
 * Runs the synchronization passes that are due. Called by the mail-sync route
 * on a timer, in a context of the mail service user.
 *
 * The accounts live in the users' homes, which the service user cannot list:
 * it finds them with a query on the settings files' mail:accountId property,
 * which returns the files whose accounts folder grants the mail service group
 * read (MailAccounts.ensureServiceAccess), and keeps the schedule in properties
 * of those files. It owns the locks (MailAccounts.LOCK_ROOT) and nothing else;
 * each pass runs in a context of the account's owner, so it can touch nothing
 * else of the home.
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
	static final long MAX_ACCOUNTS = 10000L;

	def context;
	def session;
	def log;
	def ScriptAPI;
	def XPath;

	protected MailScheduler(context) {
		this.context = context;
		this.session = context.session;
		this.log = context.getAttribute('log');
		this.ScriptAPI = context.getAttribute('ScriptAPI');
		this.XPath = context.getAttribute('XPath');
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

	/**
	 * The accounts due for a pass. The query finds the settings files the service
	 * user may read; the search index lags a commit by moments, so what decides is
	 * read from the files themselves.
	 */
	private List<Map> dueEntries() {
		def result = XPath.newBuilder().
				append("${MailAccounts.HOMES}//*[@mail:accountId and @mail:enabled=\$true]".toString()).
				variable('true', true).
				build().offset(0L).limit(MAX_ACCOUNTS).execute();
		long now = System.currentTimeMillis();
		List<Map> l = [];
		result.resources.each { file ->
			if (file.isCollection() || !file.hasProperty('mail:accountId')) {
				return;
			}
			// The owner and the account come from the path, which only the owner
			// can have written to, not from properties anyone with the file could set.
			def m = (file.path =~ /^\/home\/users\/([^\/]+)\/mail\/accounts\/([A-Za-z0-9_-]{1,64})\.json$/);
			if (!m.matches() || m.group(2) != file.getProperty('mail:accountId').getString()) {
				log?.warn("Ignoring a mail account entry out of place: ${file.path}".toString());
				return;
			}
			boolean enabled = !file.hasProperty('mail:enabled') || file.getProperty('mail:enabled').getBoolean();
			boolean requested = file.hasProperty('mail:requested') && file.getProperty('mail:requested').getBoolean();
			long nextRun = file.hasProperty('mail:nextRun') ? file.getProperty('mail:nextRun').getDate().getTimeInMillis() : 0L;
			if (enabled && (requested || nextRun <= now)) {
				l.add([
					path: file.path,
					userId: m.group(1),
					accountId: m.group(2),
					requested: requested,
					nextRun: nextRun,
					intervalMinutes: file.hasProperty('mail:intervalMinutes') ? file.getProperty('mail:intervalMinutes').getLong() : MailAccounts.DEFAULT_INTERVAL_MINUTES,
				]);
			}
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

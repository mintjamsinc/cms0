package webtop.mail;

import java.nio.file.Files;

import org.mintjams.tools.mail.MailFolder;
import org.mintjams.tools.mail.MailStore;

/**
 * One synchronization pass of one account.
 *
 * Messages are tracked by UID within UIDVALIDITY, never by Message-ID (which
 * many messages lack) or by date (which is often missing or wrong):
 *
 *   - First pass: the messages that arrived in the last `initialDays` days, as
 *     the server dates them (IMAP SEARCH SINCE on INTERNALDATE).
 *   - Later passes: every UID above the highest one seen.
 *   - Older mail on request: a further range of days before the oldest date
 *     fetched so far (requestOlder).
 *
 * Flags go both ways. Changes made in the Webtop are queued on the message
 * (mail:hasPending) and pushed first; then the server's flags are read back,
 * with CONDSTORE when the server has it and otherwise for the recent UIDs, plus
 * a full scan once a day that also notices messages removed on the server.
 *
 * Nothing is ever deleted or expunged on the server.
 *
 * A pass stops after PASS_MESSAGES messages or PASS_MILLIS and reports that more
 * is left, so a large first download is spread over several short passes instead
 * of holding the lock and the session for hours. Every message is committed on
 * its own, so an interrupted pass resumes where it stopped and one broken
 * message never takes others with it.
 */
class MailSync {

	static final int BATCH = 20;
	static final int PASS_MESSAGES = 400;
	static final long PASS_MILLIS = 3L * 60 * 1000;
	static final int FLAG_CHUNK = 2000;
	static final long FULL_SCAN_MILLIS = 24L * 60 * 60 * 1000;
	static final int MAX_ATTEMPTS = 3;
	static final long DAY = 24L * 60 * 60 * 1000;
	// Raised when stored mail has to be checked again: a pass that finds an older
	// state runs the full scan at once, which downloads what is missing.
	// 2: messages given up on because of empty multi-valued properties.
	// 3: the same, when a later message was stored before the check ran.
	static final int STATE_VERSION = 3;
	// Raised when the preview is computed differently: stored messages get the
	// new one, a page at a time, in the time a pass has left.
	// 2: ruled lines and runs of symbols are left out.
	static final int PREVIEW_VERSION = 2;
	static final long PREVIEW_PAGE = 100L;

	def context;
	def session;
	def log;
	def JSON;
	def XPath;
	def lock;
	MailAccounts accounts;
	MailStorage storage;
	Map account;
	Map state;
	long startedAt;
	int downloaded = 0;
	boolean more = false;

	protected MailSync(context, String accountId, lock) {
		this.context = context;
		this.session = context.session;
		this.log = context.getAttribute('log');
		this.JSON = context.getAttribute('JSON');
		this.XPath = context.getAttribute('XPath');
		this.lock = lock;
		this.accounts = MailAccounts.create(context);
		this.account = accounts.get(accountId);
		if (this.account == null) {
			throw new IllegalArgumentException("No such mail account: ${accountId}".toString());
		}
		this.storage = MailStorage.create(context, accounts.userId, accountId);
	}

	/**
	 * @param context a context of the account's owner
	 * @param lock the pass lock, refreshed as the pass goes on (may be null)
	 */
	static MailSync create(context, String accountId, lock) {
		return new MailSync(context, accountId, lock);
	}

	// --- state ------------------------------------------------------------------

	static Map readState(context, MailAccounts accounts, String accountId) {
		def file = context.session.getResource(accounts.statePath(accountId));
		if (!file.exists()) {
			return [folders: [:]];
		}
		try {
			Map s = context.getAttribute('JSON').parse(file.getContent()) as Map;
			if (!(s.folders instanceof Map)) {
				s.folders = [:];
			}
			return s;
		} catch (Throwable ex) {
			return [folders: [:]];
		}
	}

	static void writeState(context, MailAccounts accounts, String accountId, Map state) {
		def file = context.session.getResource(accounts.statePath(accountId));
		if (!file.exists()) {
			file.getParent().getOrCreateFolder();
			file.createFile();
		}
		file.write(context.getAttribute('JSON').stringify(state));
		file.setContentType(MailAccounts.STATE_TYPE);
		file.setContentEncoding('UTF-8');
	}

	private void saveState() {
		writeState(context, accounts, account.id as String, state);
		session.commit();
	}

	/** Asks the next pass to fetch `days` more days of older mail. */
	static void requestOlder(context, String accountId, int days) {
		MailAccounts accounts = MailAccounts.create(context);
		if (accounts.get(accountId) == null) {
			throw new IllegalArgumentException("No such mail account: ${accountId}".toString());
		}
		Map state = readState(context, accounts, accountId);
		state.fetchOlderDays = Math.max(1, Math.min(days, MailAccounts.MAX_INITIAL_DAYS));
		writeState(context, accounts, accountId, state);
		context.session.commit();
		MailAccounts.updateIndex(context, accounts.userId, accountId, [requested: true]);
	}

	// --- pass -------------------------------------------------------------------

	/** Runs the pass. Returns true when work is left for another pass. */
	boolean run() {
		startedAt = System.currentTimeMillis();
		state = readState(context, accounts, account.id as String);
		if (!(state.folders as Map)) {
			// Nothing downloaded yet: every message gets the current preview.
			state.previewVersion = PREVIEW_VERSION;
		}
		if (((state.version ?: 1) as int) < STATE_VERSION) {
			(state.folders as Map).values().each { fs ->
				(fs as Map).remove('lastFullScan');
				(fs as Map).remove('failed');
			};
			state.version = STATE_VERSION;
		}
		state.status = 'syncing';
		state.started = startedAt;
		state.progress = null;
		saveState();

		try {
			String password = accounts.password(account.id as String, 'incoming');
			def store = MailStore.Builder.create(MailAccounts.incomingURI(account)).
					setUsername((account.incoming as Map).username as String).
					setPassword(password).
					build();
			try {
				List<Map> folders = selectFolders(store.listFolders());
				Integer olderDays = state.fetchOlderDays as Integer;
				for (Map folder : folders) {
					syncFolder(store, folder, olderDays);
				}
				if (olderDays && !more) {
					state.remove('fetchOlderDays');
				}
				if (((state.previewVersion ?: 1) as int) < PREVIEW_VERSION) {
					refreshPreviews();
				}
			} finally {
				store.close();
			}
			state.status = 'idle';
			state.lastSuccess = System.currentTimeMillis();
			state.remove('lastError');
		} catch (Throwable ex) {
			log?.warn("Mail synchronization failed for ${accounts.userId}/${account.id}: ${ex.message}".toString(), ex);
			try {
				session.rollback();
			} catch (Throwable ignore) {}
			state.status = 'error';
			state.lastError = describeError(ex);
			more = false;
		} finally {
			state.lastRun = System.currentTimeMillis();
			state.progress = null;
			state.more = more;
			saveState();
		}
		return more;
	}

	static String describeError(Throwable ex) {
		Throwable t = ex;
		while (!t.message && t.cause != null && t.cause != t) {
			t = t.cause;
		}
		String message = t.message ?: t.class.simpleName;
		return (message.length() > 500) ? message.substring(0, 500) : message;
	}

	/**
	 * The folders to synchronize: INBOX and the Sent folder. The Sent folder is
	 * the one the server marks \Sent (RFC 6154), or else one with a usual name.
	 */
	static List<Map> selectFolders(List folders) {
		List<Map> l = [];
		def inbox = folders.find { it.fullName.equalsIgnoreCase('INBOX') };
		l.add([fullName: inbox ? inbox.fullName : 'INBOX', role: 'inbox']);

		def sent = folders.find { it.holdsMessages() && it.specialUse == 'sent' };
		if (sent == null) {
			sent = folders.find {
				it.holdsMessages() &&
						(it.name ==~ /(?i)sent|sent items|sent messages|sent mail|送信済み.*|送信箱/);
			};
		}
		if (sent != null) {
			l.add([fullName: sent.fullName, role: 'sent']);
		}
		return l;
	}

	private Map folderState(Map folder) {
		Map folders = state.folders as Map;
		Map fs = folders[folder.fullName] as Map;
		if (fs == null) {
			fs = [key: MailStorage.folderKey(folder.fullName as String)];
			folders[folder.fullName] = fs;
		}
		fs.role = folder.role;
		return fs;
	}

	private boolean outOfBudget() {
		return downloaded >= PASS_MESSAGES || (System.currentTimeMillis() - startedAt) >= PASS_MILLIS;
	}

	/**
	 * Recomputes the previews of the stored messages, oldest first, so messages
	 * arriving meanwhile land after the position reached. Stops when the pass is
	 * out of time and continues on the next one.
	 */
	private void refreshPreviews() {
		long offset = (state.previewOffset ?: 0L) as long;
		while ((System.currentTimeMillis() - startedAt) < PASS_MILLIS) {
			lock?.refresh();
			def result = XPath.newBuilder().
					append("${storage.root}//*".toString()).
					append('[@mail:account=$account]').
					append(' order by xs:dateTime(@mail:receivedDate)').
					variable('account', account.id as String).
					build().
					offset(offset).
					limit(PREVIEW_PAGE).
					execute();
			def resources = result.resources;
			for (def r : resources) {
				try {
					if (MailStorage.refreshPreview(r)) {
						session.commit();
					}
				} catch (Throwable ex) {
					try {
						session.rollback();
					} catch (Throwable ignore) {}
					log?.warn("The preview of ${r.path} could not be recomputed: ${ex.message}".toString());
				}
			}
			offset += resources.size();
			if (!result.hasMore()) {
				state.previewVersion = PREVIEW_VERSION;
				state.remove('previewOffset');
				saveState();
				return;
			}
			state.previewOffset = offset;
			saveState();
		}
		more = true;
	}

	private void syncFolder(store, Map folder, Integer olderDays) {
		Map fs = folderState(folder);
		Map target = [fullName: folder.fullName, role: folder.role, key: fs.key];

		// Local changes first, so reading the server's flags does not undo them.
		pushPending(store, folder, fs);

		MailFolder f = store.openFolder(folder.fullName as String);
		try {
			long validity = f.getUIDValidity();
			if (fs.uidValidity != null && (fs.uidValidity as long) != validity) {
				// The server renumbered the folder: every stored UID is void.
				log?.info("UIDVALIDITY of ${folder.fullName} changed for ${accounts.userId}/${account.id}; downloading it again.".toString());
				storage.discardFolder(fs.key as String);
				['lastUid', 'oldestUid', 'modSeq', 'lastFullScan', 'failed'].each { fs.remove(it); };
			}
			fs.uidValidity = validity;

			// New messages.
			List<Long> uids;
			if (fs.lastUid == null) {
				long days = (account.initialDays ?: MailAccounts.DEFAULT_INITIAL_DAYS) as long;
				Date since = startOfDay(new Date(System.currentTimeMillis() - days * DAY));
				uids = f.search(since, null).toList();
				fs.since = since.time;
				fs.lastUid = uids ? (uids[0] - 1) : (f.getUIDNext() - 1);
				fs.modSeq = f.getHighestModSeq();
				saveState();
			} else {
				uids = f.listUIDs((fs.lastUid as long) + 1, -1L).toList();
			}
			download(f, target, fs, validity, uids, true);

			// Older mail on request, newest first.
			if (olderDays && !outOfBudget()) {
				long since = (fs.since ?: System.currentTimeMillis()) as long;
				Date from = startOfDay(new Date(since - olderDays * DAY));
				List<Long> older = f.search(from, new Date(since)).toList().findAll {
					!storage.exists(fs.key as String, validity, it);
				};
				download(f, target, fs, validity, older.reverse(), false);
				if (!outOfBudget()) {
					fs.since = from.time;
					saveState();
				}
			}

			if (!outOfBudget()) {
				pullFlags(f, target, fs, validity);
			}
			saveState();
		} finally {
			f.close();
		}
	}

	private static Date startOfDay(Date d) {
		Calendar c = Calendar.getInstance();
		c.setTime(d);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		return c.getTime();
	}

	/**
	 * Downloads messages in batches. `advance` moves the folder's high-water mark
	 * (new mail); older mail leaves it alone.
	 */
	private void download(MailFolder f, Map folder, Map fs, long validity, List<Long> uids, boolean advance) {
		// UID -> failed attempts. Kept after MAX_ATTEMPTS so the count does not
		// start over; dropped once the high-water mark has passed the UID.
		Map failed = (fs.failed ?: [:]) as Map;
		int total = uids.size();
		int done = 0;
		for (List<Long> batch : uids.collate(BATCH)) {
			if (outOfBudget()) {
				more = true;
				break;
			}
			lock?.refresh();
			state.progress = [folder: folder.fullName, done: done, total: total];
			saveState();

			for (def summary : f.fetch(batch as long[])) {
				long uid = summary.UID;
				if (storage.exists(fs.key as String, validity, uid)) {
					continue;
				}
				downloaded++;
				File raw = Files.createTempFile('mail-', '.eml').toFile();
				try {
					boolean found = false;
					raw.withOutputStream { out ->
						found = f.writeTo(uid, out);
					};
					if (!found) {
						// Removed on the server meanwhile.
						continue;
					}
					try {
						storage.store(folder, validity, summary, raw);
						session.commit();
					} catch (Throwable storeError) {
						// The message was downloaded; keep it even if its metadata cannot
						// be stored, so it is never lost.
						session.rollback();
						log?.warn("Mail message ${folder.fullName}/${uid} stored without its metadata: ${storeError.message}".toString());
						storage.storeMinimal(folder, validity, summary, raw, storeError);
						session.commit();
					}
					failed.remove(uid.toString());
					if (fs.oldestUid == null || uid < (fs.oldestUid as long)) {
						fs.oldestUid = uid;
					}
				} catch (Throwable ex) {
					// One bad message must not stop the account. It is retried on later
					// passes and skipped for good after MAX_ATTEMPTS.
					try {
						session.rollback();
					} catch (Throwable ignore) {}
					int attempts = ((failed[uid.toString()] ?: 0) as int) + 1;
					failed[uid.toString()] = attempts;
					log?.warn("Mail message ${folder.fullName}/${uid} could not be downloaded (attempt ${attempts}): ${ex.message}".toString());
				} finally {
					raw.delete();
				}
			}

			if (advance) {
				long highest = batch.max();
				// Stay below a message that failed and may still succeed.
				List<Long> retry = failed.findAll { k, v -> (v as int) < MAX_ATTEMPTS }.
						keySet().collect { it as long }.findAll { it <= highest };
				long mark = retry ? (retry.min() - 1) : highest;
				fs.lastUid = Math.max(fs.lastUid as long, mark);
				long passed = fs.lastUid as long;
				failed = failed.findAll { k, v -> (k as long) > passed || (v as int) < MAX_ATTEMPTS };
			}
			fs.failed = failed;
			done += batch.size();
		}
		state.progress = null;
		saveState();
	}

	/** Applies the server's flags to the stored messages. */
	private void pullFlags(MailFolder f, Map target, Map fs, long validity) {
		if (fs.lastUid == null) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean fullScan = (fs.lastFullScan == null) || (now - (fs.lastFullScan as long)) >= FULL_SCAN_MILLIS;
		Long oldestUid = fs.oldestUid as Long;
		if (fullScan && fs.since != null) {
			// The full scan covers the whole synchronized period, not only what was
			// stored: a message that failed before the first success lies below
			// the oldest stored UID.
			long[] uids = f.search(new Date(fs.since as long), null);
			if (uids.length > 0 && (oldestUid == null || uids[0] < oldestUid)) {
				oldestUid = uids[0];
			}
		}
		if (oldestUid == null) {
			return;
		}
		long last = fs.lastUid as long;
		long oldest = oldestUid;
		boolean condStore = f.isCondStoreEnabled() && fs.modSeq != null && (fs.modSeq as long) > 0;

		Set<Long> present = new HashSet<>();
		if (condStore && !fullScan) {
			apply(fs, validity, f.fetchChangedSince(fs.modSeq as long));
		} else {
			// Without CONDSTORE the recent UIDs are checked every pass and all of
			// them once a day, in chunks so no single response gets huge.
			long from = fullScan ? oldest : Math.max(oldest, last - FLAG_CHUNK);
			for (long start = from; start <= last; start += FLAG_CHUNK) {
				lock?.refresh();
				List summaries = f.fetch(start, Math.min(start + FLAG_CHUNK - 1, last));
				apply(fs, validity, summaries);
				summaries.each { present.add(it.UID as long); };
				session.commit();
			}
		}

		boolean complete = true;
		if (fullScan) {
			markRemoved(fs, validity, present);
			complete = downloadMissing(f, target, fs, validity, present);
		}
		if (fullScan && complete) {
			fs.lastFullScan = now;
		}
		if (f.isCondStoreEnabled()) {
			fs.modSeq = f.getHighestModSeq();
		}
		session.commit();
	}

	/**
	 * Downloads the messages the server has in the synchronized range but that are
	 * not stored, e.g. ones given up on earlier. Returns false when the pass ran
	 * out of budget before all of them were done.
	 */
	private boolean downloadMissing(MailFolder f, Map target, Map fs, long validity, Set<Long> present) {
		Map failed = (fs.failed ?: [:]) as Map;
		List<Long> missing = present.findAll { uid ->
			!storage.exists(fs.key as String, validity, uid) &&
					((failed[uid.toString()] ?: 0) as int) < MAX_ATTEMPTS;
		}.sort().reverse();
		if (!missing) {
			return true;
		}
		log?.info("Downloading ${missing.size()} missing message(s) of ${target.fullName} for ${accounts.userId}/${account.id}.".toString());
		download(f, target, fs, validity, missing, false);
		return !outOfBudget();
	}

	private void apply(Map fs, long validity, List summaries) {
		for (def summary : summaries) {
			def file = storage.getMessage(fs.key as String, validity, summary.UID as long);
			if (!file.exists() || flag(file, 'mail:hasPending')) {
				continue;
			}
			if (differs(file, summary)) {
				MailStorage.setFlags(file, summary);
			}
		}
	}

	private static boolean differs(file, summary) {
		return flag(file, 'mail:seen') != summary.seen ||
				flag(file, 'mail:flagged') != summary.flagged ||
				flag(file, 'mail:answered') != summary.answered ||
				flag(file, 'mail:draft') != summary.draft ||
				flag(file, 'mail:deleted') != summary.deleted;
	}

	static boolean flag(file, String name) {
		return file.hasProperty(name) && file.getProperty(name).getBoolean();
	}

	/** Marks stored messages that no longer exist on the server. */
	private void markRemoved(Map fs, long validity, Set<Long> present) {
		long offset = 0L;
		while (true) {
			def result = XPath.newBuilder().
					append("${storage.folderPath(fs.key as String)}//*".toString()).
					append('[@mail:uidValidity=$validity and @mail:removed=$removed]').
					variable('validity', validity).
					variable('removed', false).
					build().
					offset(offset).
					limit(500L).
					execute();
			def resources = result.resources;
			if (!resources) {
				break;
			}
			int kept = 0;
			for (def r : resources) {
				if (present.contains(r.getProperty('mail:uid').getLong())) {
					kept++;
				} else {
					r.setProperty('mail:removed', true);
				}
			}
			session.commit();
			if (!result.hasMore()) {
				break;
			}
			// Marked messages drop out of the result; only the kept ones are skipped.
			offset += kept;
		}
	}

	/** Pushes flag changes made in the Webtop to the server. */
	private void pushPending(store, Map folder, Map fs) {
		if (fs.uidValidity == null) {
			return;
		}
		def result = XPath.newBuilder().
				append("${storage.folderPath(fs.key as String)}//*".toString()).
				append('[@mail:hasPending=$pending and @mail:uidValidity=$validity]').
				variable('pending', true).
				variable('validity', fs.uidValidity as long).
				build().
				limit(500L).
				execute();
		def resources = result.resources;
		if (!resources) {
			return;
		}

		Map<String, List<Long>> set = [:].withDefault { [] };
		Map<String, List<Long>> clear = [:].withDefault { [] };
		for (def r : resources) {
			Map pending = r.hasProperty('mail:pending') ? (JSON.parse(r.getProperty('mail:pending').getString()) as Map) : [:];
			long uid = r.getProperty('mail:uid').getLong();
			pending.each { name, value ->
				(value ? set : clear)[name as String].add(uid);
			};
		}

		MailFolder f = store.openFolder(folder.fullName as String, true);
		try {
			if (f.getUIDValidity() == (fs.uidValidity as long)) {
				set.each { name, uids -> f.setFlag(imapFlag(name), true, uids as long[]); };
				clear.each { name, uids -> f.setFlag(imapFlag(name), false, uids as long[]); };
			}
		} finally {
			f.close();
		}

		for (def r : resources) {
			r.setProperty('mail:hasPending', false);
			if (r.hasProperty('mail:pending')) {
				r.removeProperty('mail:pending');
			}
		}
		session.commit();
		if (result.hasMore()) {
			more = true;
		}
	}

	static String imapFlag(String name) {
		switch (name) {
		case 'seen':
			return MailFolder.SEEN;
		case 'flagged':
			return MailFolder.FLAGGED;
		case 'answered':
			return MailFolder.ANSWERED;
		default:
			throw new IllegalArgumentException("Unknown flag: ${name}".toString());
		}
	}

}

package webtop.chat;

import java.text.SimpleDateFormat;

/**
 * Removes what the chat no longer needs. Run by hand, as the chat service
 * user, from the BPMN process chat-maintenance (etc/bpm/processes/webtop/chat);
 * nothing is removed on its own.
 *
 * Messages are removed by the day: a day folder older than what is kept goes
 * as a whole, with the attachments in it, and nothing in it is looked at. The
 * days are UTC days, as the folders are: keeping N days keeps the UTC day of
 * today and the N-1 before it. The
 * signals and the mention marks of the same days go with it. Each folder is
 * committed on its own, so a run that is stopped has still removed what it
 * removed.
 *
 * A conversation whose file is gone (the route that removes those missed it,
 * or its queue was full) is removed with its signals.
 */
class ChatMaintenance {

	static final String TARGET_CHANNELS = 'channels';
	static final String TARGET_DIRECT = 'dm';
	static final String TARGET_FILES = 'files';
	static final int MIN_KEEP_DAYS = 1;

	def session;
	def log;

	ChatMaintenance(session, log) {
		this.session = session;
		this.log = log;
	}

	static ChatMaintenance create(session, log = null) {
		return new ChatMaintenance(session, log);
	}

	/**
	 * options: targets (which conversations: channels, dm, files), keepDays
	 * (how many days of messages stay; nothing is removed when absent) and
	 * orphans (whether to remove the conversations of files that are gone).
	 * Returns the counts of what was removed.
	 */
	Map run(Map options) {
		List<String> targets = (options.targets ?: []).collect { it.toString() };
		Integer keepDays = (options.keepDays != null) ? (options.keepDays as int) : null;
		if (keepDays != null && keepDays < MIN_KEEP_DAYS) {
			throw new IllegalArgumentException("At least ${MIN_KEEP_DAYS} day of messages is kept.".toString());
		}
		Map result = [days: 0, conversations: 0, signalDays: 0, mentionDays: 0, orphans: 0, errors: 0];
		String cutoff = (keepDays != null) ? firstKeptDay(keepDays) : null;

		if (cutoff != null) {
			if (targets.contains(TARGET_CHANNELS) || targets.contains(TARGET_DIRECT)) {
				ChatChannels.ids(session).each { String id ->
					Map channel = ChatChannels.read(session, id);
					if (channel == null) {
						return;
					}
					boolean direct = channel.kind == ChatChannels.DIRECT;
					if (direct ? !targets.contains(TARGET_DIRECT) : !targets.contains(TARGET_CHANNELS)) {
						return;
					}
					int removed = removeDaysBefore("${channel.path}/messages".toString(), cutoff, result);
					result.days += removed;
					if (removed) {
						result.conversations++;
					}
				};
			}
			if (targets.contains(TARGET_FILES)) {
				childFolders(ChatStore.FILES).each { String fileId ->
					int removed = removeDaysBefore("${ChatStore.fileRoot(fileId)}/messages".toString(), cutoff, result);
					result.days += removed;
					if (removed) {
						result.conversations++;
					}
				};
				childFolders(ChatStore.SIGNALS).each { String fileId ->
					result.signalDays += removeDaysBefore(ChatStore.signalRoot(fileId), cutoff, result);
				};
			}
			if (targets) {
				childFolders(ChatStore.MENTIONS).each { String userId ->
					result.mentionDays += removeDaysBefore(ChatStore.mentionRoot(userId), cutoff, result);
				};
			}
		}

		if (options.orphans) {
			childFolders(ChatStore.FILES).each { String fileId ->
				try {
					if (!ChatStore.exists(session, fileId) && ChatStore.removeFileConversation(session, fileId)) {
						result.orphans++;
					}
				} catch (Throwable ex) {
					result.errors++;
					warn("The conversation of ${fileId} could not be removed: ${ex.message}".toString());
				}
			};
		}
		return result;
	}

	/**
	 * The first day that stays, as yyyy/MM/dd in UTC, when `keepDays` days are
	 * kept counting today: the day folders are named by the UTC day, so the
	 * days are UTC days. Keeping 1 keeps today only.
	 */
	static String firstKeptDay(int keepDays) {
		SimpleDateFormat format = new SimpleDateFormat('yyyy/MM/dd');
		format.setTimeZone(TimeZone.getTimeZone('UTC'));
		return format.format(new Date(System.currentTimeMillis() - (keepDays - 1) * 86400000L));
	}

	/**
	 * Removes the day folders under the root (yyyy/mm/dd) that lie before the
	 * cutoff day, whole months and years at once when all of them do. Returns
	 * how many days were removed; a folder that cannot be removed is counted
	 * as an error and left.
	 */
	private int removeDaysBefore(String root, String cutoff, Map result) {
		int removed = 0;
		def base = session.getResource(root);
		if (!base.exists()) {
			return 0;
		}
		for (String y : childFolders(root)) {
			if (y >= cutoff.substring(0, 4)) {
				// From this year on, the months decide; years after need no look.
				if (y > cutoff.substring(0, 4)) {
					continue;
				}
				for (String m : childFolders("${root}/${y}".toString())) {
					String month = "${y}/${m}".toString();
					if (month > cutoff.substring(0, 7)) {
						continue;
					}
					if (month < cutoff.substring(0, 7)) {
						removed += removeFolder("${root}/${month}".toString(), countDays("${root}/${month}".toString(), 1), result);
						continue;
					}
					for (String d : childFolders("${root}/${month}".toString())) {
						if ("${month}/${d}".toString() < cutoff) {
							removed += removeFolder("${root}/${month}/${d}".toString(), 1, result);
						}
					}
				}
				continue;
			}
			removed += removeFolder("${root}/${y}".toString(), countDays("${root}/${y}".toString(), 2), result);
		}
		return removed;
	}

	/** The day folders under a month (levels = 1) or a year (levels = 2). */
	private int countDays(String path, int levels) {
		List<String> children = childFolders(path);
		if (levels <= 1) {
			return children.size();
		}
		int days = 0;
		children.each { String child -> days += countDays("${path}/${child}".toString(), levels - 1); };
		return days;
	}

	/** Removes the folder and commits; returns the days it held, or 0 when it could not be removed. */
	private int removeFolder(String path, int days, Map result) {
		try {
			def folder = session.getResource(path);
			if (folder.exists()) {
				folder.remove();
				session.commit();
			}
			return days;
		} catch (Throwable ex) {
			session.rollback();
			result.errors++;
			warn("${path} could not be removed: ${ex.message}".toString());
			return 0;
		}
	}

	private List<String> childFolders(String path) {
		List<String> names = [];
		def folder = session.getResource(path);
		if (!folder.exists()) {
			return names;
		}
		def children = folder.list();
		while (children.hasNext()) {
			def r = children.next();
			if (r.isCollection()) {
				names.add(r.name as String);
			}
		}
		return names.sort();
	}

	private void warn(String message) {
		try {
			log?.warn(message);
		} catch (Throwable ignore) {}
	}

}

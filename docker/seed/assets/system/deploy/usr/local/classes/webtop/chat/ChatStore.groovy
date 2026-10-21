package webtop.chat;

import java.security.Principal;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;

/**
 * Where the conversations live and how they are written.
 *
 *   /var/lib/chat/channels/<channel>/.channel               settings of the channel
 *   /var/lib/chat/channels/<channel>/messages/yyyy/mm/dd/   one .md file per message
 *   /var/lib/chat/files/<fileId>/messages/yyyy/mm/dd/       the conversation of a file
 *   /var/lib/chat/signals/<fileId>/yyyy/mm/dd/<id>          marks that it changed
 *
 * The area is closed to everyone (provisioning/chat.yml). Whoever is granted
 * jcr:read on the folder of a channel takes part in it. Every write is made by
 * the chat service user, after the caller's rights were checked in the caller's
 * own session, so a message cannot be changed from the Content Browser.
 *
 * The conversation of a file is kept by the file's identifier, apart from the
 * file, and nobody is granted anything on it: whoever can read the file reads
 * the conversation through the resolvers. Since its messages cannot be watched,
 * every change leaves an empty folder under signals, which everyone can read.
 * A signal tells that the conversation of that identifier changed, and when;
 * not what was said, by whom, or where the file is.
 *
 * A post only adds a node. Nothing shared is updated per post, so posts to the
 * same conversation never conflict; what would be such a value (the time of the
 * last message) is read from the name of the newest message instead.
 */
class ChatStore {

	static final String SERVICE_USER = 'chat-service-user';
	static final String SERVICE_GROUP = 'chat-service-group';
	static final String ROOT = '/var/lib/chat';
	static final String CHANNELS = ROOT + '/channels';
	static final String FILES = ROOT + '/files';
	static final String SIGNALS = ROOT + '/signals';
	static final String HOMES = '/home/users';
	static final String MENTIONS_FOLDER = 'chat/mentions';
	// How many of a user's latest mention marks are kept; older ones go with the next mark.
	static final int MAX_MENTIONS = 100;

	static final String EVERYONE = 'everyone';
	static final String ANONYMOUS = 'anonymous';
	static final String READ = 'jcr:read';
	static final String ALL = 'jcr:all';

	private static final SecureRandom RANDOM = new SecureRandom();

	static String checkId(String id) {
		if (!(id ==~ /[A-Za-z0-9_-]{1,64}/)) {
			throw new IllegalArgumentException("Invalid id: ${id}".toString());
		}
		return id;
	}

	static String randomHex(int length) {
		StringBuilder buf = new StringBuilder();
		while (buf.length() < length) {
			buf.append(Integer.toHexString(RANDOM.nextInt(16)));
		}
		return buf.toString();
	}

	static String newId() {
		return randomHex(16);
	}

	/**
	 * The id of a message posted at the given time: the time, then random digits.
	 * Ids sort in the order the messages were posted.
	 */
	static String newMessageId(Date time) {
		return String.format('%012x', time.time) + randomHex(8);
	}

	static String checkMessageId(String id) {
		if (!(id ==~ /[0-9a-f]{20}/)) {
			throw new IllegalArgumentException("Invalid message id: ${id}".toString());
		}
		return id;
	}

	static Date messageTime(String id) {
		return new Date(Long.parseLong(id.substring(0, 12), 16));
	}

	/** The folder of the day a message was posted on, as yyyy/MM/dd in UTC. */
	static String dayOf(String id) {
		SimpleDateFormat format = new SimpleDateFormat('yyyy/MM/dd');
		format.setTimeZone(TimeZone.getTimeZone('UTC'));
		return format.format(messageTime(id));
	}

	/** Where the conversation of a file is kept. */
	static String fileRoot(String fileId) {
		return "${FILES}/${checkId(fileId)}".toString();
	}

	/** What a client watches to learn that the conversation of a file changed. */
	static String signalRoot(String fileId) {
		return "${SIGNALS}/${checkId(fileId)}".toString();
	}

	/** Whether there is a file or folder of that identifier, whoever may read it. */
	static boolean exists(session, String fileId) {
		try {
			return session.getResourceByIdentifier(fileId).exists();
		} catch (Throwable ignore) {
			return false;
		}
	}

	/** Leaves a mark that the conversation of the file changed. */
	static void signal(session, String fileId) {
		String id = newMessageId(new Date());
		String path = "${signalRoot(fileId)}/${dayOf(id)}/${id}".toString();
		for (int attempt = 0; ; attempt++) {
			try {
				session.getResource(path).createFolder();
				session.commit();
				return;
			} catch (Throwable ex) {
				session.rollback();
				// Two changes may create the day's folder at the same time.
				if (attempt >= 2) {
					throw ex;
				}
			}
		}
	}

	/**
	 * Removes the conversation of a file that no longer exists, with its
	 * signals. Returns whether there was one.
	 */
	static boolean removeFileConversation(session, String fileId) {
		boolean removed = false;
		[fileRoot(fileId), signalRoot(fileId)].each { String path ->
			def folder = session.getResource(path);
			if (folder.exists()) {
				folder.remove();
				removed = true;
			}
		};
		if (removed) {
			session.commit();
		}
		return removed;
	}

	/**
	 * A node was removed from the repository (etc/eip/routes/webtop/chat.xml):
	 * removes the conversation kept by its identifier, when it has one. What
	 * is removed under the chat area itself is no file with a conversation.
	 */
	static boolean nodeRemoved(scriptAPI, String identifier, String path) {
		if (!(identifier ==~ /[A-Za-z0-9_-]{1,64}/)) {
			return false;
		}
		if (path != null && (path == ROOT || path.startsWith(ROOT + '/'))) {
			return false;
		}
		def service = scriptAPI.createServiceUserContext(SERVICE_USER);
		try {
			return removeFileConversation(service.session, identifier);
		} finally {
			service.close();
		}
	}

	/** Whether the name of a privilege is jcr:read, written with its prefix or with its namespace. */
	static boolean isRead(String privilegeName) {
		return privilegeName == READ || privilegeName == '{http://www.jcp.org/jcr/1.0}read';
	}

	/** Runs the closure with a session of the chat service user. */
	static Object withService(context, Closure closure) {
		return withServiceContext(context) { service -> closure.call(service.session); };
	}

	/** Runs the closure with the whole script context of the chat service user (for queries). */
	static Object withServiceContext(context, Closure closure) {
		def service = context.getAttribute('ScriptAPI').createServiceUserContext(SERVICE_USER);
		try {
			return closure.call(service);
		} finally {
			service.close();
		}
	}

	/** A short id that two users always arrive at together, whichever of them asks first. */
	static String directMessageId(String a, String b) {
		List<String> pair = [a, b].sort();
		def digest = java.security.MessageDigest.getInstance('SHA-256').digest("${pair[0]}\n${pair[1]}".toString().getBytes('UTF-8'));
		return 'dm-' + (digest as List).take(12).collect { String.format('%02x', (it as int) & 0xff) }.join('');
	}

	// --- mentions -----------------------------------------------------------------

	/** A user id as the name of a node: anything but what a JCR name cannot hold. */
	static String checkUserId(String userId) {
		if (!userId || userId.length() > 255 || userId in ['.', '..'] || userId =~ /[\/:\[\]|*\p{Cntrl}]/) {
			throw new IllegalArgumentException("Invalid user id: ${userId}".toString());
		}
		return userId;
	}

	/**
	 * Where the marks of a user's mentions are kept: a folder of the user's
	 * home (/home/users/<user>/chat/mentions), which goes with the user —
	 * deleting the user removes it, and a user made anew under the same id
	 * starts with none. The user owns it and watches it; the service group is
	 * granted it when it is created, and writes the marks.
	 */
	static String mentionRoot(String userId) {
		return "${HOMES}/${checkUserId(userId)}/${MENTIONS_FOLDER}".toString();
	}

	/**
	 * Leaves a mark that the user was mentioned in a message: an nt:file named
	 * by the message, under the day, carrying where the message is. Written in
	 * the service session; the first time, the user's folder is created as the
	 * user (only the user may write the home) and granted to the service
	 * group. The latest MAX_MENTIONS marks are kept, the older going now.
	 */
	static void mention(scriptAPI, session, String userId, Map ref, String messageId, String author) {
		def root = ensureMentionRoot(scriptAPI, session, userId);
		def mark = root.getResource("${dayOf(messageId)}/${messageId}".toString());
		if (mark.exists()) {
			return;
		}
		mark.getParent().getOrCreateFolder();
		mark.createFile();
		mark.setContentType('application/vnd.mintjams.webtop.chat.mention');
		if (ref.channelId) {
			mark.setProperty('chat:channelId', ref.channelId as String);
		} else {
			mark.setProperty('chat:fileId', ref.fileId as String);
		}
		mark.setProperty('chat:author', author);
		pruneMentions(root, MAX_MENTIONS);
		session.commit();
	}

	/**
	 * The user's mention folder as the service session sees it, created the
	 * first time as the user through ScriptAPI.createUserContext, which also
	 * makes sure of the user's home in this workspace. The owner's session
	 * commits the folder on its own; the service session has nothing pending
	 * when this returns.
	 */
	private static Object ensureMentionRoot(scriptAPI, session, String userId) {
		String path = mentionRoot(userId);
		def root = session.getResource(path);
		if (root.exists()) {
			return root;
		}
		Principal group = findPrincipal(session, SERVICE_GROUP);
		if (group == null) {
			throw new IllegalStateException("The group ${SERVICE_GROUP} is not provisioned (provisioning/chat.yml).".toString());
		}
		def owner = scriptAPI.createUserContext(userId);
		try {
			def ownerSession = owner.session;
			try {
				def folder = ownerSession.getResource(path);
				if (!folder.exists()) {
					folder.getParent().getOrCreateFolder();
					folder.createFolder();
					def acl = folder.getAccessControlList();
					acl.addAccessControlEntry(group, true, ALL);
					folder.setAccessControlList(acl);
					ownerSession.commit();
				}
			} catch (Throwable ex) {
				ownerSession.rollback();
				throw ex;
			}
		} finally {
			owner.close();
		}
		root = session.getResource(path);
		if (!root.exists()) {
			throw new IllegalStateException("The folder ${path} was created but the service user cannot read it.".toString());
		}
		return root;
	}

	/**
	 * The latest mentions of the user, newest first, up to the given number:
	 * [messageId, channelId, fileId, author] each. Read in the user's own
	 * session, which is the one that may.
	 */
	static List<Map> recentMentions(session, String userId, int max) {
		List<Map> marks = [];
		def root = session.getResource(mentionRoot(userId));
		if (!root.exists()) {
			return marks;
		}
		eachMention(root) { mark ->
			if (marks.size() >= max) {
				return false;
			}
			marks.add([
				messageId: mark.name as String,
				channelId: mark.hasProperty('chat:channelId') ? mark.getProperty('chat:channelId').getString() : null,
				fileId: mark.hasProperty('chat:fileId') ? mark.getProperty('chat:fileId').getString() : null,
				author: mark.hasProperty('chat:author') ? mark.getProperty('chat:author').getString() : null,
			]);
			return true;
		};
		return marks;
	}

	/**
	 * Removes the marks under the root beyond the newest `keep`, and the day,
	 * month and year folders left empty. Nothing is committed here.
	 */
	private static void pruneMentions(root, int keep) {
		int seen = 0;
		for (String y : sortedNames(root, true)) {
			def year = root.getResource(y);
			for (String m : sortedNames(year, true)) {
				def month = year.getResource(m);
				for (String d : sortedNames(month, true)) {
					def day = month.getResource(d);
					List<String> ids = sortedNames(day, false);
					for (String id : ids) {
						if (++seen > keep) {
							day.getResource(id).remove();
						}
					}
					if (seen > keep && seen - ids.size() >= keep) {
						// Every mark of the day went; nothing else is kept in a day.
						day.remove();
					}
				}
				if (seen > keep && sortedNames(month, true).isEmpty()) {
					month.remove();
				}
			}
			if (seen > keep && sortedNames(year, true).isEmpty()) {
				year.remove();
			}
		}
	}

	/** Calls the closure with each mark under the root, newest first, until it returns false. */
	private static void eachMention(root, Closure each) {
		for (String y : sortedNames(root, true)) {
			def year = root.getResource(y);
			for (String m : sortedNames(year, true)) {
				def month = year.getResource(m);
				for (String d : sortedNames(month, true)) {
					def day = month.getResource(d);
					for (String id : sortedNames(day, false)) {
						if (!each.call(day.getResource(id))) {
							return;
						}
					}
				}
			}
		}
	}

	/** The names of the folders, or of the files, under the folder; the newest first. */
	private static List<String> sortedNames(folder, boolean collections) {
		List<String> l = [];
		def children = folder.list();
		while (children.hasNext()) {
			def r = children.next();
			if (r.isCollection() == collections) {
				l.add(r.name as String);
			}
		}
		return l.sort().reverse();
	}

	/** The principal of that name, or null when there is no such user or group. */
	static Principal findPrincipal(session, String name) {
		try {
			return session.principalProvider.getPrincipal(name);
		} catch (Throwable ignore) {
			return null;
		}
	}

	/** A built-in principal (everyone, anonymous), which no identity store has to know. */
	static Principal builtin(session, String name) {
		return findPrincipal(session, name) ?: ({ -> name } as Principal);
	}

}

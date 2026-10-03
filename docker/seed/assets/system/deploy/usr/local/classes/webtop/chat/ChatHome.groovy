package webtop.chat;

/**
 * What a user keeps about the conversations, in the user's home:
 *
 *   /home/users/<user>/chat/read/<key>        how far the conversation was read
 *   /home/users/<user>/chat/following/<key>   a conversation in the sidebar
 *   /home/users/<user>/chat/uploads/<draft>/   files uploaded for a message not yet posted
 *
 * The user owns the home, so this is the one place written in the user's own
 * session. Nothing here is written when somebody else posts: whether a
 * conversation has unread messages is the time of its last message compared
 * with the read position.
 */
class ChatHome {

	static final String READ_AT = 'chat:readAt';
	static final String READ_TYPE = 'application/vnd.mintjams.webtop.chat.read';

	def session;
	String root;
	private Set<String> followingKeys;
	private Map<String, Date> readMarks;

	ChatHome(session, String userId) {
		this.session = session;
		this.root = "/home/users/${userId}/chat".toString();
	}

	static final String FILE_PREFIX = 'file-';

	static String channelKey(String channelId) {
		return "channel-${ChatStore.checkId(channelId)}".toString();
	}

	static String fileKey(String fileId) {
		return "${FILE_PREFIX}${ChatStore.checkId(fileId)}".toString();
	}

	/** The keys of the conversations in the sidebar. */
	Set<String> following() {
		if (followingKeys == null) {
			followingKeys = [] as Set;
			def folder = session.getResource("${root}/following".toString());
			if (folder.exists()) {
				def children = folder.list();
				while (children.hasNext()) {
					followingKeys.add(children.next().name as String);
				}
			}
		}
		return followingKeys;
	}

	/** Creates the folder the sidebar is kept in; fails when the home cannot be written. */
	void prepare() {
		def folder = session.getResource("${root}/following".toString());
		if (!folder.exists()) {
			folder.createFolder();
			session.commit();
		}
	}

	void follow(String key) {
		def mark = session.getResource("${root}/following/${key}".toString());
		if (!mark.exists()) {
			mark.createFolder();
			session.commit();
		}
		followingKeys = null;
	}

	void unfollow(String key) {
		def mark = session.getResource("${root}/following/${key}".toString());
		if (mark.exists()) {
			mark.remove();
			session.commit();
		}
		followingKeys = null;
	}

	/** Where the files of a message being written are uploaded, before it is posted. */
	String uploadsPath(String draftId) {
		return "${root}/uploads/${ChatStore.checkId(draftId)}".toString();
	}

	/** Removes what was uploaded for a message, once it is posted. */
	void removeUploads(String draftId) {
		if (!draftId) {
			return;
		}
		def folder = session.getResource(uploadsPath(draftId));
		if (folder.exists()) {
			folder.remove();
			session.commit();
		}
	}

	/** The read positions, by the key of the conversation. */
	Map<String, Date> reads() {
		if (readMarks == null) {
			readMarks = [:];
			def folder = session.getResource("${root}/read".toString());
			if (folder.exists()) {
				def children = folder.list();
				while (children.hasNext()) {
					def r = children.next();
					if (!r.isCollection() && r.hasProperty(READ_AT)) {
						readMarks[r.name as String] = r.getProperty(READ_AT).getDate().time;
					}
				}
			}
		}
		return readMarks;
	}

	Date markRead(String key) {
		Date now = new Date();
		def mark = session.getResource("${root}/read/${key}".toString());
		if (!mark.exists()) {
			mark.getParent().getOrCreateFolder();
			mark.createFile();
			mark.setContentType(READ_TYPE);
		}
		mark.setProperty(READ_AT, now);
		session.commit();
		readMarks = null;
		return now;
	}

}

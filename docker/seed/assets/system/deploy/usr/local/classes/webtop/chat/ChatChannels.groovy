package webtop.chat;

/**
 * Channels: their settings and who takes part in them. Every method takes the
 * session to work in, which is the service user's.
 *
 * An nt:folder cannot carry properties, so the settings of a channel are the
 * properties of the file .channel in its folder. That file is written when the
 * channel is managed (renamed, archived, ...), never when a message is posted.
 *
 * The participants of a channel are the principals granted jcr:read on its
 * folder, users and groups alike; there is no other membership list. A public
 * channel grants it to everyone.
 */
class ChatChannels {

	static final String SETTINGS = '.channel';
	static final String SETTINGS_TYPE = 'application/vnd.mintjams.webtop.chat.channel';

	static final String TITLE = 'chat:title';
	static final String DESCRIPTION = 'chat:description';
	static final String CHANNEL_KIND = 'chat:channelKind';
	static final String ADMINS = 'chat:admins';
	static final String ARCHIVED = 'chat:archived';

	static final String PUBLIC = 'public';
	static final String PRIVATE = 'private';
	static final String DIRECT = 'dm';

	static final int MAX_TITLE = 80;
	static final int MAX_DESCRIPTION = 500;

	static String path(String id) {
		return "${ChatStore.CHANNELS}/${ChatStore.checkId(id)}".toString();
	}

	/** The ids of every channel. */
	static List<String> ids(session) {
		List<String> ids = [];
		def root = session.getResource(ChatStore.CHANNELS);
		if (!root.exists()) {
			return ids;
		}
		def children = root.list();
		while (children.hasNext()) {
			def r = children.next();
			if (r.isCollection()) {
				ids.add(r.name as String);
			}
		}
		return ids;
	}

	/** The settings of the channel, or null when there is no such channel. */
	static Map read(session, String id) {
		def file = session.getResource("${path(id)}/${SETTINGS}".toString());
		if (!file.exists()) {
			return null;
		}
		return [
			id: id,
			path: path(id),
			title: file.hasProperty(TITLE) ? file.getProperty(TITLE).getString() : id,
			description: file.hasProperty(DESCRIPTION) ? file.getProperty(DESCRIPTION).getString() : '',
			kind: file.hasProperty(CHANNEL_KIND) ? file.getProperty(CHANNEL_KIND).getString() : PRIVATE,
			admins: file.hasProperty(ADMINS) ? (file.getProperty(ADMINS).getStringArray() as List<String>) : [],
			archived: file.hasProperty(ARCHIVED) && file.getProperty(ARCHIVED).getBoolean(),
		];
	}

	static String checkTitle(String title) {
		String text = (title ?: '').trim();
		if (!text) {
			throw new IllegalArgumentException('Enter a name for the channel.');
		}
		if (text.length() > MAX_TITLE) {
			throw new IllegalArgumentException("The name of a channel can be up to ${MAX_TITLE} characters.".toString());
		}
		return text;
	}

	static String checkDescription(String description) {
		String text = (description ?: '').trim();
		if (text.length() > MAX_DESCRIPTION) {
			throw new IllegalArgumentException("The description can be up to ${MAX_DESCRIPTION} characters.".toString());
		}
		return text;
	}

	/** Creates a channel whose first administrator, and first participant, is the creator. */
	static Map create(session, String creator, String title, String description, String kind) {
		if (kind != PUBLIC && kind != PRIVATE) {
			throw new IllegalArgumentException("Unknown kind of channel: ${kind}".toString());
		}
		String name = checkTitle(title);
		String text = checkDescription(description);
		String id = ChatStore.newId();

		def folder = session.getResource(path(id));
		folder.createFolder();
		def file = session.getResource("${path(id)}/${SETTINGS}".toString());
		file.createFile();
		file.setContentType(SETTINGS_TYPE);
		file.setProperty(TITLE, name);
		file.setProperty(DESCRIPTION, text);
		file.setProperty(CHANNEL_KIND, kind);
		file.setProperty(ADMINS, [creator] as String[]);
		file.setProperty(ARCHIVED, false);

		def acl = folder.getAccessControlList();
		if (kind == PUBLIC) {
			acl.addAccessControlEntry(ChatStore.builtin(session, ChatStore.EVERYONE), true, ChatStore.READ);
			// Allowing everyone would give anonymous back what the root denies it.
			acl.addAccessControlEntry(ChatStore.builtin(session, ChatStore.ANONYMOUS), false, ChatStore.READ);
		} else {
			acl.addAccessControlEntry(principalOf(session, creator), true, ChatStore.READ);
		}
		folder.setAccessControlList(acl);
		session.commit();
		return read(session, id);
	}

	/**
	 * The channel two users talk in by themselves: no title, no administrator,
	 * both granted. Its id follows from the two users (ChatStore.directMessageId),
	 * so there is one such channel per pair, whoever opens it first; when both
	 * do at once, the one that loses finds it there.
	 */
	static Map openDirect(session, String a, String b) {
		String id = ChatStore.directMessageId(a, b);
		Map existing = read(session, id);
		if (existing != null) {
			return existing;
		}
		try {
			def folder = session.getResource(path(id));
			folder.createFolder();
			def file = session.getResource("${path(id)}/${SETTINGS}".toString());
			file.createFile();
			file.setContentType(SETTINGS_TYPE);
			file.setProperty(TITLE, '');
			file.setProperty(DESCRIPTION, '');
			file.setProperty(CHANNEL_KIND, DIRECT);
			file.setProperty(ADMINS, [] as String[]);
			file.setProperty(ARCHIVED, false);
			def acl = folder.getAccessControlList();
			acl.addAccessControlEntry(principalOf(session, a), true, ChatStore.READ);
			acl.addAccessControlEntry(principalOf(session, b), true, ChatStore.READ);
			folder.setAccessControlList(acl);
			session.commit();
		} catch (Throwable ex) {
			session.rollback();
			Map raced = read(session, id);
			if (raced == null) {
				throw ex;
			}
		}
		return read(session, id);
	}

	static Map update(session, String id, Map fields) {
		def file = session.getResource("${path(id)}/${SETTINGS}".toString());
		if (fields.containsKey('title') && fields.title != null) {
			file.setProperty(TITLE, checkTitle(fields.title as String));
		}
		if (fields.containsKey('description') && fields.description != null) {
			file.setProperty(DESCRIPTION, checkDescription(fields.description as String));
		}
		if (fields.containsKey('admins') && fields.admins != null) {
			file.setProperty(ADMINS, (fields.admins as List).collect { it as String } as String[]);
		}
		if (fields.containsKey('archived') && fields.archived != null) {
			file.setProperty(ARCHIVED, !!fields.archived);
		}
		session.commit();
		return read(session, id);
	}

	private static java.security.Principal principalOf(session, String name) {
		def principal = ChatStore.findPrincipal(session, name);
		if (principal == null) {
			throw new IllegalArgumentException("No such user or group: ${name}".toString());
		}
		return principal;
	}

	/** The names of the principals granted jcr:read on the folder of the channel. */
	static List<String> memberIds(session, String id) {
		List<String> names = [];
		def acl = session.getResource(path(id)).getAccessControlList();
		for (def entry : acl) {
			if (!entry.isAllow() || !entry.privileges.any { ChatStore.isRead(it.name as String) }) {
				continue;
			}
			String name = entry.principal.name;
			if (!names.contains(name)) {
				names.add(name);
			}
		}
		return names;
	}

	/** Grants jcr:read to each principal that does not have it. Returns how many were added. */
	static int addMembers(session, String id, List<String> names) {
		List<String> members = memberIds(session, id);
		def folder = session.getResource(path(id));
		def acl = folder.getAccessControlList();
		int added = 0;
		names.unique(false).each { String name ->
			if (members.contains(name)) {
				return;
			}
			acl.addAccessControlEntry(principalOf(session, name), true, ChatStore.READ);
			added++;
		};
		if (added) {
			folder.setAccessControlList(acl);
			session.commit();
		}
		return added;
	}

	/** Takes the entries of the principals off the folder. Returns how many principals were removed. */
	static int removeMembers(session, String id, List<String> names) {
		def folder = session.getResource(path(id));
		def acl = folder.getAccessControlList();
		Set<String> removed = [] as Set;
		for (def entry : acl.getAccessControlEntries()) {
			String name = entry.principal.name;
			if (names.contains(name)) {
				acl.removeAccessControlEntry(entry);
				removed.add(name);
			}
		}
		if (removed) {
			folder.setAccessControlList(acl);
			session.commit();
		}
		return removed.size();
	}

}

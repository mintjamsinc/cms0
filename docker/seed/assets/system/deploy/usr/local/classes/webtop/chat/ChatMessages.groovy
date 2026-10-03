package webtop.chat;

/**
 * The messages of one conversation, under <root>/messages/yyyy/mm/dd/<id>.md.
 * Every method takes the session to work in, which is the service user's.
 *
 * A message is an nt:file holding its Markdown, so the full-text search covers
 * messages as it covers any file. Messages are listed by walking the day
 * folders, never through the search index, which lags behind on other nodes of
 * a cluster.
 *
 * What a message attaches is copied into the folder <id>.files next to it, in
 * the same day folder, so removing a day removes its messages with what they
 * attach. What a message links is kept by identifier only (chat:links): the
 * link gives nobody access, and whoever reads the message sees what their own
 * session can read.
 */
class ChatMessages {

	static final String TYPE = 'text/markdown';
	static final String SUFFIX = '.md';
	static final int MAX_PAGE = 200;
	static final int MAX_BODY = 20000;
	static final String FILES_SUFFIX = '.files';
	static final int MAX_ATTACHMENTS = 10;
	static final int MAX_LINKS = 10;
	static final int MAX_NAME = 120;

	static final String AUTHOR = 'chat:author';
	static final String POSTED_AT = 'chat:postedAt';
	static final String EDITED_AT = 'chat:editedAt';
	static final String DELETED = 'chat:deleted';
	static final String KIND = 'chat:kind';
	static final String LINKS = 'chat:links';
	static final String LINK_PATHS = 'chat:linkPaths';
	static final String MENTIONS = 'chat:mentions';

	static final String KIND_USER = 'user';

	/** The names of the folders, or of the files ending with the suffix, sorted. */
	private static List<String> childNames(folder, boolean collections, String suffix = null) {
		List<String> names = [];
		def children = folder.list();
		while (children.hasNext()) {
			def r = children.next();
			if (r.isCollection() != collections) {
				continue;
			}
			if (suffix != null && !r.name.endsWith(suffix)) {
				continue;
			}
			names.add(r.name as String);
		}
		names.sort();
		return names;
	}

	/**
	 * Visits the messages newest first, or oldest first, starting past the
	 * cursor (a message id, itself not visited). The closure gets the id and the
	 * file; returning false ends the walk.
	 */
	static void each(session, String root, boolean descending, String cursor, Closure visit) {
		def messages = session.getResource("${root}/messages".toString());
		if (!messages.exists()) {
			return;
		}
		String cursorDay = cursor ? ChatStore.dayOf(cursor) : null;
		Closure ordered = { List<String> names -> descending ? names.reverse() : names };
		// Whether a lies before b in the walk.
		Closure before = { String a, String b -> descending ? (a > b) : (a < b) };

		for (String y : ordered(childNames(messages, true))) {
			if (cursorDay && before(y, cursorDay.substring(0, 4))) {
				continue;
			}
			def year = messages.getResource(y);
			for (String m : ordered(childNames(year, true))) {
				if (cursorDay && before("${y}/${m}".toString(), cursorDay.substring(0, 7))) {
					continue;
				}
				def month = year.getResource(m);
				for (String d : ordered(childNames(month, true))) {
					if (cursorDay && before("${y}/${m}/${d}".toString(), cursorDay)) {
						continue;
					}
					def day = month.getResource(d);
					for (String name : ordered(childNames(day, false, SUFFIX))) {
						String id = name.substring(0, name.length() - SUFFIX.length());
						if (cursor && !before(cursor, id)) {
							continue;
						}
						if (visit.call(id, day.getResource(name)) == false) {
							return;
						}
					}
				}
			}
		}
	}

	/**
	 * A page of messages, oldest first: the latest ones, those before a message,
	 * those after a message, or those around a message (which is in the page).
	 * hasMore tells of older messages beyond the page, hasMoreAfter of newer.
	 */
	static Map page(session, String root, String before, String after, String around, int first) {
		int limit = Math.max(1, Math.min(first, MAX_PAGE));
		if (around) {
			def file = find(session, root, around);
			if (file == null) {
				throw new IllegalArgumentException('No such message.');
			}
			int half = Math.max(1, limit.intdiv(2) as int);
			Map older = walk(session, root, true, around, half);
			Map newer = walk(session, root, false, around, half);
			return [
				items: (older.items as List).reverse() + [toMessage(file)] + (newer.items as List),
				hasMore: older.hasMore,
				hasMoreAfter: newer.hasMore,
			];
		}
		if (after) {
			Map newer = walk(session, root, false, after, limit);
			return [items: newer.items, hasMore: false, hasMoreAfter: newer.hasMore];
		}
		Map older = walk(session, root, true, before, limit);
		return [items: (older.items as List).reverse(), hasMore: older.hasMore, hasMoreAfter: false];
	}

	/** Up to `limit` messages in walking order from the cursor, and whether there were more. */
	private static Map walk(session, String root, boolean descending, String cursor, int limit) {
		List<Map> items = [];
		boolean hasMore = false;
		each(session, root, descending, cursor) { String id, file ->
			if (items.size() >= limit) {
				hasMore = true;
				return false;
			}
			items.add(toMessage(file));
			return true;
		};
		return [items: items, hasMore: hasMore];
	}

	/** The user ids written as @name in the text, in order, each once. */
	static List<String> mentionsIn(String text) {
		List<String> names = [];
		def m = (text ?: '') =~ /(?<![\w@])@([A-Za-z0-9][A-Za-z0-9_.\-]{0,63})/;
		while (m.find()) {
			String name = m.group(1).replaceFirst(/[.\-]+$/, '');
			if (name && !names.contains(name)) {
				names.add(name);
			}
		}
		return names;
	}

	/** When the newest message was posted, or null for an empty conversation. */
	static Date lastMessageAt(session, String root) {
		Date last = null;
		each(session, root, true, null) { String id, file ->
			last = ChatStore.messageTime(id);
			return false;
		};
		return last;
	}

	static String path(String root, String id) {
		ChatStore.checkMessageId(id);
		return "${root}/messages/${ChatStore.dayOf(id)}/${id}${SUFFIX}".toString();
	}

	/** The file of the message, or null when there is none. */
	static Object find(session, String root, String id) {
		def file = session.getResource(path(root, id));
		return file.exists() ? file : null;
	}

	/** The text of a message, which may be empty only when something is attached or linked. */
	static String checkBody(String body, boolean required = true) {
		String text = (body ?: '').trim();
		if (!text && required) {
			throw new IllegalArgumentException('Enter a message.');
		}
		if (text.length() > MAX_BODY) {
			throw new IllegalArgumentException("A message can be up to ${MAX_BODY} characters.".toString());
		}
		return text;
	}

	/**
	 * Adds a message. Nothing but the new nodes is written.
	 *
	 * attachments: [name, resource] each, the resource being a file readable in
	 * the session of whoever posts; its content is copied next to the message.
	 * links: [id, path] each, the identifier of a file or folder and where it was
	 * when the message was posted.
	 */
	static Map post(session, String root, String author, String body, String kind = KIND_USER,
			List<Map> attachments = [], List<Map> links = [], List<String> mentions = []) {
		String text = checkBody(body, !attachments && !links);
		checkCounts(attachments.size(), links.size());
		Date now = new Date();
		String target = path(root, ChatStore.newMessageId(now));
		for (int attempt = 0; ; attempt++) {
			boolean newDay = false;
			try {
				def file = session.getResource(target);
				newDay = !file.getParent().exists();
				file.getParent().getOrCreateFolder();
				file.createFile();
				file.write(text);
				file.setContentType(TYPE);
				file.setContentEncoding('UTF-8');
				file.setProperty(AUTHOR, author);
				file.setProperty(POSTED_AT, now);
				file.setProperty(KIND, kind);
				setMentions(file, mentions);
				setLinks(file, links);
				addAttachments(file, attachments);
				session.commit();
				return toMessage(file);
			} catch (Throwable ex) {
				session.rollback();
				// The first posts of a day may create its folder at the same time.
				// The one that loses finds the folder there and posts into it.
				if (!newDay || attempt >= 2) {
					throw ex;
				}
			}
		}
	}

	/**
	 * Changes a message: its text, what is attached (added and removed by name)
	 * and, when given, the links it carries. An attachment is never replaced in
	 * place.
	 */
	static Map edit(session, file, String body, List<Map> added = [], List<String> removed = [],
			List<Map> links = null, List<String> mentions = null) {
		List<String> kept = attachmentNames(file).findAll { !removed.contains(it) };
		List<Map> newLinks = (links != null) ? links : linksOf(file);
		checkCounts(kept.size() + added.size(), newLinks.size());
		String text = checkBody(body, !kept && !added && !newLinks);

		file.write(text);
		file.setProperty(EDITED_AT, new Date());
		if (mentions != null) {
			setMentions(file, mentions);
		}
		def folder = filesFolder(file);
		removed.each { String name ->
			if (attachmentNames(file).contains(name)) {
				folder.getResource(name).remove();
			}
		};
		addAttachments(file, added);
		if (links != null) {
			setLinks(file, links);
		}
		session.commit();
		return toMessage(file);
	}

	/** Empties the message. The node stays, so the order of the others holds. */
	static Map delete(session, file) {
		file.write('');
		file.setProperty(DELETED, true);
		setLinks(file, []);
		def folder = filesFolder(file);
		if (folder.exists()) {
			folder.remove();
		}
		session.commit();
		return toMessage(file);
	}

	// --- attachments and links ----------------------------------------------------

	private static void checkCounts(int attachments, int links) {
		if (attachments > MAX_ATTACHMENTS) {
			throw new IllegalArgumentException("A message can carry up to ${MAX_ATTACHMENTS} attachments.".toString());
		}
		if (links > MAX_LINKS) {
			throw new IllegalArgumentException("A message can carry up to ${MAX_LINKS} links.".toString());
		}
	}

	/** The folder next to the message that holds its attachments; it may not exist. */
	static Object filesFolder(file) {
		String name = file.name;
		return file.getParent().getResource(name.substring(0, name.length() - SUFFIX.length()) + FILES_SUFFIX);
	}

	private static List<String> attachmentNames(file) {
		def folder = filesFolder(file);
		return folder.exists() ? childNames(folder, false) : [];
	}

	/** A name that can be a node's: no path separators and none of the characters JCR reserves. */
	static String cleanName(String name) {
		String clean = (name ?: '').replaceAll(/[\/\\:\[\]|*\p{Cntrl}]/, '_').trim();
		if (clean.length() > MAX_NAME) {
			int dot = clean.lastIndexOf('.');
			String extension = (dot > 0 && clean.length() - dot <= 16) ? clean.substring(dot) : '';
			clean = clean.substring(0, MAX_NAME - extension.length()) + extension;
		}
		return (clean && clean != '.' && clean != '..') ? clean : 'file';
	}

	/** Copies each source next to the message. Two of the same name both stay, the later one renamed. */
	private static void addAttachments(file, List<Map> attachments) {
		if (!attachments) {
			return;
		}
		def folder = filesFolder(file).getOrCreateFolder();
		List<String> taken = childNames(folder, false);
		attachments.each { Map attachment ->
			String name = cleanName(attachment.name as String);
			if (taken.contains(name)) {
				int dot = name.lastIndexOf('.');
				String stem = (dot > 0) ? name.substring(0, dot) : name;
				String extension = (dot > 0) ? name.substring(dot) : '';
				int n = 2;
				while (taken.contains("${stem} (${n})${extension}".toString())) {
					n++;
				}
				name = "${stem} (${n})${extension}".toString();
			}
			taken.add(name);
			def source = attachment.resource;
			def copy = folder.getResource(name);
			copy.createFile();
			copy.write(source.getContentAsStream());
			copy.setContentType(source.getContentType() ?: 'application/octet-stream');
		};
	}

	private static void setMentions(file, List<String> mentions) {
		if (!mentions) {
			if (file.hasProperty(MENTIONS)) {
				file.removeProperty(MENTIONS);
			}
			return;
		}
		file.setProperty(MENTIONS, mentions as String[]);
	}

	static List<String> mentionsOf(file) {
		return file.hasProperty(MENTIONS) ? (file.getProperty(MENTIONS).getStringArray() as List<String>) : [];
	}

	private static void setLinks(file, List<Map> links) {
		if (!links) {
			if (file.hasProperty(LINKS)) {
				file.removeProperty(LINKS);
			}
			if (file.hasProperty(LINK_PATHS)) {
				file.removeProperty(LINK_PATHS);
			}
			return;
		}
		file.setProperty(LINKS, links.collect { it.id as String } as String[]);
		file.setProperty(LINK_PATHS, links.collect { (it.path ?: '') as String } as String[]);
	}

	/** The links of the message as [id, path]: what is linked and where it was when it was linked. */
	static List<Map> linksOf(file) {
		if (!file.hasProperty(LINKS)) {
			return [];
		}
		List<String> ids = file.getProperty(LINKS).getStringArray() as List<String>;
		List<String> paths = file.hasProperty(LINK_PATHS) ? (file.getProperty(LINK_PATHS).getStringArray() as List<String>) : [];
		List<Map> links = [];
		ids.eachWithIndex { String id, int i ->
			links.add([id: id, path: (i < paths.size()) ? paths[i] : '']);
		};
		return links;
	}

	static String author(file) {
		return file.hasProperty(AUTHOR) ? file.getProperty(AUTHOR).getString() : null;
	}

	static boolean isDeleted(file) {
		return file.hasProperty(DELETED) && file.getProperty(DELETED).getBoolean();
	}

	static String kind(file) {
		return file.hasProperty(KIND) ? file.getProperty(KIND).getString() : KIND_USER;
	}

	/**
	 * The message as the API returns it, but for its links: `linkIds` names what
	 * is linked, and whoever reads the message resolves them in their own session.
	 */
	static Map toMessage(file) {
		String name = file.name;
		String id = name.substring(0, name.length() - SUFFIX.length());
		boolean deleted = isDeleted(file);
		List<Map> attachments = [];
		if (!deleted) {
			def folder = filesFolder(file);
			attachmentNames(file).each { String attachment ->
				def r = folder.getResource(attachment);
				attachments.add([
					name: attachment,
					path: r.path,
					mimeType: r.getContentType(),
					size: r.getContentLength(),
				]);
			};
		}
		return [
			id: id,
			author: author(file),
			postedAt: file.hasProperty(POSTED_AT) ? file.getProperty(POSTED_AT).getDate().time : ChatStore.messageTime(id),
			editedAt: file.hasProperty(EDITED_AT) ? file.getProperty(EDITED_AT).getDate().time : null,
			deleted: deleted,
			kind: kind(file),
			body: deleted ? '' : (file.getContent() ?: ''),
			attachments: attachments,
			linkIds: deleted ? [] : linksOf(file).collect { it.id as String },
			mentions: deleted ? [] : mentionsOf(file),
		];
	}

}

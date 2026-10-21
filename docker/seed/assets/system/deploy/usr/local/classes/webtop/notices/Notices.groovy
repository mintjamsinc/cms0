package webtop.notices;

import java.security.Principal;
import java.security.SecureRandom;

/**
 * The notices of the Webtop's notification center, kept per user.
 *
 *   /var/lib/webtop/notices/<user>/<id>    one file per notice, the notice as JSON
 *
 * A notice tells a user that something happened — a message for them, a task
 * assigned to them — and where a click goes (an app with launch options). It
 * is kept for the user it is for, so that the notification center shows the
 * same list on every desktop the user opens, and a notice read or dismissed
 * on one is read or dismissed on all. The user's folder is created with the
 * first notice and granted jcr:read to that user, who watches it with
 * nodeChanged; everything else in the area is closed (provisioning/notices.yml).
 *
 * Every write is made by the notice service user: a script publishes through
 * {@link #publish} with its own ScriptAPI, and the GraphQL resolvers
 * (etc/graphql/webtop/notices) act for the caller on the caller's own list.
 *
 * A notice carries texts, or i18n message references the Webtop resolves in
 * the reader's language (webtop-notifications.md); nothing here reads them.
 * The shape is checked, since a notice comes from any script: a title is
 * required, what is well-formed is kept and the rest dropped.
 *
 * At most MAX_KEPT notices are kept per user, the oldest going first; a new
 * notice with the key of an earlier one replaces it, read or not.
 */
class Notices {

	static final String SERVICE_USER = 'webtop-notice-service-user';
	static final String ROOT = '/var/lib/webtop/notices';
	static final String TYPE = 'application/vnd.mintjams.webtop.notice+json';
	static final String READ_PROPERTY = 'notice:read';
	static final String KEY_PROPERTY = 'notice:key';
	static final String APP_PROPERTY = 'notice:app';
	static final String READ_PRIVILEGE = 'jcr:read';

	static final int MAX_KEPT = 100;
	static final int MAX_TEXT = 500;
	static final int MAX_SHORT = 255;
	static final int MAX_JSON = 16 * 1024;
	static final int MAX_RECIPIENTS = 100;

	private static final SecureRandom RANDOM = new SecureRandom();

	// --- publishing ---------------------------------------------------------------

	/**
	 * Keeps the notice for each recipient, a user id each. Returns the ids of
	 * the records written, one per recipient the notice reached; a recipient
	 * that is no user is skipped. scriptAPI is the ScriptAPI binding of the
	 * caller (in a resolver, context.getAttribute('ScriptAPI')).
	 */
	static List<String> publish(scriptAPI, Map notice, Collection<String> recipients) {
		Map checked = check(notice);
		List<String> users = usersOf(recipients);
		if (!users) {
			return [];
		}
		if (users.size() > MAX_RECIPIENTS) {
			throw new IllegalArgumentException("Too many recipients: ${users.size()} (at most ${MAX_RECIPIENTS})".toString());
		}
		List<String> ids = [];
		withService(scriptAPI) { service ->
			def session = service.session;
			Throwable failed = null;
			for (String user : users) {
				try {
					String id = record(service, user, checked);
					if (id != null) {
						ids.add(id);
					}
				} catch (Throwable ex) {
					session.rollback();
					failed = failed ?: ex;
				}
			}
			if (failed != null) {
				throw failed;
			}
		};
		return ids;
	}

	/** Takes back the notices of the key from each recipient, e.g. when the task they are about is done. */
	static void withdraw(scriptAPI, String key, Collection<String> recipients) {
		String checkedKey = shortText(key);
		List<String> users = usersOf(recipients);
		if (!checkedKey || !users) {
			return;
		}
		withService(scriptAPI) { service ->
			def session = service.session;
			for (String user : users) {
				try {
					def root = session.getResource(userRoot(user));
					if (!root.exists()) {
						continue;
					}
					if (removeByKey(root, checkedKey) > 0) {
						session.commit();
					}
				} catch (Throwable ex) {
					session.rollback();
					throw ex;
				}
			}
		};
	}

	private static List<String> usersOf(Collection<String> recipients) {
		List<String> users = [];
		for (Object r : (recipients ?: [])) {
			String user = r as String;
			if (user && !users.contains(user)) {
				users.add(user);
			}
		}
		return users;
	}

	// --- the caller's own list (the resolvers) --------------------------------

	/** The caller's notices, newest first, up to MAX_KEPT. */
	static List<Map> list(context) {
		String user = callerOf(context);
		return withService(scriptAPI(context)) { service ->
			def session = service.session;
			def root = session.getResource(userRoot(user));
			if (!root.exists()) {
				return [];
			}
			List<Map> notices = [];
			for (String id : names(root).reverse()) {
				if (notices.size() >= MAX_KEPT) {
					break;
				}
				Map read = readRecord(service.getAttribute('JSON'), root.getResource(id));
				if (read != null) {
					notices.add(read);
				}
			}
			return notices;
		} as List<Map>;
	}

	/**
	 * The folder a client watches with nodeChanged to learn that the caller's
	 * list changed — on any desktop: a notice added, read, dismissed. Created
	 * and granted to the caller when it is not there yet.
	 */
	static String watchPath(context) {
		String user = callerOf(context);
		withService(scriptAPI(context)) { service ->
			def session = service.session;
			try {
				ensureRoot(session, user);
				session.commit();
			} catch (Throwable ex) {
				session.rollback();
				throw ex;
			}
		};
		return userRoot(user);
	}

	/** Marks the caller's notice read. Returns whether there was one. */
	static boolean markRead(context, String id) {
		return update(context) { root ->
			def file = root.getResource(checkId(id));
			if (!file.exists()) {
				return false;
			}
			if (!isRead(file)) {
				file.setProperty(READ_PROPERTY, true);
			}
			return true;
		};
	}

	/** Marks every notice of the caller read. Returns whether any was unread. */
	static boolean markAllRead(context) {
		return update(context) { root ->
			boolean changed = false;
			for (String id : names(root)) {
				def file = root.getResource(id);
				if (!isRead(file)) {
					file.setProperty(READ_PROPERTY, true);
					changed = true;
				}
			}
			return changed;
		};
	}

	/** Dismisses the caller's notice. Returns whether there was one. */
	static boolean remove(context, String id) {
		return update(context) { root ->
			def file = root.getResource(checkId(id));
			if (!file.exists()) {
				return false;
			}
			file.remove();
			return true;
		};
	}

	/** Dismisses every notice of the caller. Returns whether there was any. */
	static boolean clear(context) {
		return update(context) { root ->
			List<String> ids = names(root);
			for (String id : ids) {
				root.getResource(id).remove();
			}
			return !ids.isEmpty();
		};
	}

	private static boolean update(context, Closure change) {
		String user = callerOf(context);
		return withService(scriptAPI(context)) { service ->
			def session = service.session;
			try {
				def root = session.getResource(userRoot(user));
				if (!root.exists()) {
					return false;
				}
				boolean changed = change.call(root) as boolean;
				if (changed) {
					session.commit();
				}
				return changed;
			} catch (Throwable ex) {
				session.rollback();
				throw ex;
			}
		} as boolean;
	}

	// --- the records ----------------------------------------------------------

	/**
	 * Writes the notice for the user: replaces an earlier notice of the same
	 * key, and lets the oldest go beyond MAX_KEPT. Returns the id, or null
	 * when there is no such user. Two notices for one user may be written at
	 * the same time, so a commit that fails is tried again.
	 */
	private static String record(service, String user, Map notice) {
		def session = service.session;
		String content = service.getAttribute('JSON').stringify(notice) as String;
		if (content.getBytes('UTF-8').length > MAX_JSON) {
			throw new IllegalArgumentException("The notice is too large (at most ${MAX_JSON} bytes).".toString());
		}
		for (int attempt = 0; ; attempt++) {
			try {
				def root = ensureRoot(session, user);
				if (root == null) {
					return null;
				}
				String key = notice.key as String;
				if (key) {
					removeByKey(root, key);
				}
				String id = newId();
				def file = root.getResource(id);
				file.createFile();
				file.write(content);
				file.setContentType(TYPE);
				file.setContentEncoding('UTF-8');
				file.setProperty(READ_PROPERTY, false);
				if (key) {
					file.setProperty(KEY_PROPERTY, key);
				}
				if (notice.app) {
					file.setProperty(APP_PROPERTY, notice.app as String);
				}
				List<String> ids = names(root);
				int over = ids.size() - MAX_KEPT;
				for (int i = 0; i < over; i++) {
					if (ids[i] != id) {
						root.getResource(ids[i]).remove();
					}
				}
				session.commit();
				return id;
			} catch (Throwable ex) {
				session.rollback();
				if (attempt >= 2) {
					throw ex;
				}
			}
		}
	}

	/** Removes the records of the key under the root; returns how many. */
	private static int removeByKey(root, String key) {
		int removed = 0;
		for (String id : names(root)) {
			def file = root.getResource(id);
			if (file.hasProperty(KEY_PROPERTY) && file.getProperty(KEY_PROPERTY).getString() == key) {
				file.remove();
				removed++;
			}
		}
		return removed;
	}

	/** The record as the resolvers answer it, or null for a file that is no notice. */
	private static Map readRecord(json, file) {
		Map notice;
		try {
			notice = json.parse(file.getContent() as String) as Map;
		} catch (Throwable ignore) {
			return null;
		}
		if (!(notice?.title)) {
			return null;
		}
		String id = file.name as String;
		return [
			id: id,
			title: notice.title,
			body: notice.body,
			app: notice.app,
			icon: notice.icon,
			options: notice.options,
			context: notice.context,
			key: notice.key,
			read: isRead(file),
			at: timeOf(id),
		];
	}

	private static boolean isRead(file) {
		return file.hasProperty(READ_PROPERTY) && file.getProperty(READ_PROPERTY).getBoolean();
	}

	/**
	 * The user's folder, created and granted to the user the first time; null
	 * when there is no such user, for whom nothing is kept.
	 */
	private static Object ensureRoot(session, String user) {
		def root = session.getResource(userRoot(user));
		if (root.exists()) {
			return root;
		}
		Principal principal = findPrincipal(session, user);
		if (principal == null) {
			return null;
		}
		root.createFolder();
		def acl = root.getAccessControlList();
		acl.addAccessControlEntry(principal, true, READ_PRIVILEGE);
		root.setAccessControlList(acl);
		return root;
	}

	/** The names of the records under the root, oldest first (an id sorts by the time it was written). */
	private static List<String> names(root) {
		List<String> l = [];
		def children = root.list();
		while (children.hasNext()) {
			def r = children.next();
			if (!r.isCollection()) {
				l.add(r.name as String);
			}
		}
		return l.sort();
	}

	// --- the shape of a notice ------------------------------------------------

	/**
	 * The notice as it is kept: a title is required; the rest is taken when
	 * well-formed and dropped otherwise, as the Webtop does with a notice it
	 * receives (webtop/src/webtop/lib/notifications.ts).
	 */
	static Map check(Map notice) {
		if (!(notice instanceof Map)) {
			throw new IllegalArgumentException('A notice is a map.');
		}
		Object title = text(notice.title);
		if (title == null) {
			throw new IllegalArgumentException('A notice needs a title.');
		}
		Map checked = [title: title];
		Object body = text(notice.body);
		if (body != null) {
			checked.body = body;
		}
		String app = shortText(notice.app);
		if (app && app ==~ /[A-Za-z0-9_.\-]+/) {
			checked.app = app;
		}
		String icon = shortText(notice.icon);
		if (icon && icon ==~ /bi-[a-z0-9\-]+/) {
			checked.icon = icon;
		}
		if (notice.options instanceof Map) {
			checked.options = notice.options;
		}
		String context = shortText(notice.context);
		if (context) {
			checked.context = context;
		}
		String key = shortText(notice.key);
		if (key) {
			checked.key = key;
		}
		return checked;
	}

	/** A text of a notice: a string, or a message reference { id, params, fallback }; null for anything else. */
	private static Object text(Object value) {
		if (value instanceof CharSequence) {
			String s = value.toString();
			return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
		}
		if (value instanceof Map && value.id instanceof CharSequence && value.id) {
			Map ref = [id: value.id.toString()];
			if (value.params instanceof Map) {
				ref.params = value.params;
			}
			if (value.fallback instanceof CharSequence) {
				ref.fallback = value.fallback.toString();
			}
			return ref;
		}
		return null;
	}

	private static String shortText(Object value) {
		if (!(value instanceof CharSequence)) {
			return null;
		}
		String s = value.toString();
		return (s && s.length() <= MAX_SHORT) ? s : null;
	}

	// --- where and who ----------------------------------------------------------

	/** A user id as the name of a node: anything but what a JCR name cannot hold. */
	static String checkUserId(String user) {
		if (!user || user.length() > 255 || user == '.' || user == '..' || user =~ /[\/:\[\]|*\p{Cntrl}]/) {
			throw new IllegalArgumentException("Invalid user id: ${user}".toString());
		}
		return user;
	}

	static String userRoot(String user) {
		return "${ROOT}/${checkUserId(user)}".toString();
	}

	static String checkId(String id) {
		if (!(id ==~ /[0-9a-f]{20}/)) {
			throw new IllegalArgumentException("Invalid notice id: ${id}".toString());
		}
		return id;
	}

	/** The id of a notice written now: the time, then random digits, so ids sort in the order they were written. */
	static String newId() {
		StringBuilder buf = new StringBuilder(String.format('%012x', System.currentTimeMillis()));
		while (buf.length() < 20) {
			buf.append(Integer.toHexString(RANDOM.nextInt(16)));
		}
		return buf.toString();
	}

	static Date timeOf(String id) {
		return new Date(Long.parseLong(id.substring(0, 12), 16));
	}

	private static String callerOf(context) {
		def session = context.session;
		if (session.isAnonymous()) {
			throw new IllegalStateException('Sign in to read your notifications.');
		}
		return session.userID as String;
	}

	private static Object scriptAPI(context) {
		return context.getAttribute('ScriptAPI');
	}

	/**
	 * Runs the closure with the script context of the notice service user: its
	 * session, and the bindings (JSON, which groovy.json cannot replace here).
	 */
	static Object withService(scriptAPI, Closure closure) {
		def service = scriptAPI.createServiceUserContext(SERVICE_USER);
		try {
			return closure.call(service);
		} finally {
			service.close();
		}
	}

	private static Principal findPrincipal(session, String name) {
		try {
			return session.principalProvider.getPrincipal(name);
		} catch (Throwable ignore) {
			return null;
		}
	}

}

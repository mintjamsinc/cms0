package webtop.reversi;

import java.security.Principal;
import java.security.SecureRandom;

/**
 * Where the games live and how they are written.
 *
 *   /var/lib/games/reversi/rooms/<room>/.room        the settings and the state of the room
 *   /var/lib/games/reversi/rooms/<room>/moves/NNN    one file per move, named by its ply
 *
 * The area is closed to everyone (provisioning/reversi.yml). The two players
 * are granted jcr:read on the folder of their room, and every write is made
 * by the games service user after the caller's rights were checked in the
 * caller's own session, so a game cannot be changed from the Content Browser.
 *
 * A move only adds a file, named by its ply: two moves for the same ply
 * cannot both be written, which is what keeps a double click or a stale
 * client from playing twice. The settings file is written when the room
 * changes state (accepted, finished, ...), never for a move.
 */
class ReversiStore {

	static final String SERVICE_USER = 'games-service-user';
	static final String ROOT = '/var/lib/games/reversi';
	static final String ROOMS = ROOT + '/rooms';

	static final String SETTINGS = '.room';
	static final String SETTINGS_TYPE = 'application/vnd.mintjams.webtop.reversi.room';
	static final String MOVES = 'moves';
	static final String MOVE_TYPE = 'text/plain';

	static final String BLACK = 'reversi:black';
	static final String WHITE = 'reversi:white';
	static final String HOST = 'reversi:host';
	static final String STATUS = 'reversi:status';
	static final String SIZE = 'reversi:size';
	static final String CREATED_AT = 'reversi:createdAt';
	static final String STARTED_AT = 'reversi:startedAt';
	static final String FINISHED_AT = 'reversi:finishedAt';
	static final String WINNER = 'reversi:winner';
	static final String RESIGNED_BY = 'reversi:resignedBy';
	static final String PLAYER = 'reversi:player';
	static final String PLAYED_AT = 'reversi:playedAt';

	static final String WAITING = 'waiting';
	static final String PLAYING = 'playing';
	static final String FINISHED = 'finished';
	static final String DECLINED = 'declined';
	static final String CANCELLED = 'cancelled';

	static final String READ = 'jcr:read';

	/** The topic the messages of a room go out on, under this prefix. */
	static final String TOPIC_PREFIX = 'game/reversi/rooms';

	private static final SecureRandom RANDOM = new SecureRandom();

	static String checkId(String id) {
		if (!(id ==~ /[a-f0-9]{16}/)) {
			throw new IllegalArgumentException("Invalid room id: ${id}".toString());
		}
		return id;
	}

	static String newId() {
		StringBuilder buf = new StringBuilder();
		while (buf.length() < 16) {
			buf.append(Integer.toHexString(RANDOM.nextInt(16)));
		}
		return buf.toString();
	}

	static String path(String id) {
		return "${ROOMS}/${checkId(id)}".toString();
	}

	static String topic(String id) {
		return "${TOPIC_PREFIX}/${checkId(id)}".toString();
	}

	/** Runs the closure with a session of the games service user. */
	static Object withService(context, Closure closure) {
		def service = context.getAttribute('ScriptAPI').createServiceUserContext(SERVICE_USER);
		try {
			return closure.call(service);
		} finally {
			service.close();
		}
	}

	/** The ids of every room. */
	static List<String> ids(session) {
		List<String> ids = [];
		def root = session.getResource(ROOMS);
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

	/** The room, with its moves, or null when there is no such room. */
	static Map read(session, String id) {
		String path = path(id);
		def file = session.getResource("${path}/${SETTINGS}".toString());
		if (!file.exists()) {
			return null;
		}
		Closure text = { String name -> file.hasProperty(name) ? file.getProperty(name).getString() : null; };
		Closure date = { String name -> file.hasProperty(name) ? file.getProperty(name).getDate().time : null; };
		return [
			id: id,
			path: path,
			size: file.hasProperty(SIZE) ? file.getProperty(SIZE).getInt() : 8,
			status: text(STATUS) ?: WAITING,
			black: text(BLACK),
			white: text(WHITE),
			host: text(HOST),
			createdAt: date(CREATED_AT),
			startedAt: date(STARTED_AT),
			finishedAt: date(FINISHED_AT),
			winner: text(WINNER),
			resignedBy: text(RESIGNED_BY),
			moves: moves(session, path),
		];
	}

	/** The moves of the room in the order they were played. */
	static List<String> moves(session, String roomPath) {
		List<String> names = [];
		def folder = session.getResource("${roomPath}/${MOVES}".toString());
		if (!folder.exists()) {
			return [];
		}
		def children = folder.list();
		while (children.hasNext()) {
			def r = children.next();
			if (!r.isCollection()) {
				names.add(r.name as String);
			}
		}
		names.sort();
		return names.collect { String name -> (folder.getResource(name).getContent() ?: '').trim(); };
	}

	/** Creates a room for the two players and grants them its folder. */
	static Map create(session, String host, String black, String white, int size) {
		String id = newId();
		def folder = session.getResource(path(id));
		folder.createFolder();
		def file = session.getResource("${path(id)}/${SETTINGS}".toString());
		file.createFile();
		file.setContentType(SETTINGS_TYPE);
		file.setProperty(BLACK, black);
		file.setProperty(WHITE, white);
		file.setProperty(HOST, host);
		file.setProperty(STATUS, WAITING);
		file.setProperty(SIZE, size as long);
		file.setProperty(CREATED_AT, new Date());

		def acl = folder.getAccessControlList();
		[black, white].unique().each { String userId ->
			acl.addAccessControlEntry(principalOf(session, userId), true, READ);
		};
		folder.setAccessControlList(acl);
		session.commit();
		return read(session, id);
	}

	/** Writes the given settings of the room: strings, dates, or null to remove. */
	static Map update(session, String id, Map changes) {
		def file = session.getResource("${path(id)}/${SETTINGS}".toString());
		if (!file.exists()) {
			throw new IllegalArgumentException('No such game.');
		}
		changes.each { String name, Object value ->
			if (value == null) {
				if (file.hasProperty(name)) {
					file.removeProperty(name);
				}
			} else if (value instanceof Date) {
				file.setProperty(name, value as Date);
			} else {
				file.setProperty(name, value as String);
			}
		};
		session.commit();
		return read(session, id);
	}

	/**
	 * Adds the move of the given ply. Two moves cannot be written for the
	 * same ply: the second finds the file there, or fails to commit.
	 */
	static void addMove(session, String roomPath, int ply, String coord, int player) {
		def folder = session.getResource("${roomPath}/${MOVES}".toString());
		folder.getOrCreateFolder();
		def file = folder.getResource(String.format('%03d', ply));
		if (file.exists()) {
			throw new IllegalStateException('That move has already been played.');
		}
		try {
			file.createFile();
			file.write(coord);
			file.setContentType(MOVE_TYPE);
			file.setContentEncoding('UTF-8');
			file.setProperty(PLAYER, player as long);
			file.setProperty(PLAYED_AT, new Date());
			session.commit();
		} catch (Throwable ex) {
			session.rollback();
			throw ex;
		}
	}

	/** Removes the rooms that ended before the given time. Returns how many. */
	static int purge(session, Date before) {
		int removed = 0;
		for (String id : ids(session)) {
			Map room = read(session, id);
			if (room == null || room.status in [WAITING, PLAYING]) {
				continue;
			}
			Date ended = (room.finishedAt ?: room.createdAt) as Date;
			if (ended != null && ended.before(before)) {
				session.getResource(room.path as String).remove();
				removed++;
			}
		}
		if (removed) {
			session.commit();
		}
		return removed;
	}

	/** The principal of that name, or null when there is no such user or group. */
	static Principal findPrincipal(session, String name) {
		try {
			return session.principalProvider.getPrincipal(name);
		} catch (Throwable ignore) {
			return null;
		}
	}

	private static Principal principalOf(session, String name) {
		Principal principal = findPrincipal(session, name);
		if (principal == null) {
			throw new IllegalArgumentException("No such user: ${name}".toString());
		}
		return principal;
	}

	/** Whether there is a user of that id (a user, not a group). */
	static boolean userExists(session, String userId) {
		try {
			return session.userManager.getUser(userId) != null;
		} catch (Throwable ignore) {
			return false;
		}
	}

	static String displayName(session, String userId) {
		try {
			return session.userManager.getUser(userId)?.displayName ?: userId;
		} catch (Throwable ignore) {
			return userId;
		}
	}

	/**
	 * Tells the players of the room what happened, as a topic message they
	 * alone receive (Subscription.topicMessage on the room's topic). The
	 * message carries the room id and what changed; a client that cannot
	 * follow it reads the room again.
	 */
	static void publish(context, Map room, Map message) {
		List<String> players = [room.black, room.white].findAll { it }.unique() as List<String>;
		Map body = [roomId: room.id] + message;
		context.getAttribute('EventAdminAPI').publish(topic(room.id as String), body, [recipients: players]);
	}

}

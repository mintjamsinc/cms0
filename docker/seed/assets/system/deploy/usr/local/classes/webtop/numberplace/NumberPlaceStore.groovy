package webtop.numberplace;

import java.security.Principal;
import java.security.SecureRandom;

/**
 * Where the games live and how they are written.
 *
 *   /var/lib/games/numberplace/rooms/<room>/.room              the settings and the state of the room
 *   /var/lib/games/numberplace/boards/<room>/<board>/NN        one file per filled cell, named by the cell
 *   /var/lib/games/numberplace/solutions/<room>                the solution of the room's puzzle
 *
 * The area is closed to everyone (provisioning/numberplace.yml). The two
 * players are granted jcr:read on the folder of their room, whose settings
 * file carries the room's chat; the boards and the solution are granted to
 * nobody, so in a race neither player can read the other's numbers, and a
 * number is only known to be right once the server said so. Every write is
 * made by the games service user after the caller's rights were checked in
 * the caller's own session.
 *
 * A board is `shared` (cooperation, territory) or the board of one player
 * (`host` or `guest`, in a race). A filled cell only adds a file, named by
 * the cell: two numbers cannot both be written in one cell, which is what
 * settles who was first when both players fill the same cell. The settings
 * file is written when the room changes state (accepted, a colour picked, a
 * wrong number, finished, ...), never for a right number; it keeps the time
 * of its last change, and each cell the time it was filled, which together
 * tell how long a room has been idle.
 */
class NumberPlaceStore {

	static final String SERVICE_USER = 'games-service-user';
	static final String ROOT = '/var/lib/games/numberplace';
	static final String ROOMS = ROOT + '/rooms';
	static final String BOARDS = ROOT + '/boards';
	static final String SOLUTIONS = ROOT + '/solutions';

	static final String SETTINGS = '.room';
	static final String SETTINGS_TYPE = 'application/vnd.mintjams.webtop.numberplace.room';
	static final String TEXT_TYPE = 'text/plain';

	static final String HOST = 'numberplace:host';
	static final String GUEST = 'numberplace:guest';
	static final String MODE = 'numberplace:mode';
	static final String LEVEL = 'numberplace:level';
	static final String STATUS = 'numberplace:status';
	static final String HOST_COLOR = 'numberplace:hostColor';
	static final String GUEST_COLOR = 'numberplace:guestColor';
	static final String THEME = 'numberplace:theme';
	static final String GUEST_READY = 'numberplace:guestReady';
	static final String GIVENS = 'numberplace:givens';
	static final String CREATED_AT = 'numberplace:createdAt';
	static final String UPDATED_AT = 'numberplace:updatedAt';
	static final String STARTED_AT = 'numberplace:startedAt';
	static final String FINISHED_AT = 'numberplace:finishedAt';
	static final String WINNER = 'numberplace:winner';
	static final String RESIGNED_BY = 'numberplace:resignedBy';
	static final String HOST_MISSES = 'numberplace:hostMisses';
	static final String GUEST_MISSES = 'numberplace:guestMisses';
	static final String HOST_LOCKED_UNTIL = 'numberplace:hostLockedUntil';
	static final String GUEST_LOCKED_UNTIL = 'numberplace:guestLockedUntil';
	static final String PLAYER = 'numberplace:player';
	static final String PLAYED_AT = 'numberplace:playedAt';

	static final String WAITING = 'waiting';
	static final String LOBBY = 'lobby';
	static final String PLAYING = 'playing';
	static final String FINISHED = 'finished';
	static final String DECLINED = 'declined';
	/** The invited user left while getting ready. */
	static final String LEFT = 'left';
	static final String CANCELLED = 'cancelled';

	static final String COOP = 'coop';
	static final String RACE = 'race';
	static final String TERRITORY = 'territory';

	/** The board both players fill (cooperation, territory). */
	static final String SHARED = 'shared';
	static final String HOST_ROLE = 'host';
	static final String GUEST_ROLE = 'guest';

	static final String READ = 'jcr:read';

	/** The topic the messages of a room go out on, under this prefix. */
	static final String TOPIC_PREFIX = 'game/numberplace/rooms';

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

	/** The room, without its boards, or null when there is no such room. */
	static Map read(session, String id) {
		String path = path(id);
		def file = session.getResource("${path}/${SETTINGS}".toString());
		if (!file.exists()) {
			return null;
		}
		Closure text = { String name -> file.hasProperty(name) ? file.getProperty(name).getString() : null; };
		Closure date = { String name -> file.hasProperty(name) ? file.getProperty(name).getDate().time : null; };
		Closure flag = { String name -> file.hasProperty(name) && file.getProperty(name).getBoolean(); };
		Closure number = { String name -> file.hasProperty(name) ? file.getProperty(name).getLong() : 0L; };
		return [
			id: id,
			path: path,
			host: text(HOST),
			guest: text(GUEST),
			mode: text(MODE) ?: COOP,
			level: file.hasProperty(LEVEL) ? (file.getProperty(LEVEL).getLong() as int) : 2,
			status: text(STATUS) ?: WAITING,
			hostColor: text(HOST_COLOR),
			guestColor: text(GUEST_COLOR),
			theme: text(THEME),
			guestReady: flag(GUEST_READY),
			givens: text(GIVENS),
			createdAt: date(CREATED_AT),
			updatedAt: date(UPDATED_AT),
			startedAt: date(STARTED_AT),
			finishedAt: date(FINISHED_AT),
			winner: text(WINNER),
			resignedBy: text(RESIGNED_BY),
			hostMisses: number(HOST_MISSES),
			guestMisses: number(GUEST_MISSES),
			hostLockedUntil: date(HOST_LOCKED_UNTIL),
			guestLockedUntil: date(GUEST_LOCKED_UNTIL),
			// The settings file's identifier: the room's chat is its Chat conversation.
			fileId: file.getIdentifier(),
		];
	}

	/** Creates a room for the two players and grants them its folder. */
	static Map create(session, String host, String guest, String mode, int level, String hostColor, String guestColor, String theme) {
		String id = newId();
		def folder = session.getResource(path(id));
		folder.createFolder();
		def file = session.getResource("${path(id)}/${SETTINGS}".toString());
		file.createFile();
		file.setContentType(SETTINGS_TYPE);
		file.setProperty(HOST, host);
		file.setProperty(GUEST, guest);
		file.setProperty(MODE, mode);
		file.setProperty(LEVEL, level as long);
		file.setProperty(STATUS, WAITING);
		file.setProperty(HOST_COLOR, hostColor);
		file.setProperty(GUEST_COLOR, guestColor);
		file.setProperty(THEME, theme);
		file.setProperty(GUEST_READY, false);
		Date now = new Date();
		file.setProperty(CREATED_AT, now);
		file.setProperty(UPDATED_AT, now);

		def acl = folder.getAccessControlList();
		[host, guest].unique().each { String userId ->
			acl.addAccessControlEntry(principalOf(session, userId), true, READ);
		};
		folder.setAccessControlList(acl);
		session.commit();
		return read(session, id);
	}

	/** Writes the given settings of the room: strings, numbers, dates, booleans, or null to remove. */
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
			} else if (value instanceof Boolean) {
				file.setProperty(name, value as boolean);
			} else if (value instanceof Number) {
				file.setProperty(name, value as long);
			} else {
				file.setProperty(name, value as String);
			}
		};
		file.setProperty(UPDATED_AT, new Date());
		session.commit();
		return read(session, id);
	}

	/** Returns the folder at the path, created with its parents when missing. */
	private static Object folderAt(session, String path) {
		def folder = session.getResource(path);
		if (!folder.exists()) {
			int slash = path.lastIndexOf('/');
			if (slash > 0) {
				folderAt(session, path.substring(0, slash));
			}
			folder.getOrCreateFolder();
		}
		return folder;
	}

	/** Keeps the solution of the room's puzzle where the players cannot read it. */
	static void writeSolution(session, String id, String solution) {
		folderAt(session, SOLUTIONS);
		def file = session.getResource("${SOLUTIONS}/${checkId(id)}".toString());
		if (!file.exists()) {
			file.createFile();
		}
		file.write(solution);
		file.setContentType(TEXT_TYPE);
		file.setContentEncoding('UTF-8');
		session.commit();
	}

	/** The solution of the room's puzzle, or null before the game started. */
	static String readSolution(session, String id) {
		def file = session.getResource("${SOLUTIONS}/${checkId(id)}".toString());
		return file.exists() ? (file.getContent() ?: '').trim() : null;
	}

	/** The filled cells of one board of the room: [cell, digit, by, playedAt], by cell. */
	static List<Map> board(session, String id, String name) {
		List<Map> cells = [];
		def folder = session.getResource("${BOARDS}/${checkId(id)}/${name}".toString());
		if (!folder.exists()) {
			return cells;
		}
		def children = folder.list();
		while (children.hasNext()) {
			def r = children.next();
			if (r.isCollection() || !((r.name as String) ==~ /[0-9]{2}/)) {
				continue;
			}
			String digit = (r.getContent() ?: '').trim();
			if (!(digit ==~ /[1-9]/)) {
				continue;
			}
			cells.add([
				cell: (r.name as String) as int,
				digit: digit as int,
				by: r.hasProperty(PLAYER) ? r.getProperty(PLAYER).getString() : null,
				playedAt: r.hasProperty(PLAYED_AT) ? r.getProperty(PLAYED_AT).getDate().time : null,
			]);
		}
		cells.sort { Map c -> c.cell as int };
		return cells;
	}

	/**
	 * Fills a cell of a board for a player. Two numbers cannot be written in
	 * one cell: the second finds the file there, or fails to commit.
	 */
	static void addCell(session, String id, String name, int cell, int digit, String player) {
		def folder = folderAt(session, "${BOARDS}/${checkId(id)}/${name}".toString());
		def file = folder.getResource(String.format('%02d', cell));
		if (file.exists()) {
			throw new IllegalStateException('That cell is already filled.');
		}
		try {
			file.createFile();
			file.write(String.valueOf(digit));
			file.setContentType(TEXT_TYPE);
			file.setContentEncoding('UTF-8');
			file.setProperty(PLAYER, player);
			file.setProperty(PLAYED_AT, new Date());
			session.commit();
		} catch (Throwable ex) {
			session.rollback();
			if (session.getResource("${BOARDS}/${checkId(id)}/${name}/${String.format('%02d', cell)}".toString()).exists()) {
				throw new IllegalStateException('That cell is already filled.');
			}
			throw ex;
		}
	}

	/**
	 * Removes the rooms that ended before `endedBefore`, and the rooms still
	 * waiting, getting ready or playing in which nothing happened since
	 * `idleBefore`, with their boards and solutions. Returns the rooms removed.
	 */
	static List<Map> purge(session, Date endedBefore, Date idleBefore) {
		List<Map> removed = [];
		for (String id : ids(session)) {
			Map room = read(session, id);
			if (room == null) {
				continue;
			}
			boolean open = room.status in [WAITING, LOBBY, PLAYING];
			Date since = open ? lastActivity(session, room) : ((room.finishedAt ?: room.createdAt) as Date);
			if (since != null && since.before(open ? idleBefore : endedBefore)) {
				for (String p : [room.path as String, "${BOARDS}/${id}".toString(), "${SOLUTIONS}/${id}".toString()]) {
					def r = session.getResource(p);
					if (r.exists()) {
						r.remove();
					}
				}
				removed.add(room);
			}
		}
		if (removed) {
			session.commit();
		}
		return removed;
	}

	/** When something last happened in the room: a change of its settings or a filled cell. */
	static Date lastActivity(session, Map room) {
		List<Date> times = [room.createdAt, room.updatedAt, room.startedAt] as List<Date>;
		for (String name : [SHARED, HOST_ROLE, GUEST_ROLE]) {
			for (Map c : board(session, room.id as String, name)) {
				times.add(c.playedAt as Date);
			}
		}
		times = times.findAll { it != null };
		return times ? times.max() : null;
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
		List<String> players = [room.host, room.guest].findAll { it }.unique() as List<String>;
		Map body = [roomId: room.id] + message;
		context.getAttribute('EventAdminAPI').publish(topic(room.id as String), body, [recipients: players]);
	}

}

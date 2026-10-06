package webtop.reversi;

/**
 * What the GraphQL resolvers of the Reversi app do.
 *
 * A game is a room of two users. One invites the other (the room waits), the
 * other accepts (the room is a lobby) or declines. In the lobby each player
 * picks a disc, the host also picks the board, the guest says it is ready
 * and the host starts the game (the room is playing); the players then move
 * in turn until the game is over or one resigns. Every operation first
 * finds, in the caller's own session, who the caller is, then reads and
 * writes the room as the games service user (ReversiStore): the rules are
 * applied here, on the server, and a client is only told what happened.
 *
 * Each change is announced to the two players as a topic message on the
 * room's topic (game/reversi/rooms/<room>): invited, accepted, changed,
 * ready, started, declined, cancelled, move, resigned, and expired when an
 * idle room is removed. The room itself is the record; a client that misses
 * a message reads the room again.
 */
class ReversiApi {

	/** How many rooms of a user may be waiting, getting ready or playing at once. */
	static final int MAX_OPEN_ROOMS = 10;
	/** How many ended rooms a user is shown. */
	static final int ENDED_SHOWN = 10;
	/** Ended rooms are removed after this many days. */
	static final int KEEP_ENDED_DAYS = 7;
	/** Rooms waiting, getting ready or playing are removed after this many days without a change or a move. */
	static final int KEEP_IDLE_DAYS = 30;

	private static final long DAY_MS = 24L * 3600L * 1000L;

	/** The discs and the board the players get when they have not chosen. */
	static final String DEFAULT_BLACK_FACE = 'black';
	static final String DEFAULT_WHITE_FACE = 'white';
	static final String DEFAULT_THEME = 'ichigo';

	def context;
	def session;
	String userId;

	protected ReversiApi(context) {
		this.context = context;
		this.session = context.session;
		if (session.isAnonymous()) {
			throw new IllegalStateException('Sign in to play.');
		}
		this.userId = session.userID;
	}

	static ReversiApi create(context) {
		return new ReversiApi(context);
	}

	private Object asService(Closure closure) {
		return ReversiStore.withService(context) { service -> closure.call(service.session); };
	}

	// --- reading ------------------------------------------------------------------

	/**
	 * The caller's rooms: those waiting, getting ready or playing, then the
	 * ended ones, the newest first.
	 */
	List<Map> rooms() {
		return asService { s ->
			List<Map> open = [];
			List<Map> ended = [];
			for (String id : ReversiStore.ids(s)) {
				Map room = ReversiStore.read(s, id);
				if (room == null || !isPlayer(room)) {
					continue;
				}
				(isOpen(room) ? open : ended).add(room);
			}
			Closure newest = { Map a, Map b -> ((b.createdAt as Date) ?: new Date(0)) <=> ((a.createdAt as Date) ?: new Date(0)); };
			open.sort(newest);
			ended.sort(newest);
			return (open + ended.take(ENDED_SHOWN)).collect { Map room -> toRoom(s, room); };
		} as List<Map>;
	}

	Map room(String id) {
		return asService { s ->
			return toRoom(s, mine(s, id));
		} as Map;
	}

	// --- starting -----------------------------------------------------------------

	/**
	 * Invites a user to a game. The caller plays the given side (black moves
	 * first), or either when `random`, with the given disc on the given
	 * board; the other player gets a disc that differs.
	 */
	Map invite(String opponentId, String side, Object size, String face, String theme) {
		String opponent = (opponentId ?: '').trim();
		if (!opponent) {
			throw new IllegalArgumentException('Choose an opponent.');
		}
		if (opponent == userId) {
			throw new IllegalArgumentException('Invite somebody else.');
		}
		int boardSize = ReversiRules.checkSize(size);
		String mySide = side ?: 'random';
		if (!(mySide in ['black', 'white', 'random'])) {
			throw new IllegalArgumentException("Unknown side: ${side}".toString());
		}
		if (mySide == 'random') {
			mySide = (new Random().nextBoolean()) ? 'black' : 'white';
		}
		String myFace = checkLook(face, 'disc') ?: ((mySide == 'black') ? DEFAULT_BLACK_FACE : DEFAULT_WHITE_FACE);
		String theirFace = otherFace(myFace);
		String board = checkLook(theme, 'board') ?: DEFAULT_THEME;
		return asService { s ->
			if (!ReversiStore.userExists(s, opponent)) {
				throw new IllegalArgumentException('No such user.');
			}
			purge(s);
			int open = 0;
			for (String id : ReversiStore.ids(s)) {
				Map room = ReversiStore.read(s, id);
				if (room != null && isPlayer(room) && isOpen(room)) {
					open++;
				}
			}
			if (open >= MAX_OPEN_ROOMS) {
				throw new IllegalStateException("You already have ${MAX_OPEN_ROOMS} games open.".toString());
			}
			boolean black = (mySide == 'black');
			Map room = ReversiStore.create(s, userId,
				black ? userId : opponent, black ? opponent : userId, boardSize,
				black ? myFace : theirFace, black ? theirFace : myFace, board);
			ReversiStore.publish(context, room, [type: 'invited', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	/**
	 * The invited user accepts: the room is a lobby, where the two get
	 * ready. The caller gets the given disc unless it is the host's.
	 */
	Map accept(String id, String face) {
		String wanted = checkLook(face, 'disc');
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != ReversiStore.WAITING) {
				throw new IllegalStateException('The invitation is no longer open.');
			}
			if (room.host == userId) {
				throw new IllegalStateException('Wait for the other player to accept.');
			}
			Map changes = [(ReversiStore.STATUS): ReversiStore.LOBBY, (ReversiStore.GUEST_READY): false];
			if (wanted && wanted != faceOf(room, room.host as String)) {
				changes[faceKey(room, userId)] = wanted;
			}
			room = ReversiStore.update(s, id, changes);
			ReversiStore.publish(context, room, [type: 'accepted', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	/** The invited user declines or leaves the lobby, or the host takes the invitation back. */
	Map decline(String id) {
		return asService { s ->
			Map room = mine(s, id);
			if (!(room.status in [ReversiStore.WAITING, ReversiStore.LOBBY])) {
				throw new IllegalStateException('The invitation is no longer open.');
			}
			String status = (room.host == userId) ? ReversiStore.CANCELLED : ReversiStore.DECLINED;
			room = ReversiStore.update(s, id, [(ReversiStore.STATUS): status, (ReversiStore.FINISHED_AT): new Date()]);
			ReversiStore.publish(context, room, [type: status, by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	// --- getting ready ------------------------------------------------------------

	/**
	 * Changes the caller's disc and, for the host, the board. The host may
	 * do so while the room waits for an answer; both may in the lobby. The
	 * other player's disc cannot be taken.
	 */
	Map setup(String id, String face, String theme) {
		String wantedFace = checkLook(face, 'disc');
		String wantedTheme = checkLook(theme, 'board');
		return asService { s ->
			Map room = mine(s, id);
			boolean host = (room.host == userId);
			if (!(room.status == ReversiStore.LOBBY || (room.status == ReversiStore.WAITING && host))) {
				throw new IllegalStateException('The game is not being set up.');
			}
			Map changes = [:];
			if (wantedFace) {
				if (wantedFace == faceOf(room, otherOf(room, userId))) {
					throw new IllegalStateException('That disc is taken.');
				}
				changes[faceKey(room, userId)] = wantedFace;
			}
			if (wantedTheme) {
				if (!host) {
					throw new IllegalStateException('The host chooses the board.');
				}
				changes[ReversiStore.THEME] = wantedTheme;
			}
			if (changes) {
				room = ReversiStore.update(s, id, changes);
			}
			ReversiStore.publish(context, room, [type: 'changed', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	/** The invited user says it is ready (or, with `false`, no longer is). */
	Map ready(String id, Object ready) {
		boolean flag = (ready == null) ? true : (ready as boolean);
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != ReversiStore.LOBBY) {
				throw new IllegalStateException('The game is not being set up.');
			}
			if (room.host == userId) {
				throw new IllegalStateException('The host starts the game.');
			}
			room = ReversiStore.update(s, id, [(ReversiStore.GUEST_READY): flag]);
			ReversiStore.publish(context, room, [type: 'ready', by: userId, ready: flag]);
			return toRoom(s, room);
		} as Map;
	}

	/** The host starts the game once the invited user is ready. */
	Map start(String id) {
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != ReversiStore.LOBBY) {
				throw new IllegalStateException('The game is not being set up.');
			}
			if (room.host != userId) {
				throw new IllegalStateException('Wait for the host to start the game.');
			}
			if (!room.guestReady) {
				throw new IllegalStateException('Wait for the other player to get ready.');
			}
			room = ReversiStore.update(s, id, [(ReversiStore.STATUS): ReversiStore.PLAYING, (ReversiStore.STARTED_AT): new Date()]);
			ReversiStore.publish(context, room, [type: 'started', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	// --- playing ------------------------------------------------------------------

	/**
	 * Plays a move for the caller. `ply` is the number of moves the caller
	 * has seen: a stale client, or a double click, names a ply already
	 * played and is refused rather than played twice.
	 */
	Map play(String id, Object ply, String move) {
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != ReversiStore.PLAYING) {
				throw new IllegalStateException('The game is not being played.');
			}
			int size = room.size as int;
			List<String> moves = room.moves as List<String>;
			Map position = ReversiRules.replay(size, moves);
			int expected = (ply == null) ? -1 : (ply as int);
			if (expected != position.ply) {
				throw new IllegalStateException('The game has moved on. Reading it again.');
			}
			if (position.over) {
				throw new IllegalStateException('The game is over.');
			}
			int player = position.turn as int;
			if (sideOf(room, userId) != player) {
				throw new IllegalStateException('It is not your turn.');
			}
			int index = ReversiRules.fromCoord(move, size);
			String coord = ReversiRules.toCoord(index, size);
			Map next = ReversiRules.apply(position, index);
			ReversiStore.addMove(s, room.path as String, position.ply as int, coord, player);
			Map message = [type: 'move', by: userId, ply: position.ply, move: coord, over: next.over];
			if (next.over) {
				String winner = ReversiRules.winner(next.cells as List<Integer>);
				room = ReversiStore.update(s, id, [
					(ReversiStore.STATUS): ReversiStore.FINISHED,
					(ReversiStore.WINNER): winner,
					(ReversiStore.FINISHED_AT): new Date(),
				]);
				message.winner = winner;
			} else {
				room = ReversiStore.read(s, id);
			}
			ReversiStore.publish(context, room, message);
			return toRoom(s, room);
		} as Map;
	}

	/** The caller gives up: the other player wins. */
	Map resign(String id) {
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != ReversiStore.PLAYING) {
				throw new IllegalStateException('The game is not being played.');
			}
			String winner = (sideOf(room, userId) == ReversiRules.BLACK) ? 'white' : 'black';
			room = ReversiStore.update(s, id, [
				(ReversiStore.STATUS): ReversiStore.FINISHED,
				(ReversiStore.WINNER): winner,
				(ReversiStore.RESIGNED_BY): userId,
				(ReversiStore.FINISHED_AT): new Date(),
			]);
			ReversiStore.publish(context, room, [type: 'resigned', by: userId, winner: winner]);
			return toRoom(s, room);
		} as Map;
	}

	// --- helpers ------------------------------------------------------------------

	/** Removes the ended rooms and the idle ones; the players of an idle room are told it is gone. */
	private void purge(s) {
		long now = System.currentTimeMillis();
		List<Map> removed = ReversiStore.purge(s, new Date(now - KEEP_ENDED_DAYS * DAY_MS), new Date(now - KEEP_IDLE_DAYS * DAY_MS));
		removed.findAll { Map room -> isOpen(room) }.each { Map room ->
			ReversiStore.publish(context, room, [type: 'expired', by: ReversiStore.SERVICE_USER]);
		};
	}

	/** The room, which must be one the caller plays in. */
	private Map mine(s, String id) {
		Map room = ReversiStore.read(s, ReversiStore.checkId(id));
		if (room == null || !isPlayer(room)) {
			throw new IllegalArgumentException('No such game.');
		}
		return room;
	}

	private boolean isPlayer(Map room) {
		return room.black == userId || room.white == userId;
	}

	private static boolean isOpen(Map room) {
		return room.status in [ReversiStore.WAITING, ReversiStore.LOBBY, ReversiStore.PLAYING];
	}

	private static int sideOf(Map room, String user) {
		return (room.black == user) ? ReversiRules.BLACK : ReversiRules.WHITE;
	}

	private static String otherOf(Map room, String user) {
		return (room.black == user) ? (room.white as String) : (room.black as String);
	}

	/** The property that holds the disc of the given player. */
	private static String faceKey(Map room, String user) {
		return (room.black == user) ? ReversiStore.BLACK_FACE : ReversiStore.WHITE_FACE;
	}

	private static String faceOf(Map room, String user) {
		String face = (room.black == user) ? (room.blackFace as String) : (room.whiteFace as String);
		return face ?: ((room.black == user) ? DEFAULT_BLACK_FACE : DEFAULT_WHITE_FACE);
	}

	/** A default disc that differs from the given one. */
	private static String otherFace(String face) {
		return (face == DEFAULT_WHITE_FACE) ? DEFAULT_BLACK_FACE : DEFAULT_WHITE_FACE;
	}

	/** A disc or board id as the app names them; null when none was given. */
	private static String checkLook(String value, String what) {
		String id = (value ?: '').trim();
		if (!id) {
			return null;
		}
		if (!(id ==~ /[a-z0-9_-]{1,32}/)) {
			throw new IllegalArgumentException("Unknown ${what}: ${value}".toString());
		}
		return id;
	}

	private Map toRoom(s, Map room) {
		return [
			id: room.id,
			size: room.size,
			status: room.status,
			black: [id: room.black, displayName: ReversiStore.displayName(s, room.black as String)],
			white: [id: room.white, displayName: ReversiStore.displayName(s, room.white as String)],
			host: room.host,
			yourSide: (room.black == userId) ? 'black' : 'white',
			blackFace: faceOf(room, room.black as String),
			whiteFace: faceOf(room, room.white as String),
			theme: room.theme ?: DEFAULT_THEME,
			guestReady: room.guestReady ? true : false,
			moves: room.moves,
			winner: room.winner,
			resignedBy: room.resignedBy,
			createdAt: room.createdAt,
			startedAt: room.startedAt,
			finishedAt: room.finishedAt,
			topic: ReversiStore.topic(room.id as String),
		];
	}

}

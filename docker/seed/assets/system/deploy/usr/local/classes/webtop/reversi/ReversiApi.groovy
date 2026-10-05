package webtop.reversi;

/**
 * What the GraphQL resolvers of the Reversi app do.
 *
 * A game is a room of two users. One invites the other (the room waits), the
 * other accepts (the room is playing) or declines; the players then move in
 * turn until the game is over or one resigns. Every operation first finds,
 * in the caller's own session, who the caller is, then reads and writes the
 * room as the games service user (ReversiStore): the rules are applied here,
 * on the server, and a client is only told what happened.
 *
 * Each change is announced to the two players as a topic message on the
 * room's topic (game/reversi/rooms/<room>): invited, started, declined,
 * cancelled, move, resigned. The room itself is the record; a client that
 * misses a message reads the room again.
 */
class ReversiApi {

	/** How many rooms of a user may be waiting or playing at once. */
	static final int MAX_OPEN_ROOMS = 10;
	/** How many ended rooms a user is shown. */
	static final int ENDED_SHOWN = 10;
	/** Ended rooms are removed after this many days. */
	static final int KEEP_ENDED_DAYS = 7;

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
	 * The caller's rooms: those waiting or playing, then the ended ones, the
	 * newest first.
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
	 * first), or either when `random`.
	 */
	Map invite(String opponentId, String side, Object size) {
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
		return asService { s ->
			if (!ReversiStore.userExists(s, opponent)) {
				throw new IllegalArgumentException('No such user.');
			}
			ReversiStore.purge(s, new Date(System.currentTimeMillis() - KEEP_ENDED_DAYS * 24L * 3600L * 1000L));
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
			String black = (mySide == 'black') ? userId : opponent;
			String white = (mySide == 'black') ? opponent : userId;
			Map room = ReversiStore.create(s, userId, black, white, boardSize);
			ReversiStore.publish(context, room, [type: 'invited', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	/** The invited user accepts: the game begins. */
	Map accept(String id) {
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != ReversiStore.WAITING) {
				throw new IllegalStateException('The invitation is no longer open.');
			}
			if (room.host == userId) {
				throw new IllegalStateException('Wait for the other player to accept.');
			}
			room = ReversiStore.update(s, id, [(ReversiStore.STATUS): ReversiStore.PLAYING, (ReversiStore.STARTED_AT): new Date()]);
			ReversiStore.publish(context, room, [type: 'started', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	/** The invited user declines, or the host takes the invitation back. */
	Map decline(String id) {
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != ReversiStore.WAITING) {
				throw new IllegalStateException('The invitation is no longer open.');
			}
			String status = (room.host == userId) ? ReversiStore.CANCELLED : ReversiStore.DECLINED;
			room = ReversiStore.update(s, id, [(ReversiStore.STATUS): status, (ReversiStore.FINISHED_AT): new Date()]);
			ReversiStore.publish(context, room, [type: status, by: userId]);
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
		return room.status in [ReversiStore.WAITING, ReversiStore.PLAYING];
	}

	private static int sideOf(Map room, String user) {
		return (room.black == user) ? ReversiRules.BLACK : ReversiRules.WHITE;
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

package webtop.numberplace;

/**
 * What the GraphQL resolvers of the Number Place app do.
 *
 * A game is a room of two users, who either solve one board together
 * (`coop`) or play against each other: a race to fill the same puzzle on
 * boards of their own (`race`, time attack), or one board both fill, each
 * cell going to whoever filled it right (`territory`). One user invites the
 * other (the room waits), the other accepts (the room is a lobby) or
 * declines. In the lobby each player picks a colour, the host also picks
 * the difficulty and the board, the guest says it is ready and the host
 * starts the game with a puzzle its app made: the puzzle is checked here
 * and its solution kept (the room is playing). The players then write
 * numbers until the board is full or one gives up.
 *
 * Every number is checked against the solution. A right one fills the
 * cell; a wrong one fills nothing, counts as a miss and makes the player
 * wait LOCK_MS before writing again. Every operation first finds, in the
 * caller's own session, who the caller is, then reads and writes the room
 * as the games service user (NumberPlaceStore).
 *
 * Each change is announced to the two players as a topic message on the
 * room's topic (game/numberplace/rooms/<room>): invited, accepted, changed,
 * ready, started, declined, left, cancelled, fill, miss, resigned, and
 * expired when an idle room is removed. In a race a fill says how far the
 * player got, not which number went where. The room itself is the record;
 * a client that misses a message reads the room again.
 *
 * An invitation is also told in the chat: a card in the direct messages of
 * the two users (webtop.chat.ChatSystem), from the host, whose button opens
 * the game. The card's design is the app's own (INVITATION_CARD).
 */
class NumberPlaceApi {

	/** How many rooms of a user may be waiting, getting ready or playing at once. */
	static final int MAX_OPEN_ROOMS = 10;
	/** How many ended rooms a user is shown. */
	static final int ENDED_SHOWN = 10;
	/** Ended rooms are removed after this many days. */
	static final int KEEP_ENDED_DAYS = 7;
	/** Rooms waiting, getting ready or playing are removed after this many days without a change or a number. */
	static final int KEEP_IDLE_DAYS = 30;
	/** How long a player waits after a wrong number (the app's LOCK_MS too). */
	static final long LOCK_MS = 3000L;

	private static final long DAY_MS = 24L * 3600L * 1000L;

	/** The design of the invitation card: in the app's folder, deployed with the Webtop. */
	static final String INVITATION_CARD = '/usr/share/webtop/apps/numberplace/assets/cards/invitation';

	/** The colours and the board the players get when they have not chosen. */
	static final String DEFAULT_HOST_COLOR = 'strawberry';
	static final String DEFAULT_GUEST_COLOR = 'mint';
	static final String DEFAULT_THEME = 'ichigo';
	static final int DEFAULT_LEVEL = 2;

	static final List<String> MODES = [NumberPlaceStore.COOP, NumberPlaceStore.RACE, NumberPlaceStore.TERRITORY];

	def context;
	def session;
	String userId;

	protected NumberPlaceApi(context) {
		this.context = context;
		this.session = context.session;
		if (session.isAnonymous()) {
			throw new IllegalStateException('Sign in to play.');
		}
		this.userId = session.userID;
	}

	static NumberPlaceApi create(context) {
		return new NumberPlaceApi(context);
	}

	private Object asService(Closure closure) {
		return NumberPlaceStore.withService(context) { service -> closure.call(service.session); };
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
			for (String id : NumberPlaceStore.ids(s)) {
				Map room = NumberPlaceStore.read(s, id);
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
	 * Invites a user to a game: `coop`, `race` or `territory`, of the given
	 * difficulty (1 to 4), the caller in the given colour on the given
	 * board; the other player gets a colour that differs. `locale` is the
	 * language the invitation card's text is written in.
	 */
	Map invite(String opponentId, String mode, Object level, String color, String theme, String locale = null) {
		String opponent = (opponentId ?: '').trim();
		if (!opponent) {
			throw new IllegalArgumentException('Choose someone to invite.');
		}
		if (opponent == userId) {
			throw new IllegalArgumentException('Invite somebody else.');
		}
		String kind = (mode ?: '').trim();
		if (!(kind in MODES)) {
			throw new IllegalArgumentException("Unknown mode: ${mode}".toString());
		}
		int difficulty = checkLevel(level) ?: DEFAULT_LEVEL;
		String myColor = checkLook(color, 'colour') ?: DEFAULT_HOST_COLOR;
		String theirColor = otherColor(myColor);
		String board = checkLook(theme, 'board') ?: DEFAULT_THEME;
		return asService { s ->
			if (!NumberPlaceStore.userExists(s, opponent)) {
				throw new IllegalArgumentException('No such user.');
			}
			purge(s);
			int open = 0;
			for (String id : NumberPlaceStore.ids(s)) {
				Map room = NumberPlaceStore.read(s, id);
				if (room != null && isPlayer(room) && isOpen(room)) {
					open++;
				}
			}
			if (open >= MAX_OPEN_ROOMS) {
				throw new IllegalStateException("You already have ${MAX_OPEN_ROOMS} games open.".toString());
			}
			Map room = NumberPlaceStore.create(s, userId, opponent, kind, difficulty, myColor, theirColor, board);
			NumberPlaceStore.publish(context, room, [type: 'invited', by: userId]);
			inviteInChat(s, room, locale);
			return toRoom(s, room);
		} as Map;
	}

	/**
	 * Tells the invited user in the chat: a card in the direct messages of
	 * the two, from the host, whose button opens the game. The invitation
	 * stands without it, so a chat that cannot take it is only logged.
	 */
	private void inviteInChat(s, Map room, String locale) {
		try {
			String guest = room.guest as String;
			webtop.chat.ChatStore.withService(context) { chat ->
				webtop.chat.ChatChannels.openDirect(chat, userId, guest);
			};
			String channelId = webtop.chat.ChatStore.directMessageId(userId, guest);
			webtop.chat.ChatSystem.post(context.getAttribute('ScriptAPI'), [channelId: channelId], null, [
				card: [
					path: INVITATION_CARD,
					fields: [
						roomId: room.id,
						host: userId,
						hostName: NumberPlaceStore.displayName(s, userId) ?: userId,
						guest: guest,
						guestName: NumberPlaceStore.displayName(s, guest) ?: guest,
						mode: room.mode,
						level: (room.level as Long),
					],
					locale: locale,
				],
				author: userId,
				kind: webtop.chat.ChatMessages.KIND_USER,
			]);
		} catch (Throwable ex) {
			context.getAttribute('log')?.warn("The invitation to ${room.id} could not be posted to the chat: ${ex.message}".toString());
		}
	}

	/**
	 * The invited user accepts: the room is a lobby, where the two get
	 * ready. The caller gets the given colour unless it is the host's.
	 */
	Map accept(String id, String color) {
		String wanted = checkLook(color, 'colour');
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != NumberPlaceStore.WAITING) {
				throw new IllegalStateException('The invitation is no longer open.');
			}
			if (room.host == userId) {
				throw new IllegalStateException('Wait for the other player to accept.');
			}
			Map changes = [(NumberPlaceStore.STATUS): NumberPlaceStore.LOBBY, (NumberPlaceStore.GUEST_READY): false];
			if (wanted && wanted != colorOf(room, NumberPlaceStore.HOST_ROLE)) {
				changes[NumberPlaceStore.GUEST_COLOR] = wanted;
			}
			room = NumberPlaceStore.update(s, id, changes);
			NumberPlaceStore.publish(context, room, [type: 'accepted', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	/**
	 * The invited user declines (the room is `declined`) or leaves while
	 * getting ready (`left`), or the host takes the invitation back
	 * (`cancelled`).
	 */
	Map decline(String id) {
		return asService { s ->
			Map room = mine(s, id);
			if (!(room.status in [NumberPlaceStore.WAITING, NumberPlaceStore.LOBBY])) {
				throw new IllegalStateException('The invitation is no longer open.');
			}
			String status = (room.host == userId) ? NumberPlaceStore.CANCELLED :
				(room.status == NumberPlaceStore.LOBBY) ? NumberPlaceStore.LEFT : NumberPlaceStore.DECLINED;
			room = NumberPlaceStore.update(s, id, [(NumberPlaceStore.STATUS): status, (NumberPlaceStore.FINISHED_AT): new Date()]);
			NumberPlaceStore.publish(context, room, [type: status, by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	// --- getting ready ------------------------------------------------------------

	/**
	 * Changes the caller's colour and, for the host, the difficulty and the
	 * board. The host may do so while the room waits for an answer; both may
	 * in the lobby. The other player's colour cannot be taken.
	 */
	Map setup(String id, String color, Object level, String theme) {
		String wantedColor = checkLook(color, 'colour');
		Integer wantedLevel = checkLevel(level);
		String wantedTheme = checkLook(theme, 'board');
		return asService { s ->
			Map room = mine(s, id);
			boolean host = (room.host == userId);
			if (!(room.status == NumberPlaceStore.LOBBY || (room.status == NumberPlaceStore.WAITING && host))) {
				throw new IllegalStateException('The game is not being set up.');
			}
			String role = roleOf(room);
			Map changes = [:];
			if (wantedColor) {
				if (wantedColor == colorOf(room, otherRole(role))) {
					throw new IllegalStateException('That colour is taken.');
				}
				changes[colorKey(role)] = wantedColor;
			}
			if (wantedLevel || wantedTheme) {
				if (!host) {
					throw new IllegalStateException('The host chooses the puzzle and the board.');
				}
				if (wantedLevel) {
					changes[NumberPlaceStore.LEVEL] = wantedLevel as long;
				}
				if (wantedTheme) {
					changes[NumberPlaceStore.THEME] = wantedTheme;
				}
			}
			if (changes) {
				room = NumberPlaceStore.update(s, id, changes);
			}
			NumberPlaceStore.publish(context, room, [type: 'changed', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	/** The invited user says it is ready (or, with `false`, no longer is). */
	Map ready(String id, Object ready) {
		boolean flag = (ready == null) ? true : (ready as boolean);
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != NumberPlaceStore.LOBBY) {
				throw new IllegalStateException('The game is not being set up.');
			}
			if (room.host == userId) {
				throw new IllegalStateException('The host starts the game.');
			}
			room = NumberPlaceStore.update(s, id, [(NumberPlaceStore.GUEST_READY): flag]);
			NumberPlaceStore.publish(context, room, [type: 'ready', by: userId, ready: flag]);
			return toRoom(s, room);
		} as Map;
	}

	/**
	 * The host starts the game once the invited user is ready, with the
	 * puzzle its app made: 81 digits, 0 for the cells to fill. The puzzle
	 * must have exactly one solution, which is kept to check the numbers.
	 */
	Map start(String id, String givens) {
		String puzzle = (givens ?: '').trim();
		String solution = NumberPlaceRules.solution(puzzle);
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != NumberPlaceStore.LOBBY) {
				throw new IllegalStateException('The game is not being set up.');
			}
			if (room.host != userId) {
				throw new IllegalStateException('Wait for the host to start the game.');
			}
			if (!room.guestReady) {
				throw new IllegalStateException('Wait for the other player to get ready.');
			}
			NumberPlaceStore.writeSolution(s, id, solution);
			room = NumberPlaceStore.update(s, id, [
				(NumberPlaceStore.GIVENS): puzzle,
				(NumberPlaceStore.STATUS): NumberPlaceStore.PLAYING,
				(NumberPlaceStore.STARTED_AT): new Date(),
				(NumberPlaceStore.HOST_MISSES): 0L,
				(NumberPlaceStore.GUEST_MISSES): 0L,
				(NumberPlaceStore.HOST_LOCKED_UNTIL): null,
				(NumberPlaceStore.GUEST_LOCKED_UNTIL): null,
			]);
			NumberPlaceStore.publish(context, room, [type: 'started', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	// --- playing ------------------------------------------------------------------

	/**
	 * Writes a number for the caller: `cell` 0 to 80 (row by row), `digit`
	 * 1 to 9. A right number fills the cell (refused when it is filled
	 * already: on the shared board the other player was first); a wrong one
	 * makes the caller wait LOCK_MS. Returns whether it was right, and the
	 * room.
	 */
	Map play(String id, Object cell, Object digit) {
		int at = (cell == null) ? -1 : (cell as int);
		int number = (digit == null) ? 0 : (digit as int);
		if (at < 0 || at >= NumberPlaceRules.CELLS) {
			throw new IllegalArgumentException('No such cell.');
		}
		if (number < 1 || number > 9) {
			throw new IllegalArgumentException('Write a number from 1 to 9.');
		}
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != NumberPlaceStore.PLAYING) {
				throw new IllegalStateException('The game is not being played.');
			}
			int[] givens = NumberPlaceRules.parse(room.givens as String);
			if (givens[at] != 0) {
				throw new IllegalArgumentException('That number is given.');
			}
			String role = roleOf(room);
			long now = System.currentTimeMillis();
			Date lockedUntil = room[role + 'LockedUntil'] as Date;
			if (lockedUntil != null && lockedUntil.time > now) {
				throw new IllegalStateException('Wait a moment after a wrong number.');
			}
			String solution = NumberPlaceStore.readSolution(s, id);
			if (!solution || solution.length() != NumberPlaceRules.CELLS) {
				throw new IllegalStateException('The game has no puzzle.');
			}

			if (Character.getNumericValue(solution.charAt(at)) != number) {
				long misses = ((room[role + 'Misses'] ?: 0L) as long) + 1L;
				room = NumberPlaceStore.update(s, id, [
					(missesKey(role)): misses,
					(lockKey(role)): new Date(now + LOCK_MS),
				]);
				NumberPlaceStore.publish(context, room, [type: 'miss', by: userId, misses: misses, lockMs: LOCK_MS]);
				return [correct: false, room: toRoom(s, room)];
			}

			boolean race = (room.mode == NumberPlaceStore.RACE);
			String board = race ? role : NumberPlaceStore.SHARED;
			NumberPlaceStore.addCell(s, id, board, at, number, userId);
			List<Map> cells = NumberPlaceStore.board(s, id, board);
			int filled = cells.count { Map c -> c.by == userId } as int;
			boolean full = NumberPlaceRules.givens(givens) + cells.size() >= NumberPlaceRules.CELLS;
			Map message = [type: 'fill', by: userId, board: board, filled: filled, over: full];
			if (!race) {
				message.cell = at;
				message.digit = number;
			}
			if (full) {
				String winner = null;
				if (race) {
					winner = role;
				} else if (room.mode == NumberPlaceStore.TERRITORY) {
					int theirs = cells.size() - filled;
					winner = (filled == theirs) ? 'draw' : (filled > theirs) ? role : otherRole(role);
				}
				// Both may fill their last cell at once: the first to finish is kept.
				Map latest = NumberPlaceStore.read(s, id);
				if (latest.status == NumberPlaceStore.PLAYING) {
					room = NumberPlaceStore.update(s, id, [
						(NumberPlaceStore.STATUS): NumberPlaceStore.FINISHED,
						(NumberPlaceStore.WINNER): winner,
						(NumberPlaceStore.FINISHED_AT): new Date(),
					]);
				} else {
					room = latest;
					winner = latest.winner as String;
				}
				message.winner = winner;
			} else {
				room = NumberPlaceStore.read(s, id);
			}
			NumberPlaceStore.publish(context, room, message);
			return [correct: true, room: toRoom(s, room)];
		} as Map;
	}

	/**
	 * The caller gives up: against each other the other player wins; in
	 * cooperation the game ends unsolved.
	 */
	Map resign(String id) {
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != NumberPlaceStore.PLAYING) {
				throw new IllegalStateException('The game is not being played.');
			}
			String winner = (room.mode == NumberPlaceStore.COOP) ? null : otherRole(roleOf(room));
			room = NumberPlaceStore.update(s, id, [
				(NumberPlaceStore.STATUS): NumberPlaceStore.FINISHED,
				(NumberPlaceStore.WINNER): winner,
				(NumberPlaceStore.RESIGNED_BY): userId,
				(NumberPlaceStore.FINISHED_AT): new Date(),
			]);
			NumberPlaceStore.publish(context, room, [type: 'resigned', by: userId, winner: winner]);
			return toRoom(s, room);
		} as Map;
	}

	// --- helpers ------------------------------------------------------------------

	/** Removes the ended rooms and the idle ones; the players of an idle room are told it is gone. */
	private void purge(s) {
		long now = System.currentTimeMillis();
		List<Map> removed = NumberPlaceStore.purge(s, new Date(now - KEEP_ENDED_DAYS * DAY_MS), new Date(now - KEEP_IDLE_DAYS * DAY_MS));
		removed.findAll { Map room -> isOpen(room) }.each { Map room ->
			NumberPlaceStore.publish(context, room, [type: 'expired', by: NumberPlaceStore.SERVICE_USER]);
		};
	}

	/** The room, which must be one the caller plays in. */
	private Map mine(s, String id) {
		Map room = NumberPlaceStore.read(s, NumberPlaceStore.checkId(id));
		if (room == null || !isPlayer(room)) {
			throw new IllegalArgumentException('No such game.');
		}
		return room;
	}

	private boolean isPlayer(Map room) {
		return room.host == userId || room.guest == userId;
	}

	private static boolean isOpen(Map room) {
		return room.status in [NumberPlaceStore.WAITING, NumberPlaceStore.LOBBY, NumberPlaceStore.PLAYING];
	}

	/** host or guest: the caller's role in the room. */
	private String roleOf(Map room) {
		return (room.host == userId) ? NumberPlaceStore.HOST_ROLE : NumberPlaceStore.GUEST_ROLE;
	}

	private static String otherRole(String role) {
		return (role == NumberPlaceStore.HOST_ROLE) ? NumberPlaceStore.GUEST_ROLE : NumberPlaceStore.HOST_ROLE;
	}

	private static String colorKey(String role) {
		return (role == NumberPlaceStore.HOST_ROLE) ? NumberPlaceStore.HOST_COLOR : NumberPlaceStore.GUEST_COLOR;
	}

	private static String missesKey(String role) {
		return (role == NumberPlaceStore.HOST_ROLE) ? NumberPlaceStore.HOST_MISSES : NumberPlaceStore.GUEST_MISSES;
	}

	private static String lockKey(String role) {
		return (role == NumberPlaceStore.HOST_ROLE) ? NumberPlaceStore.HOST_LOCKED_UNTIL : NumberPlaceStore.GUEST_LOCKED_UNTIL;
	}

	private static String colorOf(Map room, String role) {
		String color = room[role + 'Color'] as String;
		return color ?: ((role == NumberPlaceStore.HOST_ROLE) ? DEFAULT_HOST_COLOR : DEFAULT_GUEST_COLOR);
	}

	/** A default colour that differs from the given one. */
	private static String otherColor(String color) {
		return (color == DEFAULT_GUEST_COLOR) ? DEFAULT_HOST_COLOR : DEFAULT_GUEST_COLOR;
	}

	/** A colour or board id as the app names them; null when none was given. */
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

	/** A difficulty from 1 to 4; null when none was given. */
	private static Integer checkLevel(Object value) {
		if (value == null) {
			return null;
		}
		int level = value as int;
		if (level < 1 || level > 4) {
			throw new IllegalArgumentException("Unknown difficulty: ${value}".toString());
		}
		return level;
	}

	private Map toRoom(s, Map room) {
		String role = roleOf(room);
		boolean race = (room.mode == NumberPlaceStore.RACE);
		Map<String, List<Map>> boards = [:];
		for (String name : (race ? [NumberPlaceStore.HOST_ROLE, NumberPlaceStore.GUEST_ROLE] : [NumberPlaceStore.SHARED])) {
			boards[name] = NumberPlaceStore.board(s, room.id as String, name);
		}
		long now = System.currentTimeMillis();
		Closure player = { String r ->
			String id = room[r] as String;
			List<Map> cells = race ? boards[r] : boards[NumberPlaceStore.SHARED];
			Date lockedUntil = room[r + 'LockedUntil'] as Date;
			long lock = (lockedUntil != null && room.status == NumberPlaceStore.PLAYING) ? Math.max(0L, lockedUntil.time - now) : 0L;
			return [
				id: id,
				displayName: NumberPlaceStore.displayName(s, id),
				color: colorOf(room, r),
				filled: cells.count { Map c -> c.by == id },
				misses: room[r + 'Misses'] ?: 0L,
				lockMs: lock,
			];
		};
		// In a race the caller sees its own board only.
		List<Map> cells = (race ? boards[role] : boards[NumberPlaceStore.SHARED]).collect { Map c ->
			[cell: c.cell, digit: c.digit, by: c.by];
		};
		Date startedAt = room.startedAt as Date;
		Date finishedAt = room.finishedAt as Date;
		long elapsed = (startedAt == null) ? 0L : Math.max(0L, ((finishedAt != null && room.status == NumberPlaceStore.FINISHED) ? finishedAt.time : now) - startedAt.time);
		boolean started = room.status in [NumberPlaceStore.PLAYING, NumberPlaceStore.FINISHED];
		return [
			id: room.id,
			mode: room.mode,
			level: room.level,
			status: room.status,
			host: player(NumberPlaceStore.HOST_ROLE),
			guest: player(NumberPlaceStore.GUEST_ROLE),
			you: role,
			theme: room.theme ?: DEFAULT_THEME,
			guestReady: room.guestReady ? true : false,
			givens: started ? room.givens : null,
			cells: cells,
			winner: room.winner,
			resignedBy: room.resignedBy,
			createdAt: room.createdAt,
			startedAt: startedAt,
			finishedAt: finishedAt,
			elapsedMs: Math.min(elapsed, (long) Integer.MAX_VALUE),
			topic: NumberPlaceStore.topic(room.id as String),
			chatFileId: room.fileId,
		];
	}

}

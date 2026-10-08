package webtop.minesweeper;

/**
 * What the GraphQL resolvers of the Minesweeper app do.
 *
 * A game is a room of two users, who either clear one board together
 * (`coop`) or play against each other: a race to clear the same board on
 * boards of their own (`race`, time attack), or one board both play, where
 * a flag put on a mine takes it (`territory`). One user invites the other
 * (the room waits), the other accepts (the room is a lobby) or declines. In
 * the lobby each player picks a colour, the host also picks the difficulty
 * and the board, the guest says it is ready and the host starts the game:
 * the board is made here (MinesweeperRules), every board can be cleared
 * without guessing, and where its mines are is kept from both players (the
 * room is playing). The players then open cells and put flags down until
 * the game is decided or one gives up.
 *
 * Together, a mine opened ends the game for both. Against each other it
 * makes the player wait LOCK_MS instead, and so does, in territory, a flag
 * put where there is no mine. A race is won by the first to clear its
 * board; territory by the one who took more mines once every mine is taken
 * or opened, or as soon as the other cannot catch up. Every operation first
 * finds, in the caller's own session, who the caller is, then reads and
 * writes the room as the games service user (MinesweeperStore).
 *
 * Each change is announced to the two players as a topic message on the
 * room's topic (game/minesweeper/rooms/<room>): invited, accepted, changed,
 * ready, started, declined, left, cancelled, open, flag, claim, miss,
 * resigned, and expired when an idle room is removed. In a race an open
 * says how far the player got, not which cells it opened. The room itself
 * is the record; a client that misses a message reads the room again.
 *
 * An invitation is also told in the chat: a card in the direct messages of
 * the two users (webtop.chat.ChatSystem), from the host, whose button opens
 * the game. The card's design is the app's own (INVITATION_CARD).
 */
class MinesweeperApi {

	/** How many rooms of a user may be waiting, getting ready or playing at once. */
	static final int MAX_OPEN_ROOMS = 10;
	/** How many ended rooms a user is shown. */
	static final int ENDED_SHOWN = 10;
	/** Ended rooms are removed after this many days. */
	static final int KEEP_ENDED_DAYS = 7;
	/** Rooms waiting, getting ready or playing are removed after this many days without a change or a move. */
	static final int KEEP_IDLE_DAYS = 30;
	/** How long a player waits after a miss (the app's LOCK_MS too). */
	static final long LOCK_MS = 5000L;

	private static final long DAY_MS = 24L * 3600L * 1000L;

	/** The design of the invitation card: in the app's folder, deployed with the Webtop. */
	static final String INVITATION_CARD = '/usr/share/webtop/apps/minesweeper/assets/cards/invitation';

	/** The colours and the board the players get when they have not chosen. */
	static final String DEFAULT_HOST_COLOR = 'strawberry';
	static final String DEFAULT_GUEST_COLOR = 'mint';
	static final String DEFAULT_THEME = 'ichigo';
	static final int DEFAULT_LEVEL = 1;

	static final List<String> MODES = [MinesweeperStore.COOP, MinesweeperStore.RACE, MinesweeperStore.TERRITORY];

	def context;
	def session;
	String userId;

	protected MinesweeperApi(context) {
		this.context = context;
		this.session = context.session;
		if (session.isAnonymous()) {
			throw new IllegalStateException('Sign in to play.');
		}
		this.userId = session.userID;
	}

	static MinesweeperApi create(context) {
		return new MinesweeperApi(context);
	}

	private Object asService(Closure closure) {
		return MinesweeperStore.withService(context) { service -> closure.call(service.session); };
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
			for (String id : MinesweeperStore.ids(s)) {
				Map room = MinesweeperStore.read(s, id);
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
			if (!MinesweeperStore.userExists(s, opponent)) {
				throw new IllegalArgumentException('No such user.');
			}
			purge(s);
			int open = 0;
			for (String id : MinesweeperStore.ids(s)) {
				Map room = MinesweeperStore.read(s, id);
				if (room != null && isPlayer(room) && isOpen(room)) {
					open++;
				}
			}
			if (open >= MAX_OPEN_ROOMS) {
				throw new IllegalStateException("You already have ${MAX_OPEN_ROOMS} games open.".toString());
			}
			Map room = MinesweeperStore.create(s, userId, opponent, kind, difficulty, myColor, theirColor, board);
			MinesweeperStore.publish(context, room, [type: 'invited', by: userId]);
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
						hostName: MinesweeperStore.displayName(s, userId) ?: userId,
						guest: guest,
						guestName: MinesweeperStore.displayName(s, guest) ?: guest,
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
			if (room.status != MinesweeperStore.WAITING) {
				throw new IllegalStateException('The invitation is no longer open.');
			}
			if (room.host == userId) {
				throw new IllegalStateException('Wait for the other player to accept.');
			}
			Map changes = [(MinesweeperStore.STATUS): MinesweeperStore.LOBBY, (MinesweeperStore.GUEST_READY): false];
			if (wanted && wanted != colorOf(room, MinesweeperStore.HOST_ROLE)) {
				changes[MinesweeperStore.GUEST_COLOR] = wanted;
			}
			room = MinesweeperStore.update(s, id, changes);
			MinesweeperStore.publish(context, room, [type: 'accepted', by: userId]);
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
			if (!(room.status in [MinesweeperStore.WAITING, MinesweeperStore.LOBBY])) {
				throw new IllegalStateException('The invitation is no longer open.');
			}
			String status = (room.host == userId) ? MinesweeperStore.CANCELLED :
				(room.status == MinesweeperStore.LOBBY) ? MinesweeperStore.LEFT : MinesweeperStore.DECLINED;
			room = MinesweeperStore.update(s, id, [(MinesweeperStore.STATUS): status, (MinesweeperStore.FINISHED_AT): new Date()]);
			MinesweeperStore.publish(context, room, [type: status, by: userId]);
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
			if (!(room.status == MinesweeperStore.LOBBY || (room.status == MinesweeperStore.WAITING && host))) {
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
					throw new IllegalStateException('The host chooses the difficulty and the board.');
				}
				if (wantedLevel) {
					changes[MinesweeperStore.LEVEL] = wantedLevel as long;
				}
				if (wantedTheme) {
					changes[MinesweeperStore.THEME] = wantedTheme;
				}
			}
			if (changes) {
				room = MinesweeperStore.update(s, id, changes);
			}
			MinesweeperStore.publish(context, room, [type: 'changed', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	/** The invited user says it is ready (or, with `false`, no longer is). */
	Map ready(String id, Object ready) {
		boolean flag = (ready == null) ? true : (ready as boolean);
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != MinesweeperStore.LOBBY) {
				throw new IllegalStateException('The game is not being set up.');
			}
			if (room.host == userId) {
				throw new IllegalStateException('The host starts the game.');
			}
			room = MinesweeperStore.update(s, id, [(MinesweeperStore.GUEST_READY): flag]);
			MinesweeperStore.publish(context, room, [type: 'ready', by: userId, ready: flag]);
			return toRoom(s, room);
		} as Map;
	}

	/**
	 * The host starts the game once the invited user is ready. The board is
	 * made here, of the room's difficulty, and where its mines are is kept
	 * where neither player can read it.
	 */
	Map start(String id) {
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != MinesweeperStore.LOBBY) {
				throw new IllegalStateException('The game is not being set up.');
			}
			if (room.host != userId) {
				throw new IllegalStateException('Wait for the host to start the game.');
			}
			if (!room.guestReady) {
				throw new IllegalStateException('Wait for the other player to get ready.');
			}
			Map board = MinesweeperRules.generate(room.level as int);
			MinesweeperStore.writeLayout(s, id, board.layout as String);
			room = MinesweeperStore.update(s, id, [
				(MinesweeperStore.START): board.start as long,
				(MinesweeperStore.STATUS): MinesweeperStore.PLAYING,
				(MinesweeperStore.STARTED_AT): new Date(),
				(MinesweeperStore.HOST_MISSES): 0L,
				(MinesweeperStore.GUEST_MISSES): 0L,
				(MinesweeperStore.HOST_LOCKED_UNTIL): null,
				(MinesweeperStore.GUEST_LOCKED_UNTIL): null,
			]);
			MinesweeperStore.publish(context, room, [type: 'started', by: userId]);
			return toRoom(s, room);
		} as Map;
	}

	// --- playing ------------------------------------------------------------------

	/**
	 * Opens a cell for the caller (`cell` row by row), or with `chord` the
	 * covered cells around an open number whose mines are all marked: by
	 * flags on the board played, in territory by mines taken, and by mines
	 * opened. A flagged or taken cell is not opened. Together a mine ends
	 * the game; against each other it makes the caller wait LOCK_MS.
	 * Returns what came of it (`opened`, `mine` or `none`) and the room.
	 */
	Map open(String id, Object cell, Object chord) {
		int at = (cell == null) ? -1 : (cell as int);
		boolean around = (chord == null) ? false : (chord as boolean);
		return asService { s ->
			Map room = playing(s, id, at);
			String role = roleOf(room);
			checkLock(room, role);
			String layout = MinesweeperStore.readLayout(s, id);
			boolean race = (room.mode == MinesweeperStore.RACE);
			String board = race ? role : MinesweeperStore.SHARED;
			Map before = boardState(room, layout, MinesweeperStore.actions(s, id, board));
			int[] view = before.view as int[];
			int[] mines = before.mines as int[];
			Map<Integer, String> flags = before.flags as Map<Integer, String>;

			List<Integer> targets = [];
			if (around) {
				int count = view[at];
				if (count >= 1 && count <= 8) {
					int marked = 0;
					List<Integer> covered = [];
					for (int j : MinesweeperRules.neighbors(before.width as int, before.height as int)[at]) {
						if (view[j] == MinesweeperRules.EXPLODED || (view[j] == MinesweeperRules.COVERED && flags.containsKey(j))) {
							marked++;
						} else if (view[j] == MinesweeperRules.COVERED) {
							covered.add(j);
						}
					}
					if (marked == count) {
						targets = covered;
					}
				}
			} else if (view[at] == MinesweeperRules.COVERED && !flags.containsKey(at)) {
				targets = [at];
			}
			if (!targets) {
				return [outcome: 'none', room: toRoom(s, room)];
			}

			for (int t : targets) {
				MinesweeperStore.addAction(s, id, board, MinesweeperStore.OPEN, t, userId);
			}
			Map after = boardState(room, layout, MinesweeperStore.actions(s, id, board));
			int[] now = after.view as int[];
			List<List<Integer>> reveal = [];
			for (int i = 0; i < now.length; i++) {
				if (view[i] == MinesweeperRules.COVERED && now[i] >= 0 && now[i] <= 8) {
					reveal.add([i, now[i]]);
				}
			}
			Integer blast = targets.find { int t -> mines[t] != 0 } as Integer;
			Map<String, Integer> opened = after.opened as Map<String, Integer>;
			Map message = [type: 'open', by: userId, board: board, cell: at, opened: (opened[userId] ?: 0)];
			if (!race) {
				message.reveal = reveal;
			}

			Map changes = [:];
			String winner = null;
			boolean over = false;
			if (blast != null) {
				message.mine = true;
				if (!race) {
					message.blast = blast;
				}
				if (room.mode == MinesweeperStore.COOP) {
					over = true;
				} else {
					long misses = ((room[role + 'Misses'] ?: 0L) as long) + 1L;
					changes[missesKey(role)] = misses;
					changes[lockKey(role)] = new Date(System.currentTimeMillis() + LOCK_MS);
					message.misses = misses;
					message.lockMs = LOCK_MS;
				}
			}
			if (!over) {
				if (room.mode == MinesweeperStore.TERRITORY) {
					winner = territoryWinner(room, after);
					over = (winner != null);
				} else if (MinesweeperRules.openCount(now) >= (after.safe as int)) {
					// Cleared: in a race the caller's own board, the first to finish.
					over = true;
					winner = race ? role : null;
				}
			}
			room = finish(s, room, changes, over, winner);
			message.over = (room.status == MinesweeperStore.FINISHED);
			if (message.over) {
				message.winner = room.winner;
			}
			MinesweeperStore.publish(context, room, message);
			return [outcome: (blast != null) ? 'mine' : (reveal ? 'opened' : 'none'), room: toRoom(s, room)];
		} as Map;
	}

	/**
	 * Puts a flag down for the caller (`flag` true), or takes it back up.
	 * Together the flags are shared; in a race each player's are its own. In
	 * territory a flag takes the cell: when it holds a mine the mine is the
	 * caller's (`found`), otherwise the caller waits LOCK_MS (`wrong`).
	 * Returns what came of it (`flagged`, `unflagged`, `found`, `wrong` or
	 * `none`) and the room.
	 */
	Map flag(String id, Object cell, Object flag) {
		int at = (cell == null) ? -1 : (cell as int);
		return asService { s ->
			Map room = playing(s, id, at);
			String role = roleOf(room);
			String layout = MinesweeperStore.readLayout(s, id);
			boolean race = (room.mode == MinesweeperStore.RACE);
			String board = race ? role : MinesweeperStore.SHARED;
			Map state = boardState(room, layout, MinesweeperStore.actions(s, id, board));
			int[] view = state.view as int[];
			int[] mines = state.mines as int[];
			Map<Integer, String> flags = state.flags as Map<Integer, String>;
			if (view[at] != MinesweeperRules.COVERED) {
				return [outcome: 'none', room: toRoom(s, room)];
			}

			if (room.mode == MinesweeperStore.TERRITORY) {
				checkLock(room, role);
				if (flags.containsKey(at)) {
					return [outcome: 'none', room: toRoom(s, room)];
				}
				if (mines[at] == 0) {
					long misses = ((room[role + 'Misses'] ?: 0L) as long) + 1L;
					room = MinesweeperStore.update(s, id, [
						(missesKey(role)): misses,
						(lockKey(role)): new Date(System.currentTimeMillis() + LOCK_MS),
					]);
					MinesweeperStore.publish(context, room, [type: 'miss', by: userId, misses: misses, lockMs: LOCK_MS]);
					return [outcome: 'wrong', room: toRoom(s, room)];
				}
				if (!MinesweeperStore.addAction(s, id, board, MinesweeperStore.FLAG, at, userId)) {
					// The other player took it first.
					return [outcome: 'none', room: toRoom(s, MinesweeperStore.read(s, id))];
				}
				Map after = boardState(room, layout, MinesweeperStore.actions(s, id, board));
				String winner = territoryWinner(room, after);
				room = finish(s, room, [:], winner != null, winner);
				Map message = [type: 'claim', by: userId, cell: at, found: found(after, userId)];
				message.over = (room.status == MinesweeperStore.FINISHED);
				if (message.over) {
					message.winner = room.winner;
				}
				MinesweeperStore.publish(context, room, message);
				return [outcome: 'found', room: toRoom(s, room)];
			}

			boolean on = (flag == null) ? !flags.containsKey(at) : (flag as boolean);
			boolean changed = on ?
				(!flags.containsKey(at) && MinesweeperStore.addAction(s, id, board, MinesweeperStore.FLAG, at, userId)) :
				(flags.containsKey(at) && MinesweeperStore.removeAction(s, id, board, MinesweeperStore.FLAG, at));
			if (!changed) {
				return [outcome: 'none', room: toRoom(s, room)];
			}
			if (room.mode == MinesweeperStore.COOP) {
				MinesweeperStore.publish(context, room, [type: 'flag', by: userId, cell: at, flag: on]);
			}
			return [outcome: on ? 'flagged' : 'unflagged', room: toRoom(s, room)];
		} as Map;
	}

	/**
	 * The caller gives up: against each other the other player wins; in
	 * cooperation the game ends uncleared.
	 */
	Map resign(String id) {
		return asService { s ->
			Map room = mine(s, id);
			if (room.status != MinesweeperStore.PLAYING) {
				throw new IllegalStateException('The game is not being played.');
			}
			String winner = (room.mode == MinesweeperStore.COOP) ? null : otherRole(roleOf(room));
			room = MinesweeperStore.update(s, id, [
				(MinesweeperStore.STATUS): MinesweeperStore.FINISHED,
				(MinesweeperStore.WINNER): winner,
				(MinesweeperStore.RESIGNED_BY): userId,
				(MinesweeperStore.FINISHED_AT): new Date(),
			]);
			MinesweeperStore.publish(context, room, [type: 'resigned', by: userId, winner: winner]);
			return toRoom(s, room);
		} as Map;
	}

	// --- the board ----------------------------------------------------------------

	/**
	 * A board of the room as it stands, from where the mines are and what
	 * was done on it: the start is opened first, then each cell in the order
	 * it was opened (a mine opened shows as exploded). A flag stays only on a
	 * cell still covered. Returns the view, the mines, the flags by cell
	 * (who put each down), the mines opened ([cell, by]), how many cells
	 * each player opened (the cells its own opening uncovered first), and
	 * the board's size.
	 */
	private static Map boardState(Map room, String layout, List<Map> actions) {
		int level = room.level as int;
		int width = MinesweeperRules.width(level);
		int height = MinesweeperRules.height(level);
		int cells = width * height;
		int[] mines = MinesweeperRules.parseLayout(layout, cells);
		int[] counts = MinesweeperRules.counts(width, height, mines);
		int[] view = MinesweeperRules.coveredView(cells);
		if (room.start != null) {
			MinesweeperRules.flood(width, height, counts, view, room.start as int);
		}
		Map<String, Integer> opened = [:];
		List<Map> blasts = [];
		Map<Integer, String> flags = [:];
		for (Map a : actions) {
			int c = a.cell as int;
			if (c < 0 || c >= cells) {
				continue;
			}
			String by = a.by as String;
			if (a.kind == MinesweeperStore.FLAG) {
				flags[c] = by;
			} else if (mines[c] != 0) {
				if (view[c] == MinesweeperRules.COVERED) {
					view[c] = MinesweeperRules.EXPLODED;
					blasts.add([cell: c, by: by]);
				}
			} else {
				int n = MinesweeperRules.flood(width, height, counts, view, c).size();
				opened[by] = (opened[by] ?: 0) + n;
			}
		}
		flags = flags.findAll { Integer c, String by -> view[c] == MinesweeperRules.COVERED };
		int total = 0;
		for (int m : mines) {
			total += m;
		}
		return [view: view, mines: mines, flags: flags, blasts: blasts, opened: opened,
			width: width, height: height, total: total, safe: cells - total];
	}

	/** Territory: the mines a player took. */
	private static int found(Map state, String player) {
		return (state.flags as Map<Integer, String>).count { Integer c, String by -> by == player } as int;
	}

	/**
	 * Territory: host, guest or draw once it is settled, else null. It is
	 * settled when every mine is taken or opened, or when one player has
	 * more than the other could still reach.
	 */
	private static String territoryWinner(Map room, Map state) {
		int host = found(state, room.host as String);
		int guest = found(state, room.guest as String);
		int left = (state.total as int) - host - guest - (state.blasts as List).size();
		if (left <= 0) {
			return (host == guest) ? 'draw' : (host > guest) ? MinesweeperStore.HOST_ROLE : MinesweeperStore.GUEST_ROLE;
		}
		if (host > guest + left) {
			return MinesweeperStore.HOST_ROLE;
		}
		if (guest > host + left) {
			return MinesweeperStore.GUEST_ROLE;
		}
		return null;
	}

	/**
	 * Writes the changes of a move and, when the game is over, its end. Both
	 * players may end the game at once: the first to finish is kept.
	 */
	private Map finish(s, Map room, Map changes, boolean over, String winner) {
		String id = room.id as String;
		Map all = new LinkedHashMap(changes);
		if (over) {
			Map latest = MinesweeperStore.read(s, id);
			if (latest.status == MinesweeperStore.PLAYING) {
				all[MinesweeperStore.STATUS] = MinesweeperStore.FINISHED;
				all[MinesweeperStore.WINNER] = winner;
				all[MinesweeperStore.FINISHED_AT] = new Date();
			}
		}
		return all ? MinesweeperStore.update(s, id, all) : MinesweeperStore.read(s, id);
	}

	// --- helpers ------------------------------------------------------------------

	/** Removes the ended rooms and the idle ones; the players of an idle room are told it is gone. */
	private void purge(s) {
		long now = System.currentTimeMillis();
		List<Map> removed = MinesweeperStore.purge(s, new Date(now - KEEP_ENDED_DAYS * DAY_MS), new Date(now - KEEP_IDLE_DAYS * DAY_MS));
		removed.findAll { Map room -> isOpen(room) }.each { Map room ->
			MinesweeperStore.publish(context, room, [type: 'expired', by: MinesweeperStore.SERVICE_USER]);
		};
	}

	/** The room, which must be one the caller plays in. */
	private Map mine(s, String id) {
		Map room = MinesweeperStore.read(s, MinesweeperStore.checkId(id));
		if (room == null || !isPlayer(room)) {
			throw new IllegalArgumentException('No such game.');
		}
		return room;
	}

	/** The room, which must be played now, and a cell of its board. */
	private Map playing(s, String id, int cell) {
		Map room = mine(s, id);
		if (room.status != MinesweeperStore.PLAYING) {
			throw new IllegalStateException('The game is not being played.');
		}
		if (cell < 0 || cell >= MinesweeperRules.cells(room.level as int)) {
			throw new IllegalArgumentException('No such cell.');
		}
		return room;
	}

	/** Refuses a move while the caller waits after a miss. */
	private static void checkLock(Map room, String role) {
		Date lockedUntil = room[role + 'LockedUntil'] as Date;
		if (lockedUntil != null && lockedUntil.time > System.currentTimeMillis()) {
			throw new IllegalStateException('Wait a moment after a miss.');
		}
	}

	private boolean isPlayer(Map room) {
		return room.host == userId || room.guest == userId;
	}

	private static boolean isOpen(Map room) {
		return room.status in [MinesweeperStore.WAITING, MinesweeperStore.LOBBY, MinesweeperStore.PLAYING];
	}

	/** host or guest: the caller's role in the room. */
	private String roleOf(Map room) {
		return (room.host == userId) ? MinesweeperStore.HOST_ROLE : MinesweeperStore.GUEST_ROLE;
	}

	private static String otherRole(String role) {
		return (role == MinesweeperStore.HOST_ROLE) ? MinesweeperStore.GUEST_ROLE : MinesweeperStore.HOST_ROLE;
	}

	private static String colorKey(String role) {
		return (role == MinesweeperStore.HOST_ROLE) ? MinesweeperStore.HOST_COLOR : MinesweeperStore.GUEST_COLOR;
	}

	private static String missesKey(String role) {
		return (role == MinesweeperStore.HOST_ROLE) ? MinesweeperStore.HOST_MISSES : MinesweeperStore.GUEST_MISSES;
	}

	private static String lockKey(String role) {
		return (role == MinesweeperStore.HOST_ROLE) ? MinesweeperStore.HOST_LOCKED_UNTIL : MinesweeperStore.GUEST_LOCKED_UNTIL;
	}

	private static String colorOf(Map room, String role) {
		String color = room[role + 'Color'] as String;
		return color ?: ((role == MinesweeperStore.HOST_ROLE) ? DEFAULT_HOST_COLOR : DEFAULT_GUEST_COLOR);
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
		String id = room.id as String;
		String role = roleOf(room);
		boolean race = (room.mode == MinesweeperStore.RACE);
		boolean territory = (room.mode == MinesweeperStore.TERRITORY);
		boolean finished = (room.status == MinesweeperStore.FINISHED);
		int level = room.level as int;
		String layout = (room.start != null) ? MinesweeperStore.readLayout(s, id) : null;
		Map<String, Map> boards = [:];
		if (layout) {
			for (String name : (race ? [MinesweeperStore.HOST_ROLE, MinesweeperStore.GUEST_ROLE] : [MinesweeperStore.SHARED])) {
				boards[name] = boardState(room, layout, MinesweeperStore.actions(s, id, name));
			}
		}
		long now = System.currentTimeMillis();
		Closure player = { String r ->
			String playerId = room[r] as String;
			Map state = race ? boards[r] : boards[MinesweeperStore.SHARED];
			Date lockedUntil = room[r + 'LockedUntil'] as Date;
			long lock = (lockedUntil != null && room.status == MinesweeperStore.PLAYING) ? Math.max(0L, lockedUntil.time - now) : 0L;
			return [
				id: playerId,
				displayName: MinesweeperStore.displayName(s, playerId),
				color: colorOf(room, r),
				opened: state ? ((state.opened as Map<String, Integer>)[playerId] ?: 0) : 0,
				found: (state && territory) ? found(state, playerId) : 0,
				misses: room[r + 'Misses'] ?: 0L,
				lockMs: lock,
			];
		};
		// In a race the caller sees its own board only; once over, every mine.
		Map mineBoard = race ? boards[role] : boards[MinesweeperStore.SHARED];
		Date startedAt = room.startedAt as Date;
		Date finishedAt = room.finishedAt as Date;
		long elapsed = (startedAt == null) ? 0L : Math.max(0L, ((finishedAt != null && finished) ? finishedAt.time : now) - startedAt.time);
		return [
			id: id,
			mode: room.mode,
			level: level,
			status: room.status,
			host: player(MinesweeperStore.HOST_ROLE),
			guest: player(MinesweeperStore.GUEST_ROLE),
			you: role,
			theme: room.theme ?: DEFAULT_THEME,
			guestReady: room.guestReady ? true : false,
			width: MinesweeperRules.width(level),
			height: MinesweeperRules.height(level),
			mines: MinesweeperRules.mines(level),
			start: mineBoard ? room.start : null,
			board: mineBoard ? MinesweeperRules.formatView(mineBoard.view as int[], mineBoard.mines as int[], finished) : null,
			flags: mineBoard ? (mineBoard.flags as Map<Integer, String>).collect { Integer c, String by -> [cell: c, by: by] } : [],
			blasts: mineBoard ? mineBoard.blasts : [],
			winner: room.winner,
			resignedBy: room.resignedBy,
			createdAt: room.createdAt,
			startedAt: startedAt,
			finishedAt: finishedAt,
			elapsedMs: Math.min(elapsed, (long) Integer.MAX_VALUE),
			topic: MinesweeperStore.topic(id),
			chatFileId: room.fileId,
		];
	}

}

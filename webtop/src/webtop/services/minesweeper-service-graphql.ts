/**
 * Minesweeper service: games between two users, through the Minesweeper
 * GraphQL schema (/etc/graphql/webtop/minesweeper). A room lives in the
 * workspace it was opened in, so the service works on the endpoint of the
 * client it is given.
 *
 * The server makes the board and keeps where the mines are; the app asks to
 * open a cell or put a flag down and is shown what the board looks like
 * afterwards. Changes arrive as topic messages on `MinesweeperRoom.topic`:
 * the app watches `game/minesweeper/rooms/*` once (EventHub.watchTopic) and
 * receives the messages of every room it plays in.
 */

import type { GraphQLClient } from '../graphql/client.js';

export type MinesweeperStatus = 'waiting' | 'lobby' | 'playing' | 'finished' | 'declined' | 'left' | 'cancelled';
/** Cooperation, or a race (time attack) or territory between the two. */
export type MinesweeperMode = 'coop' | 'race' | 'territory';
export type MinesweeperRole = 'host' | 'guest';

export interface MinesweeperPlayer {
	id: string;
	displayName: string | null;
	/** The player's colour (looks.ts); the two differ. */
	color: string;
	/** Safe cells the player opened: on the shared board, or on the player's own in a race. */
	opened: number;
	/** Territory: the mines the player took. */
	found: number;
	/** Mines opened and, in territory, cells taken that held none. */
	misses: number;
	/** How long the player still waits after a miss, in milliseconds. */
	lockMs: number;
}

export interface MinesweeperMark {
	cell: number;
	/** Who put it there. */
	by: string;
}

export interface MinesweeperRoom {
	id: string;
	mode: MinesweeperMode;
	/** The board's difficulty, 1 to 4, chosen by the host. */
	level: number;
	status: MinesweeperStatus;
	/** Who sent the invitation. */
	host: MinesweeperPlayer;
	/** Who was invited. */
	guest: MinesweeperPlayer;
	/** The caller's role. */
	you: MinesweeperRole;
	/** The board theme (looks.ts), chosen by the host. */
	theme: string;
	/** Whether the guest has said it is ready; the host starts the game once it has. */
	guestReady: boolean;
	width: number;
	height: number;
	/** How many mines the board holds. */
	mines: number;
	/** The cell opened when the game began; null until it starts. */
	start: number | null;
	/**
	 * The board as the caller sees it, one character per cell: '-' covered,
	 * '0' to '8' open, '*' a mine opened; once the game is over, 'm' for
	 * every other mine. Null until the game starts.
	 */
	board: string | null;
	/** The flags on the board shown: shared in cooperation, the caller's own in a race, the mines taken in territory. */
	flags: MinesweeperMark[];
	/** The mines opened on the board shown, and who opened them. */
	blasts: MinesweeperMark[];
	/** host, guest or draw once a game against each other is over; null in cooperation. */
	winner: MinesweeperRole | 'draw' | null;
	resignedBy: string | null;
	createdAt: string;
	startedAt: string | null;
	finishedAt: string | null;
	/** Time played: since the start, up to now or to the end. */
	elapsedMs: number;
	topic: string;
	/** The room's chat is the Chat conversation of this file (chat-service-graphql, { fileId }). */
	chatFileId: string;
}

export type MinesweeperOutcome = 'opened' | 'mine' | 'found' | 'wrong' | 'flagged' | 'unflagged' | 'none';

export interface MinesweeperMove {
	/**
	 * What came of it: a cell opened, a mine opened, in territory a mine
	 * taken (found) or a cell taken that holds none (wrong), a flag put down
	 * or picked up, or nothing (the cell was open or taken already).
	 */
	outcome: MinesweeperOutcome;
	room: MinesweeperRoom;
}

/** What a room's topic message carries (see minesweeper.graphqls). */
export interface MinesweeperMessage {
	roomId: string;
	type: 'invited' | 'accepted' | 'changed' | 'ready' | 'started' | 'declined' | 'left' | 'cancelled' |
		'open' | 'flag' | 'claim' | 'miss' | 'resigned' | 'expired';
	by: string;
	ready?: boolean;
	/** open: the board (shared, host or guest); on the shared board the cells opened, as [cell, count]. */
	board?: string;
	reveal?: [number, number][];
	/** open: a mine was opened; on the shared board, which one. */
	mine?: boolean;
	blast?: number;
	/** flag, claim: the cell; flag: whether a flag is there now. */
	cell?: number;
	flag?: boolean;
	/** How many cells the player opened, and mines it took. */
	opened?: number;
	found?: number;
	/** open (a mine), miss: the player's misses and how long it waits. */
	misses?: number;
	lockMs?: number;
	over?: boolean;
	winner?: MinesweeperRole | 'draw' | null;
}

/** The topic pattern that covers every room of the user. */
export const MINESWEEPER_TOPICS = 'game/minesweeper/rooms/*';

const PLAYER_FIELDS = 'id displayName color opened found misses lockMs';
const ROOM_FIELDS = `
	id mode level status you theme guestReady width height mines start board winner resignedBy
	createdAt startedAt finishedAt elapsedMs topic chatFileId
	host { ${PLAYER_FIELDS} }
	guest { ${PLAYER_FIELDS} }
	flags { cell by }
	blasts { cell by }
`;

export function isMinesweeperMessage(value: unknown): value is MinesweeperMessage {
	const m = value as MinesweeperMessage | null;
	return !!m && typeof m === 'object' && typeof m.roomId === 'string' && typeof m.type === 'string';
}

export class MinesweeperServiceGraphQL {
	#client: GraphQLClient;

	constructor(client: GraphQLClient) {
		this.#client = client;
	}

	/** The user's rooms: waiting, getting ready or playing first, then the ended ones, newest first. */
	async listRooms(): Promise<MinesweeperRoom[]> {
		const data = await this.#client.query<{ minesweeperRooms: MinesweeperRoom[] }>(
			`query { minesweeperRooms { ${ROOM_FIELDS} } }`);
		return data.minesweeperRooms;
	}

	async getRoom(id: string): Promise<MinesweeperRoom> {
		const data = await this.#client.query<{ minesweeperRoom: MinesweeperRoom }>(
			`query ($id: ID!) { minesweeperRoom(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.minesweeperRoom;
	}

	/**
	 * Invites a user to cooperate or to play against the caller. `level`,
	 * `color` and `theme` are the caller's first choices (changed later with
	 * `setup`). The invitation is also posted as a card in the direct
	 * messages of the two users; `locale` is the language its text is
	 * written in.
	 */
	async invite(opponentId: string, mode: MinesweeperMode, level: number,
		color?: string, theme?: string, locale?: string): Promise<MinesweeperRoom> {
		const data = await this.#client.mutation<{ minesweeperInvite: MinesweeperRoom }>(
			`mutation ($opponentId: ID!, $mode: String!, $level: Int, $color: String, $theme: String, $locale: String) {
				minesweeperInvite(opponentId: $opponentId, mode: $mode, level: $level, color: $color, theme: $theme, locale: $locale) { ${ROOM_FIELDS} }
			}`,
			{ opponentId, mode, level, color: color ?? null, theme: theme ?? null, locale: locale ?? null });
		return data.minesweeperInvite;
	}

	/** Accepts an invitation: the room is a lobby. `color` is the caller's first choice, unless it is the host's. */
	async accept(id: string, color?: string): Promise<MinesweeperRoom> {
		const data = await this.#client.mutation<{ minesweeperAccept: MinesweeperRoom }>(
			`mutation ($id: ID!, $color: String) { minesweeperAccept(id: $id, color: $color) { ${ROOM_FIELDS} } }`,
			{ id, color: color ?? null });
		return data.minesweeperAccept;
	}

	/** Changes the caller's colour and, for the host, the difficulty and the board, while the room is being set up. */
	async setup(id: string, changes: { color?: string; level?: number; theme?: string }): Promise<MinesweeperRoom> {
		const data = await this.#client.mutation<{ minesweeperSetup: MinesweeperRoom }>(
			`mutation ($id: ID!, $color: String, $level: Int, $theme: String) {
				minesweeperSetup(id: $id, color: $color, level: $level, theme: $theme) { ${ROOM_FIELDS} }
			}`,
			{ id, color: changes.color ?? null, level: changes.level ?? null, theme: changes.theme ?? null });
		return data.minesweeperSetup;
	}

	/** The invited user says it is ready, or no longer is. */
	async ready(id: string, ready: boolean): Promise<MinesweeperRoom> {
		const data = await this.#client.mutation<{ minesweeperReady: MinesweeperRoom }>(
			`mutation ($id: ID!, $ready: Boolean) { minesweeperReady(id: $id, ready: $ready) { ${ROOM_FIELDS} } }`,
			{ id, ready });
		return data.minesweeperReady;
	}

	/** The host starts the game; the server makes the board. */
	async start(id: string): Promise<MinesweeperRoom> {
		const data = await this.#client.mutation<{ minesweeperStart: MinesweeperRoom }>(
			`mutation ($id: ID!) { minesweeperStart(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.minesweeperStart;
	}

	/** Declines an invitation or leaves a lobby, or takes back one's own invitation. */
	async decline(id: string): Promise<MinesweeperRoom> {
		const data = await this.#client.mutation<{ minesweeperDecline: MinesweeperRoom }>(
			`mutation ($id: ID!) { minesweeperDecline(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.minesweeperDecline;
	}

	/**
	 * Opens a cell; with `chord`, the covered cells around an open number
	 * whose mines are all flagged (in territory: taken or opened).
	 */
	async open(id: string, cell: number, chord = false): Promise<MinesweeperMove> {
		const data = await this.#client.mutation<{ minesweeperOpen: MinesweeperMove }>(
			`mutation ($id: ID!, $cell: Int!, $chord: Boolean) {
				minesweeperOpen(id: $id, cell: $cell, chord: $chord) { outcome room { ${ROOM_FIELDS} } }
			}`,
			{ id, cell, chord });
		return data.minesweeperOpen;
	}

	/** Puts a flag down or picks it up; in territory, takes the cell for a mine. */
	async flag(id: string, cell: number, flag: boolean): Promise<MinesweeperMove> {
		const data = await this.#client.mutation<{ minesweeperFlag: MinesweeperMove }>(
			`mutation ($id: ID!, $cell: Int!, $flag: Boolean) {
				minesweeperFlag(id: $id, cell: $cell, flag: $flag) { outcome room { ${ROOM_FIELDS} } }
			}`,
			{ id, cell, flag });
		return data.minesweeperFlag;
	}

	/** Gives the game up: against each other the other player wins; in cooperation the game ends uncleared. */
	async resign(id: string): Promise<MinesweeperRoom> {
		const data = await this.#client.mutation<{ minesweeperResign: MinesweeperRoom }>(
			`mutation ($id: ID!) { minesweeperResign(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.minesweeperResign;
	}
}

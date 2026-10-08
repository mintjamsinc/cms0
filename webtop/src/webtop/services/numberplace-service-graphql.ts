/**
 * Number Place service: games between two users, through the Number Place
 * GraphQL schema (/etc/graphql/webtop/numberplace). A room lives in the
 * workspace it was opened in, so the service works on the endpoint of the
 * client it is given.
 *
 * The server keeps the solution and checks every number; the app sends a
 * number and is told whether it was right. Changes arrive as topic messages
 * on `NumberPlaceRoom.topic`: the app watches `game/numberplace/rooms/*`
 * once (EventHub.watchTopic) and receives the messages of every room it
 * plays in.
 */

import type { GraphQLClient } from '../graphql/client.js';

export type NumberPlaceStatus = 'waiting' | 'lobby' | 'playing' | 'finished' | 'declined' | 'left' | 'cancelled';
/** Cooperation, or a race (time attack) or territory between the two. */
export type NumberPlaceMode = 'coop' | 'race' | 'territory';
export type NumberPlaceRole = 'host' | 'guest';

export interface NumberPlacePlayer {
	id: string;
	displayName: string | null;
	/** The player's colour (looks.ts); the two differ. */
	color: string;
	/** Cells the player filled: on the shared board, or on the player's own in a race. */
	filled: number;
	/** Wrong numbers so far. */
	misses: number;
	/** How long the player still waits after a wrong number, in milliseconds. */
	lockMs: number;
}

export interface NumberPlaceCell {
	cell: number;
	digit: number;
	/** Who filled it. */
	by: string;
}

export interface NumberPlaceRoom {
	id: string;
	mode: NumberPlaceMode;
	/** The puzzle's difficulty, 1 to 4, chosen by the host. */
	level: number;
	status: NumberPlaceStatus;
	/** Who sent the invitation. */
	host: NumberPlacePlayer;
	/** Who was invited. */
	guest: NumberPlacePlayer;
	/** The caller's role. */
	you: NumberPlaceRole;
	/** The board theme (looks.ts), chosen by the host. */
	theme: string;
	/** Whether the guest has said it is ready; the host starts the game once it has. */
	guestReady: boolean;
	/** The puzzle, 81 digits with 0 for the cells to fill; null until the game starts. */
	givens: string | null;
	/** The cells filled so far: on the shared board, or on the caller's own board in a race. */
	cells: NumberPlaceCell[];
	/** host, guest or draw once a game against each other is over; null in cooperation. */
	winner: NumberPlaceRole | 'draw' | null;
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

export interface NumberPlaceMove {
	/** Whether the number was right: a right one fills the cell, a wrong one makes the player wait. */
	correct: boolean;
	room: NumberPlaceRoom;
}

/** What a room's topic message carries (see numberplace.graphqls). */
export interface NumberPlaceMessage {
	roomId: string;
	type: 'invited' | 'accepted' | 'changed' | 'ready' | 'started' | 'declined' | 'left' | 'cancelled' |
		'fill' | 'miss' | 'resigned' | 'expired';
	by: string;
	ready?: boolean;
	/** fill: the board filled (shared, host or guest), the cell and the digit (not in a race) and how many the player filled. */
	board?: string;
	cell?: number;
	digit?: number;
	filled?: number;
	/** miss: the player's wrong numbers and how long it waits. */
	misses?: number;
	lockMs?: number;
	over?: boolean;
	winner?: NumberPlaceRole | 'draw' | null;
}

/** The topic pattern that covers every room of the user. */
export const NUMBERPLACE_TOPICS = 'game/numberplace/rooms/*';

const PLAYER_FIELDS = 'id displayName color filled misses lockMs';
const ROOM_FIELDS = `
	id mode level status you theme guestReady givens winner resignedBy
	createdAt startedAt finishedAt elapsedMs topic chatFileId
	host { ${PLAYER_FIELDS} }
	guest { ${PLAYER_FIELDS} }
	cells { cell digit by }
`;

export function isNumberPlaceMessage(value: unknown): value is NumberPlaceMessage {
	const m = value as NumberPlaceMessage | null;
	return !!m && typeof m === 'object' && typeof m.roomId === 'string' && typeof m.type === 'string';
}

export class NumberPlaceServiceGraphQL {
	#client: GraphQLClient;

	constructor(client: GraphQLClient) {
		this.#client = client;
	}

	/** The user's rooms: waiting, getting ready or playing first, then the ended ones, newest first. */
	async listRooms(): Promise<NumberPlaceRoom[]> {
		const data = await this.#client.query<{ numberplaceRooms: NumberPlaceRoom[] }>(
			`query { numberplaceRooms { ${ROOM_FIELDS} } }`);
		return data.numberplaceRooms;
	}

	async getRoom(id: string): Promise<NumberPlaceRoom> {
		const data = await this.#client.query<{ numberplaceRoom: NumberPlaceRoom }>(
			`query ($id: ID!) { numberplaceRoom(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.numberplaceRoom;
	}

	/**
	 * Invites a user to cooperate or to play against the caller. `level`,
	 * `color` and `theme` are the caller's first choices (changed later with
	 * `setup`). The invitation is also posted as a card in the direct
	 * messages of the two users; `locale` is the language its text is
	 * written in.
	 */
	async invite(opponentId: string, mode: NumberPlaceMode, level: number,
		color?: string, theme?: string, locale?: string): Promise<NumberPlaceRoom> {
		const data = await this.#client.mutation<{ numberplaceInvite: NumberPlaceRoom }>(
			`mutation ($opponentId: ID!, $mode: String!, $level: Int, $color: String, $theme: String, $locale: String) {
				numberplaceInvite(opponentId: $opponentId, mode: $mode, level: $level, color: $color, theme: $theme, locale: $locale) { ${ROOM_FIELDS} }
			}`,
			{ opponentId, mode, level, color: color ?? null, theme: theme ?? null, locale: locale ?? null });
		return data.numberplaceInvite;
	}

	/** Accepts an invitation: the room is a lobby. `color` is the caller's first choice, unless it is the host's. */
	async accept(id: string, color?: string): Promise<NumberPlaceRoom> {
		const data = await this.#client.mutation<{ numberplaceAccept: NumberPlaceRoom }>(
			`mutation ($id: ID!, $color: String) { numberplaceAccept(id: $id, color: $color) { ${ROOM_FIELDS} } }`,
			{ id, color: color ?? null });
		return data.numberplaceAccept;
	}

	/** Changes the caller's colour and, for the host, the difficulty and the board, while the room is being set up. */
	async setup(id: string, changes: { color?: string; level?: number; theme?: string }): Promise<NumberPlaceRoom> {
		const data = await this.#client.mutation<{ numberplaceSetup: NumberPlaceRoom }>(
			`mutation ($id: ID!, $color: String, $level: Int, $theme: String) {
				numberplaceSetup(id: $id, color: $color, level: $level, theme: $theme) { ${ROOM_FIELDS} }
			}`,
			{ id, color: changes.color ?? null, level: changes.level ?? null, theme: changes.theme ?? null });
		return data.numberplaceSetup;
	}

	/** The invited user says it is ready, or no longer is. */
	async ready(id: string, ready: boolean): Promise<NumberPlaceRoom> {
		const data = await this.#client.mutation<{ numberplaceReady: NumberPlaceRoom }>(
			`mutation ($id: ID!, $ready: Boolean) { numberplaceReady(id: $id, ready: $ready) { ${ROOM_FIELDS} } }`,
			{ id, ready });
		return data.numberplaceReady;
	}

	/** The host starts the game with the puzzle it made; the server checks the puzzle has a single solution. */
	async start(id: string, givens: string): Promise<NumberPlaceRoom> {
		const data = await this.#client.mutation<{ numberplaceStart: NumberPlaceRoom }>(
			`mutation ($id: ID!, $givens: String!) { numberplaceStart(id: $id, givens: $givens) { ${ROOM_FIELDS} } }`,
			{ id, givens });
		return data.numberplaceStart;
	}

	/** Declines an invitation or leaves a lobby, or takes back one's own invitation. */
	async decline(id: string): Promise<NumberPlaceRoom> {
		const data = await this.#client.mutation<{ numberplaceDecline: NumberPlaceRoom }>(
			`mutation ($id: ID!) { numberplaceDecline(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.numberplaceDecline;
	}

	/** Writes a number in a cell. */
	async play(id: string, cell: number, digit: number): Promise<NumberPlaceMove> {
		const data = await this.#client.mutation<{ numberplacePlay: NumberPlaceMove }>(
			`mutation ($id: ID!, $cell: Int!, $digit: Int!) {
				numberplacePlay(id: $id, cell: $cell, digit: $digit) { correct room { ${ROOM_FIELDS} } }
			}`,
			{ id, cell, digit });
		return data.numberplacePlay;
	}

	/** Gives the game up: against each other the other player wins; in cooperation the game ends unsolved. */
	async resign(id: string): Promise<NumberPlaceRoom> {
		const data = await this.#client.mutation<{ numberplaceResign: NumberPlaceRoom }>(
			`mutation ($id: ID!) { numberplaceResign(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.numberplaceResign;
	}
}

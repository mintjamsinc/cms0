/**
 * Reversi service: games between two users, through the Reversi GraphQL
 * schema (/etc/graphql/webtop/reversi). A room lives in the workspace it was
 * opened in, so the service works on the endpoint of the client it is given.
 *
 * The rules are applied on the server; the app sends a move and is told what
 * happened. Changes arrive as topic messages on `ReversiRoom.topic`: the app
 * watches `game/reversi/rooms/*` once (EventHub.watchTopic) and receives the
 * messages of every room it plays in.
 */

import type { GraphQLClient } from '../graphql/client.js';

export type ReversiStatus = 'waiting' | 'lobby' | 'playing' | 'finished' | 'declined' | 'cancelled';
export type ReversiSide = 'black' | 'white';

export interface ReversiPlayer {
	id: string;
	displayName: string | null;
}

export interface ReversiRoom {
	id: string;
	size: number;
	status: ReversiStatus;
	black: ReversiPlayer;
	white: ReversiPlayer;
	/** Who sent the invitation. */
	host: string;
	yourSide: ReversiSide;
	/** The disc face of each player (looks.ts); the two differ. */
	blackFace: string;
	whiteFace: string;
	/** The board theme (looks.ts), chosen by the host. */
	theme: string;
	/** Whether the invited user has said it is ready; the host starts the game once it has. */
	guestReady: boolean;
	/** Coordinate notation (d3); passes are not recorded. */
	moves: string[];
	/** black, white or draw, once finished. */
	winner: ReversiSide | 'draw' | null;
	resignedBy: string | null;
	createdAt: string;
	startedAt: string | null;
	finishedAt: string | null;
	topic: string;
}

/** What a room's topic message carries (see reversi.graphqls). */
export interface ReversiMessage {
	roomId: string;
	type: 'invited' | 'accepted' | 'changed' | 'ready' | 'started' | 'declined' | 'cancelled' | 'move' | 'resigned' | 'expired';
	by: string;
	ready?: boolean;
	ply?: number;
	move?: string;
	over?: boolean;
	winner?: ReversiSide | 'draw';
}

/** The topic pattern that covers every room of the user. */
export const REVERSI_TOPICS = 'game/reversi/rooms/*';

const ROOM_FIELDS = `
	id size status host yourSide blackFace whiteFace theme guestReady
	moves winner resignedBy createdAt startedAt finishedAt topic
	black { id displayName }
	white { id displayName }
`;

export function isReversiMessage(value: unknown): value is ReversiMessage {
	const m = value as ReversiMessage | null;
	return !!m && typeof m === 'object' && typeof m.roomId === 'string' && typeof m.type === 'string';
}

export class ReversiServiceGraphQL {
	#client: GraphQLClient;

	constructor(client: GraphQLClient) {
		this.#client = client;
	}

	/** The user's rooms: waiting, getting ready or playing first, then the ended ones, newest first. */
	async listRooms(): Promise<ReversiRoom[]> {
		const data = await this.#client.query<{ reversiRooms: ReversiRoom[] }>(
			`query { reversiRooms { ${ROOM_FIELDS} } }`);
		return data.reversiRooms;
	}

	async getRoom(id: string): Promise<ReversiRoom> {
		const data = await this.#client.query<{ reversiRoom: ReversiRoom }>(
			`query ($id: ID!) { reversiRoom(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.reversiRoom;
	}

	/**
	 * Invites a user; `side` is the caller's, `random` by default. `face` and
	 * `theme` are the caller's first choices (changed later with `setup`).
	 */
	async invite(opponentId: string, side: ReversiSide | 'random' = 'random', size = 8,
		face?: string, theme?: string): Promise<ReversiRoom> {
		const data = await this.#client.mutation<{ reversiInvite: ReversiRoom }>(
			`mutation ($opponentId: ID!, $side: String, $size: Int, $face: String, $theme: String) {
				reversiInvite(opponentId: $opponentId, side: $side, size: $size, face: $face, theme: $theme) { ${ROOM_FIELDS} }
			}`,
			{ opponentId, side, size, face: face ?? null, theme: theme ?? null });
		return data.reversiInvite;
	}

	/** Accepts an invitation: the room is a lobby. `face` is the caller's first choice, unless it is the host's. */
	async accept(id: string, face?: string): Promise<ReversiRoom> {
		const data = await this.#client.mutation<{ reversiAccept: ReversiRoom }>(
			`mutation ($id: ID!, $face: String) { reversiAccept(id: $id, face: $face) { ${ROOM_FIELDS} } }`,
			{ id, face: face ?? null });
		return data.reversiAccept;
	}

	/** Changes the caller's disc and, for the host, the board, while the room is waiting or a lobby. */
	async setup(id: string, face?: string, theme?: string): Promise<ReversiRoom> {
		const data = await this.#client.mutation<{ reversiSetup: ReversiRoom }>(
			`mutation ($id: ID!, $face: String, $theme: String) {
				reversiSetup(id: $id, face: $face, theme: $theme) { ${ROOM_FIELDS} }
			}`,
			{ id, face: face ?? null, theme: theme ?? null });
		return data.reversiSetup;
	}

	/** The invited user says it is ready, or no longer is. */
	async ready(id: string, ready: boolean): Promise<ReversiRoom> {
		const data = await this.#client.mutation<{ reversiReady: ReversiRoom }>(
			`mutation ($id: ID!, $ready: Boolean) { reversiReady(id: $id, ready: $ready) { ${ROOM_FIELDS} } }`,
			{ id, ready });
		return data.reversiReady;
	}

	/** The host starts the game once the invited user is ready. */
	async start(id: string): Promise<ReversiRoom> {
		const data = await this.#client.mutation<{ reversiStart: ReversiRoom }>(
			`mutation ($id: ID!) { reversiStart(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.reversiStart;
	}

	/** Declines an invitation or leaves a lobby, or takes back one's own invitation. */
	async decline(id: string): Promise<ReversiRoom> {
		const data = await this.#client.mutation<{ reversiDecline: ReversiRoom }>(
			`mutation ($id: ID!) { reversiDecline(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.reversiDecline;
	}

	/** Plays a move; `ply` is how many moves the caller has seen. */
	async play(id: string, ply: number, move: string): Promise<ReversiRoom> {
		const data = await this.#client.mutation<{ reversiPlay: ReversiRoom }>(
			`mutation ($id: ID!, $ply: Int!, $move: String!) {
				reversiPlay(id: $id, ply: $ply, move: $move) { ${ROOM_FIELDS} }
			}`,
			{ id, ply, move });
		return data.reversiPlay;
	}

	async resign(id: string): Promise<ReversiRoom> {
		const data = await this.#client.mutation<{ reversiResign: ReversiRoom }>(
			`mutation ($id: ID!) { reversiResign(id: $id) { ${ROOM_FIELDS} } }`, { id });
		return data.reversiResign;
	}
}

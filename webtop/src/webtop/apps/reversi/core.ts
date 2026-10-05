/**
 * Reversi rules, independent of the screen.
 *
 * A game is its board size plus the list of moves in coordinate notation
 * ("d3": column letter, row number). Passes are not recorded: a player
 * with no legal move passes automatically, so replaying the moves from the
 * initial position always gives the same game. That record is what a saved
 * game, an undo and (later) a game against another user exchange.
 */

export type Player = 1 | 2;
export type Cell = 0 | Player;

/** Black moves first. */
export const BLACK: Player = 1;
export const WHITE: Player = 2;
export const EMPTY = 0;

export const DIRECTIONS: readonly [number, number][] = [
	[-1, -1], [-1, 0], [-1, 1],
	[0, -1], [0, 1],
	[1, -1], [1, 0], [1, 1],
];

export interface Position {
	size: number;
	/** Row-major, index = row * size + column. */
	cells: Cell[];
	/** The player to move; meaningless once over is true. */
	turn: Player;
	over: boolean;
}

export interface MoveResult {
	position: Position;
	player: Player;
	index: number;
	/** Discs turned over, nearest first along each line. */
	flipped: number[];
	/** The player after `player` had no legal move and was skipped. */
	passed: Player | null;
}

export function opponent(p: Player): Player {
	return p === BLACK ? WHITE : BLACK;
}

export function initialPosition(size = 8): Position {
	if (size < 4 || size % 2 !== 0 || size > 26) throw new Error(`Unsupported board size: ${size}`);
	const cells: Cell[] = new Array(size * size).fill(EMPTY);
	const m = size / 2;
	cells[(m - 1) * size + (m - 1)] = WHITE;
	cells[(m - 1) * size + m] = BLACK;
	cells[m * size + (m - 1)] = BLACK;
	cells[m * size + m] = WHITE;
	return { size, cells, turn: BLACK, over: false };
}

/** Discs `player` would turn over by playing at `index` (empty when illegal). */
export function flipsFor(cells: readonly Cell[], size: number, index: number, player: Player): number[] {
	if (cells[index] !== EMPTY) return [];
	const o = opponent(player);
	const r0 = Math.floor(index / size);
	const c0 = index % size;
	const result: number[] = [];
	for (const [dr, dc] of DIRECTIONS) {
		let r = r0 + dr;
		let c = c0 + dc;
		const line: number[] = [];
		while (r >= 0 && r < size && c >= 0 && c < size && cells[r * size + c] === o) {
			line.push(r * size + c);
			r += dr;
			c += dc;
		}
		if (line.length && r >= 0 && r < size && c >= 0 && c < size && cells[r * size + c] === player) {
			result.push(...line);
		}
	}
	return result;
}

export function legalMoves(position: Position, player: Player = position.turn): number[] {
	const { cells, size } = position;
	const moves: number[] = [];
	for (let i = 0; i < cells.length; i++) {
		if (cells[i] === EMPTY && flipsFor(cells, size, i, player).length) moves.push(i);
	}
	return moves;
}

/** Plays a legal move and works out whose turn it is next (passing when needed). */
export function applyMove(position: Position, index: number): MoveResult {
	if (position.over) throw new Error('The game is over');
	const player = position.turn;
	const flipped = flipsFor(position.cells, position.size, index, player);
	if (!flipped.length) throw new Error(`Illegal move: ${toCoord(index, position.size)}`);
	const cells = position.cells.slice();
	cells[index] = player;
	for (const i of flipped) cells[i] = player;

	const next: Position = { size: position.size, cells, turn: opponent(player), over: false };
	let passed: Player | null = null;
	if (!legalMoves(next).length) {
		if (legalMoves(next, player).length) {
			passed = next.turn;
			next.turn = player;
		} else {
			next.over = true;
		}
	}
	return { position: next, player, index, flipped, passed };
}

export function countDiscs(cells: readonly Cell[]): { black: number; white: number; empty: number } {
	let black = 0;
	let white = 0;
	for (const c of cells) {
		if (c === BLACK) black++;
		else if (c === WHITE) white++;
	}
	return { black, white, empty: cells.length - black - white };
}

/** The winner, or 0 for a draw. Only meaningful once the game is over. */
export function winner(position: Position): Cell {
	const n = countDiscs(position.cells);
	if (n.black === n.white) return EMPTY;
	return n.black > n.white ? BLACK : WHITE;
}

/** "d3" for column 3, row 2 (zero-based). */
export function toCoord(index: number, size: number): string {
	return String.fromCharCode(97 + (index % size)) + (Math.floor(index / size) + 1);
}

export function fromCoord(coord: string, size: number): number {
	const m = /^([a-z])(\d{1,2})$/.exec(coord.trim().toLowerCase());
	if (!m) throw new Error(`Bad coordinate: ${coord}`);
	const col = m[1].charCodeAt(0) - 97;
	const row = Number(m[2]) - 1;
	if (col >= size || row < 0 || row >= size) throw new Error(`Off the board: ${coord}`);
	return row * size + col;
}

/**
 * A game in progress: the record plus the position after every move, so an
 * undo is a step back rather than a replay.
 */
export class ReversiGame {
	readonly size: number;
	readonly moves: string[] = [];
	private readonly history: Position[];

	constructor(size = 8) {
		this.size = size;
		this.history = [initialPosition(size)];
	}

	/** Rebuilds a game from its record; throws on an illegal record. */
	static replay(size: number, moves: readonly string[]): ReversiGame {
		const game = new ReversiGame(size);
		for (const m of moves) game.play(fromCoord(m, size));
		return game;
	}

	get position(): Position {
		return this.history[this.history.length - 1];
	}

	get ply(): number {
		return this.moves.length;
	}

	play(index: number): MoveResult {
		const result = applyMove(this.position, index);
		this.history.push(result.position);
		this.moves.push(toCoord(index, this.size));
		return result;
	}

	/** Takes back the last `count` moves (no further than the start). */
	undo(count = 1): void {
		const n = Math.min(count, this.moves.length);
		this.history.length -= n;
		this.moves.length -= n;
	}

	/** Who played move number `ply` (zero-based). */
	playerAt(ply: number): Player {
		return this.history[ply].turn;
	}
}

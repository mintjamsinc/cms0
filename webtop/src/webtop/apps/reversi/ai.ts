/**
 * Computer player: alpha-beta search over the board with a time limit.
 *
 * Runs in ai-worker.ts so a long think never freezes the window; app.ts
 * falls back to calling chooseMove directly if the worker cannot start.
 * The levels differ in how far ahead they read, whether they count
 * mobility (the number of moves each side has), how many empty squares are
 * left when they start reading the ending out exactly, and how much
 * randomness they add to their choice at the root.
 */

import { type Player } from './core.js';

export type AiLevel = 1 | 2 | 3 | 4;

export interface AiLevelSpec {
	/** Deepest look-ahead in moves. */
	maxDepth: number;
	/** Time budget for one move. */
	timeMs: number;
	/** Read the game out exactly once this few squares are empty (0 = never). */
	solveEmpties: number;
	/** Random amount added to each root move's score; larger plays looser. */
	noise: number;
	/** Count mobility in the evaluation, not only square values. */
	mobility: boolean;
}

export const AI_LEVELS: Record<AiLevel, AiLevelSpec> = {
	1: { maxDepth: 1, timeMs: 150, solveEmpties: 0, noise: 70, mobility: false },
	2: { maxDepth: 2, timeMs: 300, solveEmpties: 0, noise: 14, mobility: false },
	3: { maxDepth: 5, timeMs: 800, solveEmpties: 10, noise: 3, mobility: true },
	4: { maxDepth: 12, timeMs: 1500, solveEmpties: 14, noise: 0, mobility: true },
};

export interface AiRequest {
	size: number;
	cells: number[];
	player: Player;
	level: AiLevel;
}

export interface AiResult {
	/** Board index, or -1 when the player has no legal move. */
	move: number;
	/** Search score from the player's side (exact disc margin when solved). */
	score: number;
	depth: number;
	nodes: number;
	solved: boolean;
}

const WIN = 10000;
const INF = 1 << 30;
const ABORT = new Error('search time is up');

interface CornerGroup {
	corner: number;
	/** The X and C squares next to the corner, risky while it is empty. */
	near: number[];
}

/** Square values: corners high, the squares that give corners away low. */
function squareWeights(size: number): { weights: Int16Array; corners: CornerGroup[] } {
	const n = size * size;
	const weights = new Int16Array(n);
	const last = size - 1;
	const edge = (v: number) => v === 0 || v === last;
	const nearEdge = (v: number) => v === 1 || v === last - 1;
	for (let r = 0; r < size; r++) {
		for (let c = 0; c < size; c++) {
			let w: number;
			if (edge(r) && edge(c)) w = 100;
			else if (nearEdge(r) && nearEdge(c)) w = -40;
			else if ((edge(r) && nearEdge(c)) || (nearEdge(r) && edge(c))) w = -15;
			else if (edge(r) || edge(c)) w = 8;
			else if (nearEdge(r) || nearEdge(c)) w = -3;
			else w = 1;
			weights[r * size + c] = w;
		}
	}
	const corners: CornerGroup[] = [];
	for (const [r, c, dr, dc] of [[0, 0, 1, 1], [0, last, 1, -1], [last, 0, -1, 1], [last, last, -1, -1]]) {
		corners.push({
			corner: r * size + c,
			near: [(r + dr) * size + (c + dc), r * size + (c + dc), (r + dr) * size + c],
		});
	}
	return { weights, corners };
}

/** For every square, the lines leading outwards from it (squares in order). */
function buildRays(size: number): number[][][] {
	const rays: number[][][] = [];
	const dirs = [[-1, -1], [-1, 0], [-1, 1], [0, -1], [0, 1], [1, -1], [1, 0], [1, 1]];
	for (let i = 0; i < size * size; i++) {
		const r0 = Math.floor(i / size);
		const c0 = i % size;
		const list: number[][] = [];
		for (const [dr, dc] of dirs) {
			const ray: number[] = [];
			for (let r = r0 + dr, c = c0 + dc; r >= 0 && r < size && c >= 0 && c < size; r += dr, c += dc) {
				ray.push(r * size + c);
			}
			// A line needs an opponent disc and then one of ours: two squares at least.
			if (ray.length >= 2) list.push(ray);
		}
		rays.push(list);
	}
	return rays;
}

class Search {
	private readonly cells: Int8Array;
	private readonly rays: number[][][];
	private readonly weights: Int16Array;
	private readonly corners: CornerGroup[];
	/** Squares in the order moves are tried: best square values first. */
	private readonly order: number[];
	private readonly flipStack: Int16Array;
	private sp = 0;
	private deadline = 0;
	private solving = false;
	nodes = 0;

	constructor(size: number, cells: readonly number[], private readonly spec: AiLevelSpec) {
		this.cells = Int8Array.from(cells);
		this.rays = buildRays(size);
		const { weights, corners } = squareWeights(size);
		this.weights = weights;
		this.corners = corners;
		this.order = Array.from(this.cells.keys()).sort((a, b) => weights[b] - weights[a]);
		// One move flips fewer than (size - 2) * 8 discs and a line of play is at
		// most size * size moves long.
		this.flipStack = new Int16Array(size * size * size * 8);
	}

	get empties(): number {
		let n = 0;
		for (let i = 0; i < this.cells.length; i++) if (this.cells[i] === 0) n++;
		return n;
	}

	run(player: Player): AiResult {
		const started = performance.now();
		this.deadline = started + this.spec.timeMs;
		const snapshot = this.cells.slice();
		const restore = () => {
			this.cells.set(snapshot);
			this.sp = 0;
		};

		const moves = this.moves(player);
		if (!moves.length) return { move: -1, score: 0, depth: 0, nodes: 0, solved: false };
		let best: AiResult = { move: moves[0], score: 0, depth: 0, nodes: 0, solved: false };
		if (moves.length === 1) return best;

		const empties = this.empties;
		const solve = this.spec.solveEmpties > 0 && empties <= this.spec.solveEmpties;
		const maxDepth = Math.min(this.spec.maxDepth, empties, solve ? 4 : Infinity);
		try {
			for (let depth = 1; depth <= maxDepth; depth++) {
				const r = this.root(player, depth, moves, false);
				best = { ...r, depth, nodes: this.nodes, solved: false };
				// Try the best move first at the next depth.
				moves.splice(moves.indexOf(r.move), 1);
				moves.unshift(r.move);
			}
			if (solve) {
				const r = this.root(player, empties, moves, true);
				best = { ...r, depth: empties, nodes: this.nodes, solved: true };
			}
		} catch (e) {
			if (e !== ABORT) throw e;
			restore();
		}
		best.nodes = this.nodes;
		return best;
	}

	private root(player: Player, depth: number, moves: number[], solving: boolean): { move: number; score: number } {
		this.solving = solving;
		const noise = solving ? 0 : this.spec.noise;
		let bestMove = moves[0];
		let bestScore = -INF;
		let alpha = -INF;
		for (const m of moves) {
			const f = this.play(m, player);
			// With noise every move needs its true score, so no window narrowing.
			const v = -this.negamax(opponentOf(player), depth - 1, -INF, noise ? INF : -alpha, false);
			this.undo(m, f, player);
			const s = noise ? v + Math.random() * noise : v;
			if (s > bestScore) {
				bestScore = s;
				bestMove = m;
			}
			if (v > alpha) alpha = v;
		}
		return { move: bestMove, score: Math.round(bestScore) };
	}

	private negamax(p: Player, depth: number, alpha: number, beta: number, passed: boolean): number {
		if ((++this.nodes & 1023) === 0 && performance.now() > this.deadline) throw ABORT;
		if (depth <= 0) return this.solving ? this.final(p) : this.evaluate(p);
		const moves = this.moves(p);
		if (!moves.length) {
			if (passed) return this.final(p);
			return -this.negamax(opponentOf(p), depth, -beta, -alpha, true);
		}
		let best = -INF;
		for (const m of moves) {
			const f = this.play(m, p);
			const v = -this.negamax(opponentOf(p), depth - 1, -beta, -alpha, false);
			this.undo(m, f, p);
			if (v > best) best = v;
			if (v > alpha) alpha = v;
			if (alpha >= beta) break;
		}
		return best;
	}

	/** Game over: a win outranks any heuristic score, by the disc margin. */
	private final(p: Player): number {
		const o = opponentOf(p);
		let diff = 0;
		for (let i = 0; i < this.cells.length; i++) {
			if (this.cells[i] === p) diff++;
			else if (this.cells[i] === o) diff--;
		}
		return diff > 0 ? WIN + diff : diff < 0 ? -WIN + diff : 0;
	}

	private evaluate(p: Player): number {
		const o = opponentOf(p);
		const cells = this.cells;
		const w = this.weights;
		let score = 0;
		let mine = 0;
		let theirs = 0;
		for (let i = 0; i < cells.length; i++) {
			const v = cells[i];
			if (v === p) {
				score += w[i];
				mine++;
			} else if (v === o) {
				score -= w[i];
				theirs++;
			}
		}
		// Once a corner is taken its X and C squares stop being a liability.
		for (const g of this.corners) {
			if (cells[g.corner] === 0) continue;
			for (const i of g.near) {
				if (cells[i] === p) score -= w[i];
				else if (cells[i] === o) score += w[i];
			}
		}
		if (this.spec.mobility) {
			score += 6 * (this.mobility(p) - this.mobility(o));
			const empties = cells.length - mine - theirs;
			if (empties < cells.length / 5) score += 3 * (mine - theirs);
		}
		return score;
	}

	private mobility(p: Player): number {
		let n = 0;
		for (let i = 0; i < this.cells.length; i++) if (this.canPlay(i, p)) n++;
		return n;
	}

	private moves(p: Player): number[] {
		const list: number[] = [];
		for (const i of this.order) if (this.canPlay(i, p)) list.push(i);
		return list;
	}

	private canPlay(i: number, p: Player): boolean {
		const cells = this.cells;
		if (cells[i] !== 0) return false;
		const o = opponentOf(p);
		for (const ray of this.rays[i]) {
			if (cells[ray[0]] !== o) continue;
			for (let k = 1; k < ray.length; k++) {
				const v = cells[ray[k]];
				if (v === o) continue;
				if (v === p) return true;
				break;
			}
		}
		return false;
	}

	/** Plays at i; returns how many discs flipped (they go on the flip stack). */
	private play(i: number, p: Player): number {
		const cells = this.cells;
		const o = opponentOf(p);
		let total = 0;
		for (const ray of this.rays[i]) {
			if (cells[ray[0]] !== o) continue;
			let k = 1;
			while (k < ray.length && cells[ray[k]] === o) k++;
			if (k < ray.length && cells[ray[k]] === p) {
				for (let j = 0; j < k; j++) {
					cells[ray[j]] = p;
					this.flipStack[this.sp++] = ray[j];
				}
				total += k;
			}
		}
		cells[i] = p;
		return total;
	}

	private undo(i: number, flipped: number, p: Player): void {
		const o = opponentOf(p);
		this.cells[i] = 0;
		for (let j = 0; j < flipped; j++) this.cells[this.flipStack[--this.sp]] = o;
	}
}

function opponentOf(p: Player): Player {
	return p === 1 ? 2 : 1;
}

export function chooseMove(request: AiRequest): AiResult {
	const spec = AI_LEVELS[request.level] || AI_LEVELS[2];
	return new Search(request.size, request.cells, spec).run(request.player);
}

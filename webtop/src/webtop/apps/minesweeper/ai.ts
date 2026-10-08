/**
 * The computer opponent.
 *
 * The computer plays by reasoning, as a person would: each step it asks the
 * solver (core.ts `deduce`) what the board it plays tells for sure, and
 * opens a safe cell or, in territory, takes a mine it found. What makes it
 * a fair opponent is its pace. It looks near where it played last, and
 * takes a while for each step: more when it took two numbers at once to
 * see it, more for a weaker level. Now and then it is wrong (it opens a
 * mine, or takes a cell that holds none) and, like a person, has to wait.
 *
 * The boards are made to be solved without guessing, so the solver always
 * has an answer; should it ever have none, the computer guesses.
 */

import { COVERED, deduce, neighborTable, type Random } from './core.js';

/** 1 chick, 2 songbird, 3 owl, 4 hawk. */
export type AiLevel = 1 | 2 | 3 | 4;

export const AI_LEVELS: AiLevel[] = [1, 2, 3, 4];

/** Milliseconds per step, before the adjustments. */
const PACE: Record<AiLevel, number> = { 1: 4200, 2: 2600, 3: 1600, 4: 950 };
/** How often a step is wrong. */
const MISS: Record<AiLevel, number> = { 1: 0.06, 2: 0.035, 3: 0.018, 4: 0.006 };
/** How much longer a step takes when it needs two numbers at once. */
const HARD = 1.7;
/** Among the cells it could play, the computer picks one of the nearest few. */
const NEAREST = 3;

export interface AiBoard {
	width: number;
	height: number;
	/** What the computer sees of the board it plays (core.ts values). */
	view: readonly number[];
	/** The cells the computer knows to be mines (its own findings, mines taken, exploded). */
	known: readonly boolean[];
	/** Where the mines are: the computer only looks when it gets a step wrong. */
	mines: readonly number[];
	totalMines: number;
	/** Territory: the computer takes the mines it finds. */
	claims: boolean;
	/** The cell it played last, or -1. */
	last: number;
}

export interface AiStep {
	action: 'open' | 'claim';
	cell: number;
	/** The step is wrong: a mine opened, or a cell taken that holds none. */
	miss: boolean;
	/** Mines the computer found on the way, to remember. */
	found: number[];
	/** How long the computer takes before it plays this step, in milliseconds. */
	delay: number;
}

/** The computer's next step, or null when nothing is left to play. */
export function nextStep(board: AiBoard, level: AiLevel, random: Random = Math.random): AiStep | null {
	const { width, height, view, mines, totalMines, claims } = board;
	const known = board.known.slice();
	const found: number[] = [];
	let hard = false;
	let safe: number[] = [];
	let sure: number[] = [];
	// Mines found while racing are only remembered: the computer looks again with them.
	for (;;) {
		const d = deduce(width, height, view, known, totalMines);
		hard = hard || d.hard;
		safe = d.safe;
		sure = d.mines;
		if (claims || safe.length || !sure.length) break;
		for (const j of sure) {
			known[j] = true;
			found.push(j);
		}
	}
	const covered: number[] = [];
	for (let i = 0; i < view.length; i++) if (view[i] === COVERED && !known[i]) covered.push(i);
	if (!covered.length && !(claims && sure.length)) return null;

	const delay = PACE[level] * (hard ? HARD : 1) * (0.6 + 0.8 * random());
	const near = (cells: number[]) => nearest(width, cells, board.last, random);
	if (random() < MISS[level]) {
		// A wrong step: a mine next to what is open, or a safe cell taken for one.
		const wrong = covered.filter(i => (claims ? !mines[i] : !!mines[i]) && touchesOpen(width, height, view, i));
		if (wrong.length) {
			return { action: claims ? 'claim' : 'open', cell: near(wrong), miss: true, found, delay };
		}
	}
	if (claims && sure.length) {
		return { action: 'claim', cell: near(sure), miss: false, found, delay };
	}
	if (safe.length) {
		return { action: 'open', cell: near(safe), miss: false, found, delay };
	}
	// Nothing is sure: a guess, away from the numbers when possible.
	const cell = covered[Math.floor(random() * covered.length)];
	return { action: 'open', cell, miss: !!mines[cell], found, delay: delay * HARD };
}

/** One of the few cells nearest to `from` (any of them when there is no `from`). */
function nearest(width: number, cells: number[], from: number, random: Random): number {
	if (from < 0) return cells[Math.floor(random() * cells.length)];
	const r0 = Math.floor(from / width);
	const c0 = from % width;
	const ranked = cells
		.map(i => ({ i, d: Math.max(Math.abs(Math.floor(i / width) - r0), Math.abs((i % width) - c0)) }))
		.sort((a, b) => a.d - b.d)
		.slice(0, NEAREST);
	return ranked[Math.floor(random() * ranked.length)].i;
}

function touchesOpen(width: number, height: number, view: readonly number[], i: number): boolean {
	return neighborTable(width, height)[i].some(j => view[j] >= 0 && view[j] <= 8);
}

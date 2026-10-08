/**
 * The computer opponent.
 *
 * The computer knows the solution; what makes it a fair opponent is its
 * pace. It fills, one at a time, a cell a person would see next (one with
 * the fewest candidates left on the board it plays), and takes a while for
 * each: less when the cell has a single candidate, more on a harder puzzle,
 * more for a weaker level. Now and then it gets a number wrong and, like a
 * person, has to wait before it can play again.
 */

import { CELLS, candidates, type Level, type Random } from './core.js';

/** 1 chick, 2 songbird, 3 owl, 4 hawk. */
export type AiLevel = 1 | 2 | 3 | 4;

export const AI_LEVELS: AiLevel[] = [1, 2, 3, 4];

/** Milliseconds per cell, before the adjustments. */
const PACE: Record<AiLevel, number> = { 1: 15000, 2: 9500, 3: 6000, 4: 3800 };
/** How often a number is wrong. */
const MISS: Record<AiLevel, number> = { 1: 0.12, 2: 0.08, 3: 0.05, 4: 0.02 };
/** A harder puzzle takes longer for the computer too. */
const PUZZLE: Record<Level, number> = { 1: 0.7, 2: 0.9, 3: 1.2, 4: 1.5 };

export interface AiStep {
	cell: number;
	digit: number;
	/** The number is wrong: the computer waits instead of filling the cell. */
	miss: boolean;
	/** How long the computer takes before it plays this step, in milliseconds. */
	delay: number;
}

/**
 * The computer's next step on `cells` (the board it plays: its own, or the
 * shared one), or null when nothing is left to fill.
 */
export function nextStep(cells: readonly number[], solution: readonly number[], level: AiLevel, puzzle: Level, random: Random = Math.random): AiStep | null {
	let fewest = 10;
	let choices: number[] = [];
	for (let i = 0; i < CELLS; i++) {
		if (cells[i]) continue;
		let n = 0;
		for (let m = candidates(cells, i); m; m &= m - 1) n++;
		if (n < fewest) {
			fewest = n;
			choices = [i];
		} else if (n === fewest) {
			choices.push(i);
		}
	}
	if (!choices.length) return null;
	const cell = choices[Math.floor(random() * choices.length)];
	const hard = fewest <= 1 ? 0.8 : 1 + 0.25 * (fewest - 1);
	const delay = PACE[level] * PUZZLE[puzzle] * hard * (0.6 + 0.8 * random());
	const miss = random() < MISS[level];
	let digit = solution[cell];
	if (miss) {
		const wrong = [1, 2, 3, 4, 5, 6, 7, 8, 9].filter(d => d !== solution[cell]);
		digit = wrong[Math.floor(random() * wrong.length)];
	}
	return { cell, digit, miss, delay };
}

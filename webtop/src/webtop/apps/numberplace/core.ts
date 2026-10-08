/**
 * Number Place rules, independent of the screen.
 *
 * A grid is 81 numbers in row-major order (index = row * 9 + column), 0 for
 * an empty cell. A puzzle is its givens, written as 81 digits with 0 for the
 * cells to fill ("530070000…"); every puzzle made here has exactly one
 * solution, so the solution follows from the givens. The same text is what
 * a saved game and a room of the server exchange.
 *
 * The solver counts solutions (a puzzle is valid when it has exactly one),
 * the rater solves a puzzle the way a person would, to tell how hard it is,
 * and the generator removes numbers from a random solved grid until the
 * puzzle has the asked difficulty. The server checks a puzzle with its own
 * solver (NumberPlaceRules.groovy), which must agree with this one.
 */

/** 1 easy, 2 medium, 3 hard, 4 expert. */
export type Level = 1 | 2 | 3 | 4;

export const LEVELS: Level[] = [1, 2, 3, 4];
export const CELLS = 81;
/** No puzzle with fewer givens has a single solution. */
export const MIN_GIVENS = 17;

const ROW: number[] = [];
const COL: number[] = [];
const BOX: number[] = [];
for (let i = 0; i < CELLS; i++) {
	const r = Math.floor(i / 9);
	const c = i % 9;
	ROW.push(r);
	COL.push(c);
	BOX.push(Math.floor(r / 3) * 3 + Math.floor(c / 3));
}

/** The 27 units: the rows, then the columns, then the boxes. */
export const UNITS: number[][] = [];
for (let u = 0; u < 9; u++) UNITS.push(Array.from({ length: 9 }, (_, k) => u * 9 + k));
for (let u = 0; u < 9; u++) UNITS.push(Array.from({ length: 9 }, (_, k) => k * 9 + u));
for (let u = 0; u < 9; u++) {
	const r0 = Math.floor(u / 3) * 3;
	const c0 = (u % 3) * 3;
	UNITS.push(Array.from({ length: 9 }, (_, k) => (r0 + Math.floor(k / 3)) * 9 + c0 + (k % 3)));
}

/** The cells of each box, box by box: the order the board is drawn in. */
export const BOX_CELLS: number[][] = UNITS.slice(18);

/** The 20 cells that share a row, a column or a box with each cell. */
export const PEERS: number[][] = Array.from({ length: CELLS }, (_, i) => {
	const set = new Set<number>();
	for (const unit of [UNITS[ROW[i]], UNITS[9 + COL[i]], UNITS[18 + BOX[i]]]) {
		for (const j of unit) if (j !== i) set.add(j);
	}
	return [...set];
});

/** The units (row, column, box) a cell belongs to. */
export function unitsOf(i: number): number[][] {
	return [UNITS[ROW[i]], UNITS[9 + COL[i]], UNITS[18 + BOX[i]]];
}

export function rowOf(i: number): number {
	return ROW[i];
}

export function colOf(i: number): number {
	return COL[i];
}

export function boxOf(i: number): number {
	return BOX[i];
}

/** Bits set in a 9-bit mask. */
const POP = new Uint8Array(512);
for (let m = 1; m < 512; m++) POP[m] = POP[m >> 1] + (m & 1);

const ALL = 511;

function bitOf(digit: number): number {
	return 1 << (digit - 1);
}

function digitOf(bit: number): number {
	return 32 - Math.clz32(bit);
}

/** The digits of a mask, smallest first. */
export function digitsOf(mask: number): number[] {
	const out: number[] = [];
	for (let d = 1; d <= 9; d++) if (mask & bitOf(d)) out.push(d);
	return out;
}

/** Reads 81 digits (0 or "." for an empty cell); throws on anything else. */
export function parseGrid(text: string): number[] {
	const s = String(text || '').trim();
	if (!/^[0-9.]{81}$/.test(s)) throw new Error('A grid is 81 digits.');
	return Array.from(s, ch => ch === '.' ? 0 : Number(ch));
}

export function formatGrid(cells: readonly number[]): string {
	return cells.map(v => (v >= 1 && v <= 9) ? String(v) : '0').join('');
}

/** The digits that can still go in cell `i` (a mask), whatever is there now. */
export function candidates(cells: readonly number[], i: number): number {
	let used = 0;
	for (const p of PEERS[i]) if (cells[p]) used |= bitOf(cells[p]);
	return ALL & ~used;
}

/** The cells whose number is repeated in one of their units. */
export function conflicts(cells: readonly number[]): Set<number> {
	const out = new Set<number>();
	for (const unit of UNITS) {
		const seen = new Map<number, number>();
		for (const i of unit) {
			const v = cells[i];
			if (!v) continue;
			const first = seen.get(v);
			if (first === undefined) {
				seen.set(v, i);
			} else {
				out.add(first);
				out.add(i);
			}
		}
	}
	return out;
}

export interface SolveResult {
	/** Solutions found, up to the limit asked. */
	count: number;
	/** The first solution found, or null. */
	solution: number[] | null;
}

/**
 * Counts the solutions of a grid, stopping at `limit`. A grid whose givens
 * already clash has none. `order` picks the order the digits are tried in
 * (a random one fills an empty grid at random).
 */
export function solve(cells: readonly number[], limit = 2, order?: () => number[]): SolveResult {
	const grid = cells.slice();
	const rows = new Array(9).fill(0);
	const cols = new Array(9).fill(0);
	const boxes = new Array(9).fill(0);
	const empties: number[] = [];
	for (let i = 0; i < CELLS; i++) {
		const v = grid[i];
		if (!v) {
			empties.push(i);
			continue;
		}
		const bit = bitOf(v);
		if ((rows[ROW[i]] | cols[COL[i]] | boxes[BOX[i]]) & bit) return { count: 0, solution: null };
		rows[ROW[i]] |= bit;
		cols[COL[i]] |= bit;
		boxes[BOX[i]] |= bit;
	}
	let count = 0;
	let solution: number[] | null = null;

	const search = (k: number): boolean => {
		if (k === empties.length) {
			count++;
			if (!solution) solution = grid.slice();
			return count >= limit;
		}
		// The cell with the fewest choices left is tried first.
		let best = k;
		let bestMask = 0;
		let bestCount = 10;
		for (let j = k; j < empties.length; j++) {
			const i = empties[j];
			const m = ALL & ~(rows[ROW[i]] | cols[COL[i]] | boxes[BOX[i]]);
			const n = POP[m];
			if (n < bestCount) {
				best = j;
				bestMask = m;
				bestCount = n;
				if (n <= 1) break;
			}
		}
		if (bestCount === 0) return false;
		const tmp = empties[k];
		empties[k] = empties[best];
		empties[best] = tmp;
		const i = empties[k];
		const r = ROW[i];
		const c = COL[i];
		const b = BOX[i];
		const tries = order ? order().filter(d => bestMask & bitOf(d)) : digitsOf(bestMask);
		for (const d of tries) {
			const bit = bitOf(d);
			grid[i] = d;
			rows[r] |= bit;
			cols[c] |= bit;
			boxes[b] |= bit;
			const stop = search(k + 1);
			rows[r] &= ~bit;
			cols[c] &= ~bit;
			boxes[b] &= ~bit;
			grid[i] = 0;
			if (stop) return true;
		}
		return false;
	};
	search(0);
	return { count, solution };
}

/** Whether the grid has exactly one solution. */
export function isUnique(cells: readonly number[]): boolean {
	return solve(cells, 2).count === 1;
}

/**
 * How hard a puzzle is for a person, by the hardest step needed to solve
 * it: 1 when singles are enough (a cell with one candidate, or a digit with
 * one place in a unit), 2 when locked candidates or pairs are needed too,
 * 3 when even those are not enough.
 */
export function rate(givens: readonly number[]): number {
	const cells = givens.slice();
	const cand = new Array(CELLS).fill(0);
	for (let i = 0; i < CELLS; i++) if (!cells[i]) cand[i] = candidates(cells, i);
	let hardest = 1;

	const place = (i: number, d: number) => {
		cells[i] = d;
		cand[i] = 0;
		const bit = bitOf(d);
		for (const p of PEERS[i]) cand[p] &= ~bit;
	};

	const singles = (): boolean => {
		let progress = false;
		for (let i = 0; i < CELLS; i++) {
			if (!cells[i] && POP[cand[i]] === 1) {
				place(i, digitOf(cand[i]));
				progress = true;
			}
		}
		if (progress) return true;
		for (const unit of UNITS) {
			for (let d = 1; d <= 9; d++) {
				const bit = bitOf(d);
				let at = -1;
				let n = 0;
				for (const i of unit) {
					if (!cells[i] && (cand[i] & bit)) {
						at = i;
						n++;
					}
				}
				if (n === 1) {
					place(at, d);
					progress = true;
				}
			}
		}
		return progress;
	};

	/** Removes `bit` from the cells of `unit` not in `keep`; true when one changed. */
	const removeFrom = (unit: number[], keep: (i: number) => boolean, bits: number): boolean => {
		let changed = false;
		for (const i of unit) {
			if (!cells[i] && !keep(i) && (cand[i] & bits)) {
				cand[i] &= ~bits;
				changed = true;
			}
		}
		return changed;
	};

	const lockedAndPairs = (): boolean => {
		let changed = false;
		// Locked candidates: a digit confined to one line of a box, or to one box of a line.
		for (let u = 0; u < 27; u++) {
			const unit = UNITS[u];
			for (let d = 1; d <= 9; d++) {
				const bit = bitOf(d);
				const at = unit.filter(i => !cells[i] && (cand[i] & bit));
				if (at.length < 2) continue;
				if (u >= 18) {
					if (at.every(i => ROW[i] === ROW[at[0]])) changed = removeFrom(UNITS[ROW[at[0]]], i => BOX[i] === u - 18, bit) || changed;
					if (at.every(i => COL[i] === COL[at[0]])) changed = removeFrom(UNITS[9 + COL[at[0]]], i => BOX[i] === u - 18, bit) || changed;
				} else if (at.every(i => BOX[i] === BOX[at[0]])) {
					changed = removeFrom(UNITS[18 + BOX[at[0]]], i => unit.includes(i), bit) || changed;
				}
			}
		}
		if (changed) return true;
		for (const unit of UNITS) {
			const open = unit.filter(i => !cells[i]);
			// Naked pairs: two cells with the same two candidates.
			for (let a = 0; a < open.length; a++) {
				const m = cand[open[a]];
				if (POP[m] !== 2) continue;
				for (let b = a + 1; b < open.length; b++) {
					if (cand[open[b]] === m) {
						changed = removeFrom(open, i => i === open[a] || i === open[b], m) || changed;
					}
				}
			}
			// Hidden pairs: two digits with the same two places.
			const places: number[][] = [];
			for (let d = 1; d <= 9; d++) places[d] = open.filter(i => cand[i] & bitOf(d));
			for (let d = 1; d <= 9; d++) {
				if (places[d].length !== 2) continue;
				for (let e = d + 1; e <= 9; e++) {
					const p = places[e];
					if (p.length === 2 && p[0] === places[d][0] && p[1] === places[d][1]) {
						const keep = bitOf(d) | bitOf(e);
						for (const i of p) {
							if (cand[i] & ~keep) {
								cand[i] &= keep;
								changed = true;
							}
						}
					}
				}
			}
		}
		return changed;
	};

	for (;;) {
		if (cells.every(v => v)) return hardest;
		if (singles()) continue;
		if (lockedAndPairs()) {
			hardest = 2;
			continue;
		}
		return 3;
	}
}

export interface Puzzle {
	/** 81 digits, 0 for the cells to fill. */
	givens: string;
	solution: string;
	level: Level;
}

/** A random number in [0, 1), as Math.random. */
export type Random = () => number;

function shuffled<T>(items: T[], random: Random): T[] {
	const a = items.slice();
	for (let i = a.length - 1; i > 0; i--) {
		const j = Math.floor(random() * (i + 1));
		const t = a[i];
		a[i] = a[j];
		a[j] = t;
	}
	return a;
}

/** A random solved grid. */
export function randomSolution(random: Random = Math.random): number[] {
	const digits = [1, 2, 3, 4, 5, 6, 7, 8, 9];
	const result = solve(new Array(CELLS).fill(0), 1, () => shuffled(digits, random));
	return result.solution as number[];
}

/** Givens kept at least, per level: easy puzzles stop removing early. */
const KEEP: Record<Level, number> = { 1: 38, 2: 32, 3: MIN_GIVENS, 4: MIN_GIVENS };
/** The rating each level asks for. */
const RATING: Record<Level, number> = { 1: 1, 2: 1, 3: 2, 4: 3 };
const ATTEMPTS: Record<Level, number> = { 1: 10, 2: 10, 3: 30, 4: 60 };

/**
 * A puzzle of the given level with a single solution. Numbers are removed
 * in pairs facing each other through the centre, so the givens look
 * balanced. A hard rating cannot always be found quickly: after a number of
 * attempts the closest puzzle found is taken.
 */
export function generate(level: Level, random: Random = Math.random): Puzzle {
	let best: { givens: number[]; solution: number[]; rating: number } | null = null;
	for (let attempt = 0; attempt < ATTEMPTS[level]; attempt++) {
		const solution = randomSolution(random);
		const givens = solution.slice();
		let left = CELLS;
		for (const i of shuffled(Array.from({ length: 41 }, (_, k) => k), random)) {
			const j = CELLS - 1 - i;
			const n = i === j ? 1 : 2;
			if (left - n < KEEP[level]) continue;
			const a = givens[i];
			const b = givens[j];
			givens[i] = 0;
			givens[j] = 0;
			if (isUnique(givens)) {
				left -= n;
			} else {
				givens[i] = a;
				givens[j] = b;
			}
		}
		const rating = rate(givens);
		if (rating === RATING[level]) {
			return { givens: formatGrid(givens), solution: formatGrid(solution), level };
		}
		if (!best || Math.abs(rating - RATING[level]) < Math.abs(best.rating - RATING[level])) {
			best = { givens, solution, rating };
		}
	}
	return { givens: formatGrid(best!.givens), solution: formatGrid(best!.solution), level };
}

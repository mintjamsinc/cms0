/**
 * Minesweeper rules, independent of the screen.
 *
 * A board is `width` by `height` cells in row-major order (index = row *
 * width + column) with a number of mines, written as a layout: one
 * character per cell, '1' for a mine and '0' for none. Every board has a
 * start cell, opened when the game begins: it has no mine around it, so it
 * opens a whole area.
 *
 * Every board made here can be solved without guessing: from the start,
 * the solver (`deduce`) always finds a cell that is surely safe or surely a
 * mine, until every safe cell is open. That keeps a race fair (nobody wins
 * on a lucky guess) and lets the computer play by reasoning, as a person
 * would. The generator places mines at random and keeps the first layout
 * the solver clears. With another user the server makes the board instead
 * (MinesweeperRules.groovy, with the same sizes and the same solver), so
 * that neither player's app ever knows where the mines are.
 *
 * What a player sees of a cell is a number: COVERED, a count of the mines
 * around it (0 to 8), EXPLODED (a mine that was opened) or MINE (a mine
 * shown once the game is over). The same text form ('-', '0'-'8', '*',
 * 'm') is what a saved game and a room of the server exchange.
 */

/** 1 easy, 2 medium, 3 hard, 4 expert. */
export type Level = 1 | 2 | 3 | 4;

export const LEVELS: Level[] = [1, 2, 3, 4];

export interface Size {
	width: number;
	height: number;
	mines: number;
}

/** The boards of each difficulty (the server's MinesweeperRules.SIZES too). */
export const SIZES: Record<Level, Size> = {
	1: { width: 9, height: 9, mines: 10 },
	2: { width: 12, height: 12, mines: 22 },
	3: { width: 16, height: 16, mines: 40 },
	4: { width: 24, height: 16, mines: 72 },
};

export const COVERED = -1;
/** A mine that was opened. */
export const EXPLODED = 9;
/** A mine shown once the game is over. */
export const MINE = 10;

export interface Board {
	level: Level;
	width: number;
	height: number;
	mines: number;
	/** One character per cell: '1' a mine, '0' none. */
	layout: string;
	/** The cell opened when the game begins; no mine touches it. */
	start: number;
}

export type Random = () => number;

const neighborCache = new Map<string, number[][]>();

/** The cells around each cell (up to eight), for a board of that size. */
export function neighborTable(width: number, height: number): number[][] {
	const key = `${width}x${height}`;
	let table = neighborCache.get(key);
	if (table) return table;
	table = [];
	for (let i = 0; i < width * height; i++) {
		const r = Math.floor(i / width);
		const c = i % width;
		const list: number[] = [];
		for (let dr = -1; dr <= 1; dr++) {
			for (let dc = -1; dc <= 1; dc++) {
				if (!dr && !dc) continue;
				const rr = r + dr;
				const cc = c + dc;
				if (rr >= 0 && rr < height && cc >= 0 && cc < width) list.push(rr * width + cc);
			}
		}
		table.push(list);
	}
	neighborCache.set(key, table);
	return table;
}

/** Reads a layout of that many cells into 1 (mine) and 0; throws on anything else. */
export function parseLayout(text: string, cells: number): number[] {
	if (typeof text !== 'string' || text.length !== cells || !/^[01]+$/.test(text)) {
		throw new Error(`A layout is ${cells} characters of 0 and 1`);
	}
	return Array.from(text, ch => (ch === '1' ? 1 : 0));
}

export function formatLayout(mines: readonly number[]): string {
	return mines.map(m => (m ? '1' : '0')).join('');
}

/** How many mines touch each cell. */
export function countsOf(width: number, height: number, mines: readonly number[]): number[] {
	const nb = neighborTable(width, height);
	return mines.map((_, i) => nb[i].reduce((n, j) => n + (mines[j] ? 1 : 0), 0));
}

/** Reads what a player sees ('-', '0'-'8', '*', 'm'); throws on anything else. */
export function parseView(text: string, cells: number): number[] {
	if (typeof text !== 'string' || text.length !== cells || !/^[-0-8*m]+$/.test(text)) {
		throw new Error(`A board is ${cells} cells`);
	}
	return Array.from(text, ch => (ch === '-' ? COVERED : ch === '*' ? EXPLODED : ch === 'm' ? MINE : Number(ch)));
}

export function formatView(view: readonly number[]): string {
	return view.map(v => (v === COVERED ? '-' : v === EXPLODED ? '*' : v === MINE ? 'm' : String(v))).join('');
}

/**
 * Opens a safe cell on `view` and, when it has no mine around it, the
 * cells around it, spreading through every such cell. Returns the cells it
 * opened (none when the cell was open already). `view` is changed in place.
 */
export function flood(width: number, height: number, counts: readonly number[], view: number[], start: number): number[] {
	if (view[start] !== COVERED) return [];
	const nb = neighborTable(width, height);
	const opened: number[] = [];
	const stack = [start];
	view[start] = counts[start];
	opened.push(start);
	while (stack.length) {
		const i = stack.pop()!;
		if (counts[i] !== 0) continue;
		for (const j of nb[i]) {
			if (view[j] !== COVERED) continue;
			view[j] = counts[j];
			opened.push(j);
			stack.push(j);
		}
	}
	return opened;
}

/** How many cells of a view are open (a count, not a mine). */
export function openCount(view: readonly number[]): number {
	let n = 0;
	for (const v of view) if (v >= 0 && v <= 8) n++;
	return n;
}

export interface Deduction {
	/** Covered cells that surely hold no mine. */
	safe: number[];
	/** Covered cells that surely hold a mine (not counting those already known). */
	mines: number[];
	/** Whether it took more than one number at a time to see it. */
	hard: boolean;
}

interface Constraint {
	cells: number[];
	need: number;
}

/**
 * What can be told for sure from what a player sees: the covered cells
 * that are safe and those that are mines. `known` marks the cells known to
 * be mines besides the exploded ones (the solver's own findings, or mines
 * taken in territory); flags a player put down are not trusted.
 *
 * Three rules, the first that tells something wins:
 *   1. one number: its covered cells are all safe (its mines are all
 *      known) or all mines (it needs every one of them);
 *   2. two numbers that share cells: when the second needs as many more
 *      mines than the first as it has cells of its own, those are mines
 *      and the cells only the first has are safe (this covers one number's
 *      cells lying inside another's);
 *   3. the count of mines: none left means every covered cell is safe; as
 *      many left as covered cells means they are all mines.
 */
export function deduce(width: number, height: number, view: readonly number[], known: readonly boolean[], totalMines: number): Deduction {
	const nb = neighborTable(width, height);
	const cells = width * height;
	const isMine = (j: number) => !!known[j] || view[j] === EXPLODED || view[j] === MINE;
	const safe = new Set<number>();
	const mines = new Set<number>();
	const constraints: Constraint[] = [];
	for (let i = 0; i < cells; i++) {
		const v = view[i];
		if (v < 0 || v > 8) continue;
		let need = v;
		const around: number[] = [];
		for (const j of nb[i]) {
			if (isMine(j)) need--;
			else if (view[j] === COVERED) around.push(j);
		}
		if (!around.length) continue;
		if (need <= 0) {
			for (const j of around) safe.add(j);
		} else if (need >= around.length) {
			for (const j of around) mines.add(j);
		} else {
			constraints.push({ cells: around, need });
		}
	}
	if (safe.size || mines.size) return { safe: [...safe], mines: [...mines], hard: false };

	// Two numbers at a time, among those that share a cell.
	const byCell = new Map<number, number[]>();
	constraints.forEach((c, k) => {
		for (const cell of c.cells) {
			const list = byCell.get(cell);
			if (list) list.push(k);
			else byCell.set(cell, [k]);
		}
	});
	constraints.forEach((a, ka) => {
		const others = new Set<number>();
		for (const cell of a.cells) for (const kb of byCell.get(cell)!) if (kb !== ka) others.add(kb);
		const inA = new Set(a.cells);
		for (const kb of others) {
			const b = constraints[kb];
			const onlyB = b.cells.filter(j => !inA.has(j));
			if (b.need - a.need !== onlyB.length) continue;
			const inB = new Set(b.cells);
			for (const j of onlyB) mines.add(j);
			for (const j of a.cells) if (!inB.has(j)) safe.add(j);
		}
	});
	if (safe.size || mines.size) return { safe: [...safe], mines: [...mines], hard: true };

	// The count of mines.
	let knownMines = 0;
	const unknown: number[] = [];
	for (let j = 0; j < cells; j++) {
		if (isMine(j)) knownMines++;
		else if (view[j] === COVERED) unknown.push(j);
	}
	const left = totalMines - knownMines;
	if (unknown.length && left <= 0) return { safe: unknown, mines: [], hard: true };
	if (unknown.length && left === unknown.length) return { safe: [], mines: unknown, hard: true };
	return { safe: [], mines: [], hard: false };
}

/** Whether the board can be cleared from its start by reasoning alone. */
export function solvable(width: number, height: number, mines: readonly number[], start: number): boolean {
	const cells = width * height;
	const counts = countsOf(width, height, mines);
	const total = mines.reduce((n, m) => n + m, 0);
	const view: number[] = new Array(cells).fill(COVERED);
	const known: boolean[] = new Array(cells).fill(false);
	flood(width, height, counts, view, start);
	let open = openCount(view);
	while (open < cells - total) {
		const d = deduce(width, height, view, known, total);
		if (!d.safe.length && !d.mines.length) return false;
		for (const j of d.mines) known[j] = true;
		for (const j of d.safe) {
			if (mines[j]) return false;
			open += flood(width, height, counts, view, j).length;
		}
	}
	return true;
}

/** How many layouts are tried before the generator gives up. */
const MAX_TRIES = 20000;

/**
 * A new board of the given difficulty that can be cleared without
 * guessing. The start is a cell away from the edge when the board allows,
 * and neither it nor the cells around it hold a mine.
 */
export function generate(level: Level, random: Random = Math.random): Board {
	const { width, height, mines: count } = SIZES[level];
	const cells = width * height;
	const nb = neighborTable(width, height);
	const inner: number[] = [];
	for (let i = 0; i < cells; i++) if (nb[i].length === 8) inner.push(i);
	for (let attempt = 0; attempt < MAX_TRIES; attempt++) {
		const start = inner[Math.floor(random() * inner.length)];
		const keep = new Set([start, ...nb[start]]);
		const free: number[] = [];
		for (let i = 0; i < cells; i++) if (!keep.has(i)) free.push(i);
		// The first `count` cells of a partial shuffle are the mines.
		for (let k = 0; k < count; k++) {
			const j = k + Math.floor(random() * (free.length - k));
			const tmp = free[k];
			free[k] = free[j];
			free[j] = tmp;
		}
		const mines: number[] = new Array(cells).fill(0);
		for (let k = 0; k < count; k++) mines[free[k]] = 1;
		if (solvable(width, height, mines, start)) {
			return { level, width, height, mines: count, layout: formatLayout(mines), start };
		}
	}
	throw new Error('No board could be made');
}

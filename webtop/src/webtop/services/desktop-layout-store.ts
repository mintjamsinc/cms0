// Where the user's desktop icons sit.
//
// The icons of the Desktop folder are laid out in one of two ways: `auto`
// flows them in name order (the default), `manual` keeps each icon where the
// user dropped it. A position is a cell of a grid, not a point in pixels, so
// the layout survives a change of window size: a cell is one icon's footprint
// (see the .desktop-icon rules in assets/css/style.css).
//
// The layout is kept in the user's preferences (category `desktop`) as one
// JSON string property, like widget placements (widget-store.ts), so it
// follows the user to every browser and arrives live through
// `preferenceChanged`.

export type DesktopLayoutMode = 'auto' | 'manual';

export interface DesktopCell {
	col: number;
	row: number;
}

export interface DesktopLayout {
	mode: DesktopLayoutMode;
	/** Node id → the cell the user put it in. Only kept in manual mode. */
	positions: Record<string, DesktopCell>;
}

/** The grid an icon layout is laid out on: how many cells fit the desktop. */
export interface DesktopGrid {
	cols: number;
	rows: number;
	/** False while the desktop has no size yet (not laid out); nothing is persisted then. */
	measured: boolean;
}

const CATEGORY = 'desktop';
const PROPERTY = 'iconLayout';
const SAVE_DELAY_MS = 500;
/** Beyond this a stored cell is taken as corrupt and ignored. */
const MAX_CELL = 10000;

/**
 * Grid geometry in rem. Must agree with the CSS: an icon is 6rem wide with a
 * 0.5rem gap, the container has 0.75rem padding, and an icon is at most
 * 5.05rem tall, so a 5.5rem row pitch leaves the same gap below it.
 */
export const DESKTOP_GRID = {
	padding: 0.75,
	gap: 0.5,
	cellWidth: 6.5,
	cellHeight: 5.5,
};

/** The rem size in pixels, which the grid constants are expressed in. */
export function remPixels(): number {
	try {
		const px = parseFloat(getComputedStyle(document.documentElement).fontSize);
		if (Number.isFinite(px) && px > 0) return px;
	} catch { /* fall through */ }
	return 16;
}

/** How many cells fit an area of the given size (the same count the flow layout gets per row). */
export function gridFor(widthPx: number, heightPx: number, remPx: number): DesktopGrid {
	const g = DESKTOP_GRID;
	const usableW = widthPx / remPx - 2 * g.padding + g.gap;
	const usableH = heightPx / remPx - 2 * g.padding + g.gap;
	return {
		cols: Math.max(1, Math.floor(usableW / g.cellWidth)),
		rows: Math.max(1, Math.floor(usableH / g.cellHeight)),
		measured: widthPx > 0 && heightPx > 0,
	};
}

/** The cell whose top-left corner is nearest to a point in desktop pixels. */
export function cellAt(xPx: number, yPx: number, remPx: number): DesktopCell {
	const g = DESKTOP_GRID;
	return {
		col: Math.max(0, Math.round((xPx / remPx - g.padding) / g.cellWidth)),
		row: Math.max(0, Math.round((yPx / remPx - g.padding) / g.cellHeight)),
	};
}

function key(c: DesktopCell): string {
	return `${c.col}:${c.row}`;
}

function inGrid(c: DesktopCell, grid: DesktopGrid): boolean {
	return c.col < grid.cols && c.row < grid.rows;
}

/**
 * The first free cell going down each column then right, the way a desktop
 * fills from its top-left corner. Columns past the grid's edge are used when
 * the grid is full; such icons sit off-screen until there is room again.
 */
export function firstFreeCell(taken: Set<string>, grid: DesktopGrid): DesktopCell {
	for (let col = 0; ; col++) {
		for (let row = 0; row < grid.rows; row++) {
			const c = { col, row };
			if (!taken.has(key(c))) return c;
		}
	}
}

/**
 * The free cell nearest to a wanted one, inside the grid; the wanted cell
 * itself when it is free. Falls back to the first free cell when every cell
 * around is taken.
 */
export function nearestFreeCell(wanted: DesktopCell, taken: Set<string>, grid: DesktopGrid): DesktopCell {
	const target = {
		col: Math.min(wanted.col, grid.cols - 1),
		row: Math.min(wanted.row, grid.rows - 1),
	};
	if (!taken.has(key(target))) return target;
	const maxRadius = Math.max(grid.cols, grid.rows);
	for (let radius = 1; radius <= maxRadius; radius++) {
		let best: DesktopCell | null = null;
		let bestDist = Infinity;
		for (let dc = -radius; dc <= radius; dc++) {
			for (let dr = -radius; dr <= radius; dr++) {
				if (Math.max(Math.abs(dc), Math.abs(dr)) !== radius) continue;
				const c = { col: target.col + dc, row: target.row + dr };
				if (c.col < 0 || c.row < 0 || !inGrid(c, grid)) continue;
				if (taken.has(key(c))) continue;
				const dist = dc * dc + dr * dr;
				if (dist < bestDist) {
					best = c;
					bestDist = dist;
				}
			}
		}
		if (best) return best;
	}
	return firstFreeCell(taken, grid);
}

/** The cells the flow layout (auto mode) shows the icons in: left to right, then down. */
export function flowCells(ids: string[], grid: DesktopGrid): Record<string, DesktopCell> {
	const out: Record<string, DesktopCell> = {};
	ids.forEach((id, i) => {
		out[id] = { col: i % grid.cols, row: Math.floor(i / grid.cols) };
	});
	return out;
}

export interface ComputedLayout {
	/** Every icon's cell for this render. */
	cells: Record<string, DesktopCell>;
	/** Icons that had no stored cell and were given one; to be stored. */
	added: Record<string, DesktopCell>;
}

/**
 * Where each icon goes in manual mode. An icon keeps its stored cell while
 * that cell is inside the grid and not already shown by another icon;
 * otherwise (new file, desktop shrunk, two tabs placed icons at once) it is
 * shown at the first free cell. A stored cell that merely fell outside the
 * grid is kept in the store, so the icon returns there when the desktop grows.
 */
export function computeLayout(ids: string[], positions: Record<string, DesktopCell>, grid: DesktopGrid): ComputedLayout {
	const taken = new Set<string>();
	const cells: Record<string, DesktopCell> = {};
	const added: Record<string, DesktopCell> = {};
	const pending: string[] = [];
	for (const id of ids) {
		const p = positions[id];
		if (p && inGrid(p, grid) && !taken.has(key(p))) {
			cells[id] = { col: p.col, row: p.row };
			taken.add(key(p));
		} else {
			pending.push(id);
		}
	}
	for (const id of pending) {
		const c = firstFreeCell(taken, grid);
		cells[id] = c;
		taken.add(key(c));
		if (!positions[id]) added[id] = c;
	}
	return { cells, added };
}

function parseCell(raw: unknown): DesktopCell | null {
	if (!raw || typeof raw !== 'object') return null;
	const col = (raw as any).col;
	const row = (raw as any).row;
	if (!Number.isInteger(col) || !Number.isInteger(row)) return null;
	if (col < 0 || row < 0 || col > MAX_CELL || row > MAX_CELL) return null;
	return { col, row };
}

export function parseLayout(raw: unknown): DesktopLayout {
	const layout: DesktopLayout = { mode: 'auto', positions: {} };
	if (typeof raw !== 'string' || !raw) return layout;
	let data: any;
	try {
		data = JSON.parse(raw);
	} catch {
		return layout;
	}
	if (!data || typeof data !== 'object') return layout;
	if (data.mode === 'manual') layout.mode = 'manual';
	const positions = data.positions;
	if (positions && typeof positions === 'object' && !Array.isArray(positions)) {
		for (const id of Object.keys(positions)) {
			const cell = parseCell(positions[id]);
			if (cell) layout.positions[id] = cell;
		}
	}
	return layout;
}

export function serializeLayout(layout: DesktopLayout): string {
	const positions: Record<string, DesktopCell> = {};
	for (const id of Object.keys(layout.positions)) {
		const p = layout.positions[id];
		positions[id] = { col: p.col, row: p.row };
	}
	return JSON.stringify({ mode: layout.mode, positions });
}

export class DesktopLayoutStore {
	#api: any;
	#userId: string;
	#timer: ReturnType<typeof setTimeout> | null = null;
	#pending: string | null = null;
	#inFlight = 0;
	#lastWritten: string | null = null;

	/** @param api WebtopAPI */
	constructor(api: any, userId: string) {
		this.#api = api;
		this.#userId = userId;
	}

	/** Whether a save is waiting or on its way, so an incoming change may be stale. */
	get busy(): boolean {
		return this.#pending !== null || this.#inFlight > 0;
	}

	/** Whether the stored value is the one this store last wrote (its own echo). */
	isOwnWrite(raw: unknown): boolean {
		return typeof raw === 'string' && raw === this.#lastWritten;
	}

	/** The property name the layout arrives under in a `preferenceChanged` event. */
	static get property(): string {
		return PROPERTY;
	}

	async load(): Promise<DesktopLayout> {
		try {
			const node = await this.#api.systemContent.getNode(`/home/users/${this.#userId}/preferences/${CATEGORY}`);
			const prop = (node?.properties || []).find((p: any) => p.name === PROPERTY);
			const raw = prop?.propertyValue?.value;
			this.#lastWritten = typeof raw === 'string' ? raw : null;
			return parseLayout(raw);
		} catch {
			return { mode: 'auto', positions: {} };
		}
	}

	/** Save after a short pause; a newer call replaces a waiting one. */
	save(layout: DesktopLayout): void {
		this.#pending = serializeLayout(layout);
		if (this.#timer) clearTimeout(this.#timer);
		this.#timer = setTimeout(() => {
			this.#timer = null;
			this.flush();
		}, SAVE_DELAY_MS);
	}

	async flush(): Promise<void> {
		if (this.#timer) {
			clearTimeout(this.#timer);
			this.#timer = null;
		}
		const value = this.#pending;
		if (value === null) return;
		this.#pending = null;
		this.#inFlight++;
		try {
			this.#lastWritten = value;
			await this.#api.idp.updatePreferences({
				username: this.#userId,
				category: CATEGORY,
				data: { [PROPERTY]: value },
			});
		} catch (e) {
			console.warn('[Webtop] Failed to save desktop icon layout:', e);
		} finally {
			this.#inFlight--;
		}
	}
}

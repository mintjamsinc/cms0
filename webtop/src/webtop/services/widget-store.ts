// Where the user's desktop widgets are placed.
//
// Placements are kept in the user's preferences (category `widgets`) as one
// JSON string property, the same shape content-browser uses, so they follow
// the user to every browser and arrive live through `preferenceChanged`. The
// shell keeps only where a placement is; what a widget shows (a note's text,
// a timer) is the app's own data, keyed by the placement id.
//
// A position is kept as the distance from the nearest corner of the desktop
// area, so a widget put in the top-right corner stays there on a screen of
// another size.
import type { WidgetLayer } from './webtop-service.js';

export type WidgetAnchor = 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right';

export interface WidgetPlacement {
	id: string;
	appId: string;
	widget: string;        // AppWidget.identifier
	layer: WidgetLayer;
	anchor: WidgetAnchor;
	dx: number;
	dy: number;
	width: number;
	height: number;
}

export interface WidgetRect {
	x: number;
	y: number;
	width: number;
	height: number;
}

/** Placements per user. Each is an iframe, so the desktop stays light. */
export const MAX_WIDGET_PLACEMENTS = 20;

const CATEGORY = 'widgets';
const PROPERTY = 'widgetPlacements';
const SAVE_DELAY_MS = 500;
const ANCHORS: WidgetAnchor[] = ['top-left', 'top-right', 'bottom-left', 'bottom-right'];

/** The placement's rectangle in desktop-area pixels, kept inside the area. */
export function placementRect(p: WidgetPlacement, areaWidth: number, areaHeight: number): WidgetRect {
	const width = Math.min(p.width, Math.max(areaWidth, 1));
	const height = Math.min(p.height, Math.max(areaHeight, 1));
	let x = p.anchor.endsWith('left') ? p.dx : areaWidth - width - p.dx;
	let y = p.anchor.startsWith('top') ? p.dy : areaHeight - height - p.dy;
	x = Math.max(0, Math.min(x, areaWidth - width));
	y = Math.max(0, Math.min(y, areaHeight - height));
	return { x, y, width, height };
}

/** The anchor and offsets that put a rectangle where it is, from its nearest corner. */
export function anchorRect(r: WidgetRect, areaWidth: number, areaHeight: number): Pick<WidgetPlacement, 'anchor' | 'dx' | 'dy'> {
	const left = r.x + r.width / 2 < areaWidth / 2;
	const top = r.y + r.height / 2 < areaHeight / 2;
	return {
		anchor: `${top ? 'top' : 'bottom'}-${left ? 'left' : 'right'}` as WidgetAnchor,
		dx: Math.max(0, Math.round(left ? r.x : areaWidth - r.width - r.x)),
		dy: Math.max(0, Math.round(top ? r.y : areaHeight - r.height - r.y)),
	};
}

/** Placements read from the stored JSON; malformed entries are dropped. */
export function parsePlacements(raw: unknown): WidgetPlacement[] {
	if (typeof raw !== 'string' || !raw) return [];
	let list: unknown;
	try {
		list = JSON.parse(raw);
	} catch {
		return [];
	}
	if (!Array.isArray(list)) return [];
	const seen = new Set<string>();
	const out: WidgetPlacement[] = [];
	for (const p of list) {
		if (!p || typeof p !== 'object') continue;
		const { id, appId, widget, layer, anchor, dx, dy, width, height } = p as Record<string, unknown>;
		if (typeof id !== 'string' || !id || seen.has(id)) continue;
		if (typeof appId !== 'string' || typeof widget !== 'string') continue;
		if (![dx, dy, width, height].every((n) => typeof n === 'number' && Number.isFinite(n))) continue;
		seen.add(id);
		out.push({
			id,
			appId,
			widget,
			layer: layer === 'pinned' ? 'pinned' : 'desktop',
			anchor: ANCHORS.includes(anchor as WidgetAnchor) ? anchor as WidgetAnchor : 'top-left',
			dx: Math.max(0, dx as number),
			dy: Math.max(0, dy as number),
			width: Math.max(1, width as number),
			height: Math.max(1, height as number),
		});
		if (out.length >= MAX_WIDGET_PLACEMENTS) break;
	}
	return out;
}

/** Placements as stored: plain objects with the known fields only. */
export function serializePlacements(list: WidgetPlacement[]): string {
	return JSON.stringify(list.map((p) => ({
		id: p.id,
		appId: p.appId,
		widget: p.widget,
		layer: p.layer,
		anchor: p.anchor,
		dx: p.dx,
		dy: p.dy,
		width: p.width,
		height: p.height,
	})));
}

export class WidgetStore {
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

	async load(): Promise<WidgetPlacement[]> {
		try {
			const node = await this.#api.systemContent.getNode(`/home/users/${this.#userId}/preferences/${CATEGORY}`);
			const prop = (node?.properties || []).find((p: any) => p.name === PROPERTY);
			const raw = prop?.propertyValue?.value;
			this.#lastWritten = typeof raw === 'string' ? raw : null;
			return parsePlacements(raw);
		} catch {
			return [];
		}
	}

	/** Save after a short pause; a newer call replaces a waiting one. */
	save(list: WidgetPlacement[]): void {
		this.#pending = serializePlacements(list);
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
			console.warn('[Webtop] Failed to save widget placements:', e);
		} finally {
			this.#inFlight--;
		}
	}
}

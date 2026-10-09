/**
 * Ambient sound mixes: the user's saved combinations of sounds, and what the
 * mixer was playing last.
 *
 * Kept per user in the system workspace, so the mixer and the Pomodoro timer
 * (which plays a saved mix during focus) read the same list:
 *
 *   /home/users/<user>/ambience/library.json
 *
 * Players also tell each other over a BroadcastChannel when they start, so
 * only one of them sounds at a time.
 */
import { SOUNDS, type Levels, type SoundId } from './engine.js';

export interface SavedMix {
	id: string;
	name: string;
	levels: Levels;
}

export interface AmbienceLibrary {
	version: 1;
	updatedAt: string;
	current: Levels;       // the mixer's sliders
	volume: number;        // the mixer's master volume, 0..1
	mixes: SavedMix[];
}

/** Built in, not saved: a name key under app.ambience.mix.<key> and its levels. */
export interface BuiltinMix {
	id: string;            // 'builtin:<key>'
	key: string;
	label: string;         // English fallback
	levels: Levels;
}

export const BUILTIN_MIXES: BuiltinMix[] = [
	{ id: 'builtin:rainy-night', key: 'rainyNight', label: 'Rainy night', levels: { rain: 0.7, thunder: 0.5, brown: 0.25 } },
	{ id: 'builtin:seaside', key: 'seaside', label: 'Seaside', levels: { waves: 0.8, wind: 0.3, birds: 0.2 } },
	{ id: 'builtin:campfire', key: 'campfire', label: 'Campfire', levels: { fire: 0.8, crickets: 0.4, wind: 0.2 } },
	{ id: 'builtin:forest-stream', key: 'forestStream', label: 'Forest stream', levels: { stream: 0.7, birds: 0.5, wind: 0.2 } },
	{ id: 'builtin:focus', key: 'focus', label: 'Focus', levels: { pink: 0.5, rain: 0.3 } },
];

export const MAX_SAVED_MIXES = 30;

/** BroadcastChannel name; messages are AmbienceMessage. */
export const AMBIENCE_CHANNEL = 'webtop-ambience';
export interface AmbienceMessage {
	type: 'playing';
	source: 'ambience' | 'pomodoro';
}

const FILE_NAME = 'library.json';
const SAVE_DELAY_MS = 800;

export function emptyLibrary(): AmbienceLibrary {
	return { version: 1, updatedAt: '', current: { ...BUILTIN_MIXES[0].levels }, volume: 0.8, mixes: [] };
}

/** Only known sounds, each 0..1. */
export function normalizeLevels(value: unknown): Levels {
	const out: Levels = {};
	if (!value || typeof value !== 'object') return out;
	for (const s of SOUNDS) {
		const v = (value as Record<string, unknown>)[s.id];
		if (typeof v === 'number' && Number.isFinite(v) && v > 0) out[s.id as SoundId] = Math.min(1, v);
	}
	return out;
}

export function sameLevels(a: Levels, b: Levels): boolean {
	return SOUNDS.every((s) => Math.abs((a[s.id] || 0) - (b[s.id] || 0)) < 0.005);
}

function normalize(value: unknown): AmbienceLibrary | null {
	if (!value || typeof value !== 'object' || (value as AmbienceLibrary).version !== 1) return null;
	const v = value as AmbienceLibrary;
	return {
		version: 1,
		updatedAt: typeof v.updatedAt === 'string' ? v.updatedAt : '',
		current: normalizeLevels(v.current),
		volume: typeof v.volume === 'number' && Number.isFinite(v.volume) ? Math.max(0, Math.min(1, v.volume)) : 0.8,
		mixes: (Array.isArray(v.mixes) ? v.mixes : [])
			.filter((m) => m && typeof m.id === 'string' && typeof m.name === 'string')
			.slice(0, MAX_SAVED_MIXES)
			.map((m) => ({ id: m.id, name: m.name, levels: normalizeLevels(m.levels) })),
	};
}

export interface AmbienceStoreServices {
	userId: string;
	content: any;          // api.systemContent
}

export class AmbienceStore {
	#services: AmbienceStoreServices;
	#timer: ReturnType<typeof setTimeout> | null = null;
	#pending: AmbienceLibrary | null = null;
	#saving: Promise<void> | null = null;

	constructor(services: AmbienceStoreServices) {
		this.#services = services;
	}

	get folder(): string {
		return `/home/users/${this.#services.userId}/ambience`;
	}

	get path(): string {
		return `${this.folder}/${FILE_NAME}`;
	}

	async load(): Promise<AmbienceLibrary> {
		try {
			const node = await this.#services.content.getNode(this.path);
			if (!node?.downloadUrl) return emptyLibrary();
			const res = await fetch(node.downloadUrl, { cache: 'no-store' });
			return (res.ok && normalize(await res.json())) || emptyLibrary();
		} catch (err) {
			console.warn('[Ambience] The mixes could not be read:', err);
			return emptyLibrary();
		}
	}

	/** Save a moment later; a newer call replaces a waiting one. */
	save(library: AmbienceLibrary): void {
		this.#pending = { ...library, updatedAt: new Date().toISOString() };
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
		if (this.#saving) await this.#saving.catch(() => { /* the save below carries the newer state */ });
		const library = this.#pending;
		if (!library) return;
		this.#pending = null;
		const content = this.#services.content;
		this.#saving = (async () => {
			const upload = await content.initiateMultipartUpload();
			try {
				await content.appendMultipartUploadData(upload.uploadId, JSON.stringify(library, null, '\t'));
				await content.completeMultipartUpload(upload.uploadId, this.folder, FILE_NAME, 'application/json', true);
			} catch (err) {
				await content.abortMultipartUpload(upload.uploadId).catch(() => { /* ignored */ });
				throw err;
			}
		})();
		try {
			await this.#saving;
		} catch (err) {
			console.warn('[Ambience] The mixes could not be saved:', err);
		} finally {
			this.#saving = null;
		}
	}
}

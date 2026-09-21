/**
 * Per-user radio library: favorites, recently played stations, stations the
 * user added by hand, and player settings.
 *
 * Stored as one JSON file at /home/users/<user>/radio/library.json in the
 * system workspace (the user's home, like preferences and wallpapers), so it
 * follows the user across browsers and devices. A copy is kept in the
 * Webtop's IndexedDB as well so the app opens instantly and still has the
 * library when the server cannot be reached; whichever copy is newer wins.
 */

export interface Station {
	/** Directory uuid, or "custom:<uuid>" for stations added by hand. */
	id: string;
	name: string;
	url: string;
	homepage: string;
	favicon: string;
	tags: string[];
	country: string;
	countryCode: string;
	/** Main broadcast language as the directory names it (e.g. "japanese"). */
	language?: string;
	codec: string;
	bitrate: number;
	hls: boolean;
	/** Directory popularity figures at the time the station was listed. */
	votes?: number;
	clicks?: number;
	source: 'directory' | 'custom';
}

export interface RecentEntry {
	station: Station;
	playedAt: string;
}

export interface PlayerSettings {
	volume: number;
	muted: boolean;
	lastStation: Station | null;
}

export interface Library {
	version: 1;
	updatedAt: string;
	favorites: Station[];
	recents: RecentEntry[];
	custom: Station[];
	settings: PlayerSettings;
}

const APP_ID = 'radio';
const SETTING_KEY = 'library';
const FILE_NAME = 'library.json';
const MAX_RECENTS = 30;

export function emptyLibrary(): Library {
	return {
		version: 1,
		updatedAt: new Date(0).toISOString(),
		favorites: [],
		recents: [],
		custom: [],
		settings: { volume: 0.8, muted: false, lastStation: null },
	};
}

export function newCustomStationId(): string {
	return `custom:${crypto.randomUUID()}`;
}

export function pushRecent(library: Library, station: Station): void {
	library.recents = [
		{ station: { ...station, tags: [...station.tags] }, playedAt: new Date().toISOString() },
		...library.recents.filter((r) => r.station.id !== station.id),
	].slice(0, MAX_RECENTS);
}

/** Minimal shape of the services the store needs from the Webtop API. */
export interface LibraryStoreServices {
	userId: string;
	db: {
		getUserSetting(userID: string, appID: string, key: string): Promise<any>;
		setUserSetting(userID: string, appID: string, key: string, value: any): Promise<void>;
	};
	content: {
		getNode(path: string): Promise<{ downloadUrl?: string } | null>;
		initiateMultipartUpload(): Promise<{ uploadId: string }>;
		appendMultipartUploadData(uploadId: string, data: Blob | string): Promise<boolean>;
		completeMultipartUpload(uploadId: string, path: string, name: string, mimeType: string, overwrite?: boolean): Promise<unknown>;
		abortMultipartUpload(uploadId: string): Promise<boolean>;
	};
}

export class LibraryStore {
	#services: LibraryStoreServices;
	#saveTimer: ReturnType<typeof setTimeout> | null = null;
	#pending: Library | null = null;
	#saving: Promise<void> | null = null;
	#waiters: { resolve: () => void; reject: (err: unknown) => void }[] = [];

	constructor(services: LibraryStoreServices) {
		this.#services = services;
	}

	get #folder(): string {
		return `/home/users/${this.#services.userId}/radio`;
	}

	/** Loads the newer of the local and server copies. */
	async load(): Promise<Library> {
		const [local, remote] = await Promise.all([this.#loadLocal(), this.#loadRemote()]);
		const candidates = [local, remote].filter((l): l is Library => !!l);
		if (!candidates.length) {
			return emptyLibrary();
		}
		candidates.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
		return normalize(candidates[0]);
	}

	/**
	 * Schedules a save. Writes are coalesced so a burst of changes (volume
	 * drag, several favorites) becomes one upload; the returned promise
	 * resolves once that upload has finished or failed.
	 */
	save(library: Library): Promise<void> {
		library.updatedAt = new Date().toISOString();
		this.#pending = JSON.parse(JSON.stringify(library));
		if (this.#saveTimer) {
			clearTimeout(this.#saveTimer);
		}
		return new Promise<void>((resolve, reject) => {
			this.#waiters.push({ resolve, reject });
			this.#saveTimer = setTimeout(() => {
				this.#saveTimer = null;
				this.#flush().catch(() => { /* reported to the waiters */ });
			}, 800);
		});
	}

	/** Writes any pending change now (used when the window closes). */
	async flush(): Promise<void> {
		if (this.#saveTimer) {
			clearTimeout(this.#saveTimer);
			this.#saveTimer = null;
		}
		await this.#flush();
	}

	async #flush(): Promise<void> {
		if (this.#saving) {
			await this.#saving.catch(() => { /* the retry below carries the newer state */ });
		}
		const snapshot = this.#pending;
		const waiters = this.#waiters;
		this.#waiters = [];
		if (!snapshot) {
			waiters.forEach((w) => w.resolve());
			return;
		}
		this.#pending = null;
		this.#saving = (async () => {
			try {
				await this.#services.db.setUserSetting(this.#services.userId, APP_ID, SETTING_KEY, snapshot);
			} catch (err) {
				console.warn('[Radio] Local library cache could not be written:', err);
			}
			await this.#saveRemote(snapshot);
		})();
		try {
			await this.#saving;
			waiters.forEach((w) => w.resolve());
		} catch (err) {
			waiters.forEach((w) => w.reject(err));
			throw err;
		} finally {
			this.#saving = null;
		}
	}

	async #loadLocal(): Promise<Library | null> {
		try {
			const value = await this.#services.db.getUserSetting(this.#services.userId, APP_ID, SETTING_KEY);
			return isLibrary(value) ? value : null;
		} catch {
			return null;
		}
	}

	async #loadRemote(): Promise<Library | null> {
		try {
			const node = await this.#services.content.getNode(`${this.#folder}/${FILE_NAME}`);
			if (!node?.downloadUrl) {
				return null;
			}
			const res = await fetch(node.downloadUrl, { cache: 'no-store' });
			if (!res.ok) {
				return null;
			}
			const value = await res.json();
			return isLibrary(value) ? value : null;
		} catch (err) {
			console.warn('[Radio] Library could not be read from the server:', err);
			return null;
		}
	}

	async #saveRemote(library: Library): Promise<void> {
		const content = this.#services.content;
		const upload = await content.initiateMultipartUpload();
		try {
			await content.appendMultipartUploadData(upload.uploadId, JSON.stringify(library, null, '\t'));
			await content.completeMultipartUpload(upload.uploadId, this.#folder, FILE_NAME, 'application/json', true);
		} catch (err) {
			await content.abortMultipartUpload(upload.uploadId).catch(() => { /* ignored */ });
			throw err;
		}
	}
}

function isLibrary(value: unknown): value is Library {
	return !!value && typeof value === 'object' && (value as Library).version === 1 &&
		typeof (value as Library).updatedAt === 'string';
}

function normalize(library: Library): Library {
	const base = emptyLibrary();
	return {
		...base,
		...library,
		favorites: Array.isArray(library.favorites) ? library.favorites : [],
		recents: Array.isArray(library.recents) ? library.recents.slice(0, MAX_RECENTS) : [],
		custom: Array.isArray(library.custom) ? library.custom : [],
		settings: { ...base.settings, ...(library.settings || {}) },
	};
}

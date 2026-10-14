import { WebtopDatabase } from './webtop-database.js';

const APP_ID = 'webtop';
const WALLPAPER_CURRENT_KEY = 'wallpaper.current';

export class WallpaperManager {
	#api;
	#userID;

	/** @param {WebtopAPI} api */
	constructor(api) {
		this.#api = api;
		this.#userID = this.#getUserID();
	}

	#getUserID() {
		return this.#api.context?.currentUser?.id || '*';
	}

	async getCurrentWallpaperID() {
		return await this.#api.db.getUserSetting(this.#userID, APP_ID, WALLPAPER_CURRENT_KEY);
	}

	async setCurrentWallpaperID(id) {
		await this.#api.db.setUserSetting(this.#userID, APP_ID, WALLPAPER_CURRENT_KEY, id);
	}

	async getWallpaperByID(id) {
		const userKey = WebtopDatabase.buildKey(this.#userID, APP_ID, id);
		let record = await this.#api.db.get('wallpapers', userKey);
		if (!record) {
			// Fallback to shared wallpaper
			const sharedKey = WebtopDatabase.buildKey('*', APP_ID, id);
			record = await this.#api.db.get('wallpapers', sharedKey);
		}
		return record;
	}

	async getCurrentWallpaper() {
		const id = await this.getCurrentWallpaperID();
		if (!id) {
			return null;
		}
		return await this.getWallpaperByID(id);
	}

	/**
	 * Ensure a wallpaper is registered in IndexedDB.
	 * If not present, fetches node info from the system workspace and saves it.
	 * Used by the preferences subscription handler when a wallpaper uploaded on
	 * another device arrives via SSE.
	 */
	async ensureWallpaperRegistered(filename: string, userID?: string): Promise<void> {
		const uid = userID || this.#userID;
		const key = WebtopDatabase.buildKey(uid, APP_ID, filename);
		const existing = await this.#api.db.get('wallpapers', key);
		if (existing?.url) return;

		const path = `/home/users/${uid}/wallpapers/${filename}`;
		const node = await this.#api.systemContent.getNode(path);
		if (!node?.downloadUrl) return;

		const timestamp = node.modified ? new Date(node.modified).getTime() : Date.now();
		await this.#api.db.put('wallpapers', {
			id: key,
			userID: uid,
			url: node.downloadUrl,
			timestamp,
		});
	}

	async removeWallpaper(id: string): Promise<void> {
		const key = WebtopDatabase.buildKey(this.#userID, APP_ID, id);
		// Delete from server
		const path = `/home/users/${this.#userID}/wallpapers/${id}`;
		try {
			await this.#api.systemContent.deleteNode(path);
		} catch {
			// Node may already be gone; proceed with local removal
		}
		await this.#api.db.delete('wallpapers', key);
	}

	async addWallpaper({ id, url, timestamp = Date.now() }) {
		const key = WebtopDatabase.buildKey(this.#userID, APP_ID, id);
		await this.#api.db.put('wallpapers', {
			id: key,
			userID: this.#userID,
			url,
			timestamp,
		});
	}

	async applyWallpaper(): Promise<void> {
		const wallpaper = await this.getCurrentWallpaper();
		if (!wallpaper?.url) {
			document.body.style.backgroundImage = '';
			return;
		}

		document.body.style.backgroundImage = `url("${wallpaper.url}?t=${wallpaper.timestamp}")`;
		document.body.style.backgroundSize = 'cover';
		document.body.style.backgroundPosition = 'center';
	}

	async init() {
		this.#userID = this.#getUserID();
		let id = await this.getCurrentWallpaperID();

		// Re-resolve the default wallpaper on every boot. Its download URL is
		// bound to the workspace the webtop is served from, and the record is
		// shared by every user of this browser ('*'). A URL recorded on an
		// earlier boot may point at a workspace the current user cannot read,
		// or one that no longer carries the webtop, so it must not be reused.
		await this.#registerDefaultWallpaper();

		if (!id) {
			id = 'default';
			await this.setCurrentWallpaperID(id);
		}

		// Sync user wallpapers with server (add new, update changed, remove deleted)
		if (this.#userID !== '*') {
			await this.#syncUserWallpapers();
		}

		await this.applyWallpaper();
	}

	/**
	 * Full sync of user wallpapers from the server.
	 * - Removes local entries that no longer exist on the server.
	 * - Adds entries that exist on the server but not locally.
	 * - Updates entries whose server-side modified timestamp is newer.
	 */
	async #syncUserWallpapers(): Promise<void> {
		const wallpaperPath = `/home/users/${this.#userID}/wallpapers`;
		let serverNodes: any[];
		try {
			const connection = await this.#api.systemContent.listChildren(wallpaperPath);
			serverNodes = connection?.edges?.map((e: any) => e.node).filter(Boolean) ?? [];
		} catch {
			// Directory may not exist yet; nothing to sync
			return;
		}

		// Build a map of server wallpapers: filename -> node
		const serverMap = new Map<string, any>();
		for (const node of serverNodes) {
			if (node.name) serverMap.set(node.name, node);
		}

		// Get all current local wallpapers for this user
		const allLocal: any[] = await this.#api.db.getAll('wallpapers');
		const userLocal = allLocal.filter(
			w => w.userID === this.#userID,
		);

		// Remove local entries that no longer exist on the server
		for (const local of userLocal) {
			const keyParts = local.id.split('/');
			const filename = keyParts.slice(2).join('/');
			if (!serverMap.has(filename)) {
				await this.#api.db.delete('wallpapers', local.id);
			}
		}

		// Add or update entries from the server
		const localMap = new Map(userLocal.map(w => {
			const keyParts = w.id.split('/');
			const filename = keyParts.slice(2).join('/');
			return [filename, w];
		}));

		for (const [filename, node] of serverMap) {
			if (!node.downloadUrl) continue;
			const serverTs = node.modified ? new Date(node.modified).getTime() : 0;
			const local = localMap.get(filename);
			if (!local || local.timestamp !== serverTs) {
				const key = WebtopDatabase.buildKey(this.#userID, APP_ID, filename);
				await this.#api.db.put('wallpapers', {
					id: key,
					userID: this.#userID,
					url: node.downloadUrl,
					timestamp: serverTs || Date.now(),
				});
			}
		}
	}

	/**
	 * Resolve the default wallpaper and (re)write its shared record.
	 * Looks in the workspace the webtop is served from first — the user is
	 * already reading the webtop there, so that URL is known to be readable —
	 * and falls back to the system workspace. When neither has it, a stale
	 * record is removed rather than left pointing at a dead URL.
	 */
	async #registerDefaultWallpaper() {
		const fullPath = this.#api.context.getFullPath('/assets/wallpapers/wallpaper-default.jpg');
		const key = WebtopDatabase.buildKey('*', APP_ID, 'default');

		let node: any = null;
		for (const contentService of [this.#api.content, this.#api.systemContent]) {
			try {
				node = await contentService.getNode(fullPath);
			} catch {
				node = null;
			}
			if (node?.downloadUrl) break;
		}

		if (!node?.downloadUrl) {
			console.warn('Default wallpaper not found:', fullPath);
			await this.#api.db.delete('wallpapers', key);
			return;
		}

		const timestamp = node.modified ? new Date(node.modified).getTime() : Date.now();
		await this.#api.db.put('wallpapers', {
			id: key,
			userID: '*',
			url: node.downloadUrl,
			timestamp,
		});
	}
}

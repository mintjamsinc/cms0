const DB_NAME = 'WebtopDB';
const DB_VERSION = 3;
const STORE_NAMES = ['apps', 'unsavedState', 'settings', 'wallpapers', 'notifications', 'resources'];

// Legacy key patterns used in DB_VERSION 1
const LEGACY_KEY_PATTERNS = {
	// ThemeManager: "{userID}:theme.mode" -> "{userID}/webtop/theme.mode"
	themeMode: /^(.+):theme\.mode$/,
	// WallpaperManager: "wallpaper.current:{userID}" -> "{userID}/webtop/wallpaper.current"
	wallpaperCurrent: /^wallpaper\.current:(.+)$/,
};

export class WebtopDatabase {
	#db: IDBDatabase;

	async open() {
		return new Promise((resolve, reject) => {
			const req = indexedDB.open(DB_NAME, DB_VERSION);
			req.onerror = () => reject(req.error);

			req.onsuccess = () => {
				this.#db = req.result;
				resolve(this);
			};

			req.onupgradeneeded = (event: IDBVersionChangeEvent) => {
				const db = req.result;
				for (const name of STORE_NAMES) {
					if (!db.objectStoreNames.contains(name)) {
						db.createObjectStore(name, { keyPath: 'id' });
					}
				}

				// v3: wallpapers now store URL instead of dataURL — clear old base64 records
				if (event.oldVersion < 3) {
					req.transaction.objectStore('wallpapers').clear();
				}

				// Migrate legacy key formats from version 1
				if (event.oldVersion < 2) {
					req.transaction.objectStore('settings').openCursor().onsuccess = (e) => {
						const cursor = (e.target as IDBRequest<IDBCursorWithValue>).result;
						if (!cursor) return;

						const record = cursor.value;
						const oldID = record.id as string;
						let newID: string | null = null;

						const themeMatch = oldID.match(LEGACY_KEY_PATTERNS.themeMode);
						if (themeMatch) {
							newID = `${themeMatch[1]}/webtop/theme.mode`;
						}

						const wpMatch = oldID.match(LEGACY_KEY_PATTERNS.wallpaperCurrent);
						if (wpMatch) {
							newID = `${wpMatch[1]}/webtop/wallpaper.current`;
						}

						if (newID && newID !== oldID) {
							const store = cursor.source as IDBObjectStore;
							store.delete(oldID);
							store.put({ ...record, id: newID });
						}

						cursor.continue();
					};

					// Migrate wallpapers store: "{userID}:{wallpaperID}" -> "{userID}/webtop/{wallpaperID}"
					req.transaction.objectStore('wallpapers').openCursor().onsuccess = (e) => {
						const cursor = (e.target as IDBRequest<IDBCursorWithValue>).result;
						if (!cursor) return;

						const record = cursor.value;
						const oldID = record.id as string;
						const colonIndex = oldID.indexOf(':');

						if (colonIndex > 0 && !oldID.includes('/')) {
							const userID = oldID.substring(0, colonIndex);
							const wallpaperID = oldID.substring(colonIndex + 1);
							const newID = `${userID}/webtop/${wallpaperID}`;

							const store = cursor.source as IDBObjectStore;
							store.delete(oldID);
							store.put({ ...record, id: newID, userID });
						}

						cursor.continue();
					};
				}
			};
		});
	}

	async put(storeName: string, record: any) {
		return new Promise((resolve, reject) => {
			const tx = this.#db.transaction([storeName], 'readwrite');
			const store = tx.objectStore(storeName);
			const req = store.put(record);
			req.onsuccess = () => resolve(req.result);
			req.onerror = () => reject(req.error);
		});
	}

	async get(storeName: string, id: string): Promise<any> {
		return new Promise((resolve, reject) => {
			const tx = this.#db.transaction([storeName], 'readonly');
			const store = tx.objectStore(storeName);
			const req = store.get(id);
			req.onsuccess = () => resolve(req.result);
			req.onerror = () => reject(req.error);
		});
	}

	async delete(storeName: string, id: string) {
		return new Promise((resolve, reject) => {
			const tx = this.#db.transaction([storeName], 'readwrite');
			const store = tx.objectStore(storeName);
			const req = store.delete(id);
			req.onsuccess = () => resolve(undefined);
			req.onerror = () => reject(req.error);
		});
	}

	async getAll(storeName: string) {
		return new Promise((resolve, reject) => {
			const tx = this.#db.transaction([storeName], 'readonly');
			const store = tx.objectStore(storeName);
			const req = store.getAll();
			req.onsuccess = () => resolve(req.result);
			req.onerror = () => reject(req.error);
		});
	}

	async clear(storeName: string) {
		return new Promise((resolve, reject) => {
			const tx = this.#db.transaction([storeName], 'readwrite');
			const store = tx.objectStore(storeName);
			const req = store.clear();
			req.onsuccess = () => resolve(undefined);
			req.onerror = () => reject(req.error);
		});
	}

	/**
	 * Build a standardized key: {userID}/{appID}/{key}
	 */
	static buildKey(userID: string, appID: string, key: string): string {
		return `${userID}/${appID}/${key}`;
	}

	/**
	 * Get a user-scoped setting from the 'settings' store.
	 * Key format: {userID}/{appID}/{key}
	 */
	async getUserSetting(userID: string, appID: string, key: string): Promise<any> {
		const id = WebtopDatabase.buildKey(userID, appID, key);
		const record = await this.get('settings', id);
		return record?.value ?? null;
	}

	/**
	 * Save a user-scoped setting to the 'settings' store.
	 * Key format: {userID}/{appID}/{key}
	 */
	async setUserSetting(userID: string, appID: string, key: string, value: any): Promise<void> {
		const id = WebtopDatabase.buildKey(userID, appID, key);
		await this.put('settings', { id, userID, appID, key, value });
	}

	/**
	 * Delete a user-scoped setting from the 'settings' store.
	 */
	async deleteUserSetting(userID: string, appID: string, key: string): Promise<void> {
		const id = WebtopDatabase.buildKey(userID, appID, key);
		await this.delete('settings', id);
	}
}

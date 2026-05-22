export class ResourceResolver {
	#resourcePaths;

	constructor(context) {
		this.#resourcePaths = context.resourcePaths; // 例: { 'asset:logo:light': '/assets/icons/logo_light.svg' }
	}

	get resourcePaths() { return this.#resourcePaths; }

	// id から対応するパスを「解決」するメソッド
	resolve(id) {
		const path = this.#resourcePaths[id];
		if (!path) {
			//console.error(`Path for id '${id}' not found.`);
			return null;
		}
		return path;
	}
}

export class ResourceManager {
	#api;

	constructor(api) {
		this.#api = api;
	}

	async getResource(baseId, themed = true) {
		const ids = [];
		if (themed) {
			let mode = await this.#api.theme.getThemeSetting();
			if (mode === 'auto') {
				const hour = new Date().getHours();
				mode = (hour >= 19 || hour < 6) ? 'dark' : 'light';
			}
			ids.push(`${baseId}:${mode}`);
		}
		ids.push(baseId);

		for (const id of ids) {
			// 1. キャッシュから探す
			const cached = await this.#getCache(id);
			if (cached) {
				//console.log(`Resource '${id}' found in cache.`);
				return cached;
			}

			// Resolverにパスの解決を委譲する
			const resourcePath = this.#api.resourceResolver.resolve(id);
			if (!resourcePath) {
				continue; // パスが解決できなければ次のリソースIDへ
			}

			// 2. キャッシュになければダウンロードしてキャッシュする
			//console.log(`Resource '${id}' not in cache. Downloading from web...`);
			const downloaded = await this.#download(resourcePath);
			if (downloaded) {
				this.#cacheResource({ id, content: downloaded });
				//console.log(`Resource '${id}' downloaded and cached.`);
				return downloaded;
			}
		}

		//console.log(`Resource '${baseId}' could not be found.`);
		return null;
	}

	async #getCache(id) {
		// キャッシュから取得するロジック
		const record = await this.#api.db.get('resources', id);
		return record?.content ?? null;
	}

	async #download(resourcePath) {
		// ダウンロードして取得するロジック
		try {
			const fullPath = this.#api.context.getFullPath(resourcePath);
			const item = await this.#api.cms.fetchItem({ path: fullPath });
			return await item.getContentAsDataURL();
		} catch (ignore) {
			return null;
		}
	}

	async #cacheResource({ id, content }) {
		// キャッシュするロジック
		await this.#api.db.put('resources', {
			id,
			content,
			timestamp: Date.now()
		});
	}
}

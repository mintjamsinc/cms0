export class BlobURLManager {
	static #activeURLs: Set<string> = new Set();

	/**
	 * Blob から ObjectURL を生成し、必要に応じて自動解放
	 * @param {Blob} blob
	 * @param {object} options
	 * @param {HTMLElement} [options.autoRevokeOn] - <img>や<video>要素など
	 * @param {number} [options.timeout] - 指定ミリ秒後に自動解放
	 * @returns {string} Object URL
	 */
	static create(blob: Blob, options: { autoRevokeOn?: HTMLElement; timeout?: number } = {}) {
		const url = URL.createObjectURL(blob);
		this.#activeURLs.add(url);

		if (options.autoRevokeOn instanceof HTMLElement) {
			options.autoRevokeOn.addEventListener('load', () => {
				this.revoke(url);
			}, { once: true });
		}

		if (typeof options.timeout === 'number' && options.timeout > 0) {
			setTimeout(() => {
				this.revoke(url);
			}, options.timeout);
		}

		return url;
	}

	/**
	 * ObjectURL を明示的に解放
	 * @param {string} url
	 */
	static revoke(url: string) {
		if (!this.#activeURLs.has(url)) return;
		URL.revokeObjectURL(url);
		this.#activeURLs.delete(url);
	}

	/**
	 * 全URLを一括解放
	 */
	static revokeAll() {
		for (const url of this.#activeURLs) {
			URL.revokeObjectURL(url);
		}
		this.#activeURLs.clear();
	}
}

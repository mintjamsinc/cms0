/**
 * @deprecated Use EventHub (realtime/event-hub.ts) with SubscriptionClient instead.
 * This class will be removed in a future release.
 */
export class WebtopEventSource {
	/** @type {EventSource|null} */
	#source = null;
	/** @type {Map<string, Set<Function>>} */
	#listeners = new Map();

	constructor(url = '/event/chat') {
		this.#source = new EventSource(url);

		this.#source.onmessage = e => {
			try {
				const msg = JSON.parse(e.data);
				if (msg?.type) this.#dispatch(msg.type, msg);
			} catch (err) {
				console.warn('SSE parse error:', e.data);
			}
		};
	}

	/**
	 * @param {string} type
	 * @param {(data: any) => void} handler
	 */
	on(type, handler) {
		if (!this.#listeners.has(type)) {
			this.#listeners.set(type, new Set());
		}
		this.#listeners.get(type).add(handler);
	}

	/**
	 * @param {string} type
	 * @param {(data: any) => void} handler
	 */
	off(type, handler) {
		this.#listeners.get(type)?.delete(handler);
	}

	/**
	 * @param {string} type
	 * @param {any} data
	 */
	#dispatch(type, data) {
		this.#listeners.get(type)?.forEach(fn => {
			try {
				fn(data);
			} catch (err) {
				console.error(`Error in SSE handler for '${type}':`, err);
			}
		});
	}

	/**
	 * 接続を明示的に閉じる
	 */
	close() {
		this.#source?.close();
		this.#source = null;
	}
}

// 利用例：
// const es = new WebtopEventSource('/event/chat');
// es.on('user-logged-in', msg => console.log(msg));
// es.on('file-created', msg => refreshFileList());

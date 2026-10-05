/**
 * Asks the computer player for a move. The search runs in ai-worker.js; if
 * the worker cannot start (or dies), the same search runs here instead,
 * which only costs smoothness while it thinks.
 */

import { chooseMove, type AiRequest, type AiResult } from './ai.js';

interface Pending {
	request: AiRequest;
	resolve: (result: AiResult) => void;
	reject: (error: Error) => void;
}

export class AiClient {
	#worker: Worker | null = null;
	#seq = 0;
	#pending = new Map<number, Pending>();

	constructor(workerUrl: URL) {
		try {
			this.#worker = new Worker(workerUrl, { type: 'module' });
			this.#worker.addEventListener('message', (e: MessageEvent) => this.#onMessage(e.data));
			this.#worker.addEventListener('error', (e) => {
				console.warn('[Reversi] AI worker failed; thinking on the main thread instead:', e.message);
				this.#fallBack();
			});
		} catch (e) {
			console.warn('[Reversi] AI worker could not start; thinking on the main thread instead:', e);
			this.#worker = null;
		}
	}

	choose(request: AiRequest): Promise<AiResult> {
		if (!this.#worker) return this.#local(request);
		const id = ++this.#seq;
		return new Promise<AiResult>((resolve, reject) => {
			this.#pending.set(id, { request, resolve, reject });
			this.#worker!.postMessage({ id, ...request });
		});
	}

	/** Drops answers still on their way (a new game or an undo). */
	cancel(): void {
		for (const p of this.#pending.values()) p.reject(new Error('cancelled'));
		this.#pending.clear();
	}

	destroy(): void {
		this.cancel();
		this.#worker?.terminate();
		this.#worker = null;
	}

	#onMessage(data: { id: number; result?: AiResult; error?: string }): void {
		const p = this.#pending.get(data.id);
		if (!p) return;
		this.#pending.delete(data.id);
		if (data.result) p.resolve(data.result);
		else p.reject(new Error(data.error || 'AI error'));
	}

	#fallBack(): void {
		this.#worker?.terminate();
		this.#worker = null;
		const pending = [...this.#pending.values()];
		this.#pending.clear();
		for (const p of pending) this.#local(p.request).then(p.resolve, p.reject);
	}

	#local(request: AiRequest): Promise<AiResult> {
		// Let the screen paint "thinking" before the search blocks it.
		return new Promise((resolve, reject) => setTimeout(() => {
			try {
				resolve(chooseMove(request));
			} catch (e) {
				reject(e);
			}
		}, 30));
	}
}

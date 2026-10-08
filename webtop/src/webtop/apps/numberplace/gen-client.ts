/**
 * Asks for a new puzzle. The puzzle is made in gen-worker.js, since a hard
 * one can take a moment; if the worker cannot start (or dies), the same
 * work runs here instead, which only costs smoothness while it runs.
 */

import { generate, type Level, type Puzzle } from './core.js';

interface Pending {
	level: Level;
	resolve: (puzzle: Puzzle) => void;
	reject: (error: Error) => void;
}

export class GenClient {
	#worker: Worker | null = null;
	#seq = 0;
	#pending = new Map<number, Pending>();

	constructor(workerUrl: URL) {
		try {
			this.#worker = new Worker(workerUrl, { type: 'module' });
			this.#worker.addEventListener('message', (e: MessageEvent) => this.#onMessage(e.data));
			this.#worker.addEventListener('error', (e) => {
				console.warn('[NumberPlace] Puzzle worker failed; making puzzles on the main thread instead:', e.message);
				this.#fallBack();
			});
		} catch (e) {
			console.warn('[NumberPlace] Puzzle worker could not start; making puzzles on the main thread instead:', e);
			this.#worker = null;
		}
	}

	generate(level: Level): Promise<Puzzle> {
		if (!this.#worker) return this.#local(level);
		const id = ++this.#seq;
		return new Promise<Puzzle>((resolve, reject) => {
			this.#pending.set(id, { level, resolve, reject });
			this.#worker!.postMessage({ id, level });
		});
	}

	/** Drops puzzles still on their way. */
	cancel(): void {
		for (const p of this.#pending.values()) p.reject(new Error('cancelled'));
		this.#pending.clear();
	}

	destroy(): void {
		this.cancel();
		this.#worker?.terminate();
		this.#worker = null;
	}

	#onMessage(data: { id: number; result?: Puzzle; error?: string }): void {
		const p = this.#pending.get(data.id);
		if (!p) return;
		this.#pending.delete(data.id);
		if (data.result) p.resolve(data.result);
		else p.reject(new Error(data.error || 'Puzzle error'));
	}

	#fallBack(): void {
		this.#worker?.terminate();
		this.#worker = null;
		const pending = [...this.#pending.values()];
		this.#pending.clear();
		for (const p of pending) this.#local(p.level).then(p.resolve, p.reject);
	}

	#local(level: Level): Promise<Puzzle> {
		// Let the screen paint "making a puzzle" before the work blocks it.
		return new Promise((resolve, reject) => setTimeout(() => {
			try {
				resolve(generate(level));
			} catch (e) {
				reject(e);
			}
		}, 30));
	}
}

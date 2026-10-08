/**
 * Web Worker entry for making puzzles (built to gen-worker.js next to
 * app.js; see the numberplace target in rollup.config.js). One request in,
 * one puzzle out, tagged with the request id.
 */

import { generate, type Level } from './core.js';

addEventListener('message', (event: MessageEvent<{ id: number; level: Level }>) => {
	const { id, level } = event.data;
	try {
		postMessage({ id, result: generate(level) });
	} catch (e) {
		postMessage({ id, error: e instanceof Error ? e.message : String(e) });
	}
});

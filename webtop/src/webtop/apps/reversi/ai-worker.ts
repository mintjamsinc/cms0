/**
 * Web Worker entry for the computer player (built to ai-worker.js next to
 * app.js; see the reversi target in rollup.config.js). One request in, one
 * result out, tagged with the request id.
 */

import { chooseMove, type AiRequest } from './ai.js';

addEventListener('message', (event: MessageEvent<AiRequest & { id: number }>) => {
	const { id, ...request } = event.data;
	try {
		postMessage({ id, result: chooseMove(request) });
	} catch (e) {
		postMessage({ id, error: e instanceof Error ? e.message : String(e) });
	}
});

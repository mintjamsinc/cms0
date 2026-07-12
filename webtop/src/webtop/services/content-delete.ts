/**
 * Shared delete orchestration for JCR content nodes.
 *
 * - Single non-folder/non-children item: synchronous `deleteNode` (one round
 *   trip, finishes faster than the async job ceremony).
 * - Otherwise (folders, multi-selection): the async job pattern via
 *   `initDeleteNodes` / `appendAllDeleteNodes` / `startDeleteNodes`, with
 *   progress streamed via `eventHub.watchJobProgress(jobId, ...)`.
 *
 * The helper hands the caller a job handle through `onStart` *before* it
 * appends/starts, so callers can wire up their progress UI synchronously and
 * have it ready before the first server-emitted progress event arrives.
 *
 * Progress arrives on two redundant channels feeding one guarded forwarder:
 * the `jobProgress(jobId)` subscription (low latency) and a poll of the
 * `jobProgress(jobId)` query (reads the job's committed record directly, so
 * it survives a lost SSE event and the journal observer lagging behind a bulk
 * delete's commit). Both auto-release on the first terminal status; call
 * `handle.release()` for defensive cleanup if the caller's UI tears down
 * earlier.
 */
import type { ContentServiceGraphQL } from './content-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';
import type { JobProgressEvent, JobStatus } from '../graphql/types.js';

export interface DeleteItem {
	path: string;
	isCollection?: boolean;
	hasChildren?: boolean;
}

export interface DeleteJobProgress {
	jobId: string;
	status: JobStatus;
	itemsTotal: number;
	itemsProcessed: number;
	itemsDeleted: number;
	currentPath: string;
	errorMessage?: string;
}

export interface DeleteJobHandle {
	jobId: string;
	/** Best-effort: requests the server to stop. Status updates will reflect it. */
	abort: () => Promise<void>;
	/** Stop receiving progress events. Auto-called on terminal status. Idempotent. */
	release: () => void;
}

export interface DeleteContentOptions {
	/**
	 * Called once on the async path right after the job is allocated (after
	 * `initDeleteNodes`, before `appendAllDeleteNodes`/`startDeleteNodes`).
	 * Use this to set up the progress UI synchronously so the first
	 * `onProgress` event has somewhere to land.
	 */
	onStart?: (handle: DeleteJobHandle) => void;
	/** Forwarded job progress events. Not called on the synchronous path. */
	onProgress?: (event: DeleteJobProgress) => void;
}

export type DeleteResult =
	| { sync: true }
	| { sync: false; handle: DeleteJobHandle };

const TERMINAL_STATUSES: ReadonlySet<JobStatus> = new Set([
	'completed', 'aborted', 'failed',
]);

/** Poll cadence for the fallback jobProgress query while a job is live. */
const POLL_INTERVAL_MILLIS = 1500;

/**
 * Delete one or more JCR content nodes.
 *
 * Returns `{ sync: true }` for the fast path (no progress events fire), or
 * `{ sync: false, handle }` for the async path. In the async case,
 * `onStart` fires synchronously before any progress events; the server then
 * emits one `jobProgress` event per batched write which the helper forwards
 * to `onProgress` and auto-releases the subscription on terminal status.
 */
export async function deleteContentItems(
	contentService: ContentServiceGraphQL,
	eventHub: EventHub | null | undefined,
	items: DeleteItem[],
	options: DeleteContentOptions = {},
): Promise<DeleteResult> {
	if (items.length === 0) return { sync: true };

	if (items.length === 1 && !items[0].isCollection && !items[0].hasChildren) {
		await contentService.deleteNode(items[0].path);
		return { sync: true };
	}

	const init = await contentService.initDeleteNodes();
	const jobId = init.jobId;

	let unsubscribe: null | (() => void) = null;
	let pollTimer: ReturnType<typeof setTimeout> | null = null;
	let finished = false;
	let lastItemsProcessed = -1;
	let lastItemsDeleted = -1;

	const release = () => {
		if (pollTimer !== null) {
			clearTimeout(pollTimer);
			pollTimer = null;
		}
		if (unsubscribe) {
			try { unsubscribe(); } catch { /* noop */ }
			unsubscribe = null;
		}
	};

	const onProgress = options.onProgress;

	// Single funnel for both channels (subscription events and poll
	// snapshots). The first terminal status wins and closes both channels;
	// a non-terminal snapshot that arrives out of order (an in-flight poll
	// response losing to a fresher subscription event) is dropped so the
	// counters never run backwards.
	const forward = (event: JobProgressEvent) => {
		if (finished || !event.status) return;
		const itemsProcessed = typeof event.itemsProcessed === 'number' ? event.itemsProcessed : 0;
		const itemsDeleted = typeof event.itemsDeleted === 'number' ? event.itemsDeleted : 0;
		const isTerminal = TERMINAL_STATUSES.has(event.status);
		if (!isTerminal
			&& (itemsProcessed < lastItemsProcessed
				|| (itemsProcessed === lastItemsProcessed && itemsDeleted < lastItemsDeleted))) {
			return;
		}
		lastItemsProcessed = itemsProcessed;
		lastItemsDeleted = itemsDeleted;
		if (isTerminal) {
			finished = true;
			release();
		}
		onProgress?.({
			jobId,
			status: event.status,
			itemsTotal: typeof event.itemsTotal === 'number' ? event.itemsTotal : items.length,
			itemsProcessed,
			itemsDeleted,
			currentPath: event.currentPath ?? '',
			errorMessage: event.errorMessage ?? undefined,
		});
	};

	// Fallback poller: reads the job's committed record, so it reconciles the
	// monitor even when the SSE stream missed the terminal event or the event
	// pipeline lags behind the worker. Poll errors are transient by
	// assumption — the next tick retries.
	const schedulePoll = () => {
		if (finished || pollTimer !== null) return;
		pollTimer = setTimeout(async () => {
			pollTimer = null;
			try {
				const snapshot = await contentService.getJobProgress(jobId);
				if (snapshot) forward(snapshot);
			} catch { /* retried on the next tick */ }
			schedulePoll();
		}, POLL_INTERVAL_MILLIS);
	};

	const handle: DeleteJobHandle = {
		jobId,
		async abort() {
			try {
				await contentService.abortDeleteNodes(jobId);
			} catch (err) {
				// Server may have already finished; the next jobProgress
				// event reconciles the UI either way.
				console.warn('abortDeleteNodes failed', err);
			}
		},
		release,
	};

	// Hand the handle to the caller before we wire up the watcher and start
	// the job, so the caller's progress UI is ready when events begin flowing.
	options.onStart?.(handle);

	if (eventHub && onProgress) {
		unsubscribe = eventHub.watchJobProgress(jobId, forward);
	}

	try {
		await contentService.appendAllDeleteNodes(jobId, items.map(it => it.path));
		const started = await contentService.startDeleteNodes(jobId);
		forward({
			jobId,
			status: started.status,
			itemsTotal: started.itemsTotal,
			itemsProcessed: 0,
			itemsDeleted: 0,
			timestamp: new Date().toISOString(),
		});
		if (onProgress) schedulePoll();
	} catch (err) {
		release();
		throw err;
	}

	return { sync: false, handle };
}

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
 * The progress subscription auto-releases when the server reports a terminal
 * status; call `handle.release()` for defensive cleanup if the caller's UI
 * tears down earlier.
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
	const release = () => {
		if (unsubscribe) {
			try { unsubscribe(); } catch { /* noop */ }
			unsubscribe = null;
		}
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

	const onProgress = options.onProgress;
	if (eventHub && onProgress) {
		unsubscribe = eventHub.watchJobProgress(jobId, (event: JobProgressEvent) => {
			onProgress({
				jobId,
				status: event.status,
				itemsTotal: typeof event.itemsTotal === 'number' ? event.itemsTotal : items.length,
				itemsProcessed: typeof event.itemsProcessed === 'number' ? event.itemsProcessed : 0,
				itemsDeleted: typeof event.itemsDeleted === 'number' ? event.itemsDeleted : 0,
				currentPath: event.currentPath ?? '',
				errorMessage: event.errorMessage,
			});
			if (TERMINAL_STATUSES.has(event.status)) release();
		});
	}

	try {
		await contentService.appendAllDeleteNodes(jobId, items.map(it => it.path));
		const started = await contentService.startDeleteNodes(jobId);
		onProgress?.({
			jobId,
			status: started.status,
			itemsTotal: started.itemsTotal,
			itemsProcessed: 0,
			itemsDeleted: 0,
			currentPath: '',
		});
	} catch (err) {
		release();
		throw err;
	}

	return { sync: false, handle };
}

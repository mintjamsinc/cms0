/**
 * Shared orchestration for downloading JCR content as a single ZIP archive.
 *
 * Used by the Content Browser when the user downloads a folder or a
 * multi-selection: rather than firing one browser download per file, the
 * selected top-level items are bundled server-side into one ZIP via the async
 * job pattern (`initDownloadArchive` / `appendAllDownloadArchive` /
 * `startDownloadArchive`), with progress streamed over
 * `eventHub.watchJobProgress(jobId, ...)`. The terminal event carries the
 * `downloadUrl` of the finished archive, which the caller then fetches.
 *
 * This mirrors {@link ./content-delete.ts}: the helper hands the caller a job
 * handle through `onStart` *before* it appends/starts, so the progress UI is
 * ready before the first server-emitted event arrives, and the progress
 * subscription auto-releases on terminal status.
 *
 * A single, plain file does not need an archive — callers should download it
 * directly and not route it through here.
 */
import type { ContentServiceGraphQL } from './content-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';
import type { JobProgressEvent, JobStatus } from '../graphql/types.js';

export interface ArchiveItem {
	path: string;
}

export interface ArchiveJobProgress {
	jobId: string;
	status: JobStatus;
	itemsTotal: number;
	itemsProcessed: number;
	/** Number of files written into the ZIP so far. */
	itemsArchived: number;
	/** Absolute path of the file currently being archived. */
	currentPath: string;
	/** Present on the terminal (completed) event: where to fetch the finished ZIP. */
	downloadUrl?: string;
	errorMessage?: string;
}

export interface ArchiveJobHandle {
	jobId: string;
	/** Best-effort: requests the server to stop. Status updates will reflect it. */
	abort: () => Promise<void>;
	/** Stop receiving progress events. Auto-called on terminal status. Idempotent. */
	release: () => void;
}

export interface DownloadArchiveOptions {
	/**
	 * Called once right after the job is allocated (after `initDownloadArchive`,
	 * before `appendAllDownloadArchive`/`startDownloadArchive`). Use this to set
	 * up the progress UI synchronously so the first `onProgress` event has
	 * somewhere to land.
	 */
	onStart?: (handle: ArchiveJobHandle) => void;
	/** Forwarded job progress events. */
	onProgress?: (event: ArchiveJobProgress) => void;
}

const TERMINAL_STATUSES: ReadonlySet<JobStatus> = new Set([
	'completed', 'aborted', 'failed',
]);

/**
 * Bundle one or more JCR content items into a single ZIP on the server and
 * stream progress back. Returns the job handle once the worker has been
 * started; the caller fetches the archive when an `onProgress` event reports
 * `status === 'completed'` together with a `downloadUrl`.
 */
export async function downloadContentAsZip(
	contentService: ContentServiceGraphQL,
	eventHub: EventHub | null | undefined,
	items: ArchiveItem[],
	filename: string,
	options: DownloadArchiveOptions = {},
): Promise<ArchiveJobHandle> {
	if (items.length === 0) {
		throw new Error('No items to archive');
	}

	const init = await contentService.initDownloadArchive();
	const jobId = init.jobId;

	let unsubscribe: null | (() => void) = null;
	const release = () => {
		if (unsubscribe) {
			try { unsubscribe(); } catch { /* noop */ }
			unsubscribe = null;
		}
	};

	const handle: ArchiveJobHandle = {
		jobId,
		async abort() {
			try {
				await contentService.abortDownloadArchive(jobId);
			} catch (err) {
				// Server may have already finished; the next jobProgress
				// event reconciles the UI either way.
				console.warn('abortDownloadArchive failed', err);
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
				itemsArchived: typeof event.itemsArchived === 'number' ? event.itemsArchived : 0,
				currentPath: event.currentPath ?? '',
				downloadUrl: event.downloadUrl,
				errorMessage: event.errorMessage,
			});
			if (TERMINAL_STATUSES.has(event.status)) release();
		});
	}

	try {
		await contentService.appendAllDownloadArchive(jobId, items.map(it => it.path));
		const started = await contentService.startDownloadArchive(jobId, filename);
		onProgress?.({
			jobId,
			status: started.status,
			itemsTotal: started.itemsTotal,
			itemsProcessed: 0,
			itemsArchived: 0,
			currentPath: '',
		});
	} catch (err) {
		release();
		throw err;
	}

	return handle;
}

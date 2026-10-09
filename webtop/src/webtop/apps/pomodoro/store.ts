/**
 * Pomodoro: where the timer's state is kept.
 *
 * One file per user in the system workspace, read and written whole:
 *
 *   /home/users/<user>/pomodoro/state.json
 *
 * A timer action (start, pause, a phase ending) is written at once; settings
 * are written a moment after the last change, so dragging a slider is one
 * write. Writes go out one at a time, newest last.
 */
import { emptyData, normalizeData, type PomodoroData } from './timer.js';

const FILE_NAME = 'state.json';
const GENTLE_DELAY_MS = 600;

export interface PomodoroStoreServices {
	userId: string;
	content: any;  // api.systemContent
}

export class PomodoroStore {
	#services: PomodoroStoreServices;
	#timer: ReturnType<typeof setTimeout> | null = null;
	#pending: PomodoroData | null = null;
	#saving: Promise<void> | null = null;

	constructor(services: PomodoroStoreServices) {
		this.#services = services;
	}

	get folder(): string {
		return `/home/users/${this.#services.userId}/pomodoro`;
	}

	get path(): string {
		return `${this.folder}/${FILE_NAME}`;
	}

	async load(): Promise<PomodoroData> {
		try {
			const node = await this.#services.content.getNode(this.path);
			if (!node?.downloadUrl) return emptyData();
			const res = await fetch(node.downloadUrl, { cache: 'no-store' });
			return (res.ok && normalizeData(await res.json())) || emptyData();
		} catch (err) {
			console.warn('[Pomodoro] The timer could not be read:', err);
			return emptyData();
		}
	}

	save(data: PomodoroData, gentle = false): void {
		this.#pending = data;
		if (this.#timer) clearTimeout(this.#timer);
		this.#timer = setTimeout(() => {
			this.#timer = null;
			this.flush();
		}, gentle ? GENTLE_DELAY_MS : 0);
	}

	async flush(): Promise<void> {
		if (this.#timer) {
			clearTimeout(this.#timer);
			this.#timer = null;
		}
		if (this.#saving) await this.#saving.catch(() => { /* the save below carries the newer state */ });
		const data = this.#pending;
		if (!data) return;
		this.#pending = null;
		const content = this.#services.content;
		this.#saving = (async () => {
			const upload = await content.initiateMultipartUpload();
			try {
				await content.appendMultipartUploadData(upload.uploadId, JSON.stringify(data, null, '\t'));
				await content.completeMultipartUpload(upload.uploadId, this.folder, FILE_NAME, 'application/json', true);
			} catch (err) {
				await content.abortMultipartUpload(upload.uploadId).catch(() => { /* ignored */ });
				throw err;
			}
		})();
		try {
			await this.#saving;
		} catch (err) {
			console.warn('[Pomodoro] The timer could not be saved:', err);
		} finally {
			this.#saving = null;
		}
	}
}

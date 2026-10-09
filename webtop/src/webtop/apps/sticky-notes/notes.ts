/**
 * Sticky notes: where a note is kept.
 *
 * Each note is one placement of the app's `note` widget on the desktop. The
 * shell keeps where the placement is; the note's text and colour are a JSON
 * file named after the placement id in the user's home of the system
 * workspace:
 *
 *   /home/users/<user>/sticky-notes/<placement id>.json
 *
 * Both the widget (widget.ts) and the list window (app.ts) read them through
 * this store. A write carries the writer's id, so a widget can tell the change
 * events of its own saves from edits made in another browser.
 */
import { SWATCH_HIGHLIGHT_COLORS } from '../../lib/color-palette.js';

export interface Note {
	version: 1;
	text: string;
	color: string;      // NOTE_COLORS key
	updatedAt: string;  // ISO-8601
	writer?: string;    // id of the widget page that wrote it
}

/** The pale palette, so dark text stays readable on every note. */
export const NOTE_COLORS = SWATCH_HIGHLIGHT_COLORS;
export const DEFAULT_NOTE_COLOR = 'banana';
export const NOTE_TEXT_COLOR = '#1f2328';
const NOTE_FILE_TYPE = 'application/json';

export function noteColorValue(key: string | undefined): string {
	return (NOTE_COLORS.find((c) => c.key === key) || NOTE_COLORS.find((c) => c.key === DEFAULT_NOTE_COLOR)!).value;
}

export function emptyNote(): Note {
	return { version: 1, text: '', color: DEFAULT_NOTE_COLOR, updatedAt: '' };
}

function toNote(value: unknown): Note | null {
	if (!value || typeof value !== 'object') return null;
	const v = value as Record<string, unknown>;
	return {
		version: 1,
		text: typeof v.text === 'string' ? v.text : '',
		color: NOTE_COLORS.some((c) => c.key === v.color) ? v.color as string : DEFAULT_NOTE_COLOR,
		updatedAt: typeof v.updatedAt === 'string' ? v.updatedAt : '',
		writer: typeof v.writer === 'string' ? v.writer : undefined,
	};
}

export interface NoteStoreServices {
	userId: string;
	content: any;       // api.systemContent
}

export class NoteStore {
	#services: NoteStoreServices;

	constructor(services: NoteStoreServices) {
		this.#services = services;
	}

	get folder(): string {
		return `/home/users/${this.#services.userId}/sticky-notes`;
	}

	pathOf(id: string): string {
		return `${this.folder}/${id}.json`;
	}

	/** The note of a placement, or null when nothing has been written yet. */
	async load(id: string): Promise<Note | null> {
		try {
			const node = await this.#services.content.getNode(this.pathOf(id));
			return node?.downloadUrl ? await this.#fetch(node.downloadUrl) : null;
		} catch (err) {
			console.warn('[Sticky Notes] A note could not be read:', err);
			return null;
		}
	}

	/** Every note in the folder, by placement id. */
	async list(): Promise<Map<string, Note>> {
		const notes = new Map<string, Note>();
		let nodes: any[] = [];
		try {
			const result = await this.#services.content.listChildren(this.folder, { first: 200 });
			nodes = (result?.edges || []).map((e: any) => e.node).filter((n: any) => n?.downloadUrl && /\.json$/.test(n.name));
		} catch {
			return notes; // no folder yet
		}
		await Promise.all(nodes.map(async (n: any) => {
			const note = await this.#fetch(n.downloadUrl);
			if (note) notes.set(n.name.replace(/\.json$/, ''), note);
		}));
		return notes;
	}

	async save(id: string, note: Note): Promise<void> {
		const content = this.#services.content;
		const upload = await content.initiateMultipartUpload();
		try {
			await content.appendMultipartUploadData(upload.uploadId, JSON.stringify(note, null, '\t'));
			await content.completeMultipartUpload(upload.uploadId, this.folder, `${id}.json`, NOTE_FILE_TYPE, true);
		} catch (err) {
			await content.abortMultipartUpload(upload.uploadId).catch(() => { /* ignored */ });
			throw err;
		}
	}

	async delete(id: string): Promise<void> {
		try {
			await this.#services.content.deleteNode(this.pathOf(id));
		} catch {
			// Nothing was written for an empty note.
		}
	}

	async #fetch(url: string): Promise<Note | null> {
		try {
			const res = await fetch(url, { cache: 'no-store' });
			return res.ok ? toNote(await res.json()) : null;
		} catch {
			return null;
		}
	}
}

/**
 * A file an editor opens, wherever it lies: a node of the repository, a file
 * of the user's Google Drive / OneDrive, or something served at a same-origin
 * URL that is not a node at all (an attachment of a mail). The apps that
 * declare `fileHandles: true` in their app.yml read and write through this
 * one shape and never look at the path themselves, so a cloud file or a URL
 * file opens in them exactly as a repository file does.
 *
 *     const handle = await openFileHandle(instance.api, launchOptions);
 *     const text = await (await handle.read()).text();
 *     ...
 *     const saved = await handle.write(text, handle.mimeType);   // a fresh handle
 *
 * `location` is what the app keeps for its session state and for finding a
 * tab again: a repository path, or a `cloud://<provider>/<driveId>/<id>`
 * string (lib/cloud-location.ts). A URL file has none and is not restored.
 *
 * Writing a repository file is the multipart upload every editor used to do
 * by itself; the checkout / checkin around it stays with the app, which owns
 * the dialogs. Writing a cloud file is a PUT to the file's `/bin/cloud.cgi`
 * URL conditioned on the etag the file was read with: when the file changed
 * elsewhere meanwhile the server answers 412 and `write` throws
 * {@link FileConflictError}, and the app offers "Save As". A URL file is
 * read-only; `write` throws.
 *
 * The handle holds services with private fields, so an app keeps it outside
 * its reactive data (a Map keyed by its tab id, say), as the editors keep
 * their editor states.
 */

import type { CloudEntry, Node } from '../graphql/types.js';
import { nodeToInspectorTarget, type InspectorTarget } from './inspector-target.js';
import { cloudPath, isCloudPath, parseCloudPath } from './cloud-location.js';

export type FileHandleKind = 'repository' | 'cloud' | 'url';

/**
 * How a file is named to an app before it is opened: in the shell's
 * `open-file-with-app` message and the app's launch options (`file`), and in
 * what an app reads from a drag payload. Everything but `kind` is optional;
 * what is missing is looked up when the handle is opened.
 */
export interface FileRef {
	kind: FileHandleKind;
	/** The repository path or the `cloud://` location; none for a URL file. */
	location?: string | null;
	/** A URL file: where its bytes are (same origin). */
	url?: string | null;
	name?: string | null;
	mimeType?: string | null;
	size?: number | null;
	/** A URL file is always read-only; a repository or cloud file may be declared so too. */
	readOnly?: boolean;
	/** A cloud file. */
	provider?: string | null;
	driveId?: string | null;
	id?: string | null;
	etag?: string | null;
	webUrl?: string | null;
	/** The same-origin URL of the bytes, when the sender knows it. */
	downloadURL?: string | null;
}

/** What only a cloud file's handle has. */
export interface FileHandleCloud {
	provider: string;
	driveId: string;
	id: string;
	/** What the next write is conditioned on; null when the provider gave none. */
	etag: string | null;
	webUrl: string | null;
	/** `/bin/cloud.cgi/<provider>/<driveId>/<id>/<name>`. */
	contentUrl: string;
	nativeType: string | null;
	entry: CloudEntry;
}

export interface FileHandle {
	kind: FileHandleKind;
	/** The repository path, the `cloud://` location, or null for a URL file. */
	location: string | null;
	name: string;
	mimeType: string;
	size: number | null;
	/** ISO date of the last modification, when known. */
	modified: string | null;
	/** The same-origin URL of the bytes (a repository file's download URL, a cloud file's content URL, a URL file's URL). */
	downloadURL: string;
	readOnly: boolean;
	/** The repository node, for a repository file. */
	node: Node | null;
	/** The cloud entry, for a cloud file. */
	cloud: FileHandleCloud | null;
	/** The bytes, as `fetch(downloadURL)` answers. */
	read(): Promise<Response>;
	/** Replaces the content and returns the handle of what is now stored. Throws {@link FileConflictError} when a cloud file changed elsewhere. */
	write(content: Blob | string, mimeType: string): Promise<FileHandle>;
	/** Repository files only: calls back when the node changes on the server, and returns the function that stops watching. */
	watch?: (callback: () => void) => () => void;
	/** The object `<wt-inspector>` takes as its target; null for a URL file, which is not an item of anything. */
	inspectorTarget(): InspectorTarget | null;
}

/** A cloud file was changed elsewhere since it was read: nothing was written. */
export class FileConflictError extends Error {
	readonly status = 412;
	constructor(message?: string) {
		super(message || 'The file was changed elsewhere.');
		this.name = 'FileConflictError';
	}
}

/** The services a handle needs; an app passes `instance.api`. */
export interface FileHandleApi {
	content: any;
	cloud?: any;
	eventHub?: any;
}

/** A launch of an editor: the `path` / `mimeType` every editor took, plus `file` for anything that is not a repository path. */
export interface FileLaunch {
	path?: string | null;
	mimeType?: string | null;
	file?: FileRef | null;
}

/** The Office document types whose cloud copies open in the provider's own editor, not in an app of the Webtop. */
export const OFFICE_MIME_TYPES = [
	'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
	'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
	'application/vnd.openxmlformats-officedocument.presentationml.presentation',
	'application/msword',
	'application/vnd.ms-excel',
	'application/vnd.ms-powerpoint',
];

export function isOfficeMimeType(mimeType: string | null | undefined): boolean {
	return !!mimeType && OFFICE_MIME_TYPES.includes(mimeType);
}

/** A Google native document (Docs, Sheets, Slides, …), which has no bytes of its own. */
export function isGoogleNativeMimeType(mimeType: string | null | undefined): boolean {
	return !!mimeType && mimeType.startsWith('application/vnd.google-apps.');
}

/** Whether a cloud file is something the provider edits and the Webtop only links to. */
export function opensAtProvider(file: { mimeType?: string | null; nativeType?: string | null }): boolean {
	return !!file.nativeType || isGoogleNativeMimeType(file.mimeType) || isOfficeMimeType(file.mimeType);
}

// ---- Opening ------------------------------------------------------------------

/**
 * Opens the file a launch names. A `file` of kind `url` needs no request; a
 * cloud file is taken from the ref when it carries what a handle needs
 * (name, type, the content URL and the etag) and looked up otherwise; a
 * repository path is looked up. Throws when the file does not exist.
 */
export async function openFileHandle(api: FileHandleApi, launch: FileLaunch | string): Promise<FileHandle> {
	const l: FileLaunch = typeof launch === 'string' ? { path: launch } : (launch || {});
	const ref = l.file || null;
	if (ref?.kind === 'url') {
		return urlFileHandle(ref);
	}
	if (ref?.kind === 'cloud' || isCloudPath(l.path)) {
		return openCloudFileHandle(api, ref, l.path || null);
	}
	const path = ref?.location || l.path || '';
	if (!path) throw new Error('No file to open.');
	const node: Node | null = await api.content.getNode(path);
	if (!node) throw new Error(`File not found: ${path}`);
	return repositoryFileHandle(api, node, !!ref?.readOnly);
}

async function openCloudFileHandle(api: FileHandleApi, ref: FileRef | null, path: string | null): Promise<FileHandle> {
	const parsed = parseCloudPath(ref?.location || path);
	const provider = ref?.provider || parsed?.provider || '';
	const driveId = ref?.driveId || parsed?.driveId || '';
	const id = ref?.id || parsed?.id || '';
	if (!provider || !driveId || !id) throw new Error('Not a cloud file.');
	if (!api.cloud) throw new Error('Cloud storage is not available.');
	if (ref && ref.name && ref.mimeType && ref.downloadURL && ref.etag) {
		const entry: CloudEntry = {
			provider, driveId, id,
			name: ref.name,
			mimeType: ref.mimeType,
			size: ref.size ?? null,
			folder: false,
			webUrl: ref.webUrl ?? null,
			modified: null,
			created: null,
			nativeType: null,
			parentId: null,
			etag: ref.etag,
			owner: null,
			shared: false,
			hasThumbnail: false,
			path: null,
			contentUrl: ref.downloadURL.replace(/[?&]attachment$/, ''),
		};
		return cloudFileHandle(api, entry, !!ref.readOnly);
	}
	const entry: CloudEntry | null = await api.cloud.getFile(provider, driveId, id);
	if (!entry) throw new Error(`File not found: ${cloudPath(provider, driveId, id)}`);
	if (entry.folder) throw new Error(`Not a file: ${entry.name}`);
	return cloudFileHandle(api, entry, !!ref?.readOnly);
}

// ---- The three kinds ------------------------------------------------------------

/** The handle of a repository node already in hand. */
export function repositoryFileHandle(api: FileHandleApi, node: Node, readOnly = false): FileHandle {
	const path = node.path;
	const handle: FileHandle = {
		kind: 'repository',
		location: path,
		name: node.name,
		mimeType: node.mimeType || 'application/octet-stream',
		size: node.size ?? null,
		modified: node.modified || null,
		downloadURL: node.downloadUrl || '',
		readOnly,
		node,
		cloud: null,
		read() {
			if (!node.downloadUrl) return Promise.reject(new Error(`The file has no content: ${path}`));
			return fetch(node.downloadUrl, { credentials: 'same-origin' });
		},
		async write(content, mimeType) {
			if (readOnly) throw new Error('The file is read-only.');
			const index = path.lastIndexOf('/');
			const parent = index > 0 ? path.substring(0, index) : '/';
			const name = path.substring(index + 1);
			const upload = await api.content.initiateMultipartUpload();
			try {
				await api.content.appendMultipartUploadData(upload.uploadId, content);
				await api.content.completeMultipartUpload(upload.uploadId, parent, name, mimeType, true);
			} catch (e) {
				try { await api.content.abortMultipartUpload(upload.uploadId); } catch { /* ignore */ }
				throw e;
			}
			const fresh: Node | null = await api.content.getNode(path);
			return fresh ? repositoryFileHandle(api, fresh, readOnly) : handle;
		},
		watch(callback) {
			const eventHub = api.eventHub;
			if (!eventHub || typeof eventHub.watchNode !== 'function') return () => { /* nothing to stop */ };
			return eventHub.watchNode(path, () => callback(), false);
		},
		inspectorTarget() {
			return nodeToInspectorTarget(node);
		},
	};
	return handle;
}

/** The handle of a cloud entry already in hand (a listing's, a save's answer). */
export function cloudFileHandle(api: FileHandleApi, entry: CloudEntry, readOnly = false): FileHandle {
	const location = cloudPath(entry.provider, entry.driveId, entry.id);
	const contentUrl = entry.contentUrl;
	const handle: FileHandle = {
		kind: 'cloud',
		location,
		name: entry.name,
		mimeType: entry.mimeType || 'application/octet-stream',
		size: entry.size ?? null,
		modified: entry.modified || null,
		downloadURL: contentUrl,
		readOnly,
		node: null,
		cloud: {
			provider: entry.provider,
			driveId: entry.driveId,
			id: entry.id,
			etag: entry.etag ?? null,
			webUrl: entry.webUrl ?? null,
			contentUrl,
			nativeType: entry.nativeType ?? null,
			entry,
		},
		read() {
			return fetch(contentUrl, { credentials: 'same-origin' });
		},
		async write(content, mimeType) {
			if (readOnly) throw new Error('The file is read-only.');
			const headers: Record<string, string> = { 'Content-Type': mimeType };
			// The server requires the condition; a file the provider gave no
			// etag for is written on the strength of the one it answers with.
			headers['If-Match'] = entry.etag ? `"${entry.etag}"` : '*';
			const response = await fetch(contentUrl, { method: 'PUT', headers, body: content, credentials: 'same-origin' });
			if (response.status === 412) {
				throw new FileConflictError();
			}
			if (!response.ok) {
				throw new Error(await errorMessageOf(response));
			}
			const updated = await response.json() as CloudEntry;
			return cloudFileHandle(api, { ...entry, ...updated, contentUrl: updated.contentUrl || contentUrl }, readOnly);
		},
		inspectorTarget() {
			return cloudEntryInspectorTarget(entry);
		},
	};
	return handle;
}

/** The handle of a file served at a URL, such as a mail attachment: read-only, not an item of anything. */
export function urlFileHandle(ref: FileRef): FileHandle {
	const url = ref.url || ref.downloadURL || '';
	if (!url) throw new Error('The file has no URL.');
	return {
		kind: 'url',
		location: null,
		name: ref.name || 'file',
		mimeType: ref.mimeType || 'application/octet-stream',
		size: ref.size ?? null,
		modified: null,
		downloadURL: url,
		readOnly: true,
		node: null,
		cloud: null,
		read() {
			return fetch(url, { credentials: 'same-origin' });
		},
		write() {
			return Promise.reject(new Error('The file is read-only.'));
		},
		inspectorTarget() {
			return null;
		},
	};
}

// ---- Helpers ------------------------------------------------------------------

/** The Inspector's target for a cloud entry outside a Content Browser listing: the rows the entry itself can fill. */
export function cloudEntryInspectorTarget(entry: CloudEntry): InspectorTarget {
	return {
		id: `cloud:${entry.driveId}:${entry.id}`,
		name: entry.name,
		path: cloudPath(entry.provider, entry.driveId, entry.id),
		isCollection: !!entry.folder,
		downloadURL: entry.folder ? null : entry.contentUrl + '?attachment',
		created: entry.created ? new Date(entry.created) : null,
		createdBy: entry.owner || '',
		createdByDisplayName: entry.owner || null,
		lastModified: entry.modified ? new Date(entry.modified) : null,
		lastModifiedBy: '',
		lastModifiedByDisplayName: null,
		contentLength: entry.size || 0,
		mimeType: entry.mimeType || '',
		encoding: '',
		isLocked: false,
		lockInfo: null,
		isReferenceable: false,
		isVersionable: false,
		isCheckedOut: false,
		baseVersionName: null,
		color: '',
		tags: [],
		orientation: '',
		imageWidth: null,
		imageHeight: null,
		thumbnailVersion: null,
		cloud: {
			provider: entry.provider,
			driveId: entry.driveId,
			id: entry.id,
			parentId: entry.parentId,
			etag: entry.etag,
			webUrl: entry.webUrl,
			nativeType: entry.nativeType,
			hasThumbnail: !!entry.hasThumbnail,
			owner: entry.owner,
			shared: !!entry.shared,
			contentUrl: entry.contentUrl,
			virtual: null,
			drive: null,
			crumbs: null,
			// Without the listing's breadcrumbs the display path the provider
			// gives (Microsoft) is the best location there is; else the name.
			location: entry.path || entry.name,
			search: null,
		},
	};
}

/** The ref an app passes on for a handle it holds (to another app, into its session state). */
export function fileRefOf(handle: FileHandle): FileRef {
	return {
		kind: handle.kind,
		location: handle.location,
		url: handle.kind === 'url' ? handle.downloadURL : null,
		name: handle.name,
		mimeType: handle.mimeType,
		size: handle.size,
		readOnly: handle.readOnly,
		provider: handle.cloud?.provider ?? null,
		driveId: handle.cloud?.driveId ?? null,
		id: handle.cloud?.id ?? null,
		etag: handle.cloud?.etag ?? null,
		webUrl: handle.cloud?.webUrl ?? null,
		downloadURL: handle.downloadURL,
	};
}

/** The ref an item of a drag payload (`application/x-webtop-file(s)`) stands for, or null for a folder. */
export function fileRefOfDragItem(item: any): FileRef | null {
	if (!item || item.isCollection || !item.path) return null;
	if (item.cloud) {
		return {
			kind: 'cloud',
			location: item.path,
			name: item.name || null,
			mimeType: item.mimeType || null,
			provider: item.cloud.provider,
			driveId: item.cloud.driveId,
			id: item.cloud.id,
			etag: item.cloud.etag ?? null,
			webUrl: item.webUrl ?? null,
			downloadURL: item.downloadURL || null,
		};
	}
	return { kind: 'repository', location: item.path, name: item.name || null, mimeType: item.mimeType || null };
}

async function errorMessageOf(response: Response): Promise<string> {
	try {
		const body = await response.json();
		if (body && typeof body.message === 'string') return body.message;
	} catch { /* not JSON */ }
	return `HTTP ${response.status}`;
}

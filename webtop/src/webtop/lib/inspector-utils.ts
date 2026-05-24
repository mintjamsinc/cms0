// Shared display helpers used by the Inspector (wt-inspector) and by the
// surrounding content-browser UI. Moved out of content-browser/app.ts so
// both pieces of code can share a single implementation without circular
// imports. All functions are pure — they depend only on the item passed in
// and on the static utility classes under webtop/utils.
import { Bytes } from '../utils/bytes.js';
import { Dates } from '../utils/dates.js';
import { MimeTypes } from '../utils/mime-types.js';
import { Encodings } from '../utils/encodings.js';

// Minimal duck-typed shape consumed by these helpers. Real items have many
// more fields, but only these are read here.
export interface DisplayItemLike {
	isCollection?: boolean;
	mimeType?: string;
	encoding?: string;
	contentLength?: number;
	isVersionable?: boolean;
	isCheckedOut?: boolean;
	baseVersionName?: string;
}

export function displaySize(item: DisplayItemLike): string {
	if (!item || item.isCollection) return '';
	return Bytes.format(item.contentLength as number, { short: true });
}

export function displayType(item: DisplayItemLike): string {
	if (!item) return '';
	if (item.isCollection) return 'Folder';
	const desc = MimeTypes.description(item.mimeType || '');
	return desc || (item.mimeType || '');
}

export function displayEncoding(item: DisplayItemLike): string {
	if (!item || item.isCollection) return '';
	if (!item.encoding) return '';
	const desc = Encodings.description(item.encoding);
	return desc || item.encoding;
}

export function displayVersion(item: DisplayItemLike): string {
	if (!item || !item.isVersionable) return '';
	const version = item.baseVersionName || '';
	if (item.isCheckedOut) {
		return version + ' ✎'; // pencil
	}
	return version;
}

export function formatDetailDate(date: Date | string | number | null | undefined): string {
	if (!date) return '—';
	return (Dates.format(date as any, { format: 'friendly' }) as string) || '—';
}

export function getFileIcon(item: DisplayItemLike): string {
	if (!item) return 'bi bi-file-earmark';
	if (item.isCollection) return 'bi bi-folder-fill';

	const mimeType = item.mimeType || '';
	const [type, subtype] = mimeType.split('/');

	if (type === 'image') return 'bi bi-image';
	if (type === 'video') return 'bi bi-file-earmark-play';
	if (type === 'audio') return 'bi bi-file-earmark-music';

	if (type === 'text') {
		if (subtype === 'html' || subtype === 'css' || subtype === 'javascript' || subtype === 'xml') {
			return 'bi bi-file-earmark-code';
		}
		if (subtype === 'csv') return 'bi bi-file-earmark-spreadsheet';
		return 'bi bi-file-earmark-text';
	}

	if (type === 'application') {
		if (subtype === 'pdf') return 'bi bi-file-earmark-pdf';
		if (
			subtype === 'zip' || subtype === 'x-rar-compressed' || subtype === 'x-7z-compressed' ||
			subtype === 'x-tar' || subtype === 'gzip' || subtype === 'x-bzip2'
		) {
			return 'bi bi-file-earmark-zip';
		}
		if (
			subtype === 'msword' ||
			subtype === 'vnd.openxmlformats-officedocument.wordprocessingml.document' ||
			subtype === 'vnd.oasis.opendocument.text'
		) {
			return 'bi bi-file-earmark-word';
		}
		if (
			subtype === 'vnd.ms-excel' ||
			subtype === 'vnd.openxmlformats-officedocument.spreadsheetml.sheet' ||
			subtype === 'vnd.oasis.opendocument.spreadsheet'
		) {
			return 'bi bi-file-earmark-spreadsheet';
		}
		if (
			subtype === 'vnd.ms-powerpoint' ||
			subtype === 'vnd.openxmlformats-officedocument.presentationml.presentation' ||
			subtype === 'vnd.oasis.opendocument.presentation'
		) {
			return 'bi bi-file-earmark-slides';
		}
		if (
			subtype === 'json' || subtype === 'xml' || subtype === 'javascript' || subtype === 'x-javascript' ||
			subtype === 'typescript' || subtype === 'x-httpd-php' || subtype === 'x-python-code' ||
			subtype === 'x-sh' || subtype === 'x-yaml' || subtype === 'sql'
		) {
			return 'bi bi-file-earmark-code';
		}
	}

	return 'bi bi-file-earmark';
}

export function getFileIconClass(item: DisplayItemLike): string {
	if (!item) return 'icon-default';
	if (item.isCollection) return 'icon-folder';

	const mimeType = item.mimeType || '';
	const [type, subtype] = mimeType.split('/');

	if (type === 'image') return 'icon-image';
	if (type === 'video') return 'icon-video';
	if (type === 'audio') return 'icon-audio';

	if (type === 'text') {
		if (subtype === 'html' || subtype === 'css' || subtype === 'javascript' || subtype === 'xml') {
			return 'icon-code';
		}
		if (subtype === 'csv') return 'icon-spreadsheet';
		return 'icon-text';
	}

	if (type === 'application') {
		if (subtype === 'pdf') return 'icon-pdf';
		if (
			subtype === 'zip' || subtype === 'x-rar-compressed' || subtype === 'x-7z-compressed' ||
			subtype === 'x-tar' || subtype === 'gzip' || subtype === 'x-bzip2'
		) {
			return 'icon-archive';
		}
		if (
			subtype === 'msword' ||
			subtype === 'vnd.openxmlformats-officedocument.wordprocessingml.document' ||
			subtype === 'vnd.oasis.opendocument.text'
		) {
			return 'icon-document';
		}
		if (
			subtype === 'vnd.ms-excel' ||
			subtype === 'vnd.openxmlformats-officedocument.spreadsheetml.sheet' ||
			subtype === 'vnd.oasis.opendocument.spreadsheet'
		) {
			return 'icon-spreadsheet';
		}
		if (
			subtype === 'vnd.ms-powerpoint' ||
			subtype === 'vnd.openxmlformats-officedocument.presentationml.presentation' ||
			subtype === 'vnd.oasis.opendocument.presentation'
		) {
			return 'icon-presentation';
		}
		if (
			subtype === 'json' || subtype === 'xml' || subtype === 'javascript' || subtype === 'x-javascript' ||
			subtype === 'typescript' || subtype === 'x-httpd-php' || subtype === 'x-python-code' ||
			subtype === 'x-sh' || subtype === 'x-yaml' || subtype === 'sql'
		) {
			return 'icon-code';
		}
	}

	return 'icon-default';
}

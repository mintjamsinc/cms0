// Builds the object handed to <wt-inspector> as its `target` prop from a
// GraphQL Node. Shared by every host that embeds the Inspector (content-browser,
// text-editor, …) so the target contract stays in one place and cannot drift
// between callers.
import { isFolderNode, type Node, type LockInfo } from '../graphql/types.js';

export interface InspectorTarget {
	id: string;
	name: string;
	path: string;
	isCollection: boolean;
	downloadURL: string | null;
	created: Date | null;
	createdBy: string;
	createdByDisplayName: string | null;
	lastModified: Date | null;
	lastModifiedBy: string;
	lastModifiedByDisplayName: string | null;
	contentLength: number;
	mimeType: string;
	encoding: string;
	isLocked: boolean;
	lockInfo: LockInfo | null;
	isReferenceable: boolean;
	isVersionable: boolean;
	isCheckedOut: boolean;
	baseVersionName: string | null;
	// Labels a user puts on a file: a swatch key from the shared palette
	// (mi:color, '' when none) and free tags (mi:tags). Both live on the file's
	// jcr:content, where the search index reads them.
	color: string;
	tags: string[];
	// Set in the background for images and videos (mi:orientation: portrait /
	// landscape / square / panorama, with mi:width and mi:height in pixels);
	// '' and null until then.
	orientation: string;
	imageWidth: number | null;
	imageHeight: number | null;
}

/** The mi:color property name. */
export const COLOR_PROPERTY = 'mi:color';
/** The mi:tags property name. */
export const TAGS_PROPERTY = 'mi:tags';
/** The mi:orientation property name. */
export const ORIENTATION_PROPERTY = 'mi:orientation';
/** The mi:width property name. */
export const WIDTH_PROPERTY = 'mi:width';
/** The mi:height property name. */
export const HEIGHT_PROPERTY = 'mi:height';

// The single string value of a node property, '' when absent or not a string.
function stringProperty(node: Node, name: string): string {
	const p = (node.properties || []).find(e => e.name === name);
	const v: any = p?.propertyValue;
	if (!v || v.__typename !== 'StringPropertyValue') return '';
	return typeof v.value === 'string' ? v.value : '';
}

// The values of a multi-valued string property; a single value counts as one.
function stringArrayProperty(node: Node, name: string): string[] {
	const p = (node.properties || []).find(e => e.name === name);
	const v: any = p?.propertyValue;
	if (!v) return [];
	if (v.__typename === 'StringPropertyValueArray') {
		return (v.values || []).filter((e: any) => typeof e === 'string' && e !== '');
	}
	if (v.__typename === 'StringPropertyValue' && typeof v.value === 'string' && v.value !== '') {
		return [v.value];
	}
	return [];
}

// The integer value of a numeric property, null when absent.
function longProperty(node: Node, name: string): number | null {
	const p = (node.properties || []).find(e => e.name === name);
	const v: any = p?.propertyValue;
	if (!v || v.value == null) return null;
	const n = Number(v.value);
	return Number.isFinite(n) ? n : null;
}

export function nodeToInspectorTarget(node: Node): InspectorTarget {
	return {
		// Prefer the always-present stable JCR identifier; fall back to uuid/path only
		// for nodes fetched by a query that does not yet select `id`. Using the stable
		// identifier lets the Content Browser drop an item on a path-free nodeChanged
		// DELETE signal (a node that became unreadable) regardless of referenceability.
		id: node.id || node.uuid || node.path,
		name: node.name,
		path: node.path,
		isCollection: isFolderNode(node),
		downloadURL: node.downloadUrl ? node.downloadUrl + (node.downloadUrl.includes('?') ? '&' : '?') + 'attachment' : null,
		created: node.created ? new Date(node.created) : null,
		createdBy: node.createdBy,
		createdByDisplayName: node.createdByDisplayName ?? null,
		lastModified: node.modified ? new Date(node.modified) : null,
		lastModifiedBy: node.modifiedBy,
		lastModifiedByDisplayName: node.modifiedByDisplayName ?? null,
		contentLength: node.size || 0,
		mimeType: node.mimeType || '',
		encoding: node.encoding || '',
		isLocked: node.isLocked || false,
		lockInfo: node.lockInfo || null,
		isReferenceable: !!node.uuid,
		isVersionable: node.isVersionable || false,
		isCheckedOut: node.isCheckedOut || false,
		baseVersionName: node.baseVersionName || null,
		color: stringProperty(node, COLOR_PROPERTY),
		tags: stringArrayProperty(node, TAGS_PROPERTY),
		orientation: stringProperty(node, ORIENTATION_PROPERTY),
		imageWidth: longProperty(node, WIDTH_PROPERTY),
		imageHeight: longProperty(node, HEIGHT_PROPERTY),
	};
}

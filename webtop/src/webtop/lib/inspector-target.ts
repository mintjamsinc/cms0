// Builds the object handed to <wt-inspector> as its `target` prop from a
// GraphQL Node. Shared by every host that embeds the Inspector (content-browser,
// text-editor, …) so the target contract stays in one place and cannot drift
// between callers. See memos/wt-inspector-設計書.md §3.1.
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
}

export function nodeToInspectorTarget(node: Node): InspectorTarget {
	return {
		id: node.uuid || node.path,
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
	};
}

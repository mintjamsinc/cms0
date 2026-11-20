// The memo document: what a memo can hold and how it is stored.
//
// One definition for every editor of a memo, so that the Memo app and the
// embeddable editor (ui/memo-editor.ts, wt-memo.esm.js) accept the same
// documents and write the same file. The Memo app adds its own working
// blocks (the slash menu, dataset and conversation blocks, the drag handle,
// find & replace) on top of these; the embeddable editor shows those blocks
// as placeholders and keeps them as they are.
//
// A memo file (application/vnd.mintjams.cms.memo+json) is Tiptap JSON in a
// small envelope, { version: 1, type: 'tiptap', doc }; a bare ProseMirror
// document ({ type: 'doc', ... }) is read as well.
import type { Extensions, JSONContent } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { TextStyle, Color } from '@tiptap/extension-text-style';
import { Highlight } from '@tiptap/extension-highlight';
import { TextAlign } from '@tiptap/extension-text-align';
import { TaskList } from '@tiptap/extension-task-list';
import { TaskItem } from '@tiptap/extension-task-item';
import { Table, TableRow, TableHeader, TableCell } from '@tiptap/extension-table';
import { Image } from '@tiptap/extension-image';
import { Placeholder } from '@tiptap/extension-placeholder';
import { createPageBreakExtension } from './memo-page-break.js';

export const MEMO_MIME = 'application/vnd.mintjams.cms.memo+json';
export const MEMO_EXTENSION = 'memo';
export const MEMO_FORMAT_VERSION = 1;

export interface MemoDocumentOptions {
	/** The text an empty document shows. */
	placeholder?: string;
	/** The label of a page break on screen. */
	pageBreakLabel?: () => string;
}

/** The extensions that make the memo document, in the Memo app's order. */
export function memoDocumentExtensions(options: MemoDocumentOptions = {}): Extensions {
	return [
		StarterKit.configure({
			heading: { levels: [1, 2, 3] },
			link: {
				openOnClick: false,
				autolink: true,
				HTMLAttributes: { rel: 'noopener noreferrer nofollow', target: '_blank' },
			},
		}),
		TextStyle,
		Color,
		Highlight.configure({ multicolor: true }),
		TextAlign.configure({ types: ['heading', 'paragraph'] }),
		TaskList,
		TaskItem.configure({ nested: true }),
		Table.configure({ resizable: true }),
		TableRow,
		TableHeader,
		TableCell,
		// Images are repository assets the memo points at (see the Memo app's
		// drop handling). `resize` turns on Tiptap's ResizableNodeView: corner
		// grips on a selected image, the ratio kept; the size is stored as the
		// node's width / height.
		Image.configure({
			inline: false,
			resize: {
				enabled: true,
				alwaysPreserveAspectRatio: true,
				minWidth: 48,
				minHeight: 48,
			},
		}),
		Placeholder.configure({ placeholder: options.placeholder || '' }),
		// Where the printed memo moves on to the next page (memo-page-break.ts).
		createPageBreakExtension({
			label: options.pageBreakLabel || (() => 'Page break'),
		}),
	];
}

export function emptyMemoDocument(): JSONContent {
	return { type: 'doc', content: [{ type: 'paragraph' }] };
}

/** The document of a memo file's text; an empty document for nothing or for JSON of another shape. */
export function parseMemoDocument(raw: string | null | undefined): JSONContent {
	const text = (raw || '').trim();
	if (!text) return emptyMemoDocument();
	try {
		const parsed = JSON.parse(text);
		if (parsed && parsed.type === 'tiptap' && parsed.doc) return parsed.doc;
		if (parsed && parsed.type === 'doc') return parsed;
	} catch {
		// not JSON
	}
	return emptyMemoDocument();
}

/** The text of a memo file holding the document. */
export function serializeMemoDocument(doc: JSONContent): string {
	return JSON.stringify({ version: MEMO_FORMAT_VERSION, type: 'tiptap', doc });
}

// Markdown editor bundle (built to dist/webtop/ui/wt-markdown.esm.js).
//
// A rich-text editor whose document is Markdown: what an app reads from a
// Markdown file goes in, what the person writes comes out as Markdown again,
// and the person never sees the syntax. Tiptap (ProseMirror) with the
// official Markdown extension, the starter kit (headings, lists, emphasis,
// links, code, quotes, rules), images and tables. Apps import this ONE module
// beside wt-ui.esm.js; the Site app's page editor is the first.
//
//     const { createMarkdownEditor } = await import('../../ui/wt-markdown.esm.js');
//     const handle = createMarkdownEditor({
//         element: el, content: markdown, placeholder: '…',
//         resolveImageSrc: (src) => downloadUrlOf(src),   // how the editor shows an image the Markdown names
//         serializeImageSrc: (src) => nameOf(src),        // how the Markdown names an image the editor shows
//         uploadImage: async (file) => await upload(file), // a dropped or pasted picture; returns its address, or null
//         onUpdate: () => mark dirty,
//     });
//     handle.getMarkdown(); handle.setMarkdown(md); handle.editor.chain().focus().toggleBold().run();
//
// The stylesheet wt-markdown.css, deployed next to this bundle, styles the
// document under .wt-markdown; initMarkdownEditorCss() appends it once.

import { Editor, type Extensions, type JSONContent } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { Image } from '@tiptap/extension-image';
import { Placeholder } from '@tiptap/extension-placeholder';
import { Table, TableCell, TableHeader, TableRow } from '@tiptap/extension-table';
import { Markdown } from '@tiptap/markdown';
import { BUILD_VERSION } from '../utils/build-version.js';

export { Editor };

export interface MarkdownEditorOptions {
	/** The element the editor is mounted in; it gets the class wt-markdown. */
	element: HTMLElement;
	/** The Markdown to start with. */
	content?: string;
	placeholder?: string;
	editable?: boolean;
	/** Called after every change of the document. */
	onUpdate?: (handle: MarkdownEditorHandle) => void;
	/** Called when the selection or the document changed, for a toolbar's state. */
	onSelectionUpdate?: (handle: MarkdownEditorHandle) => void;
	/**
	 * The address the editor shows an image at, from the address the Markdown
	 * names (a relative name such as `cover.jpg`). Identity when absent.
	 */
	resolveImageSrc?: (src: string) => string;
	/** The reverse: the address the Markdown names, from the one the editor shows. Identity when absent. */
	serializeImageSrc?: (src: string) => string;
	/**
	 * Stores a picture dropped or pasted into the document and returns the
	 * address the editor shows it at (resolveImageSrc is not applied to it),
	 * or null to refuse it. Without this, dropped pictures are ignored.
	 */
	uploadImage?: (file: File) => Promise<string | null>;
	/** Further Tiptap extensions. */
	extensions?: Extensions;
}

export interface MarkdownEditorHandle {
	readonly editor: Editor;
	/** The document as Markdown, images named as serializeImageSrc says. */
	getMarkdown(): string;
	/** Replaces the document; images are shown as resolveImageSrc says. */
	setMarkdown(markdown: string): void;
	/** Puts a picture at the caret, by the address the editor shows it at. */
	insertImage(src: string, alt?: string): void;
	setEditable(editable: boolean): void;
	focus(): void;
	destroy(): void;
}

const IMAGE_TYPES = /^image\//;

/** Maps the src of every image node of a Tiptap JSON document, in place. */
function mapImageSrc(content: JSONContent, map: (src: string) => string): JSONContent {
	if (content.type === 'image' && content.attrs && typeof content.attrs.src === 'string') {
		content.attrs = { ...content.attrs, src: map(content.attrs.src) };
	}
	for (const child of content.content || []) {
		mapImageSrc(child, map);
	}
	return content;
}

function imageFiles(items: DataTransferItemList | null | undefined, files: FileList | null | undefined): File[] {
	const found: File[] = [];
	if (items) {
		for (const item of Array.from(items)) {
			if (item.kind === 'file') {
				const file = item.getAsFile();
				if (file && IMAGE_TYPES.test(file.type)) found.push(file);
			}
		}
	}
	if (!found.length && files) {
		for (const file of Array.from(files)) {
			if (IMAGE_TYPES.test(file.type)) found.push(file);
		}
	}
	return found;
}

export function createMarkdownEditor(options: MarkdownEditorOptions): MarkdownEditorHandle {
	const resolve = options.resolveImageSrc || ((s: string) => s);
	const serialize = options.serializeImageSrc || ((s: string) => s);
	const upload = options.uploadImage;
	options.element.classList.add('wt-markdown');

	let handle: MarkdownEditorHandle;

	// A dropped or pasted picture is stored first, then put where it landed.
	const insertUploaded = async (editor: Editor, files: File[], pos: number | null) => {
		if (!upload) return;
		for (const file of files) {
			let src: string | null = null;
			try {
				src = await upload(file);
			} catch {
				src = null;
			}
			if (!src) continue;
			let chain = editor.chain().focus();
			if (pos != null) chain = chain.setTextSelection(pos);
			chain.insertContent({ type: 'image', attrs: { src, alt: file.name.replace(/\.[^.]+$/, '') } }).run();
			pos = null;
		}
	};

	const editor = new Editor({
		element: options.element,
		editable: options.editable !== false,
		content: mapImageSrc(parseMarkdown(options.content || ''), resolve),
		extensions: [
			StarterKit.configure({
				// Markdown has no underline; a link's address is edited by the app.
				underline: false,
				link: { openOnClick: false, autolink: true, defaultProtocol: 'https' },
			}),
			Image.configure({ inline: false, allowBase64: false }),
			Table.configure({ resizable: false }),
			TableRow,
			TableHeader,
			TableCell,
			Placeholder.configure({ placeholder: options.placeholder || '' }),
			Markdown,
			...(options.extensions || []),
		],
		editorProps: {
			handleDrop(view, event) {
				const files = imageFiles(event.dataTransfer?.items, event.dataTransfer?.files);
				if (!files.length || !upload) return false;
				event.preventDefault();
				const at = view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos ?? null;
				void insertUploaded(editor, files, at);
				return true;
			},
			handlePaste(_view, event) {
				const files = imageFiles(event.clipboardData?.items, event.clipboardData?.files);
				if (!files.length || !upload) return false;
				event.preventDefault();
				void insertUploaded(editor, files, null);
				return true;
			},
		},
		onUpdate: () => options.onUpdate?.(handle),
		onSelectionUpdate: () => options.onSelectionUpdate?.(handle),
		onTransaction: () => options.onSelectionUpdate?.(handle),
	});

	// The Markdown manager parses before the editor exists for the first
	// content, so the first parse goes through a throwaway editor's manager.
	function parseMarkdown(markdown: string): JSONContent {
		if (!markdown.trim()) return { type: 'doc', content: [{ type: 'paragraph' }] };
		const probe = new Editor({
			extensions: [StarterKit.configure({ underline: false }), Image, Table, TableRow, TableHeader, TableCell, Markdown],
			content: markdown,
			contentType: 'markdown',
		});
		try {
			return probe.getJSON();
		} finally {
			probe.destroy();
		}
	}

	handle = {
		editor,
		getMarkdown() {
			const json = mapImageSrc(JSON.parse(JSON.stringify(editor.getJSON())), serialize);
			return editor.markdown ? editor.markdown.serialize(json) : editor.getMarkdown();
		},
		setMarkdown(markdown: string) {
			const json = editor.markdown ? editor.markdown.parse(markdown || '') : parseMarkdown(markdown || '');
			editor.commands.setContent(mapImageSrc(json, resolve), { emitUpdate: false });
		},
		insertImage(src: string, alt?: string) {
			editor.chain().focus().insertContent({ type: 'image', attrs: { src, alt: alt || null } }).run();
		},
		setEditable(editable: boolean) {
			editor.setEditable(editable);
		},
		focus() {
			editor.commands.focus();
		},
		destroy() {
			editor.destroy();
		},
	};
	return handle;
}

let cssReady: Promise<void> | undefined;

/** Appends wt-markdown.css (deployed next to this bundle) once; resolves when it applied. */
export function initMarkdownEditorCss(): Promise<void> {
	if (cssReady) return cssReady;
	cssReady = new Promise<void>((resolve) => {
		const link = document.createElement('link');
		link.rel = 'stylesheet';
		link.href = new URL(`wt-markdown.css?v=${BUILD_VERSION}`, import.meta.url).href;
		link.onload = link.onerror = () => resolve();
		document.head.appendChild(link);
	});
	return cssReady;
}

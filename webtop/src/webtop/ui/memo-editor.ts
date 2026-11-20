// Memo editor bundle (built to dist/webtop/ui/wt-memo.esm.js).
//
// The Memo app's document and formatting toolbar, for a page that edits a
// memo file outside the Memo app: a task form showing the memo of a ledger
// row, say. The document is the Memo app's own (lib/memo-document.ts: the
// same extensions, the same file format) and so is its look (wt-memo.css,
// which the Memo app links as well), so what is written here reads the same
// in the Memo app and the other way round. Pages import this ONE module
// beside wt-ui.esm.js:
//
//     const { createMemoEditor, initMemoEditorCss } =
//         await import(new URL('ui/wt-memo.esm.js', host.webtopBaseUrl).href);
//     await initMemoEditorCss();
//     const memo = createMemoEditor({
//         element: el,                 // the box; the toolbar and the document are built inside
//         content: text,               // a memo file's text (envelope or bare document)
//         editable: true,
//         locale: 'ja',                // the toolbar's own labels (en, ja); or pass t()
//         onUpdate: (memo) => { dirty = memo.getContent() !== saved; },
//     });
//     memo.getContent();               // the memo file's text to write back
//     memo.setContent(text);           // replace, without an update
//     memo.setEditable(false);
//
// What the Memo app alone does is left out: the slash menu, the drag handle,
// find & replace, dropping files. A dataset or conversation block the memo
// holds is shown as a note and kept as it is.
//
// The text and highlight colours come from the shared swatch palette
// (lib/color-palette.ts) as in the Memo app; the colour and link pickers are
// small panels of the editor's own, since a page is not the shell.

import { Editor, Node as TiptapNode, type JSONContent } from '@tiptap/core';
import { BUILD_VERSION } from '../utils/build-version.js';
import { SWATCH_COLORS, SWATCH_HIGHLIGHT_COLORS, type SwatchColor } from '../lib/color-palette.js';
import { memoDocumentExtensions, parseMemoDocument, serializeMemoDocument } from '../lib/memo-document.js';

export { Editor };
export { MEMO_MIME, parseMemoDocument, serializeMemoDocument } from '../lib/memo-document.js';

export type MemoTranslate = (key: string, fallback: string) => string;

export interface MemoEditorOptions {
	/** The box the editor is built in; it gets the classes wt-memo and wt-memo-embedded. */
	element: HTMLElement;
	/** The memo file's text to start with (empty: an empty memo). */
	content?: string;
	editable?: boolean;
	/** Whether the formatting toolbar is shown (default true). */
	toolbar?: boolean;
	placeholder?: string;
	/** The language of the toolbar's labels when no t() is given: 'ja', or English. */
	locale?: string;
	/** The toolbar's labels; keys as in the Memo app (app.memo.format.bold, ...). */
	t?: MemoTranslate;
	/** Called after every change of the document by the person. */
	onUpdate?: (handle: MemoEditorHandle) => void;
}

export interface MemoEditorHandle {
	readonly editor: Editor;
	/** The memo file's text: the document in the Memo app's envelope. */
	getContent(): string;
	/** The document as JSON. */
	getDocument(): JSONContent;
	/** Replaces the document with a memo file's text; no update is reported. */
	setContent(text: string): void;
	isEmpty(): boolean;
	setEditable(editable: boolean): void;
	focus(): void;
	destroy(): void;
}

// ---- stylesheet --------------------------------------------------------------

let cssReady: Promise<void> | undefined;

/**
 * Appends wt-memo.css (deployed next to this bundle) once and resolves when it
 * has applied, or failed to: an unstyled editor beats one that never shows.
 */
export function initMemoEditorCss(): Promise<void> {
	if (cssReady) return cssReady;
	cssReady = new Promise<void>((resolve) => {
		const link = document.createElement('link');
		link.rel = 'stylesheet';
		link.href = new URL(`wt-memo.css?v=${BUILD_VERSION}`, import.meta.url).href;
		link.onload = link.onerror = () => resolve();
		document.head.appendChild(link);
	});
	return cssReady;
}

// ---- labels ------------------------------------------------------------------

const MESSAGES_JA: Record<string, string> = {
	'app.memo.format.paragraph': '本文',
	'app.memo.format.h1': '見出し 1',
	'app.memo.format.h2': '見出し 2',
	'app.memo.format.h3': '見出し 3',
	'app.memo.format.bold': '太字 (Ctrl+B)',
	'app.memo.format.italic': '斜体 (Ctrl+I)',
	'app.memo.format.underline': '下線 (Ctrl+U)',
	'app.memo.format.strike': '打ち消し線',
	'app.memo.format.code': 'インラインコード',
	'app.memo.format.highlight': 'ハイライトカラー',
	'app.memo.format.color': '文字色',
	'app.memo.format.colorClear': '既定',
	'app.memo.format.link': 'リンク',
	'app.memo.link.url': 'URL',
	'app.memo.link.apply': 'OK',
	'app.memo.link.remove': 'リンクを解除',
	'app.memo.format.bulletList': '箇条書き',
	'app.memo.format.orderedList': '番号付きリスト',
	'app.memo.format.taskList': 'タスクリスト',
	'app.memo.format.blockquote': '引用',
	'app.memo.format.codeBlock': 'コードブロック',
	'app.memo.format.alignLeft': '左揃え',
	'app.memo.format.alignCenter': '中央揃え',
	'app.memo.format.alignRight': '右揃え',
	'app.memo.format.table': '表を挿入',
	'app.memo.format.divider': '区切り線',
	'app.memo.format.pageBreak': '改ページ (Ctrl+Enter)',
	'app.memo.pageBreak': '改ページ',
	'app.memo.table.addColumn': '列を追加',
	'app.memo.table.addRow': '行を追加',
	'app.memo.table.deleteColumn': '列を削除',
	'app.memo.table.deleteRow': '行を削除',
	'app.memo.table.headerRow': 'ヘッダー行の切り替え',
	'app.memo.table.deleteTable': '表を削除',
	'app.memo.foreign.dataset': 'データセット（Memo アプリで表示します）',
	'app.memo.foreign.chat': '会話（Memo アプリで表示します）',
	'app.memo.color.tomato': 'トマト',
	'app.memo.color.tangerine': 'タンジェリン',
	'app.memo.color.banana': 'バナナ',
	'app.memo.color.basil': 'バジル',
	'app.memo.color.sage': 'セージ',
	'app.memo.color.peacock': 'ピーコック',
	'app.memo.color.blueberry': 'ブルーベリー',
	'app.memo.color.lavender': 'ラベンダー',
	'app.memo.color.grape': 'グレープ',
	'app.memo.color.flamingo': 'フラミンゴ',
	'app.memo.color.graphite': 'グラファイト',
};

function translator(options: MemoEditorOptions): MemoTranslate {
	if (options.t) return options.t;
	const ja = /^ja\b/i.test(options.locale || '');
	return (key, fallback) => (ja && MESSAGES_JA[key]) || fallback;
}

// ---- blocks only the Memo app draws ---------------------------------------
//
// A dataset or conversation block in the memo must survive an edit here, so
// each has a stand-in of the same name and attributes (defaults as in the
// Memo app's dataset-view.ts / chat-view.ts, so a block written back reads
// there exactly as it was) that shows a note instead of the block.

function foreignBlock(name: string, tag: string, attributes: Record<string, unknown>, label: () => string) {
	return TiptapNode.create({
		name,
		group: 'block',
		atom: true,
		selectable: true,
		draggable: false,
		addAttributes() {
			const out: Record<string, { default: unknown; rendered: boolean }> = {};
			for (const [key, value] of Object.entries(attributes)) out[key] = { default: value, rendered: false };
			return out;
		},
		parseHTML() {
			return [{ tag: `div[${tag}]` }];
		},
		renderHTML() {
			return ['div', { [tag]: '' }];
		},
		addNodeView() {
			return () => {
				const dom = document.createElement('div');
				dom.className = 'wt-memo-foreign-block';
				dom.contentEditable = 'false';
				const icon = document.createElement('i');
				icon.className = name === 'chatView' ? 'bi bi-chat-dots' : 'bi bi-database';
				const text = document.createElement('span');
				text.textContent = label();
				dom.append(icon, text);
				return { dom };
			};
		},
	});
}

// ---- the editor --------------------------------------------------------------

interface ToolButton {
	icon: string;
	badge?: string;
	key: string;
	fallback: string;
	run: (editor: Editor, button: HTMLButtonElement) => void;
	active?: (editor: Editor) => boolean;
	danger?: boolean;
}

export function createMemoEditor(options: MemoEditorOptions): MemoEditorHandle {
	const t = translator(options);
	const box = options.element;
	box.classList.add('wt-memo', 'wt-memo-embedded');
	box.replaceChildren();

	const bar = document.createElement('div');
	bar.className = 'format-bar';
	const body = document.createElement('div');
	body.className = 'wt-memo-body';
	if (options.toolbar !== false) box.appendChild(bar);
	box.appendChild(body);

	let handle: MemoEditorHandle;
	let popover: HTMLElement | null = null;
	let editable = options.editable !== false;

	const editor = new Editor({
		element: body,
		editable,
		content: parseMemoDocument(options.content),
		extensions: [
			...memoDocumentExtensions({
				placeholder: options.placeholder || '',
				pageBreakLabel: () => t('app.memo.pageBreak', 'Page break'),
			}),
			foreignBlock('datasetView', 'data-dataset-view', {
				path: '', view: 'table', hidden: [], sort: null, group: '', date: '', chart: 'bar',
				x: '', y: '', agg: 'count', series: '', bucket: 'month', start: '', end: '', widths: {}, order: [],
			}, () => t('app.memo.foreign.dataset', 'A dataset (shown in the Memo app)')),
			foreignBlock('chatView', 'data-chat-view', { file: '', channel: '' },
				() => t('app.memo.foreign.chat', 'A conversation (shown in the Memo app)')),
		],
		onUpdate: () => options.onUpdate?.(handle),
		onTransaction: () => refreshBar(),
	});

	// ---- popovers (colours, link) ----

	const closePopover = () => {
		if (popover) {
			popover.remove();
			popover = null;
		}
	};
	const onOutside = (event: MouseEvent) => {
		if (popover && !popover.contains(event.target as globalThis.Node)) closePopover();
	};
	document.addEventListener('mousedown', onOutside, true);

	const openPopover = (button: HTMLElement, fill: (panel: HTMLElement) => void) => {
		closePopover();
		const panel = document.createElement('div');
		panel.className = 'wt-memo-popover';
		fill(panel);
		box.style.position = box.style.position || 'relative';
		box.appendChild(panel);
		const boxRect = box.getBoundingClientRect();
		const r = button.getBoundingClientRect();
		const left = Math.max(0, Math.min(r.left - boxRect.left, box.clientWidth - panel.offsetWidth - 4));
		panel.style.left = `${left}px`;
		panel.style.top = `${r.bottom - boxRect.top + 4}px`;
		popover = panel;
	};

	const openSwatches = (button: HTMLElement, kind: 'text' | 'highlight') => {
		const palette: SwatchColor[] = kind === 'highlight' ? SWATCH_HIGHLIGHT_COLORS : SWATCH_COLORS;
		const current = String((kind === 'text' ? editor.getAttributes('textStyle').color : editor.getAttributes('highlight').color) || '').toLowerCase();
		openPopover(button, (panel) => {
			const grid = document.createElement('div');
			grid.className = 'wt-memo-swatches';
			const apply = (value: string | null) => {
				const chain = editor.chain().focus();
				if (kind === 'text') {
					if (value) chain.setColor(value).run(); else chain.unsetColor().run();
				} else if (value) {
					chain.setHighlight({ color: value }).run();
				} else {
					chain.unsetHighlight().run();
				}
				closePopover();
			};
			for (const c of palette) {
				const swatch = document.createElement('button');
				swatch.type = 'button';
				swatch.className = 'wt-memo-swatch' + (c.value.toLowerCase() === current ? ' is-selected' : '');
				swatch.style.backgroundColor = c.value;
				swatch.title = t('app.memo.color.' + c.key, c.label);
				swatch.addEventListener('mousedown', (e) => { e.preventDefault(); apply(c.value); });
				grid.appendChild(swatch);
			}
			const clear = document.createElement('button');
			clear.type = 'button';
			clear.className = 'wt-memo-swatch' + (current === '' ? ' is-selected' : '');
			clear.title = t('app.memo.format.colorClear', 'Default');
			clear.innerHTML = '<i class="bi bi-x-lg"></i>';
			clear.addEventListener('mousedown', (e) => { e.preventDefault(); apply(null); });
			grid.appendChild(clear);
			panel.appendChild(grid);
		});
	};

	const openLink = (button: HTMLElement) => {
		const existing = editor.isActive('link') ? String(editor.getAttributes('link').href || '') : '';
		openPopover(button, (panel) => {
			const form = document.createElement('form');
			form.className = 'wt-memo-link-form';
			const input = document.createElement('input');
			input.type = 'url';
			input.className = 'wt';
			input.placeholder = t('app.memo.link.url', 'URL');
			input.value = existing;
			const ok = document.createElement('button');
			ok.type = 'submit';
			ok.className = 'wt wt-primary';
			ok.textContent = t('app.memo.link.apply', 'OK');
			form.append(input, ok);
			if (existing) {
				const remove = document.createElement('button');
				remove.type = 'button';
				remove.className = 'wt';
				remove.textContent = t('app.memo.link.remove', 'Remove link');
				remove.addEventListener('click', () => {
					editor.chain().focus().extendMarkRange('link').unsetLink().run();
					closePopover();
				});
				form.appendChild(remove);
			}
			form.addEventListener('submit', (e) => {
				e.preventDefault();
				const url = input.value.trim();
				const chain = editor.chain().focus().extendMarkRange('link');
				if (url) chain.setLink({ href: url }).run(); else chain.unsetLink().run();
				closePopover();
			});
			panel.appendChild(form);
			setTimeout(() => { input.focus(); input.select(); }, 0);
		});
	};

	// ---- the toolbar: the Memo app's groups, in its order ----

	const groups: ToolButton[][] = [
		[
			{ icon: 'bi-text-paragraph', key: 'app.memo.format.paragraph', fallback: 'Text', run: (e) => e.chain().focus().setParagraph().run(), active: (e) => e.isActive('paragraph') },
			{ icon: 'bi-type-h1', key: 'app.memo.format.h1', fallback: 'Heading 1', run: (e) => e.chain().focus().toggleHeading({ level: 1 }).run(), active: (e) => e.isActive('heading', { level: 1 }) },
			{ icon: 'bi-type-h2', key: 'app.memo.format.h2', fallback: 'Heading 2', run: (e) => e.chain().focus().toggleHeading({ level: 2 }).run(), active: (e) => e.isActive('heading', { level: 2 }) },
			{ icon: 'bi-type-h3', key: 'app.memo.format.h3', fallback: 'Heading 3', run: (e) => e.chain().focus().toggleHeading({ level: 3 }).run(), active: (e) => e.isActive('heading', { level: 3 }) },
		],
		[
			{ icon: 'bi-type-bold', key: 'app.memo.format.bold', fallback: 'Bold (Ctrl+B)', run: (e) => e.chain().focus().toggleBold().run(), active: (e) => e.isActive('bold') },
			{ icon: 'bi-type-italic', key: 'app.memo.format.italic', fallback: 'Italic (Ctrl+I)', run: (e) => e.chain().focus().toggleItalic().run(), active: (e) => e.isActive('italic') },
			{ icon: 'bi-type-underline', key: 'app.memo.format.underline', fallback: 'Underline (Ctrl+U)', run: (e) => e.chain().focus().toggleUnderline().run(), active: (e) => e.isActive('underline') },
			{ icon: 'bi-type-strikethrough', key: 'app.memo.format.strike', fallback: 'Strikethrough', run: (e) => e.chain().focus().toggleStrike().run(), active: (e) => e.isActive('strike') },
			{ icon: 'bi-code', key: 'app.memo.format.code', fallback: 'Inline code', run: (e) => e.chain().focus().toggleCode().run(), active: (e) => e.isActive('code') },
			{ icon: 'bi-highlighter', key: 'app.memo.format.highlight', fallback: 'Highlight color', run: (_e, b) => openSwatches(b, 'highlight'), active: (e) => e.isActive('highlight') },
			{ icon: 'bi-palette', key: 'app.memo.format.color', fallback: 'Text color', run: (_e, b) => openSwatches(b, 'text') },
			{ icon: 'bi-link-45deg', key: 'app.memo.format.link', fallback: 'Link', run: (_e, b) => openLink(b), active: (e) => e.isActive('link') },
		],
		[
			{ icon: 'bi-list-ul', key: 'app.memo.format.bulletList', fallback: 'Bullet list', run: (e) => e.chain().focus().toggleBulletList().run(), active: (e) => e.isActive('bulletList') },
			{ icon: 'bi-list-ol', key: 'app.memo.format.orderedList', fallback: 'Numbered list', run: (e) => e.chain().focus().toggleOrderedList().run(), active: (e) => e.isActive('orderedList') },
			{ icon: 'bi-list-check', key: 'app.memo.format.taskList', fallback: 'Task list', run: (e) => e.chain().focus().toggleTaskList().run(), active: (e) => e.isActive('taskList') },
			{ icon: 'bi-quote', key: 'app.memo.format.blockquote', fallback: 'Quote', run: (e) => e.chain().focus().toggleBlockquote().run(), active: (e) => e.isActive('blockquote') },
			{ icon: 'bi-code-square', key: 'app.memo.format.codeBlock', fallback: 'Code block', run: (e) => e.chain().focus().toggleCodeBlock().run(), active: (e) => e.isActive('codeBlock') },
		],
		[
			{ icon: 'bi-text-left', key: 'app.memo.format.alignLeft', fallback: 'Align left', run: (e) => e.chain().focus().setTextAlign('left').run(), active: (e) => e.isActive({ textAlign: 'left' }) },
			{ icon: 'bi-text-center', key: 'app.memo.format.alignCenter', fallback: 'Align center', run: (e) => e.chain().focus().setTextAlign('center').run(), active: (e) => e.isActive({ textAlign: 'center' }) },
			{ icon: 'bi-text-right', key: 'app.memo.format.alignRight', fallback: 'Align right', run: (e) => e.chain().focus().setTextAlign('right').run(), active: (e) => e.isActive({ textAlign: 'right' }) },
		],
		[
			{ icon: 'bi-table', key: 'app.memo.format.table', fallback: 'Insert table', run: (e) => e.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run() },
			{ icon: 'bi-dash-lg', key: 'app.memo.format.divider', fallback: 'Divider', run: (e) => e.chain().focus().setHorizontalRule().run() },
			{ icon: 'bi-file-break', key: 'app.memo.format.pageBreak', fallback: 'Page break (Ctrl+Enter)', run: (e) => e.chain().focus().setPageBreak().run() },
		],
	];
	// Shown while the cursor is in a table, as in the Memo app.
	const tableGroup: ToolButton[] = [
		{ icon: 'bi-layout-three-columns', badge: 'bi-plus-lg', key: 'app.memo.table.addColumn', fallback: 'Add column', run: (e) => e.chain().focus().addColumnAfter().run() },
		{ icon: 'bi-list-ul', badge: 'bi-plus-lg', key: 'app.memo.table.addRow', fallback: 'Add row', run: (e) => e.chain().focus().addRowAfter().run() },
		{ icon: 'bi-layout-three-columns', badge: 'bi-dash-lg', key: 'app.memo.table.deleteColumn', fallback: 'Delete column', run: (e) => e.chain().focus().deleteColumn().run() },
		{ icon: 'bi-list-ul', badge: 'bi-dash-lg', key: 'app.memo.table.deleteRow', fallback: 'Delete row', run: (e) => e.chain().focus().deleteRow().run() },
		{ icon: 'bi-table', key: 'app.memo.table.headerRow', fallback: 'Toggle header row', run: (e) => e.chain().focus().toggleHeaderRow().run() },
		{ icon: 'bi-trash', key: 'app.memo.table.deleteTable', fallback: 'Delete table', run: (e) => e.chain().focus().deleteTable().run(), danger: true },
	];

	const actives: Array<{ el: HTMLButtonElement; active: (e: Editor) => boolean }> = [];
	const makeGroup = (buttons: ToolButton[]): HTMLElement => {
		const group = document.createElement('div');
		group.className = 'format-group';
		for (const b of buttons) {
			const el = document.createElement('button');
			el.type = 'button';
			el.className = 'fmt-btn' + (b.danger ? ' fmt-btn-danger' : '');
			el.title = t(b.key, b.fallback);
			el.innerHTML = `<i class="bi ${b.icon}"></i>` + (b.badge ? `<i class="bi ${b.badge} fmt-badge"></i>` : '');
			// mousedown, not click: the editor keeps its selection.
			el.addEventListener('mousedown', (event) => {
				event.preventDefault();
				if (!editable) return;
				b.run(editor, el);
			});
			if (b.active) actives.push({ el, active: b.active });
			group.appendChild(el);
		}
		return group;
	};
	const divider = () => {
		const d = document.createElement('span');
		d.className = 'format-divider';
		return d;
	};
	groups.forEach((g, i) => {
		if (i) bar.appendChild(divider());
		bar.appendChild(makeGroup(g));
	});
	const tableDivider = divider();
	const tableEl = makeGroup(tableGroup);
	bar.append(tableDivider, tableEl);

	function refreshBar() {
		if (!editor || editor.isDestroyed) return;
		for (const a of actives) a.el.classList.toggle('active', a.active(editor));
		const inTable = editor.isActive('table');
		tableDivider.style.display = inTable ? '' : 'none';
		tableEl.style.display = inTable ? '' : 'none';
	}

	const applyEditable = () => {
		box.classList.toggle('is-readonly', !editable);
		for (const button of Array.from(bar.querySelectorAll('button'))) (button as HTMLButtonElement).disabled = !editable;
		if (!editable) closePopover();
	};
	applyEditable();
	refreshBar();

	handle = {
		editor,
		getContent() {
			return serializeMemoDocument(editor.getJSON());
		},
		getDocument() {
			return editor.getJSON();
		},
		setContent(text: string) {
			editor.commands.setContent(parseMemoDocument(text), { emitUpdate: false });
		},
		isEmpty() {
			return editor.isEmpty;
		},
		setEditable(value: boolean) {
			editable = !!value;
			editor.setEditable(editable, false);
			applyEditable();
		},
		focus() {
			editor.commands.focus();
		},
		destroy() {
			document.removeEventListener('mousedown', onOutside, true);
			closePopover();
			editor.destroy();
			box.replaceChildren();
			box.classList.remove('wt-memo', 'wt-memo-embedded', 'is-readonly');
		},
	};
	return handle;
}

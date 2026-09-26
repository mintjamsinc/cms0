// Dataset block for the Memo editor.
//
// A dataset is a folder carrying a `.dataset.yml` descriptor (see
// Datasets.java); its direct child files are rows and the descriptor declares
// the typed properties (columns) each row carries. This block embeds one such
// folder in a memo and shows it three ways: a table with editable cells, a
// board whose lanes are the choices of one text column, and a calendar placing
// rows on the days of one date column. The rows are read through the search
// index (an XPath statement over the folder), every edit goes to the row's
// properties through setProperties, and the descriptor is rewritten when a
// column is added, changed or removed. The memo itself stores only where the
// dataset lives and how the block shows it (path, view, hidden columns, sort,
// column order and widths, the board's group column, the calendar's date
// column, full width); the data stays in the repository, where every other
// memo, the Content Browser and the Inspector see the same rows.
//
// The block has two faces. In the document it shows the rows, with a header
// (view switch, the view's own controls, the block menu) that appears only
// while the block is active: the cursor is in it. While it is active the
// block also hands the host a pane — the dataset's name, path and row count,
// and the column list with its add / edit / delete forms — which the host
// shows in the Inspector in place of the memo's own details. Both faces are
// rendered here, so the pane is only ever a second view of the same state.
//
// The block is a Tiptap atom node with a hand-written node view: the editor
// never looks inside it, so the inputs and selects it renders are not part of
// the document and typing in them does not reach ProseMirror.
import { Node as TiptapNode, mergeAttributes } from '@tiptap/core';
import type { Editor } from '@tiptap/core';
import type { Node as PMNode } from '@tiptap/pm/model';
import type { NodeView } from '@tiptap/pm/view';
import type { Node as GNode, Dataset, DatasetProperty, DatasetPropertyType, PropertyInput } from '../../graphql/types.js';
import { Dates } from '../../utils/dates.js';
import { SWATCH_COLORS, SWATCH_COLOR_MAP, SWATCH_HIGHLIGHT_COLOR_MAP } from '../../lib/color-palette.js';

export const DATASET_VIEW_NODE = 'datasetView';
export const DATASET_DESCRIPTOR_NAME = '.dataset.yml';
const DATASET_DESCRIPTOR_MIME = 'application/yaml';
const MEMO_MIME = 'application/vnd.mintjams.cms.memo+json';
const MEMO_EXTENSION = 'memo';
// Rows read per block. A dataset is a working list, not an archive; past this
// the pane says how many rows it left out.
const ROW_LIMIT = 500;
const TYPES: DatasetPropertyType[] = ['STRING', 'LONG', 'DOUBLE', 'DECIMAL', 'BOOLEAN', 'DATE'];
const NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_]*$/;
// Marks the block's root so the memo's drop handler can hand a dropped folder
// to the block instead of opening it (see handleDropEvent in app.ts).
export const DATASET_DROP_HANDLER = '__datasetDrop';
// Drag payload of a row moved between board lanes or calendar days, and of a
// table column moved to another position. Types of their own, so the memo's
// file-drop handling ignores them.
const ROW_DRAG_TYPE = 'application/x-memo-dataset-row';
const COLUMN_DRAG_TYPE = 'application/x-memo-dataset-column';
const VIEWS = ['table', 'board', 'calendar'] as const;
type ViewKind = typeof VIEWS[number];
const VIEW_ICONS: Record<ViewKind, string> = { table: 'bi-table', board: 'bi-kanban', calendar: 'bi-calendar3' };
// Table column widths, in pixels. The name column has no descriptor key, so
// its width is stored under a key no column can have (keys start with a
// letter).
const NAME_COLUMN_KEY = '$name';
const DEFAULT_NAME_WIDTH = 200;
const DEFAULT_COLUMN_WIDTH = 160;
const DEFAULT_BOOLEAN_WIDTH = 96;
const MIN_COLUMN_WIDTH = 64;
// Text on a colored chip: the pale swatch tints are light in both themes, so
// the ink is pinned dark, as the memo's highlight marks are.
const CHIP_TEXT_COLOR = '#202124';

// What the block needs from the hosting app. Kept as callbacks so the node
// view, which lives outside the reactive component, never holds a Proxy.
export interface DatasetViewHost {
	api(): any;
	popup(): any;
	t(key: string, params?: Record<string, any>, fallback?: string): string;
	timeZone(): string | undefined;
	locale(): string | undefined;
	// Open a row (a memo file) in a tab of this app.
	openPath(path: string): void;
	// Path of the memo the block sits in; '' while the memo is unsaved.
	activeMemoPath(): string;
	// The block that holds the cursor now: the host shows `pane.el` in the
	// Inspector in place of the memo's own details, and deactivates the block
	// that was active before. deactivatePane clears the pane only when it is
	// the active one, so a block going away cannot unseat its successor.
	activatePane(pane: DatasetPane): void;
	deactivatePane(pane: DatasetPane): void;
	// Show the Inspector: the user asked for a form that lives in the pane.
	revealPane(): void;
	// Ask the user to confirm a deletion in the app's own dialog (the same
	// kind as the unsaved-changes one). Resolves to whether they confirmed.
	confirmDelete(title: string, message: string): Promise<boolean>;
}

// The block's Inspector face, handed to the host while the block is active.
export interface DatasetPane {
	// The pane's content, owned and re-rendered by the block; the host only
	// places it.
	el: HTMLElement;
	// The block's root in the document, so the host can tell a click inside
	// the block from one elsewhere in the editor.
	blockEl: HTMLElement;
	// Called by the host when the cursor moves elsewhere in the document.
	deactivate(): void;
}

export interface DatasetViewAttrs {
	path: string;
	view: ViewKind;
	// Keys of the columns the block hides; every other column shows.
	hidden: string[];
	sort: { key: string; dir: 'asc' | 'desc' } | null;
	// Key of the choice column whose values are the board's lanes.
	group: string;
	// Key of the date column the calendar places rows by.
	date: string;
	// Table column widths in pixels by key (NAME_COLUMN_KEY for the name
	// column); a column not listed has its default width.
	widths: Record<string, number>;
	// Keys in the order the table shows them; columns not listed follow in
	// descriptor order. The name column is always first.
	order: string[];
	// Stretch the table or board to the editor's width instead of the memo's
	// text column.
	wide: boolean;
}

interface Row {
	path: string;
	name: string;
	title: string;
	// Stored property values by property name, as the index/GraphQL returns
	// them: a scalar or an array of scalars, already typed by the server.
	values: Record<string, { type: string; value: any; values: any[] | null }>;
}

// The shape written back to the descriptor. It is JSON, which YAML reads, so
// a hand-written descriptor keeps working and one the block has rewritten is
// still a valid `.dataset.yml`; only comments are lost on the first rewrite.
interface DescriptorDocument {
	id: string;
	label?: string;
	description?: string;
	properties: DescriptorProperty[];
	[extra: string]: any;
}

interface DescriptorProperty {
	key: string;
	label?: string;
	description?: string;
	type?: string;
	multiple?: boolean;
	required?: boolean;
	// Absent means printed; only `false` is written.
	print?: boolean;
	choices?: (DescriptorChoice | string)[];
	[extra: string]: any;
}

interface DescriptorChoice {
	value: string;
	label?: string;
	// A swatch key from the shared palette; absent for no color.
	color?: string;
}

// A choice while it is edited in the column form.
interface ChoiceDraft {
	value: string;
	label: string;
	color: string;
}

export function createDatasetViewExtension(host: DatasetViewHost) {
	return TiptapNode.create({
		name: DATASET_VIEW_NODE,
		group: 'block',
		atom: true,
		// Not draggable by ProseMirror's own means: for an atom node view that
		// would mark the block's root `draggable`, and selecting text in one of
		// its inputs would start a native drag of the whole block. The block is
		// moved with the editor's drag handle (drag-handle.ts), which selects
		// it and hands ProseMirror the slice itself.
		draggable: false,
		selectable: true,

		addAttributes() {
			return {
				path: { default: '', rendered: false },
				view: { default: 'table', rendered: false },
				hidden: { default: [], rendered: false },
				sort: { default: null, rendered: false },
				group: { default: '', rendered: false },
				date: { default: '', rendered: false },
				widths: { default: {}, rendered: false },
				order: { default: [], rendered: false },
				wide: { default: false, rendered: false },
			};
		},

		parseHTML() {
			return [{
				tag: 'div[data-dataset-view]',
				getAttrs: (element) => {
					const el = element as HTMLElement;
					const view = el.getAttribute('data-view') || 'table';
					return {
						path: el.getAttribute('data-path') || '',
						view: (VIEWS as readonly string[]).includes(view) ? view : 'table',
						hidden: parseJSONAttribute(el.getAttribute('data-hidden'), []),
						sort: parseJSONAttribute(el.getAttribute('data-sort'), null),
						group: el.getAttribute('data-group') || '',
						date: el.getAttribute('data-date') || '',
						widths: parseJSONAttribute(el.getAttribute('data-widths'), {}),
						order: parseJSONAttribute(el.getAttribute('data-order'), []),
						wide: el.hasAttribute('data-wide'),
					};
				},
			}];
		},

		renderHTML({ node }) {
			const attrs = node.attrs as DatasetViewAttrs;
			return ['div', mergeAttributes({
				'data-dataset-view': '',
				'data-path': attrs.path,
				'data-view': attrs.view,
				'data-hidden': attrs.hidden && attrs.hidden.length ? JSON.stringify(attrs.hidden) : null,
				'data-sort': attrs.sort ? JSON.stringify(attrs.sort) : null,
				'data-group': attrs.group || null,
				'data-date': attrs.date || null,
				'data-widths': attrs.widths && Object.keys(attrs.widths).length ? JSON.stringify(attrs.widths) : null,
				'data-order': attrs.order && attrs.order.length ? JSON.stringify(attrs.order) : null,
				'data-wide': attrs.wide ? '' : null,
			})];
		},

		addNodeView() {
			return ({ node, getPos, editor }) => new DatasetNodeView(host, editor as Editor, node, getPos as () => number | undefined);
		},
	});
}

function parseJSONAttribute(raw: string | null, fallback: any): any {
	if (!raw) return fallback;
	try { return JSON.parse(raw); } catch { return fallback; }
}

// UTF-8 text as Base64, the form createFile takes.
function toBase64(text: string): string {
	const bytes = new TextEncoder().encode(text);
	let binary = '';
	for (let i = 0; i < bytes.length; i += 0x8000) {
		binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
	}
	return btoa(binary);
}

// A dataset id: a letter first, so the joined property name is a JCR name in
// the default namespace and a single XPath token; the rest is time and chance.
export function generateDatasetId(): string {
	return 'd' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

// The key a label suggests: ASCII letters, digits and underscores only. A
// label with none of those (e.g. Japanese) yields '' and the caller numbers it.
function suggestKey(label: string): string {
	const s = label.normalize('NFKD').replace(/[^A-Za-z0-9_ ]+/g, '').trim().replace(/\s+/g, '_');
	return NAME_PATTERN.test(s) ? s : (s ? 'c_' + s : '');
}

function memoTitle(name: string): string {
	return name.toLowerCase().endsWith('.' + MEMO_EXTENSION) ? name.slice(0, -(MEMO_EXTENSION.length + 1)) : name;
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
	const e = document.createElement(tag);
	if (className) e.className = className;
	if (text != null) e.textContent = text;
	return e;
}

function icon(name: string): HTMLElement {
	return el('i', 'bi ' + name);
}

// A slim toolbar button: an icon, optional text, a tooltip.
function button(className: string, title: string, iconName: string | null, text?: string): HTMLButtonElement {
	const b = el('button', className);
	b.type = 'button';
	if (title) b.title = title;
	if (iconName) b.appendChild(icon(iconName));
	if (text) b.appendChild(document.createTextNode((iconName ? ' ' : '') + text));
	return b;
}

function anchorOf(target: HTMLElement) {
	const rect = target.getBoundingClientRect();
	return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height };
}

// The swatch key a choice carries, or '' when it has none or the palette does
// not know it (a descriptor edited by hand).
function swatchKeyOf(color: string | null | undefined): string {
	return color && SWATCH_COLOR_MAP[color] ? color : '';
}

// Whether a column is printed: only an explicit `false` says no, so a server
// that does not report the flag yet prints everything.
function isPrinted(column: DatasetProperty): boolean {
	return column.print !== false;
}

// The icon a column shows in lists: what kind of value it holds.
function columnIcon(column: DatasetProperty): string {
	if (column.choices.length > 0) return 'bi-tag';
	switch (column.type) {
		case 'LONG': case 'DOUBLE': case 'DECIMAL': return 'bi-123';
		case 'BOOLEAN': return 'bi-check2-square';
		case 'DATE': return 'bi-calendar-event';
		default: return 'bi-fonts';
	}
}

// Calendar day arithmetic on "yyyy-mm-dd" strings, done in UTC so the
// browser's own time zone never shifts a day. Wall-clock days come from the
// user's preference time zone through Dates.toZonedInputValue.
function dayString(utcMs: number): string {
	return new Date(utcMs).toISOString().slice(0, 10);
}

class DatasetNodeView implements NodeView {
	dom: HTMLElement;

	private node: PMNode;
	private dataset: Dataset | null = null;
	private rows: Row[] = [];
	private error = '';
	private loading = false;
	private truncated = 0;
	private loadSeq = 0;
	private unwatch: (() => void) | null = null;
	private reloadTimer: ReturnType<typeof setTimeout> | null = null;
	private destroyed = false;
	// The form or confirmation in progress (column add/edit, rename, delete).
	// Shown in the Inspector pane while the block is active, under the header
	// otherwise; kept as one element so a re-render never loses what the user
	// typed.
	private panel: HTMLElement | null = null;
	private editingCell: { rowPath: string; name: string } | null = null;
	// The month the calendar shows ("yyyy-mm"); the current month until the
	// user navigates. Not stored in the memo: where a reader lands is today.
	private month: string | null = null;
	// Whether the cursor is in the block: the header shows and the pane is in
	// the Inspector.
	private active = false;
	// The Inspector face (see DatasetPane); rendered together with the block.
	private readonly paneEl: HTMLElement;
	private readonly pane: DatasetPane;

	constructor(
		private readonly host: DatasetViewHost,
		private readonly editor: Editor,
		node: PMNode,
		private readonly getPos: () => number | undefined,
	) {
		this.node = node;
		this.dom = el('div', 'memo-dataset');
		this.dom.setAttribute('data-dataset-view', '');
		(this.dom as any)[DATASET_DROP_HANDLER] = (items: any[]) => this.onDropItems(items);
		this.paneEl = el('div', 'memo-dataset-pane');
		this.pane = { el: this.paneEl, blockEl: this.dom, deactivate: () => this.deactivate() };
		// The cursor comes into the block by a click, a right click, or focus
		// moving into one of its inputs (Tab). Activation waits for the click
		// rather than the mousedown: showing the header shifts the rows down,
		// and a shift between mousedown and mouseup would land the click on
		// another cell than the one pressed.
		this.dom.addEventListener('click', () => this.activate());
		this.dom.addEventListener('contextmenu', () => this.activate());
		this.dom.addEventListener('focusin', () => this.activate());
		this.render();
		this.connect();
	}

	// ---- NodeView contract ----

	update(node: PMNode): boolean {
		if (node.type !== this.node.type) return false;
		const before = this.attrs;
		this.node = node;
		const after = this.attrs;
		if (before.path !== after.path) {
			this.connect();
		} else if (before.sort !== after.sort || before.hidden !== after.hidden
			|| before.view !== after.view || before.group !== after.group || before.date !== after.date
			|| before.widths !== after.widths || before.order !== after.order || before.wide !== after.wide) {
			this.render();
		}
		return true;
	}

	// Everything inside the block is ours: keep ProseMirror out of the inputs
	// and buttons, and out of the row and column drags. The block itself is
	// moved with the editor's block drag handle (drag-handle.ts), which sits
	// outside the block.
	stopEvent(): boolean {
		return true;
	}

	ignoreMutation(): boolean {
		return true;
	}

	// The whole block is selected (a click on the grip, arrow keys onto it):
	// the cursor is in it.
	selectNode(): void {
		this.dom.classList.add('is-selected');
		this.activate();
	}

	deselectNode(): void {
		this.dom.classList.remove('is-selected');
	}

	destroy(): void {
		this.destroyed = true;
		this.deactivate();
		this.disconnect();
	}

	// ---- activation ----

	private activate(): void {
		if (this.destroyed) return;
		const wasActive = this.active;
		this.active = true;
		this.dom.classList.add('is-active');
		// The panel, if one is open, moves from under the header to the pane.
		if (!wasActive && this.panel) this.render();
		// Told to the host every time: a host that refused the block (its tab
		// is not the active one) answers by deactivating it.
		this.host.activatePane(this.pane);
	}

	private deactivate(): void {
		if (!this.active) return;
		this.active = false;
		this.dom.classList.remove('is-active');
		// A form left open belongs to the block the cursor has left; the pane
		// it lived in is gone with the activation.
		this.panel = null;
		this.host.deactivatePane(this.pane);
		if (!this.destroyed) this.render();
	}

	// ---- attributes ----

	private get attrs(): DatasetViewAttrs {
		return this.node.attrs as DatasetViewAttrs;
	}

	private setAttrs(patch: Partial<DatasetViewAttrs>): void {
		const pos = this.getPos();
		if (pos == null) return;
		const tr = this.editor.view.state.tr.setNodeMarkup(pos, undefined, { ...this.node.attrs, ...patch });
		this.editor.view.dispatch(tr);
	}

	private removeBlock(): void {
		const pos = this.getPos();
		if (pos == null) return;
		const tr = this.editor.view.state.tr.delete(pos, pos + this.node.nodeSize);
		this.editor.view.dispatch(tr);
		this.editor.commands.focus();
	}

	// Switch the view, picking a usable column for it when none is set.
	private switchView(view: ViewKind): void {
		const patch: Partial<DatasetViewAttrs> = { view };
		if (view === 'board' && !this.groupColumn()) {
			patch.group = this.groupableColumns()[0]?.key || '';
		}
		if (view === 'calendar' && !this.dateColumn()) {
			patch.date = this.dateColumns()[0]?.key || '';
		}
		this.setAttrs(patch);
	}

	// ---- loading ----

	private connect(): void {
		this.disconnect();
		this.dataset = null;
		this.rows = [];
		this.error = '';
		this.truncated = 0;
		const path = this.attrs.path;
		if (!path) {
			this.render();
			return;
		}
		const eventHub = this.host.api()?.eventHub;
		if (eventHub && typeof eventHub.watchNode === 'function') {
			try {
				this.unwatch = eventHub.watchNode(path, () => this.scheduleReload(), true);
			} catch { /* live updates are a convenience */ }
		}
		void this.reload();
	}

	private disconnect(): void {
		if (this.unwatch) {
			try { this.unwatch(); } catch { /* ignore */ }
			this.unwatch = null;
		}
		if (this.reloadTimer) {
			clearTimeout(this.reloadTimer);
			this.reloadTimer = null;
		}
	}

	private scheduleReload(): void {
		if (this.reloadTimer) clearTimeout(this.reloadTimer);
		this.reloadTimer = setTimeout(() => {
			this.reloadTimer = null;
			void this.reload();
		}, 300);
	}

	private async reload(): Promise<void> {
		const path = this.attrs.path;
		if (!path || this.destroyed) return;
		const seq = ++this.loadSeq;
		this.loading = true;
		this.error = '';
		this.render();
		try {
			const content = this.host.api().content;
			const folder: GNode | null = await content.getNode(path);
			if (seq !== this.loadSeq || this.destroyed) return;
			if (!folder) {
				this.dataset = null;
				this.error = this.t('app.memo.dataset.error.notFound', { path }, `Folder not found: ${path}`);
				return;
			}
			if (folder.dataset === undefined) {
				// The field is missing altogether, not null: the GraphQL call ran
				// through a shell (window.parent) built before datasets existed,
				// which happens when the desktop page was not reloaded after a
				// deploy. Say so instead of blaming the folder.
				this.dataset = null;
				this.error = this.t('app.memo.dataset.error.staleShell', undefined,
					'The desktop is running an older version that does not know datasets. Reload the browser page and try again.');
				return;
			}
			if (!folder.dataset) {
				this.dataset = null;
				this.error = this.t('app.memo.dataset.error.notDataset', { path }, `Not a dataset folder: ${path}`);
				return;
			}
			this.dataset = folder.dataset;
			const statement = `/jcr:root${path}/element(*, nt:file)`;
			const result = await content.xpathWithProperties(statement, { first: ROW_LIMIT });
			if (seq !== this.loadSeq || this.destroyed) return;
			const nodes: GNode[] = (result?.edges || []).map((e: any) => e.node);
			this.rows = nodes.filter(n => n.name !== DATASET_DESCRIPTOR_NAME).map(n => this.toRow(n));
			this.truncated = Math.max(0, (result?.totalCount || 0) - nodes.length);
		} catch (e: any) {
			if (seq !== this.loadSeq || this.destroyed) return;
			this.error = e?.message || String(e);
		} finally {
			if (seq === this.loadSeq && !this.destroyed) {
				this.loading = false;
				this.render();
			}
		}
	}

	private toRow(node: GNode): Row {
		const values: Row['values'] = {};
		for (const p of node.properties || []) {
			const pv: any = p.propertyValue;
			if (!pv) continue;
			const isArray = Array.isArray(pv.values);
			values[p.name] = {
				type: String(pv.type || 'STRING').toUpperCase(),
				value: isArray ? null : pv.value,
				values: isArray ? pv.values : null,
			};
		}
		return { path: node.path, name: node.name, title: memoTitle(node.name), values };
	}

	// ---- column helpers ----

	private t(key: string, params?: Record<string, any>, fallback?: string): string {
		return this.host.t(key, params, fallback);
	}

	// The columns in the order the block shows them: the stored order first,
	// then any column the order does not know, in descriptor order.
	private orderedColumns(): DatasetProperty[] {
		if (!this.dataset) return [];
		const all = this.dataset.properties;
		const order = this.attrs.order || [];
		const byKey = new Map(all.map(p => [p.key, p]));
		const result: DatasetProperty[] = [];
		for (const key of order) {
			const p = byKey.get(key);
			if (p && !result.includes(p)) result.push(p);
		}
		for (const p of all) if (!result.includes(p)) result.push(p);
		return result;
	}

	private visibleColumns(): DatasetProperty[] {
		const hidden = new Set(this.attrs.hidden || []);
		return this.orderedColumns().filter(p => !hidden.has(p.key));
	}

	private isHidden(column: DatasetProperty): boolean {
		return (this.attrs.hidden || []).includes(column.key);
	}

	// Columns a board can group by: single-valued text with declared choices,
	// so every lane is a known value.
	private groupableColumns(): DatasetProperty[] {
		return (this.dataset?.properties || []).filter(p => p.type === 'STRING' && !p.multiple && p.choices.length > 0);
	}

	private dateColumns(): DatasetProperty[] {
		return (this.dataset?.properties || []).filter(p => p.type === 'DATE');
	}

	private groupColumn(): DatasetProperty | null {
		const key = this.attrs.group;
		return key ? this.groupableColumns().find(p => p.key === key) || null : null;
	}

	private dateColumn(): DatasetProperty | null {
		const key = this.attrs.date;
		return key ? this.dateColumns().find(p => p.key === key) || null : null;
	}

	// The stored width of a table column; the default for its kind when none.
	private columnWidth(column: DatasetProperty | null): number {
		const widths = this.attrs.widths || {};
		const key = column ? column.key : NAME_COLUMN_KEY;
		const stored = widths[key];
		if (typeof stored === 'number' && stored >= MIN_COLUMN_WIDTH) return Math.round(stored);
		if (!column) return DEFAULT_NAME_WIDTH;
		return column.type === 'BOOLEAN' ? DEFAULT_BOOLEAN_WIDTH : DEFAULT_COLUMN_WIDTH;
	}

	// The scalar values a row stores in a column, as strings; [] when none.
	private valuesOf(row: Row, column: DatasetProperty): string[] {
		const stored = row.values[column.name];
		if (!stored) return [];
		const raw: any[] = stored.values ? stored.values : [stored.value];
		return raw.filter(v => v != null && v !== '').map(String);
	}

	private sortedRows(): Row[] {
		const sort = this.attrs.sort;
		const rows = this.rows.slice();
		const collator = new Intl.Collator(this.host.locale() || undefined, { numeric: true, sensitivity: 'base' });
		if (!sort || !sort.key) {
			return rows.sort((a, b) => collator.compare(a.title, b.title));
		}
		const column = this.dataset?.properties.find(p => p.key === sort.key);
		const dir = sort.dir === 'desc' ? -1 : 1;
		const keyOf = (row: Row): any => {
			if (!column) return row.title;
			const v = row.values[column.name];
			if (!v) return null;
			return v.values ? v.values[0] ?? null : v.value;
		};
		return rows.sort((a, b) => {
			const x = keyOf(a);
			const y = keyOf(b);
			if (x == null && y == null) return collator.compare(a.title, b.title);
			if (x == null) return 1;
			if (y == null) return -1;
			let c: number;
			if (column && (column.type === 'LONG' || column.type === 'DOUBLE' || column.type === 'DECIMAL')) {
				c = Number(x) - Number(y);
			} else if (column && column.type === 'DATE') {
				c = new Date(x).getTime() - new Date(y).getTime();
			} else if (column && column.type === 'BOOLEAN') {
				c = (x === true || x === 'true' ? 1 : 0) - (y === true || y === 'true' ? 1 : 0);
			} else {
				c = collator.compare(String(x), String(y));
			}
			return c === 0 ? collator.compare(a.title, b.title) : c * dir;
		});
	}

	// ---- rendering ----

	private render(): void {
		this.dom.replaceChildren();
		this.editingCell = null;
		const attrs = this.attrs;
		this.dom.dataset.view = attrs.view;
		// Full width applies to the table and the board; a calendar keeps the
		// memo's text column.
		this.dom.classList.toggle('is-wide', !!attrs.wide && attrs.view !== 'calendar');
		this.dom.appendChild(this.renderHeader());
		// A form opened while the block is inactive (the Inspector is closed,
		// say) shows under the header; the active block shows it in the pane.
		if (this.panel && !this.active) this.dom.appendChild(this.panel);
		if (!attrs.path) {
			this.dom.appendChild(this.renderSetup());
			this.renderPane();
			return;
		}
		if (this.error) {
			const msg = el('div', 'memo-dataset-message text-danger');
			msg.appendChild(icon('bi-exclamation-circle me-1'));
			msg.appendChild(document.createTextNode(this.error));
			this.dom.appendChild(msg);
		}
		if (this.dataset) {
			switch (attrs.view) {
				case 'board': this.dom.appendChild(this.renderBoard()); break;
				case 'calendar': this.dom.appendChild(this.renderCalendar()); break;
				default: this.dom.appendChild(this.renderTable());
			}
		}
		if (this.loading) {
			const status = el('div', 'memo-dataset-status');
			status.appendChild(icon('bi-arrow-repeat me-1'));
			status.appendChild(document.createTextNode(this.t('app.memo.dataset.loading', undefined, 'Loading…')));
			this.dom.appendChild(status);
		}
		this.renderPane();
	}

	// One row, shown while the block is active: the view switch with the
	// view's own controls (the board's group column; the calendar's month and
	// date column), and the block menu. The dataset's name, path and row
	// count live in the pane.
	private renderHeader(): HTMLElement {
		const attrs = this.attrs;
		const header = el('div', 'memo-dataset-header');
		if (this.dataset) {
			const viewLabel = this.t('app.memo.dataset.view.' + attrs.view, undefined, attrs.view);
			const viewBtn = button('wt wt-slim memo-dataset-btn memo-dataset-picker',
				this.t('app.memo.dataset.view.switch', undefined, 'View'), VIEW_ICONS[attrs.view] || VIEW_ICONS.table, viewLabel);
			viewBtn.appendChild(icon('bi-chevron-down ms-1'));
			viewBtn.addEventListener('click', () => this.openViewMenu(viewBtn));
			header.appendChild(viewBtn);

			if (attrs.view === 'board') {
				header.appendChild(this.renderColumnPicker(
					this.t('app.memo.dataset.board.group', undefined, 'Group by'),
					this.groupColumn(), this.groupableColumns(), key => this.setAttrs({ group: key })));
			} else if (attrs.view === 'calendar') {
				const nav = el('div', 'memo-dataset-nav');
				const prev = button('wt wt-circle memo-dataset-btn', this.t('app.memo.dataset.calendar.prev', undefined, 'Previous month'), 'bi-chevron-left');
				prev.addEventListener('click', () => this.shiftMonth(-1));
				const label = el('span', 'memo-dataset-month', this.monthLabel(this.currentMonth()));
				const next = button('wt wt-circle memo-dataset-btn', this.t('app.memo.dataset.calendar.next', undefined, 'Next month'), 'bi-chevron-right');
				next.addEventListener('click', () => this.shiftMonth(1));
				const today = button('wt wt-slim memo-dataset-btn', '', null, this.t('app.memo.dataset.calendar.today', undefined, 'Today'));
				today.addEventListener('click', () => { this.month = null; this.render(); });
				nav.appendChild(prev);
				nav.appendChild(label);
				nav.appendChild(next);
				nav.appendChild(today);
				header.appendChild(nav);
				header.appendChild(this.renderColumnPicker(
					this.t('app.memo.dataset.calendar.date', undefined, 'Date'),
					this.dateColumn(), this.dateColumns(), key => this.setAttrs({ date: key })));
			}
		} else {
			header.appendChild(icon('bi-database memo-dataset-icon'));
			header.appendChild(el('span', 'memo-dataset-title', this.t('app.memo.dataset.title', undefined, 'Dataset')));
		}

		header.appendChild(el('span', 'memo-dataset-spacer'));
		const menu = button('wt wt-circle memo-dataset-btn', this.t('app.memo.dataset.menu', undefined, 'Block menu'), 'bi-three-dots');
		menu.addEventListener('click', (e) => this.openBlockMenu(e));
		header.appendChild(menu);
		return header;
	}

	private openViewMenu(anchor: HTMLElement): void {
		const popup = this.host.popup();
		if (!popup) return;
		const current = this.attrs.view;
		const handle = popup.open({
			anchor: anchorOf(anchor),
			placement: 'bottom-start',
			minWidth: 160,
			items: VIEWS.map(view => ({
				id: view,
				label: this.t('app.memo.dataset.view.' + view, undefined, view),
				icon: 'bi ' + VIEW_ICONS[view],
				selected: view === current,
			})),
		});
		handle.result.then((id: string | null) => {
			if (id && id !== current && (VIEWS as readonly string[]).includes(id)) this.switchView(id as ViewKind);
		});
	}

	private renderColumnPicker(caption: string, current: DatasetProperty | null, candidates: DatasetProperty[], choose: (key: string) => void): HTMLElement {
		const btn = el('button', 'wt wt-slim memo-dataset-btn memo-dataset-picker');
		btn.type = 'button';
		btn.appendChild(el('span', 'memo-dataset-picker-caption', caption + ': '));
		btn.appendChild(el('span', undefined, current ? (current.label || current.key) : '—'));
		btn.appendChild(icon('bi-chevron-down ms-1'));
		btn.addEventListener('click', () => {
			const popup = this.host.popup();
			if (!popup || candidates.length === 0) return;
			const handle = popup.open({
				anchor: anchorOf(btn),
				placement: 'bottom-start',
				minWidth: 180,
				items: candidates.map(c => ({ id: c.key, label: c.label || c.key, selected: current?.key === c.key })),
			});
			handle.result.then((id: string | null) => { if (id != null) choose(String(id)); });
		});
		return btn;
	}

	// The block before it points anywhere: create a folder next to the memo,
	// or take a folder dropped from the Content Browser.
	private renderSetup(): HTMLElement {
		const box = el('div', 'memo-dataset-setup');
		const memoPath = this.host.activeMemoPath();
		if (!memoPath) {
			const note = el('div', 'memo-dataset-message');
			note.appendChild(icon('bi-info-circle me-1'));
			note.appendChild(document.createTextNode(this.t('app.memo.dataset.setup.saveFirst', undefined,
				'Save the memo first, then create a dataset next to it, or drop a dataset folder here from the Content Browser.')));
			box.appendChild(note);
			return box;
		}
		const memoName = memoTitle(memoPath.slice(memoPath.lastIndexOf('/') + 1));
		const label = el('label', 'memo-dataset-setup-label', this.t('app.memo.dataset.setup.name', undefined, 'Create a dataset folder next to this memo'));
		box.appendChild(label);
		const row = el('div', 'memo-dataset-setup-row');
		const input = el('input', 'wt');
		input.type = 'text';
		input.value = memoName;
		input.placeholder = this.t('app.memo.dataset.setup.namePlaceholder', undefined, 'Folder name');
		row.appendChild(input);
		const create = el('button', 'wt wt-primary wt-slim');
		create.type = 'button';
		create.textContent = this.t('app.memo.dataset.setup.create', undefined, 'Create');
		row.appendChild(create);
		box.appendChild(row);
		const hint = el('div', 'memo-dataset-hint', this.t('app.memo.dataset.setup.dropHint', undefined,
			'Or drop an existing dataset folder here from the Content Browser.'));
		box.appendChild(hint);
		const errorLine = el('div', 'memo-dataset-message text-danger');
		errorLine.style.display = 'none';
		box.appendChild(errorLine);

		const submit = async () => {
			const name = input.value.trim();
			if (!name || name.includes('/')) {
				input.focus();
				return;
			}
			create.disabled = true;
			errorLine.style.display = 'none';
			try {
				const parent = memoPath.slice(0, memoPath.lastIndexOf('/')) || '/';
				const path = await this.createDataset(parent, name);
				this.setAttrs({ path });
			} catch (e: any) {
				errorLine.textContent = e?.message || String(e);
				errorLine.style.display = '';
				create.disabled = false;
			}
		};
		create.addEventListener('click', () => { void submit(); });
		input.addEventListener('keydown', (e) => {
			if (e.key === 'Enter') { e.preventDefault(); void submit(); }
		});
		setTimeout(() => { input.focus(); input.select(); }, 0);
		return box;
	}

	// ---- Inspector pane ----

	// The block's Inspector face: the dataset's name, path and row count, and
	// the column list, where columns are added, edited and deleted. Rebuilt
	// with the block; the open form (this.panel) is carried over as one
	// element.
	private renderPane(): void {
		const pane = this.paneEl;
		pane.replaceChildren();
		const attrs = this.attrs;
		const dataset = this.dataset;

		pane.appendChild(el('div', 'detail-section-header', this.t('app.memo.dataset.title', undefined, 'Dataset')));
		const info = el('div', 'detail-section-body memo-dataset-pane-info');
		if (!attrs.path) {
			info.appendChild(el('div', 'text-muted small', this.t('app.memo.dataset.pane.unlinked', undefined, 'This block is not linked to a dataset folder yet.')));
		} else {
			const name = el('div', 'memo-dataset-pane-name');
			name.appendChild(icon((VIEW_ICONS[attrs.view] || VIEW_ICONS.table) + ' me-1'));
			name.appendChild(document.createTextNode(dataset ? (dataset.label || dataset.id) : this.t('app.memo.dataset.title', undefined, 'Dataset')));
			if (dataset) name.title = dataset.id;
			info.appendChild(name);
			const path = el('div', 'memo-dataset-pane-path', attrs.path);
			path.title = attrs.path;
			info.appendChild(path);
			const rows = el('div', 'memo-dataset-pane-rows');
			if (this.loading) {
				rows.appendChild(icon('bi-arrow-repeat me-1'));
				rows.appendChild(document.createTextNode(this.t('app.memo.dataset.loading', undefined, 'Loading…')));
			} else if (dataset) {
				const count = this.rows.length;
				let text = this.t('app.memo.dataset.rowCount', { count }, `${count} rows`);
				if (this.truncated > 0) {
					text += ' · ' + this.t('app.memo.dataset.truncated', { count: this.truncated }, `${this.truncated} more not shown`);
				}
				rows.textContent = text;
			}
			info.appendChild(rows);
			if (this.error) {
				const msg = el('div', 'text-danger small');
				msg.appendChild(icon('bi-exclamation-circle me-1'));
				msg.appendChild(document.createTextNode(this.error));
				info.appendChild(msg);
			}
		}
		pane.appendChild(info);

		if (!dataset) {
			if (this.panel) pane.appendChild(this.panel);
			return;
		}

		const columns = this.orderedColumns();
		const head = el('div', 'detail-section-header');
		head.appendChild(document.createTextNode(this.t('app.memo.dataset.pane.columns', { count: columns.length }, `Columns (${columns.length})`)));
		const add = button('detail-section-header-btn ms-auto', this.t('app.memo.dataset.addColumn', undefined, 'Add column'), 'bi-plus-lg');
		add.addEventListener('click', () => this.openColumnForm(null));
		head.appendChild(add);
		pane.appendChild(head);

		const body = el('div', 'detail-section-body memo-dataset-pane-columns');
		if (this.panel) {
			body.appendChild(this.panel);
		} else if (columns.length === 0) {
			body.appendChild(el('div', 'text-muted small', this.t('app.memo.dataset.pane.noColumns', undefined, 'No columns yet. Add one with "+".')));
		} else {
			const list = el('div', 'memo-dataset-pane-list');
			for (const column of columns) list.appendChild(this.renderPaneColumn(column));
			body.appendChild(list);
		}
		pane.appendChild(body);
	}

	private renderPaneColumn(column: DatasetProperty): HTMLElement {
		const row = el('div', 'memo-dataset-pane-col');
		row.appendChild(icon(columnIcon(column) + ' memo-dataset-pane-col-icon'));
		const text = el('div', 'memo-dataset-pane-col-text');
		text.appendChild(el('div', 'memo-dataset-pane-col-label', column.label || column.key));
		const meta = this.t('app.memo.dataset.type.' + column.type, undefined, column.type) + (column.multiple ? ' []' : '') + ' · ' + column.key;
		text.appendChild(el('div', 'memo-dataset-pane-col-meta', meta));
		row.appendChild(text);
		const flags = el('span', 'memo-dataset-pane-col-flags');
		if (this.isHidden(column)) {
			const f = icon('bi-eye-slash');
			f.title = this.t('app.memo.dataset.pane.hidden', undefined, 'Hidden in this block');
			flags.appendChild(f);
		}
		if (!isPrinted(column)) {
			const f = icon('bi-printer memo-dataset-pane-noprint');
			f.title = this.t('app.memo.dataset.pane.noPrint', undefined, 'Not printed');
			flags.appendChild(f);
		}
		row.appendChild(flags);
		const menu = button('detail-section-header-btn memo-dataset-pane-col-menu', this.t('app.memo.dataset.menu', undefined, 'Block menu'), 'bi-three-dots');
		menu.addEventListener('click', (e) => { e.stopPropagation(); this.openPaneColumnMenu(menu, column); });
		row.appendChild(menu);
		row.addEventListener('click', () => this.openColumnForm(column));
		return row;
	}

	private openPaneColumnMenu(anchor: HTMLElement, column: DatasetProperty): void {
		const popup = this.host.popup();
		if (!popup) return;
		const hidden = this.isHidden(column);
		const handle = popup.open({
			anchor: anchorOf(anchor),
			placement: 'bottom-end',
			minWidth: 200,
			items: [
				{ id: 'edit', label: this.t('app.memo.dataset.editColumn', undefined, 'Edit column…'), icon: 'bi bi-pencil' },
				hidden
					? { id: 'show', label: this.t('app.memo.dataset.showColumn', undefined, 'Show column'), icon: 'bi bi-eye' }
					: { id: 'hide', label: this.t('app.memo.dataset.hideColumn', undefined, 'Hide column'), icon: 'bi bi-eye-slash' },
				{ id: 'delete', label: this.t('app.memo.dataset.deleteColumn', undefined, 'Delete column…'), icon: 'bi bi-trash', danger: true },
			],
		});
		handle.result.then((id: string | null) => {
			switch (id) {
				case 'edit': this.openColumnForm(column); break;
				case 'show': this.setAttrs({ hidden: (this.attrs.hidden || []).filter(k => k !== column.key) }); break;
				case 'hide': this.setAttrs({ hidden: [...(this.attrs.hidden || []), column.key] }); break;
				case 'delete': this.confirmDeleteColumn(column); break;
			}
		});
	}

	// ---- table view ----

	private renderTable(): HTMLElement {
		const wrap = el('div', 'memo-dataset-scroll');
		const table = el('table', 'memo-dataset-table');
		const columns = this.visibleColumns();
		const sort = this.attrs.sort;

		// Fixed layout: every column has the width the block stores (or its
		// default), the last, empty column takes what is left of the block's
		// width, and the wrapper scrolls sideways when the columns exceed it.
		const colgroup = el('colgroup');
		const cols = new Map<string, HTMLTableColElement>();
		const nameCol = el('col');
		nameCol.style.width = this.columnWidth(null) + 'px';
		cols.set(NAME_COLUMN_KEY, nameCol);
		colgroup.appendChild(nameCol);
		for (const column of columns) {
			const col = el('col');
			col.style.width = this.columnWidth(column) + 'px';
			cols.set(column.key, col);
			colgroup.appendChild(col);
		}
		colgroup.appendChild(el('col', 'memo-dataset-filler-col'));
		table.appendChild(colgroup);

		const thead = el('thead');
		const headRow = el('tr');
		const nameTh = el('th', 'memo-dataset-th memo-dataset-name');
		nameTh.appendChild(this.renderHeaderCell(this.t('app.memo.dataset.nameColumn', undefined, 'Name'), null, sort && !sort.key ? sort.dir : (sort ? null : 'asc')));
		nameTh.appendChild(this.renderResizer(null, nameCol, table));
		this.acceptColumnDrops(nameTh, null);
		headRow.appendChild(nameTh);
		for (const column of columns) {
			const th = el('th', 'memo-dataset-th' + (isPrinted(column) ? '' : ' is-noprint'));
			th.appendChild(this.renderHeaderCell(column.label || column.key, column, sort && sort.key === column.key ? sort.dir : null));
			th.appendChild(this.renderResizer(column, cols.get(column.key)!, table));
			this.acceptColumnDrops(th, column);
			headRow.appendChild(th);
		}
		headRow.appendChild(el('th', 'memo-dataset-th memo-dataset-filler'));
		thead.appendChild(headRow);
		table.appendChild(thead);

		const tbody = el('tbody');
		for (const row of this.sortedRows()) {
			const tr = el('tr');
			tr.dataset.path = row.path;
			const nameTd = el('td', 'memo-dataset-name');
			nameTd.appendChild(this.renderRowLink(row));
			nameTd.addEventListener('contextmenu', (e) => { e.preventDefault(); this.openRowMenu(e, row); });
			tr.appendChild(nameTd);
			for (const column of columns) {
				tr.appendChild(this.renderCell(row, column));
			}
			tr.appendChild(el('td', 'memo-dataset-filler'));
			tbody.appendChild(tr);
		}
		if (this.rows.length === 0 && !this.loading) {
			const tr = el('tr');
			const td = el('td', 'memo-dataset-empty', this.t('app.memo.dataset.empty', undefined, 'No rows yet. Type a name below to add one.'));
			td.colSpan = columns.length + 2;
			tr.appendChild(td);
			tbody.appendChild(tr);
		}
		table.appendChild(tbody);

		const tfoot = el('tfoot');
		const footRow = el('tr');
		const footTd = el('td');
		footTd.colSpan = columns.length + 2;
		footTd.appendChild(this.renderNewRowInput());
		footRow.appendChild(footTd);
		tfoot.appendChild(footRow);
		table.appendChild(tfoot);

		wrap.appendChild(table);
		return wrap;
	}

	// The handle at a header cell's right edge: dragging it changes the
	// column's width live and stores it when the mouse is released.
	private renderResizer(column: DatasetProperty | null, col: HTMLTableColElement, table: HTMLTableElement): HTMLElement {
		const handle = el('span', 'memo-dataset-resizer');
		handle.title = this.t('app.memo.dataset.resize', undefined, 'Drag to resize');
		handle.addEventListener('mousedown', (e: MouseEvent) => {
			if (e.button !== 0) return;
			e.preventDefault();
			e.stopPropagation();
			const startX = e.clientX;
			const startWidth = this.columnWidth(column);
			let width = startWidth;
			table.classList.add('is-resizing');
			const onMove = (ev: MouseEvent) => {
				width = Math.max(MIN_COLUMN_WIDTH, Math.round(startWidth + ev.clientX - startX));
				col.style.width = width + 'px';
			};
			const onUp = () => {
				document.removeEventListener('mousemove', onMove);
				document.removeEventListener('mouseup', onUp);
				table.classList.remove('is-resizing');
				if (width === startWidth) return;
				const key = column ? column.key : NAME_COLUMN_KEY;
				this.setAttrs({ widths: { ...(this.attrs.widths || {}), [key]: width } });
			};
			document.addEventListener('mousemove', onMove);
			document.addEventListener('mouseup', onUp);
		});
		return handle;
	}

	// Let a header cell take a dragged column: dropped on the left half it
	// goes before the cell's column, on the right half after it. The name
	// column stays first, so a drop on it puts the column right after it.
	private acceptColumnDrops(th: HTMLElement, column: DatasetProperty | null): void {
		const isColumnDrag = (e: DragEvent) => !!e.dataTransfer && Array.from(e.dataTransfer.types).includes(COLUMN_DRAG_TYPE);
		th.addEventListener('dragover', (e: DragEvent) => {
			if (!isColumnDrag(e)) return;
			e.preventDefault();
			e.stopPropagation();
			e.dataTransfer!.dropEffect = 'move';
			th.classList.add('is-drop-target');
		});
		th.addEventListener('dragleave', (e: DragEvent) => {
			if (!th.contains(e.relatedTarget as globalThis.Node | null)) th.classList.remove('is-drop-target');
		});
		th.addEventListener('drop', (e: DragEvent) => {
			if (!isColumnDrag(e)) return;
			e.preventDefault();
			e.stopPropagation();
			th.classList.remove('is-drop-target');
			const key = e.dataTransfer!.getData(COLUMN_DRAG_TYPE);
			if (!key || (column && column.key === key)) return;
			const order = this.orderedColumns().map(p => p.key).filter(k => k !== key);
			let at: number;
			if (!column) {
				at = 0;
			} else {
				const rect = th.getBoundingClientRect();
				const after = e.clientX > rect.left + rect.width / 2;
				at = order.indexOf(column.key) + (after ? 1 : 0);
			}
			order.splice(at, 0, key);
			this.setAttrs({ order });
		});
	}

	private renderRowLink(row: Row): HTMLElement {
		const link = el('a', 'memo-dataset-link', row.title);
		link.href = '#';
		link.title = row.path;
		link.addEventListener('click', (e) => { e.preventDefault(); this.host.openPath(row.path); });
		return link;
	}

	// The "+ New row" input: Enter creates a memo in the folder, with the
	// given column preset (a board lane's value, a calendar day).
	private renderNewRowInput(preset?: { column: DatasetProperty; text: string }, placeholder?: string): HTMLInputElement {
		const input = el('input', 'memo-dataset-new-row');
		input.type = 'text';
		input.placeholder = placeholder || this.t('app.memo.dataset.newRow', undefined, '+ New row');
		input.addEventListener('keydown', (e) => {
			if (e.key !== 'Enter') return;
			e.preventDefault();
			const title = input.value.trim();
			if (!title) return;
			input.disabled = true;
			void this.createRow(title, preset).then(() => {
				input.value = '';
			}).catch((err: any) => {
				this.error = err?.message || String(err);
				this.render();
			}).finally(() => {
				input.disabled = false;
				input.focus();
			});
		});
		return input;
	}

	// A header cell: the label, the sort indicator, the column menu on click;
	// a value column can be dragged to another position.
	private renderHeaderCell(label: string, column: DatasetProperty | null, dir: 'asc' | 'desc' | null): HTMLElement {
		const cell = el('div', 'memo-dataset-head');
		if (column) cell.appendChild(icon(columnIcon(column) + ' memo-dataset-head-icon'));
		const text = el('span', 'memo-dataset-head-label', label);
		if (column) text.title = `${column.name} (${column.type}${column.multiple ? '[]' : ''})`;
		cell.appendChild(text);
		if (dir) cell.appendChild(icon(dir === 'asc' ? 'bi-sort-down-alt memo-dataset-sort' : 'bi-sort-up memo-dataset-sort'));
		cell.addEventListener('click', (e) => this.openColumnMenu(e, column));
		if (column) {
			cell.draggable = true;
			cell.addEventListener('dragstart', (e: DragEvent) => {
				if (!e.dataTransfer) return;
				e.dataTransfer.setData(COLUMN_DRAG_TYPE, column.key);
				e.dataTransfer.effectAllowed = 'move';
				cell.classList.add('is-dragging');
			});
			cell.addEventListener('dragend', () => cell.classList.remove('is-dragging'));
		}
		return cell;
	}

	private renderCell(row: Row, column: DatasetProperty): HTMLElement {
		const td = el('td', 'memo-dataset-cell' + (isPrinted(column) ? '' : ' is-noprint'));
		td.dataset.column = column.key;
		const stored = row.values[column.name];
		if (column.type === 'BOOLEAN') {
			const box = el('input');
			box.type = 'checkbox';
			box.checked = stored ? (stored.value === true || stored.value === 'true') : false;
			box.addEventListener('change', () => {
				void this.writeCell(row, column, box.checked ? 'true' : '', td);
			});
			td.appendChild(box);
			return td;
		}
		td.appendChild(this.renderValue(column, stored));
		td.tabIndex = 0;
		td.addEventListener('click', () => this.startCellEdit(row, column, td));
		td.addEventListener('keydown', (e) => {
			if (e.key === 'Enter' && !this.editingCell) { e.preventDefault(); this.startCellEdit(row, column, td); }
		});
		return td;
	}

	// A value as a chip, tinted with the choice's swatch when it has one.
	private renderChip(text: string, color: string): HTMLElement {
		const chip = el('span', 'memo-dataset-chip', text);
		const key = swatchKeyOf(color);
		if (key) {
			chip.style.backgroundColor = SWATCH_HIGHLIGHT_COLOR_MAP[key];
			chip.style.color = CHIP_TEXT_COLOR;
			chip.dataset.color = key;
		}
		return chip;
	}

	private renderValue(column: DatasetProperty, stored: Row['values'][string] | undefined): HTMLElement {
		const span = el('span', 'memo-dataset-value');
		if (!stored) {
			span.classList.add('is-empty');
			return span;
		}
		const raw: any[] = stored.values ? stored.values : [stored.value];
		const choices = new Map(column.choices.map(c => [c.value, c]));
		const parts = raw.filter(v => v != null && v !== '').map(v => ({ value: String(v), text: this.formatValue(column, v, choices) }));
		if (parts.length === 0) {
			span.classList.add('is-empty');
			return span;
		}
		if (column.choices.length > 0 || column.multiple) {
			for (const part of parts) span.appendChild(this.renderChip(part.text, choices.get(part.value)?.color || ''));
		} else {
			span.textContent = parts.map(p => p.text).join(', ');
		}
		return span;
	}

	private formatValue(column: DatasetProperty, value: any, choices: Map<string, { value: string; label: string }>): string {
		switch (column.type) {
			case 'DATE':
				return Dates.format(value, { format: 'datetime', locale: this.host.locale() || undefined, timeZone: this.host.timeZone() }) || String(value);
			case 'BOOLEAN':
				return value === true || value === 'true' ? '✓' : '';
			default: {
				const choice = choices.get(String(value));
				return choice ? (choice.label || choice.value) : String(value);
			}
		}
	}

	// ---- board view ----

	private renderBoard(): HTMLElement {
		const column = this.groupColumn();
		const board = el('div', 'memo-dataset-board');
		if (!column) {
			const msg = el('div', 'memo-dataset-message');
			msg.appendChild(icon('bi-info-circle me-1'));
			msg.appendChild(document.createTextNode(this.groupableColumns().length === 0
				? this.t('app.memo.dataset.board.noColumn', undefined, 'A board needs a text column with choices to group by. Add one in the Inspector.')
				: this.t('app.memo.dataset.board.pickColumn', undefined, 'Choose the column to group by.')));
			board.appendChild(msg);
			return board;
		}
		// Lanes: the column's choices in their declared order, then any value
		// in use that is not a choice, then a lane for rows without a value.
		const choices = new Map(column.choices.map(c => [c.value, c]));
		const laneValues: string[] = column.choices.map(c => c.value);
		for (const row of this.rows) {
			for (const v of this.valuesOf(row, column)) if (!laneValues.includes(v)) laneValues.push(v);
		}
		const lanes: { value: string | null; label: string; color: string }[] = laneValues.map(v => {
			const choice = choices.get(v);
			return { value: v, label: choice ? (choice.label || v) : v, color: choice?.color || '' };
		});
		lanes.push({ value: null, label: this.t('app.memo.dataset.board.none', undefined, 'No value'), color: '' });

		const sorted = this.sortedRows();
		const cardColumns = this.visibleColumns().filter(p => p.key !== column.key).slice(0, 3);
		for (const lane of lanes) {
			const laneRows = sorted.filter(r => {
				const v = this.valuesOf(r, column)[0] ?? null;
				return v === lane.value;
			});
			if (lane.value === null && laneRows.length === 0) continue;
			const laneEl = el('div', 'memo-dataset-lane');
			laneEl.dataset.value = lane.value ?? '';
			const head = el('div', 'memo-dataset-lane-head');
			head.appendChild(this.renderChip(lane.label, lane.color));
			head.appendChild(el('span', 'memo-dataset-lane-count', String(laneRows.length)));
			laneEl.appendChild(head);
			const cards = el('div', 'memo-dataset-cards');
			for (const row of laneRows) cards.appendChild(this.renderCard(row, cardColumns));
			laneEl.appendChild(cards);
			laneEl.appendChild(this.renderNewRowInput(
				lane.value !== null ? { column, text: lane.value } : undefined,
				this.t('app.memo.dataset.board.newRow', undefined, '+ New'),
			));
			this.acceptRowDrops(laneEl, (row) => {
				if ((this.valuesOf(row, column)[0] ?? null) === lane.value) return Promise.resolve();
				return this.storeValue(row, column, lane.value ?? '');
			});
			board.appendChild(laneEl);
		}
		return board;
	}

	private renderCard(row: Row, columns: DatasetProperty[]): HTMLElement {
		const card = el('div', 'memo-dataset-card');
		card.draggable = true;
		card.appendChild(this.renderRowLink(row));
		for (const column of columns) {
			const stored = row.values[column.name];
			if (!stored) continue;
			const value = this.renderValue(column, stored);
			if (value.classList.contains('is-empty')) continue;
			const line = el('div', 'memo-dataset-card-field' + (isPrinted(column) ? '' : ' is-noprint'));
			line.appendChild(el('span', 'memo-dataset-card-label', (column.label || column.key) + ' '));
			line.appendChild(value);
			card.appendChild(line);
		}
		this.makeRowDraggable(card, row);
		card.addEventListener('contextmenu', (e) => { e.preventDefault(); this.openRowMenu(e, row); });
		return card;
	}

	private makeRowDraggable(element: HTMLElement, row: Row): void {
		element.addEventListener('dragstart', (e: DragEvent) => {
			if (!e.dataTransfer) return;
			e.dataTransfer.setData(ROW_DRAG_TYPE, row.path);
			e.dataTransfer.effectAllowed = 'move';
			element.classList.add('is-dragging');
		});
		element.addEventListener('dragend', () => element.classList.remove('is-dragging'));
	}

	// Let a lane or a day take a dragged row. The memo's own drag-over handler
	// sits above the block and would refuse the drop, so propagation stops
	// here for our payload.
	private acceptRowDrops(target: HTMLElement, onDrop: (row: Row) => Promise<void>): void {
		const isRowDrag = (e: DragEvent) => !!e.dataTransfer && Array.from(e.dataTransfer.types).includes(ROW_DRAG_TYPE);
		target.addEventListener('dragover', (e: DragEvent) => {
			if (!isRowDrag(e)) return;
			e.preventDefault();
			e.stopPropagation();
			e.dataTransfer!.dropEffect = 'move';
			target.classList.add('is-drop-target');
		});
		target.addEventListener('dragleave', (e: DragEvent) => {
			if (!target.contains(e.relatedTarget as globalThis.Node | null)) target.classList.remove('is-drop-target');
		});
		target.addEventListener('drop', (e: DragEvent) => {
			if (!isRowDrag(e)) return;
			e.preventDefault();
			e.stopPropagation();
			target.classList.remove('is-drop-target');
			const path = e.dataTransfer!.getData(ROW_DRAG_TYPE);
			const row = this.rows.find(r => r.path === path);
			if (!row) return;
			void onDrop(row).then(() => this.render()).catch((err: any) => {
				this.error = err?.message || String(err);
				this.render();
			});
		});
	}

	// ---- calendar view ----

	private todayString(): string {
		return Dates.toZonedInputValue(new Date(), this.host.timeZone()).slice(0, 10);
	}

	private currentMonth(): string {
		return this.month || this.todayString().slice(0, 7);
	}

	private shiftMonth(delta: number): void {
		const [y, m] = this.currentMonth().split('-').map(Number);
		const d = new Date(Date.UTC(y, m - 1 + delta, 1));
		this.month = d.toISOString().slice(0, 7);
		this.render();
	}

	private monthLabel(month: string): string {
		const [y, m] = month.split('-').map(Number);
		return new Intl.DateTimeFormat(this.host.locale() || undefined, { year: 'numeric', month: 'long', timeZone: 'UTC' })
			.format(new Date(Date.UTC(y, m - 1, 1)));
	}

	private renderCalendar(): HTMLElement {
		const column = this.dateColumn();
		const cal = el('div', 'memo-dataset-calendar');
		if (!column) {
			const msg = el('div', 'memo-dataset-message');
			msg.appendChild(icon('bi-info-circle me-1'));
			msg.appendChild(document.createTextNode(this.dateColumns().length === 0
				? this.t('app.memo.dataset.calendar.noColumn', undefined, 'A calendar needs a date column. Add one in the Inspector.')
				: this.t('app.memo.dataset.calendar.pickColumn', undefined, 'Choose the date column.')));
			cal.appendChild(msg);
			return cal;
		}
		const tz = this.host.timeZone();
		const locale = this.host.locale() || undefined;
		const today = this.todayString();
		const month = this.currentMonth();
		const [y, m] = month.split('-').map(Number);
		const firstOfMonth = Date.UTC(y, m - 1, 1);
		const lastOfMonth = Date.UTC(y, m, 0);
		const DAY = 86400000;
		const gridStart = firstOfMonth - new Date(firstOfMonth).getUTCDay() * DAY;
		const gridEnd = lastOfMonth + (6 - new Date(lastOfMonth).getUTCDay()) * DAY;

		// Rows by the day of each of their dates, in the user's time zone.
		const byDay = new Map<string, { row: Row; iso: string }[]>();
		const undated: Row[] = [];
		for (const row of this.sortedRows()) {
			const values = this.valuesOf(row, column);
			if (values.length === 0) { undated.push(row); continue; }
			for (const iso of values) {
				const local = Dates.toZonedInputValue(iso, tz);
				if (!local) continue;
				const day = local.slice(0, 10);
				if (!byDay.has(day)) byDay.set(day, []);
				byDay.get(day)!.push({ row, iso });
			}
		}

		const weekdayFormat = new Intl.DateTimeFormat(locale, { weekday: 'short', timeZone: 'UTC' });
		const grid = el('div', 'memo-dataset-grid');
		for (let i = 0; i < 7; i++) {
			// 2024-06-02 is a Sunday.
			grid.appendChild(el('div', 'memo-dataset-weekday', weekdayFormat.format(new Date(Date.UTC(2024, 5, 2 + i)))));
		}
		for (let t = gridStart; t <= gridEnd; t += DAY) {
			const day = dayString(t);
			const cell = el('div', 'memo-dataset-day'
				+ (day.slice(0, 7) !== month ? ' is-outside' : '')
				+ (day === today ? ' is-today' : ''));
			cell.dataset.day = day;
			const head = el('div', 'memo-dataset-day-head');
			head.appendChild(el('span', 'memo-dataset-day-number', String(new Date(t).getUTCDate())));
			const add = el('button', 'memo-dataset-day-add');
			add.type = 'button';
			add.title = this.t('app.memo.dataset.calendar.newRow', undefined, 'New row on this day');
			add.appendChild(icon('bi-plus'));
			add.addEventListener('click', () => {
				const existing = cell.querySelector('input.memo-dataset-new-row') as HTMLInputElement | null;
				if (existing) { existing.focus(); return; }
				const input = this.renderNewRowInput({ column, text: `${day}T00:00` }, this.t('app.memo.dataset.calendar.newRowPlaceholder', undefined, 'Name, Enter'));
				input.classList.add('memo-dataset-day-input');
				input.addEventListener('keydown', (e) => { if (e.key === 'Escape') input.remove(); });
				input.addEventListener('blur', () => { if (!input.value) input.remove(); });
				cell.appendChild(input);
				input.focus();
			});
			head.appendChild(add);
			cell.appendChild(head);
			for (const entry of byDay.get(day) || []) {
				cell.appendChild(this.renderEvent(entry.row, entry.iso, column));
			}
			this.acceptRowDrops(cell, (row) => this.moveRowToDay(row, column, day));
			grid.appendChild(cell);
		}
		cal.appendChild(grid);

		if (undated.length > 0) {
			const tray = el('div', 'memo-dataset-undated');
			tray.appendChild(el('span', 'memo-dataset-undated-label', this.t('app.memo.dataset.calendar.undated', { count: undated.length }, `No date (${undated.length})`)));
			for (const row of undated) {
				const chip = el('span', 'memo-dataset-event');
				chip.draggable = !column.multiple;
				chip.appendChild(this.renderRowLink(row));
				if (!column.multiple) this.makeRowDraggable(chip, row);
				chip.addEventListener('contextmenu', (e) => { e.preventDefault(); this.openRowMenu(e, row); });
				tray.appendChild(chip);
			}
			cal.appendChild(tray);
		}
		return cal;
	}

	private renderEvent(row: Row, iso: string, column: DatasetProperty): HTMLElement {
		const chip = el('div', 'memo-dataset-event');
		const local = Dates.toZonedInputValue(iso, this.host.timeZone());
		const time = local.slice(11, 16);
		if (time && time !== '00:00') chip.appendChild(el('span', 'memo-dataset-event-time', time + ' '));
		chip.appendChild(this.renderRowLink(row));
		// A row with several dates cannot be moved by dragging one of them.
		if (!column.multiple) {
			chip.draggable = true;
			this.makeRowDraggable(chip, row);
		}
		chip.addEventListener('contextmenu', (e) => { e.preventDefault(); this.openRowMenu(e, row); });
		return chip;
	}

	// Put the row on `day`, keeping its time of day (midnight when it had none).
	private moveRowToDay(row: Row, column: DatasetProperty, day: string): Promise<void> {
		const current = this.valuesOf(row, column)[0];
		const time = current ? (Dates.toZonedInputValue(current, this.host.timeZone()).slice(11, 16) || '00:00') : '00:00';
		return this.storeValue(row, column, `${day}T${time}`);
	}

	// ---- cell editing ----

	private startCellEdit(row: Row, column: DatasetProperty, td: HTMLElement): void {
		if (this.editingCell) return;
		this.editingCell = { rowPath: row.path, name: column.name };
		const stored = row.values[column.name];
		const current: any[] = stored ? (stored.values ? stored.values : [stored.value]) : [];
		td.replaceChildren();
		td.classList.add('is-editing');

		let input: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
		const finish = (commit: boolean) => {
			if (!this.editingCell) return;
			this.editingCell = null;
			td.classList.remove('is-editing');
			if (commit) {
				void this.writeCell(row, column, input.value, td);
			} else {
				td.replaceChildren(this.renderValue(column, row.values[column.name]));
			}
		};

		if (column.choices.length > 0 && !column.multiple) {
			const select = el('select', 'wt memo-dataset-input');
			const none = el('option', undefined, '');
			none.value = '';
			select.appendChild(none);
			for (const c of column.choices) {
				const opt = el('option', undefined, c.label || c.value);
				opt.value = c.value;
				select.appendChild(opt);
			}
			select.value = current[0] != null ? String(current[0]) : '';
			select.addEventListener('change', () => finish(true));
			input = select;
		} else if (column.type === 'DATE') {
			const date = el('input', 'wt memo-dataset-input');
			date.type = 'datetime-local';
			date.value = current[0] ? (Dates.toZonedInputValue(String(current[0]), this.host.timeZone()) || '') : '';
			date.addEventListener('change', () => finish(true));
			input = date;
		} else if (column.type === 'LONG' || column.type === 'DOUBLE' || column.type === 'DECIMAL') {
			const num = el('input', 'wt memo-dataset-input');
			num.type = 'number';
			if (column.type !== 'LONG') num.step = 'any';
			num.value = current[0] != null ? String(current[0]) : '';
			input = num;
		} else {
			const text = el('input', 'wt memo-dataset-input');
			text.type = 'text';
			text.value = current.filter(v => v != null).map(String).join(column.multiple ? ', ' : '');
			if (column.multiple) text.placeholder = this.t('app.memo.dataset.multiplePlaceholder', undefined, 'value, value, …');
			input = text;
		}
		input.addEventListener('keydown', (e: KeyboardEvent) => {
			if (e.key === 'Enter') { e.preventDefault(); finish(true); }
			else if (e.key === 'Escape') { e.preventDefault(); finish(false); }
			else if (e.key === 'Tab') { finish(true); }
		});
		input.addEventListener('blur', () => finish(true));
		td.appendChild(input);
		input.focus();
		if (input instanceof HTMLInputElement && input.type === 'text') input.select();
	}

	// Store one cell and repaint it.
	private async writeCell(row: Row, column: DatasetProperty, text: string, td: HTMLElement): Promise<void> {
		td.classList.add('is-saving');
		try {
			await this.storeValue(row, column, text);
			td.classList.remove('is-error');
			td.title = '';
		} catch (e: any) {
			td.classList.add('is-error');
			td.title = e?.message || String(e);
		} finally {
			td.classList.remove('is-saving');
			if (column.type !== 'BOOLEAN') td.replaceChildren(this.renderValue(column, row.values[column.name]));
		}
	}

	// Store one value on a row. The input's text is turned into a typed value
	// by the column's type; an empty text deletes the property. The row model
	// is updated to match, and the node watch confirms it later.
	private async storeValue(row: Row, column: DatasetProperty, text: string): Promise<void> {
		const value = this.toPropertyValue(column, text);
		const content = this.host.api().content;
		const result = await content.setProperties(row.path, [{ name: column.name, value } as PropertyInput]);
		const errors = result?.errors || [];
		if (errors.length > 0) {
			throw new Error(errors.map((e: any) => e.message).join('; '));
		}
		if (value == null) {
			delete row.values[column.name];
		} else {
			const parsed = this.toLocalValue(column, text);
			row.values[column.name] = { type: column.type, value: parsed.values ? null : parsed.value, values: parsed.values };
		}
	}

	private splitMultiple(text: string): string[] {
		return text.split(',').map(s => s.trim()).filter(s => s !== '');
	}

	private toPropertyValue(column: DatasetProperty, text: string): any | null {
		const trimmed = text.trim();
		if (column.multiple) {
			const items = this.splitMultiple(text);
			if (items.length === 0) return null;
			switch (column.type) {
				case 'LONG': return { longArrayValue: items.map(Number) };
				case 'DOUBLE': return { doubleArrayValue: items.map(Number) };
				case 'DECIMAL': return { decimalArrayValue: items };
				case 'BOOLEAN': return { booleanArrayValue: items.map(s => s === 'true') };
				case 'DATE': return { dateArrayValue: items.map(s => this.toISO(s)) };
				default: return { stringArrayValue: items };
			}
		}
		if (trimmed === '') return null;
		switch (column.type) {
			case 'LONG': return { longValue: Number(trimmed) };
			case 'DOUBLE': return { doubleValue: Number(trimmed) };
			case 'DECIMAL': return { decimalValue: trimmed };
			case 'BOOLEAN': return { booleanValue: trimmed === 'true' };
			case 'DATE': return { dateValue: this.toISO(trimmed) };
			default: return { stringValue: trimmed };
		}
	}

	private toLocalValue(column: DatasetProperty, text: string): { value: any; values: any[] | null } {
		const convert = (s: string): any => {
			switch (column.type) {
				case 'LONG': case 'DOUBLE': return Number(s);
				case 'BOOLEAN': return s === 'true';
				case 'DATE': return this.toISO(s);
				default: return s;
			}
		};
		if (column.multiple) return { value: null, values: this.splitMultiple(text).map(convert) };
		return { value: convert(text.trim()), values: null };
	}

	// A datetime-local wall-clock in the user's time zone as an ISO instant,
	// the way the Inspector stores a DATE.
	private toISO(local: string): string {
		const d = Dates.fromZonedInputValue(local, this.host.timeZone());
		return d && !isNaN(d.getTime()) ? d.toISOString() : local;
	}

	// ---- rows ----

	// Create an empty memo in the folder, with one column preset when asked
	// (a board lane's value, a calendar day).
	private async createRow(title: string, preset?: { column: DatasetProperty; text: string }): Promise<void> {
		const path = this.attrs.path;
		const name = title.toLowerCase().endsWith('.' + MEMO_EXTENSION) ? title : `${title}.${MEMO_EXTENSION}`;
		if (name.includes('/')) {
			throw new Error(this.t('app.memo.dataset.error.badName', undefined, 'A name cannot contain "/".'));
		}
		const content = this.host.api().content;
		const body = JSON.stringify({ version: 1, type: 'tiptap', doc: { type: 'doc', content: [{ type: 'paragraph' }] } });
		const created: GNode = await content.createFile(path, name, MEMO_MIME, toBase64(body));
		if (preset) {
			const row: Row = { path: created?.path || `${path}/${name}`, name, title: memoTitle(name), values: {} };
			await this.storeValue(row, preset.column, preset.text);
		}
		this.scheduleReload();
	}

	private async deleteRow(row: Row): Promise<void> {
		const content = this.host.api().content;
		await content.deleteNode(row.path);
		this.rows = this.rows.filter(r => r.path !== row.path);
		this.render();
	}

	private openRowMenu(event: MouseEvent, row: Row): void {
		const popup = this.host.popup();
		if (!popup) return;
		const handle = popup.open({
			anchor: { left: event.clientX, top: event.clientY },
			placement: 'bottom-start',
			minWidth: 200,
			items: [
				{ id: 'open', label: this.t('app.memo.dataset.row.open', undefined, 'Open'), icon: 'bi bi-box-arrow-up-right' },
				{ id: 'delete', label: this.t('app.memo.dataset.row.delete', undefined, 'Delete row…'), icon: 'bi bi-trash', danger: true },
			],
		});
		handle.result.then((id: string | null) => {
			if (id === 'open') this.host.openPath(row.path);
			else if (id === 'delete') this.confirm(
				this.t('app.memo.dataset.row.deleteConfirm', { name: row.title }, `Delete "${row.title}"? The memo and its values are removed.`),
				this.t('app.memo.dataset.delete', undefined, 'Delete'),
				() => this.deleteRow(row),
			);
		});
	}

	// ---- menus ----

	private openBlockMenu(event: MouseEvent): void {
		const popup = this.host.popup();
		if (!popup) return;
		const target = event.currentTarget as HTMLElement;
		const attrs = this.attrs;
		const hidden = attrs.hidden || [];
		const items: any[] = [];
		if (this.dataset) {
			items.push({ id: 'reload', label: this.t('app.memo.dataset.reload', undefined, 'Reload'), icon: 'bi bi-arrow-clockwise' });
			if (hidden.length > 0) {
				items.push({ id: 'showAll', label: this.t('app.memo.dataset.showAllColumns', { count: hidden.length }, `Show hidden columns (${hidden.length})`), icon: 'bi bi-eye' });
			}
			if (attrs.view !== 'calendar') {
				items.push({ id: 'wide', label: this.t('app.memo.dataset.wide', undefined, 'Full width'), icon: 'bi bi-arrows-expand-vertical', selected: !!attrs.wide });
			}
			items.push({ id: 'rename', label: this.t('app.memo.dataset.rename', undefined, 'Rename dataset…'), icon: 'bi bi-pencil' });
		}
		if (attrs.path) {
			items.push({ id: 'unlink', label: this.t('app.memo.dataset.unlink', undefined, 'Unlink folder'), icon: 'bi bi-folder-minus' });
		}
		items.push({ id: 'remove', label: this.t('app.memo.dataset.removeBlock', undefined, 'Remove block'), icon: 'bi bi-x-lg' });
		if (this.dataset) {
			items.push({ id: 'removeAll', label: this.t('app.memo.dataset.removeBlockAndDataset', undefined, 'Remove block and delete dataset…'), icon: 'bi bi-trash', danger: true });
		}
		const handle = popup.open({
			anchor: anchorOf(target),
			placement: 'bottom-end',
			minWidth: 240,
			items,
		});
		handle.result.then((id: string | null) => {
			switch (id) {
				case 'reload': void this.reload(); break;
				case 'showAll': this.setAttrs({ hidden: [] }); break;
				case 'wide': this.setAttrs({ wide: !attrs.wide }); break;
				case 'rename': this.openRenameForm(); break;
				case 'unlink': this.setAttrs({ path: '' }); break;
				case 'remove': this.removeBlock(); break;
				case 'removeAll': this.confirmDeleteDataset(); break;
			}
		});
	}

	private openColumnMenu(event: MouseEvent, column: DatasetProperty | null): void {
		const popup = this.host.popup();
		if (!popup) return;
		const target = event.currentTarget as HTMLElement;
		const key = column ? column.key : '';
		const items: any[] = [
			{ id: 'asc', label: this.t('app.memo.dataset.sortAsc', undefined, 'Sort ascending'), icon: 'bi bi-sort-down-alt' },
			{ id: 'desc', label: this.t('app.memo.dataset.sortDesc', undefined, 'Sort descending'), icon: 'bi bi-sort-up' },
		];
		if (column) {
			items.push({ id: 'hide', label: this.t('app.memo.dataset.hideColumn', undefined, 'Hide column'), icon: 'bi bi-eye-slash' });
			items.push({ id: 'edit', label: this.t('app.memo.dataset.editColumn', undefined, 'Edit column…'), icon: 'bi bi-pencil' });
			items.push({ id: 'delete', label: this.t('app.memo.dataset.deleteColumn', undefined, 'Delete column…'), icon: 'bi bi-trash', danger: true });
		}
		const handle = popup.open({
			anchor: anchorOf(target),
			placement: 'bottom-start',
			minWidth: 200,
			items,
		});
		handle.result.then((id: string | null) => {
			switch (id) {
				case 'asc': this.setAttrs({ sort: { key, dir: 'asc' } }); break;
				case 'desc': this.setAttrs({ sort: { key, dir: 'desc' } }); break;
				case 'hide': this.setAttrs({ hidden: [...(this.attrs.hidden || []), key] }); break;
				case 'edit': this.openColumnForm(column); break;
				case 'delete': if (column) this.confirmDeleteColumn(column); break;
			}
		});
	}

	// ---- forms and confirmations ----

	// Show a form: in the Inspector pane, which the block takes over and asks
	// the host to reveal; under the header when the block cannot be activated.
	private showPanel(panel: HTMLElement): void {
		this.panel = panel;
		this.activate();
		this.host.revealPane();
		this.render();
	}

	private closePanel(): void {
		this.panel = null;
		this.render();
	}

	private confirm(message: string, action: string, run: () => Promise<void>): void {
		const panel = el('div', 'memo-dataset-panel');
		panel.appendChild(el('div', 'memo-dataset-panel-text', message));
		const buttons = el('div', 'memo-dataset-panel-buttons');
		const ok = el('button', 'wt wt-danger wt-slim', action);
		ok.type = 'button';
		const cancel = el('button', 'wt wt-slim', this.t('app.memo.dataset.cancel', undefined, 'Cancel'));
		cancel.type = 'button';
		const errorLine = el('div', 'memo-dataset-message text-danger');
		errorLine.style.display = 'none';
		ok.addEventListener('click', async () => {
			ok.disabled = true;
			try {
				await run();
				if (!this.destroyed) this.closePanel();
			} catch (e: any) {
				errorLine.textContent = e?.message || String(e);
				errorLine.style.display = '';
				ok.disabled = false;
			}
		});
		cancel.addEventListener('click', () => this.closePanel());
		buttons.appendChild(ok);
		buttons.appendChild(cancel);
		panel.appendChild(buttons);
		panel.appendChild(errorLine);
		this.showPanel(panel);
	}

	private confirmDeleteColumn(column: DatasetProperty): void {
		this.confirm(
			this.t('app.memo.dataset.deleteColumnConfirm', { label: column.label || column.key }, `Delete column "${column.label || column.key}"? Values already stored on rows are kept.`),
			this.t('app.memo.dataset.delete', undefined, 'Delete'),
			() => this.rewriteDescriptor(doc => {
				doc.properties = (doc.properties || []).filter(p => p.key !== column.key);
			}),
		);
	}

	// Delete the dataset folder with every row in it, then the block. Asked
	// in the app's dialog, not the pane: it destroys data beyond this memo.
	private confirmDeleteDataset(): void {
		const dataset = this.dataset;
		if (!dataset) return;
		const label = dataset.label || dataset.id;
		const path = this.attrs.path;
		void this.host.confirmDelete(
			this.t('app.memo.dataset.deleteDialog.title', undefined, 'Delete dataset'),
			this.t('app.memo.dataset.removeBlockAndDatasetConfirm', { label }, `Delete the dataset folder "${label}" with every row in it, and remove this block? This cannot be undone.`),
		).then(async (ok) => {
			if (!ok || this.destroyed) return;
			try {
				await this.host.api().content.deleteNode(path);
				this.removeBlock();
			} catch (e: any) {
				if (this.destroyed) return;
				this.error = e?.message || String(e);
				this.render();
			}
		});
	}

	private openRenameForm(): void {
		if (!this.dataset) return;
		const panel = el('div', 'memo-dataset-panel');
		panel.appendChild(el('div', 'memo-dataset-panel-title', this.t('app.memo.dataset.rename', undefined, 'Rename dataset…').replace(/…$/, '')));
		const field = el('label', 'memo-dataset-field');
		field.appendChild(el('span', undefined, this.t('app.memo.dataset.form.label', undefined, 'Label')));
		const input = el('input', 'wt');
		input.type = 'text';
		input.value = this.dataset.label || '';
		field.appendChild(input);
		panel.appendChild(field);
		const errorLine = el('div', 'memo-dataset-message text-danger');
		errorLine.style.display = 'none';
		const buttons = el('div', 'memo-dataset-panel-buttons');
		const save = el('button', 'wt wt-primary wt-slim', this.t('app.memo.dataset.save', undefined, 'Save'));
		save.type = 'button';
		const cancel = el('button', 'wt wt-slim', this.t('app.memo.dataset.cancel', undefined, 'Cancel'));
		cancel.type = 'button';
		const submit = async () => {
			save.disabled = true;
			try {
				const label = input.value.trim();
				await this.rewriteDescriptor(doc => { if (label) doc.label = label; else delete doc.label; });
				this.closePanel();
			} catch (e: any) {
				errorLine.textContent = e?.message || String(e);
				errorLine.style.display = '';
				save.disabled = false;
			}
		};
		save.addEventListener('click', () => { void submit(); });
		cancel.addEventListener('click', () => this.closePanel());
		input.addEventListener('keydown', (e) => {
			if (e.key === 'Enter') { e.preventDefault(); void submit(); }
			else if (e.key === 'Escape') { e.preventDefault(); this.closePanel(); }
		});
		buttons.appendChild(save);
		buttons.appendChild(cancel);
		panel.appendChild(buttons);
		panel.appendChild(errorLine);
		this.showPanel(panel);
		setTimeout(() => { input.focus(); input.select(); }, 0);
	}

	// Add a column, or edit one. The key and the type are fixed once a column
	// exists: the stored property name is derived from the key, and the search
	// index types a name once for the whole repository.
	private openColumnForm(column: DatasetProperty | null): void {
		if (!this.dataset) return;
		const dataset = this.dataset;
		const panel = el('div', 'memo-dataset-panel memo-dataset-form');
		panel.appendChild(el('div', 'memo-dataset-panel-title', column
			? this.t('app.memo.dataset.editColumn', undefined, 'Edit column…').replace(/…$/, '')
			: this.t('app.memo.dataset.addColumn', undefined, 'Add column')));

		const labelField = el('label', 'memo-dataset-field');
		labelField.appendChild(el('span', undefined, this.t('app.memo.dataset.form.label', undefined, 'Label')));
		const labelInput = el('input', 'wt');
		labelInput.type = 'text';
		labelInput.value = column ? (column.label || '') : '';
		labelField.appendChild(labelInput);
		panel.appendChild(labelField);

		const keyField = el('label', 'memo-dataset-field');
		keyField.appendChild(el('span', undefined, this.t('app.memo.dataset.form.key', undefined, 'Key')));
		const keyInput = el('input', 'wt');
		keyInput.type = 'text';
		keyInput.value = column ? column.key : '';
		keyInput.disabled = !!column;
		keyInput.placeholder = 'status';
		keyField.appendChild(keyInput);
		const keyHint = el('div', 'memo-dataset-hint', column
			? this.t('app.memo.dataset.form.keyFixed', { name: column.name }, `Stored as ${column.name}`)
			: this.t('app.memo.dataset.form.keyHint', undefined, 'Letters, digits and underscores; cannot be changed later.'));
		keyField.appendChild(keyHint);
		panel.appendChild(keyField);
		let keyTouched = !!column;
		labelInput.addEventListener('input', () => {
			if (keyTouched) return;
			keyInput.value = suggestKey(labelInput.value);
		});
		keyInput.addEventListener('input', () => { keyTouched = keyInput.value !== ''; });

		const typeField = el('label', 'memo-dataset-field');
		typeField.appendChild(el('span', undefined, this.t('app.memo.dataset.form.type', undefined, 'Type')));
		const typeSelect = el('select', 'wt');
		for (const type of TYPES) {
			const opt = el('option', undefined, this.t('app.memo.dataset.type.' + type, undefined, type));
			opt.value = type;
			typeSelect.appendChild(opt);
		}
		typeSelect.value = column ? column.type : 'STRING';
		typeSelect.disabled = !!column;
		typeField.appendChild(typeSelect);
		panel.appendChild(typeField);

		const multipleField = el('label', 'memo-dataset-field memo-dataset-check');
		const multipleInput = el('input');
		multipleInput.type = 'checkbox';
		multipleInput.checked = column ? column.multiple : false;
		multipleInput.disabled = !!column;
		multipleField.appendChild(multipleInput);
		multipleField.appendChild(el('span', undefined, this.t('app.memo.dataset.form.multiple', undefined, 'Multiple values')));
		panel.appendChild(multipleField);

		const requiredField = el('label', 'memo-dataset-field memo-dataset-check');
		const requiredInput = el('input');
		requiredInput.type = 'checkbox';
		requiredInput.checked = column ? column.required : false;
		requiredField.appendChild(requiredInput);
		requiredField.appendChild(el('span', undefined, this.t('app.memo.dataset.form.required', undefined, 'Required')));
		panel.appendChild(requiredField);

		const printField = el('label', 'memo-dataset-field memo-dataset-check');
		const printInput = el('input');
		printInput.type = 'checkbox';
		printInput.checked = column ? isPrinted(column) : true;
		printField.appendChild(printInput);
		printField.appendChild(el('span', undefined, this.t('app.memo.dataset.form.print', undefined, 'Print')));
		panel.appendChild(printField);

		// Choices: one row per value, with its label and its swatch.
		const choices: ChoiceDraft[] = column
			? column.choices.map(c => ({ value: c.value, label: c.label && c.label !== c.value ? c.label : '', color: swatchKeyOf(c.color) }))
			: [];
		const choicesField = el('div', 'memo-dataset-field');
		choicesField.appendChild(el('span', undefined, this.t('app.memo.dataset.form.choices', undefined, 'Choices')));
		const choicesList = el('div', 'memo-dataset-choices');
		choicesField.appendChild(choicesList);
		const addChoice = button('wt wt-slim memo-dataset-choice-add', '', 'bi-plus-lg', this.t('app.memo.dataset.form.addChoice', undefined, 'Add choice'));
		choicesField.appendChild(addChoice);
		panel.appendChild(choicesField);
		const renderChoices = (focusIndex?: number) => {
			choicesList.replaceChildren();
			choices.forEach((choice, index) => choicesList.appendChild(this.renderChoiceRow(choice, () => {
				choices.splice(index, 1);
				renderChoices();
			})));
			if (focusIndex != null) {
				const row = choicesList.children[focusIndex] as HTMLElement | undefined;
				(row?.querySelector('input[type="text"]') as HTMLInputElement | null)?.focus();
			}
		};
		addChoice.addEventListener('click', () => {
			choices.push({ value: '', label: '', color: '' });
			renderChoices(choices.length - 1);
		});
		renderChoices();
		const syncChoices = () => { choicesField.style.display = typeSelect.value === 'STRING' ? '' : 'none'; };
		typeSelect.addEventListener('change', syncChoices);
		syncChoices();

		const errorLine = el('div', 'memo-dataset-message text-danger');
		errorLine.style.display = 'none';
		const buttons = el('div', 'memo-dataset-panel-buttons');
		const save = el('button', 'wt wt-primary wt-slim', this.t('app.memo.dataset.save', undefined, 'Save'));
		save.type = 'button';
		const cancel = el('button', 'wt wt-slim', this.t('app.memo.dataset.cancel', undefined, 'Cancel'));
		cancel.type = 'button';
		buttons.appendChild(save);
		buttons.appendChild(cancel);
		panel.appendChild(buttons);
		panel.appendChild(errorLine);

		const fail = (message: string) => {
			errorLine.textContent = message;
			errorLine.style.display = '';
			save.disabled = false;
		};
		const submit = async () => {
			save.disabled = true;
			errorLine.style.display = 'none';
			const label = labelInput.value.trim();
			let key = column ? column.key : keyInput.value.trim();
			if (!column && !key) {
				key = 'c' + (dataset.properties.length + 1);
				let n = dataset.properties.length + 1;
				while (dataset.properties.some(p => p.key === key)) key = 'c' + (++n);
			}
			if (!NAME_PATTERN.test(key)) {
				fail(this.t('app.memo.dataset.error.badKey', undefined, 'The key must start with a letter and contain only letters, digits and underscores.'));
				return;
			}
			if (!column && dataset.properties.some(p => p.key === key)) {
				fail(this.t('app.memo.dataset.error.duplicateKey', { key }, `A column with the key "${key}" already exists.`));
				return;
			}
			const type = typeSelect.value;
			const declared: DescriptorChoice[] = [];
			if (type === 'STRING') {
				for (const choice of choices) {
					const value = choice.value.trim();
					if (!value || declared.some(c => c.value === value)) continue;
					const entry: DescriptorChoice = { value };
					const text = choice.label.trim();
					if (text && text !== value) entry.label = text;
					if (choice.color) entry.color = choice.color;
					declared.push(entry);
				}
			}
			try {
				await this.rewriteDescriptor(doc => {
					const properties = Array.isArray(doc.properties) ? doc.properties : [];
					let entry = properties.find(p => p && p.key === key);
					if (!entry) {
						entry = { key, type };
						properties.push(entry);
					}
					if (label) entry.label = label; else delete entry.label;
					if (!column) {
						entry.type = type;
						if (multipleInput.checked) entry.multiple = true; else delete entry.multiple;
					}
					if (requiredInput.checked) entry.required = true; else delete entry.required;
					if (printInput.checked) delete entry.print; else entry.print = false;
					if (declared.length > 0) entry.choices = declared; else delete entry.choices;
					doc.properties = properties;
				});
				this.closePanel();
			} catch (e: any) {
				fail(e?.message || String(e));
			}
		};
		save.addEventListener('click', () => { void submit(); });
		cancel.addEventListener('click', () => this.closePanel());
		panel.addEventListener('keydown', (e: KeyboardEvent) => {
			if (e.key === 'Escape') { e.preventDefault(); this.closePanel(); }
			else if (e.key === 'Enter' && (e.target as HTMLElement | null)?.tagName === 'INPUT') { e.preventDefault(); void submit(); }
		});
		this.showPanel(panel);
		setTimeout(() => labelInput.focus(), 0);
	}

	// One choice in the column form: its swatch (a dot that opens the palette
	// below the row), value, label and a remove button. Edits go straight
	// into the draft.
	private renderChoiceRow(choice: ChoiceDraft, remove: () => void): HTMLElement {
		const row = el('div', 'memo-dataset-choice');
		const line = el('div', 'memo-dataset-choice-line');
		const dot = button('memo-dataset-choice-dot', this.t('app.memo.dataset.form.choiceColor', undefined, 'Color'), null);
		const paintDot = () => {
			dot.style.backgroundColor = choice.color ? SWATCH_COLOR_MAP[choice.color] : '';
			dot.classList.toggle('is-none', !choice.color);
		};
		paintDot();
		line.appendChild(dot);
		const value = el('input', 'wt');
		value.type = 'text';
		value.placeholder = this.t('app.memo.dataset.form.choiceValue', undefined, 'Value');
		value.value = choice.value;
		value.addEventListener('input', () => { choice.value = value.value; });
		line.appendChild(value);
		const label = el('input', 'wt');
		label.type = 'text';
		label.placeholder = this.t('app.memo.dataset.form.choiceLabel', undefined, 'Label');
		label.value = choice.label;
		label.addEventListener('input', () => { choice.label = label.value; });
		line.appendChild(label);
		const del = button('detail-section-header-btn memo-dataset-choice-remove', this.t('app.memo.dataset.form.removeChoice', undefined, 'Remove choice'), 'bi-x-lg');
		del.addEventListener('click', remove);
		line.appendChild(del);
		row.appendChild(line);

		// The palette: the Inspector's eleven swatches and "no color".
		const palette = el('div', 'insp-color-dots memo-dataset-choice-colors');
		palette.style.display = 'none';
		const pick = (key: string) => {
			choice.color = key;
			paintDot();
			palette.style.display = 'none';
			for (const b of Array.from(palette.children)) b.classList.toggle('active', (b as HTMLElement).dataset.color === key);
		};
		for (const swatch of SWATCH_COLORS) {
			const b = button('insp-color-dot' + (choice.color === swatch.key ? ' active' : ''), this.t('app.memo.color.' + swatch.key, undefined, swatch.label), null);
			b.style.backgroundColor = swatch.value;
			b.dataset.color = swatch.key;
			b.addEventListener('click', () => pick(swatch.key));
			palette.appendChild(b);
		}
		const none = button('insp-color-dot insp-color-none' + (choice.color ? '' : ' active'), this.t('app.memo.dataset.color.none', undefined, 'No color'), 'bi-x');
		none.dataset.color = '';
		none.addEventListener('click', () => pick(''));
		palette.appendChild(none);
		row.appendChild(palette);
		dot.addEventListener('click', () => { palette.style.display = palette.style.display === 'none' ? '' : 'none'; });
		return row;
	}

	// ---- descriptor ----

	// Create a dataset folder with an empty descriptor and return its path.
	private async createDataset(parent: string, name: string): Promise<string> {
		const content = this.host.api().content;
		const folder: GNode = await content.createFolder(parent, name);
		const path = folder?.path || (parent === '/' ? '' : parent) + '/' + name;
		const doc: DescriptorDocument = { id: generateDatasetId(), label: name, properties: [] };
		await content.createFile(path, DATASET_DESCRIPTOR_NAME, DATASET_DESCRIPTOR_MIME, toBase64(JSON.stringify(doc, null, 2) + '\n'));
		return path;
	}

	// Read the descriptor, apply `mutate`, write it back and reload. A
	// descriptor the block wrote (or any JSON one) is edited in place, so keys
	// this block does not know survive; a hand-written YAML one is rebuilt
	// from the server's parsed view, which drops anything the server ignored.
	private async rewriteDescriptor(mutate: (doc: DescriptorDocument) => void): Promise<void> {
		const path = this.attrs.path;
		const dataset = this.dataset;
		if (!path || !dataset) return;
		const content = this.host.api().content;
		const descriptorPath = `${path}/${DATASET_DESCRIPTOR_NAME}`;
		let doc: DescriptorDocument | null = null;
		const node: GNode | null = await content.getNode(descriptorPath);
		if (node?.downloadUrl) {
			const response = await fetch(node.downloadUrl);
			if (response.ok) {
				const text = await response.text();
				try {
					const parsed = JSON.parse(text);
					if (parsed && typeof parsed === 'object' && parsed.id) doc = parsed;
				} catch { /* not JSON: rebuilt below */ }
			}
		}
		if (!doc) {
			doc = {
				id: dataset.id,
				properties: dataset.properties.map(p => {
					const entry: DescriptorProperty = { key: p.key, type: p.type };
					if (p.label && p.label !== p.key) entry.label = p.label;
					if (p.description) entry.description = p.description;
					if (p.multiple) entry.multiple = true;
					if (p.required) entry.required = true;
					if (!isPrinted(p)) entry.print = false;
					if (p.choices.length > 0) {
						entry.choices = p.choices.map(c => {
							const choice: DescriptorChoice = { value: c.value };
							if (c.label && c.label !== c.value) choice.label = c.label;
							if (c.color) choice.color = c.color;
							return choice;
						});
					}
					return entry;
				}),
			};
			if (dataset.label && dataset.label !== dataset.id) doc.label = dataset.label;
			if (dataset.description) doc.description = dataset.description;
		}
		mutate(doc);
		const body = JSON.stringify(doc, null, 2) + '\n';
		const upload = await content.initiateMultipartUpload();
		try {
			await content.appendMultipartUploadData(upload.uploadId, body);
			await content.completeMultipartUpload(upload.uploadId, path, DATASET_DESCRIPTOR_NAME, DATASET_DESCRIPTOR_MIME, true);
		} catch (e) {
			try { await content.abortMultipartUpload(upload.uploadId); } catch { /* ignore */ }
			throw e;
		}
		await this.reload();
	}

	// ---- drops ----

	// A Content Browser drop on the block: the first folder becomes the
	// block's dataset. Returns whether the drop was taken.
	private onDropItems(items: any[]): boolean {
		const folder = items.find(it => it && it.isCollection && it.path);
		if (!folder) return false;
		this.setAttrs({ path: folder.path });
		return true;
	}
}

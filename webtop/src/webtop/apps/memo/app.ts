import { ApplicationInstance } from "../../services/webtop-service.js";
import type { Node } from "../../graphql/types.js";
import { BUILD_VERSION } from "../../utils/build-version.js";
import { nodeToInspectorTarget, type InspectorTarget } from "../../lib/inspector-target.js";
import { SWATCH_COLORS, SWATCH_HIGHLIGHT_COLORS } from "../../lib/color-palette.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";

// Side-effect import: registers the <wt-inspector> custom element so the
// right-pane Inspector can be embedded for the active tab's memo.
import "../../components/wt-inspector.js";

// Tiptap / ProseMirror
import { Editor, Extension } from "@tiptap/core";
import type { Range } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import { TextStyle, Color } from "@tiptap/extension-text-style";
import { Highlight } from "@tiptap/extension-highlight";
import { TextAlign } from "@tiptap/extension-text-align";
import { TaskList } from "@tiptap/extension-task-list";
import { TaskItem } from "@tiptap/extension-task-item";
import { Table, TableRow, TableHeader, TableCell } from "@tiptap/extension-table";
import { Image } from "@tiptap/extension-image";
import { Placeholder } from "@tiptap/extension-placeholder";
import Suggestion from "@tiptap/suggestion";
import {
	createSearchExtension,
	setMemoSearch,
	clearMemoSearch,
	getMemoSearchState,
	memoSearchNext,
	memoSearchPrev,
	memoReplaceCurrent,
	memoReplaceAll,
	type SearchOptions,
} from "./search.js";

// ----------------------------------------------------------------------------
// Native memo document format
//
// New memos are stored as Tiptap JSON (lossless) wrapped in a small envelope so
// the on-disk shape is self-describing and forward-compatible. The custom MIME
// type routes such files back to this app (see app.yml contentTypes). HTML
// files that are dragged in are round-tripped as HTML instead, preserving their
// markup; the Text Editor stays the default editor for HTML.
// ----------------------------------------------------------------------------
const MEMO_MIME = 'application/vnd.mintjams.cms.memo+json';
const MEMO_EXTENSION = 'memo';
const MEMO_FORMAT_VERSION = 1;

type MemoFormat = 'json' | 'html';

// Content Browser app id (see apps/content-browser/app.yml). The Inspector's
// "Open Containing Folder" action launches a fresh Content Browser at the
// memo's parent folder, since this app has no folder list of its own.
const CONTENT_BROWSER_APP_ID = '2468cf47-1a30-4053-b80a-9c5486954b08';

// Text- and highlight-colour menus draw from the shared swatch palette
// (lib/color-palette) — the same 11 variations as the EIP Console's elapsed
// picker. Values are concrete hex so a saved document is theme-independent (a
// memo authored in dark mode reads correctly on a light page, and vice versa).
// The colour menus themselves are rendered by the shell as swatch-grid context
// menus (see openColorMenu / openHighlightMenu), so they escape the iframe and
// match every other app's menu chrome.
const COLOR_CLEAR = '__clear__';

interface MemoFile {
	id: string;
	path: string;
	name: string;
	mimeType: string;
	// 'json' = native Tiptap document, 'html' = round-tripped HTML file.
	format: MemoFormat;
	// Serialized representation of the last-saved (or last-loaded) content.
	// Editor changes are compared against this to derive the dirty flag.
	originalContent: string;
	isModified: boolean;
	isVersionable: boolean;
	isCheckedOut: boolean;
	baseVersionName: string;
	encoding: string;
	downloadUrl: string;
	uuid: string;
	// Raw jcr:lastModified of the backing node as last seen by this tab.
	modified: string;
	// Object handed to <wt-inspector> as its target. Null for unsaved tabs.
	inspectorItem: InspectorTarget | null;
}

interface CurrentFile {
	path: string;
	name: string;
	mimeType: string;
	format: MemoFormat;
	originalContent: string;
	isModified: boolean;
	isLoading: boolean;
	encoding: string;
	downloadUrl: string;
	isVersionable: boolean;
	isCheckedOut: boolean;
	baseVersionName: string;
	uuid: string;
}

interface LaunchOptions {
	path?: string;
	mimeType?: string;
	paths?: string[];
	activeIndex?: number;
}

// Tiptap Editor instances live OUTSIDE reactive data: ichigo.js wraps stored
// objects in deep Proxies, which breaks ProseMirror's identity-based internals
// (the same reason the Text Editor keeps its CodeMirror states module-scoped).
// Each tab keeps its own Editor (and therefore its own undo history) keyed by
// MemoFile.id, mounted into its own child of #editor-host.
const editors = new Map<string, Editor>();
const mounts = new Map<string, HTMLElement>();

// SSE subscription for the active tab's node. Module-scoped for the same
// Proxy-safety reason; there is only ever one memo instance per iframe.
let activeNodeWatchUnsubscribe: (() => void) | null = null;

// Save As response channel. Native objects with privileged internals must not
// be stored in reactive data().
let saveAsChannelRef: BroadcastChannel | null = null;

function unwatchActiveNode(): void {
	if (activeNodeWatchUnsubscribe) {
		try { activeNodeWatchUnsubscribe(); } catch { /* ignore */ }
		activeNodeWatchUnsubscribe = null;
	}
}

// Fetch and inject the wt-inspector <template> into <body> so the custom
// element can resolve `template: '#wt-inspector'` once mounted via the v-if
// guard. Mirrors the Text Editor / Content Browser helper of the same name.
async function loadInspectorTemplate(): Promise<void> {
	const res = await fetch(`../../components/wt-inspector.html?v=${BUILD_VERSION}`);
	const html = await res.text();
	const doc = new DOMParser().parseFromString(html, 'text/html');
	for (const tmpl of Array.from(doc.querySelectorAll('template'))) {
		document.body.appendChild(tmpl);
	}
}

// Decide the on-disk format for a node from its MIME type / file name. Native
// memo documents are JSON; HTML files round-trip as HTML; everything else is
// treated as HTML too (plain text becomes a single paragraph on parse).
function detectFormat(mimeType: string, fileName: string): MemoFormat {
	const ext = (fileName.split('.').pop() || '').toLowerCase();
	const mt = (mimeType || '').toLowerCase();
	if (mt === MEMO_MIME || ext === MEMO_EXTENSION) return 'json';
	if (mt === 'application/json' && ext === MEMO_EXTENSION) return 'json';
	return 'html';
}

// Parse a fetched memo body into Tiptap-acceptable content (a JSON doc object
// for native memos, or an HTML string otherwise).
function parseContent(format: MemoFormat, raw: string): any {
	if (format !== 'json') return raw || '';
	const text = (raw || '').trim();
	if (!text) return emptyDoc();
	try {
		const parsed = JSON.parse(text);
		// Enveloped form { version, type: 'tiptap', doc } …
		if (parsed && parsed.type === 'tiptap' && parsed.doc) return parsed.doc;
		// … or a bare ProseMirror document.
		if (parsed && parsed.type === 'doc') return parsed;
		// Unexpected JSON shape — fall back to an empty doc rather than throw.
		return emptyDoc();
	} catch {
		// Not valid JSON: treat the bytes as HTML/plain text so nothing is lost.
		return raw;
	}
}

function emptyDoc(): any {
	return { type: 'doc', content: [{ type: 'paragraph' }] };
}

// Whether a dragged node / file is an image, by MIME type or file extension.
// Used to route Content Browser drops to in-document image placement instead of
// opening the file in a new tab.
const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'avif', 'ico']);
function isImageFile(mimeType: string | undefined, fileName: string | undefined): boolean {
	if (mimeType && mimeType.toLowerCase().startsWith('image/')) return true;
	const ext = (fileName || '').split('.').pop()?.toLowerCase() || '';
	return IMAGE_EXTENSIONS.has(ext);
}

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			files: [] as MemoFile[],
			currentFileIndex: -1,
			currentFile: {
				path: '',
				name: 'Untitled',
				mimeType: MEMO_MIME,
				format: 'json' as MemoFormat,
				originalContent: '',
				isModified: false,
				isLoading: false,
				encoding: 'UTF-8',
				downloadUrl: '',
				isVersionable: false,
				isCheckedOut: false,
				baseVersionName: '',
				uuid: '',
			} as CurrentFile,
			errorMessage: '',
			isSaving: false,
			canUndo: false,
			canRedo: false,
			wordCount: 0,
			charCount: 0,
			// Reactive toolbar state — mirrors the active editor's marks/blocks so
			// the formatting buttons can highlight. Refreshed on every selection
			// or document change via refreshActiveState().
			active: {
				paragraph: false,
				h1: false, h2: false, h3: false,
				bold: false, italic: false, underline: false, strike: false,
				code: false, highlight: false, link: false,
				bulletList: false, orderedList: false, taskList: false,
				blockquote: false, codeBlock: false,
				alignLeft: false, alignCenter: false, alignRight: false,
				table: false,
			},
			messageListener: null as ((e: MessageEvent) => void) | null,
			// Reactive Localization snapshot (effective locale + IANA time zone).
			localization: createLocalizationSnapshot(),
			// Left pane: Find & Replace. Mirrors the Text Editor's sidebar but
			// drives a custom ProseMirror search plugin (see search.ts) instead of
			// CodeMirror. Hidden by default so the memo opens uncluttered.
			sidebarPanelVisible: false,
			sidebarPanelWidth: 260,
			sidebarResizing: false,
			sidebarResizeStartX: 0,
			sidebarResizeStartWidth: 0,
			searchTerm: '',
			replaceTerm: '',
			searchCaseSensitive: false,
			searchWholeWord: false,
			searchRegex: false,
			searchNotFound: false,
			// Live match registry, surfaced as "{current} / {total}" in the panel.
			searchMatchCount: 0,
			searchMatchCurrent: 0,
			// Right pane: Inspector (wt-inspector). Layout owned by the host.
			detailPanelVisible: false,
			detailPanelWidth: 280,
			detailPanelMinWidth: 200,
			detailPanelMaxWidth: 500,
			detailPanelResizing: false,
			detailResizeStartX: 0,
			detailResizeStartWidth: 0,
			inspectorApi: null as any,
			inspectorOverlayOpen: false,
			// Checkout / checkin / close dialogs (version control flow, identical
			// in spirit to the Text Editor's).
			checkoutDialog: {
				visible: false,
				resolve: null as null | ((result: { checkout: boolean; checkinAfterSave: boolean }) => void),
			},
			checkinAfterSave: false,
			checkinDialog: { visible: false, isLoading: false },
			closeConfirmDialog: {
				visible: false,
				resolve: null as null | ((result: 'save' | 'discard' | 'cancel') => void),
			},
			saveAsDialog: { visible: false, fileName: '' },
			saveAsToken: '',
			// Link editor dialog (standard webtop dialog format, replacing the
			// native window.prompt). `hasExisting` toggles the "Remove link"
			// button when the caret already sits on a link.
			linkDialog: { visible: false, url: '', hasExisting: false },
		};
	},
	computed: {
		dockSubtitle(): string {
			const f = (this as any).files[(this as any).currentFileIndex];
			return f?.name || '';
		},
		inspectorTarget(): InspectorTarget | null {
			const f = (this as any).files[(this as any).currentFileIndex];
			return f?.inspectorItem ?? null;
		},
		inspectorOptions(): Record<string, any> {
			return {
				showOpenItem: false,
				hasUnsavedChanges: !!(this as any).currentFile.isModified,
			};
		},
	},
	watch: {
		dockSubtitle(val: string) {
			(this as any).instance?.setDisplayInfo({ subtitle: val });
		},
	},
	methods: {
		// Reactive i18n lookup. Reads the localization snapshot so every binding
		// repaints when the user switches language or a bundle hot-reloads.
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		async onMounted() {
			const vm = this;

			try {
				await loadInspectorTemplate();
			} catch (e) {
				console.warn('[Memo] Failed to load wt-inspector template:', e);
			}

			// Save As response channel — Content Browser posts here once a dragged
			// chip has been written, so this tab can adopt the new backing node.
			saveAsChannelRef = new BroadcastChannel('webtop-save-as');
			saveAsChannelRef.onmessage = (event: MessageEvent) => {
				if (event.data?.type === 'save-as-complete' && event.data.saveAsToken && event.data.saveAsToken === vm.saveAsToken) {
					vm.currentFile.path = event.data.path;
					vm.currentFile.name = event.data.name;
					vm.currentFile.mimeType = event.data.mimeType;
					vm.currentFile.isModified = false;
					vm.currentFile.originalContent = vm.serializeActive();
					if (vm.instance) vm.instance.windowTitle = event.data.name;
					vm.saveAsToken = '';
					if (vm.currentFileIndex >= 0 && vm.currentFileIndex < vm.files.length) {
						const f = vm.files[vm.currentFileIndex];
						f.path = event.data.path;
						f.name = event.data.name;
						f.mimeType = event.data.mimeType;
						f.format = detectFormat(event.data.mimeType, event.data.name);
						f.isModified = false;
						f.originalContent = vm.currentFile.originalContent;
						vm.currentFile.format = f.format;
						vm.watchActiveFileNode();
						vm.refreshActiveFileMetadata(event.data.path);
					}
				}
			};

			vm.messageListener = async (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
					return;
				}
				// Colour chosen from a shell swatch-grid menu. The action id encodes
				// which menu raised it: `text:<key|__clear__>` or `highlight:<…>`.
				if (type === 'context-menu-action') {
					vm.applyColorAction(payload.action as string);
					return;
				}
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;
			};
			window.addEventListener('message', vm.messageListener);

			// Tiptap's ResizableNodeView keeps each image hidden
			// (visibility:hidden; pointer-events:none) until its <img> fires `load`.
			// If a referenced repository asset is gone (deleted / moved / expired),
			// `load` never fires, so the node would stay invisible *and* unclickable —
			// impossible to select or delete with the mouse. `error` events don't
			// bubble, so catch them in the capture phase on the shared editor host and
			// reveal the failed image's container so the broken node stays selectable.
			const editorHost = vm.$refs.editorHost as HTMLElement | undefined;
			if (editorHost) {
				editorHost.addEventListener('error', (e: Event) => {
					const target = e.target as HTMLElement | null;
					if (!target || target.tagName !== 'IMG') return;
					const container = target.closest('[data-resize-container]') as HTMLElement | null;
					if (container) {
						container.style.visibility = '';
						container.style.pointerEvents = '';
					}
				}, true);
			}

			document.addEventListener('keydown', (e: KeyboardEvent) => {
				// While an Inspector overlay is up the user is editing inside the
				// panel — let it own the keyboard and skip our global shortcuts.
				if (vm.inspectorOverlayOpen) return;
				if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
					e.preventDefault();
					if (vm.currentFile.isModified && !vm.isSaving) vm.saveFile();
				} else if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F') && !e.shiftKey && !e.altKey) {
					// Ctrl+F opens the Find & Replace sidebar (matches the Text Editor).
					e.preventDefault();
					vm.openFindPanel();
				} else if ((e.ctrlKey || e.metaKey) && (e.key === 'i' || e.key === 'I')) {
					// Ctrl+I toggles the Inspector. ProseMirror also uses Ctrl+I for
					// italic, but the inspector toggle matches the Text Editor; the
					// toolbar / Ctrl+B still cover inline formatting.
					e.preventDefault();
					vm.toggleDetailPanel();
				}
			});

			window.appLaunch = async (instance: ApplicationInstance, options?: LaunchOptions) => {
				vm.instance = vm.$markRaw(instance);
				vm.inspectorApi = vm.$markRaw({
					content: instance.api.content,
					eventHub: instance.api.eventHub,
					popup: instance.popup,
				});
				instance.appState = () => {
					const paths = vm.files.map((f: MemoFile) => f.path).filter((p: string) => !!p);
					if (paths.length === 0) return {};
					return { paths, activeIndex: vm.currentFileIndex };
				};
				instance.setDisplayInfo({ subtitle: vm.dockSubtitle });

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				refreshLocalization(vm.localization, vm.instance);

				await vm.loadDetailPanelState();

				instance.setBeforeCloseCallback(async () => vm.confirmClose());

				if (options?.paths && options.paths.length > 0) {
					for (const p of options.paths) await vm.loadFile(p);
					const idx = options.activeIndex != null
						? Math.min(options.activeIndex, vm.files.length - 1)
						: vm.files.length - 1;
					if (vm.files.length > 0) vm.selectTab(idx);
					else vm.newFile();
				} else if (options?.path) {
					await vm.loadFile(options.path);
					if (vm.files.length === 0) vm.newFile();
				} else {
					vm.newFile();
				}

				vm.$nextTick(() => instance.notifyLaunched());
			};
		},
		onUnmount() {
			const vm = this;
			unwatchActiveNode();
			if (vm.messageListener) window.removeEventListener('message', vm.messageListener);
			for (const ed of editors.values()) {
				try { ed.destroy(); } catch { /* ignore */ }
			}
			editors.clear();
			mounts.clear();
			if (saveAsChannelRef) {
				saveAsChannelRef.close();
				saveAsChannelRef = null;
			}
		},
		// ---- Window controls ----
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },
		// ---- Find & Replace (left sidebar drives the ProseMirror search plugin) ----
		searchOptions(): SearchOptions {
			return {
				caseSensitive: this.searchCaseSensitive,
				wholeWord: this.searchWholeWord,
				regex: this.searchRegex,
			};
		},
		toggleSidebarPanel() {
			const vm = this;
			vm.sidebarPanelVisible = !vm.sidebarPanelVisible;
			if (vm.sidebarPanelVisible) {
				vm.applySearchQuery();
				vm.$nextTick(() => {
					const input = vm.$refs.findInput as HTMLInputElement | undefined;
					if (input) { input.focus(); input.select(); }
				});
			} else {
				vm.clearSearchHighlight();
			}
		},
		openFindPanel() {
			const vm = this;
			const ed = vm.activeEditor();
			if (ed) {
				const { from, to, empty } = ed.state.selection;
				if (!empty) {
					const selected = ed.state.doc.textBetween(from, to);
					if (selected && !selected.includes('\n')) {
						vm.searchTerm = selected;
						vm.searchNotFound = false;
					}
				}
			}
			vm.sidebarPanelVisible = true;
			vm.applySearchQuery();
			vm.$nextTick(() => {
				const input = vm.$refs.findInput as HTMLInputElement | undefined;
				if (input) { input.focus(); input.select(); }
			});
		},
		onSidebarResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			vm.sidebarResizing = true;
			vm.sidebarResizeStartX = event.clientX;
			vm.sidebarResizeStartWidth = vm.sidebarPanelWidth;
			const onMove = (e: MouseEvent) => {
				if (!vm.sidebarResizing) return;
				const delta = e.clientX - vm.sidebarResizeStartX;
				vm.sidebarPanelWidth = Math.max(180, Math.min(600, vm.sidebarResizeStartWidth + delta));
			};
			const onUp = () => {
				vm.sidebarResizing = false;
				document.removeEventListener('mousemove', onMove);
				document.removeEventListener('mouseup', onUp);
			};
			document.addEventListener('mousemove', onMove);
			document.addEventListener('mouseup', onUp);
		},
		// Push the current term/options into the active editor's search plugin and
		// refresh the reactive match counters. Idempotent — safe to call before
		// every navigation/replace.
		applySearchQuery() {
			const ed = this.activeEditor();
			if (!ed) return;
			setMemoSearch(ed, this.searchTerm, this.searchOptions());
			this.syncSearchStatus();
		},
		syncSearchStatus() {
			const ed = this.activeEditor();
			const st = ed ? getMemoSearchState(ed) : null;
			const total = st ? st.matches.length : 0;
			this.searchMatchCount = total;
			this.searchMatchCurrent = st && st.current >= 0 ? st.current + 1 : 0;
			this.searchNotFound = !!this.searchTerm && total === 0;
		},
		clearSearchHighlight() {
			const ed = this.activeEditor();
			if (ed) clearMemoSearch(ed);
			this.searchMatchCount = 0;
			this.searchMatchCurrent = 0;
			this.searchNotFound = false;
		},
		onSearchTermInput() {
			this.searchNotFound = false;
			this.applySearchQuery();
		},
		clearSearchTerm() {
			this.searchTerm = '';
			this.applySearchQuery();
		},
		toggleSearchCaseSensitive() {
			this.searchCaseSensitive = !this.searchCaseSensitive;
			this.applySearchQuery();
		},
		toggleSearchWholeWord() {
			this.searchWholeWord = !this.searchWholeWord;
			this.applySearchQuery();
		},
		toggleSearchRegex() {
			this.searchRegex = !this.searchRegex;
			this.applySearchQuery();
		},
		findNextMatch() {
			const ed = this.activeEditor();
			if (!ed || !this.searchTerm) return;
			this.applySearchQuery();
			const ok = memoSearchNext(ed);
			this.syncSearchStatus();
			this.searchNotFound = !ok;
		},
		findPrev() {
			const ed = this.activeEditor();
			if (!ed || !this.searchTerm) return;
			this.applySearchQuery();
			const ok = memoSearchPrev(ed);
			this.syncSearchStatus();
			this.searchNotFound = !ok;
		},
		replaceCurrent() {
			const ed = this.activeEditor();
			if (!ed || !this.searchTerm) return;
			this.applySearchQuery();
			const ok = memoReplaceCurrent(ed, this.replaceTerm);
			this.syncSearchStatus();
			if (!ok) this.searchNotFound = true;
		},
		replaceAllInDoc() {
			const ed = this.activeEditor();
			if (!ed || !this.searchTerm) return;
			this.applySearchQuery();
			const count = memoReplaceAll(ed, this.replaceTerm);
			this.syncSearchStatus();
			// A successful replace-all naturally leaves zero matches; don't report
			// that as "No matches" (which reads like the operation failed).
			this.searchNotFound = count === 0;
		},
		onSearchTermKeydown(event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				if (event.shiftKey) this.findPrev(); else this.findNextMatch();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.focusEditor();
			}
		},
		onReplaceTermKeydown(event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				if (event.ctrlKey || event.metaKey) this.replaceAllInDoc(); else this.replaceCurrent();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.focusEditor();
			}
		},
		// ---- Inspector (right pane) ----
		toggleDetailPanel() {
			this.detailPanelVisible = !this.detailPanelVisible;
			this.persistDetailPanelState();
		},
		onDetailResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			vm.detailPanelResizing = true;
			vm.detailResizeStartX = event.clientX;
			vm.detailResizeStartWidth = vm.detailPanelWidth;
			const onMove = (e: MouseEvent) => {
				if (!vm.detailPanelResizing) return;
				const delta = vm.detailResizeStartX - e.clientX;
				vm.detailPanelWidth = Math.max(
					vm.detailPanelMinWidth,
					Math.min(vm.detailPanelMaxWidth, vm.detailResizeStartWidth + delta),
				);
			};
			const onUp = () => {
				vm.detailPanelResizing = false;
				document.removeEventListener('mousemove', onMove);
				document.removeEventListener('mouseup', onUp);
				vm.persistDetailPanelState();
			};
			document.addEventListener('mousemove', onMove);
			document.addEventListener('mouseup', onUp);
		},
		async persistDetailPanelState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'memo', 'detailPanel', {
					visible: vm.detailPanelVisible,
					width: vm.detailPanelWidth,
				});
			} catch { /* ignore */ }
		},
		async loadDetailPanelState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				const state = await db.getUserSetting(userID, 'memo', 'detailPanel');
				if (state) {
					vm.detailPanelVisible = state.visible ?? false;
					vm.detailPanelWidth = state.width ?? 280;
				}
			} catch { /* ignore */ }
		},
		onInspectorOverlayChanged(open: boolean) { this.inspectorOverlayOpen = !!open; },
		onInspectorRevealItem(target: any) {
			const path = target?.path;
			if (!path) return;
			const idx = path.lastIndexOf('/');
			const parent = idx > 0 ? path.substring(0, idx) : '/';
			window.parent?.postMessage({
				type: 'open-app',
				appId: CONTENT_BROWSER_APP_ID,
				options: { initialPath: parent },
			}, window.location.origin);
		},
		watchActiveFileNode() {
			const vm = this;
			unwatchActiveNode();
			const file = vm.files[vm.currentFileIndex];
			if (!file || !file.path) return;
			const eventHub = vm.instance?.api?.eventHub;
			if (!eventHub || typeof eventHub.watchNode !== 'function') return;
			const path = file.path;
			activeNodeWatchUnsubscribe = eventHub.watchNode(path, () => {
				vm.refreshActiveFileMetadata(path);
			}, false);
		},
		async refreshActiveFileMetadata(path: string) {
			const vm = this;
			const idx = vm.files.findIndex((f: MemoFile) => f.path === path);
			if (idx < 0) return;
			const contentService = vm.instance?.api?.content;
			if (!contentService) return;
			let node: Node | null;
			try {
				node = await contentService.getNode(path);
			} catch {
				return;
			}
			if (!node) return;
			const file = vm.files[idx];
			const prevModified = file.modified || '';
			const nextModified = node.modified || '';
			const contentMayHaveChanged = !!prevModified && !!nextModified && prevModified !== nextModified;
			file.mimeType = node.mimeType || file.mimeType;
			file.encoding = node.encoding || 'UTF-8';
			file.isVersionable = node.isVersionable || false;
			file.isCheckedOut = node.isCheckedOut || false;
			file.baseVersionName = node.baseVersionName || '';
			file.downloadUrl = node.downloadUrl || '';
			file.uuid = node.uuid || '';
			file.modified = nextModified;
			file.inspectorItem = nodeToInspectorTarget(node);
			if (idx === vm.currentFileIndex) {
				vm.currentFile.mimeType = file.mimeType;
				vm.currentFile.encoding = file.encoding;
				vm.currentFile.isVersionable = file.isVersionable;
				vm.currentFile.isCheckedOut = file.isCheckedOut;
				vm.currentFile.baseVersionName = file.baseVersionName;
				vm.currentFile.downloadUrl = file.downloadUrl;
			}
			if (contentMayHaveChanged) vm.reloadFileContent(path, false);
		},
		async reloadFileContent(path: string, force: boolean) {
			const vm = this;
			const idx = vm.files.findIndex((f: MemoFile) => f.path === path);
			if (idx < 0) return;
			let file = vm.files[idx];
			if (!force && file.isModified) return;
			const contentService = vm.instance?.api?.content;
			if (!contentService) return;
			let text: string;
			try {
				const node: Node | null = await contentService.getNode(path);
				if (!node || !node.downloadUrl) return;
				const response = await fetch(node.downloadUrl);
				if (!response.ok) return;
				text = await response.text();
			} catch {
				return;
			}
			const liveIdx = vm.files.findIndex((f: MemoFile) => f.path === path);
			if (liveIdx < 0) return;
			file = vm.files[liveIdx];
			if (!force && file.isModified) return;
			if (text === file.originalContent && !file.isModified) return;
			file.originalContent = text;
			file.isModified = false;
			const ed = editors.get(file.id);
			if (ed) {
				ed.commands.setContent(parseContent(file.format, text), { emitUpdate: false } as any);
			}
			if (liveIdx === vm.currentFileIndex) {
				vm.currentFile.originalContent = text;
				vm.currentFile.isModified = false;
				vm.refreshActiveState();
				vm.refreshCounts();
			}
		},
		onInspectorContentReverted(detail: any) {
			const path = detail?.target?.path;
			if (!path) return;
			this.reloadFileContent(path, true);
			this.refreshActiveFileMetadata(path);
		},
		// ---- Editor lifecycle ----
		buildExtensions() {
			const vm = this;
			const placeholder = vm.t('app.memo.hint.slash', undefined, "Type '/' for commands, or just start writing…");
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
				// Images are placed by dragging a Content Browser image onto the
				// editor (see handleDropEvent → insertImagesAtDrop). The node stores
				// the source node's URL, so the memo stays lightweight and the image
				// remains the repository-managed asset.
				//
				// `resize` turns on Tiptap's built-in ResizableNodeView: selecting an
				// image reveals corner grips that resize it by dragging.
				// `alwaysPreserveAspectRatio` locks the ratio so width and height
				// scale together. The chosen size persists as the node's width /
				// height attributes — lossless in the saved JSON, and emitted as
				// <img width height> when a memo is round-tripped to HTML.
				Image.configure({
					inline: false,
					resize: {
						enabled: true,
						alwaysPreserveAspectRatio: true,
						minWidth: 48,
						minHeight: 48,
					},
				}),
				Placeholder.configure({ placeholder }),
				vm.buildSlashExtension(),
				// Find & Replace (highlights + match registry); see search.ts.
				createSearchExtension(),
			];
		},
		// Create (once) the per-tab Editor and its mount, returning the Editor.
		ensureEditor(file: MemoFile, content: any): Editor {
			const vm = this;
			let ed = editors.get(file.id);
			if (ed) return ed;
			const host = vm.$refs.editorHost as HTMLElement | undefined;
			const mount = document.createElement('div');
			mount.className = 'memo-editor-mount';
			mount.dataset.tabId = file.id;
			if (host) host.appendChild(mount);
			mounts.set(file.id, mount);
			ed = new Editor({
				element: mount,
				extensions: vm.buildExtensions(),
				content,
				autofocus: false,
				onUpdate: () => vm.onEditorUpdate(file.id),
				onSelectionUpdate: () => { if (file.id === vm.activeId()) vm.refreshActiveState(); },
			});
			editors.set(file.id, ed);
			return ed;
		},
		activeId(): string {
			const f = (this as any).files[(this as any).currentFileIndex];
			return f?.id || '';
		},
		activeEditor(): Editor | null {
			return editors.get(this.activeId()) || null;
		},
		// Serialize the active editor's content to the on-disk string form.
		serializeActive(): string {
			const ed = this.activeEditor();
			if (!ed) return this.currentFile.originalContent;
			return this.serializeEditor(ed, this.currentFile.format);
		},
		serializeEditor(ed: Editor, format: MemoFormat): string {
			if (format === 'json') {
				return JSON.stringify({ version: MEMO_FORMAT_VERSION, type: 'tiptap', doc: ed.getJSON() });
			}
			return ed.getHTML();
		},
		onEditorUpdate(tabId: string) {
			const vm = this;
			if (tabId !== vm.activeId()) return;
			const ed = editors.get(tabId);
			if (!ed) return;
			const serialized = vm.serializeEditor(ed, vm.currentFile.format);
			const modified = serialized !== vm.currentFile.originalContent;
			vm.currentFile.isModified = modified;
			if (vm.currentFileIndex >= 0 && vm.currentFileIndex < vm.files.length) {
				vm.files[vm.currentFileIndex].isModified = modified;
			}
			vm.refreshActiveState();
			vm.refreshCounts();
		},
		refreshCounts() {
			const ed = this.activeEditor();
			if (!ed) { this.wordCount = 0; this.charCount = 0; return; }
			const text = ed.getText({ blockSeparator: '\n' } as any) || '';
			this.charCount = text.length;
			const words = text.trim().match(/\S+/g);
			this.wordCount = words ? words.length : 0;
		},
		refreshActiveState() {
			const vm = this;
			const ed = vm.activeEditor();
			const a = vm.active;
			if (!ed) return;
			a.paragraph = ed.isActive('paragraph');
			a.h1 = ed.isActive('heading', { level: 1 });
			a.h2 = ed.isActive('heading', { level: 2 });
			a.h3 = ed.isActive('heading', { level: 3 });
			a.bold = ed.isActive('bold');
			a.italic = ed.isActive('italic');
			a.underline = ed.isActive('underline');
			a.strike = ed.isActive('strike');
			a.code = ed.isActive('code');
			a.highlight = ed.isActive('highlight');
			a.link = ed.isActive('link');
			a.bulletList = ed.isActive('bulletList');
			a.orderedList = ed.isActive('orderedList');
			a.taskList = ed.isActive('taskList');
			a.blockquote = ed.isActive('blockquote');
			a.codeBlock = ed.isActive('codeBlock');
			a.alignLeft = ed.isActive({ textAlign: 'left' });
			a.alignCenter = ed.isActive({ textAlign: 'center' });
			a.alignRight = ed.isActive({ textAlign: 'right' });
			a.table = ed.isActive('table');
			vm.canUndo = ed.can().undo();
			vm.canRedo = ed.can().redo();
		},
		focusEditor() {
			const vm = this;
			const ed = vm.activeEditor();
			if (!ed) return;
			const doFocus = () => {
				try { window.focus(); ed.commands.focus(); } catch { /* ignore */ }
			};
			vm.$nextTick(() => { doFocus(); setTimeout(doFocus, 0); });
		},
		// ---- Formatting actions (chained through the active editor) ----
		chain() {
			const ed = this.activeEditor();
			return ed ? ed.chain().focus() : null;
		},
		setParagraph() { this.chain()?.setParagraph().run(); },
		toggleHeading(level: number) { this.chain()?.toggleHeading({ level }).run(); },
		toggleBold() { this.chain()?.toggleBold().run(); },
		toggleItalic() { this.chain()?.toggleItalic().run(); },
		toggleUnderline() { this.chain()?.toggleUnderline().run(); },
		toggleStrike() { this.chain()?.toggleStrike().run(); },
		toggleCode() { this.chain()?.toggleCode().run(); },
		toggleHighlight() { this.chain()?.toggleHighlight().run(); },
		toggleBulletList() { this.chain()?.toggleBulletList().run(); },
		toggleOrderedList() { this.chain()?.toggleOrderedList().run(); },
		toggleTaskList() { this.chain()?.toggleTaskList().run(); },
		toggleBlockquote() { this.chain()?.toggleBlockquote().run(); },
		toggleCodeBlock() { this.chain()?.toggleCodeBlock().run(); },
		setAlign(align: string) { this.chain()?.setTextAlign(align).run(); },
		insertHorizontalRule() { this.chain()?.setHorizontalRule().run(); },
		insertTable() { this.chain()?.insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(); },
		addColumnAfter() { this.chain()?.addColumnAfter().run(); },
		addRowAfter() { this.chain()?.addRowAfter().run(); },
		deleteColumn() { this.chain()?.deleteColumn().run(); },
		deleteRow() { this.chain()?.deleteRow().run(); },
		toggleHeaderRow() { this.chain()?.toggleHeaderRow().run(); },
		deleteTable() { this.chain()?.deleteTable().run(); },
		// ---- Link dialog (standard webtop dialog, replaces window.prompt) ----
		promptLink() {
			const vm = this;
			const ed = vm.activeEditor();
			if (!ed) return;
			const hasExisting = ed.isActive('link');
			vm.linkDialog.url = hasExisting ? (ed.getAttributes('link').href || '') : '';
			vm.linkDialog.hasExisting = hasExisting;
			vm.linkDialog.visible = true;
			vm.$nextTick(() => {
				const input = vm.$refs.linkInput as HTMLInputElement | undefined;
				input?.focus();
				input?.select();
			});
		},
		confirmLinkDialog() {
			const vm = this;
			const ed = vm.activeEditor();
			const url = vm.linkDialog.url.trim();
			vm.linkDialog.visible = false;
			if (!ed) return;
			if (url === '') {
				// Empty URL clears any existing link rather than setting a blank href.
				ed.chain().focus().extendMarkRange('link').unsetLink().run();
				return;
			}
			ed.chain().focus().extendMarkRange('link').setLink({ href: url }).run();
		},
		removeLinkDialog() {
			const vm = this;
			const ed = vm.activeEditor();
			vm.linkDialog.visible = false;
			ed?.chain().focus().extendMarkRange('link').unsetLink().run();
		},
		cancelLinkDialog() {
			this.linkDialog.visible = false;
		},
		// ---- Colour menus (shell-rendered swatch-grid context menus) ----
		// Text colour and highlight colour both raise the same swatch grid in the
		// shell realm (so it escapes the iframe and matches every other app's
		// menu chrome). The chosen swatch returns via a 'context-menu-action'
		// message whose id is `<kind>:<paletteKey|__clear__>` — see
		// applyColorAction / the onMounted message listener.
		openColorMenu(event: MouseEvent) { this.openSwatchMenu(event, 'text'); },
		openHighlightMenu(event: MouseEvent) { this.openSwatchMenu(event, 'highlight'); },
		openSwatchMenu(event: MouseEvent, kind: 'text' | 'highlight') {
			const vm = this;
			const ed = vm.activeEditor();
			if (!ed) return;
			const current = kind === 'text'
				? (ed.getAttributes('textStyle').color || '')
				: (ed.getAttributes('highlight').color || '');
			const currentLc = String(current).toLowerCase();
			// Highlights use the pale companion palette so dark body text stays
			// readable; foreground text colour uses the saturated base palette.
			const palette = kind === 'highlight' ? SWATCH_HIGHLIGHT_COLORS : SWATCH_COLORS;
			const items: any[] = palette.map((c) => ({
				id: `${kind}:${c.key}`,
				label: vm.t('app.memo.color.' + c.key, undefined, c.label),
				swatch: c.value,
				selected: c.value.toLowerCase() === currentLc,
			}));
			// Trailing "Default / none" cell (no swatch → rendered as a dashed X).
			items.push({
				id: `${kind}:${COLOR_CLEAR}`,
				label: vm.t('app.memo.format.colorClear', undefined, 'Default'),
				icon: 'bi-x-lg',
				selected: currentLc === '',
			});
			const btn = (event.currentTarget as HTMLElement) || (event.target as HTMLElement);
			const r = btn.getBoundingClientRect();
			try {
				window.parent.postMessage({
					type: 'show-context-menu',
					x: r.left,
					y: r.bottom + 4,
					variant: 'swatch-grid',
					columns: 6,
					items,
					sourceAppId: vm.instance?.id,
				}, window.location.origin);
			} catch { /* parent unavailable */ }
		},
		// Apply a colour chosen from the shell swatch grid. `action` is
		// `<kind>:<paletteKey|__clear__>`.
		applyColorAction(action: string) {
			const vm = this;
			const ed = vm.activeEditor();
			if (!ed || !action) return;
			const sep = action.indexOf(':');
			if (sep < 0) return;
			const kind = action.slice(0, sep);
			const key = action.slice(sep + 1);
			if (kind === 'text') {
				if (key === COLOR_CLEAR) ed.chain().focus().unsetColor().run();
				else {
					const c = SWATCH_COLORS.find((x) => x.key === key);
					if (c) ed.chain().focus().setColor(c.value).run();
				}
			} else if (kind === 'highlight') {
				if (key === COLOR_CLEAR) ed.chain().focus().unsetHighlight().run();
				else {
					const c = SWATCH_HIGHLIGHT_COLORS.find((x) => x.key === key);
					if (c) ed.chain().focus().setHighlight({ color: c.value }).run();
				}
			}
		},
		// ---- Slash command menu ----
		// The command catalog. Each entry runs against the active editor, first
		// deleting the typed "/query" range so only the resulting block remains.
		slashItems(query: string): any[] {
			const vm = this;
			const items = [
				{ key: 'text', icon: 'bi-text-paragraph', title: vm.t('app.memo.slash.text', undefined, 'Text'), desc: vm.t('app.memo.slash.textDesc', undefined, 'Plain paragraph'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).setParagraph().run() },
				{ key: 'h1', icon: 'bi-type-h1', title: vm.t('app.memo.slash.h1', undefined, 'Heading 1'), desc: vm.t('app.memo.slash.h1Desc', undefined, 'Large section heading'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).setNode('heading', { level: 1 }).run() },
				{ key: 'h2', icon: 'bi-type-h2', title: vm.t('app.memo.slash.h2', undefined, 'Heading 2'), desc: vm.t('app.memo.slash.h2Desc', undefined, 'Medium section heading'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).setNode('heading', { level: 2 }).run() },
				{ key: 'h3', icon: 'bi-type-h3', title: vm.t('app.memo.slash.h3', undefined, 'Heading 3'), desc: vm.t('app.memo.slash.h3Desc', undefined, 'Small section heading'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).setNode('heading', { level: 3 }).run() },
				{ key: 'bullet', icon: 'bi-list-ul', title: vm.t('app.memo.slash.bullet', undefined, 'Bullet list'), desc: vm.t('app.memo.slash.bulletDesc', undefined, 'Simple bulleted list'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).toggleBulletList().run() },
				{ key: 'ordered', icon: 'bi-list-ol', title: vm.t('app.memo.slash.ordered', undefined, 'Numbered list'), desc: vm.t('app.memo.slash.orderedDesc', undefined, 'Ordered list'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).toggleOrderedList().run() },
				{ key: 'task', icon: 'bi-list-check', title: vm.t('app.memo.slash.task', undefined, 'Task list'), desc: vm.t('app.memo.slash.taskDesc', undefined, 'Checklist with checkboxes'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).toggleTaskList().run() },
				{ key: 'quote', icon: 'bi-quote', title: vm.t('app.memo.slash.quote', undefined, 'Quote'), desc: vm.t('app.memo.slash.quoteDesc', undefined, 'Block quotation'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).toggleBlockquote().run() },
				{ key: 'code', icon: 'bi-code-square', title: vm.t('app.memo.slash.code', undefined, 'Code block'), desc: vm.t('app.memo.slash.codeDesc', undefined, 'Preformatted code'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).toggleCodeBlock().run() },
				{ key: 'table', icon: 'bi-table', title: vm.t('app.memo.slash.table', undefined, 'Table'), desc: vm.t('app.memo.slash.tableDesc', undefined, '3×3 table with header'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run() },
				{ key: 'divider', icon: 'bi-dash-lg', title: vm.t('app.memo.slash.divider', undefined, 'Divider'), desc: vm.t('app.memo.slash.dividerDesc', undefined, 'Horizontal rule'), run: (e: Editor, r: Range) => e.chain().focus().deleteRange(r).setHorizontalRule().run() },
			];
			const q = (query || '').toLowerCase().trim();
			if (!q) return items;
			return items.filter((it) => it.title.toLowerCase().includes(q) || it.key.includes(q));
		},
		buildSlashExtension(): any {
			const vm = this;
			return Extension.create({
				name: 'slashCommand',
				addProseMirrorPlugins() {
					return [
						Suggestion({
							editor: this.editor,
							char: '/',
							startOfLine: false,
							// Don't trigger inside code blocks.
							allow: ({ editor }: any) => !editor.isActive('codeBlock'),
							items: ({ query }: { query: string }) => vm.slashItems(query),
							command: ({ editor, range, props }: any) => props.run(editor, range),
							render: () => vm.makeSlashRenderer(),
						}),
					];
				},
			});
		},
		// Returns the render lifecycle object the Suggestion plugin drives. The
		// menu itself is rendered by the shell via the popup service (the same
		// iframe-escaping mechanism the Inspector dropdowns use), so it can't be
		// clipped by the editor iframe and shares every other app's menu chrome.
		// The editor keeps keyboard focus and drives selection: arrow keys move
		// the `highlighted` row via handle.update(), Enter runs the command.
		makeSlashRenderer() {
			const vm = this;
			let handle: any = null;
			let items: any[] = [];
			let selected = 0;
			let command: (item: any) => void = () => {};

			const toPopupItems = () => {
				if (!items.length) {
					return [{
						id: '__none__',
						label: vm.t('app.memo.slash.empty', undefined, 'No matching blocks'),
						disabled: true,
					}];
				}
				return items.map((it, i) => ({
					id: it.key,
					label: it.title,
					description: it.desc,
					icon: 'bi ' + it.icon,
					highlighted: i === selected,
				}));
			};

			const anchorFrom = (rect: DOMRect | null | undefined) => rect
				? { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height }
				: { left: 0, top: 0 };

			const refresh = () => { if (handle) handle.update(toPopupItems()); };

			const closeHandle = () => {
				if (handle) { try { handle.close(); } catch { /* ignore */ } handle = null; }
			};

			return {
				onStart: (props: any) => {
					items = props.items || [];
					selected = 0;
					command = (item: any) => props.command(item);
					const popup = vm.instance?.popup;
					if (!popup) return;
					handle = popup.open({
						anchor: anchorFrom(props.clientRect?.()),
						placement: 'bottom-start',
						minWidth: 240,
						items: toPopupItems(),
					});
					// Mouse click resolves the result promise with the chosen id.
					handle.result.then((id: string | number | null) => {
						handle = null;
						if (id == null || id === '__none__') return;
						const chosen = items.find((it) => it.key === id);
						if (chosen) command(chosen);
					});
				},
				onUpdate: (props: any) => {
					items = props.items || [];
					selected = 0;
					command = (item: any) => props.command(item);
					refresh();
				},
				onKeyDown: (props: any) => {
					const e = props.event as KeyboardEvent;
					if (!handle) return false;
					if (e.key === 'ArrowDown') {
						if (items.length) { selected = (selected + 1) % items.length; refresh(); }
						return true;
					}
					if (e.key === 'ArrowUp') {
						if (items.length) { selected = (selected - 1 + items.length) % items.length; refresh(); }
						return true;
					}
					if (e.key === 'Enter') {
						const chosen = items[selected];
						if (chosen) { const run = command; closeHandle(); run(chosen); }
						return true;
					}
					if (e.key === 'Escape') {
						closeHandle();
						return true;
					}
					return false;
				},
				onExit: () => { closeHandle(); },
			};
		},
		// ---- Tab management ----
		generateFileID(): string {
			return 'memo_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
		},
		saveCurrentFileState() {
			const vm = this;
			if (vm.currentFileIndex < 0 || vm.currentFileIndex >= vm.files.length) return;
			const file = vm.files[vm.currentFileIndex];
			file.path = vm.currentFile.path;
			file.name = vm.currentFile.name;
			file.mimeType = vm.currentFile.mimeType;
			file.format = vm.currentFile.format;
			file.originalContent = vm.currentFile.originalContent;
			file.isModified = vm.currentFile.isModified;
			file.isVersionable = vm.currentFile.isVersionable;
			file.isCheckedOut = vm.currentFile.isCheckedOut;
			file.baseVersionName = vm.currentFile.baseVersionName;
			file.encoding = vm.currentFile.encoding;
			file.downloadUrl = vm.currentFile.downloadUrl;
		},
		restoreFileState(index: number) {
			const vm = this;
			const file = vm.files[index];
			if (!file) return;
			vm.currentFile.path = file.path;
			vm.currentFile.name = file.name;
			vm.currentFile.mimeType = file.mimeType;
			vm.currentFile.format = file.format;
			vm.currentFile.originalContent = file.originalContent;
			vm.currentFile.isModified = file.isModified;
			vm.currentFile.isVersionable = file.isVersionable;
			vm.currentFile.isCheckedOut = file.isCheckedOut;
			vm.currentFile.baseVersionName = file.baseVersionName;
			vm.currentFile.encoding = file.encoding;
			vm.currentFile.downloadUrl = file.downloadUrl;
			// Show only this tab's editor mount.
			for (const [id, m] of mounts) m.classList.toggle('is-active', id === file.id);
			vm.refreshActiveState();
			vm.refreshCounts();
			vm.focusEditor();
			vm.watchActiveFileNode();
			// Re-apply the find query to this tab's editor so highlights and the
			// match count follow the active document (each editor owns its own
			// search plugin state).
			if (vm.sidebarPanelVisible) vm.applySearchQuery();
		},
		selectTab(index: number) {
			const vm = this;
			if (index === vm.currentFileIndex) return;
			if (index < 0 || index >= vm.files.length) return;
			vm.saveCurrentFileState();
			vm.currentFileIndex = index;
			vm.restoreFileState(index);
			if (vm.instance) vm.instance.windowTitle = vm.files[index].name;
		},
		async closeFile(index: number) {
			const vm = this;
			if (index < 0 || index >= vm.files.length) return;
			const file = vm.files[index];
			const originalIndex = vm.currentFileIndex;
			if (vm.currentFileIndex >= 0) vm.saveCurrentFileState();

			if (file.isModified) {
				if (index !== vm.currentFileIndex) {
					vm.currentFileIndex = index;
					vm.restoreFileState(index);
				}
				const result = await vm.showCloseConfirmDialog();
				if (result === 'cancel') return;
				if (result === 'save') {
					await vm.saveFile();
					if (vm.currentFile.isModified) return;
				}
			}

			// Tear down the tab's editor + mount.
			const ed = editors.get(file.id);
			if (ed) { try { ed.destroy(); } catch { /* ignore */ } editors.delete(file.id); }
			const m = mounts.get(file.id);
			if (m) { try { m.remove(); } catch { /* ignore */ } mounts.delete(file.id); }
			vm.files.splice(index, 1);

			if (vm.files.length === 0) {
				vm.currentFileIndex = -1;
				vm.newFile();
				return;
			}

			let targetIndex: number;
			if (index === originalIndex) targetIndex = Math.min(index, vm.files.length - 1);
			else if (index < originalIndex) targetIndex = originalIndex - 1;
			else targetIndex = originalIndex;

			vm.currentFileIndex = targetIndex;
			vm.restoreFileState(targetIndex);
			if (vm.instance) vm.instance.windowTitle = vm.files[targetIndex].name;
		},
		newFile() {
			const vm = this;
			if (vm.currentFileIndex >= 0) vm.saveCurrentFileState();

			const baseName = vm.t('app.memo.untitled', undefined, 'Untitled');
			const name = `${baseName}.${MEMO_EXTENSION}`;
			const original = JSON.stringify({ version: MEMO_FORMAT_VERSION, type: 'tiptap', doc: emptyDoc() });

			const newFile: MemoFile = {
				id: vm.generateFileID(),
				path: '',
				name,
				mimeType: MEMO_MIME,
				format: 'json',
				originalContent: original,
				isModified: false,
				isVersionable: false,
				isCheckedOut: false,
				baseVersionName: '',
				encoding: 'UTF-8',
				downloadUrl: '',
				uuid: '',
				modified: '',
				inspectorItem: null,
			};

			vm.files.push(newFile);
			vm.ensureEditor(newFile, emptyDoc());
			vm.currentFileIndex = vm.files.length - 1;
			vm.restoreFileState(vm.currentFileIndex);
			if (vm.instance) vm.instance.windowTitle = name;
		},
		// ---- Drag & drop ----
		onAppDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		onCenterPaneDragOver(event: DragEvent) {
			if (!event.dataTransfer) return;
			if (event.dataTransfer.types.includes('application/x-webtop-save')) {
				event.dataTransfer.dropEffect = 'none';
				return;
			}
			const isFileDrop = event.dataTransfer.types.includes('Files')
				|| event.dataTransfer.types.includes('application/x-webtop-file')
				|| event.dataTransfer.types.includes('application/x-webtop-files');
			event.dataTransfer.dropEffect = isFileDrop ? 'copy' : 'none';
		},
		async onCenterPaneDrop(event: DragEvent) {
			const types = event.dataTransfer?.types ?? [];
			if (types.includes('application/x-webtop-save')) {
				event.preventDefault();
				event.stopPropagation();
				return;
			}
			const isFileDrop = types.includes('Files')
				|| types.includes('application/x-webtop-file')
				|| types.includes('application/x-webtop-files');
			if (!isFileDrop) return;
			event.preventDefault();
			event.stopPropagation();
			await this.handleDropEvent(event);
		},
		onForbiddenDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		async handleDropEvent(event: DragEvent) {
			const vm = this;
			const dt = event.dataTransfer;

			// Internal Content Browser drag — one or more repository nodes. Image
			// nodes are placed into the document at the drop point (referencing the
			// node's URL); everything else keeps the "open in a new tab" behaviour.
			let items: any[] = [];
			const multi = dt?.getData('application/x-webtop-files');
			const single = dt?.getData('application/x-webtop-file');
			if (multi) {
				try { const arr = JSON.parse(multi); if (Array.isArray(arr)) items = arr; }
				catch (e) { console.error('Failed to parse webtop files data:', e); }
			} else if (single) {
				try { const o = JSON.parse(single); if (o) items = [o]; }
				catch (e) { console.error('Failed to parse webtop file data:', e); }
			}
			if (items.length > 0) {
				const isImg = (it: any) => it && !it.isCollection && isImageFile(it.mimeType, it.name);
				const images = items.filter(isImg);
				if (images.length > 0) await vm.insertImagesAtDrop(images, event);
				for (const it of items) {
					if (!isImg(it) && it?.path) await vm.loadFile(it.path);
				}
				return;
			}

			const files = dt?.files;
			if (files && files.length > 0) {
				const file = files[0];
				try {
					const raw = await file.text();
					const mimeType = file.type || 'text/html';
					const format = detectFormat(mimeType, file.name);
					if (vm.currentFileIndex >= 0) vm.saveCurrentFileState();
					const newFile: MemoFile = {
						id: vm.generateFileID(),
						path: '',
						name: file.name,
						mimeType,
						format,
						originalContent: raw,
						isModified: false,
						isVersionable: false,
						isCheckedOut: false,
						baseVersionName: '',
						encoding: 'UTF-8',
						downloadUrl: '',
						uuid: '',
						modified: '',
						inspectorItem: null,
					};
					vm.files.push(newFile);
					vm.ensureEditor(newFile, parseContent(format, raw));
					// Reconcile originalContent with the editor's normalized output so
					// the freshly opened tab is not immediately flagged as modified.
					const ed = editors.get(newFile.id)!;
					newFile.originalContent = vm.serializeEditor(ed, format);
					vm.currentFileIndex = vm.files.length - 1;
					vm.restoreFileState(vm.currentFileIndex);
					if (vm.instance) vm.instance.windowTitle = file.name;
				} catch (e) {
					console.error('Failed to load local file:', file.name, e);
				}
			}
		},
		// Insert dragged Content Browser images into the active document at the
		// drop point. Each image references the source node's URL (downloadURL
		// from the drag payload, or the node's downloadUrl as a fallback) so the
		// memo stays lightweight and the image remains a repository-managed asset.
		async insertImagesAtDrop(images: any[], event: DragEvent) {
			const vm = this;
			const ed = vm.activeEditor();
			if (!ed) return;
			const nodes: any[] = [];
			for (const it of images) {
				let src: string = it.downloadURL || '';
				if (!src && it.path) {
					try {
						const node = await vm.instance?.api?.content?.getNode(it.path);
						src = node?.downloadUrl || '';
					} catch { /* ignore */ }
				}
				if (!src) continue;
				nodes.push({ type: 'image', attrs: { src, alt: it.name || null } });
			}
			if (!nodes.length) return;
			// Place at the drop point when it resolves to a document position;
			// otherwise fall back to the current caret.
			const at = ed.view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos;
			let chain = ed.chain().focus();
			if (at != null) chain = chain.setTextSelection(at);
			chain.insertContent(nodes).run();
		},
		// ---- Loading ----
		async loadFile(path: string) {
			const vm = this;
			const existingIndex = vm.files.findIndex((f) => f.path === path);
			if (existingIndex >= 0) { vm.selectTab(existingIndex); return; }

			vm.currentFile.isLoading = true;
			vm.errorMessage = '';
			try {
				const contentService = vm.instance.api.content;
				const node: Node | null = await contentService.getNode(path);
				if (!node) throw new Error(vm.t('app.memo.error.fileNotFound', { path }, `Memo not found: ${path}`));

				if (vm.currentFileIndex >= 0) vm.saveCurrentFileState();

				let raw = '';
				if (node.downloadUrl) {
					const response = await fetch(node.downloadUrl);
					if (!response.ok) throw new Error(vm.t('app.memo.error.fetchFailed', { status: response.statusText }, `Failed to fetch: ${response.statusText}`));
					raw = await response.text();
				}

				const mimeType = node.mimeType || MEMO_MIME;
				const format = detectFormat(mimeType, node.name);

				const newFile: MemoFile = {
					id: vm.generateFileID(),
					path: node.path,
					name: node.name,
					mimeType,
					format,
					originalContent: raw,
					isModified: false,
					isVersionable: node.isVersionable || false,
					isCheckedOut: node.isCheckedOut || false,
					baseVersionName: node.baseVersionName || '',
					encoding: node.encoding || 'UTF-8',
					downloadUrl: node.downloadUrl || '',
					uuid: node.uuid || '',
					modified: node.modified || '',
					inspectorItem: nodeToInspectorTarget(node),
				};

				vm.files.push(newFile);
				vm.ensureEditor(newFile, parseContent(format, raw));
				// Normalize originalContent against the parsed/reserialized output so
				// round-trip whitespace differences don't mark a pristine tab dirty.
				const ed = editors.get(newFile.id)!;
				newFile.originalContent = vm.serializeEditor(ed, format);

				vm.currentFileIndex = vm.files.length - 1;
				vm.restoreFileState(vm.currentFileIndex);
				if (vm.instance) vm.instance.windowTitle = node.name;
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || vm.t('app.memo.error.loadFile', undefined, 'Failed to load memo');
			} finally {
				vm.currentFile.isLoading = false;
			}
		},
		// ---- Saving ----
		async saveFile() {
			const vm = this;
			if (!vm.currentFile.path || vm.isSaving) return;
			vm.errorMessage = '';
			try {
				const contentService = vm.instance.api.content;

				if (vm.currentFile.isVersionable && !vm.currentFile.isCheckedOut) {
					const result = await vm.showCheckoutDialog();
					if (!result.checkout) return;
					await contentService.checkout(vm.currentFile.path);
					vm.currentFile.isCheckedOut = true;
					vm.checkinAfterSave = result.checkinAfterSave;
				}

				vm.isSaving = true;

				const content = vm.serializeActive();
				const encoder = new TextEncoder();
				const bytes = encoder.encode(content);
				const base64 = btoa(String.fromCharCode(...bytes));

				const pathParts = vm.currentFile.path.split('/');
				const fileName = pathParts.pop() as string;
				const parentPath = pathParts.join('/') || '/';

				const uploadInfo = await contentService.initiateMultipartUpload();
				const uploadId = uploadInfo.uploadId;
				try {
					await contentService.appendMultipartUploadChunk(uploadId, base64);
					await contentService.completeMultipartUpload(
						uploadId, parentPath, fileName, vm.currentFile.mimeType, true,
					);

					vm.currentFile.originalContent = content;
					vm.currentFile.isModified = false;
					if (vm.currentFileIndex >= 0 && vm.currentFileIndex < vm.files.length) {
						vm.files[vm.currentFileIndex].isModified = false;
						vm.files[vm.currentFileIndex].originalContent = content;
					}

					if (vm.checkinAfterSave && vm.currentFile.isVersionable && vm.currentFile.isCheckedOut) {
						await contentService.checkin(vm.currentFile.path);
						vm.currentFile.isCheckedOut = false;
						vm.checkinAfterSave = false;
						const node = await contentService.getNode(vm.currentFile.path);
						if (node) vm.currentFile.baseVersionName = node.baseVersionName || '';
					} else if (vm.currentFile.isVersionable && vm.currentFile.isCheckedOut) {
						vm.showCheckinDialog();
					}
				} catch (error) {
					try { await contentService.abortMultipartUpload(uploadId); } catch { /* ignore */ }
					throw error;
				}
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || vm.t('app.memo.error.saveFile', undefined, 'Failed to save memo');
			} finally {
				vm.isSaving = false;
			}
		},
		// ---- Save As (drag chip to Content Browser) ----
		openSaveAsDialog() {
			this.saveAsDialog.fileName = this.currentFile.name || `untitled.${MEMO_EXTENSION}`;
			this.saveAsDialog.visible = true;
		},
		closeSaveAsDialog() { this.saveAsDialog.visible = false; },
		onSaveAsDragStart(event: DragEvent) {
			if (!event.dataTransfer) return;
			const content = this.serializeActive();
			const fileName = this.saveAsDialog.fileName || `untitled.${MEMO_EXTENSION}`;
			this.saveAsToken = Date.now().toString(36) + Math.random().toString(36).slice(2);
			event.dataTransfer.effectAllowed = 'copy';
			event.dataTransfer.setData('application/x-webtop-save', JSON.stringify({
				name: fileName,
				mimeType: this.currentFile.mimeType || MEMO_MIME,
				content,
				saveAsToken: this.saveAsToken,
			}));
			event.dataTransfer.setData('text/plain', fileName);
			setTimeout(() => { this.saveAsDialog.visible = false; }, 100);
		},
		// ---- Undo / Redo ----
		undo() { this.activeEditor()?.chain().focus().undo().run(); },
		redo() { this.activeEditor()?.chain().focus().redo().run(); },
		dismissError() { this.errorMessage = ''; },
		// ---- Checkout / Checkin dialogs ----
		showCheckoutDialog(): Promise<{ checkout: boolean; checkinAfterSave: boolean }> {
			const vm = this;
			vm.checkoutDialog.visible = true;
			return new Promise((resolve) => { vm.checkoutDialog.resolve = resolve; });
		},
		onCheckoutDialogAction(action: 'checkout' | 'checkoutAndCheckin' | 'cancel') {
			const vm = this;
			if (vm.checkoutDialog.resolve) {
				if (action === 'checkout') vm.checkoutDialog.resolve({ checkout: true, checkinAfterSave: false });
				else if (action === 'checkoutAndCheckin') vm.checkoutDialog.resolve({ checkout: true, checkinAfterSave: true });
				else vm.checkoutDialog.resolve({ checkout: false, checkinAfterSave: false });
			}
			vm.checkoutDialog.visible = false;
			vm.checkoutDialog.resolve = null;
		},
		showCheckinDialog() { this.checkinDialog.visible = true; this.checkinDialog.isLoading = false; },
		closeCheckinDialog() { this.checkinDialog.visible = false; this.checkinDialog.isLoading = false; },
		async submitCheckin() {
			const vm = this;
			vm.checkinDialog.isLoading = true;
			try {
				const contentService = vm.instance.api.content;
				await contentService.checkin(vm.currentFile.path);
				vm.currentFile.isCheckedOut = false;
				const node = await contentService.getNode(vm.currentFile.path);
				if (node) vm.currentFile.baseVersionName = node.baseVersionName || '';
				vm.closeCheckinDialog();
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || vm.t('app.memo.error.checkin', undefined, 'Failed to checkin');
				vm.closeCheckinDialog();
			}
		},
		async submitCheckpoint() {
			const vm = this;
			vm.checkinDialog.isLoading = true;
			try {
				const contentService = vm.instance.api.content;
				await contentService.checkpoint(vm.currentFile.path);
				const node = await contentService.getNode(vm.currentFile.path);
				if (node) vm.currentFile.baseVersionName = node.baseVersionName || '';
				vm.closeCheckinDialog();
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || vm.t('app.memo.error.checkpoint', undefined, 'Failed to create checkpoint');
				vm.closeCheckinDialog();
			}
		},
		// ---- Close confirmation ----
		async confirmClose(): Promise<boolean> {
			const vm = this;
			if (vm.currentFileIndex >= 0) vm.saveCurrentFileState();
			const hasModified = vm.files.some((f) => f.isModified);
			if (!hasModified) return true;

			const result = await vm.showCloseConfirmDialog();
			if (result === 'cancel') return false;
			if (result === 'save') {
				for (let i = 0; i < vm.files.length; i++) {
					if (vm.files[i].isModified) {
						if (i !== vm.currentFileIndex) {
							vm.saveCurrentFileState();
							vm.currentFileIndex = i;
							vm.restoreFileState(i);
						}
						await vm.saveFile();
						if (vm.currentFile.isModified) return false;
					}
				}
			}
			return true;
		},
		showCloseConfirmDialog(): Promise<'save' | 'discard' | 'cancel'> {
			const vm = this;
			vm.closeConfirmDialog.visible = true;
			return new Promise((resolve) => { vm.closeConfirmDialog.resolve = resolve; });
		},
		onCloseConfirmDialogAction(action: 'save' | 'discard' | 'cancel') {
			const vm = this;
			if (vm.closeConfirmDialog.resolve) vm.closeConfirmDialog.resolve(action);
			vm.closeConfirmDialog.visible = false;
			vm.closeConfirmDialog.resolve = null;
		},
	},
};

import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');

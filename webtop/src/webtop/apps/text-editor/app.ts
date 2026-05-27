import { ApplicationInstance } from "../../services/webtop-service.js";
import type { Node } from "../../graphql/types.js";
import { BUILD_VERSION } from "../../utils/build-version.js";
import { nodeToInspectorTarget, type InspectorTarget } from "../../lib/inspector-target.js";

// Side-effect import: registers the <wt-inspector> custom element so the
// right-pane Inspector can be embedded for the active tab's file.
import "../../components/wt-inspector.js";

// CodeMirror imports
import { EditorState, Compartment } from "@codemirror/state";
import { EditorView, keymap, lineNumbers, highlightActiveLineGutter, highlightActiveLine, drawSelection } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab, undo, redo } from "@codemirror/commands";
import { syntaxHighlighting, HighlightStyle, bracketMatching, indentOnInput } from "@codemirror/language";
import { tags as t } from "@lezer/highlight";
import { search, findNext, findPrevious, replaceNext, replaceAll, setSearchQuery, SearchQuery } from "@codemirror/search";
import { autocompletion, completionKeymap, acceptCompletion } from "@codemirror/autocomplete";

// Language imports
import { javascript } from "@codemirror/lang-javascript";
import { json } from "@codemirror/lang-json";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { xml } from "@codemirror/lang-xml";
import { markdown } from "@codemirror/lang-markdown";
import { python } from "@codemirror/lang-python";
import { java } from "@codemirror/lang-java";
import { yaml } from "@codemirror/lang-yaml";
import { sql } from "@codemirror/lang-sql";
import { php } from "@codemirror/lang-php";

// CodeMirror theme — colors come from CSS variables, so the editor follows
// the app's light/dark theme automatically (variables defined in style.css).
const cmTheme = EditorView.theme({
	"&": { backgroundColor: "var(--body-bg)", color: "var(--body-color)" },
	".cm-content": { caretColor: "var(--body-color)" },
	".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--body-color)" },
	".cm-scroller .cm-layer.cm-selectionLayer .cm-selectionBackground, ::selection": { backgroundColor: "var(--item-selected-bg)" },
	".cm-gutters": { backgroundColor: "var(--list-header-bg)", color: "var(--text-soft-color)", border: "none" },
	".cm-activeLineGutter": { backgroundColor: "var(--btn-icon-hover-bg)" },
	".cm-activeLine": { backgroundColor: "var(--btn-icon-hover-bg)" },
});

// Syntax highlight — color values are CSS variables, so the highlight
// follows the app's light/dark theme automatically.
const cmHighlight = HighlightStyle.define([
	{ tag: [t.keyword, t.controlKeyword, t.modifier, t.operatorKeyword, t.self], color: "var(--cm-keyword)" },
	{ tag: [t.string, t.special(t.string), t.regexp, t.attributeValue], color: "var(--cm-string)" },
	{ tag: [t.comment, t.lineComment, t.blockComment, t.docComment], color: "var(--cm-comment)", fontStyle: "italic" },
	{ tag: [t.number, t.bool, t.null, t.atom, t.literal], color: "var(--cm-number)" },
	{ tag: [t.function(t.variableName), t.function(t.propertyName), t.macroName, t.labelName], color: "var(--cm-function)" },
	{ tag: [t.className, t.typeName, t.namespace, t.definition(t.typeName)], color: "var(--cm-class)" },
	{ tag: [t.propertyName, t.attributeName], color: "var(--cm-property)" },
	{ tag: [t.tagName, t.angleBracket], color: "var(--cm-tag)" },
	{ tag: [t.operator, t.derefOperator, t.compareOperator, t.arithmeticOperator, t.logicOperator, t.bitwiseOperator, t.updateOperator], color: "var(--cm-operator)" },
	{ tag: [t.punctuation, t.bracket, t.brace, t.paren, t.separator], color: "var(--cm-punctuation)" },
	{ tag: [t.meta, t.processingInstruction, t.definition(t.variableName)], color: "var(--cm-meta)" },
	{ tag: t.heading, color: "var(--cm-keyword)", fontWeight: "bold" },
	{ tag: [t.link, t.url], color: "var(--cm-property)", textDecoration: "underline" },
	{ tag: t.emphasis, fontStyle: "italic" },
	{ tag: t.strong, fontWeight: "bold" },
	{ tag: t.strikethrough, textDecoration: "line-through" },
	{ tag: t.invalid, color: "var(--cm-invalid)" },
]);

// MIME / extension to language extension mapping
function getLanguageExtension(mimeType: string, fileName: string) {
	const extension = fileName.split('.').pop()?.toLowerCase() || '';

	const extensionMap: Record<string, () => any> = {
		'js': () => javascript(),
		'mjs': () => javascript(),
		'jsx': () => javascript({ jsx: true }),
		'ts': () => javascript({ typescript: true }),
		'tsx': () => javascript({ jsx: true, typescript: true }),
		'json': () => json(),
		'html': () => html(),
		'htm': () => html(),
		'css': () => css(),
		'xml': () => xml(),
		'svg': () => xml(),
		'md': () => markdown(),
		'markdown': () => markdown(),
		'py': () => python(),
		'java': () => java(),
		'groovy': () => java(),
		'yml': () => yaml(),
		'yaml': () => yaml(),
		'sql': () => sql(),
		'php': () => php(),
	};

	if (extensionMap[extension]) {
		return extensionMap[extension]();
	}

	const mimeMap: Record<string, () => any> = {
		'text/javascript': () => javascript(),
		'application/javascript': () => javascript(),
		'application/x-javascript': () => javascript(),
		'text/typescript': () => javascript({ typescript: true }),
		'application/json': () => json(),
		'text/html': () => html(),
		'text/css': () => css(),
		'application/xml': () => xml(),
		'text/xml': () => xml(),
		'text/markdown': () => markdown(),
		'text/x-markdown': () => markdown(),
		'text/x-python': () => python(),
		'application/x-python': () => python(),
		'text/x-java': () => java(),
		'application/x-groovy': () => java(),
		'application/x-gsp': () => html(),
		'application/yaml': () => yaml(),
		'application/x-yaml': () => yaml(),
		'text/yaml': () => yaml(),
		'application/sql': () => sql(),
		'text/x-sql': () => sql(),
		'text/x-php': () => php(),
		'application/x-php': () => php(),
	};

	if (mimeMap[mimeType]) {
		return mimeMap[mimeType]();
	}

	return [];
}

interface CurrentFile {
	path: string;
	name: string;
	mimeType: string;
	content: string;
	originalContent: string;
	isModified: boolean;
	isLoading: boolean;
	encoding: string;
	downloadUrl: string;
	isVersionable: boolean;
	isCheckedOut: boolean;
	baseVersionName: string;
	uuid: string;
	hasWebTemplate: boolean;
	scriptable: boolean;
}

interface TextFile {
	id: string;
	path: string;
	name: string;
	mimeType: string;
	originalContent: string;
	isModified: boolean;
	isVersionable: boolean;
	isCheckedOut: boolean;
	baseVersionName: string;
	encoding: string;
	downloadUrl: string;
	uuid: string;
	hasWebTemplate: boolean;
	scriptable: boolean;
	// Object handed to <wt-inspector> as its target. Null for unsaved tabs
	// (new file / dropped local file) that have no backing node yet.
	inspectorItem: InspectorTarget | null;
}

// MIME type → preview extension hint. Used to seed the extension input on the
// templated preview header. Fallback is 'html'.
const MIME_TO_EXTENSION: Record<string, string> = {
	'text/html': 'html',
	'application/xhtml+xml': 'html',
	'text/css': 'css',
	'text/javascript': 'js',
	'application/javascript': 'js',
	'application/json': 'json',
	'text/csv': 'csv',
	'text/tab-separated-values': 'tsv',
	'text/plain': 'txt',
	'application/xml': 'xml',
	'text/xml': 'xml',
	'application/rss+xml': 'rss',
	'application/atom+xml': 'atom',
	'application/yaml': 'yaml',
	'text/yaml': 'yaml',
	'text/markdown': 'md',
};

function guessExtensionFromMime(mimeType: string): string {
	if (!mimeType) return 'html';
	const ext = MIME_TO_EXTENSION[mimeType.toLowerCase()];
	return ext || 'html';
}

// EditorStates are kept outside of reactive data because ichigo.js wraps
// stored objects in deep Proxies, which breaks CodeMirror's identity-based
// facet/extension resolution (notably syntaxHighlighting). Indexed by
// TextFile.id so each tab keeps its own undo history and selection.
const editorStates = new Map<string, EditorState>();

// SSE subscription for the active tab's node. Kept in module scope (not in
// reactive data) for the same reason as the preview channels below: there is
// only ever one text-editor instance per iframe, and the unsubscribe handle
// carries privileged internals that must not be Proxy-wrapped. The editor
// watches whichever file is active so changes made through the Inspector
// (MIME type, encoding, lock/version) flow back into the editor's own state.
let activeNodeWatchUnsubscribe: (() => void) | null = null;

// Content Browser app id (see apps/content-browser/app.yml). The Inspector's
// "Open Containing Folder" action launches a fresh Content Browser at the
// file's parent folder, since the text-editor has no folder list of its own.
const CONTENT_BROWSER_APP_ID = '2468cf47-1a30-4053-b80a-9c5486954b08';

function unwatchActiveNode(): void {
	if (activeNodeWatchUnsubscribe) {
		try { activeNodeWatchUnsubscribe(); } catch { /* ignore */ }
		activeNodeWatchUnsubscribe = null;
	}
}

// Fetch and inject the wt-inspector <template> into <body> so the custom
// element can resolve `template: '#wt-inspector'` once it is mounted via the
// v-if guard. Mirrors content-browser's helper of the same name.
async function loadInspectorTemplate(): Promise<void> {
	const res = await fetch(`../../components/wt-inspector.html?v=${BUILD_VERSION}`);
	const html = await res.text();
	const doc = new DOMParser().parseFromString(html, 'text/html');
	for (const tmpl of Array.from(doc.querySelectorAll('template'))) {
		document.body.appendChild(tmpl);
	}
}

// Companion Preview window — see apps/text-editor-preview. The text-editor
// keeps at most one preview window per editor instance; it follows whichever
// tab is currently active. previewKey is set to the editor's
// ApplicationInstance id so multiple text-editor windows can each have their
// own preview without crosstalk on the shared BroadcastChannel.
const PREVIEW_APP_ID = 'e7a6fc4b-41c4-4362-8358-58839be4dd96';
const PREVIEW_CHANNEL = 'webtop-text-preview';
const PREVIEW_PING_INTERVAL_MS = 2000;

// Native objects with privileged internal slots (BroadcastChannel, ...) must
// NOT be stored in ichigojs's reactive data(). Even $markRaw isn't enough:
// reads still pass through the parent data Proxy and `this` gets unbound
// from the underlying instance, throwing "Illegal invocation" the moment
// any native method runs. Keep these in module scope — there is only ever
// one text-editor instance per iframe.
let previewChannelRef: BroadcastChannel | null = null;
let saveAsChannelRef: BroadcastChannel | null = null;
// Path that the companion preview window is currently pinned to. When the
// matching tab is closed, the editor notifies the preview so it can drop the
// pin and follow the new active tab.
let previewPinnedPath: string = '';

interface LaunchOptions {
	path?: string;
	mimeType?: string;
	paths?: string[];
	activeIndex?: number;
}

// Non-reactive compartment for language switching (kept outside data() so
// ichigo.js does not Proxy-wrap it — Compartment relies on identity).
const languageCompartment = new Compartment();

// Build the standard set of CodeMirror extensions used for every editor
// state. The built-in search panel is suppressed because search/replace UI
// lives in the left sidebar.
function buildEditorExtensions(updateListener: any, languageExt: any) {
	return [
		lineNumbers(),
		highlightActiveLineGutter(),
		history(),
		drawSelection(),
		bracketMatching(),
		indentOnInput(),
		syntaxHighlighting(cmHighlight, { fallback: true }),
		highlightActiveLine(),
		search({ top: true, createPanel: () => ({ dom: document.createElement('div') }) }),
		autocompletion(),
		keymap.of([
			{ key: "Tab", run: acceptCompletion },
			indentWithTab,
			...defaultKeymap,
			...historyKeymap,
			...completionKeymap,
		]),
		cmTheme,
		languageCompartment.of(languageExt),
		updateListener,
	];
}

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			files: [] as TextFile[],
			currentFileIndex: -1,
			currentFile: {
				path: '',
				name: 'Untitled',
				mimeType: 'text/plain',
				content: '',
				originalContent: '',
				isModified: false,
				isLoading: false,
				encoding: 'UTF-8',
				downloadUrl: '',
				isVersionable: false,
				isCheckedOut: false,
				baseVersionName: '',
				uuid: '',
				hasWebTemplate: false,
				scriptable: false,
			} as CurrentFile,
			editor: null as EditorView | null,
			errorMessage: '',
			isSaving: false,
			cursorLine: 1,
			cursorColumn: 1,
			canUndo: false,
			canRedo: false,
			messageListener: null as ((e: MessageEvent) => void) | null,
			// Pane visibility
			sidebarPanelVisible: true,
			sidebarPanelWidth: 260,
			// Right pane: Inspector (wt-inspector). Layout (visibility/width)
			// is the host's responsibility; the component owns everything
			// inside. The API surface is built once (marked raw) in appLaunch.
			detailPanelVisible: false,
			detailPanelWidth: 280,
			detailPanelMinWidth: 200,
			detailPanelMaxWidth: 500,
			detailPanelResizing: false,
			detailResizeStartX: 0,
			detailResizeStartWidth: 0,
			inspectorApi: null as any,
			// Display options handed to <wt-inspector>. The file is already open
			// in the editor, so the Inspector's "Open File" action is redundant —
			// hide it. "Open Containing Folder" stays (wired to open a Browser).
			inspectorOptions: { showOpenItem: false } as Record<string, any>,
			// Mirrors whether the Inspector currently has an overlay open, so
			// the editor can suppress its own global keyboard shortcuts
			// (Ctrl+S / Ctrl+F) while the user edits inside the panel.
			inspectorOverlayOpen: false,
			// Companion preview window — open/ready state and last-used
			// extension hint per tab so re-opening reuses prior input.
			previewOpen: false,
			previewReady: false,
			previewPingTimer: null as number | null,
			previewExtensionByTab: {} as Record<string, string>,
			// Search/replace state (lives in the sidebar)
			searchTerm: '',
			replaceTerm: '',
			searchCaseSensitive: false,
			searchWholeWord: false,
			searchRegex: false,
			searchNotFound: false,
			// Resize state
			sidebarResizing: false,
			sidebarResizeStartX: 0,
			sidebarResizeStartWidth: 0,
			// Checkout dialog state
			checkoutDialog: {
				visible: false,
				resolve: null as null | ((result: { checkout: boolean; checkinAfterSave: boolean }) => void),
			},
			checkinAfterSave: false,
			checkinDialog: {
				visible: false,
				isLoading: false,
			},
			closeConfirmDialog: {
				visible: false,
				resolve: null as null | ((result: 'save' | 'discard' | 'cancel') => void),
			},
			saveAsDialog: {
				visible: false,
				fileName: '',
			},
			saveAsToken: '',
		};
	},
	computed: {
		isMarkdown(): boolean {
			const name = this.currentFile.name || '';
			const ext = name.split('.').pop()?.toLowerCase() || '';
			const mimeType = this.currentFile.mimeType || '';
			return ext === 'md' || ext === 'markdown' ||
				mimeType === 'text/markdown' ||
				mimeType === 'text/x-markdown';
		},
		isHtml(): boolean {
			const name = this.currentFile.name || '';
			const ext = name.split('.').pop()?.toLowerCase() || '';
			const mimeType = this.currentFile.mimeType || '';
			return ext === 'html' || ext === 'htm' ||
				mimeType === 'text/html' ||
				mimeType === 'application/xhtml+xml';
		},
		isTemplated(): boolean {
			return !!this.currentFile.hasWebTemplate;
		},
		// True when the server evaluates this file through a script engine
		// (e.g. .gsp). Such files must be previewed via server-side rendering,
		// not by displaying their raw source.
		isScriptable(): boolean {
			return !!this.currentFile.scriptable;
		},
		// Subtitle pushed to the shell for the Dock hover preview.
		// Recomputed when the active file (or its name) changes; the
		// watcher below forwards updates via instance.setDisplayInfo().
		dockSubtitle(): string {
			const f = (this as any).files[(this as any).currentFileIndex];
			return f?.name || '';
		},
		// Target handed to <wt-inspector>: the active tab's backing node.
		// Null for unsaved tabs (new / dropped local file) — the Inspector
		// shows its "select an item" empty state for those.
		inspectorTarget(): InspectorTarget | null {
			const f = (this as any).files[(this as any).currentFileIndex];
			return f?.inspectorItem ?? null;
		},
	},
	watch: {
		dockSubtitle(val: string) {
			(this as any).instance?.setDisplayInfo({ subtitle: val });
		},
	},
	methods: {
		async onMounted() {
			const vm = this;

			// Inject the wt-inspector <template> into <body> so the custom
			// element can resolve `template: '#wt-inspector'` once it is mounted
			// via the v-if guard. Must run before any state path can flip
			// detailPanelVisible to true.
			try {
				await loadInspectorTemplate();
			} catch (e) {
				console.warn('[TextEditor] Failed to load wt-inspector template:', e);
			}

			// Save As response channel
			saveAsChannelRef = new BroadcastChannel('webtop-save-as');
			saveAsChannelRef.onmessage = (event: MessageEvent) => {
				if (event.data?.type === 'save-as-complete' && event.data.saveAsToken && event.data.saveAsToken === vm.saveAsToken) {
					vm.currentFile.path = event.data.path;
					vm.currentFile.name = event.data.name;
					vm.currentFile.mimeType = event.data.mimeType;
					vm.currentFile.isModified = false;
					vm.currentFile.originalContent = vm.currentFile.content;
					if (vm.instance) {
						vm.instance.windowTitle = event.data.name;
					}
					vm.saveAsToken = '';
					if (vm.currentFileIndex >= 0 && vm.currentFileIndex < vm.files.length) {
						vm.files[vm.currentFileIndex].path = event.data.path;
						vm.files[vm.currentFileIndex].name = event.data.name;
						vm.files[vm.currentFileIndex].mimeType = event.data.mimeType;
						vm.files[vm.currentFileIndex].isModified = false;
						vm.files[vm.currentFileIndex].originalContent = vm.currentFile.content;
						// The tab now has a backing node — wire the Inspector to it
						// and pull the freshly created node's metadata.
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
			};
			window.addEventListener('message', vm.messageListener);

			document.addEventListener('keydown', (e: KeyboardEvent) => {
				// While an Inspector overlay is up (property/ACL editor, version
				// history) the user is editing inside the panel — let it own the
				// keyboard and skip the editor's global shortcuts.
				if (vm.inspectorOverlayOpen) return;
				if ((e.ctrlKey || e.metaKey) && e.key === 's') {
					e.preventDefault();
					if (vm.currentFile.isModified && !vm.isSaving) {
						vm.saveFile();
					}
				} else if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F') && !e.shiftKey && !e.altKey) {
					e.preventDefault();
					vm.openFindPanel();
				} else if ((e.ctrlKey || e.metaKey) && (e.key === 'i' || e.key === 'I')) {
					e.preventDefault();
					vm.toggleDetailPanel();
				}
			});

			window.appLaunch = async (instance: ApplicationInstance, options?: LaunchOptions) => {
				vm.instance = vm.$markRaw(instance);
				// Build the <wt-inspector> api surface once, marked raw so the
				// reactive system never Proxy-wraps it. content/eventHub/popup all
				// carry private fields (#client, etc.) that throw "Cannot read
				// private member" when invoked through a Proxy.
				vm.inspectorApi = vm.$markRaw({
					content: instance.api.content,
					eventHub: instance.api.eventHub,
					popup: instance.popup,
				});
				instance.appState = () => {
					const paths = vm.files.map((f: TextFile) => f.path).filter((p: string) => !!p);
					if (paths.length === 0) return {};
					return { paths, activeIndex: vm.currentFileIndex };
				};
				instance.setDisplayInfo({ subtitle: vm.dockSubtitle });

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				await vm.loadDetailPanelState();

				vm.initEditor();

				instance.setBeforeCloseCallback(async () => {
					const ok = await vm.confirmClose();
					if (ok && vm.previewOpen) {
						// Close the preview window now (before the editor
						// iframe is torn down) so the user doesn't see the
						// "Editor was closed" placeholder for several seconds.
						try {
							previewChannelRef?.postMessage({
								type: 'preview-close',
								previewKey: vm.getPreviewKey(),
							});
						} catch { /* ignore */ }
						vm.previewOpen = false;
						vm.previewReady = false;
					}
					return ok;
				});

				if (options?.paths && options.paths.length > 0) {
					for (const p of options.paths) {
						await vm.loadFile(p);
					}
					const idx = options.activeIndex != null ? Math.min(options.activeIndex, vm.files.length - 1) : vm.files.length - 1;
					vm.selectTab(idx);
				} else if (options?.path) {
					await vm.loadFile(options.path);
				} else {
					vm.newFile();
				}

				vm.$nextTick(() => {
					instance.notifyLaunched();
				});
			};
		},
		async onUnmount() {
			const vm = this;
			// Tell the preview window the editor is closing so it can show
			// the disconnected placeholder, then tear down the channel.
			if (vm.previewOpen) {
				try {
					previewChannelRef?.postMessage({
						type: 'preview-close',
						previewKey: vm.getPreviewKey(),
					});
				} catch { /* ignore */ }
			}
			vm.closePreviewChannel();
			unwatchActiveNode();
			if (vm.messageListener) {
				window.removeEventListener('message', vm.messageListener);
			}
			if (vm.editor) {
				vm.editor.destroy();
			}
			if (saveAsChannelRef) {
				saveAsChannelRef.close();
				saveAsChannelRef = null;
			}
			for (const f of vm.files) editorStates.delete(f.id);
		},
		// ---- Window controls ----
		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},
		// ---- Pane toggles ----
		toggleSidebarPanel() {
			this.sidebarPanelVisible = !this.sidebarPanelVisible;
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
				// The Inspector sits on the right, so dragging the handle left
				// (decreasing clientX) widens the panel.
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
				await db.setUserSetting(userID, 'text-editor', 'detailPanel', {
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
				const state = await db.getUserSetting(userID, 'text-editor', 'detailPanel');
				if (state) {
					vm.detailPanelVisible = state.visible ?? false;
					vm.detailPanelWidth = state.width ?? 280;
				}
			} catch { /* ignore */ }
		},
		// wt-inspector event handlers. "Open File" is hidden via inspectorOptions
		// (the file is already open here), and navigation has no in-editor list,
		// so only reveal + overlay-changed are wired.
		onInspectorOverlayChanged(open: boolean) {
			this.inspectorOverlayOpen = !!open;
		},
		// "Open Containing Folder": launch a fresh Content Browser at the file's
		// parent folder. (Content Browser navigates its own list instead; that
		// behavior lives in its own host handler.)
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
		// Re-point the active-tab node watch and refresh editor metadata when the
		// Inspector (or another client) changes the backing node.
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
		// Pull the latest node metadata for the given path into its tab. Keeps the
		// editor in sync with Inspector-driven changes (MIME type drives syntax
		// highlighting; encoding/version/lock drive the status bar and save flow).
		// Also rebuilds the Inspector target so the panel reloads.
		async refreshActiveFileMetadata(path: string) {
			const vm = this;
			const idx = vm.files.findIndex((f: TextFile) => f.path === path);
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
			const prevMime = file.mimeType;
			const hasWebTemplate = !!(node.properties || []).find(
				(p: any) => p && p.name === 'web.template'
			);
			file.mimeType = node.mimeType || 'text/plain';
			file.encoding = node.encoding || 'UTF-8';
			file.isVersionable = node.isVersionable || false;
			file.isCheckedOut = node.isCheckedOut || false;
			file.baseVersionName = node.baseVersionName || '';
			file.downloadUrl = node.downloadUrl || '';
			file.uuid = node.uuid || '';
			file.hasWebTemplate = hasWebTemplate;
			file.scriptable = node.scriptable || false;
			file.inspectorItem = nodeToInspectorTarget(node);
			if (idx === vm.currentFileIndex) {
				vm.currentFile.mimeType = file.mimeType;
				vm.currentFile.encoding = file.encoding;
				vm.currentFile.isVersionable = file.isVersionable;
				vm.currentFile.isCheckedOut = file.isCheckedOut;
				vm.currentFile.baseVersionName = file.baseVersionName;
				vm.currentFile.downloadUrl = file.downloadUrl;
				vm.currentFile.uuid = file.uuid;
				vm.currentFile.hasWebTemplate = file.hasWebTemplate;
				vm.currentFile.scriptable = file.scriptable;
				if (prevMime !== file.mimeType) {
					vm.applyLanguageForActiveFile();
				}
			}
		},
		// Reconfigure CodeMirror's language for the active file (used when the
		// MIME type changes via the Inspector). The language compartment is
		// module-scoped so it can be reconfigured on the live editor state.
		applyLanguageForActiveFile() {
			const vm = this;
			if (!vm.editor) return;
			const languageExt = getLanguageExtension(vm.currentFile.mimeType, vm.currentFile.name);
			vm.editor.dispatch({
				effects: languageCompartment.reconfigure(languageExt),
			});
		},
		openFindPanel() {
			const vm = this;
			if (vm.editor) {
				const sel = vm.editor.state.selection.main;
				if (!sel.empty) {
					const selected = vm.editor.state.sliceDoc(sel.from, sel.to);
					if (selected && !selected.includes('\n')) {
						vm.searchTerm = selected;
						vm.searchNotFound = false;
						vm.applySearchQuery();
					}
				}
			}
			vm.sidebarPanelVisible = true;
			vm.$nextTick(() => {
				const input = document.getElementById('find-input') as HTMLInputElement | null;
				if (input) {
					input.focus();
					input.select();
				}
			});
		},
		// Toggle the companion Preview window. The text-editor keeps at most
		// one preview window open (per editor instance) — re-clicking the
		// button closes the existing preview.
		togglePreviewPanel() {
			const vm = this;
			if (vm.previewOpen) {
				vm.closePreviewWindow();
			} else {
				vm.openPreviewWindow();
			}
		},
		// ---- Sidebar resize ----
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
		// ---- Editor lifecycle ----
		createUpdateListener() {
			const vm = this;
			let previewTimeout: number | null = null;
			return EditorView.updateListener.of((update) => {
				if (update.docChanged) {
					const content = update.state.doc.toString();
					vm.currentFile.content = content;
					vm.currentFile.isModified = content !== vm.currentFile.originalContent;
					if (vm.currentFileIndex >= 0 && vm.currentFileIndex < vm.files.length) {
						vm.files[vm.currentFileIndex].isModified = vm.currentFile.isModified;
					}
					// Debounce updates to the companion Preview window so
					// rapid typing does not flood the BroadcastChannel.
					if (previewTimeout) clearTimeout(previewTimeout);
					previewTimeout = window.setTimeout(() => {
						if (vm.previewOpen && vm.previewReady) vm.sendPreviewState();
						previewTimeout = null;
					}, 300);
				}
				const pos = update.state.selection.main.head;
				const line = update.state.doc.lineAt(pos);
				vm.cursorLine = line.number;
				vm.cursorColumn = pos - line.from + 1;
				vm.canUndo = true;
				vm.canRedo = true;
			});
		},
		initEditor() {
			const vm = this;
			const container = document.getElementById('editor-container');
			if (!container) return;
			const updateListener = vm.createUpdateListener();
			const state = EditorState.create({
				doc: '',
				extensions: buildEditorExtensions(updateListener, []),
			});
			vm.editor = vm.$markRaw(new EditorView({ state, parent: container }));
		},
		// Move keyboard focus into the editor. Called whenever a tab becomes
		// active (open, new, tab-switch, restore) so the user can start typing
		// without clicking. Uses $nextTick + a brief deferral because the
		// iframe is not always focusable on the very first paint after launch.
		focusEditor() {
			const vm = this;
			if (!vm.editor) return;
			const doFocus = () => {
				try {
					window.focus();
					vm.editor?.focus();
				} catch { /* ignore */ }
			};
			vm.$nextTick(() => {
				doFocus();
				setTimeout(doFocus, 0);
			});
		},
		generateFileID(): string {
			return 'file_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
		},
		saveCurrentFileState() {
			const vm = this;
			if (vm.currentFileIndex < 0 || vm.currentFileIndex >= vm.files.length) return;
			const file = vm.files[vm.currentFileIndex];
			file.path = vm.currentFile.path;
			file.name = vm.currentFile.name;
			file.mimeType = vm.currentFile.mimeType;
			file.originalContent = vm.currentFile.originalContent;
			file.isModified = vm.currentFile.isModified;
			file.isVersionable = vm.currentFile.isVersionable;
			file.isCheckedOut = vm.currentFile.isCheckedOut;
			file.baseVersionName = vm.currentFile.baseVersionName;
			file.encoding = vm.currentFile.encoding;
			file.downloadUrl = vm.currentFile.downloadUrl;
			file.uuid = vm.currentFile.uuid;
			file.hasWebTemplate = vm.currentFile.hasWebTemplate;
			file.scriptable = vm.currentFile.scriptable;
			if (vm.editor) {
				editorStates.set(file.id, vm.editor.state);
			}
		},
		restoreFileState(index: number) {
			const vm = this;
			const file = vm.files[index];
			if (!file) return;
			vm.currentFile.path = file.path;
			vm.currentFile.name = file.name;
			vm.currentFile.mimeType = file.mimeType;
			vm.currentFile.originalContent = file.originalContent;
			vm.currentFile.isModified = file.isModified;
			vm.currentFile.isVersionable = file.isVersionable;
			vm.currentFile.isCheckedOut = file.isCheckedOut;
			vm.currentFile.baseVersionName = file.baseVersionName;
			vm.currentFile.encoding = file.encoding;
			vm.currentFile.downloadUrl = file.downloadUrl;
			vm.currentFile.uuid = file.uuid;
			vm.currentFile.hasWebTemplate = file.hasWebTemplate;
			vm.currentFile.scriptable = file.scriptable;
			const savedState = editorStates.get(file.id);
			if (vm.editor && savedState) {
				vm.editor.setState(savedState);
			}
			if (vm.editor) {
				vm.currentFile.content = vm.editor.state.doc.toString();
			}
			vm.$nextTick(() => {
				if (vm.previewOpen && vm.previewReady) vm.sendPreviewState();
			});
			vm.focusEditor();
			// Point the Inspector node-watch at the now-active tab so changes
			// made through the panel flow back into the editor's own state.
			vm.watchActiveFileNode();
		},
		selectTab(index: number) {
			const vm = this;
			if (index === vm.currentFileIndex) return;
			if (index < 0 || index >= vm.files.length) return;
			vm.saveCurrentFileState();
			vm.currentFileIndex = index;
			vm.restoreFileState(index);
			if (vm.instance) {
				vm.instance.windowTitle = vm.files[index].name;
			}
		},
		async closeFile(index: number) {
			const vm = this;
			if (index < 0 || index >= vm.files.length) return;

			const file = vm.files[index];
			const originalIndex = vm.currentFileIndex;

			if (vm.currentFileIndex >= 0) {
				vm.saveCurrentFileState();
			}

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

			editorStates.delete(file.id);
			// If the preview window was pinned to this file, let it know so
			// it can drop the pin and follow the new active tab.
			if (previewPinnedPath && file.path && file.path === previewPinnedPath) {
				previewPinnedPath = '';
				if (vm.previewOpen) {
					try {
						previewChannelRef?.postMessage({
							type: 'preview-unpinned',
							previewKey: vm.getPreviewKey(),
						});
					} catch { /* ignore */ }
				}
			}
			vm.files.splice(index, 1);

			if (vm.files.length === 0) {
				vm.currentFileIndex = -1;
				vm.newFile();
				return;
			}

			let targetIndex: number;
			if (index === originalIndex) {
				targetIndex = Math.min(index, vm.files.length - 1);
			} else if (index < originalIndex) {
				targetIndex = originalIndex - 1;
			} else {
				targetIndex = originalIndex;
			}

			vm.currentFileIndex = targetIndex;
			vm.restoreFileState(targetIndex);
			if (vm.instance) {
				vm.instance.windowTitle = vm.files[targetIndex].name;
			}
		},
		newFile() {
			const vm = this;

			if (vm.currentFileIndex >= 0) {
				vm.saveCurrentFileState();
			}

			const newFile: TextFile = {
				id: vm.generateFileID(),
				path: '',
				name: 'Untitled',
				mimeType: 'text/plain',
				originalContent: '',
				isModified: false,
				isVersionable: false,
				isCheckedOut: false,
				baseVersionName: '',
				encoding: 'UTF-8',
				downloadUrl: '',
				uuid: '',
				hasWebTemplate: false,
				scriptable: false,
				inspectorItem: null,
			};

			if (vm.editor) {
				editorStates.set(newFile.id, EditorState.create({
					doc: '',
					extensions: buildEditorExtensions(vm.createUpdateListener(), []),
				}));
			}

			vm.files.push(newFile);
			vm.currentFileIndex = vm.files.length - 1;
			vm.restoreFileState(vm.currentFileIndex);

			if (vm.instance) {
				vm.instance.windowTitle = 'Untitled';
			}
		},
		// ---- Drag & drop ----
		// App root rejects drops by default so browser does not navigate to
		// a dropped file; individual panes opt in or out as needed.
		onAppDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		// Center pane: accept files from Content Browser or the OS, but reject
		// the Save As chip (so it cannot be dropped back onto this editor).
		onCenterPaneDragOver(event: DragEvent) {
			if (!event.dataTransfer) return;
			if (event.dataTransfer.types.includes('application/x-webtop-save')) {
				event.dataTransfer.dropEffect = 'none';
				return;
			}
			event.dataTransfer.dropEffect = 'copy';
		},
		async onCenterPaneDrop(event: DragEvent) {
			const types = event.dataTransfer?.types ?? [];
			// Reject the Save As chip so it cannot be dropped back onto this editor.
			if (types.includes('application/x-webtop-save')) {
				event.preventDefault();
				event.stopPropagation();
				return;
			}
			// Only intercept file-like drops; let CodeMirror handle its own text drag/drop.
			const isFileDrop = types.includes('Files')
				|| types.includes('application/x-webtop-file')
				|| types.includes('application/x-webtop-files');
			if (!isFileDrop) return;
			event.preventDefault();
			event.stopPropagation();
			await this.handleDropEvent(event);
		},
		// Side panes (sidebar / preview) and the Save As chip area reject all
		// drops with a forbidden cursor.
		onForbiddenDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		async handleDropEvent(event: DragEvent) {
			const vm = this;

			const webtopFileData = event.dataTransfer?.getData('application/x-webtop-file');
			if (webtopFileData) {
				try {
					const fileInfo = JSON.parse(webtopFileData);
					if (fileInfo.path) {
						await vm.loadFile(fileInfo.path);
						return;
					}
				} catch (e) {
					console.error('Failed to parse webtop file data:', e);
				}
			}

			const files = event.dataTransfer?.files;
			if (files && files.length > 0) {
				const file = files[0];
				try {
					const content = await file.text();
					const mimeType = file.type || 'text/plain';

					if (vm.currentFileIndex >= 0) {
						vm.saveCurrentFileState();
					}

					const languageExt = getLanguageExtension(mimeType, file.name);
					const editorState = EditorState.create({
						doc: content,
						extensions: buildEditorExtensions(vm.createUpdateListener(), languageExt),
					});

					const newFile: TextFile = {
						id: vm.generateFileID(),
						path: '',
						name: file.name,
						mimeType: mimeType,
						originalContent: content,
						isModified: false,
						isVersionable: false,
						isCheckedOut: false,
						baseVersionName: '',
						encoding: 'UTF-8',
						downloadUrl: '',
						uuid: '',
						hasWebTemplate: false,
						scriptable: false,
						inspectorItem: null,
					};

					editorStates.set(newFile.id, editorState);
					vm.files.push(newFile);
					vm.currentFileIndex = vm.files.length - 1;
					vm.restoreFileState(vm.currentFileIndex);

					if (vm.instance) {
						vm.instance.windowTitle = file.name;
					}
				} catch (e) {
					console.error('Failed to load local file:', file.name, e);
				}
			}
		},
		// ---- File loading ----
		async loadFile(path: string) {
			const vm = this;

			const existingIndex = vm.files.findIndex(f => f.path === path);
			if (existingIndex >= 0) {
				vm.selectTab(existingIndex);
				return;
			}

			vm.currentFile.isLoading = true;
			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				const node: Node | null = await contentService.getNode(path);
				if (!node) throw new Error(`File not found: ${path}`);

				if (vm.currentFileIndex >= 0) {
					vm.saveCurrentFileState();
				}

				let content = '';
				if (node.downloadUrl) {
					const response = await fetch(node.downloadUrl);
					if (!response.ok) throw new Error(`Failed to fetch: ${response.statusText}`);
					content = await response.text();
				}

				const mimeType = node.mimeType || 'text/plain';
				const languageExt = getLanguageExtension(mimeType, node.name);

				const editorState = EditorState.create({
					doc: content,
					extensions: buildEditorExtensions(vm.createUpdateListener(), languageExt),
				});

				const hasWebTemplate = !!(node.properties || []).find(
					(p: any) => p && p.name === 'web.template'
				);

				const newFile: TextFile = {
					id: vm.generateFileID(),
					path: node.path,
					name: node.name,
					mimeType: mimeType,
					originalContent: content,
					isModified: false,
					isVersionable: node.isVersionable || false,
					isCheckedOut: node.isCheckedOut || false,
					baseVersionName: node.baseVersionName || '',
					encoding: node.encoding || 'UTF-8',
					downloadUrl: node.downloadUrl || '',
					uuid: node.uuid || '',
					hasWebTemplate,
					scriptable: node.scriptable || false,
					inspectorItem: nodeToInspectorTarget(node),
				};

				editorStates.set(newFile.id, editorState);
				vm.files.push(newFile);
				vm.currentFileIndex = vm.files.length - 1;
				vm.restoreFileState(vm.currentFileIndex);

				if (vm.instance) {
					vm.instance.windowTitle = node.name;
				}
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || 'Failed to load file';
			} finally {
				vm.currentFile.isLoading = false;
			}
		},
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

				const content = vm.currentFile.content;
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
						uploadId,
						parentPath,
						fileName,
						vm.currentFile.mimeType,
						true
					);

					vm.currentFile.originalContent = content;
					vm.currentFile.isModified = false;
					if (vm.currentFileIndex >= 0 && vm.currentFileIndex < vm.files.length) {
						vm.files[vm.currentFileIndex].isModified = false;
						vm.files[vm.currentFileIndex].originalContent = vm.currentFile.originalContent;
					}

					if (vm.checkinAfterSave && vm.currentFile.isVersionable && vm.currentFile.isCheckedOut) {
						await contentService.checkin(vm.currentFile.path);
						vm.currentFile.isCheckedOut = false;
						vm.checkinAfterSave = false;
						const node = await contentService.getNode(vm.currentFile.path);
						if (node) {
							vm.currentFile.baseVersionName = node.baseVersionName || '';
						}
					} else if (vm.currentFile.isVersionable && vm.currentFile.isCheckedOut) {
						vm.showCheckinDialog();
					}
				} catch (error) {
					try {
						await contentService.abortMultipartUpload(uploadId);
					} catch { /* ignore */ }
					throw error;
				}
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || 'Failed to save file';
			} finally {
				vm.isSaving = false;
			}
		},
		// ---- Save As ----
		openSaveAsDialog() {
			this.saveAsDialog.fileName = this.currentFile.name || 'untitled.txt';
			this.saveAsDialog.visible = true;
		},
		closeSaveAsDialog() {
			this.saveAsDialog.visible = false;
		},
		onSaveAsDragStart(event: DragEvent) {
			if (!event.dataTransfer) return;
			const content = this.currentFile.content;
			const fileName = this.saveAsDialog.fileName || 'untitled.txt';
			this.saveAsToken = Date.now().toString(36) + Math.random().toString(36).slice(2);
			event.dataTransfer.effectAllowed = 'copy';
			event.dataTransfer.setData('application/x-webtop-save', JSON.stringify({
				name: fileName,
				mimeType: this.currentFile.mimeType || 'text/plain',
				content: content,
				saveAsToken: this.saveAsToken,
			}));
			event.dataTransfer.setData('text/plain', fileName);
			setTimeout(() => { this.saveAsDialog.visible = false; }, 100);
		},
		// ---- Undo / Redo ----
		undo() {
			if (this.editor) undo(this.editor);
		},
		redo() {
			if (this.editor) redo(this.editor);
		},
		// ---- Search / Replace (sidebar form drives CodeMirror) ----
		applySearchQuery() {
			const vm = this;
			if (!vm.editor) return;
			const query = new SearchQuery({
				search: vm.searchTerm,
				caseSensitive: vm.searchCaseSensitive,
				regexp: vm.searchRegex,
				wholeWord: vm.searchWholeWord,
				replace: vm.replaceTerm,
			});
			vm.editor.dispatch({ effects: setSearchQuery.of(query) });
		},
		onSearchTermInput() {
			this.searchNotFound = false;
			this.applySearchQuery();
		},
		clearSearchTerm() {
			this.searchTerm = '';
			this.searchNotFound = false;
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
			const vm = this;
			if (!vm.editor || !vm.searchTerm) return;
			vm.applySearchQuery();
			vm.searchNotFound = !findNext(vm.editor);
		},
		findPrev() {
			const vm = this;
			if (!vm.editor || !vm.searchTerm) return;
			vm.applySearchQuery();
			vm.searchNotFound = !findPrevious(vm.editor);
		},
		replaceCurrent() {
			const vm = this;
			if (!vm.editor || !vm.searchTerm) return;
			vm.applySearchQuery();
			// replaceNext() leaves the selection on the inserted text instead of advancing.
			// Detect whether a replacement actually happened (doc changed) and, if so, move
			// to the next match so the following click replaces it directly.
			const before = vm.editor.state.doc;
			if (!replaceNext(vm.editor)) {
				vm.searchNotFound = true;
				return;
			}
			vm.searchNotFound = false;
			if (vm.editor.state.doc !== before) {
				findNext(vm.editor);
			}
		},
		replaceAllInDoc() {
			const vm = this;
			if (!vm.editor || !vm.searchTerm) return;
			vm.applySearchQuery();
			vm.searchNotFound = !replaceAll(vm.editor);
		},
		onSearchTermKeydown(event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				if (event.shiftKey) this.findPrev(); else this.findNextMatch();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				if (this.editor) this.editor.focus();
			}
		},
		onReplaceTermKeydown(event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				if (event.ctrlKey || event.metaKey) this.replaceAllInDoc(); else this.replaceCurrent();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				if (this.editor) this.editor.focus();
			}
		},
		// ---- Companion Preview window ----
		// Rendering itself lives in the text-editor-preview app. The editor
		// pushes content via BroadcastChannel; the preview window renders
		// markdown / plain HTML / templated HTML on its own.
		openPreviewChannel() {
			const vm = this;
			if (previewChannelRef) return;
			const ch = new BroadcastChannel(PREVIEW_CHANNEL);
			ch.onmessage = (event: MessageEvent) => {
				const msg = event.data || {};
				if (!msg.previewKey || msg.previewKey !== vm.getPreviewKey()) return;
				if (msg.type === 'preview-ready') {
					vm.previewReady = true;
					vm.sendPreviewState();
				} else if (msg.type === 'preview-closed') {
					vm.previewOpen = false;
					vm.previewReady = false;
					previewPinnedPath = '';
				} else if (msg.type === 'preview-pin') {
					previewPinnedPath = msg.filePath || '';
				} else if (msg.type === 'preview-unpin') {
					previewPinnedPath = '';
				} else if (msg.type === 'preview-set-extension') {
					// User chose a templated output extension in the preview
					// window. Persist it on the owning tab so it survives tab
					// switches and seeds future preview-state messages.
					const ext = (msg.extension || '').trim();
					const fp = msg.filePath || '';
					if (ext && fp) {
						const f = vm.files.find((x: any) => x.path === fp);
						if (f?.id) vm.previewExtensionByTab[f.id] = ext;
					}
				}
			};
			previewChannelRef = ch;
		},
		closePreviewChannel() {
			const vm = this;
			if (vm.previewPingTimer != null) {
				clearInterval(vm.previewPingTimer);
				vm.previewPingTimer = null;
			}
			if (previewChannelRef) {
				try { previewChannelRef.close(); } catch { /* ignore */ }
				previewChannelRef = null;
			}
		},
		getPreviewKey(): string {
			// Use the editor's ApplicationInstance id so multiple text-editor
			// windows each have their own preview without crosstalk.
			return (this as any).instance?.id || '';
		},
		// Place the preview window just to the right of this editor. If the
		// editor sits near the right edge and there's no room, slide the
		// preview left until it fits; this overlaps the editor but keeps the
		// preview fully on-screen. Returns null when we can't read the
		// editor's bounds (e.g. it's maximized — fall back to the cascade).
		computePreviewBounds(): { x: number; y: number; width: number; height: number } | null {
			const vm = this;
			const ed = (vm.instance as any)?._sessionState
				|| (vm.instance as any)?._initialWindowState;
			if (!ed
				|| typeof ed.x !== 'number' || typeof ed.y !== 'number'
				|| typeof ed.width !== 'number' || typeof ed.height !== 'number') {
				return null;
			}
			if (ed.maximized) return null;
			const parent = window.parent || window;
			const viewportW = (parent as Window).innerWidth || window.innerWidth;
			const viewportH = (parent as Window).innerHeight || window.innerHeight;
			// Preview minimums declared in text-editor-preview/app.yml.
			const previewMinW = 480;
			const previewMinH = 320;
			let pw = Math.max(previewMinW, Math.min(ed.width, 800));
			let ph = ed.height;
			pw = Math.max(previewMinW, Math.min(pw, viewportW));
			ph = Math.max(previewMinH, Math.min(ph, viewportH));
			let px = ed.x + ed.width;
			let py = ed.y;
			if (px + pw > viewportW) px = Math.max(0, viewportW - pw);
			if (py + ph > viewportH) py = Math.max(0, viewportH - ph);
			return { x: px, y: py, width: pw, height: ph };
		},
		openPreviewWindow() {
			const vm = this;
			if (vm.previewOpen) return;
			const key = vm.getPreviewKey();
			if (!key) return;
			vm.openPreviewChannel();
			vm.previewOpen = true;
			vm.previewReady = false;
			// Ping every 2s so the preview can detect editor closures.
			if (vm.previewPingTimer == null) {
				vm.previewPingTimer = window.setInterval(() => {
					if (vm.previewOpen) {
						previewChannelRef?.postMessage({ type: 'preview-ping', previewKey: key });
					}
				}, PREVIEW_PING_INTERVAL_MS) as unknown as number;
			}
			const launchOptions: Record<string, any> = { previewKey: key };
			const bounds = vm.computePreviewBounds();
			if (bounds) launchOptions.initialWindowState = bounds;
			window.parent?.postMessage({
				type: 'open-app',
				appId: PREVIEW_APP_ID,
				options: launchOptions,
			}, window.location.origin);
			// If the preview never reports ready (app missing, blocked,
			// crashed), unstick the toggle so the user can retry.
			window.setTimeout(() => {
				if (vm.previewOpen && !vm.previewReady) {
					vm.previewOpen = false;
					if (vm.previewPingTimer != null) {
						clearInterval(vm.previewPingTimer);
						vm.previewPingTimer = null;
					}
				}
			}, 10000);
		},
		closePreviewWindow() {
			const vm = this;
			if (!vm.previewOpen) return;
			const key = vm.getPreviewKey();
			previewChannelRef?.postMessage({ type: 'preview-close', previewKey: key });
			vm.previewOpen = false;
			vm.previewReady = false;
		},
		sendPreviewState() {
			const vm = this;
			if (!vm.previewOpen || !previewChannelRef) return;
			const key = vm.getPreviewKey();
			const file = vm.files[vm.currentFileIndex];
			const tabId = file?.id || '';
			const previewExtension = vm.previewExtensionByTab[tabId]
				|| guessExtensionFromMime(vm.currentFile.mimeType);
			previewChannelRef.postMessage({
				type: 'preview-state',
				previewKey: key,
				fileName: vm.currentFile.name,
				mimeType: vm.currentFile.mimeType,
				content: vm.currentFile.content,
				filePath: vm.currentFile.path,
				workspace: vm.instance?.api?.workspace || '',
				isMarkdown: vm.isMarkdown,
				isHtml: vm.isHtml,
				isTemplated: vm.isTemplated,
				isScriptable: vm.isScriptable,
				previewExtension,
			});
		},
		dismissError() {
			this.errorMessage = '';
		},
		// ---- Checkout / Checkin dialogs ----
		showCheckoutDialog(): Promise<{ checkout: boolean; checkinAfterSave: boolean }> {
			const vm = this;
			vm.checkoutDialog.visible = true;
			return new Promise((resolve) => {
				vm.checkoutDialog.resolve = resolve;
			});
		},
		onCheckoutDialogAction(action: 'checkout' | 'checkoutAndCheckin' | 'cancel') {
			const vm = this;
			if (vm.checkoutDialog.resolve) {
				if (action === 'checkout') {
					vm.checkoutDialog.resolve({ checkout: true, checkinAfterSave: false });
				} else if (action === 'checkoutAndCheckin') {
					vm.checkoutDialog.resolve({ checkout: true, checkinAfterSave: true });
				} else {
					vm.checkoutDialog.resolve({ checkout: false, checkinAfterSave: false });
				}
			}
			vm.checkoutDialog.visible = false;
			vm.checkoutDialog.resolve = null;
		},
		showCheckinDialog() {
			this.checkinDialog.visible = true;
			this.checkinDialog.isLoading = false;
		},
		closeCheckinDialog() {
			this.checkinDialog.visible = false;
			this.checkinDialog.isLoading = false;
		},
		async submitCheckin() {
			const vm = this;
			vm.checkinDialog.isLoading = true;
			try {
				const contentService = vm.instance.api.content;
				await contentService.checkin(vm.currentFile.path);
				vm.currentFile.isCheckedOut = false;
				const node = await contentService.getNode(vm.currentFile.path);
				if (node) {
					vm.currentFile.baseVersionName = node.baseVersionName || '';
				}
				vm.closeCheckinDialog();
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || 'Failed to checkin';
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
				if (node) {
					vm.currentFile.baseVersionName = node.baseVersionName || '';
				}
				vm.closeCheckinDialog();
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || 'Failed to create checkpoint';
				vm.closeCheckinDialog();
			}
		},
		// ---- Close confirmation ----
		async confirmClose(): Promise<boolean> {
			const vm = this;
			if (vm.currentFileIndex >= 0) {
				vm.saveCurrentFileState();
			}
			const hasModified = vm.files.some(f => f.isModified);
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
			return new Promise((resolve) => {
				vm.closeConfirmDialog.resolve = resolve;
			});
		},
		onCloseConfirmDialogAction(action: 'save' | 'discard' | 'cancel') {
			const vm = this;
			if (vm.closeConfirmDialog.resolve) {
				vm.closeConfirmDialog.resolve(action);
			}
			vm.closeConfirmDialog.visible = false;
			vm.closeConfirmDialog.resolve = null;
		},
	},
};

import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');

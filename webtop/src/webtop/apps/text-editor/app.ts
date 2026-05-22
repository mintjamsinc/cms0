import { ApplicationInstance } from "../../services/webtop-service.js";
import type { Node } from "../../graphql/types.js";

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

// Markdown parser
import { marked } from "marked";

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
			} as CurrentFile,
			editor: null as EditorView | null,
			errorMessage: '',
			isSaving: false,
			cursorLine: 1,
			cursorColumn: 1,
			canUndo: false,
			canRedo: false,
			previewHtml: '',
			previewSrcDoc: '',
			previewIframeUrl: '',
			previewExtension: 'html',
			previewLoading: false,
			previewError: '',
			messageListener: null as ((e: MessageEvent) => void) | null,
			// Pane visibility
			sidebarPanelVisible: true,
			previewPanelVisible: false,
			sidebarPanelWidth: 260,
			previewPanelWidth: 320,
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
			previewResizing: false,
			previewResizeStartX: 0,
			previewResizeStartWidth: 0,
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
			saveAsChannel: null as BroadcastChannel | null,
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
		// Subtitle pushed to the shell for the Dock hover preview.
		// Recomputed when the active file (or its name) changes; the
		// watcher below forwards updates via instance.setDisplayInfo().
		dockSubtitle(): string {
			const f = (this as any).files[(this as any).currentFileIndex];
			return f?.name || '';
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

			// Save As response channel
			vm.saveAsChannel = new BroadcastChannel('webtop-save-as');
			vm.saveAsChannel.onmessage = (event: MessageEvent) => {
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
				if ((e.ctrlKey || e.metaKey) && e.key === 's') {
					e.preventDefault();
					if (vm.currentFile.isModified && !vm.isSaving) {
						vm.saveFile();
					}
				} else if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F') && !e.shiftKey && !e.altKey) {
					e.preventDefault();
					vm.openFindPanel();
				}
			});

			window.appLaunch = async (instance: ApplicationInstance, options?: LaunchOptions) => {
				vm.instance = vm.$markRaw(instance);
				instance.appState = () => {
					const paths = vm.files.map((f: TextFile) => f.path).filter((p: string) => !!p);
					if (paths.length === 0) return {};
					return { paths, activeIndex: vm.currentFileIndex };
				};
				instance.setDisplayInfo({ subtitle: vm.dockSubtitle });

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				vm.initEditor();

				instance.setBeforeCloseCallback(async () => {
					return await vm.confirmClose();
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
			if (vm.messageListener) {
				window.removeEventListener('message', vm.messageListener);
			}
			if (vm.editor) {
				vm.editor.destroy();
			}
			if (vm.saveAsChannel) {
				vm.saveAsChannel.close();
				vm.saveAsChannel = null;
			}
			if (vm.previewIframeUrl) {
				try { URL.revokeObjectURL(vm.previewIframeUrl); } catch { /* ignore */ }
				vm.previewIframeUrl = '';
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
		togglePreviewPanel() {
			this.previewPanelVisible = !this.previewPanelVisible;
			if (this.previewPanelVisible) {
				if (!this.previewExtension) {
					this.previewExtension = guessExtensionFromMime(this.currentFile.mimeType);
				}
				this.updatePreview();
				if (this.isTemplated && !this.previewIframeUrl) {
					this.refreshTemplatedPreview();
				}
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
		onPreviewResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			vm.previewResizing = true;
			vm.previewResizeStartX = event.clientX;
			vm.previewResizeStartWidth = vm.previewPanelWidth;
			const onMove = (e: MouseEvent) => {
				if (!vm.previewResizing) return;
				const delta = vm.previewResizeStartX - e.clientX;
				vm.previewPanelWidth = Math.max(220, Math.min(800, vm.previewResizeStartWidth + delta));
			};
			const onUp = () => {
				vm.previewResizing = false;
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
					if (previewTimeout) clearTimeout(previewTimeout);
					previewTimeout = window.setTimeout(() => {
						if (vm.previewPanelVisible) vm.updatePreview();
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
			vm.previewExtension = guessExtensionFromMime(file.mimeType);
			vm.previewError = '';
			vm.previewSrcDoc = '';
			vm.previewIframeUrl = '';
			const savedState = editorStates.get(file.id);
			if (vm.editor && savedState) {
				vm.editor.setState(savedState);
			}
			if (vm.editor) {
				vm.currentFile.content = vm.editor.state.doc.toString();
			}
			vm.$nextTick(() => {
				if (vm.previewPanelVisible) vm.updatePreview();
			});
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
			vm.searchNotFound = !replaceNext(vm.editor);
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
		// ---- Preview ----
		// Real-time for Markdown / plain HTML (rendered client-side from the
		// editor buffer — no save, no server call). Templated HTML preview is
		// driven by the manual "Refresh" button in the preview header instead.
		updatePreview() {
			const vm = this;
			vm.previewError = '';
			if (vm.isMarkdown) {
				try {
					marked.setOptions({ breaks: true, gfm: true });
					vm.previewHtml = marked.parse(vm.currentFile.content) as string;
				} catch {
					vm.previewHtml = '<p class="text-danger">Error rendering preview</p>';
				}
				return;
			}
			vm.previewHtml = '';
			if (vm.isHtml && !vm.isTemplated) {
				vm.previewSrcDoc = vm.currentFile.content || '';
				return;
			}
			// Templated preview is refreshed via refreshTemplatedPreview().
		},
		refreshTemplatedPreview() {
			const vm = this;
			if (!vm.isTemplated) return;
			if (!vm.currentFile.path) {
				vm.previewError = 'File must be saved before templated preview is available.';
				return;
			}
			let ext = (vm.previewExtension || 'html').trim();
			if (!ext) {
				vm.previewError = 'Enter a file extension (e.g. html).';
				return;
			}
			if (!ext.startsWith('.')) ext = '.' + ext;

			const workspace = vm.instance?.api?.workspace || '';
			if (!workspace) {
				vm.previewError = 'Workspace is not available.';
				return;
			}

			vm.previewLoading = true;
			vm.previewError = '';
			vm.previewSrcDoc = '';

			// Browsers can only load <iframe src="..."> via GET, so we POST the
			// draft buffer ourselves, then surface the response in the iframe
			// through a blob URL. The CMS preview endpoint accepts the unsaved
			// content and renders the template without touching version
			// history.
			const url = `/bin/cms.cgi/${workspace}${vm.currentFile.path}${ext}`;
			const body = JSON.stringify({
				content: vm.currentFile.content || '',
			});

			fetch(url, {
				method: 'POST',
				credentials: 'same-origin',
				headers: { 'Content-Type': 'application/vnd.cms.preview+json; charset=UTF-8' },
				body,
			}).then(async (resp) => {
				if (!resp.ok) {
					const text = await resp.text().catch(() => '');
					throw new Error(text || `Render failed: ${resp.status} ${resp.statusText}`);
				}
				const contentType = resp.headers.get('content-type') || '';
				const isHtml = /\b(text\/html|application\/xhtml\+xml)\b/i.test(contentType);
				let blob: Blob;
				if (isHtml) {
					// The blob: URL has no relationship to the original page
					// URL, so relative links (./img.png, css, scripts) would
					// not resolve. Inject a <base> pointing at the preview URL
					// so the rendered document behaves as if served there.
					// The href must be an absolute URL: path-relative hrefs
					// resolve against the blob: document's fallback base URL,
					// which browsers treat as opaque, so the base ends up
					// ignored and relative links fall back to the iframe
					// origin. Also strip any <base> already in the source so
					// our injected one is unambiguously the document base
					// (e.g. templates that hard-code a production base href).
					const html = await resp.text();
					const absoluteBase = new URL(url, window.location.href).href;
					const baseTag = `<base href="${absoluteBase.replace(/"/g, '&quot;')}">`;
					const stripped = html.replace(/<base\b[^>]*\/?>/gi, '');
					let patched: string;
					if (/<head\b[^>]*>/i.test(stripped)) {
						patched = stripped.replace(/<head\b[^>]*>/i, (m) => m + baseTag);
					} else if (/<html\b[^>]*>/i.test(stripped)) {
						patched = stripped.replace(/<html\b[^>]*>/i, (m) => `${m}<head>${baseTag}</head>`);
					} else {
						patched = `<head>${baseTag}</head>${stripped}`;
					}
					blob = new Blob([patched], { type: contentType || 'text/html; charset=UTF-8' });
				} else {
					blob = await resp.blob();
				}
				if (vm.previewIframeUrl) {
					try { URL.revokeObjectURL(vm.previewIframeUrl); } catch { /* ignore */ }
				}
				vm.previewIframeUrl = URL.createObjectURL(blob);
			}).catch((e: any) => {
				vm.previewError = e?.message || String(e) || 'Failed to render preview.';
			}).finally(() => {
				vm.previewLoading = false;
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

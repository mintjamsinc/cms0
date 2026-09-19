import { initUi } from "../../ui/index.js";
import { ApplicationInstance } from "../../services/webtop-service.js";
import type { Node } from "../../graphql/types.js";
import { BUILD_VERSION } from "../../utils/build-version.js";
import { Bytes } from "../../utils/bytes.js";
import { nodeToInspectorTarget, type InspectorTarget } from "../../lib/inspector-target.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";

// Side-effect import: registers the <wt-inspector> custom element so the
// right-pane Inspector can be embedded for the active tab's file.
import "../../components/wt-inspector.js";

// pdf.js. The core library MUST be imported before the viewer components:
// pdf_viewer.mjs reads the core API from globalThis.pdfjsLib, which pdf.mjs
// sets when it is evaluated.
import {
	AnnotationEditorType,
	AnnotationMode,
	getDocument,
	GlobalWorkerOptions,
	PasswordResponses,
	PDFWorker,
} from "pdfjs-dist";
import type { PDFDocumentLoadingTask, PDFDocumentProxy } from "pdfjs-dist";
import {
	EventBus,
	FindState,
	LinkTarget,
	PDFFindController,
	PDFLinkService,
	PDFViewer,
} from "pdfjs-dist/web/pdf_viewer.mjs";

// Runtime files copied next to the app by rollup.config.js (pdfViewerConfig).
// Resolved to absolute URLs because pdf.js fetches some of them from inside
// its worker, where a relative URL would resolve against the worker script.
const PDFJS_BASE = new URL('./vendor/pdfjs/', window.location.href).href;
GlobalWorkerOptions.workerSrc = `${PDFJS_BASE}pdf.worker.min.js?v=${BUILD_VERSION}`;

const DOCUMENT_OPTIONS = {
	// CMaps are required to render CJK text in PDFs that do not embed them.
	cMapUrl: `${PDFJS_BASE}cmaps/`,
	cMapPacked: true,
	standardFontDataUrl: `${PDFJS_BASE}standard_fonts/`,
	wasmUrl: `${PDFJS_BASE}wasm/`,
	iccUrl: `${PDFJS_BASE}iccs/`,
	// View-only: XFA forms are not rendered as interactive HTML.
	enableXfa: false,
};

// Scale presets understood by PDFViewer.currentScaleValue. A tab whose zoom is
// a preset is re-fitted whenever the viewer area changes size.
const SCALE_PRESETS = ['auto', 'page-fit', 'page-width', 'page-actual'];

// Content Browser app id (see apps/content-browser/app.yml). The Inspector's
// "Open Containing Folder" action launches a fresh Content Browser at the
// file's parent folder, since the viewer has no folder list of its own.
const CONTENT_BROWSER_APP_ID = '2468cf47-1a30-4053-b80a-9c5486954b08';

interface OutlineNode {
	id: string;
	label: string;
	isLink: boolean;
	children: OutlineNode[];
}

interface PdfFile {
	id: string;
	// Repository path; '' for a local file dropped from the OS.
	path: string;
	name: string;
	mimeType: string;
	size: number;
	downloadUrl: string;
	// Raw jcr:lastModified of the backing node as last seen by this tab (ISO
	// string, '' when unknown). Compared against incoming node events to
	// detect out-of-band changes to the stored content.
	modified: string;
	// Object handed to <wt-inspector> as its target. Null for local files,
	// which have no backing node.
	inspectorItem: InspectorTarget | null;
	isLoading: boolean;
	// 0..1 while the size is known, -1 otherwise.
	loadProgress: number;
	error: string;
	pageNumber: number;
	pagesCount: number;
	// Effective scale (1 = 100%) and the value to restore: a preset from
	// SCALE_PRESETS or a numeric string.
	scale: number;
	scaleValue: string;
	rotation: number;
	outline: OutlineNode[];
	outlineExpanded: string[];
	// Query the find results below belong to ('' = no search in this tab).
	findQuery: string;
	findState: number;
	findCurrent: number;
	findTotal: number;
}

// pdf.js objects for one tab. Kept outside reactive data (like the text
// editor's EditorStates): ichigo.js wraps stored objects in deep Proxies,
// which breaks pdf.js private fields and cannot be structured-cloned to the
// worker. Indexed by PdfFile.id.
interface ViewerRuntime {
	container: HTMLDivElement;
	eventBus: EventBus;
	linkService: PDFLinkService;
	viewer: PDFViewer;
	abort: AbortController;
	loadingTask: PDFDocumentLoadingTask | null;
	document: PDFDocumentProxy | null;
	// Original bytes of a local file, for download / open in browser.
	localBlob: Blob | null;
	// Outline node id -> destination or external URL.
	outlineTargets: Map<string, { dest: any; url: string }>;
	// Set when a new document's pages are initialized; the scale, rotation
	// and page are applied once the tab is visible (a hidden container has no
	// size to fit against).
	needsLayout: boolean;
	pendingPage: number;
}

const runtimes = new Map<string, ViewerRuntime>();

// One worker serves every tab; pdf.js would otherwise start one per document.
let sharedWorker: PDFWorker | null = null;
function getSharedWorker(): PDFWorker {
	if (!sharedWorker || sharedWorker.destroyed) {
		sharedWorker = new PDFWorker();
	}
	return sharedWorker;
}

// SSE subscription for the active tab's node (see text-editor for why this
// lives in module scope).
let activeNodeWatchUnsubscribe: (() => void) | null = null;

function unwatchActiveNode(): void {
	if (activeNodeWatchUnsubscribe) {
		try { activeNodeWatchUnsubscribe(); } catch { /* ignore */ }
		activeNodeWatchUnsubscribe = null;
	}
}

// Re-fits preset-scaled tabs when the viewer area is resized (window resize,
// sidebar / inspector toggle, splitter drag).
let viewerResizeObserver: ResizeObserver | null = null;
let keydownListener: ((e: KeyboardEvent) => void) | null = null;

interface LaunchOptions {
	path?: string;
	mimeType?: string;
	paths?: string[];
	activeIndex?: number;
	pageNumbers?: number[];
}

function isPdf(mimeType: string | undefined, name: string): boolean {
	return (mimeType || '').toLowerCase() === 'application/pdf' || /\.pdf$/i.test(name || '');
}

function withAttachmentParam(url: string): string {
	return url + (url.includes('?') ? '&' : '?') + 'attachment';
}

// Fetch and inject the wt-inspector <template> into <body> so the custom
// element can resolve `template: '#wt-inspector'` once it is mounted via the
// v-if guard. Mirrors text-editor's helper of the same name.
async function loadInspectorTemplate(): Promise<void> {
	const res = await fetch(`../../components/wt-inspector.html?v=${BUILD_VERSION}`);
	const html = await res.text();
	const doc = new DOMParser().parseFromString(html, 'text/html');
	for (const tmpl of Array.from(doc.querySelectorAll('template'))) {
		document.body.appendChild(tmpl);
	}
}

export const App = {
	data() {
		return {
			// Readiness gate for the whole screen (see the <template v-if> in
			// index.html). Flipped by appLaunch() once the component templates
			// are present.
			isReady: false,
			instance: null as ApplicationInstance | null,
			files: [] as PdfFile[],
			currentFileIndex: -1,
			errorMessage: '',
			messageListener: null as ((e: MessageEvent) => void) | null,
			// Reactive Localization snapshot. See composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
			// Text of the page-number box; follows the active tab's page except
			// while the user is typing into it.
			pageInput: '',
			pageInputFocused: false,
			// Left pane: find + outline.
			sidebarPanelVisible: false,
			sidebarPanelWidth: 260,
			sidebarResizing: false,
			findQuery: '',
			findCaseSensitive: false,
			findWholeWord: false,
			// Right pane: Inspector (wt-inspector). See text-editor.
			detailPanelVisible: false,
			detailPanelWidth: 280,
			detailPanelMinWidth: 200,
			detailPanelMaxWidth: 500,
			inspectorApi: null as any,
			inspectorOverlayOpen: false,
			// Password prompt for encrypted PDFs. Requests from several tabs are
			// queued so only one dialog is shown at a time.
			passwordDialog: {
				visible: false,
				fileName: '',
				incorrect: false,
				value: '',
				resolve: null as null | ((password: string | null) => void),
			},
		};
	},
	computed: {
		currentFile(): PdfFile | null {
			return (this as any).files[(this as any).currentFileIndex] ?? null;
		},
		fileTabItems(): { key: string; label: string; title: string }[] {
			return ((this as any).files as PdfFile[]).map((f: PdfFile) => ({
				key: f.id, label: f.name, title: f.path || f.name,
			}));
		},
		hasDocument(): boolean {
			const f = (this as any).currentFile as PdfFile | null;
			return !!f && f.pagesCount > 0;
		},
		zoomPercent(): string {
			const f = (this as any).currentFile as PdfFile | null;
			return f && f.scale > 0 ? `${Math.round(f.scale * 100)}%` : '';
		},
		sizeLabel(): string {
			const f = (this as any).currentFile as PdfFile | null;
			if (!f || !f.size) return '';
			return Bytes.format(f.size, { short: true, locale: (this as any).localization.locale || undefined });
		},
		loadingLabel(): string {
			const f = (this as any).currentFile as PdfFile | null;
			if (!f || !f.isLoading) return '';
			const percent = f.loadProgress >= 0 ? Math.round(f.loadProgress * 100) : 0;
			return (this as any).t('app.pdf-viewer.statusbar.loading', { percent }, `Loading... ${percent}%`);
		},
		// Find results for the active tab, shown only while they belong to
		// the query currently in the search box.
		findStatus(): string {
			const vm = this as any;
			const f = vm.currentFile as PdfFile | null;
			if (!f || !vm.findQuery || f.findQuery !== vm.findQuery) return '';
			if (f.findState === FindState.PENDING) {
				return vm.t('app.pdf-viewer.find.searching', undefined, 'Searching...');
			}
			if (f.findTotal > 0) {
				return vm.t('app.pdf-viewer.find.matches', { current: f.findCurrent, total: f.findTotal },
					`${f.findCurrent} of ${f.findTotal}`);
			}
			if (f.findState === FindState.NOT_FOUND) {
				return vm.t('app.pdf-viewer.find.noMatches', undefined, 'No matches');
			}
			return '';
		},
		findNotFound(): boolean {
			const vm = this as any;
			const f = vm.currentFile as PdfFile | null;
			return !!f && !!vm.findQuery && f.findQuery === vm.findQuery
				&& f.findState === FindState.NOT_FOUND && f.findTotal === 0;
		},
		// Subtitle pushed to the shell for the Dock hover preview.
		dockSubtitle(): string {
			const f = (this as any).currentFile as PdfFile | null;
			return f?.name || '';
		},
		inspectorTarget(): InspectorTarget | null {
			const f = (this as any).currentFile as PdfFile | null;
			return f?.inspectorItem ?? null;
		},
		// The file is already open here, so the Inspector's "Open File"
		// action is redundant. A viewer never holds unsaved changes.
		inspectorOptions(): Record<string, any> {
			return { showOpenItem: false, hasUnsavedChanges: false };
		},
	},
	watch: {
		dockSubtitle(val: string) {
			(this as any).instance?.setDisplayInfo({ subtitle: val });
		},
	},
	methods: {
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		// Synchronous on purpose: window.appLaunch must be defined by the time
		// this returns (see text-editor).
		onMounted() {
			const vm = this;

			vm.messageListener = async (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
					return;
				}
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) {
					return;
				}
			};
			window.addEventListener('message', vm.messageListener);

			keydownListener = (e: KeyboardEvent) => vm.onKeyDown(e);
			document.addEventListener('keydown', keydownListener);

			window.appLaunch = async (instance: ApplicationInstance, options?: LaunchOptions) => {
				vm.instance = vm.$markRaw(instance);
				vm.inspectorApi = vm.$markRaw({
					content: instance.api.content,
					eventHub: instance.api.eventHub,
					popup: instance.popup,
				});
				// Session restore: repository files only (a dropped local file
				// cannot be reopened), with the page each was on.
				instance.appState = () => {
					const saved = (vm.files as PdfFile[]).filter((f) => !!f.path);
					if (saved.length === 0) return {};
					const active = vm.currentFile as PdfFile | null;
					const activeIndex = Math.max(0, saved.findIndex((f) => f.id === active?.id));
					return {
						paths: saved.map((f) => f.path),
						pageNumbers: saved.map((f) => f.pageNumber || 1),
						activeIndex,
					};
				};
				instance.setDisplayInfo({ subtitle: vm.dockSubtitle });

				document.documentElement.dataset.theme = vm.instance.api.theme.currentTheme || 'light';
				refreshLocalization(vm.localization, vm.instance);

				// --- Readiness gate --- (see text-editor)
				try {
					await Promise.all([initUi(), loadInspectorTemplate()]);
				} catch (e) {
					console.warn('[PdfViewer] Failed to load component templates:', e);
				}
				vm.isReady = true;
				await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));

				await vm.loadPanelState();
				vm.initViewerHost();

				if (options?.paths && options.paths.length > 0) {
					for (let i = 0; i < options.paths.length; i++) {
						await vm.loadFile(options.paths[i], options.pageNumbers?.[i] || 0);
					}
					const idx = options.activeIndex != null
						? Math.min(options.activeIndex, vm.files.length - 1)
						: vm.files.length - 1;
					if (idx >= 0) vm.selectTab(idx);
				} else if (options?.path) {
					await vm.loadFile(options.path);
				}

				vm.$nextTick(() => {
					instance.notifyLaunched();
				});
			};
		},
		onUnmount() {
			const vm = this;
			unwatchActiveNode();
			if (vm.messageListener) {
				window.removeEventListener('message', vm.messageListener);
			}
			if (keydownListener) {
				document.removeEventListener('keydown', keydownListener);
				keydownListener = null;
			}
			viewerResizeObserver?.disconnect();
			viewerResizeObserver = null;
			for (const id of Array.from(runtimes.keys())) {
				vm.destroyRuntime(id);
			}
			sharedWorker?.destroy();
			sharedWorker = null;
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
		// ---- Keyboard ----
		onKeyDown(e: KeyboardEvent) {
			const vm = this;
			// While an Inspector overlay is up the user is editing inside the
			// panel — let it own the keyboard.
			if (vm.inspectorOverlayOpen || vm.passwordDialog.visible) return;
			if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
			const key = e.key;
			if ((key === 'f' || key === 'F') && !e.shiftKey) {
				e.preventDefault();
				vm.openFindPanel();
			} else if (key === 'i' || key === 'I') {
				e.preventDefault();
				vm.toggleDetailPanel();
			} else if (key === '+' || key === '=' || key === ';') {
				// ';' is where '+' sits on JIS keyboards.
				e.preventDefault();
				vm.zoomIn();
			} else if (key === '-') {
				e.preventDefault();
				vm.zoomOut();
			} else if (key === '0') {
				e.preventDefault();
				vm.fitPage();
			}
		},
		// ---- Panes ----
		toggleSidebarPanel() {
			this.sidebarPanelVisible = !this.sidebarPanelVisible;
			this.persistPanelState();
		},
		toggleDetailPanel() {
			this.detailPanelVisible = !this.detailPanelVisible;
			this.persistPanelState();
		},
		async persistPanelState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'pdf-viewer', 'panels', {
					sidebarVisible: vm.sidebarPanelVisible,
					sidebarWidth: vm.sidebarPanelWidth,
					detailVisible: vm.detailPanelVisible,
					detailWidth: vm.detailPanelWidth,
				});
			} catch { /* ignore */ }
		},
		async loadPanelState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				const state = await db.getUserSetting(userID, 'pdf-viewer', 'panels');
				if (state) {
					vm.sidebarPanelVisible = state.sidebarVisible ?? false;
					vm.sidebarPanelWidth = state.sidebarWidth ?? 260;
					vm.detailPanelVisible = state.detailVisible ?? false;
					vm.detailPanelWidth = state.detailWidth ?? 280;
				}
			} catch { /* ignore */ }
		},
		onSidebarResizeEnd() {
			this.sidebarResizing = false;
			this.persistPanelState();
		},
		// ---- Viewer host ----
		// Tab viewers are created imperatively inside #viewer-host: PDFViewer
		// owns the DOM under its container, so it must stay out of the
		// template's reach.
		initViewerHost() {
			const vm = this;
			const host = vm.$refs.viewerHost as HTMLElement | undefined;
			if (!host) return;
			let frame = 0;
			viewerResizeObserver = new ResizeObserver(() => {
				if (frame) return;
				frame = requestAnimationFrame(() => {
					frame = 0;
					vm.refitActive();
				});
			});
			viewerResizeObserver.observe(host);
			// Ctrl+wheel / trackpad pinch zooms the document instead of the page.
			host.addEventListener('wheel', (e: WheelEvent) => vm.onViewerWheel(e), { passive: false });
		},
		onViewerWheel(e: WheelEvent) {
			if (!(e.ctrlKey || e.metaKey)) return;
			const rt = this.activeRuntime();
			if (!rt || !rt.document) return;
			e.preventDefault();
			const origin = [e.clientX, e.clientY];
			if (e.deltaMode === WheelEvent.DOM_DELTA_PIXEL) {
				// Trackpad pinch (and high-resolution wheels) report small
				// pixel deltas: scale continuously.
				rt.viewer.updateScale({ drawingDelay: 400, scaleFactor: Math.exp(-e.deltaY / 100), origin });
			} else {
				rt.viewer.updateScale({ drawingDelay: 400, steps: e.deltaY < 0 ? 1 : -1, origin });
			}
		},
		activeRuntime(): ViewerRuntime | null {
			const f = this.currentFile as PdfFile | null;
			return f ? runtimes.get(f.id) ?? null : null;
		},
		// Reactive PdfFile for an id. Callbacks from pdf.js must mutate the
		// reactive proxy (not a captured raw object) for the UI to update.
		fileById(id: string): PdfFile | null {
			return (this.files as PdfFile[]).find((f) => f.id === id) ?? null;
		},
		createRuntime(id: string): ViewerRuntime | null {
			const vm = this;
			const host = vm.$refs.viewerHost as HTMLElement | undefined;
			if (!host) return null;

			const container = document.createElement('div');
			container.className = 'pdf-container';
			container.tabIndex = 0;
			container.hidden = true;
			const viewerElement = document.createElement('div');
			viewerElement.className = 'pdfViewer';
			container.appendChild(viewerElement);
			host.appendChild(container);

			const abort = new AbortController();
			const eventBus = new EventBus();
			const linkService = new PDFLinkService({
				eventBus,
				externalLinkTarget: LinkTarget.BLANK,
				externalLinkRel: 'noopener noreferrer nofollow',
			});
			const findController = new PDFFindController({ eventBus, linkService });
			// Built as a variable because `abortSignal` (which disconnects the
			// viewer's resize observer and scroll listener when the tab is
			// closed) is read by PDFViewer but missing from its typings.
			const viewerOptions = {
				container,
				viewer: viewerElement,
				eventBus,
				linkService,
				findController,
				// View-only: annotations and form fields are drawn from their
				// appearance streams, not as editable widgets, and the
				// annotation editor (highlight / ink / text) is off.
				annotationMode: AnnotationMode.ENABLE,
				annotationEditorMode: AnnotationEditorType.DISABLE,
				abortSignal: abort.signal,
			};
			const viewer = new PDFViewer(viewerOptions);
			linkService.setViewer(viewer);

			const rt: ViewerRuntime = {
				container,
				eventBus,
				linkService,
				viewer,
				abort,
				loadingTask: null,
				document: null,
				localBlob: null,
				outlineTargets: new Map(),
				needsLayout: false,
				pendingPage: 0,
			};
			runtimes.set(id, rt);

			eventBus.on('pagesinit', () => {
				rt.needsLayout = true;
				// The initial page is set without a 'pagechanging' event.
				vm.syncPageNumber(id, viewer.currentPageNumber);
				if (vm.currentFile?.id === id) vm.layoutIfNeeded(id);
			});
			eventBus.on('pagechanging', ({ pageNumber }: { pageNumber: number }) => {
				vm.syncPageNumber(id, pageNumber);
			});
			eventBus.on('scalechanging', ({ scale, presetValue }: { scale: number; presetValue?: string }) => {
				const f = vm.fileById(id);
				if (!f) return;
				f.scale = scale;
				f.scaleValue = presetValue || String(scale);
			});
			eventBus.on('rotationchanging', ({ pagesRotation }: { pagesRotation: number }) => {
				const f = vm.fileById(id);
				if (f) f.rotation = pagesRotation;
			});
			eventBus.on('updatefindcontrolstate', ({ state, matchesCount }: any) => {
				const f = vm.fileById(id);
				if (!f) return;
				f.findState = state;
				f.findCurrent = matchesCount?.current ?? 0;
				f.findTotal = matchesCount?.total ?? 0;
			});
			eventBus.on('updatefindmatchescount', ({ matchesCount }: any) => {
				const f = vm.fileById(id);
				if (!f) return;
				f.findCurrent = matchesCount?.current ?? 0;
				f.findTotal = matchesCount?.total ?? 0;
			});
			return rt;
		},
		syncPageNumber(id: string, pageNumber: number) {
			const f = this.fileById(id);
			if (!f) return;
			f.pageNumber = pageNumber;
			if (this.currentFile?.id === id && !this.pageInputFocused) {
				this.pageInput = String(pageNumber);
			}
		},
		destroyRuntime(id: string) {
			const rt = runtimes.get(id);
			if (!rt) return;
			runtimes.delete(id);
			try { rt.loadingTask?.destroy(); } catch { /* ignore */ }
			try { rt.viewer.setDocument(null as any); } catch { /* ignore */ }
			try { rt.document?.loadingTask.destroy(); } catch { /* ignore */ }
			rt.abort.abort();
			rt.container.remove();
		},
		// Apply the tab's scale, rotation and pending page once its new
		// document is initialized and the container is visible.
		layoutIfNeeded(id: string) {
			const rt = runtimes.get(id);
			const f = this.fileById(id);
			if (!rt || !f || !rt.needsLayout || rt.container.hidden) return;
			rt.needsLayout = false;
			if (f.rotation) rt.viewer.pagesRotation = f.rotation;
			rt.viewer.currentScaleValue = f.scaleValue || 'auto';
			if (rt.pendingPage > 0) {
				rt.viewer.currentPageNumber = Math.min(rt.pendingPage, rt.viewer.pagesCount);
				rt.pendingPage = 0;
			}
		},
		// Re-apply a preset scale ("page-fit", ...) to the active tab so it
		// keeps fitting after the viewer area changed size. PDFViewer does not
		// do this by itself.
		refitActive() {
			const f = this.currentFile as PdfFile | null;
			const rt = this.activeRuntime();
			if (!f || !rt || !rt.document || rt.container.hidden) return;
			if (rt.needsLayout) {
				this.layoutIfNeeded(f.id);
				return;
			}
			if (SCALE_PRESETS.includes(f.scaleValue)) {
				rt.viewer.currentScaleValue = f.scaleValue;
			}
		},
		// ---- Tabs ----
		addFile(init: Partial<PdfFile> & { name: string }): PdfFile | null {
			const vm = this;
			const id = 'pdf_' + Date.now() + '_' + Math.random().toString(36).slice(2, 11);
			if (!vm.createRuntime(id)) return null;
			vm.files.push({
				id,
				path: '',
				mimeType: 'application/pdf',
				size: 0,
				downloadUrl: '',
				modified: '',
				inspectorItem: null,
				isLoading: false,
				loadProgress: -1,
				error: '',
				pageNumber: 0,
				pagesCount: 0,
				scale: 0,
				scaleValue: 'auto',
				rotation: 0,
				outline: [],
				outlineExpanded: [],
				findQuery: '',
				findState: FindState.FOUND,
				findCurrent: 0,
				findTotal: 0,
				...init,
			} as PdfFile);
			vm.selectTab(vm.files.length - 1);
			return vm.fileById(id);
		},
		selectTab(index: number) {
			const vm = this;
			if (index < 0 || index >= vm.files.length) return;
			const file = vm.files[index] as PdfFile;
			vm.currentFileIndex = index;
			for (const [id, rt] of runtimes) {
				rt.container.hidden = id !== file.id;
			}
			if (vm.instance) vm.instance.windowTitle = file.name;
			vm.pageInput = file.pageNumber ? String(file.pageNumber) : '';
			vm.watchActiveFileNode();
			// Let the container become visible before fitting against its size.
			requestAnimationFrame(() => {
				if (vm.currentFile?.id !== file.id) return;
				vm.refitActive();
				vm.focusViewer();
			});
		},
		closeFile(index: number) {
			const vm = this;
			if (index < 0 || index >= vm.files.length) return;
			const file = vm.files[index] as PdfFile;
			const activeId = (vm.currentFile as PdfFile | null)?.id;
			vm.destroyRuntime(file.id);
			vm.files.splice(index, 1);

			if (vm.files.length === 0) {
				vm.currentFileIndex = -1;
				vm.pageInput = '';
				unwatchActiveNode();
				if (vm.instance) vm.instance.windowTitle = null as any;
				return;
			}
			const nextIndex = file.id === activeId
				? Math.min(index, vm.files.length - 1)
				: (vm.files as PdfFile[]).findIndex((f) => f.id === activeId);
			vm.selectTab(nextIndex);
		},
		focusViewer() {
			const rt = this.activeRuntime();
			if (!rt) return;
			try {
				window.focus();
				rt.container.focus({ preventScroll: true });
			} catch { /* ignore */ }
		},
		// ---- Loading ----
		async loadFile(path: string, pageNumber = 0) {
			const vm = this;
			const existingIndex = (vm.files as PdfFile[]).findIndex((f) => f.path === path);
			if (existingIndex >= 0) {
				vm.selectTab(existingIndex);
				return;
			}
			vm.errorMessage = '';
			let node: Node | null;
			try {
				node = await vm.instance.api.content.getNode(path);
			} catch (error: any) {
				vm.errorMessage = error?.message || vm.t('app.pdf-viewer.error.loadFile', undefined, 'Failed to load the PDF file');
				return;
			}
			if (!node || !node.downloadUrl) {
				vm.errorMessage = vm.t('app.pdf-viewer.error.fileNotFound', { path }, `File not found: ${path}`);
				return;
			}
			if (!isPdf(node.mimeType, node.name)) {
				vm.errorMessage = vm.t('app.pdf-viewer.error.notPdf', { name: node.name }, `"${node.name}" is not a PDF file`);
				return;
			}
			// Opened by a concurrent call while the node was being fetched.
			const racedIndex = (vm.files as PdfFile[]).findIndex((f) => f.path === node.path);
			if (racedIndex >= 0) {
				vm.selectTab(racedIndex);
				return;
			}
			const file = vm.addFile({
				path: node.path,
				name: node.name,
				mimeType: node.mimeType || 'application/pdf',
				size: node.size || 0,
				downloadUrl: node.downloadUrl,
				modified: node.modified || '',
				inspectorItem: nodeToInspectorTarget(node),
			});
			if (!file) return;
			await vm.loadDocument(file.id, { url: node.downloadUrl }, pageNumber);
		},
		async openLocalFile(blob: File) {
			const vm = this;
			if (!isPdf(blob.type, blob.name)) {
				vm.errorMessage = vm.t('app.pdf-viewer.error.notPdf', { name: blob.name }, `"${blob.name}" is not a PDF file`);
				return;
			}
			const file = vm.addFile({ name: blob.name, size: blob.size });
			if (!file) return;
			const rt = runtimes.get(file.id);
			if (rt) rt.localBlob = blob;
			try {
				// pdf.js transfers the buffer to its worker; hand it a fresh copy.
				const data = new Uint8Array(await blob.arrayBuffer());
				await vm.loadDocument(file.id, { data }, 0);
			} catch (error: any) {
				const f = vm.fileById(file.id);
				if (f) f.error = error?.message || vm.t('app.pdf-viewer.error.loadFile', undefined, 'Failed to load the PDF file');
			}
		},
		// Load (or reload) the tab's document. The previous document, if any,
		// stays on screen until the new one is ready. `pageNumber` > 0 is the
		// page to show once loaded.
		async loadDocument(id: string, source: { url: string } | { data: Uint8Array }, pageNumber: number) {
			const vm = this;
			const rt = runtimes.get(id);
			const file = vm.fileById(id);
			if (!rt || !file) return;

			try { rt.loadingTask?.destroy(); } catch { /* ignore */ }
			file.isLoading = true;
			file.loadProgress = -1;
			file.error = '';

			const task = getDocument({ ...source, ...DOCUMENT_OPTIONS, worker: getSharedWorker() });
			rt.loadingTask = task;
			const isCurrent = () => runtimes.get(id) === rt && rt.loadingTask === task;
			task.onProgress = ({ loaded, total }: { loaded: number; total: number }) => {
				if (!isCurrent()) return;
				const f = vm.fileById(id);
				if (f) f.loadProgress = total > 0 ? Math.min(1, loaded / total) : -1;
			};
			task.onPassword = (updatePassword: (password: string) => void, reason: number) => {
				vm.requestPassword(file.name, reason === PasswordResponses.INCORRECT_PASSWORD).then((password: string | null) => {
					if (!isCurrent()) return;
					if (password === null) {
						const f = vm.fileById(id);
						if (f) {
							f.isLoading = false;
							f.error = vm.t('app.pdf-viewer.error.passwordCancelled', undefined, 'The password was not entered');
						}
						rt.loadingTask = null;
						task.destroy();
						return;
					}
					updatePassword(password);
				});
			};

			let pdfDocument: PDFDocumentProxy;
			try {
				pdfDocument = await task.promise;
			} catch (error: any) {
				if (!isCurrent()) return;
				rt.loadingTask = null;
				const f = vm.fileById(id);
				if (f) {
					f.isLoading = false;
					f.error = vm.describeLoadError(error);
				}
				return;
			}
			if (!isCurrent()) {
				task.destroy();
				return;
			}
			rt.loadingTask = null;

			const previous = rt.document;
			rt.document = pdfDocument;
			rt.pendingPage = pageNumber;
			rt.outlineTargets.clear();
			rt.viewer.setDocument(pdfDocument);
			rt.linkService.setDocument(pdfDocument, null);
			try { previous?.loadingTask.destroy(); } catch { /* ignore */ }

			const f = vm.fileById(id);
			if (!f) return;
			f.isLoading = false;
			f.loadProgress = 1;
			f.pagesCount = pdfDocument.numPages;
			// The find controller was reset with the new document.
			f.findQuery = '';
			f.findTotal = 0;
			f.findCurrent = 0;
			vm.loadOutline(id, pdfDocument);
		},
		describeLoadError(error: any): string {
			const vm = this;
			switch (error?.name) {
				case 'InvalidPDFException':
					return vm.t('app.pdf-viewer.error.invalidPdf', undefined, 'Invalid or corrupted PDF file');
				case 'ResponseException':
					return error.missing
						? vm.t('app.pdf-viewer.error.missingPdf', undefined, 'The PDF file could not be found')
						: vm.t('app.pdf-viewer.error.unexpectedResponse', { status: error.status }, `Unexpected server response (${error.status})`);
				case 'PasswordException':
					return vm.t('app.pdf-viewer.error.passwordCancelled', undefined, 'The password was not entered');
				default:
					return error?.message || vm.t('app.pdf-viewer.error.loadFile', undefined, 'Failed to load the PDF file');
			}
		},
		async loadOutline(id: string, pdfDocument: PDFDocumentProxy) {
			const vm = this;
			let items: any[] | null = null;
			try {
				items = await pdfDocument.getOutline();
			} catch { /* no outline */ }
			const rt = runtimes.get(id);
			const f = vm.fileById(id);
			if (!rt || !f || rt.document !== pdfDocument) return;
			let seq = 0;
			const expanded: string[] = [];
			const build = (list: any[]): OutlineNode[] => (list || []).map((item: any) => {
				const nodeId = `o${++seq}`;
				rt.outlineTargets.set(nodeId, { dest: item.dest ?? null, url: item.url || '' });
				const children = build(item.items);
				// A positive count means the item is open by default.
				if (children.length > 0 && item.count > 0) expanded.push(nodeId);
				return { id: nodeId, label: item.title || '', isLink: !!item.url, children };
			});
			f.outline = build(items || []);
			f.outlineExpanded = expanded;
		},
		// ---- Password prompt ----
		requestPassword(fileName: string, incorrect: boolean): Promise<string | null> {
			const vm = this;
			const ask = () => new Promise<string | null>((resolve) => {
				vm.passwordDialog.fileName = fileName;
				vm.passwordDialog.incorrect = incorrect;
				vm.passwordDialog.value = '';
				vm.passwordDialog.resolve = resolve;
				vm.passwordDialog.visible = true;
			});
			const next = passwordQueue.then(ask, ask);
			passwordQueue = next.catch(() => null);
			return next;
		},
		onPasswordDialogAction(submit: boolean) {
			const vm = this;
			const resolve = vm.passwordDialog.resolve;
			const value = vm.passwordDialog.value;
			vm.passwordDialog.visible = false;
			vm.passwordDialog.resolve = null;
			vm.passwordDialog.value = '';
			resolve?.(submit ? value : null);
		},
		// ---- Navigation / zoom / rotation ----
		previousPage() {
			this.activeRuntime()?.viewer.previousPage();
		},
		nextPage() {
			this.activeRuntime()?.viewer.nextPage();
		},
		onPageInputFocus(event: FocusEvent) {
			this.pageInputFocused = true;
			(event.target as HTMLInputElement)?.select();
		},
		onPageInputBlur() {
			this.pageInputFocused = false;
			const f = this.currentFile as PdfFile | null;
			this.pageInput = f?.pageNumber ? String(f.pageNumber) : '';
		},
		commitPageInput() {
			const vm = this;
			const rt = vm.activeRuntime();
			const f = vm.currentFile as PdfFile | null;
			if (!rt || !f || !f.pagesCount) return;
			const page = parseInt(String(vm.pageInput).trim(), 10);
			if (Number.isFinite(page)) {
				rt.viewer.currentPageNumber = Math.min(Math.max(page, 1), f.pagesCount);
			}
			vm.pageInput = String(rt.viewer.currentPageNumber);
			vm.focusViewer();
		},
		zoomIn() {
			const rt = this.activeRuntime();
			if (rt?.document) rt.viewer.increaseScale();
		},
		zoomOut() {
			const rt = this.activeRuntime();
			if (rt?.document) rt.viewer.decreaseScale();
		},
		fitWidth() {
			const rt = this.activeRuntime();
			if (rt?.document) rt.viewer.currentScaleValue = 'page-width';
		},
		fitPage() {
			const rt = this.activeRuntime();
			if (rt?.document) rt.viewer.currentScaleValue = 'page-fit';
		},
		rotate(delta: number) {
			const rt = this.activeRuntime();
			if (rt?.document) rt.viewer.pagesRotation = (rt.viewer.pagesRotation + delta + 360) % 360;
		},
		// ---- Download / open in browser ----
		// Repository files go through the download servlet (`?attachment`
		// forces a download; without it the browser's own viewer opens the
		// file, which is also the way to print). Local files use a blob URL.
		downloadFile() {
			const f = this.currentFile as PdfFile | null;
			const rt = this.activeRuntime();
			if (!f || !rt) return;
			if (f.downloadUrl) {
				this.clickLink(withAttachmentParam(f.downloadUrl), f.name);
			} else if (rt.localBlob) {
				const url = URL.createObjectURL(rt.localBlob);
				this.clickLink(url, f.name);
				setTimeout(() => URL.revokeObjectURL(url), 60_000);
			}
		},
		openInBrowser() {
			const f = this.currentFile as PdfFile | null;
			const rt = this.activeRuntime();
			if (!f || !rt) return;
			if (f.downloadUrl) {
				window.open(f.downloadUrl, '_blank', 'noopener');
			} else if (rt.localBlob) {
				const url = URL.createObjectURL(rt.localBlob);
				window.open(url, '_blank', 'noopener');
				setTimeout(() => URL.revokeObjectURL(url), 60_000);
			}
		},
		clickLink(href: string, fileName: string) {
			const a = document.createElement('a');
			a.href = href;
			a.download = fileName;
			a.rel = 'noopener';
			document.body.appendChild(a);
			a.click();
			a.remove();
		},
		// ---- Find ----
		openFindPanel() {
			const vm = this;
			const selected = String(window.getSelection() || '').trim();
			if (selected && !selected.includes('\n')) {
				vm.findQuery = selected;
			}
			vm.sidebarPanelVisible = true;
			vm.$nextTick(() => {
				const input = document.querySelector('.find-section input') as HTMLInputElement | null;
				input?.focus();
				input?.select();
			});
		},
		dispatchFind(type: '' | 'again', findPrevious = false) {
			const vm = this;
			const rt = vm.activeRuntime();
			const f = vm.currentFile as PdfFile | null;
			if (!rt || !f || !rt.document) return;
			if (!vm.findQuery) {
				rt.eventBus.dispatch('findbarclose', { source: null });
				f.findQuery = '';
				f.findTotal = 0;
				f.findCurrent = 0;
				return;
			}
			// A query this tab has not searched yet starts a new search.
			const effectiveType = f.findQuery === vm.findQuery ? type : '';
			f.findQuery = vm.findQuery;
			rt.eventBus.dispatch('find', {
				source: null,
				type: effectiveType,
				query: vm.findQuery,
				caseSensitive: vm.findCaseSensitive,
				entireWord: vm.findWholeWord,
				highlightAll: true,
				findPrevious,
				matchDiacritics: false,
			});
		},
		onFindInput() {
			// An unchanged query would make pdf.js step to the next match.
			const f = this.currentFile as PdfFile | null;
			if (f && this.findQuery && f.findQuery === this.findQuery) return;
			this.dispatchFind('');
		},
		onFindKeyDown(event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				this.dispatchFind('again', event.shiftKey);
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.focusViewer();
			}
		},
		findNext() {
			this.dispatchFind('again', false);
		},
		findPrevious() {
			this.dispatchFind('again', true);
		},
		toggleFindCaseSensitive() {
			this.findCaseSensitive = !this.findCaseSensitive;
			this.restartFind();
		},
		toggleFindWholeWord() {
			this.findWholeWord = !this.findWholeWord;
			this.restartFind();
		},
		// Options changed: search again from scratch.
		restartFind() {
			const f = this.currentFile as PdfFile | null;
			if (f) f.findQuery = '';
			this.dispatchFind('');
		},
		// ---- Outline ----
		onOutlineSelect(node: OutlineNode) {
			const f = this.currentFile as PdfFile | null;
			const rt = this.activeRuntime();
			if (!f || !rt || !node) return;
			const target = rt.outlineTargets.get(node.id);
			if (!target) return;
			if (target.url) {
				window.open(target.url, '_blank', 'noopener,noreferrer');
			} else if (target.dest) {
				rt.linkService.goToDestination(target.dest);
			}
		},
		onOutlineToggle(detail: { id: string; expanded: boolean }) {
			const f = this.currentFile as PdfFile | null;
			if (!f || !detail) return;
			const rest = f.outlineExpanded.filter((x: string) => x !== detail.id);
			f.outlineExpanded = detail.expanded ? [...rest, detail.id] : rest;
		},
		// ---- Drag & drop ----
		// App root rejects drops by default so the browser does not navigate
		// to a dropped file; the center pane opts in.
		onAppDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		onForbiddenDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		// Accept files from Content Browser or the OS; reject editor "Save As"
		// payloads, which are not files yet.
		onCenterPaneDragOver(event: DragEvent) {
			if (!event.dataTransfer) return;
			const types = event.dataTransfer.types;
			const accept = !types.includes('application/x-webtop-save') && (types.includes('Files')
				|| types.includes('application/x-webtop-file')
				|| types.includes('application/x-webtop-files'));
			event.dataTransfer.dropEffect = accept ? 'copy' : 'none';
		},
		async onCenterPaneDrop(event: DragEvent) {
			const vm = this;
			const dt = event.dataTransfer;
			if (!dt || dt.types.includes('application/x-webtop-save')) return;

			// Content Browser: every selected file (collections skipped).
			const paths: string[] = [];
			const multi = dt.getData('application/x-webtop-files');
			if (multi) {
				try {
					for (const item of JSON.parse(multi)) {
						if (item?.path && !item.isCollection) paths.push(item.path);
					}
				} catch (e) {
					console.error('Failed to parse webtop files data:', e);
				}
			} else {
				const single = dt.getData('application/x-webtop-file');
				if (single) {
					try {
						const item = JSON.parse(single);
						if (item?.path) paths.push(item.path);
					} catch (e) {
						console.error('Failed to parse webtop file data:', e);
					}
				}
			}
			if (paths.length > 0) {
				for (const path of paths) {
					await vm.loadFile(path);
				}
				return;
			}

			// Files dragged in from the OS.
			const localFiles = Array.from(dt.files || []);
			for (const localFile of localFiles) {
				await vm.openLocalFile(localFile);
			}
		},
		// ---- Inspector (right pane) ----
		onInspectorOverlayChanged(open: boolean) {
			this.inspectorOverlayOpen = !!open;
		},
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
		// The Inspector reverted the node's stored content (version restore /
		// cancel checkout): show the restored content.
		onInspectorContentReverted(detail: any) {
			const path = detail?.target?.path;
			if (path) this.refreshFileFromNode(path, true);
		},
		// Watch the active tab's node so the viewer follows changes made
		// through the Inspector or by other clients.
		watchActiveFileNode() {
			const vm = this;
			unwatchActiveNode();
			const f = vm.currentFile as PdfFile | null;
			if (!f || !f.path) return;
			const eventHub = vm.instance?.api?.eventHub;
			if (!eventHub || typeof eventHub.watchNode !== 'function') return;
			const path = f.path;
			activeNodeWatchUnsubscribe = eventHub.watchNode(path, () => {
				vm.refreshFileFromNode(path, false);
			}, false);
		},
		// Pull the latest node metadata into the tab and reload the document
		// when its stored content changed (jcr:lastModified moved), keeping
		// the current page. `force` reloads regardless.
		async refreshFileFromNode(path: string, force: boolean) {
			const vm = this;
			let node: Node | null;
			try {
				node = await vm.instance.api.content.getNode(path);
			} catch {
				return;
			}
			const f = (vm.files as PdfFile[]).find((x) => x.path === path);
			if (!node || !f) return;
			const contentChanged = force || (!!f.modified && !!node.modified && f.modified !== node.modified);
			f.name = node.name;
			f.mimeType = node.mimeType || f.mimeType;
			f.size = node.size || 0;
			f.downloadUrl = node.downloadUrl || '';
			f.modified = node.modified || '';
			f.inspectorItem = nodeToInspectorTarget(node);
			if (vm.currentFile?.id === f.id && vm.instance) {
				vm.instance.windowTitle = f.name;
			}
			if (contentChanged && f.downloadUrl) {
				await vm.loadDocument(f.id, { url: f.downloadUrl }, f.pageNumber);
			}
		},
		dismissError() {
			this.errorMessage = '';
		},
	},
};

// Serializes password prompts from concurrently loading tabs.
let passwordQueue: Promise<unknown> = Promise.resolve();

// Mount immediately. The screen itself is behind the readiness gate
// (<template v-if="isReady"> in index.html), which appLaunch opens once the
// component templates are loaded.
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');

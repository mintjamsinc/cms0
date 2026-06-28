import { ApplicationInstance } from "../../services/webtop-service.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";

// Markdown parser
import { marked } from "marked";

// =============================================================================
// Text Editor Preview — companion window for text-editor.
//
// Hidden from the Start Menu (enableStartMenu: false). The text-editor app
// launches this window via the shell's 'open-app' postMessage, passing a
// previewKey (the editor instance ID). Communication is over a
// BroadcastChannel named 'webtop-text-preview'.
//
// Protocol (all messages carry { previewKey }):
//   editor → preview:
//     - 'preview-state'  { fileName, mimeType, content, filePath,
//                          isMarkdown, isHtml, isTemplated, isScriptable,
//                          workspace, previewExtension? }
//     - 'preview-close'  asks this window to close itself (editor or tab
//                        gone).
//     - 'preview-ping'   editor announcing it is alive (used so the
//                        preview can detect editor closures via ping
//                        timeout).
//   preview → editor:
//     - 'preview-ready'   sent on mount so editor can send initial state.
//     - 'preview-closed'  sent before unmount so editor can clear its
//                        open-state flag.
//
// MIME → extension hint for the templated-preview header.
// =============================================================================

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

// A <base href> (needed so relative assets resolve to the file's CMS mount)
// also makes same-document fragment links — e.g. an on-this-page TOC entry
// "#section" — resolve against the base URL instead of this document, turning
// an in-page jump into a full navigation to the mount, out of the preview's
// service-worker scope (where the page's root-absolute CSS/JS no longer get
// rewritten, so the followed document loads unstyled). Restore in-document
// behavior by intercepting fragment-link clicks and scrolling instead, mirroring
// how the published page (which has no <base>) behaves. Cross-page links are
// left untouched. Runs for both the srcdoc and service-worker iframes.
const FRAGMENT_LINK_SHIM = '<script>(function(){document.addEventListener("click",function(e){' +
	'if(e.defaultPrevented||e.button!==0||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;' +
	'var a=e.target&&e.target.closest?e.target.closest("a[href]"):null;if(!a)return;' +
	'var href=a.getAttribute("href");if(!href||href.charAt(0)!=="#")return;e.preventDefault();' +
	'var id=decodeURIComponent(href.slice(1));var t=id?document.getElementById(id):document.body;' +
	'if(t)t.scrollIntoView();' +
	'try{history.replaceState(null,"",location.pathname+location.search+href);}catch(_){}' +
	'},true);})();<\/script>';

// Inject a <base href> so relative links/assets in the preview resolve
// against the file's real CMS location instead of the webtop app URL.
// Both the srcdoc (plain HTML) and blob: (templated) iframes otherwise
// resolve relative paths against the embedding document, breaking assets.
// Any existing <base> in the source is stripped so ours always wins.
function injectBaseTag(html: string, absoluteBaseUrl: string): string {
	const baseTag = `<base href="${absoluteBaseUrl.replace(/"/g, '&quot;')}">`;
	const head = baseTag + FRAGMENT_LINK_SHIM;
	const stripped = html.replace(/<base\b[^>]*\/?>/gi, '');
	if (/<head\b[^>]*>/i.test(stripped)) {
		return stripped.replace(/<head\b[^>]*>/i, (m) => m + head);
	}
	if (/<html\b[^>]*>/i.test(stripped)) {
		return stripped.replace(/<html\b[^>]*>/i, (m) => `${m}<head>${head}</head>`);
	}
	return `<head>${head}</head>${stripped}`;
}

// Index signature so this is assignable to the global appLaunch signature
// (which declares { path?: string; mimeType?: string }) — see global.d.ts.
interface LaunchOptions {
	previewKey?: string;
	[key: string]: any;
}

// Ping timeout — if the editor stops sending pings, the preview assumes it
// was closed and shows the "Editor was closed" placeholder. The editor sends
// a ping every 2s while alive; we treat 8s of silence as a closure.
const EDITOR_PING_TIMEOUT_MS = 8000;

// -----------------------------------------------------------------------------
// Service-worker-backed rendering.
//
// Published content uses site-root-absolute asset URLs (e.g. /docs/css/...)
// that an HTML <base> cannot remap. To reproduce a page faithfully the preview
// hands the rendered HTML to the webtop service worker, which serves it for a
// synthetic in-scope URL and rewrites the document's root-absolute subresource
// requests to the preview mount. The iframe must load a REAL same-origin URL
// (blob:/srcdoc documents are not SW-controlled, so their subresources can't be
// intercepted). When no service worker controls this window, every call here
// returns null and the caller falls back to blob:/srcdoc rendering.
// -----------------------------------------------------------------------------

// Path segment marking the synthetic preview document (kept in sync with sw.js).
const SW_PREVIEW_PATH = '__sw-preview__';
let swFrameSeq = 0;

// Absolute synthetic document URL for a preview key, under this app's path so it
// falls within the service worker's scope. Stable per key (content is replaced
// in place on each render); the query only carries the mount base and a
// cache-busting sequence.
function swDocumentUrl(previewKey: string): string {
	return new URL(`./${SW_PREVIEW_PATH}/${encodeURIComponent(previewKey || 'preview')}`, window.location.href).href;
}

// Stores `html` in the service worker under the synthetic URL for `previewKey`
// and returns the iframe URL to load (carrying the mount `base` and a fresh
// sequence), or null when no controller is available or the worker doesn't
// acknowledge in time. `base` is the preview mount prefix, e.g.
// `/bin/cms.cgi/{workspace}/content/public`.
async function swRenderFrame(previewKey: string, html: string, base: string): Promise<string | null> {
	const sw = navigator.serviceWorker;
	const controller = sw && sw.controller;
	if (!controller || !base) {
		return null;
	}
	const docUrl = swDocumentUrl(previewKey);
	const acked = await new Promise<boolean>((resolve) => {
		let settled = false;
		const finish = (ok: boolean) => {
			if (settled) return;
			settled = true;
			sw.removeEventListener('message', onMessage);
			resolve(ok);
		};
		const onMessage = (event: MessageEvent) => {
			const data = event.data || {};
			if (data.type === 'sw-preview-ack' && data.key === previewKey) {
				finish(true);
			}
		};
		sw.addEventListener('message', onMessage);
		try {
			controller.postMessage({ type: 'sw-preview-put', key: previewKey, url: docUrl, html });
		} catch {
			finish(false);
			return;
		}
		// Don't hang the preview if the worker never acknowledges.
		setTimeout(() => finish(false), 3000);
	});
	if (!acked) {
		return null;
	}
	const seq = ++swFrameSeq;
	return `${docUrl}?base=${encodeURIComponent(base)}&seq=${seq}`;
}

// Asks the service worker to drop a preview's stored document (on close).
function swDropFrame(previewKey: string): void {
	const sw = navigator.serviceWorker;
	const controller = sw && sw.controller;
	if (!controller) return;
	try {
		controller.postMessage({ type: 'sw-preview-del', key: previewKey, url: swDocumentUrl(previewKey) });
	} catch {
		/* ignore */
	}
}

// Native objects with privileged internal slots (BroadcastChannel,
// EditorView, ...) must NOT be stored in ichigojs's reactive data(). Even
// $markRaw isn't enough: reads still pass through the parent data Proxy and
// `this` gets unbound from the underlying instance, throwing "Illegal
// invocation" the moment any native method runs. Keep it in module scope
// instead — there is only ever one preview window per iframe.
let previewChannel: BroadcastChannel | null = null;

// In-flight server render. Real-time previews fire a render on every settled
// edit; only the latest content should win, so a new render aborts the
// previous one and a monotonic token lets late responses be discarded.
let previewAbort: AbortController | null = null;
let renderSeq = 0;
// Supersession token for the client-side HTML (isHtml) path, whose
// service-worker frame is produced asynchronously; a newer settled edit must
// win over a slow worker acknowledgement.
let htmlRenderSeq = 0;

// Scroll preservation across preview refreshes. A live edit re-renders the
// whole preview (markdown re-parsed, iframe reloaded), which would otherwise
// snap the reader back to the top on every keystroke. We capture the current
// scroll position just before a same-file content update and re-apply it once
// the new content is laid out. A file switch skips capture so the new file
// starts at the top. mode tells which container the offsets belong to:
//   'markdown' → the .preview-body scroll container (top)
//   'iframe'   → the preview iframe's own document (x/y)
let pendingScrollRestore = false;
let savedScroll: { mode: 'markdown' | 'iframe'; top: number; x: number; y: number } | null = null;

// Window move/resize interaction watch. The shell (wt-window) toggles
// wt-window-resizing / wt-window-dragging on our <html> for the duration of a
// move or resize, and while either is set it disables pointer events on nested
// iframes (see webtop-app.css). A browser layer optimization can then leave the
// inner preview iframe unable to scroll once that style is lifted; we watch for
// the markers and pin the inner document to overflow:auto for the duration of
// the gesture so it stays a live scroll container, clearing the override once
// the gesture ends. MutationObserver is kept in module scope (not reactive
// data) because it carries privileged internals that must not be Proxy-wrapped.
const WINDOW_INTERACTION_CLASSES = ['wt-window-resizing', 'wt-window-dragging'];
let windowInteractionObserver: MutationObserver | null = null;

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			previewKey: '',
			fileName: '',
			mimeType: 'text/plain',
			content: '',
			filePath: '',
			workspace: '',
			// Server-resolved site document root (e.g. '/content/public') for the
			// active file, from the editor's preview-state. Combined with the
			// workspace it yields the preview mount prefix the service worker uses
			// to rewrite site-root-absolute asset URLs.
			documentRoot: '',
			isMarkdown: false,
			isHtml: false,
			isTemplated: false,
			isScriptable: false,
			// Derived: templated || scriptable. Both are rendered by the
			// server so the preview matches what the CMS actually serves.
			isServerRendered: false,
			previewExtension: 'html',
			// Click-to-edit state for the templated output-extension chooser
			// (mirrors the content-browser MIME field).
			extEditing: false,
			extInput: '',
			previewHtml: '',
			previewSrcDoc: '',
			previewIframeUrl: '',
			// filePath the current previewIframeUrl was rendered from, so a
			// stale server render isn't shown after switching files.
			renderedFilePath: '',
			previewLoading: false,
			previewError: '',
			disconnected: false,
			lastPingAt: 0,
			pingTimer: null as number | null,
			messageListener: null as ((e: MessageEvent) => void) | null,
			hasState: false,
			readyRetryTimer: null as number | null,
			readyRetryCount: 0,
			// Pin: when on, ignore preview-state for files other than the
			// one we were pinned to. Editor also keeps a copy of the pinned
			// path so it can tell us when that file gets closed.
			pinned: false,
			pinnedFilePath: '',
			// Reactive Localization snapshot — see composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
		};
	},
	methods: {
		/** Reactive i18n lookup; repaints on language change. */
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		async onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) {
					return;
				}
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			// Recover inner-iframe scrolling after a window move/resize ends.
			vm.startWindowInteractionWatch();

			window.appLaunch = async (instance: ApplicationInstance, options?: LaunchOptions) => {
				vm.instance = vm.$markRaw(instance);
				vm.previewKey = options?.previewKey || '';
				refreshLocalization(vm.localization, vm.instance);

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				vm.instance.windowTitle = vm.t('app.text-editor-preview.toolbar.label', undefined, 'Preview');
				vm.instance.setDisplayInfo({ subtitle: '' });

				instance.appState = () => ({ previewKey: vm.previewKey });

				vm.openChannel();

				instance.setBeforeCloseCallback(async () => {
					vm.notifyClosed();
					return true;
				});

				vm.lastPingAt = Date.now();
				vm.startPingMonitor();

				vm.$nextTick(() => {
					instance.notifyLaunched();
					vm.sendReady();
					vm.startReadyRetry();
				});
			};
		},
		onUnmount() {
			const vm = this;
			vm.notifyClosed();
			vm.stopPingMonitor();
			vm.stopReadyRetry();
			vm.stopWindowInteractionWatch();
			if (previewChannel) {
				try { previewChannel.close(); } catch { /* ignore */ }
				previewChannel = null;
			}
			if (vm.messageListener) {
				window.removeEventListener('message', vm.messageListener);
				vm.messageListener = null;
			}
			if (vm.previewIframeUrl) {
				try { URL.revokeObjectURL(vm.previewIframeUrl); } catch { /* ignore */ }
				vm.previewIframeUrl = '';
			}
			// Drop this preview's document from the service-worker cache.
			swDropFrame(vm.previewKey);
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
		// ---- Channel ----
		openChannel() {
			const vm = this;
			if (previewChannel) return;
			const ch = new BroadcastChannel('webtop-text-preview');
			ch.onmessage = (event: MessageEvent) => {
				const msg = event.data || {};
				if (!msg.previewKey || msg.previewKey !== vm.previewKey) return;
				if (msg.type === 'preview-state') {
					vm.applyState(msg);
				} else if (msg.type === 'preview-close') {
					vm.instance?.requestClose();
				} else if (msg.type === 'preview-unpinned') {
					// Editor closed the pinned tab. Drop pin and re-request
					// state so we follow the editor's current active tab.
					vm.pinned = false;
					vm.pinnedFilePath = '';
					vm.sendReady();
				} else if (msg.type === 'preview-ping') {
					vm.lastPingAt = Date.now();
					if (vm.disconnected) vm.disconnected = false;
					// Editor is alive but we still haven't received state —
					// our preview-ready must have been missed. Resend now
					// instead of waiting for the next retry tick.
					if (!vm.hasState) vm.sendReady();
				}
			};
			previewChannel = ch;
		},
		sendReady() {
			previewChannel?.postMessage({ type: 'preview-ready', previewKey: this.previewKey });
		},
		// Re-announce ourselves until we get a preview-state. Guards against
		// the very-first preview-ready being missed (e.g. the editor's
		// channel-message handler not being attached yet when the preview
		// iframe finishes loading faster than expected).
		startReadyRetry() {
			const vm = this;
			vm.stopReadyRetry();
			vm.readyRetryCount = 0;
			vm.readyRetryTimer = window.setInterval(() => {
				if (vm.hasState || vm.disconnected) {
					vm.stopReadyRetry();
					return;
				}
				vm.readyRetryCount++;
				vm.sendReady();
				// Give up after ~10 retries (10s) — by then the ping monitor
				// will have flipped to the "disconnected" state anyway.
				if (vm.readyRetryCount >= 10) vm.stopReadyRetry();
			}, 1000) as unknown as number;
		},
		stopReadyRetry() {
			if (this.readyRetryTimer != null) {
				clearInterval(this.readyRetryTimer);
				this.readyRetryTimer = null;
			}
		},
		notifyClosed() {
			try {
				previewChannel?.postMessage({ type: 'preview-closed', previewKey: this.previewKey });
			} catch { /* ignore */ }
		},
		startPingMonitor() {
			const vm = this;
			vm.stopPingMonitor();
			vm.pingTimer = window.setInterval(() => {
				if (Date.now() - vm.lastPingAt > EDITOR_PING_TIMEOUT_MS) {
					vm.disconnected = true;
				}
			}, 2000) as unknown as number;
		},
		stopPingMonitor() {
			if (this.pingTimer != null) {
				clearInterval(this.pingTimer);
				this.pingTimer = null;
			}
		},
		// ---- State application ----
		togglePin() {
			const vm = this;
			if (vm.pinned) {
				vm.pinned = false;
				vm.pinnedFilePath = '';
				previewChannel?.postMessage({
					type: 'preview-unpin',
					previewKey: vm.previewKey,
				});
				// Reflect editor's current active tab now that we're free.
				vm.sendReady();
			} else {
				if (!vm.filePath) return;
				vm.pinned = true;
				vm.pinnedFilePath = vm.filePath;
				previewChannel?.postMessage({
					type: 'preview-pin',
					previewKey: vm.previewKey,
					filePath: vm.filePath,
				});
			}
		},
		applyState(msg: any) {
			const vm = this;
			// While pinned, drop state for any file other than the pinned
			// one. The editor still sends state for whichever tab is active
			// in its window — we just don't render it.
			if (vm.pinned && vm.pinnedFilePath && msg.filePath !== vm.pinnedFilePath) {
				vm.lastPingAt = Date.now();
				return;
			}
			vm.hasState = true;
			vm.stopReadyRetry();
			vm.disconnected = false;
			vm.lastPingAt = Date.now();
			// Detect a file switch before overwriting filePath: the extension
			// is reseeded per file, but kept across same-file content edits so
			// the user's chosen output extension isn't clobbered on every
			// keystroke (the editor re-sends a MIME-guessed default each time).
			const fileChanged = (msg.filePath || '') !== vm.filePath;
			// Same-file content edit: remember where the reader is so the
			// refreshed preview lands back at the same spot. Capture now, while
			// vm's mode flags and the DOM still reflect the preview on screen.
			// A file switch starts fresh at the top instead (the markdown
			// scroll container is reused across files, so reset it explicitly).
			if (fileChanged) {
				vm.clearScrollCapture();
			} else {
				vm.captureScroll();
			}
			vm.fileName = msg.fileName || '';
			vm.mimeType = msg.mimeType || 'text/plain';
			vm.content = msg.content || '';
			vm.filePath = msg.filePath || '';
			vm.workspace = msg.workspace || '';
			vm.documentRoot = msg.documentRoot || '';
			vm.isMarkdown = !!msg.isMarkdown;
			vm.isHtml = !!msg.isHtml;
			vm.isTemplated = !!msg.isTemplated;
			vm.isScriptable = !!msg.isScriptable;
			vm.isServerRendered = vm.isTemplated || vm.isScriptable;

			if (fileChanged) {
				vm.previewExtension = msg.previewExtension || guessExtensionFromMime(vm.mimeType);
				// Abandon any half-finished extension edit from the prior file.
				vm.extEditing = false;
				vm.extInput = '';
			} else if (!vm.previewExtension) {
				vm.previewExtension = guessExtensionFromMime(vm.mimeType);
			}

				// Window title reflects active filename.
				if (vm.instance) {
					const previewLabel = vm.t('app.text-editor-preview.toolbar.label', undefined, 'Preview');
					vm.instance.windowTitle = vm.fileName
						? vm.t('app.text-editor-preview.window.titleWithFile', { fileName: vm.fileName }, `${previewLabel} — ${vm.fileName}`)
						: previewLabel;
					vm.instance.setDisplayInfo({ subtitle: vm.fileName });
				}

			vm.updatePreview();

			// A new file always starts at the top. The markdown scroll
			// container persists across file switches, so reset it once the
			// new content is in place. (Iframe previews reload into a fresh
			// document that already starts at the top.)
			if (fileChanged) {
				vm.$nextTick(() => {
					const el = vm.getScrollContainer();
					if (el) el.scrollTop = 0;
				});
			}
		},
		// ---- Scroll preservation ----
		getScrollContainer(): HTMLElement | null {
			return (this.$refs.previewBody as HTMLElement) ?? null;
		},
		getPreviewIframe(): HTMLIFrameElement | null {
			return (this.$refs.previewIframe as HTMLIFrameElement) ?? null;
		},
		// Snapshot the current scroll position of whichever preview is on
		// screen. Reads the live DOM so it reflects the actual rendered
		// preview, independent of the mode flags about to be overwritten.
		captureScroll() {
			const vm = this;
			if (vm.isHtml || vm.isServerRendered) {
				const win = vm.getPreviewIframe()?.contentWindow;
				if (win) {
					try {
						savedScroll = { mode: 'iframe', top: 0, x: win.scrollX || 0, y: win.scrollY || 0 };
						pendingScrollRestore = true;
						return;
					} catch { /* cross-origin or not ready — skip */ }
				}
			} else if (vm.isMarkdown) {
				const el = vm.getScrollContainer();
				if (el) {
					savedScroll = { mode: 'markdown', top: el.scrollTop || 0, x: 0, y: 0 };
					pendingScrollRestore = true;
					return;
				}
			}
			vm.clearScrollCapture();
		},
		clearScrollCapture() {
			pendingScrollRestore = false;
			savedScroll = null;
		},
		// Re-apply a captured markdown scroll once the re-parsed HTML is in
		// the DOM. Iframe previews restore on their load event instead (see
		// onIframeLoad), because their content arrives asynchronously.
		restoreMarkdownScroll() {
			const vm = this;
			const s = savedScroll;
			if (!pendingScrollRestore || !s || s.mode !== 'markdown') return;
			const top = s.top;
			vm.$nextTick(() => {
				const el = vm.getScrollContainer();
				if (el) el.scrollTop = top;
				vm.clearScrollCapture();
			});
		},
		// Restore a captured iframe scroll after the reloaded document lays
		// out. Fires for both the plain-HTML (srcdoc) and server-rendered
		// (blob src) iframes; a no-op unless a same-file update captured a
		// position first (a file switch / first load leaves it pending=false).
		onIframeLoad(event: Event) {
			const s = savedScroll;
			if (!pendingScrollRestore || !s || s.mode !== 'iframe') return;
			const win = (event.target as HTMLIFrameElement)?.contentWindow;
			if (win) {
				try { win.scrollTo(s.x, s.y); } catch { /* ignore */ }
			}
			this.clearScrollCapture();
		},
		// ---- Window interaction watch (move / resize) ----
		hasInteractionClass(root: HTMLElement): boolean {
			return WINDOW_INTERACTION_CLASSES.some((c) => root.classList.contains(c));
		},
		startWindowInteractionWatch() {
			const vm = this;
			if (windowInteractionObserver) return;
			const root = document.documentElement;
			let wasInteracting = vm.hasInteractionClass(root);
			windowInteractionObserver = new MutationObserver(() => {
				const isInteracting = vm.hasInteractionClass(root);
				// The gesture just began or ended:
				// pin the inner preview to overflow:auto for its duration so
					// scrolling survives, then release the override once it settles.
				if (isInteracting !== wasInteracting) {
					vm.setInnerPreviewOverflow(isInteracting);
				}
				wasInteracting = isInteracting;
			});
			windowInteractionObserver.observe(root, { attributes: true, attributeFilter: ['class'] });
		},
		stopWindowInteractionWatch() {
			if (windowInteractionObserver) {
				try { windowInteractionObserver.disconnect(); } catch { /* ignore */ }
				windowInteractionObserver = null;
			}
		},
		// Hold the inner preview iframe's document as an explicit scroll
		// container while the window is being moved/resized, then release it.
		// During the gesture the shell disables pointer events on nested
		// iframes, and a browser layer optimization can otherwise leave the
		// inner document unable to scroll once that style is lifted; overflow:
		// auto keeps it a live scroll container, and removing the override
		// afterward restores its default. Markdown previews have no inner
		// iframe, so this is a no-op there.
		setInnerPreviewOverflow(active: boolean) {
			const el = this.getPreviewIframe()?.contentDocument?.documentElement;
			if (!el) return;
			if (active) {
				el.style.overflow = 'auto';
			} else {
				el.style.removeProperty('overflow');
			}
		},
		// ---- Preview rendering ----
		updatePreview() {
			const vm = this;
			vm.previewError = '';

			// Server-rendered content (templated or scriptable, e.g. .gsp) is
			// fetched from the server so the preview matches what the CMS
			// actually serves. Rendering the raw source here would show
			// unevaluated markup (template directives, GSP scriptlets) and
			// diverge from the real output. This now runs in real time: every
			// settled edit triggers a fresh server render.
			if (vm.isServerRendered) {
				vm.previewHtml = '';
				vm.previewSrcDoc = '';
				vm.refreshServerPreview();
				return;
			}

			// Switched to a client-rendered type — cancel any in-flight server
			// render so a late response can't overwrite the new preview.
			if (previewAbort) {
				try { previewAbort.abort(); } catch { /* ignore */ }
				previewAbort = null;
			}
			vm.previewLoading = false;

			if (vm.isMarkdown) {
				try {
					marked.setOptions({ breaks: true, gfm: true });
					vm.previewHtml = marked.parse(vm.content) as string;
				} catch {
					vm.previewHtml = `<p class="text-danger">${vm.t('app.text-editor-preview.error.markdownRender', undefined, 'Error rendering preview')}</p>`;
				}
				vm.restoreMarkdownScroll();
				return;
			}

			vm.previewHtml = '';

			if (vm.isHtml) {
				const content = vm.content || '';
				// Resolve relative paths against the file's CMS location. The
				// file is unsaved when filePath/workspace are missing, so there
				// is no meaningful base to point at — leave content untouched.
				const hasBase = !!(content && vm.filePath && vm.workspace);
				const patched = hasBase
					? injectBaseTag(content, new URL(`/bin/cms.cgi/${vm.workspace}${vm.filePath}`, window.location.href).href)
					: content;
				const mountBase = vm.previewMountBase();
				// With a known document root and an active service worker, render
				// through the worker frame so site-root-absolute assets resolve;
				// otherwise (or if the worker declines) fall back to srcdoc, which
				// still resolves relative assets via the injected <base>.
				if (hasBase && mountBase) {
					const seq = ++htmlRenderSeq;
					swRenderFrame(vm.previewKey, patched, mountBase).then((frameUrl) => {
						if (seq !== htmlRenderSeq) return; // superseded by a newer edit
						if (frameUrl) {
							vm.previewSrcDoc = '';
							vm.previewIframeUrl = frameUrl;
						} else {
							vm.previewIframeUrl = '';
							vm.previewSrcDoc = patched;
						}
					});
					return;
				}
				vm.previewIframeUrl = '';
				vm.previewSrcDoc = patched;
				return;
			}
		},
		// Preview mount prefix for the active file, e.g.
		// `/bin/cms.cgi/{workspace}/content/public`. Empty when the document root
		// is unknown (older server, or a file outside any declared site root), in
		// which case service-worker rewriting is skipped and only relative assets
		// resolve.
		previewMountBase(): string {
			if (!this.documentRoot || !this.workspace) return '';
			return `/bin/cms.cgi/${this.workspace}${this.documentRoot}`;
		},
		refreshServerPreview() {
			const vm = this;
			if (!vm.isServerRendered) return;
			if (!vm.filePath) {
				vm.previewError = vm.t('app.text-editor-preview.error.notSaved', undefined, 'File must be saved before preview is available.');
				vm.previewLoading = false;
				return;
			}
			if (!vm.workspace) {
				vm.previewError = vm.t('app.text-editor-preview.error.noWorkspace', undefined, 'Workspace is not available.');
				vm.previewLoading = false;
				return;
			}

			// Templated files are rendered for a chosen output extension (the
			// server selects the template by request extension). Scriptable
			// files (e.g. .gsp) are evaluated at their own path, so the request
			// targets the file directly without appending an extension.
			let url: string;
			if (vm.isTemplated) {
				let ext = (vm.previewExtension || 'html').trim();
				if (!ext) {
					vm.previewError = vm.t('app.text-editor-preview.error.enterExtension', undefined, 'Enter a file extension (e.g. html).');
					vm.previewLoading = false;
					return;
				}
				if (!ext.startsWith('.')) ext = '.' + ext;
				url = `/bin/cms.cgi/${vm.workspace}${vm.filePath}${ext}`;
			} else {
				url = `/bin/cms.cgi/${vm.workspace}${vm.filePath}`;
			}

			// Rendering a different file than the one currently shown: drop the
			// stale iframe so we don't display another file's output while the
			// new render is in flight. Edits to the same file keep their render
			// in place so the live update is seamless rather than flickering.
			if (vm.filePath !== vm.renderedFilePath && vm.previewIframeUrl) {
				try { URL.revokeObjectURL(vm.previewIframeUrl); } catch { /* ignore */ }
				vm.previewIframeUrl = '';
				vm.renderedFilePath = '';
			}

			// Supersede any in-flight render; only the latest content wins.
			if (previewAbort) {
				try { previewAbort.abort(); } catch { /* ignore */ }
			}
			const controller = new AbortController();
			previewAbort = controller;
			const seq = ++renderSeq;

			vm.previewLoading = true;
			vm.previewError = '';
			vm.previewSrcDoc = '';

			const targetPath = vm.filePath;
			const body = JSON.stringify({ content: vm.content || '' });

			fetch(url, {
				method: 'POST',
				credentials: 'same-origin',
				headers: { 'Content-Type': 'application/vnd.cms.preview+json; charset=UTF-8' },
				body,
				signal: controller.signal,
			}).then(async (resp) => {
				if (!resp.ok) {
					const text = await resp.text().catch(() => '');
					throw new Error(text || vm.t('app.text-editor-preview.error.renderFailed', { status: resp.status, statusText: resp.statusText }, `Render failed: ${resp.status} ${resp.statusText}`));
				}
				const contentType = resp.headers.get('content-type') || '';
				const isHtml = /\b(text\/html|application\/xhtml\+xml)\b/i.test(contentType);
				if (isHtml) {
					const html = await resp.text();
					const absoluteBase = new URL(url, window.location.href).href;
					const patched = injectBaseTag(html, absoluteBase);
					if (seq !== renderSeq) return;
					// Prefer the service-worker frame so the rendered page's
					// site-root-absolute assets (CSS, images, scripts, runtime
					// fetches) resolve to the preview mount exactly as published.
					const frameUrl = await swRenderFrame(vm.previewKey, patched, vm.previewMountBase());
					if (seq !== renderSeq) return;
					if (vm.previewIframeUrl && vm.previewIframeUrl.startsWith('blob:')) {
						try { URL.revokeObjectURL(vm.previewIframeUrl); } catch { /* ignore */ }
					}
					// Fall back to a blob: document when no worker controls us;
					// relative assets still resolve via the injected <base>.
					vm.previewIframeUrl = frameUrl
						|| URL.createObjectURL(new Blob([patched], { type: contentType || 'text/html; charset=UTF-8' }));
					vm.renderedFilePath = targetPath;
					vm.previewError = '';
					return;
				}
				// Non-HTML output (e.g. .rss/.json) has no subresources to rewrite.
				const blob = await resp.blob();
				// A newer render started while we awaited the response — discard
				// this one so the preview reflects the latest content.
				if (seq !== renderSeq) return;
				if (vm.previewIframeUrl && vm.previewIframeUrl.startsWith('blob:')) {
					try { URL.revokeObjectURL(vm.previewIframeUrl); } catch { /* ignore */ }
				}
				vm.previewIframeUrl = URL.createObjectURL(blob);
				vm.renderedFilePath = targetPath;
				vm.previewError = '';
			}).catch((e: any) => {
				if (e?.name === 'AbortError') return; // superseded — ignore
				if (seq !== renderSeq) return;
				vm.previewError = e?.message || String(e) || vm.t('app.text-editor-preview.error.renderGeneric', undefined, 'Failed to render preview.');
			}).finally(() => {
				if (seq === renderSeq) vm.previewLoading = false;
				if (previewAbort === controller) previewAbort = null;
			});
		},
		// ---- Templated output-extension chooser (click-to-edit) ----
		startExtEdit() {
			const vm = this;
			if (!vm.isTemplated) return;
			vm.extInput = vm.previewExtension || '';
			vm.extEditing = true;
			vm.$nextTick(() => {
				const input = vm.$refs.extEditorInput as HTMLInputElement | undefined;
				if (input) {
					input.focus();
					input.select();
				}
			});
		},
		confirmExtEdit() {
			const vm = this;
			// Tolerate leading dots / stray whitespace ("  .json" → "json").
			const ext = (vm.extInput || '').trim().replace(/^\.+/, '').trim();
			if (!ext) {
				vm.cancelExtEdit();
				return;
			}
			vm.extEditing = false;
			vm.extInput = '';
			vm.previewExtension = ext;
			// Persist on the editor side so the choice survives tab switches
			// (the editor seeds future preview-state messages from this).
			previewChannel?.postMessage({
				type: 'preview-set-extension',
				previewKey: vm.previewKey,
				filePath: vm.filePath,
				extension: ext,
			});
			// Confirming always refreshes, per the editor's preview contract.
			vm.refreshServerPreview();
		},
		cancelExtEdit() {
			this.extEditing = false;
			this.extInput = '';
		},
		handleExtKeydown(e: KeyboardEvent) {
			if (e.key === 'Enter') {
				e.preventDefault();
				this.confirmExtEdit();
			} else if (e.key === 'Escape') {
				e.preventDefault();
				this.cancelExtEdit();
			}
		},
	},
};

import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');

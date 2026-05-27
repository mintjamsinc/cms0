import { ApplicationInstance } from "../../services/webtop-service.js";

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

// Inject a <base href> so relative links/assets in the preview resolve
// against the file's real CMS location instead of the webtop app URL.
// Both the srcdoc (plain HTML) and blob: (templated) iframes otherwise
// resolve relative paths against the embedding document, breaking assets.
// Any existing <base> in the source is stripped so ours always wins.
function injectBaseTag(html: string, absoluteBaseUrl: string): string {
	const baseTag = `<base href="${absoluteBaseUrl.replace(/"/g, '&quot;')}">`;
	const stripped = html.replace(/<base\b[^>]*\/?>/gi, '');
	if (/<head\b[^>]*>/i.test(stripped)) {
		return stripped.replace(/<head\b[^>]*>/i, (m) => m + baseTag);
	}
	if (/<html\b[^>]*>/i.test(stripped)) {
		return stripped.replace(/<html\b[^>]*>/i, (m) => `${m}<head>${baseTag}</head>`);
	}
	return `<head>${baseTag}</head>${stripped}`;
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
		};
	},
	methods: {
		async onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance, options?: LaunchOptions) => {
				vm.instance = vm.$markRaw(instance);
				vm.previewKey = options?.previewKey || '';

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				vm.instance.windowTitle = 'Preview';
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
			vm.fileName = msg.fileName || '';
			vm.mimeType = msg.mimeType || 'text/plain';
			vm.content = msg.content || '';
			vm.filePath = msg.filePath || '';
			vm.workspace = msg.workspace || '';
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
				vm.instance.windowTitle = vm.fileName ? `Preview — ${vm.fileName}` : 'Preview';
				vm.instance.setDisplayInfo({ subtitle: vm.fileName });
			}

			vm.updatePreview();
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
					vm.previewHtml = '<p class="text-danger">Error rendering preview</p>';
				}
				return;
			}

			vm.previewHtml = '';

			if (vm.isHtml) {
				const content = vm.content || '';
				// Resolve relative paths against the file's CMS location. The
				// file is unsaved when filePath/workspace are missing, so there
				// is no meaningful base to point at — leave content untouched.
				if (content && vm.filePath && vm.workspace) {
					const fileUrl = new URL(
						`/bin/cms.cgi/${vm.workspace}${vm.filePath}`,
						window.location.href,
					).href;
					vm.previewSrcDoc = injectBaseTag(content, fileUrl);
				} else {
					vm.previewSrcDoc = content;
				}
				return;
			}
		},
		refreshServerPreview() {
			const vm = this;
			if (!vm.isServerRendered) return;
			if (!vm.filePath) {
				vm.previewError = 'File must be saved before preview is available.';
				vm.previewLoading = false;
				return;
			}
			if (!vm.workspace) {
				vm.previewError = 'Workspace is not available.';
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
					vm.previewError = 'Enter a file extension (e.g. html).';
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
					throw new Error(text || `Render failed: ${resp.status} ${resp.statusText}`);
				}
				const contentType = resp.headers.get('content-type') || '';
				const isHtml = /\b(text\/html|application\/xhtml\+xml)\b/i.test(contentType);
				let blob: Blob;
				if (isHtml) {
					const html = await resp.text();
					const absoluteBase = new URL(url, window.location.href).href;
					const patched = injectBaseTag(html, absoluteBase);
					blob = new Blob([patched], { type: contentType || 'text/html; charset=UTF-8' });
				} else {
					blob = await resp.blob();
				}
				// A newer render started while we awaited the response — discard
				// this one so the preview reflects the latest content.
				if (seq !== renderSeq) return;
				if (vm.previewIframeUrl) {
					try { URL.revokeObjectURL(vm.previewIframeUrl); } catch { /* ignore */ }
				}
				vm.previewIframeUrl = URL.createObjectURL(blob);
				vm.renderedFilePath = targetPath;
				vm.previewError = '';
			}).catch((e: any) => {
				if (e?.name === 'AbortError') return; // superseded — ignore
				if (seq !== renderSeq) return;
				vm.previewError = e?.message || String(e) || 'Failed to render preview.';
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
				const input = document.querySelector('.preview-ext-editor-input') as HTMLInputElement | null;
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

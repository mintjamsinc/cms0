// wt-window custom element
import { defineComponent } from '@mintjamsinc/ichigojs';
import { BUILD_VERSION } from '../utils/build-version.js';
import { translate, createLocalizationSnapshot } from '../composables/use-localization.js';

let zIndexSeed = 1;

const WINDOW_SIZE_KEY = 'window.size';
const FALLBACK_MIN_WIDTH = 200;
const FALLBACK_MIN_HEIGHT = 200;
const FALLBACK_DEFAULT_WIDTH = 600;
const FALLBACK_DEFAULT_HEIGHT = 400;

// Stored in IndexedDB (settings store) under key {userID}/{appID}/window.size
// to match how every other per-user/per-app preference is persisted.
// See WebtopDatabase.getUserSetting / setUserSetting.
export async function loadSavedWindowSize(appId: string | undefined): Promise<{ width: number; height: number } | null> {
	if (!appId) return null;
	try {
		const api = (window as any).Webtop?.api;
		const db = api?.db;
		if (!db) return null;
		const userID = api?.context?.currentUser?.id || '*';
		const value = await db.getUserSetting(userID, appId, WINDOW_SIZE_KEY);
		if (value && typeof value.width === 'number' && typeof value.height === 'number') {
			return { width: value.width, height: value.height };
		}
	} catch { /* ignore */ }
	return null;
}

async function saveWindowSize(appId: string | undefined, width: number, height: number): Promise<void> {
	if (!appId) return;
	try {
		const api = (window as any).Webtop?.api;
		const db = api?.db;
		if (!db) return;
		const userID = api?.context?.currentUser?.id || '*';
		await db.setUserSetting(userID, appId, WINDOW_SIZE_KEY, { width, height });
	} catch { /* ignore */ }
}

defineComponent('wt-window', {
	template: '#wt-window',
	// `localization` is the shell's reactive snapshot, passed down so window
	// chrome strings localize and repaint on language change (ichigojs
	// propagates nested mutations of the received proxy to our bindings).
	props: ['appInstance', 'localization'],
	emits: ['window-activated', 'window-deactivated', 'window-maximize-changed', 'window-minimize-changed', 'app-instance-closed'],
	data(this: any) {
		const ai = this.$markRaw(this.appInstance);
		const initialState = (this.appInstance as any)?._initialWindowState;
		const app = ai?.app;
		const minW = app?.minimumWidth || FALLBACK_MIN_WIDTH;
		const minH = app?.minimumHeight || FALLBACK_MIN_HEIGHT;

		// Width/height resolution priority:
		//   1. _initialWindowState (session restore, or saved size pre-loaded
		//      by the launcher in index.ts) — explicit user intent
		//   2. app.yml minimumWidth/minimumHeight (first-ever launch)
		//   3. fallback defaults
		// In all cases we clamp to >= the app's configured minimum and to the
		// current viewport so the window can't open larger than the desktop.
		let initWidth: number;
		let initHeight: number;
		if (typeof initialState?.width === 'number' && typeof initialState?.height === 'number') {
			initWidth = initialState.width;
			initHeight = initialState.height;
		} else if (app?.minimumWidth && app?.minimumHeight) {
			initWidth = app.minimumWidth;
			initHeight = app.minimumHeight;
		} else {
			initWidth = FALLBACK_DEFAULT_WIDTH;
			initHeight = FALLBACK_DEFAULT_HEIGHT;
		}
		initWidth = Math.min(Math.max(initWidth, minW), window.innerWidth);
		initHeight = Math.min(Math.max(initHeight, minH), window.innerHeight);

		return {
			// Non-reactive private state grouped under a single raw object
			_: this.$markRaw({
				originalAppInstance: ai,
				appInstanceId: ai?.id,
				app: ai?.app,
				dragOverlay: null as HTMLElement | null,
				mouseMoveHandler: null as ((e: MouseEvent) => void) | null,
				mouseUpHandler: null as ((e: MouseEvent) => void) | null,
				loadListener: null as (() => void) | null,
				focusInListener: null as (() => void) | null,
				iframeDragMouseDownListener: null as ((e: MouseEvent) => void) | null,
				iframeDragDblClickListener: null as ((e: MouseEvent) => void) | null,
			}),
			appInstance: ai,
			app: ai?.app,
			isLaunched: false,
			isLaunchFailed: false,
			x: initialState?.x ?? 100,
			y: initialState?.y ?? 100,
			width: initWidth,
			height: initHeight,
			maximized: false,
			minimized: false,
			peekHidden: false,
			zIndex: zIndexSeed++,
			dragInfo: null as any,
			resizeInfo: null as any,
			prevRect: null as any,
			theme: document.documentElement.dataset.theme || 'light',
		};
	},
	computed: {
		windowStyle() {
			if ((this as any).minimized) {
				return { display: 'none' };
			}
			const style: Record<string, string> = {
				left: (this as any).maximized ? '0' : (this as any).x + 'px',
				top: (this as any).maximized ? '0' : (this as any).y + 'px',
				width: (this as any).maximized ? '100%' : (this as any).width + 'px',
				height: (this as any).maximized ? '100%' : (this as any).height + 'px',
				'z-index': (this as any).zIndex,
			};
			if ((this as any).peekHidden) {
				style.visibility = 'hidden';
			}
			return style;
		},
		title() {
			return (this as any).appInstance?.windowTitle || '';
		},
		iconURL() {
			const app = (this as any)._.app;
			return app?.icon ? `./apps/${app.relPath}/${app.icon}?v=${BUILD_VERSION}` : '';
		},
		contentURL() {
			const app = (this as any)._.app;
			return app ? `./apps/${app.relPath}/index.html?v=${BUILD_VERSION}` : '';
		},
		contentFrameStyle() {
			return {
				pointerEvents: ((this as any).dragInfo || (this as any).resizeInfo) ? 'none' : undefined,
			};
		},
	},
	methods: {
		/**
		 * Reactive i18n lookup for window chrome. Reads the `localization` prop
		 * snapshot (passed from the shell) so strings repaint on language change.
		 * Falls back to an empty snapshot before the prop is wired.
		 */
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			const vm = this as any;
			return translate(vm.localization || createLocalizationSnapshot(), vm.appInstance, messageId, params, fallback);
		},
		activate() {
			const vm = this as any;
			vm.zIndex = ++zIndexSeed;
			vm.syncSessionState();
			vm.$emit('window-activated', { id: vm._.appInstanceId }, { target: document });
		},
		syncSessionState() {
			const vm = this as any;
			const state = vm.maximized && vm.prevRect
				? { x: vm.prevRect.x, y: vm.prevRect.y, width: vm.prevRect.width, height: vm.prevRect.height, zIndex: vm.zIndex, maximized: true }
				: { x: vm.x, y: vm.y, width: vm.width, height: vm.height, zIndex: vm.zIndex, maximized: vm.maximized || false };
			vm._.originalAppInstance._sessionState = state;
		},
		persistWindowSize() {
			const vm = this as any;
			// Persist the un-maximized size so the next launch restores the
			// user's preferred working size, not a transient maximized state.
			const w = vm.maximized && vm.prevRect ? vm.prevRect.width : vm.width;
			const h = vm.maximized && vm.prevRect ? vm.prevRect.height : vm.height;
			saveWindowSize(vm._.app?.id, w, h);
		},
		createOverlay(cursor?: string) {
			const vm = this as any;
			if (vm._.dragOverlay) return;
			const overlay = document.createElement('div');
			overlay.className = 'drag-overlay';
			if (cursor) {
				overlay.style.cursor = cursor;
			}
			vm._.mouseMoveHandler = (e: MouseEvent) => {
				if (vm.dragInfo) {
					const dx = e.clientX - vm.dragInfo.startX;
					const dy = e.clientY - vm.dragInfo.startY;
					vm.x = vm.dragInfo.x + dx;
					vm.y = Math.max(0, vm.dragInfo.y + dy);
					return;
				}
				if (vm.resizeInfo) {
					const dx = e.clientX - vm.resizeInfo.startX;
					const dy = e.clientY - vm.resizeInfo.startY;
					const dir = vm.resizeInfo.dir;
					const minW = vm._.app?.minimumWidth || FALLBACK_MIN_WIDTH;
					const minH = vm._.app?.minimumHeight || FALLBACK_MIN_HEIGHT;
					if (dir.includes('right')) { vm.width = Math.max(minW, vm.resizeInfo.width + dx); }
					if (dir.includes('left')) {
						const newWidth = Math.max(minW, vm.resizeInfo.width - dx);
						vm.x = vm.resizeInfo.x + (vm.resizeInfo.width - newWidth);
						vm.width = newWidth;
					}
					if (dir.includes('bottom')) { vm.height = Math.max(minH, vm.resizeInfo.height + dy); }
					if (dir.includes('top')) {
						const newHeight = Math.max(minH, vm.resizeInfo.height - dy);
						vm.y = Math.max(0, vm.resizeInfo.y + (vm.resizeInfo.height - newHeight));
						vm.height = newHeight;
					}
				}
			};
			vm._.mouseUpHandler = () => {
				const wasResize = !!vm.resizeInfo;
				vm.dragInfo = null;
				vm.resizeInfo = null;
				if (wasResize) {
					try { vm._.frame?.contentDocument?.documentElement.classList.remove('wt-window-resizing'); } catch (_) { /* cross-origin */ }
				}
				vm.removeOverlay();
				if (wasResize) vm.persistWindowSize();
			};
			overlay.addEventListener('mousemove', vm._.mouseMoveHandler);
			overlay.addEventListener('mouseup', vm._.mouseUpHandler);
			document.body.appendChild(overlay);
			vm._.dragOverlay = overlay;
		},
		removeOverlay() {
			const vm = this as any;
			if (!vm._.dragOverlay) return;
			vm._.dragOverlay.removeEventListener('mousemove', vm._.mouseMoveHandler);
			vm._.dragOverlay.removeEventListener('mouseup', vm._.mouseUpHandler);
			document.body.removeChild(vm._.dragOverlay);
			vm._.dragOverlay = null;
			vm._.mouseMoveHandler = null;
			vm._.mouseUpHandler = null;
			vm.syncSessionState();
		},
		startDrag(event: MouseEvent) {
			const vm = this as any;
			vm.activate();
			if ((event.target as HTMLElement).closest('.window-icon')) return;
			if (event.detail > 1) return;
			vm.dragInfo = { x: vm.x, y: vm.y, startX: event.clientX, startY: event.clientY };
			vm.createOverlay();
		},
		// Programmatic drag start (used when an iframe child-element is grabbed)
		startDragAt(clientX: number, clientY: number) {
			const vm = this as any;
			vm.activate();
			if (vm.maximized) return;
			vm.dragInfo = { x: vm.x, y: vm.y, startX: clientX, startY: clientY };
			vm.createOverlay();
		},
		startResize(dir: string, event: MouseEvent) {
			const vm = this as any;
			vm.activate();
			vm.resizeInfo = { dir, x: vm.x, y: vm.y, width: vm.width, height: vm.height, startX: event.clientX, startY: event.clientY };
			// Mark the iframe document so the app can react to resizing (mirrors the
			// wt-window-dragging behavior used while moving the window). Also disables
			// pointer events on nested iframes for the duration of the resize.
			try { vm._.frame?.contentDocument?.documentElement.classList.add('wt-window-resizing'); } catch (_) { /* cross-origin */ }
			const cursor = window.getComputedStyle(event.target as HTMLElement).cursor;
			vm.createOverlay(cursor);
		},
		toggleMaximize() {
			const vm = this as any;
			if (!vm.maximized) {
				vm.prevRect = { x: vm.x, y: vm.y, width: vm.width, height: vm.height };
				vm.x = 0; vm.y = 0;
				vm.width = window.innerWidth;
				vm.height = window.innerHeight;
				vm.maximized = true;
			} else {
				if (vm.prevRect) {
					vm.x = vm.prevRect.x; vm.y = vm.prevRect.y;
					vm.width = vm.prevRect.width; vm.height = vm.prevRect.height;
				}
				vm.maximized = false;
			}
			vm.syncSessionState();
			vm.notifyMaximizeState();
		},
		minimize() {
			const vm = this as any;
			vm.minimized = true;
			vm.$emit('window-deactivated', { id: vm._.appInstanceId }, { target: document });
			vm.notifyMaximizeState();
			vm.notifyMinimizeState();
		},
		notifyMaximizeState() {
			const vm = this as any;
			vm.$emit('window-maximize-changed', { id: vm._.appInstanceId, maximized: vm.maximized && !vm.minimized }, { target: document });
		},
		notifyMinimizeState() {
			const vm = this as any;
			vm.$emit('window-minimize-changed', { id: vm._.appInstanceId, minimized: vm.minimized }, { target: document });
		},
		async close() {
			const vm = this as any;
			const canClose = await vm._.originalAppInstance.canClose();
			if (!canClose) return;
			vm.$emit('app-instance-closed', { id: vm._.appInstanceId }, { target: document });
		},
		onMounted($ctx: any) {
			const vm = this as any;
			vm.activate();

			const frame = ($ctx.element as HTMLElement).querySelector('iframe') as HTMLIFrameElement | null;
			vm._.frame = frame;
			if (frame) {
				vm._.focusInListener = () => { vm.activate(); };

				let frameLoadHandled = false;
				vm._.loadListener = () => {
					if (frameLoadHandled) return;
					frameLoadHandled = true;

					let retries = 100;
					const callLaunch = () => {
						const func = frame.contentWindow?.appLaunch;
						if (typeof func !== 'function') {
							if (--retries <= 0) {
								console.error('appLaunch not found after waiting. The application could not be started.');
								return;
							}
							setTimeout(callLaunch, 100);
							return;
						}
						frame.removeEventListener('load', vm._.loadListener);
						vm._.loadListener = null;

						try {
							frame.contentDocument?.addEventListener('mousedown', vm._.focusInListener, true);
						} catch (_) { /* cross-origin */ }

						// Drag region support: any element with `.window-drag-region` inside
						// the iframe acts as a window drag handle. Clicks on interactive
						// children (button, input, etc.) are excluded.
						// Note: mousemove/mouseup are bound on BOTH the iframe document and
						// the parent document because mouse-capture stays within whichever
						// document received the original mousedown.
						const isInteractiveTarget = (el: HTMLElement | null) => {
							return !!el && !!el.closest('button, a, input, textarea, select, [contenteditable="true"], [role="button"], [role="textbox"]');
						};
						vm._.iframeDragMouseDownListener = (e: MouseEvent) => {
							if (e.button !== 0) return;
							const target = e.target as HTMLElement;
							if (!target.closest('.window-drag-region')) return;
							if (isInteractiveTarget(target)) return;
							if (e.detail > 1) return;
							if (vm.maximized) return;

							vm.activate();
							// preventDefault below would block focus from transferring to
							// the iframe, which would silently break in-iframe keyboard
							// shortcuts (Delete, Escape, Ctrl+I, ...). Force focus first.
							try { frame.contentWindow?.focus(); } catch (_) { /* cross-origin */ }
							// Disable pointer events on nested iframes inside the app
							// (e.g. OSGi Console's Felix iframe) for the duration of the
							// drag. Without this, mouse-capture transfers to the nested
							// iframe as soon as the cursor crosses it and the drag stalls
							// or hangs. See html.wt-window-dragging rule in webtop-app.css.
							try { frame.contentDocument?.documentElement.classList.add('wt-window-dragging'); } catch (_) { /* cross-origin */ }
							const rect0 = frame.getBoundingClientRect();
							const startPageX = e.clientX + rect0.left;
							const startPageY = e.clientY + rect0.top;
							const startWinX = vm.x;
							const startWinY = vm.y;

							const updateFromIframe = (ev: MouseEvent) => {
								const r = frame.getBoundingClientRect();
								const cx = ev.clientX + r.left;
								const cy = ev.clientY + r.top;
								vm.x = startWinX + (cx - startPageX);
								vm.y = Math.max(0, startWinY + (cy - startPageY));
							};
							const updateFromParent = (ev: MouseEvent) => {
								vm.x = startWinX + (ev.clientX - startPageX);
								vm.y = Math.max(0, startWinY + (ev.clientY - startPageY));
							};
							const cleanup = () => {
								try {
									frame.contentDocument?.removeEventListener('mousemove', updateFromIframe, true);
									frame.contentDocument?.removeEventListener('mouseup', cleanup, true);
									frame.contentDocument?.documentElement.classList.remove('wt-window-dragging');
								} catch (_) { /* cross-origin */ }
								document.removeEventListener('mousemove', updateFromParent, true);
								document.removeEventListener('mouseup', cleanup, true);
								vm.syncSessionState();
							};
							try {
								frame.contentDocument?.addEventListener('mousemove', updateFromIframe, true);
								frame.contentDocument?.addEventListener('mouseup', cleanup, true);
							} catch (_) { /* cross-origin */ }
							document.addEventListener('mousemove', updateFromParent, true);
							document.addEventListener('mouseup', cleanup, true);
							e.preventDefault();
						};
						vm._.iframeDragDblClickListener = (e: MouseEvent) => {
							const target = e.target as HTMLElement;
							if (!target.closest('.window-drag-region')) return;
							if (isInteractiveTarget(target)) return;
							vm.toggleMaximize();
						};
						try {
							frame.contentDocument?.addEventListener('mousedown', vm._.iframeDragMouseDownListener, true);
							frame.contentDocument?.addEventListener('dblclick', vm._.iframeDragDblClickListener, true);
						} catch (_) { /* cross-origin */ }

						const launchOptions = (vm._.originalAppInstance as any).launchOptions;
						func(vm._.originalAppInstance, launchOptions);
					};
					callLaunch();
				};
				frame.addEventListener('load', vm._.loadListener);

				if (frame.contentDocument?.readyState === 'complete') {
					vm._.loadListener();
				}
			}
		},
		// Shell→window broadcasts. Bound declaratively via @event.document in
		// the template so the listeners are removed automatically on unmount.
		onAppLaunched(e: CustomEvent) {
			const vm = this as any;
			if (e.detail.id === vm._.appInstanceId) { vm.isLaunched = true; }
		},
		onThemeChanged(e: CustomEvent) {
			(this as any).theme = e.detail.theme;
		},
		onRestoreWindow(e: CustomEvent) {
			const vm = this as any;
			if (e.detail.id === vm._.appInstanceId) {
				const wasMinimized = vm.minimized;
				vm.minimized = false;
				vm.activate();
				vm.notifyMaximizeState();
				if (wasMinimized) vm.notifyMinimizeState();
			}
		},
		onWindowPeekSet(e: CustomEvent) {
			const vm = this as any;
			const peekedId = e.detail?.id;
			vm.peekHidden = peekedId && peekedId !== vm._.appInstanceId;
		},
		onWindowPeekClear() {
			(this as any).peekHidden = false;
		},
		onWindowControl(e: CustomEvent) {
			const vm = this as any;
			if (e.detail?.id !== vm._.appInstanceId) return;
			switch (e.detail.action) {
				case 'maximize':
					if (!vm.maximized) { vm.toggleMaximize(); }
					break;
				case 'minimize':
					vm.minimize();
					break;
				case 'restore':
					if (vm.maximized) { vm.toggleMaximize(); }
					if (vm.minimized) {
						vm.minimized = false;
						vm.activate();
						vm.notifyMaximizeState();
						vm.notifyMinimizeState();
					}
					break;
				case 'toggle-maximize':
					vm.toggleMaximize();
					break;
				case 'close':
					vm.close();
					break;
			}
		},
		onUnmount($ctx: any) {
			const vm = this as any;
			const frame = ($ctx.element as HTMLElement).querySelector('iframe') as HTMLIFrameElement | null;
			if (frame) {
				if (vm._.loadListener) {
					frame.removeEventListener('load', vm._.loadListener);
					vm._.loadListener = null;
				}
				if (vm._.focusInListener) {
					try {
						frame.contentDocument?.removeEventListener('mousedown', vm._.focusInListener, true);
					} catch (_) { /* cross-origin */ }
					vm._.focusInListener = null;
				}
				if (vm._.iframeDragMouseDownListener) {
					try {
						frame.contentDocument?.removeEventListener('mousedown', vm._.iframeDragMouseDownListener, true);
					} catch (_) { /* cross-origin */ }
					vm._.iframeDragMouseDownListener = null;
				}
				if (vm._.iframeDragDblClickListener) {
					try {
						frame.contentDocument?.removeEventListener('dblclick', vm._.iframeDragDblClickListener, true);
					} catch (_) { /* cross-origin */ }
					vm._.iframeDragDblClickListener = null;
				}
			}
			vm.removeOverlay();
		},
	},
	});


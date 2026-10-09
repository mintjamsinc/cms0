// wt-widget custom element
//
// One desktop widget placement: a frameless iframe showing the widget page of
// an app (app.yml `widgets:`), placed on the desktop outside any window. The
// shell owns the placements (services/widget-store.ts) and passes each one
// in; this component renders it, lets the user move / resize it, and runs the
// widget page's `window.widgetLaunch(widget)`.
//
// Layers are z-index bands, not containers: an iframe moved to another parent
// reloads, so switching a placement between desktop and pinned only changes
// the z-index the shell passes in (`z`).
import { defineComponent } from '@mintjamsinc/ichigojs';
import { BUILD_VERSION } from '../utils/build-version.js';
import { translate, createLocalizationSnapshot } from '../composables/use-localization.js';
import { attachFrameDragRegion } from '../lib/frame-drag.js';
import { WidgetInstance, type Application, type AppWidget, type WidgetLayer } from '../services/webtop-service.js';
import { placementRect, type WidgetRect } from '../services/widget-store.js';

const FALLBACK_MIN_SIZE = 80;

defineComponent('wt-widget', {
	template: '#wt-widget',
	props: ['placement', 'z', 'desktopWidth', 'desktopHeight', 'highlighted', 'localization'],
	emits: ['widget-activated', 'widget-moved', 'widget-removed', 'webtop-widget-menu'],
	data(this: any) {
		const p = this.placement;
		const app: Application | undefined = (window.Webtop?.apps || []).find((a: Application) => a.id === p?.appId);
		const def: AppWidget | undefined = app?.widgets.find((w) => w.identifier === p?.widget);
		const instance = app && def ? new WidgetInstance(app, def, p.id, p.layer, window.Webtop) : null;
		return {
			_: this.$markRaw({
				instance,
				app,
				def,
				frame: null as HTMLIFrameElement | null,
				loadListener: null as (() => void) | null,
				frameDragTeardown: null as (() => void) | null,
				frameMouseDownListener: null as (() => void) | null,
				frameDoc: null as Document | null,
				windowFocusInListener: null as ((e: FocusEvent) => void) | null,
				dragOverlay: null as HTMLElement | null,
				overlayMove: null as ((e: MouseEvent) => void) | null,
				overlayUp: null as (() => void) | null,
			}),
			// While the user moves or resizes, the live rectangle; the
			// placement itself changes only when the gesture ends.
			liveRect: null as WidgetRect | null,
			isLaunched: false,
			isLaunchFailed: false,
			theme: document.documentElement.dataset.theme || 'light',
		};
	},
	computed: {
		rect(): WidgetRect {
			const vm = this as any;
			return vm.liveRect || placementRect(vm.placement, vm.desktopWidth || 0, vm.desktopHeight || 0);
		},
		widgetStyle() {
			const vm = this as any;
			const r = vm.rect;
			return {
				left: r.x + 'px',
				top: r.y + 'px',
				width: r.width + 'px',
				height: r.height + 'px',
				'z-index': String(vm.z || 0),
			};
		},
		contentURL() {
			const vm = this as any;
			const { app, def } = vm._;
			return app && def ? `./apps/${app.relPath}/${def.entry}?v=${BUILD_VERSION}` : '';
		},
		frameStyle() {
			const vm = this as any;
			return {
				pointerEvents: vm.liveRect ? 'none' : undefined,
				// A document whose color-scheme differs from its iframe's gets an
				// opaque backdrop; match the page (webtop-app.css follows data-theme).
				colorScheme: vm.theme === 'dark' ? 'dark' : 'light',
			};
		},
		resizable() {
			return !!(this as any)._.def?.resizable;
		},
	},
	methods: {
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			const vm = this as any;
			return translate(vm.localization || createLocalizationSnapshot(), window.Webtop, messageId, params, fallback);
		},
		activate() {
			const vm = this as any;
			vm.$emit('widget-activated', { id: vm.placement.id }, { target: document });
		},
		openMenu(event: MouseEvent) {
			const vm = this as any;
			vm.activate();
			vm.$emit('webtop-widget-menu', { id: vm.placement.id, x: event.clientX, y: event.clientY }, { target: document });
		},
		// Move and resize run over a full-page overlay, as windows do, so the
		// pointer is not captured by an iframe it passes over.
		withOverlay(cursor: string, onMove: (e: MouseEvent) => void, onUp: () => void) {
			const vm = this as any;
			const overlay = document.createElement('div');
			overlay.className = 'drag-overlay';
			overlay.style.cursor = cursor;
			vm._.overlayMove = onMove;
			vm._.overlayUp = () => {
				vm.removeOverlay();
				onUp();
			};
			overlay.addEventListener('mousemove', vm._.overlayMove);
			overlay.addEventListener('mouseup', vm._.overlayUp);
			document.body.appendChild(overlay);
			vm._.dragOverlay = overlay;
		},
		removeOverlay() {
			const vm = this as any;
			const overlay = vm._.dragOverlay;
			if (!overlay) return;
			overlay.removeEventListener('mousemove', vm._.overlayMove);
			overlay.removeEventListener('mouseup', vm._.overlayUp);
			overlay.remove();
			vm._.dragOverlay = null;
			vm._.overlayMove = null;
			vm._.overlayUp = null;
		},
		moveTo(start: WidgetRect, dx: number, dy: number) {
			const vm = this as any;
			const maxX = Math.max(0, (vm.desktopWidth || 0) - start.width);
			const maxY = Math.max(0, (vm.desktopHeight || 0) - start.height);
			vm.liveRect = {
				...start,
				x: Math.max(0, Math.min(start.x + dx, maxX)),
				y: Math.max(0, Math.min(start.y + dy, maxY)),
			};
		},
		finishGesture() {
			const vm = this as any;
			const r = vm.liveRect;
			if (!r) return;
			vm.$emit('widget-moved', { id: vm.placement.id, rect: { ...r } }, { target: document });
			// The shell updates the placement synchronously; drop the live
			// rectangle afterwards so the widget does not jump back meanwhile.
			vm.liveRect = null;
		},
		startDrag(event: MouseEvent) {
			const vm = this as any;
			if (event.button !== 0) return;
			vm.activate();
			const start = { ...vm.rect };
			const sx = event.clientX;
			const sy = event.clientY;
			vm.liveRect = start;
			vm.withOverlay('grabbing',
				(e: MouseEvent) => vm.moveTo(start, e.clientX - sx, e.clientY - sy),
				() => vm.finishGesture());
		},
		startResize(event: MouseEvent) {
			const vm = this as any;
			if (event.button !== 0) return;
			vm.activate();
			const def = vm._.def;
			const minW = def?.minWidth || Math.min(def?.width || FALLBACK_MIN_SIZE, FALLBACK_MIN_SIZE);
			const minH = def?.minHeight || Math.min(def?.height || FALLBACK_MIN_SIZE, FALLBACK_MIN_SIZE);
			const start = { ...vm.rect };
			const sx = event.clientX;
			const sy = event.clientY;
			vm.liveRect = start;
			vm.withOverlay('nwse-resize', (e: MouseEvent) => {
				const maxW = Math.max(minW, (vm.desktopWidth || 0) - start.x);
				const maxH = Math.max(minH, (vm.desktopHeight || 0) - start.y);
				vm.liveRect = {
					...start,
					width: Math.max(minW, Math.min(start.width + e.clientX - sx, maxW)),
					height: Math.max(minH, Math.min(start.height + e.clientY - sy, maxH)),
				};
			}, () => vm.finishGesture());
		},
		onMounted($ctx: any) {
			const vm = this as any;
			const instance: WidgetInstance | null = vm._.instance;
			const frame = ($ctx.element as HTMLElement).querySelector('iframe') as HTMLIFrameElement | null;
			vm._.frame = frame;
			if (!frame || !instance) {
				vm.isLaunchFailed = true;
				return;
			}

			// Clicking inside the widget raises it within its layer. Focus
			// crosses frame boundaries, mousedown on the page covers clicks on
			// non-focusable content (see wt-window for the same pair).
			vm._.windowFocusInListener = (e: FocusEvent) => {
				if (e.target === frame) vm.activate();
			};
			document.addEventListener('focusin', vm._.windowFocusInListener, true);

			let handled = false;
			vm._.loadListener = () => {
				if (handled) return;
				handled = true;
				let retries = 100;
				const callLaunch = () => {
					const func = frame.contentWindow?.widgetLaunch;
					if (typeof func !== 'function') {
						if (--retries <= 0) {
							console.error('widgetLaunch not found after waiting. The widget could not be started.');
							vm.isLaunchFailed = true;
							return;
						}
						setTimeout(callLaunch, 100);
						return;
					}
					frame.removeEventListener('load', vm._.loadListener);
					vm._.loadListener = null;

					try {
						const doc = frame.contentDocument;
						if (doc) {
							vm._.frameMouseDownListener = () => vm.activate();
							doc.addEventListener('mousedown', vm._.frameMouseDownListener, true);
							vm._.frameDoc = doc;
						}
					} catch (_) { /* cross-origin */ }

					let start: WidgetRect = { ...vm.rect };
					vm._.frameDragTeardown = attachFrameDragRegion(frame, {
						canStart: () => true,
						onStart: () => {
							vm.activate();
							start = { ...vm.rect };
							vm.liveRect = start;
						},
						onMove: (dx: number, dy: number) => vm.moveTo(start, dx, dy),
						onEnd: () => vm.finishGesture(),
					});

					try {
						const result: any = func(instance);
						if (result && typeof result.then === 'function') {
							result.catch((err: any) => {
								console.error('widgetLaunch failed. The widget could not be started.', err);
								vm.isLaunchFailed = true;
							});
						}
					} catch (err) {
						console.error('widgetLaunch threw. The widget could not be started.', err);
						vm.isLaunchFailed = true;
					}
				};
				callLaunch();
			};
			frame.addEventListener('load', vm._.loadListener);
			if (frame.contentDocument?.readyState === 'complete' && frame.contentWindow?.location.href !== 'about:blank') {
				vm._.loadListener();
			}
		},
		onUnmount() {
			const vm = this as any;
			if (vm._.windowFocusInListener) {
				document.removeEventListener('focusin', vm._.windowFocusInListener, true);
				vm._.windowFocusInListener = null;
			}
			if (vm._.frame && vm._.loadListener) {
				vm._.frame.removeEventListener('load', vm._.loadListener);
				vm._.loadListener = null;
			}
			if (vm._.frameDoc && vm._.frameMouseDownListener) {
				try { vm._.frameDoc.removeEventListener('mousedown', vm._.frameMouseDownListener, true); } catch (_) { /* ignore */ }
			}
			vm._.frameDoc = null;
			vm._.frameMouseDownListener = null;
			if (vm._.frameDragTeardown) {
				vm._.frameDragTeardown();
				vm._.frameDragTeardown = null;
			}
			vm.removeOverlay();
		},
		// Shell → widget broadcasts, bound via @event.document in the template.
		onWidgetLaunched(e: CustomEvent) {
			const vm = this as any;
			if (e.detail?.id === vm.placement.id) vm.isLaunched = true;
		},
		onThemeChanged(e: CustomEvent) {
			(this as any).theme = e.detail.theme;
		},
		async onRemoveRequest(e: CustomEvent) {
			const vm = this as any;
			if (e.detail?.id !== vm.placement.id) return;
			const instance: WidgetInstance | null = vm._.instance;
			if (instance && !(await instance.canRemove())) return;
			vm.$emit('widget-removed', { id: vm.placement.id }, { target: document });
		},
		onLayerApplied(e: CustomEvent) {
			const vm = this as any;
			if (e.detail?.id !== vm.placement.id) return;
			const layer: WidgetLayer = e.detail.layer;
			vm._.instance?.applyLayer(layer);
			try {
				vm._.frame?.contentWindow?.postMessage({ type: 'widget-layer-changed', layer }, window.location.origin);
			} catch (_) { /* ignore */ }
		},
	},
});

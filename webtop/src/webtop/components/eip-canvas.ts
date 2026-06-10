// eip-canvas custom element
//
// A shared, read-only EIP route diagram. Give it Camel XML DSL (typically the
// `route.definition.xml` dumped by the engine) and it renders a faithful,
// auto-laid-out flow diagram — the same visual language as the EIP modeler, but
// non-interactive and self-contained (own pan/zoom, own theme tokens).
//
// Parsing, layout and connection routing are delegated to the shared model
// engine (lib/camel/engine.ts), the very same code the modeler edits with, so
// the two never drift apart.
//
// Props:
//   xml             Camel XML DSL to render. May contain several routes.
//   highlightRouteId When set, dims every node that is not part of that route
//                    and gives the route's own nodes a glow — so a multi-route
//                    file still reads as "this is the route you selected".
//   spriteUrl        Optional override for the node icon sprite; defaults to the
//                    shared components/eip-canvas-icons.svg.
import { defineComponent } from '@mintjamsinc/ichigojs';
import {
	parseModel,
	computeConnectionPaths,
	computeContentBounds,
	getConnectionLabelPosition,
	getShortLabel,
	type ParsedModel,
} from '../lib/camel/engine.js';
import type { CamelProcessorSemantic } from '../lib/camel/model.js';

// Capsule-shaped origins/endpoints (rounded, like the modeler's From/To).
const CAPSULE = new Set(['from', 'to', 'toD', 'onException']);
// Types that have a dedicated icon symbol in eip-canvas-icons.svg.
const ICON_TYPES = new Set([
	'from', 'to', 'toD', 'log', 'setBody', 'setHeader', 'choice', 'filter', 'split',
	'delay', 'bean', 'stop', 'merge', 'onException', 'doTry', 'throttle', 'aggregate',
	'marshal', 'unmarshal', 'transform', 'multicast', 'recipientList', 'loop', 'wireTap',
	'enrich', 'pollEnrich', 'threads', 'circuitBreaker',
]);
// Types with a dedicated colour in eip-canvas.css; others fall back to .generic.
const COLORED = new Set([
	'from', 'to', 'toD', 'log', 'setBody', 'setHeader', 'transform', 'choice', 'filter',
	'split', 'merge', 'marshal', 'unmarshal', 'delay', 'throttle', 'bean', 'onException',
	'doTry', 'circuitBreaker', 'stop', 'aggregate', 'multicast', 'recipientList', 'loop',
	'wireTap', 'enrich', 'pollEnrich', 'threads',
]);
// Display titles that don't follow plain camelCase→Title Case.
const TITLES: Record<string, string> = {
	toD: 'To D', setBody: 'Set Body', setHeader: 'Set Header', onException: 'onException',
	doTry: 'doTry', wireTap: 'WireTap', pollEnrich: 'PollEnrich', recipientList: 'Recipients',
	circuitBreaker: 'CircuitBreaker',
};

function titleOf(type: string): string {
	if (TITLES[type]) return TITLES[type];
	const spaced = type.replace(/([a-z0-9])([A-Z])/g, '$1 $2');
	return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

interface RenderNode {
	id: string;
	kind: 'capsule' | 'choice' | 'merge' | 'rect';
	bodyClass: string;
	transform: string;
	w: number;
	h: number;
	rx: number;
	iconId: string;
	iconW: number;
	iconH: number;
	title: string;
	titleClass: string;
	titleX: number;
	titleY: number;
	subLabel: string;
	subY: number;
	highlighted: boolean;
}

interface RenderLink {
	flowId: string;
	path: string;
	label: string;
	labelClass: string;
	labelX: number;
	labelY: number;
}

defineComponent('eip-canvas', {
	template: '#eip-canvas',
	props: ['xml', 'highlightRouteId', 'spriteUrl'],
	data(this: any) {
		return {
			version: 0,
			scale: 1,
			panX: 0,
			panY: 0,
			isPanning: false,
			theme: (typeof document !== 'undefined'
				&& document.documentElement.getAttribute('data-theme')) || 'light',
			_: this.$markRaw({
				parsed: null as ParsedModel | null,
				panStart: null as { x: number; y: number } | null,
				moveHandler: null as ((e: MouseEvent) => void) | null,
				upHandler: null as (() => void) | null,
				messageHandler: null as ((e: MessageEvent) => void) | null,
			}),
		};
	},
	computed: {
		spriteHref(this: any): string {
			if (this.spriteUrl) return this.spriteUrl as string;
			try {
				return new URL('../../components/eip-canvas-icons.svg', document.baseURI).href;
			} catch {
				return '';
			}
		},

		viewportTransform(this: any): string {
			return `translate(${this.panX}, ${this.panY}) scale(${this.scale})`;
		},

		isEmpty(this: any): boolean {
			this.version; // reactive dependency
			const p = this._.parsed as ParsedModel | null;
			return !p || p.store.getAllShapes().length === 0;
		},

		// semanticIds belonging to the highlighted route (empty when no scope).
		highlightSet(this: any): Set<string> {
			this.version;
			const p = this._.parsed as ParsedModel | null;
			const id = this.highlightRouteId as string | null;
			if (!p || !id) return new Set();
			const route = p.routes.find(r => r.routeId === id);
			return route ? route.shapeIds : new Set();
		},

		// Glow/dim only earns its keep when the file holds more than one route
		// (so the selected one stands out). A lone route needs no disambiguation.
		hasHighlight(this: any): boolean {
			this.version;
			const p = this._.parsed as ParsedModel | null;
			return !!p && p.routes.length > 1 && this.highlightSet.size > 0;
		},

		nodes(this: any): RenderNode[] {
			this.version;
			const p = this._.parsed as ParsedModel | null;
			if (!p) return [];
			const highlight: Set<string> = this.highlightSet;
			const out: RenderNode[] = [];
			for (const shape of p.store.getAllShapes()) {
				const proc = p.store.getProcessor(shape.semanticId);
				if (!proc) continue;
				const type = proc.type;
				const b = shape.bounds;
				const kind: RenderNode['kind'] = type === 'choice' ? 'choice'
					: type === 'merge' ? 'merge'
					: CAPSULE.has(type) ? 'capsule' : 'rect';

				let rx = 8, iconW = 60, iconH = 60, titleX = 90, titleY = 26, subY = 40;
				let titleClass = 'node-label-light';
				let title = titleOf(type);
				let subLabel = getShortLabel(proc as CamelProcessorSemantic);

				if (kind === 'capsule') {
					rx = 25; iconW = 56; iconH = 50; titleX = 70; titleY = 20; subY = 35;
				} else if (kind === 'choice') {
					iconW = 100; iconH = 60; titleX = 50; titleY = 72; subY = 0;
					titleClass = 'node-label'; subLabel = '';
				} else if (kind === 'merge') {
					iconW = 50; iconH = 50; title = ''; subLabel = '';
				}

				out.push({
					id: shape.id,
					kind,
					bodyClass: COLORED.has(type) ? type : 'generic',
					transform: `translate(${b.x}, ${b.y})`,
					w: b.width,
					h: b.height,
					rx,
					iconId: ICON_TYPES.has(type) ? 'canvas-' + type : '',
					iconW, iconH,
					title,
					titleClass,
					titleX, titleY,
					subLabel,
					subY,
					highlighted: highlight.has(shape.semanticId),
				});
			}
			return out;
		},

		links(this: any): RenderLink[] {
			this.version;
			const p = this._.parsed as ParsedModel | null;
			if (!p) return [];
			const out: RenderLink[] = [];
			for (const conn of computeConnectionPaths(p.store)) {
				const flow = p.store.getFlow(conn.flowId);
				let label = '', labelClass = '';
				if (flow) {
					if (flow.conditionType === 'when') {
						label = (flow.expression || '').substring(0, 15) || 'when';
						labelClass = 'when';
					} else if (flow.conditionType === 'otherwise') {
						label = 'otherwise'; labelClass = 'otherwise';
					} else if (flow.role === 'try') {
						label = 'try'; labelClass = 'try';
					} else if (flow.role === 'catch') {
						const ex = flow.exceptions?.[0]?.split('.').pop() || 'Exception';
						label = 'catch: ' + ex; labelClass = 'catch';
					} else if (flow.role === 'finally') {
						label = 'finally'; labelClass = 'finally';
					}
				}
				const pos = getConnectionLabelPosition(conn.path);
				out.push({ flowId: conn.flowId, path: conn.path, label, labelClass, labelX: pos.x, labelY: pos.y });
			}
			return out;
		},
	},
	watch: {
		xml(this: any) { this.reparse(); },
		highlightRouteId(this: any) { /* recomputed via computed deps */ },
	},
	methods: {
		onMounted(this: any) {
			this.reparse();
			// Track theme changes pushed from the shell (mirrors the modeler).
			this._.messageHandler = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, theme } = event.data || {};
				if (type === 'theme-changed' && theme) this.theme = theme;
			};
			window.addEventListener('message', this._.messageHandler);
		},

		onUnmount(this: any) {
			if (this._.messageHandler) window.removeEventListener('message', this._.messageHandler);
			this.endPan();
		},

		reparse(this: any) {
			const xml = (this.xml as string) || '';
			this._.parsed = xml.trim() ? parseModel(xml) : null;
			this.version++;
			this.$nextTick(() => this.fit());
		},

		container(this: any): HTMLElement | null {
			return document.querySelector('.eip-canvas') as HTMLElement | null;
		},

		// Scale + centre the diagram so it fits the viewport (never magnified
		// past 1:1, so a small route stays its natural size).
		fit(this: any) {
			const p = this._.parsed as ParsedModel | null;
			const el = this.container();
			if (!p || !el) return;
			const bounds = computeContentBounds(p.store);
			if (bounds.width <= 0 || bounds.height <= 0) return;
			const cw = el.clientWidth || 1;
			const ch = el.clientHeight || 1;
			const scale = Math.min(cw / bounds.width, ch / bounds.height, 1);
			this.scale = scale;
			this.panX = (cw - bounds.width * scale) / 2 - bounds.x * scale;
			this.panY = (ch - bounds.height * scale) / 2 - bounds.y * scale;
		},

		// Zoom keeping the given viewport point fixed (defaults to the centre).
		zoomBy(this: any, factor: number, atX?: number, atY?: number) {
			const el = this.container();
			if (!el) return;
			const rect = el.getBoundingClientRect();
			const px = atX ?? rect.width / 2;
			const py = atY ?? rect.height / 2;
			const newScale = Math.min(Math.max(this.scale * factor, 0.2), 2.5);
			this.panX = px - (px - this.panX) * (newScale / this.scale);
			this.panY = py - (py - this.panY) * (newScale / this.scale);
			this.scale = newScale;
		},

		zoomIn(this: any) { this.zoomBy(1.2); },
		zoomOut(this: any) { this.zoomBy(1 / 1.2); },

		onWheel(this: any, event: WheelEvent) {
			const el = this.container();
			if (!el) return;
			const rect = el.getBoundingClientRect();
			this.zoomBy(event.deltaY < 0 ? 1.1 : 0.9, event.clientX - rect.left, event.clientY - rect.top);
		},

		onPanStart(this: any, event: MouseEvent) {
			if (event.button !== 0) return;
			this.isPanning = true;
			this._.panStart = { x: event.clientX - this.panX, y: event.clientY - this.panY };
			this._.moveHandler = (e: MouseEvent) => {
				if (!this._.panStart) return;
				this.panX = e.clientX - this._.panStart.x;
				this.panY = e.clientY - this._.panStart.y;
			};
			this._.upHandler = () => this.endPan();
			document.addEventListener('mousemove', this._.moveHandler);
			document.addEventListener('mouseup', this._.upHandler);
		},

		endPan(this: any) {
			this.isPanning = false;
			this._.panStart = null;
			if (this._.moveHandler) document.removeEventListener('mousemove', this._.moveHandler);
			if (this._.upHandler) document.removeEventListener('mouseup', this._.upHandler);
			this._.moveHandler = null;
			this._.upHandler = null;
		},
	},
});

/**
 * EIP Console
 *
 * Three-pane operations console for Apache Camel routes deployed in the
 * Workspace Integration Engine.
 *
 * Left pane has two modes:
 *   - search  (default): filter / route multi-select / range / status
 *   - manage  : routes navigator with start/stop/suspend/resume actions
 *
 * The chart (centre upper) shows an exchange-count time series banded by
 * elapsed time: under 1s (primary), 1s–5s (warning), 5s+ (danger). It is
 * fed by a JCR XPath `facet accumulate` query — see EipStatsQueryExecutor.
 *
 * The history list (centre lower) is a Relay-style cursor connection; the
 * client accumulates up to 1000 rows by issuing forward pages of 500 each.
 * A 1.75rem status bar pinned to the bottom of the panel surfaces the
 * total hit count and the truncation indicator.
 *
 * GraphQL conventions:
 *   - List queries use Relay cursor connections (edges/cursor/pageInfo).
 *   - Mutations use the Input Object Pattern (e.g. startRoute(input:...)).
 */

import { ApplicationInstance } from "../../services/webtop-service.js";
import { EipServiceGraphQL } from "../../services/eip-service-graphql.js";
import { createGraphQLClient } from "../../graphql/client.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
} from "../../composables/use-localization.js";
import type {
	Route,
	RouteStats,
	HistoryExchange,
	HistoryExchangeEdge,
	HistoryStep,
	StatInterval,
} from "../../graphql/types.js";

// CodeMirror — read-only JSON viewer used by the raw-record overlay.
// Theme / highlight mirror content-browser so colours follow the same
// CSS variables defined in style.css (--cm-keyword, --cm-string, …).
import { EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers, highlightActiveLine, drawSelection } from "@codemirror/view";
import { defaultKeymap } from "@codemirror/commands";
import { syntaxHighlighting, HighlightStyle, bracketMatching } from "@codemirror/language";
import { tags as t } from "@lezer/highlight";
import { search, findNext, findPrevious, setSearchQuery, SearchQuery } from "@codemirror/search";
import { json } from "@codemirror/lang-json";

const cmTheme = EditorView.theme({
	"&": { backgroundColor: "var(--body-bg)", color: "var(--body-color)" },
	".cm-content": { caretColor: "var(--body-color)" },
	".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--body-color)" },
	".cm-scroller .cm-layer.cm-selectionLayer .cm-selectionBackground, ::selection": { backgroundColor: "var(--item-selected-bg)" },
	".cm-gutters": { backgroundColor: "var(--list-header-bg)", color: "var(--text-soft-color)", border: "none" },
	".cm-activeLineGutter": { backgroundColor: "var(--btn-icon-hover-bg)" },
	".cm-activeLine": { backgroundColor: "var(--btn-icon-hover-bg)" },
});

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
	{ tag: t.invalid, color: "var(--cm-invalid)" },
]);

type RouteAction = 'startRoute' | 'stopRoute' | 'suspendRoute' | 'resumeRoute';
type RangeKey = '1h' | '24h' | '7d' | '30d';
type StatusKey = 'all' | 'completed' | 'failed';
type ElapsedKey = 'under1s' | 'under5s' | 'over5s';
type DetailType = 'none' | 'route' | 'exchange';
type LeftMode = 'search' | 'manage';

interface RouteGroup {
	key: string;
	name: string;
	expanded: boolean;
	routes: Route[];
}

interface RouteActionDialog {
	kind: 'routeAction';
	action: RouteAction;
	routeId: string;
	title: string;
	message: string;
	hint?: string;
	icon: string;
	confirmLabel: string;
	confirmClass: string;
	busy: boolean;
}

interface ErrorDialog {
	kind: 'errorMessage';
	message: string;
}

type Dialog = RouteActionDialog | ErrorDialog | null;

interface StepRow {
	kind: 'step' | 'gap';
	key: string;
	step?: HistoryStep;
	gapMs?: number;
	barStyle: Record<string, string>;
}

const RANGE_OPTIONS: { key: RangeKey; label: string; intervalMs: number; interval: StatInterval }[] = [
	{ key: '1h', label: 'Last 1h', intervalMs: 60 * 60 * 1000, interval: '5min' },
	{ key: '24h', label: 'Last 24h', intervalMs: 24 * 60 * 60 * 1000, interval: '1h' },
	{ key: '7d', label: 'Last 7d', intervalMs: 7 * 24 * 60 * 60 * 1000, interval: '1d' },
	{ key: '30d', label: 'Last 30d', intervalMs: 30 * 24 * 60 * 60 * 1000, interval: '1d' },
];

const STATUS_OPTIONS: { key: StatusKey; label: string }[] = [
	{ key: 'all', label: 'All' },
	{ key: 'completed', label: 'Completed' },
	{ key: 'failed', label: 'Failed' },
];

const ELAPSED_OPTIONS: { key: ElapsedKey; label: string }[] = [
	{ key: 'under1s', label: '< 1s' },
	{ key: 'under5s', label: '1s – 5s' },
	{ key: 'over5s', label: '≥ 5s' },
];

const ALL_ELAPSED_KEYS: ElapsedKey[] = ELAPSED_OPTIONS.map(o => o.key);

const HISTORY_PAGE_SIZE = 500;
const HISTORY_MAX_ROWS = 1000;
const LIVE_TAIL_PRESS_MS = 1500;
// Anything longer than this between consecutive steps surfaces as a gap row
// in the execution path. The threshold mirrors the "1s sub-second" band so
// that the UI's "warning" colour is consistent with the chart.
const STEP_GAP_THRESHOLD_MS = 1_000;

const ROUTE_ACTION_META: Record<RouteAction, {
	title: string; verb: string; icon: string; confirmClass: string;
}> = {
	startRoute:   { title: 'Start Route',   verb: 'start',   icon: 'bi-play-circle',   confirmClass: 'wt-primary' },
	stopRoute:    { title: 'Stop Route',    verb: 'stop',    icon: 'bi-stop-circle',   confirmClass: 'wt-danger' },
	suspendRoute: { title: 'Suspend Route', verb: 'suspend', icon: 'bi-pause-circle',  confirmClass: 'wt-primary' },
	resumeRoute:  { title: 'Resume Route',  verb: 'resume',  icon: 'bi-play-circle',   confirmClass: 'wt-primary' },
};

export const App = {
	data() {
		const bottomPanelHeight = window.innerHeight * 0.6;
		return {
			instance: null as ApplicationInstance | null,
			eip: null as EipServiceGraphQL | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			// Reactive Localization snapshot — see composables/use-localization.ts.
			localization: createLocalizationSnapshot(),

			workspace: '',
			errorMessage: '',

			// Panel layout
			sidebarPanelVisible: true,
			sidebarPanelWidth: 280,
			bottomPanelVisible: true,
			bottomPanelHeight: bottomPanelHeight,
			detailPanelVisible: true,
			detailPanelWidth: 360,

			// Resize state (private)
			_resizing: null as null | { kind: 'sidebar'|'bottom'|'detail'; startX: number; startY: number; startSize: number },

			// Live tail (BPM hold-1.5s pattern)
			liveTailActive: false,
			liveTailIntervalMs: 5000,
			_liveTailTimer: null as number | null,
			_liveTailPressTimer: null as number | null,
			_liveTailLongPressed: false,
			_refreshing: false,

			// Left pane mode
			leftMode: 'search' as LeftMode,

			// Search-mode filters
			filterText: '',
			filterEditing: false,
			filterInput: '',
			selectedRouteIds: [] as string[],
			rangeKey: '1h' as RangeKey,
			statusFilter: 'all' as StatusKey,
			// Elapsed band filter — drives both chart series visibility and
			// the history list query. Default: none selected, which is treated
			// as "no filter" (chart shows all 3 series, history query omits
			// the elapsedBands predicate).
			elapsedFilter: [] as ElapsedKey[],

			// Manage-mode state
			manageFilterText: '',
			routeGroupExpand: {} as Record<string, boolean>,
			managedRoute: null as Route | null,

			// Routes (shared by both modes)
			routes: [] as Route[],
			routesLoaded: false,

			// Stats panel
			stats: null as RouteStats | null,
			statsLoading: false,

			// Chart dimensions — kept reactive so the SVG redraws when the
			// container resizes (ResizeObserver feeds these).
			chartWidth: 800,
			chartHeight: 240,
			_resizeObserver: null as ResizeObserver | null,

			// History panel — client-side accumulator (up to HISTORY_MAX_ROWS,
			// fetched in pages of HISTORY_PAGE_SIZE). totalCount comes from
			// the server independently of pagination so we can flag truncation
			// when the dataset exceeds the cap.
			historyEdges: [] as HistoryExchangeEdge[],
			historyLoading: false,
			historyTotalCount: 0,
			historyTruncated: false,

			// Detail / Inspector
			activeDetailType: 'none' as DetailType,
			selectedExchange: null as HistoryExchange | null,

			// Constant exposed to template
			historyMaxRows: HISTORY_MAX_ROWS,

			// Reload bookkeeping — bumped on every filter change so that a
			// late-arriving response from a stale request can be discarded.
			_reloadTicket: 0,

			// Dialog state
			dialog: null as Dialog,

			// RAW JSON viewer overlay (read-only CodeMirror)
			rawJsonVisible: false,
			cmEditor: null as EditorView | null,
			cmEscHandler: null as ((e: KeyboardEvent) => void) | null,

			// Custom find UI inside the overlay (search-only, no replace)
			cmSearchVisible: false,
			cmSearchTerm: '' as string,
			cmSearchCaseSensitive: false,
			cmSearchRegex: false,
			cmSearchWholeWord: false,
			cmSearchNotFound: false,
		};
	},

	computed: {
		rangeOptions(): typeof RANGE_OPTIONS {
			return RANGE_OPTIONS;
		},

		statusOptions(): typeof STATUS_OPTIONS {
			return STATUS_OPTIONS;
		},

		elapsedOptions(): typeof ELAPSED_OPTIONS {
			return ELAPSED_OPTIONS;
		},

		elapsedFilterMap(): Record<ElapsedKey, boolean> {
			const m = { under1s: false, under5s: false, over5s: false } as Record<ElapsedKey, boolean>;
			for (const k of (this as any).elapsedFilter as ElapsedKey[]) {
				m[k] = true;
			}
			return m;
		},

		selectedRouteIdMap(): Record<string, boolean> {
			const m: Record<string, boolean> = {};
			for (const id of (this as any).selectedRouteIds as string[]) {
				m[id] = true;
			}
			return m;
		},

		filteredGroups(): RouteGroup[] {
			const vm = this as any;
			const search = (vm.manageFilterText as string).trim().toLowerCase();
			const buckets = new Map<string, Route[]>();
			for (const r of vm.routes as Route[]) {
				if (search && !r.routeId.toLowerCase().includes(search)) {
					continue;
				}
				const key = r.group || '(default)';
				if (!buckets.has(key)) buckets.set(key, []);
				buckets.get(key)!.push(r);
			}
			const groups: RouteGroup[] = [];
			for (const [key, rs] of buckets.entries()) {
				rs.sort((a, b) => a.routeId.localeCompare(b.routeId));
				groups.push({
					key,
					name: niceGroupName(key),
					expanded: vm.routeGroupExpand[key] !== false,
					routes: rs,
				});
			}
			groups.sort((a, b) => a.name.localeCompare(b.name));
			return groups;
		},

		/**
		 * Build the rendered execution-path rows for the selected exchange.
		 *
		 * Each Camel step expands to one StepRow; whenever two consecutive
		 * steps have a non-trivial gap between them (≥ STEP_GAP_THRESHOLD_MS)
		 * we synthesise a "gap" row in warning colour so route authors can
		 * tell exception/recovery latency apart from real work.
		 *
		 * Returned barStyle widths are normalised against the exchange's
		 * total elapsed time so all rows share a common horizontal scale.
		 */
		exchangeStepRows(): StepRow[] {
			const vm = this as any;
			const detail = vm.selectedExchange as HistoryExchange | null;
			if (!detail || !detail.steps || detail.steps.length === 0) return [];
			const elapsed = detail.elapsed && detail.elapsed > 0 ? detail.elapsed : 1;
			const rows: StepRow[] = [];
			let prevEnd = 0;
			for (const step of detail.steps) {
				const offset = step.offsetFromStart ?? 0;
				const taken = step.timeTaken ?? 0;
				const stepStart = Math.max(0, offset - taken);
				if (stepStart - prevEnd > STEP_GAP_THRESHOLD_MS) {
					const gapMs = stepStart - prevEnd;
					rows.push({
						kind: 'gap',
						key: `gap-${prevEnd}`,
						gapMs,
						barStyle: {
							left: ((prevEnd / elapsed) * 100).toFixed(2) + '%',
							width: ((gapMs / elapsed) * 100).toFixed(2) + '%',
						},
					});
				}
				rows.push({
					kind: 'step',
					key: `step-${step.order}`,
					step,
					barStyle: {
						left: ((stepStart / elapsed) * 100).toFixed(2) + '%',
						width: ((taken / elapsed) * 100).toFixed(2) + '%',
					},
				});
				prevEnd = stepStart + taken;
			}
			return rows;
		},

		/**
		 * Render the multi-series chart as an inline SVG fragment.
		 *
		 * Three line series — `under1s` (primary), `under5s` (warning),
		 * `over5s` (danger) — share a common Y axis scaled to the largest
		 * value across all series. X labels show the first / middle / last
		 * bucket boundaries.
		 *
		 * The SVG viewBox is driven by reactive `chartWidth` / `chartHeight`
		 * tracked by a ResizeObserver so the chart fits the panel as it is
		 * resized.
		 */
		chartSvg(): string {
			const vm = this as any;
			const stats = vm.stats as RouteStats | null;
			if (!stats || !stats.points.length) return '';

			const W = vm.chartWidth as number;
			const H = vm.chartHeight as number;
			const padL = 48, padR = 16, padT = 12, padB = 28;
			const innerW = Math.max(1, W - padL - padR);
			const innerH = Math.max(1, H - padT - padB);

			// Empty selection is treated as "no filter" — render all 3 series.
			const filter = vm.elapsedFilter as ElapsedKey[];
			const bandsOn = filter.length === 0
				? { under1s: true, under5s: true, over5s: true } as Record<ElapsedKey, boolean>
				: vm.elapsedFilterMap as Record<ElapsedKey, boolean>;

			const points = stats.points;
			const n = points.length;
			// Y-axis scale only considers visible bands so a single-band view
			// fills the chart area rather than being squashed by the largest
			// hidden series.
			let max = 1;
			for (const p of points) {
				if (bandsOn.under1s && p.under1s > max) max = p.under1s;
				if (bandsOn.under5s && p.under5s > max) max = p.under5s;
				if (bandsOn.over5s && p.over5s > max) max = p.over5s;
			}

			const xStep = n > 1 ? innerW / (n - 1) : innerW;
			const xAt = (i: number) => padL + i * xStep;
			const yAt = (v: number) => padT + innerH - (v / max) * innerH;

			const seriesLine = (cls: string, getter: (p: any) => number) => {
				const pts = points.map((p, i) => `${xAt(i).toFixed(1)},${yAt(getter(p)).toFixed(1)}`).join(' ');
				const dots = points
					.map((p, i) => `<circle class="${cls}-point" cx="${xAt(i).toFixed(1)}" cy="${yAt(getter(p)).toFixed(1)}" r="2.5"/>`)
					.join('');
				return `<polyline class="${cls}-line" points="${pts}"/>${dots}`;
			};

			const labelIdxs = n === 1 ? [0] : [0, Math.floor(n / 2), n - 1];
			const xLabels = labelIdxs
				.map(i => {
					const t = formatBucketLabel(points[i].bucket, stats.interval, vm.localization.locale || undefined, vm.localization.timeZone || undefined);
					const x = xAt(i).toFixed(1);
					return `<text class="axis-label" x="${x}" y="${(H - 8).toFixed(1)}" text-anchor="middle">${escapeXml(t)}</text>`;
				})
				.join('');

			const yLabels = [
				`<text class="axis-label" x="${padL - 6}" y="${padT + 4}" text-anchor="end">${formatNumber(max)}</text>`,
				`<text class="axis-label" x="${padL - 6}" y="${padT + innerH}" text-anchor="end">0</text>`,
			].join('');

			return `
				<line class="axis" x1="${padL}" y1="${padT}" x2="${padL}" y2="${padT + innerH}"/>
				<line class="axis" x1="${padL}" y1="${padT + innerH}" x2="${padL + innerW}" y2="${padT + innerH}"/>
				${bandsOn.under1s ? seriesLine('under1s', (p) => p.under1s) : ''}
				${bandsOn.under5s ? seriesLine('under5s', (p) => p.under5s) : ''}
				${bandsOn.over5s ? seriesLine('over5s', (p) => p.over5s) : ''}
				${xLabels}
				${yLabels}
			`;
		},

		isResizing(): boolean {
			return !!(this as any)._resizing;
		},

		/**
		 * Flatten the selected exchange's header map into a sorted list of
		 * `{ name, type, value }` rows for the detail-panel "Headers" section.
		 *
		 * Mirrors the shape used by BPM Console's `instanceVariables` so the
		 * two panels can share the `.bpm-var-list` / `.bpm-type-badge`
		 * presentation. The server wraps each header as
		 * `{ type, value, length?, size? }` (see ExchangeHistoryEventNotifier
		 * #buildHeaderInfo), so we read the type tag directly and render
		 * only the inner value in the row body.
		 */
		exchangeHeaders(): Array<{ name: string; type: string; value: string }> {
			const detail = (this as any).selectedExchange as HistoryExchange | null;
			const headers = detail?.headers;
			if (!headers || typeof headers !== 'object') return [];
			const entries = Object.keys(headers as Record<string, unknown>).sort((a, b) => a.localeCompare(b));
			return entries.map((name) => {
				const info = (headers as Record<string, unknown>)[name];
				return { name, type: headerType(info), value: headerDisplay(info) };
			});
		},

		localTZ(): string {
			return new Intl.DateTimeFormat('en', { timeZoneName: 'short' })
				.formatToParts(new Date()).find((p: { type: string }) => p.type === 'timeZoneName')?.value || '';
		},
	},

	watch: {
		filterText() { (this as any).scheduleReload(); },
		statusFilter() { (this as any).scheduleReload(); },
		rangeKey() { (this as any).scheduleReload(); },
		selectedRouteIds: { handler() { (this as any).scheduleReload(); }, deep: true },
		// Re-query history when the elapsed band filter changes. The chart
		// reacts purely from the computed property (no refetch needed since
		// every band is always returned by routeStats).
		elapsedFilter: { handler() { (this as any).scheduleReload(); }, deep: true },
	},

	methods: {
		// =====================================================
		// Lifecycle
		// =====================================================
		onMounted() {
			const vm = this as any;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;
				// Drill-down re-target while already open (singleton re-launch).
				if (type === 'app-reopen') {
					vm.applyLaunchOptions(payload.options);
				}
			};
			window.addEventListener('message', vm.messageListener);

			document.addEventListener('visibilitychange', vm.onVisibilityChange);

			window.appLaunch = async (appInstance: ApplicationInstance, options?: Record<string, any>) => {
				vm.instance = vm.$markRaw(appInstance);
				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;
				vm.instance.windowTitle = 'EIP Console';

				refreshLocalization(vm.localization, vm.instance);
				vm.workspace = appInstance.api.workspace;

				const client = createGraphQLClient(vm.workspace);
				vm.eip = vm.$markRaw(new EipServiceGraphQL(client));

				await vm.loadRoutes();
				// A drill-down (e.g. from the Dashboard) may target a specific
				// route and exchange status. Seed the filters before the first
				// reload so the initial query reflects the requested slice.
				vm.applyLaunchOptions(options);
				await vm.reloadAll();

				vm.$nextTick(() => appInstance.notifyLaunched());
			};
		},

		onUnmount() {
			const vm = this as any;
			vm.stopLiveTail();
			vm.cancelLiveTailPress();
			if (vm.messageListener) {
				window.removeEventListener('message', vm.messageListener);
			}
			document.removeEventListener('visibilitychange', vm.onVisibilityChange);
			vm.closeRawJsonViewer();
		},

		onVisibilityChange() {
			// Live-tail tick checks document.hidden before issuing requests;
			// nothing to do here.
		},

		// =====================================================
		// Chart resize handler — keeps SVG fit-to-container.
		// Wired via `v-resize` on the chart area element.
		// =====================================================
		onChartResize(entries: ResizeObserverEntry[]) {
			const vm = this as any;
			for (const e of entries) {
				const w = Math.round(e.contentRect.width);
				const h = Math.round(e.contentRect.height);
				if (w > 0) vm.chartWidth = w;
				if (h > 0) vm.chartHeight = h;
			}
		},

		// =====================================================
		// Pane toggles & resize
		// =====================================================
		toggleSidebarPanel() { (this as any).sidebarPanelVisible = !(this as any).sidebarPanelVisible; },
		toggleBottomPanel() { (this as any).bottomPanelVisible = !(this as any).bottomPanelVisible; },
		toggleDetailPanel() { (this as any).detailPanelVisible = !(this as any).detailPanelVisible; },

		onSidebarResizeStart(e: MouseEvent) { this._startResize(e, 'sidebar'); },
		onBottomResizeStart(e: MouseEvent) { this._startResize(e, 'bottom'); },
		onDetailResizeStart(e: MouseEvent) { this._startResize(e, 'detail'); },

		_startResize(e: MouseEvent, kind: 'sidebar'|'bottom'|'detail') {
			const vm = this as any;
			e.preventDefault();
			const startSize =
				kind === 'sidebar' ? vm.sidebarPanelWidth :
				kind === 'bottom' ? vm.bottomPanelHeight :
				vm.detailPanelWidth;
			vm._resizing = { kind, startX: e.clientX, startY: e.clientY, startSize };
			document.addEventListener('mousemove', vm._onResizeMove);
			document.addEventListener('mouseup', vm._onResizeEnd);
		},

		_onResizeMove(e: MouseEvent) {
			const vm = this as any;
			const r = vm._resizing;
			if (!r) return;
			if (r.kind === 'sidebar') {
				const dx = e.clientX - r.startX;
				vm.sidebarPanelWidth = Math.max(180, Math.min(640, r.startSize + dx));
			} else if (r.kind === 'detail') {
				const dx = e.clientX - r.startX;
				vm.detailPanelWidth = Math.max(220, Math.min(720, r.startSize - dx));
			} else {
				const dy = e.clientY - r.startY;
				vm.bottomPanelHeight = Math.max(120, Math.min(600, r.startSize - dy));
			}
		},

		_onResizeEnd() {
			const vm = this as any;
			vm._resizing = null;
			document.removeEventListener('mousemove', vm._onResizeMove);
			document.removeEventListener('mouseup', vm._onResizeEnd);
		},

		// =====================================================
		// Live tail (click=refresh, hold-1.5s=arm)
		// =====================================================
		onRefreshPointerDown(e: PointerEvent) {
			const vm = this as any;
			if (e && e.button !== 0) return;
			vm.cancelLiveTailPress();
			vm._liveTailLongPressed = false;
			vm._liveTailPressTimer = window.setTimeout(() => {
				vm._liveTailPressTimer = null;
				vm._liveTailLongPressed = true;
				vm.startLiveTail();
			}, LIVE_TAIL_PRESS_MS);
		},

		onRefreshPointerUp() {
			const vm = this as any;
			const timer = vm._liveTailPressTimer;
			vm._liveTailPressTimer = null;
			if (timer != null) {
				window.clearTimeout(timer);
			}
			if (vm._liveTailLongPressed) {
				vm._liveTailLongPressed = false;
				return;
			}
			if (vm.liveTailActive) {
				vm.stopLiveTail();
			} else {
				vm.reloadAll();
			}
		},

		cancelLiveTailPress() {
			const vm = this as any;
			if (vm._liveTailPressTimer != null) {
				window.clearTimeout(vm._liveTailPressTimer);
				vm._liveTailPressTimer = null;
			}
			vm._liveTailLongPressed = false;
		},

		startLiveTail() {
			const vm = this as any;
			if (vm.liveTailActive) return;
			vm.liveTailActive = true;
			vm._liveTailTimer = window.setInterval(() => {
				if (document.hidden) return;
				if (vm._refreshing) return;
				vm.reloadAll();
			}, vm.liveTailIntervalMs);
		},

		stopLiveTail() {
			const vm = this as any;
			vm.liveTailActive = false;
			if (vm._liveTailTimer != null) {
				window.clearInterval(vm._liveTailTimer);
				vm._liveTailTimer = null;
			}
		},

		// =====================================================
		// Window controls
		// =====================================================
		onMinimizeWindow() { (this as any).instance?.minimize(); },
		onToggleMaximizeWindow() { (this as any).instance?.toggleMaximize(); },
		onCloseWindow() { (this as any).instance?.requestClose(); },

		// =====================================================
		// Left pane mode
		// =====================================================
		switchLeftMode(mode: LeftMode) {
			const vm = this as any;
			vm.leftMode = mode;
			if (mode === 'search') {
				vm.managedRoute = null;
				if (vm.activeDetailType === 'route') {
					vm.activeDetailType = 'none';
				}
			}
		},

		// =====================================================
		// Filter (click-to-edit)
		// =====================================================
		startFilterEdit() {
			const vm = this as any;
			vm.filterInput = vm.filterText;
			vm.filterEditing = true;
			// ichigo.js does not expose Vue-style $refs; look the input up by
			// its `ref` attribute after the DOM commit so v-if has mounted it.
			vm.$nextTick(() => {
				const el = document.querySelector('input[ref="filterInput"]') as HTMLInputElement | null;
				if (el) el.focus();
			});
		},

		commitFilterEdit() {
			const vm = this as any;
			const next = (vm.filterInput as string).trim();
			vm.filterEditing = false;
			vm.filterText = next;
		},

		cancelFilterEdit() {
			const vm = this as any;
			vm.filterEditing = false;
			vm.filterInput = '';
		},

		// =====================================================
		// Route multi-select
		// =====================================================
		toggleRouteId(id: string) {
			const vm = this as any;
			const idx = (vm.selectedRouteIds as string[]).indexOf(id);
			if (idx >= 0) {
				vm.selectedRouteIds.splice(idx, 1);
			} else {
				vm.selectedRouteIds.push(id);
			}
		},

		clearRouteSelection() {
			(this as any).selectedRouteIds = [];
		},

		// =====================================================
		// Drill-down launch options
		//
		// A caller (e.g. the Dashboard) may open the console pre-focused.
		// Supported options:
		//   routeId  string  — focus this route (selects it in the list)
		//   status   'all' | 'completed' | 'failed' — exchange status filter
		// The field watchers reload the views automatically once set.
		// All options are optional; unknown keys are ignored.
		// =====================================================
		applyLaunchOptions(options?: Record<string, any>) {
			const vm = this as any;
			if (!options) return;
			if (typeof options.routeId === 'string' && options.routeId) {
				vm.selectedRouteIds = [options.routeId];
				// Do not also keyword-filter the route list, or the freshly
				// selected route could be hidden from view.
				vm.filterText = '';
			}
			if (options.status === 'all' || options.status === 'completed' || options.status === 'failed') {
				vm.statusFilter = options.status;
			}
		},

		// =====================================================
		// Elapsed band multi-select
		// =====================================================
		toggleElapsedBand(key: ElapsedKey) {
			const vm = this as any;
			const arr = vm.elapsedFilter as ElapsedKey[];
			const idx = arr.indexOf(key);
			if (idx >= 0) {
				arr.splice(idx, 1);
			} else {
				arr.push(key);
			}
		},

		// =====================================================
		// Manage mode — routes tree
		// =====================================================
		toggleGroupExpand(group: RouteGroup) {
			const vm = this as any;
			vm.routeGroupExpand[group.key] = !group.expanded;
		},

		selectManagedRoute(route: Route | null) {
			const vm = this as any;
			vm.managedRoute = route;
			vm.activeDetailType = route ? 'route' : 'none';
		},

		// =====================================================
		// Data loading
		// =====================================================
		async loadRoutes() {
			const vm = this as any;
			if (!vm.eip) return;
			try {
				const list = await vm.eip.getAllRoutes();
				list.sort((a: Route, b: Route) => a.routeId.localeCompare(b.routeId));
				vm.routes = list;
				vm.routesLoaded = true;
				if (vm.managedRoute) {
					const updated = (vm.routes as Route[]).find((r: Route) => r.routeId === vm.managedRoute.routeId);
					if (updated) vm.managedRoute = updated;
				}
			} catch (ex: any) {
				vm.errorMessage = ex?.message || 'Failed to load routes';
			}
		},

		// Reload everything (routes + stats + history).
		async reloadAll() {
			const vm = this as any;
			if (!vm.eip) return;
			vm._refreshing = true;
			try {
				const ticket = ++vm._reloadTicket;
				await Promise.all([
					vm.loadRoutes(),
					vm.reloadStats(ticket),
					vm.reloadHistory(ticket),
				]);
			} finally {
				vm._refreshing = false;
			}
		},

		/**
		 * Debounced re-run of the chart + history queries — invoked by
		 * filter / route / range / status watchers so we coalesce rapid
		 * checkbox flicks into a single round-trip per quiet period.
		 */
		scheduleReload() {
			const vm = this as any;
			if (!vm.eip) return;
			if (vm._scheduleTimer) window.clearTimeout(vm._scheduleTimer);
			vm._scheduleTimer = window.setTimeout(() => {
				vm._scheduleTimer = null;
				const ticket = ++vm._reloadTicket;
				vm.reloadStats(ticket);
				vm.reloadHistory(ticket);
			}, 200);
		},

		rangeOption(): typeof RANGE_OPTIONS[number] {
			const vm = this as any;
			return RANGE_OPTIONS.find(o => o.key === vm.rangeKey) || RANGE_OPTIONS[0];
		},

		rangeBounds(): [string, string] {
			const opt = (this as any).rangeOption();
			const now = Date.now();
			const from = new Date(now - opt.intervalMs).toISOString();
			const to = new Date(now).toISOString();
			return [from, to];
		},

		async reloadStats(ticket?: number) {
			const vm = this as any;
			if (!vm.eip) return;
			const myTicket = ticket ?? ++vm._reloadTicket;
			vm.statsLoading = true;
			vm.errorMessage = '';
			try {
				const [from, to] = vm.rangeBounds();
				const opt = vm.rangeOption();
				const stats = await vm.eip.getRouteStats({
					routes: vm.selectedRouteIds.length ? vm.selectedRouteIds : undefined,
					from,
					to,
					status: vm.statusFilter !== 'all' ? vm.statusFilter : undefined,
					interval: opt.interval,
				});
				if (myTicket !== vm._reloadTicket) return;
				vm.stats = stats;
			} catch (ex: any) {
				if (myTicket !== vm._reloadTicket) return;
				vm.errorMessage = ex?.message || 'Failed to load stats';
				vm.stats = null;
			} finally {
				if (myTicket === vm._reloadTicket) {
					vm.statsLoading = false;
				}
			}
		},

		/**
		 * Reload the history list from scratch, accumulating cursor pages
		 * up to HISTORY_MAX_ROWS rows.
		 *
		 * The page-by-page accumulation lets the server keep each request
		 * bounded (HISTORY_PAGE_SIZE) while the client still presents one
		 * scroll-through table. Truncation is surfaced via the status bar
		 * when totalCount exceeds the cap.
		 */
		async reloadHistory(ticket?: number) {
			const vm = this as any;
			if (!vm.eip) return;
			const myTicket = ticket ?? ++vm._reloadTicket;
			vm.historyLoading = true;
			vm.errorMessage = '';
			vm.historyEdges = [];
			vm.historyTotalCount = 0;
			vm.historyTruncated = false;
			try {
				const [from, to] = vm.rangeBounds();
				let cursor: string | undefined = undefined;
				let total = 0;
				const accum: HistoryExchangeEdge[] = [];

				while (accum.length < HISTORY_MAX_ROWS) {
					const remaining = HISTORY_MAX_ROWS - accum.length;
					const pageSize = Math.min(HISTORY_PAGE_SIZE, remaining);
					const bands = vm.elapsedFilter as ElapsedKey[];
					// Only forward elapsedBands when the user has narrowed
					// the selection — otherwise the server can skip the
					// extra predicate entirely.
					const elapsedBands = bands.length > 0 && bands.length < ALL_ELAPSED_KEYS.length
						? bands.slice()
						: undefined;
					const conn = await vm.eip.listHistoryExchanges({
						first: pageSize,
						after: cursor,
						routes: vm.selectedRouteIds.length ? vm.selectedRouteIds : undefined,
						status: vm.statusFilter !== 'all' ? vm.statusFilter : undefined,
						from,
						to,
						filter: vm.filterText || undefined,
						elapsedBands,
					});
					if (myTicket !== vm._reloadTicket) return;
					total = conn.totalCount;
					for (const edge of conn.edges) {
						accum.push(edge);
						if (accum.length >= HISTORY_MAX_ROWS) break;
					}
					if (!conn.pageInfo.hasNextPage || conn.edges.length === 0) break;
					cursor = conn.pageInfo.endCursor ?? undefined;
					if (!cursor) break;
				}

				if (myTicket !== vm._reloadTicket) return;
				// Final ordering safety net — server already orders newest first
				// by @mi:createdAt, but enforce it on the accumulated rows so the
				// table is guaranteed to render Started descending regardless of
				// per-page ordering quirks. exchangeId is the tiebreaker so rows
				// with identical timestamps keep a stable order.
				accum.sort((a, b) => {
					const ta = Date.parse(a.node.createdAt || '');
					const tb = Date.parse(b.node.createdAt || '');
					if (tb !== ta) return tb - ta;
					return (b.node.exchangeId || '').localeCompare(a.node.exchangeId || '');
				});
				vm.historyEdges = accum;
				vm.historyTotalCount = total;
				vm.historyTruncated = total > HISTORY_MAX_ROWS;
			} catch (ex: any) {
				if (myTicket !== vm._reloadTicket) return;
				vm.errorMessage = ex?.message || 'History search failed';
			} finally {
				if (myTicket === vm._reloadTicket) {
					vm.historyLoading = false;
				}
			}
		},

		// =====================================================
		// Inspector
		// =====================================================
		async openInspector(exchangeId: string) {
			const vm = this as any;
			if (!vm.eip) return;
			vm.errorMessage = '';
			try {
				const detail = await vm.eip.getHistoryExchange(exchangeId);
				if (detail) {
					vm.selectedExchange = detail;
					vm.activeDetailType = 'exchange';
					if (!vm.detailPanelVisible) vm.detailPanelVisible = true;
				} else {
					vm.errorMessage = 'Exchange not found';
				}
			} catch (ex: any) {
				vm.errorMessage = ex?.message || 'Failed to load exchange';
			}
		},

		/**
		 * Step label: id takes precedence over the endpoint URI per spec,
		 * because the DSL id is the author-meaningful name. The URI is only
		 * meaningful for to/toD/from-style steps and may be empty otherwise.
		 */
		stepLabel(step: HistoryStep): string {
			if (step.id) return step.id;
			if (step.endpointUri) return step.endpointUri;
			return '(unnamed step)';
		},

		// =====================================================
		// Dialogs — Route control (Input Object Pattern mutations)
		// =====================================================
		confirmRouteAction(action: RouteAction) {
			const vm = this as any;
			const route = vm.managedRoute as Route | null;
			if (!route) return;
			const meta = ROUTE_ACTION_META[action];
			vm.dialog = {
				kind: 'routeAction',
				action,
				routeId: route.routeId,
				title: meta.title,
				message: `${capitalize(meta.verb)} route "${route.routeId}"?`,
				hint: action === 'stopRoute'
					? 'Stopping the route will halt all in-flight exchanges.'
					: undefined,
				icon: meta.icon,
				confirmLabel: capitalize(meta.verb),
				confirmClass: meta.confirmClass,
				busy: false,
			} as RouteActionDialog;
		},

		async executeRouteAction() {
			const vm = this as any;
			const d = vm.dialog as RouteActionDialog | null;
			if (!d || d.kind !== 'routeAction') return;
			d.busy = true;
			try {
				const input = { id: d.routeId };
				switch (d.action) {
					case 'startRoute':   await vm.eip!.startRoute(input);   break;
					case 'stopRoute':    await vm.eip!.stopRoute(input);    break;
					case 'suspendRoute': await vm.eip!.suspendRoute(input); break;
					case 'resumeRoute':  await vm.eip!.resumeRoute(input);  break;
				}
				vm.dialog = null;
				await vm.loadRoutes();
			} catch (ex: any) {
				vm.dialog = {
					kind: 'errorMessage',
					message: ex?.message || `Failed to ${ROUTE_ACTION_META[d.action].verb} route`,
				} as ErrorDialog;
			}
		},

		closeDialog() {
			const vm = this as any;
			const d = vm.dialog;
			if (d && d.kind === 'routeAction' && d.busy) return;
			vm.dialog = null;
		},

		// =====================================================
		// Formatting helpers
		// =====================================================
		formatTime(iso: string | null | undefined): string {
			if (!iso) return '';
			const dates = this.instance?.util?.dates;
			if (!dates) {
				try { return new Date(iso).toLocaleString(); } catch { return String(iso); }
			}
			return dates.format(iso, {
				format: 'datetime',
				locale: this.localization.locale || undefined,
				timeZone: this.localization.timeZone || undefined,
			}) ?? String(iso);
		},

		formatJson(value: unknown): string {
			if (value === null || value === undefined) return '';
			try { return JSON.stringify(value, null, 2); } catch { return String(value); }
		},

		// =====================================================
		// RAW JSON viewer (read-only CodeMirror overlay)
		// =====================================================
		openRawJsonViewer() {
			const vm = this as any;
			if (!vm.selectedExchange) return;
			vm.rawJsonVisible = true;
			vm.cmSearchVisible = false;
			vm.cmSearchNotFound = false;

			const initialValue = vm.formatJson(vm.selectedExchange);
			vm.$nextTick(() => {
				const tryInit = (attempts = 0) => {
					const container = document.getElementById('cm-editor-expanded');
					if (container) {
						vm._initRawJsonEditor(container, initialValue);
					} else if (attempts < 20) {
						requestAnimationFrame(() => tryInit(attempts + 1));
					}
				};
				tryInit();
			});

			vm.cmEscHandler = (e: KeyboardEvent) => {
				// Ctrl/Cmd+F — open the Find bar even when focus has escaped
				// the editor (e.g. user clicked a toolbar button).
				if ((e.ctrlKey || e.metaKey) && !e.altKey && (e.key === 'f' || e.key === 'F')) {
					e.preventDefault();
					e.stopPropagation();
					e.stopImmediatePropagation();
					vm.openCmSearch();
					return;
				}
				if (e.key !== 'Escape') return;
				if (vm.cmSearchVisible) {
					e.preventDefault();
					e.stopPropagation();
					e.stopImmediatePropagation();
					vm.closeCmSearch();
					return;
				}
				e.preventDefault();
				e.stopPropagation();
				e.stopImmediatePropagation();
				vm.closeRawJsonViewer();
			};
			document.addEventListener('keydown', vm.cmEscHandler, true);
		},

		closeRawJsonViewer() {
			const vm = this as any;
			if (vm.cmEditor) {
				try { vm.cmEditor.destroy(); } catch { /* noop */ }
				vm.cmEditor = null;
			}
			if (vm.cmEscHandler) {
				document.removeEventListener('keydown', vm.cmEscHandler, true);
				vm.cmEscHandler = null;
			}
			vm.cmSearchVisible = false;
			vm.cmSearchTerm = '';
			vm.cmSearchNotFound = false;
			vm.rawJsonVisible = false;
		},

		_initRawJsonEditor(container: HTMLElement, initialValue: string) {
			const vm = this as any;
			if (vm.cmEditor) {
				try { vm.cmEditor.destroy(); } catch { /* noop */ }
				vm.cmEditor = null;
			}

			const searchKeyBindings = [
				{ key: "Mod-f", run: () => { vm.openCmSearch(); return true; } },
				{ key: "F3", run: (view: EditorView) => findNext(view) },
				{ key: "Shift-F3", run: (view: EditorView) => findPrevious(view) },
			];

			const state = EditorState.create({
				doc: initialValue,
				extensions: [
					lineNumbers(),
					highlightActiveLine(),
					drawSelection(),
					bracketMatching(),
					syntaxHighlighting(cmHighlight, { fallback: true }),
					// Read-only viewer — `readOnly` blocks edits but keeps the
					// editor focusable so Ctrl+F / F3 keybindings still fire.
					// (Using `editable.of(false)` would make cm-content
					// unfocusable and swallow those shortcuts.)
					EditorState.readOnly.of(true),
					// Custom Find UI lives in the overlay; suppress the built-in panel.
					search({ top: true, createPanel: () => ({ dom: document.createElement('div') }) }),
					keymap.of([...searchKeyBindings, ...defaultKeymap]),
					cmTheme,
					json(),
				],
			});

			const view = new EditorView({ state, parent: container });
			vm.cmEditor = (vm.$markRaw ? vm.$markRaw(view) : view);
			// Focus so the in-editor Ctrl+F keybinding is live immediately.
			view.focus();
		},

		// ----- Custom find bar (search only; no replace) -----
		openCmSearch() {
			const vm = this as any;
			if (!vm.cmEditor) return;
			vm.cmSearchVisible = true;
			vm.cmSearchNotFound = false;
			vm.applyCmSearchQuery();
			vm.$nextTick(() => {
				const el = document.getElementById('cm-search-input') as HTMLInputElement | null;
				if (el) { el.focus(); el.select(); }
			});
		},

		closeCmSearch() {
			const vm = this as any;
			vm.cmSearchVisible = false;
			vm.cmSearchNotFound = false;
			if (vm.cmEditor) vm.cmEditor.focus();
		},

		applyCmSearchQuery() {
			const vm = this as any;
			if (!vm.cmEditor) return;
			const query = new SearchQuery({
				search: vm.cmSearchTerm,
				caseSensitive: vm.cmSearchCaseSensitive,
				regexp: vm.cmSearchRegex,
				wholeWord: vm.cmSearchWholeWord,
			});
			vm.cmEditor.dispatch({ effects: setSearchQuery.of(query) });
		},

		onCmSearchInput() {
			const vm = this as any;
			vm.cmSearchNotFound = false;
			vm.applyCmSearchQuery();
		},

		toggleCmSearchCaseSensitive() {
			const vm = this as any;
			vm.cmSearchCaseSensitive = !vm.cmSearchCaseSensitive;
			vm.applyCmSearchQuery();
		},

		toggleCmSearchRegex() {
			const vm = this as any;
			vm.cmSearchRegex = !vm.cmSearchRegex;
			vm.applyCmSearchQuery();
		},

		toggleCmSearchWholeWord() {
			const vm = this as any;
			vm.cmSearchWholeWord = !vm.cmSearchWholeWord;
			vm.applyCmSearchQuery();
		},

		cmFindNext() {
			const vm = this as any;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !findNext(vm.cmEditor);
		},

		cmFindPrev() {
			const vm = this as any;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !findPrevious(vm.cmEditor);
		},

		onCmSearchKeydown(event: KeyboardEvent) {
			const vm = this as any;
			if (event.key === 'Enter') {
				event.preventDefault();
				if (event.shiftKey) vm.cmFindPrev(); else vm.cmFindNext();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				vm.closeCmSearch();
			}
		},
	},
};

/**
 * Server-side `buildHeaderInfo` tags (see
 * org.mintjams.rt.cms.internal.eip.ExchangeHistoryEventNotifier).
 * Anything not in this map is assumed to be a fully-qualified Java
 * class name, of which we surface only the simple name.
 */
const HEADER_TYPE_LABELS: Record<string, string> = {
	unknown: 'Unknown',
	string: 'String',
	int: 'Integer',
	long: 'Long',
	float: 'Float',
	double: 'Double',
	decimal: 'Decimal',
	bigint: 'BigInteger',
	boolean: 'Boolean',
	date: 'Date',
	time: 'Time',
	binary: 'Binary',
	uri: 'URI',
	url: 'URL',
	map: 'Map',
	list: 'List',
	array: 'Array',
};

function headerType(info: unknown): string {
	if (info === null || info === undefined) return 'Unknown';
	if (typeof info !== 'object') return capitalize(typeof info);
	const tag = (info as { type?: unknown }).type;
	if (typeof tag !== 'string' || tag.length === 0) return 'Unknown';
	const mapped = HEADER_TYPE_LABELS[tag];
	if (mapped) return mapped;
	// Fully-qualified Java class name → simple name
	const dot = tag.lastIndexOf('.');
	return dot >= 0 ? tag.substring(dot + 1) : tag;
}

function headerDisplay(info: unknown): string {
	if (info === null || info === undefined) return '—';
	if (typeof info !== 'object') return String(info);
	const wrapper = info as { type?: unknown; value?: unknown; size?: unknown; length?: unknown };
	const value = wrapper.value;
	if (value === null || value === undefined) {
		// Some types (binary / map / list / array) omit value; show size when present.
		if (typeof wrapper.size === 'number') return `(${wrapper.size} bytes)`;
		return '—';
	}
	if (typeof value === 'object') {
		try { return JSON.stringify(value, null, 2); } catch { return String(value); }
	}
	const s = String(value);
	if (typeof wrapper.length === 'number' && wrapper.length > s.length) {
		return `${s}… (truncated, ${wrapper.length} chars)`;
	}
	return s;
}

function escapeXml(s: string): string {
	return s.replace(/[<>&"']/g, c => {
		switch (c) {
			case '<': return '&lt;';
			case '>': return '&gt;';
			case '&': return '&amp;';
			case '"': return '&quot;';
			case "'": return '&apos;';
			default: return c;
		}
	});
}

function formatNumber(v: number): string {
	if (v >= 1_000_000) return (v / 1_000_000).toFixed(1) + 'M';
	if (v >= 1_000) return (v / 1_000).toFixed(1) + 'k';
	return v.toFixed(0);
}

function capitalize(s: string): string {
	return s.length === 0 ? s : s.charAt(0).toUpperCase() + s.slice(1);
}

function niceGroupName(key: string): string {
	if (!key) return '(default)';
	const slash = key.lastIndexOf('/');
	const base = slash >= 0 ? key.substring(slash + 1) : key;
	const dot = base.lastIndexOf('.');
	return dot > 0 ? base.substring(0, dot) : base;
}

/**
 * X-axis bucket label: pick a granularity that matches the server-resolved
 * interval. We keep labels short so they fit when the chart shrinks.
 */
function formatBucketLabel(iso: string, interval: string, locale?: string, timeZone?: string): string {
	try {
		const d = new Date(iso);
		if (interval === '1d') {
			return d.toLocaleDateString(locale || undefined, { month: 'short', day: 'numeric', timeZone: timeZone || undefined });
		}
		return d.toLocaleTimeString(locale || undefined, { hour: '2-digit', minute: '2-digit', timeZone: timeZone || undefined });
	} catch {
		return iso;
	}
}

// Mount the app
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');

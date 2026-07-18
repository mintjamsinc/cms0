/**
 * EIP Console
 *
 * Three-pane operations console for Apache Camel routes deployed in the
 * Workspace Integration Engine.
 *
 * Left pane has two modes:
 *   - search  (default): filter / route multi-select / elapsed / status
 *   - manage  : routes navigator with start/stop/suspend/resume actions
 *
 * The time-window Range selector and the Live indicator live in the top
 * toolbar (mirroring commerce-dashboard), not the left pane.
 *
 * The chart (centre upper) shows an exchange-count time series split into
 * user-defined elapsed bands. The Elapsed slider (left pane) edits the band
 * boundaries and colours; the server returns per-band counts via a JCR XPath
 * `facet accumulate` query (one facet per band) — see EipStatsQueryExecutor.
 *
 * The history list (centre lower) is a Relay-style cursor connection; the
 * client accumulates up to 1000 rows by issuing forward pages of 500 each.
 * New exchanges stream in live via a node-change subscription on
 * /var/eip/history: fresh rows are merged to the top and briefly flashed.
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
import { SWATCH_COLORS, SWATCH_COLOR_MAP } from "../../lib/color-palette.js";
import "../../components/eip-canvas.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";
import type {
	Route,
	RouteStats,
	StatPoint,
	HistoryExchange,
	HistoryExchangeEdge,
	HistoryExchangeSummary,
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
type RangeKey = '1h' | '24h' | '7d' | '30d' | '90d';
// 'all' is only used as an inbound drill-down option; the Status filter itself
// is a multi-select over the concrete exchange states.
type StatusKey = 'all' | 'completed' | 'failed';
type ExchangeStatusKey = 'completed' | 'failed';
type DetailType = 'none' | 'route' | 'exchange';
// History list sortable columns (mapped to HistoryExchangeSummary fields in
// compareHistorySummary). 'started' sorts by createdAt.
type HistorySortColumn = 'exchangeId' | 'businessKey' | 'started' | 'status' | 'elapsed' | 'route';

/** One rendered elapsed band (slider segment + chart series + legend row). */
interface BandSegment {
	index: number;
	fromMs: number;
	toMs: number;       // ELAPSED_MAX_MS for the open-ended last band
	last: boolean;
	color: string;      // resolved CSS colour
	colorKey: string;
	leftPct: number;
	widthPct: number;
	label: string;
}
// Top-level screens, switched by the toolbar tab group:
//   graph  — chart + history exploration (filters in the left pane)
//   routes — route management (routes tree in the left pane)
type ConsoleView = 'graph' | 'routes';

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
	// 90d is bounded by the same `1d` interval (90 buckets) and the chart is
	// served by count-only `facet accumulate` queries (limit=0), so it scales
	// with bucket count rather than document volume; the history list is capped
	// at HISTORY_MAX_ROWS newest-first. Neither query grows materially with the
	// window, so a 90-day range is no heavier than 30d.
	{ key: '90d', label: 'Last 90d', intervalMs: 90 * 24 * 60 * 60 * 1000, interval: '1d' },
];

const STATUS_OPTIONS: { key: ExchangeStatusKey; label: string }[] = [
	{ key: 'completed', label: 'Completed' },
	{ key: 'failed', label: 'Failed' },
];

const ALL_STATUS_KEYS: ExchangeStatusKey[] = STATUS_OPTIONS.map(o => o.key);

// Elapsed band colour palette — the 11 Google-Calendar-style swatches (Tomato …
// Graphite), shared with the Memo app's colour menus via lib/color-palette.
const ELAPSED_COLORS = SWATCH_COLORS;
const ELAPSED_COLOR_MAP = SWATCH_COLOR_MAP;

// Elapsed slider — the visible track spans 0..ELAPSED_MAX_MS; the last band runs
// to infinity ("10s+"). Boundaries are kept strictly inside the track.
const ELAPSED_MAX_MS = 10_000;
const ELAPSED_MIN_GAP_MS = 100;
// Drag a handle this many px off the bar to mark it for removal.
const ELAPSED_REMOVE_DIST_PX = 36;
const DEFAULT_BAND_BOUNDARIES = [1000, 5000];
const DEFAULT_BAND_COLORS = ['peacock', 'banana', 'tomato'];

// History list pages in from the server HISTORY_PAGE rows at a time (initial
// page + "More"/scroll), accumulating up to HISTORY_MAX_ROWS.
const HISTORY_PAGE = 100;
const HISTORY_MAX_ROWS = 1000;
// History records land under
// /var/eip/history/{yyyy}/{MM}/{dd}/{HH}/{routeId}/{exchangeId}.json
// — see ExchangeHistoryEventNotifier. We subscribe to this subtree so newly
// recorded exchanges stream into the list without polling. Routes that declare
// the mi:history route property as "failure" or "none" record fewer or no
// exchanges, so they appear here rarely or not at all.
const HISTORY_BASE_PATH = '/var/eip/history';
// Coalesce bursts of node-change events (a single exchange write can touch the
// nt:file + jcr:content child) into one batch.
const LIVE_DEBOUNCE_MS = 400;
// Wall-clock tick that drives the live window: detects when `now` crosses a
// bucket boundary (so the chart + list slide one bucket and the chart re-anchors
// to an authoritative server snapshot) and keeps the grid-aligned filter window
// current. The right-edge bucket itself grows continuously between ticks as live
// records stream in — see flushLiveHistory / chartPoints.
const CLOCK_TICK_MS = 1000;
// How long a freshly-inserted row keeps its flash highlight. Must cover the
// CSS `eip-row-flash` animation so the class is present for its full duration.
const FLASH_DURATION_MS = 1300;
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

			// Live updates — the history list streams new exchanges pushed from
			// a node-change subscription on /var/eip/history (no polling). The
			// dot in the toolbar reflects whether that subscription is live.
			liveConnected: false,
			// Exchange IDs currently showing the "just added" flash highlight,
			// kept as a reactive map for O(1) per-row lookup in the template.
			flashIds: {} as Record<string, boolean>,
			_liveUnsub: null as null | (() => void),
			_routeStateUnsub: null as null | (() => void),
			_liveDebounceTimer: null as number | null,
			_routeReloadTimer: null as number | null,
			// Public (template-bound) flag for the Refresh button spinner.
			refreshing: false,

			// Active top-level screen (toolbar tab group)
			view: 'graph' as ConsoleView,

			// History list view sort — a client-side ordering applied over the
			// loaded rows (like `filterText`), NOT a server query parameter. It is
			// shared by both screens (graph + routes) so the operator's chosen
			// ordering carries over when switching views. Default: Started (the
			// server load order) descending — newest first.
			sortColumn: 'started' as HistorySortColumn,
			sortDirection: 'desc' as 'asc' | 'desc',

			// Graph-screen filters
			filterText: '',
			// Route multi-select. Seeded to "all routes" on launch; an empty
			// selection means "show nothing" (the All toggle mirrors this).
			selectedRouteIds: [] as string[],
			rangeKey: '1h' as RangeKey,
			// Status multi-select (Completed / Failed). Default: both selected.
			// An empty selection means "show nothing".
			statusFilter: ['completed', 'failed'] as ExchangeStatusKey[],
			// Elapsed bands — define the chart's elapsed-time series. Boundaries
			// (ms, ascending) split [0, ∞) into N+1 bands; each band has a
			// colour from the palette. The slider edits these; the chart asks
			// the server for per-band counts and paints each series its colour.
			bandBoundaries: DEFAULT_BAND_BOUNDARIES.slice(),
			bandColors: DEFAULT_BAND_COLORS.slice(),
			// Slider interaction state.
			_bandDrag: null as null | { index: number; rect: { left: number; width: number; top: number; height: number }; removing: boolean },
			colorPickerBand: null as number | null,

			// Routes-screen state
			manageFilterText: '',
			routeGroupExpand: {} as Record<string, boolean>,
			managedRoute: null as Route | null,
			// Route a right-click context menu was opened for (target of the
			// shell's context-menu-action reply).
			routeContextRoute: null as Route | null,

			// Routes (shared by both modes)
			routes: [] as Route[],
			routesLoaded: false,

			// Stats panel
			stats: null as RouteStats | null,
			statsLoading: false,

			// Live window clock. `_clockMs` is a reactive "current time" advanced by
			// a 1s tick; the grid-aligned filter window (rangeBounds) derives from it
			// so the window stays current without a refetch. `_statsBucketStart` is
			// the grid bucket (epoch ms) the current chart snapshot is right-anchored
			// to, and `_statsFetchMs` the instant it reflects — together they detect a
			// bucket-boundary slide and admit only newer records into the overlay.
			_clockMs: Date.now(),
			_clockTimer: null as number | null,
			_statsBucketStart: 0,
			_statsFetchMs: 0,
			// Per-bucket live band increments overlaid on the chart between the
			// 5-minute re-anchors, keyed by grid bucket start (epoch ms). Lets the
			// right-edge bucket grow in real time with no extra server round-trip.
			_liveBuckets: {} as Record<number, number[]>,

			// Chart dimensions — kept reactive so the SVG redraws when the
			// container resizes (ResizeObserver feeds these).
			chartWidth: 800,
			chartHeight: 240,
			_resizeObserver: null as ResizeObserver | null,

			// History panel — paged accumulator (initial page + More/scroll up
			// to HISTORY_MAX_ROWS). totalCount is the server's full match count,
			// used to decide whether more rows are available.
			historyEdges: [] as HistoryExchangeEdge[],
			historyLoading: false,        // first page in flight
			historyLoadingMore: false,    // a "More" page in flight
			historyTotalCount: 0,
			historyTruncated: false,
			historyCursor: null as string | null,   // endCursor for the next page
			_historySeen: null as Set<string> | null,

			// Chart drill-down: when a chart bucket is clicked the list narrows
			// to that bucket's time window until cleared.
			bucketFocus: null as null | { from: string; to: string; label: string },

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

		elapsedMaxMs(): number {
			return ELAPSED_MAX_MS;
		},

		/**
		 * Rendered elapsed bands derived from boundaries + colours. Drives the
		 * slider segments, the chart series colours, and the legend. Band i
		 * spans [boundaries[i-1] || 0, boundaries[i] || ∞); the last band is
		 * open-ended.
		 */
		bandSegments(): BandSegment[] {
			const vm = this as any;
			const bounds = (vm.bandBoundaries as number[]);
			const colors = (vm.bandColors as string[]);
			const n = bounds.length;
			const segs: BandSegment[] = [];
			for (let i = 0; i <= n; i++) {
				const fromMs = i === 0 ? 0 : bounds[i - 1];
				const toMs = i === n ? ELAPSED_MAX_MS : bounds[i];
				const last = i === n;
				const colorKey = colors[i] || DEFAULT_BAND_COLORS[i % DEFAULT_BAND_COLORS.length];
				const leftPct = (fromMs / ELAPSED_MAX_MS) * 100;
				const rightPct = (toMs / ELAPSED_MAX_MS) * 100;
				segs.push({
					index: i,
					fromMs,
					toMs,
					last,
					color: ELAPSED_COLOR_MAP[colorKey] || '#888888',
					colorKey,
					leftPct,
					widthPct: Math.max(0, rightPct - leftPct),
					label: last
						? `≥ ${formatElapsedMs(fromMs)}`
						: i === 0
							? `< ${formatElapsedMs(toMs)}`
							: `${formatElapsedMs(fromMs)} – ${formatElapsedMs(toMs)}`,
				});
			}
			return segs;
		},

		selectedRouteIdMap(): Record<string, boolean> {
			const m: Record<string, boolean> = {};
			for (const id of (this as any).selectedRouteIds as string[]) {
				m[id] = true;
			}
			return m;
		},

		statusFilterMap(): Record<ExchangeStatusKey, boolean> {
			const m = { completed: false, failed: false } as Record<ExchangeStatusKey, boolean>;
			for (const k of (this as any).statusFilter as ExchangeStatusKey[]) {
				m[k] = true;
			}
			return m;
		},

		// "All" toggle states — derived purely from the selections so toggling
		// individual rows flips the All icon automatically.
		allRoutesSelected(): boolean {
			const vm = this as any;
			const routes = vm.routes as Route[];
			return routes.length > 0 && (vm.selectedRouteIds as string[]).length === routes.length;
		},

		allStatusSelected(): boolean {
			return (this as any).statusFilter.length === ALL_STATUS_KEYS.length;
		},

		// An empty Route or Status selection means "show nothing" — the chart
		// and history list render empty and we skip the server round-trip.
		selectionEmpty(): boolean {
			const vm = this as any;
			return (vm.selectedRouteIds as string[]).length === 0
				|| (vm.statusFilter as ExchangeStatusKey[]).length === 0;
		},

		// Query params derived from the multi-selects (full selection → omit
		// the predicate entirely; a single status → that status).
		queryRoutes(): string[] | undefined {
			const vm = this as any;
			return vm.allRoutesSelected ? undefined : (vm.selectedRouteIds as string[]).slice();
		},

		queryStatus(): string | undefined {
			const vm = this as any;
			if (vm.allStatusSelected) return undefined;
			const s = vm.statusFilter as ExchangeStatusKey[];
			return s.length === 1 ? s[0] : undefined;
		},

		// Loaded rows narrowed by the table's Filter input and ordered by the
		// active column sort — both client-side, over the already-loaded rows
		// (no server round-trip). The underlying `historyEdges` array is left in
		// its newest-first form (the data/live/cap layer relies on that); the
		// sort is purely a view projection over a copy.
		visibleHistoryEdges(): HistoryExchangeEdge[] {
			const vm = this as any;
			const edges = vm.historyEdges as HistoryExchangeEdge[];
			const f = (vm.filterText as string || '').trim().toLowerCase();
			const filtered = !f ? edges : edges.filter((e) => {
				const n = e.node;
				return `${n.businessKey || ''}\n${n.exchangeId || ''}\n${n.routeId || ''}`
					.toLowerCase().includes(f);
			});
			const column = vm.sortColumn as HistorySortColumn;
			const dir = (vm.sortDirection as 'asc' | 'desc') === 'asc' ? 1 : -1;
			return filtered.slice().sort((a, b) => compareHistorySummary(a.node, b.node, column, dir));
		},

		// More rows are available when fewer than the total (and the cap) are
		// loaded. Driven by totalCount, which the server reports reliably.
		historyHasMore(): boolean {
			const vm = this as any;
			return vm.historyEdges.length < vm.historyTotalCount
				&& vm.historyEdges.length < HISTORY_MAX_ROWS;
		},

		// The cap is reached and the server holds still more rows.
		historyCapReached(): boolean {
			const vm = this as any;
			return vm.historyEdges.length >= HISTORY_MAX_ROWS
				&& vm.historyTotalCount > HISTORY_MAX_ROWS;
		},

		// The history list shows nothing: graph screen with no Route/Status
		// selected, or routes screen with no route selected.
		historySelectionEmpty(): boolean {
			const vm = this as any;
			return vm.view === 'routes' ? !vm.managedRoute : vm.selectionEmpty;
		},

		// Camel XML DSL of the selected route, dumped from the live engine. Fed to
		// the shared <eip-canvas> for a faithful, read-only diagram (routes screen,
		// upper pane). Empty string when no route / no model is available.
		routeModelXml(): string {
			const vm = this as any;
			const def = vm.managedRoute ? (vm.managedRoute as Route).definition : null;
			return (def && def.xml) ? def.xml : '';
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
		 * The chart's time series with the live overlay applied: the authoritative
		 * server snapshot (`stats.points`) plus the band increments accumulated
		 * from the live stream for records newer than the snapshot (`_liveBuckets`).
		 *
		 * Only the right-edge (in-progress) bucket actually grows between the
		 * 5-minute re-anchors; the overlay is keyed by grid bucket start so it
		 * lands on exactly the bucket the server would have counted the record in.
		 */
		chartPoints(): StatPoint[] {
			const vm = this as any;
			const stats = vm.stats as RouteStats | null;
			if (!stats || !stats.points.length) return [];
			const live = vm._liveBuckets as Record<number, number[]>;
			if (!live || Object.keys(live).length === 0) return stats.points;
			return stats.points.map((p) => {
				const add = live[Date.parse(p.bucket)];
				if (!add) return p;
				return { bucket: p.bucket, bands: p.bands.map((v, i) => v + (add[i] || 0)) };
			});
		},

		/**
		 * Render the multi-series chart as an inline SVG fragment.
		 *
		 * One line series per elapsed band (see bandSegments), each painted its
		 * configured colour, sharing a common Y axis scaled to the largest value
		 * across all bands. X labels show the first / middle / last buckets.
		 *
		 * The SVG viewBox is driven by reactive `chartWidth` / `chartHeight`
		 * tracked by a ResizeObserver so the chart fits the panel as it is
		 * resized.
		 */
		chartSvg(): string {
			const vm = this as any;
			const stats = vm.stats as RouteStats | null;
			const points = vm.chartPoints as StatPoint[];
			if (!stats || !points.length) return '';

			const W = vm.chartWidth as number;
			const H = vm.chartHeight as number;
			const padL = 48, padR = 16, padT = 12, padB = 28;
			const innerW = Math.max(1, W - padL - padR);
			const innerH = Math.max(1, H - padT - padB);

			const n = points.length;
			// Band count comes from the server response so the chart always
			// matches the returned data even if the slider config is mid-edit.
			const bandCount = points[0]?.bands?.length ?? 0;
			if (bandCount === 0) return '';
			const segs = vm.bandSegments as BandSegment[];
			const colorAt = (b: number) => segs[b]?.color || ELAPSED_COLOR_MAP[DEFAULT_BAND_COLORS[b % DEFAULT_BAND_COLORS.length]];

			let max = 1;
			for (const p of points) {
				for (let b = 0; b < bandCount; b++) {
					if (p.bands[b] > max) max = p.bands[b];
				}
			}

			const xStep = n > 1 ? innerW / (n - 1) : innerW;
			const xAt = (i: number) => padL + i * xStep;
			const yAt = (v: number) => padT + innerH - (v / max) * innerH;

			const seriesLine = (b: number) => {
				const color = colorAt(b);
				const pts = points.map((p, i) => `${xAt(i).toFixed(1)},${yAt(p.bands[b]).toFixed(1)}`).join(' ');
				const dots = points
					.map((p, i) => `<circle cx="${xAt(i).toFixed(1)}" cy="${yAt(p.bands[b]).toFixed(1)}" r="2.5" fill="${color}"/>`)
					.join('');
				return `<polyline fill="none" stroke="${color}" stroke-width="1.75" points="${pts}"/>${dots}`;
			};

			let series = '';
			for (let b = 0; b < bandCount; b++) series += seriesLine(b);

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
				${series}
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
		// filterText is a client-side view filter (visibleHistoryEdges) — no
		// server round-trip, so it is intentionally not watched here.
		statusFilter: { handler() { (this as any).scheduleReload(); }, deep: true },
		rangeKey() { (this as any).scheduleReload(); },
		selectedRouteIds: { handler() { (this as any).scheduleReload(); }, deep: true },
		// Elapsed boundaries only change the chart bands — refetch stats alone.
		// (Colour changes need no refetch; the chart recolours reactively.)
		bandBoundaries: { handler() { (this as any).scheduleStatsReload(); }, deep: true },
	},

	methods: {
		/**
		 * Reactive i18n lookup. Reads the localization snapshot so every
		 * `{{ t(...) }}` binding repaints the moment the user switches language
		 * or an i18n bundle is hot-reloaded. See composables/use-localization.ts.
		 */
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},

		/**
		 * Display label for an exchange status code ('completed' / 'failed').
		 * The compared value stays the literal code everywhere in logic; only the
		 * rendered label is localized here.
		 */
		statusLabel(key: string | null | undefined): string {
			const vm = this as any;
			if (key === 'completed') return vm.t('app.eip-console.status.completed', undefined, 'Completed');
			if (key === 'failed') return vm.t('app.eip-console.status.failed', undefined, 'Failed');
			return key || '';
		},

		/**
		 * Display label for a Camel route lifecycle status
		 * ('Started' / 'Stopped' / 'Suspended'). Unknown / missing falls back to
		 * the localized "Unknown".
		 */
		routeStatusLabel(status: string | null | undefined): string {
			const vm = this as any;
			if (status === 'Started') return vm.t('app.eip-console.status.started', undefined, 'Started');
			if (status === 'Stopped') return vm.t('app.eip-console.status.stopped', undefined, 'Stopped');
			if (status === 'Suspended') return vm.t('app.eip-console.status.suspended', undefined, 'Suspended');
			return vm.t('app.eip-console.status.unknown', undefined, 'Unknown');
		},

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
				// Shell context-menu selection. The same channel carries both the
				// routes-tree actions and the Elapsed colour-picker choices, told
				// apart by the action id's prefix.
				if (type === 'context-menu-action') {
					const action = payload.action as string;
					if (action && action.indexOf('set-band-color:') === 0) {
						vm.handleColorPickerAction(action.slice('set-band-color:'.length));
					} else {
						vm.handleRouteContextMenuAction(action);
					}
					return;
				}
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
				vm.instance.windowTitle = vm.t('app.eip-console.title', undefined, 'EIP Console');

				refreshLocalization(vm.localization, vm.instance);
				vm.workspace = appInstance.api.workspace;

				const client = createGraphQLClient(vm.workspace);
				vm.eip = vm.$markRaw(new EipServiceGraphQL(client));

				await vm.loadRoutes();
				// Default the Route multi-select to "all selected" (All ON).
				vm.selectedRouteIds = (vm.routes as Route[]).map((r) => r.routeId);
				// A drill-down (e.g. from the Dashboard) may target a specific
				// route and exchange status. Seed the filters before the first
				// reload so the initial query reflects the requested slice.
				vm.applyLaunchOptions(options);
				await vm.reloadAll();
				// Start streaming new exchanges into the list now that the
				// initial snapshot is in place.
				vm.setupLiveUpdates();
				// Drive the live window: slide one bucket at each boundary and keep
				// the grid-aligned filter window current.
				vm.startClock();

				vm.$nextTick(() => appInstance.notifyLaunched());
			};
		},

		onUnmount() {
			const vm = this as any;
			vm.teardownLiveUpdates();
			vm.stopClock();
			if (vm.messageListener) {
				window.removeEventListener('message', vm.messageListener);
			}
			document.removeEventListener('visibilitychange', vm.onVisibilityChange);
			vm.closeRawJsonViewer();
		},

		onVisibilityChange() {
			const vm = this as any;
			if (document.hidden) return;
			// A hidden tab skips clock ticks, so it may have slept across one or
			// more bucket boundaries. Catch up on return: refresh the clock and
			// re-anchor if the window has moved on.
			vm._clockMs = Date.now();
			vm.maybeAdvanceWindow();
		},

		// =====================================================
		// Live window clock
		//
		// A 1s tick advances `_clockMs` (so the grid-aligned filter window tracks
		// real time) and, when `now` crosses into a new bucket, re-anchors the
		// chart + list to the slid window. Between boundaries the right-edge bucket
		// grows purely from the live stream (see flushLiveHistory / chartPoints),
		// so no server round-trip happens until the boundary. Ticks pause while the
		// tab is hidden; onVisibilityChange catches up on return.
		// =====================================================
		startClock() {
			const vm = this as any;
			vm.stopClock();
			vm._clockTimer = window.setInterval(() => {
				if (document.hidden) return;
				vm._clockMs = Date.now();
				vm.maybeAdvanceWindow();
			}, CLOCK_TICK_MS);
		},

		stopClock() {
			const vm = this as any;
			if (vm._clockTimer != null) {
				window.clearInterval(vm._clockTimer);
				vm._clockTimer = null;
			}
		},

		/**
		 * When the wall clock crosses into a new grid bucket, re-anchor the chart
		 * (authoritative server snapshot for the slid window, which also settles
		 * the previously-growing bucket and corrects any live-overlay drift) and
		 * reload the history list for the new window — keeping its rows visible
		 * during the fetch so the slide is flicker-free. Suspended while a chart
		 * drill-down is focused so the slide doesn't move the inspected bucket.
		 */
		maybeAdvanceWindow() {
			const vm = this as any;
			if (vm.view !== 'graph') return;
			if (vm.bucketFocus) return;
			if (vm.selectionEmpty) return;
			if (vm.refreshing) return;
			const opt = vm.rangeOption();
			const bucketIntervalMs = intervalMsOf(opt.interval);
			const curBucketStart = Math.floor((vm._clockMs as number) / bucketIntervalMs) * bucketIntervalMs;
			if (curBucketStart > (vm._statsBucketStart as number)) {
				const ticket = ++vm._reloadTicket;
				vm.reloadStats(ticket);
				vm.reloadHistory(ticket, { keepRows: true });
			}
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
		// Chart drill-down — click a bucket to focus the list on its window.
		// (The chart SVG is rendered via v-html, so we map the click x to the
		// nearest bucket here rather than binding per-point handlers.)
		// =====================================================
		onChartClick(e: MouseEvent) {
			const vm = this as any;
			const stats = vm.stats as RouteStats | null;
			if (!stats || !stats.points.length) return;
			const area = (e.currentTarget as HTMLElement);
			const rect = area.getBoundingClientRect();
			if (rect.width <= 0) return;
			// Map client x → viewBox x → bucket index (chartSvg padding padL/padR).
			const padL = 48, padR = 16;
			const vbx = ((e.clientX - rect.left) / rect.width) * vm.chartWidth;
			const n = stats.points.length;
			const innerW = Math.max(1, vm.chartWidth - padL - padR);
			const xStep = n > 1 ? innerW / (n - 1) : innerW;
			let i = Math.round((vbx - padL) / xStep);
			i = Math.max(0, Math.min(n - 1, i));
			const from = stats.points[i].bucket;
			const to = i < n - 1 ? stats.points[i + 1].bucket : stats.to;
			const label = formatBucketLabel(from, stats.interval,
				vm.localization.locale || undefined, vm.localization.timeZone || undefined);
			vm.focusBucket(from, to, label);
		},

		focusBucket(from: string, to: string, label: string) {
			const vm = this as any;
			vm.bucketFocus = { from, to, label };
			vm.reloadHistory(++vm._reloadTicket);
		},

		clearBucketFocus() {
			const vm = this as any;
			if (!vm.bucketFocus) return;
			vm.bucketFocus = null;
			vm.reloadHistory(++vm._reloadTicket);
		},

		// Auto-load the next page when the list is scrolled near the bottom.
		onHistoryScroll(e: Event) {
			const vm = this as any;
			if (!vm.historyHasMore || vm.historyLoadingMore) return;
			const el = e.target as HTMLElement;
			if (el.scrollTop + el.clientHeight >= el.scrollHeight - 80) {
				vm.loadMoreHistory();
			}
		},

		// =====================================================
		// History list — column sort + elapsed-band colouring
		//
		// The sort is a client-side view ordering over the loaded rows (see
		// visibleHistoryEdges); it never re-queries the server. State lives at the
		// app level so it is shared by the graph and routes screens. The elapsed
		// colour reuses the same bands the chart paints, so an Exchange's Elapsed
		// reads in the colour of its band on either screen.
		// =====================================================
		toggleSort(column: HistorySortColumn) {
			const vm = this as any;
			if (vm.sortColumn === column) {
				// Same column → flip direction.
				vm.sortDirection = vm.sortDirection === 'asc' ? 'desc' : 'asc';
				return;
			}
			// New column → seed a sensible default direction: time / numeric columns
			// descending (newest / longest first), text columns ascending.
			vm.sortColumn = column;
			vm.sortDirection = (column === 'started' || column === 'elapsed') ? 'desc' : 'asc';
		},

		/**
		 * Resolve the elapsed-band colour for an elapsed time (ms), or '' when there
		 * is none (null/undefined). Mirrors the chart: the value is classified into
		 * a band by the current boundaries and painted the band's palette colour.
		 */
		elapsedColor(elapsed: number | null | undefined): string {
			const vm = this as any;
			if (elapsed === null || elapsed === undefined) return '';
			const segs = vm.bandSegments as BandSegment[];
			const idx = bandIndexOf(elapsed, vm.bandBoundaries as number[]);
			return segs[idx]?.color || '';
		},

		/** Band label (e.g. "1s – 5s") for the Elapsed cell tooltip. */
		elapsedBandLabel(elapsed: number | null | undefined): string {
			const vm = this as any;
			if (elapsed === null || elapsed === undefined) return '';
			const segs = vm.bandSegments as BandSegment[];
			const idx = bandIndexOf(elapsed, vm.bandBoundaries as number[]);
			return segs[idx]?.label || '';
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
		// Refresh — a plain click reloads everything (routes + chart + list).
		// =====================================================
		onRefreshClick() {
			(this as any).reloadAll();
		},

		// =====================================================
		// Live updates — push new exchanges into the history list.
		//
		// A node-change subscription on /var/eip/history (deep) names the exact
		// record file that changed, so we fetch just that one record (debounced
		// per burst), keep it if it matches the active filters, and merge it to
		// the top with a flash. No head-page refetch. The chart is left to
		// explicit refresh / filter changes — re-running the facet queries on
		// every event would be wasteful for a panel that reads fine at a glance.
		// =====================================================
		setupLiveUpdates() {
			const vm = this as any;
			vm.teardownLiveUpdates();
			// Raw (non-reactive) set of pending record paths from the burst.
			vm._livePendingPaths = vm.$markRaw ? vm.$markRaw(new Set<string>()) : new Set<string>();
			let eventHub: any = null;
			try { eventHub = vm.instance?.api?.eventHub; } catch { eventHub = null; }
			if (!eventHub || typeof eventHub.watchNode !== 'function') {
				vm.liveConnected = false;
				return;
			}
			try {
				vm._liveUnsub = eventHub.watchNode(HISTORY_BASE_PATH, (event: any) => {
					vm.onHistoryEvent(event);
				}, true /* deep */);
				vm.liveConnected = true;
			} catch {
				vm.liveConnected = false;
			}
			// Live route lifecycle (routeStateChanged): reflect start/stop/suspend/
			// resume in the route list the instant Camel transitions. The
			// post-mutation reloadRoutes() can race the asynchronous transition (and
			// misses routes changed elsewhere), so this is the authoritative live
			// signal for route status.
			if (typeof eventHub.watchAllRoutes === 'function') {
				try {
					vm._routeStateUnsub = eventHub.watchAllRoutes((event: any) => {
						vm.onRouteStateEvent(event);
					});
				} catch { /* noop */ }
			}
		},

		teardownLiveUpdates() {
			const vm = this as any;
			if (vm._liveDebounceTimer != null) {
				window.clearTimeout(vm._liveDebounceTimer);
				vm._liveDebounceTimer = null;
			}
			if (vm._routeReloadTimer != null) {
				window.clearTimeout(vm._routeReloadTimer);
				vm._routeReloadTimer = null;
			}
			if (vm._liveUnsub) {
				try { vm._liveUnsub(); } catch { /* noop */ }
				vm._liveUnsub = null;
			}
			if (vm._routeStateUnsub) {
				try { vm._routeStateUnsub(); } catch { /* noop */ }
				vm._routeStateUnsub = null;
			}
			if (vm._livePendingPaths) vm._livePendingPaths.clear();
			vm.liveConnected = false;
		},

		/**
		 * A route's lifecycle state changed (routeStateChanged subscription).
		 * Update the matching route's status in place so the list reflects
		 * start/stop/suspend/resume live. Reassigns the array (and managedRoute)
		 * so the reactive view re-renders.
		 */
		onRouteStateEvent(event: any) {
			const vm = this as any;
			const routeId = event && typeof event.routeId === 'string' ? event.routeId : '';
			const state = event && typeof event.currentState === 'string' ? event.currentState : '';
			if (!routeId || !state) return;
			const routes = vm.routes as Route[];
			const idx = routes.findIndex((r) => r.routeId === routeId);
			// Existing route: reflect the new status in place for instant feedback.
			if (idx !== -1 && routes[idx].status !== state) {
				const updated = { ...routes[idx], status: state as Route['status'] };
				const next = routes.slice();
				next[idx] = updated;
				vm.routes = next;
				if (vm.managedRoute && vm.managedRoute.routeId === routeId) {
					vm.managedRoute = updated;
				}
			}
			// routeStateChanged carries only state transitions of existing routes;
			// route ADDED/REMOVED (topology) is filtered out server-side. So a route we
			// don't have yet (idx === -1) was just deployed, and a route that stopped
			// may next be removed — reconcile the list (debounced) to pick up additions
			// and drop removals.
			vm.scheduleRouteReload();
		},

		/**
		 * Debounced full reload of the route list. routeStateChanged delivers only
		 * status transitions of existing routes (ADDED/REMOVED topology is filtered
		 * out server-side), so after a live state event we reconcile the list to pick
		 * up newly-deployed routes and drop removed ones. Bursts (e.g. Starting then
		 * Started) coalesce into a single query.
		 */
		scheduleRouteReload() {
			const vm = this as any;
			if (vm._routeReloadTimer != null) window.clearTimeout(vm._routeReloadTimer);
			vm._routeReloadTimer = window.setTimeout(() => {
				vm._routeReloadTimer = null;
				void vm.loadRoutes();
			}, LIVE_DEBOUNCE_MS);
		},

		/**
		 * Node-change event for a /var/eip/history record. The event already
		 * names the file, so remember its path and fetch it (debounced) — we
		 * only ever fetch the records that actually changed.
		 */
		onHistoryEvent(event: any) {
			const vm = this as any;
			const path = event && typeof event.path === 'string' ? event.path : '';
			if (!path || !path.endsWith('.json')) return;
			const kind = event.eventType;
			if (kind && kind !== 'CREATED' && kind !== 'MODIFIED') return;
			if (!vm._livePendingPaths) {
				vm._livePendingPaths = vm.$markRaw ? vm.$markRaw(new Set<string>()) : new Set<string>();
			}
			vm._livePendingPaths.add(path);
			if (vm._liveDebounceTimer != null) window.clearTimeout(vm._liveDebounceTimer);
			vm._liveDebounceTimer = window.setTimeout(() => {
				vm._liveDebounceTimer = null;
				vm.flushLiveHistory();
			}, LIVE_DEBOUNCE_MS);
		},

		/**
		 * Fetch each pushed record by its exact path, keep those matching the
		 * active filters, and merge them to the top of the list with a flash.
		 * Never blanks the list (no loading flicker); rows past the cap drop
		 * from the bottom (oldest).
		 */
		async flushLiveHistory() {
			const vm = this as any;
			if (!vm.eip) return;
			const pending: Set<string> = vm._livePendingPaths || new Set<string>();
			// A full reload fetches newest-first and subsumes anything pending.
			if (vm.refreshing || vm.historyLoading) { pending.clear(); return; }
			const paths = Array.from(pending) as string[];
			pending.clear();
			if (paths.length === 0) return;

			const ticket = vm._reloadTicket;
			const have = new Set((vm.historyEdges as HistoryExchangeEdge[]).map((e) => e.node.path));
			const wanted = paths.filter((p) => !have.has(p));
			if (wanted.length === 0) return;

			let records: (HistoryExchange | null)[];
			try {
				records = await Promise.all(
					wanted.map((p) => vm.eip.getHistoryExchange({ path: p }).catch(() => null))
				);
			} catch {
				return;
			}
			if (ticket !== vm._reloadTicket) return;

			const seen = have;
			const fresh: HistoryExchangeEdge[] = [];
			for (const rec of records) {
				if (!rec || !rec.path || seen.has(rec.path)) continue;
				if (!vm.matchesHistoryFilter(rec)) continue;
				seen.add(rec.path);
				fresh.push({ node: historySummaryOf(rec), cursor: '' });
			}
			if (fresh.length === 0) return;

			// Overlay these records onto the chart so the right-edge bucket grows
			// in real time between the 5-minute re-anchors. Same population as the
			// list (matchesHistoryFilter already gated them); each is bucketed by
			// createdAt on the server's grid and banded by elapsed via the
			// snapshot's boundaries. Only records newer than the snapshot are added
			// — older ones are already counted server-side.
			vm.overlayLiveStats(fresh);

			// Keep the paging seen-set in sync so a later "More" page can't
			// re-add a row that arrived live, and bump the total count.
			if (vm._historySeen) {
				for (const e of fresh) vm._historySeen.add(e.node.path);
			}
			vm.historyTotalCount += fresh.length;
			const merged = fresh.concat(vm.historyEdges as HistoryExchangeEdge[]);
			merged.sort(compareHistoryEdges);
			vm.historyEdges = merged.slice(0, HISTORY_MAX_ROWS);
			const survivors = new Set((vm.historyEdges as HistoryExchangeEdge[]).map((e) => e.node.path));
			vm.flashRows(fresh.map((e) => e.node.path).filter((p) => survivors.has(p)));
		},

		/**
		 * Fold freshly-streamed rows into the chart's live overlay (_liveBuckets),
		 * keyed by grid bucket start and split by elapsed band — mirroring exactly
		 * how the server would have counted them. Records at or before the snapshot
		 * instant are skipped (already in stats.points); the boundary re-anchor
		 * later replaces the overlay with an authoritative snapshot.
		 */
		overlayLiveStats(fresh: HistoryExchangeEdge[]) {
			const vm = this as any;
			const stats = vm.stats as RouteStats | null;
			if (!stats || !stats.points.length) return;
			const boundaries = stats.boundaries as number[];
			const bucketIntervalMs = intervalMsOf(stats.interval);
			const fetchMs = vm._statsFetchMs as number;
			const live = { ...(vm._liveBuckets as Record<number, number[]>) };
			let changed = false;
			for (const e of fresh) {
				const createdAtMs = Date.parse(e.node.createdAt || '');
				if (Number.isNaN(createdAtMs) || createdAtMs <= fetchMs) continue;
				const startMs = Math.floor(createdAtMs / bucketIntervalMs) * bucketIntervalMs;
				const bands = live[startMs] ? live[startMs].slice() : new Array(boundaries.length + 1).fill(0);
				bands[bandIndexOf(e.node.elapsed ?? 0, boundaries)] += 1;
				live[startMs] = bands;
				changed = true;
			}
			if (changed) vm._liveBuckets = live;
		},

		/**
		 * Client-side mirror of the historyExchanges server predicate, used to
		 * decide whether a live-pushed record belongs in the current view.
		 */
		matchesHistoryFilter(rec: HistoryExchange): boolean {
			const vm = this as any;
			// Routes screen: the list is just the selected route, all time.
			if (vm.view === 'routes') {
				return !!vm.managedRoute && rec.routeId === (vm.managedRoute as Route).routeId;
			}
			// Graph screen — mirror the server predicate.
			// Empty Route or Status selection → nothing matches.
			if (vm.selectionEmpty) return false;
			// Respect the chart drill-down window when one is focused.
			const [from, to] = vm.historyBounds();
			const t = Date.parse(rec.createdAt || '');
			if (!Number.isNaN(t) && (t < Date.parse(from) || t >= Date.parse(to))) return false;
			if (!vm.allRoutesSelected) {
				const routes = vm.selectedRouteIds as string[];
				if (!rec.routeId || !routes.includes(rec.routeId)) return false;
			}
			if (!vm.allStatusSelected) {
				if (!rec.status || !(vm.statusFilter as ExchangeStatusKey[]).includes(rec.status as ExchangeStatusKey)) return false;
			}
			// The Filter input is a client-side view filter (visibleHistoryEdges),
			// so a live row is admitted regardless and simply hidden if it does
			// not match the current text.
			return true;
		},

		flashRows(ids: string[]) {
			const vm = this as any;
			if (!ids || ids.length === 0) return;
			const next = { ...(vm.flashIds as Record<string, boolean>) };
			for (const id of ids) next[id] = true;
			vm.flashIds = next;
			window.setTimeout(() => {
				const cur = { ...(vm.flashIds as Record<string, boolean>) };
				for (const id of ids) delete cur[id];
				vm.flashIds = cur;
			}, FLASH_DURATION_MS);
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
		switchView(v: ConsoleView) {
			const vm = this as any;
			if (vm.view === v) return;
			vm.view = v;
			vm.bucketFocus = null;
			if (v === 'graph') {
				// Leaving route management — drop the route-detail selection so
				// the inspector isn't left showing a route in the graph screen.
				vm.managedRoute = null;
				if (vm.activeDetailType === 'route') {
					vm.activeDetailType = 'none';
				}
				const ticket = ++vm._reloadTicket;
				vm.reloadStats(ticket);
				vm.reloadHistory(ticket);
			} else {
				// Routes screen — the list is scoped to the selected route (empty
				// until one is picked); the chart isn't shown here.
				vm.reloadHistory(++vm._reloadTicket);
			}
		},

		// =====================================================
		// Route multi-select (+ All toggle)
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

		// All ON → select every route; All OFF → clear the selection.
		toggleAllRoutes() {
			const vm = this as any;
			vm.selectedRouteIds = vm.allRoutesSelected
				? []
				: (vm.routes as Route[]).map((r) => r.routeId);
		},

		// =====================================================
		// Status multi-select (+ All toggle)
		// =====================================================
		toggleStatus(key: ExchangeStatusKey) {
			const vm = this as any;
			const arr = vm.statusFilter as ExchangeStatusKey[];
			const idx = arr.indexOf(key);
			if (idx >= 0) {
				arr.splice(idx, 1);
			} else {
				arr.push(key);
			}
		},

		toggleAllStatus() {
			const vm = this as any;
			vm.statusFilter = vm.allStatusSelected ? [] : ALL_STATUS_KEYS.slice();
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
			if (options.status === 'all') {
				vm.statusFilter = ALL_STATUS_KEYS.slice();
			} else if (options.status === 'completed' || options.status === 'failed') {
				vm.statusFilter = [options.status];
			}
		},

		// =====================================================
		// Elapsed bands — multi-handle slider + colour palette
		// =====================================================
		msToPct(ms: number): number {
			return Math.max(0, Math.min(100, (ms / ELAPSED_MAX_MS) * 100));
		},

		// ----- Handle drag (move / drag-out to remove) -----
		onBandHandleDown(e: PointerEvent, index: number) {
			const vm = this as any;
			if (e && e.button !== 0) return;
			e.preventDefault();
			e.stopPropagation();
			const bar = (e.currentTarget as HTMLElement)?.closest('.elapsed-bar') as HTMLElement | null;
			const r = bar?.getBoundingClientRect();
			if (!r) return;
			vm._bandDrag = { index, rect: { left: r.left, width: r.width, top: r.top, height: r.height }, removing: false };
			document.addEventListener('pointermove', vm.onBandHandleMove);
			document.addEventListener('pointerup', vm.onBandHandleUp);
		},

		onBandHandleMove(e: PointerEvent) {
			const vm = this as any;
			const d = vm._bandDrag;
			if (!d) return;
			const rect = d.rect;
			const ratio = (e.clientX - rect.left) / Math.max(1, rect.width);
			let ms = Math.round(ratio * ELAPSED_MAX_MS);
			const bounds = (vm.bandBoundaries as number[]).slice();
			const lo = (d.index > 0 ? bounds[d.index - 1] : 0) + ELAPSED_MIN_GAP_MS;
			const hi = (d.index < bounds.length - 1 ? bounds[d.index + 1] : ELAPSED_MAX_MS) - ELAPSED_MIN_GAP_MS;
			ms = Math.max(lo, Math.min(hi, ms));
			bounds[d.index] = ms;
			vm.bandBoundaries = bounds;
			// Mark for removal when dragged well off the bar (only if more than
			// one band remains afterwards).
			const dy = e.clientY < rect.top ? rect.top - e.clientY : e.clientY - (rect.top + rect.height);
			d.removing = dy > ELAPSED_REMOVE_DIST_PX;
		},

		onBandHandleUp() {
			const vm = this as any;
			const d = vm._bandDrag;
			document.removeEventListener('pointermove', vm.onBandHandleMove);
			document.removeEventListener('pointerup', vm.onBandHandleUp);
			vm._bandDrag = null;
			if (d && d.removing) vm.removeBoundary(d.index);
		},

		// ----- Add / remove boundaries -----
		onBandTrackDblClick(e: MouseEvent) {
			const vm = this as any;
			const bar = (e.currentTarget as HTMLElement);
			const r = bar.getBoundingClientRect();
			const ratio = (e.clientX - r.left) / Math.max(1, r.width);
			vm.addBoundaryAt(Math.round(ratio * ELAPSED_MAX_MS));
		},

		// Add a boundary at the midpoint of the widest band (the toolbar "+").
		addBoundary() {
			const vm = this as any;
			const segs = vm.bandSegments as BandSegment[];
			let widest = segs[0];
			for (const s of segs) if ((s.toMs - s.fromMs) > (widest.toMs - widest.fromMs)) widest = s;
			vm.addBoundaryAt(Math.round((widest.fromMs + widest.toMs) / 2));
		},

		addBoundaryAt(ms: number) {
			const vm = this as any;
			ms = Math.max(ELAPSED_MIN_GAP_MS, Math.min(ELAPSED_MAX_MS - ELAPSED_MIN_GAP_MS, Math.round(ms)));
			const bounds = (vm.bandBoundaries as number[]).slice();
			// Reject if it collides with an existing boundary.
			if (bounds.some((b) => Math.abs(b - ms) < ELAPSED_MIN_GAP_MS)) return;
			let idx = bounds.findIndex((b) => b > ms);
			if (idx < 0) idx = bounds.length;
			bounds.splice(idx, 0, ms);
			// Splitting band `idx`: keep its colour for the left half, give the
			// right half a fresh colour (first unused palette entry).
			const colors = (vm.bandColors as string[]).slice();
			colors.splice(idx + 1, 0, vm.nextBandColor(colors));
			vm.bandBoundaries = bounds;
			vm.bandColors = colors;
		},

		removeBoundary(boundaryIndex: number) {
			const vm = this as any;
			const bounds = (vm.bandBoundaries as number[]).slice();
			if (boundaryIndex < 0 || boundaryIndex >= bounds.length) return;
			bounds.splice(boundaryIndex, 1);
			// Merge the two bands either side — drop the right band's colour.
			const colors = (vm.bandColors as string[]).slice();
			colors.splice(boundaryIndex + 1, 1);
			vm.bandBoundaries = bounds;
			vm.bandColors = colors;
			if (vm.colorPickerBand !== null && vm.colorPickerBand > bounds.length) {
				vm.colorPickerBand = null;
			}
		},

		// Fine-tune a boundary via the numeric input.
		onBoundaryInput(index: number, value: string) {
			const vm = this as any;
			const bounds = (vm.bandBoundaries as number[]).slice();
			if (index < 0 || index >= bounds.length) return;
			let ms = Math.round(Number(value));
			if (!Number.isFinite(ms)) return;
			const lo = (index > 0 ? bounds[index - 1] : 0) + ELAPSED_MIN_GAP_MS;
			const hi = (index < bounds.length - 1 ? bounds[index + 1] : ELAPSED_MAX_MS) - ELAPSED_MIN_GAP_MS;
			bounds[index] = Math.max(lo, Math.min(hi, ms));
			vm.bandBoundaries = bounds;
		},

		// ----- Colour palette -----
		nextBandColor(used: string[]): string {
			const taken = new Set(used);
			for (const c of ELAPSED_COLORS) {
				if (!taken.has(c.key)) return c.key;
			}
			return ELAPSED_COLORS[used.length % ELAPSED_COLORS.length].key;
		},

		// Raise the band-colour picker as a shell-rendered floating popup so it
		// escapes the app iframe (anchored to the clicked chip, dismissed by an
		// outside click) — the same mechanism as the routes-tree context menu.
		// The chosen colour returns via the 'context-menu-action' message with an
		// id of `set-band-color:<key>` (see onMounted / handleColorPickerAction).
		openColorPicker(bandIndex: number, e: MouseEvent) {
			const vm = this as any;
			vm.colorPickerBand = bandIndex;
			const current = (vm.bandColors as string[])[bandIndex];
			const items = ELAPSED_COLORS.map((c) => ({
				id: 'set-band-color:' + c.key,
				label: vm.t('app.eip-console.color.' + c.key, undefined, c.label),
				swatch: c.value,
				selected: c.key === current,
			}));
			// Anchor the grid just below the chip.
			const target = e.currentTarget as HTMLElement | null;
			const rect = target ? target.getBoundingClientRect() : { left: e.clientX, bottom: e.clientY } as DOMRect;
			try {
				window.parent.postMessage({
					type: 'show-context-menu',
					x: rect.left,
					y: rect.bottom + 4,
					variant: 'swatch-grid',
					columns: 6,
					items,
					sourceAppId: vm.instance?.id,
				}, window.location.origin);
			} catch { /* parent unavailable */ }
		},

		setBandColor(bandIndex: number, key: string) {
			const vm = this as any;
			const colors = (vm.bandColors as string[]).slice();
			if (bandIndex < 0 || bandIndex >= colors.length) return;
			colors[bandIndex] = key;
			vm.bandColors = colors;
			vm.colorPickerBand = null;
		},

		// Apply a colour chosen from the shell colour-picker popup. The action id
		// is `set-band-color:<key>`; the target band is the one openColorPicker
		// recorded in colorPickerBand.
		handleColorPickerAction(key: string) {
			const vm = this as any;
			if (vm.colorPickerBand === null) return;
			vm.setBandColor(vm.colorPickerBand, key);
		},

		// =====================================================
		// Manage mode — routes tree
		// =====================================================
		toggleGroupExpand(group: RouteGroup) {
			const vm = this as any;
			vm.routeGroupExpand[group.key] = !group.expanded;
		},

		async selectManagedRoute(route: Route | null) {
			const vm = this as any;
			vm.managedRoute = route;
			vm.activeDetailType = route ? 'route' : 'none';
			// Scope the centre exchange list to the selected route.
			vm.reloadHistory(++vm._reloadTicket);
			// Refresh the full route detail so the right-pane Exchanges section
			// shows current, complete counts (the tree node is a launch-time
			// snapshot). Guard against a newer selection landing meanwhile.
			if (route && vm.eip) {
				try {
					const detail = await vm.eip.getRoute(route.id);
					if (detail && vm.managedRoute && vm.managedRoute.id === detail.id) {
						vm.managedRoute = detail;
					}
				} catch { /* keep the snapshot on failure */ }
			}
		},

		// =====================================================
		// Routes tree — right-click context menu (shell-rendered)
		// =====================================================
		showRouteContextMenu(e: MouseEvent, route: Route) {
			const vm = this as any;
			vm.routeContextRoute = vm.$markRaw ? vm.$markRaw(route) : route;
			const items: any[] = [];
			if (route.status === 'Stopped') {
				items.push({ id: 'startRoute', label: vm.t('app.eip-console.action.start', undefined, 'Start'), icon: 'bi-play-fill' });
			}
			if (route.status === 'Started') {
				items.push({ id: 'suspendRoute', label: vm.t('app.eip-console.action.suspend', undefined, 'Suspend'), icon: 'bi-pause' });
			}
			if (route.status === 'Suspended') {
				items.push({ id: 'resumeRoute', label: vm.t('app.eip-console.action.resume', undefined, 'Resume'), icon: 'bi-play' });
			}
			if (route.status !== 'Stopped') {
				if (items.length > 0) items.push({ type: 'separator', id: '', label: '' });
				items.push({ id: 'stopRoute', label: vm.t('app.eip-console.action.stop', undefined, 'Stop'), icon: 'bi-stop-fill', danger: true });
			}
			if (items.length === 0) return;
			try {
				window.parent.postMessage({
					type: 'show-context-menu',
					x: e.clientX,
					y: e.clientY,
					items,
					sourceAppId: vm.instance?.id,
				}, window.location.origin);
			} catch { /* parent unavailable */ }
		},

		handleRouteContextMenuAction(action: string) {
			const vm = this as any;
			const route = vm.routeContextRoute as Route | null;
			if (!route) return;
			if (action !== 'startRoute' && action !== 'stopRoute'
				&& action !== 'suspendRoute' && action !== 'resumeRoute') return;
			// Target the action at the right-clicked route (also selects it).
			vm.selectManagedRoute(route);
			vm.confirmRouteAction(action as RouteAction);
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
				vm.errorMessage = ex?.message || vm.t('app.eip-console.error.loadRoutes', undefined, 'Failed to load routes');
			}
		},

		// Reload everything (routes + stats + history).
		async reloadAll() {
			const vm = this as any;
			if (!vm.eip) return;
			vm.refreshing = true;
			vm.bucketFocus = null;
			try {
				const ticket = ++vm._reloadTicket;
				await Promise.all([
					vm.loadRoutes(),
					vm.reloadStats(ticket),
					vm.reloadHistory(ticket),
				]);
			} finally {
				vm.refreshing = false;
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
			// A filter/route/range change rebuilds the chart buckets, so any
			// chart drill-down focus no longer applies.
			vm.bucketFocus = null;
			if (vm._scheduleTimer) window.clearTimeout(vm._scheduleTimer);
			vm._scheduleTimer = window.setTimeout(() => {
				vm._scheduleTimer = null;
				const ticket = ++vm._reloadTicket;
				vm.reloadStats(ticket);
				vm.reloadHistory(ticket);
			}, 200);
		},

		/**
		 * Debounced chart-only refetch — used when the elapsed band boundaries
		 * change (the history list is unaffected by the band definition).
		 */
		scheduleStatsReload() {
			const vm = this as any;
			if (!vm.eip) return;
			if (vm._statsTimer) window.clearTimeout(vm._statsTimer);
			vm._statsTimer = window.setTimeout(() => {
				vm._statsTimer = null;
				vm.reloadStats(++vm._reloadTicket);
			}, 200);
		},

		rangeOption(): typeof RANGE_OPTIONS[number] {
			const vm = this as any;
			return RANGE_OPTIONS.find(o => o.key === vm.rangeKey) || RANGE_OPTIONS[0];
		},

		// Time window for the history list: the chart drill-down focus when set,
		// otherwise the toolbar Range window.
		historyBounds(): [string, string] {
			const vm = this as any;
			if (vm.bucketFocus) return [vm.bucketFocus.from, vm.bucketFocus.to];
			return vm.rangeBounds();
		},

		// Server query parameters for the history list, by screen:
		//   graph  — Route/Status multi-selects + Range/focus window
		//   routes — just the selected route (all time)
		historyQueryParams(): { routes: string[] | undefined; status: string | undefined; from: string | undefined; to: string | undefined } {
			const vm = this as any;
			if (vm.view === 'routes') {
				const r = vm.managedRoute ? (vm.managedRoute as Route).routeId : null;
				return { routes: r ? [r] : [], status: undefined, from: undefined, to: undefined };
			}
			const [from, to] = vm.historyBounds();
			return { routes: vm.queryRoutes, status: vm.queryStatus, from, to };
		},

		// Grid-aligned window shared by the chart and the history list, so the two
		// always describe the same span. The right edge is the fixed wall-clock
		// bucket containing `now`; we walk back `count` whole buckets. This matches
		// the server's anchor-mode bucketing exactly (UTC floor to the interval),
		// so live records bucket onto the same grid the snapshot was built on.
		rangeBounds(): [string, string] {
			const vm = this as any;
			const opt = vm.rangeOption();
			const bucketIntervalMs = intervalMsOf(opt.interval);
			const nowMs = (vm._clockMs as number) || Date.now();
			const rightStart = Math.floor(nowMs / bucketIntervalMs) * bucketIntervalMs;
			const toMs = rightStart + bucketIntervalMs;
			const count = Math.max(1, Math.round(opt.intervalMs / bucketIntervalMs));
			const fromMs = rightStart - (count - 1) * bucketIntervalMs;
			return [new Date(fromMs).toISOString(), new Date(toMs).toISOString()];
		},

		async reloadStats(ticket?: number) {
			const vm = this as any;
			if (!vm.eip) return;
			const myTicket = ticket ?? ++vm._reloadTicket;
			const opt = vm.rangeOption();
			const bucketIntervalMs = intervalMsOf(opt.interval);
			const anchorMs = (vm._clockMs as number) || Date.now();
			// Anchor the chart to the fixed wall-clock bucket grid and reset the
			// live overlay. Remember which bucket is the right edge (boundary
			// detection) and the instant the snapshot reflects (so only newer
			// records are overlaid). Set before the early-return / await so a failed
			// or empty fetch can't leave _statsBucketStart=0 and re-fire every tick.
			vm._statsBucketStart = Math.floor(anchorMs / bucketIntervalMs) * bucketIntervalMs;
			vm._statsFetchMs = anchorMs;
			vm._liveBuckets = {};
			// Empty Route/Status selection → render an empty chart, no query.
			if (vm.selectionEmpty) {
				vm.stats = null;
				vm.statsLoading = false;
				return;
			}
			vm.statsLoading = true;
			vm.errorMessage = '';
			try {
				const count = Math.max(1, Math.round(opt.intervalMs / bucketIntervalMs));
				const stats = await vm.eip.getRouteStats({
					routes: vm.queryRoutes,
					at: new Date(anchorMs).toISOString(),
					buckets: count,
					status: vm.queryStatus,
					interval: opt.interval,
					elapsedBoundaries: (vm.bandBoundaries as number[]).slice(),
				});
				if (myTicket !== vm._reloadTicket) return;
				vm.stats = stats;
			} catch (ex: any) {
				if (myTicket !== vm._reloadTicket) return;
				vm.errorMessage = ex?.message || vm.t('app.eip-console.error.loadStats', undefined, 'Failed to load stats');
				vm.stats = null;
			} finally {
				if (myTicket === vm._reloadTicket) {
					vm.statsLoading = false;
				}
			}
		},

		/**
		 * Reload the history list from scratch — fetch the first HISTORY_PAGE
		 * rows. Further rows load on demand via loadMoreHistory ("More" / scroll)
		 * up to HISTORY_MAX_ROWS. A per-reload "seen" set dedups by node path so
		 * overlapping keyset pages (on a live dataset) never double a row.
		 */
		async reloadHistory(ticket?: number, opts?: { keepRows?: boolean }) {
			const vm = this as any;
			if (!vm.eip) return;
			const myTicket = ticket ?? ++vm._reloadTicket;
			// keepRows: leave the current rows on screen until the new page arrives
			// (used by the bucket-boundary slide so it doesn't blank-flicker). All
			// other callers blank first — e.g. a filter change must not show stale
			// rows that no longer match.
			const keep = !!(opts && opts.keepRows);
			if (!keep) {
				vm.historyEdges = [];
				vm.historyTotalCount = 0;
				vm.historyTruncated = false;
				vm.historyCursor = null;
			}
			vm._historySeen = vm.$markRaw ? vm.$markRaw(new Set<string>()) : new Set<string>();
			// Nothing selected (graph: no Route/Status; routes: no route) → empty.
			if (vm.historySelectionEmpty) {
				vm.historyEdges = [];
				vm.historyTotalCount = 0;
				vm.historyTruncated = false;
				vm.historyCursor = null;
				vm.historyLoading = false;
				return;
			}
			vm.historyLoading = true;
			vm.errorMessage = '';
			try {
				// Bind this reload's dedup set synchronously (see _fetchHistoryPage)
				// so an overlapping reload cannot share or pollute it.
				const page = await vm._fetchHistoryPage(undefined, vm._historySeen);
				if (myTicket !== vm._reloadTicket) return;
				if (!page) { vm.historyLoading = false; return; }
				// Keep the list's newest-first invariant on this path too, matching
				// loadMoreHistory and flushLiveHistory. The server already orders
				// the page, but sorting here makes the initial/refresh view robust
				// to any server-order drift (and consistent with the other paths).
				vm.historyEdges = page.edges.slice().sort(compareHistoryEdges);
				vm.historyTotalCount = page.total;
				vm.historyTruncated = page.total > HISTORY_MAX_ROWS;
				vm.historyCursor = page.cursor;
			} catch (ex: any) {
				if (myTicket !== vm._reloadTicket) return;
				vm.errorMessage = ex?.message || vm.t('app.eip-console.error.historySearch', undefined, 'History search failed');
			} finally {
				if (myTicket === vm._reloadTicket) {
					vm.historyLoading = false;
				}
			}
		},

		/**
		 * Append the next HISTORY_PAGE rows (triggered by the "More" row or by
		 * scrolling near the bottom). No-op while a load is in flight, when no
		 * more rows are available, or once the cap is reached.
		 */
		async loadMoreHistory() {
			const vm = this as any;
			if (!vm.eip) return;
			if (vm.historyLoading || vm.historyLoadingMore) return;
			if (!vm.historyHasMore || !vm.historyCursor) return;
			const myTicket = vm._reloadTicket;
			vm.historyLoadingMore = true;
			try {
				// Continue paging into the current reload's dedup set (bound
				// synchronously — see _fetchHistoryPage) so overlapping reloads
				// stay isolated.
				const page = await vm._fetchHistoryPage(vm.historyCursor, vm._historySeen);
				if (myTicket !== vm._reloadTicket) return;
				if (!page) return;
				if (page.edges.length > 0) {
					const merged = (vm.historyEdges as HistoryExchangeEdge[]).concat(page.edges);
					merged.sort(compareHistoryEdges);
					vm.historyEdges = merged.slice(0, HISTORY_MAX_ROWS);
				}
				vm.historyTotalCount = page.total;
				vm.historyTruncated = page.total > HISTORY_MAX_ROWS;
				vm.historyCursor = page.cursor;
			} catch {
				/* keep what we have; the user can retry via More/scroll */
			} finally {
				if (myTicket === vm._reloadTicket) vm.historyLoadingMore = false;
			}
		},

		/**
		 * Fetch one page after `cursor` under the active filters, dedup against
		 * the reload's seen-set, and return the new edges + cursor + total.
		 *
		 * The dedup `seen` set is passed in (captured synchronously by the caller)
		 * rather than read from `this._historySeen` here. That field is shared
		 * instance state, and reading it *after* the network `await` is unsafe:
		 * when two reloads overlap (e.g. on launch the initial reloadAll() races
		 * the debounced scheduleReload() fired by seeding selectedRouteIds, or any
		 * rapid filter change), the field has already been replaced by the newer
		 * reload's set. A discarded older page would then populate the *newer*
		 * reload's set, and the newer page — fetching the same rows — would dedup
		 * every one of them away, leaving an empty list with a non-zero
		 * totalCount ("0 of N", with "More" shown). Each reload generation owns
		 * its own set, so binding it before the await keeps overlapping reloads
		 * isolated.
		 */
		async _fetchHistoryPage(cursor: string | undefined, seen: Set<string>): Promise<null | { edges: HistoryExchangeEdge[]; cursor: string | null; total: number }> {
			const vm = this as any;
			const q = vm.historyQueryParams();
			const conn = await vm.eip.listHistoryExchanges({
				first: HISTORY_PAGE,
				after: cursor,
				routes: q.routes,
				status: q.status,
				from: q.from,
				to: q.to,
			});
			const fresh: HistoryExchangeEdge[] = [];
			for (const edge of conn.edges as HistoryExchangeEdge[]) {
				if (seen.has(edge.node.path)) continue;
				seen.add(edge.node.path);
				fresh.push(edge);
			}
			return {
				edges: fresh,
				cursor: conn.pageInfo.endCursor ?? null,
				total: conn.totalCount,
			};
		},

		// =====================================================
		// Inspector
		// =====================================================
		async openInspector(path: string) {
			const vm = this as any;
			if (!vm.eip) return;
			vm.errorMessage = '';
			try {
				// Address the exact record by path — a multi-route exchange has
				// one record per route under the same exchangeId.
				const detail = await vm.eip.getHistoryExchange({ path });
				if (detail) {
					vm.selectedExchange = detail;
					vm.activeDetailType = 'exchange';
					if (!vm.detailPanelVisible) vm.detailPanelVisible = true;
				} else {
					vm.errorMessage = vm.t('app.eip-console.error.exchangeNotFound', undefined, 'Exchange not found');
				}
			} catch (ex: any) {
				vm.errorMessage = ex?.message || vm.t('app.eip-console.error.loadExchange', undefined, 'Failed to load exchange');
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
			return (this as any).t('app.eip-console.detail.unnamedStep', undefined, '(unnamed step)');
		},

		// =====================================================
		// Dialogs — Route control (Input Object Pattern mutations)
		// =====================================================
		confirmRouteAction(action: RouteAction) {
			const vm = this as any;
			const route = vm.managedRoute as Route | null;
			if (!route) return;
			const meta = ROUTE_ACTION_META[action];
			// Per-action localized title / confirm label / confirmation message.
			// `action` is the literal mutation name (logic), `meta.verb` the English
			// fallback verb; titles and messages each get a whole-sentence key.
			const titleKey = `app.eip-console.dialog.${action}.title`;
			const msgKey = `app.eip-console.dialog.${action}.message`;
			const confirmKey = `app.eip-console.action.${meta.verb}`;
			vm.dialog = {
				kind: 'routeAction',
				action,
				routeId: route.routeId,
				title: vm.t(titleKey, undefined, capitalize(meta.verb) + ' Route'),
				message: vm.t(msgKey, { routeId: route.routeId }, `${capitalize(meta.verb)} route "${route.routeId}"?`),
				hint: action === 'stopRoute'
					? vm.t('app.eip-console.dialog.stopRoute.hint', undefined, 'Stopping the route will halt all in-flight exchanges.')
					: undefined,
				icon: meta.icon,
				confirmLabel: vm.t(confirmKey, undefined, capitalize(meta.verb)),
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
					message: ex?.message || vm.t(
						`app.eip-console.error.${d.action}`,
						undefined,
						`Failed to ${ROUTE_ACTION_META[d.action].verb} route`,
					),
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
					const container = vm.$refs.cmEditorExpanded as HTMLElement | undefined;
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
				const el = vm.$refs.cmSearchInput as HTMLInputElement | undefined;
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

/** Project a full exchange detail down to the list-row summary shape. */
function historySummaryOf(rec: HistoryExchange): HistoryExchangeSummary {
	return {
		path: rec.path,
		exchangeId: rec.exchangeId,
		routeId: rec.routeId ?? null,
		status: rec.status ?? null,
		elapsed: rec.elapsed ?? null,
		createdAt: rec.createdAt ?? null,
		businessKey: rec.businessKey ?? null,
	};
}

/**
 * Compare two history-row summaries for the table's column sort.
 *
 * `dir` is +1 (ascending) or -1 (descending). Ties always fall back to the
 * canonical newest-first order (compareHistorySummaryDefault) so the result is
 * a stable total order regardless of the chosen column — two rows that are
 * equal on the sort column keep a deterministic relative position.
 */
function compareHistorySummary(
	a: HistoryExchangeSummary,
	b: HistoryExchangeSummary,
	column: HistorySortColumn,
	dir: number,
): number {
	let cmp = 0;
	switch (column) {
		case 'started': {
			cmp = (Date.parse(a.createdAt || '') || 0) - (Date.parse(b.createdAt || '') || 0);
			break;
		}
		case 'elapsed': {
			cmp = (a.elapsed ?? 0) - (b.elapsed ?? 0);
			break;
		}
		case 'exchangeId':
			cmp = (a.exchangeId || '').localeCompare(b.exchangeId || '');
			break;
		case 'businessKey':
			cmp = (a.businessKey || '').localeCompare(b.businessKey || '');
			break;
		case 'status':
			cmp = (a.status || '').localeCompare(b.status || '');
			break;
		case 'route':
			cmp = (a.routeId || '').localeCompare(b.routeId || '');
			break;
	}
	if (cmp !== 0) return cmp * dir;
	// Stable tie-break: canonical newest-first order, independent of `dir`.
	return compareHistorySummaryDefault(a, b);
}

/**
 * Canonical newest-first ordering for history rows, matching the server's
 * `order by @mi:createdAt desc, @mi:exchangeId desc, @mi:routeId desc`. The
 * routeId tier keeps the two route-records of a multi-route exchange (which
 * share createdAt AND exchangeId) in a stable, deterministic order. Used as the
 * data-array order (live merge / cap) and as the column sort's tie-breaker.
 */
function compareHistorySummaryDefault(a: HistoryExchangeSummary, b: HistoryExchangeSummary): number {
	const ta = Date.parse(a.createdAt || '');
	const tb = Date.parse(b.createdAt || '');
	if (tb !== ta) return tb - ta;
	const byId = (b.exchangeId || '').localeCompare(a.exchangeId || '');
	if (byId !== 0) return byId;
	return (b.routeId || '').localeCompare(a.routeId || '');
}

function compareHistoryEdges(a: HistoryExchangeEdge, b: HistoryExchangeEdge): number {
	return compareHistorySummaryDefault(a.node, b.node);
}

/** Bucket interval in milliseconds for a server StatInterval. */
function intervalMsOf(interval: StatInterval): number {
	switch (interval) {
		case '5min': return 5 * 60 * 1000;
		case '1h': return 60 * 60 * 1000;
		case '1d': return 24 * 60 * 60 * 1000;
		default: return 5 * 60 * 1000;
	}
}

/**
 * Classify an elapsed time (ms) into a band index for the given ascending
 * boundaries — mirroring the server's elapsedBandPredicate so the live overlay
 * lands in the same band the snapshot used. Band 0 is `elapsed < boundaries[0]`,
 * the last band is `elapsed >= boundaries[last]`, interior bands are half-open.
 */
function bandIndexOf(elapsed: number, boundaries: number[]): number {
	for (let i = 0; i < boundaries.length; i++) {
		if (elapsed < boundaries[i]) return i;
	}
	return boundaries.length;
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

/** Compact elapsed label in seconds (e.g. 1000 → "1s", 500 → "0.5s"). */
function formatElapsedMs(ms: number): string {
	if (ms <= 0) return '0s';
	const s = ms / 1000;
	return (Number.isInteger(s) ? s.toString() : s.toFixed(1)) + 's';
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
import { BUILD_VERSION } from "../../utils/build-version.js";

// Inject the shared <eip-canvas> template before mounting so the custom
// element's connectedCallback (triggered during the first render) can find it.
async function loadEipCanvasTemplate(): Promise<void> {
	try {
		const res = await fetch(`../../components/eip-canvas.html?v=${BUILD_VERSION}`);
		const html = await res.text();
		const doc = new DOMParser().parseFromString(html, 'text/html');
		for (const tmpl of Array.from(doc.querySelectorAll('template'))) {
			document.body.appendChild(tmpl);
		}
	} catch (e) {
		console.error('Failed to load eip-canvas template', e);
	}
}

loadEipCanvasTemplate().then(() => {
	VDOM.createApp(App).mount('#app');
});

// Dashboard — a read-only, real-time operational overview that spans the BPM
// (process / task) and EIP (integration route) engines.
//
// It is deliberately two-perspective:
//
//   • "Today's Work" (business perspective) is shown to EVERY user and answers
//     "what should I do next?" — my open / due / overdue / claimable tasks, the
//     next things assigned to me, and where work is piling up across the team.
//
//   • "Operations" (system perspective) is shown only to ADMINISTRATORS and
//     answers "is the business running, and what's broken?" — deployed vs
//     suspended process definitions, running instances, open incidents, route
//     health & throughput, whether the external systems we integrate with
//     are reachable, the health of every repository workspace (lifecycle
//     state plus per-workspace engine health), and the cluster topology
//     (every registered node with its heartbeat health — or the fact that
//     this is a single-node deployment, so an operator never wonders
//     whether a cluster card is simply missing).
//
// Every panel carries an internal `audience` ("user" | "admin") so role drives
// what renders; the two heroes intentionally differ in viewpoint rather than
// just hiding cards. Panels drill into the matching console (Tasks, BPM
// Console, EIP Console) carrying their filter so the operator lands on the
// exact slice they clicked.
//
// Data comes from the same GraphQL services the consoles use
// (BpmServiceGraphQL / EipServiceGraphQL), aggregated client-side. There is no
// bespoke server endpoint: reusing the consoles' tested query path keeps the
// dashboard from drifting from the tools it links into.

import { VDOM } from '@mintjamsinc/ichigojs';
import { createGraphQLClient } from '../../graphql/client.js';
import { BpmServiceGraphQL } from '../../services/bpm-service-graphql.js';
import { EipServiceGraphQL } from '../../services/eip-service-graphql.js';
import { WebtopServiceGraphQL } from '../../services/webtop-service-graphql.js';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatNumber,
	formatDate,
} from '../../composables/use-localization.js';
import type {
	Task,
	ProcessDefinition,
	Incident,
	Route,
	Endpoint,
} from '../../graphql/types.js';

// Type-only: the shell passes a fully-featured ApplicationInstance at launch.
type AnyInstance = any;

// Drill-down targets (app.yml `identifier` of each console).
const TASKS_APP_ID = '9c2f0e8a-7b1d-4f9a-bc28-3e51d8a9f421';
const BPM_CONSOLE_APP_ID = '29a261c2-abb3-4214-ab1f-d1796524d391';
const EIP_CONSOLE_APP_ID = '8ad27e16-2c83-4eb5-9e15-7d8a4f7d9c1a';
const WORKSPACE_MANAGER_APP_ID = '3f6a9c1e-58d2-4b7a-9e04-c7b51a2d8f63';

const POLL_INTERVAL_MS = 60000;
// How many rows the ranked "top N" lists show (task bottlenecks, top incident
// processes, failing routes, …).
const TOP_N = 5;
// Upper bound on rows pulled for client-side aggregation. The headline counts
// always come from server-side `totalCount` / `taskCounts`, so this cap only
// affects the relative ranking of the top-N lists, never the totals shown.
const SCAN_LIMIT = 500;

// --- Formatting helpers ----------------------------------------------------
// Locale-aware number formatting lives on the component (`fmtNum` / `fmtCompact`)
// so it can read the localization snapshot and repaint on a locale change.

function formatLatency(ms: number | undefined | null): string {
	const n = Number(ms);
	if (!Number.isFinite(n) || n <= 0) return '—';
	if (n < 1000) return `${Math.round(n)} ms`;
	return `${(n / 1000).toFixed(1)} s`;
}

// Humanize a process/task definition key into something readable when the
// engine gives us no display name ("order-review-flow" -> "Order review flow").
function humanizeKey(key: string): string {
	const s = String(key || '').replace(/[._-]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').trim();
	if (!s) return '';
	return s.charAt(0).toUpperCase() + s.slice(1);
}

function ageLabel(fromIso: string | number | undefined, now: number): string {
	if (fromIso == null) return '—';
	const t = typeof fromIso === 'number' ? fromIso : Date.parse(fromIso);
	if (!Number.isFinite(t)) return '—';
	const ms = Math.max(0, now - t);
	const min = Math.floor(ms / 60000);
	if (min < 60) return `${min}m`;
	const hr = Math.floor(min / 60);
	if (hr < 24) return `${hr}h`;
	const d = Math.floor(hr / 24);
	return `${d}d`;
}

// Extract a short host/label for an endpoint URI so the external-connections
// list stays readable ("https://api.example.com/v2/..." -> "api.example.com").
function endpointLabel(uri: string, component: string): string {
	const raw = String(uri || '');
	const m = raw.match(/^[a-zA-Z0-9+.-]+:\/\/([^/?#]+)/);
	if (m && m[1]) return m[1];
	const scheme = raw.split(':')[0];
	return scheme || component || raw || 'endpoint';
}

// Camel reports route/endpoint/context status via ServiceStatus.name(), which
// is capitalized ("Started", "Stopped", "Suspended", "Starting", …) — NOT the
// upper-cased spelling the GraphQL TS enums imply. Bucket case-insensitively so
// a casing change on the wire can never silently flip a running route to
// "stopped" again.
function routeBucket(status: string | undefined | null): 'started' | 'suspended' | 'stopped' {
	const s = String(status || '').toLowerCase();
	if (s.startsWith('start')) return 'started';       // Started, Starting
	if (s.startsWith('suspend')) return 'suspended';   // Suspended, Suspending
	return 'stopped';                                  // Stopped, Stopping, Unknown
}


const EMPTY_MODEL = () => ({
	work: { myOpen: 0, dueToday: 0, overdue: 0, claimable: 0, next: [] as any[] },
	myTasks: { total: 0, overdue: 0, dueToday: 0, dueThisWeek: 0 },
	queue: { unassigned: 0, claimable: 0, teamTotal: 0 },
	bottlenecks: [] as any[],
	defs: { deployed: 0, active: 0, suspended: 0 },
	running: { active: 0, suspended: 0, withIncidents: 0, top: [] as any[] },
	incidents: { total: 0, byType: [] as any[], topProcesses: [] as any[] },
	routes: { total: 0, started: 0, stopped: 0, suspended: 0, contextState: '—', contextStateRaw: '', uptime: '' },
	throughput: { total: 0, completed: 0, failed: 0, inflight: 0, meanLatency: '—', failing: [] as any[] },
	connections: { remoteTotal: 0, down: 0, problems: [] as any[] },
	// `enabled` distinguishes "single-node deployment" (a normal, healthy
	// state worth saying out loud) from "cluster with members". `stale`
	// counts members whose heartbeat the SERVER judged stale — a presumed
	// dead or partitioned node, which is an operational incident.
	cluster: { enabled: false, nodeId: '', total: 0, alive: 0, stale: 0, members: [] as any[] },
	// Repository-wide workspace health. `starting` counts workspaces whose
	// CMS services are still coming up; `failed` counts workspaces whose
	// services failed to start (an outage needing intervention, not a wait);
	// `engineIssues` counts workspaces with an engine that is enabled but not
	// running — a failed engine start, an outage for that workspace's
	// processes or routes.
	workspaces: { total: 0, online: 0, starting: 0, failed: 0, engineIssues: 0, list: [] as any[] },
});

const App = {
	data() {
		return {
			instance: null as AnyInstance,

			// Reactive Localization snapshot (effective locale + IANA time
			// zone + number format). `t()`, number and time displays read this
			// and repaint on `localization-changed` / `i18n-bundles-updated`.
			// See composables/use-localization.ts.
			localization: createLocalizationSnapshot(),

			view: 'loading' as 'loading' | 'error' | 'ready',
			errorMessage: '',
			model: EMPTY_MODEL(),
			// Epoch ms of the last successful snapshot (0 = never). Formatted
			// reactively via the `lastUpdatedLabel` computed so it repaints in
			// the user's locale / time zone on a localization change.
			lastUpdated: 0,
			refreshing: false,
			connected: false,

			isAdmin: false,
			userId: '' as string,
			userDisplay: '' as string,
			userGroups: [] as string[],
			// Whether the Workspace Manager app is deployed in this workspace.
			// The Workspaces card only links into it when it is actually
			// present here (it can be deployed to the system workspace only),
			// so the link is never a dead end. Optimistic by default: only a
			// positive "absent" result hides the link, so a transient app-list
			// failure never hides a working link.
			workspaceManagerAvailable: true,

			_workspace: '' as string,
			_bpm: null as BpmServiceGraphQL | null,
			_eip: null as EipServiceGraphQL | null,
			_webtop: null as WebtopServiceGraphQL | null,
			_pollTimer: null as any,
			_messageListener: null as any,
			// Live workspace updates: refresh promptly when any workspace starts,
			// stops, or has its settings (e.g. display name) edited, rather than
			// waiting for the next poll. Unsubscribe handle, cleared on unmount.
			_workspacesUnsub: null as null | (() => void),
		};
	},

	computed: {
		greeting(): string {
			const name = this.userDisplay || this.userId;
			const h = new Date().getHours();
			const key = h < 5 ? 'app.dashboard.greeting.hello'
				: h < 12 ? 'app.dashboard.greeting.morning'
					: h < 18 ? 'app.dashboard.greeting.afternoon'
						: 'app.dashboard.greeting.evening';
			const fallbackPart = h < 5 ? 'Hello' : h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
			// Whole-sentence keys (named vs anonymous) so word order can differ
			// per language; never concatenate the greeting and the name.
			return name
				? this.t(key + 'Named', { name }, `${fallbackPart}, ${name}`)
				: this.t(key, undefined, fallbackPart);
		},

		// Locale/zone-aware time of the last refresh. Reads the snapshot via
		// formatDate so it repaints on a localization change.
		lastUpdatedLabel(): string {
			if (!this.lastUpdated) return '';
			return formatDate(this.localization, this.lastUpdated, { format: 'time' });
		},

		work(): any { return this.model.work; },
		myTasks(): any { return this.model.myTasks; },
		queue(): any { return this.model.queue; },
		bottlenecks(): any[] { return this.model.bottlenecks; },
		defs(): any { return this.model.defs; },
		running(): any { return this.model.running; },
		incidents(): any { return this.model.incidents; },

		routes(): any {
			const r = this.model.routes;
			// Severity pill is derived from the raw (untranslated) state so the
			// bucketing keeps working regardless of display language.
			return { ...r, contextPill: pillForContextState(r.contextStateRaw || r.contextState) };
		},

		throughput(): any {
			const t = this.model.throughput;
			return {
				...t,
				completedLabel: this.fmtCompact(t.completed),
				failedLabel: this.fmtCompact(t.failed),
			};
		},

		connections(): any {
			const c = this.model.connections;
			return { ...c, healthyLabel: `${this.fmtNum(Math.max(0, c.remoteTotal - c.down))}/${this.fmtNum(c.remoteTotal)}` };
		},

		cluster(): any {
			return this.model.cluster;
		},

		workspaces(): any {
			return this.model.workspaces;
		},

		ops(): any {
			const m = this.model;
			const failed = m.throughput.failed;
			// Outage tier: a stale cluster member is a presumed dead node, an
			// engine that is enabled but not running means a workspace's
			// processes or routes are down, and a workspace whose services
			// FAILED to start will not come up on its own — all same tier as
			// incidents. A workspace merely STARTING is only the attention
			// tier: right after creation or cluster discovery it resolves by
			// itself.
			const overall = (m.incidents.total > 0 || m.connections.down > 0 || m.cluster.stale > 0 || m.workspaces.engineIssues > 0 || m.workspaces.failed > 0)
				? { cls: 'danger', label: this.t('app.dashboard.ops.overall.issues', undefined, 'Issues') }
				: (failed > 0 || m.running.suspended > 0 || m.routes.stopped > 0 || m.workspaces.starting > 0)
					? { cls: 'warn', label: this.t('app.dashboard.ops.overall.attention', undefined, 'Attention') }
					: { cls: 'ok', label: this.t('app.dashboard.ops.overall.healthy', undefined, 'Healthy') };
			return {
				runningProcesses: this.fmtNum(m.running.active),
				activeRoutes: m.routes.started,
				totalRoutes: m.routes.total,
				openIncidents: m.incidents.total,
				failedExchanges: failed,
				failedExchangesLabel: this.fmtCompact(failed),
				extIssues: m.connections.down,
				overall,
			};
		},
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

		// Locale-aware integer formatting (grouping separators) routed through
		// the localization snapshot so counts repaint on a locale change.
		fmtNum(n: number): string {
			if (!Number.isFinite(n)) return '0';
			return formatNumber(this.localization, n, { maximumFractionDigits: 0 });
		},

		// Compact count for the big KPIs (1234 -> 1.2k / 1.2万), locale-aware.
		fmtCompact(n: number): string {
			if (!Number.isFinite(n)) return '0';
			if (Math.abs(n) >= 1000) return formatNumber(this.localization, n, { notation: 'compact', maximumFractionDigits: 1 });
			return String(n);
		},

		onMounted() {
			const vm = this;

			// Mirror shell theme changes onto <html data-theme>, like every app.
			vm._messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const data: any = event.data || {};
				// Localization changes (locale / time zone / bundle hot-reload)
				// broadcast by the shell. Fold them into the reactive snapshot so
				// every `t()` and date/number binding repaints.
				if (handleLocalizationMessage(data.type, vm.localization, vm.instance)) return;
				if (data.type === 'theme-changed' && data.theme) {
					document.documentElement.dataset.theme = data.theme;
				}
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance) => {
				vm.instance = vm.$markRaw(instance);

				try {
					const theme = instance.api.theme.currentTheme || 'light';
					document.documentElement.dataset.theme = theme;
				} catch (_) { /* theme service unavailable */ }

				// Snapshot the effective Localization preference so `t()`, date
				// and number bindings render in the user's language from the
				// first paint.
				refreshLocalization(vm.localization, vm.instance);

				try { instance.windowTitle = vm.t('app.dashboard.title', undefined, 'Dashboard'); } catch (_) {}

				vm.resolveUser();
				vm._workspace = (() => { try { return instance.api.workspace as string; } catch (_) { return ''; } })();
				vm.initServices();
				vm.detectWorkspaceManager();

				await vm.load();
				vm.setupRealtime();

				// Keep the Workspaces card live: a workspace started/stopped or
				// renamed elsewhere should reflect here without waiting for the poll.
				// The snapshot is rebuilt wholesale (the model is markRaw and only
				// re-renders on reassignment), so a full refresh is the right hook.
				try {
					vm._workspacesUnsub = instance.api?.eventHub?.watchWorkspaces(() => vm.refresh()) || null;
				} catch (err) {
					console.warn('[Dashboard] Failed to subscribe to workspace changes:', err);
				}

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._pollTimer) clearInterval(this._pollTimer);
			if (this._workspacesUnsub) {
				try { this._workspacesUnsub(); } catch { /* noop */ }
				this._workspacesUnsub = null;
			}
		},

		// ---- Identity --------------------------------------------------------
		resolveUser() {
			try {
				// The shell hands the app a live ApplicationInstance whose
				// `currentUser` is the authenticated User (with `isAdmin`). This
				// is the app-facing API; `window.Webtop` is the shell realm and is
				// not populated inside the app iframe, so it is only a fallback.
				const u = (this.instance && this.instance.currentUser) || (window as any).Webtop?.currentUser;
				if (u) {
					this.userId = u.id || '';
					this.userDisplay = u.fullName || u.id || '';
					this.isAdmin = !!u.isAdmin;
					const groups = (u.groups || []) as Array<{ groupId?: string; name?: string }>;
					this.userGroups = groups.map((g) => g.groupId || g.name || '').filter(Boolean);
				}
			} catch (_) { /* anonymous host context */ }
		},

		initServices() {
			const client = createGraphQLClient(this._workspace);
			this._bpm = this.$markRaw(new BpmServiceGraphQL(client));
			this._eip = this.$markRaw(new EipServiceGraphQL(client));
			this._webtop = this.$markRaw(new WebtopServiceGraphQL(client, { isAdmin: this.isAdmin }));
		},

		// Resolve once whether the Workspace Manager app is deployed in this
		// workspace, so the Workspaces card hides its drill-in link when it
		// is not (e.g. the app is installed in the system workspace only).
		// Only relevant for admins — the Operations section that carries the
		// link is admin-only — and the admin-mode app list includes admin-only
		// apps. On any failure the optimistic default (available) stands, so a
		// transient error never hides a link that does work.
		async detectWorkspaceManager() {
			if (!this.isAdmin || !this._webtop) return;
			try {
				const result = await this._webtop.listApps();
				this.workspaceManagerAvailable = result.apps.some(
					(a) => a.id === WORKSPACE_MANAGER_APP_ID);
			} catch (_) { /* keep the optimistic default */ }
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Drill-down ------------------------------------------------------
		// Launch (or re-target, since the consoles are singletons) the matching
		// console focused on the slice the user clicked. The shell focuses a
		// running console and delivers `{ type: 'app-reopen', options }`.
		openApp(appId: string, options?: Record<string, any>) {
			try {
				window.parent?.postMessage({ type: 'open-app', appId, options: options || {} }, window.location.origin);
			} catch (_) { /* parent unavailable */ }
		},
		openTasks(options?: Record<string, any>) { this.openApp(TASKS_APP_ID, options); },
		openBpm(options?: Record<string, any>) { this.openApp(BPM_CONSOLE_APP_ID, options); },
		openEip(options?: Record<string, any>) { this.openApp(EIP_CONSOLE_APP_ID, options); },
		openWorkspaceManager() {
			// Never open a dead end: the link/rows are already hidden or made
			// static when the app is absent, but guard here too.
			if (!this.workspaceManagerAvailable) return;
			this.openApp(WORKSPACE_MANAGER_APP_ID);
		},

		// ---- Data ------------------------------------------------------------
		async load() {
			try {
				this.model = this.$markRaw(await this.buildSnapshot());
				this.lastUpdated = Date.now();
				this.view = 'ready';
			} catch (e: any) {
				this.errorMessage = (e && e.message) ? e.message : String(e);
				this.view = 'error';
			}
		},

		async refresh() {
			if (this.refreshing) return;
			this.refreshing = true;
			try {
				this.model = this.$markRaw(await this.buildSnapshot());
				this.lastUpdated = Date.now();
				if (this.view !== 'ready') this.view = 'ready';
			} catch (e: any) {
				// Keep the last good data on a transient refresh failure; only show
				// the error screen if we never loaded anything.
				if (this.view !== 'ready') {
					this.errorMessage = (e && e.message) ? e.message : String(e);
					this.view = 'error';
				}
			} finally {
				this.refreshing = false;
			}
		},

		// Build the whole model. Each subsystem is wrapped so one failing engine
		// (or a permission gap) degrades that section to zeros rather than
		// blanking the dashboard.
		async buildSnapshot(): Promise<any> {
			const bpm = this._bpm;
			const eip = this._eip;
			if (!bpm) throw new Error(this.t('app.dashboard.error.bpmUnavailable', undefined, 'BPM service is unavailable.'));

			const now = Date.now();
			const model = EMPTY_MODEL();

			// Process-definition name map — shared by the work (bottlenecks) and
			// admin (definitions / running / incidents) sections.
			const defByKey: Record<string, ProcessDefinition> = {};
			try {
				const conn = await bpm.listProcessDefinitions({ latestVersion: true, first: 1000 });
				const all = conn.edges.map((e) => e.node);
				for (const d of all) defByKey[d.key] = d;
				const suspended = all.filter((d) => d.suspended).length;
				model.defs = { deployed: all.length, active: all.length - suspended, suspended };
			} catch (_) { /* keep zeros */ }
			const nameFor = (key: string): string => {
				const d = defByKey[key];
				return (d && (d.name || '')) || humanizeKey(key) || key || this.t('app.dashboard.fallback.process', undefined, 'Process');
			};

			// ---- Today's Work (every user) ----------------------------------
			await this.buildWork(bpm, model, nameFor, now);

			// ---- Operations (admins only) -----------------------------------
			if (this.isAdmin) {
				await Promise.all([
					this.buildBpmOps(bpm, model, nameFor),
					this.buildEipOps(eip, model),
					this.buildClusterOps(model, now),
					this.buildWorkspaceOps(model),
				]);
			}

			return model;
		},

		async buildWork(bpm: BpmServiceGraphQL, model: any, nameFor: (k: string) => string, now: number) {
			const me = this.userId;
			const groups = this.userGroups.length ? this.userGroups : undefined;

			// Headline counts come from the server (accurate, unbounded). Pass
			// the viewer's Localization time zone so "due today" / "due this
			// week" boundaries follow their calendar day (falls back to the
			// OS zone in the service when the preference is unset).
			const timeZone = this.localization?.timeZone || undefined;
			const [globalCounts, myCounts, claimCounts] = await Promise.all([
				bpm.getTaskCounts({ timeZone }).catch(() => null),
				me ? bpm.getTaskCounts({ assignee: me, timeZone }).catch(() => null) : Promise.resolve(null),
				me ? bpm.getTaskCounts({ candidateUser: me, candidateGroups: groups, timeZone }).catch(() => null) : Promise.resolve(null),
			]);

			const claimable = claimCounts ? claimCounts.total : 0;
			model.myTasks = {
				total: myCounts?.total ?? 0,
				overdue: myCounts?.overdue ?? 0,
				dueToday: myCounts?.dueToday ?? 0,
				dueThisWeek: myCounts?.dueThisWeek ?? 0,
			};
			model.queue = {
				unassigned: globalCounts?.unassigned ?? 0,
				claimable,
				teamTotal: globalCounts?.total ?? 0,
			};
			model.work = {
				myOpen: myCounts?.total ?? 0,
				dueToday: myCounts?.dueToday ?? 0,
				overdue: myCounts?.overdue ?? 0,
				claimable,
				next: [] as any[],
			};

			// Pull a bounded slice of open tasks for the "next up" list and the
			// cross-team bottleneck ranking.
			let tasks: Task[] = [];
			try {
				const conn = await bpm.listTasks({ first: SCAN_LIMIT });
				tasks = conn.edges.map((e) => e.node);
			} catch (_) { tasks = []; }

			// "Next up": my tasks, soonest due (then highest priority, then
			// oldest) first.
			const mine = me ? tasks.filter((t) => t.assignee === me) : [];
			mine.sort((a, b) => {
				const da = a.due ? Date.parse(a.due) : Number.POSITIVE_INFINITY;
				const db = b.due ? Date.parse(b.due) : Number.POSITIVE_INFINITY;
				if (da !== db) return da - db;
				if ((b.priority || 0) !== (a.priority || 0)) return (b.priority || 0) - (a.priority || 0);
				return Date.parse(a.created) - Date.parse(b.created);
			});
			model.work.next = mine.slice(0, TOP_N).map((t) => {
				const dueMs = t.due ? Date.parse(t.due) : NaN;
				let pill = '', pillClass = 'neutral';
				if (Number.isFinite(dueMs)) {
					if (dueMs < now) { pill = this.t('app.dashboard.pill.overdue', undefined, 'Overdue'); pillClass = 'danger'; }
					else if (dueMs - now < 24 * 3600 * 1000) { pill = this.t('app.dashboard.pill.today', undefined, 'Today'); pillClass = 'warn'; }
					else { pill = this.t('app.dashboard.pill.dueIn', { age: ageLabel(now, dueMs) }, `in ${ageLabel(now, dueMs)}`); pillClass = 'neutral'; }
				}
				const bkey = (t.businessKey || (t.processInstance && t.processInstance.businessKey)) || '';
				return {
					id: t.id,
					title: t.name || humanizeKey(t.taskDefinitionKey) || this.t('app.dashboard.fallback.task', undefined, 'Task'),
					sub: bkey ? `${nameFor(t.processDefinitionKey)} · ${bkey}` : nameFor(t.processDefinitionKey),
					pill,
					pillClass,
				};
			});

			// Bottlenecks: group open tasks by (process, task step) and rank by
			// how many are waiting — "which step is work piling up at?".
			const groupsMap = new Map<string, { processKey: string; taskKey: string; taskName: string; count: number; oldest: number }>();
			for (const t of tasks) {
				const pk = t.processDefinitionKey || '';
				const tk = t.taskDefinitionKey || t.name || '';
				const key = `${pk} ${tk}`;
				let g = groupsMap.get(key);
				if (!g) {
					g = { processKey: pk, taskKey: tk, taskName: t.name || humanizeKey(tk) || this.t('app.dashboard.fallback.task', undefined, 'Task'), count: 0, oldest: Number.POSITIVE_INFINITY };
				}
				g.count++;
				const c = Date.parse(t.created);
				if (Number.isFinite(c)) g.oldest = Math.min(g.oldest, c);
				groupsMap.set(key, g);
			}
			model.bottlenecks = Array.from(groupsMap.values())
				.sort((a, b) => (b.count - a.count) || (a.oldest - b.oldest))
				.slice(0, TOP_N)
				.map((g) => ({
					key: `${g.processKey} ${g.taskKey}`,
					processKey: g.processKey,
					taskKey: g.taskKey,
					taskName: g.taskName,
					processName: nameFor(g.processKey),
					count: g.count,
					oldestAge: Number.isFinite(g.oldest) ? ageLabel(g.oldest, now) : '—',
				}));
		},

		async buildBpmOps(bpm: BpmServiceGraphQL, model: any, nameFor: (k: string) => string) {
			// Running instances: one scan gives both the accurate total
			// (totalCount) and the per-definition ranking.
			try {
				const active = await bpm.listProcessInstances({ active: true, first: SCAN_LIMIT });
				const byKey = new Map<string, number>();
				for (const e of active.edges) {
					const k = e.node.definitionKey || '';
					byKey.set(k, (byKey.get(k) || 0) + 1);
				}
				model.running.active = active.totalCount ?? active.edges.length;
				model.running.top = Array.from(byKey.entries())
					.sort((a, b) => b[1] - a[1])
					.slice(0, TOP_N)
					.map(([key, count]) => ({ key, name: nameFor(key), count }));
			} catch (_) { /* keep zeros */ }

			// Suspended / incident-bearing instance counts (cheap totalCount).
			const [suspended, withIncidents] = await Promise.all([
				bpm.listProcessInstances({ suspended: true, first: 1 }).then((c) => c.totalCount).catch(() => 0),
				bpm.listProcessInstances({ withIncidents: true, first: 1 }).then((c) => c.totalCount).catch(() => 0),
			]);
			model.running.suspended = suspended || 0;
			model.running.withIncidents = withIncidents || 0;

			// Incidents: total + by type + which processes carry the most.
			try {
				const conn = await bpm.listIncidents({ first: SCAN_LIMIT });
				const list: Incident[] = conn.edges.map((e) => e.node);
				model.incidents.total = conn.totalCount ?? list.length;

				const byType = new Map<string, number>();
				const byProc = new Map<string, { count: number; sample: string }>();
				for (const inc of list) {
					byType.set(inc.type, (byType.get(inc.type) || 0) + 1);
					const pk = inc.processDefinitionKey || '';
					const g = byProc.get(pk) || { count: 0, sample: '' };
					g.count++;
					if (!g.sample) g.sample = inc.activityName || inc.message || '';
					byProc.set(pk, g);
				}
				model.incidents.byType = Array.from(byType.entries())
					.sort((a, b) => b[1] - a[1])
					.map(([label, count]) => ({ label, count }));
				model.incidents.topProcesses = Array.from(byProc.entries())
					.sort((a, b) => b[1].count - a[1].count)
					.slice(0, TOP_N)
					.map(([key, g]) => ({ key, name: nameFor(key), count: g.count, sample: g.sample }));
			} catch (_) { /* keep zeros */ }
		},

		async buildEipOps(eip, model) {
			if (!eip) return;

			// The route list is the reliable source: a route's `status` comes
			// from the Camel RouteController (available even when JMX is off),
			// and the per-route exchange stats — when JMX is on — let us derive
			// the context-level throughput by summation. The platform engine does
			// not expose an aggregate context object, so the route list is the only
			// source for the context-state card.
			let routes = [];
			try { routes = await eip.getDashboardRoutes(1000); } catch (_) { routes = []; }

			let started = 0, stopped = 0, suspended = 0;
			let exTotal = 0, exCompleted = 0, exFailed = 0, exInflight = 0;
			let meanWeightedSum = 0, meanWeight = 0;
			for (const r of routes) {
				const bucket = routeBucket(r.status);
				if (bucket === 'started') started++;
				else if (bucket === 'suspended') suspended++;
				else stopped++;

				exTotal += r.exchangesTotal || 0;
				exCompleted += r.exchangesCompleted || 0;
				exFailed += r.exchangesFailed || 0;
				exInflight += r.exchangesInflight || 0;
				const meanMs = Number(r.meanProcessingTime);
				const w = r.exchangesCompleted || 0;
				if (Number.isFinite(meanMs) && meanMs > 0 && w > 0) { meanWeightedSum += meanMs * w; meanWeight += w; }
			}
			model.routes.total = routes.length;
			model.routes.started = started;
			model.routes.stopped = stopped;
			model.routes.suspended = suspended;

			model.throughput.total = exTotal;
			model.throughput.completed = exCompleted;
			model.throughput.failed = exFailed;
			model.throughput.inflight = exInflight;
			const mean = meanWeight > 0 ? meanWeightedSum / meanWeight : 0;
			model.throughput.meanLatency = formatLatency(mean);

			// Routes currently producing failures, ranked.
			model.throughput.failing = routes
				.filter((r) => (r.exchangesFailed || 0) > 0)
				.sort((a, b) => (b.exchangesFailed || 0) - (a.exchangesFailed || 0))
				.slice(0, TOP_N)
				.map((r) => ({
					routeId: r.routeId,
					failed: r.exchangesFailed || 0,
					lastError: r.lastError ? r.lastError.message : '',
				}));

			// External connectivity: every remote endpoint a route talks to, and
			// whether the external system is actually reachable (Camel Health
			// Check readiness, L2). A 'DOWN' endpoint is a real connectivity
			// problem; 'UP'/'UNKNOWN' are treated as healthy so a route with no
			// health check never raises a false alarm.
			let remoteTotal = 0;
			let down = 0;
			const problems = [];
			for (const r of routes) {
				const eps = (r.endpoints || []).filter((e) => e.remote);
				for (const e of eps) {
					remoteTotal++;
					if (String(e.health || 'UNKNOWN').toUpperCase() === 'DOWN') {
						down++;
						problems.push({
							key: `${r.routeId} ${e.uri}`,
							routeId: r.routeId,
							uri: e.uri,
							component: e.component,
							state: this.t('app.dashboard.connections.down', undefined, 'Down'),
							label: endpointLabel(e.uri, e.component),
						});
					}
				}
			}
			model.connections.remoteTotal = remoteTotal;
			model.connections.down = down;
			model.connections.problems = problems.slice(0, 6);

			// Context state: the platform engine exposes per-route status, not an
			// aggregate context object, so derive the card's state from the routes.
			model.routes.contextStateRaw = started > 0 ? 'Started' : routes.length > 0 ? 'Stopped' : '';
			model.routes.contextState = started > 0
				? this.t('app.dashboard.context.started', undefined, 'Started')
				: routes.length > 0
					? this.t('app.dashboard.context.stopped', undefined, 'Stopped')
					: '—';
			model.routes.uptime = '';
		},

		// Cluster topology: which repository nodes are registered for this
		// workspace, and whether each one's heartbeat is fresh. `alive` is
		// the SERVER's judgement (the coordinator owns the staleness policy),
		// never re-derived client-side from the heartbeat timestamp. In a
		// standalone deployment `enabled` is false and the member list is
		// empty — the card then says "single node" explicitly instead of
		// pretending a one-member cluster.
		async buildClusterOps(model: any, now: number) {
			const webtop = this._webtop;
			if (!webtop) return;
			try {
				const info = await webtop.getCluster();
				model.cluster.enabled = !!info.enabled;
				model.cluster.nodeId = info.nodeId || '';
				const members = (info.members || []).map((m) => ({
					nodeId: m.nodeId,
					// The node id is the stable identity; the host name is the
					// human-friendly headline when the node could resolve one.
					name: m.hostName || m.nodeId,
					self: !!m.self,
					alive: !!m.alive,
					started: m.started,
					lastHeartbeat: m.lastHeartbeat,
					heartbeatAge: ageLabel(m.lastHeartbeat, now),
				}));
				model.cluster.members = members;
				model.cluster.total = members.length;
				model.cluster.alive = members.filter((m) => m.alive).length;
				model.cluster.stale = members.length - model.cluster.alive;
			} catch (_) { /* keep defaults (treated as standalone view) */ }
		},

		// Workspace health across the whole repository. The server reports a
		// lifecycle state per workspace (ONLINE / STARTING) plus the state of
		// its engines; "enabled but not running" is a failed engine start —
		// an outage for that workspace — and is surfaced as a degraded
		// workspace even though the workspace itself is online.
		async buildWorkspaceOps(model: any) {
			const webtop = this._webtop;
			if (!webtop) return;
			try {
				const list = await webtop.listWorkspaces();
				const rows = list.map((w) => {
					const online = w.state === 'ONLINE';
					const failed = w.state === 'FAILED';
					const stopped = w.state === 'STOPPED';
					const bpm = this.engineStatus(w.processEngine, online);
					const eip = this.engineStatus(w.integrationEngine, online);
					const degraded = online && (bpm.failed || eip.failed);
					return {
						name: w.name,
						// Human-friendly label shown in the list; falls back to the
						// name when no display name is configured. The name itself is
						// kept (and surfaced as the row tooltip) so the underlying
						// workspace remains identifiable.
						displayName: w.displayName || '',
						current: !!w.current,
						system: !!w.system,
						online,
						failed,
						degraded,
						// The FAILED reason, surfaced as a tooltip on the pill.
						stateMessage: w.stateMessage || '',
						enginesLabel: this.t('app.dashboard.workspaces.engines',
							{ bpm: bpm.label, eip: eip.label }, `BPM ${bpm.label} · EIP ${eip.label}`),
						pillClass: failed ? 'danger' : stopped ? 'neutral' : !online ? 'warn' : degraded ? 'danger' : 'ok',
						pillLabel: failed
							? this.t('app.dashboard.workspaces.pill.failed', undefined, 'Failed')
							: stopped
								? this.t('app.dashboard.workspaces.pill.stopped', undefined, 'Stopped')
								: !online
									? this.t('app.dashboard.workspaces.pill.starting', undefined, 'Starting')
									: degraded
										? this.t('app.dashboard.workspaces.pill.degraded', undefined, 'Degraded')
										: this.t('app.dashboard.workspaces.pill.online', undefined, 'Online'),
					};
				});
				model.workspaces.list = rows;
				model.workspaces.total = rows.length;
				model.workspaces.online = rows.filter((w) => w.online).length;
				model.workspaces.failed = rows.filter((w) => w.failed).length;
				model.workspaces.starting = rows.filter((w) => !w.online && !w.failed).length;
				model.workspaces.engineIssues = rows.filter((w) => w.degraded).length;
			} catch (_) { /* keep zeros */ }
		},

		// Classify one engine report. `enabled` is the configuration switch,
		// `running` the actual state; enabled-but-not-running is a failed
		// start (prepareServices logs the error and keeps the workspace up).
		// `enabled` is null while the workspace's services are not running.
		// `enabled` is the engine's configured intent (read from bpm.yml/eip.yml,
		// so it is known even while the workspace is stopped); `running` is its
		// live state. An engine only counts as "failed" when its workspace is
		// online but the (enabled) engine isn't running — while the workspace
		// itself is down its engine runtime is not applicable, shown as "—".
		engineStatus(e: { enabled: boolean | null; running: boolean } | null | undefined, online: boolean): { label: string; failed: boolean } {
			if (e && e.running) {
				return { label: this.t('app.dashboard.workspaces.engine.running', undefined, 'running'), failed: false };
			}
			if (!online) {
				return { label: '—', failed: false };
			}
			if (e && e.enabled === false) {
				return { label: this.t('app.dashboard.workspaces.engine.disabled', undefined, 'off'), failed: false };
			}
			if (e && e.enabled === true) {
				return { label: this.t('app.dashboard.workspaces.engine.failed', undefined, 'failed'), failed: true };
			}
			return { label: '—', failed: false };
		},

		// ---- Real-time -------------------------------------------------------
		// Engine state (tasks, instances, routes) lives outside JCR, so a slow
		// poll keeps the snapshot fresh. The indicator reflects that the loop is
		// armed; it stops while the tab is hidden to avoid needless load.
		setupRealtime() {
			const vm = this;
			vm._pollTimer = window.setInterval(() => {
				if (document.visibilityState === 'hidden') return;
				vm.refresh();
			}, POLL_INTERVAL_MS);
			vm.connected = true;
		},
	},
};

// Map a Camel context state label to a severity pill.
function pillForContextState(state: string): string {
	const s = String(state || '').toLowerCase();
	if (s.startsWith('start')) return 'ok';
	if (s.startsWith('suspend')) return 'warn';
	if (!s || s === '—') return 'neutral';
	return 'danger';
}

VDOM.createApp(App).mount('#app');

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
//     health & throughput, and whether the external systems we integrate with
//     are reachable.
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
import type {
	Task,
	ProcessDefinition,
	Incident,
	Route,
	Endpoint,
	CamelContext,
} from '../../graphql/types.js';

// Type-only: the shell passes a fully-featured ApplicationInstance at launch.
type AnyInstance = any;

// Drill-down targets (app.yml `identifier` of each console).
const TASKS_APP_ID = '9c2f0e8a-7b1d-4f9a-bc28-3e51d8a9f421';
const BPM_CONSOLE_APP_ID = '29a261c2-abb3-4214-ab1f-d1796524d391';
const EIP_CONSOLE_APP_ID = '8ad27e16-2c83-4eb5-9e15-7d8a4f7d9c1a';

const POLL_INTERVAL_MS = 60000;
// How many rows the ranked "top N" lists show (task bottlenecks, top incident
// processes, failing routes, …).
const TOP_N = 5;
// Upper bound on rows pulled for client-side aggregation. The headline counts
// always come from server-side `totalCount` / `taskCounts`, so this cap only
// affects the relative ranking of the top-N lists, never the totals shown.
const SCAN_LIMIT = 500;

// --- Formatting helpers ----------------------------------------------------
function formatNum(n: number): string {
	if (!Number.isFinite(n)) return '0';
	return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(n);
}

// Compact count for the big metrics (1234 -> 1.2k) so a busy context does not
// overflow the KPI typography.
function compact(n: number): string {
	if (!Number.isFinite(n)) return '0';
	if (Math.abs(n) >= 1000) return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(n);
	return String(n);
}

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
	routes: { total: 0, started: 0, stopped: 0, suspended: 0, contextState: '—', uptime: '' },
	throughput: { total: 0, completed: 0, failed: 0, inflight: 0, meanLatency: '—', failing: [] as any[] },
	connections: { remoteTotal: 0, down: 0, problems: [] as any[] },
});

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			view: 'loading' as 'loading' | 'error' | 'ready',
			errorMessage: '',
			model: EMPTY_MODEL(),
			lastUpdated: '',
			refreshing: false,
			connected: false,

			isAdmin: false,
			userId: '' as string,
			userDisplay: '' as string,
			userGroups: [] as string[],

			_workspace: '' as string,
			_bpm: null as BpmServiceGraphQL | null,
			_eip: null as EipServiceGraphQL | null,
			_pollTimer: null as any,
			_messageListener: null as any,
		};
	},

	computed: {
		greeting(): string {
			const name = this.userDisplay || this.userId;
			const h = new Date().getHours();
			const part = h < 5 ? 'Hello' : h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
			return name ? `${part}, ${name}` : part;
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
			return { ...r, contextPill: pillForContextState(r.contextState) };
		},

		throughput(): any {
			const t = this.model.throughput;
			return {
				...t,
				completedLabel: compact(t.completed),
				failedLabel: compact(t.failed),
			};
		},

		connections(): any {
			const c = this.model.connections;
			return { ...c, healthyLabel: `${formatNum(Math.max(0, c.remoteTotal - c.down))}/${formatNum(c.remoteTotal)}` };
		},

		ops(): any {
			const m = this.model;
			const failed = m.throughput.failed;
			const overall = (m.incidents.total > 0 || m.connections.down > 0)
				? { cls: 'danger', label: 'Issues' }
				: (failed > 0 || m.running.suspended > 0 || m.routes.stopped > 0)
					? { cls: 'warn', label: 'Attention' }
					: { cls: 'ok', label: 'Healthy' };
			return {
				runningProcesses: formatNum(m.running.active),
				activeRoutes: m.routes.started,
				totalRoutes: m.routes.total,
				openIncidents: m.incidents.total,
				failedExchanges: failed,
				failedExchangesLabel: compact(failed),
				extIssues: m.connections.down,
				overall,
			};
		},
	},

	methods: {
		onMounted() {
			const vm = this;

			// Mirror shell theme changes onto <html data-theme>, like every app.
			vm._messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const data: any = event.data || {};
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

				try { instance.windowTitle = 'Dashboard'; } catch (_) {}

				vm.resolveUser();
				vm._workspace = (() => { try { return instance.api.workspace as string; } catch (_) { return ''; } })();
				vm.initServices();

				await vm.load();
				vm.setupRealtime();

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._pollTimer) clearInterval(this._pollTimer);
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

		// ---- Data ------------------------------------------------------------
		async load() {
			try {
				this.model = this.$markRaw(await this.buildSnapshot());
				this.lastUpdated = new Date().toLocaleTimeString();
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
				this.lastUpdated = new Date().toLocaleTimeString();
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
			if (!bpm) throw new Error('BPM service is unavailable.');

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
				return (d && (d.name || '')) || humanizeKey(key) || key || 'Process';
			};

			// ---- Today's Work (every user) ----------------------------------
			await this.buildWork(bpm, model, nameFor, now);

			// ---- Operations (admins only) -----------------------------------
			if (this.isAdmin) {
				await Promise.all([
					this.buildBpmOps(bpm, model, nameFor),
					this.buildEipOps(eip, model),
				]);
			}

			return model;
		},

		async buildWork(bpm: BpmServiceGraphQL, model: any, nameFor: (k: string) => string, now: number) {
			const me = this.userId;
			const groups = this.userGroups.length ? this.userGroups : undefined;

			// Headline counts come from the server (accurate, unbounded).
			const [globalCounts, myCounts, claimCounts] = await Promise.all([
				bpm.getTaskCounts().catch(() => null),
				me ? bpm.getTaskCounts({ assignee: me }).catch(() => null) : Promise.resolve(null),
				me ? bpm.getTaskCounts({ candidateUser: me, candidateGroups: groups }).catch(() => null) : Promise.resolve(null),
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
					if (dueMs < now) { pill = 'Overdue'; pillClass = 'danger'; }
					else if (dueMs - now < 24 * 3600 * 1000) { pill = 'Today'; pillClass = 'warn'; }
					else { pill = `in ${ageLabel(now, dueMs)}`; pillClass = 'neutral'; }
				}
				const bkey = (t.businessKey || (t.processInstance && t.processInstance.businessKey)) || '';
				return {
					id: t.id,
					title: t.name || humanizeKey(t.taskDefinitionKey) || 'Task',
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
					g = { processKey: pk, taskKey: tk, taskName: t.name || humanizeKey(tk) || 'Task', count: 0, oldest: Number.POSITIVE_INFINITY };
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
			// the context-level throughput by summation. We deliberately do NOT
			// depend on getCamelContext() for the headline numbers; it is used
			// only as an optional enhancement for uptime / mean latency.
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
							state: 'Down',
							label: endpointLabel(e.uri, e.component),
						});
					}
				}
			}
			model.connections.remoteTotal = remoteTotal;
			model.connections.down = down;
			model.connections.problems = problems.slice(0, 6);

			// Context state / uptime: prefer the engine's own report, but fall
			// back to what the routes tell us so the card never shows a bogus
			// "—" / "Stopped" while routes are plainly running.
			let ctx = null;
			try { ctx = await eip.getCamelContext(); } catch (_) { ctx = null; }
			const ctxState = ctx && ctx.state ? String(ctx.state) : '';
			model.routes.contextState = ctxState
				? humanizeKey(ctxState)
				: (started > 0 ? 'Started' : routes.length > 0 ? 'Stopped' : '—');
			model.routes.uptime = (ctx && ctx.uptime) ? ctx.uptime : '';

			// If JMX gave us nothing per route but the engine exposes aggregate
			// throughput, surface that instead of a misleading zero.
			if (exTotal === 0 && ctx && (ctx.exchangesTotal || 0) > 0) {
				model.throughput.total = ctx.exchangesTotal || 0;
				model.throughput.completed = ctx.exchangesCompleted || 0;
				model.throughput.failed = ctx.exchangesFailed || 0;
				model.throughput.inflight = ctx.exchangesInflight || 0;
				if (mean === 0) model.throughput.meanLatency = formatLatency(ctx.meanProcessingTime);
			}
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

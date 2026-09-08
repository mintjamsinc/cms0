/**
 * Tasks Application
 *
 * Mail-like UI for starting business processes and processing user tasks.
 * Forms are user-authored HTML stored in CMS; they run inside a plain
 * SAME-ORIGIN iframe (no `sandbox`) and reach the host directly through the
 * `window.parent.TasksFormHost` bridge published by `installFormHost()`.
 *
 * There is no postMessage RPC any more. The bridge hands a form the live
 * GraphQL client and the BPM / CMS / IdP services this app already built, so
 * a form can issue any query the signed-in user is authorized for instead of
 * being limited to a hand-maintained method whitelist. Task lifecycle calls
 * (claim / complete / startProcess) still go through the bridge rather than
 * the raw service so the host list and selection stay in sync. The one place
 * a message still leaves this app is app launch (`openApp` / `openFile`),
 * which is the shell's job and always was.
 *
 * Modes:
 *   - tasks-runtime : active user tasks (assigned to me / my candidate groups)
 *   - start         : startable process definitions
 *
 * Layout: 3 panes — search filters / list / form iframe.
 */

import { initUi } from "../../ui/index.js";
import { createShellPopupAdapter } from "../../ui/shell-popup-adapter.js";
import { ApplicationInstance, type Application } from "../../services/webtop-service.js";
import { BpmServiceGraphQL } from "../../services/bpm-service-graphql.js";
import { IdpServiceGraphQL } from "../../services/idp-service-graphql.js";
import { ContentServiceGraphQL } from "../../services/content-service-graphql.js";
import { createGraphQLClient, GraphQLClient } from "../../graphql/client.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
} from "../../composables/use-localization.js";
import type {
	Task,
	TaskEdge,
	ProcessDefinition,
	ProcessDefinitionEdge,
	ProcessInstance,
	ProcessVariable,
	ProcessVariableInput,
	IdpUser,
	Node as CmsNode,
} from "../../graphql/types.js";

type Mode = 'tasks-runtime' | 'start';

interface Favorites {
	[processDefinitionKey: string]: boolean;
}

// Server-side scope for the runtime task list. Each option maps to a single
// listTasks call — Camunda 7 resolves the user's group membership via its
// ReadOnlyIdentityProvider when `candidateUser` is set, so the client never
// needs to send `candidateGroups`.
//   assigned  → assignee = me                 (tasks I own)
//   candidate → candidateUser = me             (tasks I could pick up)
//   all       → neither filter applied         (admin/triage view; engine
//                                              authorization still gates
//                                              what the user actually sees)
type TaskScope = 'assigned' | 'candidate' | 'all';

interface Filters {
	taskKeyword: string;
	defKeyword: string;
	scope: TaskScope;
	// Multi-select filter for the "tasks-runtime" view. An empty array means
	// "no process filter applied" (show tasks for any process).
	processDefinitionKeys: string[];
	dueRange: 'any' | 'today' | 'week' | 'overdue';
	priorityMin: number;
	category: string;
	favoritesOnly: boolean;
}

interface DialogState {
	type: string;
	data: Record<string, unknown>;
}

// Effective localization handed to the form. Mirrors the shell's
// LocalizationSnapshot fields the form needs to translate labels and format
// money / dates / numbers in the user's language. Carried in the context and
// re-announced on every shell `localization-changed` / `i18n-bundles-updated`
// broadcast — the theme-channel pattern, applied to locale.
interface FormLocalization {
	locale: string;
	timeZone: string;
	numberFormat: string;
	currency: string;
}

// Events the host announces to subscribed forms. `context` fires whenever the
// selection or the selected task's assignment changes without reloading the
// frame; `theme` / `localization` mirror the shell broadcasts.
type FormEventType = 'context' | 'theme' | 'localization';

type FormEventListener = (type: FormEventType, payload: unknown) => void;

// Suggestion popup for the "Assign Task" dialog. Module-scoped because the
// shell-managed popup outlives any single dialog open/close cycle and there
// can only be one open at a time.
let assigneePopupHandle: import('../../services/webtop-service.js').PopupHandle | null = null;

// How long the loading overlay waits for a form to call notifyReady() before
// it gives up and shows the failure state. Covers the document never loading,
// the form throwing during startup, and the form simply never signalling.
// Generous compared to wt-window's 10s appLaunch poll: a form is arbitrary
// user-authored HTML that may fetch its own data before it can paint.
const FORM_READY_TIMEOUT_MS = 30000;

const CMS_PREFIX_RE = /^cms:\/{1,3}/i;

function cmsKeyToPath(formKey: string): string | null {
	if (!formKey) return null;
	// Strip BOM and surrounding whitespace — these can sneak in when authors
	// copy/paste the formKey into a BPMN modeler and silently break the prefix
	// match below.
	const key = formKey.replace(/^﻿/, '').trim();
	if (CMS_PREFIX_RE.test(key)) {
		const rest = key.replace(CMS_PREFIX_RE, '/');
		// cms://content/foo or cms:/content/foo → /content/foo
		// cms://cgi/foo → /cgi/foo
		return rest.startsWith('/') ? rest : '/' + rest;
	}
	const head = Array.from(key.slice(0, 8)).map(c => 'U+' + c.codePointAt(0)!.toString(16).padStart(4, '0')).join(' ');
	console.warn(`Tasks: unsupported formKey ${JSON.stringify(formKey)} (head: ${head})`);
	return null;
}

function cmsPathToFrameSrc(workspace: string, jcrPath: string): string {
	// CMS HTML viewer servlet, scoped to the current workspace
	// (e.g. /bin/cms.cgi/system/content/bpm/forms/start.html).
	const ws = workspace ? '/' + workspace.replace(/^\//, '') : '';
	return '/bin/cms.cgi' + ws + jcrPath;
}

// A formKey may carry a query string so the form can be cache-busted, e.g.
//   cms:/content/.../form.html?t=${modified}
// Split it into the path component (which still carries the cms: prefix and is
// fed to cmsKeyToPath) and the raw query string (resolved by
// resolveFormKeyTokens once the node is known). Only the first '?' splits.
function splitFormKeyQuery(formKey: string): { keyPath: string; query: string } {
	const q = formKey.indexOf('?');
	if (q < 0) return { keyPath: formKey, query: '' };
	return { keyPath: formKey.slice(0, q), query: formKey.slice(q + 1) };
}

// Resolve template tokens embedded in a formKey query string.
//
// The only supported token today is the form node's last-modified time in
// epoch milliseconds. Appending it as a query parameter forces the iframe to
// reload whenever the form HTML changes, so reviewers always see the latest
// version instead of a stale cached copy. Several spellings are accepted so
// authors can pick the one they prefer:
//
//   cms:/content/.../form.html?t={{lastModified}}    ← Webtop's short form
//   cms:/content/.../form.html?v={{lastModifiedMs}}  ← verbose / explicit
//
// IMPORTANT: tokens use double braces `{{...}}`, NOT `${...}`. The BPM engine
// is Camunda 7, which evaluates `camunda:formKey` as a JUEL expression
// (TaskEntity.initializeFormKey → Expression.getValue), so a `${...}` token
// would be resolved against process variables server-side and collapse to an
// empty string before it ever reaches this app. JUEL never touches `{{...}}`,
// so those tokens survive the engine and are substituted here instead.
//
// Webtop itself cache-busts content URLs as `?t=<modified-in-ms>` (see the
// avatar loader in index.ts); `{{lastModified}}` pairs naturally with that
// `t` parameter. If the node has no usable modified timestamp we fall back to
// "now" so the form is still served fresh rather than emitting an empty value.
const FORM_KEY_TOKEN_RE = /\{\{\s*([A-Za-z]+)\s*\}\}/g;

function resolveFormKeyTokens(query: string, node: { modified?: string | null } | null): string {
	if (!query || query.indexOf('{{') < 0) return query;
	const t = node && node.modified ? new Date(node.modified).getTime() : NaN;
	const modifiedMs = String(Number.isFinite(t) ? t : nowMs());
	return query.replace(FORM_KEY_TOKEN_RE, (whole, name: string) => {
		switch (name.toLowerCase()) {
			case 't':
			case 'modified':
			case 'lastmodified':
			case 'lastmodifiedms':
				return modifiedMs;
			default:
				console.warn(`Tasks: unknown formKey token ${whole}`);
				return whole;
		}
	});
}

function nowMs(): number {
	return Date.now();
}

// A message id no i18n bundle can define — JSON keys never contain NUL. Used
// to run an ad-hoc ICU template through I18nService.format, which formats the
// `fallback` argument whenever the id misses in every locale of the chain.
const NON_MESSAGE_ID = '\u0000';

// Deep-copy a value to plain data before handing it across the bridge.
//
// Everything this app holds is wrapped in ichigo.js reactive Proxies, and the
// form iframe is now same-origin — so a raw return would give the form live
// references it could mutate, silently rewriting the host's task list. The
// JSON round-trip both unwraps the proxies and severs the reference; it also
// drops anything non-JSON, which is exactly the contract the serializers
// already promise (scalars, arrays, plain objects, ISO date strings).
function toPlainData<T>(value: T): T {
	if (value === undefined || value === null) return value;
	return JSON.parse(JSON.stringify(value)) as T;
}

export const App = {
	data() {
		return {
			// Readiness gate for the whole screen (see the <template v-if> in
			// index.html). Flipped by appLaunch() once the component templates
			// are present, so no component element is connected before its
			// <template> exists.
			isReady: false,
			instance: null as ApplicationInstance | null,
			// The GraphQL client every service below is built on. Handed to
			// forms through the bridge so they can run their own operations.
			graphql: null as GraphQLClient | null,
			bpm: null as BpmServiceGraphQL | null,
			idp: null as IdpServiceGraphQL | null,
			cms: null as ContentServiceGraphQL | null,

			messageListener: null as ((event: MessageEvent) => void) | null,

			// Reactive Localization snapshot (effective locale + IANA time
			// zone). Date displays and `t()` lookups read this and repaint on
			// `localization-changed` / `i18n-bundles-updated`. See
			// `composables/use-localization.ts`.
			localization: createLocalizationSnapshot(),

			// User context
			currentUser: null as IdpUser | null,
			currentUserID: '' as string,
			currentUserDisplay: '' as string,
			myGroups: [] as string[],

			// Mode & UI state
			mode: 'tasks-runtime' as Mode,
			isLoading: false,
			errorMessage: '',
			workspace: '',

			// Pane visibility & sizes
			searchPanelVisible: true,
			listPanelVisible: true,
			searchPanelWidth: 240,
			listPanelWidth: 320,

			// Filters
			filters: {
				taskKeyword: '',
				defKeyword: '',
				scope: 'assigned' as TaskScope,
				processDefinitionKeys: [] as string[],
				dueRange: 'any' as const,
				priorityMin: 0,
				category: '',
				favoritesOnly: false,
			} as Filters,

			// Tasks
			tasks: [] as Task[],
			processDefByKey: {} as Record<string, ProcessDefinition>,
			selectedTask: null as Task | null,

			// Process definitions (latest, active, startable)
			definitions: [] as ProcessDefinition[],
			selectedDef: null as ProcessDefinition | null,
			favorites: {} as Favorites,

			// Filtered list shown in the middle pane
			filteredItems: [] as Array<Task | ProcessDefinition>,

			// Form iframe state
			currentFormKey: '' as string,
			formSrc: '' as string,
			// True while the formKey is being resolved to a frame src (node
			// lookup + ACL check). Ends when `formSrc` is assigned — the
			// overlay stays up past it, gated by `formReady` below.
			formLoading: false,
			formError: '' as string,

			// --- Readiness gate (mirrors wt-window's isLaunched / launching) ---
			// The iframe is mounted as soon as there is a src, with an opaque
			// overlay on top; `formReady` is what takes the overlay down. The
			// ONLY thing that sets it is the form calling
			// `TasksFormHost.notifyReady()` — the exact counterpart of an app
			// calling `appInstance.notifyLaunched()`. The frame's own `load`
			// event is deliberately not wired up: it fires when the document
			// has loaded, which for any form that fetches its own data is well
			// before there is anything worth looking at.
			formReady: false,
			// Watchdog behind FORM_READY_TIMEOUT_MS. Raw: it holds a timer id,
			// and nothing renders off it.
			formReadyTimer: null as ReturnType<typeof setTimeout> | null,

			// Listeners registered by the currently-loaded form through
			// `TasksFormHost.subscribe()`. Held raw (not reactive) because it
			// stores closures from the iframe's realm, and cleared in
			// clearForm() — i.e. BEFORE a new formSrc is assigned, so the
			// incoming document's own subscription (registered while its
			// module scripts run) is never swept away.
			formListeners: null as Set<FormEventListener> | null,

			// Mirrors document.documentElement.dataset.theme. Forms read it off
			// the bridge to paint with the right palette on their first render;
			// the shell broadcasts theme changes to this app, not to the form.
			currentTheme: 'light' as string,

			// Dialog
			dialog: {
				type: '',
				data: {},
			} as DialogState,

			// True while a wt-splitter drag is in progress (drives #app.is-resizing).
			resizingPane: false,

			// Debounce timer for the assign-dialog user search (matches the
			// "Add ACL Entry" flow in content-browser).
			_assigneeSearchTimer: null as ReturnType<typeof setTimeout> | null,
		};
	},

	computed: {
		hasSelection(): boolean {
			return this.mode === 'start' ? !!this.selectedDef : !!this.selectedTask;
		},

		// Drives the loading overlay above the form iframe. Deliberately spans
		// BOTH phases as one uninterrupted state — resolving the formKey to a
		// src, and the loaded document's own startup — so there is no gap where
		// a blank frame shows through between them.
		formPending(): boolean {
			if (this.formError) return false;
			return this.formLoading || (!!this.formSrc && !this.formReady);
		},

		emptyListMessage(): string {
			if (this.mode === 'start') return this.t('app.tasks.empty.noStartableProcesses', undefined, 'No startable processes');
			return this.t('app.tasks.empty.noTasks', undefined, 'No tasks');
		},

		emptyFormMessage(): string {
			if (this.mode === 'start') return this.t('app.tasks.empty.selectProcess', undefined, 'Select a process to start');
			return this.t('app.tasks.empty.selectTask', undefined, 'Select a task to process');
		},

		noFormKeyMessage(): string {
			if (this.mode === 'start') return this.t('app.tasks.empty.noStartForm', undefined, 'This process has no start form (formKey).');
			return this.t('app.tasks.empty.noTaskForm', undefined, 'This task has no form (formKey).');
		},

		contextPlaceholder(): string {
			if (this.mode === 'start') return this.t('app.tasks.context.processStart', undefined, 'Process start');
			return this.t('app.tasks.context.myTasks', undefined, 'My tasks');
		},

		// O(1) checked-state lookup for the process-definition checkbox list.
		// Returned as a plain object (rather than a Set) to keep template
		// access trivial (`selectedDefKeyMap[def.key]`) and reactive without
		// special-casing collection types.
		selectedDefKeyMap(): Record<string, boolean> {
			const m: Record<string, boolean> = {};
			for (const k of this.filters.processDefinitionKeys) m[k] = true;
			return m;
		},

		// True while a pane resize drag is in progress. Used to add an
		// `is-resizing` class on #app so the form iframe ignores pointer
		// events — otherwise the iframe captures mousemove/mouseup and the
		// drag stops following the cursor.
		isResizing(): boolean {
			return this.resizingPane;
		},

		// Item lists for the due / category filter selects
		dueItems(): any[] {
			return [
				{ value: 'any', label: this.t('app.tasks.filter.due.any', undefined, 'Any time') },
				{ value: 'overdue', label: this.t('app.tasks.filter.due.overdue', undefined, 'Overdue') },
				{ value: 'today', label: this.t('app.tasks.filter.due.today', undefined, 'Due today') },
				{ value: 'week', label: this.t('app.tasks.filter.due.week', undefined, 'Due this week') },
			];
		},
		categoryItems(): any[] {
			const cats = Array.from(new Set(this.definitions.map((d: any) => d.category).filter(Boolean) as string[])).sort();
			return [
				{ value: '', label: this.t('app.tasks.filter.category.all', undefined, 'All categories') },
				...cats.map((c: string) => ({ value: c, label: c })),
			];
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

		// =====================================================================
		// Lifecycle
		// =====================================================================

		async onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				vm.handleWindowMessage(event);
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (appInstance: ApplicationInstance, options?: Record<string, any>) => {
				vm.instance = vm.$markRaw(appInstance);

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;
				vm.currentTheme = theme;
				vm.instance.windowTitle = vm.t('app.tasks.title', undefined, 'Tasks');
				vm.workspace = appInstance.api.workspace;

				// Snapshot the effective Localization preference so date / `t()`
				// bindings render in the user's language from the first paint.
				refreshLocalization(vm.localization, vm.instance);

				// --- Readiness gate ---
				// Load the component templates BEFORE the gated markup is
				// compiled, so each <wt-*> element finds its <template> on the
				// single connectedCallback it gets. The popup adapter is passed
				// here as well: wt-select menus escape the window through the
				// shell popup API.
				try {
					await initUi({ popupAdapter: createShellPopupAdapter(appInstance) });
				} catch (e) {
					console.warn('[Tasks] Failed to load component templates:', e);
				}
				vm.isReady = true;
				await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));

				await vm.initServices();
				await vm.loadCurrentUser();
				// Publish the form bridge before the first form can be opened.
				vm.installFormHost();
				vm.loadFavorites();

				// A drill-down (e.g. from the Dashboard) hands us a filter to land
				// on. Seed the scope/process filter BEFORE the first load so the
				// initial query already targets the requested slice.
				vm.seedLaunchFilters(options);
				await vm.refresh();
				vm.selectLaunchTask(options);

				vm.$nextTick(() => {
					appInstance.notifyLaunched();
				});
			};
		},

		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
			this.uninstallFormHost();
		},

		async initServices() {
			const client = createGraphQLClient(this.workspace);
			this.graphql = this.$markRaw(client);
			this.bpm = this.$markRaw(new BpmServiceGraphQL(client));
			this.cms = this.$markRaw(new ContentServiceGraphQL(client));
			// IdP lives in the system workspace
			this.idp = this.$markRaw(new IdpServiceGraphQL());
		},

		async loadCurrentUser() {
			try {
				// Prefer the host-provided current user (already authenticated).
				const ctx = (window as any).Webtop;
				if (ctx?.currentUser?.id) {
					this.currentUserID = ctx.currentUser.id;
					this.currentUserDisplay = ctx.currentUser.fullName || ctx.currentUser.id;
				}
				if (this.idp) {
					const me = await this.idp.getMe();
					if (me) {
						this.currentUser = me;
						if (!this.currentUserID) {
							this.currentUserID = me.username;
						}
						if (!this.currentUserDisplay) {
							this.currentUserDisplay = me.displayName || me.username;
						}
						this.myGroups = (me.effectiveGroups || me.memberOf || [])
							.map(g => g.groupId || (g as any).name || '')
							.filter(Boolean);
					}
				}
			} catch (err) {
				// non-fatal — falls back to the anonymous host context
				console.warn('Tasks: failed to load current user', err);
			}
		},

		// =====================================================================
		// Window controls
		// =====================================================================

		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// =====================================================================
		// Pane toggles & resize
		// =====================================================================

		toggleSearchPanel() { this.searchPanelVisible = !this.searchPanelVisible; },
		toggleListPanel() { this.listPanelVisible = !this.listPanelVisible; },

		// =====================================================================
		// Mode switching
		// =====================================================================

		async switchMode(mode: Mode) {
			if (this.mode === mode) return;
			this.mode = mode;
			this.selectedTask = null;
			this.selectedDef = null;
			this.clearForm();
			await this.refresh();
		},

		async refresh() {
			// Process definitions feed both the start-mode list and the
			// runtime-mode "Process definition" filter, so refresh them on
			// every refresh — newly deployed processes should appear without
			// reopening the app.
			await this.loadDefinitions();
			if (this.mode === 'start') {
				this.filterList();
			} else {
				await this.loadTasks();
			}
		},

		// =====================================================================
		// Process definitions
		// =====================================================================

		// Loads ALL latest non-suspended definitions (regardless of whether
		// they are startable here). The start-mode list narrows to ones with a
		// `startFormKey` at filter time; the runtime-mode filter shows every
		// definition the user might encounter as a task source. Also rebuilds
		// `processDefByKey` so task rows can resolve human-readable names.
		async loadDefinitions() {
			if (!this.bpm) return;
			try {
				this.isLoading = true;
				this.errorMessage = '';
				const conn = await this.bpm.listProcessDefinitions({
					first: 1000,
					latestVersion: true,
					suspended: false,
				});
				const all = conn.edges.map((e: ProcessDefinitionEdge) => e.node);
				all.sort((a: ProcessDefinition, b: ProcessDefinition) => (a.name || a.key).localeCompare(b.name || b.key));
				this.definitions = all;
				const map: Record<string, ProcessDefinition> = {};
				for (const d of all) map[d.key] = d;
				this.processDefByKey = map;
			} catch (err) {
				this.errorMessage = this.t('app.tasks.error.loadProcesses', { detail: err instanceof Error ? err.message : String(err) }, 'Failed to load processes: {detail}');
			} finally {
				this.isLoading = false;
			}
		},

		// =====================================================================
		// Tasks (runtime)
		// =====================================================================

		// Issues a single listTasks call shaped by the selected scope. See
		// the `TaskScope` declaration above for what each value sends.
		async loadTasks() {
			if (!this.bpm) return;
			if (!this.currentUserID) return;
			try {
				this.isLoading = true;
				this.errorMessage = '';

				const opts: { first: number; assignee?: string; candidateUser?: string } = { first: 200 };
				if (this.filters.scope === 'assigned') {
					opts.assignee = this.currentUserID;
				} else if (this.filters.scope === 'candidate') {
					opts.candidateUser = this.currentUserID;
				}
				// 'all': neither filter — the engine still enforces authorization.

				const conn = await this.bpm.listTasks(opts);
				this.tasks = conn.edges
					.map((e: TaskEdge) => {
						const node = e.node;
						// Flatten processInstance.businessKey so the keyword filter
						// and task list can read it without traversing the relation.
						return { ...node, businessKey: node.processInstance?.businessKey ?? null };
					})
					.sort(this.compareTasks);

				this.filterList();
			} catch (err) {
				this.errorMessage = this.t('app.tasks.error.loadTasks', { detail: err instanceof Error ? err.message : String(err) }, 'Failed to load tasks: {detail}');
			} finally {
				this.isLoading = false;
			}
		},

		compareTasks(a: Task, b: Task): number {
			// Overdue first, then by due date asc, then by created asc
			const aDue = a.due ? new Date(a.due).getTime() : Number.POSITIVE_INFINITY;
			const bDue = b.due ? new Date(b.due).getTime() : Number.POSITIVE_INFINITY;
			if (aDue !== bDue) return aDue - bDue;
			const aC = new Date(a.created).getTime();
			const bC = new Date(b.created).getTime();
			return aC - bC;
		},

		resolveProcessName(task: Task): string {
			const def = this.processDefByKey[task.processDefinitionKey];
			return def?.name || task.processDefinitionKey || '';
		},

		// =====================================================================
		// Filtering
		// =====================================================================

		filterList() {
			if (this.mode === 'start') {
				this.filteredItems = this.applyDefFilters(this.definitions);
			} else {
				this.filteredItems = this.applyTaskFilters(this.tasks);
			}
		},

		applyTaskFilters(tasks: Task[]): Task[] {
			const q = this.filters.taskKeyword.trim().toLowerCase();
			const today = startOfToday();
			const todayEnd = today + 24 * 60 * 60 * 1000;
			const weekEnd = today + 7 * 24 * 60 * 60 * 1000;
			const keys = this.filters.processDefinitionKeys;

			return tasks.filter(t => {
				if (keys.length > 0 && !keys.includes(t.processDefinitionKey)) return false;
				if (this.filters.priorityMin > 0 && (t.priority ?? 0) < this.filters.priorityMin) return false;

				if (this.filters.dueRange !== 'any') {
					const due = t.due ? new Date(t.due).getTime() : null;
					if (this.filters.dueRange === 'overdue') {
						if (!due || due >= nowMs()) return false;
					} else if (this.filters.dueRange === 'today') {
						if (!due || due < today || due >= todayEnd) return false;
					} else if (this.filters.dueRange === 'week') {
						if (!due || due >= weekEnd) return false;
					}
				}

				if (q) {
					const procName = this.resolveProcessName(t).toLowerCase();
					const hay = `${t.name || ''} ${t.taskDefinitionKey || ''} ${t.description || ''} ${procName} ${t.businessKey || ''}`.toLowerCase();
					if (!hay.includes(q)) return false;
				}
				return true;
			});
		},

		applyDefFilters(defs: ProcessDefinition[]): ProcessDefinition[] {
			const q = this.filters.defKeyword.trim().toLowerCase();
			return defs.filter(d => {
				// In start mode the list shows definitions to start, so a
				// `startFormKey` is mandatory; the engine will still enforce
				// authorization on the actual start call.
				if (!d.startFormKey) return false;
				if (this.filters.favoritesOnly && !this.favorites[d.key]) return false;
				if (this.filters.category && d.category !== this.filters.category) return false;
				if (q) {
					const hay = `${d.name || ''} ${d.key || ''} ${d.description || ''}`.toLowerCase();
					if (!hay.includes(q)) return false;
				}
				return true;
			});
		},

		// Toggle membership of `key` in the multi-select process-definition
		// filter. A new array is assigned so ichigo.js picks up the change
		// regardless of how it tracks array reactivity.
		toggleDefKey(key: string) {
			const keys = this.filters.processDefinitionKeys;
			if (keys.includes(key)) {
				this.filters.processDefinitionKeys = keys.filter((k: string) => k !== key);
			} else {
				this.filters.processDefinitionKeys = [...keys, key];
			}
			this.filterList();
		},

		clearDefKeySelection() {
			if (this.filters.processDefinitionKeys.length === 0) return;
			this.filters.processDefinitionKeys = [];
			this.filterList();
		},

		// =====================================================================
		// Filter popups (postMessage → shell popup)
		// =====================================================================

		// =====================================================================
		// Selection
		// =====================================================================

		async selectTask(task: Task) {
			this.selectedTask = { ...task };
			this.selectedDef = null;
			await this.openFormForTask(task);
		},

		async selectDefinition(def: ProcessDefinition) {
			this.selectedDef = def;
			this.selectedTask = null;
			await this.openFormForDefinition(def);
		},

		// =====================================================================
		// Favorites (per-user, per-app, persisted via WebtopDatabase)
		// =====================================================================

		async loadFavorites() {
			try {
				const db = this.instance?.api?.db;
				if (!db || !this.currentUserID) return;
				const value = await db.getUserSetting(this.currentUserID, this.instance!.app.id, 'tasks/favorites');
				if (value && typeof value === 'object') {
					this.favorites = value as Favorites;
				}
			} catch {
				// non-fatal
			}
		},

		async saveFavorites() {
			try {
				const db = this.instance?.api?.db;
				if (!db || !this.currentUserID) return;
				await db.setUserSetting(this.currentUserID, this.instance!.app.id, 'tasks/favorites', this.favorites);
			} catch {
				// non-fatal
			}
		},

		toggleFavorite(def: ProcessDefinition) {
			if (this.favorites[def.key]) {
				delete this.favorites[def.key];
			} else {
				this.favorites[def.key] = true;
			}
			this.saveFavorites();
			if (this.filters.favoritesOnly) this.filterList();
		},

		// =====================================================================
		// Form iframe — load
		// =====================================================================

		clearForm() {
			this.currentFormKey = '';
			this.formSrc = '';
			this.formError = '';
			this.formLoading = false;
			this.formReady = false;
			this.cancelFormReadyWatchdog();
			// Drop the outgoing document's listeners here rather than on the
			// iframe's `load`: module scripts in the NEW document run before
			// `load` fires, so clearing there would unsubscribe the form that
			// just subscribed.
			this.formListeners?.clear();
		},

		async openFormForTask(task: Task) {
			this.clearForm();
			const formKey = task.formKey || '';
			this.currentFormKey = formKey;
			if (!formKey) return;
			await this.loadFormFromKey(formKey);
		},

		async openFormForDefinition(def: ProcessDefinition) {
			this.clearForm();
			const formKey = def.startFormKey || '';
			this.currentFormKey = formKey;
			if (!formKey) return;
			await this.loadFormFromKey(formKey);
		},

		async loadFormFromKey(formKey: string) {
			this.formLoading = true;
			this.formError = '';
			try {
				// A formKey may carry a cache-busting query string, e.g.
				// cms:/content/.../form.html?t={{lastModified}}. Resolve the path
				// against the node, then substitute the query tokens.
				const { keyPath, query } = splitFormKeyQuery(formKey);
				const path = cmsKeyToPath(keyPath);
				if (!path) {
					this.formError = this.t('app.tasks.form.unsupportedKey', { formKey }, 'Unsupported formKey: {formKey}. Expected cms:/...');
					return;
				}
				if (!this.cms) {
					this.formError = this.t('app.tasks.form.cmsNotInitialized', undefined, 'CMS service is not initialized');
					return;
				}
				// Resolving the node verifies existence + read ACL server-side;
				// the iframe then loads the rendered HTML via downloadUrl (or the
				// cms.html servlet path as a fallback). The node's modified time
				// also feeds any ${...} cache-busting tokens in the query string.
				const node = await this.cms.getNode(path);
				if (!node) {
					this.formError = this.t('app.tasks.form.notFound', { path }, 'Form not found: {path}');
					return;
				}
				const resolvedQuery = resolveFormKeyTokens(query, node);
				this.formSrc = cmsPathToFrameSrc(this.workspace, path)
					+ (resolvedQuery ? '?' + resolvedQuery : '');
				// From here the overlay is held up by `formReady`, not by
				// `formLoading`. Start the watchdog now so a document that
				// never loads at all is caught too, not just one that loads
				// and then never signals.
				this.startFormReadyWatchdog();
			} catch (err) {
				this.formError = this.t('app.tasks.form.loadFailed', { detail: err instanceof Error ? err.message : String(err) }, 'Failed to load form: {detail}');
			} finally {
				this.formLoading = false;
			}
		},

		// Re-run the whole resolve → load cycle for the current selection.
		// Backs the Retry button on the failure overlay. clearForm() blanks
		// `formSrc` first, so the iframe unmounts and the identical src is a
		// real reload rather than a no-op binding update.
		async reloadForm() {
			const formKey = this.currentFormKey;
			if (!formKey) return;
			this.clearForm();
			this.currentFormKey = formKey;
			await this.loadFormFromKey(formKey);
		},

		// =====================================================================
		// Form iframe — readiness gate
		// =====================================================================

		markFormReady() {
			this.cancelFormReadyWatchdog();
			// A form that signals late — after the watchdog already gave up —
			// is still a working form, so show it instead of leaving the user
			// staring at a timeout it just disproved. The only error reachable
			// here is that timeout: every other failure returns before a src is
			// assigned, so the frame never loads and never signals.
			this.formError = '';
			this.formReady = true;
		},

		startFormReadyWatchdog() {
			this.cancelFormReadyWatchdog();
			this.formReadyTimer = setTimeout(() => {
				this.formReadyTimer = null;
				if (this.formReady) return;
				this.formError = this.t('app.tasks.form.readyTimeout', undefined, 'The form did not finish loading.');
			}, FORM_READY_TIMEOUT_MS);
		},

		cancelFormReadyWatchdog() {
			if (this.formReadyTimer) {
				clearTimeout(this.formReadyTimer);
				this.formReadyTimer = null;
			}
		},

		handleWindowMessage(event: MessageEvent) {
			const data = event.data;

			// Localization changes (locale / time zone / bundle hot-reload)
			// broadcast by the shell. Fold them into the reactive snapshot so
			// every `t()` and date binding repaints, then announce the fresh
			// snapshot to the form (the shell broadcasts to apps, not to form
			// documents). Same-origin only.
			if (data && typeof data === 'object' && event.origin === window.location.origin) {
				if (handleLocalizationMessage(data.type, this.localization, this.instance)) {
					this.notifyForm('localization', this.buildLocalization());
					return;
				}
			}

			// Theme propagation from shell (same-origin, parent → app).
			if (data && typeof data === 'object' && data.type === 'theme-changed') {
				if (event.origin === window.location.origin) {
					document.documentElement.dataset.theme = data.theme;
					this.currentTheme = data.theme;
					this.notifyForm('theme', this.currentTheme);
				}
				return;
			}

			// Drill-down re-target: the shell re-launches this singleton with a
			// fresh filter (e.g. the Dashboard opening a specific process's
			// tasks). Re-apply the requested slice without reopening the window.
			if (data && typeof data === 'object' && data.type === 'app-reopen') {
				if (event.origin === window.location.origin) {
					this.handleReopen(data.options);
				}
				return;
			}

			// Nothing else is expected here. The form used to speak a
			// postMessage RPC on this channel; it now calls the bridge directly
			// (see installFormHost), and clicks inside it raise the window via
			// the shell's own same-origin frame-tree listener.
		},

		// =====================================================================
		// Drill-down launch options
		//
		// A caller (e.g. the Dashboard) may open Tasks pre-filtered. Supported
		// options:
		//   scope                 'assigned' | 'candidate' | 'all'
		//   processDefinitionKey  string  — narrow to a single process
		//   processDefinitionKeys string[] — narrow to several processes
		//   taskKeyword           string  — seed the keyword box (e.g. a task name)
		//   taskId                string  — select this task once loaded
		// All options are optional; unknown keys are ignored.
		// =====================================================================

		// Seed filter state from launch options BEFORE the first load so the
		// initial query already targets the requested slice. Always lands in the
		// runtime task list (the only mode a drill-down makes sense in).
		seedLaunchFilters(options?: Record<string, any>) {
			if (!options) return;
			this.mode = 'tasks-runtime';
			if (options.scope === 'assigned' || options.scope === 'candidate' || options.scope === 'all') {
				this.filters.scope = options.scope;
			}
			if (typeof options.processDefinitionKey === 'string' && options.processDefinitionKey) {
				this.filters.processDefinitionKeys = [options.processDefinitionKey];
			} else if (Array.isArray(options.processDefinitionKeys)) {
				this.filters.processDefinitionKeys = options.processDefinitionKeys.filter((k: unknown) => typeof k === 'string');
			}
			if (typeof options.taskKeyword === 'string') {
				this.filters.taskKeyword = options.taskKeyword;
			}
		},

		// Select the requested task once the list is loaded (best-effort).
		selectLaunchTask(options?: Record<string, any>) {
			if (!options || typeof options.taskId !== 'string' || !options.taskId) return;
			const t = (this.tasks as Task[]).find(x => x.id === options.taskId);
			if (t) this.selectTask(t);
		},

		// Apply launch options to an already-running window (singleton re-target).
		async handleReopen(options?: Record<string, any>) {
			const prevScope = this.filters.scope;
			const prevMode = this.mode;
			this.seedLaunchFilters(options);
			// A scope or mode change requires a fresh server query; otherwise the
			// process/keyword filters are client-side and a refilter suffices.
			if (this.filters.scope !== prevScope || this.mode !== prevMode) {
				await this.refresh();
			} else {
				this.filterList();
			}
			this.selectLaunchTask(options);
		},

		// Announce a change to the currently-loaded form. Listeners come from
		// the iframe's realm, so a throwing form must not take the host down
		// with it — each is called defensively. Iterating a copy lets a
		// listener unsubscribe during dispatch.
		notifyForm(type: FormEventType, payload: unknown) {
			const listeners = this.formListeners as Set<FormEventListener> | null;
			if (!listeners || listeners.size === 0) return;
			for (const fn of Array.from(listeners)) {
				try {
					fn(type, payload);
				} catch (err) {
					console.error(`Tasks: form '${type}' listener failed`, err);
				}
			}
		},

		// Re-announce the selection context. Called after an in-place change
		// that does NOT reload the frame — claim / unclaim / assign / takeover.
		// A selection change replaces formSrc, so the new document reads the
		// fresh context off the bridge on its own.
		notifyFormContext() {
			this.notifyForm('context', this.buildContext());
		},

		// Snapshot the effective localization for the form iframe. Read from the
		// same reactive snapshot the host's own `t()` / date bindings use, so the
		// form always paints in the user's current language and zone.
		// Read the LIVE LocalizationManager, not the reactive snapshot: the
		// snapshot is seeded once at appLaunch and only re-synced on a
		// `localization-changed` broadcast, so on the first form open it can
		// still be empty (the manager's async settings load may resolve after
		// appLaunch). Forwarding an empty locale makes the form fall back to
		// the browser locale — Japanese labels but en-US dates / half-width
		// ¥ — even though the user is on `ja`. The manager's effective*
		// getters always reflect the current preference and are authoritative
		// here; the snapshot is only a fallback.
		buildLocalization(): FormLocalization {
			const loc = this.instance?.api?.localization;
			return {
				locale: loc?.effectiveLocale || this.localization.locale || '',
				timeZone: loc?.effectiveTimezone || this.localization.timeZone || '',
				numberFormat: loc?.effectiveNumberFormat || this.localization.numberFormat || '',
				currency: loc?.effectiveCurrency || this.localization.currency || '',
			};
		},

		// The selection snapshot a form reads on load and on every `context`
		// announcement. Deep-cloned to plain data by toPlainData() so a form
		// never receives — nor can mutate — this app's reactive proxies.
		buildContext(): Record<string, unknown> {
			return toPlainData(this.buildContextRaw());
		},

		buildContextRaw(): Record<string, unknown> {
			// The theme rides along so a form paints with the right palette on
			// its first render; later changes arrive as a 'theme' event.
			const theme = this.currentTheme || 'light';
			if (this.mode === 'start' && this.selectedDef) {
				return {
					mode: 'start',
					theme,
					currentUser: { id: this.currentUserID, displayName: this.currentUserDisplay },
					localization: this.buildLocalization(),
					processDefinition: {
						id: this.selectedDef.id,
						key: this.selectedDef.key,
						name: this.selectedDef.name,
						version: this.selectedDef.version,
						description: this.selectedDef.description,
						category: this.selectedDef.category,
					},
				};
			}
			if (this.selectedTask) {
				return {
					mode: 'task',
					theme,
					currentUser: { id: this.currentUserID, displayName: this.currentUserDisplay },
					localization: this.buildLocalization(),
					task: {
						id: this.selectedTask.id,
						name: this.selectedTask.name,
						description: this.selectedTask.description,
						assignee: this.selectedTask.assignee,
						owner: this.selectedTask.owner,
						created: this.selectedTask.created,
						due: this.selectedTask.due,
						priority: this.selectedTask.priority,
						processInstanceId: this.selectedTask.processInstanceId,
						processDefinitionId: this.selectedTask.processDefinitionId,
						processDefinitionKey: this.selectedTask.processDefinitionKey,
						taskDefinitionKey: this.selectedTask.taskDefinitionKey,
						formKey: this.selectedTask.formKey,
						candidateUsers: this.selectedTask.candidateUsers,
						candidateGroups: this.selectedTask.candidateGroups,
						businessKey: this.selectedTask.businessKey ?? this.selectedTask.processInstance?.businessKey ?? null,
					},
				};
			}
			return { mode: this.mode, theme, localization: this.buildLocalization() };
		},

		// =====================================================================
		// Form host bridge — window.parent.TasksFormHost
		//
		// The form iframe is same-origin, so it reaches the host by calling
		// this object directly. There is no serialization boundary and no
		// request/reply correlation: a form gets the live GraphQL client and
		// the BPM / CMS / IdP services this app already built, and calls them
		// with the signed-in user's credentials. Server-side authorization
		// (Camunda + JCR ACLs) is the only thing gating what it can do — the
		// method list here is convenience, not a security boundary.
		//
		// Task lifecycle operations still live here rather than being left to
		// `bpm` directly, because they must also update this app's list and
		// selection state (drop a completed task, patch an assignee, refresh
		// after a process start).
		// =====================================================================

		installFormHost() {
			// The bridge reads everything through getters closed over `this`, so
			// one instance stays correct for the app's lifetime; the only
			// per-form state is the listener set, which clearForm() resets
			// between documents.
			//
			// markRaw that set: it lives in reactive data but holds closures
			// from the iframe's realm, which must not be wrapped in proxies. The
			// bridge object itself never enters reactive data, so it needs none.
			this.formListeners = this.$markRaw(new Set<FormEventListener>());
			(window as unknown as { TasksFormHost?: unknown }).TasksFormHost = this.buildFormHost();
		},

		uninstallFormHost() {
			this.cancelFormReadyWatchdog();
			this.formListeners?.clear();
			delete (window as unknown as { TasksFormHost?: unknown }).TasksFormHost;
		},

		buildFormHost() {
			const vm = this;
			return {
				// Bumped when the shape below changes incompatibly, so a form can
				// refuse to run against a host older than it expects.
				//
				// 2 — readiness is mandatory: the host holds a loading overlay
				//     over the frame until the form calls notifyReady(). A form
				//     written for version 1 never calls it and now sits behind
				//     the overlay until the watchdog fires.
				// 3 — adds app launch: listApps() / openApp() / openFile().
				//     Purely additive — every version 2 form keeps working.
				version: 3,

				// ----- Environment -----

				get workspace(): string { return vm.workspace; },

				// Absolute URL of the webtop root (…/content/webtop/), derived
				// from this app's own location (…/content/webtop/apps/tasks/).
				// Forms join it to load webtop's stylesheets, fonts and icons —
				// the whole point of dropping the iframe sandbox.
				get webtopBaseUrl(): string { return new URL('../../', window.location.href).href; },

				// ----- GraphQL -----

				// The client this app's services run on: `query` / `mutation`
				// against /bin/graphql.cgi/<workspace>, credentials included.
				get graphql(): GraphQLClient | null { return vm.graphql; },
				// A client for a different workspace (e.g. 'system').
				createGraphQLClient(workspace: string): GraphQLClient {
					return createGraphQLClient(workspace);
				},
				// Typed service facades over the same client. `idp` targets the
				// system workspace, as everywhere else in webtop.
				get bpm(): BpmServiceGraphQL | null { return vm.bpm; },
				get cms(): ContentServiceGraphQL | null { return vm.cms; },
				get idp(): IdpServiceGraphQL | null { return vm.idp; },

				// ----- Context -----

				// Plain-data snapshot of the current selection: `{ mode, theme,
				// currentUser, localization, task? , processDefinition? }`.
				get context(): Record<string, unknown> { return vm.buildContext(); },
				get theme(): string { return vm.currentTheme || 'light'; },
				get localization(): FormLocalization { return vm.buildLocalization(); },
				get currentUser(): { id: string; displayName: string; groups: string[] } {
					return {
						id: vm.currentUserID,
						displayName: vm.currentUserDisplay,
						groups: [...vm.myGroups],
					};
				},

				// Subscribe to host announcements — 'context' (selection or
				// assignment changed in place), 'theme', 'localization'. Returns
				// an unsubscribe function. Subscriptions are dropped when the
				// host loads a different form, so a document never has to worry
				// about a successor's events.
				subscribe(listener: FormEventListener): () => void {
					const listeners = vm.formListeners;
					if (!listeners || typeof listener !== 'function') return () => { /* noop */ };
					listeners.add(listener);
					return () => { listeners.delete(listener); };
				},

				// ----- Readiness -----

				// REQUIRED. Every form must call this exactly once, when it has
				// finished starting up and is worth showing. Until it does, the
				// host keeps an opaque loading overlay over the frame — the
				// same contract an app has with the shell through
				// `appInstance.notifyLaunched()`, and the reason the frame's
				// `load` event is not used: `load` means "the document
				// arrived", not "the form has something to show".
				//
				//   const App = { ... };
				//   VDOM.createApp(App).mount('#app');
				//   await loadMyData();
				//   host.notifyReady();          // <- overlay comes down here
				//
				// Call it on the failure paths too, once the form has rendered
				// its own error state — otherwise the user waits out the
				// watchdog and gets the host's generic message instead of the
				// form's specific one. A `finally` around startup is usually
				// the right place.
				//
				// The host gives up after FORM_READY_TIMEOUT_MS and shows its
				// failure state with a Retry button, so a form that throws
				// before signalling degrades to an error rather than a spinner
				// that never stops. Calling twice is harmless.
				notifyReady(): void {
					// No epoch guard needed: switching forms unmounts the iframe
					// (clearForm blanks formSrc, and the node lookup for the next
					// form yields long enough for that to render), which destroys
					// the outgoing document before its pending work can resume.
					// A stale signal therefore cannot arrive from a discarded
					// form the way a stale `subscribe` listener could.
					if (!vm.formSrc) return;
					vm.markFormReady();
				},

				// Bring the Tasks window to the front. Rarely needed: the shell
				// attaches its own mousedown listener across the same-origin
				// frame tree, so ordinary clicks in the form already raise it.
				activate(): void { vm.instance?.activate(); },

				// ----- App launch -----
				//
				// Open another webtop app from a form — the drill-down a review
				// step usually wants ("show me the order this approval is
				// about"). The shell owns window creation; these methods only
				// resolve and validate the request, then hand it over through
				// the same `open-app` / `open-file-with-app` messages Content
				// Browser uses for a double-click.
				//
				// `options` arrives at the target app as the second argument of
				// its `window.appLaunch(instance, options)`. For a SINGLETON app
				// that is already running the shell does not open a second
				// window: it focuses the existing one and re-targets it with a
				// `{ type: 'app-reopen', options }` message, so a form button
				// clicked repeatedly lands on one window rather than a stack of
				// them. Apps that ignore `app-reopen` still get focused.
				//
				// Launching is fire-and-forget: the new window has its own
				// lifecycle, so there is nothing meaningful to wait for and
				// nothing to return. Failures that the form can fix (unknown
				// app, unreadable path, no editor for the type) throw here;
				// anything after the hand-off is the shell's to report.

				// The installed apps as plain data, so a form can resolve an id
				// by title or category instead of hard-coding a UUID that
				// differs per deployment: `listApps().find(a => a.title === …)`.
				listApps(): { id: string; title: string; category: string | null; editor: boolean; contentTypes: string[]; singleton: boolean }[] {
					return vm.shellApps().map((a: Application) => ({
						id: a.id,
						title: a.title || '',
						category: a.category ?? null,
						editor: !!a.editor,
						contentTypes: [...(a.contentTypes || [])],
						singleton: a.singleton,
					}));
				},

				// Launch an app by id, optionally handing it a launch payload:
				//
				//   host.openApp(orderAppId, { view: 'order', orderId: '4711' });
				//
				// `options.initialWindowState` ({ x, y, width, height }) is
				// consumed by the shell to place the window verbatim instead of
				// cascading it; every other key is the target app's own
				// business.
				openApp(appId: string, options?: Record<string, unknown>): void {
					const id = String(appId ?? '').trim();
					if (!id) throw new Error('appId is required');
					if (!vm.findApp(id)) throw new Error(`App not found: ${id}`);
					window.parent.postMessage({
						type: 'open-app',
						appId: id,
						// Plain data only: the payload crosses into the shell
						// realm and is stored on the instance, so a live
						// reference from the form's realm must not travel with
						// it. Same contract as every return value here.
						options: options ? toPlainData(options) : undefined,
					}, window.location.origin);
				},

				// Open a CMS file in its editor — the double-click of Content
				// Browser, addressable from a form:
				//
				//   await host.openFile('/content/orders/4711/invoice.pdf');
				//
				// Both parts are optional and resolved when omitted: the MIME
				// type from the node itself, the app from the editor registered
				// for that type. Pass `appId` to force a specific editor, or
				// `mimeType` to skip the node lookup when the form already knows
				// it. Async only because of those lookups — it resolves once the
				// request has been handed to the shell, not when the app is up.
				async openFile(path: string, opts?: { appId?: string; mimeType?: string }): Promise<void> {
					const p = String(path ?? '').trim();
					if (!p) throw new Error('path is required');
					const o = opts || {};
					let appId = String(o.appId ?? '').trim();
					let mimeType = String(o.mimeType ?? '').trim();
					if (!mimeType) {
						const node = await vm.cms!.getNode(p);
						if (!node) throw new Error(`Node not found: ${p}`);
						mimeType = node.mimeType || '';
					}
					if (!appId) {
						const editor = vm.findEditorForMimeType(mimeType);
						if (!editor) {
							throw new Error(`No editor is registered for ${mimeType || 'this content type'}: ${p}`);
						}
						appId = editor.id;
					} else if (!vm.findApp(appId)) {
						throw new Error(`App not found: ${appId}`);
					}
					window.parent.postMessage({
						type: 'open-file-with-app',
						appId,
						filePath: p,
						mimeType,
					}, window.location.origin);
				},

				// ----- Identity -----

				// Look up another user (e.g. resolve an assignee username to a
				// display name). Resolves to null when the user does not exist.
				async getUser(username: string) {
					const name = String(username ?? '').trim();
					if (!name) throw new Error('username is required');
					if (!vm.idp) throw new Error('IdP service is not initialized');
					const user = await vm.idp.getUser(name);
					if (!user) return null;
					return {
						id: user.username,
						displayName: user.displayName || user.username,
						mail: user.mail,
					};
				},

				// ----- Localization -----

				// Format a message id straight through the shell's ICU engine
				// against the effective locale — the same bundles and the same
				// fallback chain every shell app uses. Forms no longer need to
				// load intl-messageformat themselves.
				//
				// The locale comes from the live LocalizationManager rather than
				// this app's reactive snapshot: the snapshot is seeded once at
				// appLaunch and can still be empty on the first form open, which
				// would format the message in the browser locale instead.
				translate(messageId: string, params?: Record<string, unknown>, fallback?: string): string {
					const i18n = vm.instance?.api?.i18n;
					if (!i18n || typeof i18n.format !== 'function') return fallback ?? messageId;
					const locale = vm.instance?.api?.localization?.effectiveLocale
						|| vm.localization.locale
						|| '';
					return i18n.format(messageId, params, fallback, locale || undefined);
				},

				// Format an ad-hoc ICU template (not a bundle key) against the
				// effective locale — plurals, select, number/date placeholders.
				// Implemented as a lookup of an id no bundle can define, whose
				// fallback is the template, which is exactly the path
				// I18nService.format takes for an unresolved id.
				formatTemplate(template: string, params?: Record<string, unknown>): string {
					const i18n = vm.instance?.api?.i18n;
					if (!i18n || typeof i18n.format !== 'function') return String(template);
					const locale = vm.instance?.api?.localization?.effectiveLocale
						|| vm.localization.locale
						|| '';
					return i18n.format(NON_MESSAGE_ID, params, String(template), locale || undefined);
				},

				// A resolved, flat message map for a namespace, for forms that
				// prefer to hold their own bundle: `{ locale, messages }`.
				getI18nMessages(prefix?: string): { locale: string; messages: Record<string, string> } {
					const i18n = vm.instance?.api?.i18n;
					// Live manager first (authoritative), then the snapshot, then
					// the i18n service's own resolution — never hand the form an
					// empty locale or it formats dates/numbers in the browser one.
					const locale = vm.instance?.api?.localization?.effectiveLocale
						|| vm.localization.locale
						|| (i18n ? i18n.currentLocale : '')
						|| '';
					if (!i18n || typeof i18n.getMessages !== 'function') {
						return { locale, messages: {} };
					}
					return { locale, messages: i18n.getMessages(prefix, locale || undefined) };
				},

				// ----- Process start (start mode only) -----

				getProcessDefinition() {
					vm.requireMode('start');
					vm.requireSelectedDefinition();
					return toPlainData(vm.serializeDefinition(vm.selectedDef!));
				},

				async startProcess(opts?: { variables?: unknown; businessKey?: string }) {
					vm.requireMode('start');
					vm.requireSelectedDefinition();
					const o = opts || {};
					const inst = await vm.bpm!.startProcess({
						definitionId: vm.selectedDef!.id,
						businessKey: typeof o.businessKey === 'string' ? o.businessKey : undefined,
						variables: vm.normalizeVariables(o.variables),
					});
					vm.errorMessage = '';
					vm.$nextTick(() => vm.refresh());
					return toPlainData(vm.serializeInstance(inst));
				},

				// ----- Task operations (task modes only) -----

				getTask() {
					vm.requireSelectedTask();
					return toPlainData(vm.serializeTask(vm.selectedTask!));
				},

				async getTaskWithVariables() {
					vm.requireSelectedTask();
					const full = await vm.bpm!.getTask(vm.selectedTask!.id);
					if (!full) return null;
					return toPlainData({
						...vm.serializeTask(full),
						variables: full.variables ?? [],
						localVariables: full.localVariables ?? [],
					});
				},

				async getTaskVariables() {
					vm.requireSelectedTask();
					const full = await vm.bpm!.getTask(vm.selectedTask!.id);
					return toPlainData({
						variables: full?.variables ?? [],
						localVariables: full?.localVariables ?? [],
					});
				},

				async setTaskVariables(variables: unknown, local?: boolean) {
					vm.requireSelectedTask();
					await vm.bpm!.setTaskVariables(vm.selectedTask!.id, vm.normalizeVariables(variables), !!local);
					return true;
				},

				async getProcessVariables() {
					vm.requireSelectedTask();
					const inst = await vm.bpm!.getProcessInstance(vm.selectedTask!.processInstanceId);
					return toPlainData(inst?.variables ?? []);
				},

				async setProcessVariables(variables: unknown) {
					vm.requireSelectedTask();
					await vm.bpm!.setProcessVariables(vm.selectedTask!.processInstanceId, vm.normalizeVariables(variables));
					return true;
				},

				async claimTask() {
					vm.requireSelectedTask();
					const taskId = vm.selectedTask!.id;
					const updated = await vm.bpm!.claimTask(taskId);
					vm.patchTaskInList(updated);
					vm.applySelectedTaskUpdate(taskId, updated);
					return toPlainData(vm.serializeTask(updated));
				},

				async unclaimTask() {
					vm.requireSelectedTask();
					vm.requireOwnership();
					const taskId = vm.selectedTask!.id;
					const updated = await vm.bpm!.unclaimTask(taskId);
					vm.patchTaskInList(updated);
					vm.applySelectedTaskUpdate(taskId, updated);
					return toPlainData(vm.serializeTask(updated));
				},

				async setAssignee(assignee: string | null) {
					vm.requireSelectedTask();
					const taskId = vm.selectedTask!.id;
					const updated = await vm.bpm!.setTaskAssignee(taskId, assignee ?? null);
					vm.patchTaskInList(updated);
					vm.applySelectedTaskUpdate(taskId, updated);
					return toPlainData(vm.serializeTask(updated));
				},

				async completeTask(variables?: unknown) {
					vm.requireSelectedTask();
					vm.requireOwnership();
					// Capture the task being completed before awaiting the server.
					// Selecting another task while the call is in flight reassigns
					// vm.selectedTask; reading the live reference afterwards would
					// drop the newly selected task from the list and leave the
					// completed one behind.
					const completedId = vm.selectedTask!.id;
					await vm.bpm!.completeTask({
						taskId: completedId,
						variables: vm.normalizeVariables(variables),
					});
					// Drop the completed task from the runtime list.
					vm.tasks = vm.tasks.filter(t => t.id !== completedId);
					vm.filterList();
					// Only clear the form/selection when the completed task is
					// still the selected one; if the user has since picked another
					// task, keep that selection and its form.
					if (vm.selectedTask?.id === completedId) {
						vm.selectedTask = null;
						vm.clearForm();
					}
					return true;
				},

				// ----- CMS convenience (server-side JCR ACLs apply) -----
				//
				// `cms` above exposes the full service; these three are kept
				// because they are what forms actually reach for, and they
				// return the flat node shape the SDK's property accessors read.

				async getNode(path: string) {
					const p = String(path ?? '');
					if (!p) throw new Error('path is required');
					return toPlainData(vm.serializeCmsNode(await vm.cms!.getNode(p)));
				},

				async listChildren(path: string, opts?: { first?: number; after?: string }) {
					const p = String(path ?? '');
					if (!p) throw new Error('path is required');
					const o = opts || {};
					const conn = await vm.cms!.listChildren(p, {
						first: typeof o.first === 'number' ? o.first : 100,
						after: typeof o.after === 'string' ? o.after : undefined,
					});
					return toPlainData({
						nodes: conn.edges.map((e: { node: CmsNode }) => vm.serializeCmsNode(e.node)),
						pageInfo: conn.pageInfo,
						totalCount: conn.totalCount,
					});
				},

				// Write a single scalar property. Array writes can be added when
				// a form needs them; until then a form wanting one can go
				// through `cms` directly.
				async setNodeProperty(path: string, name: string, value: unknown) {
					const p = String(path ?? '');
					if (!p) throw new Error('path is required');
					const n = String(name ?? '');
					if (!n) throw new Error('property name is required');
					if (typeof value !== 'string' && typeof value !== 'number' && typeof value !== 'boolean') {
						throw new Error('property value must be a string, number, or boolean');
					}
					await vm.cms!.setProperty(p, n, value);
					return true;
				},

				// Read a node's binary content as text.
				async readNodeText(path: string): Promise<string> {
					const p = String(path ?? '');
					if (!p) throw new Error('path is required');
					const node = await vm.cms!.getNode(p);
					if (!node) throw new Error(`Node not found: ${p}`);
					if (!node.downloadUrl) throw new Error(`Node has no content: ${p}`);
					const res = await fetch(node.downloadUrl, { credentials: 'same-origin' });
					if (!res.ok) throw new Error(`Failed to read ${p}: HTTP ${res.status}`);
					return await res.text();
				},
			};
		},

		// ----- Shell app registry -----
		//
		// The installed-app list lives in the shell realm, so an app iframe
		// reads it through `window.parent.Webtop` — the same access Content
		// Browser uses to find an editor for a MIME type. Kept out of reactive
		// data on purpose: these are the shell's live Application objects, and
		// they are only ever read.

		shellApps(): Application[] {
			return (window.parent?.Webtop?.apps || []) as Application[];
		},

		findApp(appId: string): Application | null {
			return this.shellApps().find((a: Application) => a.id === appId) || null;
		},

		// The app registered as an editor for a MIME type, `text/*` wildcards
		// included. Mirrors Content Browser's resolution so a form and a
		// double-click open the same file in the same app.
		findEditorForMimeType(mimeType: string): Application | null {
			const mt = String(mimeType || '');
			if (!mt) return null;
			for (const app of this.shellApps()) {
				if (!app.editor) continue;
				for (const pattern of app.contentTypes || []) {
					if (pattern.endsWith('/*')) {
						if (mt.startsWith(pattern.slice(0, -1))) return app;
					} else if (pattern === mt) {
						return app;
					}
				}
			}
			return null;
		},

		// ----- Operation guards -----
		//
		// Now that the form is same-origin these are business rules rather than
		// a sandbox boundary: they keep a form from acting on a task that is no
		// longer selected (or that the user does not own), which would leave
		// this app's list and selection out of step with the engine.

		requireMode(mode: Mode) {
			if (this.mode !== mode) throw new Error(`Operation not allowed in mode "${this.mode}"`);
		},

		requireSelectedTask() {
			if (!this.selectedTask) throw new Error('No task selected');
			if (!this.bpm) throw new Error('BPM service is not initialized');
		},

		requireSelectedDefinition() {
			if (!this.selectedDef) throw new Error('No process definition selected');
			if (!this.bpm) throw new Error('BPM service is not initialized');
		},

		// completeTask / unclaim require the user to own (be the assignee of) the task.
		requireOwnership() {
			const t = this.selectedTask!;
			if (t.assignee !== this.currentUserID) {
				throw new Error(this.t('app.tasks.error.notAssignee', undefined, 'You are not the assignee of this task. Claim it first.'));
			}
		},

		// ----- Serializers -----

		serializeTask(t: Task) {
			return {
				id: t.id,
				name: t.name,
				description: t.description,
				assignee: t.assignee,
				owner: t.owner,
				created: t.created,
				due: t.due,
				priority: t.priority,
				processInstanceId: t.processInstanceId,
				processDefinitionId: t.processDefinitionId,
				processDefinitionKey: t.processDefinitionKey,
				taskDefinitionKey: t.taskDefinitionKey,
				formKey: t.formKey,
				candidateUsers: t.candidateUsers,
				candidateGroups: t.candidateGroups,
				businessKey: t.businessKey ?? t.processInstance?.businessKey ?? null,
			};
		},

		serializeDefinition(d: ProcessDefinition) {
			return {
				id: d.id,
				key: d.key,
				name: d.name,
				description: d.description,
				version: d.version,
				category: d.category,
				startFormKey: d.startFormKey,
			};
		},

		serializeInstance(i: ProcessInstance) {
			return {
				id: i.id,
				definitionId: i.definitionId,
				definitionKey: i.definitionKey,
				businessKey: i.businessKey,
				startTime: i.startTime,
			};
		},

		serializeCmsNode(n: CmsNode | null) {
			if (!n) return null;
			return {
				path: n.path,
				name: n.name,
				nodeType: n.nodeType,
				uuid: n.uuid,
				mimeType: n.mimeType,
				size: n.size,
				hasChildren: n.hasChildren,
				downloadUrl: n.downloadUrl,
				modified: n.modified,
				modifiedBy: n.modifiedBy,
				properties: n.properties,
			};
		},

		// Camunda Typed Values flow through unchanged; we just default to
		// inferring the type on the server side when omitted.
		normalizeVariables(input: unknown): ProcessVariableInput[] {
			if (!input) return [];
			if (!Array.isArray(input)) {
				throw new Error('variables must be an array of { name, value, type? }');
			}
			const out: ProcessVariableInput[] = [];
			for (const v of input) {
				if (!v || typeof v !== 'object') continue;
				const name = (v as any).name;
				if (typeof name !== 'string' || !name) {
					throw new Error('variable.name is required');
				}
				out.push({
					name,
					value: (v as any).value,
					type: (v as any).type,
					valueInfo: (v as any).valueInfo,
				});
			}
			return out;
		},

		patchTaskInList(updated: Partial<Task>) {
			const idx = this.tasks.findIndex(t => t.id === updated.id);
			if (idx >= 0) {
				this.tasks[idx] = { ...this.tasks[idx], ...updated } as Task;
				this.filterList();
			}
		},

		// Merge a server-side task update into the current selection, but only
		// when the selection still points at the same task. The user may select
		// a different task while an async task operation is in flight; the newer
		// selection must win, so a stale update must not clobber it. Returns true
		// when the selection was still current and was updated.
		applySelectedTaskUpdate(taskId: string, updated: Partial<Task>): boolean {
			if (this.selectedTask?.id !== taskId) return false;
			this.selectedTask = { ...this.selectedTask, ...updated };
			return true;
		},

		// =====================================================================
		// Toolbar actions (claim/unclaim/assign) — same business rules as RPC
		// =====================================================================

		async claimSelectedTask() {
			if (!this.selectedTask || !this.bpm) return;
			const taskId = this.selectedTask.id;
			try {
				const updated = await this.bpm.claimTask(taskId);
				this.patchTaskInList(updated);
				if (this.applySelectedTaskUpdate(taskId, updated)) this.notifyFormContext();
			} catch (err) {
				this.errorMessage = this.t('app.tasks.error.claim', { detail: err instanceof Error ? err.message : String(err) }, 'Failed to claim: {detail}');
			}
		},

		async unclaimSelectedTask() {
			if (!this.selectedTask || !this.bpm) return;
			const taskId = this.selectedTask.id;
			try {
				const updated = await this.bpm.unclaimTask(taskId);
				this.patchTaskInList(updated);
				if (this.applySelectedTaskUpdate(taskId, updated)) this.notifyFormContext();
			} catch (err) {
				this.errorMessage = this.t('app.tasks.error.unclaim', { detail: err instanceof Error ? err.message : String(err) }, 'Failed to unclaim: {detail}');
			}
		},

		// Take over a task currently assigned to someone else. Unlike `claim`,
		// which only succeeds when the task is unassigned, takeover forcibly
		// reassigns the task to the current user via setTaskAssignee. The
		// engine-side authorization decides whether the caller is allowed to
		// do so — the client just asks for confirmation first because this
		// strips the previous assignee of access.
		confirmTakeOverSelectedTask() {
			if (!this.selectedTask) return;
			const t = this.selectedTask;
			this.dialog = {
				type: 'takeover',
				data: {
					taskId: t.id,
					taskName: t.name || t.taskDefinitionKey,
					currentAssignee: t.assignee || '',
				},
			};
		},

		async executeTakeOver() {
			if (!this.selectedTask || !this.bpm) return;
			const taskId = this.selectedTask.id;
			try {
				const updated = await this.bpm.setTaskAssignee(taskId, this.currentUserID);
				this.patchTaskInList(updated);
				this.closeDialog();
				if (this.applySelectedTaskUpdate(taskId, updated)) this.notifyFormContext();
			} catch (err) {
				this.errorMessage = this.t('app.tasks.error.takeover', { detail: err instanceof Error ? err.message : String(err) }, 'Failed to take over: {detail}');
			}
		},

		showAssignDialog() {
			if (!this.selectedTask) return;
			this.dialog = {
				type: 'assign',
				data: {
					taskId: this.selectedTask.id,
					taskName: this.selectedTask.name || this.selectedTask.taskDefinitionKey,
					assignee: '',
					assigneeDisplayName: '',
					searchResults: [] as IdpUser[],
					isSearching: false,
				},
			};
		},

		async executeAssign() {
			if (!this.selectedTask || !this.bpm) return;
			const assignee = String(this.dialog.data.assignee || '').trim();
			if (!assignee) return;
			const taskId = this.selectedTask.id;
			try {
				const updated = await this.bpm.setTaskAssignee(taskId, assignee);
				this.patchTaskInList(updated);
				this.closeDialog();
				if (this.applySelectedTaskUpdate(taskId, updated)) this.notifyFormContext();
			} catch (err) {
				this.errorMessage = this.t('app.tasks.error.assign', { detail: err instanceof Error ? err.message : String(err) }, 'Failed to assign: {detail}');
			}
		},

		closeDialog() {
			if (this._assigneeSearchTimer) {
				clearTimeout(this._assigneeSearchTimer);
				this._assigneeSearchTimer = null;
			}
			this.closeAssigneeSuggestionsPopup();
			this.dialog = { type: '', data: {} };
		},

		// =====================================================================
		// Assign dialog — user search (mirrors content-browser ACL behavior)
		// =====================================================================

		// Debounced input handler. Editing the value invalidates any prior
		// "selected" state — display name must match the value the user is
		// about to submit, so we clear it whenever the input changes.
		onAssigneeSearchInput() {
			this.dialog.data.assigneeDisplayName = '';
			if (this._assigneeSearchTimer) {
				clearTimeout(this._assigneeSearchTimer);
			}
			this._assigneeSearchTimer = setTimeout(() => {
				this.searchAssigneeUsers();
			}, 300);
		},

		onAssigneeSearchFocus() {
			const results = (this.dialog.data.searchResults as IdpUser[]) || [];
			if (results.length > 0) {
				this.refreshAssigneeSuggestionsPopup();
			}
		},

		async searchAssigneeUsers() {
			const keyword = String(this.dialog.data.assignee || '').trim();
			if (!keyword || !this.idp) {
				this.dialog.data.searchResults = [];
				this.closeAssigneeSuggestionsPopup();
				return;
			}
			this.dialog.data.isSearching = true;
			try {
				const conn = await this.idp.listUsers({ first: 20, query: keyword });
				this.dialog.data.searchResults = conn.edges.map((e: { node: IdpUser }) => e.node);
				this.refreshAssigneeSuggestionsPopup();
			} catch {
				this.dialog.data.searchResults = [];
				this.closeAssigneeSuggestionsPopup();
			} finally {
				this.dialog.data.isSearching = false;
			}
		},

		// Map IdpUser results into PopupItem shape. Show display name (or
		// username) as the primary label and email/username as the secondary
		// description so users can disambiguate similarly-named accounts.
		buildAssigneeSuggestionItems() {
			const results = (this.dialog.data.searchResults as IdpUser[]) || [];
			return results.map(u => {
				const label = u.displayName || u.username;
				const description = u.mail
					? (u.mail !== label ? `${u.mail} · ${u.username}` : u.username)
					: (label !== u.username ? u.username : '');
				return {
					id: u.username,
					label,
					description,
					icon: 'bi bi-person',
				};
			});
		},

		refreshAssigneeSuggestionsPopup() {
			const results = (this.dialog.data.searchResults as IdpUser[]) || [];
			if (results.length === 0) {
				this.closeAssigneeSuggestionsPopup();
				return;
			}
			const items = this.buildAssigneeSuggestionItems();
			if (assigneePopupHandle) {
				assigneePopupHandle.update(items);
				return;
			}
			const input = this.$refs.assigneeInput as HTMLInputElement | undefined;
			if (!input || !this.instance) return;
			const rect = input.getBoundingClientRect();
			const handle = this.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				maxHeight: 360,
				items,
			});
			assigneePopupHandle = handle;
			handle.result.then((picked: string | number | null) => {
				assigneePopupHandle = null;
				if (picked == null) return;
				const match = ((this.dialog.data.searchResults as IdpUser[]) || [])
					.find(u => u.username === picked);
				if (match) this.selectAssigneeFromSearch(match);
			});
		},

		closeAssigneeSuggestionsPopup() {
			if (assigneePopupHandle) {
				assigneePopupHandle.close();
				assigneePopupHandle = null;
			}
		},

		selectAssigneeFromSearch(user: IdpUser) {
			this.dialog.data.assignee = user.username;
			this.dialog.data.assigneeDisplayName = user.displayName || '';
			this.dialog.data.searchResults = [];
			this.closeAssigneeSuggestionsPopup();
		},

		// =====================================================================
		// Date helpers
		// =====================================================================

		isOverdue(due: string | null | undefined): boolean {
			if (!due) return false;
			return new Date(due).getTime() < nowMs();
		},

		formatRelativeDate(value: string | null | undefined): string {
			if (!value) return '';
			const d = new Date(value).getTime();
			if (Number.isNaN(d)) return '';
			// Locale-aware relative/friendly time (e.g. "in 3 days", "2時間前").
			// Routed through the localization snapshot so it repaints on
			// locale / time-zone change and renders in the user's language.
			// Delegates to Dates.formatFriendly (Intl.RelativeTimeFormat).
			return formatDate(this.localization, value, { format: 'friendly' });
		},
	},
};

function startOfToday(): number {
	const d = new Date();
	d.setHours(0, 0, 0, 0);
	return d.getTime();
}

// Mount immediately. The screen itself is behind the readiness gate
// (<template v-if="isReady"> in index.html), which appLaunch opens once the
// component templates are loaded — so mounting no longer has to wait on a
// fetch, and window.appLaunch is defined the moment the iframe finishes
// loading.
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');

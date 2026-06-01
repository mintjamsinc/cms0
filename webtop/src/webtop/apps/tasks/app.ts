/**
 * Tasks Application
 *
 * Mail-like UI for starting business processes and processing user tasks.
 * Forms are user-authored HTML stored in CMS; they run inside a sandboxed
 * iframe and talk to the host app via a postMessage RPC bridge.
 *
 * Modes:
 *   - tasks-runtime : active user tasks (assigned to me / my candidate groups)
 *   - tasks-history : completed tasks (read-only)
 *   - start         : startable process definitions
 *
 * Layout: 3 panes — search filters / list / form iframe.
 */

import { ApplicationInstance } from "../../services/webtop-service.js";
import { BpmServiceGraphQL } from "../../services/bpm-service-graphql.js";
import { IdpServiceGraphQL } from "../../services/idp-service-graphql.js";
import { ContentServiceGraphQL } from "../../services/content-service-graphql.js";
import { createGraphQLClient } from "../../graphql/client.js";
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

type Mode = 'tasks-runtime' | 'tasks-history' | 'start';

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
	dueLabel: string;
	dueRange: 'any' | 'today' | 'week' | 'overdue';
	priorityMin: number;
	category: string;
	favoritesOnly: boolean;
}

interface DialogState {
	type: string;
	data: Record<string, unknown>;
}

// JSON-RPC-style messages exchanged with the iframe.
// Iframes send { __tasksRpc: 'call', id, method, params }; parent replies with
// { __tasksRpc: 'result', id, ok, data } | { __tasksRpc: 'result', id, ok: false, error }.
interface RpcCall {
	__tasksRpc: 'call';
	id: string;
	method: string;
	params?: unknown;
}

interface RpcReady {
	__tasksRpc: 'ready';
}

type FormContextEvent =
	| { __tasksRpc: 'event'; type: 'context'; payload: Record<string, unknown> }
	| { __tasksRpc: 'event'; type: 'theme-changed'; theme: string };

// Suggestion popup for the "Assign Task" dialog. Module-scoped because the
// shell-managed popup outlives any single dialog open/close cycle and there
// can only be one open at a time.
let assigneePopupHandle: import('../../services/webtop-service.js').PopupHandle | null = null;

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

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			bpm: null as BpmServiceGraphQL | null,
			idp: null as IdpServiceGraphQL | null,
			cms: null as ContentServiceGraphQL | null,

			messageListener: null as ((event: MessageEvent) => void) | null,

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
				dueLabel: '',
				dueRange: 'any' as const,
				priorityMin: 0,
				category: '',
				favoritesOnly: false,
			} as Filters,

			// Tasks
			tasks: [] as Task[],
			processDefByKey: {} as Record<string, ProcessDefinition>,
			selectedTask: null as (Task & { completed?: boolean }) | null,

			// Process definitions (latest, active, startable)
			definitions: [] as ProcessDefinition[],
			selectedDef: null as ProcessDefinition | null,
			favorites: {} as Favorites,

			// Filtered list shown in the middle pane
			filteredItems: [] as Array<Task | ProcessDefinition>,

			// Form iframe state
			currentFormKey: '' as string,
			formSrc: '' as string,
			formLoading: false,
			formError: '' as string,
			formReady: false,
			pendingRpcs: {} as Record<string, { resolve: (v: unknown) => void; reject: (e: Error) => void }>,

			// Mirrors document.documentElement.dataset.theme so the value can be
			// forwarded to the form iframe (which has no same-origin access to
			// the parent document).
			currentTheme: 'light' as string,

			// Dialog
			dialog: {
				type: '',
				data: {},
			} as DialogState,

			// Resize handles
			_resizing: null as null | { kind: 'search' | 'list'; startX: number; startWidth: number },

			// Debounce timer for the assign-dialog user search (matches the
			// "Add ACL Entry" flow in content-browser).
			_assigneeSearchTimer: null as ReturnType<typeof setTimeout> | null,
		};
	},

	computed: {
		hasSelection(): boolean {
			return this.mode === 'start' ? !!this.selectedDef : !!this.selectedTask;
		},

		emptyListMessage(): string {
			if (this.mode === 'start') return 'No startable processes';
			if (this.mode === 'tasks-history') return 'No completed tasks';
			return 'No tasks';
		},

		emptyFormMessage(): string {
			if (this.mode === 'start') return 'Select a process to start';
			return 'Select a task to process';
		},

		noFormKeyMessage(): string {
			if (this.mode === 'start') return 'This process has no start form (formKey).';
			return 'This task has no form (formKey).';
		},

		contextPlaceholder(): string {
			if (this.mode === 'start') return 'Process start';
			if (this.mode === 'tasks-history') return 'Completed tasks';
			return 'My tasks';
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
			return !!this._resizing;
		},
	},

	methods: {
		// =====================================================================
		// Lifecycle
		// =====================================================================

		async onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				vm.handleWindowMessage(event);
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (appInstance: ApplicationInstance) => {
				vm.instance = vm.$markRaw(appInstance);

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;
				vm.currentTheme = theme;
				vm.instance.windowTitle = 'Tasks';
				vm.workspace = appInstance.api.workspace;

				await vm.initServices();
				await vm.loadCurrentUser();
				vm.loadFavorites();
				await vm.refresh();

				vm.$nextTick(() => {
					appInstance.notifyLaunched();
				});
			};
		},

		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
		},

		async initServices() {
			const client = createGraphQLClient(this.workspace);
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

		onSearchResizeStart(event: MouseEvent) {
			this._resizing = { kind: 'search', startX: event.clientX, startWidth: this.searchPanelWidth };
			window.addEventListener('mousemove', this.onResizeMove);
			window.addEventListener('mouseup', this.onResizeEnd);
			event.preventDefault();
		},
		onListResizeStart(event: MouseEvent) {
			this._resizing = { kind: 'list', startX: event.clientX, startWidth: this.listPanelWidth };
			window.addEventListener('mousemove', this.onResizeMove);
			window.addEventListener('mouseup', this.onResizeEnd);
			event.preventDefault();
		},
		onResizeMove(event: MouseEvent) {
			const r = this._resizing;
			if (!r) return;
			const delta = event.clientX - r.startX;
			if (r.kind === 'search') {
				this.searchPanelWidth = Math.max(180, Math.min(420, r.startWidth + delta));
			} else {
				this.listPanelWidth = Math.max(220, Math.min(560, r.startWidth + delta));
			}
		},
		onResizeEnd() {
			this._resizing = null;
			window.removeEventListener('mousemove', this.onResizeMove);
			window.removeEventListener('mouseup', this.onResizeEnd);
		},

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
			} else if (this.mode === 'tasks-runtime') {
				await this.loadTasks();
			} else {
				await this.loadHistoryTasks();
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
				this.errorMessage = 'Failed to load processes: ' + (err instanceof Error ? err.message : String(err));
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
				this.errorMessage = 'Failed to load tasks: ' + (err instanceof Error ? err.message : String(err));
			} finally {
				this.isLoading = false;
			}
		},

		async loadHistoryTasks() {
			// Camunda history tasks are not currently exposed through the GraphQL
			// surface used by this app. Show an empty list with a hint until the
			// server adds a historicTasks query.
			this.tasks = [];
			this.filteredItems = [];
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

		clearTaskKeyword() { this.filters.taskKeyword = ''; this.filterList(); },
		clearDefKeyword() { this.filters.defKeyword = ''; this.filterList(); },

		// =====================================================================
		// Filter popups (postMessage → shell popup)
		// =====================================================================

		async openDuePickerPopup(event: MouseEvent) {
			const options: Array<{ id: Filters['dueRange']; label: string }> = [
				{ id: 'any', label: 'Any time' },
				{ id: 'overdue', label: 'Overdue' },
				{ id: 'today', label: 'Due today' },
				{ id: 'week', label: 'Due this week' },
			];
			const handle = this.instance?.popup.open({
				anchor: (event.currentTarget as HTMLElement).getBoundingClientRect(),
				items: options.map(o => ({ id: o.id, label: o.label, selected: this.filters.dueRange === o.id })),
				placement: 'bottom-start',
				minWidth: 200,
			});
			const result = await handle?.result;
			if (result !== null && result !== undefined) {
				const id = String(result) as Filters['dueRange'];
				const opt = options.find(o => o.id === id);
				if (opt) {
					this.filters.dueRange = opt.id;
					this.filters.dueLabel = opt.id === 'any' ? '' : opt.label;
					this.filterList();
				}
			}
		},

		async openCategoryPickerPopup(event: MouseEvent) {
			const cats = Array.from(new Set(this.definitions.map(d => d.category).filter(Boolean) as string[])).sort();
			const items = [
				{ id: '', label: 'All categories', selected: !this.filters.category },
				...cats.map(c => ({ id: c, label: c, selected: this.filters.category === c })),
			];
			const handle = this.instance?.popup.open({
				anchor: (event.currentTarget as HTMLElement).getBoundingClientRect(),
				items,
				placement: 'bottom-start',
				minWidth: 200,
			});
			const result = await handle?.result;
			if (result !== null && result !== undefined) {
				this.filters.category = String(result);
				this.filterList();
			}
		},

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
		// Form iframe — load + RPC bridge
		// =====================================================================

		clearForm() {
			this.currentFormKey = '';
			this.formSrc = '';
			this.formError = '';
			this.formLoading = false;
			this.formReady = false;
			this.rejectAllPendingRpcs(new Error('Form closed'));
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
					this.formError = `Unsupported formKey: ${formKey}. Expected cms:/...`;
					return;
				}
				if (!this.cms) {
					this.formError = 'CMS service is not initialized';
					return;
				}
				// Resolving the node verifies existence + read ACL server-side;
				// the iframe then loads the rendered HTML via downloadUrl (or the
				// cms.html servlet path as a fallback). The node's modified time
				// also feeds any ${...} cache-busting tokens in the query string.
				const node = await this.cms.getNode(path);
				if (!node) {
					this.formError = `Form not found: ${path}`;
					return;
				}
				const resolvedQuery = resolveFormKeyTokens(query, node);
				this.formSrc = cmsPathToFrameSrc(this.workspace, path)
					+ (resolvedQuery ? '?' + resolvedQuery : '');
			} catch (err) {
				this.formError = 'Failed to load form: ' + (err instanceof Error ? err.message : String(err));
			} finally {
				this.formLoading = false;
			}
		},

		onFormFrameLoad() {
			// The iframe will signal readiness via the 'ready' RPC; once received,
			// we push the current context.
			this.formReady = false;
		},

		// ichigo.js does not expose Vue-style $refs, so look up the iframe by
		// its `ref` attribute selector (matching the pattern used by other
		// Webtop apps).
		getFormFrame(): HTMLIFrameElement | null {
			return document.querySelector('iframe[ref="formFrame"]');
		},

		// Stable origin check: sandboxed iframes report origin as "null".
		// We additionally verify that the source is the form iframe's window,
		// which is unforgeable.
		isFromFormFrame(event: MessageEvent): boolean {
			const frame = this.getFormFrame();
			if (!frame || !frame.contentWindow) return false;
			return event.source === frame.contentWindow;
		},

		handleWindowMessage(event: MessageEvent) {
			const data = event.data;

			// Theme propagation from shell (same-origin, parent → app).
			if (data && typeof data === 'object' && data.type === 'theme-changed') {
				if (event.origin === window.location.origin) {
					document.documentElement.dataset.theme = data.theme;
					this.currentTheme = data.theme;
					this.pushThemeToFrame();
				}
				return;
			}

			// Form iframe messages.
			if (data && typeof data === 'object' && data.__tasksRpc) {
				if (!this.isFromFormFrame(event)) return;
				if (data.__tasksRpc === 'ready') {
					this.formReady = true;
					// pushContextToFrame includes the current theme in its
					// payload, so a separate initial theme push is unnecessary.
					this.pushContextToFrame();
					return;
				}
				if (data.__tasksRpc === 'call') {
					this.handleRpcCall(data as RpcCall);
					return;
				}
			}
		},

		pushContextToFrame() {
			const frame = this.getFormFrame();
			if (!frame || !frame.contentWindow) return;
			const ctx = this.buildContext();
			// Carry the current theme alongside the context so the form can
			// paint with the correct palette on its first render. Subsequent
			// shell-driven changes are delivered via pushThemeToFrame().
			ctx.theme = this.currentTheme || 'light';
			const msg: FormContextEvent = {
				__tasksRpc: 'event',
				type: 'context',
				payload: ctx,
			};
			// Unwrap reactive Proxies (especially Proxy-wrapped arrays like
			// candidateUsers/candidateGroups) so structured clone can serialize.
			frame.contentWindow.postMessage(JSON.parse(JSON.stringify(msg)), '*');
		},

		// Sandboxed form iframes have origin "null" and cannot read the
		// parent's data-theme attribute, so theme updates must be pushed
		// explicitly. Called when the shell broadcasts a theme change; the
		// initial theme rides along inside the context payload.
		pushThemeToFrame() {
			if (!this.formReady) return;
			const frame = this.getFormFrame();
			if (!frame || !frame.contentWindow) return;
			const msg: FormContextEvent = {
				__tasksRpc: 'event',
				type: 'theme-changed',
				theme: this.currentTheme || 'light',
			};
			frame.contentWindow.postMessage(msg, '*');
		},

		buildContext(): Record<string, unknown> {
			if (this.mode === 'start' && this.selectedDef) {
				return {
					mode: 'start',
					currentUser: { id: this.currentUserID, displayName: this.currentUserDisplay },
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
					currentUser: { id: this.currentUserID, displayName: this.currentUserDisplay },
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
			return { mode: this.mode };
		},

		rejectAllPendingRpcs(err: Error) {
			for (const id of Object.keys(this.pendingRpcs)) {
				try { this.pendingRpcs[id].reject(err); } catch { /* noop */ }
			}
			this.pendingRpcs = {};
		},

		async handleRpcCall(call: RpcCall) {
			const frame = this.getFormFrame();
			if (!frame || !frame.contentWindow) return;

			const reply = (ok: boolean, data?: unknown, error?: string) => {
				const msg = {
					__tasksRpc: 'result',
					id: call.id,
					ok,
					data,
					error,
				};
				// Unwrap reactive Proxies before structured clone (see pushContextToFrame).
				frame.contentWindow!.postMessage(JSON.parse(JSON.stringify(msg)), '*');
			};

			try {
				const result = await this.dispatchRpc(call.method, call.params);
				reply(true, result);
			} catch (err) {
				const msg = err instanceof Error ? err.message : String(err);
				reply(false, undefined, msg);
			}
		},

		// Dispatch table — methods exposed to the iframe.
		// Each method MUST validate against the currently-selected context so
		// that a compromised iframe cannot manipulate other tasks/processes.
		async dispatchRpc(method: string, params: unknown): Promise<unknown> {
			const p = (params || {}) as Record<string, unknown>;
			switch (method) {

				// ----- Identity -----
				case 'getCurrentUser':
					return {
						id: this.currentUserID,
						displayName: this.currentUserDisplay,
						groups: this.myGroups,
					};

				// Look up another user (e.g. resolve an assignee username to a
				// display name). Returns null when the user does not exist.
				case 'getUser': {
					const username = String(p.username ?? '').trim();
					if (!username) throw new Error('username is required');
					if (!this.idp) throw new Error('IdP service is not initialized');
					const user = await this.idp.getUser(username);
					if (!user) return null;
					return {
						id: user.username,
						displayName: user.displayName || user.username,
						mail: user.mail,
					};
				}

				// ----- Process start (start mode only) -----
				case 'getProcessDefinition':
					this.requireMode('start');
					this.requireSelectedDefinition();
					return this.serializeDefinition(this.selectedDef!);

				case 'startProcess': {
					this.requireMode('start');
					this.requireSelectedDefinition();
					const variables = this.normalizeVariables(p.variables);
					const businessKey = typeof p.businessKey === 'string' ? p.businessKey : undefined;
					const inst = await this.bpm!.startProcess({
						definitionId: this.selectedDef!.id,
						businessKey,
						variables,
					});
					// Mark the form as completed; clear selection and refresh list later.
					this.errorMessage = '';
					this.$nextTick(() => this.refresh());
					return this.serializeInstance(inst);
				}

				// ----- Task operations (task modes only) -----
				case 'getTask':
					this.requireSelectedTask();
					return this.serializeTask(this.selectedTask!);

				case 'getTaskWithVariables': {
					this.requireSelectedTask();
					const full = await this.bpm!.getTask(this.selectedTask!.id);
					return full ? { ...this.serializeTask(full), variables: full.variables ?? [], localVariables: full.localVariables ?? [] } : null;
				}

				case 'getTaskVariables': {
					this.requireSelectedTask();
					const full = await this.bpm!.getTask(this.selectedTask!.id);
					return { variables: full?.variables ?? [], localVariables: full?.localVariables ?? [] };
				}

				case 'setTaskVariables': {
					this.requireSelectedTask();
					const variables = this.normalizeVariables(p.variables);
					const local = !!p.local;
					await this.bpm!.setTaskVariables(this.selectedTask!.id, variables, local);
					return true;
				}

				case 'getProcessVariables': {
					this.requireSelectedTask();
					const inst = await this.bpm!.getProcessInstance(this.selectedTask!.processInstanceId);
					return inst?.variables ?? [];
				}

				case 'setProcessVariables': {
					this.requireSelectedTask();
					const variables = this.normalizeVariables(p.variables);
					await this.bpm!.setProcessVariables(this.selectedTask!.processInstanceId, variables);
					return true;
				}

				case 'claimTask': {
					this.requireSelectedTask();
					const updated = await this.bpm!.claimTask(this.selectedTask!.id);
					this.selectedTask = { ...this.selectedTask!, ...updated };
					this.patchTaskInList(updated);
					return this.serializeTask(updated);
				}

				case 'unclaimTask': {
					this.requireSelectedTask();
					this.requireOwnership();
					const updated = await this.bpm!.unclaimTask(this.selectedTask!.id);
					this.selectedTask = { ...this.selectedTask!, ...updated };
					this.patchTaskInList(updated);
					return this.serializeTask(updated);
				}

				case 'setAssignee': {
					this.requireSelectedTask();
					const assignee = (p.assignee ?? null) as string | null;
					const updated = await this.bpm!.setTaskAssignee(this.selectedTask!.id, assignee);
					this.selectedTask = { ...this.selectedTask!, ...updated };
					this.patchTaskInList(updated);
					return this.serializeTask(updated);
				}

				case 'completeTask': {
					this.requireSelectedTask();
					this.requireOwnership();
					const variables = this.normalizeVariables(p.variables);
					await this.bpm!.completeTask({
						taskId: this.selectedTask!.id,
						variables,
					});
					// Drop the completed task from the runtime list.
					this.tasks = this.tasks.filter(t => t.id !== this.selectedTask!.id);
					this.filterList();
					this.selectedTask = null;
					this.clearForm();
					return true;
				}

				// ----- CMS read/write (relies on JCR ACLs server-side) -----
				case 'getNode': {
					const path = String(p.path ?? '');
					if (!path) throw new Error('path is required');
					return this.serializeCmsNode(await this.cms!.getNode(path));
				}

				case 'listChildren': {
					const path = String(p.path ?? '');
					if (!path) throw new Error('path is required');
					const conn = await this.cms!.listChildren(path, {
						first: typeof p.first === 'number' ? p.first : 100,
						after: typeof p.after === 'string' ? p.after : undefined,
					});
					return {
						edges: conn.edges.map((e: { node: CmsNode; cursor: string }) => ({
							node: this.serializeCmsNode(e.node),
							cursor: e.cursor,
						})),
						pageInfo: conn.pageInfo,
						totalCount: conn.totalCount,
					};
				}

				// Write a single property on a node. Server-side JCR ACLs gate the
				// actual write; the parent only forwards the call. Limited to
				// scalar values (string/number/boolean) — array writes can be
				// added when a form needs them.
				case 'setNodeProperty': {
					const path = String(p.path ?? '');
					if (!path) throw new Error('path is required');
					const name = String(p.name ?? '');
					if (!name) throw new Error('property name is required');
					const value = (p as { value?: unknown }).value;
					if (typeof value !== 'string' && typeof value !== 'number' && typeof value !== 'boolean') {
						throw new Error('property value must be a string, number, or boolean');
					}
					await this.cms!.setProperty(path, name, value);
					return true;
				}

				// Read a node's binary content as text. The form iframe runs in an
				// opaque origin (no allow-same-origin), so it cannot fetch the
				// downloadUrl with credentials directly; the parent (same-origin
				// with the CMS) does the fetch and returns the body as text.
				// Server-side JCR ACLs are enforced by getNode().
				case 'readNodeText': {
					const path = String(p.path ?? '');
					if (!path) throw new Error('path is required');
					const node = await this.cms!.getNode(path);
					if (!node) throw new Error(`Node not found: ${path}`);
					if (!node.downloadUrl) throw new Error(`Node has no content: ${path}`);
					const res = await fetch(node.downloadUrl, { credentials: 'same-origin' });
					if (!res.ok) {
						throw new Error(`Failed to read ${path}: HTTP ${res.status}`);
					}
					return await res.text();
				}

				default:
					throw new Error(`Unknown method: ${method}`);
			}
		},

		// ----- RPC guards -----

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
				throw new Error('You are not the assignee of this task. Claim it first.');
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

		// =====================================================================
		// Toolbar actions (claim/unclaim/assign) — same business rules as RPC
		// =====================================================================

		async claimSelectedTask() {
			if (!this.selectedTask || !this.bpm) return;
			try {
				const updated = await this.bpm.claimTask(this.selectedTask.id);
				this.selectedTask = { ...this.selectedTask, ...updated };
				this.patchTaskInList(updated);
				this.pushContextToFrame();
			} catch (err) {
				this.errorMessage = 'Failed to claim: ' + (err instanceof Error ? err.message : String(err));
			}
		},

		async unclaimSelectedTask() {
			if (!this.selectedTask || !this.bpm) return;
			try {
				const updated = await this.bpm.unclaimTask(this.selectedTask.id);
				this.selectedTask = { ...this.selectedTask, ...updated };
				this.patchTaskInList(updated);
				this.pushContextToFrame();
			} catch (err) {
				this.errorMessage = 'Failed to unclaim: ' + (err instanceof Error ? err.message : String(err));
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
			try {
				const updated = await this.bpm.setTaskAssignee(this.selectedTask.id, this.currentUserID);
				this.selectedTask = { ...this.selectedTask, ...updated };
				this.patchTaskInList(updated);
				this.closeDialog();
				this.pushContextToFrame();
			} catch (err) {
				this.errorMessage = 'Failed to take over: ' + (err instanceof Error ? err.message : String(err));
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
			try {
				const updated = await this.bpm.setTaskAssignee(this.selectedTask.id, assignee);
				this.selectedTask = { ...this.selectedTask, ...updated };
				this.patchTaskInList(updated);
				this.closeDialog();
				this.pushContextToFrame();
			} catch (err) {
				this.errorMessage = 'Failed to assign: ' + (err instanceof Error ? err.message : String(err));
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
			const input = document.querySelector('.tasks-assignee-input') as HTMLInputElement | null;
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
			const diffMs = d - nowMs();
			const absSec = Math.abs(Math.round(diffMs / 1000));
			const past = diffMs < 0;
			const fmt = (n: number, unit: string) => `${past ? '' : 'in '}${n} ${unit}${n === 1 ? '' : 's'}${past ? ' ago' : ''}`;
			if (absSec < 60) return past ? 'just now' : 'in a moment';
			const min = Math.round(absSec / 60);
			if (min < 60) return fmt(min, 'min');
			const hr = Math.round(min / 60);
			if (hr < 24) return fmt(hr, 'hr');
			const day = Math.round(hr / 24);
			if (day < 14) return fmt(day, 'day');
			const wk = Math.round(day / 7);
			if (wk < 9) return fmt(wk, 'wk');
			const mo = Math.round(day / 30);
			if (mo < 12) return fmt(mo, 'mo');
			const yr = Math.round(day / 365);
			return fmt(yr, 'yr');
		},
	},
};

function startOfToday(): number {
	const d = new Date();
	d.setHours(0, 0, 0, 0);
	return d.getTime();
}

// Mount the app
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');

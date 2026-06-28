/**
 * BPM Console Application
 *
 * Management UI for Camunda 7 BPM process definitions, instances, and tasks.
 * Layout: left sidebar (process list), center (BPMN viewer), bottom (instances/tasks),
 * right panel (detail / variable editor).
 */

import { ApplicationInstance } from "../../services/webtop-service.js";
import { BpmServiceGraphQL } from "../../services/bpm-service-graphql.js";
import { IdpServiceGraphQL } from "../../services/idp-service-graphql.js";
import { createGraphQLClient } from "../../graphql/client.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";
import type {
	ProcessDefinition,
	ProcessInstance,
	Task,
	ProcessVariable,
	Incident,
	IncidentType,
	JobProgressEvent,
	JobStatus,
	MigrationJob,
} from "../../graphql/types.js";
import {
	parseBpmnXml,
	getConnectionPath,
	getConnectionLabelPosition,
	calculateViewBox,
} from "./bpmn-viewer.js";
import type { BpmnViewModel } from "./bpmn-viewer.js";

type BottomTab = 'instances' | 'tasks' | 'incidents';
type DetailType = 'definition' | 'instance' | 'task' | 'incident' | 'none';
type ViewMode = 'diagram' | 'xml';

interface ProcessGroup {
	key: string;
	name: string;
	versions: ProcessDefinition[];
	expanded: boolean;
	latestVersion: number;
}

interface DialogVariable {
	name: string;
	type: string;
	value: string;
}

interface DialogState {
	type: string;
	data: Record<string, unknown>;
}

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			bpm: null as BpmServiceGraphQL | null,
			idp: null as IdpServiceGraphQL | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			// Reactive Localization snapshot (effective locale + IANA time
			// zone). Date displays, the TZ badge and datetime-local round-trip
			// read this. See `composables/use-localization.ts`.
			localization: createLocalizationSnapshot(),
			_onKeyDown: null as ((event: KeyboardEvent) => void) | null,
			_onKeyUp: null as ((event: KeyboardEvent) => void) | null,
			_onVisibilityChange: null as (() => void) | null,

			// Live tail — periodic auto-refresh, engaged by long-pressing the
			// Refresh button.  While `liveTailActive` is true the toolbar icon
			// spins and `refresh()` runs every `liveTailIntervalMs` ms.  A
			// single click toggles it off.  The interval pauses while the
			// document is hidden so we don't burn requests in a backgrounded
			// tab — it resumes (with an immediate refresh) on visibility.
			liveTailActive: false,
			liveTailIntervalMs: 3000,
			_liveTailTimer: null as number | null,
			_liveTailPressTimer: null as number | null,
			_liveTailLongPressed: false,
			_liveTailRefreshing: false,

			// General state
			isLoading: false,
			errorMessage: '',
			workspace: '',

			// SVG sprite URL — populated on appLaunch. The diagram renders
			// task / event / gateway icons via <use href="..."> against the
			// shared sprite from the BPMN Modeler so the two viewers stay in
			// visual lock-step.
			spriteUrl: '',

			// Process definitions
			processDefs: [] as ProcessDefinition[],
			processGroups: [] as ProcessGroup[],
			filteredGroups: [] as ProcessGroup[],
			defSearch: '',
			selectedGroup: null as ProcessGroup | null,
			selectedDef: null as ProcessDefinition | null,
			bpmnXml: '' as string,
			bpmnModel: null as BpmnViewModel | null,
			viewMode: 'diagram' as ViewMode,

			// Diagram pan/zoom (transform-based like bpmn-editor)
			diagramZoom: 1,
			diagramPanX: 0,
			diagramPanY: 0,
			diagramPanning: false,
			diagramPanStartX: 0,
			diagramPanStartY: 0,
			diagramPanStartPanX: 0,
			diagramPanStartPanY: 0,
			spacePressed: false,

			// Instance counts per definition ID { [defId]: { active: n, ended: n } }
			defInstanceCounts: {} as Record<string, { active: number; ended: number }>,

			// Global incident counts per definition ID (independent of current
			// selection — drives tree badges even when no group is selected).
			globalIncidentCounts: {} as Record<string, number>,

			// Instances (bottom panel)
			instances: [] as ProcessInstance[],
			selectedInstance: null as ProcessInstance | null,
			instanceVariables: [] as ProcessVariable[],
			instanceDef: null as ProcessDefinition | null,
			activityHistory: [] as { activityId: string; activityType: string; endTime?: string | null }[],

			// Diagram element filter (click on BPMN element to filter bottom panel)
			diagramFilterElementId: '' as string,
			diagramFilterInstanceIds: {} as Record<string, boolean>,

			// Tasks (bottom panel)
			tasks: [] as Task[],
			selectedTask: null as Task | null,

			// Incidents (bottom panel) — provisional mock state
			incidents: [] as Incident[],
			selectedIncident: null as Incident | null,
			incidentSelection: {} as Record<string, boolean>,

			// Bottom panel
			bottomTab: 'instances' as BottomTab,
			bottomPanelHeight: 220,

			// Detail type
			activeDetailType: 'none' as DetailType,

			// Definition context menu (stores target for action callback)
			defContextMenu: {
				def: null as ProcessDefinition | null,
			},

			// Dialog
			dialog: {
				type: '',
				data: {},
			} as DialogState,

			// Migration progress monitor — populated when Migrate is submitted.
			// The dialog hands the user off to a full-app overlay so the migrate
			// flow stays visible while the Camunda batch executor drains jobs.
			// Until the server's `jobProgress(jobId)` subscription produces an
			// event the monitor shows the seeded counts from the mutation
			// response; after `progressTimeoutMs` of silence it flips to
			// "running in background" with a Close button.  `abortable` mirrors
			// the server's flag — `false` for Camunda 7 migrations, so the
			// Abort button stays hidden.
			migrationMonitor: null as null | {
				jobId: string;
				abortable: boolean;
				sourceKey: string;
				sourceLabel: string;
				targetLabel: string;
				jobsTotal: number;
				jobsCompleted: number;
				status: JobStatus;
				errorMessage: string;
				isFinished: boolean;
				/** True when the server hasn't emitted any progress within the timeout. */
				isUnreachable: boolean;
				unsubscribe: null | (() => void);
				timeoutHandle: number | null;
				autoCloseHandle: number | null;
			},

			// Pane visibility
			sidebarPanelVisible: true,
			bottomPanelVisible: true,
			detailPanelVisible: true,

			// Pane sizes
			sidebarPanelWidth: 280,
			detailPanelWidth: 320,
		};
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
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) {
					return;
				}
				if (type === 'context-menu-action') {
					vm.handleContextMenuAction(payload.action);
				}
				// Drill-down re-target while already open (singleton re-launch).
				if (type === 'app-reopen') {
					vm.applyLaunchOptions(payload.options);
				}
			};
			window.addEventListener('message', vm.messageListener);

			// SPACE key for panning mode
			vm._onKeyDown = (e: KeyboardEvent) => {
				if (e.code === 'Space' && !e.repeat) {
					vm.spacePressed = true;
				}
			};
			vm._onKeyUp = (e: KeyboardEvent) => {
				if (e.code === 'Space') {
					vm.spacePressed = false;
					if (vm.diagramPanning) {
						vm.diagramPanning = false;
					}
				}
			};
			window.addEventListener('keydown', vm._onKeyDown);
			window.addEventListener('keyup', vm._onKeyUp);

			// Pause live tail while the tab is hidden; resume (with an
			// immediate refresh) on becoming visible again.
			vm._onVisibilityChange = () => {
				if (!vm.liveTailActive) return;
				if (document.visibilityState === 'hidden') {
					vm._stopLiveTailInterval();
				} else {
					vm._startLiveTailInterval(true);
				}
			};
			document.addEventListener('visibilitychange', vm._onVisibilityChange);

			window.appLaunch = async (appInstance: ApplicationInstance, options?: Record<string, any>) => {
				vm.instance = vm.$markRaw(appInstance);

				vm.spriteUrl = new URL(
					'assets/icons/icons.svg',
					window.location.origin + window.location.pathname,
				).href;

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;
				vm.instance.windowTitle = vm.t('app.bpm-console.title', undefined, 'BPM Console');

				refreshLocalization(vm.localization, vm.instance);

				vm.workspace = appInstance.api.workspace;

				vm.initBpmService();
				await vm.loadDefinitions();

				// A drill-down (e.g. from the Dashboard) may target a specific
				// process and view (instances / incidents). Apply it once the
				// definitions — and therefore the process groups — are loaded.
				await vm.applyLaunchOptions(options);

				vm.$nextTick(() => {
					appInstance.notifyLaunched();
				});
			};
		},

		async onUnmount() {
			this.stopLiveTail();
			this.cancelLiveTailPress();
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
			if (this._onKeyDown) {
				window.removeEventListener('keydown', this._onKeyDown);
			}
			if (this._onKeyUp) {
				window.removeEventListener('keyup', this._onKeyUp);
			}
			if (this._onVisibilityChange) {
				document.removeEventListener('visibilitychange', this._onVisibilityChange);
			}
			this.closeMigrationMonitor();
		},

		initBpmService() {
			const client = createGraphQLClient(this.workspace);
			this.bpm = this.$markRaw(new BpmServiceGraphQL(client));
			this.idp = this.$markRaw(new IdpServiceGraphQL(client));
		},

		async refresh() {
			await this.loadDefinitions();
			const tasks: Promise<unknown>[] = [];
			if (this.selectedGroup) {
				tasks.push(
					this.loadBpmnDiagram(this.selectedGroup.versions[0]?.id),
					this.loadInstancesForGroup(this.selectedGroup),
				);
			} else if (this.selectedDef) {
				tasks.push(
					this.loadBpmnDiagram(this.selectedDef.id),
					this.loadInstancesForDefinition(this.selectedDef),
				);
			}
			// Refresh sidebar badges (Active / Ended) for every expanded group
			// so counts visible in the tree stay in sync with the latest state.
			for (const group of this.processGroups as ProcessGroup[]) {
				if (group.expanded) {
					tasks.push(this.loadDefInstanceCounts(group));
				}
			}
			await Promise.all(tasks);
		},

		// =====================================================================
		// Live tail (long-press the Refresh button to engage)
		// =====================================================================

		onRefreshPointerDown(e: PointerEvent) {
			// Only react to the primary pointer button.  Right-click /
			// middle-click should not arm the long-press.
			if (e.button !== 0) return;
			this.cancelLiveTailPress();
			this._liveTailLongPressed = false;
			this._liveTailPressTimer = window.setTimeout(() => {
				this._liveTailPressTimer = null;
				this._liveTailLongPressed = true;
				this.startLiveTail();
			}, 1500);
		},

		onRefreshPointerUp() {
			const timer = this._liveTailPressTimer;
			this._liveTailPressTimer = null;
			if (timer !== null) {
				clearTimeout(timer);
			}
			// Long-press already fired — pointerup just releases the button,
			// don't toggle anything.
			if (this._liveTailLongPressed) {
				this._liveTailLongPressed = false;
				return;
			}
			// Short click: stop live tail if running, otherwise one-shot refresh.
			if (this.liveTailActive) {
				this.stopLiveTail();
			} else {
				this.refresh();
			}
		},

		cancelLiveTailPress() {
			if (this._liveTailPressTimer !== null) {
				clearTimeout(this._liveTailPressTimer);
				this._liveTailPressTimer = null;
			}
			this._liveTailLongPressed = false;
		},

		startLiveTail() {
			if (this.liveTailActive) return;
			this.liveTailActive = true;
			// Start ticking immediately if the tab is visible; otherwise the
			// visibilitychange handler picks it up when the user returns.
			if (document.visibilityState !== 'hidden') {
				this._startLiveTailInterval(true);
			}
		},

		stopLiveTail() {
			this._stopLiveTailInterval();
			this.liveTailActive = false;
		},

		_startLiveTailInterval(runImmediately: boolean) {
			this._stopLiveTailInterval();
			if (runImmediately) {
				void this._tickLiveTail();
			}
			this._liveTailTimer = window.setInterval(() => {
				void this._tickLiveTail();
			}, this.liveTailIntervalMs);
		},

		_stopLiveTailInterval() {
			if (this._liveTailTimer !== null) {
				clearInterval(this._liveTailTimer);
				this._liveTailTimer = null;
			}
		},

		async _tickLiveTail() {
			// Skip the tick if the previous refresh hasn't finished — avoids
			// piling up overlapping requests when the network or server is slow.
			if (this._liveTailRefreshing) return;
			this._liveTailRefreshing = true;
			try {
				await this.refresh();
			} finally {
				this._liveTailRefreshing = false;
			}
		},

		// =====================================================================
		// Window controls
		// =====================================================================

		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},

		// =====================================================================
		// Pane toggles
		// =====================================================================

		toggleSidebarPanel() {
			this.sidebarPanelVisible = !this.sidebarPanelVisible;
		},
		toggleBottomPanel() {
			this.bottomPanelVisible = !this.bottomPanelVisible;
		},
		toggleDetailPanel() {
			this.detailPanelVisible = !this.detailPanelVisible;
		},

		// =====================================================================
		// Process definitions
		// =====================================================================

		async loadDefinitions() {
			if (!this.bpm) return;
			try {
				this.isLoading = true;
				this.errorMessage = '';
				const conn = await this.bpm.listProcessDefinitions({ first: 5000, latestVersion: false });
				this.processDefs = conn.edges.map((e: { node: ProcessDefinition }) => e.node);
				this.buildGroups();
				this.filterDefs();
				// Awaited so callers (e.g. delete/migrate) don't return before the
				// incident counts reflect the new definition set. Fire-and-forget
				// here let rapid one-by-one deletes resolve their listIncidents
				// responses out of order, leaving a stale snapshot — and thus a
				// lingering group incident badge — as the last writer.
				await this.loadGlobalIncidentCounts();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.loadDefinitions', { error: err instanceof Error ? err.message : String(err) }, 'Failed to load process definitions: {error}');
			} finally {
				this.isLoading = false;
			}
		},

		async loadGlobalIncidentCounts() {
			if (!this.bpm) return;
			try {
				const conn = await this.bpm.listIncidents({ first: 5000 });
				const counts: Record<string, number> = {};
				for (const edge of conn.edges) {
					const defId = edge.node.processDefinitionId;
					counts[defId] = (counts[defId] || 0) + 1;
				}
				this.globalIncidentCounts = counts;
			} catch {
				// Non-critical — badges simply remain at last known counts.
			}
		},

		buildGroups() {
			// Preserve expanded state from existing groups
			const expandedKeys = new Set(
				this.processGroups
					.filter((g: ProcessGroup) => g.expanded)
					.map((g: ProcessGroup) => g.key)
			);

			const groupMap = new Map<string, ProcessGroup>();
			for (const def of this.processDefs) {
				let group = groupMap.get(def.key);
				if (!group) {
					group = {
						key: def.key,
						name: def.name || def.key,
						versions: [],
						expanded: expandedKeys.has(def.key),
						latestVersion: def.version,
					};
					groupMap.set(def.key, group);
				}
				group.versions.push(def);
				if (def.version > group.latestVersion) {
					group.latestVersion = def.version;
					group.name = def.name || def.key;
				}
			}

			// Sort versions descending within each group
			for (const group of groupMap.values()) {
				group.versions.sort((a: ProcessDefinition, b: ProcessDefinition) => b.version - a.version);
			}

			// Sort groups by name
			this.processGroups = Array.from(groupMap.values())
				.sort((a: ProcessGroup, b: ProcessGroup) => a.name.localeCompare(b.name));
		},

		filterDefs() {
			if (!this.defSearch) {
				this.filteredGroups = this.processGroups;
				return;
			}
			const q = this.defSearch.toLowerCase();
			this.filteredGroups = this.processGroups.filter((g: ProcessGroup) =>
				g.key.toLowerCase().includes(q) || g.name.toLowerCase().includes(q)
			);
		},

		clearDefSearch() {
			this.defSearch = '';
			this.filterDefs();
		},

		toggleGroupExpand(group: ProcessGroup) {
			group.expanded = !group.expanded;
			if (group.expanded) {
				this.loadDefInstanceCounts(group);
			}
		},

		// Drill-down entry point. A caller (e.g. the Dashboard) may open the
		// console pre-focused. Supported options:
		//   processDefinitionKey  string  — select that process group
		//   view                  'instances' | 'incidents' | 'tasks' — bottom tab
		// All options are optional; unknown keys are ignored.
		async applyLaunchOptions(options?: Record<string, any>) {
			if (!options) return;

			const key = typeof options.processDefinitionKey === 'string' ? options.processDefinitionKey : '';
			if (key) {
				const group = (this.processGroups as ProcessGroup[]).find((g) => g.key === key);
				if (group) {
					group.expanded = true;
					await this.selectGroup(group);
				}
			}

			const view = options.view;
			if (view === 'incidents' || view === 'instances' || view === 'tasks') {
				this.bottomPanelVisible = true;
				this.switchBottomTab(view);
				if (view === 'incidents') {
					await this.loadIncidents();
				}
			}
		},

		async selectGroup(group: ProcessGroup) {
			this.selectedGroup = group;
			this.selectedDef = null;
			this.selectedInstance = null;
			this.selectedTask = null;
			this.activityHistory = [];
			this.diagramFilterElementId = '';
			this.diagramFilterInstanceIds = {};
			this.activeDetailType = 'none';

			// Load latest version's BPMN for the diagram preview
			const latest = group.versions[0];
			if (latest) {
				await Promise.all([
					this.loadBpmnDiagram(latest.id),
					this.loadInstancesForGroup(group),
					this.loadDefInstanceCounts(group),
				]);
			}
		},

		// =====================================================================
		// Definition context menu & actions
		// =====================================================================

		showDefContextMenu(event: MouseEvent, def: ProcessDefinition) {
			// Store the target definition for action handling
			this.defContextMenu.def = def;

			// Build menu items
			const menuItems: { id: string; label: string; icon?: string; danger?: boolean; type?: string }[] = [];

			// Start Instance is only valid for active definitions
			if (!def.suspended) {
				menuItems.push({ id: 'start-instance', label: this.t('app.bpm-console.action.startInstance', undefined, 'Start Instance'), icon: 'bi-play-fill' });
				menuItems.push({ type: 'separator', id: '', label: '' });
				menuItems.push({ id: 'suspend-def', label: this.t('app.bpm-console.action.suspend', undefined, 'Suspend'), icon: 'bi-pause-fill' });
			} else {
				menuItems.push({ id: 'activate-def', label: this.t('app.bpm-console.action.activate', undefined, 'Activate'), icon: 'bi-play' });
			}

			// Migration is meaningful only when another version of the same key
			// exists to migrate towards.
			const sameKeyOthers = this.processDefs.filter(
				(d: ProcessDefinition) => d.key === def.key && d.id !== def.id,
			);
			if (sameKeyOthers.length > 0) {
				menuItems.push({ type: 'separator', id: '', label: '' });
				menuItems.push({ id: 'migrate-instances', label: this.t('app.bpm-console.action.migrateInstances', undefined, 'Migrate Instances…'), icon: 'bi-arrow-right-circle' });
			}

			menuItems.push({ type: 'separator', id: '', label: '' });
			menuItems.push({ id: 'delete-deployment', label: this.t('app.bpm-console.action.deleteDeployment', undefined, 'Delete Deployment'), icon: 'bi-trash', danger: true });

			// Request context menu from parent window
			window.parent.postMessage({
				type: 'show-context-menu',
				x: event.clientX,
				y: event.clientY,
				items: menuItems,
				sourceAppId: this.instance?.id,
			}, window.location.origin);
		},

		handleContextMenuAction(action: string) {
			const def = this.defContextMenu.def;
			if (!def) return;

			switch (action) {
				case 'start-instance':
					this.selectedDef = def;
					this.dialog = { type: 'startProcess', data: { businessKey: '', variables: [] } };
					break;
				case 'suspend-def':
					this.suspendDef(def);
					break;
				case 'activate-def':
					this.activateDef(def);
					break;
				case 'migrate-instances':
					this.openMigrateDialog(def);
					break;
				case 'delete-deployment':
					this.dialog = {
						type: 'deleteDeployment',
						data: {
							deploymentId: def.deploymentId,
							defName: def.name || def.key,
							defVersion: def.version,
							cascade: false,
						},
					};
					break;
			}
		},

		async suspendDef(def: ProcessDefinition) {
			if (!this.bpm) return;
			try {
				this.errorMessage = '';
				await this.bpm.suspendProcessDefinition(def.id);
				await this.loadDefinitions();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.suspend', { error: err instanceof Error ? err.message : String(err) }, 'Failed to suspend: {error}');
			}
		},

		async activateDef(def: ProcessDefinition) {
			if (!this.bpm) return;
			try {
				this.errorMessage = '';
				await this.bpm.activateProcessDefinition(def.id);
				await this.loadDefinitions();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.activate', { error: err instanceof Error ? err.message : String(err) }, 'Failed to activate: {error}');
			}
		},

		async executeDeleteDeployment() {
			if (!this.bpm) return;
			const deploymentId = this.dialog.data.deploymentId as string;
			const cascade = this.dialog.data.cascade as boolean;
			this.closeDialog();
			try {
				this.errorMessage = '';
				this.isLoading = true;
				await this.bpm.deleteDeployment(deploymentId, cascade);
				// Clear selection if the deleted definition was selected
				if (this.selectedDef?.deploymentId === deploymentId) {
					this.selectedDef = null;
					this.bpmnXml = '';
					this.bpmnModel = null;
					this.instances = [];
					this.tasks = [];
					this.activeDetailType = 'none';
				}
				await this.loadDefinitions();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.deleteDeployment', { error: err instanceof Error ? err.message : String(err) }, 'Failed to delete deployment: {error}');
			} finally {
				this.isLoading = false;
			}
		},

		// =====================================================================
		// Migration (createMigrationPlan + migrateProcessInstance)
		//
		// Flow: the operator picks a target version (defaults to the latest
		// other version of the same key) and confirms. We surface the
		// engine's plan instructions as a preview so they can spot any
		// dropped activities before submitting the async batch.
		// =====================================================================

		openMigrateDialog(def: ProcessDefinition) {
			const candidates = this.processDefs
				.filter((d: ProcessDefinition) => d.key === def.key && d.id !== def.id)
				.sort((a: ProcessDefinition, b: ProcessDefinition) => b.version - a.version);
			if (candidates.length === 0) return;
			const defaultTarget = candidates[0];
			const activeCount = this.defInstanceCounts[def.id]?.active ?? 0;
			this.dialog = {
				type: 'migrate',
				data: {
					source: def,
					targetCandidates: candidates,
					targetId: defaultTarget.id,
					activeCount,
					plan: null,
					planLoading: false,
					planError: '',
					mapEqualActivities: true,
					updateEventTriggers: false,
					skipCustomListeners: false,
					skipIoMappings: false,
					submitting: false,
					batch: null,
				},
			};
			// Prime the plan preview without blocking the dialog open.
			this.loadMigrationPlan();
		},

		async openTargetVersionPopup(event: MouseEvent) {
			if (!this.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const candidates = (this.dialog.data.targetCandidates || []) as ProcessDefinition[];
			const currentId = this.dialog.data.targetId as string;
			const items = candidates.map((c: ProcessDefinition) => ({
				id: c.id,
				label: c.suspended
					? this.t('app.bpm-console.dialog.targetVersionSuspended', { version: c.version }, 'v{version} (suspended)')
					: this.t('app.bpm-console.dialog.targetVersionLabel', { version: c.version }, 'v{version}'),
				selected: c.id === currentId,
			}));
			const handle = this.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			this.dialog.data.targetId = String(result);
			this.dialog.data.plan = null;
			this.loadMigrationPlan();
		},

		async loadMigrationPlan() {
			if (!this.bpm) return;
			const source = this.dialog.data.source as ProcessDefinition | null;
			const targetId = this.dialog.data.targetId as string;
			if (!source || !targetId) return;
			this.dialog.data.planLoading = true;
			this.dialog.data.planError = '';
			try {
				const plan = await this.bpm.createMigrationPlan({
					sourceProcessDefinitionId: source.id,
					targetProcessDefinitionId: targetId,
					mapEqualActivities: this.dialog.data.mapEqualActivities as boolean,
					updateEventTriggers: this.dialog.data.updateEventTriggers as boolean,
				});
				this.dialog.data.plan = plan;
			} catch (err) {
				this.dialog.data.planError = err instanceof Error ? err.message : String(err);
			} finally {
				this.dialog.data.planLoading = false;
			}
		},

		selectedTargetDef(): ProcessDefinition | null {
			const candidates = (this.dialog.data.targetCandidates || []) as ProcessDefinition[];
			const targetId = this.dialog.data.targetId as string;
			return candidates.find((c: ProcessDefinition) => c.id === targetId) || null;
		},

		async executeMigrate() {
			if (!this.bpm) return;
			const source = this.dialog.data.source as ProcessDefinition | null;
			const targetId = this.dialog.data.targetId as string;
			if (!source || !targetId) return;
			const target = this.selectedTargetDef();
			this.dialog.data.submitting = true;
			try {
				this.errorMessage = '';
				const job = await this.bpm.migrateProcessInstance({
					sourceProcessDefinitionId: source.id,
					targetProcessDefinitionId: targetId,
					allActiveInstances: true,
					mapEqualActivities: this.dialog.data.mapEqualActivities as boolean,
					updateEventTriggers: this.dialog.data.updateEventTriggers as boolean,
					skipCustomListeners: this.dialog.data.skipCustomListeners as boolean,
					skipIoMappings: this.dialog.data.skipIoMappings as boolean,
				});
				this.closeDialog();
				this.startMigrationMonitor(job, source, target);
				// Refresh definition/instance counts — they update once the
				// engine's batch jobs run. The badges may still lag a moment;
				// finishMigrationMonitor refreshes again on completion.
				await this.loadDefinitions();
				await this.refreshGroupCounts(source.key);
				if (this.selectedGroup && this.selectedGroup.key !== source.key) {
					await this.loadDefInstanceCounts(this.selectedGroup);
				}
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.submitMigration', { error: err instanceof Error ? err.message : String(err) }, 'Failed to submit migration: {error}');
				this.dialog.data.planError = err instanceof Error ? err.message : String(err);
			} finally {
				this.dialog.data.submitting = false;
			}
		},

		// =====================================================================
		// Migration Progress Monitor
		//
		// migrateProcessInstance returns a CMS job id; the JobManager worker
		// seeds the Camunda batch and republishes its statistics through the
		// `jobProgress(jobId)` GraphQL subscription, so the overlay drives off
		// that subscription rather than the Camunda batch id directly.  The
		// server also tells us whether the job is abortable — Camunda 7
		// migrations are not, so the Abort button stays hidden in that case.
		// =====================================================================

		startMigrationMonitor(
			job: MigrationJob,
			source: ProcessDefinition,
			target: ProcessDefinition | null,
		) {
			const vm = this;
			vm.closeMigrationMonitor();

			const sourceLabel = `${source.name || source.key} v${source.version}`;
			const targetLabel = target
				? `${target.name || target.key} v${target.version}`
				: '';

			vm.migrationMonitor = {
				jobId: job.id,
				abortable: job.abortable,
				sourceKey: source.key,
				sourceLabel,
				targetLabel,
				jobsTotal: 0,
				jobsCompleted: 0,
				status: (job.status as JobStatus) ?? 'queued',
				errorMessage: '',
				isFinished: false,
				isUnreachable: false,
				unsubscribe: null,
				timeoutHandle: null,
				autoCloseHandle: null,
			};

			const eventHub = vm.instance?.api?.eventHub;
			if (eventHub) {
				const unsub = eventHub.watchJobProgress(
					job.id,
					(event: JobProgressEvent) => vm.handleMigrationProgress(event),
				);
				vm.migrationMonitor.unsubscribe = unsub;
			}

			// If the backend never publishes a progress event (e.g. the
			// migrate-batch progress subscription isn't wired up yet), flip
			// the monitor to "running in background" after a grace period so
			// the user is never trapped without a Close button.
			const PROGRESS_TIMEOUT_MS = 60_000;
			vm.migrationMonitor.timeoutHandle = window.setTimeout(() => {
				const m = vm.migrationMonitor;
				if (!m || m.isFinished) return;
				if (m.jobsCompleted > 0) return;
				m.isUnreachable = true;
				// No progress events arrived (the batch may have completed
				// silently). Refresh the tree counts as a best-effort fallback so
				// the left pane still reflects the migration outcome.
				vm.loadDefinitions().then(() => vm.refreshGroupCounts(m.sourceKey));
			}, PROGRESS_TIMEOUT_MS);
		},

		handleMigrationProgress(event: JobProgressEvent) {
			const vm = this;
			const m = vm.migrationMonitor;
			if (!m || m.jobId !== event.jobId) return;

			// Server emitted something — cancel the unreachable timeout.
			if (m.timeoutHandle != null) {
				window.clearTimeout(m.timeoutHandle);
				m.timeoutHandle = null;
			}
			m.isUnreachable = false;

			m.status = event.status;
			if (typeof event.itemsTotal === 'number' && event.itemsTotal > 0) {
				m.jobsTotal = event.itemsTotal;
			}
			if (typeof event.itemsProcessed === 'number') {
				m.jobsCompleted = event.itemsProcessed;
			}
			if (event.errorMessage) {
				m.errorMessage = event.errorMessage;
			}

			if (event.status === 'completed' || event.status === 'aborted' || event.status === 'failed') {
				vm.finishMigrationMonitor();
			}
		},

		finishMigrationMonitor() {
			const vm = this;
			const m = vm.migrationMonitor;
			if (!m) return;
			m.isFinished = true;
			if (m.unsubscribe) {
				try { m.unsubscribe(); } catch { /* noop */ }
				m.unsubscribe = null;
			}
			if (m.timeoutHandle != null) {
				window.clearTimeout(m.timeoutHandle);
				m.timeoutHandle = null;
			}

			// Refresh the definition tree so the badges reflect the migrated
			// instances. Awaiting isn't necessary; the UI updates asynchronously.
			// Refresh the affected (source) group's counts explicitly — it may
			// differ from selectedGroup, or there may be no selection at all.
			const sourceKey = m.sourceKey;
			vm.loadDefinitions().then(() => {
				vm.refreshGroupCounts(sourceKey);
				if (vm.selectedGroup && vm.selectedGroup.key !== sourceKey) {
					vm.loadDefInstanceCounts(vm.selectedGroup);
				}
			});

			// Auto-close on success so the overlay doesn't linger. Failures
			// stay open so the user can read the error and copy it.
			if (m.status === 'completed' || m.status === 'aborted') {
				const closingJobId = m.jobId;
				m.autoCloseHandle = window.setTimeout(() => {
					if (vm.migrationMonitor && vm.migrationMonitor.jobId === closingJobId) {
						vm.closeMigrationMonitor();
					}
				}, 1500);
			}
		},

		closeMigrationMonitor() {
			const vm = this;
			const m = vm.migrationMonitor;
			if (!m) return;
			if (m.unsubscribe) {
				try { m.unsubscribe(); } catch { /* noop */ }
			}
			if (m.timeoutHandle != null) {
				window.clearTimeout(m.timeoutHandle);
			}
			if (m.autoCloseHandle != null) {
				window.clearTimeout(m.autoCloseHandle);
			}
			vm.migrationMonitor = null;
		},

		// Placeholder for the Abort button on the migration overlay.  The
		// button is only rendered when the server's `abortable` flag is
		// `true`; Camunda 7 batches report `false` (their public API has no
		// safe cancel path) so for current job types this method is
		// effectively unreachable.  It exists so the overlay can be reused
		// when a future job type opts in to abort — at that point this stub
		// should call an abort mutation against `migrationMonitor.jobId`.
		abortMigration() {
			const m = this.migrationMonitor;
			if (!m || !m.abortable) return;
			console.warn('abortMigration invoked but no abort mutation is wired for job', m.jobId);
		},

		async selectDefinition(def: ProcessDefinition) {
			this.selectedGroup = null;
			this.selectedDef = def;
			this.bpmnXml = '';
			this.bpmnModel = null;
			this.activeDetailType = 'definition';
			this.selectedInstance = null;
			this.selectedTask = null;
			this.activityHistory = [];
			this.diagramFilterElementId = '';
			this.diagramFilterInstanceIds = {};
			await Promise.all([
				this.loadBpmnDiagram(def.id),
				this.loadInstancesForDefinition(def),
			]);
		},

		async loadBpmnDiagram(definitionId: string) {
			if (!this.bpm) return;
			try {
				this.isLoading = true;
				const xml = await this.bpm.getProcessDefinitionXml(definitionId);
				this.bpmnXml = xml || '';
				if (this.bpmnXml) {
					this.bpmnModel = parseBpmnXml(this.bpmnXml);
					// Wait for DOM to render before fitting
					this.$nextTick(() => {
						this.zoomFit();
					});
				}
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.loadBpmn', { error: err instanceof Error ? err.message : String(err) }, 'Failed to load BPMN: {error}');
			} finally {
				this.isLoading = false;
			}
		},

		switchToXmlView() {
			this.viewMode = 'xml';
		},

		// =====================================================================
		// BPMN Diagram viewer helpers
		// =====================================================================

		isTaskType(type: string): boolean {
			return ['userTask', 'serviceTask', 'scriptTask', 'manualTask', 'sendTask', 'receiveTask', 'businessRuleTask', 'task'].includes(type);
		},

		isClickableElement(type: string): boolean {
			return this.isTaskType(type) || ['userTask', 'startEvent', 'endEvent', 'intermediateEvent', 'boundaryEvent', 'exclusiveGateway', 'parallelGateway', 'inclusiveGateway', 'eventBasedGateway', 'complexGateway', 'subProcess', 'callActivity'].includes(type);
		},

		async onDiagramElementClick(el: { id: string; type: string }) {
			// Toggle: click same element clears filter
			if (this.diagramFilterElementId === el.id) {
				this.diagramFilterElementId = '';
				return;
			}

			this.diagramFilterElementId = el.id;

			// Switch to the appropriate tab
			if (['userTask'].includes(el.type)) {
				this.bottomTab = 'tasks';
			} else {
				this.bottomTab = 'instances';
			}

			// Find instances currently at this activity by checking each instance's history
			if (this.bpm && this.instances.length > 0) {
				const matchingInstanceIds: Record<string, boolean> = {};
				// Check active (non-ended) instances for current activity
				const activeInstances = this.instances.filter((inst: ProcessInstance) => !inst.ended);
				const historyPromises = activeInstances.map(async (inst: ProcessInstance) => {
					try {
						const history = await this.bpm!.getActivityHistory(inst.id);
						// Check if any activity at this element is currently active (not ended)
						const hasActive = history.some((a: { activityId: string; endTime?: string | null }) =>
							a.activityId === el.id && !a.endTime
						);
						if (hasActive) {
							matchingInstanceIds[inst.id] = true;
						}
					} catch {
						// Skip
					}
				});
				await Promise.all(historyPromises);
				this.diagramFilterInstanceIds = matchingInstanceIds;
			}
		},

		clearDiagramFilter() {
			this.diagramFilterElementId = '';
			this.diagramFilterInstanceIds = {};
		},

		getViewerConnectionPath(conn: { sourceRef: string; targetRef: string; waypoints?: { x: number; y: number }[]; connectionType?: string }): string {
			if (!this.bpmnModel) return '';
			return getConnectionPath(conn as any, this.bpmnModel.elements);
		},

		getViewerConnectionLabelPos(conn: { sourceRef: string; targetRef: string; labelOffsetX?: number; labelOffsetY?: number; labelWidth?: number; waypoints?: { x: number; y: number }[] }): { x: number; y: number } {
			if (!this.bpmnModel) return { x: 0, y: 0 };
			return getConnectionLabelPosition(conn as any, this.bpmnModel.elements);
		},

		/**
		 * Position for a gateway label, mirroring the BPMN Modeler:
		 * a top-left diagonal corner that does not collide with the
		 * cardinal connection points of the diamond.
		 */
		getGatewayLabelPosition(el: { labelOffsetX?: number; labelOffsetY?: number }): { x: number; y: number } {
			const defaultX = -8;
			const defaultY = -8;
			const x = el.labelOffsetX !== undefined ? el.labelOffsetX : defaultX;
			const y = el.labelOffsetY !== undefined ? el.labelOffsetY : defaultY;
			return { x, y };
		},

		zoomIn() {
			const container = this.$refs.bpmDiagramContainer as HTMLElement | undefined;
			if (!container) return;
			const rect = container.getBoundingClientRect();
			const cx = rect.width / 2;
			const cy = rect.height / 2;
			const newZoom = Math.min(this.diagramZoom * 1.25, 5);
			this.diagramPanX = cx - (cx - this.diagramPanX) * (newZoom / this.diagramZoom);
			this.diagramPanY = cy - (cy - this.diagramPanY) * (newZoom / this.diagramZoom);
			this.diagramZoom = newZoom;
		},

		zoomOut() {
			const container = this.$refs.bpmDiagramContainer as HTMLElement | undefined;
			if (!container) return;
			const rect = container.getBoundingClientRect();
			const cx = rect.width / 2;
			const cy = rect.height / 2;
			const newZoom = Math.max(this.diagramZoom / 1.25, 0.1);
			this.diagramPanX = cx - (cx - this.diagramPanX) * (newZoom / this.diagramZoom);
			this.diagramPanY = cy - (cy - this.diagramPanY) * (newZoom / this.diagramZoom);
			this.diagramZoom = newZoom;
		},

		zoomFit() {
			if (!this.bpmnModel) return;
			const container = this.$refs.bpmDiagramContainer as HTMLElement | undefined;
			if (!container) return;
			const cw = container.clientWidth;
			const ch = container.clientHeight;
			if (cw === 0 || ch === 0) return;

			const vb = calculateViewBox(this.bpmnModel, cw, ch, 40);
			// Calculate zoom to fit
			const scaleX = cw / vb.width;
			const scaleY = ch / vb.height;
			const zoom = Math.min(scaleX, scaleY, 1.5);

			// Center the diagram
			const diagramCenterX = vb.x + vb.width / 2;
			const diagramCenterY = vb.y + vb.height / 2;

			this.diagramZoom = zoom;
			this.diagramPanX = cw / 2 - diagramCenterX * zoom;
			this.diagramPanY = ch / 2 - diagramCenterY * zoom;
		},

		onDiagramWheel(event: WheelEvent) {
			event.preventDefault();
			const delta = event.deltaY > 0 ? 0.9 : 1.1;
			const newZoom = Math.max(0.1, Math.min(5, this.diagramZoom * delta));

			// Zoom toward mouse position
			const container = event.currentTarget as HTMLElement;
			const rect = container.getBoundingClientRect();
			const mouseX = event.clientX - rect.left;
			const mouseY = event.clientY - rect.top;

			this.diagramPanX = mouseX - (mouseX - this.diagramPanX) * (newZoom / this.diagramZoom);
			this.diagramPanY = mouseY - (mouseY - this.diagramPanY) * (newZoom / this.diagramZoom);
			this.diagramZoom = newZoom;
		},

		onDiagramPanStart(event: MouseEvent) {
			// SPACE+click or middle mouse button or Alt+click for panning
			if (event.button === 1 || (event.button === 0 && (this.spacePressed || event.altKey))) {
				this.diagramPanning = true;
				this.diagramPanStartX = event.clientX;
				this.diagramPanStartY = event.clientY;
				this.diagramPanStartPanX = this.diagramPanX;
				this.diagramPanStartPanY = this.diagramPanY;
				event.preventDefault();
			}
		},

		onDiagramPanMove(event: MouseEvent) {
			if (!this.diagramPanning) return;
			this.diagramPanX = this.diagramPanStartPanX + (event.clientX - this.diagramPanStartX);
			this.diagramPanY = this.diagramPanStartPanY + (event.clientY - this.diagramPanStartY);
		},

		onDiagramPanEnd() {
			this.diagramPanning = false;
		},

		async loadInstancesForGroup(group: ProcessGroup) {
			if (!this.bpm) return;
			try {
				this.isLoading = true;
				const [instConn, taskConn] = await Promise.all([
					this.bpm.listProcessInstances({
						first: 200,
						definitionKey: group.key,
					}),
					this.bpm.listTasks({
						first: 200,
						processDefinitionKey: group.key,
					}),
				]);
				this.instances = instConn.edges.map((e: { node: ProcessInstance }) => e.node);
				this.tasks = taskConn.edges.map((e: { node: Task }) => e.node);
				await this.loadIncidents();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.loadInstances', { error: err instanceof Error ? err.message : String(err) }, 'Failed to load instances: {error}');
			} finally {
				this.isLoading = false;
			}
		},

		async loadInstancesForDefinition(def: ProcessDefinition) {
			if (!this.bpm) return;
			try {
				this.isLoading = true;
				const [instConn, taskConn] = await Promise.all([
					this.bpm.listProcessInstances({
						first: 200,
						definitionId: def.id,
					}),
					this.bpm.listTasks({
						first: 200,
						processDefinitionId: def.id,
					}),
				]);
				this.instances = instConn.edges.map((e: { node: ProcessInstance }) => e.node);
				this.tasks = taskConn.edges.map((e: { node: Task }) => e.node);
				await this.loadIncidents();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.loadInstances', { error: err instanceof Error ? err.message : String(err) }, 'Failed to load instances: {error}');
			} finally {
				this.isLoading = false;
			}
		},

		async loadDefInstanceCounts(group: ProcessGroup) {
			if (!this.bpm) return;
			try {
				// Fetch all instances for the group key to compute per-version counts
				const conn = await this.bpm.listProcessInstances({
					first: 5000,
					definitionKey: group.key,
				});
				const allInstances = conn.edges.map((e: { node: ProcessInstance }) => e.node);
				// Seed every version of this group at zero so versions whose
				// instances have disappeared since the last fetch get cleared
				// instead of retaining stale counts via the merge below.
				const counts: Record<string, { active: number; ended: number }> = {};
				for (const def of group.versions) {
					counts[def.id] = { active: 0, ended: 0 };
				}
				for (const inst of allInstances) {
					const defId = inst.definitionId;
					if (!counts[defId]) {
						counts[defId] = { active: 0, ended: 0 };
					}
					if (inst.ended) {
						counts[defId].ended++;
					} else {
						counts[defId].active++;
					}
				}
				// Merge into existing counts (other groups' entries preserved)
				this.defInstanceCounts = { ...this.defInstanceCounts, ...counts };
			} catch {
				// Non-critical
			}
		},

		// Refresh the instance-count badges for the group identified by its
		// process-definition key, resolving it against the freshly rebuilt tree.
		// Used after migrations, which run as background batch jobs and are
		// launched from the context menu without changing the current selection,
		// so the affected group is not necessarily `selectedGroup`.
		async refreshGroupCounts(key: string) {
			const group = this.groups.find((g: ProcessGroup) => g.key === key);
			if (group) await this.loadDefInstanceCounts(group);
		},

		async reloadInstances() {
			if (this.selectedGroup) {
				await this.loadInstancesForGroup(this.selectedGroup);
			} else if (this.selectedDef) {
				await this.loadInstancesForDefinition(this.selectedDef);
			}
		},

		// =====================================================================
		// Process instances
		// =====================================================================

		async selectInstance(inst: ProcessInstance) {
			this.selectedInstance = inst;
			this.activeDetailType = 'instance';
			this.selectedTask = null;
			this.activityHistory = [];

			// Resolve the definition linked to this instance
			this.instanceDef = this.processDefs.find(
				(d: ProcessDefinition) => d.id === inst.definitionId
			) || null;

			if (this.bpm) {
				try {
					const [full, history] = await Promise.all([
						this.bpm.getProcessInstance(inst.id),
						this.bpm.getActivityHistory(inst.id),
					]);
					if (full) {
						this.selectedInstance = full;
						this.instanceVariables = full.variables || [];
						// Re-resolve definition in case definitionId was updated
						if (full.definitionId && full.definitionId !== inst.definitionId) {
							this.instanceDef = this.processDefs.find(
								(d: ProcessDefinition) => d.id === full.definitionId
							) || null;
						}
					}
					this.activityHistory = history || [];
				} catch (err) {
					this.instanceVariables = [];
					this.activityHistory = [];
				}
			}
		},

		async suspendInstance(id: string) {
			if (!this.bpm) return;
			try {
				this.errorMessage = '';
				await this.bpm.suspendProcessInstance(id);
				await this.reloadInstances();
				if (this.selectedInstance?.id === id) {
					await this.selectInstance(this.selectedInstance);
				}
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.suspend', { error: err instanceof Error ? err.message : String(err) }, 'Failed to suspend: {error}');
			}
		},

		async activateInstance(id: string) {
			if (!this.bpm) return;
			try {
				this.errorMessage = '';
				await this.bpm.activateProcessInstance(id);
				await this.reloadInstances();
				if (this.selectedInstance?.id === id) {
					await this.selectInstance(this.selectedInstance);
				}
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.activate', { error: err instanceof Error ? err.message : String(err) }, 'Failed to activate: {error}');
			}
		},

		confirmCancelInstance(inst: ProcessInstance) {
			this.dialog = {
				type: 'cancelInstance',
				data: { id: inst.id, reason: '' },
			};
		},

		async executeCancelInstance() {
			if (!this.bpm) return;
			const id = this.dialog.data.id as string;
			const reason = this.dialog.data.reason as string;
			this.closeDialog();
			try {
				this.errorMessage = '';
				await this.bpm.cancelProcessInstance(id, reason || undefined);
				// Clear any right-pane selection tied to the cancelled instance —
				// whether the user reached it via the instance list or via a user
				// task that belongs to it.
				if (this.selectedInstance?.id === id) {
					this.selectedInstance = null;
				}
				if (this.selectedTask?.processInstanceId === id) {
					this.selectedTask = null;
				}
				if (!this.selectedInstance && !this.selectedTask) {
					this.activeDetailType = this.selectedDef ? 'definition' : 'none';
				}
				// Reload both the instance and user-task lists. reloadInstances()
				// guards on the current selection (group or definition), so the
				// stale task row is removed even when no specific version is
				// selected (User Tasks viewed at the group level).
				await this.reloadInstances();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.cancel', { error: err instanceof Error ? err.message : String(err) }, 'Failed to cancel: {error}');
			}
		},

		// =====================================================================
		// Start process
		// =====================================================================

		startProcessDialog() {
			this.dialog = {
				type: 'startProcess',
				data: { businessKey: '', variables: [] },
			};
		},

		formatVarValue(v: DialogVariable): string {
			if (v.type === 'Date' && v.value) {
				// The dialog model holds Date variables as an absolute ISO 8601
				// instant; normalise (re-emit via toISOString) before sending.
				const d = new Date(v.value);
				return isNaN(d.getTime()) ? v.value : d.toISOString();
			}
			return v.value;
		},

		// Write a datetime-local wall-clock back to the dialog model. The string
		// is interpreted as a time in the user's preference time zone, and the
		// model stores the absolute instant (ISO 8601). This keeps the input
		// reactive to TZ changes: the displayed wall-clock follows the new zone
		// without re-interpreting (and shifting) the underlying instant.
		setDateVar(v: DialogVariable, localStr: string): void {
			if (!localStr) { v.value = ''; return; }
			const dates = this.instance?.util?.dates;
			const tz = this.localization.timeZone || undefined;
			let iso = '';
			if (dates) {
				const d = dates.fromZonedInputValue(localStr, tz);
				if (d && !isNaN(d.getTime())) iso = d.toISOString();
			} else {
				const d = new Date(localStr);
				if (!isNaN(d.getTime())) iso = d.toISOString();
			}
			v.value = iso || localStr;
		},

		async executeStartProcess() {
			if (!this.bpm || !this.selectedDef) return;
			const businessKey = this.dialog.data.businessKey as string;
			const vars = ((this.dialog.data.variables || []) as DialogVariable[])
				.filter((v: DialogVariable) => v.name.trim() !== '')
				.map((v: DialogVariable) => ({ name: v.name, value: this.formatVarValue(v), type: v.type }));
			this.closeDialog();
			try {
				this.errorMessage = '';
				await this.bpm.startProcess({
					definitionId: this.selectedDef.id,
					businessKey: businessKey || undefined,
					variables: vars.length > 0 ? vars : undefined,
				});
				await this.reloadInstances();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.startProcess', { error: err instanceof Error ? err.message : String(err) }, 'Failed to start process: {error}');
			}
		},

		// =====================================================================
		// Set variables
		// =====================================================================

		normalizeVarType(type: string | undefined): string {
			if (!type) return 'String';
			const lower = type.toLowerCase();
			if (lower === 'string') return 'String';
			if (lower === 'long' || lower === 'integer') return 'Long';
			if (lower === 'double' || lower === 'float') return 'Double';
			if (lower === 'boolean') return 'Boolean';
			if (lower === 'date') return 'Date';
			// Capitalize first letter as fallback
			return type.charAt(0).toUpperCase() + type.slice(1).toLowerCase();
		},

		isEmptyVarValue(value: unknown): boolean {
			return value == null || value === '';
		},

		formatVarDisplayValue(v: ProcessVariable): string {
			if (this.normalizeVarType(v.type) === 'Date') {
				return this.formatDate(v.value as string | null);
			}
			return this.isEmptyVarValue(v.value) ? '—' : String(v.value);
		},

		toDatetimeLocalValue(value: unknown): string {
			if (value == null) return '';
			// Render the datetime-local wall-clock in the user's preference time
			// zone (not the OS zone) so display and input stay consistent.
			const dates = this.instance?.util?.dates;
			if (dates) {
				return dates.toZonedInputValue(value as any, this.localization.timeZone || undefined) || String(value);
			}
			const d = new Date(String(value));
			if (isNaN(d.getTime())) return String(value);
			const pad = (n: number) => String(n).padStart(2, '0');
			return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
		},

		showSetVariablesDialog() {
			if (!this.selectedInstance) return;
			const vars: DialogVariable[] = this.instanceVariables.map((v: ProcessVariable) => {
				const type = this.normalizeVarType(v.type);
				// Date variables are kept as ISO 8601 in the model; the template
				// converts to the datetime-local wall-clock in the current
				// preference time zone via toDatetimeLocalValue(), so switching
				// TZ while the dialog is open re-renders the input correctly.
				const value = v.value != null ? String(v.value) : '';
				return { name: v.name, type, value };
			});
			this.dialog = {
				type: 'setVariables',
				data: { variables: vars },
			};
		},

		addDialogVariable() {
			(this.dialog.data.variables as DialogVariable[]).push({ name: '', type: 'String', value: '' });
		},

		removeDialogVariable(idx: number) {
			(this.dialog.data.variables as DialogVariable[]).splice(idx, 1);
		},

		async executeSetVariables() {
			if (!this.bpm || !this.selectedInstance) return;
			const vars = (this.dialog.data.variables as DialogVariable[])
				.filter((v: DialogVariable) => v.name.trim() !== '')
				.map((v: DialogVariable) => ({ name: v.name, value: this.formatVarValue(v), type: v.type }));
			this.closeDialog();
			try {
				this.errorMessage = '';
				await this.bpm.setProcessVariables(this.selectedInstance.id, vars);
				await this.selectInstance(this.selectedInstance);
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.setVariables', { error: err instanceof Error ? err.message : String(err) }, 'Failed to set variables: {error}');
			}
		},

		// =====================================================================
		// Tasks
		// =====================================================================

		async selectTask(task: Task) {
			this.selectedTask = task;
			this.activeDetailType = 'task';
			this.selectedInstance = null;
			this.instanceVariables = [];
			this.instanceDef = null;
			this.activityHistory = [];

			if (this.bpm && task.processInstanceId) {
				try {
					const [full, history] = await Promise.all([
						this.bpm.getProcessInstance(task.processInstanceId),
						this.bpm.getActivityHistory(task.processInstanceId),
					]);
					if (full) {
						this.selectedInstance = full;
						this.instanceVariables = full.variables || [];
						this.instanceDef = this.processDefs.find(
							(d: ProcessDefinition) => d.id === full.definitionId
						) || null;
					}
					this.activityHistory = history || [];
				} catch {
					this.activityHistory = [];
				}
			}
		},

		async showAssignTaskDialog(taskId: string) {
			if (!this.idp) return;
			try {
				const conn = await this.idp.listUsers({ first: 200 });
				const users = conn.edges.map((e: { node: { username: string; displayName: string | null } }) => e.node);
				this.dialog = {
					type: 'assignTask',
					data: {
						taskId,
						users,
						search: '',
						selectedUser: '',
					},
				};
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.loadUsers', { error: err instanceof Error ? err.message : String(err) }, 'Failed to load users: {error}');
			}
		},

		async executeAssignTask() {
			if (!this.bpm) return;
			const taskId = this.dialog.data.taskId as string;
			const assignee = this.dialog.data.selectedUser as string;
			if (!assignee) return;
			this.closeDialog();
			try {
				this.errorMessage = '';
				await this.bpm.setTaskAssignee(taskId, assignee);
				await this.reloadInstances();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.assignTask', { error: err instanceof Error ? err.message : String(err) }, 'Failed to assign task: {error}');
			}
		},

		async unclaimTask(taskId: string) {
			if (!this.bpm) return;
			try {
				this.errorMessage = '';
				await this.bpm.setTaskAssignee(taskId, null);
				await this.reloadInstances();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.unclaimTask', { error: err instanceof Error ? err.message : String(err) }, 'Failed to unclaim task: {error}');
			}
		},

		showCompleteTaskDialog(task: Task) {
			// Pre-populate with current process variables
			const vars: DialogVariable[] = this.instanceVariables.map((v: ProcessVariable) => {
				const type = this.normalizeVarType(v.type);
				// Date variables are kept as ISO 8601 in the model; the template
				// converts to the datetime-local wall-clock in the current
				// preference time zone via toDatetimeLocalValue(), so switching
				// TZ while the dialog is open re-renders the input correctly.
				const value = v.value != null ? String(v.value) : '';
				return { name: v.name, type, value };
			});
			this.dialog = {
				type: 'completeTask',
				data: {
					taskId: task.id,
					taskName: task.name || task.taskDefinitionKey,
					variables: vars,
				},
			};
		},

		async executeCompleteTask() {
			if (!this.bpm) return;
			const taskId = this.dialog.data.taskId as string;
			const vars = ((this.dialog.data.variables || []) as DialogVariable[])
				.filter((v: DialogVariable) => v.name.trim() !== '')
				.map((v: DialogVariable) => ({ name: v.name, value: this.formatVarValue(v), type: v.type }));
			this.closeDialog();
			try {
				this.errorMessage = '';
				await this.bpm.completeTask({ taskId, variables: vars });
				if (this.selectedTask?.id === taskId) {
					this.selectedTask = null;
					this.selectedInstance = null;
					this.instanceVariables = [];
					this.activityHistory = [];
					this.activeDetailType = this.selectedDef ? 'definition' : 'none';
				}
				await this.reloadInstances();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.completeTask', { error: err instanceof Error ? err.message : String(err) }, 'Failed to complete task: {error}');
			}
		},

		// =====================================================================
		// Deploy
		// =====================================================================

		showDeployDialog() {
			this.dialog = {
				type: 'deploy',
				data: { name: '', fileName: '', bpmnXml: '', dropHighlight: false },
			};
		},

		async onDeployDrop(event: DragEvent) {
			this.dialog.data.dropHighlight = false;

			// Check for Content Browser file
			const webtopFileData = event.dataTransfer?.getData('application/x-webtop-file');
			if (webtopFileData) {
				try {
					const fileInfo = JSON.parse(webtopFileData);
					if (fileInfo.path) {
						this.dialog.data.name = fileInfo.path;
						this.dialog.data.fileName = fileInfo.path.split('/').pop() || fileInfo.path;
						// Load file content via content service
						const contentService = this.instance?.api?.content;
						if (contentService) {
							const node = await contentService.getNode(fileInfo.path);
							if (node && node.downloadUrl) {
								const response = await fetch(node.downloadUrl);
								if (response.ok) {
									this.dialog.data.bpmnXml = await response.text();
								}
							}
						}
					}
				} catch (e) {
					this.errorMessage = this.t('app.bpm-console.error.loadFile', { error: e instanceof Error ? e.message : String(e) }, 'Failed to load file: {error}');
				}
				return;
			}

			// Local file drop
			const files = event.dataTransfer?.files;
			if (files && files.length > 0) {
				const file = files[0];
				this.dialog.data.fileName = file.name;
				this.dialog.data.name = file.name;
				this.dialog.data.bpmnXml = await file.text();
			}
		},

		clearDeployFile() {
			this.dialog.data.name = '';
			this.dialog.data.fileName = '';
			this.dialog.data.bpmnXml = '';
		},

		async executeDeploy() {
			if (!this.bpm) return;
			const name = this.dialog.data.name as string;
			const bpmnXml = this.dialog.data.bpmnXml as string;
			if (!name.trim() || !bpmnXml.trim()) {
				this.errorMessage = this.t('app.bpm-console.error.dropBpmnFirst', undefined, 'Please drop a BPMN file first');
				return;
			}
			this.closeDialog();
			try {
				this.errorMessage = '';
				this.isLoading = true;
				await this.bpm.deployBpmn(name, bpmnXml);
				await this.loadDefinitions();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.deploy', { error: err instanceof Error ? err.message : String(err) }, 'Failed to deploy: {error}');
			} finally {
				this.isLoading = false;
			}
		},

		// =====================================================================
		// Bottom panel tab
		// =====================================================================

		switchBottomTab(tab: BottomTab) {
			this.bottomTab = tab;
		},

		// =====================================================================
		// Dialog
		// =====================================================================

		closeDialog() {
			this.dialog = { type: '', data: {} };
		},

		// =====================================================================
		// Splitters
		// =====================================================================

		onSidebarResizeStart(event: MouseEvent) {
			event.preventDefault();
			const startX = event.clientX;
			const startWidth = this.sidebarPanelWidth;

			const onMove = (e: MouseEvent) => {
				const dx = e.clientX - startX;
				this.sidebarPanelWidth = Math.max(180, Math.min(500, startWidth + dx));
			};
			const onUp = () => {
				document.removeEventListener('mousemove', onMove);
				document.removeEventListener('mouseup', onUp);
			};
			document.addEventListener('mousemove', onMove);
			document.addEventListener('mouseup', onUp);
		},

		onBottomResizeStart(event: MouseEvent) {
			event.preventDefault();
			const startY = event.clientY;
			const startHeight = this.bottomPanelHeight;

			const onMove = (e: MouseEvent) => {
				const dy = startY - e.clientY;
				this.bottomPanelHeight = Math.max(80, Math.min(500, startHeight + dy));
			};
			const onUp = () => {
				document.removeEventListener('mousemove', onMove);
				document.removeEventListener('mouseup', onUp);
			};
			document.addEventListener('mousemove', onMove);
			document.addEventListener('mouseup', onUp);
		},

		onDetailResizeStart(event: MouseEvent) {
			event.preventDefault();
			const startX = event.clientX;
			const startWidth = this.detailPanelWidth;

			const onMove = (e: MouseEvent) => {
				const dx = startX - e.clientX;
				this.detailPanelWidth = Math.max(220, Math.min(600, startWidth + dx));
			};
			const onUp = () => {
				document.removeEventListener('mousemove', onMove);
				document.removeEventListener('mouseup', onUp);
			};
			document.addEventListener('mousemove', onMove);
			document.addEventListener('mouseup', onUp);
		},

		// =====================================================================
		// Variable type / boolean popup (replaces native <select>)
		// =====================================================================

		async openVarTypePopup(event: MouseEvent, variable: DialogVariable) {
			if (!this.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const types = ['String', 'Long', 'Double', 'Boolean', 'Date'];
			const items = types.map(t => ({
				id: t,
				label: t,
				selected: variable.type === t,
			}));
			const handle = this.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			const newType = String(result);
			variable.type = newType;
			// Reset value when switching to incompatible types
			if (newType === 'Boolean' && variable.value !== 'true' && variable.value !== 'false') {
				variable.value = 'true';
			} else if (newType === 'Date') {
				variable.value = '';
			}
		},

		async openVarBoolPopup(event: MouseEvent, variable: DialogVariable) {
			if (!this.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const items = [
				{ id: 'true', label: 'true', selected: variable.value === 'true' },
				{ id: 'false', label: 'false', selected: variable.value === 'false' },
			];
			const handle = this.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			variable.value = String(result);
		},

		// =====================================================================
		// Incidents
		// =====================================================================

		/**
		 * Load incidents for the currently selected def/group via the GraphQL
		 * Incident endpoint. Stack traces are intentionally omitted from the
		 * list response and fetched on-demand by selectIncident.
		 */
		async loadIncidents() {
			if (!this.bpm) {
				this.incidents = [];
				return;
			}
			try {
				const filter: { processDefinitionId?: string; processDefinitionKey?: string; first: number } = {
					first: 500,
				};
				if (this.selectedDef) {
					filter.processDefinitionId = this.selectedDef.id;
				} else if (this.selectedGroup) {
					filter.processDefinitionKey = this.selectedGroup.key;
				} else {
					this.incidents = [];
					return;
				}
				const conn = await this.bpm.listIncidents(filter);
				const list = conn.edges.map((e: { node: Incident }) => e.node);
				// Resolve display names from the parsed BPMN model so the table /
				// detail panel can show the human-friendly activity label.
				const nameById: Record<string, string> = {};
				if (this.bpmnModel) {
					for (const el of this.bpmnModel.elements) {
						if (el.id && el.name) nameById[el.id] = el.name;
					}
				}
				for (const inc of list) {
					if (inc.activityId && !inc.activityName) {
						inc.activityName = nameById[inc.activityId] || inc.activityId;
					} else if (inc.activityId && nameById[inc.activityId]) {
						inc.activityName = nameById[inc.activityId];
					}
				}
				// Newest first (defensive — backend ordering is engine-defined)
				list.sort((a: Incident, b: Incident) =>
					new Date(b.incidentTimestamp).getTime() - new Date(a.incidentTimestamp).getTime(),
				);
				this.incidents = list;
				await this.loadGlobalIncidentCounts();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.loadIncidents', { error: err instanceof Error ? err.message : String(err) }, 'Failed to load incidents: {error}');
				this.incidents = [];
			}
		},

		async selectIncident(inc: Incident) {
			this.selectedIncident = inc;
			this.selectedInstance = this.instances.find(
				(i: ProcessInstance) => i.id === inc.processInstanceId,
			) || null;
			this.selectedTask = null;
			this.activeDetailType = 'incident';
			// Reflect on diagram filter so the failing activity gets focused.
			this.diagramFilterElementId = inc.activityId;
			this.diagramFilterInstanceIds = { [inc.processInstanceId]: true };

			// Fetch the full incident (with stack trace) lazily.
			if (this.bpm && !inc.stackTrace) {
				try {
					const full = await this.bpm.getIncident(inc.id, true);
					if (full && this.selectedIncident && this.selectedIncident.id === inc.id) {
						// Preserve the resolved activityName already on the row.
						const activityName = inc.activityName;
						this.selectedIncident = { ...full, activityName: activityName || full.activityName };
					}
				} catch {
					// Best effort — keep showing what we have.
				}
			}
		},

		switchToIncidents(activityId?: string) {
			this.bottomTab = 'incidents';
			if (activityId) {
				this.diagramFilterElementId = activityId;
			}
		},

		jumpToIncidentOnDiagram(inc: Incident) {
			this.viewMode = 'diagram';
			this.diagramFilterElementId = inc.activityId;
			this.diagramFilterInstanceIds = { [inc.processInstanceId]: true };
		},

		toggleIncidentSelection(incidentId: string) {
			const m = this.incidentSelection as Record<string, boolean>;
			if (m[incidentId]) delete m[incidentId]; else m[incidentId] = true;
			this.incidentSelection = { ...m };
		},

		showRetryIncidentDialog(inc: Incident) {
			this.dialog = {
				type: 'retryIncident',
				data: {
					ids: [inc.id],
					summary: `${inc.activityName || inc.activityId}`,
					retries: 1,
					clearAnnotation: false,
				},
			};
		},

		showRetrySelectedDialog() {
			const ids = Object.keys(this.incidentSelection as Record<string, boolean>)
				.filter((k: string) => (this.incidentSelection as Record<string, boolean>)[k]);
			if (ids.length === 0) return;
			const grouped = new Map<string, number>();
			for (const id of ids) {
				const inc = this.incidents.find((i: Incident) => i.id === id);
				if (!inc) continue;
				const k = inc.activityName || inc.activityId;
				grouped.set(k, (grouped.get(k) || 0) + 1);
			}
			const summary = Array.from(grouped.entries())
				.map(([k, v]) => `${k} ×${v}`).join(', ');
			this.dialog = {
				type: 'retryIncident',
				data: {
					ids,
					summary,
					retries: 1,
					clearAnnotation: false,
				},
			};
		},

		async executeRetryIncident() {
			if (!this.bpm) return;
			const ids = (this.dialog.data.ids as string[]) || [];
			const retries = Number(this.dialog.data.retries) || 1;
			const clearAnnotation = this.dialog.data.clearAnnotation === true;
			this.closeDialog();
			try {
				this.errorMessage = '';
				// Camunda's setJobRetries operates per incident/job, so we
				// loop and let exceptions bubble — partial success is fine
				// (the next refresh shows the still-failing ones).
				await Promise.all(
					ids.map((id: string) => this.bpm!.setJobRetries({
						incidentId: id,
						retries,
						clearAnnotation,
					})),
				);
				if (this.selectedIncident && ids.includes(this.selectedIncident.id)) {
					this.selectedIncident = null;
					this.activeDetailType = this.selectedInstance ? 'instance' : 'none';
				}
				this.incidentSelection = {};
				await this.loadIncidents();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.retryIncident', { error: err instanceof Error ? err.message : String(err) }, 'Failed to retry incident: {error}');
			}
		},

		showResolveIncidentDialog(inc: Incident) {
			this.dialog = {
				type: 'resolveIncident',
				data: { id: inc.id, type: inc.type, activity: inc.activityName || inc.activityId },
			};
		},

		async executeResolveIncident() {
			if (!this.bpm) return;
			const id = this.dialog.data.id as string;
			this.closeDialog();
			try {
				this.errorMessage = '';
				await this.bpm.resolveIncident(id);
				if (this.selectedIncident?.id === id) {
					this.selectedIncident = null;
					this.activeDetailType = this.selectedInstance ? 'instance' : 'none';
				}
				await this.loadIncidents();
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.resolveIncident', { error: err instanceof Error ? err.message : String(err) }, 'Failed to resolve incident: {error}');
			}
		},

		showEditAnnotationDialog(inc: Incident) {
			this.dialog = {
				type: 'editIncidentAnnotation',
				data: { id: inc.id, annotation: inc.annotation || '' },
			};
		},

		async executeSetAnnotation() {
			if (!this.bpm) return;
			const id = this.dialog.data.id as string;
			const annotation = (this.dialog.data.annotation as string) || '';
			this.closeDialog();
			try {
				this.errorMessage = '';
				const updated = await this.bpm.setIncidentAnnotation({ id, annotation: annotation || null });
				const target = this.incidents.find((i: Incident) => i.id === id);
				if (target && updated) target.annotation = updated.annotation;
				if (this.selectedIncident?.id === id && updated) {
					this.selectedIncident.annotation = updated.annotation;
				}
			} catch (err) {
				this.errorMessage = this.t('app.bpm-console.error.setAnnotation', { error: err instanceof Error ? err.message : String(err) }, 'Failed to set annotation: {error}');
			}
		},

		async copyToClipboard(text: string, event?: MouseEvent) {
			if (!text) return;
			try {
				await navigator.clipboard.writeText(text);
			} catch {
				const textarea = document.createElement('textarea');
				textarea.value = text;
				document.body.appendChild(textarea);
				textarea.select();
				try { document.execCommand('copy'); } catch { /* best effort */ }
				document.body.removeChild(textarea);
			}
			if (event) {
				const btn = (event.target as HTMLElement).closest('button');
				const icon = btn?.querySelector('i');
				if (btn && icon) {
					const original = icon.className;
					icon.className = 'bi bi-check-lg';
					btn.classList.add('copied');
					setTimeout(() => {
						icon.className = original;
						btn.classList.remove('copied');
					}, 1500);
				}
			}
		},

		async copyStackTrace(event?: MouseEvent) {
			await this.copyToClipboard(this.selectedIncident?.stackTrace || '', event);
		},

		async copyErrorMessage(event?: MouseEvent) {
			await this.copyToClipboard(this.selectedIncident?.message || '', event);
		},

		incidentTypeLabel(type: IncidentType): string {
			if (type === 'failedJob') return this.t('app.bpm-console.incidentType.failedJob', undefined, 'Failed Job');
			if (type === 'failedExternalTask') return this.t('app.bpm-console.incidentType.failedExternalTask', undefined, 'External Task');
			return this.t('app.bpm-console.incidentType.custom', undefined, 'Custom');
		},

		// =====================================================================
		// Utilities
		// =====================================================================

		formatDate(isoString: string | null): string {
			if (!isoString) return '—';
			const dates = this.instance?.util?.dates;
			if (!dates) {
				try { return new Date(isoString).toLocaleString(); } catch { return isoString; }
			}
			return dates.format(isoString, {
				format: 'datetime',
				locale: this.localization.locale || undefined,
				timeZone: this.localization.timeZone || undefined,
			}) ?? isoString;
		},

		formatDateShort(isoString: string | null): string {
			if (!isoString) return '—';
			const dates = this.instance?.util?.dates;
			if (!dates) {
				try {
					const d = new Date(isoString);
					return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
				} catch {
					return isoString;
				}
			}
			const locale = this.localization.locale || undefined;
			const timeZone = this.localization.timeZone || undefined;
			const datePart = dates.format(isoString, { format: 'date', locale, timeZone });
			const timePart = dates.format(isoString, { format: 'time', locale, timeZone });
			if (!datePart) return isoString;
			return timePart ? `${datePart} ${timePart}` : datePart;
		},

		formatDuration(ms: number | null): string {
			if (ms == null) return '—';
			const sec = Math.floor(ms / 1000);
			const min = Math.floor(sec / 60);
			const hr = Math.floor(min / 60);
			const day = Math.floor(hr / 24);
			if (day > 0) return `${day}d ${hr % 24}h`;
			if (hr > 0) return `${hr}h ${min % 60}m`;
			if (min > 0) return `${min}m ${sec % 60}s`;
			return `${sec}s`;
		},

		isOverdue(dueDate: string | null): boolean {
			if (!dueDate) return false;
			return new Date(dueDate) < new Date();
		},
	},

	computed: {
		// Map of BPMN element IDs that were traversed by the selected instance
		highlightedNodeIds(): Record<string, boolean> {
			const map: Record<string, boolean> = {};
			if (!this.activityHistory || this.activityHistory.length === 0) return map;
			for (const a of this.activityHistory) {
				map[(a as { activityId: string }).activityId] = true;
			}
			return map;
		},

		// Map of connection IDs that link consecutive traversed activities
		highlightedConnectionIds(): Record<string, boolean> {
			const map: Record<string, boolean> = {};
			if (!this.bpmnModel || !this.activityHistory || this.activityHistory.length < 2) return map;
			const nodeIds = this.highlightedNodeIds as Record<string, boolean>;
			for (const conn of this.bpmnModel.connections) {
				if (nodeIds[conn.sourceRef] && nodeIds[conn.targetRef]) {
					map[conn.id] = true;
				}
			}
			return map;
		},

		// Map of node IDs currently active (started but not yet ended)
		activeNodeIds(): Record<string, boolean> {
			const map: Record<string, boolean> = {};
			if (!this.activityHistory || this.activityHistory.length === 0) return map;
			for (const a of this.activityHistory) {
				const act = a as { activityId: string; endTime?: string | null };
				if (!act.endTime) {
					map[act.activityId] = true;
				}
			}
			return map;
		},

		localTZ(): string {
			const timeZone = this.localization.timeZone || undefined;
			return new Intl.DateTimeFormat('en', { timeZone, timeZoneName: 'short' })
				.formatToParts(new Date()).find((p: { type: string }) => p.type === 'timeZoneName')?.value || '';
		},

		viewerPools(): BpmnViewModel['elements'] {
			if (!this.bpmnModel) return [];
			return this.bpmnModel.elements.filter((e: { type: string }) => e.type === 'pool');
		},

		viewerLanes(): BpmnViewModel['elements'] {
			if (!this.bpmnModel) return [];
			return this.bpmnModel.elements.filter((e: { type: string }) => e.type === 'lane');
		},

		viewerElements(): BpmnViewModel['elements'] {
			if (!this.bpmnModel) return [];
			return this.bpmnModel.elements.filter((e: { type: string }) => e.type !== 'pool' && e.type !== 'lane');
		},

		// Filtered instances/tasks based on diagram element click
		filteredInstances(): ProcessInstance[] {
			if (!this.diagramFilterElementId) return this.instances;
			const ids = this.diagramFilterInstanceIds as Record<string, boolean>;
			if (Object.keys(ids).length === 0) return [];
			return this.instances.filter((inst: ProcessInstance) => ids[inst.id]);
		},

		filteredTasks(): Task[] {
			if (!this.diagramFilterElementId) return this.tasks;
			return this.tasks.filter((t: Task) => t.taskDefinitionKey === this.diagramFilterElementId);
		},

		filteredIncidents(): Incident[] {
			if (!this.diagramFilterElementId) return this.incidents;
			return this.incidents.filter((i: Incident) => i.activityId === this.diagramFilterElementId);
		},

		// Map of activityId → unresolved incident count (for diagram overlay).
		incidentsByActivityId(): Record<string, number> {
			const map: Record<string, number> = {};
			for (const i of this.incidents) {
				map[i.activityId] = (map[i.activityId] || 0) + 1;
			}
			return map;
		},

		// Map of process definition ID → incident count (per-version badge in tree).
		// Sourced from globalIncidentCounts so badges stay visible regardless of
		// the current selection.
		defIncidentCounts(): Record<string, number> {
			return this.globalIncidentCounts as Record<string, number>;
		},

		// Map of group key → total incident count summed across all versions.
		groupIncidentCounts(): Record<string, number> {
			const map: Record<string, number> = {};
			// Depend directly on the raw reactive map rather than the
			// defIncidentCounts computed, so this subtree total is recomputed
			// whenever the underlying incident counts change — without relying on
			// computed→computed dependency propagation.
			const counts = this.globalIncidentCounts as Record<string, number>;
			for (const group of this.processGroups as ProcessGroup[]) {
				let total = 0;
				for (const def of group.versions) {
					total += counts[def.id] || 0;
				}
				if (total > 0) map[group.key] = total;
			}
			return map;
		},

		// Incidents tied to the currently selected process instance.
		incidentsForSelectedInstance(): Incident[] {
			if (!this.selectedInstance) return [];
			return this.incidents.filter(
				(i: Incident) => i.processInstanceId === this.selectedInstance!.id,
			);
		},

		// Total open incidents across the loaded scope (for global toolbar badge).
		totalIncidentCount(): number {
			return this.incidents.length;
		},

		selectedIncidentCount(): number {
			return Object.keys(this.incidentSelection as Record<string, boolean>)
				.filter((k: string) => (this.incidentSelection as Record<string, boolean>)[k])
				.length;
		},
	},
};

// Mount the app
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');

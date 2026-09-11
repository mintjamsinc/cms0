/**
 * Workspace Manager Application
 *
 * Admin-only management of repository workspaces, modelled on the Identity
 * Manager: a two-pane layout with a filterable workspace list on the left and,
 * on the right, the editor for the selected workspace. From the editor an
 * administrator can:
 *
 *  - rename the workspace's display label (the name itself — the URL segment —
 *    is immutable);
 *  - switch the BPM (process) and EIP (integration) engines on or off — these
 *    are read only when the workspace's services start, so they take effect
 *    after a restart, which the UI states plainly;
 *  - toggle auto-start (whether the workspace runs when the cluster starts
 *    with every node stopped);
 *  - start, stop and restart the workspace; and
 *  - delete it.
 *
 * Webtop cannot choose a cluster node, so every operation acts on the whole
 * cluster: the list shows each workspace's cluster-wide state, and the editor
 * lists the workspace on every node — its state, engines, whether the latest
 * restart and engine settings are applied there, and why it failed — with a
 * retry for the nodes it failed on.
 *
 * Lifecycle actions — create, delete, start, stop, restart — run as background
 * jobs on the server that complete once every alive node has converged (they
 * can take minutes and can fail on any node), so the app shows the same
 * non-cancellable progress overlay the Content Browser uses for long
 * operations, driven by the job's `jobProgress` events: the phase and each
 * node's state while it runs, and on failure a dismissable error naming the
 * nodes instead of a spinner that never stops. The list also live-updates from
 * the `workspaceChanged` subscription so a change made anywhere in the cluster
 * is reflected here without a manual refresh. The system workspace — the
 * identity store — and the workspace the desktop is currently bound to cannot
 * be stopped, restarted or deleted.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { ApplicationInstance } from "../../services/webtop-service.js";
import { initUi } from "../../ui/index.js";
import { createShellPopupAdapter } from "../../ui/shell-popup-adapter.js";
import type { WorkspaceInfo, WorkspaceNodeInfo } from "../../services/webtop-service-graphql.js";
import type { JobProgressEvent, JobStatus } from "../../graphql/types.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";

const WORKSPACE_NAME_PATTERN = /^[a-z][a-z0-9_-]{0,63}$/;

/**
 * Backstop poll of the workspace list while an operation is in flight. The
 * `jobProgress` subscription is the primary driver; this only catches the
 * rare case where a terminal event is missed, by reading the same outcome off
 * the list.
 */
const OPERATION_POLL_INTERVAL = 3000;

/**
 * The backstop poll only declares an operation failed once it has run this
 * long: right after a request, a node that failed before may not have acted on
 * it yet, and the job's own terminal event is the authority anyway.
 */
const OPERATION_POLL_FAILURE_GRACE = 60000;

/** One change reaches the list as several events (the desired state, then each node's report). */
const REFRESH_DEBOUNCE = 300;

const TERMINAL_STATUSES: ReadonlySet<JobStatus> = new Set(['completed', 'aborted', 'failed']);

/** The kind of long-running operation the overlay is tracking. */
type OperationKind = 'create' | 'delete' | 'start' | 'stop' | 'restart';

interface Operation {
	kind: OperationKind;
	name: string;
	jobId: string;
	/** Coarse phase from the job (creating/starting/stopping/deleting). */
	phase: string;
	/** Generic job status; 'failed' switches the overlay to its error mode. */
	status: JobStatus;
	errorMessage: string;
	/** When the operation was requested (epoch millis). */
	startedAt: number;
}

/** The editable settings of the selected workspace, bound to the form. */
interface EditForm {
	displayName: string;
	autoStart: boolean;
	bpmEnabled: boolean;
	eipEnabled: boolean;
}

const App = {
	data() {
		return {
			// Readiness gate for the whole screen (see the <template v-if> in
			// index.html). Flipped by appLaunch() once the component templates
			// are present, so no component element is connected before its
			// <template> exists.
			isReady: false,
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			// Reactive Localization snapshot — see composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
			workspaces: [] as WorkspaceInfo[],
			isLoading: false,
			errorMessage: '',
			// Left pane: filter + selection. Selection is tracked by name (stable
			// across list refreshes), not by object identity.
			searchQuery: '',
			selectedName: null as string | null,
			sidebarPanelWidth: 280,
			// Right pane: the selected workspace's editable settings.
			editForm: {
				displayName: '',
				autoStart: true,
				bpmEnabled: true,
				eipEnabled: true,
			} as EditForm,
			isSaving: false,
			isRetrying: false,
			// Create dialog, delete confirmation, and the restart offered after
			// saving engine settings.
			dialog: {
				type: null as null | 'create' | 'delete' | 'restart',
				name: '',
				isLoading: false,
				errorMessage: '',
			},
			// In-flight lifecycle job. Drives the progress overlay: a loader and
			// phase message while it runs, an error with a Close button when the
			// job fails. Null when nothing is in flight.
			operation: null as null | Operation,
			operationUnsubscribe: null as null | (() => void),
			operationTimer: null as number | null,
			// Live list updates: re-read the workspaces when anything about them
			// changes anywhere in the cluster, collapsed by a short debounce.
			workspacesUnsubscribe: null as null | (() => void),
			refreshTimer: null as number | null,
		};
	},
	computed: {
		/** Client-side filtered workspace list (left pane). */
		displayedWorkspaces(): WorkspaceInfo[] {
			const q = this.searchQuery.trim().toLowerCase();
			if (!q) {
				return this.workspaces;
			}
			return (this.workspaces as WorkspaceInfo[]).filter((w) =>
				w.name.toLowerCase().includes(q)
				|| (w.displayName || '').toLowerCase().includes(q));
		},
		/** The workspace currently selected in the left pane, or null. */
		selectedWorkspace(): WorkspaceInfo | null {
			if (!this.selectedName) {
				return null;
			}
			return (this.workspaces as WorkspaceInfo[]).find((w) => w.name === this.selectedName) || null;
		},
		/** Status-bar summary. */
		listStatusText(): string {
			const total = this.workspaces.length;
			if (this.searchQuery.trim()) {
				return this.t('app.workspace-manager.status.filtered',
					{ shown: this.displayedWorkspaces.length, total },
					`${this.displayedWorkspaces.length} of ${total} workspace(s)`);
			}
			return this.t('app.workspace-manager.status.count', { count: total }, `${total} workspace(s)`);
		},
		/**
		 * Whether some node runs the workspace with engine switches other than
		 * the saved ones, so the UI can prompt for a restart to apply them on
		 * every node. A node that does not run the workspace has nothing to
		 * diverge from.
		 */
		engineChangePending(): boolean {
			const ws = this.selectedWorkspace as WorkspaceInfo | null;
			if (!ws) {
				return false;
			}
			return (ws.nodes || []).some((n: WorkspaceNodeInfo) => n.alive && n.engineSettingsPending);
		},
	},
	methods: {
		/** Reactive i18n lookup; repaints on language change. */
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) {
					return;
				}
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				// --- Readiness gate ---
				// Load the component templates BEFORE the gated markup is
				// compiled, so each <wt-*> element finds its <template> on the
				// single connectedCallback it gets. The popup adapter is passed
				// here as well: wt-select menus escape the window through the
				// shell popup API.
				try {
					await initUi({ popupAdapter: createShellPopupAdapter(instance) });
				} catch (e) {
					console.warn('[WorkspaceManager] Failed to load component templates:', e);
				}
				vm.isReady = true;
				await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));

				await vm.refresh();

				// Keep the list live: a change made elsewhere (another admin, a
				// node reporting its state) should appear here too.
				try {
					vm.workspacesUnsubscribe = vm.instance?.api?.eventHub?.watchWorkspaces(() => vm.scheduleRefresh()) || null;
				} catch (err) {
					console.warn('[WorkspaceManager] Failed to subscribe to workspace changes:', err);
				}

				this.$nextTick(() => {
					instance.notifyLaunched();
				});
			};
		},
		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
			if (this.workspacesUnsubscribe) {
				try { this.workspacesUnsubscribe(); } catch { /* noop */ }
				this.workspacesUnsubscribe = null;
			}
			if (this.refreshTimer != null) {
				window.clearTimeout(this.refreshTimer);
				this.refreshTimer = null;
			}
			// Stop watching the in-flight operation; the server keeps working.
			this.stopWatchingOperation();
			this.operation = null;
		},

		// =====================================================================
		// List + selection
		// =====================================================================

		async refresh() {
			const vm = this;
			vm.isLoading = true;
			try {
				vm.applyWorkspaces(await vm.instance.api.webtop.listWorkspaces());
			} catch (err: any) {
				vm.errorMessage = err?.message || String(err);
			} finally {
				vm.isLoading = false;
			}
		},
		/**
		 * Live update: the events of one change are collapsed into a single quiet
		 * re-read, without the loading overlay.
		 */
		scheduleRefresh() {
			const vm = this;
			if (vm.refreshTimer != null) {
				window.clearTimeout(vm.refreshTimer);
			}
			vm.refreshTimer = window.setTimeout(async () => {
				vm.refreshTimer = null;
				try {
					vm.applyWorkspaces(await vm.instance.api.webtop.listWorkspaces());
				} catch {
					// Transient; the next change or a manual refresh catches up.
				}
			}, REFRESH_DEBOUNCE);
		},
		applyWorkspaces(workspaces: WorkspaceInfo[]) {
			const vm = this;
			vm.workspaces = workspaces;
			// Drop a selection whose workspace is gone (e.g. after a delete);
			// otherwise keep it so a background refresh never steals focus.
			if (vm.selectedName && !vm.workspaces.some((w: WorkspaceInfo) => w.name === vm.selectedName)) {
				vm.selectedName = null;
			}
		},
		selectWorkspace(ws: WorkspaceInfo) {
			this.selectedName = ws.name;
			this.populateForm(ws);
			this.errorMessage = '';
		},
		/** Load the selected workspace's persisted settings into the form. */
		populateForm(ws: WorkspaceInfo) {
			this.editForm = {
				displayName: ws.displayName || '',
				autoStart: ws.autoStart,
				// `enabled` reflects the persisted config (readable while stopped);
				// default to on for forward-compatibility if ever absent.
				bpmEnabled: ws.processEngine?.enabled ?? true,
				eipEnabled: ws.integrationEngine?.enabled ?? true,
			};
		},
		// =====================================================================
		// Per-state capabilities (drive which action buttons are shown)
		//
		// The system workspace (identity store) and the workspace the desktop is
		// bound to are protected: stopping or restarting them would break the
		// running system or the user's own session, so those actions are hidden.
		// =====================================================================

		isProtected(ws: WorkspaceInfo | null): boolean {
			return !!ws && (ws.system || ws.current);
		},
		/** Stopped on purpose, or failed on some nodes: starting asks every node, and failed ones try again. */
		canStart(ws: WorkspaceInfo | null): boolean {
			return !!ws && !ws.system && ws.clusterState !== 'DELETING'
				&& (ws.desiredRun === 'STOPPED' || ws.clusterState === 'FAILED' || ws.clusterState === 'DEGRADED');
		},
		canStop(ws: WorkspaceInfo | null): boolean {
			return !!ws && !this.isProtected(ws) && ws.desiredRun === 'RUNNING' && ws.clusterState !== 'DELETING';
		},
		canRestart(ws: WorkspaceInfo | null): boolean {
			return !!ws && !this.isProtected(ws) && ws.desiredRun === 'RUNNING'
				&& (ws.clusterState === 'ONLINE' || ws.clusterState === 'DEGRADED');
		},
		/** Also offered while a deletion that did not complete is pending, to run it again. */
		canDelete(ws: WorkspaceInfo | null): boolean {
			return !!ws && !ws.system && !ws.current;
		},
		canRetryNode(ws: WorkspaceInfo | null, node: WorkspaceNodeInfo): boolean {
			return !!ws && node.alive && node.state === 'FAILED' && ws.desiredRun === 'RUNNING'
				&& ws.clusterState !== 'DELETING';
		},
		failedNodes(ws: WorkspaceInfo | null): WorkspaceNodeInfo[] {
			return (ws?.nodes || []).filter((n: WorkspaceNodeInfo) => this.canRetryNode(ws, n));
		},

		// =====================================================================
		// State presentation
		// =====================================================================

		stateLabel(ws: WorkspaceInfo): string {
			switch (ws.clusterState) {
				case 'ONLINE': return this.t('app.workspace-manager.state.online', undefined, 'Running');
				case 'STARTING': return this.t('app.workspace-manager.state.starting', undefined, 'Starting…');
				case 'STOPPING': return this.t('app.workspace-manager.state.stopping', undefined, 'Stopping…');
				case 'STOPPED': return this.t('app.workspace-manager.state.stopped', undefined, 'Stopped');
				case 'DEGRADED': {
					const alive = (ws.nodes || []).filter((n: WorkspaceNodeInfo) => n.alive);
					const online = alive.filter((n: WorkspaceNodeInfo) => n.state === 'ONLINE').length;
					return this.t('app.workspace-manager.state.degraded', { online, total: alive.length },
						`Degraded ${online}/${alive.length}`);
				}
				case 'FAILED': return this.t('app.workspace-manager.state.failed', undefined, 'Failed');
				case 'DELETING': return this.t('app.workspace-manager.state.deleting', undefined, 'Deleting…');
				default: return ws.clusterState || ws.state;
			}
		},
		stateBadgeClass(ws: WorkspaceInfo): string {
			switch (ws.clusterState) {
				case 'ONLINE': return 'service-badge';
				case 'STOPPED': return 'disabled-badge';
				case 'DEGRADED':
				case 'FAILED': return 'service-badge service-badge-failed';
				default: return 'service-badge service-badge-starting';
			}
		},
		/** The reason of every alive node the workspace failed on, one per line (the badge tooltip). */
		stateTitle(ws: WorkspaceInfo): string {
			const reasons = (ws.nodes || [])
				.filter((n: WorkspaceNodeInfo) => n.alive && n.state === 'FAILED')
				.map((n: WorkspaceNodeInfo) => `${n.hostName || n.nodeId}: ${n.stateMessage || ''}`);
			if (reasons.length > 0) {
				return reasons.join('\n');
			}
			return ws.stateMessage || '';
		},
		nodeStateLabel(node: WorkspaceNodeInfo): string {
			switch (node.state) {
				case 'ONLINE': return this.t('app.workspace-manager.state.online', undefined, 'Running');
				case 'STARTING': return this.t('app.workspace-manager.state.starting', undefined, 'Starting…');
				case 'STOPPED': return this.t('app.workspace-manager.state.stopped', undefined, 'Stopped');
				case 'FAILED': return this.t('app.workspace-manager.state.failed', undefined, 'Failed');
				case 'CLOSED': return this.t('app.workspace-manager.state.closed', undefined, 'Not open');
				default: return this.t('app.workspace-manager.state.unknown', undefined, 'Unknown');
			}
		},
		nodeStateClass(node: WorkspaceNodeInfo): string {
			switch (node.state) {
				case 'ONLINE': return 'service-badge';
				case 'FAILED': return 'service-badge service-badge-failed';
				case 'STOPPED':
				case 'CLOSED': return 'disabled-badge';
				default: return 'service-badge service-badge-starting';
			}
		},
		heartbeatLabel(node: WorkspaceNodeInfo): string {
			if (node.alive) {
				return this.t('app.workspace-manager.nodes.alive', undefined, 'Alive');
			}
			return node.registered
				? this.t('app.workspace-manager.nodes.stale', undefined, 'Stale')
				: this.t('app.workspace-manager.nodes.departed', undefined, 'Not registered');
		},
		/** "On" when running; "Failed" when switched on but not running; "Off" when switched off; "—" when the services are not running. */
		engineLabel(engine: { enabled: boolean | null; running: boolean } | null): string {
			if (!engine || engine.enabled == null) {
				return '—';
			}
			if (engine.running) {
				return this.t('app.workspace-manager.nodes.engine.on', undefined, 'On');
			}
			return engine.enabled
				? this.t('app.workspace-manager.nodes.engine.failed', undefined, 'Failed')
				: this.t('app.workspace-manager.nodes.engine.off', undefined, 'Off');
		},
		/** Whether the latest restart and the saved engine settings are applied on the node. */
		appliedLabel(node: WorkspaceNodeInfo): string {
			if (node.state !== 'ONLINE' && node.state !== 'STOPPED') {
				return '—';
			}
			return (node.restartPending || node.engineSettingsPending)
				? this.t('app.workspace-manager.nodes.pending', undefined, 'Pending')
				: this.t('app.workspace-manager.nodes.applied', undefined, 'Up to date');
		},
		nodeDetails(node: WorkspaceNodeInfo): string {
			if (node.state === 'FAILED' && node.stateMessage) {
				return node.stateMessage;
			}
			if (!node.alive) {
				const time = this.formatTime(node.updated);
				return time ? this.t('app.workspace-manager.nodes.lastReport', { time }, `Last report ${time}`) : '';
			}
			if (node.state === 'UNKNOWN') {
				return this.t('app.workspace-manager.nodes.notReported', undefined, 'Has not reported its state yet');
			}
			if (node.restartPending) {
				return this.t('app.workspace-manager.nodes.restartPending', undefined, 'Waiting to apply the restart');
			}
			if (node.engineSettingsPending) {
				return this.t('app.workspace-manager.nodes.engineSettingsPending', undefined,
					'The saved engine settings are not applied yet');
			}
			return '';
		},
		formatTime(iso: string | null | undefined): string {
			if (!iso) {
				return '';
			}
			try {
				return new Date(iso).toLocaleString();
			} catch {
				return String(iso);
			}
		},

		// =====================================================================
		// Save (display name, auto-start, engine switches)
		// =====================================================================

		async saveWorkspace() {
			const vm = this;
			const ws = vm.selectedWorkspace;
			if (!ws || vm.isSaving) {
				return;
			}
			// The engine switches only take effect when the services restart; when
			// they change on a running workspace, the restart is offered right away.
			const enginesChanged = vm.editForm.bpmEnabled !== (ws.processEngine?.enabled ?? true)
				|| vm.editForm.eipEnabled !== (ws.integrationEngine?.enabled ?? true);
			vm.isSaving = true;
			try {
				const updated = await vm.instance.api.webtop.updateWorkspace({
					name: ws.name,
					displayName: vm.editForm.displayName,
					autoStart: vm.editForm.autoStart,
					bpmEnabled: vm.editForm.bpmEnabled,
					eipEnabled: vm.editForm.eipEnabled,
				});
				// Reflect the saved state immediately (the list entry and the form).
				const idx = vm.workspaces.findIndex((w: WorkspaceInfo) => w.name === updated.name);
				if (idx >= 0) {
					vm.workspaces.splice(idx, 1, updated);
				}
				vm.populateForm(updated);
				if (enginesChanged && vm.canRestart(updated)) {
					vm.dialog.type = 'restart';
					vm.dialog.name = updated.name;
					vm.dialog.isLoading = false;
					vm.dialog.errorMessage = '';
				}
			} catch (err: any) {
				vm.errorMessage = err?.message || String(err);
			} finally {
				vm.isSaving = false;
			}
		},

		// =====================================================================
		// Create
		// =====================================================================

		isValidName(name: string): boolean {
			return WORKSPACE_NAME_PATTERN.test(name);
		},
		showCreateDialog() {
			const vm = this;
			vm.dialog.type = 'create';
			vm.dialog.name = '';
			vm.dialog.isLoading = false;
			vm.dialog.errorMessage = '';
		},
		onCreateKeydown(event: KeyboardEvent) {
			if (event.key === 'Enter' && this.isValidName(this.dialog.name)) {
				this.submitCreate();
			}
		},
		async submitCreate() {
			const vm = this;
			if (!vm.isValidName(vm.dialog.name) || vm.dialog.isLoading) {
				return;
			}
			const name = vm.dialog.name;
			vm.startOperation('create', name);
			vm.dialog.isLoading = true;
			vm.dialog.errorMessage = '';
			try {
				const handle = await vm.instance.api.webtop.createWorkspace(name);
				vm.closeDialog();
				vm.watchOperation(handle.jobId);
			} catch (err: any) {
				vm.cancelOperation();
				vm.dialog.errorMessage = err?.message || String(err);
			} finally {
				vm.dialog.isLoading = false;
			}
		},

		// =====================================================================
		// Delete (single confirmation step — no name re-typing)
		// =====================================================================

		showDeleteDialog() {
			const vm = this;
			if (!vm.canDelete(vm.selectedWorkspace)) {
				return;
			}
			vm.dialog.type = 'delete';
			vm.dialog.name = vm.selectedWorkspace!.name;
			vm.dialog.isLoading = false;
			vm.dialog.errorMessage = '';
		},
		async submitDelete() {
			const vm = this;
			if (vm.dialog.isLoading) {
				return;
			}
			const name = vm.dialog.name;
			vm.startOperation('delete', name);
			vm.dialog.isLoading = true;
			vm.dialog.errorMessage = '';
			try {
				const handle = await vm.instance.api.webtop.deleteWorkspace(name);
				vm.closeDialog();
				vm.watchOperation(handle.jobId);
			} catch (err: any) {
				vm.cancelOperation();
				vm.dialog.errorMessage = err?.message || String(err);
			} finally {
				vm.dialog.isLoading = false;
			}
		},
		closeDialog() {
			const vm = this;
			vm.dialog.type = null;
			vm.dialog.name = '';
			vm.dialog.errorMessage = '';
		},
		/** The restart offered after saving engine settings, accepted: restart on every node. */
		async confirmRestart() {
			const vm = this;
			const name = vm.dialog.name;
			vm.closeDialog();
			vm.selectedName = name;
			await vm.restartSelected();
		},

		// =====================================================================
		// Start / Stop / Restart (background jobs)
		// =====================================================================

		async startSelected() {
			await this.runLifecycle('start', (name: string) => this.instance.api.webtop.startWorkspace(name));
		},
		async stopSelected() {
			await this.runLifecycle('stop', (name: string) => this.instance.api.webtop.stopWorkspace(name));
		},
		async restartSelected() {
			await this.runLifecycle('restart', (name: string) => this.instance.api.webtop.restartWorkspace(name));
		},
		async runLifecycle(kind: OperationKind, submit: (name: string) => Promise<{ jobId: string }>) {
			const vm = this;
			const ws = vm.selectedWorkspace;
			if (!ws || vm.operation) {
				return;
			}
			vm.startOperation(kind, ws.name);
			try {
				const handle = await submit(ws.name);
				vm.watchOperation(handle.jobId);
			} catch (err: any) {
				vm.cancelOperation();
				vm.errorMessage = err?.message || String(err);
			}
		},
		/** Asks the given nodes — or every node the workspace failed on — to try starting it again. */
		async retryNodes(ws: WorkspaceInfo, nodeIds?: string[]) {
			const vm = this;
			if (!ws || vm.isRetrying) {
				return;
			}
			vm.isRetrying = true;
			try {
				const updated = await vm.instance.api.webtop.retryWorkspace(ws.name, nodeIds);
				const idx = vm.workspaces.findIndex((w: WorkspaceInfo) => w.name === updated.name);
				if (idx >= 0) {
					vm.workspaces.splice(idx, 1, updated);
				}
			} catch (err: any) {
				vm.errorMessage = err?.message || String(err);
			} finally {
				vm.isRetrying = false;
			}
		},

		// =====================================================================
		// Operation tracking (shared by every lifecycle job)
		// =====================================================================

		startOperation(kind: OperationKind, name: string) {
			this.stopWatchingOperation();
			const initialPhase = (kind === 'create') ? 'creating'
				: (kind === 'start' || kind === 'restart') ? 'starting'
					: 'stopping';
			this.operation = {
				kind,
				name,
				jobId: '',
				phase: initialPhase,
				status: 'running',
				errorMessage: '',
				startedAt: Date.now(),
			};
		},
		cancelOperation() {
			this.stopWatchingOperation();
			this.operation = null;
		},
		watchOperation(jobId: string) {
			const vm = this;
			if (!vm.operation) {
				return;
			}
			vm.operation.jobId = jobId;
			const eventHub = vm.instance?.api?.eventHub;
			if (eventHub) {
				vm.operationUnsubscribe = eventHub.watchJobProgress(jobId, (event: JobProgressEvent) => {
					vm.onOperationEvent(jobId, event);
				});
			}
			vm.scheduleOperationPoll();
		},
		onOperationEvent(jobId: string, event: JobProgressEvent) {
			const vm = this;
			if (!vm.operation || vm.operation.jobId !== jobId) {
				return;
			}
			if (event.phase) {
				vm.operation.phase = event.phase;
			}
			vm.operation.status = event.status;
			if (TERMINAL_STATUSES.has(event.status)) {
				if (event.status === 'failed') {
					vm.failOperation(event.errorMessage);
				} else {
					vm.completeOperation();
				}
			}
		},
		scheduleOperationPoll() {
			const vm = this;
			if (vm.operationTimer != null) {
				window.clearTimeout(vm.operationTimer);
			}
			vm.operationTimer = window.setTimeout(() => vm.pollOperation(), OPERATION_POLL_INTERVAL);
		},
		async pollOperation() {
			const vm = this;
			vm.operationTimer = null;
			const op = vm.operation;
			if (!op || op.status === 'failed') {
				return;
			}
			try {
				const workspaces = await vm.instance.api.webtop.listWorkspaces();
				if (!vm.operation || vm.operation.jobId !== op.jobId || vm.operation.status === 'failed') {
					return;
				}
				vm.workspaces = workspaces;
				const ws = workspaces.find((w: WorkspaceInfo) => w.name === op.name);
				if (op.kind === 'delete') {
					if (!ws) {
						vm.completeOperation();
						return;
					}
				} else if (op.kind === 'stop') {
					if (ws?.clusterState === 'STOPPED') {
						vm.completeOperation();
						return;
					}
				} else {
					// create / start / restart: online on every node is success; a
					// node that still fails once the grace period is over is failure.
					if (ws?.clusterState === 'ONLINE') {
						vm.completeOperation();
						return;
					}
					if (ws && (ws.clusterState === 'FAILED' || ws.clusterState === 'DEGRADED')
						&& Date.now() - op.startedAt > OPERATION_POLL_FAILURE_GRACE) {
						vm.failOperation(vm.stateTitle(ws) || undefined);
						return;
					}
				}
			} catch {
				// Transient query failure; keep watching.
			}
			vm.scheduleOperationPoll();
		},
		completeOperation() {
			this.stopWatchingOperation();
			this.operation = null;
			this.refresh();
		},
		failOperation(message?: string) {
			this.stopWatchingOperation();
			if (this.operation) {
				this.operation.status = 'failed';
				this.operation.errorMessage = message
					|| this.t('app.workspace-manager.progress.failedGeneric', undefined,
						'The operation failed. See the server log for details.');
			}
			this.refresh();
		},
		dismissOperation() {
			this.operation = null;
		},
		stopWatchingOperation() {
			if (this.operationUnsubscribe) {
				try { this.operationUnsubscribe(); } catch { /* noop */ }
				this.operationUnsubscribe = null;
			}
			if (this.operationTimer != null) {
				window.clearTimeout(this.operationTimer);
				this.operationTimer = null;
			}
		},
		/** Localized progress line for the current operation's phase. */
		operationMessage(): string {
			const op = this.operation;
			if (!op) {
				return '';
			}
			const fallbacks: Record<string, string> = {
				creating: 'Creating the "{name}" workspace. This may take a few minutes.',
				starting: 'Starting the "{name}" workspace services. This may take a few minutes.',
				stopping: 'Stopping the "{name}" workspace services…',
				deleting: 'Deleting the "{name}" workspace…',
			};
			const phase = fallbacks[op.phase] ? op.phase
				: (op.kind === 'create' ? 'creating'
					: op.kind === 'delete' ? 'deleting'
						: (op.kind === 'start' || op.kind === 'restart') ? 'starting'
							: 'stopping');
			return this.t('app.workspace-manager.progress.phase.' + phase, { name: op.name }, fallbacks[phase]);
		},
		/** Each alive node's state for the workspace the overlay tracks. */
		operationNodes(): WorkspaceNodeInfo[] {
			const op = this.operation;
			if (!op) {
				return [];
			}
			const ws = (this.workspaces as WorkspaceInfo[]).find((w) => w.name === op.name);
			return (ws?.nodes || []).filter((n: WorkspaceNodeInfo) => n.alive);
		},
		/** Localized title for the error overlay. */
		operationErrorTitle(): string {
			const op = this.operation;
			const titles: Record<OperationKind, [string, string]> = {
				create: ['app.workspace-manager.progress.failedCreateTitle', 'Could not create "{name}"'],
				delete: ['app.workspace-manager.progress.failedDeleteTitle', 'Could not delete "{name}"'],
				start: ['app.workspace-manager.progress.failedStartTitle', 'Could not start "{name}"'],
				stop: ['app.workspace-manager.progress.failedStopTitle', 'Could not stop "{name}"'],
				restart: ['app.workspace-manager.progress.failedRestartTitle', 'Could not restart "{name}"'],
			};
			const [id, fallback] = titles[op?.kind || 'create'];
			return this.t(id, { name: op?.name }, fallback);
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
	},
};

// Mount immediately. The screen itself is behind the readiness gate
// (<template v-if="isReady"> in index.html), which appLaunch opens once the
// component templates are loaded — so mounting no longer has to wait on a
// fetch, and window.appLaunch is defined the moment the iframe finishes
// loading.
VDOM.createApp(App).mount('#app');

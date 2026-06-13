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
 *  - toggle auto-start (whether the workspace starts when the node boots);
 *  - start, stop and restart the workspace; and
 *  - delete it.
 *
 * Lifecycle actions — create, delete, start, stop, restart — run as background
 * jobs on the server (they can take minutes and either can fail), so the app
 * shows the same non-cancellable progress overlay the Content Browser uses for
 * long operations, driven by the job's `jobProgress` events: the phase while it
 * runs, and on failure a dismissable error instead of a spinner that never
 * stops. The list also live-updates from the `workspaceChanged` subscription so
 * a workspace started or stopped elsewhere is reflected here without a manual
 * refresh. The system workspace — the identity store — and the workspace the
 * desktop is currently bound to cannot be stopped, restarted or deleted.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { ApplicationInstance } from "../../services/webtop-service.js";
import type { WorkspaceInfo } from "../../services/webtop-service-graphql.js";
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
			_sidebarResizeMoveHandler: null as null | ((e: MouseEvent) => void),
			_sidebarResizeUpHandler: null as null | (() => void),
			// Right pane: the selected workspace's editable settings.
			editForm: {
				displayName: '',
				autoStart: true,
				bpmEnabled: true,
				eipEnabled: true,
			} as EditForm,
			isSaving: false,
			// Create dialog and delete confirmation.
			dialog: {
				type: null as null | 'create' | 'delete',
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
			// Live list updates: re-read the workspaces when any of them starts or
			// stops anywhere in the repository.
			workspacesUnsubscribe: null as null | (() => void),
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
		 * Whether the form's engine switches differ from what is currently
		 * running, so the UI can prompt for a restart to apply them. While the
		 * workspace is not running there is nothing to diverge from.
		 */
		engineChangePending(): boolean {
			const ws = this.selectedWorkspace as WorkspaceInfo | null;
			if (!ws || ws.state !== 'ONLINE') {
				return false;
			}
			const bpmRunning = !!ws.processEngine?.running;
			const eipRunning = !!ws.integrationEngine?.running;
			return this.editForm.bpmEnabled !== bpmRunning || this.editForm.eipEnabled !== eipRunning;
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

				await vm.refresh();

				// Keep the list live: a workspace started or stopped elsewhere
				// (another admin, another node) should appear/disappear here too.
				try {
					vm.workspacesUnsubscribe = vm.instance?.api?.eventHub?.watchWorkspaces(() => vm.refresh()) || null;
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
				vm.workspaces = await vm.instance.api.webtop.listWorkspaces();
				// Drop a selection whose workspace is gone (e.g. after a delete);
				// otherwise keep it so a background refresh never steals focus.
				if (vm.selectedName && !vm.workspaces.some((w: WorkspaceInfo) => w.name === vm.selectedName)) {
					vm.selectedName = null;
				}
			} catch (err: any) {
				vm.errorMessage = err?.message || String(err);
			} finally {
				vm.isLoading = false;
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
		onSearchInput() {
			// v-model drives `displayedWorkspaces`; nothing else to do.
		},
		clearSearch() {
			this.searchQuery = '';
		},
		onSidebarResizeStart(e: MouseEvent) {
			e.preventDefault();
			const vm = this;
			const startX = e.clientX;
			const startWidth = vm.sidebarPanelWidth;
			vm._sidebarResizeMoveHandler = (moveEvent: MouseEvent) => {
				const delta = moveEvent.clientX - startX;
				vm.sidebarPanelWidth = Math.max(200, Math.min(600, startWidth + delta));
			};
			vm._sidebarResizeUpHandler = () => {
				document.removeEventListener('mousemove', vm._sidebarResizeMoveHandler!);
				document.removeEventListener('mouseup', vm._sidebarResizeUpHandler!);
				vm._sidebarResizeMoveHandler = null;
				vm._sidebarResizeUpHandler = null;
			};
			document.addEventListener('mousemove', vm._sidebarResizeMoveHandler);
			document.addEventListener('mouseup', vm._sidebarResizeUpHandler);
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
		canStart(ws: WorkspaceInfo | null): boolean {
			return !!ws && (ws.state === 'STOPPED' || ws.state === 'FAILED');
		},
		canStop(ws: WorkspaceInfo | null): boolean {
			return !!ws && !this.isProtected(ws) && (ws.state === 'ONLINE' || ws.state === 'STARTING');
		},
		canRestart(ws: WorkspaceInfo | null): boolean {
			return !!ws && !this.isProtected(ws) && ws.state === 'ONLINE';
		},
		canDelete(ws: WorkspaceInfo | null): boolean {
			return !!ws && !ws.system && !ws.current;
		},

		// =====================================================================
		// State presentation
		// =====================================================================

		stateLabel(ws: WorkspaceInfo): string {
			switch (ws.state) {
				case 'ONLINE': return this.t('app.workspace-manager.state.online', undefined, 'Running');
				case 'STARTING': return this.t('app.workspace-manager.state.starting', undefined, 'Starting…');
				case 'STOPPED': return this.t('app.workspace-manager.state.stopped', undefined, 'Stopped');
				case 'FAILED': return this.t('app.workspace-manager.state.failed', undefined, 'Failed');
				default: return ws.state;
			}
		},
		stateBadgeClass(ws: WorkspaceInfo): string {
			switch (ws.state) {
				case 'ONLINE': return 'service-badge';
				case 'STARTING': return 'service-badge service-badge-starting';
				case 'STOPPED': return 'disabled-badge';
				case 'FAILED': return 'service-badge service-badge-failed';
				default: return 'service-badge';
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

		// =====================================================================
		// Operation tracking (shared by every lifecycle job)
		// =====================================================================

		startOperation(kind: OperationKind, name: string) {
			this.stopWatchingOperation();
			const initialPhase = (kind === 'create') ? 'creating'
				: (kind === 'start') ? 'starting'
					: 'stopping';
			this.operation = {
				kind,
				name,
				jobId: '',
				phase: initialPhase,
				status: 'running',
				errorMessage: '',
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
					if (ws?.state === 'STOPPED') {
						vm.completeOperation();
						return;
					}
				} else {
					// create / start / restart: ONLINE is success, FAILED is failure.
					if (ws?.state === 'ONLINE') {
						vm.completeOperation();
						return;
					}
					if (ws?.state === 'FAILED') {
						vm.failOperation(ws.stateMessage || undefined);
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
						: op.kind === 'start' ? 'starting'
							: 'stopping');
			return this.t('app.workspace-manager.progress.phase.' + phase, { name: op.name }, fallbacks[phase]);
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

VDOM.createApp(App).mount('#app');

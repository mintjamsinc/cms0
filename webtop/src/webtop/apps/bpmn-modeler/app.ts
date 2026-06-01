import { ApplicationInstance, PopupHandle, PopupItem } from "../../services/webtop-service.js";
import type { Node } from "../../graphql/types.js";
import {
	BpmnModelStore,
	BpmnSemantic,
	BpmnFlowSemantic,
	BpmnDiShape,
	BpmnDiEdge,
	generateUUID,
	type Bounds,
	type Point,
	type ExecutionListener as StoreExecutionListener,
	type TaskListener as StoreTaskListener,
	type InputOutputParameter as StoreInputOutputParameter,
} from './core/bpmn-model-types.js';


import type {
	BpmnElement,
	BpmnConnection,
	SubTypeOption,
} from './core/element-types.js';
import {
	ELEMENT_SIZES,
	ELEMENT_SUBTYPES,
	CONNECTION_POINT_OFFSETS,
	getElementSubType,
	supportsTypeChange,
	isMessageThrowEvent,
	generateId,
	getElementCenter,
	getConnectionPoint,
	getHorizontalConnectionYOffset,
} from './core/element-config.js';
import { parseBpmnXmlToStore } from './core/xml-parser-store.js';
import { serializeStoreToXml } from './core/xml-serializer-store.js';

interface CurrentFile {
	path: string;
	name: string;
	mimeType: string;
	isVersionable: boolean;
	isCheckedOut: boolean;
	baseVersionName: string;
}

/**
 * Represents an open file tab in the BPMN editor, storing its own
 * model state, undo history, and viewport position.
 */
interface BpmnFile {
	id: string;
	path: string;
	name: string;
	mimeType: string;
	isVersionable: boolean;
	isCheckedOut: boolean;
	baseVersionName: string;
	store: BpmnModelStore;
	originalXml: string;
	isModified: boolean;
	commandManager: CommandManager;
	zoom: number;
	panX: number;
	panY: number;
	selectedIds: string[];
	storeVersion: number;
}

interface LaunchOptions {
	path?: string;
	mimeType?: string;
	paths?: string[];
	activeIndex?: number;
	fileStates?: { path: string; zoom?: number; panX?: number; panY?: number }[];
}

// =============================================================================
// Command Manager (Undo/Redo)
// =============================================================================

interface Command {
	execute(): void;
	undo(): void;
}

class CommandManager {
	private undoStack: Command[] = [];
	private redoStack: Command[] = [];
	private maxHistory = 50;

	execute(command: Command): void {
		command.execute();
		this.undoStack.push(command);
		this.redoStack = [];

		if (this.undoStack.length > this.maxHistory) {
			this.undoStack.shift();
		}
	}

	/**
	 * Add a command to the undo stack without executing it.
	 * Use this when the operation has already been performed (e.g., during drag).
	 */
	addCommand(command: Command): void {
		this.undoStack.push(command);
		this.redoStack = [];

		if (this.undoStack.length > this.maxHistory) {
			this.undoStack.shift();
		}
	}

	undo(): boolean {
		const command = this.undoStack.pop();
		if (command) {
			command.undo();
			this.redoStack.push(command);
			return true;
		}
		return false;
	}

	redo(): boolean {
		const command = this.redoStack.pop();
		if (command) {
			command.execute();
			this.undoStack.push(command);
			return true;
		}
		return false;
	}

	get canUndo(): boolean {
		return this.undoStack.length > 0;
	}

	get canRedo(): boolean {
		return this.redoStack.length > 0;
	}

	clear(): void {
		this.undoStack = [];
		this.redoStack = [];
	}
}

// =============================================================================
// Property Change Command (Undo/Redo for property panel changes)
// =============================================================================

/**
 * Command for undoing/redoing property changes made via the property panel.
 * Stores deep copies of the old and new data to avoid reference issues.
 */
class UpdatePropertyCommand implements Command {
	private store: BpmnModelStore;
	private elementId: string;
	private oldData: Record<string, unknown>;
	private newData: Record<string, unknown>;
	private isFlow: boolean;
	private onRestore: () => void;

	constructor(
		store: BpmnModelStore,
		id: string,
		oldData: Record<string, unknown>,
		newData: Record<string, unknown>,
		isFlow: boolean,
		onRestore: () => void
	) {
		this.store = store;
		this.elementId = id;
		// Deep copy to avoid reference issues
		this.oldData = JSON.parse(JSON.stringify(oldData));
		this.newData = JSON.parse(JSON.stringify(newData));
		this.isFlow = isFlow;
		this.onRestore = onRestore;
	}

	execute(): void {
		this.apply(this.newData);
	}

	undo(): void {
		this.apply(this.oldData);
	}

	private apply(data: Record<string, unknown>): void {
		if (this.isFlow) {
			this.store.updateFlow(this.elementId, data as Partial<BpmnFlowSemantic>);
		} else {
			this.store.updateSemantic(this.elementId, data as Partial<BpmnSemantic>);
		}
		this.onRestore();
	}
}

// =============================================================================
// App Component
// =============================================================================

// Non-reactive store instance (must be outside of data() to avoid VApplication proxy wrapping)
// Map methods like forEach fail when called on a Proxy-wrapped Map.
let bpmnModelStore: BpmnModelStore = new BpmnModelStore();

// Store expanded sub-process sizes for collapse/expand operations
// Key: SubProcess element ID, Value: { width, height } when expanded
const expandedSubProcessSizes: Map<string, { width: number; height: number }> = new Map();

// Non-reactive popup handle for the reference combobox suggestions popup.
// Held outside data() so it isn't wrapped by the reactivity proxy (which
// breaks methods on the popup handle).
let refSuggestionPopupHandle: any = null;

// =============================================================================
// Reference widget configuration (Message / Signal / Error / Escalation)
// =============================================================================

type RefKind = 'message' | 'signal' | 'error' | 'escalation';

interface RefConfig {
	kind: RefKind;
	refField: string;        // e.g. 'messageRef' on the element
	nameField: string;       // e.g. 'messageName'
	codeField?: string;      // e.g. 'errorCode' (only for Error / Escalation)
	listField: 'messages' | 'signals' | 'errors' | 'escalations'; // on vm.model
	idPrefix: string;        // generateId() prefix, e.g. 'Message'
	displayPlaceholder: string;  // e.g. '-- Select Message --'
	inputPlaceholder: string;    // e.g. 'Message name'
}

const REF_CONFIGS: Record<RefKind, RefConfig> = {
	message: {
		kind: 'message',
		refField: 'messageRef',
		nameField: 'messageName',
		listField: 'messages',
		idPrefix: 'Message',
		displayPlaceholder: '-- Select Message --',
		inputPlaceholder: 'Message name',
	},
	signal: {
		kind: 'signal',
		refField: 'signalRef',
		nameField: 'signalName',
		listField: 'signals',
		idPrefix: 'Signal',
		displayPlaceholder: '-- Select Signal --',
		inputPlaceholder: 'Signal name',
	},
	error: {
		kind: 'error',
		refField: 'errorRef',
		nameField: 'errorName',
		codeField: 'errorCode',
		listField: 'errors',
		idPrefix: 'Error',
		displayPlaceholder: '-- Select Error --',
		inputPlaceholder: 'Error name',
	},
	escalation: {
		kind: 'escalation',
		refField: 'escalationRef',
		nameField: 'escalationName',
		codeField: 'escalationCode',
		listField: 'escalations',
		idPrefix: 'Escalation',
		displayPlaceholder: '-- Select Escalation --',
		inputPlaceholder: 'Escalation name',
	},
};

// =============================================================================
// Property-panel dropdown option sets
// Shared by the shell-rendered popups that replace native <select> elements
// in the right-side properties pane.
// =============================================================================
interface ChoiceOption { id: string; label: string; }

const TIMER_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'timeDate', label: 'Date (specific date/time)' },
	{ id: 'timeDuration', label: 'Duration (after period)' },
	{ id: 'timeCycle', label: 'Cycle (recurring)' },
];
// Compact variant used by boundary events (shorter labels)
const TIMER_TYPE_SHORT_OPTIONS: ChoiceOption[] = [
	{ id: 'timeDate', label: 'Date' },
	{ id: 'timeDuration', label: 'Duration' },
	{ id: 'timeCycle', label: 'Cycle' },
];

const CONDITION_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'expression', label: 'Expression' },
	{ id: 'script', label: 'Script' },
];

const SCRIPT_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'inline', label: 'Inline Script' },
	{ id: 'external', label: 'External Resource' },
];

// JavaScript-first 3-option format list
const SCRIPT_FORMAT_JS_OPTIONS: ChoiceOption[] = [
	{ id: 'javascript', label: 'JavaScript' },
	{ id: 'groovy', label: 'Groovy' },
	{ id: 'python', label: 'Python' },
];
// Groovy-first 3-option format list
const SCRIPT_FORMAT_GROOVY_OPTIONS: ChoiceOption[] = [
	{ id: 'groovy', label: 'Groovy' },
	{ id: 'javascript', label: 'JavaScript' },
	{ id: 'python', label: 'Python' },
];
// Connector param script formats (only 2)
const PARAM_SCRIPT_FORMAT_OPTIONS: ChoiceOption[] = [
	{ id: 'javascript', label: 'JavaScript' },
	{ id: 'groovy', label: 'Groovy' },
];

const MESSAGE_IMPL_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'class', label: 'Java Class' },
	{ id: 'expression', label: 'Expression' },
	{ id: 'external', label: 'External' },
	{ id: 'connector', label: 'Connector' },
];

const PARAM_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'text', label: 'Text' },
	{ id: 'script', label: 'Script' },
];
// Extension property editor supports list / map as well
const EXTENSION_PARAM_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'text', label: 'Text' },
	{ id: 'script', label: 'Script' },
	{ id: 'list', label: 'List' },
	{ id: 'map', label: 'Map' },
];

const FIELD_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'string', label: 'String' },
	{ id: 'expression', label: 'Expression' },
];

const SERVICE_TASK_IMPL_OPTIONS: ChoiceOption[] = [
	{ id: 'class', label: 'Java Class' },
	{ id: 'expression', label: 'Expression' },
	{ id: 'delegateExpression', label: 'Delegate Expression' },
];

const BUSINESS_RULE_IMPL_OPTIONS: ChoiceOption[] = [
	{ id: 'dmn', label: 'DMN' },
	{ id: 'class', label: 'Java Class' },
	{ id: 'expression', label: 'Expression' },
	{ id: 'external', label: 'External' },
];

const REF_BINDING_OPTIONS: ChoiceOption[] = [
	{ id: 'latest', label: 'latest' },
	{ id: 'deployment', label: 'deployment' },
	{ id: 'version', label: 'version' },
	{ id: 'versionTag', label: 'versionTag' },
];

const MAP_DECISION_RESULT_OPTIONS: ChoiceOption[] = [
	{ id: 'singleEntry', label: 'singleEntry' },
	{ id: 'singleResult', label: 'singleResult' },
	{ id: 'collectEntries', label: 'collectEntries' },
	{ id: 'resultList', label: 'resultList' },
];

const CALLED_ELEMENT_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'bpmn', label: 'BPMN' },
	{ id: 'cmmn', label: 'CMMN' },
];

const EVENT_GATEWAY_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'exclusive', label: 'Exclusive' },
	{ id: 'parallel', label: 'Parallel' },
];

const LOOP_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'standard', label: 'Standard Loop' },
	{ id: 'multiInstanceParallel', label: 'Multi-Instance Parallel' },
	{ id: 'multiInstanceSequential', label: 'Multi-Instance Sequential' },
];

const TRANSACTION_METHOD_OPTIONS: ChoiceOption[] = [
	{ id: 'compensate', label: 'Compensate' },
	{ id: 'store', label: 'Store' },
	{ id: 'image', label: 'Image' },
];

const LISTENER_EVENT_OPTIONS: ChoiceOption[] = [
	{ id: 'start', label: 'start' },
	{ id: 'end', label: 'end' },
];

const LISTENER_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'class', label: 'Java Class' },
	{ id: 'expression', label: 'Expression' },
	{ id: 'script', label: 'Script' },
];

// Task listeners (user tasks): user-task lifecycle events + delegateExpression.
const TASK_LISTENER_EVENT_OPTIONS: ChoiceOption[] = [
	{ id: 'create', label: 'create' },
	{ id: 'assignment', label: 'assignment' },
	{ id: 'complete', label: 'complete' },
	{ id: 'delete', label: 'delete' },
	{ id: 'update', label: 'update' },
];

const TASK_LISTENER_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'class', label: 'Java Class' },
	{ id: 'expression', label: 'Expression' },
	{ id: 'delegateExpression', label: 'Delegate Expression' },
	{ id: 'script', label: 'Script' },
];

// Sentinel value returned by the shell popup for the blank/placeholder row.
// Actual stored property value is '' (empty).
const EMPTY_CHOICE_ID = '__empty__';

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			// Resolved URL of the BPMN icon sprite. Populated in appLaunch
			// from instance.getFullPath. Used in canvas template via
			// <use :href="spriteUrl + '#canvas-...'"/> for shape sub-icons.
			spriteUrl: '',
			files: [] as BpmnFile[],
			currentFileIndex: -1,
			currentFile: {
				path: '',
				name: 'Untitled.bpmn',
				mimeType: 'application/x-bpmn',
				isVersionable: false,
				isCheckedOut: false,
				baseVersionName: '',
			} as CurrentFile,
			// Store reference counter for reactivity trigger (increment to force recompute)
			storeVersion: 0,
			// Unified inline-combobox state for Message / Signal / Error / Escalation
			// reference widgets (mirrors Content Browser's MIME type editor).
			// While refEditing is true the bordered display is replaced by an
			// input + confirm/cancel buttons; the snapshot captures pre-edit
			// state so cancel can revert. Only one of the four kinds can be in
			// edit mode at a time.
			refEditing: false,
			refEditKind: '' as '' | 'message' | 'signal' | 'error' | 'escalation',
			refEditInput: '',
			refEditHighlightIndex: -1,
			refEditSnapshot: null as null | {
				refId: string;
				refName: string;
				code: string;
			},
			// Management dialog state for the toolbar's bi-menu-app group.
			// One dialog instance is shared across the four definition kinds;
			// `kind` controls which list is shown.
			managementDialog: {
				visible: false,
				kind: '' as '' | 'message' | 'signal' | 'error' | 'escalation',
			},
			// Generic notification dialog used in place of window.alert().
			// Severity controls the title icon and color; message can contain
			// newlines (rendered with white-space: pre-wrap).
			notificationDialog: {
				visible: false,
				severity: 'info' as 'info' | 'success' | 'warning' | 'error',
				title: '',
				message: '',
			},
			originalXml: '',
			isLoading: false,
			isSaving: false,
			isModified: false,
			errorMessage: '',
			// Pane visibility / sizing
			sidebarPanelVisible: true,
			detailPanelVisible: true,
			sidebarPanelWidth: 240,
			sidebarResizing: false,
			sidebarResizeStartX: 0,
			sidebarResizeStartWidth: 0,
			// Palette collapsible sections
			paletteExpanded: {
				events: true,
				tasks: true,
				gateways: true,
				subProcesses: true,
				data: true,
				artifacts: true,
				swimlanes: true,
			},
			messageListener: null as ((e: MessageEvent) => void) | null,
			// Viewport
			zoom: 1,
			panX: 0,
			panY: 0,
			// Selection
			selectedIds: [] as string[],
			// Drag state
			isDragging: false,
			dragStartX: 0,
			dragStartY: 0,
			dragElementStartPositions: [] as { id: string; x: number; y: number }[],
			// Pan state
			isPanning: false,
			// Space key held — alternative pan trigger alongside Alt+drag.
			// While true, a left-button mousedown on the canvas starts panning
			// instead of starting a selection rectangle.
			isSpaceHeld: false,
			panStartX: 0,
			panStartY: 0,
			panStartPanX: 0,
			panStartPanY: 0,
			// Selection rectangle
			selectionRect: null as { x: number; y: number; width: number; height: number } | null,
			selectionStartX: 0,
			selectionStartY: 0,
			// Connection preview
			connectionPreview: '' as string,
			// Connection drawing state
			isConnecting: false,
			connectionSourceId: '' as string,
			connectionSourcePoint: { x: 0, y: 0 },
			connectionSourceSide: null as 'N' | 'S' | 'E' | 'W' | null,
			connectionEndPoint: { x: 0, y: 0 },
			connectionTargetIds: [] as string[],
			// Connection reconnect state
			isReconnecting: false,
			reconnectingConnectionId: '' as string,
			reconnectingSide: '' as 'source' | 'target' | '',
			reconnectStartPoint: { x: 0, y: 0 },
			reconnectEndPoint: { x: 0, y: 0 },
			// Bend-point dragging state
			isBendDragging: false,
			bendConnectionId: '' as string,
			bendWaypointIndex: -1,
			// Label dragging state
			isDraggingLabel: false,
			draggingLabelType: '' as 'element' | 'connection' | '',
			draggingLabelId: '' as string,
			labelDragStartX: 0,
			labelDragStartY: 0,
			labelOriginalOffsetX: 0,
			labelOriginalOffsetY: 0,
			// Label resizing state
			isResizingLabel: false,
			resizingLabelType: '' as 'element' | 'connection' | '',
			resizingLabelId: '' as string,
			labelResizeStartX: 0,
			labelOriginalWidth: 0,
			// Element resizing state (for expanded sub-process)
			isResizing: false,
			resizingElement: null as BpmnElement | null,
			resizeStartX: 0,
			resizeStartY: 0,
			resizeStartWidth: 0,
			resizeStartHeight: 0,
			// Lane resizing state
			laneResizing: null as { element: BpmnElement; startY: number; startHeight: number } | null,
			// Properties panel resizing state
			propertiesPanelWidth: 280,
			propertiesPanelResizing: null as { startX: number; startWidth: number } | null,
			// Command manager
			commandManager: new CommandManager(),
			// Undo/Redo guard flag - prevents watch from creating commands during undo/redo
			isUndoing: false,
			// Property snapshot for undo - stores the state before property panel changes
			lastPropertySnapshot: null as Record<string, unknown> | null,
			lastPropertySnapshotId: '' as string,
			lastPropertySnapshotIsFlow: false,
			// Close confirmation dialog
			closeConfirmDialog: {
				visible: false,
				resolve: null as null | ((result: 'save' | 'discard' | 'cancel') => void),
			},
			// Property panel tab state
			propertyTab: 'general' as 'general' | 'forms' | 'io' | 'listeners' | 'extensions',
			// Active handle for the type-change popup (rendered in shell
			// realm via instance.popup). Stored to dismiss on selection change.
			typeChangePopupHandle: null as PopupHandle | null,
			// Save As dialog state
			saveAsDialog: {
				visible: false,
				fileName: '',
			},
			saveAsToken: '',
			saveAsChannel: null as BroadcastChannel | null,
		};
	},
	computed: {
		viewportTransform(): string {
			return `translate(${this.panX}, ${this.panY}) scale(${this.zoom})`;
		},
		hasSelection(): boolean {
			return this.selectedIds.length > 0;
		},
		selectedElement(): BpmnElement | BpmnConnection | null {
			// Reference storeVersion to trigger reactivity when store changes (e.g., after undo/redo)
			void this.storeVersion;
			if (this.selectedIds.length !== 1) return null;
			const id = this.selectedIds[0];
			return this.getElementById(id) || this.getConnectionById(id) || null;
		},
		// Get selected connection (returns null if selection is not a single connection)
		selectedConnection(): BpmnConnection | null {
			// Reference storeVersion so this computed re-evaluates when waypoints
			// or other edge state changes (e.g. bend drag or reconnect).
			void this.storeVersion;
			if (this.selectedIds.length !== 1) return null;
			const id = this.selectedIds[0];
			return this.getConnectionById(id);
		},
		canUndo(): boolean {
			// Reference storeVersion to trigger reactivity when commands change
			void this.storeVersion;
			return this.commandManager.canUndo;
		},
		canRedo(): boolean {
			// Reference storeVersion to trigger reactivity when commands change
			void this.storeVersion;
			return this.commandManager.canRedo;
		},
		typeChangeOptions(): SubTypeOption[] {
			const element = this.selectedElement as BpmnElement | null;
			if (!element || !element.type) return [];
			return ELEMENT_SUBTYPES[element.type] || [];
		},
		currentSubType(): string {
			const element = this.selectedElement as BpmnElement | null;
			if (!element) return 'none';
			return getElementSubType(element);
		},
		canChangeType(): boolean {
			const element = this.selectedElement as BpmnElement | null;
			if (!element || !element.type) return false;
			return supportsTypeChange(element.type);
		},
		// Process-level properties accessor for the empty-canvas property
		// panel. Wraps modelData with id/name aliases so v-model bindings
		// (model.id, model.name, model.isExecutable, model.candidateStarter*)
		// in index.html read and write directly to the store's modelData.
		model(): {
			id: string; name: string; isExecutable: boolean;
			candidateStarterGroups: string; candidateStarterUsers: string;
		} {
			void this.storeVersion;
			const md = bpmnModelStore.getModelData();
			return {
				get id() { return md.processId; },
				set id(v: string) { md.processId = v; },
				get name() { return md.processName; },
				set name(v: string) { md.processName = v; },
				get isExecutable() { return md.isExecutable; },
				set isExecutable(v: boolean) { md.isExecutable = v; },
				get candidateStarterGroups() { return md.candidateStarterGroups ?? ''; },
				set candidateStarterGroups(v: string) { md.candidateStarterGroups = v; },
				get candidateStarterUsers() { return md.candidateStarterUsers ?? ''; },
				set candidateStarterUsers(v: string) { md.candidateStarterUsers = v; },
			};
		},
		// Get existing message definitions from model
		existingMessages(): { id: string; name: string }[] {
			void this.storeVersion;
			return bpmnModelStore.getModelData().messages;
		},
		// Get existing signal definitions from model
		existingSignals(): { id: string; name: string }[] {
			void this.storeVersion;
			return bpmnModelStore.getModelData().signals;
		},
		// Get existing error definitions from model
		existingErrors(): { id: string; name: string; code: string }[] {
			void this.storeVersion;
			return bpmnModelStore.getModelData().errors.map(e => ({
				id: e.id,
				name: e.name,
				code: e.code || ''
			}));
		},
		// Get existing escalation definitions from model
		existingEscalations(): { id: string; name: string; code: string }[] {
			void this.storeVersion;
			return bpmnModelStore.getModelData().escalations.map(e => ({
				id: e.id,
				name: e.name,
				code: e.code || ''
			}));
		},
		// Get compensatable tasks (tasks that can have compensation handlers)
		compensatableTasks(): BpmnElement[] {
			const elements = this.getElementsForRendering();
			return elements.filter((el: BpmnElement) =>
				el.type === 'userTask' ||
				el.type === 'serviceTask' ||
				el.type === 'scriptTask' ||
				el.type === 'manualTask' ||
				el.type === 'sendTask' ||
				el.type === 'receiveTask' ||
				el.type === 'businessRuleTask' ||
				el.type === 'callActivity' ||
				el.type === 'task'
			);
		},
		// Get root level elements (elements not nested inside a subProcess).
		// Pool-parented elements still render at root level with absolute
		// coordinates; parentId on a pool is only used for serialization
		// ownership, not for SVG nesting.
		rootElements(): BpmnElement[] {
			// Reference storeVersion to trigger Vue reactivity when store changes
			void this.storeVersion;
			const elements = this.getElementsForRendering();
			const subProcessIds = new Set(
				elements
					.filter((el: BpmnElement) => el.type === 'subProcess')
					.map((el: BpmnElement) => el.id)
			);
			return elements.filter((el: BpmnElement) =>
				el.type !== 'pool' &&
				el.type !== 'lane' &&
				!(el.parentId && subProcessIds.has(el.parentId))
			);
		},
		// Pool elements for the canvas pool layer. ichigo.js's static
		// dependency analyzer only follows `this.xxx` references, so an
		// inline `getElementsForRendering().filter(...)` in the template
		// would never re-evaluate after a file load. Wrap it in a computed
		// that touches `this.storeVersion` so the v-for re-runs when the
		// store is replaced.
		poolElements(): BpmnElement[] {
			void this.storeVersion;
			return this.getElementsForRendering().filter((el: BpmnElement) => el.type === 'pool');
		},
		laneElements(): BpmnElement[] {
			void this.storeVersion;
			return this.getElementsForRendering().filter((el: BpmnElement) => el.type === 'lane');
		},
		// Get root level connections (connections between root level elements).
		// Message Flows are excluded — they render in their own top layer so the
		// hollow source-circle marker draws above task shapes.
		rootConnections(): BpmnConnection[] {
			// Reference storeVersion to trigger Vue reactivity
			void this.storeVersion;
			const rootElementIds = new Set(this.rootElements.map((el: BpmnElement) => el.id));
			const connections = this.getConnectionsForRendering();
			return connections.filter((conn: BpmnConnection) =>
				rootElementIds.has(conn.sourceRef) &&
				rootElementIds.has(conn.targetRef) &&
				!this.isMessageFlowConnection(conn)
			);
		},
		// Get all child connections (connections where at least one endpoint
		// is inside a subProcess). Pool-parented endpoints are root-level for
		// rendering, so they don't qualify here. Message Flows are excluded —
		// see messageFlowConnections.
		childConnections(): BpmnConnection[] {
			// Reference storeVersion to trigger Vue reactivity
			void this.storeVersion;
			const elements = this.getElementsForRendering();
			const connections = this.getConnectionsForRendering();
			const subProcessIds = new Set(
				elements
					.filter((el: BpmnElement) => el.type === 'subProcess')
					.map((el: BpmnElement) => el.id)
			);
			const childElementIds = new Set(
				elements
					.filter((el: BpmnElement) => el.parentId && subProcessIds.has(el.parentId))
					.map((el: BpmnElement) => el.id)
			);
			return connections.filter((conn: BpmnConnection) =>
				(childElementIds.has(conn.sourceRef) || childElementIds.has(conn.targetRef)) &&
				!this.isMessageFlowConnection(conn)
			);
		},
		// Message Flow connections render in a dedicated top layer so their
		// hollow source-circle marker is visible above the source task shape.
		messageFlowConnections(): BpmnConnection[] {
			void this.storeVersion;
			return this.getConnectionsForRendering().filter((conn: BpmnConnection) =>
				this.isMessageFlowConnection(conn)
			);
		},
		// Status bar counters.
		// ichigo.js's static dependency analyzer only tracks `this.xxx`
		// MemberExpressions, not aliased forms like `vm.xxx` (after
		// `const vm = this`). The underlying getElementsForRendering /
		// getConnectionsForRendering helpers reference `vm.storeVersion`
		// internally, so calling them straight from the template won't
		// register a dependency on storeVersion. These computeds touch
		// `this.storeVersion` directly so the analyzer wires the count up
		// to storeVersion bumps (which is the only signal fired when a
		// connection is added/removed).
		statusElementsCount(): number {
			void this.storeVersion;
			return this.getElementsForRendering().length;
		},
		statusConnectionsCount(): number {
			void this.storeVersion;
			return this.getConnectionsForRendering().length;
		},
		// Subtitle pushed to the shell for the Dock hover preview.
		dockSubtitle(): string {
			const f = (this as any).files[(this as any).currentFileIndex];
			return f?.name || '';
		},
	},
	watch: {
		dockSubtitle(val: string) {
			(this as any).instance?.setDisplayInfo({ subtitle: val });
		},
		/**
		 * Watch for selection changes to capture property snapshot.
		 * This is called when selection changes (before deep watch triggers).
		 */
		selectedIds: {
			handler(newVal: string[], _oldVal: string[]) {
				const vm = this as any;

				// Reset propertyTab to 'general' if the active tab is not visible for
				// the new selection — e.g. switching from a userTask (Forms tab) to a
				// sequence flow leaves no active tab and the Forms content lingers.
				if (vm.propertyTab === 'forms' || vm.propertyTab === 'io') {
					const el = newVal.length === 1
						? (vm.getElementById(newVal[0]) || vm.getConnectionById(newVal[0]))
						: null;
					const type = el && 'type' in el ? (el as BpmnElement).type : '';
					const formsVisible = type === 'startEvent' || type === 'userTask';
					const ioVisible = type === 'endEvent' || type === 'serviceTask';
					if ((vm.propertyTab === 'forms' && !formsVisible) ||
						(vm.propertyTab === 'io' && !ioVisible)) {
						vm.propertyTab = 'general';
					}
				}

				// Capture snapshot when selecting a new element
				if (newVal.length === 1) {
					const id = newVal[0];
					// Set flag to ignore the first selectedElement watch trigger after selection change
					vm.isUndoing = true;
					vm.capturePropertySnapshot(id);
					// Reset flag after Vue processes the selectedElement change
					vm.$nextTick(() => {
						vm.isUndoing = false;
					});
				} else {
					// Clear snapshot when deselecting or multi-selecting
					vm.lastPropertySnapshot = null;
					vm.lastPropertySnapshotId = '';
				}
			}
		},
		/**
		 * Watch for changes in the selected element (deep watch).
		 * Automatically syncs UI changes (v-model) back to the Store.
		 */
		selectedElement: {
			deep: true,
			handler(newVal: BpmnElement | BpmnConnection | null, oldVal: BpmnElement | BpmnConnection | null) {
				const vm = this as any;

				// 1. Basic checks
				if (!newVal) return;

				// 2. Skip if undo/redo is in progress (prevent infinite loop)
				if (vm.isUndoing) return;

				// 3. Skip on selection change (ID change) - only detect property changes
				if (oldVal && newVal.id !== oldVal.id) {
					return;
				}

				// 4. Determine if it's an element or connection
				const isConnection = 'sourceRef' in newVal;

				// 5. Build new data object for Store update
				let newData: Record<string, unknown>;

				if (isConnection) {
					const conn = newVal as BpmnConnection;
					newData = {
						name: conn.name,
						conditionType: conn.conditionType,
						conditionExpression: conn.conditionExpression,
						conditionScript: conn.conditionScript,
						conditionScriptFormat: conn.conditionScriptFormat,
					};
					// Update flow in Store
					bpmnModelStore.updateFlow(conn.id, newData as Partial<BpmnFlowSemantic>);
				} else {
					// Update semantic in Store
					const el = newVal as BpmnElement;
					newData = {
						name: el.name,
						subType: el.subType,
						// Event attributes
						catching: el.catching,
						attachedToRef: el.attachedToRef,
						cancelActivity: el.cancelActivity,
						// Timer
						timerType: el.timerType,
						timerValue: el.timerValue,
						// Message
						messageRef: el.messageRef,
						messageName: el.messageName,
						// Signal
						signalRef: el.signalRef,
						signalName: el.signalName,
						// Error
						errorRef: el.errorRef,
						errorName: el.errorName,
						errorCode: el.errorCode,
						// Escalation
						escalationRef: el.escalationRef,
						escalationName: el.escalationName,
						escalationCode: el.escalationCode,
						// Link
						linkName: el.linkName,
						// Condition
						conditionType: el.conditionType,
						conditionExpression: el.conditionExpression,
						conditionScript: el.conditionScript,
						conditionScriptFormat: el.conditionScriptFormat,
						conditionScriptType: el.conditionScriptType,
						conditionScriptResource: el.conditionScriptResource,
						conditionVariableName: el.conditionVariableName,
						conditionVariableEvents: el.conditionVariableEvents,
						// Compensation
						compensationActivityRef: el.compensationActivityRef,
						compensationWaitForCompletion: el.compensationWaitForCompletion,
						// Task implementation
						implementation: el.implementation,
						implementationType: el.implementationType,
						javaClass: el.javaClass,
						expression: el.expression,
						resultVariable: el.resultVariable,
						delegateExpression: el.delegateExpression,
						topic: el.topic,
						priority: el.priority,
						// User task
						assignee: el.assignee,
						candidateUsers: el.candidateUsers,
						candidateGroups: el.candidateGroups,
						formKey: el.formKey,
						initiator: el.initiator,
						// Script task
						scriptFormat: el.scriptFormat,
						script: el.script,
						// Business rule task
						decisionRef: el.decisionRef,
						decisionRefBinding: el.decisionRefBinding,
						decisionRefVersion: el.decisionRefVersion,
						decisionRefVersionTag: el.decisionRefVersionTag,
						decisionRefTenantId: el.decisionRefTenantId,
						mapDecisionResult: el.mapDecisionResult,
						// Call activity
						calledElement: el.calledElement,
						calledElementBinding: el.calledElementBinding,
						calledElementVersion: el.calledElementVersion,
						calledElementVersionTag: el.calledElementVersionTag,
						calledElementTenantId: el.calledElementTenantId,
						businessKey: el.businessKey,
						inMappings: el.inMappings,
						outMappings: el.outMappings,
						// Gateway
						instantiate: el.instantiate,
						eventGatewayType: el.eventGatewayType,
						activationCondition: el.activationCondition,
						// SubProcess
						triggeredByEvent: el.triggeredByEvent,
						loopType: el.loopType,
						collection: el.collection,
						elementVariable: el.elementVariable,
						// Async
						asyncBefore: el.asyncBefore,
						asyncAfter: el.asyncAfter,
						exclusive: el.exclusive,
						jobPriority: el.jobPriority,
						failedJobRetryTimeCycle: el.failedJobRetryTimeCycle,
						retryTimeCycle: el.retryTimeCycle,
						// Documentation
						documentation: el.documentation,
						// Extensions
						inputParameters: el.inputParameters,
						outputParameters: el.outputParameters,
						executionListeners: el.executionListeners,
						taskListeners: el.taskListeners,
						extensionProperties: el.extensionProperties,
						// Data
						isCollection: el.isCollection,
						dataState: el.dataState,
						dataObjectRef: el.dataObjectRef,
						dataStoreRef: el.dataStoreRef,
						// Pool/Lane
						processRef: el.processRef,
						isHorizontal: el.isHorizontal,
						parentPoolId: el.parentPoolId,
						flowNodeRefs: el.flowNodeRefs,
						candidateStarterGroups: el.candidateStarterGroups,
						candidateStarterUsers: el.candidateStarterUsers,
					};
					bpmnModelStore.updateSemantic(el.id, newData as Partial<BpmnSemantic>);

					// Also update shape if bounds-related properties changed
					const shapes = bpmnModelStore.getShapesForSemantic(el.id);
					if (shapes[0]) {
						bpmnModelStore.updateShape(shapes[0].id, {
							isExpanded: el.isExpanded,
							isHorizontal: el.isHorizontal,
						});
					}
				}

				// 6. Create undo command if snapshot exists and data actually changed
				if (vm.lastPropertySnapshot && vm.lastPropertySnapshotId === newVal.id) {
					// Compare snapshot with new data to check if there's an actual change
					const snapshotJson = JSON.stringify(vm.lastPropertySnapshot);
					const newDataJson = JSON.stringify(newData);

					if (snapshotJson !== newDataJson) {
						const cmd = new UpdatePropertyCommand(
							bpmnModelStore,
							newVal.id,
							vm.lastPropertySnapshot,
							newData,
							isConnection,
							() => {
								// storeVersion is incremented in undo()/redo() methods
							}
						);
						// Add command without executing (already applied above)
						vm.commandManager.addCommand(cmd);

						// Update snapshot to current state for next change
						vm.capturePropertySnapshot(newVal.id);

						// Mark as modified
						vm.markModified();
					}
				}

				// 7. Trigger re-render
				vm.storeVersion++;
			}
		},
	},
	methods: {
		// =====================================================================
		// Store-based Rendering Helpers (for gradual migration)
		// =====================================================================

		/**
		 * Get elements for rendering (unified interface for both legacy and store modes)
		 * Returns array of elements with flattened structure for drawing
		 */
		getElementsForRendering(): BpmnElement[] {
			return bpmnModelStore.getShapesForRendering().map(({ shape, semantic }) => ({
					// Core identification
					id: semantic.id,
					type: semantic.type,
					subType: semantic.subType,
					name: semantic.name || '',
					// Position from DI layer
					x: shape.bounds.x,
					y: shape.bounds.y,
					width: shape.bounds.width,
					height: shape.bounds.height,
					// Label info
					labelOffsetX: shape.label?.offsetX,
					labelOffsetY: shape.label?.offsetY,
					labelWidth: shape.label?.width,
					// DI attributes
					isExpanded: shape.isExpanded,
					isHorizontal: shape.isHorizontal ?? semantic.isHorizontal,
					// Hierarchy
					parentId: semantic.parentId,
					// Event attributes
					catching: semantic.catching,
					attachedToRef: semantic.attachedToRef,
					cancelActivity: semantic.cancelActivity,
					// Timer attributes
					timerType: semantic.timerType,
					timerValue: semantic.timerValue,
					// Message attributes
					messageRef: semantic.messageRef,
					messageName: semantic.messageName,
					// Signal attributes
					signalRef: semantic.signalRef,
					signalName: semantic.signalName,
					// Error attributes
					errorRef: semantic.errorRef,
					errorName: semantic.errorName,
					errorCode: semantic.errorCode,
					// Escalation attributes
					escalationRef: semantic.escalationRef,
					escalationName: semantic.escalationName,
					escalationCode: semantic.escalationCode,
					// Link attributes
					linkName: semantic.linkName,
					// Condition attributes
					conditionType: semantic.conditionType,
					conditionExpression: semantic.conditionExpression,
					conditionScript: semantic.conditionScript,
					conditionScriptFormat: semantic.conditionScriptFormat,
					conditionScriptType: semantic.conditionScriptType,
					conditionScriptResource: semantic.conditionScriptResource,
					conditionVariableName: semantic.conditionVariableName,
					conditionVariableEvents: semantic.conditionVariableEvents,
					// Compensation attributes
					compensationActivityRef: semantic.compensationActivityRef,
					compensationWaitForCompletion: semantic.compensationWaitForCompletion,
					// Task implementation
					implementation: semantic.implementation,
					implementationType: semantic.implementationType,
					javaClass: semantic.javaClass,
					expression: semantic.expression,
					resultVariable: semantic.resultVariable,
					delegateExpression: semantic.delegateExpression,
					topic: semantic.topic,
					priority: semantic.priority,
					// User task
					assignee: semantic.assignee,
					candidateUsers: semantic.candidateUsers,
					candidateGroups: semantic.candidateGroups,
					formKey: semantic.formKey,
					// Script task
					scriptFormat: semantic.scriptFormat,
					script: semantic.script,
					// Business rule task (DMN)
					decisionRef: semantic.decisionRef,
					decisionRefBinding: semantic.decisionRefBinding,
					decisionRefVersion: semantic.decisionRefVersion,
					decisionRefVersionTag: semantic.decisionRefVersionTag,
					decisionRefTenantId: semantic.decisionRefTenantId,
					mapDecisionResult: semantic.mapDecisionResult,
					// Call activity
					calledElement: semantic.calledElement,
					calledElementBinding: semantic.calledElementBinding,
					calledElementVersion: semantic.calledElementVersion,
					calledElementVersionTag: semantic.calledElementVersionTag,
					calledElementTenantId: semantic.calledElementTenantId,
					businessKey: semantic.businessKey,
					inMappings: semantic.inMappings,
					outMappings: semantic.outMappings,
					// Gateway attributes
					defaultFlow: semantic.defaultFlow,
					instantiate: semantic.instantiate,
					eventGatewayType: semantic.eventGatewayType,
					activationCondition: semantic.activationCondition,
					// SubProcess
					triggeredByEvent: semantic.triggeredByEvent,
					loopType: semantic.loopType,
					collection: semantic.collection,
					elementVariable: semantic.elementVariable,
					// Async/Job
					asyncBefore: semantic.asyncBefore,
					asyncAfter: semantic.asyncAfter,
					exclusive: semantic.exclusive,
					jobPriority: semantic.jobPriority,
					failedJobRetryTimeCycle: semantic.failedJobRetryTimeCycle,
					// Documentation
					documentation: semantic.documentation,
					// Extensions
					inputParameters: semantic.inputParameters,
					outputParameters: semantic.outputParameters,
					executionListeners: semantic.executionListeners,
					taskListeners: semantic.taskListeners,
					extensionProperties: semantic.extensionProperties,
					// Data
					isCollection: semantic.isCollection,
					dataState: semantic.dataState,
					dataObjectRef: semantic.dataObjectRef,
					dataStoreRef: semantic.dataStoreRef,
					// Pool/Lane
					processRef: semantic.processRef,
					parentPoolId: semantic.parentPoolId,
					flowNodeRefs: semantic.flowNodeRefs,
					candidateStarterGroups: semantic.candidateStarterGroups,
					candidateStarterUsers: semantic.candidateStarterUsers,
				} as BpmnElement));
		},

		/**
		 * Get connections for rendering, filtered to hide flows whose endpoints
		 * both live inside a collapsed sub-process.
		 */
		getConnectionsForRendering(): BpmnConnection[] {
			const vm = this;

			// Reference storeVersion to trigger Vue reactivity when store/subprocess state changes
			void vm.storeVersion;

			// Get IDs of elements inside collapsed subprocesses
			const collapsedChildIds = new Set<string>();
			const allSemantics = bpmnModelStore.getAllSemantics();
			const collapsedSubProcessIds = allSemantics
				.filter(s => s.type === 'subProcess')
				.filter(s => {
					const shapes = bpmnModelStore.getShapesForSemantic(s.id);
					const shape = shapes[0];
					return s.subType !== 'expanded' && !shape?.isExpanded;
				})
				.map(s => s.id);
			for (const subProcessId of collapsedSubProcessIds) {
				const children = bpmnModelStore.getChildren(subProcessId);
				for (const child of children) {
					collapsedChildIds.add(child.id);
				}
			}

			return bpmnModelStore.getEdgesForRendering()
				.filter(({ flow }) => {
					const sourceInCollapsed = collapsedChildIds.has(flow.sourceRef);
					const targetInCollapsed = collapsedChildIds.has(flow.targetRef);
					return !(sourceInCollapsed && targetInCollapsed);
				})
				.map(({ edge, flow }) => ({
					id: flow.id,
					sourceRef: flow.sourceRef,
					targetRef: flow.targetRef,
					name: flow.name || '',
					waypoints: edge.waypoints,
					labelOffsetX: edge.label?.offsetX,
					labelOffsetY: edge.label?.offsetY,
					labelWidth: edge.label?.width,
					conditionType: flow.conditionType,
					conditionExpression: flow.conditionExpression,
					conditionScript: flow.conditionScript,
					conditionScriptFormat: flow.conditionScriptFormat,
					connectionType: flow.connectionType,
					sourceSide: edge.sourceSide,
					targetSide: edge.targetSide,
					manuallyAdjusted: edge.manuallyAdjusted,
				} as BpmnConnection));
		},

		/**
		 * Get all connections without filtering (internal use for moving child connections)
		 */
		getConnectionsForRenderingInternal(): BpmnConnection[] {
			return bpmnModelStore.getEdgesForRendering().map(({ edge, flow }) => ({
				id: flow.id,
				sourceRef: flow.sourceRef,
				targetRef: flow.targetRef,
				name: flow.name || '',
				waypoints: edge.waypoints,
				labelOffsetX: edge.label?.offsetX,
				labelOffsetY: edge.label?.offsetY,
				labelWidth: edge.label?.width,
				conditionType: flow.conditionType,
				conditionExpression: flow.conditionExpression,
				conditionScript: flow.conditionScript,
				conditionScriptFormat: flow.conditionScriptFormat,
				connectionType: flow.connectionType,
			} as BpmnConnection));
		},

		/**
		 * Get an element by ID (returns semantic data in store mode)
		 * Returns undefined if not found (e.g., when ID is for a connection/flow)
		 */
		getElementById(id: string): BpmnElement | undefined {
			const semantic = bpmnModelStore.getSemantic(id);
			if (!semantic) {
				// Not found — may be a connection ID, which is expected.
				return undefined;
			}
			const shapes = bpmnModelStore.getShapesForSemantic(id);
			const shape = shapes[0];
			if (!shape) return undefined;
			return {
				...semantic,
				x: shape.bounds.x,
				y: shape.bounds.y,
				width: shape.bounds.width,
				height: shape.bounds.height,
				labelOffsetX: shape.label?.offsetX,
				labelOffsetY: shape.label?.offsetY,
				labelWidth: shape.label?.width,
				isExpanded: shape.isExpanded,
				isHorizontal: shape.isHorizontal ?? semantic.isHorizontal,
			} as BpmnElement;
		},

		/**
		 * Get a connection by ID
		 */
		getConnectionById(id: string): BpmnConnection | undefined {
			const flow = bpmnModelStore.getFlow(id);
			if (!flow) return undefined;
			const edges = bpmnModelStore.getEdgesForFlow(id);
			const edge = edges[0];
			return {
				id: flow.id,
				sourceRef: flow.sourceRef,
				targetRef: flow.targetRef,
				name: flow.name || '',
				waypoints: edge?.waypoints,
				labelOffsetX: edge?.label?.offsetX,
				labelOffsetY: edge?.label?.offsetY,
				labelWidth: edge?.label?.width,
				conditionType: flow.conditionType,
				conditionExpression: flow.conditionExpression,
				connectionType: flow.connectionType,
				sourceSide: edge?.sourceSide,
				targetSide: edge?.targetSide,
				manuallyAdjusted: edge?.manuallyAdjusted,
			} as BpmnConnection;
		},

		/**
		 * Get element bounds by ID
		 */
		getElementBounds(id: string): Bounds | undefined {
			const shapes = bpmnModelStore.getShapesForSemantic(id);
			return shapes[0]?.bounds;
		},

		/**
		 * Update element bounds (for drag operations)
		 */
		updateElementBounds(id: string, bounds: Partial<Bounds>): void {
			const vm = this;

			let dx = 0;
			let dy = 0;
			const shapes = bpmnModelStore.getShapesForSemantic(id);
			if (shapes[0]) {
				const currentBounds = shapes[0].bounds;
				if (bounds.x !== undefined) dx = bounds.x - currentBounds.x;
				if (bounds.y !== undefined) dy = bounds.y - currentBounds.y;
				bpmnModelStore.updateShape(shapes[0].id, {
					bounds: {
						x: bounds.x ?? currentBounds.x,
						y: bounds.y ?? currentBounds.y,
						width: bounds.width ?? currentBounds.width,
						height: bounds.height ?? currentBounds.height,
					},
				});
			}

			// Update waypoints for connected flows so they follow the element.
			// Strategy:
			//   - If routing sides are known and the user has not manually
			//     adjusted bend points, regenerate waypoints from the sides so
			//     the path stays clean.
			//   - If the user has dragged a bend (manuallyAdjusted), translate
			//     just the source/target endpoint waypoint by the element delta
			//     to preserve their custom bend points.
			//   - Otherwise fall back to the legacy behaviour of clearing
			//     waypoints so the next render uses center-based auto-routing.
			bpmnModelStore.getAllFlows()
				.filter(f => f.sourceRef === id || f.targetRef === id)
				.forEach(f => {
					const edge = bpmnModelStore.getEdgesForFlow(f.id)[0];
					if (!edge) return;

					const isSource = f.sourceRef === id;
					const otherId = isSource ? f.targetRef : f.sourceRef;
					const movedEl = vm.getElementById(id);
					const otherEl = vm.getElementById(otherId);

					if (edge.sourceSide && edge.targetSide && movedEl && otherEl && !edge.manuallyAdjusted) {
						const sourceEl = isSource ? movedEl : otherEl;
						const targetEl = isSource ? otherEl : movedEl;
						const wps = vm.buildSideAwareWaypoints(sourceEl, edge.sourceSide, targetEl, edge.targetSide);
						bpmnModelStore.updateEdgeWaypoints(edge.id, wps);
						return;
					}

					if (edge.manuallyAdjusted && edge.waypoints && edge.waypoints.length >= 2 && (dx !== 0 || dy !== 0)) {
						const wps = edge.waypoints.map(p => ({ x: p.x, y: p.y }));
						if (isSource) {
							wps[0] = { x: wps[0].x + dx, y: wps[0].y + dy };
						} else {
							const lastIdx = wps.length - 1;
							wps[lastIdx] = { x: wps[lastIdx].x + dx, y: wps[lastIdx].y + dy };
						}
						bpmnModelStore.updateEdgeWaypoints(edge.id, wps);
						return;
					}

					// Legacy fallback: clear waypoints so the renderer recalculates.
					vm.updateWaypointsDual(f.id, []);
				});

			// Bump storeVersion so v-for computeds (rootElements, poolElements,
			// etc.) re-evaluate and the canvas visually follows the drag.
			vm.storeVersion++;
		},

		/**
		 * Get selected element or connection (supports both modes)
		 */
		getSelectedItem(): BpmnElement | BpmnConnection | null {
			if (this.selectedIds.length !== 1) return null;
			const id = this.selectedIds[0];
			const element = this.getElementById(id);
			if (element) return element;
			return this.getConnectionById(id) || null;
		},

		// =====================================================================
		// Store Update Helpers
		// =====================================================================

		/**
		 * Add element to the Store
		 */
		addElementDual(element: BpmnElement): void {
			const vm = this;
			// Create semantic from element
			const semantic: BpmnSemantic = {
					id: element.id,
					type: element.type,
					subType: element.subType,
					name: element.name || '',
					parentId: element.parentId,
					// Event attributes
					catching: element.catching,
					attachedToRef: element.attachedToRef,
					cancelActivity: element.cancelActivity,
					// Timer attributes
					timerType: element.timerType,
					timerValue: element.timerValue,
					// Message attributes
					messageRef: element.messageRef,
					messageName: element.messageName,
					// Signal attributes
					signalRef: element.signalRef,
					signalName: element.signalName,
					// Error attributes
					errorRef: element.errorRef,
					errorName: element.errorName,
					errorCode: element.errorCode,
					// Escalation attributes
					escalationRef: element.escalationRef,
					escalationName: element.escalationName,
					escalationCode: element.escalationCode,
					// Link attributes
					linkName: element.linkName,
					// Condition attributes
					conditionType: element.conditionType,
					conditionExpression: element.conditionExpression,
					conditionScript: element.conditionScript,
					conditionScriptFormat: element.conditionScriptFormat,
					conditionScriptType: element.conditionScriptType,
					conditionScriptResource: element.conditionScriptResource,
					conditionVariableName: element.conditionVariableName,
					conditionVariableEvents: element.conditionVariableEvents,
					// Compensation
					compensationActivityRef: element.compensationActivityRef,
					compensationWaitForCompletion: element.compensationWaitForCompletion,
					// Task implementation
					implementation: element.implementation,
					implementationType: element.implementationType,
					javaClass: element.javaClass,
					expression: element.expression,
					resultVariable: element.resultVariable,
					delegateExpression: element.delegateExpression,
					topic: element.topic,
					priority: element.priority,
					// User task
					assignee: element.assignee,
					candidateUsers: element.candidateUsers,
					candidateGroups: element.candidateGroups,
					formKey: element.formKey,
					// Script task
					scriptFormat: element.scriptFormat,
					script: element.script,
					// Business rule task
					decisionRef: element.decisionRef,
					decisionRefBinding: element.decisionRefBinding,
					decisionRefVersion: element.decisionRefVersion,
					decisionRefVersionTag: element.decisionRefVersionTag,
					decisionRefTenantId: element.decisionRefTenantId,
					mapDecisionResult: element.mapDecisionResult,
					// Call activity
					calledElement: element.calledElement,
					calledElementBinding: element.calledElementBinding,
					calledElementVersion: element.calledElementVersion,
					calledElementVersionTag: element.calledElementVersionTag,
					calledElementTenantId: element.calledElementTenantId,
					businessKey: element.businessKey,
					inMappings: element.inMappings,
					outMappings: element.outMappings,
					// Gateway
					instantiate: element.instantiate,
					eventGatewayType: element.eventGatewayType,
					activationCondition: element.activationCondition,
					// SubProcess
					triggeredByEvent: element.triggeredByEvent,
					loopType: element.loopType,
					collection: element.collection,
					elementVariable: element.elementVariable,
					// Async
					asyncBefore: element.asyncBefore,
					asyncAfter: element.asyncAfter,
					exclusive: element.exclusive,
					jobPriority: element.jobPriority,
					failedJobRetryTimeCycle: element.failedJobRetryTimeCycle,
					// Documentation
					documentation: element.documentation,
					// Extensions
					inputParameters: element.inputParameters as StoreInputOutputParameter[] | undefined,
					outputParameters: element.outputParameters as StoreInputOutputParameter[] | undefined,
					executionListeners: element.executionListeners as StoreExecutionListener[] | undefined,
					taskListeners: element.taskListeners as StoreTaskListener[] | undefined,
					extensionProperties: element.extensionProperties,
					// Data
					isCollection: element.isCollection,
					dataState: element.dataState,
					dataObjectRef: element.dataObjectRef,
					dataStoreRef: element.dataStoreRef,
					// Pool/Lane
					processRef: element.processRef,
					isHorizontal: element.isHorizontal,
					parentPoolId: element.parentPoolId,
					flowNodeRefs: element.flowNodeRefs,
					candidateStarterGroups: element.candidateStarterGroups,
					candidateStarterUsers: element.candidateStarterUsers,
				};
				bpmnModelStore.addSemantic(semantic);

				// Create shape
				const shape: BpmnDiShape = {
					id: generateUUID(),
					bpmnElement: element.id,
					bounds: {
						x: element.x,
						y: element.y,
						width: element.width,
						height: element.height,
					},
					isExpanded: element.isExpanded,
					isHorizontal: element.isHorizontal,
				};
				if (element.labelOffsetX !== undefined || element.labelOffsetY !== undefined) {
					shape.label = {
						offsetX: element.labelOffsetX,
						offsetY: element.labelOffsetY,
						width: element.labelWidth,
					};
				}
			bpmnModelStore.addShape(shape);
			vm.storeVersion++;
		},

		/**
		 * Remove element. Cascade-deletes its shape and any connected flows.
		 */
		removeElementDual(id: string): void {
			const vm = this;
			bpmnModelStore.removeSemantic(id);
			vm.storeVersion++;
		},

		/**
		 * Add connection (flow + edge) to the store.
		 */
		addConnectionDual(connection: BpmnConnection): void {
			const vm = this;
			const flow: BpmnFlowSemantic = {
				id: connection.id,
				sourceRef: connection.sourceRef,
				targetRef: connection.targetRef,
				name: connection.name,
				conditionType: connection.conditionType,
				conditionExpression: connection.conditionExpression,
				conditionScript: connection.conditionScript,
				conditionScriptFormat: connection.conditionScriptFormat,
				connectionType: connection.connectionType,
			};
			bpmnModelStore.addFlow(flow);

			const edge: BpmnDiEdge = {
				id: generateUUID(),
				bpmnElement: connection.id,
				waypoints: connection.waypoints || [],
			};
			if (connection.sourceSide) edge.sourceSide = connection.sourceSide;
			if (connection.targetSide) edge.targetSide = connection.targetSide;
			if (connection.manuallyAdjusted) edge.manuallyAdjusted = connection.manuallyAdjusted;
			if (connection.labelOffsetX !== undefined || connection.labelOffsetY !== undefined) {
				edge.label = {
					offsetX: connection.labelOffsetX,
					offsetY: connection.labelOffsetY,
					width: connection.labelWidth,
				};
			}
			bpmnModelStore.addEdge(edge);
			vm.storeVersion++;
		},

		/**
		 * Remove connection from the store. Cascade-deletes its edge.
		 */
		removeConnectionDual(id: string): void {
			const vm = this;
			bpmnModelStore.removeFlow(id);
			vm.storeVersion++;
		},

		/**
		 * Update semantic properties (for property panel changes)
		 */
		updateSemanticDual(id: string, changes: Partial<BpmnSemantic>): void {
			bpmnModelStore.updateSemantic(id, changes);
		},

		/**
		 * Update flow properties (for connection property changes)
		 */
		updateFlowDual(id: string, changes: Partial<BpmnFlowSemantic>): void {
			bpmnModelStore.updateFlow(id, changes);
		},

		/**
		 * Update connection waypoints
		 */
		updateWaypointsDual(connectionId: string, waypoints: Point[]): void {
			const vm = this;
			const edges = bpmnModelStore.getEdgesForFlow(connectionId);
			if (edges[0]) {
				bpmnModelStore.updateEdgeWaypoints(edges[0].id, waypoints);
			}
			vm.storeVersion++;
		},

		/**
		 * Update element label offset
		 */
		updateElementLabelDual(id: string, offsetX: number, offsetY: number, width?: number): void {
			const vm = this;
			const shapes = bpmnModelStore.getShapesForSemantic(id);
			if (shapes[0]) {
				bpmnModelStore.updateShape(shapes[0].id, {
					label: { offsetX, offsetY, width },
				});
			}
			vm.storeVersion++;
		},

		/**
		 * Update connection label offset
		 */
		updateConnectionLabelDual(id: string, offsetX: number, offsetY: number, width?: number): void {
			const vm = this;
			const edges = bpmnModelStore.getEdgesForFlow(id);
			if (edges[0]) {
				bpmnModelStore.updateEdge(edges[0].id, {
					label: { offsetX, offsetY, width },
				});
			}
			vm.storeVersion++;
		},

		/**
		 * Update connection waypoints (used when dragging a sub-process moves
		 * its child flows along with it).
		 */
		updateConnectionWaypoints(id: string, waypoints: Array<{ x: number; y: number }>): void {
			const vm = this;
			const edges = bpmnModelStore.getEdgesForFlow(id);
			if (edges[0]) {
				bpmnModelStore.updateEdge(edges[0].id, { waypoints });
			}
			vm.storeVersion++;
		},

		// =====================================================================
		// Legacy Methods (kept for compatibility)
		// =====================================================================

		isSelected(id: string): boolean {
			return this.selectedIds.indexOf(id) !== -1;
		},

		/**
		 * Get child elements of a subprocess
		 */
		getChildElements(parentId: string): BpmnElement[] {
			void this.storeVersion;
			const children = bpmnModelStore.getChildren(parentId);
			return children.map((semantic: BpmnSemantic) => {
				const shapes = bpmnModelStore.getShapesForSemantic(semantic.id);
				const shape = shapes[0];
				return {
					id: semantic.id,
					type: semantic.type,
					subType: semantic.subType,
					name: semantic.name || '',
					x: shape?.bounds.x ?? 0,
					y: shape?.bounds.y ?? 0,
					width: shape?.bounds.width ?? 100,
					height: shape?.bounds.height ?? 80,
					parentId: semantic.parentId,
					labelOffsetX: shape?.label?.offsetX,
					labelOffsetY: shape?.label?.offsetY,
					labelWidth: shape?.label?.width,
					isExpanded: shape?.isExpanded,
					catching: semantic.catching,
				} as BpmnElement;
			});
		},

		/**
		 * Get connections within a subprocess (both source and target must be in the subprocess)
		 */
		getChildConnections(parentId: string): BpmnConnection[] {
			void this.storeVersion;
			const children = bpmnModelStore.getChildren(parentId);
			const childElementIds = new Set(children.map((s: BpmnSemantic) => s.id));
			return this.getConnectionsForRendering().filter((conn: BpmnConnection) =>
				childElementIds.has(conn.sourceRef) && childElementIds.has(conn.targetRef)
			);
		},

		/**
		 * Get the connection path for a child connection inside a subprocess
		 * Coordinates are relative to the parent subprocess origin
		 */
		getChildConnectionPath(conn: BpmnConnection, parentId: string): string {
			// See getConnectionPath. Required even though we delegate to
			// getConnectionPath: ichigo's analyzer doesn't transitively pick up
			// dependencies from `vm.xxx` calls, so this binding needs its own tag.
			void this.storeVersion;
			const vm = this;
			const parent = vm.getElementById(parentId);
			if (!parent) return '';

			// Get the regular path (absolute coordinates)
			const path = vm.getConnectionPath(conn);
			if (!path) return '';

			// Offset all coordinates by the parent's position
			// Parse the SVG path and transform coordinates
			const offsetX = parent.x;
			const offsetY = parent.y;

			// Transform path coordinates (M, L commands)
			return path.replace(/([ML])\s*([\d.-]+)\s+([\d.-]+)/g, (_match: string, cmd: string, x: string, y: string) => {
				const newX = parseFloat(x) - offsetX;
				const newY = parseFloat(y) - offsetY;
				return `${cmd} ${newX} ${newY}`;
			});
		},

		/**
		 * Get the connection label position for a child connection inside a subprocess
		 */
		getChildConnectionLabelPosition(conn: BpmnConnection, parentId: string): { x: number; y: number } {
			// See getConnectionPath: tags this method as storeVersion-dependent.
			void this.storeVersion;
			const vm = this;
			const parent = vm.getElementsForRendering().find((e: BpmnElement) => e.id === parentId);
			if (!parent) return { x: 0, y: 0 };

			const pos = vm.getConnectionLabelPosition(conn);
			return {
				x: pos.x - parent.x,
				y: pos.y - parent.y,
			};
		},

		async onMounted() {
			const vm = this;

			// Set up Save As response channel
			vm.saveAsChannel = new BroadcastChannel('webtop-save-as');
			vm.saveAsChannel.onmessage = (event: MessageEvent) => {
				if (event.data?.type === 'save-as-complete' && event.data.saveAsToken && event.data.saveAsToken === vm.saveAsToken) {
					vm.currentFile.path = event.data.path;
					vm.currentFile.name = event.data.name;
					vm.currentFile.mimeType = event.data.mimeType;
					vm.isModified = false;
					// Update file object after Save As
					if (vm.currentFileIndex >= 0 && vm.currentFileIndex < vm.files.length) {
						vm.files[vm.currentFileIndex].path = event.data.path;
						vm.files[vm.currentFileIndex].name = event.data.name;
						vm.files[vm.currentFileIndex].mimeType = event.data.mimeType;
						vm.files[vm.currentFileIndex].isModified = false;
						vm.files.splice(vm.currentFileIndex, 1, vm.files[vm.currentFileIndex]);
					}
					if (vm.instance) {
						vm.instance.windowTitle = event.data.name;
					}
					vm.saveAsToken = '';
				}
			};

			// Register message listener
			vm.messageListener = async (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
					return;
				}
			};
			window.addEventListener('message', vm.messageListener);

			// Register keyboard shortcuts
			document.addEventListener('keydown', (e: KeyboardEvent) => {
				// Space: hold to enable canvas panning (alongside Alt+drag).
				// Skip while typing in a form field so Space still inserts a
				// space character there. preventDefault stops the page-scroll
				// fallback when the canvas/document is the focus owner.
				if (e.key === ' ' || e.code === 'Space') {
					const tag = document.activeElement?.tagName;
					if (tag === 'INPUT' || tag === 'TEXTAREA') return;
					e.preventDefault();
					if (!e.repeat) vm.isSpaceHeld = true;
					return;
				}
				if ((e.ctrlKey || e.metaKey) && e.key === 's') {
					e.preventDefault();
					if (!vm.isSaving) {
						vm.saveFile();
					}
				}
				if ((e.ctrlKey || e.metaKey) && e.key === 'z') {
					e.preventDefault();
					vm.undo();
				}
				if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || (e.shiftKey && e.key === 'z'))) {
					e.preventDefault();
					vm.redo();
				}
				if (e.key === 'Delete' || e.key === 'Backspace') {
					if (vm.hasSelection && document.activeElement?.tagName !== 'INPUT' && document.activeElement?.tagName !== 'TEXTAREA') {
						e.preventDefault();
						vm.deleteSelected();
					}
				}
				if (e.key === 'Escape') {
					// Don't clear the canvas selection while the user is editing
					// in a text field (e.g. the reference combobox or property
					// inputs); local handlers handle Esc themselves.
					const tag = document.activeElement?.tagName;
					if (tag !== 'INPUT' && tag !== 'TEXTAREA') {
						vm.selectedIds = [];
					}
				}
			});
			document.addEventListener('keyup', (e: KeyboardEvent) => {
				if (e.key === ' ' || e.code === 'Space') {
					vm.isSpaceHeld = false;
				}
			});
			// Reset space-held flag if focus leaves the window mid-press
			// (e.g. user alt-tabs away while holding Space). Otherwise the
			// keyup we never received would leave the editor stuck in pan mode.
			window.addEventListener('blur', () => {
				vm.isSpaceHeld = false;
			});

			// Register appLaunch callback
			window.appLaunch = async (instance: ApplicationInstance, options?: LaunchOptions) => {
				vm.instance = vm.$markRaw(instance);
				vm.spriteUrl = new URL("assets/icons/icons.svg", window.location.origin + window.location.pathname).href
				instance.appState = () => {
					vm.saveCurrentFileState();
					const paths = vm.files.map((f: BpmnFile) => f.path).filter((p: string) => !!p);
					if (paths.length === 0) return {};
					const fileStates = vm.files
						.filter((f: BpmnFile) => !!f.path)
						.map((f: BpmnFile) => ({ path: f.path, zoom: f.zoom, panX: f.panX, panY: f.panY }));
					return { paths, activeIndex: vm.currentFileIndex, fileStates };
				};
				instance.setDisplayInfo({ subtitle: vm.dockSubtitle });

				// Apply theme
				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				// Set beforeClose callback
				instance.setBeforeCloseCallback(async () => {
					return await vm.confirmClose();
				});

				// Load file(s) from launch options
				if (options?.paths && options.paths.length > 0) {
					for (const p of options.paths) {
						await vm.loadFile(p);
					}
					if (options.fileStates) {
						for (const fs of options.fileStates) {
							const fi = vm.files.findIndex((f: BpmnFile) => f.path === fs.path);
							if (fi >= 0) {
								vm.files[fi].zoom = fs.zoom ?? 1;
								vm.files[fi].panX = fs.panX ?? 0;
								vm.files[fi].panY = fs.panY ?? 0;
							}
						}
					}
					const idx = options.activeIndex != null ? Math.min(options.activeIndex, vm.files.length - 1) : vm.files.length - 1;
					vm.selectTab(idx);
				} else if (options?.path) {
					await vm.loadFile(options.path);
				} else {
					// Create new diagram
					vm.newDiagram();
				}

				vm.$nextTick(() => {
					instance.notifyLaunched();
				});
			};
		},
		async onUnmount() {
			const vm = this;
			if (vm.messageListener) {
				window.removeEventListener('message', vm.messageListener);
			}
			if (vm.saveAsChannel) {
				vm.saveAsChannel.close();
				vm.saveAsChannel = null;
			}
		},

		// ==========================================================================
		// Multi-file Tab Management
		// ==========================================================================

		generateFileId(): string {
			return 'file_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
		},

		/**
		 * Save current component state into the current file object in files[].
		 */
		saveCurrentFileState() {
			const vm = this;
			if (vm.currentFileIndex < 0 || vm.currentFileIndex >= vm.files.length) return;
			const file = vm.files[vm.currentFileIndex];
			file.store = bpmnModelStore;
			file.originalXml = vm.originalXml;
			file.isModified = vm.isModified;
			file.commandManager = vm.commandManager;
			file.zoom = vm.zoom;
			file.panX = vm.panX;
			file.panY = vm.panY;
			file.selectedIds = vm.selectedIds;
			file.storeVersion = vm.storeVersion;
		},

		/**
		 * Restore a file's state into the component data properties.
		 */
		restoreFileState(index: number) {
			const vm = this;
			const file = vm.files[index];
			if (!file) return;
			bpmnModelStore = file.store;
			vm.originalXml = file.originalXml;
			vm.isModified = file.isModified;
			vm.commandManager = file.commandManager;
			vm.zoom = file.zoom;
			vm.panX = file.panX;
			vm.panY = file.panY;
			vm.selectedIds = file.selectedIds;
			vm.storeVersion = file.storeVersion;
			// Update file metadata on currentFile
			vm.currentFile.path = file.path;
			vm.currentFile.name = file.name;
			vm.currentFile.mimeType = file.mimeType;
			vm.currentFile.isVersionable = file.isVersionable;
			vm.currentFile.isCheckedOut = file.isCheckedOut;
			vm.currentFile.baseVersionName = file.baseVersionName;
		},

		/**
		 * Switch to the tab at the given index.
		 */
		selectTab(index: number) {
			const vm = this;
			if (index === vm.currentFileIndex) return;
			if (index < 0 || index >= vm.files.length) return;
			// Save current state
			vm.saveCurrentFileState();
			// Switch
			vm.currentFileIndex = index;
			vm.restoreFileState(index);
			// Update window title
			if (vm.instance) {
				vm.instance.windowTitle = vm.files[index].name;
			}
			// Force re-render
			vm.storeVersion++;
		},

		/**
		 * Close the file tab at the given index, prompting to save if modified.
		 */
		async closeFile(index: number) {
			const vm = this;
			if (index < 0 || index >= vm.files.length) return;

			const file = vm.files[index];
			const originalIndex = vm.currentFileIndex;

			// Save current file state before any switching
			if (vm.currentFileIndex >= 0) {
				vm.saveCurrentFileState();
			}

			if (file.isModified) {
				// Switch to the file being closed to show its content during dialog
				if (index !== vm.currentFileIndex) {
					vm.currentFileIndex = index;
					vm.restoreFileState(index);
					vm.storeVersion++;
				}
				const result = await vm.showCloseConfirmDialog();
				if (result === 'cancel') {
					// Stay on the tab being reviewed
					return;
				}
				if (result === 'save') {
					await vm.saveFile();
					if (vm.isModified) return;
				}
			}

			// Remove file
			vm.files.splice(index, 1);

			if (vm.files.length === 0) {
				vm.currentFileIndex = -1;
				vm.newDiagram();
				return;
			}

			// Determine which tab to show after closing
			let targetIndex: number;
			if (index === originalIndex) {
				targetIndex = Math.min(index, vm.files.length - 1);
			} else if (index < originalIndex) {
				targetIndex = originalIndex - 1;
			} else {
				targetIndex = originalIndex;
			}

			vm.currentFileIndex = targetIndex;
			vm.restoreFileState(targetIndex);
			if (vm.instance) {
				vm.instance.windowTitle = vm.files[targetIndex].name;
			}
			vm.storeVersion++;
		},

		// ==========================================================================
		// File Operations
		// ==========================================================================

		newDiagram() {
			const vm = this;

			// Save current file state before creating new
			if (vm.currentFileIndex >= 0) {
				vm.saveCurrentFileState();
			}

			const newStore = new BpmnModelStore();
			newStore.setModelData({
				processId: 'Process_1',
				processName: '',
				isExecutable: true,
			});

			const newFile: BpmnFile = {
				id: vm.generateFileId(),
				path: '',
				name: 'Untitled.bpmn',
				mimeType: 'application/x-bpmn',
				isVersionable: false,
				isCheckedOut: false,
				baseVersionName: '',
				store: vm.$markRaw(newStore),
				originalXml: serializeStoreToXml(newStore),
				isModified: false,
				commandManager: vm.$markRaw(new CommandManager()),
				zoom: 1,
				panX: 0,
				panY: 0,
				selectedIds: [],
				storeVersion: 0,
			};

			vm.files.push(vm.$markRaw(newFile));
			vm.currentFileIndex = vm.files.length - 1;
			vm.restoreFileState(vm.currentFileIndex);

			if (vm.instance) {
				vm.instance.windowTitle = 'Untitled.bpmn';
			}
		},

		async loadFile(path: string) {
			const vm = this;

			// Check if already open in another tab
			const existingIndex = vm.files.findIndex((f: BpmnFile) => f.path === path);
			if (existingIndex >= 0) {
				vm.selectTab(existingIndex);
				return;
			}

			vm.isLoading = true;
			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				const node: Node | null = await contentService.getNode(path);

				if (!node) {
					throw new Error(`File not found: ${path}`);
				}

				// Save current file state
				if (vm.currentFileIndex >= 0) {
					vm.saveCurrentFileState();
				}

				// Create new store for this file
				const newStore = new BpmnModelStore();
				let originalXml = '';

				// Fetch file content
				if (node.downloadUrl) {
					const response = await fetch(node.downloadUrl);
					if (!response.ok) {
						throw new Error(`Failed to fetch file content: ${response.statusText}`);
					}
					const content = await response.text();
					originalXml = content;
					parseBpmnXmlToStore(content, newStore);
				}

				const newFile: BpmnFile = {
					id: vm.generateFileId(),
					path: node.path,
					name: node.name,
					mimeType: node.mimeType || 'application/x-bpmn',
					isVersionable: node.isVersionable || false,
					isCheckedOut: node.isCheckedOut || false,
					baseVersionName: node.baseVersionName || '',
					store: vm.$markRaw(newStore),
					originalXml: originalXml,
					isModified: false,
					commandManager: vm.$markRaw(new CommandManager()),
					zoom: 1,
					panX: 0,
					panY: 0,
					selectedIds: [],
					storeVersion: 0,
				};

				vm.files.push(vm.$markRaw(newFile));
				vm.currentFileIndex = vm.files.length - 1;
				vm.restoreFileState(vm.currentFileIndex);
				vm.storeVersion++;

				if (vm.instance) {
					vm.instance.windowTitle = node.name;
				}

				// Center view on content
				vm.fitToScreen();
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || 'Failed to load file';
			} finally {
				vm.isLoading = false;
			}

		},

		async saveFile() {
			const vm = this;
			if (vm.isSaving) return;

			vm.isSaving = true;
			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				const xml = serializeStoreToXml(bpmnModelStore);

				// Encode content to base64
				const encoder = new TextEncoder();
				const bytes = encoder.encode(xml);
				const base64 = btoa(String.fromCharCode(...bytes));

				// Get parent path and filename
				const pathParts = vm.currentFile.path.split('/');
				const fileName = pathParts.pop() as string;
				const parentPath = pathParts.join('/') || '/';

				// Use multipart upload
				const uploadInfo = await contentService.initiateMultipartUpload();
				const uploadId = uploadInfo.uploadId;

				try {
					await contentService.appendMultipartUploadChunk(uploadId, base64);
					await contentService.completeMultipartUpload(
						uploadId,
						parentPath,
						fileName,
						vm.currentFile.mimeType,
						true
					);

					vm.originalXml = xml;
					vm.isModified = false;
					// Update file object state after successful save
					if (vm.currentFileIndex >= 0 && vm.currentFileIndex < vm.files.length) {
						vm.files[vm.currentFileIndex].isModified = false;
						vm.files[vm.currentFileIndex].originalXml = xml;
						vm.files.splice(vm.currentFileIndex, 1, vm.files[vm.currentFileIndex]);
					}
				} catch (error) {
					try {
						await contentService.abortMultipartUpload(uploadId);
					} catch { /* ignore */ }
					throw error;
				}
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || 'Failed to save file';
			} finally {
				vm.isSaving = false;
			}
		},

		// Save As
		openSaveAsDialog() {
			this.saveAsDialog.fileName = this.currentFile.name || 'Untitled.bpmn';
			this.saveAsDialog.visible = true;
		},
		closeSaveAsDialog() {
			this.saveAsDialog.visible = false;
		},
		onSaveAsDragStart(event: DragEvent) {
			if (!event.dataTransfer) return;
			const content = serializeStoreToXml(bpmnModelStore);
			const fileName = this.saveAsDialog.fileName || 'Untitled.bpmn';
			this.saveAsToken = Date.now().toString(36) + Math.random().toString(36).slice(2);
			event.dataTransfer.effectAllowed = 'copy';
			event.dataTransfer.setData('application/x-webtop-save', JSON.stringify({
				name: fileName,
				mimeType: this.currentFile.mimeType || 'application/x-bpmn',
				content: content,
				saveAsToken: this.saveAsToken,
			}));
			event.dataTransfer.setData('text/plain', fileName);
			setTimeout(() => { this.saveAsDialog.visible = false; }, 100);
		},

		// ---- Pane toggles / resize ----
		toggleSidebarPanel() {
			this.sidebarPanelVisible = !this.sidebarPanelVisible;
		},
		toggleDetailPanel() {
			this.detailPanelVisible = !this.detailPanelVisible;
		},
		onSidebarResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			vm.sidebarResizing = true;
			vm.sidebarResizeStartX = event.clientX;
			vm.sidebarResizeStartWidth = vm.sidebarPanelWidth;
			const onMove = (e: MouseEvent) => {
				if (!vm.sidebarResizing) return;
				const delta = e.clientX - vm.sidebarResizeStartX;
				vm.sidebarPanelWidth = Math.max(180, Math.min(600, vm.sidebarResizeStartWidth + delta));
			};
			const onUp = () => {
				vm.sidebarResizing = false;
				document.removeEventListener('mousemove', onMove);
				document.removeEventListener('mouseup', onUp);
			};
			document.addEventListener('mousemove', onMove);
			document.addEventListener('mouseup', onUp);
		},

		// ---- Window controls ----
		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},

		// ---- Drag & drop ----
		// App root rejects drops by default so the browser does not navigate.
		onAppDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		// Side panes (palette / properties) reject all drops.
		onForbiddenDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		// Center pane: accept files (OS / Content Browser) for opening in a new tab,
		// palette drags are handled by the canvas itself; reject self-drop of save-as chip.
		onCenterPaneDragOver(event: DragEvent) {
			if (!event.dataTransfer) return;
			const types = event.dataTransfer.types;
			if (types.includes('application/x-webtop-save')) {
				event.dataTransfer.dropEffect = 'none';
				return;
			}
			const isFileDrop = types.includes('Files') || types.includes('application/x-webtop-file');
			if (!isFileDrop) return; // palette drag — canvas owns the dropEffect
			event.dataTransfer.dropEffect = 'copy';
		},
		async onCenterPaneDrop(event: DragEvent) {
			const types = event.dataTransfer?.types ?? [];
			if (types.includes('application/x-webtop-save')) return; // reject self-drop
			const isFileDrop = types.includes('Files') || types.includes('application/x-webtop-file');
			if (!isFileDrop) return; // palette drop — canvas already handled
			await this.handleFileDropEvent(event);
		},
		async handleFileDropEvent(event: DragEvent) {
			const vm = this;

			// Content Browser (cross-iframe) file drag
			const webtopFileData = event.dataTransfer?.getData('application/x-webtop-file');
			if (webtopFileData) {
				try {
					const fileInfo = JSON.parse(webtopFileData);
					if (fileInfo.path) {
						await vm.loadFile(fileInfo.path);
						return;
					}
				} catch (e) {
					console.error('Failed to parse webtop file data:', e);
				}
			}

			// Local files (OS)
			const files = event.dataTransfer?.files;
			if (files && files.length > 0) {
				const file = files[0];
				try {
					const content = await file.text();

					// Save current file state before opening new tab
					if (vm.currentFileIndex >= 0) {
						vm.saveCurrentFileState();
					}

					const newStore = new BpmnModelStore();
					parseBpmnXmlToStore(content, newStore);

					const newFile: BpmnFile = {
						id: vm.generateFileId(),
						path: '',
						name: file.name,
						mimeType: file.type || 'application/x-bpmn',
						isVersionable: false,
						isCheckedOut: false,
						baseVersionName: '',
						store: vm.$markRaw(newStore),
						originalXml: content,
						isModified: false,
						commandManager: vm.$markRaw(new CommandManager()),
						zoom: 1,
						panX: 0,
						panY: 0,
						selectedIds: [],
						storeVersion: 0,
					};

					vm.files.push(vm.$markRaw(newFile));
					vm.currentFileIndex = vm.files.length - 1;
					vm.restoreFileState(vm.currentFileIndex);
					vm.storeVersion++;

					if (vm.instance) {
						vm.instance.windowTitle = file.name;
					}

					vm.fitToScreen();
				} catch (e) {
					console.error('Failed to load local file:', file.name, e);
					vm.errorMessage = 'Failed to load file: ' + file.name;
				}
			}
		},

		// ==========================================================================
		// Canvas Operations
		// ==========================================================================

		/**
		 * Begin dragging an intermediate bend-point of a connection's waypoint
		 * polyline. Bend handles are rendered for the currently selected
		 * connection (see index.html). Dragging marks the edge as manually
		 * adjusted so subsequent element moves preserve the custom path.
		 */
		onBendHandleMouseDown(e: MouseEvent, conn: BpmnConnection, waypointIndex: number) {
			const vm = this;
			e.stopPropagation();
			e.preventDefault();
			vm.isBendDragging = true;
			vm.bendConnectionId = conn.id;
			vm.bendWaypointIndex = waypointIndex;
		},

		/**
		 * Compute the array of bend-point handle positions for a connection.
		 * Returns waypoints[1 .. n-2] (i.e. excludes the source / target
		 * endpoints which already have their own reconnect handles).
		 */
		getBendHandles(conn: BpmnConnection): { index: number; x: number; y: number }[] {
			const vm = this;
			void vm;
			if (!conn.waypoints || conn.waypoints.length <= 2) return [];
			const out: { index: number; x: number; y: number }[] = [];
			for (let i = 1; i < conn.waypoints.length - 1; i++) {
				const p = conn.waypoints[i];
				if (p && !isNaN(p.x) && !isNaN(p.y)) {
					out.push({ index: i, x: p.x, y: p.y });
				}
			}
			return out;
		},

		/**
		 * Handle mousedown on reconnect handle to start reconnection
		 */
		onReconnectHandleMouseDown(e: MouseEvent, conn: BpmnConnection, side: 'source' | 'target') {
			const vm = this;
			e.stopPropagation();
			e.preventDefault();

			const endpoints = vm.getConnectionEndpoints(conn);
			if (!endpoints) return;

			vm.isReconnecting = true;
			vm.reconnectingConnectionId = conn.id;
			vm.reconnectingSide = side;

			// Fixed point is the opposite end
			if (side === 'source') {
				vm.reconnectStartPoint = { x: endpoints.target.x, y: endpoints.target.y };
				vm.reconnectEndPoint = { x: endpoints.source.x, y: endpoints.source.y };
			} else {
				vm.reconnectStartPoint = { x: endpoints.source.x, y: endpoints.source.y };
				vm.reconnectEndPoint = { x: endpoints.target.x, y: endpoints.target.y };
			}
		},

		/**
		 * Finalize an in-progress reconnect by attaching the dragged endpoint
		 * to `target` (or whatever element sits at the drop point) and
		 * recomputing waypoints. Always clears the reconnect state, even when
		 * the drop is invalid, so the cursor can't get stuck mid-drag.
		 *
		 * `explicitSide` overrides the side computed from (x, y) — used when the
		 * user releases over a specific connection-point circle, so we know
		 * exactly which anchor they intended even if the cursor sat on the
		 * shape body or the bounding-box edge.
		 */
		finalizeReconnectAt(
			x: number,
			y: number,
			target?: BpmnElement | null,
			explicitSide?: 'N' | 'S' | 'E' | 'W' | null
		): void {
			const vm = this;
			const conn = vm.getConnectionById(vm.reconnectingConnectionId);
			const targetElement = target ?? vm.findElementAtPoint(x, y);

			if (targetElement && conn) {
				const currentRef = vm.reconnectingSide === 'source' ? conn.sourceRef : conn.targetRef;
				const otherRef = vm.reconnectingSide === 'source' ? conn.targetRef : conn.sourceRef;

				// Reject only self-loops (dropping onto the opposite endpoint's
				// element). Dropping onto the SAME element the dragged end is
				// already attached to is allowed — it lets the user change which
				// side/anchor the connection enters/exits.
				if (targetElement.id !== otherRef) {
					const isSameElement = targetElement.id === currentRef;
					if (!isSameElement) {
						if (vm.reconnectingSide === 'source') {
							vm.updateFlowDual(conn.id, { sourceRef: targetElement.id });
						} else {
							vm.updateFlowDual(conn.id, { targetRef: targetElement.id });
						}
					}
					// Recompute side and waypoints based on where the user dropped.
					const droppedSide = explicitSide ?? vm.getSideForCanvasPoint(targetElement, x, y);
					const newSourceRef = vm.reconnectingSide === 'source' ? targetElement.id : conn.sourceRef;
					const newTargetRef = vm.reconnectingSide === 'target' ? targetElement.id : conn.targetRef;
					const newSource = vm.getElementById(newSourceRef);
					const newTarget = vm.getElementById(newTargetRef);
					// For the side that wasn't dragged, prefer the existing
					// stored side; otherwise infer from element-to-element
					// direction so a connection without prior side hints (e.g.
					// imported XML) still gets a coherent side-aware route.
					const inferSide = (
						from: { x: number; y: number; width: number; height: number } | undefined,
						to: { x: number; y: number; width: number; height: number } | undefined
					): 'N' | 'S' | 'E' | 'W' | undefined => {
						if (!from || !to) return undefined;
						const fcx = from.x + from.width / 2;
						const fcy = from.y + from.height / 2;
						const tcx = to.x + to.width / 2;
						const tcy = to.y + to.height / 2;
						const dx = tcx - fcx;
						const dy = tcy - fcy;
						if (Math.abs(dx) >= Math.abs(dy)) return dx >= 0 ? 'E' : 'W';
						return dy >= 0 ? 'S' : 'N';
					};
					const newSourceSide: 'N' | 'S' | 'E' | 'W' | undefined =
						vm.reconnectingSide === 'source'
							? droppedSide
							: (conn.sourceSide ?? inferSide(newSource, newTarget));
					const newTargetSide: 'N' | 'S' | 'E' | 'W' | undefined =
						vm.reconnectingSide === 'target'
							? droppedSide
							: (conn.targetSide ?? inferSide(newTarget, newSource));
					const sideChanged = vm.reconnectingSide === 'source'
						? droppedSide !== conn.sourceSide
						: droppedSide !== conn.targetSide;
					const isSequenceFlow = (conn.connectionType ?? 'sequenceFlow') === 'sequenceFlow';
					if (isSequenceFlow && newSource && newTarget && newSourceSide && newTargetSide) {
						const wps = vm.buildSideAwareWaypoints(newSource, newSourceSide, newTarget, newTargetSide);
						const edge = bpmnModelStore.getEdgesForFlow(conn.id)[0];
						if (edge) {
							bpmnModelStore.updateEdge(edge.id, {
								sourceSide: newSourceSide,
								targetSide: newTargetSide,
								manuallyAdjusted: false,
								waypoints: wps,
							});
							vm.storeVersion++;
						}
					} else if (!isSameElement) {
						// Fallback: clear waypoints, let auto-routing handle it.
						vm.updateWaypointsDual(conn.id, []);
					}
					if (!isSameElement || sideChanged) {
						vm.markModified();
					}
				}
			}

			vm.isReconnecting = false;
			vm.reconnectingConnectionId = '';
			vm.reconnectingSide = '';
			vm.connectionTargetIds = [];
		},

		onCanvasMouseDown(e: MouseEvent) {
			const vm = this;

			// Close type change popup when clicking on canvas
			vm.closeTypeChangePopup();

			// Middle button, Alt+drag, or Space+drag for panning
			if (e.button === 1 || (e.button === 0 && (e.altKey || vm.isSpaceHeld))) {
				vm.isPanning = true;
				vm.panStartX = e.clientX;
				vm.panStartY = e.clientY;
				vm.panStartPanX = vm.panX;
				vm.panStartPanY = vm.panY;
				e.preventDefault();
				return;
			}

			// Left click - start selection rectangle
			if (e.button === 0) {
				const container = e.currentTarget as HTMLElement;
				const rect = container.getBoundingClientRect();
				const x = (e.clientX - rect.left - vm.panX) / vm.zoom;
				const y = (e.clientY - rect.top - vm.panY) / vm.zoom;

				vm.selectionStartX = x;
				vm.selectionStartY = y;
				vm.selectionRect = { x, y, width: 0, height: 0 };

				// Clear selection if not holding shift
				if (!e.shiftKey) {
					vm.selectedIds = [];
				}
			}
		},

		onCanvasMouseMove(e: MouseEvent) {
			const vm = this;
			const container = e.currentTarget as HTMLElement;

			// Panning
			if (vm.isPanning) {
				vm.panX = vm.panStartPanX + (e.clientX - vm.panStartX);
				vm.panY = vm.panStartPanY + (e.clientY - vm.panStartY);
				return;
			}

			// Connection drawing
			if (vm.isConnecting) {
				const rect = container.getBoundingClientRect();
				const x = (e.clientX - rect.left - vm.panX) / vm.zoom;
				const y = (e.clientY - rect.top - vm.panY) / vm.zoom;

				vm.connectionEndPoint = { x, y };
				vm.connectionPreview = `M ${vm.connectionSourcePoint.x} ${vm.connectionSourcePoint.y} L ${x} ${y}`;

				// Detect nearby elements to show their connection points
				const proximityThreshold = 80;
				const nearbyIds: string[] = [];
				const elements = vm.getElementsForRendering();
				for (const el of elements) {
					if (el.id === vm.connectionSourceId) continue;
					const cx = el.x + el.width / 2;
					const cy = el.y + el.height / 2;
					const dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
					if (dist < proximityThreshold + Math.max(el.width, el.height) / 2) {
						nearbyIds.push(el.id);
					}
				}
				vm.connectionTargetIds = nearbyIds;
				return;
			}

			// Connection reconnecting
			if (vm.isReconnecting) {
				const rect = container.getBoundingClientRect();
				const x = (e.clientX - rect.left - vm.panX) / vm.zoom;
				const y = (e.clientY - rect.top - vm.panY) / vm.zoom;

				vm.reconnectEndPoint = { x, y };

				// Highlight nearby elements so their connection-points become
				// visible while the user is choosing where to drop the handle.
				// Without this, the dragged endpoint can't aim at a specific
				// anchor — particularly painful on Gateway, where each side is
				// a distinct port the user wants to pick.
				const proximityThreshold = 80;
				const nearbyIds: string[] = [];
				const elements = vm.getElementsForRendering();
				for (const el of elements) {
					const cx = el.x + el.width / 2;
					const cy = el.y + el.height / 2;
					const dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
					if (dist < proximityThreshold + Math.max(el.width, el.height) / 2) {
						nearbyIds.push(el.id);
					}
				}
				vm.connectionTargetIds = nearbyIds;
				return;
			}

			// Bend-point dragging on a connection
			if (vm.isBendDragging) {
				const rect = container.getBoundingClientRect();
				const x = (e.clientX - rect.left - vm.panX) / vm.zoom;
				const y = (e.clientY - rect.top - vm.panY) / vm.zoom;
				const edge = bpmnModelStore.getEdgesForFlow(vm.bendConnectionId)[0];
				if (edge && edge.waypoints && vm.bendWaypointIndex > 0 && vm.bendWaypointIndex < edge.waypoints.length - 1) {
					// Snap to 10px grid for consistency with element drag.
					const snappedX = Math.round(x / 10) * 10;
					const snappedY = Math.round(y / 10) * 10;
					const wps = edge.waypoints.map(p => ({ x: p.x, y: p.y }));
					wps[vm.bendWaypointIndex] = { x: snappedX, y: snappedY };
					bpmnModelStore.updateEdge(edge.id, {
						waypoints: wps,
						manuallyAdjusted: true,
					});
					vm.storeVersion++;
					vm.markModified();
				}
				return;
			}

			// Dragging elements
			if (vm.isDragging) {
				const dx = (e.clientX - vm.dragStartX) / vm.zoom;
				const dy = (e.clientY - vm.dragStartY) / vm.zoom;

				vm.dragElementStartPositions.forEach(({ id, x, y }) => {
					// Use getElementById in store mode to get current element data
					const element = vm.getElementById(id);
					if (element) {
						// Calculate base position with grid snap
						let newX = Math.round((x + dx) / 10) * 10;
						let newY = Math.round((y + dy) / 10) * 10;

						// Snap to connected elements' connection points for alignment
						const snapThreshold = 15; // Snap within 15px
						const elementYOffset = getHorizontalConnectionYOffset(element.type);

						// Find connected elements and try to align
						vm.getConnectionsForRenderingInternal().forEach((conn: BpmnConnection) => {
							let connectedElement: BpmnElement | undefined;

							if (conn.sourceRef === element.id) {
								connectedElement = vm.getElementsForRendering().find(el => el.id === conn.targetRef);
							} else if (conn.targetRef === element.id) {
								connectedElement = vm.getElementsForRendering().find(el => el.id === conn.sourceRef);
							}

							if (connectedElement) {
								const connectedYOffset = getHorizontalConnectionYOffset(connectedElement.type);
								// Calculate the Y position that would align connection points
								const alignedY = connectedElement.y + connectedYOffset - elementYOffset;

								// If close enough, snap to aligned position
								if (Math.abs(newY - alignedY) < snapThreshold) {
									newY = alignedY;
								}
							}
						});

						// Special handling for Boundary Event: snap to parent task boundary
						if (element.type === 'boundaryEvent' && element.attachedToRef) {
							const attachedTo = vm.getElementsForRendering().find(el => el.id === element.attachedToRef);
							if (attachedTo) {
								const taskSize = ELEMENT_SIZES[attachedTo.type] || { width: 100, height: 80 };
								const boundarySize = 36;
								const halfBoundary = boundarySize / 2;

								// Calculate snap positions on task boundary
								const snapPositions = [
									// Top edge
									{ x: Math.max(attachedTo.x, Math.min(newX, attachedTo.x + taskSize.width - boundarySize)),
									  y: attachedTo.y - halfBoundary },
									// Bottom edge
									{ x: Math.max(attachedTo.x, Math.min(newX, attachedTo.x + taskSize.width - boundarySize)),
									  y: attachedTo.y + taskSize.height - halfBoundary },
									// Left edge
									{ x: attachedTo.x - halfBoundary,
									  y: Math.max(attachedTo.y, Math.min(newY, attachedTo.y + taskSize.height - boundarySize)) },
									// Right edge
									{ x: attachedTo.x + taskSize.width - halfBoundary,
									  y: Math.max(attachedTo.y, Math.min(newY, attachedTo.y + taskSize.height - boundarySize)) },
								];

								// Find closest snap position
								let minDist = Infinity;
								let snapPos = { x: newX, y: newY };
								for (const pos of snapPositions) {
									const dist = Math.sqrt(Math.pow(newX - pos.x, 2) + Math.pow(newY - pos.y, 2));
									if (dist < minDist) {
										minDist = dist;
										snapPos = pos;
									}
								}
								newX = snapPos.x;
								newY = snapPos.y;
							}
						}

						// Calculate how much the element moved for moving children
						const moveX = newX - element.x;
						const moveY = newY - element.y;

						// Update element position using dual helper
						vm.updateElementBounds(element.id, { x: newX, y: newY });

						// If this is a subprocess, also move its child elements (both expanded and collapsed)
						if (element.type === 'subProcess') {
							const childElements = vm.getChildElements(element.id);
							const childIds = new Set(childElements.map((c: BpmnElement) => c.id));

							// Move child elements
							childElements.forEach((child: BpmnElement) => {
								vm.updateElementBounds(child.id, { x: child.x + moveX, y: child.y + moveY });
							});

							// Move connections between child elements (update waypoints)
							const connections = vm.getConnectionsForRenderingInternal();
							connections.forEach((conn: BpmnConnection) => {
								// Only move connections where both endpoints are children of this subprocess
								if (childIds.has(conn.sourceRef) && childIds.has(conn.targetRef)) {
									if (conn.waypoints && conn.waypoints.length > 0) {
										const newWaypoints = conn.waypoints.map(wp => ({
											x: wp.x + moveX,
											y: wp.y + moveY,
										}));
										vm.updateConnectionWaypoints(conn.id, newWaypoints);
									}
								}
							});
						}

						// If this is a Pool, also move its Lanes and contained elements
						if (element.type === 'pool') {
							const poolBounds = {
								x: element.x,
								y: element.y,
								width: element.width || ELEMENT_SIZES.pool.width,
								height: element.height || ELEMENT_SIZES.pool.height,
							};

							// Get all lanes in this pool
							const lanes = vm.getElementsForRendering().filter((el: BpmnElement) =>
								el.type === 'lane' && el.parentPoolId === element.id
							);

							// Collect all element IDs that are inside the pool
							const poolElementIds = new Set<string>();

							// Method 1: Elements referenced by lanes (via flowNodeRefs)
							lanes.forEach((lane: BpmnElement) => {
								// Move the lane itself
								vm.updateElementBounds(lane.id, { x: lane.x + moveX, y: lane.y + moveY });

								// Collect elements referenced by this lane
								if (lane.flowNodeRefs) {
									lane.flowNodeRefs.forEach((refId: string) => poolElementIds.add(refId));
								}
							});

							// Method 2: Elements whose center is within pool bounds (for pools without lanes or elements not in flowNodeRefs)
							vm.getElementsForRendering().forEach((el: BpmnElement) => {
								// Skip pool, lane, and already collected elements
								if (el.type === 'pool' || el.type === 'lane' || poolElementIds.has(el.id)) return;

								// Check if element center is within pool bounds
								const elSize = ELEMENT_SIZES[el.type] || { width: 100, height: 80 };
								const elCenterX = el.x + (el.width || elSize.width) / 2;
								const elCenterY = el.y + (el.height || elSize.height) / 2;

								// Pool header is 30px, content area starts after that
								const contentX = poolBounds.x + 30;
								const contentY = poolBounds.y;
								const contentWidth = poolBounds.width - 30;
								const contentHeight = poolBounds.height;

								if (elCenterX >= contentX && elCenterX <= contentX + contentWidth &&
									elCenterY >= contentY && elCenterY <= contentY + contentHeight) {
									poolElementIds.add(el.id);
								}
							});

							// Move all elements inside the pool
							poolElementIds.forEach((elementId: string) => {
								const poolElement = vm.getElementsForRendering().find((el: BpmnElement) => el.id === elementId);
								if (poolElement) {
									vm.updateElementBounds(poolElement.id, { x: poolElement.x + moveX, y: poolElement.y + moveY });
								}
							});

							// Move connections between elements in the pool (update waypoints)
							const connections = vm.getConnectionsForRenderingInternal();
							connections.forEach((conn: BpmnConnection) => {
								// Move connections where both endpoints are inside the pool
								if (poolElementIds.has(conn.sourceRef) && poolElementIds.has(conn.targetRef)) {
									if (conn.waypoints && conn.waypoints.length > 0) {
										const newWaypoints = conn.waypoints.map(wp => ({
											x: wp.x + moveX,
											y: wp.y + moveY,
										}));
										vm.updateConnectionWaypoints(conn.id, newWaypoints);
									}
								}
							});
						}
					}
				});

				vm.markModified();
				return;
			}

			// Dragging labels
			if (vm.isDraggingLabel) {
				const dx = (e.clientX - vm.labelDragStartX) / vm.zoom;
				const dy = (e.clientY - vm.labelDragStartY) / vm.zoom;

				const newOffsetX = vm.labelOriginalOffsetX + dx;
				const newOffsetY = vm.labelOriginalOffsetY + dy;

				if (vm.draggingLabelType === 'element') {
					const element = vm.getElementsForRendering().find(el => el.id === vm.draggingLabelId);
					if (element) {
						vm.updateElementLabelDual(vm.draggingLabelId, newOffsetX, newOffsetY, element.labelWidth);
					}
				} else if (vm.draggingLabelType === 'connection') {
					const conn = vm.getConnectionsForRenderingInternal().find(c => c.id === vm.draggingLabelId);
					if (conn) {
						vm.updateConnectionLabelDual(vm.draggingLabelId, newOffsetX, newOffsetY, conn.labelWidth);
					}
				}
				vm.markModified();
				return;
			}

			// Resizing labels
			if (vm.isResizingLabel) {
				const dx = (e.clientX - vm.labelResizeStartX) / vm.zoom;
				const newWidth = Math.max(50, vm.labelOriginalWidth + dx); // Minimum width 50px

				if (vm.resizingLabelType === 'element') {
					const element = vm.getElementsForRendering().find(el => el.id === vm.resizingLabelId);
					if (element) {
						vm.updateElementLabelDual(vm.resizingLabelId, element.labelOffsetX || 0, element.labelOffsetY || 0, newWidth);
					}
				} else if (vm.resizingLabelType === 'connection') {
					const conn = vm.getConnectionsForRenderingInternal().find(c => c.id === vm.resizingLabelId);
					if (conn) {
						vm.updateConnectionLabelDual(vm.resizingLabelId, conn.labelOffsetX || 0, conn.labelOffsetY || 0, newWidth);
					}
				}
				vm.markModified();
				return;
			}

			// Selection rectangle
			if (vm.selectionRect) {
				const rect = container.getBoundingClientRect();
				const x = (e.clientX - rect.left - vm.panX) / vm.zoom;
				const y = (e.clientY - rect.top - vm.panY) / vm.zoom;

				vm.selectionRect = {
					x: Math.min(vm.selectionStartX, x),
					y: Math.min(vm.selectionStartY, y),
					width: Math.abs(x - vm.selectionStartX),
					height: Math.abs(y - vm.selectionStartY),
				};
			}
		},

		onCanvasMouseUp(e: MouseEvent) {
			const vm = this;
			const container = e.currentTarget as HTMLElement;

			if (vm.isPanning) {
				vm.isPanning = false;
				return;
			}

			// Finish bend-point drag.
			if (vm.isBendDragging) {
				vm.isBendDragging = false;
				vm.bendConnectionId = '';
				vm.bendWaypointIndex = -1;
				return;
			}

			// Connection drawing - check if we dropped on an element
			if (vm.isConnecting) {
				const rect = container.getBoundingClientRect();
				const x = (e.clientX - rect.left - vm.panX) / vm.zoom;
				const y = (e.clientY - rect.top - vm.panY) / vm.zoom;

				// Find element under cursor
				const targetElement = vm.findElementAtPoint(x, y);
				if (targetElement && targetElement.id !== vm.connectionSourceId) {
					const targetSide = vm.getSideForCanvasPoint(targetElement, x, y);
					vm.createConnection(
						vm.connectionSourceId,
						targetElement.id,
						vm.connectionSourceSide,
						targetSide
					);
				}

				vm.isConnecting = false;
				vm.connectionSourceId = '';
				vm.connectionSourceSide = null;
				vm.connectionPreview = '';
				vm.connectionTargetIds = [];
				return;
			}

			// Connection reconnecting - check if we dropped on an element
			if (vm.isReconnecting) {
				const rect = container.getBoundingClientRect();
				const x = (e.clientX - rect.left - vm.panX) / vm.zoom;
				const y = (e.clientY - rect.top - vm.panY) / vm.zoom;
				vm.finalizeReconnectAt(x, y);
				return;
			}

			if (vm.isDragging) {
				vm.isDragging = false;

				// Create undo/redo command for move operation
				const startPositions = [...vm.dragElementStartPositions];
				const endPositions = startPositions.map(pos => {
					const el = vm.getElementById(pos.id);
					return { id: pos.id, x: el?.x ?? pos.x, y: el?.y ?? pos.y };
				});

				// Check if any element actually moved
				const hasMoved = startPositions.some((start, i) => {
					const end = endPositions[i];
					return start.x !== end.x || start.y !== end.y;
				});

				if (hasMoved) {
					const command: Command = {
						execute: () => {
							endPositions.forEach(pos => {
								vm.updateElementBounds(pos.id, { x: pos.x, y: pos.y });
							});
							vm.storeVersion++;
						},
						undo: () => {
							startPositions.forEach(pos => {
								vm.updateElementBounds(pos.id, { x: pos.x, y: pos.y });
							});
							vm.storeVersion++;
						},
					};
					// Add command without executing (already moved during drag)
					vm.commandManager.addCommand(command);

					// Re-parent elements that crossed Pool boundaries. Without
					// this, a node dragged from Pool A into Pool B keeps its
					// old parentId and would still be serialized into A's
					// <bpmn:process>. We only adjust ownership when the new
					// center actually lands inside a Pool — landing outside any
					// pool leaves parentId untouched (otherwise nodes would
					// silently fall out of every <bpmn:process> on save).
					endPositions.forEach(({ id }) => {
						const el = vm.getElementById(id);
						if (!el) return;
						// Pools and Lanes are containers themselves; their
						// parent is fixed (root for Pool, parentPoolId for
						// Lane) and shouldn't change based on geometry.
						if (el.type === 'pool' || el.type === 'lane') return;
						// Boundary events follow their attached task, not the
						// pool they happen to overlap.
						if (el.type === 'boundaryEvent') return;
						// Don't yank a sub-process child out of its parent
						// just because a sibling sub-process or pool happens
						// to overlap. Cross-Pool moves of sub-process contents
						// must be done by dragging the sub-process itself.
						if (el.parentId) {
							const parentSemantic = bpmnModelStore.getSemantic(el.parentId);
							if (parentSemantic && parentSemantic.type === 'subProcess') return;
						}
						const elSize = ELEMENT_SIZES[el.type] || { width: el.width, height: el.height };
						const centerX = el.x + (el.width || elSize.width) / 2;
						const centerY = el.y + (el.height || elSize.height) / 2;
						const containingPool = vm.findPoolAtPosition(centerX, centerY);
						if (!containingPool) return;
						if (el.parentId === containingPool.id) return;
						bpmnModelStore.updateSemantic(el.id, { parentId: containingPool.id });
					});
				}

				vm.dragElementStartPositions = [];
				return;
			}

			// Label dragging
			if (vm.isDraggingLabel) {
				vm.isDraggingLabel = false;
				vm.draggingLabelType = '';
				vm.draggingLabelId = '';
				return;
			}

			// Label resizing
			if (vm.isResizingLabel) {
				vm.isResizingLabel = false;
				vm.resizingLabelType = '';
				vm.resizingLabelId = '';
				return;
			}

			// Finalize selection rectangle
			if (vm.selectionRect && vm.selectionRect.width > 5 && vm.selectionRect.height > 5) {
				const { x, y, width, height } = vm.selectionRect;

				vm.getElementsForRendering().forEach(element => {
					if (element.x >= x && element.y >= y &&
						element.x + element.width <= x + width &&
						element.y + element.height <= y + height) {
						if (!vm.selectedIds.includes(element.id)) {
							vm.selectedIds.push(element.id);
						}
					}
				});
			}

			vm.selectionRect = null;
		},

		onCanvasWheel(e: WheelEvent) {
			const vm = this;
			e.preventDefault();

			const delta = e.deltaY > 0 ? 0.9 : 1.1;
			const newZoom = Math.max(0.1, Math.min(3, vm.zoom * delta));

			// Zoom toward mouse position
			const container = e.currentTarget as HTMLElement;
			const rect = container.getBoundingClientRect();
			const mouseX = e.clientX - rect.left;
			const mouseY = e.clientY - rect.top;

			vm.panX = mouseX - (mouseX - vm.panX) * (newZoom / vm.zoom);
			vm.panY = mouseY - (mouseY - vm.panY) * (newZoom / vm.zoom);
			vm.zoom = newZoom;
		},

		onCanvasDragOver(e: DragEvent) {
			// File-like drags are handled by the parent (center pane) as "open in new tab".
			// Don't override the dropEffect in that case.
			const types = e.dataTransfer?.types ?? [];
			if (types.includes('Files') || types.includes('application/x-webtop-file')
				|| types.includes('application/x-webtop-save')) return;
			e.dataTransfer!.dropEffect = 'copy';
		},

		onCanvasDrop(e: DragEvent) {
			const vm = this;
			// Skip file drops — those are handled by onCenterPaneDrop which opens them in a new tab.
			const types = e.dataTransfer?.types ?? [];
			if (types.includes('Files') || types.includes('application/x-webtop-file')
				|| types.includes('application/x-webtop-save')) return;
			const type = e.dataTransfer?.getData('text/plain');
			if (!type) return;

			const container = e.currentTarget as HTMLElement;
			const rect = container.getBoundingClientRect();
			const x = (e.clientX - rect.left - vm.panX) / vm.zoom;
			const y = (e.clientY - rect.top - vm.panY) / vm.zoom;

			// Pools and Lanes never sit inside another container — drop them at root.
			// For everything else, prefer a sub-process if the drop lands inside one;
			// otherwise inherit the enclosing Pool so the new node is owned by that
			// pool's <bpmn:process> at serialize time.
			let parentId: string | undefined;
			if (type !== 'pool' && type !== 'lane') {
				const parentSubProcess = vm.findExpandedSubProcessAtPosition(x, y);
				if (parentSubProcess) {
					parentId = parentSubProcess.id;
				} else {
					const parentPool = vm.findPoolAtPosition(x, y);
					if (parentPool) parentId = parentPool.id;
				}
			}
			vm.addElement(type, x, y, parentId);
		},

		/**
		 * Find a Pool at the given canvas position. Pools are matched by their
		 * content area only (the 30px-wide left header is excluded so dropping
		 * on the header doesn't reparent into the pool).
		 */
		findPoolAtPosition(canvasX: number, canvasY: number): BpmnElement | null {
			const vm = this;
			const pools = vm.getElementsForRendering().filter((el: BpmnElement) => el.type === 'pool');
			for (const pool of pools) {
				const poolWidth = pool.width || ELEMENT_SIZES.pool.width;
				const poolHeight = pool.height || ELEMENT_SIZES.pool.height;
				if (canvasX >= pool.x + 30 && canvasX <= pool.x + poolWidth &&
					canvasY >= pool.y && canvasY <= pool.y + poolHeight) {
					return pool;
				}
			}
			return null;
		},

		/**
		 * Find an expanded sub-process at the given canvas position
		 */
		findExpandedSubProcessAtPosition(canvasX: number, canvasY: number): BpmnElement | null {
			const vm = this;
			// Find expanded subprocesses (excluding the header area for dropping)
			// Use getElementsForRendering() to get the correct state in store mode
			const elements = vm.getElementsForRendering();
			const expandedSubProcesses = elements.filter((el: BpmnElement) =>
				el.type === 'subProcess' && (el.subType === 'expanded' || el.isExpanded)
			);

			// Check from innermost to outermost (reverse order by area size, smaller first)
			const sorted = expandedSubProcesses.sort((a: BpmnElement, b: BpmnElement) => {
				const areaA = (a.width || 300) * (a.height || 200);
				const areaB = (b.width || 300) * (b.height || 200);
				return areaA - areaB;
			});

			for (const sp of sorted) {
				const spWidth = sp.width || 300;
				const spHeight = sp.height || 200;
				// Header area is about 30px, so allow drops below the header
				const headerHeight = 30;
				if (canvasX >= sp.x && canvasX <= sp.x + spWidth &&
				    canvasY >= sp.y + headerHeight && canvasY <= sp.y + spHeight) {
					return sp;
				}
			}
			return null;
		},

		// ==========================================================================
		// Element Operations
		// ==========================================================================

		onPaletteDragStart(e: DragEvent, type: string) {
			e.dataTransfer?.setData('text/plain', type);
		},

		onElementMouseDown(e: MouseEvent, element: BpmnElement | BpmnConnection) {
			const vm = this;

			// Close type change popup when clicking on elements
			vm.closeTypeChangePopup();

			// Toggle selection with Ctrl/Cmd
			if (e.ctrlKey || e.metaKey) {
				const idx = vm.selectedIds.indexOf(element.id);
				if (idx >= 0) {
					vm.selectedIds.splice(idx, 1);
				} else {
					vm.selectedIds.push(element.id);
				}
				return;
			}

			// Select if not already selected
			if (!vm.selectedIds.includes(element.id)) {
				if (!e.shiftKey) {
					vm.selectedIds = [];
				}
				vm.selectedIds.push(element.id);
			}

			// Start dragging (only for elements, not connections)
			if ('x' in element) {
				vm.isDragging = true;
				vm.dragStartX = e.clientX;
				vm.dragStartY = e.clientY;

				// Get selected elements' start positions
				// Use getElementById in store mode to get current positions from store
				const selectedElements = vm.selectedIds
					.map((id: string) => vm.getElementById(id))
					.filter((el: BpmnElement | undefined): el is BpmnElement => el !== undefined);

				// Also include boundary events attached to selected tasks
				const attachableTypes = [
					'userTask', 'serviceTask', 'scriptTask', 'manualTask',
					'sendTask', 'receiveTask', 'businessRuleTask', 'callActivity', 'task', 'subProcess'
				];
				const attachedBoundaryEvents: BpmnElement[] = [];
				// Use getElementsForRendering in store mode to get all elements
				const allElements = vm.getElementsForRendering();
				selectedElements.forEach((el: BpmnElement) => {
					if (attachableTypes.includes(el.type)) {
						allElements
							.filter((be: BpmnElement) => be.type === 'boundaryEvent' && be.attachedToRef === el.id)
							.forEach((be: BpmnElement) => {
								if (!vm.selectedIds.includes(be.id)) {
									attachedBoundaryEvents.push(be);
								}
							});
					}
				});

				// Also include lanes that belong to selected pools
				const attachedLanes: BpmnElement[] = [];
				selectedElements.forEach((el: BpmnElement) => {
					if (el.type === 'pool') {
						allElements
							.filter((lane: BpmnElement) => lane.type === 'lane' && lane.parentPoolId === el.id)
							.forEach((lane: BpmnElement) => {
								if (!vm.selectedIds.includes(lane.id)) {
									attachedLanes.push(lane);
								}
							});
					}
				});

				vm.dragElementStartPositions = [
					...selectedElements.map((el: BpmnElement) => ({ id: el.id, x: el.x, y: el.y })),
					...attachedBoundaryEvents.map((el: BpmnElement) => ({ id: el.id, x: el.x, y: el.y, isAttached: true })),
					...attachedLanes.map((el: BpmnElement) => ({ id: el.id, x: el.x, y: el.y, isAttached: true })),
				] as { id: string; x: number; y: number; isAttached?: boolean }[];
			}
		},

		/**
		 * Handle mouse down on an element label (Gateway, etc.)
		 */
		onElementLabelMouseDown(e: MouseEvent, element: BpmnElement) {
			const vm = this;
			e.stopPropagation();

			// Select the element when clicking on its label
			if (!vm.selectedIds.includes(element.id)) {
				if (!e.shiftKey && !e.ctrlKey && !e.metaKey) {
					vm.selectedIds = [];
				}
				vm.selectedIds.push(element.id);
			}

			vm.isDraggingLabel = true;
			vm.draggingLabelType = 'element';
			vm.draggingLabelId = element.id;
			vm.labelDragStartX = e.clientX;
			vm.labelDragStartY = e.clientY;

			// Get current offset or calculate from default position
			const pos = vm.getGatewayLabelPosition(element);
			vm.labelOriginalOffsetX = element.labelOffsetX !== undefined ? element.labelOffsetX : pos.x;
			vm.labelOriginalOffsetY = element.labelOffsetY !== undefined ? element.labelOffsetY : pos.y;
		},

		/**
		 * Handle mouse down on a connection label
		 */
		onConnectionLabelMouseDown(e: MouseEvent, conn: BpmnConnection) {
			const vm = this;
			e.stopPropagation();

			// Select the connection when clicking on its label
			if (!vm.selectedIds.includes(conn.id)) {
				if (!e.shiftKey && !e.ctrlKey && !e.metaKey) {
					vm.selectedIds = [];
				}
				vm.selectedIds.push(conn.id);
			}

			vm.isDraggingLabel = true;
			vm.draggingLabelType = 'connection';
			vm.draggingLabelId = conn.id;
			vm.labelDragStartX = e.clientX;
			vm.labelDragStartY = e.clientY;

			// Get current offset or calculate from default position
			const pos = vm.getConnectionLabelPosition(conn);
			vm.labelOriginalOffsetX = conn.labelOffsetX !== undefined ? conn.labelOffsetX : pos.x;
			vm.labelOriginalOffsetY = conn.labelOffsetY !== undefined ? conn.labelOffsetY : pos.y;
		},

		/**
		 * Handle mouse down on a label resize handle
		 */
		onLabelResizeMouseDown(e: MouseEvent, type: 'element' | 'connection', id: string, currentWidth: number) {
			const vm = this;
			e.stopPropagation();

			vm.isResizingLabel = true;
			vm.resizingLabelType = type;
			vm.resizingLabelId = id;
			vm.labelResizeStartX = e.clientX;
			vm.labelOriginalWidth = currentWidth || 100; // Default width
		},

		/**
		 * Find a task element at the given canvas position
		 */
		findTaskAtPosition(canvasX: number, canvasY: number): BpmnElement | null {
			const vm = this;
			const attachableTypes = [
				'userTask', 'serviceTask', 'scriptTask', 'manualTask',
				'sendTask', 'receiveTask', 'businessRuleTask', 'task', 'subProcess'
			];

			return vm.getElementsForRendering().find((el: BpmnElement) => {
				if (!attachableTypes.includes(el.type)) {
					return false;
				}
				const size = ELEMENT_SIZES[el.type] || { width: 100, height: 80 };
				return canvasX >= el.x && canvasX <= el.x + size.width &&
					   canvasY >= el.y && canvasY <= el.y + size.height;
			}) || null;
		},

		addElement(type: string, x: number, y: number, parentId?: string) {
			const vm = this;
			const size = ELEMENT_SIZES[type] || { width: 100, height: 80 };

			// Special handling for Boundary Event
			if (type === 'boundaryEvent') {
				const targetTask = vm.findTaskAtPosition(x, y);
				if (!targetTask) {
					vm.showNotification('Boundary Event must be dropped on a Task or SubProcess', 'warning');
					return;
				}

				const taskSize = ELEMENT_SIZES[targetTask.type] || { width: 100, height: 80 };
				const element: BpmnElement = {
					id: generateId('BoundaryEvent'),
					type: 'boundaryEvent',
					name: '',
					// Position at bottom center of the task
					x: targetTask.x + taskSize.width / 2 - 18,
					y: targetTask.y + taskSize.height - 18,
					width: 36,
					height: 36,
					attachedToRef: targetTask.id,
					cancelActivity: true, // Default to Interrupting
					subType: 'timer', // Default subType
					parentId: targetTask.parentId, // Inherit parent from the attached task
				};

				vm.addElementDual(element);
				vm.selectedIds = [element.id];
				vm.markModified();
				return;
			}

			const element: BpmnElement = {
				id: generateId(type.charAt(0).toUpperCase() + type.slice(1)),
				type,
				name: '',
				x: Math.round((x - size.width / 2) / 10) * 10,
				y: Math.round((y - size.height / 2) / 10) * 10,
				width: size.width,
				height: size.height,
				parentId, // Set parent sub-process ID if dropping inside one
			};

			// Set defaults for specific types
			let isFirstPoolDrop = false;
			if (type === 'pool') {
				const md = bpmnModelStore.getModelData();
				const existingPools = vm.getElementsForRendering().filter(
					(el: BpmnElement) => el.type === 'pool'
				);
				if (existingPools.length === 0) {
					// First Pool: bind to the existing primary process so the
					// canvas's current contents end up inside this pool's
					// <bpmn:process>. Existing root-level elements are migrated
					// below (after the pool's semantic exists in the store) so
					// ownerPoolOf() in the serializer can resolve them.
					element.processRef = md.processId;
					element.candidateStarterGroups = md.candidateStarterGroups;
					element.candidateStarterUsers = md.candidateStarterUsers;
					isFirstPoolDrop = true;
				} else {
					// Subsequent Pool: needs its own <bpmn:process>. Allocate a
					// new processId and append to processes[] so multi-pool
					// save/round-trip emits a separate process per pool.
					const newProcessId = generateId('Process');
					element.processRef = newProcessId;
					bpmnModelStore.setProcesses([
						...bpmnModelStore.getProcesses(),
						{ id: newProcessId, name: '', isExecutable: true },
					]);
				}
			}
			if (type === 'serviceTask') {
				element.implementation = 'class';
			}
			if (type === 'scriptTask') {
				element.scriptFormat = 'groovy';
			}
			if (type === 'intermediateEvent') {
				element.catching = true; // Default to Catching (receive)
			}

			vm.addElementDual(element);

			if (isFirstPoolDrop) {
				// Adopt every existing root-level node (no parentId, not a Pool
				// or Lane) so the serializer's ownerPoolOf() resolves them to
				// this Pool. Without this, dropping the first Pool would cause
				// the prior canvas contents to be silently dropped on save.
				bpmnModelStore.getAllSemantics().forEach(s => {
					if (s.id === element.id) return;
					if (s.type === 'pool' || s.type === 'lane') return;
					if (s.parentId) return;
					bpmnModelStore.updateSemantic(s.id, { parentId: element.id });
				});
			}

			vm.selectedIds = [element.id];
			vm.markModified();
		},

		deleteSelected() {
			const vm = this;
			if (vm.selectedIds.length === 0) return;

			// Find all boundary events attached to selected task elements
			const attachableTypes = [
				'userTask', 'serviceTask', 'scriptTask', 'manualTask',
				'sendTask', 'receiveTask', 'businessRuleTask', 'task', 'subProcess'
			];
			const boundaryEventsToDelete: string[] = [];
			const childElementsToDelete: string[] = [];

			vm.selectedIds.forEach((id: string) => {
				const element = vm.getElementsForRendering().find((e: BpmnElement) => e.id === id);
				if (element && attachableTypes.includes(element.type)) {
					// Find attached boundary events
					vm.getElementsForRendering()
						.filter((e: BpmnElement) => e.type === 'boundaryEvent' && e.attachedToRef === id)
						.forEach((be: BpmnElement) => {
							boundaryEventsToDelete.push(be.id);
						});
				}
				// Find child elements of selected subprocesses (recursively)
				if (element && element.type === 'subProcess') {
					const collectChildElements = (parentId: string) => {
						vm.getElementsForRendering()
							.filter((e: BpmnElement) => e.parentId === parentId)
							.forEach((child: BpmnElement) => {
								childElementsToDelete.push(child.id);
								if (child.type === 'subProcess') {
									collectChildElements(child.id);
								}
							});
					};
					collectChildElements(id);
				}
			});

			// Combine selected IDs with boundary events and child elements to delete
			const allIdsToDelete = [...vm.selectedIds, ...boundaryEventsToDelete, ...childElementsToDelete];

			// Separate element IDs and connection IDs
			const elementIds = allIdsToDelete.filter(id => vm.getElementsForRendering().some((e: BpmnElement) => e.id === id));
			const connectionIds = allIdsToDelete.filter(id => vm.getConnectionsForRenderingInternal().some((c: BpmnConnection) => c.id === id));

			// Remove connections first (selected connections and connections to deleted elements)
			const connectionsToRemove = vm.getConnectionsForRenderingInternal()
				.filter((c: BpmnConnection) =>
					connectionIds.includes(c.id) ||
					elementIds.includes(c.sourceRef) ||
					elementIds.includes(c.targetRef)
				)
				.map((c: BpmnConnection) => c.id);

			connectionsToRemove.forEach((connId: string) => {
				vm.removeConnectionDual(connId);
			});

			// Remove elements (including boundary events and child elements)
			elementIds.forEach((elId: string) => {
				vm.removeElementDual(elId);
			});

			vm.selectedIds = [];
			vm.markModified();
		},

		// ==========================================================================
		// Type Change Operations
		// ==========================================================================

		/**
		 * Show the type change menu for the selected element.
		 * Uses the shell-side common popup (instance.popup.open) so the menu
		 * can escape iframe overflow and renders icons via the shared
		 * assets/icons/icons.svg sprite (one symbol per BPMN sub-type).
		 */
		async showTypeChangeMenuFor(e: MouseEvent) {
			const vm = this;
			e.stopPropagation();

			const element = vm.selectedElement as BpmnElement | null;
			if (!element || !supportsTypeChange(element.type)) return;
			if (!vm.instance) return;

			// Anchor the popup to the wrench-icon DOM rect so the shell can
			// place it next to the trigger regardless of iframe position.
			const target = e.currentTarget as Element;
			const anchor = target.getBoundingClientRect();

			const options = vm.typeChangeOptions;
			const spriteUrl = new URL("assets/icons/icons.svg", window.location.origin + window.location.pathname).href
			const items: PopupItem[] = options.map((opt, idx) => ({
				id: idx,
				label: opt.label,
				iconSvg: `${spriteUrl}#${opt.icon || 'blank'}`,
				selected: vm.isCurrentSubTypeOption(opt),
			}));

			vm.closeTypeChangePopup();
			const handle = vm.instance.popup.open({
				anchor,
				placement: 'bottom-start',
				minWidth: 200,
				maxHeight: 360,
				items,
			});
			vm.typeChangePopupHandle = handle;
			const result = await handle.result;
			if (vm.typeChangePopupHandle === handle) vm.typeChangePopupHandle = null;
			if (result == null) return;
			const chosen = options[Number(result)];
			if (chosen) vm.changeElementType(chosen);
		},

		/**
		 * Close the active type-change popup if one is open.
		 */
		closeTypeChangePopup() {
			const vm = this;
			if (vm.typeChangePopupHandle) {
				vm.typeChangePopupHandle.close();
				vm.typeChangePopupHandle = null;
			}
		},

		/**
		 * Change the type of the selected element
		 */
		changeElementType(option: SubTypeOption) {
			const vm = this;
			const element = vm.selectedElement as BpmnElement | null;
			if (!element) return;

			const newSubType = option.subType;
			const newCatching = option.catching;
			const oldSubType = getElementSubType(element);
			const oldCatching = element.catching;

			// For intermediate events, check both subType and catching flag
			if (element.type === 'intermediateEvent') {
				if (oldSubType === newSubType && oldCatching === newCatching) {
					vm.closeTypeChangePopup();
					return;
				}
			} else if (oldSubType === newSubType) {
				vm.closeTypeChangePopup();
				return;
			}

			// Execute type change with undo/redo support
			const elementId = element.id;
			const oldType = element.type;

			// Determine new type based on subType
			let newType = oldType;

			// For tasks, update the type based on subType
			if (oldType === 'task' || oldType.endsWith('Task') || oldType === 'callActivity' || ['userTask', 'serviceTask', 'scriptTask', 'manualTask', 'sendTask', 'receiveTask', 'businessRuleTask', 'callActivity'].includes(oldType)) {
				switch (newSubType) {
					case 'user': newType = 'userTask'; break;
					case 'service': newType = 'serviceTask'; break;
					case 'script': newType = 'scriptTask'; break;
					case 'manual': newType = 'manualTask'; break;
					case 'send': newType = 'sendTask'; break;
					case 'receive': newType = 'receiveTask'; break;
					case 'businessRule': newType = 'businessRuleTask'; break;
					case 'callActivity': newType = 'callActivity'; break;
					default: newType = oldType; break;
				}
			}

			// For gateways, update the type based on subType
			if (oldType.includes('Gateway')) {
				switch (newSubType) {
					case 'exclusive': newType = 'exclusiveGateway'; break;
					case 'parallel': newType = 'parallelGateway'; break;
					case 'inclusive': newType = 'inclusiveGateway'; break;
					case 'eventBased': newType = 'eventBasedGateway'; break;
					case 'complex': newType = 'complexGateway'; break;
					default: newType = oldType; break;
				}
			}

			// For sub-processes, handle size changes and special flags
			const oldWidth = element.width;
			const oldHeight = element.height;
			const oldTriggeredByEvent = element.triggeredByEvent;
			const oldIsExpanded = element.isExpanded;
			let newWidth = oldWidth;
			let newHeight = oldHeight;
			let newTriggeredByEvent = oldTriggeredByEvent;
			let newIsExpanded = oldIsExpanded;

			if (oldType === 'subProcess') {
				if (newSubType === 'expanded') {
					newWidth = ELEMENT_SIZES.expandedSubProcess.width;
					newHeight = ELEMENT_SIZES.expandedSubProcess.height;
					newIsExpanded = true;
					newTriggeredByEvent = false;
				} else if (newSubType === 'collapsed') {
					newWidth = ELEMENT_SIZES.subProcess.width;
					newHeight = ELEMENT_SIZES.subProcess.height;
					newIsExpanded = false;
					newTriggeredByEvent = false;
				} else if (newSubType === 'event') {
					// Event sub-process can be collapsed or expanded
					// Keep current size, just set triggeredByEvent
					newTriggeredByEvent = true;
				} else if (newSubType === 'transaction') {
					// Transaction keeps its size but needs expanded view
					newWidth = ELEMENT_SIZES.transaction.width;
					newHeight = ELEMENT_SIZES.transaction.height;
					newIsExpanded = true;
					newTriggeredByEvent = false;
				}
			}

			// Create command for undo/redo
			const command: Command = {
				execute: () => {
					const changes: Partial<BpmnSemantic> = {
						type: newType,
						subType: newSubType,
					};
					if (newType === 'intermediateEvent' && newCatching !== undefined) {
						changes.catching = newCatching;
					}
					if (newType === 'subProcess') {
						changes.triggeredByEvent = newTriggeredByEvent;
					}
					bpmnModelStore.updateSemantic(elementId, changes);
					if (newType === 'subProcess') {
						const shapes = bpmnModelStore.getShapesForSemantic(elementId);
						if (shapes[0]) {
							bpmnModelStore.updateShape(shapes[0].id, {
								bounds: { ...shapes[0].bounds, width: newWidth, height: newHeight },
								isExpanded: newIsExpanded,
							});
						}
					}
					vm.storeVersion++;
				},
				undo: () => {
					const changes: Partial<BpmnSemantic> = {
						type: oldType,
						subType: oldSubType,
					};
					if (oldType === 'intermediateEvent') {
						changes.catching = oldCatching;
					}
					if (oldType === 'subProcess') {
						changes.triggeredByEvent = oldTriggeredByEvent;
					}
					bpmnModelStore.updateSemantic(elementId, changes);
					if (oldType === 'subProcess') {
						const shapes = bpmnModelStore.getShapesForSemantic(elementId);
						if (shapes[0]) {
							bpmnModelStore.updateShape(shapes[0].id, {
								bounds: { ...shapes[0].bounds, width: oldWidth, height: oldHeight },
								isExpanded: oldIsExpanded,
							});
						}
					}
					vm.storeVersion++;
				},
			};

			vm.commandManager.execute(command);
			vm.markModified();
			vm.closeTypeChangePopup();
		},

		/**
		 * Check if the given option matches the current element's subType and catching flag
		 */
		isCurrentSubTypeOption(option: SubTypeOption): boolean {
			const element = this.selectedElement as BpmnElement | null;
			if (!element) return false;

			const currentSubType = getElementSubType(element);

			// For intermediate events, also check catching flag
			if (element.type === 'intermediateEvent') {
				// Normalize catching flag: undefined or true means catching, false means throwing
				const elementCatching = element.catching !== false;
				const optionCatching = option.catching !== false;
				return currentSubType === option.subType && elementCatching === optionCatching;
			}

			return currentSubType === option.subType;
		},

		/**
		 * Get the icon SVG for a subType
		 */
		getSubTypeIcon(icon: string | undefined): string {
			if (!icon) return '';
			// Return empty string for now - icons will be rendered via CSS or inline SVG
			return '';
		},

		// ==========================================================================
		// Connection Operations
		// ==========================================================================

		onConnectionPointMouseDown(e: MouseEvent, element: BpmnElement, pointX: number, pointY: number) {
			const vm = this;
			e.stopPropagation();

			// Start connection drawing
			vm.isConnecting = true;
			vm.connectionSourceId = element.id;
			vm.connectionSourcePoint = {
				x: element.x + pointX,
				y: element.y + pointY,
			};
			vm.connectionSourceSide = vm.getSideForLocalPoint(element, pointX, pointY);
			vm.connectionEndPoint = { ...vm.connectionSourcePoint };
		},

		/**
		 * Handle mouseup on a connection point during connection drawing.
		 * This allows direct port-to-port connection like camel-route-editor.
		 *
		 * pointX/pointY are local offsets of the connection-point inside `element`.
		 * Passing them through the template lets us pin the drop side to the exact
		 * connection point the user released over, instead of inferring from the
		 * (possibly stale or imprecise) cursor position.
		 */
		onConnectionPointMouseUp(e: MouseEvent, element: BpmnElement, pointX?: number, pointY?: number) {
			const vm = this;
			// connection-point stops mouseup propagation, so we must handle
			// both new-connection completion and reconnect-handle drop here —
			// otherwise canvas-level mouseup never fires when the user
			// releases on a connection point and the drag would appear stuck.
			e.stopPropagation();

			const droppedSide: 'N' | 'S' | 'E' | 'W' | null = (pointX !== undefined && pointY !== undefined)
				? vm.getSideForLocalPoint(element, pointX, pointY)
				: null;

			if (vm.isConnecting && vm.connectionSourceId && vm.connectionSourceId !== element.id) {
				const targetSide = droppedSide ?? vm.getSideForCanvasPoint(
					element,
					vm.connectionEndPoint.x,
					vm.connectionEndPoint.y
				);
				vm.createConnection(vm.connectionSourceId, element.id, vm.connectionSourceSide, targetSide);
				vm.isConnecting = false;
				vm.connectionSourceId = '';
				vm.connectionSourceSide = null;
				vm.connectionPreview = '';
				vm.connectionTargetIds = [];
				return;
			}

			if (vm.isReconnecting) {
				const dropX = pointX !== undefined ? element.x + pointX : vm.reconnectEndPoint.x;
				const dropY = pointY !== undefined ? element.y + pointY : vm.reconnectEndPoint.y;
				vm.finalizeReconnectAt(dropX, dropY, element, droppedSide);
			}
		},

		findElementAtPoint(x: number, y: number): BpmnElement | null {
			const vm = this;
			const all = vm.getElementsForRendering();
			// Reverse order so the topmost (most recently added) element wins.
			for (let i = all.length - 1; i >= 0; i--) {
				const el = all[i];
				if (x >= el.x && x <= el.x + el.width &&
					y >= el.y && y <= el.y + el.height) {
					return el;
				}
			}
			return null;
		},

		createConnection(
			sourceId: string,
			targetId: string,
			sourceSide?: 'N' | 'S' | 'E' | 'W' | null,
			targetSide?: 'N' | 'S' | 'E' | 'W' | null
		) {
			const vm = this;

			// Check if connection already exists
			const exists = vm.getConnectionsForRenderingInternal().some(
				c => c.sourceRef === sourceId && c.targetRef === targetId
			);
			if (exists) return;

			// Find source and target elements to determine connection type
			const source = vm.getElementsForRendering().find(e => e.id === sourceId);
			const target = vm.getElementsForRendering().find(e => e.id === targetId);

			// Determine connection type based on source/target element types
			let connectionType: 'sequenceFlow' | 'dataAssociation' | 'association' = 'sequenceFlow';
			if (source && target) {
				// Text Annotation uses association (dotted line, no arrow)
				if (source.type === 'textAnnotation' || target.type === 'textAnnotation') {
					connectionType = 'association';
				}
				// Data Object/Store uses dataAssociation (dashed line)
				else if (source.type === 'dataObject' || source.type === 'dataStore' ||
					target.type === 'dataObject' || target.type === 'dataStore') {
					connectionType = 'dataAssociation';
				}
			}

			// Generate appropriate ID based on connection type
			let idPrefix = 'Flow';
			if (connectionType === 'dataAssociation') {
				idPrefix = 'DataAssociation';
			} else if (connectionType === 'association') {
				idPrefix = 'Association';
			}

			const connection: BpmnConnection = {
				id: generateId(idPrefix),
				sourceRef: sourceId,
				targetRef: targetId,
				name: '',
				connectionType: connectionType,
			};

			// Direction-aware initial routing for sequence flows when both sides
			// are known. For associations and data associations the existing
			// straight-line / center-based routing is more appropriate, so leave
			// waypoints empty in those cases.
			if (source && target && sourceSide && targetSide && connectionType === 'sequenceFlow') {
				connection.sourceSide = sourceSide;
				connection.targetSide = targetSide;
				connection.waypoints = vm.buildSideAwareWaypoints(source, sourceSide, target, targetSide);
			}

			vm.addConnectionDual(connection);
			vm.selectedIds = [connection.id];
			vm.markModified();
		},

		/**
		 * Calculate the intersection point of a line from element center to external point
		 * with the element's perimeter (boundary).
		 * Handles both rectangles (Tasks, Events) and diamonds (Gateways).
		 * @param bounds The bounds of the element
		 * @param externalPoint The external point to draw a line to
		 * @param elementType The type of element (to determine shape)
		 * @returns The intersection point on the element's perimeter
		 */
		getPerimeterPoint(
			bounds: { x: number; y: number; width: number; height: number },
			externalPoint: { x: number; y: number },
			elementType: string
		): { x: number; y: number } {
			const cx = bounds.x + bounds.width / 2;
			const cy = bounds.y + bounds.height / 2;
			const dx = externalPoint.x - cx;
			const dy = externalPoint.y - cy;

			// If external point is at the center, return center
			if (dx === 0 && dy === 0) {
				return { x: cx, y: cy };
			}

			// Check if element is a Gateway (diamond shape)
			const isGateway = elementType.includes('Gateway');

			if (isGateway) {
				// Diamond shape: corners at N, S, E, W of bounds center
				// Half-widths from center to diamond corners
				const hw = bounds.width / 2;
				const hh = bounds.height / 2;

				// Diamond edges: |x/hw| + |y/hh| = 1
				// For a ray from center (0,0) to (dx, dy), find t where:
				// |t*dx/hw| + |t*dy/hh| = 1
				// t = 1 / (|dx|/hw + |dy|/hh)
				const t = 1 / (Math.abs(dx) / hw + Math.abs(dy) / hh);
				return {
					x: cx + dx * t,
					y: cy + dy * t
				};
			} else {
				// Rectangle shape (Tasks, Events, etc.)
				const hw = bounds.width / 2;
				const hh = bounds.height / 2;

				// Calculate intersection with rectangle edges
				// Use parametric form: (cx + t*dx, cy + t*dy)
				// Find smallest positive t that hits an edge

				let t = Infinity;

				// Right edge: cx + t*dx = bounds.x + bounds.width
				if (dx > 0) {
					const tRight = hw / dx;
					if (tRight < t) t = tRight;
				}
				// Left edge: cx + t*dx = bounds.x
				if (dx < 0) {
					const tLeft = -hw / dx;
					if (tLeft < t) t = tLeft;
				}
				// Bottom edge: cy + t*dy = bounds.y + bounds.height
				if (dy > 0) {
					const tBottom = hh / dy;
					if (tBottom < t) t = tBottom;
				}
				// Top edge: cy + t*dy = bounds.y
				if (dy < 0) {
					const tTop = -hh / dy;
					if (tTop < t) t = tTop;
				}

				return {
					x: cx + dx * t,
					y: cy + dy * t
				};
			}
		},

		/**
		 * Determine the best compass point (N, S, E, W) for connecting to a target element.
		 * Returns the anchor point on the source element's edge that best faces the target.
		 * @param sourceBounds The bounds of the source element
		 * @param targetCenter The center point of the target element
		 * @param sourceType The type of the source element
		 * @returns The best compass point on the source element
		 */
		getBestCompassPoint(
			sourceBounds: { x: number; y: number; width: number; height: number },
			targetCenter: { x: number; y: number },
			sourceType: string
		): { x: number; y: number; direction: 'N' | 'S' | 'E' | 'W' } {
			const cx = sourceBounds.x + sourceBounds.width / 2;
			const cy = sourceBounds.y + sourceBounds.height / 2;
			const dx = targetCenter.x - cx;
			const dy = targetCenter.y - cy;

			const isGateway = sourceType.includes('Gateway');
			const hw = sourceBounds.width / 2;
			const hh = sourceBounds.height / 2;

			// Determine primary direction based on angle
			// Compare normalized distances to decide N/S/E/W
			const normalizedDx = Math.abs(dx) / hw;
			const normalizedDy = Math.abs(dy) / hh;

			let direction: 'N' | 'S' | 'E' | 'W';
			let point: { x: number; y: number };

			if (normalizedDx > normalizedDy) {
				// Horizontal dominant: E or W
				if (dx > 0) {
					direction = 'E';
					point = isGateway
						? { x: cx + hw, y: cy }
						: { x: sourceBounds.x + sourceBounds.width, y: cy };
				} else {
					direction = 'W';
					point = isGateway
						? { x: cx - hw, y: cy }
						: { x: sourceBounds.x, y: cy };
				}
			} else {
				// Vertical dominant: N or S
				if (dy > 0) {
					direction = 'S';
					point = isGateway
						? { x: cx, y: cy + hh }
						: { x: cx, y: sourceBounds.y + sourceBounds.height };
				} else {
					direction = 'N';
					point = isGateway
						? { x: cx, y: cy - hh }
						: { x: cx, y: sourceBounds.y };
				}
			}

			return { ...point, direction };
		},

		/**
		 * Determine the compass side (N/S/E/W) of a point given as a local offset
		 * inside an element (relative to its top-left corner). Used when the user
		 * clicks one of the static connection-point circles on an element.
		 */
		getSideForLocalPoint(
			element: { type: string; width?: number; height?: number },
			offsetX: number,
			offsetY: number
		): 'N' | 'S' | 'E' | 'W' {
			const size = ELEMENT_SIZES[element.type] || { width: 100, height: 80 };
			const w = element.width || size.width;
			const h = element.height || size.height;
			const distLeft = offsetX;
			const distRight = w - offsetX;
			const distTop = offsetY;
			const distBottom = h - offsetY;
			const min = Math.min(distLeft, distRight, distTop, distBottom);
			if (min === distLeft) return 'W';
			if (min === distRight) return 'E';
			if (min === distTop) return 'N';
			return 'S';
		},

		/**
		 * Determine the compass side of an element that an absolute canvas point
		 * lies closest to. Used when the user drops a connection on the body of
		 * an element (not on a specific connection point).
		 */
		getSideForCanvasPoint(
			element: { x: number; y: number; type: string; width?: number; height?: number },
			canvasX: number,
			canvasY: number
		): 'N' | 'S' | 'E' | 'W' {
			const size = ELEMENT_SIZES[element.type] || { width: 100, height: 80 };
			const w = element.width || size.width;
			const h = element.height || size.height;
			const cx = element.x + w / 2;
			const cy = element.y + h / 2;
			const halfW = w / 2 || 1;
			const halfH = h / 2 || 1;
			const ndx = (canvasX - cx) / halfW;
			const ndy = (canvasY - cy) / halfH;
			if (Math.abs(ndx) >= Math.abs(ndy)) {
				return ndx >= 0 ? 'E' : 'W';
			}
			return ndy >= 0 ? 'S' : 'N';
		},

		/**
		 * Absolute coordinates of the connection-point that lies on the given
		 * compass side of an element. Diamonds (Gateways) and rectangles share
		 * the same N/S/E/W anchors at the midpoint of each side / vertex.
		 */
		getElementSideAnchor(
			element: { x: number; y: number; type: string; width?: number; height?: number },
			side: 'N' | 'S' | 'E' | 'W'
		): { x: number; y: number } {
			const size = ELEMENT_SIZES[element.type] || { width: 100, height: 80 };
			const w = element.width || size.width;
			const h = element.height || size.height;
			switch (side) {
				case 'N': return { x: element.x + w / 2, y: element.y };
				case 'S': return { x: element.x + w / 2, y: element.y + h };
				case 'W': return { x: element.x,         y: element.y + h / 2 };
				case 'E': return { x: element.x + w,     y: element.y + h / 2 };
			}
		},

		/**
		 * Infer source/target sides from existing waypoints by inspecting the
		 * first and last segment orientations. Used to recover routing intent
		 * for connections loaded from XML (which doesn't persist side hints).
		 */
		inferSidesFromWaypoints(
			waypoints: { x: number; y: number }[],
			source: { x: number; y: number; type: string; width?: number; height?: number },
			target: { x: number; y: number; type: string; width?: number; height?: number }
		): { sourceSide: 'N' | 'S' | 'E' | 'W'; targetSide: 'N' | 'S' | 'E' | 'W' } {
			const vm = this;
			const first = waypoints[0];
			const second = waypoints[1] || waypoints[waypoints.length - 1];
			const last = waypoints[waypoints.length - 1];
			const beforeLast = waypoints[waypoints.length - 2] || first;

			const sourceSide: 'N' | 'S' | 'E' | 'W' = (() => {
				const dx = (second.x - first.x);
				const dy = (second.y - first.y);
				if (Math.abs(dx) >= Math.abs(dy)) {
					return dx >= 0 ? 'E' : 'W';
				}
				return dy >= 0 ? 'S' : 'N';
			})();
			const targetSide: 'N' | 'S' | 'E' | 'W' = (() => {
				const dx = (last.x - beforeLast.x);
				const dy = (last.y - beforeLast.y);
				if (Math.abs(dx) >= Math.abs(dy)) {
					// Approaching from W when moving +X, from E when moving -X.
					return dx >= 0 ? 'W' : 'E';
				}
				return dy >= 0 ? 'N' : 'S';
			})();
			void vm; void source; void target;
			return { sourceSide, targetSide };
		},

		/**
		 * Direction-aware orthogonal routing. Given absolute start/end points and
		 * the compass sides at which the path exits the source / enters the
		 * target, produce a list of waypoints (always in canvas coordinates).
		 *
		 * The first segment always extends from the source in the direction of
		 * sourceSide; the last segment always approaches the target along
		 * targetSide. When source and target axes don't align with their relative
		 * geometry, a stub detour is added so the line cannot back-track into
		 * the element it just left.
		 */
		computeRouteWaypoints(
			start: { x: number; y: number },
			sourceSide: 'N' | 'S' | 'E' | 'W',
			end: { x: number; y: number },
			targetSide: 'N' | 'S' | 'E' | 'W'
		): { x: number; y: number }[] {
			const STUB = 30;
			const sourceVert = sourceSide === 'N' || sourceSide === 'S';
			const targetVert = targetSide === 'N' || targetSide === 'S';
			// sourceDir is +1 when the line leaves the source toward +axis, -1 toward -axis.
			const sourceDir = (sourceSide === 'S' || sourceSide === 'E') ? +1 : -1;
			// targetEntryDir is the direction of the FINAL segment heading INTO the target.
			// targetSide=N: the line enters from above going +Y → +1
			// targetSide=S: enters from below going -Y → -1
			// targetSide=W: enters from left going +X → +1
			// targetSide=E: enters from right going -X → -1
			const targetEntryDir = (targetSide === 'N' || targetSide === 'W') ? +1 : -1;

			if (sourceVert && targetVert) {
				const dy = end.y - start.y;
				if (sourceDir * dy > 0 && targetEntryDir * dy > 0 && Math.abs(dy) > 1) {
					if (Math.abs(start.x - end.x) < 1) {
						return [start, end];
					}
					const midY = (start.y + end.y) / 2;
					return [start, { x: start.x, y: midY }, { x: end.x, y: midY }, end];
				}
				const escapeY = (sourceDir > 0)
					? Math.max(start.y, end.y) + STUB
					: Math.min(start.y, end.y) - STUB;
				return [start, { x: start.x, y: escapeY }, { x: end.x, y: escapeY }, end];
			}

			if (!sourceVert && !targetVert) {
				const dx = end.x - start.x;
				if (sourceDir * dx > 0 && targetEntryDir * dx > 0 && Math.abs(dx) > 1) {
					if (Math.abs(start.y - end.y) < 1) {
						return [start, end];
					}
					const midX = (start.x + end.x) / 2;
					return [start, { x: midX, y: start.y }, { x: midX, y: end.y }, end];
				}
				const escapeX = (sourceDir > 0)
					? Math.max(start.x, end.x) + STUB
					: Math.min(start.x, end.x) - STUB;
				return [start, { x: escapeX, y: start.y }, { x: escapeX, y: end.y }, end];
			}

			if (sourceVert && !targetVert) {
				const dx = end.x - start.x;
				const dy = end.y - start.y;
				if (sourceDir * dy > 0 && targetEntryDir * dx > 0) {
					return [start, { x: start.x, y: end.y }, end];
				}
				const escapeY = (sourceDir > 0)
					? Math.max(start.y, end.y) + STUB
					: Math.min(start.y, end.y) - STUB;
				const escapeX = (targetEntryDir > 0) ? end.x - STUB : end.x + STUB;
				return [start, { x: start.x, y: escapeY }, { x: escapeX, y: escapeY }, { x: escapeX, y: end.y }, end];
			}

			// !sourceVert && targetVert
			const dx = end.x - start.x;
			const dy = end.y - start.y;
			if (sourceDir * dx > 0 && targetEntryDir * dy > 0) {
				return [start, { x: end.x, y: start.y }, end];
			}
			const escapeX = (sourceDir > 0)
				? Math.max(start.x, end.x) + STUB
				: Math.min(start.x, end.x) - STUB;
			const escapeY = (targetEntryDir > 0) ? end.y - STUB : end.y + STUB;
			return [start, { x: escapeX, y: start.y }, { x: escapeX, y: escapeY }, { x: end.x, y: escapeY }, end];
		},

		/**
		 * Build initial waypoints for a connection from the source element / side
		 * to the target element / side. Returns absolute coordinates.
		 */
		buildSideAwareWaypoints(
			sourceEl: { x: number; y: number; type: string; width?: number; height?: number },
			sourceSide: 'N' | 'S' | 'E' | 'W',
			targetEl: { x: number; y: number; type: string; width?: number; height?: number },
			targetSide: 'N' | 'S' | 'E' | 'W'
		): { x: number; y: number }[] {
			const vm = this;
			const start = vm.getElementSideAnchor(sourceEl, sourceSide);
			const end = vm.getElementSideAnchor(targetEl, targetSide);
			return vm.computeRouteWaypoints(start, sourceSide, end, targetSide);
		},

		/**
		 * Walk up the parentId chain of an element to find its containing Pool.
		 * Returns the Pool's ID, or undefined if the element is not inside any Pool.
		 */
		getContainingPoolId(elementId: string | undefined): string | undefined {
			if (!elementId) return undefined;
			let currentId: string | undefined = elementId;
			const visited = new Set<string>();
			while (currentId && !visited.has(currentId)) {
				visited.add(currentId);
				const semantic = bpmnModelStore.getSemantic(currentId);
				if (!semantic) return undefined;
				if (semantic.type === 'pool') return semantic.id;
				currentId = semantic.parentId;
			}
			return undefined;
		},

		/**
		 * A connection is rendered as a Message Flow when:
		 *   - its connectionType is explicitly 'messageFlow', or
		 *   - source and target reside in different Pools (BPMN spec: cross-pool
		 *     connections must be Message Flows, not Sequence Flows).
		 */
		isMessageFlowConnection(conn: BpmnConnection): boolean {
			if (!conn) return false;
			if (conn.connectionType === 'messageFlow') return true;
			// Only sequenceFlow (or unspecified) is auto-promoted; data/association
			// connections keep their own visual treatment.
			if (conn.connectionType && conn.connectionType !== 'sequenceFlow') return false;
			const sourcePool = this.getContainingPoolId(conn.sourceRef);
			const targetPool = this.getContainingPoolId(conn.targetRef);
			if (!sourcePool || !targetPool) return false;
			return sourcePool !== targetPool;
		},

		getConnectionPath(conn: BpmnConnection): string {
			// Reactive tag: ichigo.js's static analyzer only tracks `this.xxx`
			// MemberExpressions, so the `vm.xxx` reads below are invisible to it.
			// Without this line the `:d="getConnectionPath(conn)"` binding has no
			// dependency on storeVersion and won't re-evaluate when an element is
			// dragged — connections then render at their pre-drag endpoints. This
			// reference forces the binding to refresh after any model mutation.
			void this.storeVersion;
			const vm = this;

			// 1. Get element bounds (Store/Legacy compatible)
			const source = vm.getElementById(conn.sourceRef);
			const target = vm.getElementById(conn.targetRef);

			if (!source || !target) return '';

			const sourceBounds = { x: source.x, y: source.y, width: source.width, height: source.height };
			const targetBounds = { x: target.x, y: target.y, width: target.width, height: target.height };

			// 2. If waypoints exist, use them as-is. They represent either the
			//    direction-aware initial path computed at creation time, the
			//    user's manual bend-point edits, or imported XML.
			if (conn.waypoints && conn.waypoints.length > 1) {
				// NaN check: fall back to auto-calculation if data is corrupted
				const isValid = conn.waypoints.every(p => !isNaN(p.x) && !isNaN(p.y));
				if (isValid) {
					let d = `M ${conn.waypoints[0].x} ${conn.waypoints[0].y}`;
					for (let i = 1; i < conn.waypoints.length; i++) {
						d += ` L ${conn.waypoints[i].x} ${conn.waypoints[i].y}`;
					}
					return d;
				}
			}

			// Element center coordinates
			const sCx = sourceBounds.x + sourceBounds.width / 2;
			const sCy = sourceBounds.y + sourceBounds.height / 2;
			const tCx = targetBounds.x + targetBounds.width / 2;
			const tCy = targetBounds.y + targetBounds.height / 2;

			// 3. Association (annotation dotted line) uses straight line
			// with perimeter intersection (line stops at element border, not center)
			if (conn.connectionType === 'association') {
				// Calculate perimeter points for both source and target
				const sourcePerimeter = vm.getPerimeterPoint(sourceBounds, { x: tCx, y: tCy }, source.type);
				const targetPerimeter = vm.getPerimeterPoint(targetBounds, { x: sCx, y: sCy }, target.type);
				return `M ${sourcePerimeter.x} ${sourcePerimeter.y} L ${targetPerimeter.x} ${targetPerimeter.y}`;
			}

			// 3b. Side-aware routing when source/target sides are known.
			if (conn.sourceSide && conn.targetSide) {
				const wps = vm.buildSideAwareWaypoints(source, conn.sourceSide, target, conn.targetSide);
				let d = `M ${wps[0].x} ${wps[0].y}`;
				for (let i = 1; i < wps.length; i++) d += ` L ${wps[i].x} ${wps[i].y}`;
				return d;
			}

			// 4. Auto-calculate: Manhattan routing with buffer-based turning
			// Buffer distance: how far from element to turn (px)
			const BUFFER = 20;

			// Get best compass points for source and target
			const sourceAnchor = vm.getBestCompassPoint(sourceBounds, { x: tCx, y: tCy }, source.type);
			const targetAnchor = vm.getBestCompassPoint(targetBounds, { x: sCx, y: sCy }, target.type);

			const start = { x: sourceAnchor.x, y: sourceAnchor.y };
			const end = { x: targetAnchor.x, y: targetAnchor.y };

			// Determine if starting horizontally or vertically
			// (if Y difference from center is small, we're exiting horizontally)
			const isHorzStart = Math.abs(start.y - sCy) < 1;

			let p1: { x: number; y: number };
			let p2: { x: number; y: number };

			if (isHorzStart) {
				// Horizontal exit: turn shortly after leaving source
				const dirX = end.x > start.x ? 1 : -1;
				const turnX = start.x + (dirX * BUFFER);

				p1 = { x: turnX, y: start.y };
				p2 = { x: turnX, y: end.y };
			} else {
				// Vertical exit: turn shortly after leaving source
				const dirY = end.y > start.y ? 1 : -1;
				const turnY = start.y + (dirY * BUFFER);

				p1 = { x: start.x, y: turnY };
				p2 = { x: end.x, y: turnY };
			}

			return `M ${start.x} ${start.y} L ${p1.x} ${p1.y} L ${p2.x} ${p2.y} L ${end.x} ${end.y}`;
		},

		/**
		 * Get the source and target endpoint coordinates for a connection
		 * Used for rendering reconnect handles
		 * Must match the logic in getConnectionPath for consistency
		 */
		getConnectionEndpoints(conn: BpmnConnection): { source: { x: number; y: number }; target: { x: number; y: number } } | null {
			// See getConnectionPath: tags this method as storeVersion-dependent for
			// ichigo.js's static dependency analyzer. Reconnect handles bind to
			// `getConnectionEndpoints(selectedConnection).source.x` etc., which would
			// otherwise stay pinned to the pre-drag endpoint.
			void this.storeVersion;
			const vm = this;
			const source = vm.getElementById(conn.sourceRef);
			const target = vm.getElementById(conn.targetRef);

			if (!source || !target) return null;

			const sourceBounds = { x: source.x, y: source.y, width: source.width, height: source.height };
			const targetBounds = { x: target.x, y: target.y, width: target.width, height: target.height };

			// If waypoints are defined, use first and last points
			if (conn.waypoints && conn.waypoints.length >= 2) {
				return {
					source: { x: conn.waypoints[0].x, y: conn.waypoints[0].y },
					target: { x: conn.waypoints[conn.waypoints.length - 1].x, y: conn.waypoints[conn.waypoints.length - 1].y }
				};
			}

			// Calculate dynamically using same logic as getConnectionPath
			const sCx = sourceBounds.x + sourceBounds.width / 2;
			const sCy = sourceBounds.y + sourceBounds.height / 2;
			const tCx = targetBounds.x + targetBounds.width / 2;
			const tCy = targetBounds.y + targetBounds.height / 2;

			// Association uses perimeter points
			if (conn.connectionType === 'association') {
				const sourcePerimeter = vm.getPerimeterPoint(sourceBounds, { x: tCx, y: tCy }, source.type);
				const targetPerimeter = vm.getPerimeterPoint(targetBounds, { x: sCx, y: sCy }, target.type);
				return {
					source: sourcePerimeter,
					target: targetPerimeter
				};
			}

			// Side-aware routing when source/target sides are known.
			if (conn.sourceSide && conn.targetSide) {
				const wps = vm.buildSideAwareWaypoints(source, conn.sourceSide, target, conn.targetSide);
				return {
					source: wps[0],
					target: wps[wps.length - 1],
				};
			}

			// Sequence flows use best compass points
			const sourceAnchor = vm.getBestCompassPoint(sourceBounds, { x: tCx, y: tCy }, source.type);
			const targetAnchor = vm.getBestCompassPoint(targetBounds, { x: sCx, y: sCy }, target.type);

			return {
				source: { x: sourceAnchor.x, y: sourceAnchor.y },
				target: { x: targetAnchor.x, y: targetAnchor.y }
			};
		},

		/**
		 * Get the position for a connection label (on the first segment of the path)
		 */
		getConnectionLabelPosition(conn: BpmnConnection): { x: number; y: number } {
			// See getConnectionPath: tags this method as storeVersion-dependent so
			// the connection label follows when its source element is moved.
			void this.storeVersion;
			const vm = this;

			// If custom offsets are set, use absolute positioning
			if (conn.labelOffsetX !== undefined && conn.labelOffsetY !== undefined) {
				return { x: conn.labelOffsetX, y: conn.labelOffsetY };
			}

			const source = vm.getElementsForRendering().find((e: BpmnElement) => e.id === conn.sourceRef);
			const target = vm.getElementsForRendering().find((e: BpmnElement) => e.id === conn.targetRef);

			if (!source || !target) return { x: 0, y: 0 };

			// Prefer the stored compass side when available — this keeps the
			// label aligned with the actual exit direction of the line.
			if (conn.sourceSide) {
				const anchor = vm.getElementSideAnchor(source, conn.sourceSide);
				const labelOffset = 30;
				const verticalOffset = -8;
				switch (conn.sourceSide) {
					case 'E': return { x: anchor.x + labelOffset, y: anchor.y + verticalOffset };
					case 'W': return { x: anchor.x - labelOffset, y: anchor.y + verticalOffset };
					case 'S': return { x: anchor.x + 15,          y: anchor.y + labelOffset };
					case 'N': return { x: anchor.x + 15,          y: anchor.y - labelOffset };
				}
			}

			// Use the same logic as getConnectionPath to determine connection points
			const sourceCenter = getElementCenter(source);
			const targetCenter = getElementCenter(target);

			const dx = targetCenter.x - sourceCenter.x;
			const dy = targetCenter.y - sourceCenter.y;
			const absDx = Math.abs(dx);
			const absDy = Math.abs(dy);
			const alignThreshold = 30;

			let sourceSide: 'left' | 'right' | 'top' | 'bottom';

			if (absDy < alignThreshold && absDx > alignThreshold) {
				sourceSide = dx > 0 ? 'right' : 'left';
			} else if (absDx < alignThreshold && absDy > alignThreshold) {
				sourceSide = dy > 0 ? 'bottom' : 'top';
			} else {
				const verticalRatio = absDy / (absDx + 1);
				const isSourceGateway = source.type.includes('Gateway');

				if (verticalRatio > 0.5) {
					sourceSide = isSourceGateway ? (dy > 0 ? 'bottom' : 'top') : (dx > 0 ? 'right' : 'left');
				} else {
					sourceSide = dx > 0 ? 'right' : 'left';
				}
			}

			const sourcePoint = getConnectionPoint(source, sourceSide);

			// Position label on the first segment, offset from source
			const labelOffset = 30; // Distance along the first segment
			const verticalOffset = -8; // Offset above/beside the line

			if (sourceSide === 'right') {
				return { x: sourcePoint.x + labelOffset, y: sourcePoint.y + verticalOffset };
			} else if (sourceSide === 'left') {
				return { x: sourcePoint.x - labelOffset, y: sourcePoint.y + verticalOffset };
			} else if (sourceSide === 'bottom') {
				return { x: sourcePoint.x + 15, y: sourcePoint.y + labelOffset };
			} else {
				return { x: sourcePoint.x + 15, y: sourcePoint.y - labelOffset };
			}
		},

		/**
		 * Get the label position for a Gateway element.
		 * Places label at a diagonal corner position to avoid overlap with connection lines.
		 * Connection lines enter/exit from the 4 cardinal directions (top, bottom, left, right),
		 * so diagonal corners are safe positions for labels.
		 */
		getGatewayLabelPosition(element: BpmnElement): { x: number; y: number; style: string } {
			// Default position: top-left diagonal corner (outside the diamond)
			// This position doesn't conflict with connection lines which use cardinal directions
			// The diamond shape has vertices at (25,0), (50,25), (25,50), (0,25)
			const defaultX = -8;
			const defaultY = -8;

			// Use custom offsets if set, otherwise use defaults
			const x = (element.labelOffsetX !== undefined) ? element.labelOffsetX : defaultX;
			const y = (element.labelOffsetY !== undefined) ? element.labelOffsetY : defaultY;

			return { x, y, style: 'text-anchor: end; dominant-baseline: auto;' };
		},

		/**
		 * Get the name of the element that a boundary event is attached to.
		 */
		getAttachedToName(attachedToRef: string | undefined): string {
			const vm = this;
			if (!attachedToRef) return '(none)';
			const el = vm.getElementsForRendering().find((e: BpmnElement) => e.id === attachedToRef);
			return el ? (el.name || el.id) : '(unknown)';
		},

		// ==========================================================================
		// Zoom Operations
		// ==========================================================================

		zoomIn() {
			this.zoom = Math.min(3, this.zoom * 1.2);
		},

		zoomOut() {
			this.zoom = Math.max(0.1, this.zoom / 1.2);
		},

		fitToScreen() {
			const vm = this;
			if (vm.getElementsForRendering().length === 0) {
				vm.zoom = 1;
				vm.panX = 100;
				vm.panY = 100;
				return;
			}

			// Calculate bounding box
			let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
			vm.getElementsForRendering().forEach(el => {
				minX = Math.min(minX, el.x);
				minY = Math.min(minY, el.y);
				maxX = Math.max(maxX, el.x + el.width);
				maxY = Math.max(maxY, el.y + el.height);
			});

			const canvas = document.getElementById('bpmn-canvas');
			if (!canvas) return;

			const rect = canvas.getBoundingClientRect();
			const padding = 50;

			const contentWidth = maxX - minX + padding * 2;
			const contentHeight = maxY - minY + padding * 2;

			const scaleX = rect.width / contentWidth;
			const scaleY = rect.height / contentHeight;

			vm.zoom = Math.min(1.5, Math.min(scaleX, scaleY));
			vm.panX = rect.width / 2 - (minX + (maxX - minX) / 2) * vm.zoom;
			vm.panY = rect.height / 2 - (minY + (maxY - minY) / 2) * vm.zoom;
		},

		// ==========================================================================
		// Undo/Redo Operations
		// ==========================================================================

		undo() {
			const vm = this;
			if (vm.commandManager.canUndo) {
				// Set flag to prevent watch from creating new commands
				vm.isUndoing = true;
				vm.commandManager.undo();
				vm.markModified();
				// Force storeVersion increment to trigger computed re-evaluation
				vm.storeVersion++;
				// Update snapshot for next property change
				if (vm.selectedIds.length === 1) {
					vm.capturePropertySnapshot(vm.selectedIds[0]);
				}
				// Reset flag after Vue's next tick to ensure watch doesn't create new commands
				vm.$nextTick(() => {
					vm.isUndoing = false;
				});
			}
		},

		redo() {
			const vm = this;
			if (vm.commandManager.canRedo) {
				// Set flag to prevent watch from creating new commands
				vm.isUndoing = true;
				vm.commandManager.redo();
				vm.markModified();
				// Force storeVersion increment to trigger computed re-evaluation
				vm.storeVersion++;
				// Update snapshot for next property change
				if (vm.selectedIds.length === 1) {
					vm.capturePropertySnapshot(vm.selectedIds[0]);
				}
				// Reset flag after Vue's next tick to ensure watch doesn't create new commands
				vm.$nextTick(() => {
					vm.isUndoing = false;
				});
			}
		},

		/**
		 * Capture a snapshot of the current property state for undo.
		 * Called when selection changes or after undo/redo.
		 */
		capturePropertySnapshot(id: string) {
			const vm = this;

			// Check if it's a flow or semantic
			const flow = bpmnModelStore.getFlow(id);
			if (flow) {
				vm.lastPropertySnapshot = {
					name: flow.name,
					conditionType: flow.conditionType,
					conditionExpression: flow.conditionExpression,
					conditionScript: flow.conditionScript,
					conditionScriptFormat: flow.conditionScriptFormat,
				};
				vm.lastPropertySnapshotId = id;
				vm.lastPropertySnapshotIsFlow = true;
				return;
			}

			const semantic = bpmnModelStore.getSemantic(id);
			if (semantic) {
				// Capture all relevant properties (same as watch handler)
				vm.lastPropertySnapshot = {
					name: semantic.name,
					subType: semantic.subType,
					catching: semantic.catching,
					attachedToRef: semantic.attachedToRef,
					cancelActivity: semantic.cancelActivity,
					timerType: semantic.timerType,
					timerValue: semantic.timerValue,
					messageRef: semantic.messageRef,
					messageName: semantic.messageName,
					signalRef: semantic.signalRef,
					signalName: semantic.signalName,
					errorRef: semantic.errorRef,
					errorName: semantic.errorName,
					errorCode: semantic.errorCode,
					escalationRef: semantic.escalationRef,
					escalationName: semantic.escalationName,
					escalationCode: semantic.escalationCode,
					linkName: semantic.linkName,
					conditionType: semantic.conditionType,
					conditionExpression: semantic.conditionExpression,
					conditionScript: semantic.conditionScript,
					conditionScriptFormat: semantic.conditionScriptFormat,
					conditionScriptType: semantic.conditionScriptType,
					conditionScriptResource: semantic.conditionScriptResource,
					conditionVariableName: semantic.conditionVariableName,
					conditionVariableEvents: semantic.conditionVariableEvents,
					compensationActivityRef: semantic.compensationActivityRef,
					compensationWaitForCompletion: semantic.compensationWaitForCompletion,
					implementation: semantic.implementation,
					implementationType: semantic.implementationType,
					javaClass: semantic.javaClass,
					expression: semantic.expression,
					resultVariable: semantic.resultVariable,
					delegateExpression: semantic.delegateExpression,
					topic: semantic.topic,
					priority: semantic.priority,
					assignee: semantic.assignee,
					candidateUsers: semantic.candidateUsers,
					candidateGroups: semantic.candidateGroups,
					formKey: semantic.formKey,
					initiator: semantic.initiator,
					scriptFormat: semantic.scriptFormat,
					script: semantic.script,
					decisionRef: semantic.decisionRef,
					decisionRefBinding: semantic.decisionRefBinding,
					decisionRefVersion: semantic.decisionRefVersion,
					decisionRefVersionTag: semantic.decisionRefVersionTag,
					decisionRefTenantId: semantic.decisionRefTenantId,
					mapDecisionResult: semantic.mapDecisionResult,
					calledElement: semantic.calledElement,
					calledElementBinding: semantic.calledElementBinding,
					calledElementVersion: semantic.calledElementVersion,
					calledElementVersionTag: semantic.calledElementVersionTag,
					calledElementTenantId: semantic.calledElementTenantId,
					businessKey: semantic.businessKey,
					inMappings: semantic.inMappings,
					outMappings: semantic.outMappings,
					instantiate: semantic.instantiate,
					eventGatewayType: semantic.eventGatewayType,
					activationCondition: semantic.activationCondition,
					triggeredByEvent: semantic.triggeredByEvent,
					loopType: semantic.loopType,
					collection: semantic.collection,
					elementVariable: semantic.elementVariable,
					asyncBefore: semantic.asyncBefore,
					asyncAfter: semantic.asyncAfter,
					exclusive: semantic.exclusive,
					jobPriority: semantic.jobPriority,
					failedJobRetryTimeCycle: semantic.failedJobRetryTimeCycle,
					retryTimeCycle: semantic.retryTimeCycle,
					documentation: semantic.documentation,
					inputParameters: semantic.inputParameters,
					outputParameters: semantic.outputParameters,
					executionListeners: semantic.executionListeners,
					taskListeners: semantic.taskListeners,
					extensionProperties: semantic.extensionProperties,
					isCollection: semantic.isCollection,
					dataState: semantic.dataState,
					dataObjectRef: semantic.dataObjectRef,
					dataStoreRef: semantic.dataStoreRef,
					processRef: semantic.processRef,
					isHorizontal: semantic.isHorizontal,
					parentPoolId: semantic.parentPoolId,
					flowNodeRefs: semantic.flowNodeRefs,
					candidateStarterGroups: semantic.candidateStarterGroups,
					candidateStarterUsers: semantic.candidateStarterUsers,
				};
				vm.lastPropertySnapshotId = id;
				vm.lastPropertySnapshotIsFlow = false;
			}
		},

		/**
		 * Refresh selectedElement from Store after undo/redo.
		 * Forces Vue to re-read the element data from Store.
		 */
		refreshSelectedElement() {
			const vm = this;
			if (vm.selectedIds.length !== 1) return;

			// Force Vue reactivity by incrementing storeVersion
			vm.storeVersion++;
		},

		// ==========================================================================
		// Property-panel dropdowns (shell-rendered popups replacing <select>)
		// ==========================================================================

		/**
		 * Open a choice popup anchored at the trigger element's rect.
		 * The shell renders the menu above all iframes; the selected id is
		 * resolved via the popup service.
		 */
		async openChoicePopup(
			event: MouseEvent,
			options: ChoiceOption[],
			currentValue: string,
			onSelect: (value: string) => void,
			placeholder?: string,
		) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement | null;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const items: any[] = [];
			if (placeholder !== undefined) {
				items.push({
					id: EMPTY_CHOICE_ID,
					label: placeholder,
					selected: !currentValue,
				});
			}
			for (const o of options) {
				items.push({
					id: o.id,
					label: o.label,
					selected: currentValue === o.id,
				});
			}
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			const value = result === EMPTY_CHOICE_ID ? '' : String(result);
			onSelect(value);
			// Force ichigo.js reactivity. Some downstream handlers (e.g.
			// onConditionTypeChange) only mutate nested fields without
			// reassigning selectedElement; bumping storeVersion guarantees
			// the property pane re-renders for v-if branches that depend
			// on the new value.
			(vm as any).storeVersion++;
		},

		/**
		 * Resolve the label for the current value of a choice option list.
		 * Returns the placeholder if value is unset or unknown.
		 */
		optionLabel(options: ChoiceOption[], value: string | undefined, fallback = ''): string {
			if (!value) return fallback;
			return options.find(o => o.id === value)?.label ?? fallback;
		},

		// -- Static enum dropdowns --------------------------------------------

		openTimerTypeDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, TIMER_TYPE_OPTIONS,
				(vm.selectedElement as any)?.timerType || '',
				v => { (vm.selectedElement as any).timerType = v; vm.onPropertyChange(); },
				'Select timer type...');
		},
		timerTypeLabel(): string {
			return this.optionLabel(TIMER_TYPE_OPTIONS,
				(this.selectedElement as any)?.timerType, 'Select timer type...');
		},

		openTimerTypeShortDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, TIMER_TYPE_SHORT_OPTIONS,
				(vm.selectedElement as any)?.timerType || '',
				v => { (vm.selectedElement as any).timerType = v; vm.onPropertyChange(); },
				'(none)');
		},
		timerTypeShortLabel(): string {
			return this.optionLabel(TIMER_TYPE_SHORT_OPTIONS,
				(this.selectedElement as any)?.timerType, '(none)');
		},

		openConditionTypeDropdown(event: MouseEvent, onChange?: 'conditionTypeChange' | 'propertyChange') {
			const vm = this;
			const use = onChange || 'conditionTypeChange';
			// Implicit default is 'expression' (matches conditionTypeLabel fallback and the
			// `!conditionType` branch in the property pane). Pass it explicitly so the
			// popup highlights Expression when the property is unset.
			this.openChoicePopup(event, CONDITION_TYPE_OPTIONS,
				(vm.selectedElement as any)?.conditionType || 'expression',
				v => {
					(vm.selectedElement as any).conditionType = v;
					if (use === 'conditionTypeChange') vm.onConditionTypeChange();
					else vm.onPropertyChange();
				});
		},
		conditionTypeLabel(): string {
			return this.optionLabel(CONDITION_TYPE_OPTIONS,
				(this.selectedElement as any)?.conditionType, 'Expression');
		},

		// Variant allowing an empty "None" choice (Sequence Flow condition editor)
		openConditionTypeWithNoneDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, CONDITION_TYPE_OPTIONS,
				(vm.selectedElement as any)?.conditionType || '',
				v => { (vm.selectedElement as any).conditionType = v; vm.onPropertyChange(); },
				'None');
		},
		conditionTypeWithNoneLabel(): string {
			return this.optionLabel(CONDITION_TYPE_OPTIONS,
				(this.selectedElement as any)?.conditionType, 'None');
		},

		openConditionScriptTypeDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, SCRIPT_TYPE_OPTIONS,
				(vm.selectedElement as any)?.conditionScriptType || 'inline',
				v => { (vm.selectedElement as any).conditionScriptType = v; vm.onPropertyChange(); });
		},
		conditionScriptTypeLabel(): string {
			return this.optionLabel(SCRIPT_TYPE_OPTIONS,
				(this.selectedElement as any)?.conditionScriptType || 'inline', 'Inline Script');
		},

		openConditionScriptFormatDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, SCRIPT_FORMAT_JS_OPTIONS,
				(vm.selectedElement as any)?.conditionScriptFormat || 'javascript',
				v => { (vm.selectedElement as any).conditionScriptFormat = v; vm.onPropertyChange(); });
		},
		conditionScriptFormatLabel(): string {
			return this.optionLabel(SCRIPT_FORMAT_JS_OPTIONS,
				(this.selectedElement as any)?.conditionScriptFormat || 'javascript', 'JavaScript');
		},

		openMessageImplTypeDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, MESSAGE_IMPL_TYPE_OPTIONS,
				(vm.selectedElement as any)?.messageImplementationType || '',
				v => { (vm.selectedElement as any).messageImplementationType = v; vm.onPropertyChange(); },
				'(none)');
		},
		messageImplTypeLabel(): string {
			return this.optionLabel(MESSAGE_IMPL_TYPE_OPTIONS,
				(this.selectedElement as any)?.messageImplementationType, '(none)');
		},

		openServiceTaskImplDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, SERVICE_TASK_IMPL_OPTIONS,
				(vm.selectedElement as any)?.implementation || 'class',
				v => { (vm.selectedElement as any).implementation = v; vm.onPropertyChange(); });
		},
		serviceTaskImplLabel(): string {
			return this.optionLabel(SERVICE_TASK_IMPL_OPTIONS,
				(this.selectedElement as any)?.implementation || 'class', 'Java Class');
		},

		openScriptFormatGroovyFirstDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, SCRIPT_FORMAT_GROOVY_OPTIONS,
				(vm.selectedElement as any)?.scriptFormat || 'groovy',
				v => { (vm.selectedElement as any).scriptFormat = v; vm.onPropertyChange(); });
		},
		scriptFormatGroovyFirstLabel(): string {
			return this.optionLabel(SCRIPT_FORMAT_GROOVY_OPTIONS,
				(this.selectedElement as any)?.scriptFormat || 'groovy', 'Groovy');
		},

		openImplementationTypeDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, MESSAGE_IMPL_TYPE_OPTIONS,
				(vm.selectedElement as any)?.implementationType || '',
				v => { (vm.selectedElement as any).implementationType = v; vm.onPropertyChange(); },
				'(none)');
		},
		implementationTypeLabel(): string {
			return this.optionLabel(MESSAGE_IMPL_TYPE_OPTIONS,
				(this.selectedElement as any)?.implementationType, '(none)');
		},

		openBusinessRuleImplDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, BUSINESS_RULE_IMPL_OPTIONS,
				(vm.selectedElement as any)?.implementationType || 'dmn',
				v => { (vm.selectedElement as any).implementationType = v; vm.onPropertyChange(); });
		},
		businessRuleImplLabel(): string {
			return this.optionLabel(BUSINESS_RULE_IMPL_OPTIONS,
				(this.selectedElement as any)?.implementationType || 'dmn', 'DMN');
		},

		openDecisionRefBindingDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, REF_BINDING_OPTIONS,
				(vm.selectedElement as any)?.decisionRefBinding || 'latest',
				v => { (vm.selectedElement as any).decisionRefBinding = v; vm.onPropertyChange(); });
		},
		decisionRefBindingLabel(): string {
			return this.optionLabel(REF_BINDING_OPTIONS,
				(this.selectedElement as any)?.decisionRefBinding || 'latest', 'latest');
		},

		openMapDecisionResultDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, MAP_DECISION_RESULT_OPTIONS,
				(vm.selectedElement as any)?.mapDecisionResult || 'singleEntry',
				v => { (vm.selectedElement as any).mapDecisionResult = v; vm.onPropertyChange(); });
		},
		mapDecisionResultLabel(): string {
			return this.optionLabel(MAP_DECISION_RESULT_OPTIONS,
				(this.selectedElement as any)?.mapDecisionResult || 'singleEntry', 'singleEntry');
		},

		openCalledElementTypeDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, CALLED_ELEMENT_TYPE_OPTIONS,
				(vm.selectedElement as any)?.calledElementType || 'bpmn',
				v => { (vm.selectedElement as any).calledElementType = v; vm.onPropertyChange(); });
		},
		calledElementTypeLabel(): string {
			return this.optionLabel(CALLED_ELEMENT_TYPE_OPTIONS,
				(this.selectedElement as any)?.calledElementType || 'bpmn', 'BPMN');
		},

		openCalledElementBindingDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, REF_BINDING_OPTIONS,
				(vm.selectedElement as any)?.calledElementBinding || 'latest',
				v => { (vm.selectedElement as any).calledElementBinding = v; vm.onPropertyChange(); });
		},
		calledElementBindingLabel(): string {
			return this.optionLabel(REF_BINDING_OPTIONS,
				(this.selectedElement as any)?.calledElementBinding || 'latest', 'latest');
		},

		openEventGatewayTypeDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, EVENT_GATEWAY_TYPE_OPTIONS,
				(vm.selectedElement as any)?.eventGatewayType || 'exclusive',
				v => { (vm.selectedElement as any).eventGatewayType = v; vm.onPropertyChange(); });
		},
		eventGatewayTypeLabel(): string {
			return this.optionLabel(EVENT_GATEWAY_TYPE_OPTIONS,
				(this.selectedElement as any)?.eventGatewayType || 'exclusive', 'Exclusive');
		},

		openLoopTypeDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, LOOP_TYPE_OPTIONS,
				(vm.selectedElement as any)?.loopType || '',
				v => { (vm.selectedElement as any).loopType = v; vm.onPropertyChange(); },
				'None');
		},
		loopTypeLabel(): string {
			return this.optionLabel(LOOP_TYPE_OPTIONS,
				(this.selectedElement as any)?.loopType, 'None');
		},

		openTransactionMethodDropdown(event: MouseEvent) {
			const vm = this;
			this.openChoicePopup(event, TRANSACTION_METHOD_OPTIONS,
				(vm.selectedElement as any)?.transactionMethod || '',
				v => { (vm.selectedElement as any).transactionMethod = v; vm.onPropertyChange(); },
				'Default');
		},
		transactionMethodLabel(): string {
			return this.optionLabel(TRANSACTION_METHOD_OPTIONS,
				(this.selectedElement as any)?.transactionMethod, 'Default');
		},

		// Compensation Activity Ref — dynamic list of tasks in the process
		async openCompensationActivityRefDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement | null;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const currentId = (vm.selectedElement as any)?.compensationActivityRef || '';
			const items: any[] = [
				{ id: EMPTY_CHOICE_ID, label: '(none - broadcast)', selected: !currentId },
			];
			for (const task of (vm.compensatableTasks as any[])) {
				items.push({
					id: task.id,
					label: task.name || task.id,
					selected: task.id === currentId,
				});
			}
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			const value = result === EMPTY_CHOICE_ID ? '' : String(result);
			(vm.selectedElement as any).compensationActivityRef = value;
			vm.onPropertyChange();
		},
		compensationActivityRefLabel(): string {
			const vm = this;
			const id = (vm.selectedElement as any)?.compensationActivityRef || '';
			if (!id) return '(none - broadcast)';
			const task = (vm.compensatableTasks as any[]).find(t => t.id === id);
			return (task?.name || task?.id) || id;
		},

		// -- Array-nested dropdowns (per-row in v-for lists) -------------------

		openInputTypeDropdown(input: any, event: MouseEvent) {
			this.openChoicePopup(event, PARAM_TYPE_OPTIONS,
				input.type || 'text',
				v => { input.type = v; this.onPropertyChange(); });
		},
		openOutputTypeDropdown(output: any, event: MouseEvent) {
			this.openChoicePopup(event, PARAM_TYPE_OPTIONS,
				output.type || 'text',
				v => { output.type = v; this.onPropertyChange(); });
		},
		paramTypeLabel(item: any): string {
			return this.optionLabel(PARAM_TYPE_OPTIONS, item?.type || 'text', 'Text');
		},

		openParamScriptFormatDropdown(item: any, event: MouseEvent) {
			this.openChoicePopup(event, PARAM_SCRIPT_FORMAT_OPTIONS,
				item.scriptFormat || 'javascript',
				v => { item.scriptFormat = v; this.onPropertyChange(); });
		},
		paramScriptFormatLabel(item: any): string {
			return this.optionLabel(PARAM_SCRIPT_FORMAT_OPTIONS,
				item?.scriptFormat || 'javascript', 'JavaScript');
		},

		openFieldTypeDropdown(field: any, event: MouseEvent) {
			this.openChoicePopup(event, FIELD_TYPE_OPTIONS,
				field.type || 'string',
				v => { field.type = v; this.onPropertyChange(); });
		},
		fieldTypeLabel(field: any): string {
			return this.optionLabel(FIELD_TYPE_OPTIONS, field?.type || 'string', 'String');
		},

		// Extension properties: 4-way param.type (text/script/list/map)
		openExtParamTypeDropdown(param: any, event: MouseEvent) {
			this.openChoicePopup(event, EXTENSION_PARAM_TYPE_OPTIONS,
				param.type || 'text',
				v => { param.type = v; this.onPropertyChange(); });
		},
		extParamTypeLabel(param: any): string {
			return this.optionLabel(EXTENSION_PARAM_TYPE_OPTIONS, param?.type || 'text', 'Text');
		},

		openExtParamScriptFormatDropdown(param: any, event: MouseEvent) {
			this.openChoicePopup(event, SCRIPT_FORMAT_GROOVY_OPTIONS,
				param.scriptFormat || 'groovy',
				v => { param.scriptFormat = v; this.onPropertyChange(); });
		},
		extParamScriptFormatLabel(param: any): string {
			return this.optionLabel(SCRIPT_FORMAT_GROOVY_OPTIONS,
				param?.scriptFormat || 'groovy', 'Groovy');
		},

		// Execution listeners
		openListenerEventDropdown(listener: any, event: MouseEvent) {
			this.openChoicePopup(event, LISTENER_EVENT_OPTIONS,
				listener.event || 'start',
				v => { listener.event = v; this.onPropertyChange(); });
		},
		listenerEventLabel(listener: any): string {
			return this.optionLabel(LISTENER_EVENT_OPTIONS, listener?.event || 'start', 'start');
		},

		openListenerTypeDropdown(listener: any, event: MouseEvent) {
			this.openChoicePopup(event, LISTENER_TYPE_OPTIONS,
				listener.listenerType || 'class',
				v => { listener.listenerType = v; this.onPropertyChange(); });
		},
		listenerTypeLabel(listener: any): string {
			return this.optionLabel(LISTENER_TYPE_OPTIONS,
				listener?.listenerType || 'class', 'Java Class');
		},

		openListenerScriptFormatDropdown(listener: any, event: MouseEvent) {
			this.openChoicePopup(event, SCRIPT_FORMAT_GROOVY_OPTIONS,
				listener.scriptFormat || 'groovy',
				v => { listener.scriptFormat = v; this.onPropertyChange(); });
		},
		listenerScriptFormatLabel(listener: any): string {
			return this.optionLabel(SCRIPT_FORMAT_GROOVY_OPTIONS,
				listener?.scriptFormat || 'groovy', 'Groovy');
		},

		openListenerScriptTypeDropdown(listener: any, event: MouseEvent) {
			this.openChoicePopup(event, SCRIPT_TYPE_OPTIONS,
				listener.scriptType || 'inline',
				v => { listener.scriptType = v; this.onPropertyChange(); });
		},
		listenerScriptTypeLabel(listener: any): string {
			return this.optionLabel(SCRIPT_TYPE_OPTIONS,
				listener?.scriptType || 'inline', 'Inline Script');
		},

		openListenerFieldTypeDropdown(field: any, event: MouseEvent) {
			this.openChoicePopup(event, FIELD_TYPE_OPTIONS,
				field.type || 'string',
				v => { field.type = v; this.onPropertyChange(); });
		},

		// Task listeners (script format / type / field type reuse the execution
		// listener dropdowns above; only event and listener type differ).
		openTaskListenerEventDropdown(listener: any, event: MouseEvent) {
			this.openChoicePopup(event, TASK_LISTENER_EVENT_OPTIONS,
				listener.event || 'create',
				v => { listener.event = v; this.onPropertyChange(); });
		},
		taskListenerEventLabel(listener: any): string {
			return this.optionLabel(TASK_LISTENER_EVENT_OPTIONS, listener?.event || 'create', 'create');
		},

		openTaskListenerTypeDropdown(listener: any, event: MouseEvent) {
			this.openChoicePopup(event, TASK_LISTENER_TYPE_OPTIONS,
				listener.listenerType || 'class',
				v => { listener.listenerType = v; this.onPropertyChange(); });
		},
		taskListenerTypeLabel(listener: any): string {
			return this.optionLabel(TASK_LISTENER_TYPE_OPTIONS,
				listener?.listenerType || 'class', 'Java Class');
		},

		// -- Reference combobox widget (Message / Signal / Error / Escalation) --
		// Inline-edit pattern that mirrors Content Browser's MIME type editor:
		// the bordered display (.wt-ref-display) is replaced on click by a
		// text input with confirm/cancel buttons and a suggestions popup that
		// filters by name. Picking an item from the popup uses that
		// definition's id; confirming the typed text matches by name (case-
		// insensitive) and reuses the matching id, otherwise creates a new
		// definition. Cancelling restores the pre-edit snapshot.

		isRefEditing(kind: RefKind): boolean {
			return this.refEditing && this.refEditKind === kind;
		},

		refDisplayLabel(kind: RefKind): string {
			const vm = this;
			const cfg = REF_CONFIGS[kind];
			const el = vm.selectedElement as any;
			if (!el) return cfg.displayPlaceholder;
			const refId = el[cfg.refField];
			if (!refId) return cfg.displayPlaceholder;
			// Prefer the document-level definition's name so the label stays
			// in sync if the definition is renamed elsewhere.
			const list = (bpmnModelStore.getModelData() as any)[cfg.listField] as any[] | undefined;
			const def = list?.find(d => d.id === refId);
			const name = def?.name || el[cfg.nameField] || refId;
			if (cfg.codeField) {
				const code = def?.code || el[cfg.codeField] || '';
				return code ? `${name} (${code})` : name;
			}
			return name;
		},

		startRefEdit(kind: RefKind) {
			const vm = this;
			if (!vm.selectedElement) return;
			const cfg = REF_CONFIGS[kind];
			const el = vm.selectedElement as any;

			vm.refEditSnapshot = {
				refId: el[cfg.refField] || '',
				refName: el[cfg.nameField] || '',
				code: cfg.codeField ? (el[cfg.codeField] || '') : '',
			};
			vm.refEditKind = kind;
			vm.refEditInput = el[cfg.nameField] || '';
			vm.refEditHighlightIndex = -1;
			vm.refEditing = true;
			(vm as any).storeVersion++;

			(vm as any).$nextTick?.(() => {
				const input = document.querySelector('.wt-ref-input') as HTMLInputElement | null;
				if (input) {
					input.focus();
					input.select();
				}
				vm.refreshRefSuggestions();
			});
		},

		buildRefSuggestionItems(): any[] {
			const vm = this;
			if (!vm.refEditKind) return [];
			const cfg = REF_CONFIGS[vm.refEditKind as RefKind];
			const list = ((bpmnModelStore.getModelData() as any)[cfg.listField] || []) as any[];
			const query = vm.refEditInput.trim().toLowerCase();
			let filtered = list.slice();
			if (query) {
				filtered = filtered.filter(d => (d.name || '').toLowerCase().includes(query));
			}
			filtered.sort((a, b) => (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' }));
			return filtered.slice(0, 20).map((d, i) => ({
				id: d.id,
				label: d.name || d.id,
				description: cfg.codeField && d.code ? d.code : undefined,
				highlighted: i === vm.refEditHighlightIndex,
			}));
		},

		refreshRefSuggestions() {
			const vm = this;
			if (!vm.refEditing || !vm.instance) return;
			const items = vm.buildRefSuggestionItems();
			if (items.length === 0) {
				vm.closeRefSuggestions();
				return;
			}
			if (refSuggestionPopupHandle) {
				refSuggestionPopupHandle.update(items);
				return;
			}
			const input = document.querySelector('.wt-ref-input') as HTMLInputElement | null;
			if (!input) return;
			const rect = input.getBoundingClientRect();
			refSuggestionPopupHandle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				maxHeight: 360,
				items,
			});
			refSuggestionPopupHandle.result.then((picked: any) => {
				refSuggestionPopupHandle = null;
				if (picked == null) return;
				vm.applyRefById(String(picked));
			});
		},

		closeRefSuggestions() {
			if (refSuggestionPopupHandle) {
				refSuggestionPopupHandle.close();
				refSuggestionPopupHandle = null;
			}
		},

		onRefInputChange() {
			const vm = this;
			vm.refEditHighlightIndex = -1;
			vm.refreshRefSuggestions();
		},

		onRefKeydown(e: KeyboardEvent) {
			const vm = this;
			if (e.key === 'Escape') {
				e.preventDefault();
				vm.cancelRefEdit();
				return;
			}
			const items = vm.buildRefSuggestionItems();
			if (e.key === 'ArrowDown' && items.length > 0) {
				e.preventDefault();
				vm.refEditHighlightIndex = Math.min(vm.refEditHighlightIndex + 1, items.length - 1);
				vm.refreshRefSuggestions();
				return;
			}
			if (e.key === 'ArrowUp') {
				e.preventDefault();
				vm.refEditHighlightIndex = Math.max(vm.refEditHighlightIndex - 1, -1);
				vm.refreshRefSuggestions();
				return;
			}
			if (e.key === 'Enter') {
				e.preventDefault();
				if (vm.refEditHighlightIndex >= 0 && vm.refEditHighlightIndex < items.length) {
					vm.applyRefById(items[vm.refEditHighlightIndex].id);
				} else {
					vm.confirmRefEdit();
				}
			}
		},

		applyRefById(id: string) {
			// Picking from suggestions: reuse an existing definition's id (and
			// pull its name/code so the element stays consistent with the
			// document-level definition).
			const vm = this;
			if (!vm.refEditing || !vm.selectedElement) return;
			const cfg = REF_CONFIGS[vm.refEditKind as RefKind];
			const list = ((bpmnModelStore.getModelData() as any)[cfg.listField] || []) as any[];
			const existing = list.find(d => d.id === id);
			if (!existing) return;
			const el = vm.selectedElement as any;
			el[cfg.refField] = existing.id;
			el[cfg.nameField] = existing.name;
			if (cfg.codeField && existing.code !== undefined) {
				el[cfg.codeField] = existing.code;
			}
			vm.exitRefEdit();
			vm.onPropertyChange();
		},

		confirmRefEdit() {
			// Plain-text confirm: case-insensitive name match against existing
			// defs, otherwise create a new def. Empty input clears the ref.
			const vm = this;
			if (!vm.refEditing || !vm.selectedElement) return;
			const cfg = REF_CONFIGS[vm.refEditKind as RefKind];
			const el = vm.selectedElement as any;
			const list = ((bpmnModelStore.getModelData() as any)[cfg.listField] || []) as any[];
			const name = vm.refEditInput.trim();

			if (!name) {
				el[cfg.refField] = '';
				el[cfg.nameField] = '';
				if (cfg.codeField) el[cfg.codeField] = '';
				vm.exitRefEdit();
				vm.onPropertyChange();
				return;
			}

			const existing = list.find(d => (d.name || '').toLowerCase() === name.toLowerCase());
			if (existing) {
				el[cfg.refField] = existing.id;
				el[cfg.nameField] = existing.name;
				if (cfg.codeField && existing.code !== undefined) {
					el[cfg.codeField] = existing.code;
				}
			} else {
				const newId = generateId(cfg.idPrefix);
				el[cfg.refField] = newId;
				el[cfg.nameField] = name;
				const newDef: any = { id: newId, name };
				if (cfg.codeField) newDef.code = el[cfg.codeField] || '';
				bpmnModelStore.setModelData({ [cfg.listField]: [...list, newDef] } as any);
				vm.syncProcessDefinitionsToStore();
			}

			vm.exitRefEdit();
			vm.onPropertyChange();
		},

		cancelRefEdit() {
			const vm = this;
			const snap = vm.refEditSnapshot;
			if (snap && vm.selectedElement && vm.refEditKind) {
				const cfg = REF_CONFIGS[vm.refEditKind as RefKind];
				const el = vm.selectedElement as any;
				el[cfg.refField] = snap.refId;
				el[cfg.nameField] = snap.refName;
				if (cfg.codeField) el[cfg.codeField] = snap.code;
				vm.onPropertyChange();
			}
			vm.exitRefEdit();
		},

		exitRefEdit() {
			const vm = this;
			vm.refEditing = false;
			vm.refEditKind = '';
			vm.refEditInput = '';
			vm.refEditHighlightIndex = -1;
			vm.refEditSnapshot = null;
			vm.closeRefSuggestions();
			(vm as any).storeVersion++;
		},

		// ==========================================================================
		// Definition management (toolbar bi-menu-app)
		//
		// One dialog handles all four kinds (Message/Signal/Error/Escalation) by
		// reading from the corresponding `vm.model[cfg.listField]`. The toolbar
		// button opens a shell-rendered popup menu that picks which kind to
		// manage; the dialog then lists definitions with reference counts and
		// lets unreferenced entries be deleted (individually or in bulk).
		// ==========================================================================

		async openManagementMenu(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement | null;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const items = [
				{ id: 'message',    label: 'Manage Messages...' },
				{ id: 'signal',     label: 'Manage Signals...' },
				{ id: 'error',      label: 'Manage Errors...' },
				{ id: 'escalation', label: 'Manage Escalations...' },
			];
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.openManagementDialog(String(result) as RefKind);
		},

		openManagementDialog(kind: RefKind) {
			this.managementDialog.visible = true;
			this.managementDialog.kind = kind;
		},

		closeManagementDialog() {
			this.managementDialog.visible = false;
			this.managementDialog.kind = '';
		},

		managementDialogTitle(): string {
			const titles: Record<RefKind, string> = {
				message: 'Manage Messages',
				signal: 'Manage Signals',
				error: 'Manage Errors',
				escalation: 'Manage Escalations',
			};
			return this.managementDialog.kind ? titles[this.managementDialog.kind as RefKind] : '';
		},

		managementDialogHasCode(): boolean {
			const k = this.managementDialog.kind;
			return k === 'error' || k === 'escalation';
		},

		/**
		 * Count how many elements in the document reference the given
		 * definition. The store is the source of truth in store mode; legacy
		 * mode falls back to scanning vm.model.elements.
		 */
		referenceCount(kind: RefKind, id: string): number {
			void this.storeVersion;
			const cfg = REF_CONFIGS[kind];
			let n = 0;
			for (const s of bpmnModelStore.getAllSemantics()) {
				if ((s as any)[cfg.refField] === id) n++;
			}
			return n;
		},

		/**
		 * Build the rows shown in the management dialog table. Sorted by name
		 * to match how the suggestion popup orders things.
		 */
		managementDialogItems(): { id: string; name: string; code?: string; refCount: number }[] {
			void this.storeVersion;
			const kind = this.managementDialog.kind as RefKind | '';
			if (!kind) return [];
			const cfg = REF_CONFIGS[kind];
			const list = ((bpmnModelStore.getModelData() as any)[cfg.listField] || []) as any[];
			const rows = list.map(d => ({
				id: d.id,
				name: d.name || '',
				code: cfg.codeField ? (d.code || '') : undefined,
				refCount: this.referenceCount(kind, d.id),
			}));
			rows.sort((a, b) => (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' }));
			return rows;
		},

		/**
		 * True when at least one row in the current dialog has refCount === 0,
		 * controlling whether the bulk-delete button is enabled.
		 */
		hasUnreferencedDefinitions(): boolean {
			return this.managementDialogItems().some((r: { refCount: number }) => r.refCount === 0);
		},

		deleteDefinition(kind: RefKind, id: string) {
			const vm = this;
			// Defensive: skip if still referenced (the UI shouldn't expose the
			// delete button in that case, but guard against stale clicks).
			if (vm.referenceCount(kind, id) > 0) return;
			const cfg = REF_CONFIGS[kind];
			const md = bpmnModelStore.getModelData();
			const list = ((md as any)[cfg.listField] || []) as any[];
			const idx = list.findIndex(d => d.id === id);
			if (idx < 0) return;
			const next = list.slice();
			next.splice(idx, 1);
			bpmnModelStore.setModelData({ [cfg.listField]: next } as any);
			vm.syncProcessDefinitionsToStore();
			vm.markModified();
			(vm as any).storeVersion++;
		},

		deleteUnreferencedDefinitions(kind: RefKind) {
			const vm = this;
			const cfg = REF_CONFIGS[kind];
			const md = bpmnModelStore.getModelData();
			const list = ((md as any)[cfg.listField] || []) as any[];
			const kept = list.filter(d => vm.referenceCount(kind, d.id) > 0);
			if (kept.length === list.length) return;  // nothing to delete
			bpmnModelStore.setModelData({ [cfg.listField]: kept } as any);
			vm.syncProcessDefinitionsToStore();
			vm.markModified();
			(vm as any).storeVersion++;
		},

		// ==========================================================================
		// Notification dialog (replacement for window.alert)
		// ==========================================================================

		showNotification(
			message: string,
			severity: 'info' | 'success' | 'warning' | 'error' = 'info',
			title?: string,
		) {
			const defaultTitles: Record<'info' | 'success' | 'warning' | 'error', string> = {
				info: 'Information',
				success: 'Success',
				warning: 'Warning',
				error: 'Error',
			};
			this.notificationDialog.severity = severity;
			this.notificationDialog.title = title || defaultTitles[severity];
			this.notificationDialog.message = message;
			this.notificationDialog.visible = true;
		},

		closeNotificationDialog() {
			this.notificationDialog.visible = false;
		},

		notificationIconClass(): string {
			const map: Record<'info' | 'success' | 'warning' | 'error', string> = {
				info:    'bi-info-circle-fill notification-icon-info',
				success: 'bi-check-circle-fill notification-icon-success',
				warning: 'bi-exclamation-triangle-fill text-warning',
				error:   'bi-x-circle-fill text-danger',
			};
			return map[this.notificationDialog.severity];
		},

		// ==========================================================================
		// Property Operations
		// ==========================================================================

		onPropertyChange() {
			const vm = this;
			if (vm.selectedElement) {
				const isConnection = 'sourceRef' in vm.selectedElement;
				if (isConnection) {
					const conn = vm.selectedElement as BpmnConnection;
					// DI-layer fields (waypoints, label offsets) belong to the edge,
					// not to the flow semantic — strip them before persisting.
					const { waypoints, labelOffsetX, labelOffsetY, labelWidth, ...flowFields } = conn;
					bpmnModelStore.updateFlow(conn.id, flowFields as Partial<BpmnFlowSemantic>);
				} else {
					const el = vm.selectedElement as BpmnElement;
					const { x, y, width, height, labelOffsetX, labelOffsetY, labelWidth, isExpanded, ...semanticFields } = el;
					bpmnModelStore.updateSemantic(el.id, semanticFields as Partial<BpmnSemantic>);
					// When editing a Pool whose processRef matches the primary
					// process, mirror candidateStarter to modelData so the
					// empty-canvas panel keeps the same value the Pool shows.
					const primaryId = bpmnModelStore.getModelData().processId;
					if (el.type === 'pool' && (!el.processRef || el.processRef === primaryId)) {
						bpmnModelStore.setModelData({
							candidateStarterGroups: el.candidateStarterGroups || undefined,
							candidateStarterUsers: el.candidateStarterUsers || undefined,
						});
					}
				}
				vm.storeVersion++;
			} else {
				// Empty-canvas property panel edits process-level metadata.
				// Mirror process-level candidateStarter into matching Pools so
				// the Pool Properties UI stays in sync.
				const md = bpmnModelStore.getModelData();
				const primaryId = md.processId;
				bpmnModelStore.getAllSemantics().forEach(s => {
					if (s.type !== 'pool') return;
					if (s.processRef && s.processRef !== primaryId) return;
					bpmnModelStore.updateSemantic(s.id, {
						candidateStarterGroups: md.candidateStarterGroups || undefined,
						candidateStarterUsers: md.candidateStarterUsers || undefined,
					});
				});
			}
			this.markModified();
		},

		// ==========================================================================
		// Sub-Process Operations
		// ==========================================================================

		/**
		 * Get label for sub-process type
		 */
		getSubProcessTypeLabel(subType: string): string {
			switch (subType) {
				case 'collapsed': return 'Collapsed Sub-Process';
				case 'expanded': return 'Expanded Sub-Process';
				case 'event': return 'Event Sub-Process';
				case 'transaction': return 'Transaction';
				default: return 'Sub-Process';
			}
		},

		/**
		 * Collapse an expanded sub-process
		 */
		collapseSubProcess(element: BpmnElement) {
			const vm = this;

			expandedSubProcessSizes.set(element.id, {
				width: element.width || ELEMENT_SIZES.expandedSubProcess.width,
				height: element.height || ELEMENT_SIZES.expandedSubProcess.height,
			});

			const newWidth = ELEMENT_SIZES.subProcess.width;
			const newHeight = ELEMENT_SIZES.subProcess.height;

			bpmnModelStore.updateSemantic(element.id, { subType: 'collapsed' });
			const shapes = bpmnModelStore.getShapesForSemantic(element.id);
			if (shapes[0]) {
				bpmnModelStore.updateShape(shapes[0].id, {
					isExpanded: false,
					bounds: { ...shapes[0].bounds, width: newWidth, height: newHeight },
				});
			}
			vm.storeVersion++;
			vm.markModified();
		},

		/**
		 * Expand a collapsed sub-process
		 */
		expandSubProcess(element: BpmnElement) {
			const vm = this;

			const savedSize = expandedSubProcessSizes.get(element.id);
			const newWidth = savedSize?.width || ELEMENT_SIZES.expandedSubProcess.width;
			const newHeight = savedSize?.height || ELEMENT_SIZES.expandedSubProcess.height;

			bpmnModelStore.updateSemantic(element.id, { subType: 'expanded' });
			const shapes = bpmnModelStore.getShapesForSemantic(element.id);
			if (shapes[0]) {
				bpmnModelStore.updateShape(shapes[0].id, {
					isExpanded: true,
					bounds: { ...shapes[0].bounds, width: newWidth, height: newHeight },
				});
			}
			vm.storeVersion++;
			vm.markModified();
		},

		// ==========================================================================
		// Pool / Lane Operations
		// ==========================================================================

		/**
		 * Add a Lane to a Pool
		 */
		addLaneToPool(pool: BpmnElement) {
			const vm = this;
			const existingLanes = vm.getElementsForRendering().filter(
				(el: BpmnElement) => el.type === 'lane' && el.parentPoolId === pool.id
			);

			const laneHeight = ELEMENT_SIZES.lane.height;
			const newLaneY = existingLanes.length > 0
				? Math.max(...existingLanes.map((l: BpmnElement) => l.y + (l.height || laneHeight)))
				: pool.y;

			const lane: BpmnElement = {
				id: generateId('Lane'),
				type: 'lane',
				name: 'Lane ' + (existingLanes.length + 1),
				parentPoolId: pool.id,
				x: pool.x + 30,
				y: newLaneY,
				width: (pool.width || ELEMENT_SIZES.pool.width) - 30,
				height: laneHeight,
				flowNodeRefs: [],
			};

			vm.addElementDual(lane);

			// Adjust Pool height if needed
			const totalLaneHeight = (existingLanes.length + 1) * laneHeight;
			if (totalLaneHeight > (pool.height || ELEMENT_SIZES.pool.height)) {
				vm.updateElementBounds(pool.id, { height: totalLaneHeight });
			}

			vm.markModified();
		},

		/**
		 * Get Lanes for a Pool
		 */
		getLanesForPool(poolId: string): BpmnElement[] {
			const vm = this;
			return vm.getElementsForRendering()
				.filter((el: BpmnElement) => el.type === 'lane' && el.parentPoolId === poolId)
				.sort((a: BpmnElement, b: BpmnElement) => a.y - b.y);
		},

		/**
		 * Sync single Lane height with Pool height
		 * When there's only one Lane in a Pool, it should fill the entire Pool
		 */
		syncSingleLaneWithPool(poolId: string) {
			const vm = this;
			const pool = vm.getElementsForRendering().find((el: BpmnElement) => el.id === poolId);
			const lanes = vm.getLanesForPool(poolId);

			if (pool && lanes.length === 1) {
				lanes[0].height = pool.height || ELEMENT_SIZES.pool.height;
				lanes[0].y = pool.y;
			}
		},

		/**
		 * Get Lane Y position relative to Pool (for divider lines)
		 */
		getLaneY(poolId: string, index: number): number {
			const vm = this;
			const lanes = vm.getLanesForPool(poolId);
			if (index > 0 && lanes[index]) {
				const pool = vm.getElementsForRendering().find((el: BpmnElement) => el.id === poolId);
				if (pool) {
					return lanes[index].y - pool.y;
				}
			}
			return 0;
		},

		/**
		 * Get Pool name by ID
		 */
		getPoolName(poolId: string): string {
			const vm = this;
			const pool = vm.getElementsForRendering().find((el: BpmnElement) => el.id === poolId);
			return pool ? (pool.name || pool.id) : '(unknown)';
		},

		/**
		 * Handle Lane body mousedown - select parent Pool instead of Lane itself
		 */
		onLaneMouseDown(_event: MouseEvent, lane: BpmnElement) {
			const vm = this;

			// When clicking on Lane body, select its parent Pool instead
			if (lane.parentPoolId) {
				const parentPool = vm.getElementsForRendering().find(
					(el: BpmnElement) => el.id === lane.parentPoolId
				);
				if (parentPool) {
					vm.selectedIds = [parentPool.id];
					// Do not start dragging - Pool movement is done via Pool header
					return;
				}
			}

			// If no parent Pool (orphan Lane), use traditional behavior
			vm.selectedIds = [lane.id];
		},

		/**
		 * Handle Lane header mousedown - select the Lane itself for property editing
		 */
		onLaneHeaderMouseDown(_event: MouseEvent, lane: BpmnElement) {
			const vm = this;
			// Select the Lane (not the parent Pool) to allow property editing
			vm.selectedIds = [lane.id];
		},

		/**
		 * Check if a lane is the last one in its parent pool
		 */
		isLastLaneInPool(lane: BpmnElement): boolean {
			const vm = this;
			if (!lane.parentPoolId) return true;

			const lanes = vm.getLanesForPool(lane.parentPoolId);
			const laneIndex = lanes.findIndex((l: BpmnElement) => l.id === lane.id);
			return laneIndex === lanes.length - 1;
		},

		/**
		 * Start Lane vertical resize
		 */
		onLaneResizeStart(event: MouseEvent, lane: BpmnElement) {
			const vm = this;
			event.preventDefault();
			event.stopPropagation();

			vm.laneResizing = {
				element: lane,
				startY: event.clientY,
				startHeight: lane.height || ELEMENT_SIZES.lane.height,
			};

			document.addEventListener('mousemove', vm.onLaneResizeMove);
			document.addEventListener('mouseup', vm.onLaneResizeEnd);
		},

		/**
		 * Handle Lane resize move
		 */
		onLaneResizeMove(event: MouseEvent) {
			const vm = this;
			if (!vm.laneResizing) return;

			const dy = (event.clientY - vm.laneResizing.startY) / vm.zoom;
			const newHeight = Math.max(50, vm.laneResizing.startHeight + dy);

			vm.laneResizing.element.height = newHeight;

			// Adjust subsequent lanes
			vm.adjustLanesAfter(vm.laneResizing.element);

			// Adjust parent Pool height
			if (vm.laneResizing.element.parentPoolId) {
				vm.adjustPoolHeight(vm.laneResizing.element.parentPoolId);
			}
		},

		/**
		 * End Lane resize
		 */
		onLaneResizeEnd() {
			const vm = this;
			if (vm.laneResizing) {
				vm.laneResizing = null;
				vm.markModified();
			}
			document.removeEventListener('mousemove', vm.onLaneResizeMove);
			document.removeEventListener('mouseup', vm.onLaneResizeEnd);
		},

		/**
		 * Start properties panel resize
		 */
		onPropertiesPanelResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			vm.propertiesPanelResizing = {
				startX: event.clientX,
				startWidth: vm.propertiesPanelWidth,
			};
			document.addEventListener('mousemove', vm.onPropertiesPanelResizeMove);
			document.addEventListener('mouseup', vm.onPropertiesPanelResizeEnd);
		},

		/**
		 * Handle properties panel resize move
		 */
		onPropertiesPanelResizeMove(event: MouseEvent) {
			const vm = this;
			if (!vm.propertiesPanelResizing) return;
			const dx = vm.propertiesPanelResizing.startX - event.clientX;
			vm.propertiesPanelWidth = Math.max(200, Math.min(600, vm.propertiesPanelResizing.startWidth + dx));
		},

		/**
		 * End properties panel resize
		 */
		onPropertiesPanelResizeEnd() {
			const vm = this;
			vm.propertiesPanelResizing = null;
			document.removeEventListener('mousemove', vm.onPropertiesPanelResizeMove);
			document.removeEventListener('mouseup', vm.onPropertiesPanelResizeEnd);
		},

		/**
		 * Adjust lanes after a lane resize
		 */
		adjustLanesAfter(lane: BpmnElement) {
			const vm = this;
			if (!lane.parentPoolId) return;

			const lanes = vm.getLanesForPool(lane.parentPoolId);
			const laneIndex = lanes.findIndex((l: BpmnElement) => l.id === lane.id);

			let currentY = lane.y + (lane.height || ELEMENT_SIZES.lane.height);
			for (let i = laneIndex + 1; i < lanes.length; i++) {
				lanes[i].y = currentY;
				currentY += lanes[i].height || ELEMENT_SIZES.lane.height;
			}
		},

		/**
		 * Adjust Pool height based on Lanes
		 */
		adjustPoolHeight(poolId: string) {
			const vm = this;
			const pool = vm.getElementsForRendering().find((el: BpmnElement) => el.id === poolId);
			if (!pool) return;

			const lanes = vm.getLanesForPool(poolId);
			if (lanes.length === 0) return;

			const totalHeight = lanes.reduce((sum: number, lane: BpmnElement) => sum + (lane.height || ELEMENT_SIZES.lane.height), 0);
			pool.height = Math.max(pool.height || ELEMENT_SIZES.pool.height, totalHeight);
		},

		/**
		 * Handle Lane height change from property panel
		 */
		onLaneHeightChange() {
			const vm = this;
			if (vm.selectedElement && vm.selectedElement.type === 'lane') {
				vm.adjustLanesAfter(vm.selectedElement);
				if (vm.selectedElement.parentPoolId) {
					vm.adjustPoolHeight(vm.selectedElement.parentPoolId);
				}
				vm.markModified();
			}
		},

		/**
		 * Start resizing an expanded sub-process
		 */
		onResizeStart(event: MouseEvent, element: BpmnElement) {
			const vm = this;
			event.preventDefault();
			event.stopPropagation();

			vm.isResizing = true;
			vm.resizingElement = element;
			vm.resizeStartX = event.clientX;
			vm.resizeStartY = event.clientY;
			vm.resizeStartWidth = element.width || 300;
			vm.resizeStartHeight = element.height || 200;

			document.addEventListener('mousemove', vm.onResizeMove);
			document.addEventListener('mouseup', vm.onResizeEnd);
		},

		/**
		 * Handle resize move
		 */
		onResizeMove(event: MouseEvent) {
			const vm = this;
			if (!vm.isResizing || !vm.resizingElement) return;

			const dx = (event.clientX - vm.resizeStartX) / vm.zoom;
			const dy = (event.clientY - vm.resizeStartY) / vm.zoom;

			// Minimum size constraints
			const newWidth = Math.max(150, vm.resizeStartWidth + dx);
			const newHeight = Math.max(100, vm.resizeStartHeight + dy);

			// Update element bounds in store and legacy model
			vm.updateElementBounds(vm.resizingElement.id, { width: newWidth, height: newHeight });

			// Update local reference for next calculation
			vm.resizingElement.width = newWidth;
			vm.resizingElement.height = newHeight;

			// If resizing a Pool, also resize its child Lanes
			if (vm.resizingElement.type === 'pool') {
				const allElements = vm.getElementsForRendering();
				const childLanes = allElements.filter(
					(el: BpmnElement) => el.type === 'lane' && el.parentPoolId === vm.resizingElement!.id
				);
				childLanes.forEach((lane: BpmnElement) => {
					// Lane width is Pool width minus header width (30px)
					vm.updateElementBounds(lane.id, { width: newWidth - 30 });
				});
			}

			vm.storeVersion++;
		},

		/**
		 * End resize operation
		 */
		onResizeEnd() {
			const vm = this;
			if (vm.isResizing) {
				vm.isResizing = false;
				vm.resizingElement = null;
				document.removeEventListener('mousemove', vm.onResizeMove);
				document.removeEventListener('mouseup', vm.onResizeEnd);
				vm.markModified();
			}
		},

		/**
		 * Handle condition type change - set default values for script type
		 */
		onConditionTypeChange() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			// When switching to script type, seed defaults so the dependent
			// dropdowns (Script Type / Script Format) render with valid values.
			// conditionType itself is set by the caller before invoking this.
			if (element.conditionType === 'script') {
				if (!element.conditionScriptType) {
					element.conditionScriptType = 'inline';
				}
				if (!element.conditionScriptFormat) {
					element.conditionScriptFormat = 'javascript';
				}
			}

			// Sync mutations made on the computed-returned element back to the
			// BpmnModelStore so the next reactive read sees the new conditionType.
			vm.onPropertyChange();
		},

		/**
		 * Handle condition variable event checkbox change
		 */
		onConditionVariableEventChange(eventType: string, checked: boolean) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			// Parse current events
			const currentEvents = (element.conditionVariableEvents || '')
				.split(',')
				.map((e: string) => e.trim())
				.filter((e: string) => e.length > 0);

			if (checked) {
				// Add event if not present
				if (!currentEvents.includes(eventType)) {
					currentEvents.push(eventType);
				}
			} else {
				// Remove event
				const index = currentEvents.indexOf(eventType);
				if (index > -1) {
					currentEvents.splice(index, 1);
				}
			}

			// Save in specific order: create, update, delete
			const orderedEvents: string[] = [];
			if (currentEvents.includes('create')) orderedEvents.push('create');
			if (currentEvents.includes('update')) orderedEvents.push('update');
			if (currentEvents.includes('delete')) orderedEvents.push('delete');

			element.conditionVariableEvents = orderedEvents.join(', ');
			vm.markModified();
		},

		/**
		 * Bump storeVersion so the suggestion popup and the management dialog
		 * recompute after a definition list changes. Definitions are stored
		 * directly in BpmnModelStore.modelData; this hook used to mirror from
		 * a parallel legacy model and is now a single-line reactivity nudge.
		 */
		syncProcessDefinitionsToStore() {
			(this as any).storeVersion++;
		},

		/**
		 * Add a new connector input parameter
		 */
		addConnectorInput() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (!element.messageConnectorInputs) {
				element.messageConnectorInputs = [];
			}
			element.messageConnectorInputs.push({
				name: '',
				type: 'text',
				value: ''
			});
			vm.markModified();
		},

		/**
		 * Remove a connector input parameter by index
		 */
		removeConnectorInput(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (element.messageConnectorInputs && element.messageConnectorInputs.length > index) {
				element.messageConnectorInputs.splice(index, 1);
				vm.markModified();
			}
		},

		/**
		 * Add a new connector output parameter
		 */
		addConnectorOutput() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (!element.messageConnectorOutputs) {
				element.messageConnectorOutputs = [];
			}
			element.messageConnectorOutputs.push({
				name: '',
				type: 'text',
				value: ''
			});
			vm.markModified();
		},

		/**
		 * Remove a connector output parameter by index
		 */
		removeConnectorOutput(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (element.messageConnectorOutputs && element.messageConnectorOutputs.length > index) {
				element.messageConnectorOutputs.splice(index, 1);
				vm.markModified();
			}
		},

		/**
		 * Add a new message field injection
		 */
		addMessageFieldInjection() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (!element.messageFieldInjections) {
				element.messageFieldInjections = [];
			}
			element.messageFieldInjections.push({
				name: '',
				type: 'string',
				value: ''
			});
			vm.markModified();
		},

		/**
		 * Add a new field injection to a service task
		 */
		addServiceTaskFieldInjection() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (!element.fieldInjections) {
				element.fieldInjections = [];
			}
			element.fieldInjections.push({ name: '', type: 'string', value: '' });
			vm.markModified();
		},

		/**
		 * Remove a field injection from a service task by index
		 */
		removeServiceTaskFieldInjection(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (element.fieldInjections && element.fieldInjections.length > index) {
				element.fieldInjections.splice(index, 1);
				vm.markModified();
			}
		},

		/**
		 * Remove a message field injection by index
		 */
		removeMessageFieldInjection(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (element.messageFieldInjections && element.messageFieldInjections.length > index) {
				element.messageFieldInjections.splice(index, 1);
				vm.markModified();
			}
		},


		/**
		 * Handle existing error code change - update all elements referencing the same error
		 */
		onExistingErrorCodeChange() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (element.errorRef) {
				const md = bpmnModelStore.getModelData();
				const errors = (md.errors || []).map(e =>
					e.id === element.errorRef ? { ...e, code: element.errorCode } : e
				);
				bpmnModelStore.setModelData({ errors });
				// Mirror the new code onto every other element bound to the
				// same error so the property panel stays consistent.
				bpmnModelStore.getAllSemantics().forEach(s => {
					if (s.errorRef === element.errorRef && s.id !== element.id) {
						bpmnModelStore.updateSemantic(s.id, { errorCode: element.errorCode });
					}
				});
				vm.syncProcessDefinitionsToStore();
			}
			vm.onPropertyChange();
		},


		/**
		 * Handle existing escalation code change - update all elements referencing the same escalation
		 */
		onExistingEscalationCodeChange() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;
			const element = vm.selectedElement as BpmnElement;

			if (element.escalationRef) {
				const md = bpmnModelStore.getModelData();
				const escalations = (md.escalations || []).map(e =>
					e.id === element.escalationRef ? { ...e, code: element.escalationCode } : e
				);
				bpmnModelStore.setModelData({ escalations });
				bpmnModelStore.getAllSemantics().forEach(s => {
					if (s.escalationRef === element.escalationRef && s.id !== element.id) {
						bpmnModelStore.updateSemantic(s.id, { escalationCode: element.escalationCode });
					}
				});
				vm.syncProcessDefinitionsToStore();
			}
			vm.onPropertyChange();
		},

		/**
		 * Add a new execution listener to the selected element
		 */
		addExecutionListener() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.executionListeners) {
				element.executionListeners = [];
			}

			element.executionListeners.push({
				event: 'start',
				listenerType: 'class',
				javaClass: '',
				fields: [],
			});
			vm.markModified();
		},

		/**
		 * Add a new field injection to an execution listener
		 */
		addFieldInjection(listenerIndex: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.executionListeners || !element.executionListeners[listenerIndex]) return;

			const listener = element.executionListeners[listenerIndex];
			if (!listener.fields) {
				listener.fields = [];
			}

			listener.fields.push({
				name: '',
				type: 'string',
				value: '',
			});
			vm.markModified();
		},

		/**
		 * Remove a field injection from an execution listener
		 */
		removeFieldInjection(listenerIndex: number, fieldIndex: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.executionListeners || !element.executionListeners[listenerIndex]) return;

			const listener = element.executionListeners[listenerIndex];
			if (listener.fields && fieldIndex >= 0 && fieldIndex < listener.fields.length) {
				listener.fields.splice(fieldIndex, 1);
				vm.markModified();
			}
		},

		/**
		 * Remove an execution listener from the selected element
		 */
		removeExecutionListener(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (element.executionListeners && index >= 0 && index < element.executionListeners.length) {
				element.executionListeners.splice(index, 1);
				vm.markModified();
			}
		},

		/**
		 * Add a new task listener to the selected element (user tasks)
		 */
		addTaskListener() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.taskListeners) {
				element.taskListeners = [];
			}

			element.taskListeners.push({
				event: 'create',
				listenerType: 'class',
				javaClass: '',
				fields: [],
			});
			vm.markModified();
		},

		/**
		 * Add a new field injection to a task listener
		 */
		addTaskListenerField(listenerIndex: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.taskListeners || !element.taskListeners[listenerIndex]) return;

			const listener = element.taskListeners[listenerIndex];
			if (!listener.fields) {
				listener.fields = [];
			}

			listener.fields.push({
				name: '',
				type: 'string',
				value: '',
			});
			vm.markModified();
		},

		/**
		 * Remove a field injection from a task listener
		 */
		removeTaskListenerField(listenerIndex: number, fieldIndex: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.taskListeners || !element.taskListeners[listenerIndex]) return;

			const listener = element.taskListeners[listenerIndex];
			if (listener.fields && fieldIndex >= 0 && fieldIndex < listener.fields.length) {
				listener.fields.splice(fieldIndex, 1);
				vm.markModified();
			}
		},

		/**
		 * Remove a task listener from the selected element
		 */
		removeTaskListener(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (element.taskListeners && index >= 0 && index < element.taskListeners.length) {
				element.taskListeners.splice(index, 1);
				vm.markModified();
			}
		},

		/**
		 * Add a new extension property to the selected element
		 */
		addExtensionProperty() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.extensionProperties) {
				element.extensionProperties = [];
			}

			element.extensionProperties.push({
				name: '',
				value: '',
			});
			vm.markModified();
		},

		/**
		 * Remove an extension property from the selected element
		 */
		removeExtensionProperty(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (element.extensionProperties && index >= 0 && index < element.extensionProperties.length) {
				element.extensionProperties.splice(index, 1);
				vm.markModified();
			}
		},

		/**
		 * Add a new input parameter to the selected element (for EndEvent)
		 */
		addInputParameter() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.inputParameters) {
				element.inputParameters = [];
			}

			element.inputParameters.push({
				name: '',
				type: 'text',
				value: '',
			});
			vm.markModified();
		},

		/**
		 * Remove an input parameter from the selected element
		 */
		removeInputParameter(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (element.inputParameters && index >= 0 && index < element.inputParameters.length) {
				element.inputParameters.splice(index, 1);
				vm.markModified();
			}
		},

		/**
		 * Add a value to list type input parameter
		 */
		addInputParameterListValue(paramIndex: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.inputParameters || !element.inputParameters[paramIndex]) return;

			const param = element.inputParameters[paramIndex];
			if (!param.listValues) {
				param.listValues = [];
			}
			param.listValues.push('');
			vm.markModified();
		},

		/**
		 * Remove a value from list type input parameter
		 */
		removeInputParameterListValue(paramIndex: number, valueIndex: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.inputParameters || !element.inputParameters[paramIndex]) return;

			const param = element.inputParameters[paramIndex];
			if (param.listValues && valueIndex >= 0 && valueIndex < param.listValues.length) {
				param.listValues.splice(valueIndex, 1);
				vm.markModified();
			}
		},

		/**
		 * Add an entry to map type input parameter
		 */
		addInputParameterMapEntry(paramIndex: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.inputParameters || !element.inputParameters[paramIndex]) return;

			const param = element.inputParameters[paramIndex];
			if (!param.mapEntries) {
				param.mapEntries = [];
			}
			param.mapEntries.push({ key: '', value: '' });
			vm.markModified();
		},

		/**
		 * Remove an entry from map type input parameter
		 */
		removeInputParameterMapEntry(paramIndex: number, entryIndex: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.inputParameters || !element.inputParameters[paramIndex]) return;

			const param = element.inputParameters[paramIndex];
			if (param.mapEntries && entryIndex >= 0 && entryIndex < param.mapEntries.length) {
				param.mapEntries.splice(entryIndex, 1);
				vm.markModified();
			}
		},

		/**
		 * Add a new in mapping for Call Activity
		 */
		addInMapping() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.inMappings) {
				element.inMappings = [];
			}
			element.inMappings.push({ source: '', target: '' });
			vm.markModified();
		},

		/**
		 * Remove an in mapping from Call Activity
		 */
		removeInMapping(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (element.inMappings && index >= 0 && index < element.inMappings.length) {
				element.inMappings.splice(index, 1);
				vm.markModified();
			}
		},

		/**
		 * Add a new out mapping for Call Activity
		 */
		addOutMapping() {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (!element.outMappings) {
				element.outMappings = [];
			}
			element.outMappings.push({ source: '', target: '' });
			vm.markModified();
		},

		/**
		 * Remove an out mapping from Call Activity
		 */
		removeOutMapping(index: number) {
			const vm = this;
			if (!vm.selectedElement || !('type' in vm.selectedElement)) return;

			const element = vm.selectedElement as BpmnElement;
			if (element.outMappings && index >= 0 && index < element.outMappings.length) {
				element.outMappings.splice(index, 1);
				vm.markModified();
			}
		},

		// ==========================================================================
		// Utility Methods
		// ==========================================================================

		markModified() {
			const vm = this;
			vm.isModified = true;
			if (vm.currentFileIndex >= 0 && vm.currentFileIndex < vm.files.length && !vm.files[vm.currentFileIndex].isModified) {
				vm.files[vm.currentFileIndex].isModified = true;
				vm.files.splice(vm.currentFileIndex, 1, vm.files[vm.currentFileIndex]);
			}
		},

		// Close confirmation methods
		async confirmClose(): Promise<boolean> {
			const vm = this;

			// Save current file state
			if (vm.currentFileIndex >= 0) {
				vm.saveCurrentFileState();
			}

			// Check if any file is modified
			const hasModified = vm.files.some((f: BpmnFile) => f.isModified);
			if (!hasModified) return true;

			const result = await vm.showCloseConfirmDialog();

			if (result === 'cancel') {
				return false;
			}

			if (result === 'save') {
				// Save all modified files
				for (let i = 0; i < vm.files.length; i++) {
					if (vm.files[i].isModified) {
						if (i !== vm.currentFileIndex) {
							vm.saveCurrentFileState();
							vm.currentFileIndex = i;
							vm.restoreFileState(i);
						}
						await vm.saveFile();
						if (vm.isModified) return false;
					}
				}
			}

			return true;
		},

		showCloseConfirmDialog(): Promise<'save' | 'discard' | 'cancel'> {
			const vm = this;
			vm.closeConfirmDialog.visible = true;
			return new Promise((resolve) => {
				vm.closeConfirmDialog.resolve = resolve;
			});
		},

		onCloseConfirmDialogAction(action: 'save' | 'discard' | 'cancel') {
			const vm = this;
			if (vm.closeConfirmDialog.resolve) {
				vm.closeConfirmDialog.resolve(action);
			}
			vm.closeConfirmDialog.visible = false;
			vm.closeConfirmDialog.resolve = null;
		},
	},
};

// Mount the app
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');

// =============================================================================
// BPMN Model/DI Separation Type Definitions
// =============================================================================
// This file implements the separation of BPMN semantic model and diagram interchange (DI).
// - Semantic Layer: Logical process model without visual information
// - DI Layer: Visual representation (shapes, edges, positions)
// =============================================================================

// =============================================================================
// Utility
// =============================================================================

/**
 * Generate a UUID using crypto.randomUUID() if available, with fallback
 */
export function generateUUID(): string {
	if (typeof crypto !== 'undefined' && crypto.randomUUID) {
		return crypto.randomUUID();
	}
	// Fallback for environments without crypto.randomUUID
	return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
		const r = Math.random() * 16 | 0;
		const v = c === 'x' ? r : (r & 0x3 | 0x8);
		return v.toString(16);
	});
}

// =============================================================================
// Common Types (migrated from app.ts)
// =============================================================================

/**
 * Field Injection for Execution Listeners
 */
export interface FieldInjection {
	name: string;
	type: 'string' | 'expression';
	value: string;
}

/**
 * Execution Listener for BPMN elements
 */
export interface ExecutionListener {
	event: 'start' | 'end' | 'take';
	listenerType: 'class' | 'expression' | 'script';
	// For class type
	javaClass?: string;
	fields?: FieldInjection[];
	// For expression type
	expression?: string;
	// For script type
	scriptFormat?: string;
	scriptType?: 'inline' | 'external';
	script?: string;
	resource?: string;
}

/**
 * Camunda Task Listener (user tasks only). Mirrors ExecutionListener but with
 * the user-task lifecycle events and the additional delegateExpression type.
 * The timeout event (which carries a timer definition) is not modeled here; such
 * listeners are preserved verbatim via BpmnSemantic.unknownExtensions instead.
 */
export interface TaskListener {
	event: 'create' | 'assignment' | 'complete' | 'delete' | 'update';
	listenerType: 'class' | 'expression' | 'delegateExpression' | 'script';
	// For class type
	javaClass?: string;
	fields?: FieldInjection[];
	// For expression type
	expression?: string;
	// For delegateExpression type
	delegateExpression?: string;
	// For script type
	scriptFormat?: string;
	scriptType?: 'inline' | 'external';
	script?: string;
	resource?: string;
}

/**
 * Extension Property for BPMN elements
 */
export interface ExtensionProperty {
	name: string;
	value: string;
}

/**
 * Input/Output Parameter for BPMN elements
 */
export interface InputOutputParameter {
	name: string;
	type: 'text' | 'script' | 'list' | 'map';
	// For text type
	value?: string;
	// For script type
	scriptFormat?: string;
	script?: string;
	// For list type
	listValues?: string[];
	// For map type
	mapEntries?: { key: string; value: string }[];
}

/**
 * Connector Input Parameter for Message Throw Events
 */
export interface ConnectorInput {
	name: string;
	type: 'text' | 'script' | 'list' | 'map';
	value?: string;
	scriptFormat?: string;
	script?: string;
	listValues?: string[];
	mapEntries?: { key: string; value: string }[];
}

/**
 * Connector Output Parameter for Message Throw Events
 */
export interface ConnectorOutput {
	name: string;
	type: 'text' | 'script';
	value?: string;
	scriptFormat?: string;
	script?: string;
}

// =============================================================================
// Geometry Types
// =============================================================================

/**
 * Point coordinates
 */
export interface Point {
	x: number;
	y: number;
}

/**
 * Bounding box for shapes
 */
export interface Bounds {
	x: number;
	y: number;
	width: number;
	height: number;
}

/**
 * Label positioning information
 */
export interface LabelInfo {
	offsetX?: number;
	offsetY?: number;
	width?: number;
}

// =============================================================================
// Definition Types (Process-level definitions)
// =============================================================================

/**
 * Message definition at process level
 */
export interface MessageDefinition {
	id: string;
	name: string;
}

/**
 * Signal definition at process level
 */
export interface SignalDefinition {
	id: string;
	name: string;
}

/**
 * Error definition at process level
 */
export interface ErrorDefinition {
	id: string;
	name: string;
	code?: string;
}

/**
 * Escalation definition at process level
 */
export interface EscalationDefinition {
	id: string;
	name: string;
	code?: string;
}

// =============================================================================
// Semantic Layer Types
// =============================================================================

/**
 * BPMN Semantic Element - Logical process element without visual information
 * Coordinates (x, y, width, height) are NOT stored here; they belong to DI layer.
 */
export interface BpmnSemantic {
	id: string;
	type: string;
	subType?: string; // Derived type for type change feature (e.g., 'none', 'message', 'timer')
	name: string;
	// Parent relationship for nested elements (Sub-Process children)
	parentId?: string;
	// Common Camunda extensions
	documentation?: string;
	executionListeners?: ExecutionListener[];
	taskListeners?: TaskListener[];
	extensionProperties?: ExtensionProperty[];
	// Start Event specific
	initiator?: string;
	formKey?: string;
	// Start Event and End Event common (Asynchronous Continuations)
	asyncBefore?: boolean;
	asyncAfter?: boolean;
	exclusive?: boolean;
	jobPriority?: string;
	retryTimeCycle?: string;
	// End Event specific (Input/Output Parameters)
	inputParameters?: InputOutputParameter[];
	outputParameters?: InputOutputParameter[];
	// Failed job retry time cycle (Camunda extension)
	failedJobRetryTimeCycle?: string;
	// Verbatim-preserved children of <bpmn:extensionElements> that the editor
	// does not model (e.g. camunda:taskListener, camunda:connector). Each entry
	// is one pretty-printed extension element (2-space indented, base column 0)
	// captured on parse and re-emitted unchanged on save so unknown extensions
	// survive the round-trip instead of being silently dropped.
	unknownExtensions?: string[];
	// User Task specific
	assignee?: string;
	candidateUsers?: string;
	candidateGroups?: string;
	// Service Task specific
	implementation?: string;
	javaClass?: string;
	expression?: string;
	delegateExpression?: string;
	fieldInjections?: Array<{
		name: string;
		type: 'string' | 'expression';
		value: string;
	}>;
	// Script Task specific
	scriptFormat?: string;
	script?: string;
	// Event Definition specific (Message, Timer, Conditional, Signal, Error, Escalation, etc.)
	messageRef?: string;
	messageName?: string;
	timerType?: 'timeDate' | 'timeDuration' | 'timeCycle';
	timerValue?: string;
	signalRef?: string;
	signalName?: string;
	errorRef?: string;
	errorName?: string;
	errorCode?: string;
	escalationRef?: string;
	escalationName?: string;
	escalationCode?: string;
	// Conditional Event specific
	conditionType?: 'expression' | 'script';
	conditionExpression?: string;
	conditionScriptFormat?: string;
	conditionScript?: string;
	conditionScriptType?: 'inline' | 'external';
	conditionScriptResource?: string;
	conditionVariableName?: string;
	conditionVariableEvents?: string; // 'create', 'update', 'delete' comma-separated
	// Message Throw Event Implementation (End Event / Intermediate Throw Event)
	messageImplementationType?: 'class' | 'expression' | 'external' | 'connector';
	messageJavaClass?: string;
	messageExpression?: string;
	messageResultVariable?: string;
	messageTopic?: string;
	messageExternalPriority?: string;
	messageConnectorId?: string;
	messageConnectorInputs?: ConnectorInput[];
	messageConnectorOutputs?: ConnectorOutput[];
	messageFieldInjections?: Array<{
		name: string;
		type: 'string' | 'expression';
		value: string;
	}>;
	// Intermediate Event specific
	catching?: boolean; // true: Catching (receive), false: Throwing (send)
	// Link Event
	linkName?: string;
	// Compensation Event
	compensationActivityRef?: string;
	compensationWaitForCompletion?: boolean;
	// Boundary Event specific
	attachedToRef?: string;      // ID of the element this boundary event is attached to
	cancelActivity?: boolean;    // true: Interrupting (default), false: Non-interrupting
	// Send/Receive Task specific
	implementationType?: 'class' | 'expression' | 'external' | 'connector' | 'dmn';
	resultVariable?: string;
	topic?: string;
	priority?: string;
	// Business Rule Task specific (DMN)
	decisionRef?: string;
	decisionRefBinding?: 'latest' | 'deployment' | 'version' | 'versionTag';
	decisionRefVersion?: string;
	decisionRefVersionTag?: string;
	decisionRefTenantId?: string;
	mapDecisionResult?: 'singleEntry' | 'singleResult' | 'collectEntries' | 'resultList';
	// Call Activity specific
	calledElementType?: 'bpmn' | 'cmmn';
	calledElement?: string;
	calledElementBinding?: 'latest' | 'deployment' | 'version' | 'versionTag';
	calledElementVersion?: string;
	calledElementVersionTag?: string;
	calledElementTenantId?: string;
	businessKey?: string;
	variableMappingDelegateExpression?: boolean;
	inMappings?: Array<{ source: string; target: string }>;
	outMappings?: Array<{ source: string; target: string }>;
	// Gateway specific (Exclusive, Inclusive)
	defaultFlow?: string;        // Reference to default sequence flow ID
	// Event-based Gateway specific
	instantiate?: boolean;
	eventGatewayType?: 'exclusive' | 'parallel';
	// Complex Gateway specific
	activationCondition?: string;
	// Sub-Process specific
	triggeredByEvent?: boolean;  // Event Sub-Process: true
	transactionMethod?: string;  // Transaction: 'compensate', 'store', 'image'
	// Loop / Multi-Instance (for Tasks and Sub-Processes)
	loopType?: '' | 'standard' | 'multiInstanceParallel' | 'multiInstanceSequential';
	collection?: string;
	elementVariable?: string;
	// Data Object specific
	isCollection?: boolean;      // Collection (multiple data) marker
	dataState?: string;          // Data state name
	dataObjectRef?: string;      // Reference to data object
	// Data Store specific
	dataStoreRef?: string;       // Reference to data store
	// Pool specific
	processRef?: string;         // Reference to process ID
	isHorizontal?: boolean;      // Horizontal (default) or vertical
	// Pool/Process-level Camunda extensions: who can start this process.
	// Edited on the Pool but serialized onto bpmn:process as camunda:candidateStarterGroups/Users.
	candidateStarterGroups?: string;
	candidateStarterUsers?: string;
	// Lane specific
	parentPoolId?: string;       // Parent Pool ID
	flowNodeRefs?: string[];     // Element IDs within this Lane
}

/**
 * BPMN Flow Semantic - Logical connection without visual information
 * Waypoints are NOT stored here; they belong to DI layer.
 */
export interface BpmnFlowSemantic {
	id: string;
	sourceRef: string;
	targetRef: string;
	name?: string;
	// Condition settings
	conditionType?: 'expression' | 'script';
	conditionExpression?: string;
	conditionScriptFormat?: string;
	conditionScript?: string;
	// Connection type. messageFlow connects two pools and is emitted inside
	// <bpmn:collaboration>; the others stay inside their owning <bpmn:process>.
	connectionType?: 'sequenceFlow' | 'dataAssociation' | 'association' | 'messageFlow';
	// messageFlow only: optional reference to a <bpmn:message> definition
	messageRef?: string;
}

// =============================================================================
// DI Layer Types (Diagram Interchange)
// =============================================================================

/**
 * BPMN DI Shape - Visual representation of a semantic element
 */
export interface BpmnDiShape {
	id: string;                   // Shape-specific UUID
	bpmnElement: string;          // Reference to BpmnSemantic.id
	bounds: Bounds;               // Position and size
	label?: LabelInfo;            // Optional label positioning
	isExpanded?: boolean;         // For Sub-Process: expanded/collapsed state
	isHorizontal?: boolean;       // For Pool: horizontal or vertical orientation
}

/**
 * BPMN DI Edge - Visual representation of a flow connection
 */
export interface BpmnDiEdge {
	id: string;                   // Edge-specific UUID
	bpmnElement: string;          // Reference to BpmnFlowSemantic.id
	waypoints: Point[];           // Connection path points
	label?: LabelInfo;            // Optional label positioning
	// Routing hints (in-memory only, not serialized to BPMN XML).
	// Compass side from which the connection exits the source element ('N'|'S'|'E'|'W').
	sourceSide?: 'N' | 'S' | 'E' | 'W';
	// Compass side at which the connection enters the target element.
	targetSide?: 'N' | 'S' | 'E' | 'W';
	// True once the user has dragged a bend point or otherwise customised the
	// path; the renderer will then use waypoints as-is and the move handler
	// will only translate first/last waypoint instead of regenerating.
	manuallyAdjusted?: boolean;
}

/**
 * BPMN DI Plane - Container for shapes and edges of a single diagram
 */
export interface BpmnDiPlane {
	id: string;
	bpmnElement: string;          // Reference to Process or Collaboration
	shapes: BpmnDiShape[];
	edges: BpmnDiEdge[];
}

/**
 * BPMN DI Diagram - Top-level diagram container
 */
export interface BpmnDiDiagram {
	id: string;
	name: string;
	plane: BpmnDiPlane;
}

// =============================================================================
// Combined Model Data
// =============================================================================

/**
 * Per-process data. One of these exists for each <bpmn:process> in the file.
 * In a single-process file (no pools) there is exactly one entry, mirrored to
 * the top-level processId/processName/isExecutable fields for legacy callers.
 * In a collaboration the array holds one entry per pool's processRef.
 */
export interface BpmnProcessData {
	id: string;
	name: string;
	isExecutable: boolean;
	candidateStarterGroups?: string;
	candidateStarterUsers?: string;
}

/**
 * Complete BPMN Model Data - Contains both semantic and diagram information
 */
export interface BpmnModelData {
	// Primary process information.
	// In a multi-process (collaboration) file these mirror processes[0] for
	// backwards compatibility with callers that still assume a single process.
	processId: string;
	processName: string;
	isExecutable: boolean;
	// Process-level Camunda extensions: who can start an instance of this process.
	// Comma-separated lists serialized as camunda:candidateStarterGroups / camunda:candidateStarterUsers.
	candidateStarterGroups?: string;
	candidateStarterUsers?: string;
	// All processes in the file. When empty the serializer falls back to
	// emitting a single <bpmn:process> built from the primary fields above.
	processes?: BpmnProcessData[];
	// Process-level definitions
	messages: MessageDefinition[];
	signals: SignalDefinition[];
	errors: ErrorDefinition[];
	escalations: EscalationDefinition[];
}

// =============================================================================
// Rendering Helper Types
// =============================================================================

/**
 * Combined shape data for rendering (Shape + Semantic)
 */
export interface ShapeForRendering {
	shape: BpmnDiShape;
	semantic: BpmnSemantic;
}

/**
 * Combined edge data for rendering (Edge + FlowSemantic)
 */
export interface EdgeForRendering {
	edge: BpmnDiEdge;
	flow: BpmnFlowSemantic;
}

// =============================================================================
// BpmnModelStore - Central store for BPMN model data
// =============================================================================

/**
 * Central store for BPMN model data with indexing and lookup capabilities.
 * Manages both semantic layer and DI layer with automatic index maintenance.
 */
export class BpmnModelStore {
	// Semantic layer storage
	private semantics: Map<string, BpmnSemantic> = new Map();
	private flows: Map<string, BpmnFlowSemantic> = new Map();

	// DI layer storage
	private shapes: Map<string, BpmnDiShape> = new Map();
	private edges: Map<string, BpmnDiEdge> = new Map();
	private diagrams: Map<string, BpmnDiDiagram> = new Map();

	// Reverse lookup indexes
	private shapesByBpmnElement: Map<string, string[]> = new Map();  // SemanticID -> ShapeIDs
	private edgesByBpmnElement: Map<string, string[]> = new Map();   // FlowID -> EdgeIDs
	private childrenByParentId: Map<string, string[]> = new Map();   // ParentID -> SemanticIDs

	// Model data
	private modelData: BpmnModelData = {
		processId: '',
		processName: '',
		isExecutable: true,
		messages: [],
		signals: [],
		errors: [],
		escalations: []
	};

	// =========================================================================
	// Model Data Methods
	// =========================================================================

	/**
	 * Get model data
	 */
	getModelData(): BpmnModelData {
		return this.modelData;
	}

	/**
	 * Set model data
	 */
	setModelData(data: Partial<BpmnModelData>): void {
		this.modelData = { ...this.modelData, ...data };
	}

	/**
	 * Get all processes. If processes[] is unset, derive a single-entry array
	 * from the primary processId/processName/isExecutable fields so callers
	 * can iterate uniformly regardless of whether the file is a collaboration.
	 */
	getProcesses(): BpmnProcessData[] {
		if (this.modelData.processes && this.modelData.processes.length > 0) {
			return this.modelData.processes;
		}
		return [{
			id: this.modelData.processId || 'Process_1',
			name: this.modelData.processName || '',
			isExecutable: this.modelData.isExecutable,
			candidateStarterGroups: this.modelData.candidateStarterGroups,
			candidateStarterUsers: this.modelData.candidateStarterUsers,
		}];
	}

	/**
	 * Replace the processes array. Also mirrors processes[0] onto the primary
	 * fields so legacy callers that read processId/processName/etc. keep working.
	 */
	setProcesses(processes: BpmnProcessData[]): void {
		this.modelData.processes = processes;
		const primary = processes[0];
		if (primary) {
			this.modelData.processId = primary.id;
			this.modelData.processName = primary.name;
			this.modelData.isExecutable = primary.isExecutable;
			this.modelData.candidateStarterGroups = primary.candidateStarterGroups;
			this.modelData.candidateStarterUsers = primary.candidateStarterUsers;
		}
	}

	// =========================================================================
	// Semantic Methods
	// =========================================================================

	/**
	 * Add a semantic element
	 */
	addSemantic(semantic: BpmnSemantic): void {
		this.semantics.set(semantic.id, semantic);
		this.updateChildrenIndex(semantic.id, undefined, semantic.parentId);
	}

	/**
	 * Get a semantic element by ID
	 */
	getSemantic(id: string): BpmnSemantic | undefined {
		return this.semantics.get(id);
	}

	/**
	 * Get all semantic elements
	 */
	getAllSemantics(): BpmnSemantic[] {
		return Array.from(this.semantics.values());
	}

	/**
	 * Update a semantic element (partial update)
	 * Automatically updates children index if parentId changes
	 * NOTE: parentId is only updated if explicitly provided in changes (using 'in' check)
	 */
	updateSemantic(id: string, changes: Partial<BpmnSemantic>): boolean {
		const existing = this.semantics.get(id);
		if (!existing) return false;

		// Only process parentId change if explicitly provided in changes
		// This prevents accidental clearing of parentId when updating other properties
		const parentIdExplicitlyProvided = 'parentId' in changes;
		const oldParentId = existing.parentId;
		const newParentId = parentIdExplicitlyProvided ? changes.parentId : oldParentId;

		// Update the semantic, but preserve parentId if not explicitly provided
		const updated = parentIdExplicitlyProvided
			? { ...existing, ...changes }
			: { ...existing, ...changes, parentId: oldParentId };
		this.semantics.set(id, updated);

		// Update children index only if parentId was explicitly changed
		if (parentIdExplicitlyProvided && oldParentId !== newParentId) {
			this.updateChildrenIndex(id, oldParentId, newParentId);
		}

		return true;
	}

	/**
	 * Remove a semantic element and all related shapes/flows (cascade delete)
	 * Also removes any flows connected to this element to prevent dangling references
	 */
	removeSemantic(id: string): boolean {
		const semantic = this.semantics.get(id);
		if (!semantic) return false;

		// Remove from children index
		this.updateChildrenIndex(id, semantic.parentId, undefined);

		// Remove all related shapes
		const shapeIds = this.shapesByBpmnElement.get(id) || [];
		for (const shapeId of shapeIds) {
			this.shapes.delete(shapeId);
		}
		this.shapesByBpmnElement.delete(id);

		// Remove connected flows (to prevent dangling references)
		const connectedFlows = this.getConnectedFlows(id);
		for (const flow of connectedFlows) {
			this.removeFlow(flow.id);
		}

		// Remove the semantic
		this.semantics.delete(id);

		return true;
	}

	/**
	 * Get children of a parent element
	 */
	getChildren(parentId: string): BpmnSemantic[] {
		const childIds = this.childrenByParentId.get(parentId) || [];
		return childIds.map(id => this.semantics.get(id)).filter((s): s is BpmnSemantic => s !== undefined);
	}

	/**
	 * Get root-level semantics (no parent)
	 */
	getRootSemantics(): BpmnSemantic[] {
		return Array.from(this.semantics.values()).filter(s => !s.parentId);
	}

	// =========================================================================
	// Flow Methods
	// =========================================================================

	/**
	 * Add a flow
	 */
	addFlow(flow: BpmnFlowSemantic): void {
		this.flows.set(flow.id, flow);
	}

	/**
	 * Get a flow by ID
	 */
	getFlow(id: string): BpmnFlowSemantic | undefined {
		return this.flows.get(id);
	}

	/**
	 * Get all flows
	 */
	getAllFlows(): BpmnFlowSemantic[] {
		return Array.from(this.flows.values());
	}

	/**
	 * Update a flow (partial update)
	 */
	updateFlow(id: string, changes: Partial<BpmnFlowSemantic>): boolean {
		const existing = this.flows.get(id);
		if (!existing) return false;

		this.flows.set(id, { ...existing, ...changes });
		return true;
	}

	/**
	 * Remove a flow and all related edges (cascade delete)
	 */
	removeFlow(id: string): boolean {
		if (!this.flows.has(id)) return false;

		// Remove all related edges
		const edgeIds = this.edgesByBpmnElement.get(id) || [];
		for (const edgeId of edgeIds) {
			this.edges.delete(edgeId);
		}
		this.edgesByBpmnElement.delete(id);

		// Remove the flow
		this.flows.delete(id);

		return true;
	}

	/**
	 * Get flows connected to a semantic element (as source or target)
	 */
	getConnectedFlows(semanticId: string): BpmnFlowSemantic[] {
		return Array.from(this.flows.values()).filter(
			f => f.sourceRef === semanticId || f.targetRef === semanticId
		);
	}

	// =========================================================================
	// Shape Methods
	// =========================================================================

	/**
	 * Add a shape
	 */
	addShape(shape: BpmnDiShape): void {
		this.shapes.set(shape.id, shape);
		this.addToIndex(this.shapesByBpmnElement, shape.bpmnElement, shape.id);
	}

	/**
	 * Get a shape by ID
	 */
	getShape(id: string): BpmnDiShape | undefined {
		return this.shapes.get(id);
	}

	/**
	 * Get all shapes
	 */
	getAllShapes(): BpmnDiShape[] {
		return Array.from(this.shapes.values());
	}

	/**
	 * Get shapes for a semantic element
	 */
	getShapesForSemantic(semanticId: string): BpmnDiShape[] {
		const shapeIds = this.shapesByBpmnElement.get(semanticId) || [];
		return shapeIds.map(id => this.shapes.get(id)).filter((s): s is BpmnDiShape => s !== undefined);
	}

	/**
	 * Update shape bounds (optimized for drag operations)
	 */
	updateShapeBounds(id: string, newBounds: Bounds): boolean {
		const shape = this.shapes.get(id);
		if (!shape) return false;

		shape.bounds = newBounds;
		return true;
	}

	/**
	 * Update a shape (partial update)
	 */
	updateShape(id: string, changes: Partial<BpmnDiShape>): boolean {
		const existing = this.shapes.get(id);
		if (!existing) return false;

		// Handle bpmnElement change
		if (changes.bpmnElement && changes.bpmnElement !== existing.bpmnElement) {
			this.removeFromIndex(this.shapesByBpmnElement, existing.bpmnElement, id);
			this.addToIndex(this.shapesByBpmnElement, changes.bpmnElement, id);
		}

		this.shapes.set(id, { ...existing, ...changes });
		return true;
	}

	/**
	 * Remove a shape
	 */
	removeShape(id: string): boolean {
		const shape = this.shapes.get(id);
		if (!shape) return false;

		this.removeFromIndex(this.shapesByBpmnElement, shape.bpmnElement, id);
		this.shapes.delete(id);

		return true;
	}

	// =========================================================================
	// Edge Methods
	// =========================================================================

	/**
	 * Add an edge
	 */
	addEdge(edge: BpmnDiEdge): void {
		this.edges.set(edge.id, edge);
		this.addToIndex(this.edgesByBpmnElement, edge.bpmnElement, edge.id);
	}

	/**
	 * Get an edge by ID
	 */
	getEdge(id: string): BpmnDiEdge | undefined {
		return this.edges.get(id);
	}

	/**
	 * Get all edges
	 */
	getAllEdges(): BpmnDiEdge[] {
		return Array.from(this.edges.values());
	}

	/**
	 * Get edges for a flow
	 */
	getEdgesForFlow(flowId: string): BpmnDiEdge[] {
		const edgeIds = this.edgesByBpmnElement.get(flowId) || [];
		return edgeIds.map(id => this.edges.get(id)).filter((e): e is BpmnDiEdge => e !== undefined);
	}

	/**
	 * Update edge waypoints (optimized for connection editing)
	 */
	updateEdgeWaypoints(id: string, waypoints: Point[]): boolean {
		const edge = this.edges.get(id);
		if (!edge) return false;

		edge.waypoints = waypoints;
		return true;
	}

	/**
	 * Update an edge (partial update)
	 */
	updateEdge(id: string, changes: Partial<BpmnDiEdge>): boolean {
		const existing = this.edges.get(id);
		if (!existing) return false;

		// Handle bpmnElement change
		if (changes.bpmnElement && changes.bpmnElement !== existing.bpmnElement) {
			this.removeFromIndex(this.edgesByBpmnElement, existing.bpmnElement, id);
			this.addToIndex(this.edgesByBpmnElement, changes.bpmnElement, id);
		}

		this.edges.set(id, { ...existing, ...changes });
		return true;
	}

	/**
	 * Remove an edge
	 */
	removeEdge(id: string): boolean {
		const edge = this.edges.get(id);
		if (!edge) return false;

		this.removeFromIndex(this.edgesByBpmnElement, edge.bpmnElement, id);
		this.edges.delete(id);

		return true;
	}

	// =========================================================================
	// Diagram Methods
	// =========================================================================

	/**
	 * Add a diagram
	 */
	addDiagram(diagram: BpmnDiDiagram): void {
		this.diagrams.set(diagram.id, diagram);
	}

	/**
	 * Get a diagram by ID
	 */
	getDiagram(id: string): BpmnDiDiagram | undefined {
		return this.diagrams.get(id);
	}

	/**
	 * Get all diagrams
	 */
	getAllDiagrams(): BpmnDiDiagram[] {
		return Array.from(this.diagrams.values());
	}

	/**
	 * Update a diagram (partial update)
	 */
	updateDiagram(id: string, changes: Partial<BpmnDiDiagram>): boolean {
		const existing = this.diagrams.get(id);
		if (!existing) return false;

		this.diagrams.set(id, { ...existing, ...changes });
		return true;
	}

	/**
	 * Remove a diagram
	 */
	removeDiagram(id: string): boolean {
		return this.diagrams.delete(id);
	}

	// =========================================================================
	// Rendering Helpers
	// =========================================================================

	/**
	 * Get shapes combined with their semantic data for rendering
	 */
	getShapesForRendering(): ShapeForRendering[] {
		const result: ShapeForRendering[] = [];

		this.shapes.forEach((shape) => {
			const semantic = this.semantics.get(shape.bpmnElement);
			if (semantic) {
				result.push({ shape, semantic });
			}
		});

		return result;
	}

	/**
	 * Get edges combined with their flow data for rendering
	 */
	getEdgesForRendering(): EdgeForRendering[] {
		const result: EdgeForRendering[] = [];

		this.edges.forEach((edge) => {
			const flow = this.flows.get(edge.bpmnElement);
			if (flow) {
				result.push({ edge, flow });
			}
		});

		return result;
	}

	// =========================================================================
	// Clear
	// =========================================================================

	/**
	 * Clear all data
	 */
	clear(): void {
		this.semantics.clear();
		this.flows.clear();
		this.shapes.clear();
		this.edges.clear();
		this.diagrams.clear();
		this.shapesByBpmnElement.clear();
		this.edgesByBpmnElement.clear();
		this.childrenByParentId.clear();
		this.modelData = {
			processId: '',
			processName: '',
			isExecutable: true,
			processes: [],
			messages: [],
			signals: [],
			errors: [],
			escalations: []
		};
	}

	// =========================================================================
	// Private Index Helpers
	// =========================================================================

	/**
	 * Add value to index
	 */
	private addToIndex(index: Map<string, string[]>, key: string, value: string): void {
		const existing = index.get(key);
		if (existing) {
			if (!existing.includes(value)) {
				existing.push(value);
			}
		} else {
			index.set(key, [value]);
		}
	}

	/**
	 * Remove value from index
	 */
	private removeFromIndex(index: Map<string, string[]>, key: string, value: string): void {
		const existing = index.get(key);
		if (existing) {
			const idx = existing.indexOf(value);
			if (idx !== -1) {
				existing.splice(idx, 1);
			}
			if (existing.length === 0) {
				index.delete(key);
			}
		}
	}

	/**
	 * Update children index when parentId changes
	 */
	private updateChildrenIndex(semanticId: string, oldParentId: string | undefined, newParentId: string | undefined): void {
		// Remove from old parent's children
		if (oldParentId) {
			this.removeFromIndex(this.childrenByParentId, oldParentId, semanticId);
		}

		// Add to new parent's children
		if (newParentId) {
			this.addToIndex(this.childrenByParentId, newParentId, semanticId);
		}
	}
}

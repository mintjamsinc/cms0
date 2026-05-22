// =============================================================================
// Rendering DTO types (semantic + geometry merged)
// =============================================================================
// BpmnElement / BpmnConnection are flat shapes consumed by the Vue template
// and toolbar code. The editor builds them on the fly from BpmnModelStore
// (semantic + DI layers) via getElementsForRendering() / getConnectionsForRendering().
// They carry coordinates so the canvas can render with simple property access.
// =============================================================================

import type {
	ConnectorInput,
	ConnectorOutput,
	ExecutionListener,
	ExtensionProperty,
	InputOutputParameter,
	MessageDefinition,
	SignalDefinition,
	ErrorDefinition,
	EscalationDefinition,
} from './bpmn-model-types.js';

export type {
	ConnectorInput,
	ConnectorOutput,
	ExecutionListener,
	ExtensionProperty,
	InputOutputParameter,
	MessageDefinition,
	SignalDefinition,
	ErrorDefinition,
	EscalationDefinition,
} from './bpmn-model-types.js';

export interface BpmnElement {
	id: string;
	type: string;
	subType?: string; // Derived type for type change feature (e.g., 'none', 'message', 'timer')
	name: string;
	x: number;
	y: number;
	width: number;
	height: number;
	// Label position and size (optional, for user customization)
	labelOffsetX?: number;
	labelOffsetY?: number;
	labelWidth?: number;
	// Common Camunda extensions
	documentation?: string;
	executionListeners?: ExecutionListener[];
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
	// Event-based Gateway specific
	instantiate?: boolean;
	eventGatewayType?: 'exclusive' | 'parallel';
	// Complex Gateway specific
	activationCondition?: string;
	// Sub-Process specific
	triggeredByEvent?: boolean;  // Event Sub-Process: true
	isExpanded?: boolean;        // Expanded state
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
	// Sub-Process child element support
	parentId?: string;           // Parent Sub-Process ID (for nested elements)
}

export interface BpmnConnection {
	id: string;
	sourceRef: string;
	targetRef: string;
	name?: string;
	// Condition settings
	conditionType?: 'expression' | 'script';
	conditionExpression?: string;
	conditionScriptFormat?: string;
	conditionScript?: string;
	waypoints?: { x: number; y: number }[];
	// Label position and size (optional, for user customization)
	labelOffsetX?: number;
	labelOffsetY?: number;
	labelWidth?: number;
	// Connection type: 'sequenceFlow' (default), 'dataAssociation' (dashed),
	// 'association' (dotted, no arrow), or 'messageFlow' (cross-pool, dashed
	// with hollow circle/arrow markers).
	connectionType?: 'sequenceFlow' | 'dataAssociation' | 'association' | 'messageFlow';
	// Routing hints (in-memory only). See BpmnDiEdge for semantics.
	sourceSide?: 'N' | 'S' | 'E' | 'W';
	targetSide?: 'N' | 'S' | 'E' | 'W';
	manuallyAdjusted?: boolean;
}

/**
 * Option entry in the type-change menu (e.g. wrench popup) and several
 * subtype dropdowns. Defined here so element-config.ts can declare the
 * ELEMENT_SUBTYPES table that drives these menus.
 */
export interface SubTypeOption {
	label: string;
	subType: string;
	icon?: string;
	catching?: boolean; // For intermediate events: true = Catching, false = Throwing
}

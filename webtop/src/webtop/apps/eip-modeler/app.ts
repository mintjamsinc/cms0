import { ApplicationInstance } from "../../services/webtop-service.js";
import type { Node } from "../../graphql/types.js";
import { VDOM } from '@mintjamsinc/ichigojs';
import {
	CamelModelStore,
	CamelProcessorSemantic,
	CamelFlowSemantic,
	CamelDiShape,
	CamelDiEdge,
	generateUUID,
	type Bounds,
	type Point
} from './camel-model-types.js';

// =============================================================================
// Type Definitions
// =============================================================================

/**
 * EIP Types supported by Apache Camel
 */
type EipType =
	// Basic
	| 'from' | 'to' | 'toD'
	// Routing
	| 'choice' | 'filter' | 'split' | 'aggregate' | 'multicast'
	| 'recipientList' | 'routingSlip' | 'dynamicRouter' | 'loadBalance' | 'loop'
	// Transformation
	| 'log' | 'setBody' | 'setHeader' | 'setHeaders' | 'setProperty' | 'setVariable'
	| 'removeHeader' | 'removeHeaders' | 'transform' | 'marshal' | 'unmarshal' | 'convertBodyTo'
	// Error Handling
	| 'onException' | 'doTry' | 'doCatch' | 'doFinally'
	// Control Flow
	| 'delay' | 'throttle' | 'stop' | 'process' | 'bean' | 'circuitBreaker' | 'threads' | 'step'
	// Merge (visual-only, not output to XML)
	| 'merge'
	// Others
	| 'wireTap' | 'enrich' | 'pollEnrich' | 'script' | 'validate' | 'saga';

/**
 * Escape special characters for XML content
 */
function escapeXml(str: string): string {
	return str
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;')
		.replace(/'/g, '&apos;');
}

/**
 * File management interface (tab management)
 */
interface CamelFile {
	id: string;
	name: string;
	path: string;
	mimeType: string;
	store: CamelModelStore;
	scale: number;
	panOffset: { x: number; y: number };
	isModified: boolean;
	undoStack: string[];
	redoStack: string[];
}

/**
 * Palette item definition
 */
interface PaletteCategory {
	/** Stable key used for the per-category collapsed/expanded state. */
	key: string;
	name: string;
	items: PaletteItem[];
}

/**
 * Palette item
 */
interface PaletteItem {
	type: EipType;
	label: string;
	icon: string;
}

/**
 * Generic option for shell-rendered popup dropdowns.
 * Mirrors the BPMN editor's ChoiceOption shape.
 */
interface ChoiceOption {
	id: string;
	label: string;
}

// Sentinel for the implicit "(none)" / placeholder choice in popups.
const EMPTY_CHOICE_ID = '__empty__';

// -- Static option lists for property-panel dropdowns ----------------------
const FLOW_CONDITION_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'when',      label: 'When (Condition)' },
	{ id: 'otherwise', label: 'Otherwise' },
];
const FLOW_LANGUAGE_OPTIONS: ChoiceOption[] = [
	{ id: 'simple',   label: 'Simple' },
	{ id: 'xpath',    label: 'XPath' },
	{ id: 'jsonpath', label: 'JSONPath' },
];
const FLOW_ROLE_OPTIONS: ChoiceOption[] = [
	{ id: 'try',     label: 'Try (Main Flow)' },
	{ id: 'catch',   label: 'Catch (Error Handling)' },
	{ id: 'finally', label: 'Finally (Always Run)' },
];
const LOGGING_LEVEL_OPTIONS: ChoiceOption[] = [
	{ id: 'TRACE', label: 'TRACE' },
	{ id: 'DEBUG', label: 'DEBUG' },
	{ id: 'INFO',  label: 'INFO' },
	{ id: 'WARN',  label: 'WARN' },
	{ id: 'ERROR', label: 'ERROR' },
];
const SET_HEADER_EXPRESSION_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'simple',   label: 'Simple' },
	{ id: 'constant', label: 'Constant' },
	{ id: 'jsonpath', label: 'JSONPath' },
];
const FILTER_EXPRESSION_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'simple',   label: 'Simple' },
	{ id: 'xpath',    label: 'XPath' },
	{ id: 'jsonpath', label: 'JSONPath' },
];
const SPLIT_EXPRESSION_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'simple',   label: 'Simple' },
	{ id: 'xpath',    label: 'XPath' },
	{ id: 'jsonpath', label: 'JSONPath' },
	{ id: 'tokenize', label: 'Tokenize' },
];
const TRANSFORM_EXPRESSION_TYPE_OPTIONS: ChoiceOption[] = [
	{ id: 'simple',   label: 'Simple' },
	{ id: 'xpath',    label: 'XPath' },
	{ id: 'jsonpath', label: 'JSONPath' },
	{ id: 'groovy',   label: 'Groovy' },
];
const DATA_FORMAT_OPTIONS: ChoiceOption[] = [
	{ id: 'json',     label: 'JSON' },
	{ id: 'xml',      label: 'XML (JAXB)' },
	{ id: 'csv',      label: 'CSV' },
	{ id: 'yaml',     label: 'YAML' },
	{ id: 'avro',     label: 'Avro' },
	{ id: 'protobuf', label: 'Protobuf' },
];
const JSON_LIBRARY_OPTIONS: ChoiceOption[] = [
	{ id: 'jackson', label: 'Jackson' },
	{ id: 'gson',    label: 'Gson' },
	{ id: 'jsonb',   label: 'JSON-B' },
];

/**
 * Launch options for the application
 */
interface LaunchOptions {
	path?: string;
	mimeType?: string;
	paths?: string[];
	activeIndex?: number;
	fileStates?: { path: string; scale?: number; panOffset?: { x: number; y: number } }[];
}

// =============================================================================
// Constants
// =============================================================================

// Node dimensions
const NODE_WIDTH = 140;
const NODE_HEIGHT = 60;
const FROM_WIDTH = 120;
const FROM_HEIGHT = 50;
const CHOICE_WIDTH = 100;
const CHOICE_HEIGHT = 60;
const MERGE_WIDTH = 50;
const MERGE_HEIGHT = 50;

// Layout constants
const STEP_GAP_X = 40;
const STEP_GAP_Y = 80;

/**
 * Unified node style constants
 */
const NODE_STYLE = {
	width: 140,
	height: 60,
	borderRadius: 8,
	iconSize: 24,
	padding: 10,
	ports: {
		left:   { x: 0,   y: 30 },
		right:  { x: 140, y: 30 },
		top:    { x: 70,  y: 0 },
		bottom: { x: 70,  y: 60 }
	},
	inputPort: { x: 0, y: 30 },
	outputPort: { x: 140, y: 30 }
};

const FROM_NODE_STYLE = {
	width: 120,
	height: 50,
	borderRadius: 25,
	iconSize: 20,
	padding: 10,
	ports: {
		left:   { x: 0,   y: 25 },
		right:  { x: 120, y: 25 },
		top:    { x: 60,  y: 0 },
		bottom: { x: 60,  y: 50 }
	},
	inputPort: { x: 0, y: 25 },
	outputPort: { x: 120, y: 25 }
};

const CHOICE_NODE_STYLE = {
	width: 100,
	height: 60,
	ports: {
		left:   { x: 0,   y: 30 },
		right:  { x: 100, y: 30 },
		top:    { x: 50,  y: 0 },
		bottom: { x: 50,  y: 60 }
	},
	inputPort: { x: 0, y: 30 },
	outputPort: { x: 100, y: 30 }
};

const MERGE_NODE_STYLE = {
	width: 50,
	height: 50,
	ports: {
		left:   { x: 0,   y: 25 },
		right:  { x: 50,  y: 25 },
		top:    { x: 25,  y: 0 },
		bottom: { x: 25,  y: 50 }
	},
	inputPort: { x: 0, y: 25 },
	outputPort: { x: 50, y: 25 }
};

// =============================================================================
// Helper Functions
// =============================================================================

/**
 * Create default properties for a processor type
 */
function getDefaultProperties(type: EipType): Record<string, any> {
	const defaults: Record<string, Record<string, any>> = {
		from: { uri: 'timer:tick', parameters: { period: '1000' }, routeId: '', routeConfigurationId: '' },
		to: { uri: 'log:output' },
		toD: { uri: '' },
		log: { message: '${body}', loggingLevel: 'INFO', loggerName: '' },
		setBody: { simple: '' },
		setHeader: { name: '', expressionType: 'simple', expression: '' },
		setHeaders: { headers: [] },
		setProperty: { name: '', simple: '' },
		setVariable: { name: '', simple: '' },
		removeHeader: { name: '' },
		removeHeaders: { pattern: '' },
		transform: { expressionType: 'simple', expression: '' },
		marshal: { dataFormat: 'json', library: 'jackson', prettyPrint: false, unmarshalType: '' },
		unmarshal: { dataFormat: 'json', library: 'jackson', unmarshalType: '' },
		convertBodyTo: { type: '' },
		choice: { when: [{ expressionType: 'simple', expression: '', steps: [] }], otherwise: undefined },
		filter: { expressionType: 'simple', expression: '' },
		split: {
			expressionType: 'simple',
			expression: '${body}',
			streaming: false,
			parallelProcessing: false,
			aggregationStrategy: '',
			stopOnException: false,
			shareUnitOfWork: false
		},
		aggregate: {
			correlationExpression: '${header.CamelFileName}',
			completionSize: 10,
			completionTimeout: 5000,
			aggregationStrategy: '',
			eagerCheckCompletion: false,
			completionFromBatchConsumer: false
		},
		multicast: { parallelProcessing: false, aggregationStrategy: '', stopOnException: false },
		recipientList: { simple: '', parallelProcessing: false, stopOnException: false },
		routingSlip: { simple: '' },
		dynamicRouter: { simple: '' },
		loadBalance: { roundRobin: true },
		loop: { simple: '3', doWhile: false, copy: false },
		onException: { exceptions: ['java.lang.Exception'], handled: true, maximumRedeliveries: 0, redeliveryDelay: '1000', routeConfigurationId: '' },
		doTry: { doCatch: [{ exceptions: ['java.lang.Exception'] }], doFinally: undefined },
		doCatch: { exceptions: ['java.lang.Exception'] },
		doFinally: {},
		delay: { constant: '1000', asyncDelayed: false },
		throttle: { constant: '10', timePeriodMillis: '1000', asyncDelayed: false, rejectExecution: false },
		stop: {},
		process: { ref: '' },
		bean: { ref: '', method: '', beanType: '' },
		circuitBreaker: { resilience4jConfiguration: { minimumNumberOfCalls: 5, failureRateThreshold: 50, waitDurationInOpenState: 60000 } },
		threads: { poolSize: 10, maxPoolSize: 20, maxQueueSize: 1000 },
		step: { id: '' },
		wireTap: { uri: '', copy: true },
		enrich: { uri: '', aggregationStrategy: '' },
		pollEnrich: { uri: '', timeout: 10000, aggregationStrategy: '' },
		script: { groovy: '' },
		validate: { simple: '' },
		saga: {},
		merge: {},
	};
	return defaults[type] ? { ...defaults[type] } : {};
}

/**
 * Get the Y offset for horizontal connection points (left/right ports) for a given node type.
 * This is used for snapping nodes to align connection lines horizontally.
 */
function getHorizontalConnectionYOffset(type: string): number {
	switch (type) {
		case 'from':
		case 'to':
		case 'toD':
		case 'onException':
			return FROM_NODE_STYLE.ports.left.y;
		case 'choice':
			return CHOICE_NODE_STYLE.ports.left.y;
		case 'merge':
			return MERGE_NODE_STYLE.ports.left.y;
		default:
			return NODE_STYLE.ports.left.y;
	}
}

/**
 * Get node dimensions for a type
 */
function getNodeDimensions(type: string): { width: number; height: number } {
	switch (type) {
		case 'from':
		case 'to':
		case 'toD':
			return { width: FROM_WIDTH, height: FROM_HEIGHT };
		case 'onException':
			return { width: NODE_WIDTH, height: FROM_HEIGHT };
		case 'choice':
			return { width: CHOICE_WIDTH, height: CHOICE_HEIGHT };
		case 'merge':
			return { width: MERGE_WIDTH, height: MERGE_HEIGHT };
		default:
			return { width: NODE_WIDTH, height: NODE_HEIGHT };
	}
}

/**
 * Get node style for a type
 */
function getNodeStyleForType(type: string): typeof NODE_STYLE {
	switch (type) {
		case 'from':
		case 'to':
		case 'toD':
		case 'onException':
			return FROM_NODE_STYLE as typeof NODE_STYLE;
		case 'choice':
			return CHOICE_NODE_STYLE as typeof NODE_STYLE;
		case 'merge':
			return MERGE_NODE_STYLE as typeof NODE_STYLE;
		default:
			return NODE_STYLE;
	}
}

// =============================================================================
// XML Serialization
// =============================================================================

/**
 * Convert store to Camel XML DSL string
 */
function storeToXml(store: CamelModelStore): string {
	const lines: string[] = [];
	const processors = store.getAllProcessors();

	lines.push('<?xml version="1.0" encoding="UTF-8"?>');
	// camel-app DSL root: unlike <routes> (RoutesDefinition), <camel> (BeansDefinition)
	// can contain both <route> and <routeConfiguration>, so route configurations survive.
	lines.push('<camel>');

	// 1. Group onException nodes by routeConfigurationId
	const onExceptionNodes = processors.filter(p => p.type === 'onException');
	const grouped = new Map<string, CamelProcessorSemantic[]>();
	for (const ex of onExceptionNodes) {
		const configId = ex.properties.routeConfigurationId || '';
		if (!grouped.has(configId)) grouped.set(configId, []);
		grouped.get(configId)!.push(ex);
	}

	// 1a. Output routeConfiguration blocks (nodes with a configId)
	grouped.forEach((exNodes, configId) => {
		if (!configId) return;
		lines.push(`  <routeConfiguration id="${escapeXml(configId)}">`);
		for (const exNode of exNodes) {
			onExceptionToXmlLines(lines, exNode, store, 4);
		}
		lines.push('  </routeConfiguration>');
	});

	// 1b. Output standalone onException nodes (no configId)
	const standaloneExNodes = grouped.get('') || [];
	for (const exNode of standaloneExNodes) {
		onExceptionToXmlLines(lines, exNode, store, 2);
	}

	// 2. Then output routes (from nodes)
	const fromNodes = processors.filter(p => p.type === 'from');

	for (const fromNode of fromNodes) {
		const routeId = fromNode.properties.routeId || `route-${fromNode.id.substring(0, 8)}`;
		const routeAttrs = [`id="${escapeXml(routeId)}"`];
		if (fromNode.properties.routeConfigurationId) {
			routeAttrs.push(`routeConfigurationId="${escapeXml(fromNode.properties.routeConfigurationId)}"`);
		}
		lines.push(`  <route ${routeAttrs.join(' ')}>`);

		// From
		const fromUri = fromNode.properties.uri || 'direct:start';
		const params = fromNode.properties.parameters;
		if (params && Object.keys(params).length > 0) {
			// Build URI with query parameters
			const queryParts = Object.entries(params).map(([k, v]) => `${k}=${v}`);
			lines.push(`    <from uri="${escapeXml(fromUri + '?' + queryParts.join('&amp;'))}"/>`);
		} else {
			lines.push(`    <from uri="${escapeXml(fromUri)}"/>`);
		}

		// Find connected steps and output as child elements
		const connectedSteps = getConnectedSteps(store, fromNode.id);
		const visitedIds = new Set<string>();
		for (const step of connectedSteps) {
			const stepXml = processorToXml(step, 4, store, visitedIds);
			lines.push(...stepXml);
		}

		lines.push('  </route>');
	}

	lines.push('</camel>');
	return lines.join('\n');
}

/**
 * Output onException XML lines.
 */
function onExceptionToXmlLines(
	lines: string[],
	exNode: CamelProcessorSemantic,
	store: CamelModelStore,
	baseIndent: number
): void {
	const pad = ' '.repeat(baseIndent);
	lines.push(`${pad}<onException>`);

	const exList = exNode.properties.exceptions || ['java.lang.Exception'];
	for (const ex of exList) {
		lines.push(`${pad}  <exception>${escapeXml(ex)}</exception>`);
	}

	// Output handled property
	if (exNode.properties.handled !== undefined) {
		lines.push(`${pad}  <handled>`);
		lines.push(`${pad}    <constant>${exNode.properties.handled}</constant>`);
		lines.push(`${pad}  </handled>`);
	}

	// Output redelivery policy
	const maxRedel = exNode.properties.maximumRedeliveries;
	const redelDelay = exNode.properties.redeliveryDelay;
	const hasRedelivery = (maxRedel !== undefined && maxRedel > 0) || (redelDelay !== undefined && redelDelay !== '0');

	if (hasRedelivery) {
		const attrs: string[] = [];
		if (maxRedel !== undefined && maxRedel > 0) {
			attrs.push(`maximumRedeliveries="${maxRedel}"`);
		}
		if (redelDelay !== undefined && redelDelay !== '0') {
			attrs.push(`redeliveryDelay="${redelDelay}"`);
		}
		lines.push(`${pad}  <redeliveryPolicy ${attrs.join(' ')}/>`);
	}

	// Get connected steps for exception handling flow
	const connectedSteps = getConnectedSteps(store, exNode.id);
	const visitedIds = new Set<string>();
	for (const step of connectedSteps) {
		const stepXml = processorToXml(step, baseIndent + 2, store, visitedIds);
		lines.push(...stepXml);
	}

	lines.push(`${pad}</onException>`);
}

/**
 * Find the merge node that follows a branching node (Choice, Split, DoTry)
 * by tracing all branches and finding a common merge point
 */
function findMergeNodeForBranching(store: CamelModelStore, branchingId: string): string | null {
	const flows = store.getAllFlows();
	// Get all outgoing flows from the branching node
	const outgoing = flows.filter(f => f.sourceRef === branchingId);

	if (outgoing.length === 0) return null;

	// Trace each branch to find merge nodes
	for (const flow of outgoing) {
		let curr = flow.targetRef;
		const visited = new Set<string>();

		while (!visited.has(curr)) {
			visited.add(curr);
			const node = store.getProcessor(curr);
			if (!node) break;

			// Found a merge node
			if (node.type === 'merge') {
				return node.id;
			}

			// Stop at other branching nodes (nested)
			if (['choice', 'split', 'doTry', 'filter'].includes(node.type)) {
				// Try to find merge after this nested branching
				const nestedMerge = findMergeNodeForBranching(store, curr);
				if (nestedMerge) {
					curr = nestedMerge;
					continue;
				}
				break;
			}

			// Follow to next node
			const nextFlow = flows.find(f => f.sourceRef === curr);
			if (!nextFlow) break;
			curr = nextFlow.targetRef;
		}
	}
	return null;
}

/**
 * Get steps connected to a source processor (following flows)
 * Handles merge nodes for branching constructs (Choice, Split, DoTry)
 * @param store The model store
 * @param sourceId Starting processor ID
 * @param stopAtMerge If true, stop traversal when hitting a Merge node (used within branches)
 */
function getConnectedSteps(store: CamelModelStore, sourceId: string, stopAtMerge: boolean = false): CamelProcessorSemantic[] {
	const result: CamelProcessorSemantic[] = [];
	const flows = store.getAllFlows();
	const visited = new Set<string>();
	visited.add(sourceId);

	let currentId = sourceId;
	while (true) {
		// Get current processor to check if it's a branching node
		const currentProc = store.getProcessor(currentId);

		// Stop traversal at branching nodes (Choice, Split, DoTry, Multicast) - their content is handled recursively
		// But only if we're not at the starting node (to allow recursive calls to continue)
		if (currentProc && ['choice', 'split', 'doTry', 'multicast', 'filter'].includes(currentProc.type)) {
			if (currentId !== sourceId) {
				// Add the branching node to result
				result.push(currentProc);

				// Look for a merge node that follows this branching node
				const mergeNodeId = findMergeNodeForBranching(store, currentId);
				if (mergeNodeId) {
					// If stopAtMerge is true, don't continue past the merge
					if (stopAtMerge) {
						break;
					}
					// Skip to the merge node and continue from there
					visited.add(mergeNodeId);
					currentId = mergeNodeId;
					// Don't add merge node to result (it's visual-only)
					continue;
				}
				// No merge found, stop here
				break;
			}
		}

		// If we're at a merge node (from the starting point being a merge), skip it
		if (currentProc && currentProc.type === 'merge' && currentId === sourceId) {
			if (stopAtMerge) {
				break;
			}
			const outgoing = flows.find(f => f.sourceRef === currentId && !visited.has(f.targetRef));
			if (outgoing) {
				visited.add(outgoing.targetRef);
				currentId = outgoing.targetRef;
				continue;
			}
			break;
		}

		// Find next node in the chain
		const outgoing = flows.find(f => f.sourceRef === currentId && !visited.has(f.targetRef));
		if (!outgoing) break;

		visited.add(outgoing.targetRef);
		const target = store.getProcessor(outgoing.targetRef);

		if (target) {
			// If we hit a merge node during normal traversal
			if (target.type === 'merge') {
				// If stopAtMerge is true, stop here (within branch context)
				if (stopAtMerge) {
					break;
				}
				// Otherwise skip it (don't add to result) but continue traversal from its output
				currentId = outgoing.targetRef;
				continue;
			}

			result.push(target);

			// If we just added a branching node, look for its merge point
			if (['choice', 'split', 'doTry', 'multicast', 'filter'].includes(target.type)) {
				// If stopAtMerge is true, stop after adding the branching node
				// (its internal steps will be handled by processorToXml recursively)
				if (stopAtMerge) {
					break;
				}
				const mergeNodeId = findMergeNodeForBranching(store, target.id);
				if (mergeNodeId) {
					visited.add(mergeNodeId);
					currentId = mergeNodeId;
					continue;
				}
				break;
			}

			currentId = outgoing.targetRef;
		} else {
			break;
		}
	}

	return result;
}

/**
 * Helper to gather branch steps safely, handling nested container nodes
 * If the start node is itself a container (Choice/Split), treat it as a single block
 * and then jump to its merge node to find subsequent steps in this branch.
 */
function getBranchSteps(store: CamelModelStore, startNodeRef: string): CamelProcessorSemantic[] {
	const targetProc = store.getProcessor(startNodeRef);
	if (!targetProc) return [];

	// If the start node is ITSELF a container (Choice/Split/Multicast), we must treat it as a single block
	// and then jump to its merge node to find subsequent steps in this branch.
	if (['choice', 'split', 'doTry', 'multicast', 'filter'].includes(targetProc.type)) {
		const mergeId = findMergeNodeForBranching(store, targetProc.id);
		const subsequentSteps = mergeId ? getConnectedSteps(store, mergeId, true) : [];
		return [targetProc, ...subsequentSteps];
	} else {
		// Regular node start
		return [targetProc, ...getConnectedSteps(store, startNodeRef, true)];
	}
}

/**
 * Convert a processor to XML DSL lines
 * @param proc The processor to convert
 * @param indent Indentation level (in spaces)
 * @param store The model store (needed for Choice branches)
 * @param visitedIds Set of already visited processor IDs (to prevent infinite loops)
 */
function processorToXml(
	proc: CamelProcessorSemantic,
	indent: number,
	store: CamelModelStore,
	visitedIds: Set<string> = new Set()
): string[] {
	const lines: string[] = [];
	const pad = ' '.repeat(indent);
	const props = proc.properties;

	// Prevent infinite loops
	if (visitedIds.has(proc.id)) return lines;
	visitedIds.add(proc.id);

	// User-authored Camel `id` attribute (round-tripped from XML / set via UI).
	// Empty string is treated as "no id" so the attribute is omitted.
	const idAttr = props.id ? ` id="${escapeXml(String(props.id))}"` : '';

	switch (proc.type) {
		case 'to':
		case 'toD': {
			const tagName = proc.type;
			const uri = props.uri || '';
			const params = props.parameters;
			if (params && Object.keys(params).length > 0) {
				const queryParts = Object.entries(params).map(([k, v]) => `${k}=${v}`);
				lines.push(`${pad}<${tagName}${idAttr} uri="${escapeXml(uri + '?' + queryParts.join('&amp;'))}"/>`);
			} else {
				lines.push(`${pad}<${tagName}${idAttr} uri="${escapeXml(uri)}"/>`);
			}
			break;
		}

		case 'log': {
			const attrs = [`message="${escapeXml(props.message || '')}"`];
			if (props.loggingLevel && props.loggingLevel !== 'INFO') {
				attrs.push(`loggingLevel="${escapeXml(props.loggingLevel)}"`);
			}
			if (props.loggerName) {
				attrs.push(`loggerName="${escapeXml(props.loggerName)}"`);
			}
			lines.push(`${pad}<log${idAttr} ${attrs.join(' ')}/>`);
			break;
		}

		case 'setBody':
			lines.push(`${pad}<setBody${idAttr}>`);
			if (props.simple) {
				lines.push(`${pad}  <simple>${escapeXml(props.simple)}</simple>`);
			} else if (props.constant) {
				lines.push(`${pad}  <constant>${escapeXml(props.constant)}</constant>`);
			}
			lines.push(`${pad}</setBody>`);
			break;

		case 'setHeader': {
			lines.push(`${pad}<setHeader${idAttr} name="${escapeXml(props.name || '')}">`);
			const exprType = props.expressionType || 'simple';
			if (props.expression) {
				lines.push(`${pad}  <${exprType}>${escapeXml(props.expression)}</${exprType}>`);
			}
			lines.push(`${pad}</setHeader>`);
			break;
		}

		case 'choice': {
			lines.push(`${pad}<choice${idAttr}>`);
			const flows = store.getAllFlows().filter(f => f.sourceRef === proc.id);
			const whenFlows = flows.filter(f => f.conditionType === 'when');
			const otherwiseFlow = flows.find(f => f.conditionType === 'otherwise');

			for (const flow of whenFlows) {
				const exprType = flow.language || 'simple';
				lines.push(`${pad}  <when>`);
				lines.push(`${pad}    <${exprType}>${escapeXml(flow.expression || '')}</${exprType}>`);
				const branchSteps = getBranchSteps(store, flow.targetRef);
				for (const step of branchSteps) {
					const stepLines = processorToXml(step, indent + 4, store, new Set(visitedIds));
					lines.push(...stepLines);
				}
				lines.push(`${pad}  </when>`);
			}

			if (otherwiseFlow) {
				lines.push(`${pad}  <otherwise>`);
				const branchSteps = getBranchSteps(store, otherwiseFlow.targetRef);
				for (const step of branchSteps) {
					const stepLines = processorToXml(step, indent + 4, store, new Set(visitedIds));
					lines.push(...stepLines);
				}
				lines.push(`${pad}  </otherwise>`);
			}

			lines.push(`${pad}</choice>`);
			break;
		}

		case 'filter': {
			const filterExprType = props.expressionType || 'simple';
			lines.push(`${pad}<filter${idAttr}>`);
			lines.push(`${pad}  <${filterExprType}>${escapeXml(props.expression || '')}</${filterExprType}>`);
			// <filter> is a block: the steps reached from its output port (up to the
			// merge node that closes the block) run only when the predicate matches.
			const filterFlows = store.getAllFlows().filter(f => f.sourceRef === proc.id);
			for (const flow of filterFlows) {
				const bodySteps = getBranchSteps(store, flow.targetRef);
				for (const step of bodySteps) {
					const stepLines = processorToXml(step, indent + 2, store, new Set(visitedIds));
					lines.push(...stepLines);
				}
			}
			lines.push(`${pad}</filter>`);
			break;
		}

		case 'split': {
			const splitAttrs: string[] = [];
			if (props.streaming) splitAttrs.push('streaming="true"');
			if (props.parallelProcessing) splitAttrs.push('parallelProcessing="true"');
			if (props.stopOnException) splitAttrs.push('stopOnException="true"');
			if (props.shareUnitOfWork) splitAttrs.push('shareUnitOfWork="true"');
			if (props.aggregationStrategy) splitAttrs.push(`aggregationStrategy="${escapeXml(props.aggregationStrategy)}"`);

			const attrStr = splitAttrs.length > 0 ? ' ' + splitAttrs.join(' ') : '';
			lines.push(`${pad}<split${idAttr}${attrStr}>`);
			const splitExprType = props.expressionType || 'simple';
			lines.push(`${pad}  <${splitExprType}>${escapeXml(props.expression || '')}</${splitExprType}>`);

			const splitFlows = store.getAllFlows().filter(f => f.sourceRef === proc.id);
			for (const flow of splitFlows) {
				const innerSteps = getBranchSteps(store, flow.targetRef);
				for (const step of innerSteps) {
					const stepLines = processorToXml(step, indent + 2, store, new Set(visitedIds));
					lines.push(...stepLines);
				}
			}
			lines.push(`${pad}</split>`);
			break;
		}

		case 'delay':
			lines.push(`${pad}<delay${idAttr}>`);
			lines.push(`${pad}  <constant>${escapeXml(props.constant || '1000')}</constant>`);
			if (props.asyncDelayed) lines.push(`${pad}  <asyncDelayed>true</asyncDelayed>`);
			lines.push(`${pad}</delay>`);
			break;

		case 'throttle': {
			const throttleAttrs: string[] = [];
			if (props.timePeriodMillis) throttleAttrs.push(`timePeriodMillis="${props.timePeriodMillis}"`);
			if (props.asyncDelayed) throttleAttrs.push('asyncDelayed="true"');
			if (props.rejectExecution) throttleAttrs.push('rejectExecution="true"');
			const tAttrStr = throttleAttrs.length > 0 ? ' ' + throttleAttrs.join(' ') : '';
			lines.push(`${pad}<throttle${idAttr}${tAttrStr}>`);
			lines.push(`${pad}  <constant>${escapeXml(props.constant || '10')}</constant>`);
			lines.push(`${pad}</throttle>`);
			break;
		}

		case 'bean': {
			const beanAttrs: string[] = [];
			if (props.ref) beanAttrs.push(`ref="${escapeXml(props.ref)}"`);
			if (props.method) beanAttrs.push(`method="${escapeXml(props.method)}"`);
			if (props.beanType) beanAttrs.push(`beanType="${escapeXml(props.beanType)}"`);
			lines.push(`${pad}<bean${idAttr} ${beanAttrs.join(' ')}/>`);
			break;
		}

		case 'aggregate': {
			const aggAttrs: string[] = [];
			if (props.aggregationStrategy) aggAttrs.push(`aggregationStrategy="${escapeXml(props.aggregationStrategy)}"`);
			if (props.completionSize) aggAttrs.push(`completionSize="${props.completionSize}"`);
			if (props.completionTimeout) aggAttrs.push(`completionTimeout="${props.completionTimeout}"`);
			if (props.eagerCheckCompletion) aggAttrs.push('eagerCheckCompletion="true"');
			if (props.completionFromBatchConsumer) aggAttrs.push('completionFromBatchConsumer="true"');
			const aAttrStr = aggAttrs.length > 0 ? ' ' + aggAttrs.join(' ') : '';
			lines.push(`${pad}<aggregate${idAttr}${aAttrStr}>`);
			lines.push(`${pad}  <correlationExpression>`);
			lines.push(`${pad}    <simple>${escapeXml(props.correlationExpression || '')}</simple>`);
			lines.push(`${pad}  </correlationExpression>`);
			lines.push(`${pad}</aggregate>`);
			break;
		}

		case 'marshal':
			lines.push(`${pad}<marshal${idAttr}>`);
			if (props.dataFormat === 'json') {
				const jsonAttrs: string[] = [];
				if (props.library) jsonAttrs.push(`library="${props.library}"`);
				if (props.prettyPrint) jsonAttrs.push('prettyPrint="true"');
				if (props.unmarshalType) jsonAttrs.push(`unmarshalType="${escapeXml(props.unmarshalType)}"`);
				lines.push(`${pad}  <json ${jsonAttrs.join(' ')}/>`);
			} else {
				lines.push(`${pad}  <${props.dataFormat || 'json'}/>`);
			}
			lines.push(`${pad}</marshal>`);
			break;

		case 'unmarshal':
			lines.push(`${pad}<unmarshal${idAttr}>`);
			if (props.dataFormat === 'json') {
				const jsonAttrs: string[] = [];
				if (props.library) jsonAttrs.push(`library="${props.library}"`);
				if (props.unmarshalType) jsonAttrs.push(`unmarshalType="${escapeXml(props.unmarshalType)}"`);
				lines.push(`${pad}  <json ${jsonAttrs.join(' ')}/>`);
			} else {
				lines.push(`${pad}  <${props.dataFormat || 'json'}/>`);
			}
			lines.push(`${pad}</unmarshal>`);
			break;

		case 'transform': {
			const transformExprType = props.expressionType || 'simple';
			lines.push(`${pad}<transform${idAttr}>`);
			lines.push(`${pad}  <${transformExprType}>${escapeXml(props.expression || '')}</${transformExprType}>`);
			lines.push(`${pad}</transform>`);
			break;
		}

		case 'multicast': {
			const mcAttrs: string[] = [];
			if (props.parallelProcessing) mcAttrs.push('parallelProcessing="true"');
			if (props.stopOnException) mcAttrs.push('stopOnException="true"');
			if (props.aggregationStrategy) mcAttrs.push(`aggregationStrategy="${escapeXml(props.aggregationStrategy)}"`);
			const mcAttrStr = mcAttrs.length > 0 ? ' ' + mcAttrs.join(' ') : '';
			lines.push(`${pad}<multicast${idAttr}${mcAttrStr}>`);

			const multicastFlows = store.getAllFlows().filter(f => f.sourceRef === proc.id);
			for (const flow of multicastFlows) {
				const branchSteps = getBranchSteps(store, flow.targetRef);
				for (const step of branchSteps) {
					const stepLines = processorToXml(step, indent + 2, store, new Set(visitedIds));
					lines.push(...stepLines);
				}
			}
			lines.push(`${pad}</multicast>`);
			break;
		}

		case 'recipientList': {
			const rlAttrs: string[] = [];
			if (props.parallelProcessing) rlAttrs.push('parallelProcessing="true"');
			if (props.stopOnException) rlAttrs.push('stopOnException="true"');
			if (props.aggregationStrategy) rlAttrs.push(`aggregationStrategy="${escapeXml(props.aggregationStrategy)}"`);
			const rlAttrStr = rlAttrs.length > 0 ? ' ' + rlAttrs.join(' ') : '';
			lines.push(`${pad}<recipientList${idAttr}${rlAttrStr}>`);
			lines.push(`${pad}  <simple>${escapeXml(props.simple || '')}</simple>`);
			lines.push(`${pad}</recipientList>`);
			break;
		}

		case 'loop': {
			const loopAttrs: string[] = [];
			if (props.doWhile) loopAttrs.push('doWhile="true"');
			if (props.copy) loopAttrs.push('copy="true"');
			const loopAttrStr = loopAttrs.length > 0 ? ' ' + loopAttrs.join(' ') : '';
			lines.push(`${pad}<loop${idAttr}${loopAttrStr}>`);
			lines.push(`${pad}  <simple>${escapeXml(props.simple || '3')}</simple>`);
			lines.push(`${pad}</loop>`);
			break;
		}

		case 'wireTap': {
			const wtAttrs = [`uri="${escapeXml(props.uri || '')}"`];
			if (props.copy === false) wtAttrs.push('copy="false"');
			lines.push(`${pad}<wireTap${idAttr} ${wtAttrs.join(' ')}/>`);
			break;
		}

		case 'enrich': {
			const enrichAttrs: string[] = [];
			if (props.aggregationStrategy) enrichAttrs.push(`aggregationStrategy="${escapeXml(props.aggregationStrategy)}"`);
			const eAttrStr = enrichAttrs.length > 0 ? ' ' + enrichAttrs.join(' ') : '';
			lines.push(`${pad}<enrich${idAttr}${eAttrStr}>`);
			lines.push(`${pad}  <constant>${escapeXml(props.uri || '')}</constant>`);
			lines.push(`${pad}</enrich>`);
			break;
		}

		case 'pollEnrich': {
			const peAttrs: string[] = [];
			if (props.timeout) peAttrs.push(`timeout="${props.timeout}"`);
			if (props.aggregationStrategy) peAttrs.push(`aggregationStrategy="${escapeXml(props.aggregationStrategy)}"`);
			const peAttrStr = peAttrs.length > 0 ? ' ' + peAttrs.join(' ') : '';
			lines.push(`${pad}<pollEnrich${idAttr}${peAttrStr}>`);
			lines.push(`${pad}  <constant>${escapeXml(props.uri || '')}</constant>`);
			lines.push(`${pad}</pollEnrich>`);
			break;
		}

		case 'threads': {
			const thAttrs: string[] = [];
			if (props.poolSize) thAttrs.push(`poolSize="${props.poolSize}"`);
			if (props.maxPoolSize) thAttrs.push(`maxPoolSize="${props.maxPoolSize}"`);
			if (props.maxQueueSize) thAttrs.push(`maxQueueSize="${props.maxQueueSize}"`);
			lines.push(`${pad}<threads${idAttr} ${thAttrs.join(' ')}/>`);
			break;
		}

		case 'circuitBreaker':
			lines.push(`${pad}<circuitBreaker${idAttr}>`);
			if (props.resilience4jConfiguration) {
				const r4jAttrs: string[] = [];
				if (props.resilience4jConfiguration.minimumNumberOfCalls) {
					r4jAttrs.push(`minimumNumberOfCalls="${props.resilience4jConfiguration.minimumNumberOfCalls}"`);
				}
				if (props.resilience4jConfiguration.failureRateThreshold) {
					r4jAttrs.push(`failureRateThreshold="${props.resilience4jConfiguration.failureRateThreshold}"`);
				}
				if (props.resilience4jConfiguration.waitDurationInOpenState) {
					r4jAttrs.push(`waitDurationInOpenState="${props.resilience4jConfiguration.waitDurationInOpenState}"`);
				}
				lines.push(`${pad}  <resilience4jConfiguration ${r4jAttrs.join(' ')}/>`);
			}
			lines.push(`${pad}</circuitBreaker>`);
			break;

		case 'stop':
			lines.push(`${pad}<stop${idAttr}/>`);
			break;

		case 'doTry': {
			lines.push(`${pad}<doTry${idAttr}>`);
			const doTryFlows = store.getAllFlows().filter(f => f.sourceRef === proc.id);

			// 1. try steps - flows with role='try' or no role set
			const tryFlows = doTryFlows.filter(f => f.role === 'try' || (!f.role && !f.conditionType));
			for (const flow of tryFlows) {
				const branchSteps = getBranchSteps(store, flow.targetRef);
				for (const step of branchSteps) {
					const stepLines = processorToXml(step, indent + 2, store, new Set(visitedIds));
					lines.push(...stepLines);
				}
			}

			// 2. doCatch blocks
			const catchFlows = doTryFlows.filter(f => f.role === 'catch');
			for (const flow of catchFlows) {
				lines.push(`${pad}  <doCatch>`);
				const exceptionList = flow.exceptions || ['java.lang.Exception'];
				for (const ex of exceptionList) {
					lines.push(`${pad}    <exception>${escapeXml(ex)}</exception>`);
				}
				const branchSteps = getBranchSteps(store, flow.targetRef);
				for (const step of branchSteps) {
					const stepLines = processorToXml(step, indent + 4, store, new Set(visitedIds));
					lines.push(...stepLines);
				}
				lines.push(`${pad}  </doCatch>`);
			}

			// 3. doFinally
			const finallyFlow = doTryFlows.find(f => f.role === 'finally');
			if (finallyFlow) {
				lines.push(`${pad}  <doFinally>`);
				const branchSteps = getBranchSteps(store, finallyFlow.targetRef);
				for (const step of branchSteps) {
					const stepLines = processorToXml(step, indent + 4, store, new Set(visitedIds));
					lines.push(...stepLines);
				}
				lines.push(`${pad}  </doFinally>`);
			}

			lines.push(`${pad}</doTry>`);
			break;
		}

		case 'merge':
			// Merge is a visual-only node, not output to XML
			return [];

		default: {
			// Generic element output
			const defaultAttrs: string[] = [];
			for (const [key, value] of Object.entries(props)) {
				if (typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number') {
					defaultAttrs.push(`${key}="${escapeXml(String(value))}"`);
				}
			}
			if (defaultAttrs.length > 0) {
				lines.push(`${pad}<${proc.type} ${defaultAttrs.join(' ')}/>`);
			} else {
				lines.push(`${pad}<${proc.type}/>`);
			}
		}
	}

	return lines;
}

/**
 * Get text content of a direct child element by tag name
 */
function getChildText(parent: Element, tagName: string): string | null {
	const child = Array.from(parent.children).find(c => c.localName === tagName);
	return child ? child.textContent?.trim() || null : null;
}

/**
 * Get all direct child elements (excluding text nodes)
 */
function getChildElements(parent: Element): Element[] {
	return Array.from(parent.children);
}

/**
 * Get direct child elements by tag name
 */
function getChildrenByTag(parent: Element, tagName: string): Element[] {
	return Array.from(parent.children).filter(c => c.localName === tagName);
}

/**
 * Parse XML string to CamelModelStore
 */
function parseXmlToStore(xmlString: string): CamelModelStore {
	const store = new CamelModelStore();

	try {
		const parser = new DOMParser();
		const doc = parser.parseFromString(xmlString, 'application/xml');

		// Check for parse errors
		const parseError = doc.querySelector('parsererror');
		if (parseError) {
			console.error('XML parse error:', parseError.textContent);
			return store;
		}

		const root = doc.documentElement;

		let currentY = 100;

		// Parse routeConfiguration elements
		for (const rcEl of getChildrenByTag(root, 'routeConfiguration')) {
			parseRouteConfigurationElementToStore(store, rcEl, currentY);
			currentY += 250;
		}

		// Parse standalone onException elements
		for (const oeEl of getChildrenByTag(root, 'onException')) {
			parseOnExceptionElementToStore(store, oeEl, currentY);
			currentY += 250;
		}

		// Parse route elements
		for (const routeEl of getChildrenByTag(root, 'route')) {
			parseRouteElementToStore(store, routeEl, currentY);
			currentY += 300;
		}
	} catch (e) {
		console.error('Failed to parse XML:', e);
	}

	return store;
}

/**
 * Parse a routeConfiguration element into the store
 */
function parseRouteConfigurationElementToStore(store: CamelModelStore, configEl: Element, baseY: number) {
	const configId = configEl.getAttribute('id') || '';

	let currentY = baseY;
	for (const oeEl of getChildrenByTag(configEl, 'onException')) {
		parseOnExceptionElementToStore(store, oeEl, currentY, configId);
		currentY += 250;
	}
}

/**
 * Parse an onException XML element into the store
 */
function parseOnExceptionElementToStore(store: CamelModelStore, oeEl: Element, baseY: number, routeConfigurationId?: string) {
	const stepId = generateUUID();
	const x = 150;
	const y = baseY;

	// Parse exception classes
	const exceptionEls = getChildrenByTag(oeEl, 'exception');
	const exceptions = exceptionEls.length > 0
		? exceptionEls.map(el => el.textContent?.trim() || 'java.lang.Exception')
		: ['java.lang.Exception'];

	// Parse handled
	const handledEl = Array.from(oeEl.children).find(c => c.localName === 'handled');
	const handledConstant = handledEl ? getChildText(handledEl, 'constant') : null;
	const handled = handledConstant === 'true';

	// Parse redeliveryPolicy
	const rpEl = Array.from(oeEl.children).find(c => c.localName === 'redeliveryPolicy');
	const maxRedeliveries = rpEl ? parseInt(rpEl.getAttribute('maximumRedeliveries') || '0', 10) : 0;
	const redeliveryDelay = rpEl ? rpEl.getAttribute('redeliveryDelay') || '1000' : '1000';

	const semantic: CamelProcessorSemantic = {
		id: stepId,
		type: 'onException',
		properties: {
			exceptions,
			handled,
			maximumRedeliveries: maxRedeliveries,
			redeliveryDelay: String(redeliveryDelay),
			routeConfigurationId: routeConfigurationId || ''
		}
	};
	store.addProcessor(semantic);

	const dims = getNodeDimensions('onException');
	const shape: CamelDiShape = {
		id: stepId + '_di',
		semanticId: stepId,
		bounds: { x, y, width: dims.width, height: dims.height }
	};
	store.addShape(shape);

	// Parse step child elements (skip exception, handled, redeliveryPolicy)
	const skipTags = new Set(['exception', 'handled', 'redeliveryPolicy']);
	const stepElements = getChildElements(oeEl).filter(c => !skipTags.has(c.localName));
	if (stepElements.length > 0) {
		let currentX = x + dims.width + STEP_GAP_X;
		let previousId = stepId;

		for (const stepEl of stepElements) {
			const result = parseStepElementToStore(store, stepEl, currentX, y, previousId);
			if (result) {
				previousId = result.endId;
				currentX = result.nextX;
			}
		}
	}
}

/**
 * Parse a route XML element into the store
 */
function parseRouteElementToStore(store: CamelModelStore, routeEl: Element, baseY: number) {
	let currentX = 150;
	let currentY = baseY;
	let previousId: string | null = null;

	const routeId = routeEl.getAttribute('id') || '';
	const routeConfigurationId = routeEl.getAttribute('routeConfigurationId') || '';

	// Parse from element
	const fromEl = Array.from(routeEl.children).find(c => c.localName === 'from');
	if (fromEl) {
		const fromId = generateUUID();
		const rawUri = fromEl.getAttribute('uri') || '';

		// Split URI and query parameters
		const qIdx = rawUri.indexOf('?');
		const fromUri = qIdx >= 0 ? rawUri.substring(0, qIdx) : rawUri;
		const parameters: Record<string, string> = {};
		if (qIdx >= 0) {
			const queryStr = rawUri.substring(qIdx + 1);
			for (const pair of queryStr.split('&')) {
				const eqIdx = pair.indexOf('=');
				if (eqIdx >= 0) {
					parameters[pair.substring(0, eqIdx)] = pair.substring(eqIdx + 1);
				}
			}
		}

		const fromSemantic: CamelProcessorSemantic = {
			id: fromId,
			type: 'from',
			properties: {
				uri: fromUri,
				parameters,
				routeId,
				routeConfigurationId
			}
		};
		store.addProcessor(fromSemantic);

		const fromShape: CamelDiShape = {
			id: fromId + '_di',
			semanticId: fromId,
			bounds: { x: currentX, y: currentY, width: FROM_WIDTH, height: FROM_HEIGHT }
		};
		store.addShape(fromShape);

		previousId = fromId;
		currentX += FROM_WIDTH + STEP_GAP_X;
	}

	// Parse step elements (all children after from)
	const stepElements = getChildElements(routeEl).filter(c => c.localName !== 'from');
	for (const stepEl of stepElements) {
		const result = parseStepElementToStore(store, stepEl, currentX, currentY, previousId);
		if (result) {
			previousId = result.endId;
			currentX = result.nextX;
		}
	}
}

/**
 * Parse a step XML element into the store
 * Returns the created node's ID details and layout info
 *
 * @param store The model store
 * @param stepEl The step XML element
 * @param x Current X position
 * @param y Current Y position
 * @param previousId Previous node ID for connection
 * @param flowProperties Optional properties for the connecting flow (for when/otherwise/doTry)
 * @returns id: the created node's ID, endId: the end point (node itself or its merge node), nextX, maxY
 */
function parseStepElementToStore(
	store: CamelModelStore,
	stepEl: Element,
	x: number,
	y: number,
	previousId: string | null,
	flowProperties?: {
		conditionType?: 'when' | 'otherwise';
		expression?: string;
		language?: string;
		role?: 'try' | 'catch' | 'finally';
		exceptions?: string[];
	}
): { id: string; endId: string; nextX: number; maxY: number } | null {
	const stepType = stepEl.localName as EipType;
	const stepId = generateUUID();

	// User-authored Camel `id` attribute on the step (e.g., <to id="GetMetafields" ...>).
	// Kept separate from the internal UUID `stepId` so flow/shape references stay stable
	// even when the user edits this value.
	const userIdAttr = stepEl.getAttribute('id') || undefined;

	// Create semantic (for Choice, don't include when/otherwise in properties - they're represented as flows)
	const baseProps = stepType === 'choice' ? {} : parseStepElementProperties(stepType, stepEl);
	const semantic: CamelProcessorSemantic = {
		id: stepId,
		type: stepType,
		properties: userIdAttr ? { ...baseProps, id: userIdAttr } : baseProps
	};
	store.addProcessor(semantic);

	// Create shape
	const dims = getNodeDimensions(stepType);
	const shape: CamelDiShape = {
		id: stepId + '_di',
		semanticId: stepId,
		bounds: { x, y, width: dims.width, height: dims.height }
	};
	store.addShape(shape);

	// Create flow from previous
	if (previousId) {
		const flowId = generateUUID();
		const flow: CamelFlowSemantic = {
			id: flowId,
			sourceRef: previousId,
			targetRef: stepId,
			...(flowProperties?.conditionType && { conditionType: flowProperties.conditionType }),
			...(flowProperties?.expression && { expression: flowProperties.expression }),
			...(flowProperties?.language && { language: flowProperties.language }),
			...(flowProperties?.role && { role: flowProperties.role }),
			...(flowProperties?.exceptions && { exceptions: flowProperties.exceptions })
		};
		store.addFlow(flow);

		const edge: CamelDiEdge = {
			id: flowId + '_di',
			semanticId: flowId,
			waypoints: []
		};
		store.addEdge(edge);
	}

	let nextX = x + dims.width + STEP_GAP_X;
	let maxY = y;
	let endId = stepId;

	// Handle Choice element with when/otherwise branches
	if (stepType === 'choice') {
		let branchY = y;
		const branchStartX = nextX;
		let branchMaxY = y;
		const branchEndIds: string[] = [];

		// Parse 'when' branches
		const whenEls = getChildrenByTag(stepEl, 'when');
		for (const whenEl of whenEls) {
			// Extract expression - first child element that is an expression type
			const exprEl = Array.from(whenEl.children).find(c =>
				['simple', 'xpath', 'jsonpath', 'constant', 'tokenize'].includes(c.localName)
			);
			const exprType = exprEl?.localName || 'simple';
			const expression = exprEl?.textContent?.trim() || '';

			branchMaxY = branchY;

			// Get step elements inside when (skip expression element)
			const whenStepEls = getChildElements(whenEl).filter(c =>
				!['simple', 'xpath', 'jsonpath', 'constant', 'tokenize'].includes(c.localName)
			);
			if (whenStepEls.length > 0) {
				let branchPrevId: string | null = null;
				let branchX = branchStartX;
				let lastResultEndId: string | null = null;

				for (let i = 0; i < whenStepEls.length; i++) {
					const branchStepEl = whenStepEls[i];
					const flowProps = i === 0 ? { conditionType: 'when' as const, expression, language: exprType } : undefined;
					const connectFrom = i === 0 ? stepId : branchPrevId;

					const result = parseStepElementToStore(store, branchStepEl, branchX, branchY, connectFrom, flowProps);
					if (result) {
						branchPrevId = result.endId;
						branchX = result.nextX;
						nextX = Math.max(nextX, result.nextX);
						branchMaxY = Math.max(branchMaxY, result.maxY);
						lastResultEndId = result.endId;
					}
				}
				if (lastResultEndId) branchEndIds.push(lastResultEndId);
			}

			branchY = branchMaxY + NODE_HEIGHT + STEP_GAP_Y;
			maxY = Math.max(maxY, branchMaxY);
		}

		// Parse 'otherwise' branch
		const otherwiseEl = Array.from(stepEl.children).find(c => c.localName === 'otherwise');
		if (otherwiseEl) {
			const otherwiseStepEls = getChildElements(otherwiseEl);
			if (otherwiseStepEls.length > 0) {
				branchMaxY = branchY;
				let branchPrevId: string | null = null;
				let branchX = branchStartX;
				let lastResultEndId: string | null = null;

				for (let i = 0; i < otherwiseStepEls.length; i++) {
					const branchStepEl = otherwiseStepEls[i];
					const flowProps = i === 0 ? { conditionType: 'otherwise' as const } : undefined;
					const connectFrom = i === 0 ? stepId : branchPrevId;

					const result = parseStepElementToStore(store, branchStepEl, branchX, branchY, connectFrom, flowProps);
					if (result) {
						branchPrevId = result.endId;
						branchX = result.nextX;
						nextX = Math.max(nextX, result.nextX);
						branchMaxY = Math.max(branchMaxY, result.maxY);
						lastResultEndId = result.endId;
					}
				}
				if (lastResultEndId) branchEndIds.push(lastResultEndId);
				maxY = Math.max(maxY, branchMaxY);
			}
		}

		maxY = Math.max(maxY, branchY);

		// AUTO-MERGE
		if (branchEndIds.length > 0) {
			const mergeId = generateUUID();
			store.addProcessor({ id: mergeId, type: 'merge', properties: {} });
			store.addShape({ id: mergeId + '_di', semanticId: mergeId, bounds: { x: nextX, y, width: MERGE_WIDTH, height: MERGE_HEIGHT } });
			for (const branchEndId of branchEndIds) {
				const flowId = generateUUID();
				store.addFlow({ id: flowId, sourceRef: branchEndId, targetRef: mergeId });
				store.addEdge({ id: flowId + '_di', semanticId: flowId, waypoints: [] });
			}
			endId = mergeId;
			nextX += MERGE_WIDTH + STEP_GAP_X;
		}
	}

	// Handle Split element with internal steps
	if (stepType === 'split') {
		// Get step elements (skip expression elements)
		const exprTags = new Set(['simple', 'xpath', 'jsonpath', 'constant', 'tokenize']);
		const splitStepEls = getChildElements(stepEl).filter(c => !exprTags.has(c.localName));

		if (splitStepEls.length > 0) {
			const splitStartX = nextX;
			let splitMaxY = y;
			const splitEndIds: string[] = [];
			let splitPrevId: string | null = null;
			let splitX = splitStartX;
			let lastResultEndId: string | null = null;

			for (let i = 0; i < splitStepEls.length; i++) {
				const connectFrom = i === 0 ? stepId : splitPrevId;
				const result = parseStepElementToStore(store, splitStepEls[i], splitX, y, connectFrom);
				if (result) {
					splitPrevId = result.endId;
					splitX = result.nextX;
					nextX = Math.max(nextX, result.nextX);
					splitMaxY = Math.max(splitMaxY, result.maxY);
					lastResultEndId = result.endId;
				}
			}
			if (lastResultEndId) splitEndIds.push(lastResultEndId);
			maxY = Math.max(maxY, splitMaxY);

			if (splitEndIds.length > 0) {
				const mergeId = generateUUID();
				store.addProcessor({ id: mergeId, type: 'merge', properties: {} });
				store.addShape({ id: mergeId + '_di', semanticId: mergeId, bounds: { x: nextX, y, width: MERGE_WIDTH, height: MERGE_HEIGHT } });
				for (const splitEndId of splitEndIds) {
					const flowId = generateUUID();
					store.addFlow({ id: flowId, sourceRef: splitEndId, targetRef: mergeId });
					store.addEdge({ id: flowId + '_di', semanticId: flowId, waypoints: [] });
				}
				endId = mergeId;
				nextX += MERGE_WIDTH + STEP_GAP_X;
			}
		}
	}

	// Handle Filter element with conditional body steps.
	// In Camel <filter> is a block, not a leaf: the child steps execute only when the
	// predicate matches, then the route continues. Model it like Split (single branch +
	// auto-merge) so the body (e.g. a nested <to>) is preserved and round-trips.
	if (stepType === 'filter') {
		const exprTags = new Set(['simple', 'xpath', 'jsonpath', 'constant', 'tokenize']);
		const bodyStepEls = getChildElements(stepEl).filter(c => !exprTags.has(c.localName));

		if (bodyStepEls.length > 0) {
			let bodyMaxY = y;
			let bodyPrevId: string | null = null;
			let bodyX = nextX;
			let lastResultEndId: string | null = null;

			for (let i = 0; i < bodyStepEls.length; i++) {
				const connectFrom = i === 0 ? stepId : bodyPrevId;
				const result = parseStepElementToStore(store, bodyStepEls[i], bodyX, y, connectFrom);
				if (result) {
					bodyPrevId = result.endId;
					bodyX = result.nextX;
					nextX = Math.max(nextX, result.nextX);
					bodyMaxY = Math.max(bodyMaxY, result.maxY);
					lastResultEndId = result.endId;
				}
			}
			maxY = Math.max(maxY, bodyMaxY);

			if (lastResultEndId) {
				const mergeId = generateUUID();
				store.addProcessor({ id: mergeId, type: 'merge', properties: {} });
				store.addShape({ id: mergeId + '_di', semanticId: mergeId, bounds: { x: nextX, y, width: MERGE_WIDTH, height: MERGE_HEIGHT } });
				const flowId = generateUUID();
				store.addFlow({ id: flowId, sourceRef: lastResultEndId, targetRef: mergeId });
				store.addEdge({ id: flowId + '_di', semanticId: flowId, waypoints: [] });
				endId = mergeId;
				nextX += MERGE_WIDTH + STEP_GAP_X;
			}
		}
	}

	// Handle DoTry element with doCatch/doFinally
	if (stepType === 'doTry') {
		let branchY = y;
		const branchStartX = nextX;
		let branchMaxY = y;
		const branchEndIds: string[] = [];

		// Helper function to parse step elements with flow properties
		const parseBranchElements = (
			stepEls: Element[],
			startY: number,
			flowProps?: { role?: 'try' | 'catch' | 'finally'; exceptions?: string[] }
		) => {
			if (stepEls.length === 0) return { lastEndId: null, maxY: startY };

			let branchPrevId: string | null = null;
			let branchX = branchStartX;
			let lastResultEndId: string | null = null;
			let currentMaxY = startY;

			for (let i = 0; i < stepEls.length; i++) {
				const connectFrom = i === 0 ? stepId : branchPrevId;
				const firstStepFlowProps = i === 0 ? flowProps : undefined;
				const result = parseStepElementToStore(store, stepEls[i], branchX, startY, connectFrom, firstStepFlowProps as any);
				if (result) {
					branchPrevId = result.endId;
					branchX = result.nextX;
					nextX = Math.max(nextX, result.nextX);
					currentMaxY = Math.max(currentMaxY, result.maxY);
					lastResultEndId = result.endId;
				}
			}
			return { lastEndId: lastResultEndId, maxY: currentMaxY };
		};

		// 1. Try steps: child elements that are NOT doCatch/doFinally
		const tryStepEls = getChildElements(stepEl).filter(c =>
			c.localName !== 'doCatch' && c.localName !== 'doFinally'
		);
		if (tryStepEls.length > 0) {
			branchMaxY = branchY;
			const result = parseBranchElements(tryStepEls, branchY, { role: 'try' });
			if (result.lastEndId) branchEndIds.push(result.lastEndId);
			branchMaxY = Math.max(branchMaxY, result.maxY);
			branchY = branchMaxY + NODE_HEIGHT + STEP_GAP_Y;
			maxY = Math.max(maxY, branchMaxY);
		}

		// 2. doCatch blocks
		const catchEls = getChildrenByTag(stepEl, 'doCatch');
		for (const catchEl of catchEls) {
			branchMaxY = branchY;
			const exceptionEls = getChildrenByTag(catchEl, 'exception');
			const exceptions = exceptionEls.length > 0
				? exceptionEls.map(el => el.textContent?.trim() || 'java.lang.Exception')
				: ['java.lang.Exception'];
			const catchStepEls = getChildElements(catchEl).filter(c => c.localName !== 'exception');
			const result = parseBranchElements(catchStepEls, branchY, { role: 'catch', exceptions });
			if (result.lastEndId) branchEndIds.push(result.lastEndId);
			branchMaxY = Math.max(branchMaxY, result.maxY);
			branchY = branchMaxY + NODE_HEIGHT + STEP_GAP_Y;
			maxY = Math.max(maxY, branchMaxY);
		}

		// 3. doFinally
		const finallyEl = Array.from(stepEl.children).find(c => c.localName === 'doFinally');
		if (finallyEl) {
			branchMaxY = branchY;
			const finallyStepEls = getChildElements(finallyEl);
			const result = parseBranchElements(finallyStepEls, branchY, { role: 'finally' });
			if (result.lastEndId) branchEndIds.push(result.lastEndId);
			branchMaxY = Math.max(branchMaxY, result.maxY);
			maxY = Math.max(maxY, branchMaxY);
		}

		// AUTO-MERGE
		if (branchEndIds.length > 0) {
			const mergeId = generateUUID();
			store.addProcessor({ id: mergeId, type: 'merge', properties: {} });
			store.addShape({ id: mergeId + '_di', semanticId: mergeId, bounds: { x: nextX, y, width: MERGE_WIDTH, height: MERGE_HEIGHT } });
			for (const branchEndId of branchEndIds) {
				const flowId = generateUUID();
				store.addFlow({ id: flowId, sourceRef: branchEndId, targetRef: mergeId });
				store.addEdge({ id: flowId + '_di', semanticId: flowId, waypoints: [] });
			}
			endId = mergeId;
			nextX += MERGE_WIDTH + STEP_GAP_X;
		}
	}

	// Handle Multicast element with child step branches
	if (stepType === 'multicast') {
		const multicastStepEls = getChildElements(stepEl);
		if (multicastStepEls.length > 0) {
			let branchY = y;
			const branchStartX = nextX;
			let branchMaxY = y;
			const branchEndIds: string[] = [];

			for (const branchStepEl of multicastStepEls) {
				branchMaxY = branchY;
				const result = parseStepElementToStore(store, branchStepEl, branchStartX, branchY, stepId);
				if (result) {
					nextX = Math.max(nextX, result.nextX);
					branchMaxY = Math.max(branchMaxY, result.maxY);
					branchEndIds.push(result.endId);
				}
				branchY = branchMaxY + NODE_HEIGHT + STEP_GAP_Y;
				maxY = Math.max(maxY, branchMaxY);
			}

			if (branchEndIds.length > 0) {
				const mergeId = generateUUID();
				store.addProcessor({ id: mergeId, type: 'merge', properties: {} });
				store.addShape({ id: mergeId + '_di', semanticId: mergeId, bounds: { x: nextX, y, width: MERGE_WIDTH, height: MERGE_HEIGHT } });
				for (const branchEndId of branchEndIds) {
					const flowId = generateUUID();
					store.addFlow({ id: flowId, sourceRef: branchEndId, targetRef: mergeId });
					store.addEdge({ id: flowId + '_di', semanticId: flowId, waypoints: [] });
				}
				endId = mergeId;
				nextX += MERGE_WIDTH + STEP_GAP_X;
			}
		}
	}

	return {
		id: stepId,
		endId,
		nextX,
		maxY
	};
}

/**
 * Parse step element properties based on type
 */
function parseStepElementProperties(type: EipType, el: Element): Record<string, any> {
	switch (type) {
		case 'to':
		case 'toD': {
			const rawUri = el.getAttribute('uri') || '';
			const qIdx = rawUri.indexOf('?');
			const uri = qIdx >= 0 ? rawUri.substring(0, qIdx) : rawUri;
			const parameters: Record<string, string> = {};
			if (qIdx >= 0) {
				for (const pair of rawUri.substring(qIdx + 1).split('&')) {
					const eqIdx = pair.indexOf('=');
					if (eqIdx >= 0) parameters[pair.substring(0, eqIdx)] = pair.substring(eqIdx + 1);
				}
			}
			return { uri, parameters: Object.keys(parameters).length > 0 ? parameters : undefined };
		}
		case 'log':
			return {
				message: el.getAttribute('message') || '',
				loggingLevel: el.getAttribute('loggingLevel') || 'INFO',
				loggerName: el.getAttribute('loggerName') || undefined
			};
		case 'setBody': {
			const simple = getChildText(el, 'simple');
			const constant = getChildText(el, 'constant');
			return { simple: simple || undefined, constant: constant || undefined };
		}
		case 'setHeader': {
			const name = el.getAttribute('name') || '';
			const exprEl = Array.from(el.children).find(c =>
				['simple', 'constant', 'jsonpath', 'xpath'].includes(c.localName)
			);
			return {
				name,
				expressionType: exprEl?.localName || 'simple',
				expression: exprEl?.textContent?.trim() || ''
			};
		}
		case 'filter': {
			const exprEl = Array.from(el.children).find(c =>
				['simple', 'xpath', 'jsonpath'].includes(c.localName)
			);
			return {
				expressionType: exprEl?.localName || 'simple',
				expression: exprEl?.textContent?.trim() || ''
			};
		}
		case 'split': {
			const exprEl = Array.from(el.children).find(c =>
				['simple', 'xpath', 'jsonpath', 'tokenize'].includes(c.localName)
			);
			return {
				expressionType: exprEl?.localName || 'simple',
				expression: exprEl?.textContent?.trim() || '',
				streaming: el.getAttribute('streaming') === 'true',
				parallelProcessing: el.getAttribute('parallelProcessing') === 'true',
				stopOnException: el.getAttribute('stopOnException') === 'true',
				shareUnitOfWork: el.getAttribute('shareUnitOfWork') === 'true',
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined
			};
		}
		case 'choice':
			return {};
		case 'delay': {
			const constant = getChildText(el, 'constant') || getChildText(el, 'simple') || '1000';
			return { constant };
		}
		case 'throttle': {
			const constant = getChildText(el, 'constant') || '10';
			return {
				constant,
				timePeriodMillis: el.getAttribute('timePeriodMillis') || undefined,
				asyncDelayed: el.getAttribute('asyncDelayed') === 'true' || undefined,
				rejectExecution: el.getAttribute('rejectExecution') === 'true' || undefined
			};
		}
		case 'bean':
			return {
				ref: el.getAttribute('ref') || '',
				method: el.getAttribute('method') || '',
				beanType: el.getAttribute('beanType') || undefined
			};
		case 'aggregate': {
			const corrEl = Array.from(el.children).find(c => c.localName === 'correlationExpression');
			const corrSimple = corrEl ? getChildText(corrEl, 'simple') : '';
			return {
				correlationExpression: corrSimple || '',
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined,
				completionSize: el.getAttribute('completionSize') || undefined,
				completionTimeout: el.getAttribute('completionTimeout') || undefined,
				eagerCheckCompletion: el.getAttribute('eagerCheckCompletion') === 'true' || undefined,
				completionFromBatchConsumer: el.getAttribute('completionFromBatchConsumer') === 'true' || undefined
			};
		}
		case 'marshal':
		case 'unmarshal': {
			const dfEl = el.children[0];
			if (!dfEl) return { dataFormat: 'json' };
			const dataFormat = dfEl.localName;
			const result: Record<string, any> = { dataFormat };
			if (dataFormat === 'json') {
				if (dfEl.getAttribute('library')) result.library = dfEl.getAttribute('library');
				if (dfEl.getAttribute('prettyPrint') === 'true') result.prettyPrint = true;
				if (dfEl.getAttribute('unmarshalType')) result.unmarshalType = dfEl.getAttribute('unmarshalType');
			}
			return result;
		}
		case 'transform': {
			const exprEl = Array.from(el.children).find(c =>
				['simple', 'constant', 'xpath'].includes(c.localName)
			);
			return {
				expressionType: exprEl?.localName || 'simple',
				expression: exprEl?.textContent?.trim() || ''
			};
		}
		case 'multicast':
			return {
				parallelProcessing: el.getAttribute('parallelProcessing') === 'true' || undefined,
				stopOnException: el.getAttribute('stopOnException') === 'true' || undefined,
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined
			};
		case 'recipientList': {
			const simple = getChildText(el, 'simple') || '';
			return {
				simple,
				parallelProcessing: el.getAttribute('parallelProcessing') === 'true' || undefined,
				stopOnException: el.getAttribute('stopOnException') === 'true' || undefined,
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined
			};
		}
		case 'loop': {
			const simple = getChildText(el, 'simple') || '3';
			return {
				simple,
				doWhile: el.getAttribute('doWhile') === 'true' || undefined,
				copy: el.getAttribute('copy') === 'true' || undefined
			};
		}
		case 'wireTap':
			return {
				uri: el.getAttribute('uri') || '',
				copy: el.getAttribute('copy') === 'false' ? false : undefined
			};
		case 'enrich': {
			const constant = getChildText(el, 'constant') || '';
			return {
				uri: constant,
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined
			};
		}
		case 'pollEnrich': {
			const constant = getChildText(el, 'constant') || '';
			return {
				uri: constant,
				timeout: el.getAttribute('timeout') || undefined,
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined
			};
		}
		case 'threads':
			return {
				poolSize: el.getAttribute('poolSize') || undefined,
				maxPoolSize: el.getAttribute('maxPoolSize') || undefined,
				maxQueueSize: el.getAttribute('maxQueueSize') || undefined
			};
		case 'circuitBreaker': {
			const r4jEl = Array.from(el.children).find(c => c.localName === 'resilience4jConfiguration');
			if (r4jEl) {
				return {
					resilience4jConfiguration: {
						minimumNumberOfCalls: r4jEl.getAttribute('minimumNumberOfCalls') || undefined,
						failureRateThreshold: r4jEl.getAttribute('failureRateThreshold') || undefined,
						waitDurationInOpenState: r4jEl.getAttribute('waitDurationInOpenState') || undefined
					}
				};
			}
			return {};
		}
		case 'stop':
			return {};
		default: {
			// Generic: extract all attributes as properties
			const props: Record<string, any> = {};
			for (const attr of Array.from(el.attributes)) {
				props[attr.name] = attr.value;
			}
			return props;
		}
	}
}

// =============================================================================
// Application
// =============================================================================

const App = {
	data() {
		return {
			// Application instance
			instance: null as ApplicationInstance | null,

			// Resolved URL of the canvas EIP icon sprite. Populated in
			// appLaunch from the app's deployed location. Used in the
			// canvas template via <use :href="spriteUrl + '#canvas-...'"/>.
			spriteUrl: '',

			// Application state
			isLoading: false,
			isSaving: false,

			// File management (tabs)
			files: [] as CamelFile[],
			currentFileIndex: -1,

			// UI state
			selectedElementId: null as string | null,
			draggedType: null as EipType | null,

			// Pane visibility / sizing
			sidebarPanelVisible: true,
			detailPanelVisible: true,
			sidebarPanelWidth: 240,
			sidebarResizing: false,
			sidebarResizeStartX: 0,
			sidebarResizeStartWidth: 0,
			// Palette collapsible sections (keyed by category.key)
			paletteExpanded: {
				basic: true,
				transform: true,
				routing: true,
				control: true,
				integration: true,
				errorHandling: true,
			} as Record<string, boolean>,

			// Canvas interaction state
			isDragging: false,
			draggedShapeId: null as string | null,
			dragOffset: { x: 0, y: 0 },
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

			// Connection drawing state
			isDrawingConnection: false,
			connectionSourceId: null as string | null,
			connectionPreview: null as { x: number; y: number } | null,
			connectionTargetIds: [] as string[],

			// Properties panel resize
			propertiesPanelWidth: 280,
			propertiesPanelResizing: null as { startX: number; startWidth: number } | null,

			// Store version trigger for reactivity
			storeVersion: 0,

			// Close confirmation dialog state
			closeConfirmDialog: {
				visible: false,
				resolve: null as null | ((result: 'save' | 'discard' | 'cancel') => void),
			},

			// Save As dialog state
			saveAsDialog: {
				visible: false,
				fileName: '',
			},
			saveAsToken: '',
			saveAsChannel: null as BroadcastChannel | null,
			messageListener: null as ((e: MessageEvent) => void) | null,

			// Palette categories
			paletteCategories: [
				{
					key: 'basic',
					name: 'Basic',
					items: [
						{ type: 'from', label: 'From', icon: 'icon-from' },
						{ type: 'to', label: 'To', icon: 'icon-to' },
						{ type: 'toD', label: 'To D', icon: 'icon-tod' },
					],
				},
				{
					key: 'transform',
					name: 'Transform',
					items: [
						{ type: 'log', label: 'Log', icon: 'icon-log' },
						{ type: 'setBody', label: 'Set Body', icon: 'icon-setbody' },
						{ type: 'setHeader', label: 'Set Header', icon: 'icon-setheader' },
						{ type: 'transform', label: 'Transform', icon: 'icon-transform' },
						{ type: 'marshal', label: 'Marshal', icon: 'icon-marshal' },
						{ type: 'unmarshal', label: 'Unmarshal', icon: 'icon-unmarshal' },
					],
				},
				{
					key: 'routing',
					name: 'Routing',
					items: [
						{ type: 'choice', label: 'Choice', icon: 'icon-choice' },
						{ type: 'filter', label: 'Filter', icon: 'icon-filter' },
						{ type: 'split', label: 'Split', icon: 'icon-split' },
						{ type: 'aggregate', label: 'Aggregate', icon: 'icon-aggregate' },
						{ type: 'multicast', label: 'Multicast', icon: 'icon-multicast' },
						{ type: 'recipientList', label: 'Recipient List', icon: 'icon-recipientlist' },
						{ type: 'loop', label: 'Loop', icon: 'icon-loop' },
						{ type: 'merge', label: 'Merge', icon: 'icon-merge' },
					],
				},
				{
					key: 'control',
					name: 'Control',
					items: [
						{ type: 'delay', label: 'Delay', icon: 'icon-delay' },
						{ type: 'throttle', label: 'Throttle', icon: 'icon-throttle' },
						{ type: 'bean', label: 'Bean', icon: 'icon-bean' },
						{ type: 'threads', label: 'Threads', icon: 'icon-threads' },
						{ type: 'circuitBreaker', label: 'Circuit Breaker', icon: 'icon-circuitbreaker' },
						{ type: 'stop', label: 'Stop', icon: 'icon-stop' },
					],
				},
				{
					key: 'integration',
					name: 'Integration',
					items: [
						{ type: 'wireTap', label: 'Wire Tap', icon: 'icon-wiretap' },
						{ type: 'enrich', label: 'Enrich', icon: 'icon-enrich' },
						{ type: 'pollEnrich', label: 'Poll Enrich', icon: 'icon-pollenrich' },
					],
				},
				{
					key: 'errorHandling',
					name: 'Error Handling',
					items: [
						{ type: 'onException', label: 'On Exception', icon: 'icon-onexception' },
						{ type: 'doTry', label: 'Try-Catch', icon: 'icon-dotry' },
					],
				},
			] as PaletteCategory[],
		};
	},

	computed: {
		/**
		 * Get current file
		 */
		currentFile(): CamelFile | null {
			return this.currentFileIndex >= 0 && this.currentFileIndex < this.files.length
				? this.files[this.currentFileIndex]
				: null;
		},

		/**
		 * Get active store (shortcut)
		 */
		activeStore(): CamelModelStore | null {
			return this.currentFile?.store || null;
		},

		/**
		 * Get current scale
		 */
		scale(): number {
			return this.currentFile?.scale || 1;
		},

		/**
		 * Get current pan offset
		 */
		panOffset(): { x: number; y: number } {
			return this.currentFile?.panOffset || { x: 0, y: 0 };
		},

		/**
		 * Check if current file is modified
		 */
		isModified(): boolean {
			return this.currentFile?.isModified || false;
		},

		/**
		 * Check if there is a selection
		 */
		hasSelection(): boolean {
			return this.selectedElementId !== null;
		},

		/**
		 * Check if undo is available
		 */
		canUndo(): boolean {
			return (this.currentFile?.undoStack.length || 0) > 0;
		},

		/**
		 * Check if redo is available
		 */
		canRedo(): boolean {
			return (this.currentFile?.redoStack.length || 0) > 0;
		},

		/**
		 * Get viewport transform string
		 */
		viewportTransform(): string {
			const offset = this.panOffset;
			const s = this.scale;
			return `translate(${offset.x}, ${offset.y}) scale(${s})`;
		},

		/**
		 * Get all shapes from store (reactive)
		 */
		shapes(): CamelDiShape[] {
			// Trigger reactivity
			const _version = this.storeVersion;
			if (!this.activeStore) return [];
			return this.activeStore.getAllShapes();
		},

		/**
		 * Get all edges from store (reactive)
		 */
		edges(): CamelDiEdge[] {
			const _version = this.storeVersion;
			if (!this.activeStore) return [];
			return this.activeStore.getAllEdges();
		},

		/**
		 * Get selected element (processor or flow)
		 */
		selectedElement(): CamelProcessorSemantic | CamelFlowSemantic | null {
			if (!this.selectedElementId || !this.activeStore) return null;
			// First try to find a processor (node)
			const processor = this.activeStore.getProcessor(this.selectedElementId);
			if (processor) return processor;
			// Otherwise try to find a flow (connection)
			const flow = this.activeStore.getFlow(this.selectedElementId);
			if (flow) return flow;
			return null;
		},

		/**
		 * Check if selected element is a flow (connection)
		 */
		isFlowSelected(): boolean {
			if (!this.selectedElementId || !this.activeStore) return false;
			return !!this.activeStore.getFlow(this.selectedElementId);
		},

		/**
		 * Get the selected flow (if a flow is selected)
		 */
		selectedFlow(): CamelFlowSemantic | null {
			if (!this.selectedElementId || !this.activeStore) return null;
			return this.activeStore.getFlow(this.selectedElementId) || null;
		},

		/**
		 * Get the selected processor (if a processor is selected)
		 */
		selectedProcessor(): CamelProcessorSemantic | null {
			if (!this.selectedElementId || !this.activeStore) return null;
			return this.activeStore.getProcessor(this.selectedElementId) || null;
		},

		/**
		 * Get connection paths for rendering
		 */
		connectionPaths(): { path: string; sourceId: string; targetId: string; flowId: string }[] {
			const _version = this.storeVersion;
			if (!this.activeStore) return [];

			const paths: { path: string; sourceId: string; targetId: string; flowId: string }[] = [];
			const flows = this.activeStore.getAllFlows();

			for (const flow of flows) {
				const sourceShape = this.activeStore.getShapeForSemantic(flow.sourceRef);
				const targetShape = this.activeStore.getShapeForSemantic(flow.targetRef);

				if (sourceShape && targetShape) {
					const sourceSemantic = this.activeStore.getProcessor(flow.sourceRef);
					const targetSemantic = this.activeStore.getProcessor(flow.targetRef);

					const sourceStyle = sourceSemantic?.type === 'from' ? FROM_NODE_STYLE : getNodeStyleForType(sourceSemantic?.type || '');
					const targetStyle = getNodeStyleForType(targetSemantic?.type || '');

					const { sourcePort, targetPort } = this.selectOptimalPorts(
						{ x: sourceShape.bounds.x, y: sourceShape.bounds.y, style: sourceStyle },
						{ x: targetShape.bounds.x, y: targetShape.bounds.y, style: targetStyle }
					);

					const path = this.createSmartPath(sourcePort, targetPort);

					paths.push({
						path,
						sourceId: flow.sourceRef,
						targetId: flow.targetRef,
						flowId: flow.id
					});
				}
			}

			return paths;
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
	},

	methods: {
		// =========================================================================
		// Lifecycle
		// =========================================================================

		onMounted() {
			const vm = this;

			// Set up keyboard shortcuts
			document.addEventListener('keydown', this.onKeyDown);
			document.addEventListener('keyup', this.onKeyUp);
			window.addEventListener('blur', this.onWindowBlur);

			// Listen for theme changes from parent window
			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			// Set up Save As response channel
			vm.saveAsChannel = new BroadcastChannel('webtop-save-as');
			vm.saveAsChannel.onmessage = (event: MessageEvent) => {
				if (event.data?.type === 'save-as-complete' && event.data.saveAsToken && event.data.saveAsToken === vm.saveAsToken) {
					const file = vm.currentFile;
					if (file) {
						file.path = event.data.path;
						file.name = event.data.name;
						file.mimeType = event.data.mimeType;
						file.isModified = false;
						if (vm.instance) {
							vm.instance.windowTitle = event.data.name;
						}
					}
					vm.saveAsToken = '';
				}
			};

			// Register appLaunch callback
			window.appLaunch = async (instance: ApplicationInstance, options?: LaunchOptions) => {
				vm.instance = vm.$markRaw(instance);
				vm.spriteUrl = new URL("assets/icons/icons.svg", window.location.origin + window.location.pathname).href;
				instance.appState = () => {
					const paths = vm.files.map((f: CamelFile) => f.path).filter((p: string) => !!p);
					if (paths.length === 0) return {};
					const fileStates = vm.files
						.filter((f: CamelFile) => !!f.path)
						.map((f: CamelFile) => ({ path: f.path, scale: f.scale, panOffset: { ...f.panOffset } }));
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
							const file = vm.files.find((f: CamelFile) => f.path === fs.path);
							if (file) {
								if (fs.scale != null) file.scale = fs.scale;
								if (fs.panOffset != null) file.panOffset = { ...fs.panOffset };
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

			// Initialize with default if not running in Webtop context
			setTimeout(() => {
				if (!vm.instance && vm.files.length === 0) {
					document.documentElement.dataset.theme = 'light';
					vm.newDiagram();
				}
			}, 100);
		},

		onUnmount() {
			document.removeEventListener('keydown', this.onKeyDown);
			document.removeEventListener('keyup', this.onKeyUp);
			window.removeEventListener('blur', this.onWindowBlur);
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
				this.messageListener = null;
			}
			if (this.saveAsChannel) {
				this.saveAsChannel.close();
				this.saveAsChannel = null;
			}
		},

		// =========================================================================
		// File Management (Tabs)
		// =========================================================================

		/**
		 * Create new diagram
		 */
		newDiagram() {
			const store = new CamelModelStore();

			// Create initial 'from' node
			const fromId = generateUUID();
			const fromSemantic: CamelProcessorSemantic = {
				id: fromId,
				type: 'from',
				properties: {
					uri: 'timer:tick',
					parameters: { period: '1000' }
				}
			};
			store.addProcessor(fromSemantic);

			const fromShape: CamelDiShape = {
				id: fromId + '_di',
				semanticId: fromId,
				bounds: { x: 150, y: 100, width: FROM_WIDTH, height: FROM_HEIGHT }
			};
			store.addShape(fromShape);

			// Create new file
			const newFile: CamelFile = {
				id: generateUUID(),
				name: `Route-${this.files.length + 1}.camel.xml`,
				path: '',
				mimeType: 'application/x-camel-route',
				store: this.$markRaw(store),
				scale: 1.0,
				panOffset: { x: 0, y: 0 },
				isModified: false,
				undoStack: [],
				redoStack: []
			};

			this.files.push(newFile);
			this.currentFileIndex = this.files.length - 1;
			this.selectedElementId = null;
			this.storeVersion++;

			if (this.instance) {
				this.instance.windowTitle = newFile.name;
			}
		},

		/**
		 * Close a file tab
		 */
		async closeFile(index: number) {
			const vm = this;
			if (index < 0 || index >= vm.files.length) return;

			const file = vm.files[index];
			const originalIndex = vm.currentFileIndex;

			if (file.isModified) {
				// Switch to the file being closed to show context
				if (index !== vm.currentFileIndex) {
					vm.currentFileIndex = index;
					vm.selectedElementId = null;
					vm.storeVersion++;
				}
				const result = await vm.showCloseConfirmDialog();
				if (result === 'cancel') {
					// Stay on the tab being reviewed
					return;
				}
				if (result === 'save') {
					await vm.saveFile();
					if (vm.files[index]?.isModified) return;
				}
			}

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
			vm.selectedElementId = null;
			vm.storeVersion++;
		},

		/**
		 * Select a file tab
		 */
		selectTab(index: number) {
			if (index >= 0 && index < this.files.length) {
				this.currentFileIndex = index;
				this.selectedElementId = null;
				this.storeVersion++;

				if (this.instance && this.currentFile) {
					this.instance.windowTitle = this.currentFile.name;
				}
			}
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

			// Content Browser (cross-iframe) file drag — open via path in a new tab
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

			// Local files (OS) — read content and open as a new untitled tab
			const files = event.dataTransfer?.files;
			if (files && files.length > 0) {
				for (let i = 0; i < files.length; i++) {
					const file = files[i];
					try {
						const text = await file.text();
						const store = parseXmlToStore(text);

						const newFile: CamelFile = {
							id: generateUUID(),
							name: file.name,
							path: '',
							mimeType: 'application/x-camel-route',
							store: vm.$markRaw(store),
							scale: 1.0,
							panOffset: { x: 0, y: 0 },
							isModified: false,
							undoStack: [],
							redoStack: []
						};

						vm.files.push(newFile);
						vm.currentFileIndex = vm.files.length - 1;
						vm.selectedElementId = null;
						vm.storeVersion++;

						if (vm.instance) {
							vm.instance.windowTitle = file.name;
						}
					} catch (e) {
						console.error('Failed to load local file:', file.name, e);
					}
				}
			}
		},

		/**
		 * Load file from path
		 */
		async loadFile(path: string) {
			const vm = this;
			vm.isLoading = true;

			try {
				const contentService = vm.instance!.api.content;
				const node: Node | null = await contentService.getNode(path);

				if (!node) {
					throw new Error(`File not found: ${path}`);
				}

				// Fetch file content
				if (node.downloadUrl) {
					const response = await fetch(node.downloadUrl);
					if (!response.ok) {
						throw new Error(`Failed to fetch file: ${response.statusText}`);
					}
					const xmlContent = await response.text();
					const store = parseXmlToStore(xmlContent);

					// Create file entry
					const newFile: CamelFile = {
						id: generateUUID(),
						name: node.name,
						path: path,
						mimeType: node.mimeType || 'application/x-camel-route',
						store: vm.$markRaw(store),
						scale: 1.0,
						panOffset: { x: 0, y: 0 },
						isModified: false,
						undoStack: [],
						redoStack: []
					};

					vm.files.push(newFile);
					vm.currentFileIndex = vm.files.length - 1;
					vm.selectedElementId = null;
					vm.storeVersion++;

					if (vm.instance) {
						vm.instance.windowTitle = node.name;
					}
				}
			} catch (error) {
				console.error('Failed to load file:', error);
			} finally {
				vm.isLoading = false;
			}
	
		},

		/**
		 * Save file
		 */
		async saveFile() {
			const vm = this;
			if (vm.isSaving || !vm.currentFile || !vm.activeStore) return;

			vm.isSaving = true;

			try {
				const contentService = vm.instance!.api.content;
				const xml = storeToXml(vm.activeStore);

				const encoder = new TextEncoder();
				const bytes = encoder.encode(xml);
				const base64Content = btoa(String.fromCharCode(...bytes));

				if (vm.currentFile.path) {
					const pathParts = vm.currentFile.path.split('/');
					const fileName = pathParts.pop() as string;
					const parentPath = pathParts.join('/') || '/';

					const uploadInfo = await contentService.initiateMultipartUpload();
					const uploadId = uploadInfo.uploadId;

					try {
						await contentService.appendMultipartUploadChunk(uploadId, base64Content);
						await contentService.completeMultipartUpload(
							uploadId, parentPath, fileName, vm.currentFile.mimeType, true
						);
						vm.currentFile.isModified = false;
					} catch (error) {
						try {
							await contentService.abortMultipartUpload(uploadId);
						} catch { /* ignore */ }
						throw error;
					}
				} else {
					console.log('Save as new file - not yet implemented');
				}
			} catch (error) {
				console.error('Failed to save file:', error);
			} finally {
				vm.isSaving = false;
			}
		},

		// =========================================================================
		// Save As
		// =========================================================================

		openSaveAsDialog() {
			this.saveAsDialog.fileName = this.currentFile?.name || 'untitled.xml';
			this.saveAsDialog.visible = true;
		},

		closeSaveAsDialog() {
			this.saveAsDialog.visible = false;
		},

		onSaveAsDragStart(event: DragEvent) {
			if (!event.dataTransfer || !this.activeStore) return;
			const xml = storeToXml(this.activeStore);
			const fileName = this.saveAsDialog.fileName || 'untitled.xml';
			this.saveAsToken = Date.now().toString(36) + Math.random().toString(36).slice(2);
			event.dataTransfer.effectAllowed = 'copy';
			event.dataTransfer.setData('application/x-webtop-save', JSON.stringify({
				name: fileName,
				mimeType: 'application/x-camel-route',
				content: xml,
				saveAsToken: this.saveAsToken,
			}));
			event.dataTransfer.setData('text/plain', fileName);
			// Close dialog after drag starts
			setTimeout(() => { this.saveAsDialog.visible = false; }, 100);
		},

		// =========================================================================
		// Element Selection
		// =========================================================================

		/**
		 * Select an element by semantic ID
		 */
		selectElement(semanticId: string) {
			this.selectedElementId = semanticId;
		},

		/**
		 * Clear selection
		 */
		clearSelection() {
			this.selectedElementId = null;
		},

		/**
		 * Delete selected element (processor or flow)
		 */
		deleteSelected() {
			if (!this.selectedElementId || !this.activeStore) return;

			// Check if it's a flow (connection)
			const flow = this.activeStore.getFlow(this.selectedElementId);
			if (flow) {
				this.activeStore.removeFlow(this.selectedElementId);
				this.selectedElementId = null;
				this.markModified();
				this.storeVersion++;
				return;
			}

			// Check if it's a processor (node)
			const semantic = this.activeStore.getProcessor(this.selectedElementId);
			if (!semantic) return;

			// Don't allow deleting 'from' nodes if it's the only one
			if (semantic.type === 'from') {
				const fromNodes = this.activeStore.getAllProcessors().filter(p => p.type === 'from');
				if (fromNodes.length <= 1) return;
			}

			this.activeStore.removeProcessor(this.selectedElementId);
			this.selectedElementId = null;
			this.markModified();
			this.storeVersion++;
		},

		// =========================================================================
		// Drag & Drop from Palette
		// =========================================================================

		/**
		 * Handle palette drag start
		 */
		onPaletteDragStart(event: DragEvent, type: EipType) {
			this.draggedType = type;
			if (event.dataTransfer) {
				event.dataTransfer.effectAllowed = 'copy';
				event.dataTransfer.setData('application/x-camel-type', type);
			}
		},

		/**
		 * Handle canvas drag over
		 */
		onCanvasDragOver(event: DragEvent) {
			event.preventDefault();
			if (event.dataTransfer) {
				event.dataTransfer.dropEffect = 'copy';
			}
		},

		/**
		 * Handle canvas drop
		 */
		onCanvasDrop(event: DragEvent) {
			event.preventDefault();

			const type = event.dataTransfer?.getData('application/x-camel-type') as EipType;
			if (!type || !this.activeStore || !this.currentFile) return;

			const svg = document.getElementById('camel-canvas') as unknown as SVGSVGElement;
			if (!svg) return;

			const rect = svg.getBoundingClientRect();
			const x = (event.clientX - rect.left - this.currentFile.panOffset.x) / this.currentFile.scale;
			const y = (event.clientY - rect.top - this.currentFile.panOffset.y) / this.currentFile.scale;

			const snappedX = Math.round(x / 10) * 10;
			const snappedY = Math.round(y / 10) * 10;

			// Create new processor
			const id = type + '_' + generateUUID().substring(0, 8);
			const dims = getNodeDimensions(type);

			const semantic: CamelProcessorSemantic = {
				id: id,
				type: type,
				properties: getDefaultProperties(type)
			};

			const shape: CamelDiShape = {
				id: id + '_di',
				semanticId: id,
				bounds: { x: snappedX, y: snappedY, width: dims.width, height: dims.height }
			};

			this.activeStore.addProcessor(semantic);
			this.activeStore.addShape(shape);

			// Manual connection mode - no auto-connect
			// User will draw connections manually via port dragging

			this.selectedElementId = id;
			this.markModified();
			this.storeVersion++;

			this.draggedType = null;
		},

		/**
		 * Try to auto-connect a new node to nearby nodes
		 */
		tryAutoConnect(newId: string, x: number, y: number) {
			if (!this.activeStore) return;

			const newSemantic = this.activeStore.getProcessor(newId);
			if (!newSemantic) return;

			// Don't auto-connect 'from' nodes as targets
			if (newSemantic.type === 'from') return;

			const shapes = this.activeStore.getAllShapes();
			const flows = this.activeStore.getAllFlows();

			let nearestSource: { id: string; distance: number } | null = null;
			const connectionThreshold = 200;

			for (const shape of shapes) {
				if (shape.semanticId === newId) continue;

				const semantic = this.activeStore.getProcessor(shape.semanticId);
				if (!semantic) continue;

				// Check if this node already has an outgoing connection
				const hasOutgoing = flows.some(f => f.sourceRef === shape.semanticId);
				if (hasOutgoing && semantic.type !== 'choice') continue;

				// Calculate distance from right edge of source to left edge of new node
				const sourceRight = shape.bounds.x + shape.bounds.width;
				const dx = x - sourceRight;
				const dy = Math.abs(y - shape.bounds.y);

				// Only connect if new node is to the right and within threshold
				if (dx > 0 && dx < connectionThreshold && dy < 100) {
					const distance = Math.sqrt(dx * dx + dy * dy);
					if (!nearestSource || distance < nearestSource.distance) {
						nearestSource = { id: shape.semanticId, distance };
					}
				}
			}

			if (nearestSource) {
				const flowId = generateUUID();
				const flow: CamelFlowSemantic = {
					id: flowId,
					sourceRef: nearestSource.id,
					targetRef: newId
				};
				this.activeStore.addFlow(flow);

				const edge: CamelDiEdge = {
					id: flowId + '_di',
					semanticId: flowId,
					waypoints: []
				};
				this.activeStore.addEdge(edge);
			}
		},

		// =========================================================================
		// Canvas Events
		// =========================================================================

		onCanvasMouseDown(event: MouseEvent) {
			// Middle button, Alt+drag, or Space+drag for panning
			if (event.button === 1 || (event.button === 0 && (event.altKey || this.isSpaceHeld))) {
				this.isPanning = true;
				this.panStartX = event.clientX;
				this.panStartY = event.clientY;
				if (this.currentFile) {
					this.panStartPanX = this.currentFile.panOffset.x;
					this.panStartPanY = this.currentFile.panOffset.y;
				}
				event.preventDefault();
				return;
			}

			// Left click on empty area - start selection rectangle
			if (event.button === 0) {
				const svg = document.getElementById('camel-canvas') as unknown as SVGSVGElement;
				if (!svg || !this.currentFile) return;

				const rect = svg.getBoundingClientRect();
				const x = (event.clientX - rect.left - this.currentFile.panOffset.x) / this.currentFile.scale;
				const y = (event.clientY - rect.top - this.currentFile.panOffset.y) / this.currentFile.scale;

				this.selectionStartX = x;
				this.selectionStartY = y;
				this.selectionRect = { x, y, width: 0, height: 0 };

				if (!event.shiftKey) {
					this.clearSelection();
				}
			}
		},

		onCanvasMouseMove(event: MouseEvent) {
			// Panning
			if (this.isPanning && this.currentFile) {
				this.currentFile.panOffset.x = this.panStartPanX + (event.clientX - this.panStartX);
				this.currentFile.panOffset.y = this.panStartPanY + (event.clientY - this.panStartY);
				return;
			}

			// Element dragging
			if (this.isDragging && this.draggedShapeId && this.activeStore && this.currentFile) {
				const svg = document.getElementById('camel-canvas') as unknown as SVGSVGElement;
				if (!svg) return;

				const rect = svg.getBoundingClientRect();
				const x = (event.clientX - rect.left - this.currentFile.panOffset.x) / this.currentFile.scale - this.dragOffset.x;
				const y = (event.clientY - rect.top - this.currentFile.panOffset.y) / this.currentFile.scale - this.dragOffset.y;

				const shape = this.activeStore.getShape(this.draggedShapeId);
				if (shape) {
					// Calculate base position with grid snap
					let newX = Math.round(x / 10) * 10;
					let newY = Math.round(y / 10) * 10;

					// Snap to connected elements' connection points for horizontal alignment
					const snapThreshold = 15;
					const processor = this.activeStore.getProcessor(shape.semanticId);
					if (processor) {
						const elementYOffset = getHorizontalConnectionYOffset(processor.type);
						const flows = this.activeStore.getAllFlows();

						// Find connected elements and try to align Y positions
						for (const flow of flows) {
							let connectedSemanticId: string | null = null;

							if (flow.sourceRef === shape.semanticId) {
								connectedSemanticId = flow.targetRef;
							} else if (flow.targetRef === shape.semanticId) {
								connectedSemanticId = flow.sourceRef;
							}

							if (connectedSemanticId) {
								const connectedShape = this.activeStore.getShapeForSemantic(connectedSemanticId);
								const connectedProcessor = this.activeStore.getProcessor(connectedSemanticId);

								if (connectedShape && connectedProcessor) {
									const connectedYOffset = getHorizontalConnectionYOffset(connectedProcessor.type);
									// Calculate the Y position that would align connection points horizontally
									const alignedY = connectedShape.bounds.y + connectedYOffset - elementYOffset;

									// If close enough, snap to aligned position
									if (Math.abs(newY - alignedY) < snapThreshold) {
										newY = alignedY;
										break; // Use first snap match
									}
								}
							}
						}
					}

					this.activeStore.updateShape(this.draggedShapeId, {
						bounds: {
							...shape.bounds,
							x: newX,
							y: newY
						}
					});
					this.storeVersion++;
				}
				return;
			}

			// Connection preview
			if (this.isDrawingConnection && this.currentFile) {
				const svg = document.getElementById('camel-canvas') as unknown as SVGSVGElement;
				if (!svg) return;

				const rect = svg.getBoundingClientRect();
				const x = (event.clientX - rect.left - this.currentFile.panOffset.x) / this.currentFile.scale;
				const y = (event.clientY - rect.top - this.currentFile.panOffset.y) / this.currentFile.scale;

				this.connectionPreview = { x, y };

				// Detect nearby elements to show their connection points
				if (this.activeStore) {
					const proximityThreshold = 80;
					const nearbyIds: string[] = [];
					for (const shape of this.activeStore.getAllShapes()) {
						if (shape.semanticId === this.connectionSourceId) continue;
						const cx = shape.bounds.x + shape.bounds.width / 2;
						const cy = shape.bounds.y + shape.bounds.height / 2;
						const dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
						if (dist < proximityThreshold + Math.max(shape.bounds.width, shape.bounds.height) / 2) {
							nearbyIds.push(shape.semanticId);
						}
					}
					this.connectionTargetIds = nearbyIds;
				}
				return;
			}

			// Selection rectangle
			if (this.selectionRect && this.currentFile) {
				const svg = document.getElementById('camel-canvas') as unknown as SVGSVGElement;
				if (!svg) return;

				const rect = svg.getBoundingClientRect();
				const x = (event.clientX - rect.left - this.currentFile.panOffset.x) / this.currentFile.scale;
				const y = (event.clientY - rect.top - this.currentFile.panOffset.y) / this.currentFile.scale;

				this.selectionRect = {
					x: Math.min(this.selectionStartX, x),
					y: Math.min(this.selectionStartY, y),
					width: Math.abs(x - this.selectionStartX),
					height: Math.abs(y - this.selectionStartY),
				};
			}
		},

		onCanvasMouseUp(event: MouseEvent) {
			if (this.isPanning) {
				this.isPanning = false;
				return;
			}

			if (this.isDragging) {
				this.isDragging = false;
				this.draggedShapeId = null;
				this.markModified();
				return;
			}

			if (this.isDrawingConnection) {
				this.isDrawingConnection = false;
				this.connectionSourceId = null;
				this.connectionPreview = null;
				this.connectionTargetIds = [];
				return;
			}

			// Finalize selection rectangle
			if (this.selectionRect && this.selectionRect.width > 5 && this.selectionRect.height > 5) {
				// Select elements within rectangle
				if (this.activeStore) {
					const { x, y, width, height } = this.selectionRect;
					for (const shape of this.activeStore.getAllShapes()) {
						if (shape.bounds.x >= x && shape.bounds.y >= y &&
							shape.bounds.x + shape.bounds.width <= x + width &&
							shape.bounds.y + shape.bounds.height <= y + height) {
							this.selectedElementId = shape.semanticId;
							break; // Single selection for now
						}
					}
				}
			}

			this.selectionRect = null;
		},

		// Properties panel resize
		onPropertiesPanelResizeStart(event: MouseEvent) {
			event.preventDefault();
			this.propertiesPanelResizing = {
				startX: event.clientX,
				startWidth: this.propertiesPanelWidth,
			};
			document.addEventListener('mousemove', this.onPropertiesPanelResizeMove);
			document.addEventListener('mouseup', this.onPropertiesPanelResizeEnd);
		},

		onPropertiesPanelResizeMove(event: MouseEvent) {
			if (!this.propertiesPanelResizing) return;
			const dx = this.propertiesPanelResizing.startX - event.clientX;
			this.propertiesPanelWidth = Math.max(200, Math.min(600, this.propertiesPanelResizing.startWidth + dx));
		},

		onPropertiesPanelResizeEnd() {
			this.propertiesPanelResizing = null;
			document.removeEventListener('mousemove', this.onPropertiesPanelResizeMove);
			document.removeEventListener('mouseup', this.onPropertiesPanelResizeEnd);
		},

		// =========================================================================
		// Property-panel dropdowns (shell-rendered popups replacing <select>)
		// =========================================================================

		/**
		 * Open a choice popup anchored at the trigger element's rect.
		 * The shell renders the menu above all iframes; the selected id is
		 * resolved via the popup service. Mirrors the BPMN editor pattern.
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
			vm.storeVersion++;
		},

		/**
		 * Resolve the label for the current value of a choice option list.
		 */
		optionLabel(options: ChoiceOption[], value: string | undefined, fallback = ''): string {
			if (!value) return fallback;
			return options.find(o => o.id === value)?.label ?? fallback;
		},

		// -- Flow (connection) dropdowns --
		openFlowConditionTypeDropdown(event: MouseEvent) {
			const vm = this;
			const flow = vm.selectedFlow;
			if (!flow) return;
			vm.openChoicePopup(event, FLOW_CONDITION_TYPE_OPTIONS,
				flow.conditionType || 'when',
				v => { (flow as any).conditionType = v || 'when'; vm.markModified(); });
		},
		flowConditionTypeLabel(): string {
			return this.optionLabel(FLOW_CONDITION_TYPE_OPTIONS,
				this.selectedFlow?.conditionType || 'when', 'When (Condition)');
		},

		openFlowLanguageDropdown(event: MouseEvent) {
			const vm = this;
			const flow = vm.selectedFlow;
			if (!flow) return;
			vm.openChoicePopup(event, FLOW_LANGUAGE_OPTIONS,
				flow.language || 'simple',
				v => { (flow as any).language = v || 'simple'; vm.markModified(); });
		},
		flowLanguageLabel(): string {
			return this.optionLabel(FLOW_LANGUAGE_OPTIONS,
				this.selectedFlow?.language || 'simple', 'Simple');
		},

		openFlowRoleDropdown(event: MouseEvent) {
			const vm = this;
			const flow = vm.selectedFlow;
			if (!flow) return;
			vm.openChoicePopup(event, FLOW_ROLE_OPTIONS,
				flow.role || 'try',
				v => { (flow as any).role = v || 'try'; vm.markModified(); });
		},
		flowRoleLabel(): string {
			return this.optionLabel(FLOW_ROLE_OPTIONS,
				this.selectedFlow?.role || 'try', 'Try (Main Flow)');
		},

		// -- Processor dropdowns --
		openLoggingLevelDropdown(event: MouseEvent) {
			const vm = this;
			const proc = vm.selectedProcessor;
			if (!proc) return;
			vm.openChoicePopup(event, LOGGING_LEVEL_OPTIONS,
				proc.properties.loggingLevel || 'INFO',
				v => { proc.properties.loggingLevel = v || 'INFO'; vm.markModified(); });
		},
		loggingLevelLabel(): string {
			return this.optionLabel(LOGGING_LEVEL_OPTIONS,
				this.selectedProcessor?.properties.loggingLevel || 'INFO', 'INFO');
		},

		openSetHeaderExpressionTypeDropdown(event: MouseEvent) {
			const vm = this;
			const proc = vm.selectedProcessor;
			if (!proc) return;
			vm.openChoicePopup(event, SET_HEADER_EXPRESSION_TYPE_OPTIONS,
				proc.properties.expressionType || 'simple',
				v => { proc.properties.expressionType = v || 'simple'; vm.markModified(); });
		},
		setHeaderExpressionTypeLabel(): string {
			return this.optionLabel(SET_HEADER_EXPRESSION_TYPE_OPTIONS,
				this.selectedProcessor?.properties.expressionType || 'simple', 'Simple');
		},

		openFilterExpressionTypeDropdown(event: MouseEvent) {
			const vm = this;
			const proc = vm.selectedProcessor;
			if (!proc) return;
			vm.openChoicePopup(event, FILTER_EXPRESSION_TYPE_OPTIONS,
				proc.properties.expressionType || 'simple',
				v => { proc.properties.expressionType = v || 'simple'; vm.markModified(); });
		},
		filterExpressionTypeLabel(): string {
			return this.optionLabel(FILTER_EXPRESSION_TYPE_OPTIONS,
				this.selectedProcessor?.properties.expressionType || 'simple', 'Simple');
		},

		openSplitExpressionTypeDropdown(event: MouseEvent) {
			const vm = this;
			const proc = vm.selectedProcessor;
			if (!proc) return;
			vm.openChoicePopup(event, SPLIT_EXPRESSION_TYPE_OPTIONS,
				proc.properties.expressionType || 'simple',
				v => { proc.properties.expressionType = v || 'simple'; vm.markModified(); });
		},
		splitExpressionTypeLabel(): string {
			return this.optionLabel(SPLIT_EXPRESSION_TYPE_OPTIONS,
				this.selectedProcessor?.properties.expressionType || 'simple', 'Simple');
		},

		openTransformExpressionTypeDropdown(event: MouseEvent) {
			const vm = this;
			const proc = vm.selectedProcessor;
			if (!proc) return;
			vm.openChoicePopup(event, TRANSFORM_EXPRESSION_TYPE_OPTIONS,
				proc.properties.expressionType || 'simple',
				v => { proc.properties.expressionType = v || 'simple'; vm.markModified(); });
		},
		transformExpressionTypeLabel(): string {
			return this.optionLabel(TRANSFORM_EXPRESSION_TYPE_OPTIONS,
				this.selectedProcessor?.properties.expressionType || 'simple', 'Simple');
		},

		openMarshalDataFormatDropdown(event: MouseEvent) {
			const vm = this;
			const proc = vm.selectedProcessor;
			if (!proc) return;
			vm.openChoicePopup(event, DATA_FORMAT_OPTIONS,
				proc.properties.dataFormat || 'json',
				v => { proc.properties.dataFormat = v || 'json'; vm.markModified(); });
		},
		openUnmarshalDataFormatDropdown(event: MouseEvent) {
			this.openMarshalDataFormatDropdown(event);
		},
		dataFormatLabel(): string {
			return this.optionLabel(DATA_FORMAT_OPTIONS,
				this.selectedProcessor?.properties.dataFormat || 'json', 'JSON');
		},

		openMarshalLibraryDropdown(event: MouseEvent) {
			const vm = this;
			const proc = vm.selectedProcessor;
			if (!proc) return;
			vm.openChoicePopup(event, JSON_LIBRARY_OPTIONS,
				proc.properties.library || 'jackson',
				v => { proc.properties.library = v || 'jackson'; vm.markModified(); });
		},
		openUnmarshalLibraryDropdown(event: MouseEvent) {
			this.openMarshalLibraryDropdown(event);
		},
		jsonLibraryLabel(): string {
			return this.optionLabel(JSON_LIBRARY_OPTIONS,
				this.selectedProcessor?.properties.library || 'jackson', 'Jackson');
		},

		onCanvasWheel(event: WheelEvent) {
			event.preventDefault();
			if (!this.currentFile) return;

			const delta = event.deltaY > 0 ? 0.9 : 1.1;
			const newScale = Math.max(0.25, Math.min(2, this.currentFile.scale * delta));

			const svg = document.getElementById('camel-canvas') as unknown as SVGSVGElement;
			if (svg) {
				const rect = svg.getBoundingClientRect();
				const mouseX = event.clientX - rect.left;
				const mouseY = event.clientY - rect.top;

				this.currentFile.panOffset.x = mouseX - (mouseX - this.currentFile.panOffset.x) * (newScale / this.currentFile.scale);
				this.currentFile.panOffset.y = mouseY - (mouseY - this.currentFile.panOffset.y) * (newScale / this.currentFile.scale);
			}

			this.currentFile.scale = newScale;
		},

		// =========================================================================
		// Element Events
		// =========================================================================

		onElementMouseDown(event: MouseEvent, shapeId: string, semanticId: string) {
			event.stopPropagation();

			this.selectElement(semanticId);

			// Start dragging
			this.isDragging = true;
			this.draggedShapeId = shapeId;

			if (this.activeStore && this.currentFile) {
				const shape = this.activeStore.getShape(shapeId);
				if (shape) {
					const svg = document.getElementById('camel-canvas') as unknown as SVGSVGElement;
					if (svg) {
						const rect = svg.getBoundingClientRect();
						const x = (event.clientX - rect.left - this.currentFile.panOffset.x) / this.currentFile.scale;
						const y = (event.clientY - rect.top - this.currentFile.panOffset.y) / this.currentFile.scale;
						this.dragOffset.x = x - shape.bounds.x;
						this.dragOffset.y = y - shape.bounds.y;
					}
				}
			}
		},

		/**
		 * Start drawing a connection from output port
		 */
		onOutputPortMouseDown(event: MouseEvent, semanticId: string) {
			event.stopPropagation();
			this.isDrawingConnection = true;
			this.connectionSourceId = semanticId;
		},

		/**
		 * Complete connection on input port
		 */
		onInputPortMouseUp(event: MouseEvent, targetId: string) {
			if (this.isDrawingConnection && this.connectionSourceId && this.activeStore) {
				if (this.connectionSourceId !== targetId) {
					// Check if connection already exists
					const flows = this.activeStore.getAllFlows();
					const exists = flows.some(f => f.sourceRef === this.connectionSourceId! && f.targetRef === targetId);

					if (!exists) {
						const sourceProc = this.activeStore.getProcessor(this.connectionSourceId);
						const flowId = generateUUID();
						const flow: CamelFlowSemantic = {
							id: flowId,
							sourceRef: this.connectionSourceId,
							targetRef: targetId,
							// For Choice node connections, default to 'when' with empty expression
							...(sourceProc?.type === 'choice' && { conditionType: 'when', language: 'simple', expression: '' }),
							// For DoTry node connections, default to 'try' role with default exception
							...(sourceProc?.type === 'doTry' && { role: 'try', exceptions: ['java.lang.Exception'] })
						};
						this.activeStore.addFlow(flow);

						const edge: CamelDiEdge = {
							id: flowId + '_di',
							semanticId: flowId,
							waypoints: []
						};
						this.activeStore.addEdge(edge);

						this.markModified();
						this.storeVersion++;
					}
				}
			}

			this.isDrawingConnection = false;
			this.connectionSourceId = null;
			this.connectionPreview = null;
			this.connectionTargetIds = [];
		},

		/**
		 * Handle connection line click for selection
		 */
		onConnectionMouseDown(event: MouseEvent, flowId: string) {
			event.stopPropagation();
			this.selectedElementId = flowId;
		},

		/**
		 * Delete a connection (flow)
		 */
		deleteConnection(flowId: string) {
			if (!this.activeStore) return;
			this.activeStore.removeFlow(flowId);
			this.selectedElementId = null;
			this.markModified();
			this.storeVersion++;
		},

		// =========================================================================
		// Smart Path Calculation
		// =========================================================================

		selectOptimalPorts(
			source: { x: number; y: number; style: typeof NODE_STYLE },
			target: { x: number; y: number; style: typeof NODE_STYLE }
		): { sourcePort: { x: number; y: number }; targetPort: { x: number; y: number } } {
			const sourceCenterX = source.x + source.style.width / 2;
			const sourceCenterY = source.y + source.style.height / 2;
			const targetCenterX = target.x + target.style.width / 2;
			const targetCenterY = target.y + target.style.height / 2;

			const dx = targetCenterX - sourceCenterX;
			const dy = targetCenterY - sourceCenterY;
			const absDx = Math.abs(dx);
			const absDy = Math.abs(dy);
			const threshold = 30;

			let sourcePortKey: 'left' | 'right' | 'top' | 'bottom';
			let targetPortKey: 'left' | 'right' | 'top' | 'bottom';

			if (absDx > absDy + threshold) {
				if (dx > 0) {
					sourcePortKey = 'right';
					targetPortKey = 'left';
				} else {
					sourcePortKey = 'left';
					targetPortKey = 'right';
				}
			} else if (absDy > absDx + threshold) {
				if (dy > 0) {
					sourcePortKey = 'bottom';
					targetPortKey = 'top';
				} else {
					sourcePortKey = 'top';
					targetPortKey = 'bottom';
				}
			} else {
				if (dx >= 0) {
					sourcePortKey = 'right';
					targetPortKey = 'left';
				} else {
					sourcePortKey = 'left';
					targetPortKey = 'right';
				}
			}

			const sourcePorts = source.style.ports || {
				left: source.style.inputPort,
				right: source.style.outputPort,
				top: { x: source.style.width / 2, y: 0 },
				bottom: { x: source.style.width / 2, y: source.style.height }
			};
			const targetPorts = target.style.ports || {
				left: target.style.inputPort,
				right: target.style.outputPort,
				top: { x: target.style.width / 2, y: 0 },
				bottom: { x: target.style.width / 2, y: target.style.height }
			};

			return {
				sourcePort: {
					x: source.x + sourcePorts[sourcePortKey].x,
					y: source.y + sourcePorts[sourcePortKey].y
				},
				targetPort: {
					x: target.x + targetPorts[targetPortKey].x,
					y: target.y + targetPorts[targetPortKey].y
				}
			};
		},

		createSmartPath(
			sourcePort: { x: number; y: number },
			targetPort: { x: number; y: number }
		): string {
			const dx = targetPort.x - sourcePort.x;
			const dy = targetPort.y - sourcePort.y;

			if (Math.abs(dx) > Math.abs(dy)) {
				const midX = (sourcePort.x + targetPort.x) / 2;
				return `M ${sourcePort.x} ${sourcePort.y} C ${midX} ${sourcePort.y}, ${midX} ${targetPort.y}, ${targetPort.x} ${targetPort.y}`;
			}

			const midY = (sourcePort.y + targetPort.y) / 2;
			return `M ${sourcePort.x} ${sourcePort.y} C ${sourcePort.x} ${midY}, ${targetPort.x} ${midY}, ${targetPort.x} ${targetPort.y}`;
		},

		/**
		 * Get connection label position (midpoint of the path)
		 */
		getConnectionLabelPosition(pathData: string): { x: number; y: number } {
			// Parse the path to get start and end points
			// Path format: M x1 y1 C cx1 cy1, cx2 cy2, x2 y2
			const match = pathData.match(/M\s+([\d.]+)\s+([\d.]+).*?([\d.]+)\s+([\d.]+)$/);
			if (match) {
				const x1 = parseFloat(match[1]);
				const y1 = parseFloat(match[2]);
				const x2 = parseFloat(match[3]);
				const y2 = parseFloat(match[4]);
				return {
					x: (x1 + x2) / 2,
					y: (y1 + y2) / 2 - 8 // Offset above the line
				};
			}
			return { x: 0, y: 0 };
		},

		/**
		 * Get connection preview path
		 */
		getConnectionPreviewPath(): string {
			if (!this.connectionSourceId || !this.connectionPreview || !this.activeStore) return '';

			const sourceShape = this.activeStore.getShapeForSemantic(this.connectionSourceId);
			if (!sourceShape) return '';

			const sourceSemantic = this.activeStore.getProcessor(this.connectionSourceId);
			const sourceStyle = sourceSemantic?.type === 'from' ? FROM_NODE_STYLE : getNodeStyleForType(sourceSemantic?.type || '');

			const sourceX = sourceShape.bounds.x + sourceStyle.outputPort.x;
			const sourceY = sourceShape.bounds.y + sourceStyle.outputPort.y;

			return this.createSmartPath(
				{ x: sourceX, y: sourceY },
				this.connectionPreview
			);
		},

		// =========================================================================
		// Keyboard Shortcuts
		// =========================================================================

		onKeyDown(event: KeyboardEvent) {
			const target = event.target as HTMLElement;
			const isInputField = target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT';

			// Ctrl+S: Save
			if (event.ctrlKey && event.key === 's') {
				event.preventDefault();
				this.saveFile();
				return;
			}

			if (isInputField) return;

			// Space: hold to enable canvas panning (alongside Alt+drag).
			// preventDefault stops the page-scroll fallback when the canvas
			// or document is the focus owner.
			if (event.key === ' ' || event.code === 'Space') {
				event.preventDefault();
				if (!event.repeat) this.isSpaceHeld = true;
				return;
			}

			// Ctrl+Z: Undo
			if (event.ctrlKey && event.key === 'z') {
				event.preventDefault();
				this.undo();
				return;
			}

			// Ctrl+Y: Redo
			if (event.ctrlKey && event.key === 'y') {
				event.preventDefault();
				this.redo();
				return;
			}

			// Delete: Delete selected
			if (event.key === 'Delete') {
				this.deleteSelected();
				return;
			}
		},

		onKeyUp(event: KeyboardEvent) {
			if (event.key === ' ' || event.code === 'Space') {
				this.isSpaceHeld = false;
			}
		},

		// Reset space-held flag if focus leaves the window mid-press
		// (e.g. user alt-tabs away while holding Space). Otherwise the
		// keyup we never received would leave the editor stuck in pan mode.
		onWindowBlur() {
			this.isSpaceHeld = false;
		},

		// =========================================================================
		// Zoom Controls
		// =========================================================================

		zoomIn() {
			if (this.currentFile) {
				this.currentFile.scale = Math.min(2, this.currentFile.scale * 1.2);
			}
		},

		zoomOut() {
			if (this.currentFile) {
				this.currentFile.scale = Math.max(0.25, this.currentFile.scale / 1.2);
			}
		},

		fitToScreen() {
			if (this.currentFile) {
				this.currentFile.scale = 1;
				this.currentFile.panOffset = { x: 0, y: 0 };
			}
		},

		/**
		 * Perform automatic layout of all nodes using tree-based layout algorithm.
		 * Uses DFS to traverse the flow graph and assigns Y coordinates based on
		 * subtree sizes, ensuring branches don't overlap.
		 * Groups nodes by their root (from/onException) to create separate lanes.
		 */
		autoLayout() {
			if (!this.activeStore) return;

			// Save state for undo before making changes
			this.saveState();

			const processors = this.activeStore.getAllProcessors();
			const flows = this.activeStore.getAllFlows();
			const rootNodes = processors.filter(p => p.type === 'from' || p.type === 'onException');

			// Sort root nodes: onException first, then from nodes
			rootNodes.sort((a, _b) => (a.type === 'onException' ? -1 : 1));

			let currentBaseY = 100;
			const startX = 100;
			const spacingX = 220;
			const spacingY = 100;
			const routeGap = 200;

			const globalAssignedNodes = new Set<string>();

			rootNodes.forEach((root) => {
				// Track nodes belonging to this route and their positions
				const nodePositions = new Map<string, { x: number; y: number }>();

				// Get children of a node (outgoing flows)
				// For branching nodes (Choice, DoTry), sort by condition/role type
				const getChildren = (nodeId: string): string[] => {
					const node = this.activeStore!.getProcessor(nodeId);
					let childFlows = flows.filter((f: CamelFlowSemantic) => f.sourceRef === nodeId);

					// Sort flows based on node type
					if (node?.type === 'choice') {
						// Sort: when conditions first, otherwise last
						childFlows.sort((a: CamelFlowSemantic, b: CamelFlowSemantic) => {
							if (a.conditionType === 'otherwise') return 1;
							if (b.conditionType === 'otherwise') return -1;
							return 0;
						});
					} else if (node?.type === 'doTry') {
						// Sort: try first, catch second, finally last
						const roleOrder: Record<string, number> = { 'try': 0, 'catch': 1, 'finally': 2 };
						childFlows.sort((a: CamelFlowSemantic, b: CamelFlowSemantic) => {
							const aOrder = roleOrder[a.role || 'try'] ?? 0;
							const bOrder = roleOrder[b.role || 'try'] ?? 0;
							return aOrder - bOrder;
						});
					}

					return childFlows.map((f: CamelFlowSemantic) => f.targetRef);
				};

				// Count how many "leaf slots" this subtree needs
				// A leaf slot is the vertical space needed for one branch endpoint
				const countSubtreeSlots = (nodeId: string, visited: Set<string>): number => {
					if (visited.has(nodeId)) return 0;
					visited.add(nodeId);

					const children = getChildren(nodeId);
					if (children.length === 0) {
						return 1; // Leaf node takes 1 slot
					}

					let totalSlots = 0;
					for (const child of children) {
						totalSlots += countSubtreeSlots(child, visited);
					}
					return Math.max(1, totalSlots);
				};

				// Calculate rank (X position) for each node using BFS
				// Merge points get the maximum depth from all incoming paths
				const nodeRanks = new Map<string, number>();
				const queue: { id: string; rank: number }[] = [{ id: root.id, rank: 0 }];

				while (queue.length > 0) {
					const { id, rank } = queue.shift()!;
					const currentRank = nodeRanks.get(id) || 0;

					if (rank >= currentRank) {
						nodeRanks.set(id, rank);
						for (const child of getChildren(id)) {
							queue.push({ id: child, rank: rank + 1 });
						}
					}
				}

				// Check if a node is a branching container (Choice, DoTry)
				const isBranchingNode = (nodeId: string): boolean => {
					const node = this.activeStore!.getProcessor(nodeId);
					return node?.type === 'choice' || node?.type === 'doTry';
				};

				// DFS to assign Y positions based on subtree sizes
				const assignPositions = (
					nodeId: string,
					rank: number,
					yStart: number,
					visited: Set<string>
				): number => {
					if (visited.has(nodeId)) {
						// Already positioned, return the Y we assigned before
						const pos = nodePositions.get(nodeId);
						return pos ? pos.y + spacingY : yStart;
					}
					visited.add(nodeId);
					globalAssignedNodes.add(nodeId);

					const actualRank = nodeRanks.get(nodeId) || rank;
					const children = getChildren(nodeId);

					if (children.length === 0) {
						// Leaf node: place at yStart
						nodePositions.set(nodeId, {
							x: startX + actualRank * spacingX,
							y: yStart
						});
						return yStart + spacingY;
					}

					// Calculate slot counts for each child's subtree
					// For branching nodes (Choice, DoTry), each branch gets independent slot counting
					const childSlots: { child: string; slots: number }[] = [];
					const isBranching = isBranchingNode(nodeId);

					for (const child of children) {
						// For branching nodes, each branch should count independently
						// Don't share visited set across branches to ensure each gets at least 1 slot
						const visitedForCount = isBranching ? new Set<string>() : new Set(visited);
						const slots = countSubtreeSlots(child, visitedForCount);
						childSlots.push({ child, slots: Math.max(1, slots) });
					}

					// Position children, accumulating Y offset
					let currentY = yStart;
					const childYPositions: number[] = [];

					// For branching nodes, we need to handle merge points specially
					// Each branch should get its own Y space, even if they converge to same merge
					if (isBranching) {
						// Take snapshot of visited before processing branches
						const visitedSnapshot = new Set(visited);

						for (const { child, slots } of childSlots) {
							const childY = currentY;
							childYPositions.push(childY + (slots - 1) * spacingY / 2);
							// Each branch starts from the snapshot, not accumulated visited
							const branchVisited = new Set(visitedSnapshot);
							assignPositions(child, actualRank + 1, childY, branchVisited);
							// Merge the branch's visited nodes into main visited set
							branchVisited.forEach(id => visited.add(id));
							// Advance Y by slots, not by what assignPositions returned (which may be wrong for shared merge)
							currentY = childY + slots * spacingY;
						}
					} else {
						for (const { child, slots } of childSlots) {
							const childY = currentY;
							childYPositions.push(childY + (slots - 1) * spacingY / 2);
							currentY = assignPositions(child, actualRank + 1, childY, visited);
						}
					}

					// Position this node at the center of its children's Y range
					const minChildY = Math.min(...childYPositions);
					const maxChildY = Math.max(...childYPositions);
					const nodeY = (minChildY + maxChildY) / 2;

					nodePositions.set(nodeId, {
						x: startX + actualRank * spacingX,
						y: nodeY
					});

					return currentY;
				};

				// Run layout for this route
				const routeEndY = assignPositions(root.id, 0, currentBaseY, new Set());

				// Apply positions to shapes
				nodePositions.forEach((pos, nodeId) => {
					const shape = this.activeStore!.getShapeForSemantic(nodeId);
					if (shape) {
						this.activeStore!.updateShape(shape.id, {
							bounds: {
								...shape.bounds,
								x: pos.x,
								y: pos.y
							}
						});
					}
				});

				// Update base Y for next route
				currentBaseY = routeEndY + routeGap;
			});

			// Handle orphan nodes (nodes not connected to any root)
			processors.forEach(proc => {
				if (!globalAssignedNodes.has(proc.id)) {
					const shape = this.activeStore!.getShapeForSemantic(proc.id);
					if (shape) {
						this.activeStore!.updateShape(shape.id, {
							bounds: { ...shape.bounds, x: startX, y: currentBaseY }
						});
						currentBaseY += spacingY;
					}
				}
			});

			this.markModified();
			this.storeVersion++;
			this.fitToScreen();
		},

		// =========================================================================
		// Undo/Redo
		// =========================================================================

		saveState() {
			if (!this.currentFile || !this.activeStore) return;

			// Serialize store state
			const state = JSON.stringify({
				processors: this.activeStore.getAllProcessors(),
				flows: this.activeStore.getAllFlows(),
				shapes: this.activeStore.getAllShapes(),
				edges: this.activeStore.getAllEdges()
			});

			this.currentFile.undoStack.push(state);
			this.currentFile.redoStack = [];

			if (this.currentFile.undoStack.length > 50) {
				this.currentFile.undoStack.shift();
			}
		},

		undo() {
			if (!this.currentFile || this.currentFile.undoStack.length === 0 || !this.activeStore) return;

			// Save current state for redo
			const currentState = JSON.stringify({
				processors: this.activeStore.getAllProcessors(),
				flows: this.activeStore.getAllFlows(),
				shapes: this.activeStore.getAllShapes(),
				edges: this.activeStore.getAllEdges()
			});
			this.currentFile.redoStack.push(currentState);

			// Restore previous state
			const previousState = JSON.parse(this.currentFile.undoStack.pop()!);
			this.restoreStoreState(previousState);
		},

		redo() {
			if (!this.currentFile || this.currentFile.redoStack.length === 0 || !this.activeStore) return;

			// Save current state for undo
			const currentState = JSON.stringify({
				processors: this.activeStore.getAllProcessors(),
				flows: this.activeStore.getAllFlows(),
				shapes: this.activeStore.getAllShapes(),
				edges: this.activeStore.getAllEdges()
			});
			this.currentFile.undoStack.push(currentState);

			// Restore next state
			const nextState = JSON.parse(this.currentFile.redoStack.pop()!);
			this.restoreStoreState(nextState);
		},

		restoreStoreState(state: any) {
			if (!this.activeStore) return;

			this.activeStore.clear();

			for (const proc of state.processors) {
				this.activeStore.addProcessor(proc);
			}
			for (const flow of state.flows) {
				this.activeStore.addFlow(flow);
			}
			for (const shape of state.shapes) {
				this.activeStore.addShape(shape);
			}
			for (const edge of state.edges) {
				this.activeStore.addEdge(edge);
			}

			this.selectedElementId = null;
			this.storeVersion++;
		},

		markModified() {
			if (!this.currentFile) return;

			if (!this.currentFile.isModified) {
				this.saveState();
			}
			this.currentFile.isModified = true;
		},

		// =========================================================================
		// Close Confirmation
		// =========================================================================

		async confirmClose(): Promise<boolean> {
			const hasModified = this.files.some(f => f.isModified);
			if (!hasModified) return true;

			const result = await this.showCloseConfirmDialog();

			if (result === 'cancel') return false;

			if (result === 'save') {
				for (let i = 0; i < this.files.length; i++) {
					if (this.files[i].isModified) {
						if (i !== this.currentFileIndex) {
							this.currentFileIndex = i;
							this.storeVersion++;
						}
						await this.saveFile();
						if (this.files[i]?.isModified) return false;
					}
				}
			}

			return true;
		},

		showCloseConfirmDialog(): Promise<'save' | 'discard' | 'cancel'> {
			this.closeConfirmDialog.visible = true;
			return new Promise((resolve) => {
				this.closeConfirmDialog.resolve = resolve;
			});
		},

		onCloseConfirmDialogAction(action: 'save' | 'discard' | 'cancel') {
			if (this.closeConfirmDialog.resolve) {
				this.closeConfirmDialog.resolve(action);
			}
			this.closeConfirmDialog.visible = false;
			this.closeConfirmDialog.resolve = null;
		},

		// =========================================================================
		// Property Panel Helpers
		// =========================================================================

		/**
		 * Update selected element property
		 */
		updateProperty(key: string, event: Event) {
			if (!this.selectedElementId || !this.activeStore) return;

			const semantic = this.activeStore.getProcessor(this.selectedElementId);
			if (!semantic) return;

			const target = event.target as HTMLInputElement | HTMLSelectElement;
			semantic.properties[key] = target.value;
			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Update selected element boolean property
		 */
		updatePropertyBoolean(key: string, event: Event) {
			if (!this.selectedElementId || !this.activeStore) return;

			const semantic = this.activeStore.getProcessor(this.selectedElementId);
			if (!semantic) return;

			const target = event.target as HTMLInputElement;
			semantic.properties[key] = target.checked;
			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Update flow (connection) property
		 */
		updateFlowProperty(key: string, event: Event) {
			if (!this.selectedElementId || !this.activeStore) return;

			const flow = this.activeStore.getFlow(this.selectedElementId);
			if (!flow) return;

			const target = event.target as HTMLInputElement | HTMLSelectElement;
			(flow as any)[key] = target.value;
			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Update exceptions array on a flow (for doTry catch flows)
		 */
		updateFlowExceptions(event: Event) {
			if (!this.selectedElementId || !this.activeStore) return;

			const flow = this.activeStore.getFlow(this.selectedElementId);
			if (!flow) return;

			const target = event.target as HTMLInputElement;
			// Convert comma-separated string to array, trimming whitespace
			flow.exceptions = target.value.split(',').map(s => s.trim()).filter(s => s !== '');

			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Update exception list property (comma-separated string to array)
		 */
		updateExceptionList(key: string, event: Event) {
			if (!this.selectedElementId || !this.activeStore) return;
			const semantic = this.activeStore.getProcessor(this.selectedElementId);
			if (!semantic) return;

			const target = event.target as HTMLInputElement;
			// Convert comma-separated string to array, trimming whitespace
			semantic.properties[key] = target.value.split(',').map(s => s.trim()).filter(s => s !== '');

			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Update numeric property
		 */
		updatePropertyNumber(key: string, event: Event) {
			if (!this.selectedElementId || !this.activeStore) return;
			const semantic = this.activeStore.getProcessor(this.selectedElementId);
			if (!semantic) return;

			const target = event.target as HTMLInputElement;
			semantic.properties[key] = parseInt(target.value, 10) || 0;

			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Update CircuitBreaker resilience4j configuration property
		 */
		updateCircuitBreakerProperty(key: string, event: Event) {
			if (!this.selectedElementId || !this.activeStore) return;
			const semantic = this.activeStore.getProcessor(this.selectedElementId);
			if (!semantic) return;

			const target = event.target as HTMLInputElement;
			if (!semantic.properties.resilience4jConfiguration) {
				semantic.properties.resilience4jConfiguration = {};
			}
			semantic.properties.resilience4jConfiguration[key] = parseInt(target.value, 10) || 0;

			this.markModified();
			this.storeVersion++;
		},

		// =========================================================================
		// URI Parameter Management
		// =========================================================================

		/**
		 * Add a new parameter to the selected processor's parameters object
		 */
		addParameter() {
			if (!this.selectedProcessor) return;
			if (!this.selectedProcessor.properties.parameters) {
				this.selectedProcessor.properties.parameters = {};
			}
			// Create a unique default key
			let newKey = 'param';
			let counter = 1;
			while (this.selectedProcessor.properties.parameters[newKey]) {
				newKey = `param${counter++}`;
			}
			this.selectedProcessor.properties.parameters[newKey] = '';
			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Update the value of a parameter
		 */
		updateParameterValue(key: string, event: Event) {
			if (!this.selectedProcessor?.properties.parameters) return;
			const target = event.target as HTMLInputElement;
			this.selectedProcessor.properties.parameters[key] = target.value;
			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Rename a parameter key while preserving its value
		 */
		renameParameter(oldKey: string, event: Event) {
			if (!this.selectedProcessor?.properties.parameters) return;
			const newKey = (event.target as HTMLInputElement).value;
			if (!newKey || newKey === oldKey) return;

			const value = this.selectedProcessor.properties.parameters[oldKey];
			delete this.selectedProcessor.properties.parameters[oldKey];
			this.selectedProcessor.properties.parameters[newKey] = value;

			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Remove a parameter from the selected processor
		 */
		removeParameter(key: string) {
			if (!this.selectedProcessor?.properties.parameters) return;
			delete this.selectedProcessor.properties.parameters[key];
			this.markModified();
			this.storeVersion++;
		},

		/**
		 * Get parameters object for the selected processor (for template reactivity)
		 */
		getParameters(): Record<string, string> {
			const _version = this.storeVersion; // Trigger reactivity
			return this.selectedProcessor?.properties?.parameters || {};
		},

		/**
		 * Check if the selected processor has any parameters
		 */
		hasParameters(): boolean {
			const params = this.getParameters();
			return Object.keys(params).length > 0;
		},

		/**
		 * Get parameter keys as array (for iteration in template)
		 */
		getParameterKeys(): string[] {
			const _version = this.storeVersion; // Trigger reactivity
			return Object.keys(this.selectedProcessor?.properties?.parameters || {});
		},

		/**
		 * Get parameter value by key
		 */
		getParameterValue(key: string): string {
			const _version = this.storeVersion; // Trigger reactivity
			return this.selectedProcessor?.properties?.parameters?.[key] || '';
		},

		/**
		 * Get label for a flow (connection)
		 */
		getFlowLabel(flow: CamelFlowSemantic): string {
			if (flow.conditionType === 'when') {
				return `When: ${flow.expression || '(no condition)'}`;
			}
			if (flow.conditionType === 'otherwise') {
				return 'Otherwise';
			}
			return 'Connection';
		},

		/**
		 * Get label for a processor
		 */
		getProcessorLabel(semantic: CamelProcessorSemantic): string {
			const props = semantic.properties;
			switch (semantic.type) {
				case 'from':
					return props.uri || 'From';
				case 'to':
					return props.uri || 'To';
				case 'toD':
					return props.uri || 'To D';
				case 'log':
					return 'Log';
				case 'setBody':
					return 'Set Body';
				case 'setHeader':
					return props.name || 'Set Header';
				case 'choice':
					return 'Choice';
				case 'filter':
					return 'Filter';
				case 'split':
					return 'Split';
				case 'delay':
					return `Delay ${props.constant || ''}ms`;
				case 'bean':
					return props.ref || 'Bean';
				case 'stop':
					return 'Stop';
				case 'merge':
					return 'Merge';
				case 'onException':
					const exceptions = props.exceptions || ['Exception'];
					return `onException (${exceptions[0]?.split('.').pop() || 'Exception'})`;
				default:
					return semantic.type;
			}
		},

		/**
		 * Get short label for display on node
		 */
		getShortLabel(semantic: CamelProcessorSemantic): string {
			const props = semantic.properties;
			// User-authored step ID takes precedence — matches what
			// MessageHistory will show in logs.
			if (props.id) {
				const idStr = String(props.id);
				return idStr.length > 15 ? idStr.substring(0, 15) + '...' : idStr;
			}
			switch (semantic.type) {
				case 'from':
					const fromUri = props.uri || '';
					return fromUri.length > 15 ? fromUri.substring(0, 15) + '...' : fromUri;
				case 'to':
				case 'toD': {
					const toUri = props.uri || '';
					return toUri.length > 12 ? toUri.substring(0, 12) + '...' : toUri;
				}
				case 'log':
					return props.loggingLevel || 'INFO';
				case 'setBody':
					return props.simple ? '${...}' : 'Set Body';
				case 'setHeader':
					return props.name?.substring(0, 10) || 'Header';
				case 'delay':
					return `${props.constant || '1000'}ms`;
				case 'bean':
					return props.ref?.substring(0, 10) || 'Bean';
				case 'merge':
					return '';  // Merge node shows only the icon
				case 'onException':
					// Show the short class name of the first exception
					const exceptions = props.exceptions || ['Exception'];
					const firstEx = exceptions[0] || 'Exception';
					const shortName = firstEx.split('.').pop() || firstEx;
					return shortName.length > 12 ? shortName.substring(0, 12) + '...' : shortName;
				case 'aggregate':
					return props.correlationExpression ? props.correlationExpression.substring(0, 12) : 'Aggregate';
				case 'transform':
					return props.expression ? '${...}' : 'Transform';
				case 'wireTap':
				case 'enrich':
				case 'pollEnrich':
					const uri = props.uri || '';
					return uri.length > 12 ? uri.substring(0, 12) + '...' : uri;
				case 'filter':
					return props.expression ? props.expression.substring(0, 12) : 'Filter';
				case 'split':
					return props.expression ? props.expression.substring(0, 12) : 'Split';
				default:
					return semantic.type;
			}
		},

		/**
		 * Find the route root (from / onException) that owns the given processor,
		 * by walking outgoing flows. Returns null if the processor isn't connected
		 * to any root.
		 */
		getRouteRootOf(procId: string): string | null {
			if (!this.activeStore) return null;
			const store = this.activeStore;
			const flows = store.getAllFlows();
			const outgoing = new Map<string, string[]>();
			flows.forEach((f: CamelFlowSemantic) => {
				if (!outgoing.has(f.sourceRef)) outgoing.set(f.sourceRef, []);
				outgoing.get(f.sourceRef)!.push(f.targetRef);
			});
			const roots = store.getAllProcessors().filter((p: CamelProcessorSemantic) =>
				p.type === 'from' || p.type === 'onException'
			);
			for (const root of roots) {
				const queue: string[] = [root.id];
				const visited = new Set<string>();
				while (queue.length > 0) {
					const cur = queue.shift()!;
					if (visited.has(cur)) continue;
					visited.add(cur);
					if (cur === procId) return root.id;
					(outgoing.get(cur) || []).forEach((t: string) => queue.push(t));
				}
			}
			return null;
		},

		/**
		 * Return a field-level error message for the step ID input, or '' when valid.
		 * Empty IDs are valid (the attribute is optional).
		 */
		getStepIdError(proc: CamelProcessorSemantic): string {
			if (!this.activeStore || !proc) return '';
			const idVal = (proc.properties?.id || '').toString().trim();
			if (!idVal) return '';
			if (/\s/.test(idVal)) return 'ID must not contain whitespace';
			const route = this.getRouteRootOf(proc.id);
			const dup = this.activeStore.getAllProcessors().some((p: CamelProcessorSemantic) =>
				p.id !== proc.id &&
				(p.properties?.id || '').toString().trim() === idVal &&
				this.getRouteRootOf(p.id) === route
			);
			return dup ? `Duplicate ID '${idVal}' in this route` : '';
		},

		/**
		 * Validate all processors in the active store.
		 * Returns a Map of processorId -> error message.
		 */
		getValidationErrors(): Map<string, string> {
			const errors = new Map<string, string>();
			if (!this.activeStore) return errors;

			const processors = this.activeStore.getAllProcessors();
			const flows = this.activeStore.getAllFlows();

			processors.forEach((proc: CamelProcessorSemantic) => {
				const props = proc.properties;
				const outgoing = flows.filter((f: CamelFlowSemantic) => f.sourceRef === proc.id);

				switch (proc.type) {
					case 'from':
					case 'to':
					case 'toD':
					case 'wireTap':
						if (!props.uri) errors.set(proc.id, 'URI is required');
						break;
					case 'log':
						if (!props.message) errors.set(proc.id, 'Log message is required');
						break;
					case 'setHeader':
					case 'setProperty':
					case 'setVariable':
						if (!props.name) errors.set(proc.id, 'Name is required');
						break;
					case 'choice':
						if (!outgoing.some((f: CamelFlowSemantic) => f.conditionType === 'when')) {
							errors.set(proc.id, 'Choice requires at least one "When" branch');
						}
						break;
					case 'aggregate':
						if (!props.correlationExpression) errors.set(proc.id, 'Correlation Expression is required');
						if (!props.aggregationStrategy) errors.set(proc.id, 'Aggregation Strategy is required');
						break;
					case 'split':
					case 'recipientList':
					case 'filter':
						if (!props.expression && !props.simple) errors.set(proc.id, 'Expression is required');
						break;
					case 'doTry':
						if (!outgoing.some((f: CamelFlowSemantic) => f.role === 'catch')) {
							errors.set(proc.id, 'doTry usually requires at least one "Catch" branch');
						}
						break;
					case 'bean':
						if (!props.ref && !props.beanType) errors.set(proc.id, 'Bean reference or type is required');
						break;
					case 'enrich':
					case 'pollEnrich':
						if (!props.uri) errors.set(proc.id, 'URI is required');
						break;
				}
			});

			// Duplicate step ID detection (per route). Only fires when the
			// processor has no other validation error, so URI/message issues
			// stay surfaced first.
			processors.forEach((proc: CamelProcessorSemantic) => {
				if (errors.has(proc.id)) return;
				const idErr = this.getStepIdError(proc);
				if (idErr) errors.set(proc.id, idErr);
			});

			return errors;
		},

		/**
		 * Check if a processor has a validation error
		 */
		hasValidationError(semanticId: string): boolean {
			return this.getValidationErrors().has(semanticId);
		},

		/**
		 * Get validation error message for a processor
		 */
		getValidationError(semanticId: string): string {
			return this.getValidationErrors().get(semanticId) || '';
		},

		/**
		 * Check if an element is selected
		 */
		isSelected(semanticId: string): boolean {
			return this.selectedElementId === semanticId;
		},
	},
};

// Mount the app
VDOM.createApp(App).mount('#app');

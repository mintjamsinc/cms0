// =============================================================================
// Element configuration: sizes, subtypes, connection points
// =============================================================================
// Pure data tables and the small helpers that read them. Imported by both
// the editor view-model (app.ts) and the XML parser/serializer modules.
// =============================================================================

import { generateUUID } from './bpmn-model-types.js';
import type { BpmnElement, SubTypeOption } from './element-types.js';

/**
 * Default geometry (width / height) for each shape type. The XML parser
 * uses these as a fallback when a BPMNShape lacks Bounds; the serializer
 * uses them to round-trip elements that were created without explicit
 * sizes (e.g. dropped from the palette).
 */
export const ELEMENT_SIZES: Record<string, { width: number; height: number }> = {
	startEvent: { width: 36, height: 36 },
	endEvent: { width: 36, height: 36 },
	intermediateEvent: { width: 36, height: 36 },
	boundaryEvent: { width: 36, height: 36 },
	userTask: { width: 100, height: 80 },
	serviceTask: { width: 100, height: 80 },
	scriptTask: { width: 100, height: 80 },
	manualTask: { width: 100, height: 80 },
	sendTask: { width: 100, height: 80 },
	receiveTask: { width: 100, height: 80 },
	businessRuleTask: { width: 100, height: 80 },
	callActivity: { width: 100, height: 80 },
	task: { width: 100, height: 80 },
	exclusiveGateway: { width: 50, height: 50 },
	parallelGateway: { width: 50, height: 50 },
	inclusiveGateway: { width: 50, height: 50 },
	eventBasedGateway: { width: 50, height: 50 },
	complexGateway: { width: 50, height: 50 },
	subProcess: { width: 100, height: 80 },
	expandedSubProcess: { width: 300, height: 200 },
	transaction: { width: 300, height: 200 },
	dataObject: { width: 36, height: 50 },
	dataStore: { width: 50, height: 50 },
	textAnnotation: { width: 100, height: 50 },
	pool: { width: 600, height: 200 },
	lane: { width: 570, height: 100 },
};

/**
 * Available subtypes for each element type, used by the type-change menu.
 * Order is the order shown in the menu.
 */
export const ELEMENT_SUBTYPES: Record<string, SubTypeOption[]> = {
	startEvent: [
		{ label: 'None', subType: 'none' },
		{ label: 'Message', subType: 'message', icon: 'message' },
		{ label: 'Timer', subType: 'timer', icon: 'timer' },
		{ label: 'Conditional', subType: 'conditional', icon: 'conditional' },
		{ label: 'Signal', subType: 'signal', icon: 'signal' },
	],
	endEvent: [
		{ label: 'None', subType: 'none' },
		{ label: 'Message', subType: 'message', icon: 'message-filled' },
		{ label: 'Terminate', subType: 'terminate', icon: 'terminate' },
		{ label: 'Error', subType: 'error', icon: 'error' },
		{ label: 'Escalation', subType: 'escalation', icon: 'escalation' },
		{ label: 'Signal', subType: 'signal', icon: 'signal-filled' },
	],
	userTask: [
		{ label: 'User', subType: 'user', icon: 'user' },
		{ label: 'Service', subType: 'service', icon: 'service' },
		{ label: 'Script', subType: 'script', icon: 'script' },
		{ label: 'Manual', subType: 'manual', icon: 'manual' },
		{ label: 'Send', subType: 'send', icon: 'send' },
		{ label: 'Receive', subType: 'receive', icon: 'receive' },
		{ label: 'Business Rule', subType: 'businessRule', icon: 'businessRule' },
		{ label: 'Call Activity', subType: 'callActivity', icon: 'callActivity' },
	],
	serviceTask: [
		{ label: 'User', subType: 'user', icon: 'user' },
		{ label: 'Service', subType: 'service', icon: 'service' },
		{ label: 'Script', subType: 'script', icon: 'script' },
		{ label: 'Manual', subType: 'manual', icon: 'manual' },
		{ label: 'Send', subType: 'send', icon: 'send' },
		{ label: 'Receive', subType: 'receive', icon: 'receive' },
		{ label: 'Business Rule', subType: 'businessRule', icon: 'businessRule' },
		{ label: 'Call Activity', subType: 'callActivity', icon: 'callActivity' },
	],
	scriptTask: [
		{ label: 'User', subType: 'user', icon: 'user' },
		{ label: 'Service', subType: 'service', icon: 'service' },
		{ label: 'Script', subType: 'script', icon: 'script' },
		{ label: 'Manual', subType: 'manual', icon: 'manual' },
		{ label: 'Send', subType: 'send', icon: 'send' },
		{ label: 'Receive', subType: 'receive', icon: 'receive' },
		{ label: 'Business Rule', subType: 'businessRule', icon: 'businessRule' },
		{ label: 'Call Activity', subType: 'callActivity', icon: 'callActivity' },
	],
	manualTask: [
		{ label: 'User', subType: 'user', icon: 'user' },
		{ label: 'Service', subType: 'service', icon: 'service' },
		{ label: 'Script', subType: 'script', icon: 'script' },
		{ label: 'Manual', subType: 'manual', icon: 'manual' },
		{ label: 'Send', subType: 'send', icon: 'send' },
		{ label: 'Receive', subType: 'receive', icon: 'receive' },
		{ label: 'Business Rule', subType: 'businessRule', icon: 'businessRule' },
		{ label: 'Call Activity', subType: 'callActivity', icon: 'callActivity' },
	],
	sendTask: [
		{ label: 'User', subType: 'user', icon: 'user' },
		{ label: 'Service', subType: 'service', icon: 'service' },
		{ label: 'Script', subType: 'script', icon: 'script' },
		{ label: 'Manual', subType: 'manual', icon: 'manual' },
		{ label: 'Send', subType: 'send', icon: 'send' },
		{ label: 'Receive', subType: 'receive', icon: 'receive' },
		{ label: 'Business Rule', subType: 'businessRule', icon: 'businessRule' },
		{ label: 'Call Activity', subType: 'callActivity', icon: 'callActivity' },
	],
	receiveTask: [
		{ label: 'User', subType: 'user', icon: 'user' },
		{ label: 'Service', subType: 'service', icon: 'service' },
		{ label: 'Script', subType: 'script', icon: 'script' },
		{ label: 'Manual', subType: 'manual', icon: 'manual' },
		{ label: 'Send', subType: 'send', icon: 'send' },
		{ label: 'Receive', subType: 'receive', icon: 'receive' },
		{ label: 'Business Rule', subType: 'businessRule', icon: 'businessRule' },
		{ label: 'Call Activity', subType: 'callActivity', icon: 'callActivity' },
	],
	businessRuleTask: [
		{ label: 'User', subType: 'user', icon: 'user' },
		{ label: 'Service', subType: 'service', icon: 'service' },
		{ label: 'Script', subType: 'script', icon: 'script' },
		{ label: 'Manual', subType: 'manual', icon: 'manual' },
		{ label: 'Send', subType: 'send', icon: 'send' },
		{ label: 'Receive', subType: 'receive', icon: 'receive' },
		{ label: 'Business Rule', subType: 'businessRule', icon: 'businessRule' },
		{ label: 'Call Activity', subType: 'callActivity', icon: 'callActivity' },
	],
	callActivity: [
		{ label: 'User', subType: 'user', icon: 'user' },
		{ label: 'Service', subType: 'service', icon: 'service' },
		{ label: 'Script', subType: 'script', icon: 'script' },
		{ label: 'Manual', subType: 'manual', icon: 'manual' },
		{ label: 'Send', subType: 'send', icon: 'send' },
		{ label: 'Receive', subType: 'receive', icon: 'receive' },
		{ label: 'Business Rule', subType: 'businessRule', icon: 'businessRule' },
		{ label: 'Call Activity', subType: 'callActivity', icon: 'callActivity' },
	],
	task: [
		{ label: 'User', subType: 'user', icon: 'user' },
		{ label: 'Service', subType: 'service', icon: 'service' },
		{ label: 'Script', subType: 'script', icon: 'script' },
		{ label: 'Manual', subType: 'manual', icon: 'manual' },
		{ label: 'Send', subType: 'send', icon: 'send' },
		{ label: 'Receive', subType: 'receive', icon: 'receive' },
		{ label: 'Business Rule', subType: 'businessRule', icon: 'businessRule' },
		{ label: 'Call Activity', subType: 'callActivity', icon: 'callActivity' },
	],
	exclusiveGateway: [
		{ label: 'Exclusive', subType: 'exclusive', icon: 'exclusive' },
		{ label: 'Parallel', subType: 'parallel', icon: 'parallel' },
		{ label: 'Inclusive', subType: 'inclusive', icon: 'inclusive' },
		{ label: 'Event-based', subType: 'eventBased', icon: 'eventBased' },
		{ label: 'Complex', subType: 'complex', icon: 'complex' },
	],
	parallelGateway: [
		{ label: 'Exclusive', subType: 'exclusive', icon: 'exclusive' },
		{ label: 'Parallel', subType: 'parallel', icon: 'parallel' },
		{ label: 'Inclusive', subType: 'inclusive', icon: 'inclusive' },
		{ label: 'Event-based', subType: 'eventBased', icon: 'eventBased' },
		{ label: 'Complex', subType: 'complex', icon: 'complex' },
	],
	inclusiveGateway: [
		{ label: 'Exclusive', subType: 'exclusive', icon: 'exclusive' },
		{ label: 'Parallel', subType: 'parallel', icon: 'parallel' },
		{ label: 'Inclusive', subType: 'inclusive', icon: 'inclusive' },
		{ label: 'Event-based', subType: 'eventBased', icon: 'eventBased' },
		{ label: 'Complex', subType: 'complex', icon: 'complex' },
	],
	eventBasedGateway: [
		{ label: 'Exclusive', subType: 'exclusive', icon: 'exclusive' },
		{ label: 'Parallel', subType: 'parallel', icon: 'parallel' },
		{ label: 'Inclusive', subType: 'inclusive', icon: 'inclusive' },
		{ label: 'Event-based', subType: 'eventBased', icon: 'eventBased' },
		{ label: 'Complex', subType: 'complex', icon: 'complex' },
	],
	complexGateway: [
		{ label: 'Exclusive', subType: 'exclusive', icon: 'exclusive' },
		{ label: 'Parallel', subType: 'parallel', icon: 'parallel' },
		{ label: 'Inclusive', subType: 'inclusive', icon: 'inclusive' },
		{ label: 'Event-based', subType: 'eventBased', icon: 'eventBased' },
		{ label: 'Complex', subType: 'complex', icon: 'complex' },
	],
	intermediateEvent: [
		{ label: 'None', subType: 'none', catching: true },
		{ label: 'Message Catch', subType: 'message', catching: true, icon: 'message' },
		{ label: 'Message Throw', subType: 'message', catching: false, icon: 'message-filled' },
		{ label: 'Timer', subType: 'timer', catching: true, icon: 'timer' },
		{ label: 'Conditional', subType: 'conditional', catching: true, icon: 'conditional' },
		{ label: 'Signal Catch', subType: 'signal', catching: true, icon: 'signal' },
		{ label: 'Signal Throw', subType: 'signal', catching: false, icon: 'signal-filled' },
		{ label: 'Link Catch', subType: 'link', catching: true, icon: 'link' },
		{ label: 'Link Throw', subType: 'link', catching: false, icon: 'link-filled' },
		{ label: 'Escalation Catch', subType: 'escalation', catching: true, icon: 'escalation' },
		{ label: 'Escalation Throw', subType: 'escalation', catching: false, icon: 'escalation-filled' },
		{ label: 'Compensation Catch', subType: 'compensation', catching: true, icon: 'compensation' },
		{ label: 'Compensation Throw', subType: 'compensation', catching: false, icon: 'compensation-filled' },
	],
	boundaryEvent: [
		{ label: 'Message', subType: 'message', icon: 'message' },
		{ label: 'Timer', subType: 'timer', icon: 'timer' },
		{ label: 'Conditional', subType: 'conditional', icon: 'conditional' },
		{ label: 'Signal', subType: 'signal', icon: 'signal' },
		{ label: 'Error', subType: 'error', icon: 'error' },
		{ label: 'Escalation', subType: 'escalation', icon: 'escalation' },
		{ label: 'Compensation', subType: 'compensation', icon: 'compensation' },
	],
	subProcess: [
		{ label: 'Collapsed Sub-Process', subType: 'collapsed', icon: 'collapsed' },
		{ label: 'Expanded Sub-Process', subType: 'expanded', icon: 'expanded' },
		{ label: 'Event Sub-Process', subType: 'event', icon: 'event' },
		{ label: 'Transaction', subType: 'transaction', icon: 'transaction' },
	],
};

/**
 * Get the current subType for an element based on its type.
 * For legacy elements without subType, derive it from type.
 */
export function getElementSubType(element: BpmnElement): string {
	if (element.subType) {
		return element.subType;
	}
	// Derive subType from legacy type names
	switch (element.type) {
		case 'startEvent':
		case 'endEvent':
		case 'intermediateEvent':
			return 'none';
		case 'boundaryEvent':
			return 'timer'; // Default for boundary events
		case 'userTask':
			return 'user';
		case 'serviceTask':
			return 'service';
		case 'scriptTask':
			return 'script';
		case 'manualTask':
			return 'manual';
		case 'sendTask':
			return 'send';
		case 'receiveTask':
			return 'receive';
		case 'businessRuleTask':
			return 'businessRule';
		case 'callActivity':
			return 'callActivity';
		case 'exclusiveGateway':
			return 'exclusive';
		case 'parallelGateway':
			return 'parallel';
		case 'inclusiveGateway':
			return 'inclusive';
		case 'eventBasedGateway':
			return 'eventBased';
		case 'complexGateway':
			return 'complex';
		case 'subProcess':
			return element.triggeredByEvent ? 'event' : (element.isExpanded ? 'expanded' : 'collapsed');
		default:
			return 'none';
	}
}

/**
 * Check if an element type supports type change.
 */
export function supportsTypeChange(type: string): boolean {
	return type in ELEMENT_SUBTYPES;
}

/**
 * Check if an element is a Message Throw Event (End Event or Intermediate Throw Event with message subType).
 * These events require Implementation configuration like Service Tasks.
 */
export function isMessageThrowEvent(element: BpmnElement): boolean {
	// Message End Event
	if (element.type === 'endEvent' && element.subType === 'message') {
		return true;
	}
	// Message Intermediate Throw Event
	if (element.type === 'intermediateEvent' && element.subType === 'message' && element.catching === false) {
		return true;
	}
	return false;
}

// =============================================================================
// Identifier / geometry helpers
// =============================================================================

export function generateId(prefix: string = 'Element'): string {
	return `${prefix}_${generateUUID().substring(0, 8)}`;
}

export function getElementCenter(element: BpmnElement): { x: number; y: number } {
	return {
		x: element.x + element.width / 2,
		y: element.y + element.height / 2,
	};
}

/**
 * Connection point offsets for each element type.
 * These define where connections attach relative to element position.
 */
export const CONNECTION_POINT_OFFSETS: Record<string, { left: { x: number; y: number }; right: { x: number; y: number }; top: { x: number; y: number }; bottom: { x: number; y: number } }> = {
	startEvent: {
		left: { x: 0, y: 18 },
		right: { x: 36, y: 18 },
		top: { x: 18, y: 0 },
		bottom: { x: 18, y: 36 },
	},
	endEvent: {
		left: { x: 0, y: 18 },
		right: { x: 36, y: 18 },
		top: { x: 18, y: 0 },
		bottom: { x: 18, y: 36 },
	},
	userTask: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	serviceTask: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	scriptTask: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	manualTask: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	sendTask: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	receiveTask: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	businessRuleTask: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	callActivity: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	task: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	exclusiveGateway: {
		left: { x: 0, y: 25 },
		right: { x: 50, y: 25 },
		top: { x: 25, y: 0 },
		bottom: { x: 25, y: 50 },
	},
	parallelGateway: {
		left: { x: 0, y: 25 },
		right: { x: 50, y: 25 },
		top: { x: 25, y: 0 },
		bottom: { x: 25, y: 50 },
	},
	inclusiveGateway: {
		left: { x: 0, y: 25 },
		right: { x: 50, y: 25 },
		top: { x: 25, y: 0 },
		bottom: { x: 25, y: 50 },
	},
	eventBasedGateway: {
		left: { x: 0, y: 25 },
		right: { x: 50, y: 25 },
		top: { x: 25, y: 0 },
		bottom: { x: 25, y: 50 },
	},
	complexGateway: {
		left: { x: 0, y: 25 },
		right: { x: 50, y: 25 },
		top: { x: 25, y: 0 },
		bottom: { x: 25, y: 50 },
	},
	intermediateEvent: {
		left: { x: 0, y: 18 },
		right: { x: 36, y: 18 },
		top: { x: 18, y: 0 },
		bottom: { x: 18, y: 36 },
	},
	boundaryEvent: {
		left: { x: 0, y: 18 },
		right: { x: 36, y: 18 },
		top: { x: 18, y: 0 },
		bottom: { x: 18, y: 36 },
	},
	subProcess: {
		left: { x: 0, y: 40 },
		right: { x: 100, y: 40 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 80 },
	},
	dataObject: {
		left: { x: 0, y: 25 },
		right: { x: 36, y: 25 },
		top: { x: 18, y: 0 },
		bottom: { x: 18, y: 50 },
	},
	dataStore: {
		left: { x: 0, y: 25 },
		right: { x: 50, y: 25 },
		top: { x: 25, y: 0 },
		bottom: { x: 25, y: 50 },
	},
	textAnnotation: {
		left: { x: 0, y: 25 },
		right: { x: 100, y: 25 },
		top: { x: 50, y: 0 },
		bottom: { x: 50, y: 50 },
	},
};

export function getConnectionPoint(element: BpmnElement, side: 'left' | 'right' | 'top' | 'bottom'): { x: number; y: number } {
	const offsets = CONNECTION_POINT_OFFSETS[element.type];
	if (offsets) {
		const offset = offsets[side];
		return {
			x: element.x + offset.x,
			y: element.y + offset.y,
		};
	}
	// Fallback to center-based calculation
	const center = getElementCenter(element);
	switch (side) {
		case 'left':
			return { x: element.x, y: center.y };
		case 'right':
			return { x: element.x + element.width, y: center.y };
		case 'top':
			return { x: center.x, y: element.y };
		case 'bottom':
			return { x: center.x, y: element.y + element.height };
	}
}

/**
 * Gets the Y offset for horizontal connection points (left/right) for a given element type.
 */
export function getHorizontalConnectionYOffset(type: string): number {
	const offsets = CONNECTION_POINT_OFFSETS[type];
	return offsets ? offsets.left.y : 0;
}

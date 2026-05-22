/**
 * BPMN Viewer - Read-only BPMN diagram parser and rendering utilities.
 * Reuses parsing logic from bpmn-editor for display-only purposes.
 */

// =============================================================================
// Types
// =============================================================================

export interface BpmnViewerElement {
	id: string;
	type: string;
	subType?: string;
	name: string;
	x: number;
	y: number;
	width: number;
	height: number;
	labelOffsetX?: number;
	labelOffsetY?: number;
	labelWidth?: number;
	// Event specifics
	catching?: boolean;
	// Boundary Event
	attachedToRef?: string;
	cancelActivity?: boolean;
	// Sub-Process
	isExpanded?: boolean;
	triggeredByEvent?: boolean;
	// Loop markers
	loopType?: '' | 'standard' | 'multiInstanceParallel' | 'multiInstanceSequential';
	// Pool / Lane
	processRef?: string;
	isHorizontal?: boolean;
	parentPoolId?: string;
	flowNodeRefs?: string[];
	// Sub-Process child
	parentId?: string;
	// Data object
	isCollection?: boolean;
}

export interface BpmnViewerConnection {
	id: string;
	sourceRef: string;
	targetRef: string;
	name?: string;
	waypoints?: { x: number; y: number }[];
	labelOffsetX?: number;
	labelOffsetY?: number;
	labelWidth?: number;
	connectionType?: 'sequenceFlow' | 'dataAssociation' | 'association';
}

export interface BpmnViewModel {
	id: string;
	name: string;
	elements: BpmnViewerElement[];
	connections: BpmnViewerConnection[];
}

// =============================================================================
// Element Size Constants
// =============================================================================

const ELEMENT_SIZES: Record<string, { width: number; height: number }> = {
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

let idCounter = 0;
function generateId(prefix: string = 'Element'): string {
	return `${prefix}_${++idCounter}`;
}

// =============================================================================
// BPMN XML Parser (simplified for read-only viewing)
// =============================================================================

export function parseBpmnXml(xml: string): BpmnViewModel {
	const parser = new DOMParser();
	const doc = parser.parseFromString(xml, 'application/xml');

	const model: BpmnViewModel = {
		id: 'Process_1',
		name: '',
		elements: [],
		connections: [],
	};

	// Parse process
	const process = doc.querySelector('process');
	if (process) {
		model.id = process.getAttribute('id') || 'Process_1';
		model.name = process.getAttribute('name') || '';
	}

	// Parse diagram element positions (BPMNShape)
	const shapes = doc.querySelectorAll('BPMNShape');
	const shapePositions: Record<string, { x: number; y: number; width: number; height: number; labelX?: number; labelY?: number; labelWidth?: number }> = {};
	shapes.forEach(shape => {
		const bpmnElement = shape.getAttribute('bpmnElement');
		const bounds = shape.querySelector(':scope > Bounds, :scope > dc\\:Bounds');
		if (bpmnElement && bounds) {
			const shapeData: { x: number; y: number; width: number; height: number; labelX?: number; labelY?: number; labelWidth?: number } = {
				x: parseFloat(bounds.getAttribute('x') || '0'),
				y: parseFloat(bounds.getAttribute('y') || '0'),
				width: parseFloat(bounds.getAttribute('width') || '0'),
				height: parseFloat(bounds.getAttribute('height') || '0'),
			};

			const labelEl = shape.querySelector('BPMNLabel, bpmndi\\:BPMNLabel');
			if (labelEl) {
				const labelBounds = labelEl.querySelector('Bounds, dc\\:Bounds');
				if (labelBounds) {
					shapeData.labelX = parseFloat(labelBounds.getAttribute('x') || '0');
					shapeData.labelY = parseFloat(labelBounds.getAttribute('y') || '0');
					shapeData.labelWidth = parseFloat(labelBounds.getAttribute('width') || '100');
				}
			}

			shapePositions[bpmnElement] = shapeData;
		}
	});

	// Parse Pools (participants in collaboration)
	doc.querySelectorAll('collaboration > participant, participant').forEach(participant => {
		const id = participant.getAttribute('id');
		if (!id) return;

		const pos = shapePositions[id] || { x: 100, y: 100, width: ELEMENT_SIZES.pool.width, height: ELEMENT_SIZES.pool.height };

		model.elements.push({
			id,
			type: 'pool',
			name: participant.getAttribute('name') || '',
			processRef: participant.getAttribute('processRef') || '',
			isHorizontal: true,
			x: pos.x,
			y: pos.y,
			width: pos.width || ELEMENT_SIZES.pool.width,
			height: pos.height || ELEMENT_SIZES.pool.height,
		});
	});

	// Parse Lanes
	doc.querySelectorAll('laneSet > lane, lane').forEach(laneEl => {
		const id = laneEl.getAttribute('id');
		if (!id) return;

		const pos = shapePositions[id] || { x: 130, y: 100, width: ELEMENT_SIZES.lane.width, height: ELEMENT_SIZES.lane.height };

		const lane: BpmnViewerElement = {
			id,
			type: 'lane',
			name: laneEl.getAttribute('name') || '',
			x: pos.x,
			y: pos.y,
			width: pos.width || ELEMENT_SIZES.lane.width,
			height: pos.height || ELEMENT_SIZES.lane.height,
			flowNodeRefs: [],
		};

		laneEl.querySelectorAll('flowNodeRef').forEach(ref => {
			if (ref.textContent) {
				lane.flowNodeRefs = lane.flowNodeRefs || [];
				lane.flowNodeRefs.push(ref.textContent);
			}
		});

		// Find parent Pool
		const laneSet = laneEl.parentElement;
		if (laneSet && laneSet.tagName.includes('laneSet')) {
			const processEl = laneSet.parentElement;
			if (processEl) {
				const processId = processEl.getAttribute('id');
				const parentPool = model.elements.find(el =>
					el.type === 'pool' && el.processRef === processId
				);
				if (parentPool) {
					lane.parentPoolId = parentPool.id;
				}
			}
		}

		model.elements.push(lane);
	});

	// Parse flow node elements
	const elementTypes = [
		{ selector: 'startEvent', type: 'startEvent' },
		{ selector: 'endEvent', type: 'endEvent' },
		{ selector: 'intermediateCatchEvent', type: 'intermediateEvent', catching: true },
		{ selector: 'intermediateThrowEvent', type: 'intermediateEvent', catching: false },
		{ selector: 'boundaryEvent', type: 'boundaryEvent' },
		{ selector: 'userTask', type: 'userTask' },
		{ selector: 'serviceTask', type: 'serviceTask' },
		{ selector: 'scriptTask', type: 'scriptTask' },
		{ selector: 'manualTask', type: 'manualTask' },
		{ selector: 'sendTask', type: 'sendTask' },
		{ selector: 'receiveTask', type: 'receiveTask' },
		{ selector: 'businessRuleTask', type: 'businessRuleTask' },
		{ selector: 'callActivity', type: 'callActivity' },
		{ selector: 'task', type: 'task' },
		{ selector: 'exclusiveGateway', type: 'exclusiveGateway' },
		{ selector: 'parallelGateway', type: 'parallelGateway' },
		{ selector: 'inclusiveGateway', type: 'inclusiveGateway' },
		{ selector: 'eventBasedGateway', type: 'eventBasedGateway' },
		{ selector: 'complexGateway', type: 'complexGateway' },
		{ selector: 'subProcess', type: 'subProcess' },
		{ selector: 'transaction', type: 'subProcess' },
		{ selector: 'dataObjectReference', type: 'dataObject' },
		{ selector: 'dataStoreReference', type: 'dataStore' },
	];

	elementTypes.forEach(({ selector, type, catching }) => {
		doc.querySelectorAll(selector).forEach(el => {
			const id = el.getAttribute('id') || generateId(type);
			const defaultSize = ELEMENT_SIZES[type] || { width: 100, height: 80 };
			const pos = shapePositions[id] || { x: 100, y: 100, width: defaultSize.width, height: defaultSize.height };

			const element: BpmnViewerElement = {
				id,
				type,
				name: el.getAttribute('name') || '',
				x: pos.x,
				y: pos.y,
				width: pos.width || defaultSize.width,
				height: pos.height || defaultSize.height,
			};

			if (catching !== undefined) {
				element.catching = catching;
			}

			// Label position
			if (pos.labelX !== undefined && pos.labelY !== undefined) {
				element.labelOffsetX = pos.labelX - pos.x;
				element.labelOffsetY = pos.labelY - pos.y;
				if (pos.labelWidth !== undefined) {
					element.labelWidth = pos.labelWidth;
				}
			}

			// Detect event subType
			if (type === 'startEvent' || type === 'endEvent' || type === 'intermediateEvent' || type === 'boundaryEvent') {
				if (el.querySelector('messageEventDefinition')) {
					element.subType = 'message';
				} else if (el.querySelector('timerEventDefinition')) {
					element.subType = 'timer';
				} else if (el.querySelector('conditionalEventDefinition')) {
					element.subType = 'conditional';
				} else if (el.querySelector('signalEventDefinition')) {
					element.subType = 'signal';
				} else if (el.querySelector('errorEventDefinition')) {
					element.subType = 'error';
				} else if (el.querySelector('escalationEventDefinition')) {
					element.subType = 'escalation';
				} else if (el.querySelector('terminateEventDefinition')) {
					element.subType = 'terminate';
				} else if (el.querySelector('compensateEventDefinition')) {
					element.subType = 'compensation';
				} else if (el.querySelector('linkEventDefinition')) {
					element.subType = 'link';
				} else {
					element.subType = 'none';
				}
			}

			// Boundary event attachment
			if (type === 'boundaryEvent') {
				element.attachedToRef = el.getAttribute('attachedToRef') || '';
				element.cancelActivity = el.getAttribute('cancelActivity') !== 'false';
			}

			// Sub-Process specifics
			if (type === 'subProcess') {
				element.triggeredByEvent = el.getAttribute('triggeredByEvent') === 'true';
				// Expanded if shape is larger than collapsed default
				element.isExpanded = pos.width > 120 || pos.height > 100;
			}

			// Loop / Multi-Instance markers
			if (el.querySelector('standardLoopCharacteristics')) {
				element.loopType = 'standard';
			} else if (el.querySelector('multiInstanceLoopCharacteristics')) {
				const mi = el.querySelector('multiInstanceLoopCharacteristics');
				element.loopType = mi?.getAttribute('isSequential') === 'true'
					? 'multiInstanceSequential'
					: 'multiInstanceParallel';
			}

			// Parent sub-process detection
			const parentEl = el.parentElement;
			if (parentEl && (parentEl.localName === 'subProcess' || parentEl.localName === 'transaction')) {
				const parentId = parentEl.getAttribute('id');
				if (parentId) {
					element.parentId = parentId;
				}
			}

			model.elements.push(element);
		});
	});

	// Parse text annotations
	doc.querySelectorAll('textAnnotation').forEach(ta => {
		const id = ta.getAttribute('id') || generateId('TextAnnotation');
		const textEl = ta.querySelector('text');
		const rawText = textEl ? textEl.textContent || '' : '';
		const text = rawText.split('\n').map(line => line.trim()).join('\n').trim();
		const defaultSize = ELEMENT_SIZES.textAnnotation;
		const pos = shapePositions[id] || { x: 100, y: 100, width: defaultSize.width, height: defaultSize.height };

		model.elements.push({
			id,
			type: 'textAnnotation',
			name: text,
			x: pos.x,
			y: pos.y,
			width: pos.width || defaultSize.width,
			height: pos.height || defaultSize.height,
		});
	});

	// Parse sequence flows
	doc.querySelectorAll('sequenceFlow').forEach(flow => {
		const id = flow.getAttribute('id') || generateId('Flow');
		const connection: BpmnViewerConnection = {
			id,
			sourceRef: flow.getAttribute('sourceRef') || '',
			targetRef: flow.getAttribute('targetRef') || '',
			name: flow.getAttribute('name') || '',
		};

		const edge = doc.querySelector(`BPMNEdge[bpmnElement="${id}"]`);
		if (edge) {
			const waypoints: { x: number; y: number }[] = [];
			edge.querySelectorAll('waypoint').forEach(wp => {
				waypoints.push({
					x: parseFloat(wp.getAttribute('x') || '0'),
					y: parseFloat(wp.getAttribute('y') || '0'),
				});
			});
			if (waypoints.length > 0) {
				connection.waypoints = waypoints;
			}

			const labelEl = edge.querySelector('BPMNLabel, bpmndi\\:BPMNLabel');
			if (labelEl) {
				const labelBounds = labelEl.querySelector('Bounds, dc\\:Bounds');
				if (labelBounds) {
					connection.labelOffsetX = parseFloat(labelBounds.getAttribute('x') || '0');
					connection.labelOffsetY = parseFloat(labelBounds.getAttribute('y') || '0');
					connection.labelWidth = parseFloat(labelBounds.getAttribute('width') || '100');
				}
			}
		}

		model.connections.push(connection);
	});

	// Parse data associations
	doc.querySelectorAll('dataInputAssociation, dataOutputAssociation').forEach(assoc => {
		const id = assoc.getAttribute('id') || generateId('DataAssociation');
		const sourceRef = assoc.querySelector('sourceRef')?.textContent || '';
		const targetRef = assoc.querySelector('targetRef')?.textContent || '';

		if (sourceRef && targetRef) {
			const connection: BpmnViewerConnection = {
				id,
				sourceRef,
				targetRef,
				connectionType: 'dataAssociation',
			};

			const edge = doc.querySelector(`BPMNEdge[bpmnElement="${id}"]`);
			if (edge) {
				const waypoints: { x: number; y: number }[] = [];
				edge.querySelectorAll('waypoint').forEach(wp => {
					waypoints.push({
						x: parseFloat(wp.getAttribute('x') || '0'),
						y: parseFloat(wp.getAttribute('y') || '0'),
					});
				});
				if (waypoints.length > 0) {
					connection.waypoints = waypoints;
				}
			}

			model.connections.push(connection);
		}
	});

	// Parse associations (text annotation connections)
	doc.querySelectorAll('association').forEach(assoc => {
		const id = assoc.getAttribute('id') || generateId('Association');
		const sourceRef = assoc.getAttribute('sourceRef') || '';
		const targetRef = assoc.getAttribute('targetRef') || '';

		if (sourceRef && targetRef) {
			const connection: BpmnViewerConnection = {
				id,
				sourceRef,
				targetRef,
				connectionType: 'association',
			};

			const edge = doc.querySelector(`BPMNEdge[bpmnElement="${id}"]`);
			if (edge) {
				const waypoints: { x: number; y: number }[] = [];
				edge.querySelectorAll('waypoint').forEach(wp => {
					waypoints.push({
						x: parseFloat(wp.getAttribute('x') || '0'),
						y: parseFloat(wp.getAttribute('y') || '0'),
					});
				});
				if (waypoints.length > 0) {
					connection.waypoints = waypoints;
				}
			}

			model.connections.push(connection);
		}
	});

	return model;
}

// =============================================================================
// Connection Path Computation
// =============================================================================

function getPerimeterPoint(
	bounds: { x: number; y: number; width: number; height: number },
	externalPoint: { x: number; y: number },
	elementType: string
): { x: number; y: number } {
	const cx = bounds.x + bounds.width / 2;
	const cy = bounds.y + bounds.height / 2;
	const dx = externalPoint.x - cx;
	const dy = externalPoint.y - cy;

	if (dx === 0 && dy === 0) {
		return { x: cx, y: cy };
	}

	const isGateway = elementType.includes('Gateway');

	if (isGateway) {
		const hw = bounds.width / 2;
		const hh = bounds.height / 2;
		const t = 1 / (Math.abs(dx) / hw + Math.abs(dy) / hh);
		return { x: cx + dx * t, y: cy + dy * t };
	} else {
		const hw = bounds.width / 2;
		const hh = bounds.height / 2;

		let t = Infinity;
		if (dx > 0) { const tRight = hw / dx; if (tRight < t) t = tRight; }
		if (dx < 0) { const tLeft = -hw / dx; if (tLeft < t) t = tLeft; }
		if (dy > 0) { const tBottom = hh / dy; if (tBottom < t) t = tBottom; }
		if (dy < 0) { const tTop = -hh / dy; if (tTop < t) t = tTop; }

		return { x: cx + dx * t, y: cy + dy * t };
	}
}

function getBestCompassPoint(
	sourceBounds: { x: number; y: number; width: number; height: number },
	targetCenter: { x: number; y: number },
	sourceType: string
): { x: number; y: number } {
	const cx = sourceBounds.x + sourceBounds.width / 2;
	const cy = sourceBounds.y + sourceBounds.height / 2;
	const dx = targetCenter.x - cx;
	const dy = targetCenter.y - cy;

	const isGateway = sourceType.includes('Gateway');
	const hw = sourceBounds.width / 2;
	const hh = sourceBounds.height / 2;

	const normalizedDx = Math.abs(dx) / hw;
	const normalizedDy = Math.abs(dy) / hh;

	if (normalizedDx > normalizedDy) {
		if (dx > 0) {
			return isGateway ? { x: cx + hw, y: cy } : { x: sourceBounds.x + sourceBounds.width, y: cy };
		} else {
			return isGateway ? { x: cx - hw, y: cy } : { x: sourceBounds.x, y: cy };
		}
	} else {
		if (dy > 0) {
			return isGateway ? { x: cx, y: cy + hh } : { x: cx, y: sourceBounds.y + sourceBounds.height };
		} else {
			return isGateway ? { x: cx, y: cy - hh } : { x: cx, y: sourceBounds.y };
		}
	}
}

/**
 * Compute SVG path string for a connection.
 */
export function getConnectionPath(
	conn: BpmnViewerConnection,
	elements: BpmnViewerElement[]
): string {
	const source = elements.find(e => e.id === conn.sourceRef);
	const target = elements.find(e => e.id === conn.targetRef);
	if (!source || !target) return '';

	const sourceBounds = { x: source.x, y: source.y, width: source.width, height: source.height };
	const targetBounds = { x: target.x, y: target.y, width: target.width, height: target.height };

	// Use stored waypoints if available
	if (conn.waypoints && conn.waypoints.length > 1) {
		const isValid = conn.waypoints.every(p => !isNaN(p.x) && !isNaN(p.y));
		if (isValid) {
			let d = `M ${conn.waypoints[0].x} ${conn.waypoints[0].y}`;
			for (let i = 1; i < conn.waypoints.length; i++) {
				d += ` L ${conn.waypoints[i].x} ${conn.waypoints[i].y}`;
			}
			return d;
		}
	}

	const sCx = sourceBounds.x + sourceBounds.width / 2;
	const sCy = sourceBounds.y + sourceBounds.height / 2;
	const tCx = targetBounds.x + targetBounds.width / 2;
	const tCy = targetBounds.y + targetBounds.height / 2;

	// Association uses straight line with perimeter intersection
	if (conn.connectionType === 'association') {
		const sp = getPerimeterPoint(sourceBounds, { x: tCx, y: tCy }, source.type);
		const tp = getPerimeterPoint(targetBounds, { x: sCx, y: sCy }, target.type);
		return `M ${sp.x} ${sp.y} L ${tp.x} ${tp.y}`;
	}

	// Manhattan routing
	const BUFFER = 20;
	const startPt = getBestCompassPoint(sourceBounds, { x: tCx, y: tCy }, source.type);
	const endPt = getBestCompassPoint(targetBounds, { x: sCx, y: sCy }, target.type);

	const isHorzStart = Math.abs(startPt.y - sCy) < 1;

	let p1: { x: number; y: number };
	let p2: { x: number; y: number };

	if (isHorzStart) {
		const dirX = endPt.x > startPt.x ? 1 : -1;
		const turnX = startPt.x + (dirX * BUFFER);
		p1 = { x: turnX, y: startPt.y };
		p2 = { x: turnX, y: endPt.y };
	} else {
		const dirY = endPt.y > startPt.y ? 1 : -1;
		const turnY = startPt.y + (dirY * BUFFER);
		p1 = { x: startPt.x, y: turnY };
		p2 = { x: endPt.x, y: turnY };
	}

	return `M ${startPt.x} ${startPt.y} L ${p1.x} ${p1.y} L ${p2.x} ${p2.y} L ${endPt.x} ${endPt.y}`;
}

/**
 * Compute the label position for a connection. Matches bpmn-modeler's
 * convention: the returned point is the centre of the label, and the
 * foreignObject in the template offsets itself by (-width/2, -10).
 */
export function getConnectionLabelPosition(
	conn: BpmnViewerConnection,
	elements: BpmnViewerElement[]
): { x: number; y: number } {
	// Stored label position from BPMN DI is the absolute top-left of the label
	// box. The modeler treats this same value as the rendering centre, so we
	// match that to keep the two viewers visually identical.
	if (conn.labelOffsetX !== undefined && conn.labelOffsetY !== undefined) {
		return { x: conn.labelOffsetX, y: conn.labelOffsetY };
	}

	// Calculate midpoint from waypoints
	if (conn.waypoints && conn.waypoints.length >= 2) {
		const mid = Math.floor(conn.waypoints.length / 2);
		if (conn.waypoints.length % 2 === 0) {
			return {
				x: (conn.waypoints[mid - 1].x + conn.waypoints[mid].x) / 2,
				y: (conn.waypoints[mid - 1].y + conn.waypoints[mid].y) / 2,
			};
		}
		return { x: conn.waypoints[mid].x, y: conn.waypoints[mid].y };
	}

	// Fallback: midpoint between source and target centers
	const source = elements.find(e => e.id === conn.sourceRef);
	const target = elements.find(e => e.id === conn.targetRef);
	if (source && target) {
		return {
			x: (source.x + source.width / 2 + target.x + target.width / 2) / 2,
			y: (source.y + source.height / 2 + target.y + target.height / 2) / 2,
		};
	}

	return { x: 0, y: 0 };
}

// =============================================================================
// Viewport Helpers
// =============================================================================

/**
 * Calculate the bounding box of all elements and a suitable viewport transform
 * to fit the diagram into the given container dimensions.
 */
export function calculateViewBox(
	model: BpmnViewModel,
	containerWidth: number,
	containerHeight: number,
	padding: number = 40
): { x: number; y: number; width: number; height: number } {
	if (model.elements.length === 0) {
		return { x: 0, y: 0, width: containerWidth, height: containerHeight };
	}

	let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;

	for (const el of model.elements) {
		minX = Math.min(minX, el.x);
		minY = Math.min(minY, el.y);
		maxX = Math.max(maxX, el.x + el.width);
		maxY = Math.max(maxY, el.y + el.height);
	}

	// Include connection waypoints
	for (const conn of model.connections) {
		if (conn.waypoints) {
			for (const wp of conn.waypoints) {
				minX = Math.min(minX, wp.x);
				minY = Math.min(minY, wp.y);
				maxX = Math.max(maxX, wp.x);
				maxY = Math.max(maxY, wp.y);
			}
		}
	}

	return {
		x: minX - padding,
		y: minY - padding,
		width: (maxX - minX) + padding * 2,
		height: (maxY - minY) + padding * 2,
	};
}

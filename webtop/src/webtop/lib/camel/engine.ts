/**
 * Camel model engine (shared).
 *
 * The single, canonical implementation of:
 *   - parsing Camel XML DSL into a CamelModelStore, with automatic top-to-bottom
 *     layout (no diagram-interchange / coordinates are required in the source);
 *   - node geometry (dimensions, per-type styles, port anchors);
 *   - smart connection routing between node ports.
 *
 * Both the EIP modeler (interactive editing) and the read-only `eip-canvas`
 * component (operations console) build on this module, so a route always looks
 * the same wherever it is rendered. The source XML can carry any number of
 * routes; each is laid out independently and `routeId` is recorded on every
 * processor so consumers can scope selection/highlight to one route.
 */

import {
	CamelModelStore,
	CamelProcessorSemantic,
	CamelFlowSemantic,
	CamelDiShape,
	CamelDiEdge,
	generateUUID,
	type Bounds,
	type Point,
	type EipType,
} from './model.js';

// =============================================================================
// Geometry — node dimensions, styles and port anchors
// =============================================================================

export const NODE_WIDTH = 140;
export const NODE_HEIGHT = 60;
export const FROM_WIDTH = 120;
export const FROM_HEIGHT = 50;
export const CHOICE_WIDTH = 100;
export const CHOICE_HEIGHT = 60;
export const MERGE_WIDTH = 50;
export const MERGE_HEIGHT = 50;

// Layout constants
export const STEP_GAP_X = 40;
export const STEP_GAP_Y = 80;

/**
 * Unified node style constants
 */
export const NODE_STYLE = {
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

export const FROM_NODE_STYLE = {
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

export const CHOICE_NODE_STYLE = {
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

export const MERGE_NODE_STYLE = {
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

// --- node geometry helpers ---
/**
 * Get the Y offset for horizontal connection points (left/right ports) for a given node type.
 * This is used for snapping nodes to align connection lines horizontally.
 */
export function getHorizontalConnectionYOffset(type: string): number {
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
export function getNodeDimensions(type: string): { width: number; height: number } {
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
export function getNodeStyleForType(type: string): typeof NODE_STYLE {
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
// XML DSL → store (parse + auto-layout)
// =============================================================================

/**
 * Get text content of a direct child element by tag name
 */
export function getChildText(parent: Element, tagName: string): string | null {
	const child = Array.from(parent.children).find(c => c.localName === tagName);
	return child ? child.textContent?.trim() || null : null;
}

/**
 * Get all direct child elements (excluding text nodes)
 */
export function getChildElements(parent: Element): Element[] {
	return Array.from(parent.children);
}

/**
 * Get direct child elements by tag name
 */
export function getChildrenByTag(parent: Element, tagName: string): Element[] {
	return Array.from(parent.children).filter(c => c.localName === tagName);
}

/**
 * Parse XML string to CamelModelStore
 */
export function parseXmlToStore(xmlString: string): CamelModelStore {
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
export function parseRouteConfigurationElementToStore(store: CamelModelStore, configEl: Element, baseY: number) {
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
export function parseOnExceptionElementToStore(store: CamelModelStore, oeEl: Element, baseY: number, routeConfigurationId?: string) {
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
export function parseRouteElementToStore(store: CamelModelStore, routeEl: Element, baseY: number) {
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
export function parseStepElementToStore(
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
export function parseStepElementProperties(type: EipType, el: Element): Record<string, any> {
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
// Smart connection routing (node port → node port)
// =============================================================================

/**
 * Pick the best entry/exit ports for a connection based on the relative
 * position of the two nodes, then return the absolute anchor points.
 */
export function selectOptimalPorts(
	source: { x: number; y: number; style: typeof NODE_STYLE },
	target: { x: number; y: number; style: typeof NODE_STYLE }
): { sourcePort: Point; targetPort: Point } {
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
		if (dx > 0) { sourcePortKey = 'right'; targetPortKey = 'left'; }
		else { sourcePortKey = 'left'; targetPortKey = 'right'; }
	} else if (absDy > absDx + threshold) {
		if (dy > 0) { sourcePortKey = 'bottom'; targetPortKey = 'top'; }
		else { sourcePortKey = 'top'; targetPortKey = 'bottom'; }
	} else {
		if (dx >= 0) { sourcePortKey = 'right'; targetPortKey = 'left'; }
		else { sourcePortKey = 'left'; targetPortKey = 'right'; }
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
}

/** Build a smooth cubic Bezier path between two anchor points. */
export function createSmartPath(sourcePort: Point, targetPort: Point): string {
	const dx = targetPort.x - sourcePort.x;
	const dy = targetPort.y - sourcePort.y;

	if (Math.abs(dx) > Math.abs(dy)) {
		const midX = (sourcePort.x + targetPort.x) / 2;
		return `M ${sourcePort.x} ${sourcePort.y} C ${midX} ${sourcePort.y}, ${midX} ${targetPort.y}, ${targetPort.x} ${targetPort.y}`;
	}

	const midY = (sourcePort.y + targetPort.y) / 2;
	return `M ${sourcePort.x} ${sourcePort.y} C ${sourcePort.x} ${midY}, ${targetPort.x} ${midY}, ${targetPort.x} ${targetPort.y}`;
}

/** Midpoint (slightly raised) of a connection path, for placing branch labels. */
export function getConnectionLabelPosition(pathData: string): Point {
	const match = pathData.match(/M\s+([\d.]+)\s+([\d.]+).*?([\d.]+)\s+([\d.]+)$/);
	if (match) {
		const x1 = parseFloat(match[1]);
		const y1 = parseFloat(match[2]);
		const x2 = parseFloat(match[3]);
		const y2 = parseFloat(match[4]);
		return { x: (x1 + x2) / 2, y: (y1 + y2) / 2 - 8 };
	}
	return { x: 0, y: 0 };
}

export interface ConnectionPath {
	path: string;
	sourceId: string;
	targetId: string;
	flowId: string;
}

/** Compute the rendered Bezier path for every flow in the store. */
export function computeConnectionPaths(store: CamelModelStore): ConnectionPath[] {
	const paths: ConnectionPath[] = [];
	for (const flow of store.getAllFlows()) {
		const sourceShape = store.getShapeForSemantic(flow.sourceRef);
		const targetShape = store.getShapeForSemantic(flow.targetRef);
		if (!sourceShape || !targetShape) continue;

		const sourceSemantic = store.getProcessor(flow.sourceRef);
		const targetSemantic = store.getProcessor(flow.targetRef);

		const sourceStyle = sourceSemantic?.type === 'from'
			? FROM_NODE_STYLE
			: getNodeStyleForType(sourceSemantic?.type || '');
		const targetStyle = getNodeStyleForType(targetSemantic?.type || '');

		const { sourcePort, targetPort } = selectOptimalPorts(
			{ x: sourceShape.bounds.x, y: sourceShape.bounds.y, style: sourceStyle },
			{ x: targetShape.bounds.x, y: targetShape.bounds.y, style: targetStyle }
		);

		paths.push({
			path: createSmartPath(sourcePort, targetPort),
			sourceId: flow.sourceRef,
			targetId: flow.targetRef,
			flowId: flow.id,
		});
	}
	return paths;
}

// =============================================================================
// Read-only presentation helpers
// =============================================================================

/** Compact, human-friendly one-line label for a node (icon caption). */
export function getShortLabel(semantic: CamelProcessorSemantic): string {
	const props = semantic.properties;
	// User-authored step ID takes precedence — matches what MessageHistory shows.
	if (props.id) {
		const idStr = String(props.id);
		return idStr.length > 15 ? idStr.substring(0, 15) + '...' : idStr;
	}
	switch (semantic.type) {
		case 'from': {
			const fromUri = props.uri || '';
			return fromUri.length > 15 ? fromUri.substring(0, 15) + '...' : fromUri;
		}
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
		case 'onException': {
			const exceptions = props.exceptions || ['Exception'];
			const firstEx = exceptions[0] || 'Exception';
			const shortName = firstEx.split('.').pop() || firstEx;
			return shortName.length > 12 ? shortName.substring(0, 12) + '...' : shortName;
		}
		case 'aggregate':
			return props.correlationExpression ? props.correlationExpression.substring(0, 12) : 'Aggregate';
		case 'transform':
			return props.expression ? '${...}' : 'Transform';
		case 'wireTap':
		case 'enrich':
		case 'pollEnrich': {
			const uri = props.uri || '';
			return uri.length > 12 ? uri.substring(0, 12) + '...' : uri;
		}
		case 'filter':
			return props.expression ? props.expression.substring(0, 12) : 'Filter';
		case 'split':
			return props.expression ? props.expression.substring(0, 12) : 'Split';
		default:
			return semantic.type;
	}
}

// =============================================================================
// Multi-route parsing + content bounds (read-only canvas)
// =============================================================================

export interface ParsedRoute {
	routeId: string;
	/** semanticIds of every processor laid out for this route. */
	shapeIds: Set<string>;
}

export interface ParsedModel {
	store: CamelModelStore;
	/** One entry per <route> in source order; used to scope highlight/glow. */
	routes: ParsedRoute[];
}

/**
 * Parse Camel XML DSL into a laid-out store, additionally recording which
 * processors belong to which route. A single source file may declare several
 * routes (e.g. a whole DSL file); the `routes` grouping lets a consumer apply a
 * selection glow to just one of them while still drawing them all.
 *
 * Layout mirrors {@link parseXmlToStore}: routeConfigurations, then standalone
 * onExceptions, then routes — each stacked vertically.
 */
export function parseModel(xmlString: string): ParsedModel {
	const store = new CamelModelStore();
	const routes: ParsedRoute[] = [];
	try {
		const doc = new DOMParser().parseFromString(xmlString, 'application/xml');
		if (doc.querySelector('parsererror')) {
			console.error('XML parse error');
			return { store, routes };
		}
		const root = doc.documentElement;

		// The XML these consumers receive comes in two shapes:
		//   - A hand-written/DSL file wraps everything in a container
		//     (<camel>, <routes>, <camelContext>, …) whose children are the
		//     <routeConfiguration> / <onException> / <route> units.
		//   - The engine's model dumper emits a *bare* <route> (or
		//     <routeConfiguration>) as the document element, with any applied
		//     route configuration inlined as an <onException> child of <route>.
		// Normalise both: when the document element is itself a unit, treat it as
		// the only unit; otherwise iterate the container's children.
		const UNIT_TAGS = ['route', 'routeConfiguration', 'onException'];
		const units = UNIT_TAGS.includes(root.localName || '')
			? [root]
			: getChildElements(root);

		let currentY = 100;
		for (const el of units) {
			if (el.localName === 'routeConfiguration') {
				parseRouteConfigurationElementToStore(store, el, currentY);
				currentY += 250;
			} else if (el.localName === 'onException') {
				parseOnExceptionElementToStore(store, el, currentY);
				currentY += 250;
			} else if (el.localName === 'route') {
				// An applied route configuration is dumped inline as an
				// <onException> child of the route. Lift it into its own group
				// (as the modeler shows route configurations) so the route's main
				// flow renders cleanly rather than being treated as a step.
				const routeConfigurationId = el.getAttribute('routeConfigurationId') || undefined;
				for (const oeEl of getChildrenByTag(el, 'onException')) {
					parseOnExceptionElementToStore(store, oeEl, currentY, routeConfigurationId);
					currentY += 250;
					el.removeChild(oeEl);
				}

				const before = new Set(store.getAllShapes().map(s => s.semanticId));
				parseRouteElementToStore(store, el, currentY);
				const after = store.getAllShapes().map(s => s.semanticId);
				const shapeIds = new Set(after.filter(id => !before.has(id)));
				const routeId = el.getAttribute('id')
					|| store.getProcessor([...shapeIds][0] || '')?.properties?.routeId
					|| '';
				routes.push({ routeId, shapeIds });
				currentY += 300;
			}
		}
	} catch (e) {
		console.error('Failed to parse Camel XML:', e);
	}
	return { store, routes };
}

/** Tight bounding box of all laid-out shapes (with margin), for auto-fit. */
export function computeContentBounds(store: CamelModelStore, margin = 40): Bounds {
	const shapes = store.getAllShapes();
	if (shapes.length === 0) {
		return { x: 0, y: 0, width: 0, height: 0 };
	}
	let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
	for (const s of shapes) {
		minX = Math.min(minX, s.bounds.x);
		minY = Math.min(minY, s.bounds.y);
		maxX = Math.max(maxX, s.bounds.x + s.bounds.width);
		maxY = Math.max(maxY, s.bounds.y + s.bounds.height);
	}
	return {
		x: minX - margin,
		y: minY - margin,
		width: (maxX - minX) + margin * 2,
		height: (maxY - minY) + margin * 2,
	};
}

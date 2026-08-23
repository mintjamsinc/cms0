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
 * How this module obtains a DOM parser.
 *
 * The browser's DOMParser is the default. The seam exists so the parse and
 * round-trip behaviour can be exercised outside a browser - a check that the
 * Modeler still writes back what it read is only useful if it can run
 * automatically, and it cannot while the only way to run it is to open a route
 * and click save.
 */
type DomParserFactory = () => { parseFromString(source: string, mimeType: string): Document };

let domParserFactory: DomParserFactory = () => new DOMParser();

export function setDomParserFactory(factory: DomParserFactory): void {
	domParserFactory = factory;
}

/**
 * The elements Camel accepts wherever a route asks for an expression.
 *
 * There is one set because there used to be five, each listing a slightly
 * different handful, and a language missing from the list a step happened to use
 * was not an error: the expression simply came back empty and the step was
 * written out without it. `<language language="jexl">` was absent from every one
 * of them.
 *
 * Its counterpart on the writing side is BRANCHING_TYPES in serializer.ts. The
 * two are not derived from each other, and nothing but the round-trip check
 * notices when only one of them is updated - which is the reason that check
 * exists and the reason it covers every step type rather than the ones the
 * routes happen to use.
 */
export const EXPRESSION_TAGS: ReadonlySet<string> = new Set([
	'simple', 'constant', 'jsonpath', 'xpath', 'xquery', 'tokenize', 'xtokenize',
	'language', 'csimple', 'groovy', 'js', 'python', 'mvel', 'ognl', 'spel',
	'datasonnet', 'header', 'exchangeProperty', 'variable', 'method', 'ref',
	'bean', 'file', 'hl7terser', 'joor', 'jq', 'wasm', 'expression'
]);

/** The first child that is an expression, whichever language it is written in. */
export function findExpressionElement(el: Element): Element | undefined {
	return Array.from(el.children).find(c => EXPRESSION_TAGS.has(c.localName));
}

/**
 * The name a route step's expression is edited and stored under.
 *
 * Most languages are their own element - <simple>, <jsonpath>. A language
 * registered by name instead is written <language language="jexl">, which would
 * otherwise appear in the property panel as the type "language" with the actual
 * language hidden in an attribute. It is stored under the language's own name so
 * that the panel shows what it is, and written back to the same two-part form.
 */
export function expressionTypeOf(exprEl: Element | undefined): string {
	if (!exprEl) return 'simple';
	if (exprEl.localName !== 'language') return exprEl.localName;
	return exprEl.getAttribute('language') || 'language';
}

/** The attributes to keep, minus the one that named the language. */
export function expressionAttributesOf(exprEl: Element | undefined): Record<string, string> | undefined {
	const attributes = readExpressionAttributes(exprEl);
	if (!attributes || exprEl?.localName !== 'language') return attributes;
	const { language, ...rest } = attributes;
	return Object.keys(rest).length > 0 ? rest : undefined;
}

/**
 * Step types whose children form one chain that rejoins the route after the block.
 * Each needs the same treatment as <filter>: parse the body, and write it back.
 */
export const LINEAR_BLOCK_TYPES: ReadonlySet<string> = new Set([
	'filter', 'step', 'circuitBreaker', 'loadBalance', 'aggregate', 'loop'
]);

/**
 * Children of a block that configure it rather than run inside it, so they are
 * not mistaken for body steps.
 */
export const BLOCK_CONFIG_TAGS: Record<string, ReadonlySet<string>> = {
	aggregate: new Set(['correlationExpression', 'completionPredicate', 'completionTimeoutExpression',
		'completionSizeExpression', 'optimisticLockRetryPolicy']),
	circuitBreaker: new Set(['resilience4jConfiguration', 'faultToleranceConfiguration', 'onFallback']),
	loadBalance: new Set(['roundRobin', 'random', 'sticky', 'topic', 'failover', 'weighted',
		'customLoadBalancer']),
};

/** The children of a block that are steps rather than its own configuration. */
export function bodyStepElements(el: Element, alsoSkip: ReadonlySet<string> = new Set()): Element[] {
	return Array.from(el.children).filter(
		c => !EXPRESSION_TAGS.has(c.localName) && !alsoSkip.has(c.localName));
}

/**
 * Attributes carried by an expression element, kept so a save writes them back.
 *
 * They are not decoration: <jsonpath suppressExceptions="true"> is the
 * difference between a missing path yielding null and a missing path throwing,
 * and a webhook route relies on the former.
 */
export function readExpressionAttributes(exprEl: Element | undefined): Record<string, string> | undefined {
	if (!exprEl || exprEl.attributes.length === 0) return undefined;
	const attributes: Record<string, string> = {};
	for (const attr of Array.from(exprEl.attributes)) {
		attributes[attr.name] = attr.value;
	}
	return attributes;
}

/**
 * A boolean attribute as three states: written `true`, written `false`, and not
 * written at all.
 *
 * The distinction is not pedantry. The platform refuses a `<split>` inside a
 * session scope that does not state `stopOnException`, on the grounds that
 * whether one bad item stops the run is a decision and not an accident. Read as
 * a plain boolean, `stopOnException="false"` and an absent attribute are the
 * same value — so the serializer wrote neither, the Modeler deleted a
 * declaration the platform requires, and the route it had just saved would no
 * longer deploy. The author could not fix it in the Modeler either, because the
 * Modeler could only ever write `true`.
 *
 * So: absent stays absent, and anything written comes back as written.
 */
export function boolAttribute(el: Element, name: string): boolean | undefined {
	const raw = el.getAttribute(name);
	if (raw === null || raw.trim() === '') {
		return undefined;
	}
	return raw.trim() === 'true';
}

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
		const parser = domParserFactory();
		const doc = parser.parseFromString(xmlString, 'application/xml');

		// Check for parse errors. getElementsByTagName rather than a selector: this
		// has to work under whatever DOM the parser factory supplies, and the minimal
		// ones used outside the browser implement the former only.
		const parseErrors = doc.getElementsByTagName('parsererror');
		if (parseErrors.length > 0) {
			console.error('XML parse error:', parseErrors[0].textContent);
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

	// Parse redeliveryPolicy. Absent stays absent: defaulting a delay here made
	// the serializer write a <redeliveryPolicy> into every handler that had none,
	// which is not a formatting difference but a change to how failures retry.
	const rpEl = Array.from(oeEl.children).find(c => c.localName === 'redeliveryPolicy');
	const maxRedeliveries = rpEl?.getAttribute('maximumRedeliveries');
	const redeliveryDelay = rpEl?.getAttribute('redeliveryDelay');

	const semantic: CamelProcessorSemantic = {
		id: stepId,
		type: 'onException',
		properties: {
			exceptions,
			handled,
			...(maxRedeliveries ? { maximumRedeliveries: parseInt(maxRedeliveries, 10) } : {}),
			...(redeliveryDelay ? { redeliveryDelay } : {}),
			...(oeEl.getAttribute('id') ? { id: oeEl.getAttribute('id') } : {}),
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

	// <routeProperty> configures the route, not a step in it. It is carried on the
	// from node beside routeId, because that is the node whose property panel
	// stands for the route. Keeping it out of the step list matters: as a step it
	// was re-emitted after <from> on save, and the properties expressed this way -
	// mi:history among them - decide whether a route is observable at all.
	const routeProperties: { key: string; value: string }[] = [];
	for (const rpEl of getChildrenByTag(routeEl, 'routeProperty')) {
		const key = rpEl.getAttribute('key') || '';
		if (!key) continue;
		routeProperties.push({ key, value: rpEl.getAttribute('value') || '' });
	}

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
				routeConfigurationId,
				routeProperties,
				...(fromEl.getAttribute('id') ? { id: fromEl.getAttribute('id') } : {})
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

	// Parse step elements (all children after from). routeProperty is route
	// configuration, already captured above, and must not become a node.
	const stepElements = getChildElements(routeEl)
		.filter(c => c.localName !== 'from' && c.localName !== 'routeProperty');
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
		/** The block element's own Camel id, for the branch elements that are flows here. */
		elementId?: string;
		expression?: string;
		language?: string;
		expressionAttributes?: Record<string, string>;
		role?: 'try' | 'catch' | 'finally' | 'completion';
		exceptions?: string[];
		blockId?: string;
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
			...(flowProperties?.elementId && { elementId: flowProperties.elementId }),
			...(flowProperties?.expression && { expression: flowProperties.expression }),
			...(flowProperties?.language && { language: flowProperties.language }),
			...(flowProperties?.expressionAttributes && { expressionAttributes: flowProperties.expressionAttributes }),
			...(flowProperties?.role && { role: flowProperties.role }),
			...(flowProperties?.exceptions && { exceptions: flowProperties.exceptions }),
			...(flowProperties?.blockId && { blockId: flowProperties.blockId })
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
			const exprEl = findExpressionElement(whenEl);
			const exprType = expressionTypeOf(exprEl);
			const expression = exprEl?.textContent?.trim() || '';
			const expressionAttributes = expressionAttributesOf(exprEl);
			// A branch is a flow rather than a node, so its own id has nowhere else
			// to live. Without this a <when id="..."> comes back unnamed, and the id
			// is what message history reports a failure against.
			const elementId = whenEl.getAttribute('id') || undefined;

			branchMaxY = branchY;

			// Get step elements inside when (skip expression element)
			const whenStepEls = bodyStepElements(whenEl);
			if (whenStepEls.length > 0) {
				let branchPrevId: string | null = null;
				let branchX = branchStartX;
				let lastResultEndId: string | null = null;

				for (let i = 0; i < whenStepEls.length; i++) {
					const branchStepEl = whenStepEls[i];
					const flowProps = i === 0
						? { conditionType: 'when' as const, expression, language: exprType, expressionAttributes, elementId }
						: undefined;
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
			const otherwiseId = otherwiseEl.getAttribute('id') || undefined;
			const otherwiseStepEls = getChildElements(otherwiseEl);
			if (otherwiseStepEls.length > 0) {
				branchMaxY = branchY;
				let branchPrevId: string | null = null;
				let branchX = branchStartX;
				let lastResultEndId: string | null = null;

				for (let i = 0; i < otherwiseStepEls.length; i++) {
					const branchStepEl = otherwiseStepEls[i];
					const flowProps = i === 0
						? { conditionType: 'otherwise' as const, elementId: otherwiseId }
						: undefined;
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

	// Blocks whose children are a single chain that rejoins the route afterwards.
	//
	// In Camel these are not leaves: <filter> runs its children when the predicate
	// matches, <step> groups them for tracing, <circuitBreaker> guards them,
	// <loadBalance> distributes across them, <aggregate> runs them per batch. Model
	// each like Split - one branch plus an auto-merge - so the body survives a save.
	// Read as leaves, as they were, the children were simply gone.
	if (LINEAR_BLOCK_TYPES.has(stepType)) {
		const bodyStepEls = bodyStepElements(stepEl, BLOCK_CONFIG_TAGS[stepType] ?? new Set());

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

	// Handle OnCompletion element: a container whose children are its own chain.
	//
	// Camel hoists a route-scoped onCompletion out of the output list, so its
	// position among the siblings does not affect behaviour — but its children very
	// much do, and reading them as a branch is what keeps them from being dropped
	// on save. The branch does not merge back into the main flow, because an
	// onCompletion is not part of it: it runs after the exchange is done.
	if (stepType === 'onCompletion') {
		const completionStepEls = getChildElements(stepEl);
		const branchY = y + NODE_HEIGHT + STEP_GAP_Y;
		let branchPrevId: string | null = null;
		let branchX = nextX;
		let branchMaxY = branchY;

		for (let i = 0; i < completionStepEls.length; i++) {
			const connectFrom = i === 0 ? stepId : branchPrevId;
			const flowProps = i === 0 ? { role: 'completion' as const } : undefined;
			const result = parseStepElementToStore(store, completionStepEls[i], branchX, branchY, connectFrom, flowProps);
			if (result) {
				branchPrevId = result.endId;
				branchX = result.nextX;
				branchMaxY = Math.max(branchMaxY, result.maxY);
			}
		}

		// The backstop runs below the main flow and does not rejoin it: the node
		// itself stays the end point, so the next step continues from here rather
		// than from the last thing the backstop does.
		return { id: stepId, endId: stepId, nextX: Math.max(nextX, branchX), maxY: Math.max(maxY, branchMaxY) };
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
			flowProps?: { role?: 'try' | 'catch' | 'finally' | 'completion'; exceptions?: string[]; blockId?: string }
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
			const result = parseBranchElements(catchStepEls, branchY,
				{ role: 'catch', exceptions, blockId: catchEl.getAttribute('id') || undefined });
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
			const result = parseBranchElements(finallyStepEls, branchY,
				{ role: 'finally', blockId: finallyEl.getAttribute('id') || undefined });
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
			// loggingLevel is kept exactly as written, including when it matches
			// Camel's default: a level the author stated is a decision, and one
			// that disappeared because it equalled a default is a lost decision.
			return {
				message: el.getAttribute('message') || '',
				loggingLevel: el.getAttribute('loggingLevel') || undefined,
				loggerName: el.getAttribute('loggerName') || undefined
			};
		case 'setBody': {
			const simple = getChildText(el, 'simple');
			const constant = getChildText(el, 'constant');
			return { simple: simple || undefined, constant: constant || undefined };
		}
		case 'setHeader':
		case 'setProperty':
		case 'setVariable': {
			const name = el.getAttribute('name') || '';
			const exprEl = findExpressionElement(el);
			return {
				name,
				expressionType: expressionTypeOf(exprEl),
				expression: exprEl?.textContent?.trim() || '',
				expressionAttributes: expressionAttributesOf(exprEl)
			};
		}
		case 'filter':
		case 'validate':
		case 'routingSlip':
		case 'dynamicRouter':
		case 'script': {
			const exprEl = findExpressionElement(el);
			return {
				expressionType: expressionTypeOf(exprEl),
				expression: exprEl?.textContent?.trim() || '',
				expressionAttributes: expressionAttributesOf(exprEl)
			};
		}
		case 'split': {
			const exprEl = findExpressionElement(el);
			return {
				expressionType: expressionTypeOf(exprEl),
				expression: exprEl?.textContent?.trim() || '',
				expressionAttributes: expressionAttributesOf(exprEl),
				streaming: boolAttribute(el, 'streaming'),
				parallelProcessing: boolAttribute(el, 'parallelProcessing'),
				stopOnException: boolAttribute(el, 'stopOnException'),
				shareUnitOfWork: boolAttribute(el, 'shareUnitOfWork'),
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined
			};
		}
		case 'choice':
			return {};
		case 'delay': {
			const constant = getChildText(el, 'constant') || getChildText(el, 'simple') || '1000';
			return { constant, asyncDelayed: boolAttribute(el, 'asyncDelayed') };
		}
		case 'throttle': {
			const constant = getChildText(el, 'constant') || '10';
			return {
				constant,
				timePeriodMillis: el.getAttribute('timePeriodMillis') || undefined,
				asyncDelayed: boolAttribute(el, 'asyncDelayed'),
				rejectExecution: boolAttribute(el, 'rejectExecution')
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
			const corrExprEl = corrEl ? findExpressionElement(corrEl) : undefined;
			return {
				correlationExpression: corrExprEl?.textContent?.trim() || '',
				correlationExpressionType: expressionTypeOf(corrExprEl),
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined,
				completionSize: el.getAttribute('completionSize') || undefined,
				completionTimeout: el.getAttribute('completionTimeout') || undefined,
				eagerCheckCompletion: boolAttribute(el, 'eagerCheckCompletion'),
				completionFromBatchConsumer: boolAttribute(el, 'completionFromBatchConsumer')
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
				const prettyPrint = boolAttribute(dfEl, 'prettyPrint');
				if (prettyPrint !== undefined) result.prettyPrint = prettyPrint;
				if (dfEl.getAttribute('unmarshalType')) result.unmarshalType = dfEl.getAttribute('unmarshalType');
			}
			return result;
		}
		case 'transform': {
			const exprEl = findExpressionElement(el);
			return {
				expressionType: expressionTypeOf(exprEl),
				expression: exprEl?.textContent?.trim() || '',
				expressionAttributes: expressionAttributesOf(exprEl)
			};
		}
		case 'multicast':
			return {
				parallelProcessing: boolAttribute(el, 'parallelProcessing'),
				stopOnException: boolAttribute(el, 'stopOnException'),
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined
			};
		case 'recipientList': {
			const exprEl = findExpressionElement(el);
			return {
				expressionType: expressionTypeOf(exprEl),
				expression: exprEl?.textContent?.trim() || '',
				expressionAttributes: expressionAttributesOf(exprEl),
				parallelProcessing: boolAttribute(el, 'parallelProcessing'),
				stopOnException: boolAttribute(el, 'stopOnException'),
				aggregationStrategy: el.getAttribute('aggregationStrategy') || undefined
			};
		}
		case 'loop': {
			// The expression element is kept as written. It used to be read as text
			// and written back as <simple>, so <constant>2</constant> came back as a
			// Simple expression that happens to evaluate to 2 - the same count today
			// and a different one the moment the text contains a ${}.
			const exprEl = findExpressionElement(el);
			return {
				expressionType: expressionTypeOf(exprEl),
				expression: exprEl?.textContent?.trim() || '3',
				expressionAttributes: expressionAttributesOf(exprEl),
				doWhile: boolAttribute(el, 'doWhile'),
				copy: boolAttribute(el, 'copy')
			};
		}
		case 'wireTap':
			return {
				uri: el.getAttribute('uri') || '',
				copy: boolAttribute(el, 'copy')
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
		case 'onCompletion':
			// parallelProcessing is kept as an explicit string rather than being
			// normalised away: a session-closing backstop depends on staying false,
			// and a value that vanished because it matched a default would take that
			// guarantee with it.
			return {
				mode: el.getAttribute('mode') || undefined,
				parallelProcessing: el.getAttribute('parallelProcessing') || undefined,
				executorService: el.getAttribute('executorService') || undefined,
				onCompleteOnly: el.getAttribute('onCompleteOnly') || undefined,
				onFailureOnly: el.getAttribute('onFailureOnly') || undefined
			};
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
/**
 * Reduce an endpoint URI to the part that says what the node does.
 *
 * Truncating the raw URI is close to useless in practice: a repository full of
 * `cms:/etc/commerce/scripts/...` endpoints renders as the same dozen characters
 * on every node, so a canvas of thirty distinct steps reads as thirty copies of
 * `cms:/etc/com…`. What distinguishes them is the operation — the part after the
 * scheme, or, when the remainder is a script path, the script's file name.
 *
 * Query parameters are dropped: they are the node's configuration, which belongs
 * in the property panel rather than in a label.
 */
export function describeEndpoint(uri: string): string {
	if (!uri) return '';

	const queryAt = uri.indexOf('?');
	const base = queryAt >= 0 ? uri.substring(0, queryAt) : uri;

	const schemeAt = base.indexOf(':');
	if (schemeAt < 0) return base;

	const scheme = base.substring(0, schemeAt);
	let operation = base.substring(schemeAt + 1);

	// http://host/path and the like: the authority identifies the endpoint.
	if (operation.startsWith('//')) {
		operation = operation.substring(2);
		const slashAt = operation.indexOf('/');
		if (slashAt > 0) operation = operation.substring(0, slashAt);
		return `${scheme}:${operation}`;
	}

	// A path-shaped remainder names a script; its file name is the operation.
	if (operation.startsWith('/')) {
		const name = operation.substring(operation.lastIndexOf('/') + 1);
		if (name) operation = name;
	}

	return `${scheme}:${operation}`;
}

/** Trim a label to fit a node, marking that it was cut. */
function truncateLabel(text: string, max: number): string {
	return text.length > max ? text.substring(0, max) + '...' : text;
}

export function getShortLabel(semantic: CamelProcessorSemantic): string {
	const props = semantic.properties;
	// User-authored step ID takes precedence — matches what MessageHistory shows.
	if (props.id) {
		return truncateLabel(String(props.id), 15);
	}
	switch (semantic.type) {
		case 'from':
			return truncateLabel(describeEndpoint(props.uri || ''), 15);
		case 'to':
		case 'toD':
			return truncateLabel(describeEndpoint(props.uri || ''), 15);
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
		case 'onCompletion':
			return 'backstop';
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
		case 'pollEnrich':
			return truncateLabel(describeEndpoint(props.uri || ''), 15);
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
		const doc = domParserFactory().parseFromString(xmlString, 'application/xml');
		if (doc.getElementsByTagName('parsererror').length > 0) {
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

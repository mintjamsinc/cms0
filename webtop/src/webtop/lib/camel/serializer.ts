/*
 * Camel model store -> XML DSL (shared).
 *
 * The other half of the contract in engine.ts. They live together on purpose:
 * an element the parser reads and the serializer does not write is silently
 * deleted the first time a route is saved, and keeping the two apart is how
 * <onCompletion> and <routeProperty> came to be dropped.
 *
 * Every element understood here must be understood there, and the round-trip
 * test asserts it.
 */

import {
	CamelModelStore,
	CamelProcessorSemantic,
	CamelFlowSemantic,
} from './model.js';

/**
 * Render an expression element, keeping the attributes it was read with.
 *
 * <jsonpath suppressExceptions="true"> is the difference between a missing path
 * yielding null and a missing path throwing, so dropping the attribute on save
 * changes what the route does with a payload that omits a field.
 */
export function expressionToXml(
	pad: string,
	tag: string,
	expression: string,
	attributes?: Record<string, string>
): string {
	// A language that is not an element of its own is written the way Camel
	// resolves it by name: <language language="jexl">. The store keeps the
	// language's own name so the property panel can show it, so the two-part form
	// is rebuilt here rather than being carried around as a type called "language".
	const named = !ELEMENT_LANGUAGES.has(tag);
	const all = named ? { language: tag, ...(attributes ?? {}) } : attributes;
	const element = named ? 'language' : tag;
	const attrs = all
		? Object.entries(all).map(([k, v]) => ` ${k}="${escapeXml(String(v))}"`).join('')
		: '';
	return `${pad}<${element}${attrs}>${escapeXml(expression)}</${element}>`;
}

/**
 * Languages Camel gives an element of their own. Anything else is written
 * <language language="...">, which is how a language registered by name is
 * referred to.
 */
const ELEMENT_LANGUAGES: ReadonlySet<string> = new Set([
	'simple', 'constant', 'jsonpath', 'xpath', 'xquery', 'tokenize', 'xtokenize',
	'csimple', 'groovy', 'js', 'python', 'mvel', 'ognl', 'spel', 'datasonnet',
	'header', 'exchangeProperty', 'variable', 'method', 'ref', 'bean', 'file',
	'hl7terser', 'joor', 'jq', 'wasm', 'expression',
]);


/**
 * A boolean attribute, written when the model has one and omitted when it does
 * not.
 *
 * `if (props.x)` was the shape everywhere, which wrote `true` and silently
 * dropped an explicit `false`. That is a value the author wrote, deleted by a
 * save — and for `stopOnException`, which the platform requires a route inside a
 * session scope to state, it deleted a declaration and made the route
 * undeployable, with no way to put it back from the Modeler.
 *
 * `undefined` still means "not written", so a node nobody has touched stays as
 * quiet as it was.
 */
function boolAttr(name: string, value: unknown): string | null {
	if (value === undefined || value === null || value === '') {
		return null;
	}
	const on = (value === true) || (value === 'true');
	return `${name}="${on}"`;
}

/** Appends every boolean attribute that has a value. */
function pushBoolAttrs(attrs: string[], props: Record<string, any>, names: string[]): void {
	for (const name of names) {
		const attr = boolAttr(name, props[name]);
		if (attr !== null) {
			attrs.push(attr);
		}
	}
}

/**
 * The opening tag of a linear block, with the attributes that belong to the
 * block itself rather than to what runs inside it.
 */
function blockOpenTag(proc: CamelProcessorSemantic, idAttr: string): string {
	const props = proc.properties;
	const attrs: string[] = [];
	if (proc.type === 'loop') {
		pushBoolAttrs(attrs, props, ['doWhile', 'copy']);
	}
	if (proc.type === 'aggregate') {
		if (props.aggregationStrategy) attrs.push(`aggregationStrategy="${escapeXml(props.aggregationStrategy)}"`);
		if (props.completionSize) attrs.push(`completionSize="${props.completionSize}"`);
		if (props.completionTimeout) attrs.push(`completionTimeout="${props.completionTimeout}"`);
		pushBoolAttrs(attrs, props, ['eagerCheckCompletion', 'completionFromBatchConsumer']);
	}
	return `<${proc.type}${idAttr}${attrs.length ? ' ' + attrs.join(' ') : ''}>`;
}

/** A block's own configuration children, written before its body. */
function blockConfigLines(proc: CamelProcessorSemantic, pad: string): string[] {
	const props = proc.properties;
	if (proc.type === 'filter' || proc.type === 'loop') {
		return [expressionToXml(`${pad}  `, props.expressionType || 'simple',
			props.expression || (proc.type === 'loop' ? '3' : ''), props.expressionAttributes)];
	}
	if (proc.type === 'aggregate') {
		return [
			`${pad}  <correlationExpression>`,
			expressionToXml(`${pad}    `, props.correlationExpressionType || 'simple',
				props.correlationExpression || ''),
			`${pad}  </correlationExpression>`,
		];
	}
	if (proc.type === 'circuitBreaker' && props.resilience4jConfiguration) {
		const r4j = props.resilience4jConfiguration;
		const attrs: string[] = [];
		if (r4j.minimumNumberOfCalls) attrs.push(`minimumNumberOfCalls="${r4j.minimumNumberOfCalls}"`);
		if (r4j.failureRateThreshold) attrs.push(`failureRateThreshold="${r4j.failureRateThreshold}"`);
		if (r4j.waitDurationInOpenState) attrs.push(`waitDurationInOpenState="${r4j.waitDurationInOpenState}"`);
		return [`${pad}  <resilience4jConfiguration ${attrs.join(' ')}/>`];
	}
	return [];
}

/**
 * Escape special characters for XML content
 */
export function escapeXml(str: string): string {
	return str
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;')
		.replace(/'/g, '&apos;');
}

/**
 * Build an endpoint URI from its base and query parameters.
 *
 * The separator is a bare `&`: this returns the URI itself, not markup, and the
 * caller escapes it exactly once on the way into the attribute. Writing `&amp;`
 * here and then escaping the result produced `&amp;amp;`, which corrupted every
 * URI carrying two or more parameters — silently, and on every save.
 */
export function buildEndpointUri(uri: string, parameters?: Record<string, any>): string {
	if (!parameters || Object.keys(parameters).length === 0) {
		return uri;
	}
	const queryParts = Object.entries(parameters).map(([k, v]) => `${k}=${v}`);
	return `${uri}?${queryParts.join('&')}`;
}

/**
 * Convert store to Camel XML DSL string
 */
export function storeToXml(store: CamelModelStore): string {
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

		// <routeProperty> configures the route rather than being a step in it, so
		// it is written before <from>, where it was read from. These decide things
		// no step can express - whether the route is recorded in message history
		// among them - so losing them on save loses a decision, silently.
		const routeProperties = fromNode.properties.routeProperties;
		if (Array.isArray(routeProperties)) {
			for (const routeProperty of routeProperties) {
				if (!routeProperty?.key) continue;
				lines.push(`    <routeProperty key="${escapeXml(String(routeProperty.key))}"`
					+ ` value="${escapeXml(String(routeProperty.value ?? ''))}"/>`);
			}
		}

		// From
		const fromUri = fromNode.properties.uri || 'direct:start';
		const fromIdAttr = fromNode.properties.id ? ` id="${escapeXml(String(fromNode.properties.id))}"` : '';
		lines.push(`    <from${fromIdAttr} uri="${escapeXml(buildEndpointUri(fromUri, fromNode.properties.parameters))}"/>`);

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
export function onExceptionToXmlLines(
	lines: string[],
	exNode: CamelProcessorSemantic,
	store: CamelModelStore,
	baseIndent: number
): void {
	const pad = ' '.repeat(baseIndent);
	const exIdAttr = exNode.properties.id ? ` id="${escapeXml(String(exNode.properties.id))}"` : '';
	lines.push(`${pad}<onException${exIdAttr}>`);

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
	// Written only when the handler actually declares one. Inventing a policy
	// from defaults would change how failures retry, which is not the
	// serializer's decision to make.
	const maxRedel = exNode.properties.maximumRedeliveries;
	const redelDelay = exNode.properties.redeliveryDelay;
	if (maxRedel !== undefined || redelDelay !== undefined) {
		const attrs: string[] = [];
		if (maxRedel !== undefined) attrs.push(`maximumRedeliveries="${maxRedel}"`);
		if (redelDelay !== undefined) attrs.push(`redeliveryDelay="${redelDelay}"`);
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
 * Types whose outgoing flows lead into a body rather than along the route.
 *
 * Every walk over the main chain has to know these, or it follows a block's
 * children out of the block and writes them as siblings. The list lived in four
 * places and was four different lists; a type missing from one of them was a
 * container whose body escaped on save.
 *
 * Its counterparts on the reading side are LINEAR_BLOCK_TYPES and
 * EXPRESSION_TAGS in engine.ts. The two sides are not derived from each other,
 * and nothing but the round-trip check notices when only one is updated.
 */
const BRANCHING_TYPES = [
	'choice', 'split', 'doTry', 'multicast', 'filter',
	'step', 'circuitBreaker', 'loadBalance', 'aggregate', 'loop',
];

/**
 * Find the merge node that follows a branching node (Choice, Split, DoTry)
 * by tracing all branches and finding a common merge point
 */
export function findMergeNodeForBranching(store: CamelModelStore, branchingId: string): string | null {
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

			// Step over a nested container: its merge closes the nested container,
			// not this one, so the search has to resume after it.
			//
			// Returning the nested merge here was how a step that followed a
			// <filter> or <split> inside a <doTry> escaped its container on save.
			// The main chain jumped to the inner merge, found the following step
			// still unvisited, and emitted it as a sibling of the </doTry> - which
			// for a cms:commit means the commit left the transaction.
			if (BRANCHING_TYPES.includes(node.type)) {
				const nestedMerge = findMergeNodeForBranching(store, curr);
				if (!nestedMerge) break;
				visited.add(nestedMerge);
				const afterNested = flows.find(f => f.sourceRef === nestedMerge);
				if (!afterNested) break;
				curr = afterNested.targetRef;
				continue;
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
/**
 * Whether a flow continues the main chain from the given node.
 *
 * An onCompletion's own steps hang off a flow marked 'completion'. They are
 * emitted inside the <onCompletion> element, so following that flow here would
 * write them a second time as siblings of the node - and, worse, would make the
 * main chain appear to run through the backstop.
 */
export function isMainChainFlow(flow: CamelFlowSemantic, currentId: string, visited: Set<string>): boolean {
	return flow.sourceRef === currentId && !visited.has(flow.targetRef) && flow.role !== 'completion';
}

export function getConnectedSteps(store: CamelModelStore, sourceId: string, stopAtMerge: boolean = false): CamelProcessorSemantic[] {
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
		if (currentProc && BRANCHING_TYPES.includes(currentProc.type)) {
			if (currentId !== sourceId) {
				// Add the branching node to result
				result.push(currentProc);

				// Look for a merge node that follows this branching node.
				// Skipping past it is right even inside a branch: a container
				// nested in a branch ends at its own merge, and the steps after
				// it still belong to the branch. Ending the walk here instead
				// moved them out of their container on save - which for a
				// <toD uri="cms:commit"> inside a <doTry> means the commit
				// silently leaves the transaction.
				const mergeNodeId = findMergeNodeForBranching(store, currentId);
				if (mergeNodeId) {
					visited.add(mergeNodeId);
					currentId = mergeNodeId;
					// Don't add merge node to result (it's visual-only)
					continue;
				}
				// No merge found, stop here
				break;
			}
		}

		// If we're at a merge node (from the starting point being a merge), skip it.
		// stopAtMerge does not apply to the node the walk started from: the caller
		// jumped here on purpose, having just emitted the container it closes.
		if (currentProc && currentProc.type === 'merge' && currentId === sourceId) {
			const outgoing = flows.find(f => isMainChainFlow(f, currentId, visited));
			if (!outgoing) break;
			// Reaching a second merge means the container we started from was the
			// last step of the branch that holds it, and this merge closes that
			// branch. Stepping over it would pull the enclosing container's
			// siblings into the branch - which is how a <choice> that followed a
			// nested <choice> ended up inside its <when>.
			const next = store.getProcessor(outgoing.targetRef);
			if (stopAtMerge && next?.type === 'merge') break;
			visited.add(outgoing.targetRef);
			// A plain step here is the first step after the container the caller
			// just emitted, and nothing else will collect it: the top of the loop
			// only picks up containers, and the tail below only ever pushes the
			// node AFTER the current one. Without this, a step sitting between a
			// nested container and the next one was dropped on save.
			if (next && next.type !== 'merge' && !BRANCHING_TYPES.includes(next.type)) {
				result.push(next);
			}
			currentId = outgoing.targetRef;
			continue;
		}

		// Find next node in the chain
		const outgoing = flows.find(f => isMainChainFlow(f, currentId, visited));
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

			// If we just added a branching node, look for its merge point. Its
			// internal steps are emitted recursively by processorToXml; the walk
			// continues past the merge so that the steps that follow the container
			// stay in the branch that contains it.
			if (BRANCHING_TYPES.includes(target.type)) {
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
export function getBranchSteps(store: CamelModelStore, startNodeRef: string): CamelProcessorSemantic[] {
	const targetProc = store.getProcessor(startNodeRef);
	if (!targetProc) return [];

	// If the start node is ITSELF a container (Choice/Split/Multicast), we must treat it as a single block
	// and then jump to its merge node to find subsequent steps in this branch.
	if (BRANCHING_TYPES.includes(targetProc.type)) {
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
export function processorToXml(
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
			lines.push(`${pad}<${tagName}${idAttr} uri="${escapeXml(buildEndpointUri(props.uri || '', props.parameters))}"/>`);
			break;
		}

		case 'log': {
			const attrs = [`message="${escapeXml(props.message || '')}"`];
			// Written whenever the node carries one, default level included: a
			// level that vanished because it matched the default is a decision
			// the author made and the file no longer records.
			if (props.loggingLevel) {
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

		case 'setProperty':
		case 'setVariable':
		case 'setHeader': {
			lines.push(`${pad}<${proc.type}${idAttr} name="${escapeXml(props.name || '')}">`);
			const exprType = props.expressionType || 'simple';
			if (props.expression) {
				lines.push(expressionToXml(`${pad}  `, exprType, props.expression, props.expressionAttributes));
			}
			lines.push(`${pad}</${proc.type}>`);
			break;
		}

		case 'choice': {
			lines.push(`${pad}<choice${idAttr}>`);
			const flows = store.getAllFlows().filter(f => f.sourceRef === proc.id);
			const whenFlows = flows.filter(f => f.conditionType === 'when');
			const otherwiseFlow = flows.find(f => f.conditionType === 'otherwise');

			for (const flow of whenFlows) {
				const exprType = flow.language || 'simple';
				const whenIdAttr = flow.elementId ? ` id="${escapeXml(flow.elementId)}"` : '';
				lines.push(`${pad}  <when${whenIdAttr}>`);
				lines.push(expressionToXml(`${pad}    `, exprType, flow.expression || '', flow.expressionAttributes));
				const branchSteps = getBranchSteps(store, flow.targetRef);
				for (const step of branchSteps) {
					const stepLines = processorToXml(step, indent + 4, store, new Set(visitedIds));
					lines.push(...stepLines);
				}
				lines.push(`${pad}  </when>`);
			}

			if (otherwiseFlow) {
				const otherwiseIdAttr = otherwiseFlow.elementId ? ` id="${escapeXml(otherwiseFlow.elementId)}"` : '';
				lines.push(`${pad}  <otherwise${otherwiseIdAttr}>`);
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

		// The blocks whose children are one chain: <filter> runs them when its
		// predicate matches, <step> groups them, <circuitBreaker> guards them,
		// <loadBalance> distributes across them, <aggregate> runs them per batch.
		// Each writes its own opening tag and configuration, then the same body -
		// the steps reached from its output port, up to the merge node that closes
		// the block. Written as leaves, as four of them were, the children vanished.
		case 'filter':
		case 'step':
		case 'circuitBreaker':
		case 'loadBalance':
		case 'aggregate':
		case 'loop': {
			lines.push(`${pad}${blockOpenTag(proc, idAttr)}`);
			lines.push(...blockConfigLines(proc, pad));
			for (const flow of store.getAllFlows().filter(f => f.sourceRef === proc.id)) {
				for (const step of getBranchSteps(store, flow.targetRef)) {
					lines.push(...processorToXml(step, indent + 2, store, new Set(visitedIds)));
				}
			}
			lines.push(`${pad}</${proc.type}>`);
			break;
		}

		case 'split': {
			const splitAttrs: string[] = [];
			pushBoolAttrs(splitAttrs, props,
				['streaming', 'parallelProcessing', 'stopOnException', 'shareUnitOfWork']);
			if (props.aggregationStrategy) splitAttrs.push(`aggregationStrategy="${escapeXml(props.aggregationStrategy)}"`);

			const attrStr = splitAttrs.length > 0 ? ' ' + splitAttrs.join(' ') : '';
			lines.push(`${pad}<split${idAttr}${attrStr}>`);
			const splitExprType = props.expressionType || 'simple';
			lines.push(expressionToXml(`${pad}  `, splitExprType, props.expression || '', props.expressionAttributes));

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

		case 'delay': {
			// asyncDelayed is an ATTRIBUTE of <delay>. It used to be written as a
			// child element, which Camel's parser refuses outright - so a route saved
			// with it set would not load at all, and not only that node.
			const delayAttrs: string[] = [];
			pushBoolAttrs(delayAttrs, props, ['asyncDelayed']);
			const dAttrStr = delayAttrs.length > 0 ? ' ' + delayAttrs.join(' ') : '';
			lines.push(`${pad}<delay${idAttr}${dAttrStr}>`);
			lines.push(`${pad}  <constant>${escapeXml(props.constant || '1000')}</constant>`);
			lines.push(`${pad}</delay>`);
			break;
		}

		case 'throttle': {
			const throttleAttrs: string[] = [];
			if (props.timePeriodMillis) throttleAttrs.push(`timePeriodMillis="${props.timePeriodMillis}"`);
			pushBoolAttrs(throttleAttrs, props, ['asyncDelayed', 'rejectExecution']);
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

		case 'marshal':
			lines.push(`${pad}<marshal${idAttr}>`);
			if (props.dataFormat === 'json') {
				const jsonAttrs: string[] = [];
				if (props.library) jsonAttrs.push(`library="${props.library}"`);
				pushBoolAttrs(jsonAttrs, props, ['prettyPrint']);
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
			lines.push(expressionToXml(`${pad}  `, transformExprType, props.expression || '', props.expressionAttributes));
			lines.push(`${pad}</transform>`);
			break;
		}

		// One expression, no body. They differ only in the element name, so writing
		// them apart was four chances to forget one - which is how routingSlip,
		// dynamicRouter, validate and script came to be written without theirs.
		case 'routingSlip':
		case 'dynamicRouter':
		case 'validate':
		case 'script': {
			lines.push(`${pad}<${proc.type}${idAttr}>`);
			lines.push(expressionToXml(`${pad}  `, props.expressionType || 'simple',
				props.expression || '', props.expressionAttributes));
			lines.push(`${pad}</${proc.type}>`);
			break;
		}

		case 'multicast': {
			const mcAttrs: string[] = [];
			pushBoolAttrs(mcAttrs, props, ['parallelProcessing', 'stopOnException']);
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
			pushBoolAttrs(rlAttrs, props, ['parallelProcessing', 'stopOnException']);
			if (props.aggregationStrategy) rlAttrs.push(`aggregationStrategy="${escapeXml(props.aggregationStrategy)}"`);
			const rlAttrStr = rlAttrs.length > 0 ? ' ' + rlAttrs.join(' ') : '';
			lines.push(`${pad}<recipientList${idAttr}${rlAttrStr}>`);
			lines.push(expressionToXml(`${pad}  `, props.expressionType || 'simple',
				props.expression || '', props.expressionAttributes));
			lines.push(`${pad}</recipientList>`);
			break;
		}

		case 'wireTap': {
			const wtAttrs = [`uri="${escapeXml(props.uri || '')}"`];
			// copy defaults to true in Camel, so this used to write only the false
			// case and read only the false case - which meant an explicit
			// copy="true" was deleted on save. Harmless in what it does and not in
			// what it says, which is the same rule as everywhere else here.
			pushBoolAttrs(wtAttrs, props, ['copy']);
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
				const catchIdAttr = flow.blockId ? ` id="${escapeXml(flow.blockId)}"` : '';
				lines.push(`${pad}  <doCatch${catchIdAttr}>`);
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
				const finallyIdAttr = finallyFlow.blockId ? ` id="${escapeXml(finallyFlow.blockId)}"` : '';
				lines.push(`${pad}  <doFinally${finallyIdAttr}>`);
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

		case 'onCompletion': {
			// A container, like doTry: its children are what it does, and writing
			// the element without them would leave a backstop that backs nothing up.
			const attrs: string[] = [];
			for (const name of ['mode', 'parallelProcessing', 'executorService', 'onCompleteOnly', 'onFailureOnly']) {
				const value = props[name];
				if (value !== undefined && value !== null && String(value) !== '') {
					attrs.push(`${name}="${escapeXml(String(value))}"`);
				}
			}
			lines.push(`${pad}<onCompletion${idAttr}${attrs.length > 0 ? ' ' + attrs.join(' ') : ''}>`);

			const completionFlow = store.getAllFlows().find(f => f.sourceRef === proc.id && f.role === 'completion');
			if (completionFlow) {
				for (const step of getBranchSteps(store, completionFlow.targetRef)) {
					lines.push(...processorToXml(step, indent + 2, store, new Set(visitedIds)));
				}
			}

			lines.push(`${pad}</onCompletion>`);
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

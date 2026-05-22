// =============================================================================
// BPMN XML Parser (store-mode, Model/DI separation)
// =============================================================================
// Two-pass parser that populates a BpmnModelStore:
//   Pass 1: Build semantic layer (BpmnSemantic, BpmnFlowSemantic)
//   Pass 2: Build DI layer (BpmnDiShape, BpmnDiEdge, BpmnDiDiagram)
// The legacy flat-model variant lives in xml-parser-legacy.ts.
// =============================================================================

import {
	BpmnModelStore,
	type BpmnSemantic,
	type BpmnFlowSemantic,
	type BpmnDiShape,
	type BpmnDiEdge,
	type BpmnDiPlane,
	type BpmnDiDiagram,
	type Bounds,
	type Point,
	type ExecutionListener as StoreExecutionListener,
	type InputOutputParameter as StoreInputOutputParameter,
	generateUUID,
} from './bpmn-model-types.js';
import { generateId } from './element-config.js';

/**
 * Parse BPMN XML and populate the BpmnModelStore with separated semantic and DI layers.
 *
 * @param xml - The BPMN XML string to parse
 * @param store - The BpmnModelStore to populate
 */
export function parseBpmnXmlToStore(xml: string, store: BpmnModelStore): void {
	const parser = new DOMParser();
	const doc = parser.parseFromString(xml, 'application/xml');

	// Clear existing data
	store.clear();

	// =========================================================================
	// Namespace-aware Helper Functions
	// =========================================================================

	const CAMUNDA_NS = 'http://camunda.org/schema/1.0/bpmn';

	/**
	 * Get attribute value with namespace fallback support.
	 * Handles cases like: attr, camunda:attr, or NS-qualified attr
	 */
	const getAttr = (el: Element, attrName: string): string => {
		// 1. Direct attribute (e.g., id, name)
		const val = el.getAttribute(attrName);
		if (val !== null) return val;

		// 2. camunda: prefixed attribute (e.g., camunda:class)
		const valCamunda = el.getAttribute(`camunda:${attrName}`);
		if (valCamunda !== null) return valCamunda;

		// 3. Namespace-qualified attribute
		return el.getAttributeNS(CAMUNDA_NS, attrName) || '';
	};

	/**
	 * Find child element by localName (namespace-agnostic).
	 */
	const findChildByLocalName = (parent: Element, localName: string): Element | null => {
		for (const child of Array.from(parent.children)) {
			if (child.localName === localName) {
				return child;
			}
		}
		return null;
	};

	/**
	 * Find all child elements by localName (namespace-agnostic).
	 */
	const findChildrenByLocalName = (parent: Element, localName: string): Element[] => {
		const result: Element[] = [];
		for (const child of Array.from(parent.children)) {
			if (child.localName === localName) {
				result.push(child);
			}
		}
		return result;
	};

	/**
	 * Find all descendant elements by localName (namespace-agnostic).
	 */
	const findAllByLocalName = (root: Element | Document, localName: string): Element[] => {
		return Array.from(root.getElementsByTagNameNS('*', localName));
	};

	/**
	 * Find first descendant element by localName (namespace-agnostic).
	 */
	const findFirstByLocalName = (root: Element | Document, localName: string): Element | null => {
		const elements = root.getElementsByTagNameNS('*', localName);
		return elements.length > 0 ? elements[0] : null;
	};

	// =========================================================================
	// Parse Definitions (Message, Signal, Error, Escalation)
	// =========================================================================

	const messageDefinitions = new Map<string, string>();
	findAllByLocalName(doc, 'message').forEach(msg => {
		const id = msg.getAttribute('id');
		const name = msg.getAttribute('name');
		if (id) messageDefinitions.set(id, name || '');
	});

	const signalDefinitions = new Map<string, string>();
	findAllByLocalName(doc, 'signal').forEach(sig => {
		const id = sig.getAttribute('id');
		const name = sig.getAttribute('name');
		if (id) signalDefinitions.set(id, name || '');
	});

	const errorDefinitions = new Map<string, { name: string; code?: string }>();
	findAllByLocalName(doc, 'error').forEach(err => {
		const id = err.getAttribute('id');
		const name = err.getAttribute('name');
		const code = err.getAttribute('errorCode');
		if (id) errorDefinitions.set(id, { name: name || '', code: code || undefined });
	});

	const escalationDefinitions = new Map<string, { name: string; code?: string }>();
	findAllByLocalName(doc, 'escalation').forEach(esc => {
		const id = esc.getAttribute('id');
		const name = esc.getAttribute('name');
		const code = esc.getAttribute('escalationCode');
		if (id) escalationDefinitions.set(id, { name: name || '', code: code || undefined });
	});

	// Collect every <bpmn:process> so collaboration files round-trip with all
	// of their pools' processes, not just the first one. The primary fields
	// (processId/Name/isExecutable) mirror the first process for legacy
	// callers that still assume a single-process model.
	const allProcessElsForModel = findAllByLocalName(doc, 'process');
	const processes = allProcessElsForModel.map(p => ({
		id: p.getAttribute('id') || 'Process_1',
		name: p.getAttribute('name') || '',
		isExecutable: p.getAttribute('isExecutable') !== 'false',
		candidateStarterGroups: getAttr(p, 'candidateStarterGroups') || undefined,
		candidateStarterUsers: getAttr(p, 'candidateStarterUsers') || undefined,
	}));
	const primary = processes[0];
	store.setModelData({
		processId: primary?.id || 'Process_1',
		processName: primary?.name || '',
		isExecutable: primary?.isExecutable ?? true,
		candidateStarterGroups: primary?.candidateStarterGroups,
		candidateStarterUsers: primary?.candidateStarterUsers,
		processes,
		messages: Array.from(messageDefinitions.entries()).map(([id, name]) => ({ id, name })),
		signals: Array.from(signalDefinitions.entries()).map(([id, name]) => ({ id, name })),
		errors: Array.from(errorDefinitions.entries()).map(([id, def]) => ({ id, name: def.name, code: def.code })),
		escalations: Array.from(escalationDefinitions.entries()).map(([id, def]) => ({ id, name: def.name, code: def.code })),
	});

	// =========================================================================
	// PASS 1: Build Semantic Layer
	// =========================================================================

	// Helper function to parse execution listeners
	const parseExecutionListeners = (extensionElements: Element | null): StoreExecutionListener[] | undefined => {
		if (!extensionElements) return undefined;
		const listeners = findAllByLocalName(extensionElements, 'executionListener');
		if (listeners.length === 0) return undefined;

		const result: StoreExecutionListener[] = [];
		listeners.forEach(listener => {
			const listenerObj: StoreExecutionListener = {
				event: (getAttr(listener, 'event') || 'start') as 'start' | 'end' | 'take',
				listenerType: 'class',
			};

			const listenerClass = getAttr(listener, 'class');
			const listenerExpression = getAttr(listener, 'expression');
			const scriptEl = findChildByLocalName(listener, 'script');

			if (listenerClass) {
				listenerObj.listenerType = 'class';
				listenerObj.javaClass = listenerClass;

				const fieldEls = findAllByLocalName(listener, 'field');
				if (fieldEls.length > 0) {
					listenerObj.fields = [];
					fieldEls.forEach(fieldEl => {
						const fieldName = getAttr(fieldEl, 'name');
						const stringEl = findChildByLocalName(fieldEl, 'string');
						const expressionEl = findChildByLocalName(fieldEl, 'expression');

						if (stringEl) {
							listenerObj.fields!.push({
								name: fieldName,
								type: 'string',
								value: stringEl.textContent || '',
							});
						} else if (expressionEl) {
							listenerObj.fields!.push({
								name: fieldName,
								type: 'expression',
								value: expressionEl.textContent || '',
							});
						}
					});
				}
			} else if (listenerExpression) {
				listenerObj.listenerType = 'expression';
				listenerObj.expression = listenerExpression;
			} else if (scriptEl) {
				listenerObj.listenerType = 'script';
				listenerObj.scriptFormat = getAttr(scriptEl, 'scriptFormat') || 'groovy';

				const resource = getAttr(scriptEl, 'resource');
				if (resource) {
					listenerObj.scriptType = 'external';
					listenerObj.resource = resource;
				} else {
					listenerObj.scriptType = 'inline';
					listenerObj.script = scriptEl.textContent || '';
				}
			}

			result.push(listenerObj);
		});
		return result;
	};

	// Helper function to parse input parameters
	const parseInputParameters = (extensionElements: Element | null): StoreInputOutputParameter[] | undefined => {
		if (!extensionElements) return undefined;
		const inputOutput = findFirstByLocalName(extensionElements, 'inputOutput');
		if (!inputOutput) return undefined;

		const inputParams = findAllByLocalName(inputOutput, 'inputParameter');
		if (inputParams.length === 0) return undefined;

		const result: StoreInputOutputParameter[] = [];
		inputParams.forEach(param => {
			const paramObj: StoreInputOutputParameter = {
				name: getAttr(param, 'name'),
				type: 'text',
			};

			const scriptEl = findChildByLocalName(param, 'script');
			const listEl = findChildByLocalName(param, 'list');
			const mapEl = findChildByLocalName(param, 'map');

			if (scriptEl) {
				paramObj.type = 'script';
				paramObj.scriptFormat = getAttr(scriptEl, 'scriptFormat') || 'groovy';
				paramObj.script = scriptEl.textContent || '';
			} else if (listEl) {
				paramObj.type = 'list';
				paramObj.listValues = [];
				findAllByLocalName(listEl, 'value').forEach(val => {
					paramObj.listValues!.push(val.textContent || '');
				});
			} else if (mapEl) {
				paramObj.type = 'map';
				paramObj.mapEntries = [];
				findAllByLocalName(mapEl, 'entry').forEach(entry => {
					paramObj.mapEntries!.push({
						key: getAttr(entry, 'key'),
						value: entry.textContent || '',
					});
				});
			} else {
				paramObj.type = 'text';
				paramObj.value = param.textContent || '';
			}

			result.push(paramObj);
		});
		return result;
	};

	// Helper function to parse extension properties
	const parseExtensionProperties = (extensionElements: Element | null): { name: string; value: string }[] | undefined => {
		if (!extensionElements) return undefined;
		const properties = findFirstByLocalName(extensionElements, 'properties');
		if (!properties) return undefined;

		const propertyEls = findAllByLocalName(properties, 'property');
		if (propertyEls.length === 0) return undefined;

		const result: { name: string; value: string }[] = [];
		propertyEls.forEach(prop => {
			result.push({
				name: getAttr(prop, 'name'),
				value: getAttr(prop, 'value'),
			});
		});
		return result;
	};

	// Helper to convert XML element to BpmnSemantic
	const convertToSemantic = (
		el: Element,
		type: string,
		catching?: boolean
	): BpmnSemantic => {
		const id = el.getAttribute('id') || generateId(type);

		const semantic: BpmnSemantic = {
			id,
			type,
			name: el.getAttribute('name') || '',
		};

		// Set catching flag for intermediate events
		if (catching !== undefined) {
			semantic.catching = catching;
		}

		// Parse documentation
		const docEl = findChildByLocalName(el, 'documentation');
		if (docEl) {
			semantic.documentation = docEl.textContent || '';
		}

		// Parse extension elements
		const extensionElements = findChildByLocalName(el, 'extensionElements');
		semantic.executionListeners = parseExecutionListeners(extensionElements);
		semantic.extensionProperties = parseExtensionProperties(extensionElements);

		// Parse failed job retry time cycle
		if (extensionElements) {
			const failedJobRetryTimeCycle = findFirstByLocalName(extensionElements, 'failedJobRetryTimeCycle');
			if (failedJobRetryTimeCycle) {
				semantic.retryTimeCycle = failedJobRetryTimeCycle.textContent || '';
			}
		}

		// =====================================================================
		// Type-specific parsing
		// =====================================================================

		// Start Event
		if (type === 'startEvent') {
			const msgDef = findFirstByLocalName(el, 'messageEventDefinition');
			const timerDef = findFirstByLocalName(el, 'timerEventDefinition');
			const condDef = findFirstByLocalName(el, 'conditionalEventDefinition');
			const sigDef = findFirstByLocalName(el, 'signalEventDefinition');

			if (msgDef) {
				semantic.subType = 'message';
				semantic.messageRef = msgDef.getAttribute('messageRef') || '';
				if (semantic.messageRef && messageDefinitions.has(semantic.messageRef)) {
					semantic.messageName = messageDefinitions.get(semantic.messageRef);
				}
			} else if (timerDef) {
				semantic.subType = 'timer';
				const timeDate = findChildByLocalName(timerDef, 'timeDate');
				const timeDuration = findChildByLocalName(timerDef, 'timeDuration');
				const timeCycle = findChildByLocalName(timerDef, 'timeCycle');
				if (timeDate) {
					semantic.timerType = 'timeDate';
					semantic.timerValue = timeDate.textContent || '';
				} else if (timeDuration) {
					semantic.timerType = 'timeDuration';
					semantic.timerValue = timeDuration.textContent || '';
				} else if (timeCycle) {
					semantic.timerType = 'timeCycle';
					semantic.timerValue = timeCycle.textContent || '';
				}
			} else if (condDef) {
				semantic.subType = 'conditional';
				const variableName = getAttr(condDef, 'variableName');
				const variableEvents = getAttr(condDef, 'variableEvents');
				if (variableName) semantic.conditionVariableName = variableName;
				if (variableEvents) semantic.conditionVariableEvents = variableEvents;

				const condition = findChildByLocalName(condDef, 'condition');
				if (condition) {
					const language = condition.getAttribute('language');
					const resource = getAttr(condition, 'resource');
					if (language) {
						semantic.conditionType = 'script';
						semantic.conditionScriptFormat = language;
						if (resource) {
							semantic.conditionScriptType = 'external';
							semantic.conditionScriptResource = resource;
						} else {
							semantic.conditionScriptType = 'inline';
							semantic.conditionScript = condition.textContent || '';
						}
					} else {
						semantic.conditionType = 'expression';
						semantic.conditionExpression = condition.textContent || '';
					}
				} else {
					semantic.conditionType = 'expression';
				}
			} else if (sigDef) {
				semantic.subType = 'signal';
				semantic.signalRef = sigDef.getAttribute('signalRef') || '';
				if (semantic.signalRef && signalDefinitions.has(semantic.signalRef)) {
					semantic.signalName = signalDefinitions.get(semantic.signalRef);
				}
			} else {
				semantic.subType = 'none';
			}

			semantic.initiator = getAttr(el, 'initiator');
			semantic.asyncBefore = getAttr(el, 'asyncBefore') === 'true';
			semantic.asyncAfter = getAttr(el, 'asyncAfter') === 'true';
			const exclusiveAttr = getAttr(el, 'exclusive');
			semantic.exclusive = exclusiveAttr === '' ? true : exclusiveAttr === 'true';
			semantic.jobPriority = getAttr(el, 'jobPriority');
			semantic.formKey = getAttr(el, 'formKey');
		}

		// End Event
		if (type === 'endEvent') {
			const msgDef = findFirstByLocalName(el, 'messageEventDefinition');
			const termDef = findFirstByLocalName(el, 'terminateEventDefinition');
			const errDef = findFirstByLocalName(el, 'errorEventDefinition');
			const escDef = findFirstByLocalName(el, 'escalationEventDefinition');
			const sigDef = findFirstByLocalName(el, 'signalEventDefinition');

			if (msgDef) {
				semantic.subType = 'message';
				semantic.messageRef = msgDef.getAttribute('messageRef') || '';
				if (semantic.messageRef && messageDefinitions.has(semantic.messageRef)) {
					semantic.messageName = messageDefinitions.get(semantic.messageRef);
				}

				// Parse Message Throw Event Implementation
				const camundaType = getAttr(el, 'type');
				if (camundaType === 'external') {
					semantic.messageImplementationType = 'external';
					semantic.messageTopic = getAttr(el, 'topic');
					semantic.messageExternalPriority = getAttr(el, 'taskPriority');
				} else {
					const connector = extensionElements ? findFirstByLocalName(extensionElements, 'connector') : null;
					if (connector) {
						semantic.messageImplementationType = 'connector';
						const connectorId = findChildByLocalName(connector, 'connectorId');
						semantic.messageConnectorId = connectorId?.textContent || '';
					} else {
						const javaClass = getAttr(msgDef, 'class');
						if (javaClass) {
							semantic.messageImplementationType = 'class';
							semantic.messageJavaClass = javaClass;
						} else {
							const expression = getAttr(msgDef, 'expression');
							if (expression) {
								semantic.messageImplementationType = 'expression';
								semantic.messageExpression = expression;
								semantic.messageResultVariable = getAttr(msgDef, 'resultVariable');
							}
						}
					}
				}
			} else if (termDef) {
				semantic.subType = 'terminate';
			} else if (errDef) {
				semantic.subType = 'error';
				semantic.errorRef = errDef.getAttribute('errorRef') || '';
				if (semantic.errorRef && errorDefinitions.has(semantic.errorRef)) {
					const errInfo = errorDefinitions.get(semantic.errorRef)!;
					semantic.errorName = errInfo.name;
					semantic.errorCode = errInfo.code;
				}
			} else if (escDef) {
				semantic.subType = 'escalation';
				semantic.escalationRef = escDef.getAttribute('escalationRef') || '';
				if (semantic.escalationRef && escalationDefinitions.has(semantic.escalationRef)) {
					const escInfo = escalationDefinitions.get(semantic.escalationRef)!;
					semantic.escalationName = escInfo.name;
					semantic.escalationCode = escInfo.code;
				}
			} else if (sigDef) {
				semantic.subType = 'signal';
				semantic.signalRef = sigDef.getAttribute('signalRef') || '';
				if (semantic.signalRef && signalDefinitions.has(semantic.signalRef)) {
					semantic.signalName = signalDefinitions.get(semantic.signalRef);
				}
			} else {
				semantic.subType = 'none';
			}

			semantic.asyncBefore = getAttr(el, 'asyncBefore') === 'true';
			semantic.asyncAfter = getAttr(el, 'asyncAfter') === 'true';
			const exclusiveAttr = getAttr(el, 'exclusive');
			semantic.exclusive = exclusiveAttr === '' ? true : exclusiveAttr === 'true';
			semantic.jobPriority = getAttr(el, 'jobPriority');
			semantic.inputParameters = parseInputParameters(extensionElements);
		}

		// Intermediate Event
		if (type === 'intermediateEvent') {
			const msgDef = findFirstByLocalName(el, 'messageEventDefinition');
			const timerDef = findFirstByLocalName(el, 'timerEventDefinition');
			const condDef = findFirstByLocalName(el, 'conditionalEventDefinition');
			const sigDef = findFirstByLocalName(el, 'signalEventDefinition');
			const linkDef = findFirstByLocalName(el, 'linkEventDefinition');
			const escDef = findFirstByLocalName(el, 'escalationEventDefinition');
			const compDef = findFirstByLocalName(el, 'compensateEventDefinition');

			if (msgDef) {
				semantic.subType = 'message';
				semantic.messageRef = msgDef.getAttribute('messageRef') || '';
				if (semantic.messageRef && messageDefinitions.has(semantic.messageRef)) {
					semantic.messageName = messageDefinitions.get(semantic.messageRef);
				}

				// For Throw Events, parse Implementation attributes
				if (semantic.catching === false) {
					const camundaType = getAttr(el, 'type');
					if (camundaType === 'external') {
						semantic.messageImplementationType = 'external';
						semantic.messageTopic = getAttr(el, 'topic');
						semantic.messageExternalPriority = getAttr(el, 'taskPriority');
					} else {
						const connector = extensionElements ? findFirstByLocalName(extensionElements, 'connector') : null;
						if (connector) {
							semantic.messageImplementationType = 'connector';
							const connectorId = findChildByLocalName(connector, 'connectorId');
							semantic.messageConnectorId = connectorId?.textContent || '';
						} else {
							const javaClass = getAttr(msgDef, 'class');
							if (javaClass) {
								semantic.messageImplementationType = 'class';
								semantic.messageJavaClass = javaClass;
							} else {
								const expression = getAttr(msgDef, 'expression');
								if (expression) {
									semantic.messageImplementationType = 'expression';
									semantic.messageExpression = expression;
									semantic.messageResultVariable = getAttr(msgDef, 'resultVariable');
								}
							}
						}
					}
				}
			} else if (timerDef) {
				semantic.subType = 'timer';
				const timeDate = findChildByLocalName(timerDef, 'timeDate');
				const timeDuration = findChildByLocalName(timerDef, 'timeDuration');
				const timeCycle = findChildByLocalName(timerDef, 'timeCycle');
				if (timeDate) {
					semantic.timerType = 'timeDate';
					semantic.timerValue = timeDate.textContent || '';
				} else if (timeDuration) {
					semantic.timerType = 'timeDuration';
					semantic.timerValue = timeDuration.textContent || '';
				} else if (timeCycle) {
					semantic.timerType = 'timeCycle';
					semantic.timerValue = timeCycle.textContent || '';
				}
				semantic.jobPriority = getAttr(el, 'jobPriority');
			} else if (condDef) {
				semantic.subType = 'conditional';
				const variableName = getAttr(condDef, 'variableName');
				const variableEvents = getAttr(condDef, 'variableEvents');
				if (variableName) semantic.conditionVariableName = variableName;
				if (variableEvents) semantic.conditionVariableEvents = variableEvents;

				const condition = findChildByLocalName(condDef, 'condition');
				if (condition) {
					const language = condition.getAttribute('language');
					const resource = getAttr(condition, 'resource');
					if (language) {
						semantic.conditionType = 'script';
						semantic.conditionScriptFormat = language;
						if (resource) {
							semantic.conditionScriptType = 'external';
							semantic.conditionScriptResource = resource;
						} else {
							semantic.conditionScriptType = 'inline';
							semantic.conditionScript = condition.textContent || '';
						}
					} else {
						semantic.conditionType = 'expression';
						semantic.conditionExpression = condition.textContent || '';
					}
				} else {
					semantic.conditionType = 'expression';
				}
			} else if (sigDef) {
				semantic.subType = 'signal';
				semantic.signalRef = sigDef.getAttribute('signalRef') || '';
				if (semantic.signalRef && signalDefinitions.has(semantic.signalRef)) {
					semantic.signalName = signalDefinitions.get(semantic.signalRef);
				}
			} else if (linkDef) {
				semantic.subType = 'link';
				semantic.linkName = linkDef.getAttribute('name') || '';
			} else if (escDef) {
				semantic.subType = 'escalation';
				semantic.escalationRef = escDef.getAttribute('escalationRef') || '';
				if (semantic.escalationRef && escalationDefinitions.has(semantic.escalationRef)) {
					const escInfo = escalationDefinitions.get(semantic.escalationRef)!;
					semantic.escalationName = escInfo.name;
					semantic.escalationCode = escInfo.code;
				}
			} else if (compDef) {
				semantic.subType = 'compensation';
				semantic.compensationActivityRef = compDef.getAttribute('activityRef') || '';
				semantic.compensationWaitForCompletion = compDef.getAttribute('waitForCompletion') === 'true';
			} else {
				semantic.subType = 'none';
			}
		}

		// Boundary Event
		if (type === 'boundaryEvent') {
			semantic.attachedToRef = el.getAttribute('attachedToRef') || '';
			semantic.cancelActivity = el.getAttribute('cancelActivity') !== 'false';

			const msgDef = findFirstByLocalName(el, 'messageEventDefinition');
			const timerDef = findFirstByLocalName(el, 'timerEventDefinition');
			const condDef = findFirstByLocalName(el, 'conditionalEventDefinition');
			const sigDef = findFirstByLocalName(el, 'signalEventDefinition');
			const errDef = findFirstByLocalName(el, 'errorEventDefinition');
			const escDef = findFirstByLocalName(el, 'escalationEventDefinition');
			const compDef = findFirstByLocalName(el, 'compensateEventDefinition');

			if (msgDef) {
				semantic.subType = 'message';
				semantic.messageRef = msgDef.getAttribute('messageRef') || '';
				if (semantic.messageRef && messageDefinitions.has(semantic.messageRef)) {
					semantic.messageName = messageDefinitions.get(semantic.messageRef);
				}
			} else if (timerDef) {
				semantic.subType = 'timer';
				const timeDate = findChildByLocalName(timerDef, 'timeDate');
				const timeDuration = findChildByLocalName(timerDef, 'timeDuration');
				const timeCycle = findChildByLocalName(timerDef, 'timeCycle');
				if (timeDate) {
					semantic.timerType = 'timeDate';
					semantic.timerValue = timeDate.textContent || '';
				} else if (timeDuration) {
					semantic.timerType = 'timeDuration';
					semantic.timerValue = timeDuration.textContent || '';
				} else if (timeCycle) {
					semantic.timerType = 'timeCycle';
					semantic.timerValue = timeCycle.textContent || '';
				}
			} else if (condDef) {
				semantic.subType = 'conditional';
				const condition = findChildByLocalName(condDef, 'condition');
				if (condition) {
					const language = condition.getAttribute('language');
					if (language) {
						semantic.conditionType = 'script';
						semantic.conditionScriptFormat = language;
						semantic.conditionScript = condition.textContent || '';
					} else {
						semantic.conditionType = 'expression';
						semantic.conditionExpression = condition.textContent || '';
					}
				} else {
					semantic.conditionType = 'expression';
				}
			} else if (sigDef) {
				semantic.subType = 'signal';
				semantic.signalRef = sigDef.getAttribute('signalRef') || '';
				if (semantic.signalRef && signalDefinitions.has(semantic.signalRef)) {
					semantic.signalName = signalDefinitions.get(semantic.signalRef);
				}
			} else if (errDef) {
				semantic.subType = 'error';
				semantic.errorRef = errDef.getAttribute('errorRef') || '';
				if (semantic.errorRef && errorDefinitions.has(semantic.errorRef)) {
					const errInfo = errorDefinitions.get(semantic.errorRef)!;
					semantic.errorName = errInfo.name;
					semantic.errorCode = errInfo.code;
				}
			} else if (escDef) {
				semantic.subType = 'escalation';
				semantic.escalationRef = escDef.getAttribute('escalationRef') || '';
				if (semantic.escalationRef && escalationDefinitions.has(semantic.escalationRef)) {
					const escInfo = escalationDefinitions.get(semantic.escalationRef)!;
					semantic.escalationName = escInfo.name;
					semantic.escalationCode = escInfo.code;
				}
			} else if (compDef) {
				semantic.subType = 'compensation';
			} else {
				semantic.subType = 'timer';
			}
		}

		// User Task
		if (type === 'userTask') {
			semantic.assignee = getAttr(el, 'assignee');
			semantic.candidateUsers = getAttr(el, 'candidateUsers');
			semantic.candidateGroups = getAttr(el, 'candidateGroups');
			semantic.formKey = getAttr(el, 'formKey');
		}

		// Service Task
		if (type === 'serviceTask') {
			const javaClass = getAttr(el, 'class');
			const expression = getAttr(el, 'expression');
			const delegateExpression = getAttr(el, 'delegateExpression');

			if (javaClass) {
				semantic.implementation = 'class';
				semantic.javaClass = javaClass;
			} else if (expression) {
				semantic.implementation = 'expression';
				semantic.expression = expression;
			} else if (delegateExpression) {
				semantic.implementation = 'delegateExpression';
				semantic.delegateExpression = delegateExpression;
			} else {
				semantic.implementation = 'class';
			}
			semantic.inputParameters = parseInputParameters(extensionElements);
			if (extensionElements) {
				const fieldEls = findAllByLocalName(extensionElements, 'field');
				if (fieldEls.length > 0) {
					semantic.fieldInjections = [];
					fieldEls.forEach(fieldEl => {
						const fieldName = getAttr(fieldEl, 'name');
						const stringEl = findChildByLocalName(fieldEl, 'string');
						const expressionEl = findChildByLocalName(fieldEl, 'expression');
						const stringValue = fieldEl.getAttribute('stringValue');
						const expressionValue = fieldEl.getAttribute('expression');

						if (stringEl) {
							semantic.fieldInjections!.push({ name: fieldName, type: 'string', value: stringEl.textContent || '' });
						} else if (expressionEl) {
							semantic.fieldInjections!.push({ name: fieldName, type: 'expression', value: expressionEl.textContent || '' });
						} else if (stringValue !== null) {
							semantic.fieldInjections!.push({ name: fieldName, type: 'string', value: stringValue });
						} else if (expressionValue !== null) {
							semantic.fieldInjections!.push({ name: fieldName, type: 'expression', value: expressionValue });
						}
					});
				}
			}
		}

		// Script Task
		if (type === 'scriptTask') {
			semantic.scriptFormat = getAttr(el, 'scriptFormat') || 'groovy';
			const scriptEl = findChildByLocalName(el, 'script');
			semantic.script = scriptEl?.textContent || '';
		}

		// Send Task
		if (type === 'sendTask') {
			const javaClass = getAttr(el, 'class');
			const expression = getAttr(el, 'expression');
			const topic = getAttr(el, 'topic');

			if (javaClass) {
				semantic.implementationType = 'class';
				semantic.javaClass = javaClass;
			} else if (expression) {
				semantic.implementationType = 'expression';
				semantic.expression = expression;
				semantic.resultVariable = el.getAttributeNS(CAMUNDA_NS, 'resultVariable') || '';
			} else if (topic) {
				semantic.implementationType = 'external';
				semantic.topic = topic;
				semantic.priority = el.getAttributeNS(CAMUNDA_NS, 'taskPriority') || '';
			}
		}

		// Receive Task
		if (type === 'receiveTask') {
			semantic.messageRef = el.getAttribute('messageRef') || '';
			if (semantic.messageRef && messageDefinitions.has(semantic.messageRef)) {
				semantic.messageName = messageDefinitions.get(semantic.messageRef);
			}
		}

		// Business Rule Task
		if (type === 'businessRuleTask') {
			const javaClass = getAttr(el, 'class');
			const expression = getAttr(el, 'expression');
			const topic = getAttr(el, 'topic');
			const decisionRef = getAttr(el, 'decisionRef');

			if (javaClass) {
				semantic.implementationType = 'class';
				semantic.javaClass = javaClass;
			} else if (expression) {
				semantic.implementationType = 'expression';
				semantic.expression = expression;
				semantic.resultVariable = getAttr(el, 'resultVariable');
			} else if (topic) {
				semantic.implementationType = 'external';
				semantic.topic = topic;
				semantic.priority = getAttr(el, 'taskPriority');
			} else {
				semantic.implementationType = 'dmn';
			}

			if (decisionRef) {
				semantic.decisionRef = decisionRef;
				semantic.decisionRefBinding = (getAttr(el, 'decisionRefBinding') || 'latest') as 'latest' | 'deployment' | 'version' | 'versionTag';
				semantic.decisionRefVersion = getAttr(el, 'decisionRefVersion');
				semantic.decisionRefVersionTag = getAttr(el, 'decisionRefVersionTag');
				semantic.decisionRefTenantId = getAttr(el, 'decisionRefTenantId');
				semantic.mapDecisionResult = (getAttr(el, 'mapDecisionResult') || 'singleEntry') as 'singleEntry' | 'singleResult' | 'collectEntries' | 'resultList';
				semantic.resultVariable = getAttr(el, 'resultVariable');
			}
		}

		// Call Activity
		if (type === 'callActivity') {
			semantic.calledElement = el.getAttribute('calledElement') || '';
			semantic.calledElementBinding = (getAttr(el, 'calledElementBinding') || 'latest') as 'latest' | 'deployment' | 'version' | 'versionTag';
			semantic.calledElementVersion = getAttr(el, 'calledElementVersion');
			semantic.calledElementVersionTag = getAttr(el, 'calledElementVersionTag');
			semantic.calledElementTenantId = getAttr(el, 'calledElementTenantId');
			semantic.businessKey = getAttr(el, 'businessKey');

			if (extensionElements) {
				semantic.inMappings = [];
				semantic.outMappings = [];

				findChildrenByLocalName(extensionElements, 'in').forEach(inEl => {
					const source = inEl.getAttribute('source') || inEl.getAttribute('sourceExpression') || '';
					const target = inEl.getAttribute('target') || '';
					if (source || target) {
						semantic.inMappings!.push({ source, target });
					}
				});

				findChildrenByLocalName(extensionElements, 'out').forEach(outEl => {
					const source = outEl.getAttribute('source') || outEl.getAttribute('sourceExpression') || '';
					const target = outEl.getAttribute('target') || '';
					if (source || target) {
						semantic.outMappings!.push({ source, target });
					}
				});
			}
		}

		// Event-based Gateway
		if (type === 'eventBasedGateway') {
			semantic.instantiate = el.getAttribute('instantiate') === 'true';
			const eventGatewayType = el.getAttribute('eventGatewayType');
			if (eventGatewayType === 'exclusive' || eventGatewayType === 'parallel') {
				semantic.eventGatewayType = eventGatewayType;
			} else {
				semantic.eventGatewayType = 'exclusive';
			}
		}

		// Complex Gateway
		if (type === 'complexGateway') {
			const activationCondition = findChildByLocalName(el, 'activationCondition');
			if (activationCondition) {
				semantic.activationCondition = activationCondition.textContent || '';
			}
		}

		// Sub-Process / Transaction
		if (type === 'subProcess') {
			const elLocalName = el.localName || el.nodeName.replace(/^bpmn:/, '');
			const triggeredByEvent = el.getAttribute('triggeredByEvent') === 'true';

			if (elLocalName === 'transaction') {
				semantic.subType = 'transaction';
			} else if (triggeredByEvent) {
				semantic.subType = 'event';
				semantic.triggeredByEvent = true;
			} else {
				// Check for direct children using localName iteration
				const flowElementTypes = ['startEvent', 'endEvent', 'task', 'userTask', 'serviceTask', 'scriptTask', 'manualTask', 'sendTask', 'receiveTask', 'businessRuleTask', 'callActivity', 'exclusiveGateway', 'parallelGateway', 'inclusiveGateway', 'eventBasedGateway', 'complexGateway', 'subProcess', 'transaction'];
				let hasChildElements = false;
				for (const child of Array.from(el.children)) {
					if (flowElementTypes.includes(child.localName)) {
						hasChildElements = true;
						break;
					}
				}
				if (hasChildElements) {
					semantic.subType = 'expanded';
				} else {
					semantic.subType = 'collapsed';
				}
			}

			// Parse multi-instance loop characteristics
			const multiInstanceLoop = findChildByLocalName(el, 'multiInstanceLoopCharacteristics');
			if (multiInstanceLoop) {
				const isSequential = multiInstanceLoop.getAttribute('isSequential') === 'true';
				semantic.loopType = isSequential ? 'multiInstanceSequential' : 'multiInstanceParallel';

				const loopCardinality = findChildByLocalName(multiInstanceLoop, 'loopCardinality');
				if (loopCardinality) {
					semantic.collection = loopCardinality.textContent || '';
				}

				const elementVariable = getAttr(multiInstanceLoop, 'elementVariable');
				if (elementVariable) semantic.elementVariable = elementVariable;

				const collection = getAttr(multiInstanceLoop, 'collection');
				if (collection) semantic.collection = collection;
			} else {
				const standardLoop = findChildByLocalName(el, 'standardLoopCharacteristics');
				if (standardLoop) semantic.loopType = 'standard';
			}
		}

		// Data Object Reference
		if (type === 'dataObject') {
			semantic.isCollection = el.getAttribute('isCollection') === 'true';
			semantic.dataObjectRef = el.getAttribute('dataObjectRef') || '';

			const dataState = findChildByLocalName(el, 'dataState');
			if (dataState) {
				semantic.dataState = dataState.getAttribute('name') || '';
			}
		}

		// Data Store Reference
		if (type === 'dataStore') {
			semantic.dataStoreRef = el.getAttribute('dataStoreRef') || '';
		}

		// Pool
		if (type === 'pool') {
			semantic.processRef = el.getAttribute('processRef') || '';
			semantic.isHorizontal = true;
		}

		// Lane
		if (type === 'lane') {
			semantic.flowNodeRefs = [];
			findChildrenByLocalName(el, 'flowNodeRef').forEach(ref => {
				if (ref.textContent) {
					semantic.flowNodeRefs!.push(ref.textContent);
				}
			});
		}

		// Text Annotation
		if (type === 'textAnnotation') {
			const textEl = findChildByLocalName(el, 'text');
			// Trim each line to remove XML indentation whitespace, then remove leading/trailing empty lines
			const rawText = textEl?.textContent || '';
			semantic.name = rawText.split('\n').map(line => line.trim()).join('\n').trim();
		}

		return semantic;
	};

	// Recursive function to parse flow elements within a container
	const parseFlowElements = (
		container: Element,
		parentId?: string
	): void => {
		// Map localName to type (and catching flag for intermediate events)
		const elementTypeMap: Record<string, { type: string; catching?: boolean }> = {
			'startEvent': { type: 'startEvent' },
			'endEvent': { type: 'endEvent' },
			'intermediateCatchEvent': { type: 'intermediateEvent', catching: true },
			'intermediateThrowEvent': { type: 'intermediateEvent', catching: false },
			'boundaryEvent': { type: 'boundaryEvent' },
			'userTask': { type: 'userTask' },
			'serviceTask': { type: 'serviceTask' },
			'scriptTask': { type: 'scriptTask' },
			'manualTask': { type: 'manualTask' },
			'sendTask': { type: 'sendTask' },
			'receiveTask': { type: 'receiveTask' },
			'businessRuleTask': { type: 'businessRuleTask' },
			'callActivity': { type: 'callActivity' },
			'task': { type: 'task' },
			'exclusiveGateway': { type: 'exclusiveGateway' },
			'parallelGateway': { type: 'parallelGateway' },
			'inclusiveGateway': { type: 'inclusiveGateway' },
			'eventBasedGateway': { type: 'eventBasedGateway' },
			'complexGateway': { type: 'complexGateway' },
			'subProcess': { type: 'subProcess' },
			'transaction': { type: 'subProcess' },
			'dataObjectReference': { type: 'dataObject' },
			'dataStoreReference': { type: 'dataStore' },
			'textAnnotation': { type: 'textAnnotation' },
		};

		// Iterate direct children using localName
		for (const child of Array.from(container.children)) {
			const localName = child.localName;
			const typeInfo = elementTypeMap[localName];
			if (!typeInfo) continue;

			const semantic = convertToSemantic(child, typeInfo.type, typeInfo.catching);
			semantic.parentId = parentId;
			store.addSemantic(semantic);

			// Recursively process SubProcess/Transaction children
			if (typeInfo.type === 'subProcess') {
				parseFlowElements(child, semantic.id);
			}
		}
	};

	// Parse Pools (participants in collaboration)
	const allProcessEls = findAllByLocalName(doc, 'process');
	findAllByLocalName(doc, 'participant').forEach(participant => {
		const id = participant.getAttribute('id');
		if (!id) return;

		const semantic = convertToSemantic(participant, 'pool');
		// Pull candidateStarter attributes from the referenced bpmn:process so
		// the Pool Properties UI can display/edit them on this Pool.
		if (semantic.processRef) {
			const procEl = allProcessEls.find(p => p.getAttribute('id') === semantic.processRef);
			if (procEl) {
				const groups = getAttr(procEl, 'candidateStarterGroups');
				const users = getAttr(procEl, 'candidateStarterUsers');
				if (groups) semantic.candidateStarterGroups = groups;
				if (users) semantic.candidateStarterUsers = users;
			}
		}
		store.addSemantic(semantic);
	});

	// Parse Lanes
	findAllByLocalName(doc, 'lane').forEach(laneEl => {
		const semantic = convertToSemantic(laneEl, 'lane');

		// Find parent Pool
		const laneSet = laneEl.parentElement;
		if (laneSet && laneSet.localName === 'laneSet') {
			const processEl = laneSet.parentElement;
			if (processEl) {
				const processId = processEl.getAttribute('id');
				const allSemantics = store.getAllSemantics();
				const parentPool = allSemantics.find(s =>
					s.type === 'pool' && s.processRef === processId
				);
				if (parentPool) {
					semantic.parentPoolId = parentPool.id;
				}
			}
		}

		store.addSemantic(semantic);
	});

	// Build processId -> poolId map from participants so that a flow element's
	// parentId can be set to its owning Pool. Without this, the serializer
	// cannot tell which pool a node belongs to in a multi-pool collaboration.
	const processIdToPoolId = new Map<string, string>();
	store.getAllSemantics().forEach(s => {
		if (s.type === 'pool' && s.processRef) {
			processIdToPoolId.set(s.processRef, s.id);
		}
	});

	// Parse process elements (all processes for collaboration diagrams).
	// Top-level flow elements inherit their pool as parentId. Sub-process
	// children continue to use the sub-process id (handled by parseFlowElements
	// recursion). Lanes remain a separate concept; they hold flowNodeRefs but
	// do not become parentId, since BPMN allows a node to belong to a Pool
	// without being in any lane.
	findAllByLocalName(doc, 'process').forEach(processEl => {
		const procId = processEl.getAttribute('id') || '';
		const ownerPoolId = processIdToPoolId.get(procId);
		parseFlowElements(processEl, ownerPoolId);
	});

	// Parse flows (sequence flows)
	findAllByLocalName(doc, 'sequenceFlow').forEach(flow => {
		const id = flow.getAttribute('id') || generateId('Flow');
		const flowSemantic: BpmnFlowSemantic = {
			id,
			sourceRef: flow.getAttribute('sourceRef') || '',
			targetRef: flow.getAttribute('targetRef') || '',
			name: flow.getAttribute('name') || '',
		};

		const conditionEl = findChildByLocalName(flow, 'conditionExpression');
		if (conditionEl) {
			const language = conditionEl.getAttribute('language');
			if (language) {
				flowSemantic.conditionType = 'script';
				flowSemantic.conditionScriptFormat = language;
				flowSemantic.conditionScript = conditionEl.textContent || '';
			} else {
				flowSemantic.conditionType = 'expression';
				flowSemantic.conditionExpression = conditionEl.textContent || '';
			}
		}

		store.addFlow(flowSemantic);
	});

	// Parse data associations
	const dataAssocs = [
		...findAllByLocalName(doc, 'dataInputAssociation'),
		...findAllByLocalName(doc, 'dataOutputAssociation')
	];
	dataAssocs.forEach(assoc => {
		const id = assoc.getAttribute('id') || generateId('DataAssociation');
		const sourceRefEl = findChildByLocalName(assoc, 'sourceRef');
		const targetRefEl = findChildByLocalName(assoc, 'targetRef');
		const sourceRef = sourceRefEl?.textContent || '';
		const targetRef = targetRefEl?.textContent || '';

		if (sourceRef && targetRef) {
			const flowSemantic: BpmnFlowSemantic = {
				id,
				sourceRef,
				targetRef,
				connectionType: 'dataAssociation',
			};
			store.addFlow(flowSemantic);
		}
	});

	// Parse message flows (cross-pool connections, only valid inside <bpmn:collaboration>)
	findAllByLocalName(doc, 'messageFlow').forEach(mf => {
		const id = mf.getAttribute('id') || generateId('MessageFlow');
		const sourceRef = mf.getAttribute('sourceRef') || '';
		const targetRef = mf.getAttribute('targetRef') || '';
		if (!sourceRef || !targetRef) return;
		const flowSemantic: BpmnFlowSemantic = {
			id,
			sourceRef,
			targetRef,
			name: mf.getAttribute('name') || '',
			connectionType: 'messageFlow',
		};
		const messageRef = mf.getAttribute('messageRef');
		if (messageRef) flowSemantic.messageRef = messageRef;
		store.addFlow(flowSemantic);
	});

	// Parse associations (connections to/from text annotations)
	findAllByLocalName(doc, 'association').forEach(assoc => {
		const id = assoc.getAttribute('id') || generateId('Association');
		const sourceRef = assoc.getAttribute('sourceRef') || '';
		const targetRef = assoc.getAttribute('targetRef') || '';

		if (sourceRef && targetRef) {
			const flowSemantic: BpmnFlowSemantic = {
				id,
				sourceRef,
				targetRef,
				connectionType: 'association',
			};
			store.addFlow(flowSemantic);
		}
	});

	// =========================================================================
	// PASS 2: Build DI Layer
	// =========================================================================

	// Parse diagram
	const diagramEl = findFirstByLocalName(doc, 'BPMNDiagram');
	const planeEl = findFirstByLocalName(doc, 'BPMNPlane');

	if (diagramEl && planeEl) {
		const diagramId = diagramEl.getAttribute('id') || generateUUID();
		const planeId = planeEl.getAttribute('id') || generateUUID();
		// Preserve the file's BPMNPlane@bpmnElement verbatim. It may reference a
		// <bpmn:collaboration> in multi-pool diagrams or a <bpmn:process> in
		// single-process diagrams; the serializer reads this back to decide
		// which to point at on save.
		const planeBpmnElement = planeEl.getAttribute('bpmnElement') || store.getModelData().processId;

		const plane: BpmnDiPlane = {
			id: planeId,
			bpmnElement: planeBpmnElement,
			shapes: [],
			edges: [],
		};

		// Parse shapes
		findAllByLocalName(doc, 'BPMNShape').forEach(shape => {
			const bpmnElement = shape.getAttribute('bpmnElement');
			const bounds = findChildByLocalName(shape, 'Bounds');

			if (bpmnElement && bounds) {
				const shapeBounds: Bounds = {
					x: parseFloat(bounds.getAttribute('x') || '0'),
					y: parseFloat(bounds.getAttribute('y') || '0'),
					width: parseFloat(bounds.getAttribute('width') || '0'),
					height: parseFloat(bounds.getAttribute('height') || '0'),
				};

				const diShape: BpmnDiShape = {
					id: generateUUID(),
					bpmnElement,
					bounds: shapeBounds,
				};

				// Parse label bounds if present
				const labelEl = findChildByLocalName(shape, 'BPMNLabel');
				if (labelEl) {
					const labelBounds = findChildByLocalName(labelEl, 'Bounds');
					if (labelBounds) {
						diShape.label = {
							offsetX: parseFloat(labelBounds.getAttribute('x') || '0') - shapeBounds.x,
							offsetY: parseFloat(labelBounds.getAttribute('y') || '0') - shapeBounds.y,
							width: parseFloat(labelBounds.getAttribute('width') || '100'),
						};
					}
				}

				// Parse isExpanded attribute
				const isExpanded = shape.getAttribute('isExpanded');
				if (isExpanded !== null) {
					diShape.isExpanded = isExpanded === 'true';
				}

				// Parse isHorizontal attribute
				const isHorizontal = shape.getAttribute('isHorizontal');
				if (isHorizontal !== null) {
					diShape.isHorizontal = isHorizontal === 'true';
				}

				store.addShape(diShape);
				plane.shapes.push(diShape);
			}
		});

		// Parse edges
		findAllByLocalName(doc, 'BPMNEdge').forEach(edge => {
			const bpmnElement = edge.getAttribute('bpmnElement');
			if (!bpmnElement) return;

			const waypoints: Point[] = [];
			findChildrenByLocalName(edge, 'waypoint').forEach(wp => {
				waypoints.push({
					x: parseFloat(wp.getAttribute('x') || '0'),
					y: parseFloat(wp.getAttribute('y') || '0'),
				});
			});

			const diEdge: BpmnDiEdge = {
				id: generateUUID(),
				bpmnElement,
				waypoints,
			};

			// Parse label bounds if present
			const labelEl = findChildByLocalName(edge, 'BPMNLabel');
			if (labelEl) {
				const labelBounds = findChildByLocalName(labelEl, 'Bounds');
				if (labelBounds) {
					// For edges, we store absolute label position
					diEdge.label = {
						offsetX: parseFloat(labelBounds.getAttribute('x') || '0'),
						offsetY: parseFloat(labelBounds.getAttribute('y') || '0'),
						width: parseFloat(labelBounds.getAttribute('width') || '100'),
					};
				}
			}

			store.addEdge(diEdge);
			plane.edges.push(diEdge);
		});

		const diagram: BpmnDiDiagram = {
			id: diagramId,
			name: diagramEl.getAttribute('name') || 'BPMNDiagram_1',
			plane,
		};

		store.addDiagram(diagram);
	}
}

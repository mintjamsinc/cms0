// =============================================================================
// BPMN XML Serializer (store-mode, two-pass)
// =============================================================================
// Reads from a BpmnModelStore (semantic + DI layers) and produces BPMN 2.0
// XML. Pass 1 emits the semantic process tree; pass 2 emits the diagram
// interchange (BPMNShape / BPMNEdge). The legacy flat-model serializer
// lives in xml-serializer-legacy.ts.
// =============================================================================

import type {
	BpmnModelStore,
	BpmnSemantic,
	BpmnFlowSemantic,
	Point,
} from './bpmn-model-types.js';
import {
	escapeXml,
	escapeXmlAttr,
	getCenterForSave,
	getBestCompassPointForSave,
	getPerimeterPointForSave,
} from './xml-utils.js';

/**
 * Serialize BpmnModelStore to BPMN XML
 * Pass 1: Semantic layer (process elements, flows)
 * Pass 2: DI layer (shapes, edges with coordinates)
 */
export function serializeStoreToXml(store: BpmnModelStore): string {
	const lines: string[] = [];
	const modelData = store.getModelData();

	// XML header and definitions
	lines.push('<?xml version="1.0" encoding="UTF-8"?>');
	lines.push('<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"');
	lines.push('                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"');
	lines.push('                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"');
	lines.push('                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"');
	lines.push('                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"');
	lines.push('                  xmlns:camunda="http://camunda.org/schema/1.0/bpmn"');
	lines.push(`                  id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">`);

	const allSemantics = store.getAllSemantics();
	const allFlows = store.getAllFlows();

	// =========================================================================
	// PASS 1: Semantic Layer
	// =========================================================================

	// Collaboration (if pools exist).
	// Holds participants (one per pool) and messageFlows (cross-pool edges).
	// Per BPMN XSD, messageFlow lives inside collaboration, not inside a process.
	const pools = allSemantics.filter(s => s.type === 'pool');
	const lanes = allSemantics.filter(s => s.type === 'lane');
	if (pools.length > 0) {
		lines.push(`  <bpmn:collaboration id="Collaboration_1">`);
		pools.forEach(pool => {
			let attrs = `id="${escapeXml(pool.id)}"`;
			if (pool.name) attrs += ` name="${escapeXmlAttr(pool.name)}"`;
			if (pool.processRef) attrs += ` processRef="${escapeXml(pool.processRef)}"`;
			lines.push(`    <bpmn:participant ${attrs}/>`);
		});
		allFlows.filter(f => f.connectionType === 'messageFlow').forEach(mf => {
			let attrs = `id="${escapeXml(mf.id)}"`;
			attrs += ` sourceRef="${escapeXml(mf.sourceRef)}"`;
			attrs += ` targetRef="${escapeXml(mf.targetRef)}"`;
			if (mf.name) attrs += ` name="${escapeXmlAttr(mf.name)}"`;
			if (mf.messageRef) attrs += ` messageRef="${escapeXml(mf.messageRef)}"`;
			lines.push(`    <bpmn:messageFlow ${attrs}/>`);
		});
		lines.push(`  </bpmn:collaboration>`);
	}

	// Message definitions
	const messages = modelData.messages || [];
	messages.forEach(msg => {
		let attrs = `id="${escapeXml(msg.id)}"`;
		if (msg.name) attrs += ` name="${escapeXmlAttr(msg.name)}"`;
		lines.push(`  <bpmn:message ${attrs}/>`);
	});

	// Signal definitions
	const signals = modelData.signals || [];
	signals.forEach(sig => {
		let attrs = `id="${escapeXml(sig.id)}"`;
		if (sig.name) attrs += ` name="${escapeXmlAttr(sig.name)}"`;
		lines.push(`  <bpmn:signal ${attrs}/>`);
	});

	// Error definitions
	const errors = modelData.errors || [];
	errors.forEach(err => {
		let attrs = `id="${escapeXml(err.id)}"`;
		if (err.name) attrs += ` name="${escapeXmlAttr(err.name)}"`;
		if (err.code) attrs += ` errorCode="${escapeXml(err.code)}"`;
		lines.push(`  <bpmn:error ${attrs}/>`);
	});

	// Escalation definitions
	const escalations = modelData.escalations || [];
	escalations.forEach(esc => {
		let attrs = `id="${escapeXml(esc.id)}"`;
		if (esc.name) attrs += ` name="${escapeXmlAttr(esc.name)}"`;
		if (esc.code) attrs += ` escalationCode="${escapeXml(esc.code)}"`;
		lines.push(`  <bpmn:escalation ${attrs}/>`);
	});

	// (process open + laneSet emission moved to per-process loop below; the
	// helpers defined next operate on a single process at a time.)

	// Helper to determine if a semantic is an Artifact (must come after all Flow Elements)
	const isArtifactNode = (semantic: BpmnSemantic): boolean => {
		const t = semantic.type.toLowerCase();
		// textAnnotation and group are Artifacts - must be serialized last
		// dataObjectReference and dataStoreReference are Flow Elements - serialize first
		return t.includes('textannotation') || t === 'group';
	};

	// Helper to serialize a semantic element
	const serializeSemantic = (semantic: BpmnSemantic, indent: string): void => {
		// Skip pools and lanes (handled separately)
		if (semantic.type === 'pool' || semantic.type === 'lane') return;

		// Determine tag name
		let tagName: string;
		if (semantic.type === 'intermediateEvent') {
			tagName = semantic.catching === false ? 'bpmn:intermediateThrowEvent' : 'bpmn:intermediateCatchEvent';
		} else if (semantic.type === 'boundaryEvent') {
			tagName = 'bpmn:boundaryEvent';
		} else if (semantic.type === 'subProcess') {
			tagName = semantic.subType === 'transaction' ? 'bpmn:transaction' : 'bpmn:subProcess';
		} else if (semantic.type === 'dataObject') {
			tagName = 'bpmn:dataObjectReference';
		} else if (semantic.type === 'dataStore') {
			tagName = 'bpmn:dataStoreReference';
		} else {
			tagName = `bpmn:${semantic.type}`;
		}

		// Build attributes
		let attrs = `id="${escapeXml(semantic.id)}"`;
		// TextAnnotation must NOT have a name attribute (use <bpmn:text> instead)
		if (semantic.name && semantic.type !== 'textAnnotation') {
			attrs += ` name="${escapeXmlAttr(semantic.name)}"`;
		}

		// Boundary Event attributes
		if (semantic.type === 'boundaryEvent') {
			if (semantic.attachedToRef) attrs += ` attachedToRef="${escapeXml(semantic.attachedToRef)}"`;
			if (semantic.cancelActivity === false) attrs += ` cancelActivity="false"`;
		}

		// User Task Camunda attributes
		if (semantic.type === 'userTask') {
			if (semantic.assignee) attrs += ` camunda:assignee="${escapeXml(semantic.assignee)}"`;
			if (semantic.candidateUsers) attrs += ` camunda:candidateUsers="${escapeXml(semantic.candidateUsers)}"`;
			if (semantic.candidateGroups) attrs += ` camunda:candidateGroups="${escapeXml(semantic.candidateGroups)}"`;
			if (semantic.formKey) attrs += ` camunda:formKey="${escapeXml(semantic.formKey)}"`;
		}

		// Service Task Camunda attributes
		if (semantic.type === 'serviceTask') {
			if (semantic.implementation === 'class' && semantic.javaClass) {
				attrs += ` camunda:class="${escapeXml(semantic.javaClass)}"`;
			} else if (semantic.implementation === 'expression' && semantic.expression) {
				attrs += ` camunda:expression="${escapeXml(semantic.expression)}"`;
			} else if (semantic.implementation === 'delegateExpression' && semantic.delegateExpression) {
				attrs += ` camunda:delegateExpression="${escapeXml(semantic.delegateExpression)}"`;
			}
		}

		// Script Task attributes
		if (semantic.type === 'scriptTask') {
			if (semantic.scriptFormat) attrs += ` scriptFormat="${escapeXml(semantic.scriptFormat)}"`;
		}

		// Send Task attributes
		if (semantic.type === 'sendTask') {
			if (semantic.implementationType === 'class' && semantic.javaClass) {
				attrs += ` camunda:class="${escapeXml(semantic.javaClass)}"`;
			} else if (semantic.implementationType === 'expression' && semantic.expression) {
				attrs += ` camunda:expression="${escapeXml(semantic.expression)}"`;
				if (semantic.resultVariable) attrs += ` camunda:resultVariable="${escapeXml(semantic.resultVariable)}"`;
			} else if (semantic.implementationType === 'external' && semantic.topic) {
				attrs += ` camunda:type="external"`;
				attrs += ` camunda:topic="${escapeXml(semantic.topic)}"`;
				if (semantic.priority) attrs += ` camunda:taskPriority="${escapeXml(semantic.priority)}"`;
			}
		}

		// Receive Task attributes
		if (semantic.type === 'receiveTask' && semantic.messageRef) {
			attrs += ` messageRef="${escapeXml(semantic.messageRef)}"`;
		}

		// Business Rule Task attributes
		if (semantic.type === 'businessRuleTask') {
			if (semantic.implementationType === 'class' && semantic.javaClass) {
				attrs += ` camunda:class="${escapeXml(semantic.javaClass)}"`;
			} else if (semantic.implementationType === 'expression' && semantic.expression) {
				attrs += ` camunda:expression="${escapeXml(semantic.expression)}"`;
				if (semantic.resultVariable) attrs += ` camunda:resultVariable="${escapeXml(semantic.resultVariable)}"`;
			} else if (semantic.implementationType === 'external' && semantic.topic) {
				attrs += ` camunda:type="external"`;
				attrs += ` camunda:topic="${escapeXml(semantic.topic)}"`;
				if (semantic.priority) attrs += ` camunda:taskPriority="${escapeXml(semantic.priority)}"`;
			} else if (semantic.implementationType === 'dmn' || !semantic.implementationType) {
				if (semantic.decisionRef) attrs += ` camunda:decisionRef="${escapeXml(semantic.decisionRef)}"`;
				if (semantic.decisionRefBinding && semantic.decisionRefBinding !== 'latest') {
					attrs += ` camunda:decisionRefBinding="${escapeXml(semantic.decisionRefBinding)}"`;
				}
				if (semantic.decisionRefVersion) attrs += ` camunda:decisionRefVersion="${escapeXml(semantic.decisionRefVersion)}"`;
				if (semantic.decisionRefVersionTag) attrs += ` camunda:decisionRefVersionTag="${escapeXml(semantic.decisionRefVersionTag)}"`;
				if (semantic.decisionRefTenantId) attrs += ` camunda:decisionRefTenantId="${escapeXml(semantic.decisionRefTenantId)}"`;
				if (semantic.mapDecisionResult && semantic.mapDecisionResult !== 'singleEntry') {
					attrs += ` camunda:mapDecisionResult="${escapeXml(semantic.mapDecisionResult)}"`;
				}
				if (semantic.resultVariable) attrs += ` camunda:resultVariable="${escapeXml(semantic.resultVariable)}"`;
			}
		}

		// Call Activity attributes
		if (semantic.type === 'callActivity') {
			if (semantic.calledElement) attrs += ` calledElement="${escapeXml(semantic.calledElement)}"`;
			if (semantic.calledElementBinding && semantic.calledElementBinding !== 'latest') {
				attrs += ` camunda:calledElementBinding="${escapeXml(semantic.calledElementBinding)}"`;
			}
			if (semantic.calledElementVersion) attrs += ` camunda:calledElementVersion="${escapeXml(semantic.calledElementVersion)}"`;
			if (semantic.calledElementVersionTag) attrs += ` camunda:calledElementVersionTag="${escapeXml(semantic.calledElementVersionTag)}"`;
			if (semantic.calledElementTenantId) attrs += ` camunda:calledElementTenantId="${escapeXml(semantic.calledElementTenantId)}"`;
			if (semantic.businessKey) attrs += ` camunda:businessKey="${escapeXml(semantic.businessKey)}"`;
		}

		// Event-based Gateway attributes
		if (semantic.type === 'eventBasedGateway') {
			if (semantic.instantiate) attrs += ` instantiate="true"`;
			if (semantic.eventGatewayType && semantic.eventGatewayType !== 'exclusive') {
				attrs += ` eventGatewayType="${escapeXml(semantic.eventGatewayType)}"`;
			}
		}

		// Sub-Process attributes
		if (semantic.type === 'subProcess') {
			if (semantic.triggeredByEvent || semantic.subType === 'event') attrs += ` triggeredByEvent="true"`;
		}

		// Data Object Reference attributes
		if (semantic.type === 'dataObject') {
			if (semantic.isCollection) attrs += ` isCollection="true"`;
			if (semantic.dataObjectRef) attrs += ` dataObjectRef="${escapeXml(semantic.dataObjectRef)}"`;
		}

		// Data Store Reference attributes
		if (semantic.type === 'dataStore' && semantic.dataStoreRef) {
			attrs += ` dataStoreRef="${escapeXml(semantic.dataStoreRef)}"`;
		}

		// Async attributes for events
		if (semantic.type === 'startEvent' || semantic.type === 'endEvent') {
			if (semantic.asyncBefore) attrs += ` camunda:asyncBefore="true"`;
			if (semantic.asyncAfter) attrs += ` camunda:asyncAfter="true"`;
			if ((semantic.asyncBefore || semantic.asyncAfter) && semantic.exclusive === false) {
				attrs += ` camunda:exclusive="false"`;
			}
			if (semantic.jobPriority) attrs += ` camunda:jobPriority="${escapeXml(semantic.jobPriority)}"`;
		}

		// Build child content
		const childLines: string[] = [];

		// Documentation
		if (semantic.documentation) {
			childLines.push(`${indent}  <bpmn:documentation>${escapeXml(semantic.documentation)}</bpmn:documentation>`);
		}

		// Extension elements (must precede incoming/outgoing per BPMN 2.0 XSD).
		// Content nests one level under <bpmn:extensionElements> (indent + 4).
		const extLines = serializeExtensionElements(semantic, indent + '    ');
		if (extLines.length > 0) {
			childLines.push(`${indent}  <bpmn:extensionElements>`);
			childLines.push(...extLines);
			childLines.push(`${indent}  </bpmn:extensionElements>`);
		}

		// Incoming/outgoing references
		// IMPORTANT: Artifacts (TextAnnotation, Group) must NOT have incoming/outgoing
		// Only Flow Nodes (tasks, events, gateways, etc.) can have these.
		// Only Sequence Flows count: associations, data associations, and message
		// flows are explicitly excluded (message flows live in collaboration).
		if (!isArtifactNode(semantic)) {
			const incoming = allFlows.filter(f =>
				f.targetRef === semantic.id &&
				f.connectionType !== 'association' &&
				f.connectionType !== 'dataAssociation' &&
				f.connectionType !== 'messageFlow'
			).map(f => f.id);
			const outgoing = allFlows.filter(f =>
				f.sourceRef === semantic.id &&
				f.connectionType !== 'association' &&
				f.connectionType !== 'dataAssociation' &&
				f.connectionType !== 'messageFlow'
			).map(f => f.id);
			incoming.forEach(id => childLines.push(`${indent}  <bpmn:incoming>${id}</bpmn:incoming>`));
			outgoing.forEach(id => childLines.push(`${indent}  <bpmn:outgoing>${id}</bpmn:outgoing>`));
		}

		// Event definitions
		if (semantic.subType && semantic.subType !== 'none') {
			const defLines = serializeEventDefinition(semantic, indent + '  ');
			childLines.push(...defLines);
		}

		// Script content for scriptTask
		if (semantic.type === 'scriptTask' && semantic.script) {
			childLines.push(`${indent}  <bpmn:script><![CDATA[${semantic.script}]]></bpmn:script>`);
		}

		// Data Object dataState
		if (semantic.type === 'dataObject' && semantic.dataState) {
			childLines.push(`${indent}  <bpmn:dataState id="${semantic.id}_ds" name="${escapeXml(semantic.dataState)}"/>`);
		}

		// Complex Gateway activationCondition
		if (semantic.type === 'complexGateway' && semantic.activationCondition) {
			childLines.push(`${indent}  <bpmn:activationCondition xsi:type="bpmn:tFormalExpression">${escapeXml(semantic.activationCondition)}</bpmn:activationCondition>`);
		}

		// Text Annotation text
		if (semantic.type === 'textAnnotation') {
			childLines.push(`${indent}  <bpmn:text>${escapeXml(semantic.name || '')}</bpmn:text>`);
		}

		// Multi-instance loop characteristics
		if (semantic.loopType) {
			if (semantic.loopType === 'standard') {
				childLines.push(`${indent}  <bpmn:standardLoopCharacteristics/>`);
			} else if (semantic.loopType === 'multiInstanceSequential' || semantic.loopType === 'multiInstanceParallel') {
				const isSeq = semantic.loopType === 'multiInstanceSequential';
				let miAttrs = isSeq ? ' isSequential="true"' : '';
				if (semantic.collection) miAttrs += ` camunda:collection="${escapeXml(semantic.collection)}"`;
				if (semantic.elementVariable) miAttrs += ` camunda:elementVariable="${escapeXml(semantic.elementVariable)}"`;
				childLines.push(`${indent}  <bpmn:multiInstanceLoopCharacteristics${miAttrs}/>`);
			}
		}

		// Check if SubProcess has children
		const isSubProcess = semantic.type === 'subProcess';
		const children = isSubProcess ? store.getChildren(semantic.id) : [];
		const childSequenceFlows = isSubProcess ? allFlows.filter(f => {
			if (f.connectionType === 'association' || f.connectionType === 'dataAssociation') return false;
			const source = allSemantics.find(s => s.id === f.sourceRef);
			const target = allSemantics.find(s => s.id === f.targetRef);
			return source?.parentId === semantic.id && target?.parentId === semantic.id;
		}) : [];
		const childAssociations = isSubProcess ? allFlows.filter(f => {
			if (f.connectionType !== 'association') return false;
			const source = allSemantics.find(s => s.id === f.sourceRef);
			const target = allSemantics.find(s => s.id === f.targetRef);
			return source?.parentId === semantic.id || target?.parentId === semantic.id;
		}) : [];

		// Output element
		if (childLines.length > 0 || children.length > 0 || childSequenceFlows.length > 0 || childAssociations.length > 0) {
			lines.push(`${indent}<${tagName} ${attrs}>`);
			childLines.forEach(line => lines.push(line));

			// BPMN 2.0 XSD requires ordering within SubProcess:
			// 1. Flow Elements (tasks, events, gateways, etc. - excluding artifacts)
			// 2. Sequence Flows
			// 3. Artifacts (textAnnotation, group)
			// 4. Associations

			// Step 1: Flow Elements (excluding artifacts)
			const childFlowElements = children.filter(c => !isArtifactNode(c));
			childFlowElements.forEach(child => serializeSemantic(child, indent + '  '));

			// Step 2: Sequence flows
			childSequenceFlows.forEach(flow => serializeFlow(flow, indent + '  '));

			// Step 3: Artifacts
			const childArtifacts = children.filter(c => isArtifactNode(c));
			childArtifacts.forEach(child => serializeSemantic(child, indent + '  '));

			// Step 4: Associations
			childAssociations.forEach(assoc => {
				lines.push(`${indent}  <bpmn:association id="${escapeXml(assoc.id)}" sourceRef="${escapeXml(assoc.sourceRef)}" targetRef="${escapeXml(assoc.targetRef)}"/>`);
			});

			lines.push(`${indent}</${tagName}>`);
		} else {
			lines.push(`${indent}<${tagName} ${attrs}/>`);
		}
	};

	// Helper to serialize event definitions
	const serializeEventDefinition = (semantic: BpmnSemantic, indent: string): string[] => {
		const defLines: string[] = [];
		const subType = semantic.subType;

		if (subType === 'message') {
			let msgAttrs = `id="${semantic.id}_med"`;
			if (semantic.messageRef) msgAttrs += ` messageRef="${escapeXml(semantic.messageRef)}"`;
			if (semantic.messageImplementationType === 'class' && semantic.messageJavaClass) {
				msgAttrs += ` camunda:class="${escapeXml(semantic.messageJavaClass)}"`;
			}
			if (semantic.messageImplementationType === 'expression' && semantic.messageExpression) {
				msgAttrs += ` camunda:expression="${escapeXml(semantic.messageExpression)}"`;
				if (semantic.messageResultVariable) msgAttrs += ` camunda:resultVariable="${escapeXml(semantic.messageResultVariable)}"`;
			}
			defLines.push(`${indent}<bpmn:messageEventDefinition ${msgAttrs}/>`);
		} else if (subType === 'timer') {
			defLines.push(`${indent}<bpmn:timerEventDefinition id="${semantic.id}_ted">`);
			if (semantic.timerType === 'timeDate' && semantic.timerValue) {
				defLines.push(`${indent}  <bpmn:timeDate xsi:type="bpmn:tFormalExpression">${escapeXml(semantic.timerValue)}</bpmn:timeDate>`);
			} else if (semantic.timerType === 'timeDuration' && semantic.timerValue) {
				defLines.push(`${indent}  <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">${escapeXml(semantic.timerValue)}</bpmn:timeDuration>`);
			} else if (semantic.timerType === 'timeCycle' && semantic.timerValue) {
				defLines.push(`${indent}  <bpmn:timeCycle xsi:type="bpmn:tFormalExpression">${escapeXml(semantic.timerValue)}</bpmn:timeCycle>`);
			}
			defLines.push(`${indent}</bpmn:timerEventDefinition>`);
		} else if (subType === 'conditional') {
			let cedAttrs = `id="${semantic.id}_ced"`;
			if (semantic.conditionVariableName) cedAttrs += ` camunda:variableName="${escapeXml(semantic.conditionVariableName)}"`;
			if (semantic.conditionVariableEvents) cedAttrs += ` camunda:variableEvents="${escapeXml(semantic.conditionVariableEvents)}"`;
			defLines.push(`${indent}<bpmn:conditionalEventDefinition ${cedAttrs}>`);
			if (semantic.conditionType === 'script' && semantic.conditionScript) {
				defLines.push(`${indent}  <bpmn:condition xsi:type="bpmn:tFormalExpression" language="${escapeXml(semantic.conditionScriptFormat || 'javascript')}"><![CDATA[${semantic.conditionScript}]]></bpmn:condition>`);
			} else if (semantic.conditionExpression) {
				defLines.push(`${indent}  <bpmn:condition xsi:type="bpmn:tFormalExpression">${escapeXml(semantic.conditionExpression)}</bpmn:condition>`);
			}
			defLines.push(`${indent}</bpmn:conditionalEventDefinition>`);
		} else if (subType === 'signal') {
			let sigAttrs = `id="${semantic.id}_sed"`;
			if (semantic.signalRef) sigAttrs += ` signalRef="${escapeXml(semantic.signalRef)}"`;
			defLines.push(`${indent}<bpmn:signalEventDefinition ${sigAttrs}/>`);
		} else if (subType === 'error') {
			let errAttrs = `id="${semantic.id}_eed"`;
			if (semantic.errorRef) errAttrs += ` errorRef="${escapeXml(semantic.errorRef)}"`;
			defLines.push(`${indent}<bpmn:errorEventDefinition ${errAttrs}/>`);
		} else if (subType === 'escalation') {
			let escAttrs = `id="${semantic.id}_esed"`;
			if (semantic.escalationRef) escAttrs += ` escalationRef="${escapeXml(semantic.escalationRef)}"`;
			defLines.push(`${indent}<bpmn:escalationEventDefinition ${escAttrs}/>`);
		} else if (subType === 'terminate') {
			defLines.push(`${indent}<bpmn:terminateEventDefinition id="${semantic.id}_ted"/>`);
		} else if (subType === 'link') {
			let linkAttrs = `id="${semantic.id}_led"`;
			if (semantic.linkName) linkAttrs += ` name="${escapeXml(semantic.linkName)}"`;
			defLines.push(`${indent}<bpmn:linkEventDefinition ${linkAttrs}/>`);
		} else if (subType === 'compensation') {
			let compAttrs = `id="${semantic.id}_coed"`;
			if (semantic.compensationActivityRef) compAttrs += ` activityRef="${escapeXml(semantic.compensationActivityRef)}"`;
			if (semantic.compensationWaitForCompletion) compAttrs += ` waitForCompletion="true"`;
			defLines.push(`${indent}<bpmn:compensateEventDefinition ${compAttrs}/>`);
		}

		return defLines;
	};

	// Helper to serialize extension elements
	const serializeExtensionElements = (semantic: BpmnSemantic, indent: string): string[] => {
		const extLines: string[] = [];

		// Field injections (serviceTask with Java Class)
		if (semantic.fieldInjections && semantic.fieldInjections.length > 0) {
			semantic.fieldInjections.forEach(field => {
				extLines.push(`${indent}<camunda:field name="${escapeXml(field.name)}">`);
				if (field.type === 'expression') {
					extLines.push(`${indent}  <camunda:expression>${escapeXml(field.value)}</camunda:expression>`);
				} else {
					extLines.push(`${indent}  <camunda:string>${escapeXml(field.value)}</camunda:string>`);
				}
				extLines.push(`${indent}</camunda:field>`);
			});
		}

		// Failed job retry time cycle. The parser reads this into retryTimeCycle
		// (the field the properties panel binds to); failedJobRetryTimeCycle is
		// accepted as a fallback for models populated by other code paths.
		const retryCycle = semantic.retryTimeCycle || semantic.failedJobRetryTimeCycle;
		if (retryCycle) {
			extLines.push(`${indent}<camunda:failedJobRetryTimeCycle>${escapeXml(retryCycle)}</camunda:failedJobRetryTimeCycle>`);
		}

		// Execution listeners
		if (semantic.executionListeners && semantic.executionListeners.length > 0) {
			semantic.executionListeners.forEach(listener => {
				if (listener.listenerType === 'script') {
					extLines.push(`${indent}<camunda:executionListener event="${listener.event}">`);
					if (listener.scriptType === 'external' && listener.resource) {
						extLines.push(`${indent}  <camunda:script scriptFormat="${escapeXml(listener.scriptFormat || 'groovy')}" resource="${escapeXml(listener.resource)}"/>`);
					} else if (listener.script) {
						extLines.push(`${indent}  <camunda:script scriptFormat="${escapeXml(listener.scriptFormat || 'groovy')}"><![CDATA[${listener.script}]]></camunda:script>`);
					}
					extLines.push(`${indent}</camunda:executionListener>`);
				} else if (listener.listenerType === 'class' && listener.javaClass) {
					if (listener.fields && listener.fields.length > 0) {
						extLines.push(`${indent}<camunda:executionListener event="${listener.event}" class="${escapeXml(listener.javaClass)}">`);
						listener.fields.forEach(field => {
							extLines.push(`${indent}  <camunda:field name="${escapeXml(field.name)}">`);
							if (field.type === 'expression') {
								extLines.push(`${indent}    <camunda:expression>${escapeXml(field.value)}</camunda:expression>`);
							} else {
								extLines.push(`${indent}    <camunda:string>${escapeXml(field.value)}</camunda:string>`);
							}
							extLines.push(`${indent}  </camunda:field>`);
						});
						extLines.push(`${indent}</camunda:executionListener>`);
					} else {
						extLines.push(`${indent}<camunda:executionListener event="${listener.event}" class="${escapeXml(listener.javaClass)}"/>`);
					}
				} else if (listener.listenerType === 'expression' && listener.expression) {
					extLines.push(`${indent}<camunda:executionListener event="${listener.event}" expression="${escapeXml(listener.expression)}"/>`);
				}
			});
		}

		// Task listeners (user tasks). Mirrors execution listeners, plus the
		// delegateExpression type.
		if (semantic.taskListeners && semantic.taskListeners.length > 0) {
			semantic.taskListeners.forEach(listener => {
				if (listener.listenerType === 'script') {
					extLines.push(`${indent}<camunda:taskListener event="${listener.event}">`);
					if (listener.scriptType === 'external' && listener.resource) {
						extLines.push(`${indent}  <camunda:script scriptFormat="${escapeXml(listener.scriptFormat || 'groovy')}" resource="${escapeXml(listener.resource)}"/>`);
					} else if (listener.script) {
						extLines.push(`${indent}  <camunda:script scriptFormat="${escapeXml(listener.scriptFormat || 'groovy')}"><![CDATA[${listener.script}]]></camunda:script>`);
					}
					extLines.push(`${indent}</camunda:taskListener>`);
				} else if (listener.listenerType === 'class' && listener.javaClass) {
					if (listener.fields && listener.fields.length > 0) {
						extLines.push(`${indent}<camunda:taskListener event="${listener.event}" class="${escapeXml(listener.javaClass)}">`);
						listener.fields.forEach(field => {
							extLines.push(`${indent}  <camunda:field name="${escapeXml(field.name)}">`);
							if (field.type === 'expression') {
								extLines.push(`${indent}    <camunda:expression>${escapeXml(field.value)}</camunda:expression>`);
							} else {
								extLines.push(`${indent}    <camunda:string>${escapeXml(field.value)}</camunda:string>`);
							}
							extLines.push(`${indent}  </camunda:field>`);
						});
						extLines.push(`${indent}</camunda:taskListener>`);
					} else {
						extLines.push(`${indent}<camunda:taskListener event="${listener.event}" class="${escapeXml(listener.javaClass)}"/>`);
					}
				} else if (listener.listenerType === 'expression' && listener.expression) {
					extLines.push(`${indent}<camunda:taskListener event="${listener.event}" expression="${escapeXml(listener.expression)}"/>`);
				} else if (listener.listenerType === 'delegateExpression' && listener.delegateExpression) {
					extLines.push(`${indent}<camunda:taskListener event="${listener.event}" delegateExpression="${escapeXml(listener.delegateExpression)}"/>`);
				}
			});
		}

		// Input/Output parameters
		if ((semantic.inputParameters && semantic.inputParameters.length > 0) ||
			(semantic.outputParameters && semantic.outputParameters.length > 0)) {
			extLines.push(`${indent}<camunda:inputOutput>`);
			if (semantic.inputParameters) {
				semantic.inputParameters.forEach(param => {
					if (param.type === 'text') {
						extLines.push(`${indent}  <camunda:inputParameter name="${escapeXml(param.name)}">${escapeXml(param.value || '')}</camunda:inputParameter>`);
					} else if (param.type === 'script' && param.script) {
						extLines.push(`${indent}  <camunda:inputParameter name="${escapeXml(param.name)}">`);
						extLines.push(`${indent}    <camunda:script scriptFormat="${escapeXml(param.scriptFormat || 'groovy')}"><![CDATA[${param.script}]]></camunda:script>`);
						extLines.push(`${indent}  </camunda:inputParameter>`);
					} else if (param.type === 'list' && param.listValues) {
						extLines.push(`${indent}  <camunda:inputParameter name="${escapeXml(param.name)}">`);
						extLines.push(`${indent}    <camunda:list>`);
						param.listValues.forEach(v => extLines.push(`${indent}      <camunda:value>${escapeXml(v)}</camunda:value>`));
						extLines.push(`${indent}    </camunda:list>`);
						extLines.push(`${indent}  </camunda:inputParameter>`);
					} else if (param.type === 'map' && param.mapEntries) {
						extLines.push(`${indent}  <camunda:inputParameter name="${escapeXml(param.name)}">`);
						extLines.push(`${indent}    <camunda:map>`);
						param.mapEntries.forEach(e => extLines.push(`${indent}      <camunda:entry key="${escapeXml(e.key)}">${escapeXml(e.value)}</camunda:entry>`));
						extLines.push(`${indent}    </camunda:map>`);
						extLines.push(`${indent}  </camunda:inputParameter>`);
					}
				});
			}
			if (semantic.outputParameters) {
				semantic.outputParameters.forEach(param => {
					if (param.type === 'text') {
						extLines.push(`${indent}  <camunda:outputParameter name="${escapeXml(param.name)}">${escapeXml(param.value || '')}</camunda:outputParameter>`);
					} else if (param.type === 'script' && param.script) {
						extLines.push(`${indent}  <camunda:outputParameter name="${escapeXml(param.name)}">`);
						extLines.push(`${indent}    <camunda:script scriptFormat="${escapeXml(param.scriptFormat || 'groovy')}"><![CDATA[${param.script}]]></camunda:script>`);
						extLines.push(`${indent}  </camunda:outputParameter>`);
					} else if (param.type === 'list' && param.listValues) {
						extLines.push(`${indent}  <camunda:outputParameter name="${escapeXml(param.name)}">`);
						extLines.push(`${indent}    <camunda:list>`);
						param.listValues.forEach(v => extLines.push(`${indent}      <camunda:value>${escapeXml(v)}</camunda:value>`));
						extLines.push(`${indent}    </camunda:list>`);
						extLines.push(`${indent}  </camunda:outputParameter>`);
					} else if (param.type === 'map' && param.mapEntries) {
						extLines.push(`${indent}  <camunda:outputParameter name="${escapeXml(param.name)}">`);
						extLines.push(`${indent}    <camunda:map>`);
						param.mapEntries.forEach(e => extLines.push(`${indent}      <camunda:entry key="${escapeXml(e.key)}">${escapeXml(e.value)}</camunda:entry>`));
						extLines.push(`${indent}    </camunda:map>`);
						extLines.push(`${indent}  </camunda:outputParameter>`);
					}
				});
			}
			extLines.push(`${indent}</camunda:inputOutput>`);
		}

		// Extension properties
		if (semantic.extensionProperties && semantic.extensionProperties.length > 0) {
			extLines.push(`${indent}<camunda:properties>`);
			semantic.extensionProperties.forEach(prop => {
				extLines.push(`${indent}  <camunda:property name="${escapeXml(prop.name)}" value="${escapeXml(prop.value)}"/>`);
			});
			extLines.push(`${indent}</camunda:properties>`);
		}

		// Call Activity in/out mappings
		if (semantic.type === 'callActivity') {
			if (semantic.inMappings && semantic.inMappings.length > 0) {
				semantic.inMappings.forEach(m => {
					extLines.push(`${indent}<camunda:in source="${escapeXml(m.source)}" target="${escapeXml(m.target)}"/>`);
				});
			}
			if (semantic.outMappings && semantic.outMappings.length > 0) {
				semantic.outMappings.forEach(m => {
					extLines.push(`${indent}<camunda:out source="${escapeXml(m.source)}" target="${escapeXml(m.target)}"/>`);
				});
			}
		}

		// Unmodeled extension elements preserved verbatim on parse (e.g.
		// camunda:taskListener). Re-indent each captured block to sit under the
		// current <bpmn:extensionElements>.
		if (semantic.unknownExtensions && semantic.unknownExtensions.length > 0) {
			semantic.unknownExtensions.forEach(block => {
				block.split('\n').forEach(line => {
					extLines.push(line.length > 0 ? `${indent}${line}` : line);
				});
			});
		}

		return extLines;
	};

	// Helper to serialize a flow
	const serializeFlow = (flow: BpmnFlowSemantic, indent: string): void => {
		// Skip data associations, text annotation associations, and message flows
		// (message flows live inside <bpmn:collaboration>, not inside <bpmn:process>).
		if (flow.connectionType === 'dataAssociation'
			|| flow.connectionType === 'association'
			|| flow.connectionType === 'messageFlow') return;

		let attrs = `id="${escapeXml(flow.id)}"`;
		attrs += ` sourceRef="${escapeXml(flow.sourceRef)}"`;
		attrs += ` targetRef="${escapeXml(flow.targetRef)}"`;
		if (flow.name) attrs += ` name="${escapeXmlAttr(flow.name)}"`;

		// Condition expression
		if (flow.conditionType && (flow.conditionExpression || flow.conditionScript)) {
			lines.push(`${indent}<bpmn:sequenceFlow ${attrs}>`);
			if (flow.conditionType === 'script' && flow.conditionScript) {
				lines.push(`${indent}  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression" language="${escapeXml(flow.conditionScriptFormat || 'javascript')}"><![CDATA[${flow.conditionScript}]]></bpmn:conditionExpression>`);
			} else if (flow.conditionExpression) {
				lines.push(`${indent}  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${escapeXml(flow.conditionExpression)}</bpmn:conditionExpression>`);
			}
			lines.push(`${indent}</bpmn:sequenceFlow>`);
		} else {
			lines.push(`${indent}<bpmn:sequenceFlow ${attrs}/>`);
		}
	};

	// =========================================================================
	// Per-process emit
	// =========================================================================
	// In a collaboration the file holds one <bpmn:process> per pool. In a
	// single-process file there is one <bpmn:process> built from modelData's
	// primary fields. Within each process we emit, in BPMN 2.0 XSD order:
	//   1. Flow Elements (nodes)
	//   2. Sequence Flows
	//   3. Artifacts (text annotations, groups)
	//   4. Associations / Data associations

	// Walk parentId up to find the owning pool, if any. A node directly under a
	// pool returns that pool; a node inside a sub-process inside a pool walks
	// through the sub-process and returns the pool.
	const ownerPoolOf = (s: BpmnSemantic): string | undefined => {
		let cur: BpmnSemantic | undefined = s;
		while (cur && cur.parentId) {
			const parent = allSemantics.find(x => x.id === cur!.parentId);
			if (!parent) return undefined;
			if (parent.type === 'pool') return parent.id;
			cur = parent;
		}
		return undefined;
	};

	// True when a semantic sits at the root of its <bpmn:process> (not inside a
	// sub-process). With multi-pool, "root" means parent is undefined OR a pool.
	const isProcessRoot = (s: BpmnSemantic): boolean => {
		if (!s.parentId) return true;
		const parent = allSemantics.find(x => x.id === s.parentId);
		return parent?.type === 'pool';
	};

	type EmitProcess = {
		id: string;
		name: string;
		isExecutable: boolean;
		candidateStarterGroups?: string;
		candidateStarterUsers?: string;
		ownerPoolId: string | undefined;
	};

	const processesMeta = modelData.processes || [];
	const processesToEmit: EmitProcess[] = pools.length > 0
		? pools.map(pool => {
			const procId = pool.processRef || `${pool.id}_process`;
			const meta = processesMeta.find(p => p.id === procId);
			return {
				id: procId,
				name: meta?.name ?? '',
				isExecutable: meta?.isExecutable ?? true,
				// Pool Properties UI is the source of truth for candidateStarter*;
				// fall back to per-process metadata if the pool didn't carry them.
				candidateStarterGroups: pool.candidateStarterGroups || meta?.candidateStarterGroups,
				candidateStarterUsers: pool.candidateStarterUsers || meta?.candidateStarterUsers,
				ownerPoolId: pool.id,
			};
		})
		: [{
			id: modelData.processId || 'Process_1',
			name: modelData.processName || '',
			isExecutable: modelData.isExecutable ?? true,
			candidateStarterGroups: modelData.candidateStarterGroups,
			candidateStarterUsers: modelData.candidateStarterUsers,
			ownerPoolId: undefined,
		}];

	processesToEmit.forEach((proc, idx) => {
		let processAttrs = `id="${escapeXml(proc.id)}" name="${escapeXmlAttr(proc.name)}" isExecutable="${proc.isExecutable}"`;
		if (proc.candidateStarterGroups) {
			processAttrs += ` camunda:candidateStarterGroups="${escapeXmlAttr(proc.candidateStarterGroups)}"`;
		}
		if (proc.candidateStarterUsers) {
			processAttrs += ` camunda:candidateStarterUsers="${escapeXmlAttr(proc.candidateStarterUsers)}"`;
		}
		lines.push(`  <bpmn:process ${processAttrs}>`);

		// LaneSet: lanes whose parentPoolId points at this pool (no-pool case
		// has no lanes by construction — lanes always belong to a pool).
		const ownLanes = proc.ownerPoolId
			? lanes.filter(l => l.parentPoolId === proc.ownerPoolId)
			: [];
		if (ownLanes.length > 0) {
			lines.push(`    <bpmn:laneSet id="LaneSet_${idx + 1}">`);
			ownLanes.forEach(lane => {
				let laneAttrs = `id="${escapeXml(lane.id)}"`;
				if (lane.name) laneAttrs += ` name="${escapeXmlAttr(lane.name)}"`;
				if (lane.flowNodeRefs && lane.flowNodeRefs.length > 0) {
					lines.push(`      <bpmn:lane ${laneAttrs}>`);
					lane.flowNodeRefs.forEach(ref => {
						lines.push(`        <bpmn:flowNodeRef>${ref}</bpmn:flowNodeRef>`);
					});
					lines.push(`      </bpmn:lane>`);
				} else {
					lines.push(`      <bpmn:lane ${laneAttrs}/>`);
				}
			});
			lines.push(`    </bpmn:laneSet>`);
		}

		// Root-level elements that belong to this process.
		const ownedRoots = allSemantics.filter(s => {
			if (s.type === 'pool' || s.type === 'lane') return false;
			if (!isProcessRoot(s)) return false;
			return ownerPoolOf(s) === proc.ownerPoolId;
		});

		// --- Group 1: Flow Elements (Nodes) ---
		ownedRoots.filter(s => !isArtifactNode(s)).forEach(s => serializeSemantic(s, '    '));

		// --- Group 2: Sequence Flows ---
		// Both endpoints must be at process root within the same pool. Cross-pool
		// edges are messageFlows and were already emitted under <collaboration>.
		allFlows.filter(f => {
			if (f.connectionType === 'dataAssociation'
				|| f.connectionType === 'association'
				|| f.connectionType === 'messageFlow') return false;
			const source = allSemantics.find(s => s.id === f.sourceRef);
			const target = allSemantics.find(s => s.id === f.targetRef);
			if (!source || !target) return false;
			if (!isProcessRoot(source) || !isProcessRoot(target)) return false;
			return ownerPoolOf(source) === proc.ownerPoolId
				&& ownerPoolOf(target) === proc.ownerPoolId;
		}).forEach(flow => serializeFlow(flow, '    '));

		// --- Group 3: Artifacts (Nodes) ---
		ownedRoots.filter(s => isArtifactNode(s)).forEach(s => serializeSemantic(s, '    '));

		// --- Group 4: Associations (Edges) ---
		allFlows.filter(f => {
			if (f.connectionType !== 'association') return false;
			const source = allSemantics.find(s => s.id === f.sourceRef);
			const target = allSemantics.find(s => s.id === f.targetRef);
			if (!source || !target) return false;
			if (!isProcessRoot(source) || !isProcessRoot(target)) return false;
			return ownerPoolOf(source) === proc.ownerPoolId
				&& ownerPoolOf(target) === proc.ownerPoolId;
		}).forEach(assoc => {
			lines.push(`    <bpmn:association id="${escapeXml(assoc.id)}" sourceRef="${escapeXml(assoc.sourceRef)}" targetRef="${escapeXml(assoc.targetRef)}"/>`);
		});

		// Data associations
		allFlows.filter(f => {
			if (f.connectionType !== 'dataAssociation') return false;
			const source = allSemantics.find(s => s.id === f.sourceRef);
			if (!source) return false;
			return ownerPoolOf(source) === proc.ownerPoolId;
		}).forEach(assoc => {
			lines.push(`    <bpmn:dataInputAssociation id="${escapeXml(assoc.id)}">`);
			lines.push(`      <bpmn:sourceRef>${escapeXml(assoc.sourceRef)}</bpmn:sourceRef>`);
			lines.push(`      <bpmn:targetRef>${escapeXml(assoc.targetRef)}</bpmn:targetRef>`);
			lines.push(`    </bpmn:dataInputAssociation>`);
		});

		lines.push(`  </bpmn:process>`);
	});

	// =========================================================================
	// PASS 2: DI Layer
	// =========================================================================

	const diagrams = store.getAllDiagrams();
	const diagram = diagrams[0];
	const diagramId = diagram?.id || 'BPMNDiagram_1';
	const planeId = diagram?.plane?.id || 'BPMNPlane_1';
	// In a collaboration the BPMNPlane references the Collaboration, not any
	// individual process; bpmn-js / Camunda rely on this to render multi-pool
	// diagrams. Fall back to the process id only when there are no pools.
	const planeBpmnElement = pools.length > 0
		? 'Collaboration_1'
		: (diagram?.plane?.bpmnElement || modelData.processId);

	lines.push(`  <bpmndi:BPMNDiagram id="${diagramId}">`);
	lines.push(`    <bpmndi:BPMNPlane id="${planeId}" bpmnElement="${planeBpmnElement}">`);

	// Helper to ensure ID starts with a letter (NCName compliance)
	const toValidId = (id: string): string => {
		// If ID starts with a digit, prefix with underscore
		if (/^[0-9]/.test(id)) {
			return `_${id}`;
		}
		return id;
	};

	// Shapes
	store.getAllShapes().forEach(shape => {
		const shapeId = toValidId(shape.id);
		let shapeAttrs = `id="${shapeId}" bpmnElement="${shape.bpmnElement}"`;

		// Add isExpanded for SubProcess
		const semantic = store.getSemantic(shape.bpmnElement);
		if (semantic?.type === 'subProcess') {
			shapeAttrs += ` isExpanded="${shape.isExpanded ?? false}"`;
		}

		// Add isHorizontal for Pool/Lane
		if (semantic?.type === 'pool' || semantic?.type === 'lane') {
			shapeAttrs += ` isHorizontal="${shape.isHorizontal ?? true}"`;
		}

		lines.push(`      <bpmndi:BPMNShape ${shapeAttrs}>`);
		lines.push(`        <dc:Bounds x="${Math.round(shape.bounds.x)}" y="${Math.round(shape.bounds.y)}" width="${Math.round(shape.bounds.width)}" height="${Math.round(shape.bounds.height)}"/>`);

		// Label bounds
		if (shape.label) {
			const labelX = Math.round(shape.bounds.x + (shape.label.offsetX ?? 0));
			const labelY = Math.round(shape.bounds.y + (shape.label.offsetY ?? 0));
			const labelWidth = Math.round(shape.label.width ?? 100);
			lines.push(`        <bpmndi:BPMNLabel>`);
			lines.push(`          <dc:Bounds x="${labelX}" y="${labelY}" width="${labelWidth}" height="14"/>`);
			lines.push(`        </bpmndi:BPMNLabel>`);
		}

		lines.push(`      </bpmndi:BPMNShape>`);
	});

	// Edges
	store.getAllEdges().forEach(edge => {
		const edgeId = toValidId(edge.id);
		lines.push(`      <bpmndi:BPMNEdge id="${edgeId}" bpmnElement="${edge.bpmnElement}">`);

		let points: Point[] = [];

		if (edge.waypoints && edge.waypoints.length >= 2) {
			// Use existing waypoints
			points = edge.waypoints;
		} else {
			// Fallback: calculate Manhattan routing (same as rendering logic)
			// This ensures saved XML matches the displayed routing
			const flow = store.getFlow(edge.bpmnElement);
			if (flow) {
				const sShapes = store.getShapesForSemantic(flow.sourceRef);
				const tShapes = store.getShapesForSemantic(flow.targetRef);

				if (sShapes[0] && tShapes[0]) {
					const sBounds = sShapes[0].bounds;
					const tBounds = tShapes[0].bounds;
					const isAssoc = flow.connectionType === 'association';

					if (isAssoc) {
						// Association: straight line with perimeter intersection
						const sSem = store.getSemantic(flow.sourceRef);
						const tSem = store.getSemantic(flow.targetRef);
						const isGwS = sSem && sSem.type.toLowerCase().includes('gateway');
						const isGwT = tSem && tSem.type.toLowerCase().includes('gateway');

						const start = getPerimeterPointForSave(sBounds, getCenterForSave(tBounds), !!isGwS);
						const end = getPerimeterPointForSave(tBounds, getCenterForSave(sBounds), !!isGwT);
						points = [start, end];
					} else {
						// Sequence Flow: Manhattan routing with buffer
						const sC = getCenterForSave(sBounds);
						const tC = getCenterForSave(tBounds);
						const start = getBestCompassPointForSave(sBounds, tC);
						const end = getBestCompassPointForSave(tBounds, sC);

						const BUFFER = 20;
						// Determine if starting horizontally (Y close to center Y)
						const isHorzStart = Math.abs(start.y - sC.y) < 1;

						const p1 = { x: 0, y: 0 };
						const p2 = { x: 0, y: 0 };

						if (isHorzStart) {
							// Horizontal exit
							const dirX = end.x > start.x ? 1 : -1;
							const turnX = start.x + (dirX * BUFFER);
							p1.x = turnX; p1.y = start.y;
							p2.x = turnX; p2.y = end.y;
						} else {
							// Vertical exit
							const dirY = end.y > start.y ? 1 : -1;
							const turnY = start.y + (dirY * BUFFER);
							p1.x = start.x; p1.y = turnY;
							p2.x = end.x; p2.y = turnY;
						}
						points = [start, p1, p2, end];
					}
				}
			}
		}

		// Output calculated or existing waypoints (round to avoid floating point issues)
		points.forEach(wp => {
			lines.push(`        <di:waypoint x="${Math.round(wp.x)}" y="${Math.round(wp.y)}"/>`);
		});

		// Label bounds
		if (edge.label) {
			const labelX = Math.round(edge.label.offsetX ?? 0);
			const labelY = Math.round(edge.label.offsetY ?? 0);
			const labelWidth = Math.round(edge.label.width ?? 100);
			lines.push(`        <bpmndi:BPMNLabel>`);
			lines.push(`          <dc:Bounds x="${labelX}" y="${labelY}" width="${labelWidth}" height="14"/>`);
			lines.push(`        </bpmndi:BPMNLabel>`);
		}

		lines.push(`      </bpmndi:BPMNEdge>`);
	});

	lines.push('    </bpmndi:BPMNPlane>');
	lines.push('  </bpmndi:BPMNDiagram>');
	lines.push('</bpmn:definitions>');

	return lines.join('\n');
}

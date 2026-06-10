/*
 * Copyright (c) 2022 MintJams Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.rt.cms.internal.bpm.event;

import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.impl.history.event.HistoricIncidentEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoricVariableUpdateEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoryEvent;
import org.camunda.bpm.engine.impl.history.event.HistoryEventTypes;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.camunda.bpm.engine.runtime.Incident;
import org.camunda.bpm.engine.runtime.VariableInstance;

/**
 * Bridges the engine's history stream to EventAdmin for the two domains that
 * have no execution/task listener equivalent: process variables and incidents.
 *
 * <p>Process, activity and user task lifecycle events are deliberately
 * <em>not</em> handled here; they are emitted by
 * {@link EventAdminBpmnParseListener} so that a single source of truth exists
 * per event and no notification is published twice. This handler is registered
 * <em>alongside</em> the engine's database history handler (via
 * {@code CompositeDbHistoryEventHandler}), so persisting history is unaffected.
 *
 * <p>Because history events are produced by the engine only at or above the
 * configured history level, variable and incident notifications follow that
 * level: with {@code history: none} they are not produced at all, and the most
 * fine-grained variable-update detail requires {@code history: full}. The
 * level-independent lifecycle/flow events remain available regardless. See
 * {@code documents/bpm-eventadmin.md}.
 */
public class EventAdminHistoryEventHandler implements HistoryEventHandler {

	private final BpmEventDispatcher fDispatcher;

	public EventAdminHistoryEventHandler(BpmEventDispatcher dispatcher) {
		fDispatcher = dispatcher;
	}

	@Override
	public void handleEvent(HistoryEvent historyEvent) {
		if (historyEvent instanceof HistoricVariableUpdateEventEntity) {
			handleVariableEvent((HistoricVariableUpdateEventEntity) historyEvent);
			return;
		}

		if (historyEvent instanceof HistoricIncidentEventEntity) {
			handleIncidentEvent((HistoricIncidentEventEntity) historyEvent);
			return;
		}

		// Process/activity/task history events are intentionally ignored here;
		// they are emitted by the BPMN parse listener instead.
	}

	@Override
	public void handleEvents(List<HistoryEvent> historyEvents) {
		if (historyEvents == null) {
			return;
		}
		for (HistoryEvent historyEvent : historyEvents) {
			handleEvent(historyEvent);
		}
	}

	private void handleVariableEvent(HistoricVariableUpdateEventEntity event) {
		String action = variableAction(event.getEventType());
		if (action == null) {
			return;
		}

		Map<String, Object> properties = fDispatcher.newProperties();
		BpmEventDispatcher.put(properties, "variableInstanceId", event.getVariableInstanceId());
		BpmEventDispatcher.put(properties, "variableName", event.getVariableName());
		BpmEventDispatcher.put(properties, "serializerName", event.getSerializerName());
		BpmEventDispatcher.put(properties, "scopeActivityInstanceId", event.getScopeActivityInstanceId());
		BpmEventDispatcher.put(properties, "processInstanceId", event.getProcessInstanceId());
		BpmEventDispatcher.put(properties, "processDefinitionId", event.getProcessDefinitionId());
		BpmEventDispatcher.put(properties, "executionId", event.getExecutionId());
		fDispatcher.dispatchOnCommit(fDispatcher.topic(VariableInstance.class, action), properties);
	}

	private void handleIncidentEvent(HistoricIncidentEventEntity event) {
		String action = incidentAction(event.getEventType());
		if (action == null) {
			return;
		}

		Map<String, Object> properties = fDispatcher.newProperties();
		BpmEventDispatcher.put(properties, "incidentId", event.getId());
		BpmEventDispatcher.put(properties, "incidentType", event.getIncidentType());
		BpmEventDispatcher.put(properties, "incidentMessage", event.getIncidentMessage());
		BpmEventDispatcher.put(properties, "activityId", event.getActivityId());
		BpmEventDispatcher.put(properties, "causeIncidentId", event.getCauseIncidentId());
		BpmEventDispatcher.put(properties, "processInstanceId", event.getProcessInstanceId());
		BpmEventDispatcher.put(properties, "processDefinitionId", event.getProcessDefinitionId());
		BpmEventDispatcher.put(properties, "executionId", event.getExecutionId());
		BpmEventDispatcher.put(properties, "tenantId", event.getTenantId());
		fDispatcher.dispatchOnCommit(fDispatcher.topic(Incident.class, action), properties);
	}

	/**
	 * Maps a variable history event type to an action. The fine-grained
	 * {@code UPDATE_DETAIL} record (only produced at {@code history: full}) is
	 * skipped so a single value change yields at most one {@code UPDATED} event.
	 */
	private String variableAction(String eventType) {
		if (HistoryEventTypes.VARIABLE_INSTANCE_CREATE.getEventName().equals(eventType)) {
			return BpmEventDispatcher.ACTION_CREATED;
		}
		if (HistoryEventTypes.VARIABLE_INSTANCE_UPDATE.getEventName().equals(eventType)) {
			return BpmEventDispatcher.ACTION_UPDATED;
		}
		if (HistoryEventTypes.VARIABLE_INSTANCE_MIGRATE.getEventName().equals(eventType)) {
			return BpmEventDispatcher.ACTION_MIGRATED;
		}
		if (HistoryEventTypes.VARIABLE_INSTANCE_DELETE.getEventName().equals(eventType)) {
			return BpmEventDispatcher.ACTION_DELETED;
		}
		return null;
	}

	private String incidentAction(String eventType) {
		if (HistoryEventTypes.INCIDENT_CREATE.getEventName().equals(eventType)) {
			return BpmEventDispatcher.ACTION_CREATED;
		}
		if (HistoryEventTypes.INCIDENT_RESOLVE.getEventName().equals(eventType)) {
			return BpmEventDispatcher.ACTION_RESOLVED;
		}
		if (HistoryEventTypes.INCIDENT_DELETE.getEventName().equals(eventType)) {
			return BpmEventDispatcher.ACTION_DELETED;
		}
		if (HistoryEventTypes.INCIDENT_MIGRATE.getEventName().equals(eventType)) {
			return BpmEventDispatcher.ACTION_MIGRATED;
		}
		if (HistoryEventTypes.INCIDENT_UPDATE.getEventName().equals(eventType)) {
			return BpmEventDispatcher.ACTION_UPDATED;
		}
		return null;
	}

}

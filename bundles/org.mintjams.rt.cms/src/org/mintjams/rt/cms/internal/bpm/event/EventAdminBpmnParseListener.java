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

import java.util.Map;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.pvm.process.TransitionImpl;
import org.camunda.bpm.engine.impl.task.TaskDefinition;
import org.camunda.bpm.engine.impl.util.xml.Element;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;

/**
 * Injects EventAdmin-bridging listeners into <em>every</em> deployed process at
 * parse time, so no BPMN authoring is required to observe the process lifecycle.
 *
 * <p>The listeners are wired here rather than via the history event handler
 * because BPMN parse listeners are independent of the configured history level:
 * the lifecycle and flow events below are emitted even when {@code history} is
 * set to {@code none}. Variable and incident events, which have no execution
 * listener equivalent, are handled separately by
 * {@link EventAdminHistoryEventHandler} to avoid duplicate notifications.
 *
 * <p>Coverage:
 * <ul>
 *   <li>process instance start/end (on the process definition scope);</li>
 *   <li>start/end of every flow node — events, tasks, gateways, sub-processes,
 *       call activities and transactions;</li>
 *   <li>take of every sequence flow;</li>
 *   <li>the full user task lifecycle (create, assignment, complete, update,
 *       delete).</li>
 * </ul>
 */
public class EventAdminBpmnParseListener extends AbstractBpmnParseListener {

	/**
	 * Marker stored on an activity once its execution listeners have been
	 * attached, guarding against the (rare) case of an activity being visited by
	 * more than one parse callback.
	 */
	private static final String LISTENERS_ATTACHED = EventAdminBpmnParseListener.class.getName() + "#attached";

	private final BpmEventDispatcher fDispatcher;

	public EventAdminBpmnParseListener(BpmEventDispatcher dispatcher) {
		fDispatcher = dispatcher;
	}

	@Override
	public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
		processDefinition.addExecutionListener(ExecutionListener.EVENTNAME_START,
				new ProcessExecutionListener(fDispatcher, BpmEventDispatcher.ACTION_STARTED));
		processDefinition.addExecutionListener(ExecutionListener.EVENTNAME_END,
				new ProcessExecutionListener(fDispatcher, BpmEventDispatcher.ACTION_ENDED));
	}

	@Override
	public void parseSequenceFlow(Element sequenceFlowElement, ScopeImpl scopeElement, TransitionImpl transition) {
		// Transitions only ever fire the "take" event.
		transition.addExecutionListener(new SequenceFlowExecutionListener(fDispatcher));
	}

	@Override
	public void parseStartEvent(Element startEventElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseEndEvent(Element endEventElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseExclusiveGateway(Element exclusiveGwElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseInclusiveGateway(Element inclusiveGwElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseParallelGateway(Element parallelGwElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseEventBasedGateway(Element eventBasedGwElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseTask(Element taskElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseManualTask(Element manualTaskElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseScriptTask(Element scriptTaskElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseServiceTask(Element serviceTaskElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseBusinessRuleTask(Element businessRuleTaskElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseSendTask(Element sendTaskElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseReceiveTask(Element receiveTaskElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);

		TaskDefinition taskDefinition = ((UserTaskActivityBehavior) activity.getActivityBehavior()).getTaskDefinition();
		taskDefinition.addTaskListener(TaskListener.EVENTNAME_CREATE,
				new UserTaskListener(fDispatcher, BpmEventDispatcher.ACTION_CREATED));
		taskDefinition.addTaskListener(TaskListener.EVENTNAME_ASSIGNMENT,
				new UserTaskListener(fDispatcher, BpmEventDispatcher.ACTION_ASSIGNED));
		taskDefinition.addTaskListener(TaskListener.EVENTNAME_COMPLETE,
				new UserTaskListener(fDispatcher, BpmEventDispatcher.ACTION_COMPLETED));
		taskDefinition.addTaskListener(TaskListener.EVENTNAME_UPDATE,
				new UserTaskListener(fDispatcher, BpmEventDispatcher.ACTION_UPDATED));
		taskDefinition.addTaskListener(TaskListener.EVENTNAME_DELETE,
				new UserTaskListener(fDispatcher, BpmEventDispatcher.ACTION_DELETED));
	}

	@Override
	public void parseSubProcess(Element subProcessElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseTransaction(Element transactionElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseCallActivity(Element callActivityElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseIntermediateThrowEvent(Element intermediateEventElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseIntermediateCatchEvent(Element intermediateEventElement, ScopeImpl scope, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	@Override
	public void parseBoundaryEvent(Element boundaryEventElement, ScopeImpl scopeElement, ActivityImpl activity) {
		addActivityListeners(activity);
	}

	private void addActivityListeners(ActivityImpl activity) {
		if (activity == null || activity.getProperty(LISTENERS_ATTACHED) != null) {
			return;
		}
		activity.setProperty(LISTENERS_ATTACHED, Boolean.TRUE);

		activity.addExecutionListener(ExecutionListener.EVENTNAME_START,
				new ActivityExecutionListener(fDispatcher, BpmEventDispatcher.ACTION_STARTED));
		activity.addExecutionListener(ExecutionListener.EVENTNAME_END,
				new ActivityExecutionListener(fDispatcher, BpmEventDispatcher.ACTION_ENDED));
	}

	private static class ProcessExecutionListener implements ExecutionListener {
		private final BpmEventDispatcher fDispatcher;
		private final String fAction;

		private ProcessExecutionListener(BpmEventDispatcher dispatcher, String action) {
			fDispatcher = dispatcher;
			fAction = action;
		}

		@Override
		public void notify(DelegateExecution execution) throws Exception {
			Map<String, Object> properties = fDispatcher.newProperties();
			BpmEventDispatcher.put(properties, "processDefinitionId", execution.getProcessDefinitionId());
			BpmEventDispatcher.put(properties, "processInstanceId", execution.getProcessInstanceId());
			BpmEventDispatcher.put(properties, "executionId", execution.getId());
			BpmEventDispatcher.put(properties, "businessKey", execution.getBusinessKey());
			BpmEventDispatcher.put(properties, "tenantId", execution.getTenantId());
			fDispatcher.dispatchOnCommit(fDispatcher.topic(ProcessInstance.class, fAction), properties);
		}
	}

	private static class ActivityExecutionListener implements ExecutionListener {
		private final BpmEventDispatcher fDispatcher;
		private final String fAction;

		private ActivityExecutionListener(BpmEventDispatcher dispatcher, String action) {
			fDispatcher = dispatcher;
			fAction = action;
		}

		@Override
		public void notify(DelegateExecution execution) throws Exception {
			Map<String, Object> properties = fDispatcher.newProperties();
			BpmEventDispatcher.put(properties, "processDefinitionId", execution.getProcessDefinitionId());
			BpmEventDispatcher.put(properties, "processInstanceId", execution.getProcessInstanceId());
			BpmEventDispatcher.put(properties, "executionId", execution.getId());
			BpmEventDispatcher.put(properties, "activityInstanceId", execution.getActivityInstanceId());
			BpmEventDispatcher.put(properties, "activityId", execution.getCurrentActivityId());
			BpmEventDispatcher.put(properties, "activityName", execution.getCurrentActivityName());
			BpmEventDispatcher.put(properties, "businessKey", execution.getBusinessKey());
			BpmEventDispatcher.put(properties, "tenantId", execution.getTenantId());
			fDispatcher.dispatchOnCommit(fDispatcher.topic(ActivityInstance.class, fAction), properties);
		}
	}

	private static class SequenceFlowExecutionListener implements ExecutionListener {
		private final BpmEventDispatcher fDispatcher;

		private SequenceFlowExecutionListener(BpmEventDispatcher dispatcher) {
			fDispatcher = dispatcher;
		}

		@Override
		public void notify(DelegateExecution execution) throws Exception {
			Map<String, Object> properties = fDispatcher.newProperties();
			BpmEventDispatcher.put(properties, "processDefinitionId", execution.getProcessDefinitionId());
			BpmEventDispatcher.put(properties, "processInstanceId", execution.getProcessInstanceId());
			BpmEventDispatcher.put(properties, "executionId", execution.getId());
			BpmEventDispatcher.put(properties, "transitionId", execution.getCurrentTransitionId());
			BpmEventDispatcher.put(properties, "activityId", execution.getCurrentActivityId());
			BpmEventDispatcher.put(properties, "businessKey", execution.getBusinessKey());
			BpmEventDispatcher.put(properties, "tenantId", execution.getTenantId());
			fDispatcher.dispatchOnCommit(fDispatcher.topic(SequenceFlow.class, BpmEventDispatcher.ACTION_TAKEN), properties);
		}
	}

	private static class UserTaskListener implements TaskListener {
		private final BpmEventDispatcher fDispatcher;
		private final String fAction;

		private UserTaskListener(BpmEventDispatcher dispatcher, String action) {
			fDispatcher = dispatcher;
			fAction = action;
		}

		@Override
		public void notify(DelegateTask task) {
			Map<String, Object> properties = fDispatcher.newProperties();
			BpmEventDispatcher.put(properties, "taskId", task.getId());
			BpmEventDispatcher.put(properties, "taskName", task.getName());
			BpmEventDispatcher.put(properties, "taskDefinitionKey", task.getTaskDefinitionKey());
			BpmEventDispatcher.put(properties, "assignee", task.getAssignee());
			BpmEventDispatcher.put(properties, "eventName", task.getEventName());
			BpmEventDispatcher.put(properties, "processInstanceId", task.getProcessInstanceId());
			BpmEventDispatcher.put(properties, "processDefinitionId", task.getProcessDefinitionId());
			BpmEventDispatcher.put(properties, "executionId", task.getExecutionId());
			BpmEventDispatcher.put(properties, "tenantId", task.getTenantId());
			BpmEventDispatcher.put(properties, "createTime", task.getCreateTime());
			fDispatcher.dispatchOnCommit(fDispatcher.topic(Task.class, fAction), properties);
		}
	}

}

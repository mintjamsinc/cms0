/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.rt.cms.internal.bpm.record;

import java.util.Map;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseUtil;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;

/**
 * Keeps the records of {@link ProcessRecords}: at parse time it gives the
 * steps that are recorded a listener that writes a record when the engine
 * passes them.
 *
 * <ul>
 *   <li>a start event that declares a body, or is a message start event - when
 *       it is left, that is, when the process (or a sub-process) starts;</li>
 *   <li>a user task that declares a body - when it is completed;</li>
 *   <li>an intermediate throw event or an end event that declares a body - when
 *       it is reached (how a BPMN names an outcome: "Approved", "Returned");</li>
 *   <li>a message catch event (intermediate or boundary) and a receive task,
 *       always, and any other catch event that declares a body - when it is
 *       left after being triggered, never when it is cancelled.</li>
 * </ul>
 *
 * <p>A step that declares {@code record} = {@code true} is recorded like one
 * that declares a body, without words: who took it and when.
 */
public class ProcessRecordParseListener extends AbstractBpmnParseListener {

	@Override
	public void parseStartEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
		String body = body(element);
		if (body != null || recorded(element) || hasEventDefinition(element, "messageEventDefinition")) {
			activity.addExecutionListener(ExecutionListener.EVENTNAME_END,
					new StepListener("startEvent", name(element), body));
		}
	}

	@Override
	public void parseUserTask(Element element, ScopeImpl scope, ActivityImpl activity) {
		String body = body(element);
		if ((body == null && !recorded(element)) || !(activity.getActivityBehavior() instanceof UserTaskActivityBehavior)) {
			return;
		}
		((UserTaskActivityBehavior) activity.getActivityBehavior()).getTaskDefinition()
				.addTaskListener(TaskListener.EVENTNAME_COMPLETE, new TaskStepListener(name(element), body));
	}

	@Override
	public void parseIntermediateThrowEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
		reachedEvent(element, activity, "intermediateThrowEvent");
	}

	@Override
	public void parseEndEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
		reachedEvent(element, activity, "endEvent");
	}

	@Override
	public void parseIntermediateCatchEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
		catchEvent(element, activity, "intermediateCatchEvent");
	}

	@Override
	public void parseBoundaryEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
		catchEvent(element, activity, "boundaryEvent");
	}

	@Override
	public void parseReceiveTask(Element element, ScopeImpl scope, ActivityImpl activity) {
		activity.addExecutionListener(ExecutionListener.EVENTNAME_END,
				new StepListener("receiveTask", name(element), body(element)));
	}

	/**
	 * An event the process reaches by itself (a throw event, an end event): it
	 * is how a BPMN names an outcome ("Approved", "Returned"). Recorded, when
	 * it declares a body or asks to be recorded, as soon as it is reached, as
	 * the user whose action led there.
	 */
	private static void reachedEvent(Element element, ActivityImpl activity, String type) {
		String body = body(element);
		if (body != null || recorded(element)) {
			activity.addExecutionListener(ExecutionListener.EVENTNAME_START, new StepListener(type, name(element), body));
		}
	}

	private static void catchEvent(Element element, ActivityImpl activity, String type) {
		String body = body(element);
		if (body != null || recorded(element) || hasEventDefinition(element, "messageEventDefinition")) {
			activity.addExecutionListener(ExecutionListener.EVENTNAME_END, new StepListener(type, name(element), body));
		}
	}

	/** The body template the element declares, or null when it declares none. */
	private static String body(Element element) {
		return property(element, ProcessRecords.PROPERTY_BODY);
	}

	/** True when the element asks to be recorded without a body ({@code record} = {@code true}). */
	private static boolean recorded(Element element) {
		String record = property(element, ProcessRecords.PROPERTY_RECORD);
		return record != null && "true".equalsIgnoreCase(record.trim());
	}

	private static String property(Element element, String name) {
		try {
			Map<String, String> props = BpmnParseUtil.parseCamundaExtensionProperties(element);
			return (props != null) ? props.get(name) : null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static String name(Element element) {
		String name = element.attribute("name");
		return (name == null || name.isEmpty()) ? null : name;
	}

	private static boolean hasEventDefinition(Element element, String tagName) {
		return element.element(tagName) != null;
	}

	private static String authenticatedUserId() {
		CommandContext commandContext = Context.getCommandContext();
		return (commandContext != null) ? commandContext.getAuthenticatedUserId() : null;
	}

	/** Records a start event or a catch event when the engine leaves it. */
	private static class StepListener implements ExecutionListener {
		private final String fType;
		private final String fName;
		private final String fBody;

		private StepListener(String type, String name, String body) {
			fType = type;
			fName = name;
			fBody = body;
		}

		@Override
		public void notify(DelegateExecution execution) throws Exception {
			if (execution.isCanceled()) {
				// Cancelled (the process was deleted, a boundary event interrupted it): nothing happened here.
				return;
			}
			String root = (execution instanceof ExecutionEntity)
					? ((ExecutionEntity) execution).getRootProcessInstanceId()
					: execution.getProcessInstanceId();
			ProcessRecords.write(execution.getProcessInstanceId(), root, null, authenticatedUserId(),
					execution.getCurrentActivityId(), fType, fName, fBody, execution::getVariable);
		}
	}

	/** Records the completion of a user task, by the user who completed it. */
	private static class TaskStepListener implements TaskListener {
		private final String fName;
		private final String fBody;

		private TaskStepListener(String name, String body) {
			fName = name;
			fBody = body;
		}

		@Override
		public void notify(DelegateTask task) {
			String user = authenticatedUserId();
			if (user == null || user.isEmpty()) {
				user = task.getAssignee();
			}
			String root = (task instanceof TaskEntity && ((TaskEntity) task).getExecution() != null)
					? ((TaskEntity) task).getExecution().getRootProcessInstanceId()
					: task.getProcessInstanceId();
			String name = (task.getName() != null && !task.getName().isEmpty()) ? task.getName() : fName;
			ProcessRecords.write(task.getProcessInstanceId(), root, task.getId(), user,
					task.getTaskDefinitionKey(), "userTask", name, fBody, task::getVariable);
		}
	}

}

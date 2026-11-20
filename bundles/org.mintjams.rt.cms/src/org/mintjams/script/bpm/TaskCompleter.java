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

package org.mintjams.script.bpm;

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.variable.Variables;
import org.mintjams.tools.lang.Strings;

/**
 * Completes a user task from a script, as a user, with the comment that goes
 * with it:
 *
 * <pre>
 * ProcessAPI.createTaskCompleter()
 *     .setTaskId(taskId)
 *     .setUserId(user)
 *     .setVariables([decision: 'approve'])
 *     .setTransientVariable('approverComment', comment)
 *     .setComment(comment)
 *     .complete();
 * </pre>
 *
 * <p>The comment is kept as a comment of the task written by the user, and the
 * task is completed in the same transaction: both are kept, or neither. A
 * transient variable is seen by the process until it next waits (a script of
 * a service task after the user task, say) and is never stored.
 */
public class TaskCompleter {

	private final ProcessAPI fProcessAPI;
	private String fTaskId;
	private Map<String, Object> fVariables;
	private String fUserId;
	private String fComment;

	protected TaskCompleter(ProcessAPI processAPI) {
		fProcessAPI = processAPI;
	}

	public TaskCompleter setTaskId(String taskId) {
		fTaskId = taskId;
		return this;
	}

	public TaskCompleter setVariables(Map<String, Object> variables) {
		if (variables == null) {
			fVariables = null;
			return this;
		}

		if (fVariables == null) {
			fVariables = new HashMap<>();
		}
		fVariables.putAll(variables);
		return this;
	}

	public TaskCompleter setVariable(String variableName, Object value) {
		if (Strings.isEmpty(variableName)) {
			throw new IllegalArgumentException("Variable name must not be null or empty.");
		}

		if (fVariables == null) {
			fVariables = new HashMap<>();
		}
		fVariables.put(variableName, value);
		return this;
	}

	/**
	 * A variable the process sees until it next waits and never stores. It
	 * cannot take the name of a stored variable of the same scope (the engine
	 * refuses it).
	 */
	public TaskCompleter setTransientVariable(String variableName, Object value) {
		return setVariable(variableName, Variables.untypedValue(value, true));
	}

	public TaskCompleter setUserId(String userId) {
		fUserId = userId;
		return this;
	}

	/**
	 * The comment given with the completion, kept as a comment of the task
	 * written by the user. Blank, or at history level {@code none}, none is
	 * written.
	 */
	public TaskCompleter setComment(String comment) {
		fComment = comment;
		return this;
	}

	public void complete() {
		if (Strings.isEmpty(fTaskId)) {
			throw new IllegalStateException("The task id must not be empty");
		}

		fProcessAPI.callAsUser(fUserId, () -> fProcessAPI.inOneCommand(() -> {
			TaskService tasks = fProcessAPI.getEngine().getTaskService();
			Task task = tasks.createTaskQuery().taskId(fTaskId).singleResult();
			if (task == null) {
				throw new IllegalStateException("Could not find task '" + fTaskId + "'");
			}
			fProcessAPI.writeComment(fTaskId, task.getProcessInstanceId(), fComment);
			tasks.complete(fTaskId, (fVariables != null) ? fVariables : new HashMap<>());
			return null;
		}));
	}

}

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

package org.mintjams.script.bpm;

import java.util.function.Supplier;

import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.identity.Authentication;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.bpm.record.ProcessRecords;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.ScriptingContext;
import org.mintjams.tools.lang.Strings;

public class ProcessAPI {

	private WorkspaceScriptContext fContext;

	public ProcessAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static ProcessAPI get(ScriptingContext context) {
		return (ProcessAPI) context.getAttribute(ProcessAPI.class.getSimpleName());
	}

	public ProcessEngine getEngine() {
		return CmsService.getWorkspaceProcessEngineProvider(fContext.getWorkspaceName()).getProcessEngine();
	}

	public ProcessStarter createProcessStarter() {
		return new ProcessStarter(this);
	}

	public MessageCorrelator createMessageCorrelator() {
		return new MessageCorrelator(this);
	}

	public SignalSender createSignalSender() {
		return new SignalSender(this);
	}

	public TaskCompleter createTaskCompleter() {
		return new TaskCompleter(this);
	}

	/**
	 * Whether the engine keeps comments ({@code TaskService.createComment}):
	 * every history level but {@code none}. An app hides its comment fields
	 * when it is false; a comment given to a starter, a correlator or a
	 * completer is then not written.
	 */
	public boolean isCommentsEnabled() {
		return ProcessRecords.commentsKept(getEngine());
	}

	/**
	 * Runs {@code action} as one engine command: the engine calls it makes join
	 * that command, so they are kept together or not at all. A comment written
	 * on a process in the command that ends it also gets the process's removal
	 * time, which one written afterwards would not (history cleanup would never
	 * remove it).
	 */
	<T> T inOneCommand(Supplier<T> action) {
		ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl) getEngine().getProcessEngineConfiguration();
		return config.getCommandExecutorTxRequired().execute(commandContext -> action.get());
	}

	/** Writes {@code comment} on a task or a process when it says something and the engine keeps comments. */
	void writeComment(String taskId, String processInstanceId, String comment) {
		if (comment == null || comment.trim().isEmpty() || !isCommentsEnabled()) {
			return;
		}
		getEngine().getTaskService().createComment(taskId, processInstanceId, comment.trim());
	}

	/**
	 * Runs {@code action} with {@code userId} as the engine's authenticated user, so
	 * Camunda attributes the work to that user (process initiator, operation log).
	 * The authentication current before the call is restored afterward, so a script
	 * running inside an engine command does not wipe its caller's identity. Without
	 * a user id the action runs under whatever authentication is current.
	 */
	<T> T callAsUser(String userId, Supplier<T> action) {
		if (Strings.isEmpty(userId)) {
			return action.get();
		}

		IdentityService identity = getEngine().getIdentityService();
		Authentication previous = identity.getCurrentAuthentication();
		identity.setAuthenticatedUserId(userId);
		try {
			return action.get();
		} finally {
			if (previous == null) {
				identity.clearAuthentication();
			} else {
				identity.setAuthentication(previous);
			}
		}
	}

}

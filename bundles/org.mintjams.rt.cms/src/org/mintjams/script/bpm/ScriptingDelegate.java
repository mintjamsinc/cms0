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

import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.delegate.Expression;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.delegate.VariableScope;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptEngineManager;
import org.mintjams.rt.cms.internal.web.WebResourceResolver;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class ScriptingDelegate implements JavaDelegate, ExecutionListener, TaskListener {

	private Expression resourcePath;

	@Override
	public void execute(DelegateExecution execution) throws Exception {
		String resourcePath = getResourcePath(execution);
		if (Strings.isEmpty(resourcePath)) {
			throw new IllegalStateException("The resource path is empty.");
		}

		try (WorkspaceScriptContext context = new WorkspaceScriptContext(getWorkspaceName(execution))) {
			context.setAttribute("execution", execution);
			Scripts.prepareAPIs(context);

			evaluate(context, execution);
		}
	}

	@Override
	public void notify(DelegateTask task) {
		String resourcePath = getResourcePath(task);
		if (Strings.isEmpty(resourcePath)) {
			throw new IllegalStateException("The resource path is empty.");
		}

		try (WorkspaceScriptContext context = new WorkspaceScriptContext(getWorkspaceName(task))) {
			context.setAttribute("task", task);
			Scripts.prepareAPIs(context);

			evaluate(context, task);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public void notify(DelegateExecution execution) throws Exception {
		String resourcePath = getResourcePath(execution);
		if (Strings.isEmpty(resourcePath)) {
			throw new IllegalStateException("The resource path is empty.");
		}

		try (WorkspaceScriptContext context = new WorkspaceScriptContext(getWorkspaceName(execution))) {
			context.setAttribute("execution", execution);
			Scripts.prepareAPIs(context);

			evaluate(context, execution);
		}
	}

	private String getWorkspaceName(DelegateExecution execution) {
		ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl) execution.getProcessEngine().getProcessEngineConfiguration();
		return ((WorkspaceScriptEngineManager) config.getScriptingEngines().getScriptEngineManager()).getWorkspaceName();
	}

	private String getWorkspaceName(DelegateTask task) {
		ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl) task.getProcessEngine().getProcessEngineConfiguration();
		return ((WorkspaceScriptEngineManager) config.getScriptingEngines().getScriptEngineManager()).getWorkspaceName();
	}

	private Object evaluate(WorkspaceScriptContext context, VariableScope variableScope) throws Exception {
		String resourcePath = getResourcePath(variableScope);
		WebResourceResolver.ResolveResult result = new WebResourceResolver(context).resolve(resourcePath);
		if (result.isNotFound()) {
			throw new PathNotFoundException(resourcePath);
		}
		if (!result.isScriptable()) {
			return null;
		}
		if (result.isAccessDenied()) {
			throw new AccessDeniedException(resourcePath);
		}

		context.setAttribute("resource", context.getResourceResolver().toResource(result.getNode()));

		try (ScriptReader scriptReader = new ScriptReader(result.getContentAsReader())) {
			return scriptReader
					.setScriptName("jcr://" + result.getPath())
					.setExtension(result.getScriptExtension())
					.setLastModified(result.getLastModified())
					.setScriptEngineManager(Scripts.getScriptEngineManager(context))
					.setClassLoader(Scripts.getClassLoader(context))
					.setScriptContext(Scripts.getWorkspaceScriptContext(context))
					.eval();
		}
	}

	private String getResourcePath(VariableScope variableScope) {
		if (resourcePath == null) {
			return null;
		}

		return (String) resourcePath.getValue(variableScope);
	}

}

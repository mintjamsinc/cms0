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

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;
import javax.script.ScriptEngine;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.delegate.Expression;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.delegate.VariableScope;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptEngineManager;
import org.mintjams.rt.cms.internal.security.UserServiceCredentials;
import org.mintjams.script.resource.Resource;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class CmsDelegate implements JavaDelegate, ExecutionListener, TaskListener {

	private Expression path;
	private Expression inputs;
	private Expression outputs;
	private Expression runAs;

	@Override
	public void execute(DelegateExecution execution) throws Exception {
		String resourcePath = getPath(execution);
		if (Strings.isEmpty(resourcePath)) {
			throw new IllegalStateException("The resource path is empty.");
		}

		try (WorkspaceScriptContext context = new WorkspaceScriptContext(getWorkspaceName(execution))) {
			String runAs = getRunAs(execution);
			if (runAs != null && !runAs.trim().isEmpty()) {
				context.setCredentials(new UserServiceCredentials(runAs));
			}
			context.setAttribute("execution", execution);
			Scripts.prepareAPIs(context);

			evaluate(context, execution);
		}
	}

	@Override
	public void notify(DelegateTask task) {
		String resourcePath = getPath(task);
		if (Strings.isEmpty(resourcePath)) {
			throw new IllegalStateException("The resource path is empty.");
		}

		try (WorkspaceScriptContext context = new WorkspaceScriptContext(getWorkspaceName(task))) {
			String runAs = getRunAs(task);
			if (runAs != null && !runAs.trim().isEmpty()) {
				context.setCredentials(new UserServiceCredentials(runAs));
			}
			context.setAttribute("task", task);
			Scripts.prepareAPIs(context);

			evaluate(context, task);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public void notify(DelegateExecution execution) throws Exception {
		String resourcePath = getPath(execution);
		if (Strings.isEmpty(resourcePath)) {
			throw new IllegalStateException("The resource path is empty.");
		}

		try (WorkspaceScriptContext context = new WorkspaceScriptContext(getWorkspaceName(execution))) {
			String runAs = getRunAs(execution);
			if (runAs != null && !runAs.trim().isEmpty()) {
				context.setCredentials(new UserServiceCredentials(runAs));
			}
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

	private void evaluate(WorkspaceScriptContext context, VariableScope variableScope) throws Exception {
		String resourcePath = getPath(variableScope);
		Resource resource = context.getRepositorySession().getResource(resourcePath);

		// Check if resource exists and is readable
		if (!resource.exists()) {
			throw new PathNotFoundException("Resource not found: " + resourcePath);
		}
		if (!resource.canRead()) {
			throw new AccessDeniedException("Cannot read resource: " + resourcePath);
		}

		// Determine script engine by MIME type
		String mimeType = resource.getContentType();
		ScriptEngine scriptEngine = Scripts.getScriptEngineManager(context).getEngineByMimeType(mimeType);
		if (scriptEngine == null) {
			throw new IllegalStateException("No script engine found for MIME type: " + mimeType);
		}

		// Set variables to script context based on input filters
		for (String filter : getInputs(variableScope)) {
			if (filter.indexOf("=") > 0) {
				String[] parts = filter.split("=", 2);
				String attributeName = parts[0].trim();
				String variableName = parts[1].trim();
				if (variableScope.hasVariable(variableName)) {
					context.setAttribute(attributeName, variableScope.getVariable(variableName));
				}
				continue;
			}

			if (filter.endsWith("*")) {
				String prefix = filter.substring(0, filter.length() - 1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> context.setAttribute(entry.getKey(), entry.getValue()));
			} else if (filter.startsWith("*")) {
				String suffix = filter.substring(1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> context.setAttribute(entry.getKey(), entry.getValue()));
			} else if (filter.endsWith("~")) {
				String prefix = filter.substring(0, filter.length() - 1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> context.setAttribute(entry.getKey().substring(prefix.length()), entry.getValue()));
			} else if (filter.startsWith("~")) {
				String suffix = filter.substring(1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> context.setAttribute(entry.getKey().substring(0, entry.getKey().length() - suffix.length()), entry.getValue()));
			} else {
				if (variableScope.hasVariable(filter)) {
					context.setAttribute(filter, variableScope.getVariable(filter));
				}
			}
		}

		// Set resource to script context
		context.setAttribute("resource", resource);

		// Evaluate script
		try (ScriptReader scriptReader = new ScriptReader(resource.getContentAsReader())) {
			scriptReader
					.setScriptName("jcr://" + resource.getPath())
					.setMimeType(mimeType)
					.setLastModified(resource.getLastModified())
					.setScriptEngineManager(Scripts.getScriptEngineManager(context))
					.setClassLoader(Scripts.getClassLoader(context))
					.setScriptContext(Scripts.getWorkspaceScriptContext(context))
					.eval();
		}

		// Set variables from script context to variable scope based on output filters
		for (String filter : getOutputs(variableScope)) {
			if (filter.indexOf("=") > 0) {
				String[] parts = filter.split("=", 2);
				String variableName = parts[0].trim();
				String attributeName = parts[1].trim();
				if (context.hasAttribute(attributeName)) {
					variableScope.setVariable(variableName, context.getAttribute(attributeName));
				}
				continue;
			}

			if (filter.endsWith("*")) {
				String prefix = filter.substring(0, filter.length() - 1);
				context.getAttributes().entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> variableScope.setVariable(entry.getKey(), entry.getValue()));
			} else if (filter.startsWith("*")) {
				String suffix = filter.substring(1);
				context.getAttributes().entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> variableScope.setVariable(entry.getKey(), entry.getValue()));
			} else if (filter.endsWith("~")) {
				String prefix = filter.substring(0, filter.length() - 1);
				context.getAttributes().entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> variableScope.setVariable(entry.getKey().substring(prefix.length()), entry.getValue()));
			} else if (filter.startsWith("~")) {
				String suffix = filter.substring(1);
				context.getAttributes().entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> variableScope.setVariable(entry.getKey().substring(0, entry.getKey().length() - suffix.length()), entry.getValue()));
			} else {
				if (context.hasAttribute(filter)) {
					variableScope.setVariable(filter, context.getAttribute(filter));
				}
			}
		}
	}

	private String getPath(VariableScope variableScope) {
		if (path == null) {
			return null;
		}

		return (String) path.getValue(variableScope);
	}

	private List<String> getInputs(VariableScope variableScope) {
		if (inputs == null) {
			return Collections.emptyList();
		}

		Object value = inputs.getValue(variableScope);
		if (value == null) {
			return Collections.emptyList();
		}

		if (value instanceof List) {
			return ((List<?>) value).stream()
					.map(Object::toString)
					.map(String::trim)
					.collect(Collectors.toList());
		}
		if (value instanceof String) {
			return List.of(((String) value).split("\\s*,\\s*"));
		}
		if (value instanceof Collection<?>) {
			return ((Collection<?>) value).stream()
					.map(Object::toString)
					.map(String::trim)
					.collect(Collectors.toList());
		}
		return List.of(value.toString().trim());
	}

	private List<String> getOutputs(VariableScope variableScope) {
		if (outputs == null) {
			return Collections.emptyList();
		}

		Object value = outputs.getValue(variableScope);
		if (value == null) {
			return Collections.emptyList();
		}

		if (value instanceof List) {
			return ((List<?>) value).stream()
					.map(Object::toString)
					.map(String::trim)
					.collect(Collectors.toList());
		}
		if (value instanceof String) {
			return List.of(((String) value).split("\\s*,\\s*"));
		}
		if (value instanceof Collection<?>) {
			return ((Collection<?>) value).stream()
					.map(Object::toString)
					.map(String::trim)
					.collect(Collectors.toList());
		}
		return List.of(value.toString().trim());
	}

	private String getRunAs(VariableScope variableScope) {
		if (runAs == null) {
			return null;
		}

		return (String) runAs.getValue(variableScope);
	}

}

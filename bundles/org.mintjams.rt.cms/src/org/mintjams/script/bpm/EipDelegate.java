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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.delegate.Expression;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.delegate.VariableScope;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptEngineManager;
import org.mintjams.script.eip.IntegrationAPI;
import org.mintjams.script.eip.Reply;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class EipDelegate implements JavaDelegate, ExecutionListener, TaskListener {

	private Expression endpointURI;
	private Expression inputs;
	private Expression outputs;

	@Override
	public void execute(DelegateExecution execution) throws Exception {
		String endpointURI = getEndpointURI(execution);
		if (Strings.isEmpty(endpointURI)) {
			throw new IllegalStateException("The endpoint URI is empty.");
		}

		try (WorkspaceScriptContext context = new WorkspaceScriptContext(getWorkspaceName(execution))) {
			context.setAttribute("execution", execution);
			Scripts.prepareAPIs(context);

			evaluate(context, execution);
		}
	}

	@Override
	public void notify(DelegateTask task) {
		String endpointURI = getEndpointURI(task);
		if (Strings.isEmpty(endpointURI)) {
			throw new IllegalStateException("The endpoint URI is empty.");
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
		String endpointURI = getEndpointURI(execution);
		if (Strings.isEmpty(endpointURI)) {
			throw new IllegalStateException("The endpoint URI is empty.");
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
		String endpointURI = getEndpointURI(variableScope);

		IntegrationAPI eip = (IntegrationAPI) context.getAttribute(IntegrationAPI.class.getSimpleName());
		Reply reply = eip.createMessageSender()
			.setEndpointURI(endpointURI)
			.setHeaders(getHeaders(variableScope))
			.send();

		// Set result headers as process variables
		Map<String, Object> headers = reply.getHeaders();
		for (String filter : getOutputs(variableScope)) {
			if (Strings.isEmpty(filter)) {
				continue;
			}

			if (filter.indexOf("=") > 0) {
				String[] parts = filter.split("=", 2);
				String variableName = parts[0].trim();
				String headerName = parts[1].trim();
				if (Objects.equals(headerName.toLowerCase(), "@body")) {
					variableScope.setVariable(variableName, reply.getBody());
					continue;
				}
				if (headers.containsKey(headerName)) {
					variableScope.setVariable(variableName, headers.get(headerName));
				}
				continue;
			}

			if (filter.endsWith("*")) {
				String prefix = filter.substring(0, filter.length() - 1);
				headers.entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> variableScope.setVariable(entry.getKey(), entry.getValue()));
			} else if (filter.startsWith("*")) {
				String suffix = filter.substring(1);
				headers.entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> variableScope.setVariable(entry.getKey(), entry.getValue()));
			} else if (filter.endsWith("~")) {
				String prefix = filter.substring(0, filter.length() - 1);
				headers.entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> variableScope.setVariable(entry.getKey().substring(prefix.length()), entry.getValue()));
			} else if (filter.startsWith("~")) {
				String suffix = filter.substring(1);
				headers.entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> variableScope.setVariable(entry.getKey().substring(0, entry.getKey().length() - suffix.length()), entry.getValue()));
			} else {
				if (headers.containsKey(filter)) {
					variableScope.setVariable(filter, headers.get(filter));
				}
			}
		}

		return null;
	}

	private String getEndpointURI(VariableScope variableScope) {
		if (endpointURI == null) {
			return null;
		}

		return (String) endpointURI.getValue(variableScope);
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

	private Map<String, Object> getHeaders(VariableScope variableScope) {
		Map<String, Object> headers = new HashMap<>();
		for (String filter : getInputs(variableScope)) {
			if (Strings.isEmpty(filter)) {
				continue;
			}

			if (filter.indexOf("=") > 0) {
				String[] parts = filter.split("=", 2);
				String headerName = parts[0].trim();
				String variableName = parts[1].trim();
				if (variableScope.hasVariable(variableName)) {
					headers.put(headerName, variableScope.getVariable(variableName));
				}
				continue;
			}

			if (filter.endsWith("*")) {
				String prefix = filter.substring(0, filter.length() - 1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
			} else if (filter.startsWith("*")) {
				String suffix = filter.substring(1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
			} else if (filter.endsWith("~")) {
				String prefix = filter.substring(0, filter.length() - 1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> headers.put(entry.getKey().substring(prefix.length()), entry.getValue()));
			} else if (filter.startsWith("~")) {
				String suffix = filter.substring(1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> headers.put(entry.getKey().substring(0, entry.getKey().length() - suffix.length()), entry.getValue()));
			} else {
				if (variableScope.hasVariable(filter)) {
					headers.put(filter, variableScope.getVariable(filter));
				}
			}
		}
		return headers;
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

}

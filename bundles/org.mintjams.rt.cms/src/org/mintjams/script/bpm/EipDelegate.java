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
	private Expression headers;
	private Expression resultHeaders;
	private Expression resultBody;

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
		List<String> resultHeaders = getResultHeaders(variableScope);
		for (String header : resultHeaders) {
			if (Strings.isEmpty(header)) {
				continue;
			}

			if (header.endsWith("*")) {
				String prefix = header.substring(0, header.length() - 1);
				headers.entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> variableScope.setVariable(entry.getKey(), entry.getValue()));
			} else if (header.startsWith("*")) {
				String suffix = header.substring(1);
				headers.entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> variableScope.setVariable(entry.getKey(), entry.getValue()));
			} else {
				if (headers.containsKey(header)) {
					variableScope.setVariable(header, headers.get(header));
				}
			}
		}

		// Set result body as a process variable
		String resultBody = getResultBody(variableScope);
		if (!Strings.isEmpty(resultBody)) {
			variableScope.setVariable(resultBody, reply.getBody());
			return reply.getBody();
		}

		return null;
	}

	private String getEndpointURI(VariableScope variableScope) {
		if (endpointURI == null) {
			return null;
		}

		return (String) endpointURI.getValue(variableScope);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getHeaders(VariableScope variableScope) {
		if (headers == null) {
			return Collections.emptyMap();
		}

		Object value = headers.getValue(variableScope);
		if (value == null) {
			return Collections.emptyMap();
		}

		List<String> names;
		if (value instanceof List) {
			names = (List<String>) value;
		} else if (value instanceof String) {
			names = List.of(((String) value).split("\\s*,\\s*"));
		} else if (value instanceof Collection<?>) {
			names = ((Collection<?>) value).stream()
					.map(Object::toString)
					.collect(Collectors.toList());
		} else {
			names = List.of(value.toString());
		}

		Map<String, Object> nvp = new HashMap<>();
		for (String name : names) {
			if (Strings.isEmpty(name)) {
				continue;
			}

			if (name.indexOf("=") > 0) {
				String[] parts = name.split("=", 2);
				nvp.put(parts[0].trim(), variableScope.getVariable(parts[1].trim()));
				continue;
			}

			if (name.endsWith("*")) {
				String prefix = name.substring(0, name.length() - 1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(prefix))
						.forEach(entry -> nvp.put(entry.getKey(), entry.getValue()));
			} else if (name.startsWith("*")) {
				String suffix = name.substring(1);
				variableScope.getVariables().entrySet().stream()
						.filter(entry -> entry.getKey().endsWith(suffix))
						.forEach(entry -> nvp.put(entry.getKey(), entry.getValue()));
			} else {
				if (variableScope.hasVariable(name)) {
					nvp.put(name, variableScope.getVariable(name));
				}
			}
		}
		return nvp;
	}

	@SuppressWarnings("unchecked")
	private List<String> getResultHeaders(VariableScope variableScope) {
		if (resultHeaders == null) {
			return Collections.emptyList();
		}

		Object value = resultHeaders.getValue(variableScope);
		if (value == null) {
			return Collections.emptyList();
		}

		if (value instanceof List) {
			return (List<String>) value;
		}
		if (value instanceof String) {
			return List.of(((String) value).split("\\s*,\\s*"));
		}
		if (value instanceof Collection<?>) {
			return ((Collection<?>) value).stream()
					.map(Object::toString)
					.collect(Collectors.toList());
		}
		return List.of(value.toString());
	}

	private String getResultBody(VariableScope variableScope) {
		if (resultBody == null) {
			return null;
		}

		Object value = resultBody.getValue(variableScope);
		return value != null ? value.toString() : null;
	}

}

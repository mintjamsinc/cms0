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

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ExecutionQuery;
import org.mintjams.tools.lang.Strings;

public class MessageCorrelator {

	private final ProcessAPI fProcessAPI;
	private String fProcessDefinitionId;
	private String fProcessDefinitionKey;
	private String fProcessInstanceId;
	private String fMessageName;
	private String fBusinessKey;
	private Map<String, Object> fCorrelationKeys;
	private Map<String, Object> fVariables;

	protected MessageCorrelator(ProcessAPI processAPI) {
		fProcessAPI = processAPI;
	}

	public MessageCorrelator setProcessDefinitionId(String processDefinitionId) {
		fProcessDefinitionId = processDefinitionId;
		return this;
	}

	public MessageCorrelator setProcessDefinitionKey(String processDefinitionKey) {
		fProcessDefinitionKey = processDefinitionKey;
		return this;
	}

	public MessageCorrelator setProcessInstanceId(String processInstanceId) {
		fProcessInstanceId = processInstanceId;
		return this;
	}

	public MessageCorrelator setMessageName(String messageName) {
		fMessageName = messageName;
		return this;
	}

	public MessageCorrelator setBusinessKey(String businessKey) {
		fBusinessKey = businessKey;
		return this;
	}

	public MessageCorrelator setCorrelationKeys(Map<String, Object> keys) {
		if (keys == null) {
			fCorrelationKeys = null;
			return this;
		}

		if (fCorrelationKeys == null) {
			fCorrelationKeys = new HashMap<>();
		}
		fCorrelationKeys.putAll(keys);
		return this;
	}

	public MessageCorrelator setCorrelationKey(String keyName, Object value) {
		if (Strings.isEmpty(keyName)) {
			throw new IllegalArgumentException("Key name must not be null or empty.");
		}

		if (fCorrelationKeys == null) {
			fCorrelationKeys = new HashMap<>();
		}
		fCorrelationKeys.put(keyName, value);
		return this;
	}

	public MessageCorrelator setVariables(Map<String, Object> variables) {
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

	public MessageCorrelator setVariable(String variableName, Object value) {
		if (Strings.isEmpty(variableName)) {
			throw new IllegalArgumentException("Variable name must not be null or empty.");
		}

		if (fVariables == null) {
			fVariables = new HashMap<>();
		}
		fVariables.put(variableName, value);
		return this;
	}

	public void correlate() {
		if (!Strings.isEmpty(fMessageName)) {
			throw new IllegalStateException("The message name must not be empty");
		}

		RuntimeService runtime = fProcessAPI.getEngine().getRuntimeService();

		if (!Strings.isEmpty(fProcessInstanceId)) {
			ExecutionQuery query = runtime.createExecutionQuery()
					.processInstanceId(fProcessInstanceId)
					.messageEventSubscriptionName(fMessageName);
			if (!Strings.isEmpty(fProcessDefinitionKey)) {
				query.processDefinitionKey(fProcessDefinitionKey);
			}
			if (!Strings.isEmpty(fProcessDefinitionId)) {
				query.processDefinitionId(fProcessDefinitionId);
			}
			if (!Strings.isEmpty(fBusinessKey)) {
				query.processInstanceBusinessKey(fBusinessKey);
			}
			Execution execution = query.singleResult();
			if (execution == null) {
				throw new IllegalStateException("Could not find waiting process instance with id '" + fProcessInstanceId + "' for message '" + fMessageName + "'");
			}

			runtime.messageEventReceived(fMessageName, execution.getId(), fVariables);
			return;
		}

		if (!Strings.isEmpty(fBusinessKey)) {
			runtime.correlateMessage(fMessageName, fBusinessKey, fCorrelationKeys, fVariables);
			return;
		}
		runtime.correlateMessage(fMessageName, fCorrelationKeys, fVariables);
	}

}

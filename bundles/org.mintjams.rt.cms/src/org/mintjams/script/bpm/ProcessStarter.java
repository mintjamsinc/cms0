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
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.mintjams.tools.lang.Strings;

public class ProcessStarter {

	private final ProcessAPI fProcessAPI;
	private String fProcessDefinitionId;
	private String fProcessDefinitionKey;
	private String fMessageName;
	private String fBusinessKey;
	private Map<String, Object> fVariables;

	protected ProcessStarter(ProcessAPI processAPI) {
		fProcessAPI = processAPI;
	}

	public ProcessStarter setProcessDefinitionId(String processDefinitionId) {
		fProcessDefinitionId = processDefinitionId;
		return this;
	}

	public ProcessStarter setProcessDefinitionKey(String processDefinitionKey) {
		fProcessDefinitionKey = processDefinitionKey;
		return this;
	}

	public ProcessStarter setMessageName(String messageName) {
		fMessageName = messageName;
		return this;
	}

	public ProcessStarter setBusinessKey(String businessKey) {
		fBusinessKey = businessKey;
		return this;
	}

	public ProcessStarter setVariables(Map<String, Object> variables) {
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

	public ProcessStarter setVariable(String variableName, Object value) {
		if (Strings.isEmpty(variableName)) {
			throw new IllegalArgumentException("Variable name must not be null or empty.");
		}

		if (fVariables == null) {
			fVariables = new HashMap<>();
		}
		fVariables.put(variableName, value);
		return this;
	}

	public ProcessInstance start() {
		RuntimeService runtime = fProcessAPI.getEngine().getRuntimeService();

		if (!Strings.isEmpty(fProcessDefinitionId)) {
			if (!Strings.isEmpty(fBusinessKey)) {
				return runtime.startProcessInstanceById(fProcessDefinitionId, fBusinessKey, fVariables);
			}
			return runtime.startProcessInstanceById(fProcessDefinitionId, fVariables);
		}

		if (!Strings.isEmpty(fProcessDefinitionKey)) {
			if (!Strings.isEmpty(fBusinessKey)) {
				return runtime.startProcessInstanceByKey(fProcessDefinitionKey, fBusinessKey, fVariables);
			}
			return runtime.startProcessInstanceByKey(fProcessDefinitionKey, fVariables);
		}

		if (!Strings.isEmpty(fMessageName)) {
			if (!Strings.isEmpty(fProcessDefinitionId)) {
				if (!Strings.isEmpty(fBusinessKey)) {
					return runtime.startProcessInstanceByMessageAndProcessDefinitionId(fMessageName, fProcessDefinitionId, fBusinessKey, fVariables);
				}
				return runtime.startProcessInstanceByMessageAndProcessDefinitionId(fMessageName, fProcessDefinitionId, fVariables);
			}

			if (!Strings.isEmpty(fBusinessKey)) {
				return runtime.startProcessInstanceByMessage(fMessageName, fBusinessKey, fVariables);
			}
			return runtime.startProcessInstanceByMessage(fMessageName, fVariables);
		}

		throw new IllegalStateException("Could not start BPM process");
	}

}

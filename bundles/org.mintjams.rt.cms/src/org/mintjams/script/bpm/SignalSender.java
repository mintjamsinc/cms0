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

import org.camunda.bpm.engine.runtime.SignalEventReceivedBuilder;
import org.mintjams.tools.lang.Strings;

public class SignalSender {

	private final ProcessAPI fProcessAPI;
	private String fSignalName;
	private String fExecutionId;
	private Map<String, Object> fVariables;
	private String fUserId;

	protected SignalSender(ProcessAPI processAPI) {
		fProcessAPI = processAPI;
	}

	public SignalSender setSignalName(String signalName) {
		fSignalName = signalName;
		return this;
	}

	public SignalSender setExecutionId(String executionId) {
		fExecutionId = executionId;
		return this;
	}

	public SignalSender setVariables(Map<String, Object> variables) {
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

	public SignalSender setVariable(String variableName, Object value) {
		if (Strings.isEmpty(variableName)) {
			throw new IllegalArgumentException("Variable name must not be null or empty.");
		}

		if (fVariables == null) {
			fVariables = new HashMap<>();
		}
		fVariables.put(variableName, value);
		return this;
	}

	public SignalSender setUserId(String userId) {
		fUserId = userId;
		return this;
	}

	public void send() {
		if (Strings.isEmpty(fSignalName)) {
			throw new IllegalStateException("The signal name must not be empty");
		}

		fProcessAPI.callAsUser(fUserId, () -> {
			SignalEventReceivedBuilder builder = fProcessAPI.getEngine().getRuntimeService()
					.createSignalEvent(fSignalName);
			if (fVariables != null) {
				builder.setVariables(fVariables);
			}
			if (!Strings.isEmpty(fExecutionId)) {
				builder.executionId(fExecutionId);
			}
			builder.send();
			return null;
		});
	}

}

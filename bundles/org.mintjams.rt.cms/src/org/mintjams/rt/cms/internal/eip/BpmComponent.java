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

package org.mintjams.rt.cms.internal.eip;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ExecutionQuery;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.lang.Strings;

public class BpmComponent extends DefaultComponent {

	public static final String COMPONENT_NAME = "bpm";

	private final String fWorkspaceName;

	public BpmComponent(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		// Parse operation type from remaining (e.g., "startProcess", "correlateMessage")
		String operation = remaining;

		BpmEndpoint endpoint = new BpmEndpoint(uri, operation, parameters);
		parameters.clear(); // All parameters are consumed internally by BpmEndpoint
		return endpoint;
	}

	public class BpmEndpoint extends DefaultEndpoint {
		private final String fOperation;
		private final Map<String, Object> fParameters;

		private BpmEndpoint(String endpointUri, String operation, Map<String, Object> parameters) {
			super(endpointUri, BpmComponent.this);
			fOperation = operation;
			fParameters = new HashMap<>(parameters);
		}

		@Override
		public Consumer createConsumer(Processor processor) throws Exception {
			// Not supported
			return null;
		}

		@Override
		public Producer createProducer() throws Exception {
			// Determine operation type
			if ("startProcess".equals(fOperation)) {
				return new StartProcessProducer();
			} else if ("correlateMessage".equals(fOperation)) {
				return new CorrelateMessageProducer();
			} else {
				// Unsupported operation
				throw new IllegalStateException("Could not process BPM endpoint");
			}
		}

		/**
		 * Get parameter value from endpoint parameters or exchange headers.
		 */
		private String getParameter(Exchange exchange, String key) {
			// First check endpoint parameters
			if (fParameters.containsKey(key)) {
				Object value = fParameters.get(key);
				return value != null ? value.toString() : null;
			}

			// Then check exchange headers
			Object headerValue = exchange.getIn().getHeader(key);
			return headerValue != null ? headerValue.toString() : null;
		}

		/**
		 * Extract variables from exchange headers based on a specified prefix.
		 */
		private Map<String, Object> getVariables(Exchange exchange) {
			// Get property prefix: 1. endpoint param, 2. exchange header, 3. default
			String prefix = getParameter(exchange, "propertyPrefix");
			if (prefix == null || prefix.trim().isEmpty()) {
				prefix = "bpm_";
			}

			// Set variables from exchange headers
			Map<String, Object> variables = new HashMap<>();
			Map<String, Object> headers = exchange.getIn().getHeaders();
			for (Map.Entry<String, Object> entry : headers.entrySet()) {
				String key = entry.getKey();
				if (key.startsWith(prefix)) {
					String variableName = key.substring(prefix.length());
					variables.put(variableName, entry.getValue());
				}
			}
			return variables;
		}

		/**
		 * Extract correlation keys from exchange headers based on a specified prefix.
		 */
		private Map<String, Object> getCorrelationKeys(Exchange exchange) {
			// Get property prefix: 1. endpoint param, 2. exchange header, 3. default
			String prefix = getParameter(exchange, "correlationKeyPrefix");
			if (prefix == null || prefix.trim().isEmpty()) {
				prefix = "bpm_key_";
			}

			// Set variables from exchange headers
			Map<String, Object> correlationKeys = new HashMap<>();
			Map<String, Object> headers = exchange.getIn().getHeaders();
			for (Map.Entry<String, Object> entry : headers.entrySet()) {
				String key = entry.getKey();
				if (key.startsWith(prefix)) {
					String variableName = key.substring(prefix.length());
					correlationKeys.put(variableName, entry.getValue());
				}
			}
			return correlationKeys;
		}

		/**
		 * Producer for starting a BPM process instance.
		 * Supports starting by process definition id, key, or message name, with optional business key and variables.
		 * Parameters can be provided as endpoint parameters or exchange headers, with endpoint parameters taking precedence.
		 * 
		 * Supported parameters:
		 * - processDefinitionId: ID of the process definition to start
		 * - processDefinitionKey: Key of the process definition to start
		 * - messageName: Name of the message to start the process (can be used with processDefinitionId to specify the process definition to start)
		 * - businessKey: Optional business key for the process instance
		 * - propertyPrefix: Optional prefix for exchange headers to be included as process variables (default is "bpm_")
		 */
		private class StartProcessProducer extends DefaultProducer {
			private StartProcessProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				// Start process instance and set process definition and instance ids in exchange headers
				ProcessInstance instance = startProcessInstance(exchange);

				// Set process instance id in exchange headers for downstream processing
				exchange.getMessage().setHeader("processInstanceId", instance.getProcessInstanceId());
			}

			/**
			 * Start a process instance based on provided parameters.
			 * 
			 * Priority for starting:
			 * 1. messageName + processDefinitionId
			 * 2. messageName
			 * 3. processDefinitionId
			 * 4. processDefinitionKey
			 */
			private ProcessInstance startProcessInstance(Exchange exchange) {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				// Get required parameters for starting process instance
				String processDefinitionId = getParameter(exchange, "processDefinitionId");
				String processDefinitionKey = getParameter(exchange, "processDefinitionKey");
				String messageName = getParameter(exchange, "messageName");

				// Get optional business key and variables
				String businessKey = getParameter(exchange, "businessKey");
				Map<String, Object> variables = getVariables(exchange);

				// Start process instance based on provided parameters
				if (!Strings.isEmpty(messageName)) {
					if (!Strings.isEmpty(processDefinitionId)) {
						if (!Strings.isEmpty(businessKey)) {
							return runtime.startProcessInstanceByMessageAndProcessDefinitionId(messageName, processDefinitionId, businessKey, variables);
						}
						return runtime.startProcessInstanceByMessageAndProcessDefinitionId(messageName, processDefinitionId, variables);
					}

					if (!Strings.isEmpty(businessKey)) {
						return runtime.startProcessInstanceByMessage(messageName, businessKey, variables);
					}
					return runtime.startProcessInstanceByMessage(messageName, variables);
				}

				if (!Strings.isEmpty(processDefinitionId)) {
					if (!Strings.isEmpty(businessKey)) {
						return runtime.startProcessInstanceById(processDefinitionId, businessKey, variables);
					}
					return runtime.startProcessInstanceById(processDefinitionId, variables);
				}

				if (!Strings.isEmpty(processDefinitionKey)) {
					if (!Strings.isEmpty(businessKey)) {
						return runtime.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
					}
					return runtime.startProcessInstanceByKey(processDefinitionKey, variables);
				}

				throw new IllegalStateException("Either messageName, processDefinitionId, or processDefinitionKey must be provided to start a process instance");
			}
		}

		/**
		 * Producer for correlating a message to a running BPM process instance.
		 * Supports correlating by message name with optional business key and correlation keys, or by process instance id with message name.
		 * Parameters can be provided as endpoint parameters or exchange headers, with endpoint parameters taking precedence.
		 * 
		 * Supported parameters:
		 * - messageName: Name of the message to correlate (required)
		 * - businessKey: Optional business key to correlate the message
		 * - processInstanceId: Optional process instance id to correlate the message to a specific process
		 * - processDefinitionKey: Optional process definition key to filter when correlating by process instance id
		 * - processDefinitionId: Optional process definition id to filter when correlating by process instance
		 * - propertyPrefix: Optional prefix for exchange headers to be included as process variables (default is "bpm_")
		 * - correlationKeyPrefix: Optional prefix for exchange headers to be included as correlation keys (default is "bpm_key_")
		 */
		private class CorrelateMessageProducer extends DefaultProducer {
			private CorrelateMessageProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				correlateMessage(exchange);
			}

			/**
			 * Correlate a message to a running process instance based on provided parameters.
			 * 
			 * Priority for correlating:
			 * 1. messageName + processInstanceId (with optional processDefinitionKey and processDefinitionId filters)
			 * 2. messageName + businessKey + correlationKeys
			 * 3. messageName + correlationKeys
			 */
			private void correlateMessage(Exchange exchange) {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				// Get required message name
				String messageName = getParameter(exchange, "messageName");
				if (Strings.isEmpty(messageName)) {
					throw new IllegalStateException("The message name must not be empty");
				}

				// Get optional business key, variables, and correlation keys
				String businessKey = getParameter(exchange, "businessKey");
				Map<String, Object> variables = getVariables(exchange);
				Map<String, Object> correlationKeys = getCorrelationKeys(exchange);

				// First try to correlate message to a specific process instance if processInstanceId is provided
				String processInstanceId = getParameter(exchange, "processInstanceId");
				if (!Strings.isEmpty(processInstanceId)) {
					ExecutionQuery query = runtime.createExecutionQuery()
							.processInstanceId(processInstanceId)
							.messageEventSubscriptionName(messageName);
					// Optionally filter by process definition key if provided
					String processDefinitionKey = getParameter(exchange, "processDefinitionKey");
					if (!Strings.isEmpty(processDefinitionKey)) {
						query.processDefinitionKey(processDefinitionKey);
					}
					// Optionally filter by process definition id if provided
					String processDefinitionId = getParameter(exchange, "processDefinitionId");
					if (!Strings.isEmpty(processDefinitionId)) {
						query.processDefinitionId(processDefinitionId);
					}
					// Optionally filter by business key if provided
					if (!Strings.isEmpty(businessKey)) {
						query.processInstanceBusinessKey(businessKey);
					}

					// Correlate message to the execution waiting for the message event
					Execution execution = query.singleResult();
					if (execution == null) {
						throw new IllegalStateException("Could not find waiting process instance with id '" + processInstanceId + "' for message '" + messageName + "'");
					}

					// Correlate message to the execution
					runtime.messageEventReceived(messageName, execution.getId(), variables);
					return;
				}

				// Correlate message based on message name and optional correlation keys and business key
				if (!Strings.isEmpty(businessKey)) {
					runtime.correlateMessage(messageName, businessKey, correlationKeys, variables);
					return;
				}
				runtime.correlateMessage(messageName, correlationKeys, variables);
			}
		}
	}
}

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
import java.util.Map.Entry;

import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.CamelContextHelper;
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
		BpmEndpoint endpoint = new BpmEndpoint(uri, remaining);
		setProperties(endpoint, parameters);
		return endpoint;
	}

	public class BpmEndpoint extends DefaultEndpoint {
		private static final String PROCESS_DEFINITION_ID = "processDefinitionId";
		private static final String PROCESS_DEFINITION_KEY = "processDefinitionKey";
		private static final String PROCESS_INSTANCE_ID = "processInstanceId";
		private static final String MESSAGE_NAME = "messageName";
		private static final String BUSINESS_KEY = "businessKey";
		private static final String CORRELATION_KEYS = "correlationKeys";

		private final String fPath;
		private final Map<String, Object> fParameters = new HashMap<>();

		private BpmEndpoint(String endpointUri, String remaining) {
			super(endpointUri, BpmComponent.this);
			fPath = remaining;
		}

		public void setProcessDefinitionId(String value) {
			fParameters.put(PROCESS_DEFINITION_ID, value);
		}

		public void setProcessDefinitionKey(String value) {
			fParameters.put(PROCESS_DEFINITION_KEY, value);
		}

		public void setProcessInstanceId(String value) {
			fParameters.put(PROCESS_INSTANCE_ID, value);
		}

		public void setMessageName(String value) {
			fParameters.put(MESSAGE_NAME, value);
		}

		public void setBusinessKey(String value) {
			fParameters.put(BUSINESS_KEY, value);
		}

		@Override
		public Consumer createConsumer(Processor processor) throws Exception {
			// Not supported
			return null;
		}

		@Override
		public Producer createProducer() throws Exception {
			return new BpmProducer();
		}

		private class BpmProducer extends DefaultProducer {
			private BpmProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				if (fPath.equals("start")) {
					ProcessInstance instance = startProcessInstance(exchange);
					exchange.getMessage().setHeader(PROCESS_DEFINITION_ID, instance.getProcessDefinitionId());
					exchange.getMessage().setHeader(PROCESS_INSTANCE_ID, instance.getProcessInstanceId());
					return;
				}

				if (fPath.equals("message")) {
					correlateMessage(exchange);
					return;
				}

				throw new IllegalStateException("Could not process BPM endpoint");
			}

			private ProcessInstance startProcessInstance(Exchange exchange) {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();
				String businessKey = getBusinessKey(exchange);
				Map<String, Object> variables = getVariables(exchange);

				String processDefinitionId = getProcessDefinitionId(exchange);
				if (!Strings.isEmpty(processDefinitionId)) {
					if (!Strings.isEmpty(businessKey)) {
						return runtime.startProcessInstanceById(processDefinitionId, businessKey, variables);
					}
					return runtime.startProcessInstanceById(processDefinitionId, variables);
				}

				String processDefinitionKey = getProcessDefinitionKey(exchange);
				if (!Strings.isEmpty(processDefinitionKey)) {
					if (!Strings.isEmpty(businessKey)) {
						return runtime.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
					}
					return runtime.startProcessInstanceByKey(processDefinitionKey, variables);
				}

				String messageName = getMessageName(exchange);
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

				throw new IllegalStateException("Could not start BPM process");
			}

			private void correlateMessage(Exchange exchange) {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();
				String messageName = getMessageName(exchange);
				if (Strings.isEmpty(messageName)) {
					throw new IllegalStateException("The message name must not be empty");
				}

				String businessKey = getBusinessKey(exchange);
				Map<String, Object> variables = getVariables(exchange);
				Map<String, Object> correlationKeys = getCorrelationKeys(exchange);

				String processInstanceId = getProcessInstanceId(exchange);
				if (!Strings.isEmpty(processInstanceId)) {
					ExecutionQuery query = runtime.createExecutionQuery()
							.processInstanceId(processInstanceId)
							.messageEventSubscriptionName(messageName);
					String processDefinitionKey = getProcessDefinitionKey(exchange);
					if (!Strings.isEmpty(processDefinitionKey)) {
						query.processDefinitionKey(processDefinitionKey);
					}
					String processDefinitionId = getProcessDefinitionId(exchange);
					if (!Strings.isEmpty(processDefinitionId)) {
						query.processDefinitionId(processDefinitionId);
					}
					if (!Strings.isEmpty(businessKey)) {
						query.processInstanceBusinessKey(businessKey);
					}
					Execution execution = query.singleResult();
					if (execution == null) {
						throw new IllegalStateException("Could not find waiting process instance with id '" + processInstanceId + "' for message '" + messageName + "'");
					}

					runtime.messageEventReceived(messageName, execution.getId(), variables);
					return;
				}

				if (!Strings.isEmpty(businessKey)) {
					runtime.correlateMessage(messageName, businessKey, correlationKeys, variables);
					return;
				}
				runtime.correlateMessage(messageName, correlationKeys, variables);
			}

			private String getProcessDefinitionId(Exchange exchange) {
				Message in = exchange.getIn();
				String value = in.getHeader(PROCESS_DEFINITION_ID, String.class);
				if (value == null) {
					if (fParameters.containsKey(PROCESS_DEFINITION_ID)) {
						value = (String) fParameters.get(PROCESS_DEFINITION_ID);
					}
				}
				return value;
			}

			private String getProcessDefinitionKey(Exchange exchange) {
				Message in = exchange.getIn();
				String value = in.getHeader(PROCESS_DEFINITION_KEY, String.class);
				if (value == null) {
					if (fParameters.containsKey(PROCESS_DEFINITION_KEY)) {
						value = (String) fParameters.get(PROCESS_DEFINITION_KEY);
					}
				}
				return value;
			}

			private String getProcessInstanceId(Exchange exchange) {
				Message in = exchange.getIn();
				String value = in.getHeader(PROCESS_INSTANCE_ID, String.class);
				if (value == null) {
					if (fParameters.containsKey(PROCESS_INSTANCE_ID)) {
						value = (String) fParameters.get(PROCESS_INSTANCE_ID);
					}
				}
				return value;
			}

			private String getMessageName(Exchange exchange) {
				Message in = exchange.getIn();
				String value = in.getHeader(MESSAGE_NAME, String.class);
				if (value == null) {
					if (fParameters.containsKey(MESSAGE_NAME)) {
						value = (String) fParameters.get(MESSAGE_NAME);
					}
				}
				return value;
			}

			private String getBusinessKey(Exchange exchange) {
				Message in = exchange.getIn();
				String value = in.getHeader(BUSINESS_KEY, String.class);
				if (value == null) {
					if (fParameters.containsKey(BUSINESS_KEY)) {
						value = (String) fParameters.get(BUSINESS_KEY);
					}
				}
				return value;
			}

			private Map<String, Object> getCorrelationKeys(Exchange exchange) {
				Message in = exchange.getIn();
				CamelContext context = getEndpoint().getCamelContext();
				Map<?, ?> valueAsMap = in.getHeader(CORRELATION_KEYS, Map.class);
				Map<String, Object> correlationKeys = new HashMap<>();
				if (valueAsMap == null) {
					return correlationKeys;
				}

				for (Entry<?, ?> entry : valueAsMap.entrySet()) {
					String key = CamelContextHelper.convertTo(context, String.class, entry.getKey());
					if (key == null) {
						continue;
					}
					correlationKeys.put(key, entry.getValue());
				}
				return correlationKeys;
			}

			private Map<String, Object> getVariables(Exchange exchange) {
				Message in = exchange.getIn();
				CamelContext context = getEndpoint().getCamelContext();
				Map<?, ?> bodyAsMap = context.getTypeConverter().convertTo(Map.class, exchange, in.getBody());
				Map<String, Object> variables = new HashMap<>();
				if (bodyAsMap == null) {
					return variables;
				}

				for (Entry<?, ?> entry : bodyAsMap.entrySet()) {
					String key = CamelContextHelper.convertTo(context, String.class, entry.getKey());
					if (key == null) {
						continue;
					}
					variables.put(key, entry.getValue());
				}
				return variables;
			}
		}
	}
}

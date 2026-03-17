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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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

		private abstract class BpmProducer extends DefaultProducer {
			protected BpmProducer(Endpoint endpoint) {
				super(endpoint);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				try (ProcessContext context = new ProcessContext(exchange)) {
					doProcess(context);
				}
			}

			protected abstract void doProcess(ProcessContext context) throws Exception;

			/**
			 * Per-invocation context that tracks consumed headers and provides
			 * parameter resolution. Created at the start of each process() call
			 * and cleaned up in its finally block, ensuring thread safety and
			 * no state leakage across invocations.
			 */
			protected class ProcessContext implements Closeable {
				private final Exchange fExchange;
				private final List<String> fConsumedHeaders = new ArrayList<>();

				protected ProcessContext(Exchange exchange) {
					fExchange = exchange;
				}

				/**
				 * Get parameter value from endpoint parameters or exchange headers.
				 */
				public Object getParameter(String key) {
					if (fParameters.containsKey(key)) {
						return fParameters.get(key);
					}

					if (fExchange.getIn().getHeaders().containsKey(key)) {
						if (!fConsumedHeaders.contains(key)) {
							fConsumedHeaders.add(key);
						}
					}
					return fExchange.getIn().getHeader(key);
				}

				/**
				 * Parse a filter parameter into a list of strings.
				 * Supports comma-separated strings, lists, and collections.
				 */
				private List<String> parseFilterList(Object filter) {
					if (filter == null) {
						return Collections.emptyList();
					}
					if (filter instanceof List) {
						return ((List<?>) filter).stream()
								.map(Object::toString)
								.map(String::trim)
								.collect(Collectors.toList());
					}
					if (filter instanceof String) {
						return List.of(((String) filter).split("\\s*,\\s*"));
					}
					if (filter instanceof Collection<?>) {
						return ((Collection<?>) filter).stream()
								.map(Object::toString)
								.map(String::trim)
								.collect(Collectors.toList());
					}
					return List.of(filter.toString().trim());
				}

				/**
				 * Extract variables from exchange headers based on a specified prefix.
				 */
				public Map<String, Object> getVariables() {
					return extractHeadersByFilters(parseFilterList(getParameter("variables")), parseFilterList(getParameter("excludeVariables")));
				}

				/**
				 * Extract correlation keys from exchange headers based on a specified prefix.
				 */
				public Map<String, Object> getCorrelationKeys() {
					return extractHeadersByFilters(parseFilterList(getParameter("correlationKeys")), parseFilterList(getParameter("excludeCorrelationKeys")));
				}

				/**
				 * Extract key-value pairs from exchange headers based on filter rules.
				 *
				 * Filter syntax:
				 *   "varName=headerName" — rename: headers[headerName] → result[varName]
				 *   "prefix*"            — prefix match, keep original key
				 *   "*suffix"            — suffix match, keep original key
				 *   "prefix~"            — prefix match, strip prefix from key
				 *   "~suffix"            — suffix match, strip suffix from key
				 *   "exactKey"           — exact match
				 */
				private Map<String, Object> extractHeadersByFilters(List<String> filters, List<String> excludeFilters) {
					Map<String, Object> result = new HashMap<>();
					Map<String, Object> headers = fExchange.getIn().getHeaders();
					for (String filter : filters) {
						if (Strings.isEmpty(filter)) {
							continue;
						}

						if (filter.indexOf("=") > 0) {
							String[] parts = filter.split("=", 2);
							String resultKey = parts[0].trim();
							String headerName = parts[1].trim();
							if (Objects.equals(headerName.toLowerCase(), "@body")) {
								result.put(resultKey, fExchange.getIn().getBody());
								continue;
							}
							if (headers.containsKey(headerName) && !matches(headerName, excludeFilters)) {
								result.put(resultKey, headers.get(headerName));
							}
							continue;
						}

						if (filter.endsWith("~")) {
							String prefix = filter.substring(0, filter.length() - 1);
							headers.entrySet().stream()
									.filter(entry -> entry.getKey().startsWith(prefix))
									.filter(entry -> !matches(entry.getKey(), excludeFilters))
									.forEach(entry -> result.put(entry.getKey().substring(prefix.length()), entry.getValue()));
						} else if (filter.startsWith("~")) {
							String suffix = filter.substring(1);
							headers.entrySet().stream()
									.filter(entry -> entry.getKey().endsWith(suffix))
									.filter(entry -> !matches(entry.getKey(), excludeFilters))
									.forEach(entry -> result.put(entry.getKey().substring(0, entry.getKey().length() - suffix.length()), entry.getValue()));
						} else if (filter.endsWith("*")) {
							String prefix = filter.substring(0, filter.length() - 1);
							headers.entrySet().stream()
									.filter(entry -> entry.getKey().startsWith(prefix))
									.filter(entry -> !matches(entry.getKey(), excludeFilters))
									.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
						} else if (filter.startsWith("*")) {
							String suffix = filter.substring(1);
							headers.entrySet().stream()
									.filter(entry -> entry.getKey().endsWith(suffix))
									.filter(entry -> !matches(entry.getKey(), excludeFilters))
									.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
						} else {
							if (headers.containsKey(filter) && !matches(filter, excludeFilters)) {
								result.put(filter, headers.get(filter));
							}
						}
					}
					return result;
				}

				/**
				 * Check if a key matches any of the provided filters.
				 */
				private boolean matches(String key, List<String> filters) {
					for (String filter : filters) {
						if (Strings.isEmpty(filter)) {
							continue;
						}

						if (filter.endsWith("*")) {
							String prefix = filter.substring(0, filter.length() - 1);
							if (key.startsWith(prefix)) {
								return true;
							}
						} else if (filter.startsWith("*")) {
							String suffix = filter.substring(1);
							if (key.endsWith(suffix)) {
								return true;
							}
						} else if (filter.endsWith("~")) {
							String prefix = filter.substring(0, filter.length() - 1);
							if (key.startsWith(prefix)) {
								return true;
							}
						} else if (filter.startsWith("~")) {
							String suffix = filter.substring(1);
							if (key.endsWith(suffix)) {
								return true;
							}
						} else {
							if (key.equals(filter)) {
								return true;
							}
						}
					}
					return false;
				}

				/**
				 * Set a header in the exchange and unmark track it as consumed if it was previously marked.
				 * This allows producers to set result headers without them being removed in the finally block.
				 */
				public void setResultHeader(String key, Object value) {
					fExchange.getIn().setHeader(key, value);
					if (fConsumedHeaders.contains(key)) {
						fConsumedHeaders.remove(key);
					}
				}

				@Override
				public void close() throws IOException {
					try {
						for (String header : fConsumedHeaders) {
							fExchange.getIn().removeHeader(header);
						}
					} catch (Throwable ignore) {}
					fConsumedHeaders.clear();
				}
			}
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
		 * - headerFilter: Optional prefix for exchange headers to be included as process variables
		 */
		private class StartProcessProducer extends BpmProducer {
			private StartProcessProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			public void doProcess(ProcessContext pc) throws Exception {
				// Start process instance and set process definition and instance ids in exchange headers
				ProcessInstance instance = startProcessInstance(pc);

				// Set process instance id in exchange headers for downstream processing
				pc.setResultHeader("processInstanceId", instance.getProcessInstanceId());
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
			private ProcessInstance startProcessInstance(ProcessContext pc) {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				// Get required parameters for starting process instance
				String processDefinitionId = (String) pc.getParameter("processDefinitionId");
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				String messageName = (String) pc.getParameter("messageName");

				// Get optional business key and variables
				String businessKey = (String) pc.getParameter("businessKey");
				Map<String, Object> variables = pc.getVariables();

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
		 * - headerFilter: Optional prefix for exchange headers to be included as process variables
		 * - correlationKeyPrefix: Optional prefix for exchange headers to be included as correlation keys
		 */
		private class CorrelateMessageProducer extends BpmProducer {
			private CorrelateMessageProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			public void doProcess(ProcessContext pc) throws Exception {
				correlateMessage(pc);
			}

			/**
			 * Correlate a message to a running process instance based on provided parameters.
			 * 
			 * Priority for correlating:
			 * 1. messageName + processInstanceId (with optional processDefinitionKey and processDefinitionId filters)
			 * 2. messageName + businessKey + correlationKeys
			 * 3. messageName + correlationKeys
			 */
			private void correlateMessage(ProcessContext pc) {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				// Get required message name
				String messageName = (String) pc.getParameter("messageName");
				if (Strings.isEmpty(messageName)) {
					throw new IllegalStateException("The message name must not be empty");
				}

				// Get optional parameters for correlating message
				String processInstanceId = (String) pc.getParameter("processInstanceId");
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				String processDefinitionId = (String) pc.getParameter("processDefinitionId");

				// Get optional business key, variables, and correlation keys
				String businessKey = (String) pc.getParameter("businessKey");
				Map<String, Object> variables = pc.getVariables();
				Map<String, Object> correlationKeys = pc.getCorrelationKeys();

				// First try to correlate message to a specific process instance if processInstanceId is provided
				if (!Strings.isEmpty(processInstanceId)) {
					ExecutionQuery query = runtime.createExecutionQuery()
							.processInstanceId(processInstanceId)
							.messageEventSubscriptionName(messageName);
					// Optionally filter by process definition key if provided
					if (!Strings.isEmpty(processDefinitionKey)) {
						query.processDefinitionKey(processDefinitionKey);
					}
					// Optionally filter by process definition id if provided
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

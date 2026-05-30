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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.apache.camel.support.ScheduledPollConsumer;
import org.camunda.bpm.dmn.engine.DmnDecisionResult;
import org.camunda.bpm.engine.DecisionService;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.dmn.DecisionsEvaluationBuilder;
import org.camunda.bpm.engine.externaltask.ExternalTaskQueryBuilder;
import org.camunda.bpm.engine.externaltask.ExternalTaskQueryTopicBuilder;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstanceQuery;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstanceQuery;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ExecutionQuery;
import org.camunda.bpm.engine.runtime.Incident;
import org.camunda.bpm.engine.runtime.IncidentQuery;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.JobQuery;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstanceModificationBuilder;
import org.camunda.bpm.engine.runtime.ProcessInstanceModificationInstantiationBuilder;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;
import org.camunda.bpm.engine.runtime.SignalEventReceivedBuilder;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.task.TaskQuery;
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
			// Only the external task topic subscription supports consuming (driving a route from Camunda).
			if ("externalTask".equals(fOperation)) {
				ExternalTaskConsumer consumer = new ExternalTaskConsumer(processor);
				consumer.setDelay(longParameter("delay", 5000L));
				consumer.setInitialDelay(longParameter("initialDelay", 1000L));
				return consumer;
			}

			throw new UnsupportedOperationException("The BPM operation '" + fOperation
					+ "' cannot be used as a consumer (from). Only 'externalTask' supports consuming.");
		}

		/**
		 * Read an endpoint parameter as a {@code long}, returning the default when it is missing or blank.
		 */
		private long longParameter(String key, long defaultValue) {
			Object value = fParameters.get(key);
			if (value == null) {
				return defaultValue;
			}
			String text = value.toString().trim();
			if (text.isEmpty()) {
				return defaultValue;
			}
			return Long.parseLong(text);
		}

		/**
		 * Read an endpoint parameter as a {@code boolean}, returning the default when it is missing or blank.
		 */
		private boolean booleanParameter(String key, boolean defaultValue) {
			Object value = fParameters.get(key);
			if (value == null) {
				return defaultValue;
			}
			if (value instanceof Boolean) {
				return ((Boolean) value).booleanValue();
			}
			String text = value.toString().trim();
			if (text.isEmpty()) {
				return defaultValue;
			}
			return Boolean.parseBoolean(text);
		}

		/**
		 * Read an endpoint parameter as a string (trimmed value preserved), or {@code null} when absent.
		 */
		private String stringParameter(String key) {
			Object value = fParameters.get(key);
			return (value == null) ? null : value.toString();
		}

		/**
		 * Read an endpoint parameter as a list of strings, splitting comma-separated values and trimming
		 * each entry. Lists and collections are accepted as-is.
		 */
		private List<String> parameterList(String key) {
			Object value = fParameters.get(key);
			if (value == null) {
				return Collections.emptyList();
			}
			if (value instanceof Collection) {
				return ((Collection<?>) value).stream()
						.map(Object::toString)
						.map(String::trim)
						.filter(s -> !s.isEmpty())
						.collect(Collectors.toList());
			}
			List<String> result = new ArrayList<>();
			for (String part : value.toString().split("\\s*,\\s*")) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty()) {
					result.add(trimmed);
				}
			}
			return result;
		}

		/**
		 * Resolve the Camunda {@link ExternalTaskService} for this component's workspace.
		 */
		private ExternalTaskService externalTaskService() {
			return CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine().getExternalTaskService();
		}

		@Override
		public Producer createProducer() throws Exception {
			// Determine operation type
			if ("startProcess".equals(fOperation)) {
				return new StartProcessProducer();
			}
			if ("correlateMessage".equals(fOperation)) {
				return new CorrelateMessageProducer();
			}
			if ("signalProcess".equals(fOperation)) {
				return new SignalProcessProducer();
			}
			if ("setVariables".equals(fOperation)) {
				return new SetVariablesProducer();
			}
			if ("getVariables".equals(fOperation)) {
				return new GetVariablesProducer();
			}
			if ("suspendProcess".equals(fOperation)) {
				return new SuspendProcessProducer(true);
			}
			if ("resumeProcess".equals(fOperation)) {
				return new SuspendProcessProducer(false);
			}
			if ("deleteProcess".equals(fOperation)) {
				return new DeleteProcessProducer();
			}
			if ("queryProcessInstances".equals(fOperation)) {
				return new QueryProcessInstancesProducer();
			}
			if ("modifyProcess".equals(fOperation)) {
				return new ModifyProcessProducer();
			}
			if ("completeExternalTask".equals(fOperation)) {
				return new CompleteExternalTaskProducer();
			}
			if ("failExternalTask".equals(fOperation)) {
				return new FailExternalTaskProducer();
			}
			if ("bpmnErrorExternalTask".equals(fOperation)) {
				return new BpmnErrorExternalTaskProducer();
			}
			if ("extendLock".equals(fOperation)) {
				return new ExtendLockProducer();
			}
			if ("evaluateDecision".equals(fOperation)) {
				return new EvaluateDecisionProducer();
			}
			if ("completeTask".equals(fOperation)) {
				return new CompleteTaskProducer();
			}
			if ("claimTask".equals(fOperation)) {
				return new ClaimTaskProducer(true);
			}
			if ("unclaimTask".equals(fOperation)) {
				return new ClaimTaskProducer(false);
			}
			if ("setAssignee".equals(fOperation)) {
				return new SetAssigneeProducer();
			}
			if ("delegateTask".equals(fOperation)) {
				return new DelegateTaskProducer();
			}
			if ("resolveTask".equals(fOperation)) {
				return new ResolveTaskProducer();
			}
			if ("addTaskComment".equals(fOperation)) {
				return new AddTaskCommentProducer();
			}
			if ("addAttachment".equals(fOperation)) {
				return new AddAttachmentProducer();
			}
			if ("queryTasks".equals(fOperation)) {
				return new QueryTasksProducer();
			}
			if ("queryIncidents".equals(fOperation)) {
				return new QueryIncidentsProducer();
			}
			if ("queryJobs".equals(fOperation)) {
				return new QueryJobsProducer();
			}
			if ("retryJob".equals(fOperation)) {
				return new RetryJobProducer();
			}
			if ("queryHistoricProcessInstances".equals(fOperation)) {
				return new QueryHistoricProcessInstancesProducer();
			}
			if ("queryHistoricVariables".equals(fOperation)) {
				return new QueryHistoricVariablesProducer();
			}
			if ("deployResource".equals(fOperation)) {
				return new DeployResourceProducer();
			}
			if ("suspendDefinition".equals(fOperation)) {
				return new SuspendDefinitionProducer(true);
			}
			if ("activateDefinition".equals(fOperation)) {
				return new SuspendDefinitionProducer(false);
			}
			if ("queryProcessDefinitions".equals(fOperation)) {
				return new QueryProcessDefinitionsProducer();
			}

			// Unsupported operation
			throw new UnsupportedOperationException("Could not process BPM endpoint");
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
			 * ProcessContext provides convenient access to endpoint parameters and exchange data for producers.
			 */
			protected class ProcessContext implements Closeable {
				private final Exchange fExchange;

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
				 * Select process variables from exchange headers using the same includes/excludes scheme
				 * as {@code CmsComponent.SetPropertiesProducer}. Map values are passed through as-is
				 * (there is no delimiter-based expansion for process variables).
				 */
				public Map<String, Object> getVariables() {
					return extractHeadersByFilters(parseFilterList(getParameter("includes")), parseFilterList(getParameter("excludes")));
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
				 * Set a header in the exchange for downstream processing.
				 * This can be used to pass information such as process instance id to subsequent steps in the route.
				 * Headers set here will be available in the exchange after the producer finishes processing.
				 */
				public void setHeader(String key, Object value) {
					fExchange.getIn().setHeader(key, value);
				}

				/**
				 * Set the message body in the exchange for downstream processing.
				 */
				public void setBody(Object value) {
					fExchange.getIn().setBody(value);
				}

				/**
				 * Get the current message body from the exchange.
				 */
				public Object getBody() {
					return fExchange.getIn().getBody();
				}

				/**
				 * Resolve a source reference to its raw value from the exchange.
				 *
				 * Reference syntax (case-insensitive prefixes):
				 *   "@body"          — the message body
				 *   "@header.name"   — the named header
				 *   "@property.name" — the named exchange property
				 * Any other (or null) reference resolves to {@code null}.
				 */
				public Object resolveSource(String reference) {
					if (Strings.isEmpty(reference)) {
						return null;
					}
					String trimmed = reference.trim();
					String lower = trimmed.toLowerCase();
					if (lower.equals("@body")) {
						return fExchange.getIn().getBody();
					}
					if (lower.startsWith("@header.")) {
						return fExchange.getIn().getHeader(trimmed.substring("@header.".length()));
					}
					if (lower.startsWith("@property.")) {
						return fExchange.getProperty(trimmed.substring("@property.".length()));
					}
					return null;
				}

				/**
				 * Whether the given reference uses the {@code @body} / {@code @header.x} / {@code @property.x}
				 * indirection syntax (as opposed to being a literal value).
				 */
				public boolean isSourceReference(String reference) {
					if (Strings.isEmpty(reference)) {
						return false;
					}
					String lower = reference.trim().toLowerCase();
					return lower.equals("@body") || lower.startsWith("@header.") || lower.startsWith("@property.");
				}

				/**
				 * Convert a value to an {@link InputStream} using Camel's type converter, so that byte arrays,
				 * strings, files and existing streams are all accepted as attachment content.
				 */
				public InputStream toInputStream(Object value) {
					if (value == null) {
						return null;
					}
					if (value instanceof InputStream) {
						return (InputStream) value;
					}
					return fExchange.getContext().getTypeConverter().convertTo(InputStream.class, fExchange, value);
				}

				/**
				 * Set an exchange property for downstream processing.
				 */
				public void setProperty(String key, Object value) {
					fExchange.setProperty(key, value);
				}

				/**
				 * Get a parameter value interpreted as a boolean.
				 * Accepts {@link Boolean} values as well as their string representations ("true"/"false").
				 * Returns the supplied default value when the parameter is missing or blank.
				 */
				public boolean getParameterAsBoolean(String key, boolean defaultValue) {
					Object value = getParameter(key);
					if (value == null) {
						return defaultValue;
					}
					if (value instanceof Boolean) {
						return ((Boolean) value).booleanValue();
					}
					String text = value.toString().trim();
					if (text.isEmpty()) {
						return defaultValue;
					}
					return Boolean.parseBoolean(text);
				}

				/**
				 * Get a parameter value interpreted as a {@code long}. Accepts {@link Number} values as well
				 * as their string representations. Returns the supplied default when the parameter is missing
				 * or blank.
				 */
				public long getParameterAsLong(String key, long defaultValue) {
					Object value = getParameter(key);
					if (value == null) {
						return defaultValue;
					}
					if (value instanceof Number) {
						return ((Number) value).longValue();
					}
					String text = value.toString().trim();
					if (text.isEmpty()) {
						return defaultValue;
					}
					return Long.parseLong(text);
				}

				/**
				 * Get a parameter value interpreted as an {@code Integer}, or {@code null} when the parameter
				 * is missing or blank. Accepts {@link Number} values as well as their string representations.
				 */
				public Integer getParameterAsInteger(String key) {
					Object value = getParameter(key);
					if (value == null) {
						return null;
					}
					if (value instanceof Number) {
						return ((Number) value).intValue();
					}
					String text = value.toString().trim();
					if (text.isEmpty()) {
						return null;
					}
					return Integer.valueOf(text);
				}

				/**
				 * Parse the given value into a list of strings, applying the same rules as the variable/correlation key filters.
				 */
				public List<String> asList(Object filter) {
					return parseFilterList(filter);
				}

				/**
				 * Check whether the given key matches any of the supplied filters.
				 * Exposes the internal {@link #matches(String, List)} rules (prefix*, *suffix,
				 * prefix~, ~suffix and exact match) to the producers.
				 */
				public boolean matchesAny(String key, List<String> filters) {
					return matches(key, filters);
				}

				/**
				 * Enumerate every available parameter name, combining endpoint (URL) parameters
				 * with exchange header names. Endpoint parameters take precedence and are listed first;
				 * header names already present as endpoint parameters are not duplicated.
				 */
				public List<String> getParameterNames() {
					List<String> names = new ArrayList<>(fParameters.keySet());
					for (String headerName : fExchange.getIn().getHeaders().keySet()) {
						if (!names.contains(headerName)) {
							names.add(headerName);
						}
					}
					return names;
				}

				/**
				 * A single output binding describing how a named result source should be placed
				 * back into the exchange (body, a header, or an exchange property).
				 */
				private final class ResultBinding {
					private final String kind; // "body", "header" or "property"
					private final String target; // header/property name (null for body)
					private final String sourceName;

					private ResultBinding(String kind, String target, String sourceName) {
						this.kind = kind;
						this.target = target;
						this.sourceName = sourceName;
					}
				}

				/**
				 * Collect output bindings declared either as endpoint (URL) parameters or as exchange headers.
				 *
				 * Binding syntax:
				 *   "@body=sourceName"          — set the message body to the named source value
				 *   "@header.headerName=source" — set the header "headerName" to the named source value
				 *   "@property.propName=source" — set the exchange property "propName" to the named source value
				 *
				 * Endpoint parameters take precedence over exchange headers for the same binding key,
				 * so a binding may be supplied through either channel (URL parameter first, header as fallback).
				 */
				private List<ResultBinding> collectBindings() {
					Map<String, Object> declared = new LinkedHashMap<>();
					for (Map.Entry<String, Object> entry : fParameters.entrySet()) {
						if (isBindingKey(entry.getKey())) {
							declared.put(entry.getKey(), entry.getValue());
						}
					}
					for (Map.Entry<String, Object> entry : fExchange.getIn().getHeaders().entrySet()) {
						if (isBindingKey(entry.getKey()) && !declared.containsKey(entry.getKey())) {
							declared.put(entry.getKey(), entry.getValue());
						}
					}

					List<ResultBinding> bindings = new ArrayList<>();
					for (Map.Entry<String, Object> entry : declared.entrySet()) {
						String key = entry.getKey();
						String sourceName = (entry.getValue() == null) ? null : entry.getValue().toString().trim();
						if (key.equals("@body")) {
							bindings.add(new ResultBinding("body", null, sourceName));
						} else if (key.startsWith("@header.")) {
							bindings.add(new ResultBinding("header", key.substring("@header.".length()), sourceName));
						} else if (key.startsWith("@property.")) {
							bindings.add(new ResultBinding("property", key.substring("@property.".length()), sourceName));
						}
					}
					return bindings;
				}

				/**
				 * Whether the given key declares an output binding (@body / @header.x / @property.x).
				 */
				private boolean isBindingKey(String key) {
					if (Strings.isEmpty(key)) {
						return false;
					}
					return key.equals("@body") || key.startsWith("@header.") || key.startsWith("@property.");
				}

				/**
				 * The set of result source names referenced by the declared output bindings.
				 * Callers use this to compute only the sources that are actually needed (for example,
				 * to avoid listing all matches when only a count is bound).
				 * <p>
				 * Because bindings may be declared as exchange headers, this set can include names left
				 * over from a binding set for a previously invoked producer in the same route. Callers must
				 * therefore test for the specific source names they support (rather than treating a
				 * non-empty set as "something was requested"), so that a foreign leftover binding never
				 * changes this producer's behaviour.
				 */
				public Set<String> getBoundSourceNames() {
					Set<String> names = new HashSet<>();
					for (ResultBinding binding : collectBindings()) {
						names.add(binding.sourceName);
					}
					return names;
				}

				/**
				 * Bind named result values to the exchange according to the declared output bindings.
				 * Only bindings whose source name is present in {@code sources} take effect; bindings that
				 * reference an unknown source (for example, a binding left over from a previously invoked
				 * producer) are ignored. Output is produced solely through these bindings — there is no
				 * implicit default such as placing a list in the body or a count in a header.
				 */
				public void applyResultBindings(Map<String, Object> sources) {
					for (ResultBinding binding : collectBindings()) {
						if (!sources.containsKey(binding.sourceName)) {
							continue;
						}

						Object value = sources.get(binding.sourceName);
						if ("body".equals(binding.kind)) {
							setBody(value);
						} else if ("header".equals(binding.kind)) {
							setHeader(binding.target, value);
						} else {
							setProperty(binding.target, value);
						}
					}
				}

				@Override
				public void close() throws IOException {
					// No resources to clean up in this implementation, but this is where you would release any resources if needed
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
		 * - includes: Optional filter selecting exchange headers to pass as process variables (same scheme as CmsComponent.SetPropertiesProducer)
		 * - excludes: Optional filter excluding headers from the process variables
		 */
		private class StartProcessProducer extends BpmProducer {
			private StartProcessProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				// Start process instance and set process definition and instance ids in exchange headers
				ProcessInstance instance = startProcessInstance(pc);

				// Set process instance id in exchange headers for downstream processing
				pc.setHeader("processInstanceId", instance.getProcessInstanceId());
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
		 * - includes: Optional filter selecting exchange headers to pass as process variables (same scheme as CmsComponent.SetPropertiesProducer)
		 * - excludes: Optional filter excluding headers from the process variables
		 * - correlationKeys: Optional filter selecting exchange headers to pass as correlation keys
		 * - excludeCorrelationKeys: Optional filter excluding headers from the correlation keys
		 */
		private class CorrelateMessageProducer extends BpmProducer {
			private CorrelateMessageProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
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

		/**
		 * Producer for broadcasting or targeting a BPMN signal event.
		 * This is the signal counterpart of correlateMessage: signals may be broadcast to all
		 * waiting subscriptions, or delivered to a single execution when an executionId is given.
		 *
		 * Supported parameters:
		 * - signalName: Name of the signal to throw (required)
		 * - executionId: Optional execution id to deliver the signal to a specific waiting execution
		 * - includes: Optional filter selecting exchange headers to pass as process variables (same scheme as CmsComponent.SetPropertiesProducer)
		 * - excludes: Optional filter excluding headers from the process variables
		 */
		private class SignalProcessProducer extends BpmProducer {
			private SignalProcessProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				String signalName = (String) pc.getParameter("signalName");
				if (Strings.isEmpty(signalName)) {
					throw new IllegalStateException("The signal name must not be empty");
				}

				String executionId = (String) pc.getParameter("executionId");
				Map<String, Object> variables = pc.getVariables();

				SignalEventReceivedBuilder builder = runtime.createSignalEvent(signalName).setVariables(variables);
				if (!Strings.isEmpty(executionId)) {
					builder.executionId(executionId);
				}
				builder.send();
			}
		}

		/**
		 * Producer for updating variables of a running process instance (or execution).
		 *
		 * Supported parameters:
		 * - executionId: Execution id whose variables are updated (falls back to processInstanceId)
		 * - processInstanceId: Process instance id used when executionId is not provided
		 * - includes: Filter selecting exchange headers to set as process variables (same scheme as CmsComponent.SetPropertiesProducer; Map values are set as-is)
		 * - excludes: Optional filter excluding headers from the process variables
		 * - local: When true, sets the variables in the local scope only (default false)
		 */
		private class SetVariablesProducer extends BpmProducer {
			private SetVariablesProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				String executionId = (String) pc.getParameter("executionId");
				if (Strings.isEmpty(executionId)) {
					executionId = (String) pc.getParameter("processInstanceId");
				}
				if (Strings.isEmpty(executionId)) {
					throw new IllegalStateException("Either executionId or processInstanceId must be provided to set variables");
				}

				Map<String, Object> variables = pc.getVariables();
				if (pc.getParameterAsBoolean("local", false)) {
					runtime.setVariablesLocal(executionId, variables);
				} else {
					runtime.setVariables(executionId, variables);
				}
			}
		}

		/**
		 * Producer for reading variables of a running process instance (or execution) into the exchange.
		 *
		 * Supported parameters:
		 * - executionId: Execution id whose variables are read (falls back to processInstanceId)
		 * - processInstanceId: Process instance id used when executionId is not provided
		 * - local: When true, reads variables from the local scope only (default false)
		 * - includes: Comma-separated variable-name filters to expose (wildcards supported: "prefix*",
		 *     "*suffix", exact). When omitted, all variables are eligible.
		 * - excludes: Comma-separated variable-name filters to skip (same wildcard rules as includes).
		 *
		 * Flexible variable-to-target mapping (mirrors CmsComponent.GetPropertiesProducer), declared as
		 * endpoint parameters or exchange headers (URL parameter takes precedence over header):
		 *   "@header.headerName=variableName" — map a variable to a specific header
		 *   "@header.headerName"              — map the variable of the same name to that header
		 *   "@header.*=var1,var2"             — expose the named variables under their own names ("@header.*" = all)
		 *   "@header.prefix*=var1,var2"       — expose the named variables with a common header prefix ("*" = all)
		 *   "@header.*suffix=var1,var2"       — expose the named variables with a common header suffix ("*" = all)
		 *   "@body=variableName"              — set the message body from a variable
		 *   "@property.propName=variableName" — set an exchange property from a variable
		 *
		 * When no includes/excludes, selector, or mapping is specified, every variable is exposed as a
		 * header keyed by its variable name.
		 */
		private class GetVariablesProducer extends BpmProducer {
			private GetVariablesProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				String executionId = (String) pc.getParameter("executionId");
				if (Strings.isEmpty(executionId)) {
					executionId = (String) pc.getParameter("processInstanceId");
				}
				if (Strings.isEmpty(executionId)) {
					throw new IllegalStateException("Either executionId or processInstanceId must be provided to get variables");
				}

				Map<String, Object> variables;
				if (pc.getParameterAsBoolean("local", false)) {
					variables = runtime.getVariablesLocal(executionId);
				} else {
					variables = runtime.getVariables(executionId);
				}

				List<String> includes = pc.asList(pc.getParameter("includes"));
				List<String> excludes = pc.asList(pc.getParameter("excludes"));

				// 1. includes/excludes: expose matching variables under their own variable name.
				if (!includes.isEmpty() || !excludes.isEmpty()) {
					for (Map.Entry<String, Object> entry : variables.entrySet()) {
						String name = entry.getKey();
						boolean included = includes.isEmpty() || pc.matchesAny(name, includes);
						if (!included || pc.matchesAny(name, excludes)) {
							continue;
						}
						pc.setHeader(name, entry.getValue());
					}
				}

				// 2. Flexible @header.* / @body / @property.x mapping (endpoint parameters or headers).
				applyVariableBindings(pc, variables, excludes);
			}

			/**
			 * Apply the flexible @header.* / @body / @property.x mappings, treating process variable
			 * names as the available sources. Mirrors the behaviour of CmsComponent.GetPropertiesProducer.
			 * Returns {@code true} when at least one mapping directive was found.
			 */
			private boolean applyVariableBindings(ProcessContext pc, Map<String, Object> variables, List<String> excludes) {
				boolean applied = false;
				for (String name : pc.getParameterNames()) {
					if (Strings.isEmpty(name)) {
						continue;
					}
					String lower = name.toLowerCase();

					if (lower.startsWith("@header.")) {
						String headerName = name.substring("@header.".length());
						if (headerName.isEmpty()) {
							continue;
						}

						if (headerName.equals("*")) {
							// @header.*=var1,var2 (or @header.* / @header.*=* for all): expose under the variable's own name.
							String filter = Strings.defaultIfEmpty(asString(pc.getParameter(name)), "*").trim();
							if (filter.equals("*")) {
								for (Map.Entry<String, Object> entry : variables.entrySet()) {
									if (pc.matchesAny(entry.getKey(), excludes)) {
										continue;
									}
									pc.setHeader(entry.getKey(), entry.getValue());
								}
							} else {
								for (String variableName : pc.asList(filter)) {
									if (pc.matchesAny(variableName, excludes) || !variables.containsKey(variableName)) {
										continue;
									}
									pc.setHeader(variableName, variables.get(variableName));
								}
							}
							applied = true;
						} else if (headerName.endsWith("*")) {
							// @header.prefix*=var1,var2 (or *): expose under "prefix" + variable name.
							String filter = Strings.defaultIfEmpty(asString(pc.getParameter(name)), "*").trim();
							String prefix = headerName.substring(0, headerName.length() - 1);
							if (filter.equals("*")) {
								for (Map.Entry<String, Object> entry : variables.entrySet()) {
									if (pc.matchesAny(entry.getKey(), excludes)) {
										continue;
									}
									pc.setHeader(prefix + entry.getKey(), entry.getValue());
								}
							} else {
								for (String variableName : pc.asList(filter)) {
									if (pc.matchesAny(variableName, excludes) || !variables.containsKey(variableName)) {
										continue;
									}
									pc.setHeader(prefix + variableName, variables.get(variableName));
								}
							}
							applied = true;
						} else if (headerName.startsWith("*")) {
							// @header.*suffix=var1,var2 (or *): expose under variable name + "suffix".
							String filter = Strings.defaultIfEmpty(asString(pc.getParameter(name)), "*").trim();
							String suffix = headerName.substring(1);
							if (filter.equals("*")) {
								for (Map.Entry<String, Object> entry : variables.entrySet()) {
									if (pc.matchesAny(entry.getKey(), excludes)) {
										continue;
									}
									pc.setHeader(entry.getKey() + suffix, entry.getValue());
								}
							} else {
								for (String variableName : pc.asList(filter)) {
									if (pc.matchesAny(variableName, excludes) || !variables.containsKey(variableName)) {
										continue;
									}
									pc.setHeader(variableName + suffix, variables.get(variableName));
								}
							}
							applied = true;
						} else {
							// @header.headerName=variableName, or @header.headerName for same-name mapping.
							String variableName = asString(pc.getParameter(name));
							if (Strings.isEmpty(variableName)) {
								variableName = headerName;
							}
							if (!pc.matchesAny(variableName, excludes) && variables.containsKey(variableName)) {
								pc.setHeader(headerName, variables.get(variableName));
							}
							applied = true;
						}
					} else if (lower.equals("@body")) {
						String variableName = asString(pc.getParameter(name));
						if (!Strings.isEmpty(variableName)) {
							if (variables.containsKey(variableName)) {
								pc.setBody(variables.get(variableName));
							}
							applied = true;
						}
					} else if (lower.startsWith("@property.")) {
						String propertyName = name.substring("@property.".length());
						if (propertyName.isEmpty()) {
							continue;
						}
						String variableName = asString(pc.getParameter(name));
						if (Strings.isEmpty(variableName)) {
							variableName = propertyName;
						}
						if (!pc.matchesAny(variableName, excludes) && variables.containsKey(variableName)) {
							pc.setProperty(propertyName, variables.get(variableName));
						}
						applied = true;
					}
				}
				return applied;
			}

			private String asString(Object value) {
				return (value == null) ? null : value.toString();
			}
		}

		/**
		 * Producer for suspending or activating (resuming) process instances.
		 * The target may be a single process instance, or every instance of a process definition.
		 *
		 * Supported parameters (evaluated in priority order):
		 * - processInstanceId: Suspend/activate a single instance
		 * - processDefinitionId: Suspend/activate all instances of the definition (by id)
		 * - processDefinitionKey: Suspend/activate all instances of the definition (by key)
		 */
		private class SuspendProcessProducer extends BpmProducer {
			private final boolean fSuspend;

			private SuspendProcessProducer(boolean suspend) {
				super(BpmEndpoint.this);
				fSuspend = suspend;
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				String processInstanceId = (String) pc.getParameter("processInstanceId");
				String processDefinitionId = (String) pc.getParameter("processDefinitionId");
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");

				if (!Strings.isEmpty(processInstanceId)) {
					if (fSuspend) {
						runtime.suspendProcessInstanceById(processInstanceId);
					} else {
						runtime.activateProcessInstanceById(processInstanceId);
					}
					return;
				}

				if (!Strings.isEmpty(processDefinitionId)) {
					if (fSuspend) {
						runtime.suspendProcessInstanceByProcessDefinitionId(processDefinitionId);
					} else {
						runtime.activateProcessInstanceByProcessDefinitionId(processDefinitionId);
					}
					return;
				}

				if (!Strings.isEmpty(processDefinitionKey)) {
					if (fSuspend) {
						runtime.suspendProcessInstanceByProcessDefinitionKey(processDefinitionKey);
					} else {
						runtime.activateProcessInstanceByProcessDefinitionKey(processDefinitionKey);
					}
					return;
				}

				throw new IllegalStateException("Either processInstanceId, processDefinitionId, or processDefinitionKey must be provided");
			}
		}

		/**
		 * Producer for cancelling (deleting) a running process instance.
		 *
		 * Supported parameters:
		 * - processInstanceId: Process instance id to delete (required)
		 * - reason: Optional reason recorded for the cancellation
		 * - skipCustomListeners: When true, execution and task listeners are not invoked (default false)
		 *
		 * Sets the "deletedProcessInstanceId" header for downstream processing.
		 */
		private class DeleteProcessProducer extends BpmProducer {
			private DeleteProcessProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				String processInstanceId = (String) pc.getParameter("processInstanceId");
				if (Strings.isEmpty(processInstanceId)) {
					throw new IllegalStateException("The process instance id must not be empty");
				}

				String reason = (String) pc.getParameter("reason");
				if (pc.getParameterAsBoolean("skipCustomListeners", false)) {
					runtime.deleteProcessInstance(processInstanceId, reason, true);
				} else {
					runtime.deleteProcessInstance(processInstanceId, reason);
				}

				pc.setHeader("deletedProcessInstanceId", processInstanceId);
			}
		}

		/**
		 * Producer for querying running process instances.
		 *
		 * Supported filter parameters (all optional; combined with AND semantics):
		 * - processInstanceId: Match a single instance id
		 * - businessKey: Match the business key
		 * - processDefinitionId: Match the process definition id
		 * - processDefinitionKey: Match the process definition key
		 * - active: When true, restrict to active (non-suspended) instances
		 * - suspended: When true, restrict to suspended instances
		 *
		 * Variable-value filters (combined with AND semantics) may be declared as endpoint parameters
		 * or exchange headers, keyed by the variable name. Each may be supplied through either channel
		 * (URL parameter takes precedence over header):
		 * - var.&lt;name&gt; / varEquals.&lt;name&gt;: variable equals the value
		 * - varNotEquals.&lt;name&gt;: variable does not equal the value
		 * - varGreaterThan.&lt;name&gt; / varGreaterThanOrEqual.&lt;name&gt;: variable &gt; / &gt;= the value
		 * - varLessThan.&lt;name&gt; / varLessThanOrEqual.&lt;name&gt;: variable &lt; / &lt;= the value
		 * - varLike.&lt;name&gt;: string variable matches the SQL LIKE pattern (use % as wildcard)
		 * String values are coerced to Boolean/Long/Double when they look numeric/boolean so that
		 * comparisons against typed variables work; supply a typed header value to bypass coercion.
		 *
		 * Results are returned via output bindings (@body / @header.x / @property.x), declared as
		 * endpoint parameters or exchange headers (URL parameter takes precedence over header).
		 * The available sources are:
		 * - count: number of matching instances (Integer)
		 * - ids: list of process instance ids (List)
		 * - instances: list of detail maps, one per instance (List)
		 *
		 * Output is produced solely through the declared bindings: callers must set the bindings they
		 * need before invoking this producer. The list of matches is materialised only when a list
		 * source (ids/instances) is bound; when only the count is bound the matches are counted without
		 * being listed (an efficient count-only query).
		 */
		private class QueryProcessInstancesProducer extends BpmProducer {
			private QueryProcessInstancesProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				ProcessInstanceQuery query = runtime.createProcessInstanceQuery();
				String processInstanceId = (String) pc.getParameter("processInstanceId");
				if (!Strings.isEmpty(processInstanceId)) {
					query.processInstanceId(processInstanceId);
				}
				String businessKey = (String) pc.getParameter("businessKey");
				if (!Strings.isEmpty(businessKey)) {
					query.processInstanceBusinessKey(businessKey);
				}
				String processDefinitionId = (String) pc.getParameter("processDefinitionId");
				if (!Strings.isEmpty(processDefinitionId)) {
					query.processDefinitionId(processDefinitionId);
				}
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				if (!Strings.isEmpty(processDefinitionKey)) {
					query.processDefinitionKey(processDefinitionKey);
				}
				if (pc.getParameterAsBoolean("active", false)) {
					query.active();
				}
				if (pc.getParameterAsBoolean("suspended", false)) {
					query.suspended();
				}
				applyVariableValueFilters(pc, query);

				// Materialise only the result sources that the declared bindings actually reference, so a
				// count-only request avoids listing (and materialising details for) every matching instance.
				Set<String> requested = pc.getBoundSourceNames();
				boolean needList = requested.contains("ids") || requested.contains("instances");

				Map<String, Object> sources = new HashMap<>();
				if (needList) {
					List<ProcessInstance> found = query.list();
					List<Object> ids = new ArrayList<>();
					List<Object> instances = new ArrayList<>();
					for (ProcessInstance instance : found) {
						ids.add(instance.getProcessInstanceId());

						Map<String, Object> detail = new LinkedHashMap<>();
						detail.put("processInstanceId", instance.getProcessInstanceId());
						detail.put("processDefinitionId", instance.getProcessDefinitionId());
						detail.put("businessKey", instance.getBusinessKey());
						detail.put("rootProcessInstanceId", instance.getRootProcessInstanceId());
						detail.put("caseInstanceId", instance.getCaseInstanceId());
						detail.put("tenantId", instance.getTenantId());
						detail.put("suspended", instance.isSuspended());
						detail.put("ended", instance.isEnded());
						instances.add(detail);
					}

					sources.put("count", found.size());
					sources.put("ids", ids);
					sources.put("instances", instances);
				} else if (requested.contains("count")) {
					// Only the count was requested: count without listing.
					sources.put("count", (int) query.count());
				}
				pc.applyResultBindings(sources);
			}

			/**
			 * Apply variable-value restrictions declared as "var.&lt;name&gt;" (and operator-specific
			 * variants) to the query. Parameter names may come from endpoint parameters or exchange headers.
			 */
			private void applyVariableValueFilters(ProcessContext pc, ProcessInstanceQuery query) {
				for (String name : pc.getParameterNames()) {
					if (Strings.isEmpty(name)) {
						continue;
					}

					String variableName;
					Object value = pc.getParameter(name);
					if (name.startsWith("varNotEquals.")) {
						variableName = name.substring("varNotEquals.".length());
						if (!Strings.isEmpty(variableName)) {
							query.variableValueNotEquals(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varGreaterThanOrEqual.")) {
						variableName = name.substring("varGreaterThanOrEqual.".length());
						if (!Strings.isEmpty(variableName)) {
							query.variableValueGreaterThanOrEqual(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varGreaterThan.")) {
						variableName = name.substring("varGreaterThan.".length());
						if (!Strings.isEmpty(variableName)) {
							query.variableValueGreaterThan(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varLessThanOrEqual.")) {
						variableName = name.substring("varLessThanOrEqual.".length());
						if (!Strings.isEmpty(variableName)) {
							query.variableValueLessThanOrEqual(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varLessThan.")) {
						variableName = name.substring("varLessThan.".length());
						if (!Strings.isEmpty(variableName)) {
							query.variableValueLessThan(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varLike.")) {
						variableName = name.substring("varLike.".length());
						if (!Strings.isEmpty(variableName) && value != null) {
							query.variableValueLike(variableName, value.toString());
						}
					} else if (name.startsWith("varEquals.")) {
						variableName = name.substring("varEquals.".length());
						if (!Strings.isEmpty(variableName)) {
							query.variableValueEquals(variableName, coerceValue(value));
						}
					} else if (name.startsWith("var.")) {
						variableName = name.substring("var.".length());
						if (!Strings.isEmpty(variableName)) {
							query.variableValueEquals(variableName, coerceValue(value));
						}
					}
				}
			}
		}

		/**
		 * Producer for the advanced process instance modification API
		 * ({@code RuntimeService.createProcessInstanceModification(...)}). It allows starting and/or
		 * cancelling activities of a running instance in a single atomic operation, which is the
		 * standard remedy for correcting "stuck" or mis-routed instances.
		 *
		 * Supported parameters (processInstanceId is required; at least one instruction must be given):
		 * - processInstanceId: Process instance to modify (required)
		 * - startBeforeActivity: Activity id(s) to start before (comma-separated or list)
		 * - startAfterActivity: Activity id(s) to start after (comma-separated or list)
		 * - startTransition: Sequence-flow / transition id(s) to start (comma-separated or list)
		 * - cancelActivityId: Activity id(s) whose every running instance is cancelled (cancelAllForActivity)
		 * - cancelActivityInstanceId: Concrete activity-instance id(s) to cancel
		 * - cancelTransitionInstanceId: Concrete transition-instance id(s) to cancel
		 * - ancestorActivityInstanceId: Optional ancestor activity-instance id scoping the start instructions
		 * - skipCustomListeners: When true, execution and task listeners are not invoked (default false)
		 * - skipIoMappings: When true, input/output mappings are not applied (default false)
		 * - includes / excludes: Optional filters selecting exchange headers to set as process variables
		 *     (same scheme as the other producers). Variables are attached to the started activities, so
		 *     they only take effect when at least one start instruction is present; use the setVariables
		 *     operation to update variables without a modification.
		 *
		 * Sets the "processInstanceId" header for downstream processing.
		 */
		private class ModifyProcessProducer extends BpmProducer {
			private ModifyProcessProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				String processInstanceId = (String) pc.getParameter("processInstanceId");
				if (Strings.isEmpty(processInstanceId)) {
					throw new IllegalStateException("The process instance id must not be empty");
				}

				List<String> startBeforeActivityIds = pc.asList(pc.getParameter("startBeforeActivity"));
				List<String> startAfterActivityIds = pc.asList(pc.getParameter("startAfterActivity"));
				List<String> startTransitionIds = pc.asList(pc.getParameter("startTransition"));
				List<String> cancelActivityIds = pc.asList(pc.getParameter("cancelActivityId"));
				List<String> cancelActivityInstanceIds = pc.asList(pc.getParameter("cancelActivityInstanceId"));
				List<String> cancelTransitionInstanceIds = pc.asList(pc.getParameter("cancelTransitionInstanceId"));

				if (startBeforeActivityIds.isEmpty() && startAfterActivityIds.isEmpty() && startTransitionIds.isEmpty()
						&& cancelActivityIds.isEmpty() && cancelActivityInstanceIds.isEmpty() && cancelTransitionInstanceIds.isEmpty()) {
					throw new IllegalStateException("At least one modification instruction (startBeforeActivity, startAfterActivity, startTransition, cancelActivityId, cancelActivityInstanceId, or cancelTransitionInstanceId) must be provided");
				}

				Map<String, Object> variables = pc.getVariables();
				String ancestorActivityInstanceId = (String) pc.getParameter("ancestorActivityInstanceId");

				ProcessInstanceModificationBuilder modification = runtime.createProcessInstanceModification(processInstanceId);

				// Start instructions first; declared variables are attached to the started activities.
				for (String activityId : startBeforeActivityIds) {
					ProcessInstanceModificationInstantiationBuilder builder = Strings.isEmpty(ancestorActivityInstanceId)
							? modification.startBeforeActivity(activityId)
							: modification.startBeforeActivity(activityId, ancestorActivityInstanceId);
					if (!variables.isEmpty()) {
						builder.setVariables(variables);
					}
					modification = builder;
				}
				for (String activityId : startAfterActivityIds) {
					ProcessInstanceModificationInstantiationBuilder builder = Strings.isEmpty(ancestorActivityInstanceId)
							? modification.startAfterActivity(activityId)
							: modification.startAfterActivity(activityId, ancestorActivityInstanceId);
					if (!variables.isEmpty()) {
						builder.setVariables(variables);
					}
					modification = builder;
				}
				for (String transitionId : startTransitionIds) {
					ProcessInstanceModificationInstantiationBuilder builder = Strings.isEmpty(ancestorActivityInstanceId)
							? modification.startTransition(transitionId)
							: modification.startTransition(transitionId, ancestorActivityInstanceId);
					if (!variables.isEmpty()) {
						builder.setVariables(variables);
					}
					modification = builder;
				}

				// Cancel instructions.
				for (String activityId : cancelActivityIds) {
					modification = modification.cancelAllForActivity(activityId);
				}
				for (String activityInstanceId : cancelActivityInstanceIds) {
					modification = modification.cancelActivityInstance(activityInstanceId);
				}
				for (String transitionInstanceId : cancelTransitionInstanceIds) {
					modification = modification.cancelTransitionInstance(transitionInstanceId);
				}

				boolean skipCustomListeners = pc.getParameterAsBoolean("skipCustomListeners", false);
				boolean skipIoMappings = pc.getParameterAsBoolean("skipIoMappings", false);
				if (skipCustomListeners || skipIoMappings) {
					modification.execute(skipCustomListeners, skipIoMappings);
				} else {
					modification.execute();
				}

				pc.setHeader("processInstanceId", processInstanceId);
			}
		}

		/**
		 * Producer for evaluating a DMN decision and exposing the result to the route (Group B, B1).
		 * This lets content-based routing be delegated to a business-maintained DMN decision table instead
		 * of being hard-coded as {@code <choice><when>} in the route.
		 *
		 * Supported parameters:
		 * - decisionDefinitionKey: Key of the decision to evaluate (latest version unless a version is given)
		 * - decisionDefinitionId: Id of a specific decision definition (takes precedence over the key)
		 * - version: Optional version selector used together with decisionDefinitionKey
		 * - decisionDefinitionTenantId: Optional tenant id used together with decisionDefinitionKey
		 * - withoutTenantId: When true (and no tenant id given), restrict to the no-tenant definition
		 * - includes / excludes: Filters selecting exchange headers to pass as decision input variables
		 *     (same scheme as the other producers)
		 *
		 * Results are returned via output bindings (@body / @header.x / @property.x), declared as endpoint
		 * parameters or exchange headers (URL parameter takes precedence over header). The available sources
		 * are:
		 * - resultList: every matching rule's outputs (List&lt;Map&lt;String, Object&gt;&gt;)
		 * - singleResult: the single matching rule's outputs (Map), available when exactly one rule matched
		 * - singleEntry: the single output value, available when exactly one rule with one output matched
		 * - count: the number of matching rules (Integer)
		 *
		 * Output is produced solely through the declared bindings: callers must set the bindings they
		 * need before invoking this producer. To branch on a single matched rule's outputs in a following
		 * {@code <choice>}, bind {@code singleResult} (or the individual outputs) explicitly.
		 */
		private class EvaluateDecisionProducer extends BpmProducer {
			private EvaluateDecisionProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				DecisionService decisionService = processEngine.getDecisionService();

				String decisionDefinitionId = (String) pc.getParameter("decisionDefinitionId");
				String decisionDefinitionKey = (String) pc.getParameter("decisionDefinitionKey");

				DecisionsEvaluationBuilder builder;
				if (!Strings.isEmpty(decisionDefinitionId)) {
					builder = decisionService.evaluateDecisionById(decisionDefinitionId);
				} else if (!Strings.isEmpty(decisionDefinitionKey)) {
					builder = decisionService.evaluateDecisionByKey(decisionDefinitionKey);
					Integer version = pc.getParameterAsInteger("version");
					if (version != null) {
						builder.version(version);
					}
					String tenantId = (String) pc.getParameter("decisionDefinitionTenantId");
					if (!Strings.isEmpty(tenantId)) {
						builder.decisionDefinitionTenantId(tenantId);
					} else if (pc.getParameterAsBoolean("withoutTenantId", false)) {
						builder.decisionDefinitionWithoutTenantId();
					}
				} else {
					throw new IllegalStateException("Either decisionDefinitionKey or decisionDefinitionId must be provided to evaluate a decision");
				}

				builder.variables(pc.getVariables());

				DmnDecisionResult result = builder.evaluate();
				List<Map<String, Object>> rows = result.getResultList();

				// Derive the result sources directly from the row list to avoid the multiplicity exceptions
				// thrown by getSingleResult()/getSingleEntry() when they do not apply.
				Map<String, Object> sources = new HashMap<>();
				sources.put("count", rows.size());
				sources.put("resultList", rows);
				if (rows.size() == 1) {
					Map<String, Object> only = rows.get(0);
					sources.put("singleResult", only);
					if (only.size() == 1) {
						sources.put("singleEntry", only.values().iterator().next());
					}
				}

				pc.applyResultBindings(sources);
			}
		}

		/**
		 * Producer for completing a user task and returning variables to the process (Group D, D1).
		 *
		 * Supported parameters:
		 * - taskId: Id of the user task to complete (required)
		 * - includes / excludes: Filters selecting exchange headers to set as task/process variables
		 *     (same scheme as the other producers)
		 */
		private class CompleteTaskProducer extends BpmProducer {
			private CompleteTaskProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				TaskService taskService = taskService();
				String taskId = requireTaskId(pc);

				Map<String, Object> variables = pc.getVariables();
				if (variables.isEmpty()) {
					taskService.complete(taskId);
				} else {
					taskService.complete(taskId, variables);
				}
			}
		}

		/**
		 * Producer for claiming or unclaiming a user task (Group D, D2).
		 * Claiming assigns the task to a user; unclaiming clears the current assignee.
		 *
		 * Supported parameters:
		 * - taskId: Id of the user task (required)
		 * - userId: Id of the user claiming the task (required when claiming; ignored when unclaiming)
		 */
		private class ClaimTaskProducer extends BpmProducer {
			private final boolean fClaim;

			private ClaimTaskProducer(boolean claim) {
				super(BpmEndpoint.this);
				fClaim = claim;
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				TaskService taskService = taskService();
				String taskId = requireTaskId(pc);

				if (fClaim) {
					String userId = (String) pc.getParameter("userId");
					if (Strings.isEmpty(userId)) {
						throw new IllegalStateException("The user id must not be empty when claiming a task");
					}
					taskService.claim(taskId, userId);
				} else {
					// Unclaiming is modelled as clearing the assignee.
					taskService.setAssignee(taskId, null);
				}
			}
		}

		/**
		 * Producer for setting (or clearing) the assignee of a user task (Group D, D4).
		 *
		 * Supported parameters:
		 * - taskId: Id of the user task (required)
		 * - userId: Id of the user to assign; when omitted or blank, the assignee is cleared
		 */
		private class SetAssigneeProducer extends BpmProducer {
			private SetAssigneeProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				TaskService taskService = taskService();
				String taskId = requireTaskId(pc);

				String userId = (String) pc.getParameter("userId");
				taskService.setAssignee(taskId, Strings.isEmpty(userId) ? null : userId);
			}
		}

		/**
		 * Producer for delegating a user task to another user (Group D, D4).
		 * The task is delegated and its delegation state is set to PENDING, so the original owner can later
		 * resolve it back via the resolveTask operation.
		 *
		 * Supported parameters:
		 * - taskId: Id of the user task (required)
		 * - userId: Id of the user the task is delegated to (required)
		 */
		private class DelegateTaskProducer extends BpmProducer {
			private DelegateTaskProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				TaskService taskService = taskService();
				String taskId = requireTaskId(pc);

				String userId = (String) pc.getParameter("userId");
				if (Strings.isEmpty(userId)) {
					throw new IllegalStateException("The user id must not be empty when delegating a task");
				}
				taskService.delegateTask(taskId, userId);
			}
		}

		/**
		 * Producer for resolving a delegated user task, returning it to its owner (Group D, D4).
		 *
		 * Supported parameters:
		 * - taskId: Id of the user task (required)
		 * - includes / excludes: Optional filters selecting exchange headers to set as task/process variables
		 */
		private class ResolveTaskProducer extends BpmProducer {
			private ResolveTaskProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				TaskService taskService = taskService();
				String taskId = requireTaskId(pc);

				Map<String, Object> variables = pc.getVariables();
				if (variables.isEmpty()) {
					taskService.resolveTask(taskId);
				} else {
					taskService.resolveTask(taskId, variables);
				}
			}
		}

		/**
		 * Producer for adding a comment to a user task (Group D, D5).
		 *
		 * Supported parameters:
		 * - taskId: Id of the user task the comment is attached to (required)
		 * - processInstanceId: Optional process instance id to also associate with the comment
		 * - message: The comment text (required; falls back to the message body when not given)
		 *
		 * Sets the "commentId" header for downstream processing.
		 */
		private class AddTaskCommentProducer extends BpmProducer {
			private AddTaskCommentProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				TaskService taskService = taskService();
				String taskId = requireTaskId(pc);

				String message = (String) pc.getParameter("message");
				if (Strings.isEmpty(message)) {
					Object body = pc.getBody();
					message = (body == null) ? null : body.toString();
				}
				if (Strings.isEmpty(message)) {
					throw new IllegalStateException("The comment message must not be empty");
				}

				String processInstanceId = (String) pc.getParameter("processInstanceId");
				org.camunda.bpm.engine.task.Comment comment = taskService.createComment(taskId, processInstanceId, message);
				if (comment != null) {
					pc.setHeader("commentId", comment.getId());
				}
			}
		}

		/**
		 * Producer for attaching a file or an external URL to a user task (Group D, D5).
		 *
		 * Exactly one content source must be provided, either binary content or a URL reference:
		 *
		 * Binary content (stored in the engine, via createAttachment(..., InputStream)):
		 *   content=@body                    — the message body as the attachment content
		 *   content=@header.attachmentFile   — a header value as the attachment content
		 *   content=@property.attachmentFile — an exchange property as the attachment content
		 * The referenced value is converted to an InputStream (byte[], String, File or InputStream).
		 *
		 * URL reference (stored as a link, via createAttachment(..., String url)):
		 *   url=https://www.example.org/img/logo.jpg — a literal external URL
		 *   url=jcr:///content/item.jpg              — a literal JCR URL (resolved by the client)
		 *   url=@header.attachmentUrl                — a header value used as the URL
		 *   url=@property.attachmentUrl              — an exchange property used as the URL
		 * A url value that does not use the @header./@property. indirection is taken as a literal URL.
		 *
		 * Other supported parameters:
		 * - taskId: Id of the user task the attachment is added to (required)
		 * - processInstanceId: Optional process instance id to also associate with the attachment
		 * - attachmentName / name: Display name of the attachment
		 * - attachmentDescription / description: Optional description
		 * - attachmentType / type: Attachment type (for example the MIME type)
		 *
		 * Sets the "attachmentId" header for downstream processing.
		 */
		private class AddAttachmentProducer extends BpmProducer {
			private AddAttachmentProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				TaskService taskService = taskService();
				String taskId = requireTaskId(pc);
				String processInstanceId = (String) pc.getParameter("processInstanceId");

				String attachmentName = firstNonEmpty((String) pc.getParameter("attachmentName"), (String) pc.getParameter("name"));
				String attachmentDescription = firstNonEmpty((String) pc.getParameter("attachmentDescription"), (String) pc.getParameter("description"));
				String attachmentType = firstNonEmpty((String) pc.getParameter("attachmentType"), (String) pc.getParameter("type"));

				String content = (String) pc.getParameter("content");
				String url = (String) pc.getParameter("url");
				if (!Strings.isEmpty(content) && !Strings.isEmpty(url)) {
					throw new IllegalStateException("Only one of 'content' or 'url' may be provided for an attachment");
				}

				org.camunda.bpm.engine.task.Attachment attachment;
				if (!Strings.isEmpty(content)) {
					// Binary content: resolve the source reference and convert it to an InputStream.
					Object source = pc.resolveSource(content);
					if (source == null) {
						throw new IllegalStateException("The attachment content source '" + content + "' resolved to no value");
					}
					InputStream in = pc.toInputStream(source);
					if (in == null) {
						throw new IllegalStateException("The attachment content source '" + content + "' could not be converted to an InputStream");
					}
					attachment = taskService.createAttachment(attachmentType, taskId, processInstanceId, attachmentName, attachmentDescription, in);
				} else if (!Strings.isEmpty(url)) {
					// URL reference: use the indirected value when given, otherwise the literal URL.
					String urlValue;
					if (pc.isSourceReference(url)) {
						Object source = pc.resolveSource(url);
						urlValue = (source == null) ? null : source.toString();
					} else {
						urlValue = url;
					}
					if (Strings.isEmpty(urlValue)) {
						throw new IllegalStateException("The attachment url source '" + url + "' resolved to no value");
					}
					attachment = taskService.createAttachment(attachmentType, taskId, processInstanceId, attachmentName, attachmentDescription, urlValue);
				} else {
					throw new IllegalStateException("Either 'content' or 'url' must be provided to add an attachment");
				}

				if (attachment != null) {
					pc.setHeader("attachmentId", attachment.getId());
				}
			}

			private String firstNonEmpty(String first, String second) {
				return Strings.isEmpty(first) ? second : first;
			}
		}

		/**
		 * Producer for querying user tasks, for example to drive reminder or escalation routes (Group D, D3).
		 *
		 * Supported filter parameters (all optional; combined with AND semantics):
		 * - taskId: Match a single task id
		 * - taskName: Match the task name
		 * - assignee: Match the assignee
		 * - unassigned: When true, restrict to tasks with no assignee
		 * - owner: Match the task owner
		 * - candidateUser: Match tasks the user is a candidate for
		 * - candidateGroup: Match tasks the group is a candidate for
		 * - processInstanceId: Match the process instance id
		 * - processDefinitionKey: Match the process definition key
		 * - taskDefinitionKey: Match the task definition key (BPMN element id)
		 * - businessKey: Match the process instance business key
		 * - priority: Match the task priority (integer)
		 * - dueBefore / dueAfter: Match tasks due before/after the given Date (header value)
		 * - active: When true, restrict to active (non-suspended) tasks
		 *
		 * Variable-value filters (combined with AND semantics) may be declared as endpoint parameters or
		 * exchange headers, keyed by the variable name (process-instance variables), using the same
		 * "var.&lt;name&gt;" / operator-specific variants as queryProcessInstances.
		 *
		 * Results are returned via output bindings (@body / @header.x / @property.x). The available sources are:
		 * - count: number of matching tasks (Integer)
		 * - ids: list of task ids (List)
		 * - tasks: list of detail maps, one per task (List)
		 * Output is produced solely through the declared bindings: callers must set the bindings they need
		 * before invoking this producer. The list of matches is materialised only when a list source
		 * (ids/tasks) is bound; when only the count is bound the matches are counted without being listed.
		 */
		private class QueryTasksProducer extends BpmProducer {
			private QueryTasksProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				TaskService taskService = taskService();
				TaskQuery query = taskService.createTaskQuery();

				String taskId = (String) pc.getParameter("taskId");
				if (!Strings.isEmpty(taskId)) {
					query.taskId(taskId);
				}
				String taskName = (String) pc.getParameter("taskName");
				if (!Strings.isEmpty(taskName)) {
					query.taskName(taskName);
				}
				String assignee = (String) pc.getParameter("assignee");
				if (!Strings.isEmpty(assignee)) {
					query.taskAssignee(assignee);
				}
				if (pc.getParameterAsBoolean("unassigned", false)) {
					query.taskUnassigned();
				}
				String owner = (String) pc.getParameter("owner");
				if (!Strings.isEmpty(owner)) {
					query.taskOwner(owner);
				}
				String candidateUser = (String) pc.getParameter("candidateUser");
				if (!Strings.isEmpty(candidateUser)) {
					query.taskCandidateUser(candidateUser);
				}
				String candidateGroup = (String) pc.getParameter("candidateGroup");
				if (!Strings.isEmpty(candidateGroup)) {
					query.taskCandidateGroup(candidateGroup);
				}
				String processInstanceId = (String) pc.getParameter("processInstanceId");
				if (!Strings.isEmpty(processInstanceId)) {
					query.processInstanceId(processInstanceId);
				}
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				if (!Strings.isEmpty(processDefinitionKey)) {
					query.processDefinitionKey(processDefinitionKey);
				}
				String taskDefinitionKey = (String) pc.getParameter("taskDefinitionKey");
				if (!Strings.isEmpty(taskDefinitionKey)) {
					query.taskDefinitionKey(taskDefinitionKey);
				}
				String businessKey = (String) pc.getParameter("businessKey");
				if (!Strings.isEmpty(businessKey)) {
					query.processInstanceBusinessKey(businessKey);
				}
				Integer priority = pc.getParameterAsInteger("priority");
				if (priority != null) {
					query.taskPriority(priority);
				}
				Object dueBefore = pc.getParameter("dueBefore");
				if (dueBefore instanceof java.util.Date) {
					query.dueBefore((java.util.Date) dueBefore);
				}
				Object dueAfter = pc.getParameter("dueAfter");
				if (dueAfter instanceof java.util.Date) {
					query.dueAfter((java.util.Date) dueAfter);
				}
				if (pc.getParameterAsBoolean("active", false)) {
					query.active();
				}
				applyVariableValueFilters(pc, query);

				Set<String> requested = pc.getBoundSourceNames();
				boolean needList = requested.contains("ids") || requested.contains("tasks");

				Map<String, Object> sources = new HashMap<>();
				if (needList) {
					List<Task> found = query.list();
					List<Object> ids = new ArrayList<>();
					List<Object> tasks = new ArrayList<>();
					for (Task task : found) {
						ids.add(task.getId());

						Map<String, Object> detail = new LinkedHashMap<>();
						detail.put("id", task.getId());
						detail.put("name", task.getName());
						detail.put("assignee", task.getAssignee());
						detail.put("owner", task.getOwner());
						detail.put("priority", task.getPriority());
						detail.put("taskDefinitionKey", task.getTaskDefinitionKey());
						detail.put("processInstanceId", task.getProcessInstanceId());
						detail.put("executionId", task.getExecutionId());
						detail.put("processDefinitionId", task.getProcessDefinitionId());
						detail.put("createTime", task.getCreateTime());
						detail.put("dueDate", task.getDueDate());
						detail.put("description", task.getDescription());
						tasks.add(detail);
					}

					sources.put("count", found.size());
					sources.put("ids", ids);
					sources.put("tasks", tasks);
				} else if (requested.contains("count")) {
					sources.put("count", (int) query.count());
				}
				pc.applyResultBindings(sources);
			}

			/**
			 * Apply variable-value restrictions declared as "var.&lt;name&gt;" (and operator-specific
			 * variants) to the query, using the same scheme as queryProcessInstances. The values are matched
			 * against process-instance variables.
			 */
			private void applyVariableValueFilters(ProcessContext pc, TaskQuery query) {
				for (String name : pc.getParameterNames()) {
					if (Strings.isEmpty(name)) {
						continue;
					}

					Object value = pc.getParameter(name);
					if (name.startsWith("varNotEquals.")) {
						String variableName = name.substring("varNotEquals.".length());
						if (!Strings.isEmpty(variableName)) {
							query.processVariableValueNotEquals(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varGreaterThanOrEqual.")) {
						String variableName = name.substring("varGreaterThanOrEqual.".length());
						if (!Strings.isEmpty(variableName)) {
							query.processVariableValueGreaterThanOrEquals(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varGreaterThan.")) {
						String variableName = name.substring("varGreaterThan.".length());
						if (!Strings.isEmpty(variableName)) {
							query.processVariableValueGreaterThan(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varLessThanOrEqual.")) {
						String variableName = name.substring("varLessThanOrEqual.".length());
						if (!Strings.isEmpty(variableName)) {
							query.processVariableValueLessThanOrEquals(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varLessThan.")) {
						String variableName = name.substring("varLessThan.".length());
						if (!Strings.isEmpty(variableName)) {
							query.processVariableValueLessThan(variableName, coerceValue(value));
						}
					} else if (name.startsWith("varLike.")) {
						String variableName = name.substring("varLike.".length());
						if (!Strings.isEmpty(variableName) && value != null) {
							query.processVariableValueLike(variableName, value.toString());
						}
					} else if (name.startsWith("varEquals.")) {
						String variableName = name.substring("varEquals.".length());
						if (!Strings.isEmpty(variableName)) {
							query.processVariableValueEquals(variableName, coerceValue(value));
						}
					} else if (name.startsWith("var.")) {
						String variableName = name.substring("var.".length());
						if (!Strings.isEmpty(variableName)) {
							query.processVariableValueEquals(variableName, coerceValue(value));
						}
					}
				}
			}
		}

		/**
		 * Producer for querying incidents, for example to drive monitoring or notification routes (Group F, F3).
		 * Incidents represent failures Camunda could not handle automatically (failed jobs/external tasks,
		 * etc.), so polling them on a timer is a natural way to raise alerts.
		 *
		 * Supported filter parameters (all optional; combined with AND semantics):
		 * - incidentId: Match a single incident id
		 * - incidentType: Match the incident type (for example "failedJob", "failedExternalTask")
		 * - incidentMessage: Match the incident message exactly
		 * - incidentMessageLike: Match the incident message with a SQL LIKE pattern (use % as wildcard)
		 * - processInstanceId: Match the process instance id
		 * - processDefinitionId: Match the process definition id
		 * - processDefinitionKey: Match the process definition key
		 * - executionId: Match the execution id
		 * - activityId: Match the activity id where the incident occurred
		 * - failedActivityId: Match the activity id where the failure originated
		 * - causeIncidentId / rootCauseIncidentId: Match the (root) cause incident id
		 * - configuration: Match the incident configuration (for example the failed job id)
		 * - timestampBefore / timestampAfter: Match incidents raised before/after the given Date (header value)
		 *
		 * Results are returned via output bindings (@body / @header.x / @property.x). The available sources are:
		 * - count: number of matching incidents (Integer)
		 * - ids: list of incident ids (List)
		 * - incidents: list of detail maps, one per incident (List)
		 * Output is produced solely through the declared bindings: callers must set the bindings they need
		 * before invoking this producer. The list of matches is materialised only when a list source
		 * (ids/incidents) is bound; when only the count is bound the matches are counted without being listed.
		 */
		private class QueryIncidentsProducer extends BpmProducer {
			private QueryIncidentsProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RuntimeService runtime = processEngine.getRuntimeService();

				IncidentQuery query = runtime.createIncidentQuery();
				String incidentId = (String) pc.getParameter("incidentId");
				if (!Strings.isEmpty(incidentId)) {
					query.incidentId(incidentId);
				}
				String incidentType = (String) pc.getParameter("incidentType");
				if (!Strings.isEmpty(incidentType)) {
					query.incidentType(incidentType);
				}
				String incidentMessage = (String) pc.getParameter("incidentMessage");
				if (!Strings.isEmpty(incidentMessage)) {
					query.incidentMessage(incidentMessage);
				}
				String incidentMessageLike = (String) pc.getParameter("incidentMessageLike");
				if (!Strings.isEmpty(incidentMessageLike)) {
					query.incidentMessageLike(incidentMessageLike);
				}
				String processInstanceId = (String) pc.getParameter("processInstanceId");
				if (!Strings.isEmpty(processInstanceId)) {
					query.processInstanceId(processInstanceId);
				}
				String processDefinitionId = (String) pc.getParameter("processDefinitionId");
				if (!Strings.isEmpty(processDefinitionId)) {
					query.processDefinitionId(processDefinitionId);
				}
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				if (!Strings.isEmpty(processDefinitionKey)) {
					query.processDefinitionKeyIn(processDefinitionKey);
				}
				String executionId = (String) pc.getParameter("executionId");
				if (!Strings.isEmpty(executionId)) {
					query.executionId(executionId);
				}
				String activityId = (String) pc.getParameter("activityId");
				if (!Strings.isEmpty(activityId)) {
					query.activityId(activityId);
				}
				String failedActivityId = (String) pc.getParameter("failedActivityId");
				if (!Strings.isEmpty(failedActivityId)) {
					query.failedActivityId(failedActivityId);
				}
				String causeIncidentId = (String) pc.getParameter("causeIncidentId");
				if (!Strings.isEmpty(causeIncidentId)) {
					query.causeIncidentId(causeIncidentId);
				}
				String rootCauseIncidentId = (String) pc.getParameter("rootCauseIncidentId");
				if (!Strings.isEmpty(rootCauseIncidentId)) {
					query.rootCauseIncidentId(rootCauseIncidentId);
				}
				String configuration = (String) pc.getParameter("configuration");
				if (!Strings.isEmpty(configuration)) {
					query.configuration(configuration);
				}
				Object timestampBefore = pc.getParameter("timestampBefore");
				if (timestampBefore instanceof java.util.Date) {
					query.incidentTimestampBefore((java.util.Date) timestampBefore);
				}
				Object timestampAfter = pc.getParameter("timestampAfter");
				if (timestampAfter instanceof java.util.Date) {
					query.incidentTimestampAfter((java.util.Date) timestampAfter);
				}

				Set<String> requested = pc.getBoundSourceNames();
				boolean needList = requested.contains("ids") || requested.contains("incidents");

				Map<String, Object> sources = new HashMap<>();
				if (needList) {
					List<Incident> found = query.list();
					List<Object> ids = new ArrayList<>();
					List<Object> incidents = new ArrayList<>();
					for (Incident incident : found) {
						ids.add(incident.getId());

						Map<String, Object> detail = new LinkedHashMap<>();
						detail.put("id", incident.getId());
						detail.put("incidentTimestamp", incident.getIncidentTimestamp());
						detail.put("incidentType", incident.getIncidentType());
						detail.put("incidentMessage", incident.getIncidentMessage());
						detail.put("processInstanceId", incident.getProcessInstanceId());
						detail.put("processDefinitionId", incident.getProcessDefinitionId());
						detail.put("executionId", incident.getExecutionId());
						detail.put("activityId", incident.getActivityId());
						detail.put("failedActivityId", incident.getFailedActivityId());
						detail.put("causeIncidentId", incident.getCauseIncidentId());
						detail.put("rootCauseIncidentId", incident.getRootCauseIncidentId());
						detail.put("configuration", incident.getConfiguration());
						detail.put("jobDefinitionId", incident.getJobDefinitionId());
						detail.put("annotation", incident.getAnnotation());
						detail.put("tenantId", incident.getTenantId());
						incidents.add(detail);
					}

					sources.put("count", found.size());
					sources.put("ids", ids);
					sources.put("incidents", incidents);
				} else if (requested.contains("count")) {
					sources.put("count", (int) query.count());
				}
				pc.applyResultBindings(sources);
			}
		}

		/**
		 * Producer for querying jobs, for example to monitor a dead-letter queue of exhausted jobs (Group F, F4).
		 * Jobs back timers, async continuations and message events; a job with no retries left and an
		 * exception is effectively "dead" and waiting for intervention.
		 *
		 * Supported filter parameters (all optional; combined with AND semantics):
		 * - jobId: Match a single job id
		 * - jobDefinitionId: Match the job definition id
		 * - processInstanceId: Match the process instance id
		 * - processDefinitionId: Match the process definition id
		 * - processDefinitionKey: Match the process definition key
		 * - executionId: Match the execution id
		 * - activityId: Match the activity id
		 * - failedActivityId: Match the activity id where the failure originated
		 * - withException: When true, restrict to jobs that have an exception
		 * - exceptionMessage: Match the exception message exactly
		 * - withRetriesLeft: When true, restrict to jobs that still have retries left
		 * - noRetriesLeft: When true, restrict to jobs with no retries left (the dead-letter set)
		 * - executable: When true, restrict to jobs that are due and ready to execute
		 * - timers / messages: When true, restrict to timer / message jobs respectively
		 * - active / suspended: When true, restrict to active / suspended jobs
		 * - duedateLowerThan / duedateHigherThan: Match jobs due before/after the given Date (header value)
		 *
		 * Results are returned via output bindings (@body / @header.x / @property.x). The available sources are:
		 * - count: number of matching jobs (Integer)
		 * - ids: list of job ids (List)
		 * - jobs: list of detail maps, one per job (List)
		 * Output is produced solely through the declared bindings: callers must set the bindings they need
		 * before invoking this producer. The list of matches is materialised only when a list source
		 * (ids/jobs) is bound; when only the count is bound the matches are counted without being listed.
		 */
		private class QueryJobsProducer extends BpmProducer {
			private QueryJobsProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				ManagementService management = processEngine.getManagementService();

				JobQuery query = management.createJobQuery();
				String jobId = (String) pc.getParameter("jobId");
				if (!Strings.isEmpty(jobId)) {
					query.jobId(jobId);
				}
				String jobDefinitionId = (String) pc.getParameter("jobDefinitionId");
				if (!Strings.isEmpty(jobDefinitionId)) {
					query.jobDefinitionId(jobDefinitionId);
				}
				String processInstanceId = (String) pc.getParameter("processInstanceId");
				if (!Strings.isEmpty(processInstanceId)) {
					query.processInstanceId(processInstanceId);
				}
				String processDefinitionId = (String) pc.getParameter("processDefinitionId");
				if (!Strings.isEmpty(processDefinitionId)) {
					query.processDefinitionId(processDefinitionId);
				}
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				if (!Strings.isEmpty(processDefinitionKey)) {
					query.processDefinitionKey(processDefinitionKey);
				}
				String executionId = (String) pc.getParameter("executionId");
				if (!Strings.isEmpty(executionId)) {
					query.executionId(executionId);
				}
				String activityId = (String) pc.getParameter("activityId");
				if (!Strings.isEmpty(activityId)) {
					query.activityId(activityId);
				}
				String failedActivityId = (String) pc.getParameter("failedActivityId");
				if (!Strings.isEmpty(failedActivityId)) {
					query.failedActivityId(failedActivityId);
				}
				if (pc.getParameterAsBoolean("withException", false)) {
					query.withException();
				}
				String exceptionMessage = (String) pc.getParameter("exceptionMessage");
				if (!Strings.isEmpty(exceptionMessage)) {
					query.exceptionMessage(exceptionMessage);
				}
				if (pc.getParameterAsBoolean("withRetriesLeft", false)) {
					query.withRetriesLeft();
				}
				if (pc.getParameterAsBoolean("noRetriesLeft", false)) {
					query.noRetriesLeft();
				}
				if (pc.getParameterAsBoolean("executable", false)) {
					query.executable();
				}
				if (pc.getParameterAsBoolean("timers", false)) {
					query.timers();
				}
				if (pc.getParameterAsBoolean("messages", false)) {
					query.messages();
				}
				if (pc.getParameterAsBoolean("active", false)) {
					query.active();
				}
				if (pc.getParameterAsBoolean("suspended", false)) {
					query.suspended();
				}
				Object duedateLowerThan = pc.getParameter("duedateLowerThan");
				if (duedateLowerThan instanceof java.util.Date) {
					query.duedateLowerThan((java.util.Date) duedateLowerThan);
				}
				Object duedateHigherThan = pc.getParameter("duedateHigherThan");
				if (duedateHigherThan instanceof java.util.Date) {
					query.duedateHigherThan((java.util.Date) duedateHigherThan);
				}

				Set<String> requested = pc.getBoundSourceNames();
				boolean needList = requested.contains("ids") || requested.contains("jobs");

				Map<String, Object> sources = new HashMap<>();
				if (needList) {
					List<Job> found = query.list();
					List<Object> ids = new ArrayList<>();
					List<Object> jobs = new ArrayList<>();
					for (Job job : found) {
						ids.add(job.getId());

						Map<String, Object> detail = new LinkedHashMap<>();
						detail.put("id", job.getId());
						detail.put("jobDefinitionId", job.getJobDefinitionId());
						detail.put("processInstanceId", job.getProcessInstanceId());
						detail.put("processDefinitionId", job.getProcessDefinitionId());
						detail.put("processDefinitionKey", job.getProcessDefinitionKey());
						detail.put("executionId", job.getExecutionId());
						detail.put("retries", job.getRetries());
						detail.put("exceptionMessage", job.getExceptionMessage());
						detail.put("failedActivityId", job.getFailedActivityId());
						detail.put("duedate", job.getDuedate());
						detail.put("createTime", job.getCreateTime());
						detail.put("priority", job.getPriority());
						detail.put("suspended", job.isSuspended());
						detail.put("deploymentId", job.getDeploymentId());
						detail.put("tenantId", job.getTenantId());
						jobs.add(detail);
					}

					sources.put("count", found.size());
					sources.put("ids", ids);
					sources.put("jobs", jobs);
				} else if (requested.contains("count")) {
					sources.put("count", (int) query.count());
				}
				pc.applyResultBindings(sources);
			}
		}

		/**
		 * Producer for resetting the retry count of one or more jobs, so the engine attempts them again
		 * (Group F, F4). This is the natural remedy after diagnosing an incident detected via queryIncidents
		 * or a dead job found via queryJobs.
		 *
		 * Supported parameters:
		 * - jobId: One or more job ids (comma-separated, list, or collection); required
		 * - retries: Number of retries to set (default 1)
		 *
		 * Sets the "retriedJobIds" header (List) for downstream processing.
		 */
		private class RetryJobProducer extends BpmProducer {
			private RetryJobProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				ManagementService management = processEngine.getManagementService();

				List<String> jobIds = pc.asList(pc.getParameter("jobId"));
				if (jobIds.isEmpty()) {
					throw new IllegalStateException("At least one job id must be provided via the 'jobId' parameter");
				}

				Integer retriesObj = pc.getParameterAsInteger("retries");
				int retries = (retriesObj == null) ? 1 : retriesObj.intValue();

				if (jobIds.size() == 1) {
					management.setJobRetries(jobIds.get(0), retries);
				} else {
					management.setJobRetries(jobIds, retries);
				}

				pc.setHeader("retriedJobIds", jobIds);
			}
		}

		/**
		 * Producer for querying historic (completed and running) process instances, for reporting, auditing
		 * or export routes (Group F, F1). Backed by HistoryService.createHistoricProcessInstanceQuery.
		 *
		 * Supported filter parameters (all optional; combined with AND semantics):
		 * - processInstanceId: Match a single process instance id
		 * - processDefinitionId: Match the process definition id
		 * - processDefinitionKey: Match the process definition key
		 * - businessKey: Match the process instance business key
		 * - finished: When true, restrict to finished instances
		 * - unfinished: When true, restrict to still-running instances
		 * - completed: When true, restrict to normally completed instances
		 * - externallyTerminated: When true, restrict to externally terminated instances
		 * - active / suspended: When true, restrict to active / suspended instances
		 * - startedBefore / startedAfter: Match instances started before/after the given Date (header value)
		 * - finishedBefore / finishedAfter: Match instances finished before/after the given Date (header value)
		 *
		 * Variable-value filters (combined with AND semantics) may be declared as endpoint parameters or
		 * exchange headers using the same "var.&lt;name&gt;" scheme as queryProcessInstances (equals only,
		 * as the historic query only exposes variableValueEquals for process-instance variables).
		 *
		 * Results are returned via output bindings (@body / @header.x / @property.x). The available sources are:
		 * - count: number of matching instances (Integer)
		 * - ids: list of process instance ids (List)
		 * - instances: list of detail maps, one per instance (List)
		 * Output is produced solely through the declared bindings: callers must set the bindings they need
		 * before invoking this producer. The list of matches is materialised only when a list source
		 * (ids/instances) is bound; when only the count is bound the matches are counted without being listed.
		 */
		private class QueryHistoricProcessInstancesProducer extends BpmProducer {
			private QueryHistoricProcessInstancesProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				HistoryService history = processEngine.getHistoryService();

				HistoricProcessInstanceQuery query = history.createHistoricProcessInstanceQuery();
				String processInstanceId = (String) pc.getParameter("processInstanceId");
				if (!Strings.isEmpty(processInstanceId)) {
					query.processInstanceId(processInstanceId);
				}
				String processDefinitionId = (String) pc.getParameter("processDefinitionId");
				if (!Strings.isEmpty(processDefinitionId)) {
					query.processDefinitionId(processDefinitionId);
				}
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				if (!Strings.isEmpty(processDefinitionKey)) {
					query.processDefinitionKey(processDefinitionKey);
				}
				String businessKey = (String) pc.getParameter("businessKey");
				if (!Strings.isEmpty(businessKey)) {
					query.processInstanceBusinessKey(businessKey);
				}
				if (pc.getParameterAsBoolean("finished", false)) {
					query.finished();
				}
				if (pc.getParameterAsBoolean("unfinished", false)) {
					query.unfinished();
				}
				if (pc.getParameterAsBoolean("completed", false)) {
					query.completed();
				}
				if (pc.getParameterAsBoolean("externallyTerminated", false)) {
					query.externallyTerminated();
				}
				if (pc.getParameterAsBoolean("active", false)) {
					query.active();
				}
				if (pc.getParameterAsBoolean("suspended", false)) {
					query.suspended();
				}
				Object startedBefore = pc.getParameter("startedBefore");
				if (startedBefore instanceof java.util.Date) {
					query.startedBefore((java.util.Date) startedBefore);
				}
				Object startedAfter = pc.getParameter("startedAfter");
				if (startedAfter instanceof java.util.Date) {
					query.startedAfter((java.util.Date) startedAfter);
				}
				Object finishedBefore = pc.getParameter("finishedBefore");
				if (finishedBefore instanceof java.util.Date) {
					query.finishedBefore((java.util.Date) finishedBefore);
				}
				Object finishedAfter = pc.getParameter("finishedAfter");
				if (finishedAfter instanceof java.util.Date) {
					query.finishedAfter((java.util.Date) finishedAfter);
				}
				applyVariableValueFilters(pc, query);

				Set<String> requested = pc.getBoundSourceNames();
				boolean needList = requested.contains("ids") || requested.contains("instances");

				Map<String, Object> sources = new HashMap<>();
				if (needList) {
					List<HistoricProcessInstance> found = query.list();
					List<Object> ids = new ArrayList<>();
					List<Object> instances = new ArrayList<>();
					for (HistoricProcessInstance instance : found) {
						ids.add(instance.getId());

						Map<String, Object> detail = new LinkedHashMap<>();
						detail.put("id", instance.getId());
						detail.put("businessKey", instance.getBusinessKey());
						detail.put("processDefinitionId", instance.getProcessDefinitionId());
						detail.put("processDefinitionKey", instance.getProcessDefinitionKey());
						detail.put("processDefinitionName", instance.getProcessDefinitionName());
						detail.put("processDefinitionVersion", instance.getProcessDefinitionVersion());
						detail.put("startTime", instance.getStartTime());
						detail.put("endTime", instance.getEndTime());
						detail.put("durationInMillis", instance.getDurationInMillis());
						detail.put("startUserId", instance.getStartUserId());
						detail.put("startActivityId", instance.getStartActivityId());
						detail.put("endActivityId", instance.getEndActivityId());
						detail.put("deleteReason", instance.getDeleteReason());
						detail.put("state", instance.getState());
						detail.put("superProcessInstanceId", instance.getSuperProcessInstanceId());
						detail.put("rootProcessInstanceId", instance.getRootProcessInstanceId());
						detail.put("tenantId", instance.getTenantId());
						instances.add(detail);
					}

					sources.put("count", found.size());
					sources.put("ids", ids);
					sources.put("instances", instances);
				} else if (requested.contains("count")) {
					sources.put("count", (int) query.count());
				}
				pc.applyResultBindings(sources);
			}

			/**
			 * Apply variable-value restrictions declared as "var.&lt;name&gt;" (or "varEquals.&lt;name&gt;") to
			 * the query. The historic process-instance query only supports equality on process-instance
			 * variables, so only the equals variants are honoured.
			 */
			private void applyVariableValueFilters(ProcessContext pc, HistoricProcessInstanceQuery query) {
				for (String name : pc.getParameterNames()) {
					if (Strings.isEmpty(name)) {
						continue;
					}

					Object value = pc.getParameter(name);
					if (name.startsWith("varEquals.")) {
						String variableName = name.substring("varEquals.".length());
						if (!Strings.isEmpty(variableName)) {
							query.variableValueEquals(variableName, coerceValue(value));
						}
					} else if (name.startsWith("var.")) {
						String variableName = name.substring("var.".length());
						if (!Strings.isEmpty(variableName)) {
							query.variableValueEquals(variableName, coerceValue(value));
						}
					}
				}
			}
		}

		/**
		 * Producer for querying historic variable instances, for example to export a process instance's
		 * variable history (Group F, F2). Backed by HistoryService.createHistoricVariableInstanceQuery.
		 *
		 * Supported filter parameters (all optional; combined with AND semantics):
		 * - processInstanceId: Match the process instance id
		 * - processDefinitionId: Match the process definition id
		 * - processDefinitionKey: Match the process definition key
		 * - variableName: Match the variable name exactly
		 * - variableNameLike: Match the variable name with a SQL LIKE pattern (use % as wildcard)
		 * - includeDeleted: When true, also include deleted variable instances
		 *
		 * Results are returned via output bindings (@body / @header.x / @property.x). The available sources are:
		 * - count: number of matching variable instances (Integer)
		 * - names: list of variable names (List)
		 * - values: map of variable name to value (Map; last value wins on duplicate names)
		 * - variables: list of detail maps, one per variable instance (List)
		 * Output is produced solely through the declared bindings: callers must set the bindings they need
		 * before invoking this producer. The list of matches is materialised only when a list source
		 * (names/values/variables) is bound; when only the count is bound the matches are counted without
		 * being listed.
		 */
		private class QueryHistoricVariablesProducer extends BpmProducer {
			private QueryHistoricVariablesProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				HistoryService history = processEngine.getHistoryService();

				HistoricVariableInstanceQuery query = history.createHistoricVariableInstanceQuery();
				String processInstanceId = (String) pc.getParameter("processInstanceId");
				if (!Strings.isEmpty(processInstanceId)) {
					query.processInstanceId(processInstanceId);
				}
				String processDefinitionId = (String) pc.getParameter("processDefinitionId");
				if (!Strings.isEmpty(processDefinitionId)) {
					query.processDefinitionId(processDefinitionId);
				}
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				if (!Strings.isEmpty(processDefinitionKey)) {
					query.processDefinitionKey(processDefinitionKey);
				}
				String variableName = (String) pc.getParameter("variableName");
				if (!Strings.isEmpty(variableName)) {
					query.variableName(variableName);
				}
				String variableNameLike = (String) pc.getParameter("variableNameLike");
				if (!Strings.isEmpty(variableNameLike)) {
					query.variableNameLike(variableNameLike);
				}
				if (pc.getParameterAsBoolean("includeDeleted", false)) {
					query.includeDeleted();
				}

				Set<String> requested = pc.getBoundSourceNames();
				boolean needList = requested.contains("names")
						|| requested.contains("values") || requested.contains("variables");

				Map<String, Object> sources = new HashMap<>();
				if (needList) {
					List<HistoricVariableInstance> found = query.list();
					List<Object> names = new ArrayList<>();
					Map<String, Object> values = new LinkedHashMap<>();
					List<Object> variables = new ArrayList<>();
					for (HistoricVariableInstance variable : found) {
						names.add(variable.getName());
						values.put(variable.getName(), variable.getValue());

						Map<String, Object> detail = new LinkedHashMap<>();
						detail.put("id", variable.getId());
						detail.put("name", variable.getName());
						detail.put("value", variable.getValue());
						detail.put("typeName", variable.getTypeName());
						detail.put("processInstanceId", variable.getProcessInstanceId());
						detail.put("processDefinitionId", variable.getProcessDefinitionId());
						detail.put("processDefinitionKey", variable.getProcessDefinitionKey());
						detail.put("executionId", variable.getExecutionId());
						detail.put("activityInstanceId", variable.getActivityInstanceId());
						detail.put("taskId", variable.getTaskId());
						detail.put("state", variable.getState());
						detail.put("createTime", variable.getCreateTime());
						detail.put("errorMessage", variable.getErrorMessage());
						detail.put("tenantId", variable.getTenantId());
						variables.add(detail);
					}

					sources.put("count", found.size());
					sources.put("names", names);
					sources.put("values", values);
					sources.put("variables", variables);
				} else if (requested.contains("count")) {
					sources.put("count", (int) query.count());
				}
				pc.applyResultBindings(sources);
			}
		}

		/**
		 * Producer for deploying a BPMN / DMN / CMMN resource from a route (Group E, E1). The resource
		 * content is taken from the exchange using the same source-reference indirection as addAttachment,
		 * so a static {@code <to>} can deploy binary content without ${...} stringification.
		 *
		 * Supported parameters:
		 * - content: Source of the resource content; one of "@body" (default), "@header.x" or "@property.x".
		 *     The referenced value is converted to an InputStream (byte[], String, File or InputStream).
		 * - resourceName: Logical resource name including the extension (required; the extension decides the
		 *     resource type, for example "order.bpmn", "rating.dmn"). Falls back to the deployment name.
		 * - deploymentName / name: Optional deployment name
		 * - source: Optional deployment source tag (default "process application" when omitted is left to the engine)
		 * - enableDuplicateFiltering: When true, skip the deployment if an identical one already exists (default false)
		 *
		 * Sets the "deploymentId" header for downstream processing.
		 *
		 * Note: this overlaps the existing JCR auto-deployment under /bpm/deployments; prefer that for the
		 * normal lifecycle and use this operation for ad-hoc, route-driven deployments.
		 */
		private class DeployResourceProducer extends BpmProducer {
			private DeployResourceProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RepositoryService repository = processEngine.getRepositoryService();

				String deploymentName = firstNonEmpty((String) pc.getParameter("deploymentName"), (String) pc.getParameter("name"));
				String resourceName = (String) pc.getParameter("resourceName");
				if (Strings.isEmpty(resourceName)) {
					resourceName = deploymentName;
				}
				if (Strings.isEmpty(resourceName)) {
					throw new IllegalStateException("A resourceName (including the file extension) must be provided to deploy a resource");
				}

				String content = (String) pc.getParameter("content");
				if (Strings.isEmpty(content)) {
					content = "@body";
				}
				Object source = pc.resolveSource(content);
				if (source == null) {
					throw new IllegalStateException("The deployment content source '" + content + "' resolved to no value");
				}
				InputStream in = pc.toInputStream(source);
				if (in == null) {
					throw new IllegalStateException("The deployment content source '" + content + "' could not be converted to an InputStream");
				}

				DeploymentBuilder builder = repository.createDeployment();
				builder.addInputStream(resourceName, in);
				if (!Strings.isEmpty(deploymentName)) {
					builder.name(deploymentName);
				}
				String sourceTag = (String) pc.getParameter("source");
				if (!Strings.isEmpty(sourceTag)) {
					builder.source(sourceTag);
				}
				if (pc.getParameterAsBoolean("enableDuplicateFiltering", false)) {
					builder.enableDuplicateFiltering();
				}

				Deployment deployment = builder.deploy();
				if (deployment != null) {
					pc.setHeader("deploymentId", deployment.getId());
				}
			}

			private String firstNonEmpty(String first, String second) {
				return Strings.isEmpty(first) ? second : first;
			}
		}

		/**
		 * Producer for suspending or activating a process definition (and, optionally, its instances)
		 * (Group E, E2). The target is selected by id or key.
		 *
		 * Supported parameters (evaluated in priority order):
		 * - processDefinitionId: Suspend/activate the definition by id
		 * - processDefinitionKey: Suspend/activate the definition by key
		 * - includeInstances: When true, also suspend/activate all instances of the definition (default false)
		 * - executionDate: Optional Date (header value) at which the change should take effect; when omitted
		 *     the change is immediate
		 */
		private class SuspendDefinitionProducer extends BpmProducer {
			private final boolean fSuspend;

			private SuspendDefinitionProducer(boolean suspend) {
				super(BpmEndpoint.this);
				fSuspend = suspend;
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RepositoryService repository = processEngine.getRepositoryService();

				boolean includeInstances = pc.getParameterAsBoolean("includeInstances", false);
				Object executionDateObj = pc.getParameter("executionDate");
				java.util.Date executionDate = (executionDateObj instanceof java.util.Date) ? (java.util.Date) executionDateObj : null;
				boolean scheduled = includeInstances || executionDate != null;

				String processDefinitionId = (String) pc.getParameter("processDefinitionId");
				if (!Strings.isEmpty(processDefinitionId)) {
					if (fSuspend) {
						if (scheduled) {
							repository.suspendProcessDefinitionById(processDefinitionId, includeInstances, executionDate);
						} else {
							repository.suspendProcessDefinitionById(processDefinitionId);
						}
					} else {
						if (scheduled) {
							repository.activateProcessDefinitionById(processDefinitionId, includeInstances, executionDate);
						} else {
							repository.activateProcessDefinitionById(processDefinitionId);
						}
					}
					return;
				}

				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				if (!Strings.isEmpty(processDefinitionKey)) {
					if (fSuspend) {
						if (scheduled) {
							repository.suspendProcessDefinitionByKey(processDefinitionKey, includeInstances, executionDate);
						} else {
							repository.suspendProcessDefinitionByKey(processDefinitionKey);
						}
					} else {
						if (scheduled) {
							repository.activateProcessDefinitionByKey(processDefinitionKey, includeInstances, executionDate);
						} else {
							repository.activateProcessDefinitionByKey(processDefinitionKey);
						}
					}
					return;
				}

				throw new IllegalStateException("Either processDefinitionId or processDefinitionKey must be provided");
			}
		}

		/**
		 * Producer for querying process definitions (Group E, E3), for example to discover deployed keys
		 * and versions for routing or administration.
		 *
		 * Supported filter parameters (all optional; combined with AND semantics):
		 * - processDefinitionId: Match a single definition id
		 * - processDefinitionKey: Match the definition key
		 * - processDefinitionKeyLike: Match the definition key with a SQL LIKE pattern (use % as wildcard)
		 * - name: Match the definition name exactly
		 * - nameLike: Match the definition name with a SQL LIKE pattern
		 * - deploymentId: Match definitions from a deployment
		 * - versionTag: Match the version tag
		 * - latestVersion: When true, restrict to the latest version of each key
		 * - active / suspended: When true, restrict to active / suspended definitions
		 *
		 * Results are returned via output bindings (@body / @header.x / @property.x). The available sources are:
		 * - count: number of matching definitions (Integer)
		 * - ids: list of definition ids (List)
		 * - keys: list of definition keys (List)
		 * - definitions: list of detail maps, one per definition (List)
		 * Output is produced solely through the declared bindings: callers must set the bindings they need
		 * before invoking this producer. The list of matches is materialised only when a list source
		 * (ids/keys/definitions) is bound; when only the count is bound the matches are counted without
		 * being listed.
		 */
		private class QueryProcessDefinitionsProducer extends BpmProducer {
			private QueryProcessDefinitionsProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ProcessEngine processEngine = CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine();
				RepositoryService repository = processEngine.getRepositoryService();

				ProcessDefinitionQuery query = repository.createProcessDefinitionQuery();
				String processDefinitionId = (String) pc.getParameter("processDefinitionId");
				if (!Strings.isEmpty(processDefinitionId)) {
					query.processDefinitionId(processDefinitionId);
				}
				String processDefinitionKey = (String) pc.getParameter("processDefinitionKey");
				if (!Strings.isEmpty(processDefinitionKey)) {
					query.processDefinitionKey(processDefinitionKey);
				}
				String processDefinitionKeyLike = (String) pc.getParameter("processDefinitionKeyLike");
				if (!Strings.isEmpty(processDefinitionKeyLike)) {
					query.processDefinitionKeyLike(processDefinitionKeyLike);
				}
				String name = (String) pc.getParameter("name");
				if (!Strings.isEmpty(name)) {
					query.processDefinitionName(name);
				}
				String nameLike = (String) pc.getParameter("nameLike");
				if (!Strings.isEmpty(nameLike)) {
					query.processDefinitionNameLike(nameLike);
				}
				String deploymentId = (String) pc.getParameter("deploymentId");
				if (!Strings.isEmpty(deploymentId)) {
					query.deploymentId(deploymentId);
				}
				String versionTag = (String) pc.getParameter("versionTag");
				if (!Strings.isEmpty(versionTag)) {
					query.versionTag(versionTag);
				}
				if (pc.getParameterAsBoolean("latestVersion", false)) {
					query.latestVersion();
				}
				if (pc.getParameterAsBoolean("active", false)) {
					query.active();
				}
				if (pc.getParameterAsBoolean("suspended", false)) {
					query.suspended();
				}

				Set<String> requested = pc.getBoundSourceNames();
				boolean needList = requested.contains("ids")
						|| requested.contains("keys") || requested.contains("definitions");

				Map<String, Object> sources = new HashMap<>();
				if (needList) {
					List<ProcessDefinition> found = query.list();
					List<Object> ids = new ArrayList<>();
					List<Object> keys = new ArrayList<>();
					List<Object> definitions = new ArrayList<>();
					for (ProcessDefinition definition : found) {
						ids.add(definition.getId());
						keys.add(definition.getKey());

						Map<String, Object> detail = new LinkedHashMap<>();
						detail.put("id", definition.getId());
						detail.put("key", definition.getKey());
						detail.put("name", definition.getName());
						detail.put("version", definition.getVersion());
						detail.put("versionTag", definition.getVersionTag());
						detail.put("category", definition.getCategory());
						detail.put("description", definition.getDescription());
						detail.put("resourceName", definition.getResourceName());
						detail.put("deploymentId", definition.getDeploymentId());
						detail.put("diagramResourceName", definition.getDiagramResourceName());
						detail.put("suspended", definition.isSuspended());
						detail.put("tenantId", definition.getTenantId());
						definitions.add(detail);
					}

					sources.put("count", found.size());
					sources.put("ids", ids);
					sources.put("keys", keys);
					sources.put("definitions", definitions);
				} else if (requested.contains("count")) {
					sources.put("count", (int) query.count());
				}
				pc.applyResultBindings(sources);
			}
		}

		/**
		 * Coerce a string filter value into a Boolean, Long or Double when it looks like one, so that
		 * comparisons against typed process variables behave as expected. Non-string values (for example,
		 * typed exchange headers) are passed through unchanged. Shared by every variable-value filter
		 * (queryProcessInstances, queryTasks and the historic process-instance query).
		 */
		private Object coerceValue(Object value) {
			if (!(value instanceof String)) {
				return value;
			}
			String text = ((String) value).trim();
			if (text.isEmpty()) {
				return text;
			}
			if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
				return Boolean.valueOf(text);
			}
			try {
				return Long.valueOf(text);
			} catch (NumberFormatException ignore) {
				// not a long
			}
			try {
				return Double.valueOf(text);
			} catch (NumberFormatException ignore) {
				// not a double
			}
			return text;
		}

		/**
		 * Resolve the Camunda {@link TaskService} for this component's workspace.
		 */
		private TaskService taskService() {
			return CmsService.getWorkspaceProcessEngineProvider(fWorkspaceName).getProcessEngine().getTaskService();
		}

		/**
		 * Resolve and validate the user task id from endpoint parameters or exchange headers.
		 */
		private String requireTaskId(BpmProducer.ProcessContext pc) {
			String taskId = (String) pc.getParameter("taskId");
			if (Strings.isEmpty(taskId)) {
				throw new IllegalStateException("The task id must not be empty");
			}
			return taskId;
		}

		/**
		 * Exchange property set by the external-task producers (complete / fail / bpmnError) to mark that the
		 * task has already been finalised, so the consumer's auto-complete does not act on it a second time.
		 */
		private static final String EXTERNAL_TASK_HANDLED = "CamundaExternalTaskHandled";

		/**
		 * Consumer (from) that subscribes to one or more Camunda external-task topics and drives a Camel
		 * route for each fetched-and-locked task. This makes BpmComponent a bidirectional bridge: a Camunda
		 * service task implemented as an external task hands control to a Camel route, which can call out to
		 * external systems and then report the result back to the process.
		 *
		 * <p>The consumer is a scheduled poller. On each poll it issues a single {@code fetchAndLock} for all
		 * configured topics and processes the returned tasks one by one. Polling cadence, lock duration and
		 * batch size are configurable, so throughput and parallelism are governed entirely by the Camel
		 * route configuration and the CamelContext lifecycle (start/stop manage the poll scheduler).</p>
		 *
		 * <p>Each task is exposed to the route as headers: {@code externalTaskId}, {@code workerId},
		 * {@code externalTaskTopicName}, {@code externalTaskRetries}, {@code externalTaskPriority},
		 * {@code lockExpirationTime}, {@code processInstanceId}, {@code executionId}, {@code activityId},
		 * {@code activityInstanceId}, {@code processDefinitionId}, {@code processDefinitionKey},
		 * {@code businessKey}, {@code tenantId}, plus every fetched process variable under its own name. The
		 * message body is set to the fetched variable {@link Map}.</p>
		 *
		 * <p>Completion model (controlled by {@code autoComplete}, default {@code true}):</p>
		 * <ul>
		 *   <li>When the route finishes without error, the task is completed. Variables to return are taken
		 *       from the headers named by {@code outputVariables} (none by default, to avoid leaking Camel
		 *       internal headers into the process).</li>
		 *   <li>When the route throws, the failure is reported with {@code handleFailure}, decrementing the
		 *       remaining retries (seeded from {@code retries}, default 3, when the task has no retry count)
		 *       and applying {@code retryTimeout} (default 0).</li>
		 *   <li>If the route explicitly finalises the task through {@code completeExternalTask},
		 *       {@code failExternalTask} or {@code bpmnErrorExternalTask}, the consumer detects this and does
		 *       not act again. Set {@code autoComplete=false} to take full manual control.</li>
		 * </ul>
		 *
		 * Supported parameters:
		 * - topics / topic: One or more topic names to subscribe to (comma-separated). Required.
		 * - lockDuration: Lock duration in milliseconds (default 30000).
		 * - maxTasks: Maximum number of tasks fetched per poll (default 10).
		 * - workerId: Worker id used for locking/completion (default a generated, per-consumer id).
		 * - usePriority: Fetch higher-priority tasks first (default false).
		 * - fetchVariables: Comma-separated process variable names to fetch (default all).
		 * - localVariables: Fetch only the task-local variables (default false).
		 * - businessKey / processDefinitionKey / tenantId / withoutTenantId: Optional topic filters.
		 * - delay / initialDelay: Poll cadence in milliseconds (defaults 5000 / 1000).
		 * - autoComplete: Auto complete/fail the task after the route runs (default true).
		 * - outputVariables: Comma-separated header names returned as variables on auto-complete.
		 * - retries / retryTimeout: Auto-fail retry seed and back-off used when the route throws.
		 */
		private class ExternalTaskConsumer extends ScheduledPollConsumer {
			private final String fWorkerId;

			private ExternalTaskConsumer(Processor processor) {
				super(BpmEndpoint.this, processor);
				String configured = stringParameter("workerId");
				fWorkerId = Strings.isEmpty(configured)
						? ("camel-" + fWorkspaceName + "-" + UUID.randomUUID())
						: configured;
			}

			@Override
			protected int poll() throws Exception {
				List<String> topics = parameterList("topics");
				if (topics.isEmpty()) {
					topics = parameterList("topic");
				}
				if (topics.isEmpty()) {
					throw new IllegalStateException("At least one topic must be provided via the 'topics' (or 'topic') parameter");
				}

				int maxTasks = (int) longParameter("maxTasks", 10L);
				long lockDuration = longParameter("lockDuration", 30000L);
				boolean usePriority = booleanParameter("usePriority", false);
				boolean localVariables = booleanParameter("localVariables", false);
				List<String> fetchVariables = parameterList("fetchVariables");
				String businessKey = stringParameter("businessKey");
				List<String> processDefinitionKeyIn = parameterList("processDefinitionKey");
				List<String> tenantIdIn = parameterList("tenantId");
				boolean withoutTenantId = booleanParameter("withoutTenantId", false);

				ExternalTaskService service = externalTaskService();
				ExternalTaskQueryBuilder fetch = service.fetchAndLock(maxTasks, fWorkerId, usePriority);
				for (String topic : topics) {
					ExternalTaskQueryTopicBuilder tb = fetch.topic(topic, lockDuration);
					if (!fetchVariables.isEmpty()) {
						tb.variables(fetchVariables);
					}
					if (localVariables) {
						tb.localVariables();
					}
					if (!Strings.isEmpty(businessKey)) {
						tb.businessKey(businessKey);
					}
					if (!processDefinitionKeyIn.isEmpty()) {
						tb.processDefinitionKeyIn(processDefinitionKeyIn.toArray(new String[0]));
					}
					if (withoutTenantId) {
						tb.withoutTenantId();
					} else if (!tenantIdIn.isEmpty()) {
						tb.tenantIdIn(tenantIdIn.toArray(new String[0]));
					}
					fetch = tb;
				}

				List<LockedExternalTask> tasks = fetch.execute();
				if (tasks == null || tasks.isEmpty()) {
					return 0;
				}

				boolean autoComplete = booleanParameter("autoComplete", true);
				for (LockedExternalTask task : tasks) {
					processTask(service, task, autoComplete);
				}
				return tasks.size();
			}

			/**
			 * Route a single locked task and, when auto-complete is enabled, finalise it based on the outcome.
			 */
			private void processTask(ExternalTaskService service, LockedExternalTask task, boolean autoComplete) {
				Exchange exchange = getEndpoint().createExchange();
				try {
					Message in = exchange.getIn();
					in.setHeader("externalTaskId", task.getId());
					in.setHeader("workerId", fWorkerId);
					in.setHeader("externalTaskTopicName", task.getTopicName());
					in.setHeader("externalTaskRetries", task.getRetries());
					in.setHeader("externalTaskPriority", task.getPriority());
					in.setHeader("lockExpirationTime", task.getLockExpirationTime());
					in.setHeader("processInstanceId", task.getProcessInstanceId());
					in.setHeader("executionId", task.getExecutionId());
					in.setHeader("activityId", task.getActivityId());
					in.setHeader("activityInstanceId", task.getActivityInstanceId());
					in.setHeader("processDefinitionId", task.getProcessDefinitionId());
					in.setHeader("processDefinitionKey", task.getProcessDefinitionKey());
					in.setHeader("businessKey", task.getBusinessKey());
					in.setHeader("tenantId", task.getTenantId());

					Map<String, Object> variables = task.getVariables();
					if (variables != null) {
						for (Map.Entry<String, Object> entry : variables.entrySet()) {
							in.setHeader(entry.getKey(), entry.getValue());
						}
						in.setBody(variables);
					}

					try {
						getProcessor().process(exchange);
					} catch (Exception ex) {
						exchange.setException(ex);
					}

					if (!autoComplete) {
						return;
					}
					if (Boolean.TRUE.equals(exchange.getProperty(EXTERNAL_TASK_HANDLED))) {
						// The route already completed/failed the task through an explicit producer.
						return;
					}

					Exception failure = exchange.getException();
					if (failure != null) {
						int remaining = computeRemainingRetries(task);
						long retryTimeout = longParameter("retryTimeout", 0L);
						service.handleFailure(task.getId(), fWorkerId, failure.getMessage(), stackTrace(failure), remaining, retryTimeout);
					} else {
						service.complete(task.getId(), fWorkerId, selectOutputVariables(exchange));
					}
				} catch (Exception handlingError) {
					getExceptionHandler().handleException(
							"An error occurred while handling external task '" + task.getId() + "'", exchange, handlingError);
				}
			}

			/**
			 * Compute the remaining retries for an auto-fail: decrement the current count, seeding from the
			 * {@code retries} parameter (default 3) when the task has never been attempted.
			 */
			private int computeRemainingRetries(LockedExternalTask task) {
				Integer current = task.getRetries();
				int base = (current == null) ? (int) longParameter("retries", 3L) : current.intValue();
				return Math.max(base - 1, 0);
			}

			/**
			 * Collect the headers named by the {@code outputVariables} parameter to return as process variables.
			 */
			private Map<String, Object> selectOutputVariables(Exchange exchange) {
				Map<String, Object> output = new HashMap<>();
				Map<String, Object> headers = exchange.getIn().getHeaders();
				for (String name : parameterList("outputVariables")) {
					if (headers.containsKey(name)) {
						output.put(name, headers.get(name));
					}
				}
				return output;
			}

			private String stackTrace(Throwable t) {
				java.io.StringWriter sw = new java.io.StringWriter();
				t.printStackTrace(new java.io.PrintWriter(sw));
				return sw.toString();
			}
		}

		/**
		 * Producer for completing a locked external task and returning variables to the process.
		 *
		 * Supported parameters:
		 * - externalTaskId: Id of the locked external task (required; defaults to the consumer's header)
		 * - workerId: Worker id holding the lock (required; defaults to the consumer's header)
		 * - includes / excludes: Filters selecting exchange headers to return as process variables
		 *     (same scheme as the other producers)
		 */
		private class CompleteExternalTaskProducer extends BpmProducer {
			private CompleteExternalTaskProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ExternalTaskService service = externalTaskService();
				String externalTaskId = requireExternalTaskId(pc);
				String workerId = requireWorkerId(pc);

				service.complete(externalTaskId, workerId, pc.getVariables());
				pc.setProperty(EXTERNAL_TASK_HANDLED, Boolean.TRUE);
			}
		}

		/**
		 * Producer for reporting a recoverable failure of an external task.
		 *
		 * Supported parameters:
		 * - externalTaskId: Id of the locked external task (required; defaults to the consumer's header)
		 * - workerId: Worker id holding the lock (required; defaults to the consumer's header)
		 * - errorMessage: Short failure message
		 * - errorDetails: Optional detailed failure information (for example a stack trace)
		 * - retries: Remaining retries; when 0 (the default) an incident is created
		 * - retryTimeout: Back-off in milliseconds before the task becomes available again (default 0)
		 * - includes / excludes: Optional filters selecting exchange headers to set as process variables
		 */
		private class FailExternalTaskProducer extends BpmProducer {
			private FailExternalTaskProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ExternalTaskService service = externalTaskService();
				String externalTaskId = requireExternalTaskId(pc);
				String workerId = requireWorkerId(pc);

				String errorMessage = (String) pc.getParameter("errorMessage");
				String errorDetails = (String) pc.getParameter("errorDetails");
				Integer retriesObj = pc.getParameterAsInteger("retries");
				int retries = (retriesObj == null) ? 0 : retriesObj.intValue();
				long retryTimeout = pc.getParameterAsLong("retryTimeout", 0L);

				Map<String, Object> variables = pc.getVariables();
				if (variables.isEmpty()) {
					service.handleFailure(externalTaskId, workerId, errorMessage, errorDetails, retries, retryTimeout);
				} else {
					service.handleFailure(externalTaskId, workerId, errorMessage, errorDetails, retries, retryTimeout, variables, Collections.emptyMap());
				}
				pc.setProperty(EXTERNAL_TASK_HANDLED, Boolean.TRUE);
			}
		}

		/**
		 * Producer for raising a BPMN error from an external task, so the process can handle it with a
		 * boundary or event subprocess error catch.
		 *
		 * Supported parameters:
		 * - externalTaskId: Id of the locked external task (required; defaults to the consumer's header)
		 * - workerId: Worker id holding the lock (required; defaults to the consumer's header)
		 * - errorCode: BPMN error code to raise (required)
		 * - errorMessage: Optional error message
		 * - includes / excludes: Optional filters selecting exchange headers to set as process variables
		 */
		private class BpmnErrorExternalTaskProducer extends BpmProducer {
			private BpmnErrorExternalTaskProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ExternalTaskService service = externalTaskService();
				String externalTaskId = requireExternalTaskId(pc);
				String workerId = requireWorkerId(pc);

				String errorCode = (String) pc.getParameter("errorCode");
				if (Strings.isEmpty(errorCode)) {
					throw new IllegalStateException("The BPMN error code must not be empty");
				}
				String errorMessage = (String) pc.getParameter("errorMessage");

				Map<String, Object> variables = pc.getVariables();
				if (variables.isEmpty()) {
					service.handleBpmnError(externalTaskId, workerId, errorCode, errorMessage);
				} else {
					service.handleBpmnError(externalTaskId, workerId, errorCode, errorMessage, variables);
				}
				pc.setProperty(EXTERNAL_TASK_HANDLED, Boolean.TRUE);
			}
		}

		/**
		 * Producer for extending the lock on an external task, for long-running work that needs more time.
		 *
		 * Supported parameters:
		 * - externalTaskId: Id of the locked external task (required; defaults to the consumer's header)
		 * - workerId: Worker id holding the lock (required; defaults to the consumer's header)
		 * - lockDuration: Additional lock duration in milliseconds (required)
		 */
		private class ExtendLockProducer extends BpmProducer {
			private ExtendLockProducer() {
				super(BpmEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				ExternalTaskService service = externalTaskService();
				String externalTaskId = requireExternalTaskId(pc);
				String workerId = requireWorkerId(pc);

				long lockDuration = pc.getParameterAsLong("lockDuration", -1L);
				if (lockDuration < 0) {
					throw new IllegalStateException("A positive lockDuration (milliseconds) must be provided to extend the lock");
				}

				service.extendLock(externalTaskId, workerId, lockDuration);
			}
		}

		/**
		 * Resolve and validate the external task id from endpoint parameters or exchange headers.
		 */
		private String requireExternalTaskId(BpmProducer.ProcessContext pc) {
			String externalTaskId = (String) pc.getParameter("externalTaskId");
			if (Strings.isEmpty(externalTaskId)) {
				throw new IllegalStateException("The external task id must not be empty");
			}
			return externalTaskId;
		}

		/**
		 * Resolve and validate the worker id from endpoint parameters or exchange headers.
		 */
		private String requireWorkerId(BpmProducer.ProcessContext pc) {
			String workerId = (String) pc.getParameter("workerId");
			if (Strings.isEmpty(workerId)) {
				throw new IllegalStateException("The worker id must not be empty");
			}
			return workerId;
		}
	}
}

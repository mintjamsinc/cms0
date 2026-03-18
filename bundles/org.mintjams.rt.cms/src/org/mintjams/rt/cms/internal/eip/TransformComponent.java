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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.apache.commons.lang3.StringUtils;
import org.mintjams.tools.collections.AdaptableList;

public class TransformComponent extends DefaultComponent {

	public static final String COMPONENT_NAME = "transform";

	private final String fWorkspaceName;

	public TransformComponent(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		// Parse operation type from remaining (e.g., "store", "truncate", "move")
		String operation = remaining;

		TransformEndpoint endpoint = new TransformEndpoint(uri, operation, parameters);
		parameters.clear(); // All parameters are consumed internally by TransformEndpoint
		return endpoint;
	}

	public class TransformEndpoint extends DefaultEndpoint {
		private final String fOperation;
		private final Map<String, Object> fParameters;

		private TransformEndpoint(String endpointUri, String operation, Map<String, Object> parameters) {
			super(endpointUri, TransformComponent.this);
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
			if ("truncate".equals(fOperation)) {
				return new TruncateProducer();
			} else if ("toDate".equals(fOperation)) {
				return new ToDateProducer();
			} else if ("toDatetime".equals(fOperation)) {
				return new ToDatetimeProducer();
			} else if ("toTime".equals(fOperation)) {
				return new ToTimeProducer();
			} else if ("toInteger".equals(fOperation)) {
				return new ToIntegerProducer();
			} else if ("toLong".equals(fOperation)) {
				return new ToLongProducer();
			} else if ("toDouble".equals(fOperation)) {
				return new ToDoubleProducer();
			} else if ("toDecimal".equals(fOperation)) {
				return new ToDecimalProducer();
			} else if ("toBoolean".equals(fOperation)) {
				return new ToBooleanProducer();
			} else if ("toString".equals(fOperation)) {
				return new ToStringProducer();
			} else if ("remove".equals(fOperation)) {
				return new RemoveProducer();
			} else {
				// Unsupported operation
				throw new UnsupportedOperationException("Unsupported operation: " + fOperation);
			}
		}

		private abstract class TransformProducer extends DefaultProducer {
			protected TransformProducer(Endpoint endpoint) {
				super(endpoint);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				try (ProcessContext context = new ProcessContext(exchange)) {
					doProcess(context);
				}
			}

			/**
			 * Process the exchange by applying the transformation logic to the headers specified in the "targets" parameter.
			 */
			protected void doProcess(ProcessContext pc) throws Exception {
				Map<String, Object> headers = pc.getExchange().getIn().getHeaders();
				for (String filter : pc.parseFilterList(pc.getParameter("targets"))) {
					if (filter.endsWith("*")) {
						String prefix = filter.substring(0, filter.length() - 1);
						headers.entrySet().stream()
								.filter(entry -> entry.getKey().startsWith(prefix))
								.forEach(entry -> transform(entry.getKey(), entry.getValue(), pc));
					} else if (filter.startsWith("*")) {
						String suffix = filter.substring(1);
						headers.entrySet().stream()
								.filter(entry -> entry.getKey().endsWith(suffix))
								.forEach(entry -> transform(entry.getKey(), entry.getValue(), pc));
					} else {
						transform(filter, headers.get(filter), pc);
					}
				}
			}

			/**
			 * Apply the transformation logic to a specific header name and value, while respecting any exclusion filters defined in the "excludeTargets" parameter.
			 */
			protected void transform(String name, Object value, ProcessContext pc) {
				if (matches(name, pc.getExcludeTargets())) {
					return; // Skip transformation for excluded targets
				}

				applyTransform(name, value, pc);
			}

			/**
			 * Apply the specific transformation logic for the given header name and value.
			 * This method is implemented by each concrete producer to perform the appropriate transformation based on the operation type.
			 * The ProcessContext provides access to endpoint parameters and allows setting headers in the exchange for downstream processing.
			 */
			protected abstract void applyTransform(String name, Object value, ProcessContext pc);

			/**
			 * Check if a name matches any of the provided filters.
			 */
			protected boolean matches(String name, List<String> filters) {
				for (String filter : filters) {
					if (filter.endsWith("*")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (name.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("*")) {
						String suffix = filter.substring(1);
						if (name.endsWith(suffix)) {
							return true;
						}
					} else if (filter.endsWith("~")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (name.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("~")) {
						String suffix = filter.substring(1);
						if (name.endsWith(suffix)) {
							return true;
						}
					} else {
						if (name.equals(filter)) {
							return true;
						}
					}
				}
				return false;
			}

			/**
			 * ProcessContext provides convenient access to endpoint parameters and exchange data for producers.
			 */
			protected class ProcessContext implements Closeable {
				private final Exchange fExchange;
				private final Map<String, Object> fAttributes = new HashMap<>();

				protected ProcessContext(Exchange exchange) {
					fExchange = exchange;
				}

				public Exchange getExchange() {
					return fExchange;
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

				@SuppressWarnings("unchecked")
				public List<String> getExcludeTargets() {
					if (fAttributes.containsKey("excludeTargets")) {
						return (List<String>) fAttributes.get("excludeTargets");
					}

					List<String> excludeTargets = parseFilterList(getParameter("excludeTargets"));
					fAttributes.put("excludeTargets", excludeTargets);
					return excludeTargets;
				}

				/**
				 * Get a parameter value as a string.
				 */
				public String getParameterAsString(String key) {
					Object value = getParameter(key);
					return value != null ? value.toString() : null;
				}

				/**
				 * Get a parameter value as an integer.
				 */
				public Integer getParameterAsInteger(String key) {
					Object value = getParameter(key);
					return AdaptableList.newBuilder().add(value).build().getInteger(0);
				}

				/**
				 * Parse a filter parameter into a list of strings.
				 * Supports comma-separated strings, lists, and collections.
				 */
				public List<String> parseFilterList(Object filter) {
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
				 * Set a header in the exchange for downstream processing.
				 * This method can be used by producers to set headers based on their processing logic, which can then be consumed by other processors or components in the route.
				 */
				public void setHeader(String key, Object value) {
					fExchange.getIn().setHeader(key, value);
				}

				@Override
				public void close() throws IOException {
					// No resources to close in this implementation, but this is where you would clean up any per-invocation state if needed.
				}
			}
		}

		/**
		 * TruncateProducer implements the "truncate" operation.
		 */
		private class TruncateProducer extends TransformProducer {
			private TruncateProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				Integer offset = pc.getParameterAsInteger("offset");
				Integer maxLength = pc.getParameterAsInteger("maxLength");
				if (maxLength == null) {
					throw new IllegalArgumentException("maxLength parameter is required for truncate operation");
				}

				if (offset != null) {
					pc.setHeader(name, StringUtils.truncate(value.toString(), offset, maxLength));
				}
				pc.setHeader(name, StringUtils.truncate(value.toString(), maxLength));
			}
		}

		/**
		 * ToDateProducer implements the "toDate" operation.
		 */
		private class ToDateProducer extends TransformProducer {
			private ToDateProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getDate(0));
			}
		}

		/**
		 * ToDatetimeProducer implements the "toDatetime" operation.
		 */
		private class ToDatetimeProducer extends TransformProducer {
			private ToDatetimeProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getOffsetDateTime(0));
			}
		}

		/**
		 * ToTimeProducer implements the "toTime" operation.
		 */
		private class ToTimeProducer extends TransformProducer {
			private ToTimeProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getOffsetTime(0));
			}
		}

		/**
		 * ToIntegerProducer implements the "toTime" operation.
		 */
		private class ToIntegerProducer extends TransformProducer {
			private ToIntegerProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getInteger(0));
			}
		}

		/**
		 * ToLongProducer implements the "toTime" operation.
		 */
		private class ToLongProducer extends TransformProducer {
			private ToLongProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getLong(0));
			}
		}

		/**
		 * ToDoubleProducer implements the "toTime" operation.
		 */
		private class ToDoubleProducer extends TransformProducer {
			private ToDoubleProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getDouble(0));
			}
		}

		/**
		 * ToDecimalProducer implements the "toTime" operation.
		 */
		private class ToDecimalProducer extends TransformProducer {
			private ToDecimalProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getBigDecimal(0));
			}
		}

		/**
		 * ToBooleanProducer implements the "toTime" operation.
		 */
		private class ToBooleanProducer extends TransformProducer {
			private ToBooleanProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getBoolean(0));
			}
		}

		/**
		 * ToStringProducer implements the "toTime" operation.
		 */
		private class ToStringProducer extends TransformProducer {
			private ToStringProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				if (value == null) {
					return;
				}

				pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getString(0));
			}
		}

		/**
		 * RemoveProducer implements the "remove" operation.
		 */
		private class RemoveProducer extends TransformProducer {
			private RemoveProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) {
				pc.getExchange().getIn().removeHeader(name);
			}
		}
	}
}

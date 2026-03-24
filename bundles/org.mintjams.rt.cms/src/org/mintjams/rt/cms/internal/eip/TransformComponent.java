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
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
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
import org.apache.commons.lang3.time.DateUtils;
import org.mintjams.tools.collections.AdaptableList;

public class TransformComponent extends DefaultComponent {

	public static final String COMPONENT_NAME = "transform";

	private final String fWorkspaceName;

	public TransformComponent(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		// Parse operation type from remaining (e.g., "truncateString", "toDate", "parseDate", etc.)
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
			if ("truncateString".equals(fOperation)) {
				return new TruncateStringProducer();
			} else if ("truncateDate".equals(fOperation)) {
				return new TruncateDateProducer();
			} else if ("toDate".equals(fOperation)) {
				return new ToDateProducer();
			} else if ("parseDate".equals(fOperation)) {
				return new ParseDateProducer();
			} else if ("parseOffsetDate".equals(fOperation)) {
				return new ParseOffsetDateProducer();
			} else if ("parseLocalDate".equals(fOperation)) {
				return new ParseLocalDateProducer();
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
			} else if ("removeHeader".equals(fOperation)) {
				return new RemoveHeaderProducer();
			} else if ("toMap".equals(fOperation)) {
				return new ToMapProducer();
			} else if ("fromMap".equals(fOperation)) {
				return new FromMapProducer();
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
			 * This method retrieves the headers from the exchange and delegates to the doProcess(Map<String, Object>, ProcessContext) method for actual processing.
			 */
			protected void doProcess(ProcessContext pc) throws Exception {
				doProcess(pc.getExchange().getIn().getHeaders(), pc);
			}

			/**
			 * Process the given map of headers by applying the transformation logic to the headers specified in the "targets" parameter.
			 * This method iterates over the headers and applies the transformation to those that match the specified targets, while respecting any exclusion filters defined in the "excludeTargets" parameter.
			 */
			protected void doProcess(Map<String, Object> map, ProcessContext pc) throws Exception {
				if (map == null) {
					return; // No headers to process, so skip transformation
				}

				for (String filter : pc.parseFilterList(pc.getParameter("targets"))) {
					if (filter.endsWith("*")) {
						String prefix = filter.substring(0, filter.length() - 1);
						for (Map.Entry<String, Object> entry : map.entrySet()) {
							if (entry.getKey().startsWith(prefix)) {
								transform(entry.getKey(), entry.getValue(), pc);
							}
						}
					} else if (filter.startsWith("*")) {
						String suffix = filter.substring(1);
						for (Map.Entry<String, Object> entry : map.entrySet()) {
							if (entry.getKey().endsWith(suffix)) {
								transform(entry.getKey(), entry.getValue(), pc);
							}
						}
					} else {
						transform(filter, map.get(filter), pc);
					}
				}
			}

			/**
			 * Apply the transformation logic to a specific header name and value, while respecting any exclusion filters defined in the "excludeTargets" parameter.
			 */
			protected void transform(String name, Object value, ProcessContext pc) throws Exception {
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
			protected abstract void applyTransform(String name, Object value, ProcessContext pc) throws Exception;

			/**
			 * Parse a number from the given value using the locale specified in the "locale" parameter (if provided) or the default locale.
			 */
			protected BigDecimal parseDecimal(Object value, ProcessContext pc) throws NumberFormatException, ParseException {
				if (value instanceof BigDecimal) {
					return (BigDecimal) value;
				}

				Locale locale = Locale.getDefault();
				String localeString = pc.getParameterAsString("locale");
				if (localeString != null) {
					locale = parseLocale(localeString);
				}

				DecimalFormat df = (DecimalFormat) NumberFormat.getInstance(locale);
				df.setParseBigDecimal(true);
				return (BigDecimal) df.parse(value.toString());
			}

			/**
			 * Parse a locale string in the format "language", "language_country", or "language_country_variant".
			 */
			protected Locale parseLocale(String s) {
				String[] parts = s.split("[_-]");
				return switch (parts.length) {
					case 1 -> new Locale(parts[0]); // language only
					case 2 -> new Locale(parts[0], parts[1]); // language + country
					case 3 -> new Locale(parts[0], parts[1], parts[2]); // language + country + variant
					default -> throw new IllegalArgumentException("Invalid locale format: " + s);
				};
			}

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
					return value != null ? value.toString().trim() : null;
				}

				/**
				 * Get a parameter value as an integer.
				 */
				public Integer getParameterAsInteger(String key) {
					Object value = getParameter(key);
					return new BigDecimal(value.toString()).intValue();
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

		private class TruncateStringProducer extends TransformProducer {
			private TruncateStringProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
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
				} else {
					pc.setHeader(name, StringUtils.truncate(value.toString(), maxLength));
				}
			}
		}

		private class TruncateDateProducer extends TransformProducer {
			private TruncateDateProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				String field = pc.getParameterAsString("field");
				if (field == null) {
					throw new IllegalArgumentException("field parameter is required for truncateDate operation");
				}

				pc.setHeader(name, DateUtils.truncate((Date) value, toCalendarField(field)));
			}

			private int toCalendarField(String field) {
				return switch (field.toLowerCase()) {
					case "year" -> Calendar.YEAR;
					case "month" -> Calendar.MONTH;
					case "day" -> Calendar.DAY_OF_MONTH;
					case "hour" -> Calendar.HOUR_OF_DAY;
					case "minute" -> Calendar.MINUTE;
					case "second" -> Calendar.SECOND;
					default -> throw new IllegalArgumentException("Invalid field for truncateDate: " + field);
				};
			}
		}

		private class ToDateProducer extends TransformProducer {
			private ToDateProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				try {
					pc.setHeader(name, Date.from(OffsetDateTime.parse(value.toString()).toInstant()));
					return;
				} catch (DateTimeParseException ignore) {}

				try {
					LocalDateTime ldt = LocalDateTime.parse(value.toString());
					pc.setHeader(name, Date.from(ldt.toInstant(ZoneOffset.UTC)));
					return;
				} catch (DateTimeParseException ignore) {}

				try {
					pc.setHeader(name, AdaptableList.newBuilder().add(value).build().getDate(0));
					return;
				} catch (DateTimeParseException ignore) {}

				
			}
		}

		private class ParseDateProducer extends TransformProducer {
			private ParseDateProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				String pattern = pc.getParameterAsString("pattern");
				if (pattern == null) {
					throw new IllegalArgumentException("pattern parameter is required for toDate operation");
				}

				DateFormat df = new SimpleDateFormat(pattern);
				String timeZone = pc.getParameterAsString("timeZone");
				if (timeZone != null) {
					df.setTimeZone(TimeZone.getTimeZone(timeZone));
				}
				pc.setHeader(name, df.parse(value.toString()));
			}
		}

		private class ParseOffsetDateProducer extends TransformProducer {
			private ParseOffsetDateProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				pc.setHeader(name, Date.from(OffsetDateTime.parse(value.toString()).toInstant()));
			}
		}

		private class ParseLocalDateProducer extends TransformProducer {
			private ParseLocalDateProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				LocalDateTime ldt = LocalDateTime.parse(value.toString());

				ZoneOffset zoneOffset = ZoneOffset.of(ZoneId.systemDefault().getId());
				String offset = pc.getParameterAsString("offset");
				if (offset != null) {
					zoneOffset = ZoneOffset.of(offset);
				}

				pc.setHeader(name, Date.from(ldt.toInstant(zoneOffset)));
			}
		}

		private class ToIntegerProducer extends TransformProducer {
			private ToIntegerProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				pc.setHeader(name, parseDecimal(value, pc).intValue());
			}
		}

		private class ToLongProducer extends TransformProducer {
			private ToLongProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				pc.setHeader(name, parseDecimal(value, pc).longValue());
			}
		}

		private class ToDoubleProducer extends TransformProducer {
			private ToDoubleProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				pc.setHeader(name, parseDecimal(value, pc).doubleValue());
			}
		}

		private class ToDecimalProducer extends TransformProducer {
			private ToDecimalProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				pc.setHeader(name, parseDecimal(value, pc));
			}
		}

		private class ToBooleanProducer extends TransformProducer {
			private ToBooleanProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				Boolean b = switch (value.toString().trim().toLowerCase()) {
					case "true", "yes", "on", "1" -> Boolean.TRUE;
					case "false", "no", "off", "0" -> Boolean.FALSE;
					default -> throw new IllegalArgumentException("Cannot convert value to boolean: " + value);
				};
				pc.setHeader(name, b);
			}
		}

		private class ToStringProducer extends TransformProducer {
			private ToStringProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				if (value == null) {
					return;
				}

				pc.setHeader(name, value.toString());
			}
		}

		private class RemoveHeaderProducer extends TransformProducer {
			private RemoveHeaderProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				pc.getExchange().getIn().removeHeader(name);
			}
		}

		private class ToMapProducer extends TransformProducer {
			private ToMapProducer() {
				super(TransformEndpoint.this);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				String to = pc.getParameterAsString("to");
				@SuppressWarnings("unchecked")
				Map<String, Object> map = (Map<String, Object>) pc.getExchange().getIn().getHeader(to);
				if (map == null) {
					map = new HashMap<>();
					pc.getExchange().getIn().setHeader(to, map);
				}
				map.put(name, value);
			}
		}

		private class FromMapProducer extends TransformProducer {
			private FromMapProducer() {
				super(TransformEndpoint.this);
			}

			protected void doProcess(ProcessContext pc) throws Exception {
				String from = pc.getParameterAsString("from");
				@SuppressWarnings("unchecked")
				Map<String, Object> map = (Map<String, Object>) pc.getExchange().getIn().getHeader(from);
				doProcess(map, pc);
			}

			@Override
			protected void applyTransform(String name, Object value, ProcessContext pc) throws Exception {
				String prefix = pc.getParameterAsString("prefix");
				String suffix = pc.getParameterAsString("suffix");
				if (prefix != null) {
					name = prefix + name;
				}
				if (suffix != null) {
					name = name + suffix;
				}
				pc.setHeader(name, value);
			}
		}
	}
}

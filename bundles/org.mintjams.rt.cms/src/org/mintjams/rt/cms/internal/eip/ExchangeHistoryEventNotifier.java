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

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.camel.Exchange;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.commons.lang3.StringUtils;
import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Camel {@link org.apache.camel.spi.EventNotifier} that captures exchange
 * execution history and writes each exchange to its own JSON file in JCR.
 *
 * <h3>Storage layout</h3>
 * <pre>
 * /var/eip/history/{yyyy}/{MM}/{dd}/{exchangeId}.json
 * </pre>
 *
 * <p>Each file is a single JSON object representing one
 * {@link ExchangeHistoryRecord}, including its execution path (steps).
 *
 * <h3>Write strategy</h3>
 * Records are enqueued to an internal {@link LinkedBlockingQueue} and
 * written asynchronously by a dedicated writer thread. This ensures
 * that JCR I/O never blocks the Camel routes. If the queue fills up,
 * the oldest records are dropped with a warning.
 *
 * <h3>Header capture</h3>
 * Headers to capture are controlled by two exchange headers:
 * {@code mi:history.header.includes} (comma-separated patterns) and
 * {@code mi:history.header.excludes} (comma-separated patterns).
 * Patterns support exact match, prefix ({@code foo*} or {@code foo~}),
 * and suffix ({@code *bar} or {@code ~bar}) matching.
 * Captured headers include type information and values.
 *
 * <h3>Usage</h3>
 * <pre>
 * ExchangeHistoryEventNotifier notifier = new ExchangeHistoryEventNotifier(workspaceName);
 * notifier.start();
 * camelContext.getManagementStrategy().addEventNotifier(notifier);
 * // ... when shutting down:
 * notifier.close();
 * </pre>
 */
public class ExchangeHistoryEventNotifier extends EventNotifierSupport implements Closeable {

	private static final Logger LOG = LoggerFactory.getLogger(ExchangeHistoryEventNotifier.class);

	private static final String BASE_PATH = "/var/eip/history";

	private static final DateTimeFormatter DIR_DATE_FMT =
			DateTimeFormatter.ofPattern("yyyy/MM/dd/HH").withZone(ZoneOffset.UTC);

	private final String fWorkspaceName;
	private final ConcurrentMap<String, List<ExchangeHistoryRecord.Step>> fInflightSteps = new ConcurrentHashMap<>();

	private static final String HEADER_INCLUDES = "mi:history.header.includes";
	private static final String HEADER_EXCLUDES = "mi:history.header.excludes";
	private static final String HEADER_BUSINESS_KEY = "mi:history.businessKey";

	private static final int STRING_MAX_LENGTH = 1000;

	// -- configuration --
	private int fQueueCapacity = 1000;
	private boolean fTraceSteps = true;

	// -- async writer --
	private LinkedBlockingQueue<ExchangeHistoryRecord> fQueue;
	private Thread fWriterThread;
	private volatile boolean fCloseRequested;

	public ExchangeHistoryEventNotifier(String workspaceName) {
		fWorkspaceName = workspaceName;

		// We only care about exchange lifecycle events.
		setIgnoreCamelContextEvents(true);
		setIgnoreCamelContextInitEvents(true);
		setIgnoreRouteEvents(true);
		setIgnoreServiceEvents(true);
		setIgnoreStepEvents(true);
	}

	// -- configuration setters --

	/**
	 * Maximum number of records waiting to be written.
	 * When the queue is full, the oldest record is dropped.
	 * Default: 1000.
	 */
	public ExchangeHistoryEventNotifier setQueueCapacity(int capacity) {
		fQueueCapacity = capacity;
		return this;
	}

	/**
	 * Whether to capture per-step execution path via {@code ExchangeSentEvent}.
	 * Enabled by default. Disable to reduce memory/storage overhead when
	 * only aggregate timing is needed.
	 */
	public ExchangeHistoryEventNotifier setTraceSteps(boolean enabled) {
		fTraceSteps = enabled;
		return this;
	}

	// ------------------------------------------------------------------
	// EventNotifier SPI
	// ------------------------------------------------------------------

	@Override
	public void notify(CamelEvent event) throws Exception {
		if (fTraceSteps && event instanceof CamelEvent.ExchangeCreatedEvent) {
			Exchange exchange = ((CamelEvent.ExchangeCreatedEvent) event).getExchange();
			fInflightSteps.put(exchange.getExchangeId(), new ArrayList<>());
		} else if (fTraceSteps && event instanceof CamelEvent.ExchangeSentEvent) {
			onExchangeSent((CamelEvent.ExchangeSentEvent) event);
		} else if (event instanceof CamelEvent.ExchangeCompletedEvent) {
			onExchangeDone(((CamelEvent.ExchangeCompletedEvent) event).getExchange(), false);
		} else if (event instanceof CamelEvent.ExchangeFailedEvent) {
			onExchangeDone(((CamelEvent.ExchangeFailedEvent) event).getExchange(), true);
		}
	}

	private void onExchangeSent(CamelEvent.ExchangeSentEvent event) {
		try {
			Exchange exchange = event.getExchange();
			List<ExchangeHistoryRecord.Step> steps = fInflightSteps.get(exchange.getExchangeId());
			if (steps == null) {
				steps = new ArrayList<>();
				fInflightSteps.put(exchange.getExchangeId(), steps);
			}

			long created = exchange.getCreated();
			long now = System.currentTimeMillis();
			long offsetFromStart = now - created;

			synchronized (steps) {
				steps.add(new ExchangeHistoryRecord.Step(
						event.getEndpoint().getEndpointUri(),
						event.getTimeTaken(),
						offsetFromStart,
						steps.size()));
			}
		} catch (Throwable ex) {
			LOG.debug("Failed to record step for {}: {}",
					event.getExchange().getExchangeId(), ex.getMessage());
		}
	}

	// ------------------------------------------------------------------
	// Record building
	// ------------------------------------------------------------------

	private void onExchangeDone(Exchange exchange, boolean failed) {
		try {
			Instant now = Instant.now();
			long created = exchange.getCreated();
			long elapsed = now.toEpochMilli() - created;

			ExchangeHistoryRecord.Builder builder = ExchangeHistoryRecord.newBuilder()
					.setExchangeId(exchange.getExchangeId())
					.setRouteId(exchange.getFromRouteId())
					.setFromEndpoint(exchange.getFromEndpoint() != null
							? exchange.getFromEndpoint().getEndpointUri() : null)
					.setWorkspace(fWorkspaceName)
					.setTimestamp(now)
					.setCreatedAt(Instant.ofEpochMilli(created))
					.setCompletedAt(now)
					.setElapsed(elapsed)
					.setStatus(failed ? "failed" : "completed")
					.setRedelivered(exchange.isExternalRedelivered() == Boolean.TRUE)
					.setRedeliveryCounter(exchange.getIn().getHeader(
							Exchange.REDELIVERY_COUNTER, 0, Integer.class))
					.setRedeliveryMaxCounter(exchange.getIn().getHeader(
							Exchange.REDELIVERY_MAX_COUNTER, 0, Integer.class));

			// Exception details
			Exception cause = exchange.getException();
			if (cause == null) {
				cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
				if (cause != null) {
					builder.setFailureHandled(true);
				}
			}
			if (cause != null) {
				builder.setExceptionType(cause.getClass().getName());
				builder.setExceptionMessage(StringUtils.truncate(cause.getMessage(), 512));
			}

			// Body metadata
			Object body = exchange.getMessage().getBody();
			if (body != null) {
				builder.setBodyType(body.getClass().getSimpleName());
				if (body instanceof byte[]) {
					builder.setBodySize(((byte[]) body).length);
				} else if (body instanceof String) {
					builder.setBodySize(((String) body).getBytes(StandardCharsets.UTF_8).length);
				}
			}

			// Business key
			String businessKey = exchange.getIn().getHeader(HEADER_BUSINESS_KEY, String.class);
			if (businessKey != null) {
				builder.setBusinessKey(businessKey);
			}

			// Capture headers specified by include/exclude filters
			captureHeaders(exchange, builder);

			// Attach execution path
			List<ExchangeHistoryRecord.Step> steps =
					fInflightSteps.remove(exchange.getExchangeId());
			if (steps != null) {
				synchronized (steps) {
					for (ExchangeHistoryRecord.Step step : steps) {
						builder.addStep(step);
					}
				}
			}

			enqueue(builder.build(), elapsed);
		} catch (Throwable ex) {
			LOG.warn("Failed to record exchange history for {}: {}",
					exchange.getExchangeId(), ex.getMessage());
		}
	}

	private void captureHeaders(Exchange exchange, ExchangeHistoryRecord.Builder builder) {
		Map<String, Object> headers = exchange.getMessage().getHeaders();

		List<String> includes = parseFilterList(headers.get(HEADER_INCLUDES));
		List<String> excludes = parseFilterList(headers.get(HEADER_EXCLUDES));
		if (includes.isEmpty()) {
			return;
		}

		for (Map.Entry<String, Object> entry : headers.entrySet()) {
			String key = entry.getKey();
			String resolvedKey = resolveKey(key, includes);
			if (resolvedKey == null || matches(key, excludes)) {
				continue;
			}
			builder.addHeader(resolvedKey, buildHeaderInfo(entry.getValue()));
		}
	}

	// ------------------------------------------------------------------
	// Filter matching (supports exact, prefix*, *suffix, prefix~, ~suffix)
	// ------------------------------------------------------------------

	/**
	 * Resolve the recording key for a header name against include filters.
	 * Returns {@code null} if no filter matches.
	 * <ul>
	 *   <li>{@code prefix*} / {@code *suffix} — match by prefix/suffix, key unchanged</li>
	 *   <li>{@code prefix~} — match by prefix, strip the prefix from the key</li>
	 *   <li>{@code ~suffix} — match by suffix, strip the suffix from the key</li>
	 *   <li>exact — exact match, key unchanged</li>
	 * </ul>
	 */
	private String resolveKey(String name, List<String> filters) {
		for (String filter : filters) {
			if (filter.endsWith("*")) {
				String prefix = filter.substring(0, filter.length() - 1);
				if (name.startsWith(prefix)) {
					return name;
				}
			} else if (filter.startsWith("*")) {
				String suffix = filter.substring(1);
				if (name.endsWith(suffix)) {
					return name;
				}
			} else if (filter.endsWith("~")) {
				String prefix = filter.substring(0, filter.length() - 1);
				if (name.startsWith(prefix)) {
					return name.substring(prefix.length());
				}
			} else if (filter.startsWith("~")) {
				String suffix = filter.substring(1);
				if (name.endsWith(suffix)) {
					return name.substring(0, name.length() - suffix.length());
				}
			} else {
				if (name.equals(filter)) {
					return name;
				}
			}
		}
		return null;
	}

	private boolean matches(String name, List<String> filters) {
		for (String filter : filters) {
			if (filter.endsWith("*")) {
				if (name.startsWith(filter.substring(0, filter.length() - 1))) {
					return true;
				}
			} else if (filter.startsWith("*")) {
				if (name.endsWith(filter.substring(1))) {
					return true;
				}
			} else if (filter.endsWith("~")) {
				if (name.startsWith(filter.substring(0, filter.length() - 1))) {
					return true;
				}
			} else if (filter.startsWith("~")) {
				if (name.endsWith(filter.substring(1))) {
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

	// ------------------------------------------------------------------
	// Typed header value capture
	// ------------------------------------------------------------------

	private Map<String, Object> buildHeaderInfo(Object value) {
		Map<String, Object> info = new LinkedHashMap<>();

		if (value == null) {
			info.put("type", "string");
			info.put("value", null);
			return info;
		}

		if (value instanceof String s) {
			info.put("type", "string");
			if (s.length() >= STRING_MAX_LENGTH) {
				info.put("value", s.substring(0, STRING_MAX_LENGTH));
				info.put("length", s.length());
			} else {
				info.put("value", s);
			}
		} else if (value instanceof Integer) {
			info.put("type", "int");
			info.put("value", value);
		} else if (value instanceof Long) {
			info.put("type", "long");
			info.put("value", value);
		} else if (value instanceof Float) {
			info.put("type", "float");
			info.put("value", value);
		} else if (value instanceof Double) {
			info.put("type", "double");
			info.put("value", value);
		} else if (value instanceof BigDecimal) {
			info.put("type", "decimal");
			info.put("value", value);
		} else if (value instanceof BigInteger) {
			info.put("type", "bigint");
			info.put("value", value);
		} else if (value instanceof Boolean) {
			info.put("type", "boolean");
			info.put("value", value);
		} else if (value instanceof Date date) {
			info.put("type", "date");
			info.put("value", date.toInstant().atOffset(ZoneOffset.UTC)
					.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		} else if (value instanceof Calendar cal) {
			info.put("type", "date");
			info.put("value", cal.toInstant().atOffset(ZoneOffset.UTC)
					.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		} else if (value instanceof Instant instant) {
			info.put("type", "date");
			info.put("value", instant.atOffset(ZoneOffset.UTC)
					.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		} else if (value instanceof OffsetDateTime odt) {
			info.put("type", "date");
			info.put("value", odt.withOffsetSameInstant(ZoneOffset.UTC)
					.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		} else if (value instanceof ZonedDateTime zdt) {
			info.put("type", "date");
			info.put("value", zdt.withZoneSameInstant(ZoneOffset.UTC)
					.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		} else if (value instanceof LocalDateTime ldt) {
			info.put("type", "date");
			info.put("value", ldt.atOffset(ZoneOffset.UTC)
					.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		} else if (value instanceof LocalDate ld) {
			info.put("type", "date");
			info.put("value", ld.atStartOfDay(ZoneOffset.UTC)
					.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		} else if (value instanceof OffsetTime ot) {
			info.put("type", "time");
			info.put("value", ot.withOffsetSameInstant(ZoneOffset.UTC)
					.format(DateTimeFormatter.ISO_OFFSET_TIME));
		} else if (value instanceof LocalTime lt) {
			info.put("type", "time");
			info.put("value", lt.atOffset(ZoneOffset.UTC)
					.format(DateTimeFormatter.ISO_OFFSET_TIME));
		} else if (value instanceof byte[] bytes) {
			info.put("type", "binary");
			info.put("size", bytes.length);
		} else if (value instanceof InputStream) {
			info.put("type", "binary");
		} else if (value instanceof URI) {
			info.put("type", "uri");
			info.put("value", value.toString());
		} else if (value instanceof URL) {
			info.put("type", "url");
			info.put("value", value.toString());
		} else if (value instanceof Map) {
			info.put("type", "map");
		} else if (value instanceof List) {
			info.put("type", "list");
		} else if (value.getClass().isArray()) {
			info.put("type", "array");
		} else {
			info.put("type", value.getClass().getName());
			info.put("value", StringUtils.truncate(value.toString(), STRING_MAX_LENGTH));
		}

		return info;
	}

	// ------------------------------------------------------------------
	// Async queue
	// ------------------------------------------------------------------

	private void enqueue(ExchangeHistoryRecord record, long elapsed) {
		LOG.debug("Exchange {} on route {} — {} in {}ms",
				record.getExchangeId(), record.getRouteId(),
				record.getStatus(), elapsed);

		if (!fQueue.offer(record)) {
			// Queue is full — drop the head (oldest) and retry
			fQueue.poll();
			if (!fQueue.offer(record)) {
				LOG.warn("Dropped exchange history record for {} — queue full",
						record.getExchangeId());
			}
		}
	}

	// ------------------------------------------------------------------
	// Writer thread — one record = one file
	// ------------------------------------------------------------------

	private void writerLoop() {
		Session session = null;
		int consecutiveErrors = 0;

		while (!fCloseRequested || !fQueue.isEmpty()) {
			ExchangeHistoryRecord record = null;
			try {
				record = fQueue.poll(1, TimeUnit.SECONDS);
				if (record == null) {
					continue;
				}

				// Lazy open / reopen session
				if (session == null || !session.isLive()) {
					session = CmsService.getRepository().login(new CmsServiceCredentials(), fWorkspaceName);
				}

				writeRecord(session, record);
				consecutiveErrors = 0;

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Throwable ex) {
				consecutiveErrors++;
				LOG.error("Failed to write exchange history for {}: {}",
						record != null ? record.getExchangeId() : "unknown",
						ex.getMessage(), ex);

				// Close broken session so it gets reopened
				if (session != null) {
					try {
						session.logout();
					} catch (Throwable ignore) {}
					session = null;
				}

				// Back off on consecutive errors
				if (consecutiveErrors > 3) {
					try {
						Thread.sleep(Math.min(consecutiveErrors * 1000L, 10000L));
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		}

		// Drain remaining records before exit
		if (session == null || !session.isLive()) {
			try {
				session = CmsService.getRepository().login(new CmsServiceCredentials(), fWorkspaceName);
			} catch (Throwable ex) {
				LOG.error("Cannot open session for final drain: {}", ex.getMessage());
			}
		}
		if (session != null) {
			ExchangeHistoryRecord remaining;
			while ((remaining = fQueue.poll()) != null) {
				try {
					writeRecord(session, remaining);
				} catch (Throwable ex) {
					LOG.error("Failed to write remaining record {}: {}", remaining.getExchangeId(), ex.getMessage());
				}
			}
			try {
				session.logout();
			} catch (Throwable ignore) {
			}
		}
	}

	private void writeRecord(Session session, ExchangeHistoryRecord record) throws Exception {
		Instant timestamp = Instant.parse(record.getTimestamp());
		String routeId = record.getRouteId() != null ? record.getRouteId() : "_unknown";
		String dirPath = BASE_PATH + "/" + DIR_DATE_FMT.format(timestamp) + "/" + routeId;
		String fileName = record.getExchangeId() + ".json";

		Node parentNode = JCRs.getOrCreateFolder(JcrPath.valueOf(dirPath), session);

		String json = CmsService.toJSON(record);

		try (ByteArrayInputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
			Node fileNode;
			if (parentNode.hasNode(fileName)) {
				fileNode = parentNode.getNode(fileName);
			} else {
				fileNode = JCRs.createFile(parentNode, fileName);
			}
			JCRs.write(fileNode, in);
			JCRs.setProperty(fileNode, "jcr:mimeType", "application/json");
			JCRs.setProperty(fileNode, "jcr:lastModified", java.util.Calendar.getInstance());

			// Searchable properties
			JCRs.setProperty(fileNode, "mi:exchangeId", record.getExchangeId());
			JCRs.setProperty(fileNode, "mi:routeId", record.getRouteId());
			JCRs.setProperty(fileNode, "mi:status", record.getStatus());
			JCRs.setProperty(fileNode, "mi:elapsed", record.getElapsed());

			Calendar createdAtCal = Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC));
			createdAtCal.setTimeInMillis(Instant.parse(record.getCreatedAt()).toEpochMilli());
			JCRs.setProperty(fileNode, "mi:createdAt", createdAtCal);

			if (record.getBusinessKey() != null) {
				JCRs.setProperty(fileNode, "mi:businessKey", record.getBusinessKey());
			}

			session.save();
			LOG.debug("Wrote exchange history to {}/{}", dirPath, fileName);
		}
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	@Override
	protected void doStart() throws Exception {
		super.doStart();

		fCloseRequested = false;
		fQueue = new LinkedBlockingQueue<>(fQueueCapacity);
		fWriterThread = new Thread(this::writerLoop,
				"eip-history-writer-" + fWorkspaceName);
		fWriterThread.setDaemon(true);
		fWriterThread.start();
	}

	@Override
	protected void doStop() throws Exception {
		close();
		super.doStop();
	}

	@Override
	public void close() throws IOException {
		fCloseRequested = true;
		fInflightSteps.clear();
		if (fWriterThread != null) {
			fWriterThread.interrupt();
			try {
				fWriterThread.join(10000);
			} catch (InterruptedException ignore) {
				Thread.currentThread().interrupt();
			}
			fWriterThread = null;
		}
	}

}

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable record capturing the execution of a single Camel exchange.
 */
public class ExchangeHistoryRecord {

	// -- identity --
	private final String exchangeId;
	private final String routeId;
	private final String fromEndpoint;
	private final String workspace;

	// -- timing --
	private final String timestamp;      // ISO 8601, event time
	private final String createdAt;      // ISO 8601, exchange creation
	private final String completedAt;    // ISO 8601, exchange completion
	private final long elapsed;          // millis from creation to completion

	// -- outcome --
	private final String status;         // "completed" | "failed"
	private final String exceptionType;  // null if success
	private final String exceptionMessage;
	private final boolean failureHandled;

	// -- redelivery --
	private final boolean redelivered;
	private final int redeliveryCounter;
	private final int redeliveryMaxCounter;

	// -- payload --
	private final String bodyType;
	private final long bodySize;         // -1 if unknown

	// -- custom business keys --
	private final Map<String, String> properties;

	// -- execution path --
	private final List<Step> steps;

	/**
	 * A single step in the exchange execution path.
	 *
	 * <p>Each step corresponds to a {@code to} / {@code toD} endpoint call
	 * within the route, captured via {@code ExchangeSentEvent}.
	 */
	public static class Step {
		private final String endpointUri;
		private final long timeTaken;       // millis
		private final long offsetFromStart; // millis since exchange creation
		private final int order;            // 0-based sequence

		public Step(String endpointUri, long timeTaken, long offsetFromStart, int order) {
			this.endpointUri = endpointUri;
			this.timeTaken = timeTaken;
			this.offsetFromStart = offsetFromStart;
			this.order = order;
		}

		public String getEndpointUri() { return endpointUri; }
		public long getTimeTaken() { return timeTaken; }
		public long getOffsetFromStart() { return offsetFromStart; }
		public int getOrder() { return order; }
	}

	private ExchangeHistoryRecord(Builder builder) {
		this.exchangeId = builder.exchangeId;
		this.routeId = builder.routeId;
		this.fromEndpoint = builder.fromEndpoint;
		this.workspace = builder.workspace;
		this.timestamp = builder.timestamp;
		this.createdAt = builder.createdAt;
		this.completedAt = builder.completedAt;
		this.elapsed = builder.elapsed;
		this.status = builder.status;
		this.exceptionType = builder.exceptionType;
		this.exceptionMessage = builder.exceptionMessage;
		this.failureHandled = builder.failureHandled;
		this.redelivered = builder.redelivered;
		this.redeliveryCounter = builder.redeliveryCounter;
		this.redeliveryMaxCounter = builder.redeliveryMaxCounter;
		this.bodyType = builder.bodyType;
		this.bodySize = builder.bodySize;
		this.properties = builder.properties.isEmpty()
				? null : new LinkedHashMap<>(builder.properties);
		this.steps = builder.steps.isEmpty()
				? null : Collections.unmodifiableList(new ArrayList<>(builder.steps));
	}

	public String getExchangeId() { return exchangeId; }
	public String getRouteId() { return routeId; }
	public String getFromEndpoint() { return fromEndpoint; }
	public String getWorkspace() { return workspace; }
	public String getTimestamp() { return timestamp; }
	public String getCreatedAt() { return createdAt; }
	public String getCompletedAt() { return completedAt; }
	public long getElapsed() { return elapsed; }
	public String getStatus() { return status; }
	public String getExceptionType() { return exceptionType; }
	public String getExceptionMessage() { return exceptionMessage; }
	public boolean isFailureHandled() { return failureHandled; }
	public boolean isRedelivered() { return redelivered; }
	public int getRedeliveryCounter() { return redeliveryCounter; }
	public int getRedeliveryMaxCounter() { return redeliveryMaxCounter; }
	public String getBodyType() { return bodyType; }
	public long getBodySize() { return bodySize; }
	public Map<String, String> getProperties() { return properties; }
	public List<Step> getSteps() { return steps; }

	public static Builder newBuilder() {
		return new Builder();
	}

	public static class Builder {
		private String exchangeId;
		private String routeId;
		private String fromEndpoint;
		private String workspace;
		private String timestamp;
		private String createdAt;
		private String completedAt;
		private long elapsed;
		private String status = "completed";
		private String exceptionType;
		private String exceptionMessage;
		private boolean failureHandled;
		private boolean redelivered;
		private int redeliveryCounter;
		private int redeliveryMaxCounter;
		private String bodyType;
		private long bodySize = -1;
		private final Map<String, String> properties = new LinkedHashMap<>();
		private final List<Step> steps = new ArrayList<>();

		public Builder setExchangeId(String v) { exchangeId = v; return this; }
		public Builder setRouteId(String v) { routeId = v; return this; }
		public Builder setFromEndpoint(String v) { fromEndpoint = v; return this; }
		public Builder setWorkspace(String v) { workspace = v; return this; }
		public Builder setTimestamp(Instant v) { timestamp = v != null ? v.toString() : null; return this; }
		public Builder setCreatedAt(Instant v) { createdAt = v != null ? v.toString() : null; return this; }
		public Builder setCompletedAt(Instant v) { completedAt = v != null ? v.toString() : null; return this; }
		public Builder setElapsed(long v) { elapsed = v; return this; }
		public Builder setStatus(String v) { status = v; return this; }
		public Builder setExceptionType(String v) { exceptionType = v; return this; }
		public Builder setExceptionMessage(String v) { exceptionMessage = v; return this; }
		public Builder setFailureHandled(boolean v) { failureHandled = v; return this; }
		public Builder setRedelivered(boolean v) { redelivered = v; return this; }
		public Builder setRedeliveryCounter(int v) { redeliveryCounter = v; return this; }
		public Builder setRedeliveryMaxCounter(int v) { redeliveryMaxCounter = v; return this; }
		public Builder setBodyType(String v) { bodyType = v; return this; }
		public Builder setBodySize(long v) { bodySize = v; return this; }
		public Builder addProperty(String key, String value) {
			if (key != null && value != null) {
				properties.put(key, value);
			}
			return this;
		}
		public Builder addStep(Step step) {
			if (step != null) {
				steps.add(step);
			}
			return this;
		}

		public ExchangeHistoryRecord build() {
			return new ExchangeHistoryRecord(this);
		}
	}
}

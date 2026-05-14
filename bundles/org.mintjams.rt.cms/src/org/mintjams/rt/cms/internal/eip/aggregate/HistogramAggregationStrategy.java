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

package org.mintjams.rt.cms.internal.eip.aggregate;

import java.io.InputStream;
import java.io.Reader;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.eip.stats.Bucket;
import org.mintjams.rt.cms.internal.eip.stats.BucketPathResolver;
import org.mintjams.rt.cms.internal.eip.stats.Interval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Camel {@link AggregationStrategy} that builds a {@link Bucket} from raw
 * exchange history records pulled out of {@code /var/eip/history}.
 *
 * <p>Each incoming exchange is expected to carry a single history record as
 * its body, either already parsed to a {@link Map} (e.g. after
 * {@code <unmarshal><json/></unmarshal>}) or as JSON text in a {@code String},
 * {@code byte[]}, {@code InputStream} or {@code Reader}. The record shape
 * matches {@code ExchangeHistoryRecord}, in particular:
 * <ul>
 *   <li>{@code routeId} (String)</li>
 *   <li>{@code timestamp} (ISO-8601 String, used to derive the bucket window)</li>
 *   <li>{@code elapsed} (long, milliseconds)</li>
 *   <li>{@code status} (String; anything other than {@code "completed"}
 *       counts as an error)</li>
 *   <li>{@code steps[]} with each step carrying {@code endpointUri},
 *       {@code timeTaken} and {@code order}</li>
 * </ul>
 *
 * <p>The bucket window is taken from the <em>first</em> record observed in
 * each correlation group. Callers are expected to choose a correlation
 * expression that already aligns records to the chosen interval — for
 * example: {@code ${body[routeId]}-${body[timestamp].substring(0,16)}} for
 * 1-minute buckets keyed on the record's own timestamp.
 *
 * <p>On completion (when Camel's aggregator emits the merged exchange) this
 * strategy:
 * <ol>
 *   <li>serializes the bucket to JSON and replaces the message body with it,</li>
 *   <li>sets {@code eipBucketPath} to the canonical JCR path derived from
 *       {@link BucketPathResolver},</li>
 *   <li>sets {@code eipBucketRoute}, {@code eipBucketInterval} and
 *       {@code eipBucketStart} for downstream routing convenience.</li>
 * </ol>
 *
 * <p>Subsequent steps in the route can therefore use
 * {@code cms:store?path=${header.eipBucketPath}&mimeType=application/json}
 * to persist the bucket.
 */
public class HistogramAggregationStrategy implements AggregationStrategy {

	private static final String PROPERTY_BUCKET = "eip.bucket";
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
			new TypeReference<LinkedHashMap<String, Object>>() {};

	private final ObjectMapper fMapper = new ObjectMapper();
	private final Interval fInterval;
	private final StatsConfigCache fConfigCache;

	public HistogramAggregationStrategy() {
		this(Interval.ONE_MINUTE, null);
	}

	public HistogramAggregationStrategy(Interval interval) {
		this(interval, null);
	}

	public HistogramAggregationStrategy(Interval interval, StatsConfigCache configCache) {
		fInterval = interval;
		fConfigCache = configCache;
	}

	@Override
	public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
		Map<String, Object> record = readRecord(newExchange);

		if (oldExchange == null) {
			Bucket bucket = createBucket(record);
			addRecord(bucket, record);
			newExchange.setProperty(PROPERTY_BUCKET, bucket);
			return newExchange;
		}

		Bucket bucket = oldExchange.getProperty(PROPERTY_BUCKET, Bucket.class);
		if (bucket == null) {
			bucket = createBucket(record);
			oldExchange.setProperty(PROPERTY_BUCKET, bucket);
		}
		addRecord(bucket, record);
		return oldExchange;
	}

	@Override
	public void onCompletion(Exchange exchange) {
		if (exchange == null) {
			return;
		}
		Bucket bucket = exchange.getProperty(PROPERTY_BUCKET, Bucket.class);
		if (bucket == null) {
			return;
		}

		try {
			String json = fMapper.writeValueAsString(bucket.toJsonMap());
			exchange.getIn().setBody(json);
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize bucket: " + ex.getMessage(), ex);
		}

		exchange.getIn().setHeader("eipBucketPath", BucketPathResolver.bucketPath(
				bucket.getRouteId(), bucket.getInterval(), bucket.getBucketStart()));
		exchange.getIn().setHeader("eipBucketRoute", bucket.getRouteId());
		exchange.getIn().setHeader("eipBucketInterval", bucket.getInterval().label());
		exchange.getIn().setHeader("eipBucketStart", bucket.getBucketStart().toString());

		exchange.removeProperty(PROPERTY_BUCKET);
	}

	private Bucket createBucket(Map<String, Object> record) {
		String routeId = stringValue(record.get("routeId"));
		if (routeId == null || routeId.isEmpty()) {
			routeId = "_unknown";
		}
		Instant timestamp = parseInstant(stringValue(record.get("timestamp")));
		Instant bucketStart = fInterval.truncate(timestamp);
		return new Bucket(routeId, bucketStart, fInterval);
	}

	private void addRecord(Bucket bucket, Map<String, Object> record) {
		StatsConfig config = fConfigCache == null ? null : fConfigCache.get(bucket.getRouteId());
		RecordAggregator.apply(bucket, record, config);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readRecord(Exchange exchange) {
		Object body = exchange.getIn().getBody();
		try {
			if (body instanceof Map) {
				return (Map<String, Object>) body;
			}
			if (body instanceof String) {
				return fMapper.readValue((String) body, MAP_TYPE);
			}
			if (body instanceof byte[]) {
				return fMapper.readValue((byte[]) body, MAP_TYPE);
			}
			if (body instanceof InputStream) {
				try (InputStream in = (InputStream) body) {
					return fMapper.readValue(in, MAP_TYPE);
				}
			}
			if (body instanceof Reader) {
				try (Reader in = (Reader) body) {
					return fMapper.readValue(in, MAP_TYPE);
				}
			}
		} catch (Exception ex) {
			CmsService.getLogger(getClass()).warn(
					"Failed to parse history record (exchange={}): {}",
					exchange.getExchangeId(), ex.getMessage());
			return Collections.emptyMap();
		}
		CmsService.getLogger(getClass()).warn(
				"Unsupported history record body type: {}",
				body == null ? "null" : body.getClass().getName());
		return Collections.emptyMap();
	}

	private static String stringValue(Object o) {
		return o == null ? null : o.toString();
	}

	private static Instant parseInstant(String iso) {
		if (iso == null || iso.isEmpty()) {
			return Instant.now();
		}
		try {
			return Instant.parse(iso);
		} catch (Exception ignore) {
			return Instant.now();
		}
	}
}

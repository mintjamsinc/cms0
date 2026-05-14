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

package org.mintjams.rt.cms.internal.eip.stats;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single aggregation bucket. Represents the statistics gathered for one
 * route within one time window at one granularity.
 *
 * <p>Buckets are mergeable: combining two buckets that share the same
 * {@code routeId} and bucket start produces another bucket with the same
 * granularity. Combining buckets from a lower granularity into a higher one
 * (e.g. five 1-minute buckets into one 5-minute bucket) is how rollup works.
 */
public class Bucket {

	private final String fRouteId;
	private final Instant fBucketStart;
	private final Interval fInterval;
	private final Stats fElapsed = new Stats();
	private final Map<String, Stats> fSteps = new LinkedHashMap<>();
	private final Map<String, Map<String, Stats>> fBy = new LinkedHashMap<>();

	public Bucket(String routeId, Instant bucketStart, Interval interval) {
		fRouteId = routeId;
		fBucketStart = bucketStart;
		fInterval = interval;
	}

	public String getRouteId() {
		return fRouteId;
	}

	public Instant getBucketStart() {
		return fBucketStart;
	}

	public Interval getInterval() {
		return fInterval;
	}

	public Stats elapsed() {
		return fElapsed;
	}

	public Map<String, Stats> steps() {
		return fSteps;
	}

	public Map<String, Map<String, Stats>> by() {
		return fBy;
	}

	public Stats step(String stepKey) {
		return fSteps.computeIfAbsent(stepKey, k -> new Stats());
	}

	public Stats by(String dimension, String value) {
		return fBy.computeIfAbsent(dimension, k -> new LinkedHashMap<>())
				.computeIfAbsent(value, k -> new Stats());
	}

	/**
	 * Merge another bucket into this one. The other bucket must cover an equal
	 * or narrower time range that falls within this bucket's window.
	 */
	public Bucket merge(Bucket other) {
		if (other == null) {
			return this;
		}
		fElapsed.merge(other.fElapsed);
		for (Map.Entry<String, Stats> e : other.fSteps.entrySet()) {
			fSteps.computeIfAbsent(e.getKey(), k -> new Stats()).merge(e.getValue());
		}
		for (Map.Entry<String, Map<String, Stats>> dim : other.fBy.entrySet()) {
			Map<String, Stats> target = fBy.computeIfAbsent(dim.getKey(), k -> new LinkedHashMap<>());
			for (Map.Entry<String, Stats> val : dim.getValue().entrySet()) {
				target.computeIfAbsent(val.getKey(), k -> new Stats()).merge(val.getValue());
			}
		}
		return this;
	}

	public Map<String, Object> toJsonMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("route", fRouteId);
		map.put("bucket", fBucketStart.toString());
		map.put("interval", fInterval.label());
		map.put("count", fElapsed.getCount());
		map.put("errors", fElapsed.getErrors());
		map.put("elapsed", elapsedMap(fElapsed));

		if (!fSteps.isEmpty()) {
			Map<String, Object> stepsMap = new LinkedHashMap<>();
			for (Map.Entry<String, Stats> e : fSteps.entrySet()) {
				stepsMap.put(e.getKey(), e.getValue().toMap(false));
			}
			map.put("steps", stepsMap);
		}

		if (!fBy.isEmpty()) {
			Map<String, Object> byMap = new LinkedHashMap<>();
			for (Map.Entry<String, Map<String, Stats>> dim : fBy.entrySet()) {
				Map<String, Object> valueMap = new LinkedHashMap<>();
				for (Map.Entry<String, Stats> val : dim.getValue().entrySet()) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("count", val.getValue().getCount());
					entry.put("errors", val.getValue().getErrors());
					entry.put("elapsed", elapsedMap(val.getValue()));
					valueMap.put(val.getKey(), entry);
				}
				byMap.put(dim.getKey(), valueMap);
			}
			map.put("by", byMap);
		}

		return map;
	}

	private static Map<String, Object> elapsedMap(Stats s) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("min", s.getMin());
		map.put("max", s.getMax());
		map.put("sum", s.getSum());
		String hdr = HistogramCodec.encode(s.getHistogram());
		if (hdr != null) {
			map.put("hdr", hdr);
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	public static Bucket fromJsonMap(Map<String, Object> map) {
		String route = (String) map.get("route");
		Instant bucketStart = Instant.parse((String) map.get("bucket"));
		Interval interval = Interval.of((String) map.get("interval"));
		Bucket b = new Bucket(route, bucketStart, interval);

		Map<String, Object> elapsed = (Map<String, Object>) map.get("elapsed");
		mergeElapsedInto(b.fElapsed, elapsed, toLong(map.get("count")), toLong(map.get("errors")));

		Map<String, Object> steps = (Map<String, Object>) map.get("steps");
		if (steps != null) {
			for (Map.Entry<String, Object> e : steps.entrySet()) {
				b.fSteps.put(e.getKey(), Stats.fromMap((Map<String, Object>) e.getValue()));
			}
		}

		Map<String, Object> by = (Map<String, Object>) map.get("by");
		if (by != null) {
			for (Map.Entry<String, Object> dim : by.entrySet()) {
				Map<String, Object> values = (Map<String, Object>) dim.getValue();
				Map<String, Stats> target = new LinkedHashMap<>();
				for (Map.Entry<String, Object> val : values.entrySet()) {
					Map<String, Object> entry = (Map<String, Object>) val.getValue();
					Map<String, Object> elapsedEntry = (Map<String, Object>) entry.get("elapsed");
					Stats s = new Stats();
					mergeElapsedInto(s, elapsedEntry, toLong(entry.get("count")), toLong(entry.get("errors")));
					target.put(val.getKey(), s);
				}
				b.fBy.put(dim.getKey(), target);
			}
		}

		return b;
	}

	private static void mergeElapsedInto(Stats target, Map<String, Object> elapsed, long count, long errors) {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("count", count);
		source.put("errors", errors);
		if (elapsed != null) {
			source.put("min", elapsed.get("min"));
			source.put("max", elapsed.get("max"));
			source.put("sum", elapsed.get("sum"));
			source.put("hdr", elapsed.get("hdr"));
		}
		target.merge(Stats.fromMap(source));
	}

	private static long toLong(Object o) {
		if (o == null) {
			return 0L;
		}
		if (o instanceof Number) {
			return ((Number) o).longValue();
		}
		return Long.parseLong(o.toString());
	}
}

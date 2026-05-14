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

import java.util.List;
import java.util.Map;

import org.mintjams.rt.cms.internal.eip.stats.Bucket;

/**
 * Stateless helper that folds a single parsed exchange-history record into a
 * {@link Bucket}.
 *
 * <p>Used by both the live aggregator
 * ({@link HistogramAggregationStrategy}) and the offline rebuild
 * ({@code eip:rebuild}) so that scalar statistics, per-step timings and
 * per-dimension breakdowns are computed identically regardless of who builds
 * the bucket.
 */
public final class RecordAggregator {

	private RecordAggregator() {}

	/**
	 * Fold {@code record} into {@code bucket}. When {@code config} is non-null
	 * its {@link StatsConfig#dimensions()} drive the {@code bucket.by} breakdown.
	 */
	public static void apply(Bucket bucket, Map<String, Object> record, StatsConfig config) {
		if (record == null || record.isEmpty()) {
			return;
		}

		long elapsed = toLong(record.get("elapsed"));
		boolean error = !"completed".equals(record.get("status"));
		bucket.elapsed().record(elapsed, error);

		Object stepsObj = record.get("steps");
		if (stepsObj instanceof List) {
			for (Object o : (List<?>) stepsObj) {
				if (!(o instanceof Map)) {
					continue;
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> step = (Map<String, Object>) o;
				bucket.step(stepKey(step)).record(toLong(step.get("timeTaken")), false);
			}
		}

		if (config != null) {
			for (String dimSpec : config.dimensions()) {
				String value = extractDimensionValue(record, dimSpec);
				if (value == null || value.isEmpty()) {
					continue;
				}
				bucket.by(shortDimensionName(dimSpec), value).record(elapsed, error);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static String extractDimensionValue(Map<String, Object> record, String spec) {
		if (spec == null || spec.isEmpty()) {
			return null;
		}
		if (spec.startsWith("header.")) {
			String name = spec.substring("header.".length());
			Object headers = record.get("headers");
			if (!(headers instanceof Map)) {
				return null;
			}
			Object info = ((Map<String, Object>) headers).get(name);
			if (!(info instanceof Map)) {
				return null;
			}
			Object value = ((Map<String, Object>) info).get("value");
			if (value == null || value instanceof Map || value instanceof List) {
				return null;
			}
			return value.toString();
		}
		return null;
	}

	private static String shortDimensionName(String spec) {
		int dot = spec.indexOf('.');
		return dot < 0 ? spec : spec.substring(dot + 1);
	}

	private static String stepKey(Map<String, Object> step) {
		Object uri = step.get("endpointUri");
		String s = uri == null ? "unknown" : uri.toString();
		Object order = step.get("order");
		return (order instanceof Number) ? (order + "_" + s) : s;
	}

	private static long toLong(Object o) {
		if (o == null) {
			return 0L;
		}
		if (o instanceof Number) {
			return ((Number) o).longValue();
		}
		try {
			return Long.parseLong(o.toString());
		} catch (NumberFormatException ignore) {
			return 0L;
		}
	}
}

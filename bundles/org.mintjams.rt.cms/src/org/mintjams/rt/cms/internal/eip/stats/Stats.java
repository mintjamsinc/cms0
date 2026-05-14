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

import java.util.LinkedHashMap;
import java.util.Map;

import org.HdrHistogram.Histogram;

/**
 * Mergeable statistics for a single dimension at a single time bucket.
 *
 * <p>All scalar fields ({@code count}, {@code sum}, {@code min}, {@code max})
 * are linearly composable and can therefore be propagated to higher granularity
 * buckets without revisiting raw data. Percentiles are represented by an
 * {@link Histogram}, which is mergeable via {@link Histogram#add(Histogram)}.
 */
public class Stats {

	private long fCount;
	private long fErrors;
	private long fMin = Long.MAX_VALUE;
	private long fMax = Long.MIN_VALUE;
	private long fSum;
	private Histogram fHistogram;

	public Stats() {
		fHistogram = HistogramCodec.newHistogram();
	}

	public long getCount() {
		return fCount;
	}

	public long getErrors() {
		return fErrors;
	}

	public long getMin() {
		return fCount == 0 ? 0 : fMin;
	}

	public long getMax() {
		return fCount == 0 ? 0 : fMax;
	}

	public long getSum() {
		return fSum;
	}

	public Histogram getHistogram() {
		return fHistogram;
	}

	/**
	 * Record a single observation.
	 */
	public Stats record(long elapsedMillis, boolean error) {
		fCount++;
		if (error) {
			fErrors++;
		}
		fSum += elapsedMillis;
		if (elapsedMillis < fMin) {
			fMin = elapsedMillis;
		}
		if (elapsedMillis > fMax) {
			fMax = elapsedMillis;
		}
		long clamped = Math.max(1L, Math.min(elapsedMillis, HistogramCodec.MAX_TRACKABLE_VALUE));
		fHistogram.recordValue(clamped);
		return this;
	}

	/**
	 * Merge another {@link Stats} into this one.
	 */
	public Stats merge(Stats other) {
		if (other == null || other.fCount == 0) {
			return this;
		}
		fCount += other.fCount;
		fErrors += other.fErrors;
		fSum += other.fSum;
		if (other.fMin < fMin) {
			fMin = other.fMin;
		}
		if (other.fMax > fMax) {
			fMax = other.fMax;
		}
		fHistogram.add(other.fHistogram);
		return this;
	}

	public Map<String, Object> toMap(boolean includeErrors) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("count", fCount);
		if (includeErrors) {
			map.put("errors", fErrors);
		}
		map.put("min", getMin());
		map.put("max", getMax());
		map.put("sum", fSum);
		String hdr = HistogramCodec.encode(fHistogram);
		if (hdr != null) {
			map.put("hdr", hdr);
		}
		return map;
	}

	public static Stats fromMap(Map<String, Object> map) {
		Stats s = new Stats();
		if (map == null) {
			return s;
		}
		s.fCount = toLong(map.get("count"));
		s.fErrors = toLong(map.get("errors"));
		s.fSum = toLong(map.get("sum"));
		if (s.fCount > 0) {
			s.fMin = toLong(map.get("min"));
			s.fMax = toLong(map.get("max"));
		}
		Object hdr = map.get("hdr");
		if (hdr instanceof String && !((String) hdr).isEmpty()) {
			s.fHistogram = HistogramCodec.decode((String) hdr);
		}
		return s;
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

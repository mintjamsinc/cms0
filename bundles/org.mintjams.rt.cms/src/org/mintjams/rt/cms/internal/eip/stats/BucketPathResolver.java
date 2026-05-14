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
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Resolves bucket file paths under {@code /var/eip/stats}.
 *
 * <p>Layout:
 * <ul>
 *   <li>1min: {@code /var/eip/stats/{routeId}/1min/{Y}/{MM}/{DD}/{HH}/{mm}.json}</li>
 *   <li>5min: {@code /var/eip/stats/{routeId}/5min/{Y}/{MM}/{DD}/{HH}/{mm}.json}</li>
 *   <li>1h:   {@code /var/eip/stats/{routeId}/1h/{Y}/{MM}/{DD}/{HH}.json}</li>
 *   <li>1d:   {@code /var/eip/stats/{routeId}/1d/{Y}/{MM}/{DD}.json}</li>
 * </ul>
 */
public final class BucketPathResolver {

	public static final String BASE_PATH = "/var/eip/stats";

	private BucketPathResolver() {}

	public static String routeRoot(String routeId) {
		return BASE_PATH + "/" + routeId;
	}

	public static String intervalRoot(String routeId, Interval interval) {
		return routeRoot(routeId) + "/" + interval.label();
	}

	/**
	 * Path of the bucket file for the given (routeId, interval, bucketStart).
	 * {@code bucketStart} must already be aligned to the interval.
	 */
	public static String bucketPath(String routeId, Interval interval, Instant bucketStart) {
		LocalDateTime t = LocalDateTime.ofInstant(bucketStart, ZoneOffset.UTC);
		String prefix = intervalRoot(routeId, interval);
		switch (interval) {
			case ONE_MINUTE:
			case FIVE_MINUTES:
				return String.format("%s/%04d/%02d/%02d/%02d/%02d.json",
						prefix, t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute());
			case ONE_HOUR:
				return String.format("%s/%04d/%02d/%02d/%02d.json",
						prefix, t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour());
			case ONE_DAY:
				return String.format("%s/%04d/%02d/%02d.json",
						prefix, t.getYear(), t.getMonthValue(), t.getDayOfMonth());
			default:
				throw new IllegalStateException("Unhandled interval: " + interval);
		}
	}
}

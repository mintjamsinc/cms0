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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public enum Interval {
	ONE_MINUTE("1min", Duration.ofMinutes(1)),
	FIVE_MINUTES("5min", Duration.ofMinutes(5)),
	ONE_HOUR("1h", Duration.ofHours(1)),
	ONE_DAY("1d", Duration.ofDays(1));

	private final String fLabel;
	private final Duration fDuration;

	Interval(String label, Duration duration) {
		fLabel = label;
		fDuration = duration;
	}

	public String label() {
		return fLabel;
	}

	public Duration duration() {
		return fDuration;
	}

	public static Interval of(String label) {
		if (label == null) {
			throw new IllegalArgumentException("interval label is null");
		}
		for (Interval v : values()) {
			if (v.fLabel.equals(label)) {
				return v;
			}
		}
		throw new IllegalArgumentException("Unknown interval: " + label);
	}

	/**
	 * Truncate the instant to the start of the bucket that contains it.
	 */
	public Instant truncate(Instant instant) {
		LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
		switch (this) {
			case ONE_MINUTE:
				return ldt.withSecond(0).withNano(0).toInstant(ZoneOffset.UTC);
			case FIVE_MINUTES: {
				int m = (ldt.getMinute() / 5) * 5;
				return ldt.withMinute(m).withSecond(0).withNano(0).toInstant(ZoneOffset.UTC);
			}
			case ONE_HOUR:
				return ldt.withMinute(0).withSecond(0).withNano(0).toInstant(ZoneOffset.UTC);
			case ONE_DAY:
				return ldt.withHour(0).withMinute(0).withSecond(0).withNano(0).toInstant(ZoneOffset.UTC);
			default:
				throw new IllegalStateException("Unhandled interval: " + this);
		}
	}

	/**
	 * Return the start of the bucket that immediately precedes the bucket containing the given instant.
	 */
	public Instant previousBucketStart(Instant bucketStart) {
		return bucketStart.minus(fDuration);
	}

	/**
	 * Return the exclusive end of the bucket starting at bucketStart.
	 */
	public Instant endOf(Instant bucketStart) {
		return bucketStart.plus(fDuration);
	}
}

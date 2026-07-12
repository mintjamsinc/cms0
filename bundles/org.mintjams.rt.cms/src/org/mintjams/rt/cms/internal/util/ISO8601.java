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

package org.mintjams.rt.cms.internal.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * The single wire format for date-time values exchanged over the GraphQL API:
 * ISO 8601 in UTC with fixed millisecond precision, e.g.
 * {@code 2026-07-06T09:30:00.000Z}. All GraphQL output must be produced with
 * {@link #format} and all date-time input parsed with {@link #parseInstant} /
 * {@link #parseCalendar} so that precision and time zone handling stay
 * identical across resolvers.
 */
public final class ISO8601 {

	/**
	 * Millisecond precision is fixed at three digits so that equal instants
	 * always render as identical strings (Instant.toString() would emit 0, 3,
	 * 6 or 9 fractional digits depending on the value).
	 */
	public static final DateTimeFormatter FORMATTER =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);

	private ISO8601() {}

	public static String format(Instant instant) {
		return (instant == null) ? null : FORMATTER.format(instant);
	}

	public static String format(Date date) {
		return (date == null) ? null : FORMATTER.format(date.toInstant());
	}

	public static String format(Calendar calendar) {
		return (calendar == null) ? null : FORMATTER.format(calendar.toInstant());
	}

	public static String now() {
		return FORMATTER.format(Instant.now());
	}

	/**
	 * Parses an ISO 8601 date-time string. An explicit offset ({@code Z} or
	 * {@code ±hh:mm}) is required — zoneless strings are rejected rather than
	 * interpreted in some implicit time zone, so the resulting instant never
	 * depends on the server's default time zone.
	 *
	 * @throws java.time.format.DateTimeParseException if the string is not a
	 *         valid ISO 8601 date-time with an offset
	 */
	public static Instant parseInstant(String text) {
		return OffsetDateTime.parse(text).toInstant();
	}

	public static Calendar parseCalendar(String text) {
		Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		calendar.setTimeInMillis(parseInstant(text).toEpochMilli());
		return calendar;
	}
}

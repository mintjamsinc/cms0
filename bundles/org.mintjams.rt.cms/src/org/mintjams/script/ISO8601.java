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

package org.mintjams.script;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.TimeZone;

import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;

public class ISO8601 {

	private WorkspaceScriptContext fContext;
	private static final DateTimeFormatter fFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

	public ISO8601(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static ISO8601 get(ScriptingContext context) {
		return (ISO8601) context.getAttribute(ISO8601.class.getSimpleName());
	}

	public Instant parse(String value) throws IOException {
		if (value == null) {
			return null;
		}

		return OffsetDateTime.parse(value).toInstant();
	}

	public java.util.Date toDate(String value) throws IOException {
		if (value == null) {
			return null;
		}

		return java.util.Date.from(parse(value));
	}

	public Calendar toCalendar(String value) throws IOException {
		if (value == null) {
			return null;
		}

		Calendar v = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		v.setTimeInMillis(parse(value).toEpochMilli());
		return v;
	}

	public OffsetDateTime toOffsetDateTime(String value) throws IOException {
		if (value == null) {
			return null;
		}

		return parse(value).atOffset(ZoneOffset.UTC);
	}

	public String format(Object value) throws IOException {
		if (value == null) {
			return null;
		}

		return format(value, ZoneOffset.UTC);
	}

	public String format(Object value, ZoneId zone) throws IOException {
		if (value == null) {
			return null;
		}

		DateTimeFormatter formatter = fFormatter.withZone(zone);

		if (value instanceof java.util.Date v) {
			return formatter.format(v.toInstant());
		}

		if (value instanceof java.util.Calendar v) {
			return formatter.format(v.toInstant());
		}

		if (value instanceof Number v) {
			Instant instant = (v.longValue() == 0) ? Instant.EPOCH : Instant.ofEpochMilli(v.longValue());
			return formatter.format(instant);
		}

		if (value instanceof Instant v) {
			return formatter.format(v);
		}

		if (value instanceof OffsetDateTime v) {
			return formatter.format(v);
		}

		if (value instanceof ZonedDateTime v) {
			return formatter.format(v);
		}

		throw new IllegalArgumentException("Unsupported value type: " + value.getClass().getName());
	}

}

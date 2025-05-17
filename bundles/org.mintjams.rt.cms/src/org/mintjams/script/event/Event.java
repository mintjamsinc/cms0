/*
 * Copyright (c) 2025 MintJams Inc.
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

package org.mintjams.script.event;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

public class Event {

	private final org.osgi.service.event.Event fEvent;
	private final Map<String, Object> fProperties;
	private final int fHashCode;

	protected Event(org.osgi.service.event.Event event) {
		fEvent = event;
		fProperties = Collections.unmodifiableMap(copyProperties(event));
		fHashCode = computeHashCode();
	}

	private Map<String, Object> copyProperties(org.osgi.service.event.Event event) {
		Map<String, Object> props = new HashMap<>();
		String[] keys = event.getPropertyNames();
		for (String key : keys) {
			props.put(key, event.getProperty(key));
		}
		return props;
	}

	private int computeHashCode() {
		return Objects.hash(fEvent.getTopic(), fProperties);
	}

	public String getTopic() {
		return fEvent.getTopic();
	}

	public Map<String, Object> getProperties() {
		return fProperties;
	}

	public Object getProperty(String name) {
		return fProperties.get(name);
	}

	public String getPropertyAsString(String name) {
		Object val = fProperties.get(name);
		return (val != null) ? val.toString() : null;
	}

	public String[] getPropertyAsStringArray(String name) {
		Object val = fProperties.get(name);
		if (val == null) {
			return null;
		} else if (val instanceof String[]) {
			return (String[]) val;
		} else if (val instanceof Collection) {
			return ((Collection<?>) val).stream().map(String::valueOf).toArray(String[]::new);
		} else if (val instanceof Object[]) {
			return Arrays.stream((Object[]) val).map(String::valueOf).toArray(String[]::new);
		} else {
			return new String[] { val.toString() };
		}
	}

	public boolean containsProperty(String name) {
		return fProperties.containsKey(name);
	}

	public String[] getPropertyNames() {
		return fProperties.keySet().stream().sorted().toArray(String[]::new);
	}

	public boolean matches(String filterString) {
		try {
			return fEvent.matches(FrameworkUtil.createFilter(filterString));
		} catch (InvalidSyntaxException e) {
			throw new IllegalArgumentException("Invalid filter syntax: " + filterString, e);
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof Event)) {
			return false;
		}
		return (hashCode() == obj.hashCode());
	}

	@Override
	public int hashCode() {
		return fHashCode;
	}

	@Override
	public String toString() {
		return getClass().getName() + " [topic=" + fEvent.getTopic() + "] " + fProperties.toString();
	}

}

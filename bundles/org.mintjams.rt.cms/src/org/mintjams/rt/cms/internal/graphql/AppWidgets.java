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

package org.mintjams.rt.cms.internal.graphql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the {@code widgets} map of a Webtop {@code app.yml} into the
 * {@code AppWidget} list of the {@code App} type. Shared by
 * {@link QueryExecutor} and the platform wiring, which both build App nodes.
 *
 * <pre>
 * widgets:
 *   note:
 *     title: Sticky Note
 *     entry: widget.html
 *     width: 240
 *     height: 220
 *     resizable: true
 *     minWidth: 160
 *     minHeight: 120
 *     multiple: true
 *     layer: desktop
 * </pre>
 *
 * An entry without a usable {@code entry} page or size is dropped, so a
 * malformed descriptor never hands the shell a widget it cannot place.
 */
public final class AppWidgets {

	/** A page inside the app directory: relative segments only, ending in .html. */
	private static final Pattern ENTRY_PATTERN = Pattern.compile("([A-Za-z0-9_-][A-Za-z0-9._-]*/)*[A-Za-z0-9_-][A-Za-z0-9._-]*\\.html");
	private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
	private static final int MAX_SIZE = 1200;

	private AppWidgets() {}

	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> fromDescriptor(Object widgetsObj) {
		List<Map<String, Object>> widgets = new ArrayList<>();
		if (!(widgetsObj instanceof Map)) {
			return widgets;
		}
		for (Map.Entry<String, Object> entry : ((Map<String, Object>) widgetsObj).entrySet()) {
			if (!(entry.getValue() instanceof Map)) {
				continue;
			}
			String identifier = entry.getKey();
			if (identifier == null || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
				continue;
			}
			Map<String, Object> value = (Map<String, Object>) entry.getValue();
			Object entryObj = value.get("entry");
			if (!(entryObj instanceof String) || !ENTRY_PATTERN.matcher((String) entryObj).matches()
					|| ((String) entryObj).contains("..")) {
				continue;
			}
			Integer width = asSize(value.get("width"));
			Integer height = asSize(value.get("height"));
			if (width == null || height == null) {
				continue;
			}

			Map<String, Object> widget = new LinkedHashMap<>();
			widget.put("identifier", identifier);
			widget.put("title", value.get("title") instanceof String ? value.get("title") : null);
			widget.put("entry", entryObj);
			widget.put("width", width);
			widget.put("height", height);
			widget.put("minWidth", asSize(value.get("minWidth")));
			widget.put("minHeight", asSize(value.get("minHeight")));
			widget.put("resizable", asBoolean(value.get("resizable")));
			widget.put("multiple", asBoolean(value.get("multiple")));
			widget.put("layer", "pinned".equals(value.get("layer")) ? "pinned" : "desktop");
			widgets.add(widget);
		}
		return widgets;
	}

	private static Integer asSize(Object value) {
		int size;
		if (value instanceof Number) {
			size = ((Number) value).intValue();
		} else if (value instanceof String) {
			try {
				size = Integer.parseInt(((String) value).trim());
			} catch (NumberFormatException ex) {
				return null;
			}
		} else {
			return null;
		}
		if (size <= 0) {
			return null;
		}
		return Math.min(size, MAX_SIZE);
	}

	private static boolean asBoolean(Object value) {
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof String) {
			return Boolean.parseBoolean((String) value);
		}
		return false;
	}

}

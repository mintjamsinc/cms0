/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.script.metadata;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.PropertyType;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.script.resource.Property;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;

/**
 * A handle bound to a single node ({@link Resource}) and a metadata schema.
 *
 * <p>It evaluates a property's display-format, validation or calculated-value
 * script through the same unified {@code ctx} the browser uses, so a single
 * schema definition drives both the in-browser inspector and server-side
 * rendering. Obtain one via {@link MetadataAPI#of(Resource, String)}.
 *
 * <p>The node is serialised into a JSON-friendly snapshot lazily and only once;
 * {@code ctx.item} exposes the node's own attributes (name/path/mimeType/...)
 * while {@code ctx.get/getString/getNumber/getValues} read its sibling
 * properties.
 */
public class MetadataItem {

	private final MetadataAPI fAPI;
	private final Resource fResource;
	private final Map<String, Object> fSchema;

	// Lazily built JSON-friendly snapshots shared by every evaluation.
	private Map<String, Object> fItem;
	private Map<String, Object> fAllProps;

	MetadataItem(MetadataAPI api, Resource resource, Map<String, Object> schema) {
		fAPI = api;
		fResource = resource;
		fSchema = schema;
	}

	/**
	 * Returns the formatted display value for {@code key}. When the property
	 * defines no display format, the raw stored value is returned. The result
	 * is normally a string, but a script may return a structured object.
	 */
	public Object getDisplayText(String key) throws ResourceException {
		ensureLoaded();
		Map<String, Object> entry = entryOf(key);
		String script = displayFormatOf(findProperty(key));
		if (isBlank(script)) {
			Object raw = (entry != null) ? entry.get("value") : null;
			return (raw == null) ? "" : raw;
		}
		Map<String, Object> result = run(key, entry, script);
		if (result == null) {
			return "";
		}
		Object value = result.get("value");
		return (value == null) ? "" : value;
	}

	/**
	 * Validates the current value of {@code key} against the property's
	 * validation script. Returns a map of the shape
	 * {@code {valid: boolean, errors?: [...]}}; a property without a validation
	 * script (or whose script does not return a result) is treated as valid.
	 */
	public Map<String, Object> validate(String key) throws ResourceException {
		String script = validationOf(findProperty(key));
		if (isBlank(script)) {
			return valid();
		}
		ensureLoaded();
		Map<String, Object> result = run(key, entryOf(key), script);
		if (result != null && result.get("value") instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> value = (Map<String, Object>) result.get("value");
			if (value.containsKey("valid")) {
				return value;
			}
		}
		return valid();
	}

	/**
	 * Evaluates the property's calculated formula. As on the client, the
	 * property's own value is not yet established when a default is computed, so
	 * {@code ctx.value} is null; cross-property access via {@code ctx.get(...)}
	 * and {@code ctx.item} remain available. Returns the computed string or null.
	 */
	public Object calculate(String key) throws ResourceException {
		String script = formulaOf(findProperty(key));
		if (isBlank(script)) {
			return null;
		}
		ensureLoaded();
		Map<String, Object> result = run(key, null, script);
		if (result == null) {
			return null;
		}
		Object value = result.get("value");
		return (value == null) ? null : String.valueOf(value);
	}

	/** Returns the raw stored (scalar) value of {@code key}, or null. */
	public Object getValue(String key) throws ResourceException {
		ensureLoaded();
		Map<String, Object> entry = entryOf(key);
		return (entry != null) ? entry.get("value") : null;
	}

	// ────────────────────────────────────────────────────────────────────
	// Evaluation
	// ────────────────────────────────────────────────────────────────────

	// Runs the unified ctx runtime for one property. When entry is null (the
	// calculated-default case) ctx.value/values/isArray default to empty.
	private Map<String, Object> run(String key, Map<String, Object> entry, String script) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("item", fItem);
		params.put("propertyName", key);
		params.put("value", (entry != null) ? entry.get("value") : null);
		Object values = (entry != null) ? entry.get("values") : null;
		params.put("values", (values != null) ? values : new ArrayList<>());
		params.put("isArray", (entry != null) && Boolean.TRUE.equals(entry.get("isArray")));
		params.put("allProps", fAllProps);
		params.put("script", script);

		Map<String, Object> result = fAPI.evaluate(CmsService.toJSON(params));
		if (result != null && result.containsKey("error")) {
			return null;
		}
		return result;
	}

	// ────────────────────────────────────────────────────────────────────
	// Node serialisation (Java; no Groovy wrapper required)
	// ────────────────────────────────────────────────────────────────────

	private void ensureLoaded() throws ResourceException {
		if (fAllProps != null) {
			return;
		}
		fItem = buildItem();
		fAllProps = buildAllProps();
	}

	private Map<String, Object> buildItem() throws ResourceException {
		boolean collection = fResource.isCollection();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", quietly(fResource::getIdentifier));
		m.put("name", nz(fResource.getName()));
		m.put("path", nz(fResource.getPath()));
		m.put("isCollection", collection);
		m.put("mimeType", collection ? "" : nz(quietly(fResource::getContentType)));
		m.put("contentLength", collection ? 0L : quietlyLong(fResource::getContentLength));
		m.put("encoding", collection ? "" : nz(quietly(fResource::getContentEncoding)));
		m.put("created", iso(quietlyDate(fResource::getCreated)));
		m.put("createdBy", nz(quietly(fResource::getCreatedBy)));
		m.put("lastModified", iso(quietlyDate(fResource::getLastModified)));
		m.put("lastModifiedBy", nz(quietly(fResource::getLastModifiedBy)));
		m.put("isLocked", Boolean.TRUE.equals(quietlyBoolean(fResource::isLocked)));
		m.put("baseVersionName", baseVersionName());
		return m;
	}

	private Map<String, Object> buildAllProps() throws ResourceException {
		Map<String, Object> all = new LinkedHashMap<>();
		for (Resource.PropertyIterator it = fResource.getProperties(); it.hasNext();) {
			Property p = it.next();
			String name;
			try {
				name = p.getName();
			} catch (Throwable ex) {
				continue;
			}
			if (name.startsWith("jcr:") || name.startsWith("rep:") || name.startsWith("oak:")) {
				continue;
			}

			boolean multiple;
			List<Object> values;
			try {
				multiple = p.isMultiple();
				values = extractValues(p);
			} catch (Throwable ex) {
				continue;
			}

			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("isArray", multiple);
			entry.put("values", values);
			entry.put("value", values.isEmpty() ? null : values.get(0));
			all.put(name, entry);
		}
		return all;
	}

	private List<Object> extractValues(Property p) throws ResourceException {
		List<Object> out = new ArrayList<>();
		int type = p.getType();
		try {
			switch (type) {
			case PropertyType.BINARY:
				break;
			case PropertyType.LONG:
				for (Long v : p.getValue(Long[].class)) {
					out.add(v);
				}
				break;
			case PropertyType.DOUBLE:
				for (Double v : p.getValue(Double[].class)) {
					out.add(v);
				}
				break;
			case PropertyType.DECIMAL:
				for (BigDecimal v : p.getValue(BigDecimal[].class)) {
					out.add(v);
				}
				break;
			case PropertyType.BOOLEAN:
				for (Boolean v : p.getValue(Boolean[].class)) {
					out.add(v);
				}
				break;
			case PropertyType.DATE:
				for (java.util.Date v : p.getValue(java.util.Date[].class)) {
					out.add(iso(v));
				}
				break;
			case PropertyType.REFERENCE:
			case PropertyType.WEAKREFERENCE:
				// Reference values are exposed as their target identifiers; a
				// script that needs the referenced node resolves it separately.
				for (String v : p.getValue(String[].class)) {
					out.add(v);
				}
				break;
			default:
				for (String v : p.getValue(String[].class)) {
					out.add(v);
				}
				break;
			}
		} catch (Throwable ex) {
			out.clear();
			try {
				for (String v : p.getValue(String[].class)) {
					out.add(v);
				}
			} catch (Throwable ignore) {
				// give up on this property's values
			}
		}
		return out;
	}

	private String baseVersionName() {
		try {
			if (!fResource.isCollection() && fResource.isVersionControlled()) {
				return fResource.getBaseVersion().getName();
			}
		} catch (Throwable ignore) {
			// not versionable / unavailable
		}
		return null;
	}

	// ────────────────────────────────────────────────────────────────────
	// Schema lookup
	// ────────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private Map<String, Object> findProperty(String key) {
		if (fSchema == null) {
			return null;
		}
		Object props = fSchema.get("properties");
		if (!(props instanceof List)) {
			return null;
		}
		for (Object o : (List<Object>) props) {
			if (o instanceof Map) {
				Map<String, Object> p = (Map<String, Object>) o;
				if (key.equals(p.get("key"))) {
					return p;
				}
			}
		}
		return null;
	}

	private static String displayFormatOf(Map<String, Object> prop) {
		Map<String, Object> uiHint = mapOf(prop, "uiHint");
		return (uiHint != null) ? strOf(uiHint.get("displayFormat")) : null;
	}

	private static String validationOf(Map<String, Object> prop) {
		Map<String, Object> uiHint = mapOf(prop, "uiHint");
		return (uiHint != null) ? strOf(uiHint.get("validation")) : null;
	}

	private static String formulaOf(Map<String, Object> prop) {
		Map<String, Object> behavior = mapOf(prop, "behavior");
		if (behavior == null) {
			return null;
		}
		Map<String, Object> calculated = asMap(behavior.get("calculated"));
		if (calculated != null && Boolean.TRUE.equals(calculated.get("enabled"))) {
			String formula = strOf(calculated.get("formula"));
			if (!isBlank(formula)) {
				return formula;
			}
		}
		Map<String, Object> defaultValue = asMap(behavior.get("defaultValue"));
		if (defaultValue != null && "CALCULATED".equals(strOf(defaultValue.get("type")))) {
			return strOf(defaultValue.get("value"));
		}
		return null;
	}

	// ────────────────────────────────────────────────────────────────────
	// Helpers
	// ────────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private Map<String, Object> entryOf(String key) {
		Object e = (fAllProps != null) ? fAllProps.get(key) : null;
		return (e instanceof Map) ? (Map<String, Object>) e : null;
	}

	private static Map<String, Object> valid() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("valid", Boolean.TRUE);
		return m;
	}

	private static Map<String, Object> mapOf(Map<String, Object> parent, String key) {
		return (parent != null) ? asMap(parent.get(key)) : null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return (o instanceof Map) ? (Map<String, Object>) o : null;
	}

	private static String strOf(Object o) {
		return (o == null) ? null : o.toString();
	}

	private static boolean isBlank(String s) {
		return (s == null) || s.trim().isEmpty();
	}

	private static String nz(String s) {
		return (s == null) ? "" : s;
	}

	private static String iso(java.util.Date date) {
		return (date == null) ? null : DateTimeFormatter.ISO_INSTANT.format(date.toInstant());
	}

	// Functional helpers that swallow ResourceException for best-effort fields.

	private interface ResourceSupplier<T> {
		T get() throws ResourceException;
	}

	private static String quietly(ResourceSupplier<String> s) {
		try {
			return s.get();
		} catch (Throwable ex) {
			return null;
		}
	}

	private static long quietlyLong(ResourceSupplier<Long> s) {
		try {
			Long v = s.get();
			return (v != null) ? v : 0L;
		} catch (Throwable ex) {
			return 0L;
		}
	}

	private static Boolean quietlyBoolean(ResourceSupplier<Boolean> s) {
		try {
			return s.get();
		} catch (Throwable ex) {
			return null;
		}
	}

	private static java.util.Date quietlyDate(ResourceSupplier<java.util.Date> s) {
		try {
			return s.get();
		} catch (Throwable ex) {
			return null;
		}
	}

}

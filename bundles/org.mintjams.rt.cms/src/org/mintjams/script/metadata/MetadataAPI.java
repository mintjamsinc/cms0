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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

/**
 * Server-side counterpart of the webtop metadata-schema scripting.
 *
 * <p>Metadata schemas are authored in the schema-manager and stored as JSON
 * under {@code /etc/metadata/schemas/<schemaKey>.json}. Each property may carry
 * three ECMAScript hooks — a display format, a validation script and a
 * calculated value — that the browser evaluates through the unified {@code ctx}
 * object (see {@code webtop/src/webtop/components/wt-inspector.ts},
 * {@code buildScriptContext}). This API reproduces the exact same {@code ctx}
 * server-side so the identical scripts can drive server-side rendering (e.g.
 * blog / commerce GSP templates).
 *
 * <p>Templates obtain a handle bound to a node and a schema, then read the
 * formatted, validated or calculated values:
 * <pre>
 *   def meta = MetadataAPI.of(resource, "blogArticle");
 *   def title = meta.getDisplayText("ogp_title");
 * </pre>
 *
 * <p>The unified {@code ctx} runtime lives in this bundle (see
 * {@link #RUNTIME_SCRIPT}) rather than in editable content, so it is versioned
 * together with the client implementation.
 */
public class MetadataAPI implements Adaptable {

	static final String SCHEMAS_PATH = "/etc/metadata/schemas";
	static final String MIXINS_PATH = "/etc/metadata/mixins";

	// Synthetic identity used to cache the compiled runtime in the native
	// ECMAScript engine. The body is fixed, so a constant timestamp is fine.
	private static final String RUNTIME_SCRIPT_NAME = "metadata://schema-runtime.njs";
	private static final java.util.Date RUNTIME_LAST_MODIFIED = new java.util.Date(0L);

	// Builds the unified ctx (mirroring the client buildScriptContext) and runs
	// one property script against it. Input is supplied as the JSON string
	// `parametersJson`; the result is returned as a JSON string of the form
	// {"value": <result>} or {"error": <message>}.
	//
	// ctx.formatDate(date, optionsOrPattern?) — `optionsOrPattern` is either a
	//   legacy pattern string ("YYYY/MM/DD HH:mm") or an options object
	//   { pattern?, locale?, timeZone? }. With an explicit time zone the
	//   Y/M/D/H/m/s tokens (and the locale-default form when no pattern is
	//   given) are resolved in that zone via Intl.DateTimeFormat. The server
	//   has no user Preferences; when an option is omitted the runtime falls
	//   back to the locale / time zone configured on the MetadataItem via
	//   setLocale / setTimeZone (typically derived in a GSP template from
	//   HttpServletRequest), and to the JVM defaults when those are unset —
	//   so the same schema-defined script runs on browser and server.
	// ctx.formatCurrency(amount, currencyCodeOrOptions?) — `currencyCodeOrOptions`
	//   is either a legacy currency code string ("JPY") or an options object
	//   { currency?, locale? }. Locale falls back to the item's locale (or the
	//   JVM default) when not supplied, mirroring the client.
	private static final String RUNTIME_SCRIPT = """
			(function() {
				var p = JSON.parse(parametersJson);
				var allProps = p.allProps || {};
				// Item-level fallbacks for the format helpers; the Java side
				// pre-populates these with the JVM defaults when the GSP
				// template didn't call setLocale / setTimeZone, so the script
				// always sees a usable value here.
				var effectiveLocale = p.effectiveLocale || null;
				var effectiveTimeZone = p.effectiveTimeZone || null;
				function entryOf(name) {
					return Object.prototype.hasOwnProperty.call(allProps, name) ? allProps[name] : null;
				}
				var ctx = {
					item: p.item || null,
					propertyName: p.propertyName,
					value: (p.value === undefined ? null : p.value),
					values: p.values || [],
					isArray: !!p.isArray,
					get: function(name, dflt) {
						var e = entryOf(name);
						if (!e || e.value == null) { return (dflt === undefined ? null : dflt); }
						return e.value;
					},
					getString: function(name, dflt) {
						var e = entryOf(name);
						if (!e || e.value == null) { return (dflt === undefined ? '' : dflt); }
						return String(e.value);
					},
					getNumber: function(name, dflt) {
						var e = entryOf(name);
						if (!e || e.value == null) { return (dflt === undefined ? 0 : dflt); }
						var n = Number(e.value);
						return isNaN(n) ? (dflt === undefined ? 0 : dflt) : n;
					},
					getValues: function(name, dflt) {
						var e = entryOf(name);
						if (!e || !e.values) { return (dflt === undefined ? [] : dflt); }
						return e.values;
					},
					formatCurrency: function(amount, currencyCodeOrOptions) {
						var currencyCode = null;
						var locale = null;
						if (typeof currencyCodeOrOptions === 'string') {
							currencyCode = currencyCodeOrOptions;
						} else if (currencyCodeOrOptions && typeof currencyCodeOrOptions === 'object') {
							currencyCode = currencyCodeOrOptions.currency || null;
							locale = currencyCodeOrOptions.locale || null;
						}
						if (locale == null) { locale = effectiveLocale; }
						try {
							return new Intl.NumberFormat(locale || undefined, { style: 'currency', currency: currencyCode || 'USD' }).format(amount);
						} catch (e) {
							return String(amount);
						}
					},
					formatDate: function(date, optionsOrPattern) {
						try {
							var d = (date instanceof Date) ? date : new Date(date);
							if (isNaN(d.getTime())) { return String(date); }
							var pattern = null, locale = null, timeZone = null;
							if (typeof optionsOrPattern === 'string') {
								pattern = optionsOrPattern;
							} else if (optionsOrPattern && typeof optionsOrPattern === 'object') {
								pattern = optionsOrPattern.pattern || null;
								locale = optionsOrPattern.locale || null;
								timeZone = optionsOrPattern.timeZone || null;
							}
							if (locale == null) { locale = effectiveLocale; }
							if (timeZone == null) { timeZone = effectiveTimeZone; }
							if (!pattern) {
								var opts = {};
								if (timeZone) { opts.timeZone = timeZone; }
								return d.toLocaleString(locale || undefined, opts);
							}
							if (timeZone || locale) {
								// Derive Y/M/D/H/m/s in the requested zone via Intl.
								var dtfOpts = {
									hourCycle: 'h23',
									year: 'numeric', month: '2-digit', day: '2-digit',
									hour: '2-digit', minute: '2-digit', second: '2-digit'
								};
								if (timeZone) { dtfOpts.timeZone = timeZone; }
								var dtf = new Intl.DateTimeFormat('en-US', dtfOpts);
								var map = {};
								var parts = dtf.formatToParts(d);
								for (var i = 0; i < parts.length; i++) {
									if (parts[i].type !== 'literal') { map[parts[i].type] = parts[i].value; }
								}
								return pattern
									.replace('YYYY', map.year)
									.replace('MM', map.month)
									.replace('DD', map.day)
									.replace('HH', map.hour === '24' ? '00' : map.hour)
									.replace('mm', map.minute)
									.replace('ss', map.second);
							}
							var pad = function(n) { n = String(n); return (n.length < 2) ? ('0' + n) : n; };
							return pattern
								.replace('YYYY', String(d.getFullYear()))
								.replace('MM', pad(d.getMonth() + 1))
								.replace('DD', pad(d.getDate()))
								.replace('HH', pad(d.getHours()))
								.replace('mm', pad(d.getMinutes()))
								.replace('ss', pad(d.getSeconds()));
						} catch (e) {
							return String(date);
						}
					}
				};
				try {
					var fn = new Function('ctx', p.script);
					var result = fn(ctx);
					return JSON.stringify({ value: (result === undefined ? null : result) });
				} catch (ex) {
					return JSON.stringify({ error: String(ex) });
				}
			})();
			""";

	private final WorkspaceScriptContext fContext;
	// Per-context (per-request) cache of parsed schemas. A null entry records a
	// schema that could not be resolved, so we do not re-read it repeatedly.
	private final Map<String, Map<String, Object>> fSchemaCache = new HashMap<>();
	private final Map<String, Map<String, Object>> fMixinCache = new HashMap<>();

	public MetadataAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public MetadataItem of(Resource resource, String schemaKey) throws ResourceException {
		return new MetadataItem(this, resource, getSchema(schemaKey));
	}

	public MetadataItem of(String path, String schemaKey) throws ResourceException {
		return of(fContext.getResourceResolver().getResource(path), schemaKey);
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> getSchema(String schemaKey) throws ResourceException {
		if (fSchemaCache.containsKey(schemaKey)) {
			return fSchemaCache.get(schemaKey);
		}

		Map<String, Object> schema = null;
		try {
			Resource r = fContext.getResourceResolver().getResource(SCHEMAS_PATH + "/" + schemaKey + ".json");
			if (r.exists() && r.canRead()) {
				schema = CmsService.fromJSON(r.getContent(), Map.class);
				schema = resolveMixins(schema);
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Failed to load metadata schema: " + schemaKey, ex);
		}
		fSchemaCache.put(schemaKey, schema);
		return schema;
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> getMixin(String mixinKey) {
		if (fMixinCache.containsKey(mixinKey)) {
			return fMixinCache.get(mixinKey);
		}

		Map<String, Object> mixin = null;
		try {
			Resource r = fContext.getResourceResolver().getResource(MIXINS_PATH + "/" + mixinKey + ".json");
			if (r.exists() && r.canRead()) {
				mixin = CmsService.fromJSON(r.getContent(), Map.class);
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Failed to load metadata mixin: " + mixinKey, ex);
		}
		fMixinCache.put(mixinKey, mixin);
		return mixin;
	}

	// Expands `schema.mixins[]` into `schema.properties` so downstream consumers
	// (MetadataItem.findProperty et al.) keep seeing a flat property list. Schema
	// own properties fully override mixin properties on key collision. Mixins
	// must not themselves declare mixins (Phase 1: nesting is forbidden — any
	// `mixins` field on a mixin is ignored). The `mixins` field is stripped from
	// the returned schema so consumers never inspect it.
	@SuppressWarnings("unchecked")
	private Map<String, Object> resolveMixins(Map<String, Object> schema) {
		if (schema == null) {
			return null;
		}
		Object mixinsObj = schema.get("mixins");
		if (!(mixinsObj instanceof List) || ((List<Object>) mixinsObj).isEmpty()) {
			schema.remove("mixins");
			return schema;
		}

		List<Map<String, Object>> resolved = new ArrayList<>();
		Map<String, Integer> indexByKey = new HashMap<>();

		for (Object mxKeyObj : (List<Object>) mixinsObj) {
			if (!(mxKeyObj instanceof String)) {
				continue;
			}
			String mxKey = (String) mxKeyObj;
			Map<String, Object> mx = getMixin(mxKey);
			if (mx == null) {
				CmsService.getLogger(getClass()).warn("Metadata mixin not found: " + mxKey
						+ " (referenced by schema: " + schema.get("key") + ")");
				continue;
			}
			Object mxProps = mx.get("properties");
			if (!(mxProps instanceof List)) {
				continue;
			}
			for (Object p : (List<Object>) mxProps) {
				if (!(p instanceof Map)) {
					continue;
				}
				Map<String, Object> prop = (Map<String, Object>) p;
				Object keyObj = prop.get("key");
				if (!(keyObj instanceof String)) {
					continue;
				}
				String key = (String) keyObj;
				if (indexByKey.containsKey(key)) {
					CmsService.getLogger(getClass()).warn("Metadata mixin property collision: '" + key
							+ "' from mixin '" + mxKey + "' is shadowed by an earlier mixin"
							+ " (schema: " + schema.get("key") + ")");
					continue;
				}
				indexByKey.put(key, resolved.size());
				resolved.add(prop);
			}
		}

		Object ownProps = schema.get("properties");
		if (ownProps instanceof List) {
			for (Object p : (List<Object>) ownProps) {
				if (!(p instanceof Map)) {
					continue;
				}
				Map<String, Object> prop = (Map<String, Object>) p;
				Object keyObj = prop.get("key");
				if (!(keyObj instanceof String)) {
					continue;
				}
				String key = (String) keyObj;
				Integer idx = indexByKey.get(key);
				if (idx != null) {
					resolved.set(idx, prop);
				} else {
					indexByKey.put(key, resolved.size());
					resolved.add(prop);
				}
			}
		}

		schema.put("properties", resolved);
		schema.remove("mixins");
		return schema;
	}

	// Runs the unified ctx runtime with the given parameters and returns the
	// parsed result map ({"value": ...} or {"error": ...}), or null on failure.
	@SuppressWarnings("unchecked")
	Map<String, Object> evaluate(String parametersJson) {
		fContext.setAttribute("parametersJson", parametersJson);
		try (ScriptReader reader = new ScriptReader(new StringReader(RUNTIME_SCRIPT))) {
			Object resultJson = reader
					.setScriptName(RUNTIME_SCRIPT_NAME)
					.setExtension("njs")
					.setLastModified(RUNTIME_LAST_MODIFIED)
					.setScriptEngineManager(Scripts.getScriptEngineManager(fContext))
					.setClassLoader(Scripts.getClassLoader(fContext))
					.setScriptContext(fContext)
					.eval();
			if (resultJson == null) {
				return null;
			}
			return CmsService.fromJSON(resultJson.toString(), Map.class);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Failed to evaluate metadata schema script.", ex);
			return null;
		} finally {
			fContext.removeAttribute("parametersJson");
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fContext, adapterType);
	}

}

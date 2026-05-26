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
import java.util.HashMap;
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

	// Synthetic identity used to cache the compiled runtime in the native
	// ECMAScript engine. The body is fixed, so a constant timestamp is fine.
	private static final String RUNTIME_SCRIPT_NAME = "metadata://schema-runtime.njs";
	private static final java.util.Date RUNTIME_LAST_MODIFIED = new java.util.Date(0L);

	// Builds the unified ctx (mirroring the client buildScriptContext) and runs
	// one property script against it. Input is supplied as the JSON string
	// `parametersJson`; the result is returned as a JSON string of the form
	// {"value": <result>} or {"error": <message>}.
	private static final String RUNTIME_SCRIPT = """
			(function() {
				var p = JSON.parse(parametersJson);
				var allProps = p.allProps || {};
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
					formatCurrency: function(amount, currencyCode) {
						try {
							return new Intl.NumberFormat(undefined, { style: 'currency', currency: currencyCode || 'USD' }).format(amount);
						} catch (e) {
							return String(amount);
						}
					},
					formatDate: function(date, pattern) {
						try {
							var d = (date instanceof Date) ? date : new Date(date);
							if (isNaN(d.getTime())) { return String(date); }
							if (!pattern) { return d.toLocaleString(); }
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
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Failed to load metadata schema: " + schemaKey, ex);
		}
		fSchemaCache.put(schemaKey, schema);
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

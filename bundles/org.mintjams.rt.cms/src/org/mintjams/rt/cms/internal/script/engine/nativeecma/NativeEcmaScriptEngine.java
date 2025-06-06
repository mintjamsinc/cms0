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

package org.mintjams.rt.cms.internal.script.engine.nativeecma;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;

import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.engine.AbstractScriptEngine;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

import com.google.gson.Gson;

public class NativeEcmaScriptEngine extends AbstractScriptEngine {

	private static final String NO_SCRIPT_NAME = "NO_SCRIPT_NAME";

	private Map<String, ScriptCacheEntry> fCache;

	public NativeEcmaScriptEngine(ScriptEngineFactory scriptEngineFactory) {
		super(scriptEngineFactory);
		fCache = new WeakHashMap<>();
	}

	@Override
	public Object eval(Reader reader, ScriptContext ctx) throws ScriptException {
		CompiledScript compiledScript = null;

		if (reader instanceof ScriptReader) {
			try {
				ScriptReader scriptReader = (ScriptReader) reader;
				ScriptCacheEntry e = fCache.get(scriptReader.getScriptName());
				if (e != null) {
					if (e.getLastModified() == scriptReader.getLastModified().getTime()) {
						compiledScript = e.getCompiledScript();
					}
				}
			} catch (Throwable ignore) {}
		}

		if (compiledScript == null) {
			try {
				compiledScript = new CompiledScriptImpl(getPreparedReader(reader));

				if (reader instanceof ScriptReader) {
					ScriptReader scriptReader = (ScriptReader) reader;
					ScriptCacheEntry e = new ScriptCacheEntry(compiledScript, scriptReader.getScriptName(), scriptReader.getLastModified().getTime());
					fCache.put(e.getFilename(), e);
				}
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ScriptException.class, "Unable to compile ECMA script: " + ex.getMessage());
			}
		}

		return compiledScript.eval(ctx);
	}

	protected Reader getPreparedReader(Reader scriptReader) throws ScriptException {
		return scriptReader;
	}

	private String getScriptName(Reader scriptReader) {
		if (scriptReader instanceof ScriptReader) {
			return ((ScriptReader) scriptReader).getScriptName();
		}
		return NO_SCRIPT_NAME;
	}

	private class CompiledScriptImpl extends CompiledScript {
		private final String fScript;
		private final String fScriptName;

		private CompiledScriptImpl(Reader scriptReader) throws ScriptException, IOException {
			fScriptName = getScriptName(scriptReader);
			fScript = Strings.readAll(scriptReader);
		}

		private String getVariablesSource(ScriptContext scriptContext) {
			StringBuilder source = new StringBuilder();
			Bindings bindings = scriptContext.getBindings(ScriptContext.ENGINE_SCOPE);
			for (Object entryObject : bindings.entrySet()) {
				Entry<?, ?> entry = (Entry<?, ?>) entryObject;
				String name = (String) entry.getKey();
				if (name.contains(".")) {
					continue;
				}

				Object value = entry.getValue();
				if (value instanceof String
						|| value instanceof String[]
						|| value instanceof BigDecimal
						|| value instanceof BigDecimal[]
						|| value instanceof Double
						|| value instanceof double[]
						|| value instanceof Long
						|| value instanceof long[]
						|| value instanceof Integer
						|| value instanceof int[]
						|| value instanceof Boolean
						|| value instanceof boolean[]) {
					source.append("var ").append(name).append("=").append(new Gson().toJson(value)).append(";");
				} else if (value instanceof Calendar) {
					source.append("var ").append(name).append("=").append(new Gson().toJson("new Date(" + ((Calendar) value).getTime().getTime() + ")")).append(";");
				} else if (value instanceof Calendar[]) {
					Calendar[] values = (Calendar[]) value;
					source.append("var ").append(name).append("=").append("[");
					for (int i = 0; i < values.length; i++) {
						if (i > 0) {
							source.append(",");
						}
						source.append(new Gson().toJson("new Date(" + values[i].getTime().getTime() + ")"));
					}
					source.append("]").append(";");
				} else if (value instanceof java.util.Date) {
					source.append("var ").append(name).append("=").append(new Gson().toJson("new Date(" + ((java.util.Date) value).getTime() + ")")).append(";");
				} else if (value instanceof java.util.Date[]) {
					java.util.Date[] values = (java.util.Date[]) value;
					source.append("var ").append(name).append("=").append("[");
					for (int i = 0; i < values.length; i++) {
						if (i > 0) {
							source.append(",");
						}
						source.append(new Gson().toJson("new Date(" + values[i].getTime() + ")"));
					}
					source.append("]").append(";");
				}
			}
			return source.toString();
		}

		@Override
		public Object eval(ScriptContext scriptContext) throws ScriptException {
			try {
				List<String> sources = new ArrayList<String>();
				String variablesSource = getVariablesSource(scriptContext);
				if (!Strings.isEmpty(variablesSource)) {
					sources.add(variablesSource);
				}
				sources.add(fScript);
				return Adaptables.getAdapter(getFactory(), NativeEcma.class).eval(sources);
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ScriptException.class, "Failed to execute the script '" + fScriptName + "': " + ex.getMessage());
			}
		}

		@Override
		public ScriptEngine getEngine() {
			return NativeEcmaScriptEngine.this;
		}
	}

	private static class ScriptCacheEntry {
		private final CompiledScript fCompiledScript;
		private final String fFilename;
		private final long fLastModified;

		private ScriptCacheEntry(CompiledScript compiledScript, String filename, long lastModified) {
			fCompiledScript = compiledScript;
			fFilename = filename;
			fLastModified = lastModified;
		}

		public CompiledScript getCompiledScript() {
			return fCompiledScript;
		}

		public String getFilename() {
			return fFilename;
		}

		public long getLastModified() {
			return fLastModified;
		}
	}

}

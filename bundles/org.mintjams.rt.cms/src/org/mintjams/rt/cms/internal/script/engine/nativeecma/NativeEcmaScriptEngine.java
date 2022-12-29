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
import java.util.Map.Entry;

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

	public NativeEcmaScriptEngine(ScriptEngineFactory scriptEngineFactory) {
		super(scriptEngineFactory);
	}

	@Override
	public Object eval(Reader script, ScriptContext ctx) throws ScriptException {
		try {
			return new CompiledScriptImpl(getPreparedReader(script)).eval(ctx);
		} catch (IOException ex) {
			throw Cause.create(ex).wrap(ScriptException.class);
		}
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

}

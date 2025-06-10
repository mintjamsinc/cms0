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

package org.mintjams.rt.cms.internal.script.engine.groovy;

import java.io.IOException;
import java.io.Reader;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.mintjams.rt.cms.internal.script.engine.AbstractScriptEngine;
import org.mintjams.rt.cms.internal.script.engine.ResourceScript;
import org.mintjams.rt.cms.internal.script.engine.ScriptCache;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

import groovy.lang.GroovyClassLoader;
import groovy.lang.Writable;
import groovy.text.GStringTemplateEngine;
import groovy.text.Template;

public class GspScriptEngine extends AbstractScriptEngine {

	public GspScriptEngine(GspScriptEngineFactory scriptEngineFactory) {
		super(scriptEngineFactory);
	}

	@Override
public Object eval(Reader reader, ScriptContext ctx) throws ScriptException {
ResourceScript script;
ScriptCache cache = Adaptables.getAdapter(getFactory(), ScriptCache.class);
String scriptName = ResourceScript.getScriptName(reader);
long lastModified = ResourceScript.getLastModified(reader);

script = cache.getScript(scriptName);
if (script == null || script.getLastModified() != lastModified) {
try {
script = new GspScript(reader);
} catch (Throwable ex) {
throw Cause.create(ex).wrap(ScriptException.class, "Unable to compile script: " + ex.getMessage());
}
cache.registerScript(script);
}

		return script.eval(ctx);
	}

	private class GspScript extends ResourceScript {
		private final String fScriptName;
		private final Template fTemplate;
		private final long fLastModified;

		private GspScript(Reader scriptReader) throws ScriptException, IOException {
			fScriptName = getScriptName(scriptReader);
			try {
				GroovyClassLoader classLoader = Adaptables.getAdapter(getFactory(), GroovyClassLoader.class);
				fTemplate = new GStringTemplateEngine(classLoader).createTemplate(scriptReader);
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ScriptException.class, "Unable to compile GSP script: " + ex.getMessage());
			}
			fLastModified = getLastModified(scriptReader);
		}

		@Override
		public Object eval(ScriptContext scriptContext) throws ScriptException {
			try {
				Bindings bindings = scriptContext.getBindings(ScriptContext.ENGINE_SCOPE);
Writable out = fTemplate.make(bindings);
				out.writeTo(scriptContext.getWriter());
				return null;
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ScriptException.class, "Unable to write result of script execution: " + ex.getMessage());
			}
		}

		@Override
		public ScriptEngine getEngine() {
			return GspScriptEngine.this;
		}

		@Override
		public String getScriptName() {
			return fScriptName;
		}

		@Override
		public long getLastModified() {
			return fLastModified;
		}
	}

}

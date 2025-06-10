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

import java.io.Reader;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;

import org.mintjams.rt.cms.internal.script.engine.AbstractScriptEngine;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

import groovy.lang.GroovyClassLoader;
import groovy.text.GStringTemplateEngine;
import groovy.text.Template;

public class GspScriptEngine extends AbstractScriptEngine {

	public GspScriptEngine(GspScriptEngineFactory scriptEngineFactory) {
		super(scriptEngineFactory);
	}

	@Override
	public Object eval(Reader reader, ScriptContext ctx) throws ScriptException {
		Template template = null;

		try {
			template = new GStringTemplateEngine(Adaptables.getAdapter(getFactory(), GroovyClassLoader.class)).createTemplate(reader);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(ScriptException.class, "Unable to compile GSP script: " + ex.getMessage());
		}

		try {
			Bindings bindings = ctx.getBindings(ScriptContext.ENGINE_SCOPE);
			template.make(bindings).writeTo(ctx.getWriter());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(ScriptException.class, "Unable to write result of script execution: " + ex.getMessage());
		}

		return null;
	}

}

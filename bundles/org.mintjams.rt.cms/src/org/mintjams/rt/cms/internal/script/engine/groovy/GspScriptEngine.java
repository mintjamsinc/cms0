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
import java.util.Map;
import java.util.WeakHashMap;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;

import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.engine.AbstractScriptEngine;
import org.mintjams.tools.lang.Cause;

import groovy.text.GStringTemplateEngine;
import groovy.text.Template;
import groovy.text.TemplateEngine;

public class GspScriptEngine extends AbstractScriptEngine {

       private TemplateEngine fTemplateEngine;
       private Map<String, ScriptCacheEntry> fCache;

	public GspScriptEngine(ScriptEngineFactory scriptEngineFactory, ClassLoader classLoader) {
		super(scriptEngineFactory);
               fTemplateEngine = new GStringTemplateEngine(classLoader);
               fCache = new WeakHashMap<>();
	}

	@Override
	public Object eval(Reader reader, ScriptContext ctx) throws ScriptException {
		Template template = null;

		if (reader instanceof ScriptReader) {
			try {
				ScriptReader scriptReader = (ScriptReader) reader;
                               ScriptCacheEntry e = fCache.get(scriptReader.getScriptName());
				if (e != null) {
					if (e.getLastModified() == scriptReader.getLastModified().getTime()) {
						template = e.getTemplate();
					}
				}
			} catch (Throwable ignore) {}
		}

		if (template == null) {
			try {
                               template = fTemplateEngine.createTemplate(reader);

				if (reader instanceof ScriptReader) {
					ScriptReader scriptReader = (ScriptReader) reader;
					ScriptCacheEntry e = new ScriptCacheEntry(template, scriptReader.getScriptName(), scriptReader.getLastModified().getTime());
                                       fCache.put(e.getFilename(), e);
				}
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ScriptException.class, "Unable to compile GSP script: " + ex.getMessage());
			}
		}

		try {
			Bindings bindings = ctx.getBindings(ScriptContext.ENGINE_SCOPE);
			template.make(bindings).writeTo(ctx.getWriter());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(ScriptException.class, "Unable to write result of script execution: " + ex.getMessage());
		}

		return null;
	}

       private static class ScriptCacheEntry {
               private final Template fTemplate;
               private final String fFilename;
               private final long fLastModified;

               private ScriptCacheEntry(Template template, String filename, long lastModified) {
                       fTemplate = template;
                       fFilename = filename;
                       fLastModified = lastModified;
               }

               public Template getTemplate() {
                       return fTemplate;
               }

               public String getFilename() {
                       return fFilename;
               }

               public long getLastModified() {
                       return fLastModified;
               }
       }

}

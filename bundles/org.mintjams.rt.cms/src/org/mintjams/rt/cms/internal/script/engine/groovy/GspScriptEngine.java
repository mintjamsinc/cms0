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

	private TemplateEngine templateEngine;
	private Map<String, ScriptCacheEntry> cache;

	public GspScriptEngine(ScriptEngineFactory scriptEngineFactory, ClassLoader classLoader) {
		super(scriptEngineFactory);
		this.templateEngine = new GStringTemplateEngine(classLoader);
		this.cache = new WeakHashMap<>();
	}

	@Override
	public Object eval(Reader reader, ScriptContext ctx) throws ScriptException {
		Template template = null;

		if (reader instanceof ScriptReader) {
			try {
				ScriptReader scriptReader = (ScriptReader) reader;
				ScriptCacheEntry e = cache.get(scriptReader.getScriptName());
				if (e != null) {
					if (e.getLastModified() == scriptReader.getLastModified().getTime()) {
						template = e.getTemplate();
					}
				}
			} catch (Throwable ignore) {}
		}

		if (template == null) {
			try {
				template = templateEngine.createTemplate(reader);

				if (reader instanceof ScriptReader) {
					ScriptReader scriptReader = (ScriptReader) reader;
					ScriptCacheEntry e = new ScriptCacheEntry(template, scriptReader.getScriptName(), scriptReader.getLastModified().getTime());
					cache.put(e.getFilename(), e);
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
		private final Template template;
		private final String filename;
		private final long lastModified;

		private ScriptCacheEntry(Template template, String filename, long lastModified) {
			this.template = template;
			this.filename = filename;
			this.lastModified = lastModified;
		}

		public Template getTemplate() {
			return template;
		}

		public String getFilename() {
			return filename;
		}

		public long getLastModified() {
			return lastModified;
		}
	}

}

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

package org.mintjams.rt.cms.internal.script;

import java.io.Closeable;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.ClassLoaders;

public class ScriptReader extends FilterReader {

	private String fScriptName;
	private String fMimeType = null;
	private String fExtension = null;
	private java.util.Date fLastModified;
	private ScriptContext fScriptContext;
	private ScriptEngineManager fScriptEngineManager;
	private String fScriptEngineName = null;
	private ClassLoader fClassLoader = null;

	public ScriptReader(Reader reader) {
		super(reader);
	}

	public String getScriptName() {
		return fScriptName;
	}

	public ScriptReader setScriptName(String scriptName) {
		fScriptName = scriptName;
		return this;
	}

	public String getExtension() {
		return fExtension;
	}

	public ScriptReader setExtension(String extension) {
		fExtension = extension;
		return this;
	}

	public String getMimeType() {
		return fMimeType;
	}

	public ScriptReader setMimeType(String mimeType) {
		fMimeType = mimeType;
		return this;
	}

	public java.util.Date getLastModified() {
		return fLastModified;
	}

	public ScriptReader setLastModified(java.util.Date lastModified) {
		fLastModified = lastModified;
		return this;
	}

	public ScriptContext getScriptContext() {
		return fScriptContext;
	}

	public ScriptReader setScriptContext(ScriptContext scriptContext) {
		fScriptContext = scriptContext;
		return this;
	}

	public ScriptEngineManager getScriptEngineManager() {
		return fScriptEngineManager;
	}

	public ScriptReader setScriptEngineManager(ScriptEngineManager scriptEngineManager) {
		fScriptEngineManager = scriptEngineManager;
		return this;
	}

	public String getScriptEngineName() {
		return fScriptEngineName;
	}

	public ScriptReader setScriptEngineName(String scriptEngineName) {
		this.fScriptEngineName = scriptEngineName;
		return this;
	}

	public ClassLoader getClassLoader() {
		return fClassLoader;
	}

	public ScriptReader setClassLoader(ClassLoader classLoader) {
		this.fClassLoader = classLoader;
		return this;
	}

	public Object eval() throws ScriptException, IOException {
		try (Reader _this = this) {
			ScriptCache scriptCache = Adaptables.getAdapter(fClassLoader, ScriptCache.class);
			if (scriptCache != null) {
				ScriptCache.Entry cached = scriptCache.getScriptCacheEntry(fScriptName);
				if (cached != null && cached.getLastModified().compareTo(fLastModified) == 0) {
					try (Closeable c = ClassLoaders.withClassLoader(fClassLoader)) {
						return cached.getScript().eval(fScriptContext);
					}
				}
			}

			ScriptEngine engine = null;
			if (fMimeType != null) {
				engine = fScriptEngineManager.getEngineByMimeType(fMimeType);
				if (engine == null) {
					throw new ScriptException("ScriptEngine with type '" + fMimeType + "' not found");
				}
			} else if (fExtension != null) {
				engine = fScriptEngineManager.getEngineByExtension(fExtension);
				if (engine == null) {
					throw new ScriptException("ScriptEngine with extension '" + fExtension + "' not found");
				}
			} else if (fScriptEngineName != null) {
				engine = fScriptEngineManager.getEngineByName(fScriptEngineName);
				if (engine == null) {
					throw new ScriptException("ScriptEngine with name '" + fScriptEngineName + "' not found");
				}
			} else {
				String extension = fScriptName;
				int p = extension.lastIndexOf("/");
				if (p != -1) {
					extension = extension.substring(p + 1);
				}
				for (;;) {
					engine = fScriptEngineManager.getEngineByExtension(extension);
					if (engine != null) {
						break;
					}

					p = extension.indexOf(".");
					if (p == -1) {
						break;
					}

					extension = extension.substring(p + 1);
				}
				if (engine == null) {
					throw new ScriptException("ScriptEngine with extension '" + extension + "' not found");
				}
			}

			try (Closeable c = ClassLoaders.withClassLoader(fClassLoader)) {
				if (engine instanceof Compilable) {
					CompiledScript script = ((Compilable) engine).compile(this);
					if (scriptCache != null) {
						scriptCache.setScriptCacheEntry(fScriptName, script, fLastModified);
					}
					return script.eval(fScriptContext);
				}

				return engine.eval(this, fScriptContext);
			}
		}
	}

}

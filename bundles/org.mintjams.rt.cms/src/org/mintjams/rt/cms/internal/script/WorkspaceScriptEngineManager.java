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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.engine.groovy.GroovyScriptEngineFactory;
import org.mintjams.rt.cms.internal.script.engine.groovy.GspScriptEngineFactory;
import org.mintjams.rt.cms.internal.script.engine.nativeecma.NativeEcmaScriptEngineFactory;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.Closer;

public class WorkspaceScriptEngineManager extends ScriptEngineManager implements Closeable, Adaptable {

	private final String fWorkspaceName;
	private final List<ScriptEngineFactory> fScriptEngineFactories = new ArrayList<>();
	private final Closer fCloser = Closer.create();

	public WorkspaceScriptEngineManager(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	public synchronized void open() {
		fScriptEngineFactories.add(new GspScriptEngineFactory(fWorkspaceName));
		fScriptEngineFactories.add(new GroovyScriptEngineFactory(fWorkspaceName));
		try {
			fScriptEngineFactories.add(fCloser.register(new NativeEcmaScriptEngineFactory()));
		} catch (Throwable ex) {
			if (ex instanceof Error) {
				CmsService.getLogger(getClass()).error(ex.getMessage(), ex);
			}
			CmsService.getLogger(getClass()).info("The native ECMA script engine is disabled.");
		}
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	@Override
	public ScriptEngine getEngineByName(String shortName) {
		for (ScriptEngineFactory factory : getEngineFactories()) {
			List<String> names = null;
			try {
				names = factory.getNames();
			} catch (Throwable ignore) {}
			if (names == null) {
				continue;
			}

			for (String name : names) {
				if (shortName.equals(name)) {
					try {
						ScriptEngine engine = factory.getScriptEngine();
						engine.setBindings(getBindings(), ScriptContext.GLOBAL_SCOPE);
						return engine;
					} catch (Throwable ignore) {}
				}
			}
		}
		return null;
	}

	@Override
	public ScriptEngine getEngineByExtension(String extension) {
		for (ScriptEngineFactory factory : getEngineFactories()) {
			List<String> exts = null;
			try {
				exts = factory.getExtensions();
			} catch (Throwable ignore) {}
			if (exts == null) {
				continue;
			}

			for (String ext : exts) {
				if (extension.equals(ext)) {
					try {
						ScriptEngine engine = factory.getScriptEngine();
						engine.setBindings(getBindings(), ScriptContext.GLOBAL_SCOPE);
						return engine;
					} catch (Throwable ignore) {}
				}
			}
		}
		return null;
	}

	@Override
	public ScriptEngine getEngineByMimeType(String mimeType) {
		for (ScriptEngineFactory factory : getEngineFactories()) {
			List<String> types = null;
			try {
				types = factory.getMimeTypes();
			} catch (Throwable ignore) {}
			if (types == null) {
				continue;
			}

			for (String type : types) {
				if (mimeType.equals(type)) {
					try {
						ScriptEngine engine = factory.getScriptEngine();
						engine.setBindings(getBindings(), ScriptContext.GLOBAL_SCOPE);
						return engine;
					} catch (Throwable ignore) {}
				}
			}
		}
		return null;
	}

	@Override
	public List<ScriptEngineFactory> getEngineFactories() {
		return Collections.unmodifiableList(fScriptEngineFactories);
	}

	@Override
	public void registerEngineName(String name, ScriptEngineFactory factory) {
		throw new UnsupportedOperationException(
				"This ScriptEngineManager only supports registered ScriptEngineFactory.");
	}

	@Override
	public void registerEngineMimeType(String type, ScriptEngineFactory factory) {
		throw new UnsupportedOperationException(
				"This ScriptEngineManager only supports registered ScriptEngineFactory.");
	}

	@Override
	public void registerEngineExtension(String extension, ScriptEngineFactory factory) {
		throw new UnsupportedOperationException(
				"This ScriptEngineManager only supports registered ScriptEngineFactory.");
	}

	@Override
	public synchronized void close() throws IOException {
		fCloser.close();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(CmsService.getWorkspaceClassLoaderProvider(fWorkspaceName), adapterType);
	}

}

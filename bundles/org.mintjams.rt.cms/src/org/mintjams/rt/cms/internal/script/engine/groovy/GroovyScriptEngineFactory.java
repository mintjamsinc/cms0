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

import javax.script.ScriptEngine;

import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.WorkspaceClassLoaderProvider;
import org.mintjams.tools.adapter.Adaptables;

import groovy.lang.GroovyClassLoader;

public class GroovyScriptEngineFactory extends org.codehaus.groovy.jsr223.GroovyScriptEngineFactory {

	private final String fWorkspaceName;

	/**
	 * The engine for the current class loader, and the loader it was built for.
	 * <p>
	 * A fresh {@code GroovyScriptEngineImpl} per call means a fresh compiled class
	 * per call: the engine's script cache is what stops a script being recompiled,
	 * and a discarded engine takes its cache with it. Every {@code Class} that
	 * produces stays in metaspace, so a part invoked once per record over a large
	 * import compiles — and leaks — once per record.
	 * <p>
	 * The engine is keyed by class loader rather than simply held forever, so that
	 * redeploying the workspace's classes yields a new engine and lets the old one,
	 * along with everything it compiled, be collected.
	 */
	private volatile GroovyClassLoader fClassLoader;
	private volatile ScriptEngine fScriptEngine;

	public GroovyScriptEngineFactory(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	public ScriptEngine getScriptEngine() {
		GroovyClassLoader classLoader = Adaptables
				.getAdapter(getWorkspaceClassLoaderProvider().getClassLoader(), GroovyClassLoader.class);

		ScriptEngine scriptEngine = fScriptEngine;
		if (scriptEngine != null && fClassLoader == classLoader) {
			return scriptEngine;
		}

		synchronized (this) {
			if (fScriptEngine == null || fClassLoader != classLoader) {
				fScriptEngine = new GroovyScriptEngineImpl(classLoader);
				fClassLoader = classLoader;
			}
			return fScriptEngine;
		}
	}

	private WorkspaceClassLoaderProvider getWorkspaceClassLoaderProvider() {
		return CmsService.getWorkspaceClassLoaderProvider(fWorkspaceName);
	}

}

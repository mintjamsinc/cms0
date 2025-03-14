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

	public GroovyScriptEngineFactory(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	public ScriptEngine getScriptEngine() {
		return new GroovyScriptEngineImpl(Adaptables.getAdapter(getWorkspaceClassLoaderProvider().getClassLoader(), GroovyClassLoader.class));
	}

	private WorkspaceClassLoaderProvider getWorkspaceClassLoaderProvider() {
		return CmsService.getWorkspaceClassLoaderProvider(fWorkspaceName);
	}

}

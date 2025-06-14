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

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.engine.AbstractScriptEngineFactory;
import org.mintjams.rt.cms.internal.script.engine.ScriptCache;
import org.mintjams.rt.cms.internal.script.engine.ScriptCacheManager;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

import groovy.lang.GroovyClassLoader;

public class GspScriptEngineFactory extends AbstractScriptEngineFactory implements Adaptable {

	private final String fWorkspaceName;
	private final String fLanguageName;
	private final String fLanguageVersion;
	private final ScriptCache fScriptCache;

	public GspScriptEngineFactory(String workspaceName) {
		fWorkspaceName = workspaceName;
		org.codehaus.groovy.jsr223.GroovyScriptEngineFactory gf = new org.codehaus.groovy.jsr223.GroovyScriptEngineFactory();
		setNames("gsp", "GSP");
		setExtensions("gsp");
		setMimeTypes("application/x-gsp");
		setEngineName("Groovy Server Pages Scripting Engine");
		setEngineVersion(gf.getEngineVersion());
		fLanguageName = "Groovy Server Pages";
		fLanguageVersion = gf.getLanguageVersion();
		fScriptCache = new ScriptCache(GspScriptEngine.class.getSimpleName(), CmsService.getConfiguration().getMaxScriptCachePerScriptEngine());
		Adaptables.getAdapter(getWorkspaceClassLoader(), ScriptCacheManager.class).registerScriptCache(fScriptCache);
	}

	@Override
	public String getLanguageName() {
		return fLanguageName;
	}

	@Override
	public String getLanguageVersion() {
		return fLanguageVersion;
	}

	@Override
	public Object getParameter(String name) {
		if ("THREADING".equals(name)) {
			return "MULTITHREADED";
		}

		return super.getParameter(name);
	}

	@Override
	public ScriptEngine getScriptEngine() {
		return new GspScriptEngine(this);
	}

	private ClassLoader getWorkspaceClassLoader() {
		return CmsService.getWorkspaceClassLoaderProvider(fWorkspaceName).getClassLoader();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(GroovyClassLoader.class)) {
			return (AdapterType) Adaptables.getAdapter(getWorkspaceClassLoader(), GroovyClassLoader.class);
		}

		if (adapterType.equals(ScriptCache.class)) {
			return (AdapterType) fScriptCache;
		}

		return null;
	}

}

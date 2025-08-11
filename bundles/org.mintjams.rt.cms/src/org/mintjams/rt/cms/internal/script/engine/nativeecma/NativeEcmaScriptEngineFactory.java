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

import java.io.Closeable;
import java.io.IOException;

import javax.script.ScriptEngine;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.engine.AbstractScriptEngineFactory;
import org.mintjams.rt.cms.internal.script.engine.ScriptCache;
import org.mintjams.rt.cms.internal.script.engine.ScriptCacheManager;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.Closer;

public class NativeEcmaScriptEngineFactory extends AbstractScriptEngineFactory implements Closeable, Adaptable {

	private final String fWorkspaceName;
	private String fLanguageName;
	private String fLanguageVersion;
	private NativeEcma fNativeEcma;
	private final Closer fCloser = Closer.create();
	private final ScriptCache fScriptCache;

	public NativeEcmaScriptEngineFactory(String workspaceName) throws IOException {
		fWorkspaceName = workspaceName;
		setNames("nativeecma", "NativeECMA");
		setExtensions("nativeecma", "njs");
		setMimeTypes("text/native-ecmascript", "application/native-ecmascript");
		setEngineName("Native ECMAScript Engine");
		setEngineVersion("8.9.0");
		fLanguageName = "ECMAScript";
		fLanguageVersion = "6";
		fNativeEcma = fCloser.register(new NativeEcma());
		fNativeEcma.load(CmsService.getConfiguration().getNativeEcmaPoolSizePerScriptEngine());
		fScriptCache = new ScriptCache(NativeEcmaScriptEngine.class.getSimpleName(), CmsService.getConfiguration().getMaxScriptCachePerScriptEngine());
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
		return new NativeEcmaScriptEngine(this);
	}

	@Override
	public void close() throws IOException {
		fCloser.close();
	}

	private ClassLoader getWorkspaceClassLoader() {
		return CmsService.getWorkspaceClassLoaderProvider(fWorkspaceName).getClassLoader();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(NativeEcma.class)) {
			return (AdapterType) fNativeEcma;
		}

		if (adapterType.equals(ScriptCache.class)) {
			return (AdapterType) fScriptCache;
		}

		return null;
	}

}

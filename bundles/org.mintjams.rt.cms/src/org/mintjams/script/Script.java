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

package org.mintjams.script;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.Credentials;
import javax.script.ScriptException;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.tools.lang.Cause;

public class Script {

	private final Resource fResource;
	private final ScriptAPI fScriptAPI;
	private boolean fAsync;
	private String fExtension;
	private String fMimeType;
	private Map<String, Object> fAttributes = new HashMap<>();

	protected Script(Resource resource, ScriptAPI scriptAPI) {
		fResource = resource;
		fScriptAPI = scriptAPI;
		fAsync = true;
	}

	public Script setAsync(boolean async) {
		fAsync = async;
		return this;
	}

	public Script setExtension(String extension) {
		fExtension = extension;
		return this;
	}

	public Script setMimeType(String mimeType) {
		fMimeType = mimeType;
		return this;
	}

	public Script setAttribute(String name, Object value) {
		fAttributes.put(name, value);
		return this;
	}

	public Object eval() throws ScriptException {
		if (!fAsync) {
			return evaluate(fScriptAPI.adaptTo(WorkspaceScriptContext.class));
		}

		WorkspaceScriptContext context = new WorkspaceScriptContext(fScriptAPI.adaptTo(WorkspaceScriptContext.class).getWorkspaceName());
		context.setCredentials(fScriptAPI.adaptTo(Credentials.class));
		try {
			Scripts.prepareAPIs(context);
		} catch (IOException ex) {
			throw Cause.create(ex).wrap(ScriptException.class);
		}

		ScriptAPI api = (ScriptAPI) context.getAttribute("ScriptAPI");
		Script script;
		try {
			script = (Script) api.createScript(context.getResourceResolver().getResource(fResource.getPath()));
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(ScriptException.class);
		}
		script.fAsync = fAsync;
		script.fExtension = fExtension;
		script.fMimeType = fMimeType;
		script.fAttributes = fAttributes;
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try (context) {
					script.evaluate(context);
				} catch (Throwable ex) {
					CmsService.getLogger(Script.class).error(ex.getMessage(), ex);
				}
			}
		});
		t.setDaemon(true);
		t.start();
		return null;
	}

	private Object evaluate(WorkspaceScriptContext context) throws ScriptException {
		try (ScriptReader scriptReader = new ScriptReader(fResource.getContentAsReader())) {
			try (EvaluationScope evaluationScope = new EvaluationScope(context, fAttributes)) {
				scriptReader
						.setScriptName("jcr://" + fResource.getPath())
						.setLastModified(fResource.getLastModified())
						.setScriptEngineManager(Scripts.getScriptEngineManager(context))
						.setClassLoader(Scripts.getClassLoader(context))
						.setScriptContext(context);
				if (fExtension != null) {
					scriptReader.setExtension(fExtension);
				}
				if (fMimeType != null) {
					scriptReader.setMimeType(fMimeType);
				}
				return scriptReader.eval();
			}
		} catch (ResourceException | IOException ex) {
			throw Cause.create(ex).wrap(ScriptException.class);
		}
	}

	private static class EvaluationScope implements Closeable {
		private final WorkspaceScriptContext fContext;
		private final Map<String, Object> fAttributes;

		private EvaluationScope(WorkspaceScriptContext context, Map<String, Object> attributes) {
			fContext = context;
			fAttributes = attributes;
			for (String name : fAttributes.keySet()) {
				fContext.setAttribute(name, fAttributes.get(name));
			}
		}

		@Override
		public void close() throws IOException {
			for (String name : fAttributes.keySet()) {
				fContext.removeAttribute(name);
			}
		}
	}

}

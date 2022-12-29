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

import javax.script.ScriptException;

import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceNotFoundException;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

public class ScriptAPI implements Adaptable {

	private WorkspaceScriptContext fContext;

	public ScriptAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public Script createScript(String path) throws ScriptException {
		try {
			return createScript(fContext.getResourceResolver().getResource(path));
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(ScriptException.class);
		}
	}

	public Script createScript(Resource resource) throws ScriptException {
		try {
			if (!resource.exists()) {
				throw new ResourceNotFoundException(resource.getPath());
			}
			if (!resource.canRead()) {
				throw new ResourceNotFoundException(resource.getPath());
			}
			return new Script(resource, this);
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(ScriptException.class);
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fContext, adapterType);
	}

}

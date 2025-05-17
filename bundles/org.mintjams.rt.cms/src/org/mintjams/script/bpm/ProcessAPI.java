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

package org.mintjams.script.bpm;

import org.camunda.bpm.engine.ProcessEngine;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.ScriptingContext;

public class ProcessAPI {

	private WorkspaceScriptContext fContext;

	public ProcessAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static ProcessAPI get(ScriptingContext context) {
		return (ProcessAPI) context.getAttribute(ProcessAPI.class.getSimpleName());
	}

	public ProcessEngine getEngine() {
		return CmsService.getWorkspaceProcessEngineProvider(fContext.getWorkspaceName()).getProcessEngine();
	}

	public ProcessStarter createProcessStarter() {
		return new ProcessStarter(this);
	}

	public MessageCorrelator createMessageCorrelator() {
		return new MessageCorrelator(this);
	}

}

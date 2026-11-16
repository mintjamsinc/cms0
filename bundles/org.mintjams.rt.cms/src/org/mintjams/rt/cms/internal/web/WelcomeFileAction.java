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

package org.mintjams.rt.cms.internal.web;

import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.util.Action;
import org.mintjams.tools.util.ActionChain;
import org.mintjams.tools.util.ActionContext;
import org.mintjams.tools.util.ActionException;

/**
 * Maps a request for a folder URL with a trailing slash ({@code /docs/}) to
 * the folder's welcome file ({@code /docs/index.gsp}) before the rest of the
 * chain runs, the way a servlet container maps welcome files: the address and
 * the query string stay as requested, and the protection checks, the filters
 * and the error pages see the welcome file's path, as if it had been
 * requested by name.
 *
 * <p>A folder URL without the trailing slash is left alone here: it answers
 * a redirect to the slash form ({@link EvaluateAction}), so that relative
 * references in the welcome file resolve against the folder.</p>
 */
public class WelcomeFileAction implements Action {

	public void doAction(ActionContext context, ActionChain chain) throws ActionException {
		// The resource path is normalized and has lost the trailing slash; the
		// request path still has it.
		String pathInfo = Webs.getEffectivePathInfo(Webs.getRequest(context));
		if (pathInfo != null && pathInfo.endsWith("/")) {
			try {
				String welcomeFile = Webs.findWelcomeFile(context, Webs.getResourcePath(context));
				if (welcomeFile != null) {
					Scripts.getWorkspaceScriptContext(context).setResourcePath(welcomeFile);
				}
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ActionException.class);
			}
		}

		chain.doAction(context);
	}

}

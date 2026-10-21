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

import javax.jcr.GuestCredentials;
import javax.script.ScriptException;

import org.apache.commons.io.IOUtils;
import org.mintjams.rt.cms.internal.WorkspaceUserHomes;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.ResourceNotFoundException;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

public class ScriptAPI implements Adaptable {

	private WorkspaceScriptContext fContext;

	public ScriptAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static ScriptAPI get(ScriptingContext context) {
		return (ScriptAPI) context.getAttribute(ScriptAPI.class.getSimpleName());
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

	@Deprecated
	public ScriptingContext createInternalContext() {
		WorkspaceScriptContext context = null;
		try {
			context = new WorkspaceScriptContext(fContext.getWorkspaceName());
			context.setCredentials(new CmsServiceCredentials());
			Scripts.prepareAPIs(context);
			return context;
		} catch (Throwable ex) {
			IOUtils.closeQuietly(context);
			throw new IllegalStateException(ex.getMessage(), ex);
		}
	}

	public ScriptingContext createServiceUserContext(String userId) {
		WorkspaceScriptContext context = null;
		try {
			context = new WorkspaceScriptContext(fContext.getWorkspaceName());
			context.setCredentials(new ServiceUserCredentials(userId));
			Scripts.prepareAPIs(context);
			context.getSession();  // Ensure that the session is created to validate the credentials
			return context;
		} catch (Throwable ex) {
			IOUtils.closeQuietly(context);
			throw new IllegalStateException(ex.getMessage(), ex);
		}
	}

	/**
	 * A context of the user, as a request of that user has it: the user's own
	 * session, with the user's home in this workspace made sure of first, the
	 * way a request makes sure of it. For a script that acts in the user's
	 * home on the user's behalf, which only the user may write — a folder an
	 * app keeps for the user and grants to its service account, say. A
	 * service account has no home: for one, use
	 * {@link #createServiceUserContext}.
	 */
	public ScriptingContext createUserContext(String userId) {
		WorkspaceScriptContext context = null;
		try {
			context = new WorkspaceScriptContext(fContext.getWorkspaceName());
			context.setCredentials(new ServiceUserCredentials(userId));
			Scripts.prepareAPIs(context);
			WorkspaceUserHomes.ensureUserHome(context.adaptTo(javax.jcr.Session.class));
			return context;
		} catch (Throwable ex) {
			IOUtils.closeQuietly(context);
			throw new IllegalStateException(ex.getMessage(), ex);
		}
	}

	@Deprecated
	public ScriptingContext createAnonymousContext() {
		WorkspaceScriptContext context = null;
		try {
			context = new WorkspaceScriptContext(fContext.getWorkspaceName());
			context.setCredentials(new GuestCredentials());
			Scripts.prepareAPIs(context);
			return context;
		} catch (Throwable ex) {
			IOUtils.closeQuietly(context);
			throw new IllegalStateException(ex.getMessage(), ex);
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fContext, adapterType);
	}

}

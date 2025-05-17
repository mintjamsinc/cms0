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

package org.mintjams.script.resource;

import javax.jcr.Credentials;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.ScriptingContext;
import org.mintjams.script.resource.security.CredentialExpiredException;
import org.mintjams.script.resource.security.LoginException;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class SessionAPI implements Adaptable {

	private WorkspaceScriptContext fContext;

	public SessionAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static SessionAPI get(ScriptingContext context) {
		return (SessionAPI) context.getAttribute(SessionAPI.class.getSimpleName());
	}

	public Session login(Credentials credentials) throws ResourceException {
		javax.jcr.Repository jcrRepository = CmsService.getRepository();
		if (jcrRepository == null) {
			throw new LoginException("Repository is not available.");
		}

		javax.jcr.Session jcrSession;
		try {
			jcrSession = jcrRepository.login(credentials, fContext.getWorkspaceName());
		} catch (javax.jcr.LoginException ex) {
			for (Throwable e = ex.getCause(); e != null; e = e.getCause()) {
				if (e instanceof javax.security.auth.login.CredentialExpiredException) {
					throw new CredentialExpiredException("Credential expired");
				}
			}
			throw (LoginException) new LoginException(ex.getMessage()).initCause(ex);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}

		return new Session(jcrSession, fContext);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fContext, adapterType);
	}

}

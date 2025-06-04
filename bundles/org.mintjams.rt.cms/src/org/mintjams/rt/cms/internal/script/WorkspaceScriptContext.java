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

package org.mintjams.rt.cms.internal.script;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;

import javax.jcr.Credentials;
import javax.jcr.GuestCredentials;
import javax.jcr.RepositoryException;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngineManager;
import javax.script.SimpleScriptContext;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.web.ScriptWriter;
import org.mintjams.script.JSON;
import org.mintjams.script.ScriptingContext;
import org.mintjams.script.YAML;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.ResourceResolver;
import org.mintjams.script.resource.Session;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.util.ActionContext;

public class WorkspaceScriptContext extends SimpleScriptContext implements ScriptingContext, ActionContext, Closeable, Adaptable {

	private final String fWorkspaceName;
	private final Closer fCloser = Closer.create();
	private Credentials fCredentials;

	public WorkspaceScriptContext(String workspaceName) {
		fWorkspaceName = workspaceName;
		setAttribute("context", this);
		Writer out = new ScriptWriter(this);
		setWriter(out);
		setAttribute("out", out);
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public void setCredentials(Credentials credentials) {
		fCredentials = credentials;
	}

	public void setResourcePath(String path) {
		setAttribute("resourcePath", path);
	}

	public String getResourcePath() {
		return (String) getAttribute("resourcePath");
	}

	public ScriptEngineManager getScriptEngineManager() {
		return Scripts.getScriptEngineManager(this);
	}

	private Credentials getCredentials() {
		if (fCredentials == null) {
			return new GuestCredentials();
		}
		return fCredentials;
	}

	private Session fSession;
	public Session getSession() throws ResourceException {
		if (fSession == null) {
			try {
				fSession = fCloser.register(new Session(getJcrSession(), this));
			} catch (Throwable ex) {
				throw ResourceException.wrap(ex);
			}
		}
		return fSession;
	}

	public Session getRepositorySession() throws ResourceException {
		return getSession();
	}

	public ResourceResolver getResourceResolver() throws ResourceException {
		return getSession().getResourceResolver();
	}

	private javax.jcr.Session fJcrSession;
	private javax.jcr.Session getJcrSession() throws RepositoryException {
		if (fJcrSession == null) {
			fJcrSession = CmsService.getRepository().login(getCredentials(), getWorkspaceName());
			fCloser.add(new Closeable() {
				@Override
				public void close() throws IOException {
					for (int scope : new int[] { ENGINE_SCOPE, GLOBAL_SCOPE }) {
						Bindings bindings = getBindings(scope);
						if (bindings == null) {
							continue;
						}

						for (Object e : bindings.values()) {
							if (e instanceof Closeable && !this.equals(e)) {
								try {
									((Closeable) e).close();
								} catch (Throwable ignore) {}
							}
						}

						bindings.clear();
					}
				}
			});
			fCloser.add(new Closeable() {
				@Override
				public void close() throws IOException {
					if (fJcrSession.isLive()) {
						fJcrSession.logout();
					}
				}
			});
		}
		return fJcrSession;
	}

	public void setAttribute(String name, Object value) {
		super.setAttribute(name, value, ScriptContext.ENGINE_SCOPE);
	}

	public Object removeAttribute(String name) {
		return super.removeAttribute(name, ScriptContext.ENGINE_SCOPE);
	}

	@Override
	public void close() throws IOException {
		fCloser.close();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(javax.jcr.Session.class)) {
			try {
				return (AdapterType) getJcrSession();
			} catch (RepositoryException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}

		if (adapterType.equals(Credentials.class)) {
			return (AdapterType) fCredentials;
		}

		if (adapterType.equals(JSON.class)) {
			return (AdapterType) getAttribute(JSON.class.getSimpleName());
		}

		if (adapterType.equals(YAML.class)) {
			return (AdapterType) getAttribute(YAML.class.getSimpleName());
		}

		return null;
	}

}

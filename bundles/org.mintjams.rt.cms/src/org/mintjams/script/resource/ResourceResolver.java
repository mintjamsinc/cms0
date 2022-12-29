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

import java.io.IOException;
import java.io.Reader;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.web.WebResourceResolver;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

public class ResourceResolver {

	private final Session fSession;

	protected ResourceResolver(Session session) {
		fSession = session;
	}

	public Session getSession() {
		return fSession;
	}

	public Resource getResource(String absPath) throws ResourceException {
		return fSession.getResource(absPath);
	}

	public Resource getResourceByIdentifier(String id) throws ResourceException {
		return fSession.getResourceByIdentifier(id);
	}

	public Resource getRootFolder() throws ResourceException {
		return fSession.getRootFolder();
	}

	public Resource toResource(Node node) throws ResourceException {
		return new ResourceImpl(node, fSession);
	}

	public Template getTemplate(String templatePath, String prefix) throws RepositoryException {
		WorkspaceScriptContext context = Adaptables.getAdapter(fSession, WorkspaceScriptContext.class);
		WebResourceResolver.Template tmpl = new WebResourceResolver(context).getTemplate(templatePath, prefix);
		return new Template(tmpl);
	}

	public static class Template {
		private final WebResourceResolver.Template fTemplate;

		private Template(WebResourceResolver.Template template) {
			fTemplate = template;
		}

		public String getScriptExtension() {
			return fTemplate.getScriptExtension();
		}

		public Reader getContentAsReader() throws ResourceException, IOException {
			try {
				return fTemplate.getContentAsReader();
			} catch (RepositoryException ex) {
				throw Cause.create(ex).wrap(ResourceException.class);
			}
		}

		public String getPath() throws ResourceException {
			try {
				return fTemplate.getPath();
			} catch (RepositoryException ex) {
				throw Cause.create(ex).wrap(ResourceException.class);
			}
		}
	}

}

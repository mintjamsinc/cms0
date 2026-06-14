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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.JSON;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.util.ActionContext;

public class WebResourceResolver {

	private final ActionContext fContext;

	public WebResourceResolver(ActionContext context) {
		fContext = context;
	}

	public ResolveResult resolve(String absPath) throws RepositoryException {
		try {
			Node node = Scripts.getJcrSession(fContext).getNode(absPath);
			// A templated source kept under its natural name (e.g. "index.md"
			// bound to a template) is authoring input, not a public
			// representation: its rendered output is served at the output
			// extension (e.g. "index.html"), and the raw source itself is not
			// web-exposed. A plain file that merely carries a source extension
			// but has no template binding (e.g. a downloadable "readme.md")
			// keeps being served as-is.
			if (isHiddenSource(node)) {
				return new ResolveResult(new PathNotFoundException(absPath));
			}
			return new ResolveResult(node);
		} catch (PathNotFoundException ignore) {
		} catch (AccessDeniedException ex) {
			return new ResolveResult(ex);
		}

		int p = absPath.lastIndexOf("/");
		String parentPath = absPath.substring(0, p + 1);
		String filename = absPath.substring(p + 1);
		String basename = filename;
		String suffix = "";
		for (;;) {
			// Natural-extension sources first (e.g. a request for "index.html"
			// resolves the stored "index.md" source). This is strictly additive
			// to the legacy lookup below and never alters its behavior.
			for (String sourceExtension : getSourceExtensions()) {
				ResolveResult result = resolveTemplatedSource(parentPath + basename + "." + sourceExtension, suffix);
				if (result != null) {
					return result;
				}
			}

			// Legacy extension-less convention: the source node is named exactly
			// "index" (no extension) and the output extension lives only on the
			// request. Folder descriptors are honored here too, but otherwise the
			// original "base exists but is not templatable -> stop" behavior is
			// preserved.
			try {
				Node baseNode = Scripts.getJcrSession(fContext).getNode(parentPath + basename);
				WebRenders.Binding binding = WebRenders.resolveBinding(baseNode);
				if (binding == null || !binding.allowsOutput(suffix)) {
					break;
				}

				Template template = getTemplate(binding.getTemplatePath() + suffix);
				if (template == null) {
					break;
				}

				return new ResolveResult(baseNode, template);
			} catch (PathNotFoundException ignore) {
			} catch (AccessDeniedException ex) {
				return new ResolveResult(ex);
			}

			p = basename.lastIndexOf(".");
			if (p == -1) {
				break;
			}
			suffix = basename.substring(p) + suffix;
			basename = basename.substring(0, p);
		}

		return new ResolveResult(new PathNotFoundException(absPath));
	}

	/**
	 * Attempts to resolve a stored source node at {@code nodePath} as a templated
	 * resource for the given output {@code suffix} (e.g. ".html"). Returns the
	 * templated {@link ResolveResult} on success, an access-denied result when
	 * the source exists but is not readable, or {@code null} when the node does
	 * not exist or is not a usable templated source (so the caller can keep
	 * trying other candidates).
	 */
	private ResolveResult resolveTemplatedSource(String nodePath, String suffix) throws RepositoryException {
		Node node;
		try {
			node = Scripts.getJcrSession(fContext).getNode(nodePath);
		} catch (PathNotFoundException ignore) {
			return null;
		} catch (AccessDeniedException ex) {
			return new ResolveResult(ex);
		}

		WebRenders.Binding binding = WebRenders.resolveBinding(node);
		if (binding == null || !binding.allowsOutput(suffix)) {
			return null;
		}

		Template template = getTemplate(binding.getTemplatePath() + suffix);
		if (template == null) {
			return null;
		}

		return new ResolveResult(node, template);
	}

	/**
	 * Whether the given node is a templated source that must not be served as a
	 * raw representation: an {@code nt:file} whose name carries one of the
	 * configured source extensions and which is bound to a template. The rendered
	 * output is reachable at the output extension instead.
	 */
	private boolean isHiddenSource(Node node) throws RepositoryException {
		if (!node.isNodeType(NodeType.NT_FILE)) {
			return false;
		}
		if (!WebRenders.hasSourceExtension(node.getName(), getSourceExtensions())) {
			return false;
		}
		return WebRenders.resolveBinding(node) != null;
	}

	private List<String> _sourceExtensions;
	private List<String> getSourceExtensions() {
		if (_sourceExtensions == null) {
			_sourceExtensions = WebRenders.normalizeExtensions(Arrays.asList(Webs.getSourceExtensions(fContext)));
		}
		return _sourceExtensions;
	}

	public Template getTemplate(String templatePath) throws RepositoryException {
		return getTemplate(templatePath, null);
	}

	public Template getTemplate(String templatePath, String prefix) throws RepositoryException {
		if (!templatePath.startsWith("/")) {
			templatePath = Webs.DEFAULT_WEB_TEMPLATE_PATH + "/" + templatePath;
		}

		if (Strings.isEmpty(prefix)) {
			prefix = Webs.getRequest(fContext).getMethod();
		}
		if (prefix.endsWith(".")) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		prefix = prefix.toUpperCase();

		int p = templatePath.lastIndexOf('/');
		String parentPath = templatePath.substring(0, p + 1);
		String filename = templatePath.substring(p + 1);
		String[] scriptExtensions = Scripts.getScriptExtensions(fContext);
		for (String basename : new String[] { prefix + "." + filename, filename }) {
			for (String scriptExtension : scriptExtensions) {
				try {
					return new Template(
							Scripts.getJcrSession(fContext).getNode(parentPath + basename + "." + scriptExtension),
							scriptExtension);
				} catch (PathNotFoundException ignore) {
				} catch (AccessDeniedException ignore) {}
			}
		}
		return null;
	}

	public class ResolveResult {
		private final Node fNode;
		private final Template fTemplate;
		private final Exception fException;

		private ResolveResult(Node node) {
			fNode = node;
			fTemplate = null;
			fException = null;
			init();
		}

		private ResolveResult(Node node, Template template) {
			fNode = node;
			fTemplate = template;
			fException = null;
			init();
		}

		private ResolveResult(Exception exception) {
			fNode = null;
			fTemplate = null;
			fException = exception;
			init();
		}

		private void init() {
			try {
				if (exists() && isFile()) {
					WorkspaceScriptContext ctx = Scripts.getWorkspaceScriptContext(fContext);
					HttpServletRequest request = Webs.getRequest(fContext);
					if (Webs.isPreviewRequest(request)) {
						@SuppressWarnings("unchecked")
						Map<String, Object> payload = (Map<String, Object>) JSON.get(ctx).parse(Webs.getRequest(fContext).getReader());
						ExpressionContext exp = ExpressionContext.create().setVariable("payload", payload);
						String content = exp.defaultIfEmpty("payload.content", "").toString();
						String encoding =  Strings.defaultIfEmpty(request.getCharacterEncoding(), StandardCharsets.UTF_8.name());
						JCRs.write(fNode, new ByteArrayInputStream(content.getBytes(encoding)));
						JCRs.setProperty(fNode, "jcr:lastModified", new java.util.Date());
						// Don't save because it is transient change for preview.
					}
				}
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}

		public Node getNode() {
			return fNode;
		}

		public boolean exists() {
			return (fNode != null);
		}

		public boolean isFile() throws RepositoryException {
			return fNode.isNodeType(NodeType.NT_FILE);
		}

		public boolean isFolder() throws RepositoryException {
			return fNode.isNodeType(NodeType.NT_FOLDER);
		}

		public boolean hasTemplate() {
			return (fTemplate != null);
		}

		public Template getTemplate() {
			return fTemplate;
		}

		String _scriptExtension;
		public String getScriptExtension() throws RepositoryException {
			if (_scriptExtension == null) {
				_scriptExtension = "";
				if (fNode != null) {
					for (String scriptExtension : Scripts.getScriptExtensions(fContext)) {
						if (fNode.getName().endsWith("." + scriptExtension)) {
							_scriptExtension = scriptExtension;
							break;
						}
					}
				}
			}
			return _scriptExtension;
		}

		public boolean isScriptable() throws RepositoryException {
			return !Strings.isEmpty(getScriptExtension());
		}

		public InputStream getContentAsStream() throws RepositoryException, IOException {
			return JCRs.getContentAsStream(fNode);
		}

		public Reader getContentAsReader() throws RepositoryException, IOException {
			return JCRs.getContentAsReader(fNode);
		}

		public long getContentLength() throws RepositoryException, IOException {
			try (org.mintjams.jcr.Binary value = (org.mintjams.jcr.Binary) fNode.getNode(Node.JCR_CONTENT)
					.getProperty(Property.JCR_DATA).getBinary()) {
				return value.getSize();
			}
		}

		public String getPath() throws RepositoryException {
			return fNode.getPath();
		}

		public String getMimeType() throws RepositoryException {
			return JCRs.getMimeType(fNode);
		}

		public String getEncoding() throws RepositoryException {
			return JCRs.getEncoding(fNode);
		}

		public void setEncodingTo(HttpServletResponse response) throws RepositoryException {
			String encoding = getEncoding();
			if (Strings.isNotEmpty(encoding)) {
				response.setCharacterEncoding(encoding);
			}
		}

		public java.util.Date getLastModified() throws RepositoryException {
			try {
				return fNode.getNode(Node.JCR_CONTENT).getProperty(Property.JCR_LAST_MODIFIED).getDate().getTime();
			} catch (PathNotFoundException ignore) {}
			return new java.util.Date();
		}

		public boolean isNotFound() {
			return (fException instanceof PathNotFoundException);
		}

		public boolean isAccessDenied() {
			return (fException instanceof AccessDeniedException);
		}
	}

	public static class Template {
		private final Node fNode;
		private final String fScriptExtension;

		private Template(Node node, String scriptExtension) {
			fNode = node;
			fScriptExtension = scriptExtension;
		}

		public String getScriptExtension() {
			return fScriptExtension;
		}

		public Reader getContentAsReader() throws RepositoryException, IOException {
			return JCRs.getContentAsReader(fNode);
		}

		public String getPath() throws RepositoryException {
			return fNode.getPath();
		}

		public java.util.Date getLastModified() throws RepositoryException {
			try {
				return fNode.getNode(Node.JCR_CONTENT).getProperty(Property.JCR_LAST_MODIFIED).getDate().getTime();
			} catch (PathNotFoundException ignore) {}
			return new java.util.Date();
		}
	}

}

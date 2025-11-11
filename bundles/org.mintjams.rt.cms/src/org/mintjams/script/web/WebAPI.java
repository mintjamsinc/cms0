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

package org.mintjams.script.web;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.web.Webs;
import org.mintjams.script.ScriptingContext;
import org.mintjams.script.resource.Resource;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class WebAPI implements Closeable {

	private WorkspaceScriptContext fContext;
	private Map<String, List<FileItem>> fMultipart;
	private final Closer fCloser = Closer.create();

	public WebAPI(WorkspaceScriptContext context) throws IOException {
		fContext = context;
		if (ServletFileUpload.isMultipartContent(Webs.getRequest(fContext))) {
			DiskFileItemFactory factory = new DiskFileItemFactory();
			File tempDir = (File) Webs.getRequest(fContext).getServletContext().getAttribute(ServletContext.TEMPDIR);
			factory.setRepository(tempDir);
			ServletFileUpload upload = new ServletFileUpload(factory);
			try {
				fMultipart = upload.parseParameterMap(Webs.getRequest(fContext));
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IOException.class);
			}
			for (List<FileItem> l : fMultipart.values()) {
				for (FileItem e : l) {
					fCloser.add(new Closeable() {
						@Override
						public void close() throws IOException {
							e.delete();
						}
					});
				}
			}
		}
	}

	public static WebAPI get(ScriptingContext context) {
		return (WebAPI) context.getAttribute(WebAPI.class.getSimpleName());
	}

	public static ScriptingContext getScriptingContext(ServletRequest request) {
		return (ScriptingContext) request.getAttribute(WorkspaceScriptContext.class.getName());
	}

	private Reader getContentAsReader(String src, String encoding) throws IOException {
		if (src.startsWith("http://") || src.startsWith("https://")) {
			WebResource resource = fetch(src);
			if (Strings.isEmpty(encoding)) {
				encoding = Strings.defaultIfEmpty(resource.getContentEncoding(), StandardCharsets.UTF_8.name());
			}
			return new InputStreamReader(resource.getContentAsStream(), encoding);
		}

		if (src.startsWith("jcr://")) {
			src = src.substring("jcr://".length());
		}

		try {
			Resource resource = ((Resource) fContext.getAttribute("resource")).getResource(src);
			if (Strings.isEmpty(encoding)) {
				return resource.getContentAsReader();
			}
			return new InputStreamReader(resource.getContentAsStream(), encoding);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	public void importContent(String path, String encoding) throws IOException {
		try (Reader in = getContentAsReader(path, encoding)) {
			IOs.copy(in, fContext.getWriter());
		}
	}

	public void importContent(String path) throws IOException {
		try (Reader in = getContentAsReader(path, null)) {
			IOs.copy(in, fContext.getWriter());
		}
	}

	public void include(String path) throws ServletException, IOException {
		HttpServletRequest request = Webs.getRequest(fContext);
		HttpServletResponse response = Webs.getResponse(fContext);
		if (path.startsWith("/")) {
			path = Webs.getWorkspacePath(fContext) + path;
		}
		request.getRequestDispatcher(path).include(request, response);
	}

	public void forward(String path) throws ServletException, IOException {
		HttpServletRequest request = Webs.getRequest(fContext);
		HttpServletResponse response = Webs.getResponse(fContext);
		if (path.startsWith("/")) {
			path = Webs.getWorkspacePath(fContext) + path;
		}
		request.getRequestDispatcher(path).forward(request, response);
	}

	public String encodeURIComponent(String value) throws IOException {
		return Webs.encode(value);
	}

	public String encodeURI(String value) throws IOException {
		return Webs.encodePath(value);
	}

	public String decodeURIComponent(String value) throws IOException {
		return Webs.decode(value);
	}

	public WebParameter getParameter(String name) {
		return new WebParameter(Webs.getRequest(fContext), name, (fMultipart != null) ? fMultipart.get(name) : null);
	}

	public WebResource fetch(String uri) throws IOException {
		try {
			// Create HttpClient with timeouts
			HttpClient httpClient = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(3))
					.build();

			// Create HttpRequest
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(uri))
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();

			// Send request and get response
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

			// Check status code
			if (response.statusCode() == 200) {
				// Get content type from headers
				String contentType = response.headers().firstValue("Content-Type").orElse(null);
				String contentEncoding = response.headers().firstValue("Content-Encoding").orElse(null);

				try {
					return fCloser.register(new WebResource(response.body()))
							.setURI(uri)
							.setContentType(contentType)
							.setContentEncoding(contentEncoding);
				} catch (URISyntaxException ex) {
					throw Cause.create(ex).wrap(IOException.class);
				}
			}

			throw new IOException(uri + ": HTTP " + response.statusCode());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Request interrupted: " + uri, ex);
		}
	}

	@Override
	public void close() throws IOException {
		fCloser.close();
	}

}

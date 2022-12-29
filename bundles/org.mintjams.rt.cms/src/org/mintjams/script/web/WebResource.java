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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

import org.mintjams.jcr.util.FileCache;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.adapter.Adaptable;

public class WebResource implements Closeable, Adaptable {

	private final FileCache fCache;

	protected WebResource(InputStream in) throws IOException {
		fCache = FileCache.create(in, CmsService.getTemporaryDirectoryPath());
	}

	private URI fURI;
	public URI getURI() {
		return fURI;
	}
	protected WebResource setURI(String uri) throws URISyntaxException {
		fURI = new URI(uri);
		return this;
	}

	private String fContentType;
	public String getContentType() {
		return fContentType;
	}
	protected WebResource setContentType(String contentType) {
		fContentType = contentType;
		return this;
	}

	private String fContentEncoding;
	public String getContentEncoding() {
		return fContentEncoding;
	}
	protected WebResource setContentEncoding(String contentEncoding) {
		fContentEncoding = contentEncoding;
		return this;
	}

	public long getContentLength() throws IOException {
		return fCache.getSize();
	}

	public InputStream getContentAsStream() throws IOException {
		return fCache.getInputStream();
	}

	public String getContent() throws IOException {
		return fCache.toString(getContentEncoding());
	}

	@Override
	public void close() throws IOException {
		fCache.close();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(Path.class)) {
			return fCache.adaptTo(adapterType);
		}

		return null;
	}

}

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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.fileupload.FileItem;
import org.mintjams.tools.io.IOs;

public class WebFile {

	private final FileItem fFileItem;
	private final WebParameter fWebParameter;

	protected WebFile(FileItem file, WebParameter webParameter) {
		fFileItem = file;
		fWebParameter = webParameter;
	}

	public String getName() throws IOException {
		return fFileItem.getName();
	}

	public String getContentType() throws IOException {
		return fFileItem.getContentType();
	}

	public long getLength() throws IOException {
		return fFileItem.getSize();
	}

	public String getString() throws IOException {
		return getString(fWebParameter.getRequest().getCharacterEncoding());
	}

	public String getString(String encoding) throws IOException {
		return fFileItem.getString(encoding);
	}

	public byte[] getByteArray() throws IOException {
		try (InputStream in = fFileItem.getInputStream()) {
			return IOs.toByteArray(in);
		}
	}

	public InputStream getInputStream() throws IOException {
		return new BufferedInputStream(fFileItem.getInputStream());
	}

}

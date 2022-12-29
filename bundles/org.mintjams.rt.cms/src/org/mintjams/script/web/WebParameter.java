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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.fileupload.FileItem;
import org.mintjams.tools.lang.Strings;

public class WebParameter {

	private final HttpServletRequest fRequest;
	private final String fName;
	private final List<FileItem> fFileItems;

	protected WebParameter(HttpServletRequest request, String name, List<FileItem> fileItems) {
		fRequest = request;
		fName = name;
		fFileItems = fileItems;
	}

	protected HttpServletRequest getRequest() {
		return fRequest;
	}

	public String getName() {
		return fName;
	}

	public boolean isSpecified() throws IOException {
		return (getString() != null || getFile() != null);
	}

	public boolean isNotSpecified() throws IOException {
		return !isSpecified();
	}

	public boolean isEmpty() throws IOException {
		return Strings.isEmpty(getString());
	}

	public boolean isNotEmpty() throws IOException {
		return !isEmpty();
	}

	public boolean isBlank() throws IOException {
		return Strings.isBlank(getString());
	}

	public boolean isNotBlank() throws IOException {
		return !isBlank();
	}

	public String defaultString() throws IOException {
		return Strings.defaultString(getString());
	}

	public String defaultString(String defaultValue) throws IOException {
		return Strings.defaultString(getString(), defaultValue);
	}

	public String defaultIfEmpty(String defaultValue) throws IOException {
		return Strings.defaultIfEmpty(getString(), defaultValue);
	}

	public String defaultIfBlank(String defaultValue) throws IOException {
		return Strings.defaultIfBlank(getString(), defaultValue);
	}

	public String getString() throws IOException {
		if (fFileItems == null) {
			return fRequest.getParameter(fName);
		}

		if (!fFileItems.get(0).isFormField()) {
			return null;
		}

		String encoding = Strings.defaultIfEmpty(fRequest.getCharacterEncoding(), StandardCharsets.UTF_8.name());
		return fFileItems.get(0).getString(encoding);
	}

	public String[] getStringArray() throws IOException {
		if (fFileItems == null) {
			return fRequest.getParameterValues(fName);
		}

		if (!fFileItems.get(0).isFormField()) {
			return null;
		}

		String encoding = Strings.defaultIfEmpty(fRequest.getCharacterEncoding(), StandardCharsets.UTF_8.name());
		List<String> l = new ArrayList<>();
		for (FileItem e : fFileItems) {
			l.add(e.getString(encoding));
		}
		return l.toArray(String[]::new);
	}

	public WebFile getFile() {
		if (fFileItems == null) {
			return null;
		}

		if (fFileItems.get(0).isFormField()) {
			return null;
		}

		return new WebFile(fFileItems.get(0), this);
	}

	public WebFile[] getFileArray() {
		if (fFileItems == null) {
			return null;
		}

		if (fFileItems.get(0).isFormField()) {
			return null;
		}

		WebFile[] a = new WebFile[fFileItems.size()];
		for (int i = 0; i < a.length; i++) {
			a[i] = new WebFile(fFileItems.get(i), this);
		}
		return a;
	}

}

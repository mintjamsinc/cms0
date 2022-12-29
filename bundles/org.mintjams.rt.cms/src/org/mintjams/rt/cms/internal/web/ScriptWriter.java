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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

import org.apache.commons.io.output.NullOutputStream;
import org.mintjams.tools.util.ActionContext;

public class ScriptWriter extends PrintWriter {

	private final ActionContext fContext;
	private PrintWriter fWriter;

	public ScriptWriter(ActionContext context) {
		super(NullOutputStream.NULL_OUTPUT_STREAM);
		fContext = context;
	}

	private PrintWriter getWriter() {
		if (fWriter == null) {
			try {
				fWriter = Webs.getResponse(fContext).getWriter();
			} catch (IOException ex) {
				throw (IllegalStateException) new IllegalStateException(ex.getMessage()).initCause(ex);
			}
		}
		return fWriter;
	}

	@Override
	public void flush() {
		getWriter().flush();
	}

	@Override
	public void close() {
		if (fWriter != null) {
			fWriter.flush();
		}
		super.close();
	}

	@Override
	public boolean checkError() {
		return getWriter().checkError();
	}

	@Override
	public void write(int c) {
		getWriter().write(c);
	}

	@Override
	public void write(char[] buf, int off, int len) {
		getWriter().write(buf, off, len);
	}

	@Override
	public void write(char[] buf) {
		getWriter().write(buf);
	}

	@Override
	public void write(String s, int off, int len) {
		getWriter().write(s, off, len);
	}

	@Override
	public void write(String s) {
		getWriter().write(s);
	}

	@Override
	public void print(boolean b) {
		getWriter().print(b);
	}

	@Override
	public void print(char c) {
		getWriter().print(c);
	}

	@Override
	public void print(int i) {
		getWriter().print(i);
	}

	@Override
	public void print(long l) {
		getWriter().print(l);
	}

	@Override
	public void print(float f) {
		getWriter().print(f);
	}

	@Override
	public void print(double d) {
		getWriter().print(d);
	}

	@Override
	public void print(char[] s) {
		getWriter().print(s);
	}

	@Override
	public void print(String s) {
		getWriter().print(s);
	}

	@Override
	public void print(Object obj) {
		getWriter().print(obj);
	}

	@Override
	public void println() {
		getWriter().println();
	}

	@Override
	public void println(boolean x) {
		getWriter().println(x);
	}

	@Override
	public void println(char x) {
		getWriter().println(x);
	}

	@Override
	public void println(int x) {
		getWriter().println(x);
	}

	@Override
	public void println(long x) {
		getWriter().println(x);
	}

	@Override
	public void println(float x) {
		getWriter().println(x);
	}

	@Override
	public void println(double x) {
		getWriter().println(x);
	}

	@Override
	public void println(char[] x) {
		getWriter().println(x);
	}

	@Override
	public void println(String x) {
		getWriter().println(x);
	}

	@Override
	public void println(Object x) {
		getWriter().println(x);
	}

	@Override
	public PrintWriter printf(String format, Object... args) {
		return getWriter().printf(format, args);
	}

	@Override
	public PrintWriter printf(Locale l, String format, Object... args) {
		return getWriter().printf(l, format, args);
	}

	@Override
	public PrintWriter format(String format, Object... args) {
		return getWriter().format(format, args);
	}

	@Override
	public PrintWriter format(Locale l, String format, Object... args) {
		return getWriter().format(l, format, args);
	}

	@Override
	public PrintWriter append(CharSequence csq) {
		return getWriter().append(csq);
	}

	@Override
	public PrintWriter append(CharSequence csq, int start, int end) {
		return getWriter().append(csq, start, end);
	}

	@Override
	public PrintWriter append(char c) {
		return getWriter().append(c);
	}
}

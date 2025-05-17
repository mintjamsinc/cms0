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

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;

public class LoggerAPI {

	private WorkspaceScriptContext fContext;

	public LoggerAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static LoggerAPI get(ScriptingContext context) {
		return (LoggerAPI) context.getAttribute(LoggerAPI.class.getSimpleName());
	}

	private Class<?> getSourceClass() {
		StackTraceElement[] l = Thread.currentThread().getStackTrace();
		for (int i = 1; i < l.length; i++) {
			if (!l[i].getClassName().equals(LoggerAPI.class.getName()) && !l[i].getClassName().startsWith(Thread.class.getName())) {
				return l[i].getClass();
			}
		}
		return LoggerAPI.class;
	}

	public void debug(String message) {
		CmsService.getLogger(getSourceClass()).debug(message);
	}

	public void debug(String message, Throwable ex) {
		CmsService.getLogger(getSourceClass()).debug(message, ex);
	}

	public void debug(Throwable ex) {
		debug(ex.getMessage(), ex);
	}

	public void info(String message) {
		CmsService.getLogger(getSourceClass()).info(message);
	}

	public void info(String message, Throwable ex) {
		CmsService.getLogger(getSourceClass()).info(message, ex);
	}

	public void info(Throwable ex) {
		info(ex.getMessage(), ex);
	}

	public void warning(String message) {
		warn(message);
	}

	public void warning(String message, Throwable ex) {
		warn(message, ex);
	}

	public void warning(Throwable ex) {
		warn(ex);
	}

	public void warn(String message) {
		CmsService.getLogger(getSourceClass()).warn(message);
	}

	public void warn(String message, Throwable ex) {
		CmsService.getLogger(getSourceClass()).warn(message, ex);
	}

	public void warn(Throwable ex) {
		warn(ex.getMessage(), ex);
	}

	public void error(String message) {
		CmsService.getLogger(getSourceClass()).error(message);
	}

	public void error(String message, Throwable ex) {
		CmsService.getLogger(getSourceClass()).error(message, ex);
	}

	public void error(Throwable ex) {
		error(ex.getMessage(), ex);
	}

}

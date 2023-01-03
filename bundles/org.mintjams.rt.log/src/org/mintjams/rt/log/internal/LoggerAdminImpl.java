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

package org.mintjams.rt.log.internal;

import java.util.HashMap;
import java.util.Map;

import org.mintjams.tools.lang.Strings;
import org.osgi.framework.Bundle;
import org.osgi.service.log.admin.LoggerAdmin;
import org.osgi.service.log.admin.LoggerContext;

public class LoggerAdminImpl implements LoggerAdmin {

	private final LoggerContextImpl fRootLoggerContext = new LoggerContextImpl();
	private final Map<String, LoggerContextImpl> fLoggerContexts = new HashMap<>();

	@Override
	public LoggerContext getLoggerContext(String name) {
		if (Strings.isEmpty(name)) {
			return fRootLoggerContext;
		}

		synchronized (fLoggerContexts) {
			LoggerContextImpl context = fLoggerContexts.get(name);
			if (context == null) {
				context = new LoggerContextImpl(name, fRootLoggerContext);
				fLoggerContexts.put(name, context);
			}
			return context;
		}
	}

	public LoggerContext getLoggerContext(Bundle bundle) {
		LoggerContext context = getLoggerContext(String.join("|",
				new String[] { bundle.getSymbolicName(), bundle.getVersion().toString(), bundle.getLocation() }));
		if (!context.isEmpty()) {
			return context;
		}

		context = getLoggerContext(
				String.join("|", new String[] { bundle.getSymbolicName(), bundle.getVersion().toString() }));
		if (!context.isEmpty()) {
			return context;
		}

		return getLoggerContext(bundle.getSymbolicName());
	}

}

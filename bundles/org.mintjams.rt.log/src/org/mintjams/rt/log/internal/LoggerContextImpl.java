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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mintjams.tools.lang.Strings;
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.Logger;
import org.osgi.service.log.admin.LoggerContext;

public class LoggerContextImpl implements LoggerContext {

	private final String fName;
	private final LoggerContextImpl fRootLoggerContext;
	private final Map<String, LogLevel> fLogLevels = new HashMap<>();

	public LoggerContextImpl() {
		this(null, null);
	}

	public LoggerContextImpl(String name, LoggerContextImpl rootLoggerContext) {
		fName = name;
		fRootLoggerContext = rootLoggerContext;
	}

	@Override
	public void clear() {
		synchronized (fLogLevels) {
			fLogLevels.clear();
		}
	}

	@Override
	public LogLevel getEffectiveLogLevel(String name) {
		synchronized (fLogLevels) {
			if (!Strings.isEmpty(name) && !name.equals(Logger.ROOT_LOGGER_NAME)) {
				List<String> names = new ArrayList<>(Arrays.asList(name.split("\\.")));
				while (!names.isEmpty()) {
					LogLevel level = fLogLevels.get(String.join(".", names));
					if (level != null) {
						return level;
					}

					names.remove(names.size() - 1);
				}
			}

			LogLevel rootLevel = fLogLevels.get(Logger.ROOT_LOGGER_NAME);
			if (rootLevel != null) {
				return rootLevel;
			}

			if (fRootLoggerContext != null) {
				return fRootLoggerContext.getEffectiveLogLevel(name);
			}

			String defaultLevel = null;
			try {
				defaultLevel = Activator.getDefault().getBundleContext()
						.getProperty(LoggerContext.LOGGER_CONTEXT_DEFAULT_LOGLEVEL);
			} catch (Throwable ignore) {}
			if (defaultLevel != null) {
				for (LogLevel level : LogLevel.values()) {
					if (level.name().equalsIgnoreCase(defaultLevel)) {
						return level;
					}
				}
			}
			return LogLevel.WARN;
		}
	}

	@Override
	public Map<String, LogLevel> getLogLevels() {
		synchronized (fLogLevels) {
			return new HashMap<>(fLogLevels);
		}
	}

	@Override
	public String getName() {
		return fName;
	}

	@Override
	public boolean isEmpty() {
		synchronized (fLogLevels) {
			return fLogLevels.isEmpty();
		}
	}

	@Override
	public void setLogLevels(Map<String, LogLevel> logLevels) {
		synchronized (fLogLevels) {
			fLogLevels.clear();
			fLogLevels.putAll(logLevels);
		}
	}

}

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

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.FormatterLogger;
import org.osgi.service.log.LogService;
import org.osgi.service.log.Logger;

public class LogServiceImpl implements LogService {

	private final Bundle fBundle;
	private final Map<LoggerKey, Logger> fLoggers = new HashMap<>();

	public LogServiceImpl(Bundle bundle) {
		fBundle = bundle;
	}

	@Override
	public Logger getLogger(String name) {
		return getLogger(fBundle, name, Logger.class);
	}

	@Override
	public Logger getLogger(Class<?> clazz) {
		return getLogger(fBundle, clazz.getName(), Logger.class);
	}

	@Override
	public <L extends Logger> L getLogger(String name, Class<L> loggerType) {
		return getLogger(fBundle, name, loggerType);
	}

	@Override
	public <L extends Logger> L getLogger(Class<?> clazz, Class<L> loggerType) {
		return getLogger(fBundle, clazz.getName(), loggerType);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <L extends Logger> L getLogger(Bundle bundle, String name, Class<L> loggerType) {
		LoggerKey key = new LoggerKey(bundle, name, loggerType);

		synchronized (fLoggers) {
			Logger logger = fLoggers.get(key);
			if (logger == null) {
				if (loggerType.equals(FormatterLogger.class)) {
					logger = new FormatterLoggerImpl(name, this);
				} else {
					logger = new LoggerImpl(name, this);
				}

				fLoggers.put(key, logger);
			}
			return (L) logger;
		}
	}

	@Override
	public void log(int level, String message) {
		log(null, level, message, null);
	}

	@Override
	public void log(int level, String message, Throwable exception) {
		log(null, level, message, exception);
	}

	@Override
	public void log(ServiceReference<?> sr, int level, String message) {
		log(sr, level, message, null);
	}

	@SuppressWarnings("deprecation")
	@Override
	public void log(ServiceReference<?> sr, int level, String message, Throwable exception) {
		Logger logger = getLogger(fBundle, "LogService.".concat(fBundle.getSymbolicName()), Logger.class);
		switch (level) {
		case LogService.LOG_ERROR:
			logger.error(message, sr, exception);
			break;
		case LogService.LOG_WARNING:
			logger.warn(message, sr, exception);
			break;
		case LogService.LOG_INFO:
			logger.info(message, sr, exception);
			break;
		case LogService.LOG_DEBUG:
			logger.debug(message, sr, exception);
			break;
		default:
			logger.trace(message, sr, exception);
		}
	}

	public Bundle getBundle() {
		return fBundle;
	}

	private static class LoggerKey {
		private final Bundle fBundle;
		private final String fName;
		private final Class<? extends Logger> fLoggerType;

		public LoggerKey(Bundle bundle, String name, Class<? extends Logger> loggerType) {
			this.fBundle = bundle;
			this.fName = name;
			this.fLoggerType = loggerType;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == null) {
				return false;
			}

			if (this == obj) {
				return true;
			}

			if (!getClass().equals(obj.getClass())) {
				return false;
			}

			LoggerKey other = (LoggerKey) obj;

			if (fBundle == null) {
				if (other.fBundle != null) {
					return false;
				}
			} else {
				if (fBundle.getBundleId() != other.fBundle.getBundleId()) {
					return false;
				}
			}

			if (fName == null) {
				if (other.fName != null) {
					return false;
				}
			} else {
				if (!fName.equals(other.fName)) {
					return false;
				}
			}

			if (fLoggerType == null) {
				if (other.fLoggerType != null) {
					return false;
				}
			} else {
				if (!fLoggerType.equals(other.fLoggerType)) {
					return false;
				}
			}

			return true;
		}
	}

}

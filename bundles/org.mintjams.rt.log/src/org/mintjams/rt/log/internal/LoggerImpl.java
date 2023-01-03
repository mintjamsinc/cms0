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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.mintjams.tools.lang.Strings;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerConsumer;

public class LoggerImpl implements Logger {

	protected final String fName;
	protected final LogServiceImpl fLogService;

	public LoggerImpl(String name, LogServiceImpl logService) {
		fName = name;
		fLogService = logService;
	}

	@Override
	public String getName() {
		return fName;
	}

	@Override
	public boolean isErrorEnabled() {
		return isEnabled(LogLevel.ERROR);
	}

	@Override
	public boolean isWarnEnabled() {
		return isEnabled(LogLevel.WARN);
	}

	@Override
	public boolean isInfoEnabled() {
		return isEnabled(LogLevel.INFO);
	}

	@Override
	public boolean isDebugEnabled() {
		return isEnabled(LogLevel.DEBUG);
	}

	@Override
	public boolean isTraceEnabled() {
		return isEnabled(LogLevel.TRACE);
	}

	@Override
	public void audit(String message) {
		log(LogLevel.AUDIT, message, (Object[]) null);
	}

	@Override
	public void audit(String format, Object arg) {
		log(LogLevel.AUDIT, format, arg);
	}

	@Override
	public void audit(String format, Object... arguments) {
		log(LogLevel.AUDIT, format, arguments);
	}

	@Override
	public void audit(String format, Object arg1, Object arg2) {
		log(LogLevel.AUDIT, format, arg1, arg2);
	}

	@Override
	public void debug(String message) {
		log(LogLevel.DEBUG, message, (Object[]) null);
	}

	@Override
	public <E extends Exception> void debug(LoggerConsumer<E> consumer) throws E {
		if (isDebugEnabled()) {
			consumer.accept(this);
		}
	}

	@Override
	public void debug(String format, Object arg) {
		log(LogLevel.DEBUG, format, arg);
	}

	@Override
	public void debug(String format, Object... arguments) {
		log(LogLevel.DEBUG, format, arguments);
	}

	@Override
	public void debug(String format, Object arg1, Object arg2) {
		log(LogLevel.DEBUG, format, arg1, arg2);
	}

	@Override
	public void error(String message) {
		log(LogLevel.ERROR, message, (Object[]) null);
	}

	@Override
	public <E extends Exception> void error(LoggerConsumer<E> consumer) throws E {
		if (isErrorEnabled()) {
			consumer.accept(this);
		}
	}

	@Override
	public void error(String format, Object arg) {
		log(LogLevel.ERROR, format, arg);
	}

	@Override
	public void error(String format, Object... arguments) {
		log(LogLevel.ERROR, format, arguments);
	}

	@Override
	public void error(String format, Object arg1, Object arg2) {
		log(LogLevel.ERROR, format, arg1, arg2);
	}

	@Override
	public void info(String message) {
		log(LogLevel.INFO, message, (Object[]) null);
	}

	@Override
	public <E extends Exception> void info(LoggerConsumer<E> consumer) throws E {
		if (isInfoEnabled()) {
			consumer.accept(this);
		}
	}

	@Override
	public void info(String format, Object arg) {
		log(LogLevel.INFO, format, arg);
	}

	@Override
	public void info(String format, Object... arguments) {
		log(LogLevel.INFO, format, arguments);
	}

	@Override
	public void info(String format, Object arg1, Object arg2) {
		log(LogLevel.INFO, format, arg1, arg2);
	}

	@Override
	public void trace(String message) {
		log(LogLevel.TRACE, message, (Object[]) null);
	}

	@Override
	public <E extends Exception> void trace(LoggerConsumer<E> consumer) throws E {
		if (isTraceEnabled()) {
			consumer.accept(this);
		}
	}

	@Override
	public void trace(String format, Object arg) {
		log(LogLevel.TRACE, format, arg);
	}

	@Override
	public void trace(String format, Object... arguments) {
		log(LogLevel.TRACE, format, arguments);
	}

	@Override
	public void trace(String format, Object arg1, Object arg2) {
		log(LogLevel.TRACE, format, arg1, arg2);
	}

	@Override
	public void warn(String message) {
		log(LogLevel.WARN, message, (Object[]) null);
	}

	@Override
	public <E extends Exception> void warn(LoggerConsumer<E> consumer) throws E {
		if (isWarnEnabled()) {
			consumer.accept(this);
		}
	}

	@Override
	public void warn(String format, Object arg) {
		log(LogLevel.WARN, format, arg);
	}

	@Override
	public void warn(String format, Object... arguments) {
		log(LogLevel.WARN, format, arguments);
	}

	@Override
	public void warn(String format, Object arg1, Object arg2) {
		log(LogLevel.WARN, format, arg1, arg2);
	}

	private boolean isEnabled(LogLevel logLevel) {
		return Activator.getLoggerAdmin().getLoggerContext(fLogService.getBundle()).getEffectiveLogLevel(fName)
				.implies(logLevel);
	}

	private void log(LogLevel logLevel, String message, Object... arguments) {
		if (!isEnabled(logLevel)) {
			return;
		}

		message = Strings.defaultString(message);

		if (arguments == null) {
			Activator.getLogQueue().add(new LogEntryImpl(fName)
					.setLogLevel(logLevel)
					.setMessage(message)
					.setBundle(fLogService.getBundle()));
			return;
		}

		Throwable exception = null;
		ServiceReference<?> serviceReference = null;
		List<Object> args = new ArrayList<>(Arrays.asList(arguments));
		Object last = null;
		if (args.size() > 0) {
			Object o = args.get(args.size() - 1);
			if (o instanceof Throwable) {
				exception = (Throwable) o;
				last = args.remove(args.size() - 1);
			} else if (o instanceof ServiceReference) {
				serviceReference = (ServiceReference<?>) o;
				last = args.remove(args.size() - 1);
			}
		}
		if (args.size() > 0) {
			Object o = args.get(args.size() - 1);
			if (o instanceof Throwable) {
				if (last != null) {
					if (!(last instanceof Throwable)) {
						exception = (Throwable) o;
						args.remove(args.size() - 1);
					}
				} else {
					exception = (Throwable) o;
					args.remove(args.size() - 1);
				}
			} else if (o instanceof ServiceReference) {
				if (last != null) {
					if (!(last instanceof ServiceReference)) {
						serviceReference = (ServiceReference<?>) o;
						args.remove(args.size() - 1);
					}
				} else {
					serviceReference = (ServiceReference<?>) o;
					args.remove(args.size() - 1);
				}
			}
		}
		arguments = args.toArray();

		Activator.getLogQueue().add(new LogEntryImpl(fName)
				.setLogLevel(logLevel)
				.setMessage(formatMessage(message, arguments))
				.setException(exception)
				.setServiceReference(serviceReference)
				.setBundle(fLogService.getBundle()));
	}

	protected String formatMessage(String message, Object... arguments) {
		try {
			int parameterIndex = 0;
			StringBuilder buf = new StringBuilder();
			char[] chars = message.toCharArray();
			for (int i = 0; i < chars.length; i++) {
				char c = chars[i];

				if (c == '\\') {
					i++;
					buf.append(chars[i]);
					continue;
				}

				if (c == '{') {
					StringBuilder s = new StringBuilder();
					for (i++;; i++) {
						c = chars[i];
						if (c == '\\') {
							i++;
							s.append(chars[i]);
							continue;
						}
						if (c == '}') {
							break;
						}
						s.append(c);
					}

					if (s.toString().trim().isEmpty()) {
						buf.append(getValueAsString(arguments[parameterIndex++]));
					} else {
						buf.append("{").append(s).append("}");
					}
					continue;
				}

				buf.append(c);
			}
			return buf.toString();
		} catch (Throwable ignore) {}
		return message;
	}

	private String getValueAsString(Object value) {
		if (value.getClass().isArray()) {
			Object[] values = (Object[]) value;
			StringBuilder buf = new StringBuilder();
			for (int i = 0; i < values.length; i++) {
				if (i > 0) {
					buf.append(", ");
				}
				buf.append(getValueAsString(values[i]));
			}
			return buf.toString();
		}

		if (value instanceof BigDecimal) {
			return ((BigDecimal) value).toPlainString();
		}

		return value.toString();
	}

}

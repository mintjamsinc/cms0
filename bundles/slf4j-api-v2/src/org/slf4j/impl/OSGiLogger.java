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

package org.slf4j.impl;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.osgi.service.log.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.internal.Activator;

/**
 * An SLF4J v2 {@link org.slf4j.Logger} backed by an
 * {@link org.osgi.service.log.Logger}.
 *
 * <p>{@code AbstractLogger} takes care of:
 * <ul>
 *   <li>All 60+ overloads of trace/debug/info/warn/error</li>
 *   <li>The fluent {@code atDebug().addArgument(…).log(…)} API</li>
 *   <li>Marker propagation</li>
 * </ul>
 * Subclasses only implement level checks and one normalized dispatch method.
 */
public class OSGiLogger extends AbstractLogger {

	private static final long serialVersionUID = 1L;

	/**
	 * @param osgiLogger a non-null OSGi R7+ Logger obtained from
	 *                   {@link org.osgi.service.log.LoggerFactory}
	 */
	public OSGiLogger(String name) {
		if (name == null) {
			throw new IllegalArgumentException("name must not be null");
		}
		this.name = name;
	}

	private Logger getLogger() {
		Logger logger = Activator.getLogger(name);
		if (logger == null) {
			throw new IllegalStateException("OSGi LoggerFactory returned null for logger name: " + name);
		}
		return logger;
	}

	// ------------------------------------------------------------------
	// Level checks – delegate directly to OSGi Logger
	// ------------------------------------------------------------------

	@Override
	public boolean isTraceEnabled() {
		try {
			return getLogger().isTraceEnabled();
		} catch (Throwable ignore) {
			return false;
		}
	}

	@Override
	public boolean isTraceEnabled(Marker marker) {
		return isTraceEnabled();
	}

	@Override
	public boolean isDebugEnabled() {
		try {
			return getLogger().isDebugEnabled();
		} catch (Throwable ignore) {
			return false;
		}
	}

	@Override
	public boolean isDebugEnabled(Marker marker) {
		return isDebugEnabled();
	}

	@Override
	public boolean isInfoEnabled() {
		try {
			return getLogger().isInfoEnabled();
		} catch (Throwable ignore) {
			return true;
		}
	}

	@Override
	public boolean isInfoEnabled(Marker marker) {
		return isInfoEnabled();
	}

	@Override
	public boolean isWarnEnabled() {
		try {
			return getLogger().isWarnEnabled();
		} catch (Throwable ignore) {
			return true;
		}
	}

	@Override
	public boolean isWarnEnabled(Marker marker) {
		return isWarnEnabled();
	}

	@Override
	public boolean isErrorEnabled() {
		try {
			return getLogger().isErrorEnabled();
		} catch (Throwable ignore) {
			return true;
		}
	}

	@Override
	public boolean isErrorEnabled(Marker marker) {
		return isErrorEnabled();
	}

	// ------------------------------------------------------------------
	// AbstractLogger SPI
	// ------------------------------------------------------------------

	@Override
	protected String getFullyQualifiedCallerName() {
		// Returning null lets SLF4J skip caller-location lookup.
		// OSGi LogService does not use this information.
		return null;
	}

	/**
	 * Single dispatch point for every log invocation.
	 *
	 * <p>{@code AbstractLogger} guarantees that this method is only called
	 * when the corresponding level is enabled, so no additional guard is
	 * needed here.
	 *
	 * @param level          SLF4J log level
	 * @param marker         may be {@code null}
	 * @param messagePattern SLF4J message pattern (with {@code {}} placeholders)
	 * @param arguments      may be {@code null}
	 * @param throwable      may be {@code null}
	 */
	@Override
	protected void handleNormalizedLoggingCall(
			Level level, Marker marker,
			String messagePattern, Object[] arguments, Throwable throwable) {

		// Format the message once – OSGi Logger does not support SLF4J's
		// "{}" placeholders natively.
		String formattedMessage = MessageFormatter.basicArrayFormat(messagePattern, arguments);

		try {
			switch (level) {
				case TRACE:
					if (throwable != null) {
						getLogger().trace("{}", formattedMessage, throwable);
					} else {
						getLogger().trace("{}", formattedMessage);
					}
					break;
				case DEBUG:
					if (throwable != null) {
						getLogger().debug("{}", formattedMessage, throwable);
					} else {
						getLogger().debug("{}", formattedMessage);
					}
					break;
				case INFO:
					if (throwable != null) {
						getLogger().info("{}", formattedMessage, throwable);
					} else {
						getLogger().info("{}", formattedMessage);
					}
					break;
				case WARN:
					if (throwable != null) {
						getLogger().warn("{}", formattedMessage, throwable);
					} else {
						getLogger().warn("{}", formattedMessage);
					}
					break;
				case ERROR:
					if (throwable != null) {
						getLogger().error("{}", formattedMessage, throwable);
					} else {
						getLogger().error("{}", formattedMessage);
					}
					break;
			}
		} catch (Throwable ex) {
			try (StringWriter log = new StringWriter()) {
				try (PrintWriter out = new PrintWriter(log)) {
					out.append(formattedMessage);
					if (throwable != null) {
						out.append(System.lineSeparator());
						throwable.printStackTrace(out);
					}
					out.flush();
				}
				PrintStream ps;
				if (level == Level.ERROR) {
					ps = System.err;
				} else {
					ps = System.out;
				}
				ps.println(log.toString());
			} catch (Throwable ignore) {}
		}
	}

}
